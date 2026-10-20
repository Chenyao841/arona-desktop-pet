package com.cy.qq;

import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;

/**
 * 群聊图片识别（P1 第一项）。
 *
 * <p>进度文档：「从 OneBot 消息里取 {@code image} 段的 url → 下载 → 缩到 ≤800px → {@code chatVision*}」。
 *
 * <p>为什么必须缩：原图往往两三 MB、4000px 起步，直接丢给视觉模型既慢又贵；
 * 缩到长边 800 之后，模型对「图里有什么」的判断几乎不变。
 *
 * <p>输出统一成 JPEG（{@code data:image/jpeg;base64,}）——{@link DeepSeekService#chatVision}
 * 里 MIME 是写死 jpeg 的，声明与实际一致才不会某些网关被拒。
 */
@Service
public class QqImageService {

    /**
     * ★ 关键修复：图片缩放用的是 java.awt（BufferedImage/Graphics2D）。
     * 桌面端为了截屏把 java.awt.headless 强制成了 false，但云端服务器没有 X11，
     * AWT 一初始化就抛 {@code java.awt.AWTError: Can't connect to X11 window server}（Error 而非 Exception），
     * 结果整条回复直接变兜底文案，日志里连 [视觉] 都不会出现。
     *
     * <p>headless=true 下 BufferedImage / Graphics2D / ImageIO 都是可用的，所以：
     * 只在「没有图形环境」（Linux/Unix 且没有 DISPLAY）时把这个属性钉成 true。
     * Windows/macOS 或带 DISPLAY 的机器不受影响，桌面截屏照旧。
     */
    static {
        String os = System.getProperty("os.name", "").toLowerCase();
        String display = System.getenv("DISPLAY");
        String wayland = System.getenv("WAYLAND_DISPLAY");
        boolean windowsOrMac = os.contains("win") || os.contains("mac");
        boolean hasDisplay = (display != null && !display.isEmpty()) || (wayland != null && !wayland.isEmpty());
        if (!windowsOrMac && !hasDisplay) {
            System.setProperty("java.awt.headless", "true");
            System.out.println("[QQ] 无图形环境（无 DISPLAY）→ 图片处理使用 headless 模式（不连 X11）");
        }
    }

    /** 单张图最大下载体积 */
    private static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;

    /** 让视觉模型只做「客观描述」，不做评价（评价交给后面的对话模型） */
    private static final String PROMPT =
            "这是 QQ 群里群友发来的一张图片。请客观、简短地描述你能看到的内容："
                    + "画面主体是什么（人物/动物/物品/截图/表情包）、主要颜色、有没有明显文字（照抄关键文字）。"
                    + "90 字以内，只讲看到的事实，不要评价、不要寒暄、不要猜测画面外的内容。"
                    + "即使不完全确定也要尽力描述你看到的部分；"
                    + "只有当整张图是纯色/全黑/完全空白时，才回答「看不清」。";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            // ★ 必须锁 HTTP/1.1：JDK HttpClient 默认会带 `Upgrade: h2c` 去试 HTTP/2，
            //   而 NapCat 的 HTTP 服务（3000 端口 / 图床）看到 Upgrade 头会把请求当成 WebSocket 握手，
            //   直接 401 —— 图片就永远下载不下来。这是本机拿假 NapCat 复现出来的坑。
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    @Autowired
    private QqConfig config;

    @Autowired
    private DeepSeekService deepSeekService;

    /**
     * 下载 → 缩放 → 视觉描述。
     *
     * @param url OneBot {@code image} 段里的 url（也接受 {@code base64://} 内联图，NapCat 有时这么给）
     * @return 中文描述；失败返回 null（调用方降级为「群友发了一张图」）
     */
    /** 下载 + 缩放后的图片（留底/重读用，不再重复下载） */
    public static final class Prepared {
        public final String base64;
        public final String mime;

        Prepared(String base64, String mime) {
            this.base64 = base64;
            this.mime = mime;
        }
    }

