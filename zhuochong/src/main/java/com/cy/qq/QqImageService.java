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

    /** 单张图最大下载体积 */
    private static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;

    /** 让视觉模型只做「客观描述」，不做评价（评价交给后面的对话模型） */
    private static final String PROMPT =
            "这是 QQ 群里群友发来的一张图片。请客观、简短地描述："
                    + "画面主体是什么、有没有明显文字（照抄关键文字）、是不是表情包/梗图。"
                    + "90 字以内，只讲看到的事实，不要评价、不要寒暄、不要猜测画面外的内容。"
                    + "如果实在看不清，就回答「看不清」。";

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
     * @param url OneBot {@code image} 段里的 url
     * @return 中文描述；失败返回 null（调用方降级为「群友发了一张图」）
     */
    public String describe(String url) {
        if (url == null || url.isEmpty()) return null;
        long t0 = System.currentTimeMillis();
        byte[] raw = download(url);
        if (raw == null) return null;
        byte[] jpeg = toJpeg(raw, Math.max(200, config.getVisionMaxEdge()));
        if (jpeg == null) {
            System.out.println("[QQ] 图片解码失败（可能不是常见格式）: " + url);
            return null;
        }
        String base64 = Base64.getEncoder().encodeToString(jpeg);
        System.out.println("[QQ] 图片已缩放 " + raw.length / 1024 + "KB → " + jpeg.length / 1024
                + "KB，送视觉模型（" + (System.currentTimeMillis() - t0) + "ms）");
        // 用 chatVisionContentOnly：只认正式回答，不接受 reasoning_content 兜底。
        // 因为现在的视觉模型多是"推理模型"，思考过程会跑到 reasoning_content 里；
        // 把思考过程当图片描述用会得到一堆"我们需要回答这个问题…"的废话。
        // max_tokens 默认 4000（可配 ZC_QQ_VISION_MAX_TOKENS）：推理模型会先用掉一部分在思考上，
        // 给小了 content 就是空的（实测 600 就被吃光）。
        String desc = deepSeekService.chatVisionContentOnly(base64, PROMPT, config.getVisionMaxTokens());
        if (desc == null || desc.trim().isEmpty()) {
            System.out.println("[QQ] 视觉模型没有返回描述（模型不支持读图 / max_tokens 被思考吃光 / 未配 Key）");
            return null;
        }
        String clean = desc.trim().replaceAll("\\s+", " ");
        System.out.println("[QQ] 图片理解: " + (clean.length() > 120 ? clean.substring(0, 120) + "…" : clean));
        return clean;
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
