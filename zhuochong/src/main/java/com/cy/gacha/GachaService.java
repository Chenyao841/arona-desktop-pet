package com.cy.gacha;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 蔚蓝档案自动抽卡服务：
 * 人工参考图（麻将参考截屏/蔚蓝档案抽卡1-5.png，2560x1530）+ regions.json 抽卡标注（抽卡1-1 ~ 抽卡5-1）。
 * 流程：匹配 抽卡1-1 界面 → 点击 1-1 → 等待黑屏加载结束 → 依序点击 2-1, 3-1, 3-2, 4-1, 5-1（每步间隔 1s）。
 * 坐标：regions 为 2560 基准（参考图 2560x1530），实机游戏窗口占顶部 2560x1530 → 标注坐标 ≈ 屏幕物理坐标（x 同比例，y 用 1530 高基准换算）。
 */
@Service
public class GachaService {

    private static final File REF_DIR = new File(System.getProperty("user.dir"),
            "src/main/resources/static/麻将参考截屏");
    private static final File REGIONS_FILE = new File(REF_DIR, "regions.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** 参考图基准宽高（蔚蓝档案抽卡截图分辨率） */
    private static final int REF_W = 2560, REF_H = 1530;
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** 抽卡流程：固定步骤序列（regions 键名 → 点击前是否做界面匹配/黑屏等待） */
    private static final String[] STEP_KEYS = {
            "抽卡1-1", "抽卡2-1", "抽卡3-1", "抽卡3-2", "抽卡4-1", "抽卡5-1"
    };
    /** 参考图文件名（与步骤键序号对应，用于界面匹配；仅第 1 步做匹配，2→3 黑屏等待） */
    private static final String[] STEP_REFS = {
            "抽卡参考图1.png", "蔚蓝档案抽卡2.png", "蔚蓝档案抽卡3.png",
            "蔚蓝档案抽卡3.png", "蔚蓝档案抽卡4.png", "蔚蓝档案抽卡5.png"
    };
    private static final int CLICK_INTERVAL_MS = 1000; // 后续步骤固定间隔
    private static final int BLACK_TIMEOUT_MS = 30000; // 黑屏等待上限
    private static final int STEP_TIMEOUT_MS = 15000;  // 界面匹配等待上限
    private static final double MATCH_THRESHOLD = 0.50; // 界面匹配相似度阈值（1=完全一致）

    private Robot robot = null;

    public boolean isRunning() { return running.get(); }

    /**
     * 执行抽卡全流程。需游戏已停在"蔚蓝档案抽卡1.png"界面（1-1 抽卡按钮可见）。
     */
    public Map<String, Object> start() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!running.compareAndSet(false, true)) {
            out.put("ok", false);
            out.put("message", "抽卡已在进行中，请稍候");
            return out;
        }
        try {
            Map<String, Object> regions = readRegions();
            List<String> log = new ArrayList<>();
            ensureRobot();

            // 第 1 步：匹配抽卡1 界面（1-1 区域）并点击
            int[] r1 = regionOf(regions, "抽卡1-1");
            if (r1 == null) { out.put("ok", false); out.put("message", "缺少标注「抽卡1-1」，请先在标注页框选"); return out; }
            double[] diag = { -1, -1, -1 }; // {最后相似度, 实机截屏宽, 实机截屏高}
            if (!waitForMatch(STEP_REFS[0], r1, log, STEP_TIMEOUT_MS, diag)) {
                out.put("ok", false);
                out.put("message", "未检测到抽卡界面（参照 抽卡参考图1.png 的抽卡按钮），请把游戏切到抽卡页后重试。"
                        + "诊断：相似度=" + (diag[0] >= 0 ? String.format("%.2f", diag[0]) : "无")
                        + "，实机截屏=" + (int) diag[1] + "x" + (int) diag[2]);
                return out;
            }
            clickCenter(r1, "抽卡1-1", log, true); // 首步带诊断快照
            out.put("matchScore", diag[0]);
            out.put("screenSize", ((int) diag[1]) + "x" + ((int) diag[2]));

            // 图1 → 图2：黑屏/画面变化等待；若点击未生效则中止
            if (!waitBlackLoading(log)) {
                out.put("ok", false);
                out.put("message", "点击 1-1 后画面无变化，抽卡未开始。请确认：①游戏窗口在前台且未被遮挡；"
                        + "②实机截屏尺寸 " + out.get("screenSize") + " 与参考图基准匹配。诊断快照已存 desktop_vision_log/gacha_before_1-1.png");
                return out;
            }

            // 后续步骤：固定 1s 间隔点击
            for (int i = 1; i < STEP_KEYS.length; i++) {
                sleep(CLICK_INTERVAL_MS);
                int[] reg = regionOf(regions, STEP_KEYS[i]);
                if (reg == null) {
                    log.add("缺少标注「" + STEP_KEYS[i] + "」，跳过");
                    continue;
                }
                clickCenter(reg, STEP_KEYS[i], log, false);
            }
            out.put("ok", true);
            out.put("message", "抽卡流程完成（步骤：1-1→2-1→3-1→3-2→4-1→5-1）");
            out.put("log", log);
            return out;
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "抽卡失败: " + e.getMessage());
            return out;
        } finally {
            running.set(false);
        }
    }

    /** 等待指定参考图的指定区域与实时屏幕匹配（界面匹配检测）；diag[0]=最后相似度, diag[1]=截屏宽, diag[2]=截屏高 */
    private boolean waitForMatch(String refFile, int[] reg2560, List<String> log, int timeoutMs, double[] diag) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        BufferedImage ref = loadRefImage(refFile);
        if (ref == null) { log.add("参考图缺失: " + refFile); return false; }
        while (System.currentTimeMillis() < deadline) {
            BufferedImage screen = captureScreen();
            if (screen == null) { sleep(500); continue; }
            diag[1] = screen.getWidth();
            diag[2] = screen.getHeight();
            double score = regionMatchScore(screen, ref, reg2560);
            diag[0] = score;
            if (score >= MATCH_THRESHOLD) { // 匹配度足够（经验阈值）
                log.add("界面匹配 " + refFile + " 区域 抽卡1-1 相似度=" + String.format("%.2f", score));
                return true;
            }
            log.add("等待界面匹配 " + refFile + " 相似度=" + String.format("%.2f", score)
                    + "（实机截屏 " + screen.getWidth() + "x" + screen.getHeight() + "）");
            sleep(500);
        }
        return false;
    }

    /**
     * 图1→图2 黑屏加载：点击 1-1 后可能出现全屏黑屏过渡。
     * 若始终无画面变化（无黑屏、帧差很小），说明 1-1 点击很可能没生效，返回 false 让流程中止提示。
     */
    private boolean waitBlackLoading(List<String> log) {
        BufferedImage prev = captureScreen();
        long appearDeadline = System.currentTimeMillis() + 5000;
        boolean changed = false;
        while (System.currentTimeMillis() < appearDeadline) {
            BufferedImage screen = captureScreen();
            if (screen != null) {
                double darkRatio = frameDarkRatio(screen);
                if (darkRatio > 0.85) { log.add("检测到黑屏 dark=" + String.format("%.2f", darkRatio)); changed = true; break; }
                if (prev != null && frameDiff(prev, screen) > 0.12) { log.add("画面有变化（点击生效）"); changed = true; break; }
                prev = screen;
            }
            sleep(250);
        }
        if (!changed) {
            log.add("点击 1-1 后 5s 内画面无变化——点击可能未生效（窗口未在前台？坐标偏？），中止流程");
            return false;
        }
        // 出现黑屏 → 等黑屏结束
        long endDeadline = System.currentTimeMillis() + BLACK_TIMEOUT_MS;
        while (System.currentTimeMillis() < endDeadline) {
            BufferedImage screen = captureScreen();
            if (screen != null) {
                double darkRatio = frameDarkRatio(screen);
                if (darkRatio < 0.6) {
                    log.add("黑屏结束 dark=" + String.format("%.2f", darkRatio));
                    return true;
                }
            }
            sleep(300);
        }
        log.add("黑屏等待超时，按已结束处理");
        return true;
    }

    /** 整帧平均灰度差（0~1），用于判断画面是否变化 */
    private double frameDiff(BufferedImage a, BufferedImage b) {
        try {
            int W = 96, H = 54;
            BufferedImage sa = resizeGray(a, W, H), sb = resizeGray(b, W, H);
            double diff = 0;
            for (int yy = 0; yy < H; yy++) {
                for (int xx = 0; xx < W; xx++) {
                    diff += Math.abs((sa.getRGB(xx, yy) & 0xFF) - (sb.getRGB(xx, yy) & 0xFF));
                }
            }
            return diff / (W * H) / 255.0;
        } catch (Exception e) { return 0; }
    }

    // ===== 工具 =====

    /**
     * 屏幕→标注坐标换算比例。regions/参考图以 2560 宽基准；抽卡窗口与参考图同宽高比且顶部对齐，
     * 故 x、y 统一乘同一比例（screenW/2560）即可兼容物理(2560宽)与逻辑(1707宽)两种实机截屏。
     */
    private double scaleFactor() {
        Rectangle scr = screenSize();
        return scr.width / (double) REF_W;
    }

    private void clickCenter(int[] reg2560, String stepName, List<String> log, boolean saveDiag) {
        if (reg2560 == null) return;
        double s = scaleFactor();
        int cx = (int) Math.round(((reg2560[0] + reg2560[1]) / 2.0) * s);
        int cy = (int) Math.round(((reg2560[2] + reg2560[3]) / 2.0) * s);
        if (saveDiag) saveDiagFrame("gacha_before_1-1", cx, cy, reg2560, s);
        click(cx, cy);
        log.add("点击 " + stepName + " 中心 (" + cx + "," + cy + ")");
        if (saveDiag) saveDiagFrame("gacha_after_1-1", cx, cy, reg2560, s);
    }

    /** 诊断快照：保存整屏截图并画上目标框与点击点（存到 desktop_vision_log，供人工核对点击落点） */
    private void saveDiagFrame(String tag, int cx, int cy, int[] reg2560, double s) {
        try {
            BufferedImage screen = captureScreen();
            if (screen == null) return;
            Graphics2D g = screen.createGraphics();
            g.setColor(new Color(255, 0, 0));
            g.setStroke(new java.awt.BasicStroke(6));
            if (reg2560 != null) {
                int x1 = (int) Math.round(reg2560[0] * s), y1 = (int) Math.round(reg2560[2] * s);
                int x2 = (int) Math.round(reg2560[1] * s), y2 = (int) Math.round(reg2560[3] * s);
                g.drawRect(x1, y1, Math.max(1, x2 - x1), Math.max(1, y2 - y1));
            }
            g.setColor(new Color(0, 255, 0));
            g.fillOval(cx - 12, cy - 12, 24, 24);
            g.dispose();
            File dir = new File(System.getProperty("user.dir"), "desktop_vision_log");
            dir.mkdirs();
            ImageIO.write(screen, "png", new File(dir, tag + ".png"));
        } catch (Exception e) {
            System.out.println("[抽卡] 诊断快照失败: " + e.getMessage());
        }
    }

    private void click(int x, int y) {
        try {
            lockPetWindow();
            ensureRobot();
            robot.mouseMove(x, y);
            robot.delay(120); // 等光标落位，避免移动后立即点击被吞
            // 重要：用 BUTTON1_MASK(16)——BUTTON1_DOWN_MASK(1024) 在部分 JDK 上不触发左键（与桌面操控 clickAt 同款经验）
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
            robot.delay(80);  // 按下保持一小段，模拟真实点击
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
            robot.delay(150);
            unlockPetWindow();
            try {
                Rectangle scr = screenSize();
                robot.mouseMove(scr.width / 2, scr.height / 2);
            } catch (Exception ignored) {}
        } catch (Exception e) {
            unlockPetWindow();
            System.out.println("[抽卡] 点击失败: " + e.getMessage());
        }
    }

    /** 实时屏幕与参考图在区域内的匹配度（0~1，越高越像）。
     *  窗口与参考图同宽高比、顶部对齐并铺满屏幕可用宽度 → 实机区域 x、y 统一乘 scaleFactor 换算；
     *  参考图按 2560x1530 原生坐标裁剪。 */
    private double regionMatchScore(BufferedImage screen, BufferedImage ref, int[] reg2560) {
        try {
            double s = scaleFactor();
            int x1 = (int) Math.round(reg2560[0] * s), x2 = (int) Math.round(reg2560[1] * s);
            int y1 = (int) Math.round(reg2560[2] * s), y2 = (int) Math.round(reg2560[3] * s);
            BufferedImage a = crop(screen, x1, y1, x2, y2);
            BufferedImage b = crop(ref, reg2560[0], reg2560[2], reg2560[1], reg2560[3]);
            if (a == null || b == null) return 0;
            int W = 48, H = 32;
            BufferedImage sa = resizeGray(a, W, H), sb = resizeGray(b, W, H);
            double diff = 0;
            for (int yy = 0; yy < H; yy++) {
                for (int xx = 0; xx < W; xx++) {
                    diff += Math.abs((sa.getRGB(xx, yy) & 0xFF) - (sb.getRGB(xx, yy) & 0xFF));
                }
            }
            double avg = diff / (W * H) / 255.0; // 0~1，越小越像
            return 1.0 - avg;
        } catch (Exception e) {
            return 0;
        }
    }

    private BufferedImage crop(BufferedImage img, int x1, int y1, int x2, int y2) {
        try {
            int X1 = Math.max(0, Math.min(img.getWidth() - 1, x1)), Y1 = Math.max(0, Math.min(img.getHeight() - 1, y1));
            int X2 = Math.max(0, Math.min(img.getWidth() - 1, x2)), Y2 = Math.max(0, Math.min(img.getHeight() - 1, y2));
            if (X2 <= X1 || Y2 <= Y1) return null;
            return img.getSubimage(X1, Y1, X2 - X1, Y2 - Y1);
        } catch (Exception e) { return null; }
    }

    private BufferedImage resizeGray(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    /** 整帧暗像素占比 */
    private double frameDarkRatio(BufferedImage screen) {
        try {
            int dark = 0, total = 0;
            for (int y = 0; y < screen.getHeight(); y += 12) {
                for (int x = 0; x < screen.getWidth(); x += 12) {
                    int rgb = screen.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum < 80) dark++;
                    total++;
                }
            }
            return total == 0 ? 0 : (double) dark / total;
        } catch (Exception e) { return 0; }
    }

    private Rectangle screenSize() {
        try { return new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()); }
        catch (Throwable t) { return new Rectangle(2560, 1530); }
    }

    private BufferedImage captureScreen() {
        try {
            return new Robot().createScreenCapture(screenSize());
        } catch (Exception e) {
            System.out.println("[抽卡] 截屏失败: " + e.getMessage());
            return null;
        }
    }

    private void ensureRobot() {
        try { if (robot == null) robot = new Robot(); } catch (Exception ignored) {}
    }

    private BufferedImage loadRefImage(String name) {
        try {
            File f = new File(REF_DIR, name);
            if (f.exists()) return ImageIO.read(f);
            return null;
        } catch (Exception e) { return null; }
    }

    private void sleep(int ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 桌宠窗口点击穿透（Electron 控制端口 3081），避免透明置顶桌宠吞点击 */
    private void lockPetWindow() { petControl("lock"); }
    private void unlockPetWindow() { petControl("unlock"); }

    private void petControl(String action) {
        try {
            URL url = new URL("http://127.0.0.1:3081/" + action);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(400);
            con.setReadTimeout(400);
            con.getResponseCode();
            con.disconnect();
        } catch (Exception e) {
            System.out.println("[抽卡] 桌宠控制(" + action + ")失败(忽略): " + e.getMessage());
        }
    }

    private Map<String, Object> readRegions() {
        try {
            if (REGIONS_FILE.exists()) {
                return JSON.readValue(REGIONS_FILE, Map.class);
            }
        } catch (Exception e) {
            System.out.println("[抽卡] 读取 regions.json 失败: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    private int[] regionOf(Map<String, Object> m, String name) {
        try {
            Object r = m.get(name);
            if (r instanceof Map) {
                Map<?, ?> reg = (Map<?, ?>) r;
                return new int[]{
                        ((Number) reg.get("x1")).intValue(), ((Number) reg.get("x2")).intValue(),
                        ((Number) reg.get("y1")).intValue(), ((Number) reg.get("y2")).intValue()
                };
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 停止（预留） */
    public Map<String, Object> stop() {
        Map<String, Object> out = new LinkedHashMap<>();
        running.set(false);
        out.put("ok", true);
        out.put("message", "已请求停止抽卡（当前步骤结束后不再继续）");
        return out;
    }
}