    /**
     * 只把图准备好（下载 → 缩放 → base64），不做视觉理解。
     * 这样同一张图可以先描述一次、留底，后面被追问时用不同提示词**重读**。
     */
    public Prepared prepare(String url) {
        if (url == null || url.isEmpty()) return null;
        long t0 = System.currentTimeMillis();

        // base64:// 内联图：不用下载，直接解码
        byte[] raw;
        if (url.startsWith("base64://")) {
            try {
                raw = Base64.getDecoder().decode(url.substring("base64://".length()));
                System.out.println("[QQ] 内联 base64 图片，" + raw.length / 1024 + "KB");
            } catch (Exception e) {
                System.out.println("[QQ] 内联 base64 解码失败: " + e.getMessage());
                return null;
            }
        } else {
            raw = download(url);
            if (raw == null) return null;
        }

        // 优先缩到 ≤800px 再送；缩放失败（AWT 初始化失败等）就直接送原图，
        // 宁可多花一点 token，也别让整条回复变成"看不清"。
        byte[] jpeg = null;
        try {
            jpeg = toJpeg(raw, Math.max(200, config.getVisionMaxEdge()));
        } catch (Throwable t) {
            System.out.println("[QQ] 图片缩放失败（" + t.getClass().getSimpleName() + ": " + t.getMessage() + "）→ 直接送原图");
        }
        if (jpeg != null) {
            System.out.println("[QQ] 图片已缩放 " + raw.length / 1024 + "KB → " + jpeg.length / 1024
                    + "KB（" + (System.currentTimeMillis() - t0) + "ms）");
            return new Prepared(Base64.getEncoder().encodeToString(jpeg), "image/jpeg");
        }
        System.out.println("[QQ] 原图直送（" + raw.length / 1024 + "KB）");
        return new Prepared(Base64.getEncoder().encodeToString(raw), sniffMime(raw));
    }

    /** 用指定提示词描述一张已准备好的图（重读时把"当前问题"当提示词传进来） */
    public String describePrepared(Prepared p, String prompt) {
        if (p == null) return null;
        String desc = deepSeekService.chatVisionContentOnly(p.base64, prompt, config.getVisionMaxTokens(), p.mime);
        if (desc == null || desc.trim().isEmpty()) {
            System.out.println("[QQ] 视觉模型没有返回内容（模型不支持读图 / max_tokens 被思考吃光 / 未配 Key）");
            return null;
        }
        return desc.trim().replaceAll("\\s+", " ");
    }

    /**
     * 下载 → 缩放 → 视觉描述（首次看图用默认提示词）。
     *
     * @param url OneBot {@code image} 段里的 url（也接受 {@code base64://} 内联图）
     */
    public String describe(String url) {
        Prepared p = prepare(url);
        if (p == null) return null;
        String clean = describePrepared(p, PROMPT);
        if (clean != null) {
            System.out.println("[QQ] 图片理解: " + (clean.length() > 120 ? clean.substring(0, 120) + "…" : clean));
        }
        return clean;
    }

    /** 看文件头猜 MIME（不缩放直接送原图时用，避免把 PNG 声明成 JPEG） */
    private static String sniffMime(byte[] b) {
        if (b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return "image/webp";
        if (b.length > 3 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        return "image/jpeg";
    }

    private byte[] download(String url) {
        String u = url.trim();
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            // NapCat 有时给的是容器内路径（file:// 或 /root/napcat/...），宿主机读不到
            System.out.println("[QQ] 图片地址不是 http(s)，跳过: " + u);
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(u))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Mozilla/5.0 (compatible; zhuochong-qq-bot)")
                    .GET().build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                System.out.println("[QQ] 图片下载失败 HTTP " + resp.statusCode() + ": " + u);
                return null;
            }
            byte[] body = resp.body();
            if (body == null || body.length == 0) return null;
            if (body.length > MAX_DOWNLOAD_BYTES) {
                System.out.println("[QQ] 图片太大（" + body.length / 1024 / 1024 + "MB），跳过: " + u);
                return null;
            }
            return body;
        } catch (Exception e) {
            System.out.println("[QQ] 图片下载异常: " + e.getMessage());
            return null;
        }
    }

    /** 长边缩到 maxEdge 以内，白底 JPEG（透明 PNG 直接转 JPEG 会变黑底） */
    private byte[] toJpeg(byte[] raw, int maxEdge) {
        try {
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(raw));
            if (src == null) return null;
            int w = src.getWidth();
            int h = src.getHeight();
            double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
            int nw = Math.max(1, (int) Math.round(w * scale));
            int nh = Math.max(1, (int) Math.round(h * scale));
            BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, nw, nh);
                g.drawImage(src, 0, 0, nw, nh, null);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(out, "jpg", bos);
            return bos.toByteArray();
        } catch (Exception e) {
            System.out.println("[QQ] 图片缩放失败: " + e.getMessage());
            return null;
        }
    }
}
