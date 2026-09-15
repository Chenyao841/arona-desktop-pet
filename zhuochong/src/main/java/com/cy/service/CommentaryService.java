package com.cy.service;

import com.cy.mapper.CommentaryMapper;
import com.cy.pojo.CommentaryConfig;
import com.cy.pojo.CommentaryContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.Base64;

/**
 * 陪玩点评模式：短间隔读屏截图 → 一步法视觉点评（视觉模型直接按人设输出一句短评）。
 * 设计要点：
 *  - 一步法：把语气/语境/长度/去重要求全写进视觉提示词，一次调用出点评（不经过"先描述再改编"两步）
 *  - 变化检测：64×40 灰度帧平均差，静止画面直接跳过（省一次视觉调用）
 *  - 截图留存：按配置保留 N 分钟（便于调试），到点自动清理
 *  - 截图固定降到宽 800px（实测 1000px 会被视觉模型拒答）
 */
@Service
public class CommentaryService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int SHOT_WIDTH = 800;      // 视觉模型可接受的宽度上限（实测）
    private static final int SMALL_W = 64, SMALL_H = 40;  // 变化检测用的缩略帧

    @Autowired
    private CommentaryMapper commentaryMapper;

    @Autowired
    private DeepSeekService deepSeekService;

    /** 运行期状态（单机单实例，够用） */
    private BufferedImage lastSmall = null;   // 上一次的缩略灰度帧
    private volatile String lastComment = "";  // 上一次点评内容（避免重复）
    private volatile long lastShotAt = 0;

    // ===== 初始化：建表 + 默认值 =====
    @PostConstruct
    public void init() {
        try {
            commentaryMapper.createConfigTable();
            commentaryMapper.createContextTable();
            if (commentaryMapper.findConfig() == null) {
                CommentaryConfig c = new CommentaryConfig();
                c.setEnabled(0); c.setInterval_sec(12); c.setMin_change_score(3); c.setMax_streak(3);
                c.setSilence_sec(45); c.setAfter_talk_sec(20); c.setVoice_on(1); c.setVolume_percent(45);
                c.setSkip_percent(35); c.setMax_chars(20); c.setKeep_minutes(60); c.setAuto_detect(1);
                c.setManual_ttl_min(15); c.setMax_tokens(1200);
                commentaryMapper.insertConfig(c);
                System.out.println("[陪玩点评] 配置表已初始化");
            }
            if (commentaryMapper.findAllContexts().isEmpty()) {
                seedContexts();
            }
        } catch (Exception e) {
            System.out.println("[陪玩点评] 初始化失败: " + e.getMessage());
        }
    }

    private void seedContexts() {
        Object[][] seed = {
                {"bilibili", "B站（视频/直播/番剧）",
                 "像一起看视频的观众那样吐槽：可以惊呼、接梗、吐槽剧情或画面（“诶诶诶这什么展开！”“这也太真实了吧”）。不剧透后面剧情、不刷烂梗。"},
                {"mahjong", "麻将（雀魂等）",
                 "只点评氛围和运势，例如“这手牌有点难办呀”“差一点就立直了”。绝对不要给操作建议、不要说该打哪张牌（会干扰老师自己的判断）。"},
                {"bluearchive", "蔚蓝档案",
                 "认出场景（抽卡/战斗/剧情/主界面）并做短促反应，例如“出金了！”“老师这关好难”。不剧透剧情。"},
                {"work", "办公/写代码/看文档",
                 "以关心为主，例如“老师又在写代码…记得喝水”“这个页面看起来好复杂”。不要评价技术水平、不要念屏幕上的文字或文件名。"},
                {"other", "其它/看不清",
                 "泛用陪看：对画面里最显眼的东西给一句轻评论。不要编造画面里没有的内容，看不清就说看不清。"}
        };
        for (Object[] s : seed) {
            CommentaryContext c = new CommentaryContext();
            c.setCode((String) s[0]); c.setLabel((String) s[1]); c.setPrompt((String) s[2]); c.setEnabled(1);
            commentaryMapper.insertContext(c);
        }
        System.out.println("[陪玩点评] 语境默认值已写入（" + seed.length + " 条）");
    }

    public CommentaryConfig config() {
        CommentaryConfig c = commentaryMapper.findConfig();
        if (c == null) { c = new CommentaryConfig(); c.setInterval_sec(12); c.setMin_change_score(3); c.setMax_streak(3);
            c.setSilence_sec(45); c.setAfter_talk_sec(20); c.setVoice_on(1); c.setVolume_percent(45);
            c.setSkip_percent(35); c.setMax_chars(20); c.setKeep_minutes(60); c.setAuto_detect(1); c.setManual_ttl_min(15); c.setMax_tokens(1200); }
        return c;
    }

    // 注意：本类 import 了 java.awt.*（Robot/Image），java.util.List 会被 java.awt.List 抢占，故此处写全限定名
    public java.util.List<CommentaryContext> contexts() { return commentaryMapper.findAllContexts(); }

    public void saveConfig(CommentaryConfig c) { commentaryMapper.updateConfig(c); }

    public void saveContext(CommentaryContext c) { commentaryMapper.updateContext(c); }

    /** 重置基准帧（进入模式时调用，避免与上次的旧画面比较） */
    public void resetBaseline() { lastSmall = null; lastComment = ""; }

    /**
     * 执行一次点评。
     * @param contextHint 人工告知的语境 code（可为空 → 自动判定兜底）
     * @return {ok, skipped, reason, changeScore, text, motion, context, shot}
     */
    public Map<String, Object> once(String contextHint) {
        Map<String, Object> out = new LinkedHashMap<>();
        CommentaryConfig cfg = config();
        int keepMin = cfg.getKeep_minutes() == null ? 60 : cfg.getKeep_minutes();

        // ① 截屏（缩略帧用于变化检测；800px JPEG 用于视觉）
        BufferedImage full;
        try {
            Robot robot = new Robot();
            full = robot.createScreenCapture(new Rectangle(Toolkit.getDefaultToolkit().getScreenSize()));
        } catch (Exception e) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "截屏失败：" + e.getMessage());
            return out;
        }
        BufferedImage small = toSmallGray(full);
        int score = diffScore(lastSmall, small);
        out.put("changeScore", score);

        // ② 变化检测：画面基本没动就不点评（省一次视觉调用）
        int minScore = cfg.getMin_change_score() == null ? 3 : cfg.getMin_change_score();
        if (lastSmall != null && score < minScore) {
            out.put("ok", true); out.put("skipped", true);
            out.put("reason", "画面无变化（差异 " + score + " < " + minScore + "）");
            return out;
        }
        lastSmall = small;

        // ③ 800px JPEG + 留存
        String base64;
        try {
            base64 = toJpegBase64(full);
        } catch (Exception e) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "截图编码失败：" + e.getMessage());
            return out;
        }
        String shotName = null;
        if (keepMin > 0) {
            shotName = "commentary_" + System.currentTimeMillis() + ".jpg";
            try {
                File dir = new File(System.getProperty("user.dir"), "desktop_vision_log");
                dir.mkdirs();
                Files.write(new File(dir, shotName).toPath(), Base64.getDecoder().decode(base64));
            } catch (Exception ignored) {}
            cleanupOldShots(keepMin);
        }
        out.put("shot", shotName);

        // ④ 组装一步法提示词
        String code = (contextHint == null || contextHint.trim().isEmpty()) ? "" : contextHint.trim();
        CommentaryContext ctx = code.isEmpty() ? null : commentaryMapper.findByCode(code);
        String prompt = buildPrompt(cfg, ctx, code.isEmpty());

        // ⑤ 一次视觉调用直接出点评（只取正式回答，绝不把思考过程当点评）
        int maxTokens = cfg.getMax_tokens() == null || cfg.getMax_tokens() < 200 ? 1200 : cfg.getMax_tokens();
        String raw = deepSeekService.chatVisionContentOnly(base64, prompt, maxTokens);
        if (raw == null || raw.trim().isEmpty()) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "视觉模型没有产出正式回答（推理型模型可能把 max_tokens=" + maxTokens
                    + " 全用在思考上；可在设置页调大「点评 token 预算」，或检查 API Key）");
            return out;
        }
        Map<String, String> parsed = parseReply(raw);
        if (parsed == null) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "模型没有按要求返回 JSON（已丢弃本次输出，避免把思考过程当点评）");
            out.put("raw", raw.length() > 300 ? raw.substring(0, 300) + "…" : raw);
            return out;
        }
        String text = parsed.get("text");
        if (text == null || text.trim().isEmpty()) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "点评内容为空");
            out.put("raw", raw.length() > 300 ? raw.substring(0, 300) + "…" : raw);
            return out;
        }
        text = cleanupComment(text);
        // 长度策略：正常的短评直接放行；**明显超长视为"混入思考过程"直接丢弃**，短超长则按标点截断
        int maxChars = cfg.getMax_chars() == null ? 20 : cfg.getMax_chars();
        if (text.length() > maxChars * 3) {
            out.put("ok", false); out.put("skipped", true);
            out.put("reason", "点评过长（" + text.length() + " 字，疑似混入思考过程），已丢弃");
            out.put("raw", text.length() > 300 ? text.substring(0, 300) + "…" : text);
            return out;
        }
        if (text.length() > maxChars) text = trimToChars(text, maxChars);
        lastComment = text;
        lastShotAt = System.currentTimeMillis();

        out.put("ok", true); out.put("skipped", false);
        out.put("text", text);
        out.put("motion", parsed.get("motion"));
        // 语境：人工指定优先；否则用视觉判定的（自动兜底）
        String detected = parsed.get("context");
        String valid = isValidCode(detected) ? detected : (ctx != null ? ctx.getCode() : "other");
        out.put("context", valid);
        out.put("contextSource", ctx != null ? "manual" : "auto");
        return out;
    }

    private boolean isValidCode(String c) {
        if (c == null || c.isEmpty()) return false;
        for (CommentaryContext ctx : commentaryMapper.findAllContexts()) {
            if (ctx.getCode() != null && ctx.getCode().equals(c)) return true;
        }
        return false;
    }

    private String buildPrompt(CommentaryConfig cfg, CommentaryContext ctx, boolean autoDetect) {
        StringBuilder sb = new StringBuilder();
        int maxChars = cfg.getMax_chars() == null ? 20 : cfg.getMax_chars();
        sb.append("你是「阿罗娜」，现在正**陪着老师用电脑**，刚瞄了一眼屏幕，要说一句像弹幕一样的短评。\n");
        if (ctx != null) {
            sb.append("【当前语境】").append(ctx.getLabel()).append("：").append(ctx.getPrompt() == null ? "" : ctx.getPrompt()).append("\n");
        } else if (autoDetect) {
            sb.append("【当前语境】未知，请你先自己判断屏幕里是什么（B站视频/麻将/蔚蓝档案/办公文档/其它）。\n");
        }
        sb.append("【语气】像旁边的观众，轻松、短促、有情绪；可以吐槽或惊呼，但不要长篇大论。\n");
        sb.append("【硬性要求】\n");
        sb.append("1. **只输出一行 JSON，第一个字符必须是 {，最后一个字符必须是 }**；不要任何思考过程、不要解释、不要代码块、不要前后寒暄。\n");
        sb.append("2. JSON 格式：{\"boat_text\":\"点评内容\",\"motion\":\"表情文件名\",\"context\":\"语境代码\"}\n");
        sb.append("3. boat_text 不超过 ").append(maxChars).append(" 个字，一句话，不要换行；不要问老师问题、不要给建议、不要念屏幕上的文字。\n");
        sb.append("4. motion 只能从这个列表里选一个：aluona_kaixin.png / aluona_jidong.png / aluona_qidai.png / aluona_kunhuo.png / aluona_huaiyi.png / aluona_zhengjing.png / aluona_zhengchang.png / aluona_haixiu.png\n");
        sb.append("5. context 只能是这几个代码之一：bilibili / mahjong / bluearchive / work / other。\n");
        if (lastComment != null && !lastComment.isEmpty()) {
            sb.append("6. 不要和上一句重复或近似（上一句：「").append(lastComment).append("」）。\n");
        }
        sb.append("7. 只依据画面里真实可见的内容；看不清就说看不清，绝不编造。\n");
        sb.append("8. 不评价老师的操作水平、不剧透剧情、不提及聊天记录/文件名等私密内容。\n");
        sb.append("现在直接输出那一行 JSON：\n");
        return sb.toString();
    }

    /**
     * 解析视觉模型返回：只认第一个 JSON 对象（去代码块/前后废话）。
     * 找不到 JSON 一律返回 null —— 早先的实现会"整段当点评"，结果把模型的思考过程
     * （如「用户想让我以阿罗娜的语气点评…」）当成了点评，这里必须严格。
     */
    private Map<String, String> parseReply(String raw) {
        if (raw == null) return null;
        String s = raw.trim().replaceAll("(?s)```json", "").replaceAll("```", "").trim();
        int i = s.indexOf('{'), j = s.lastIndexOf('}');
        if (i < 0 || j <= i) return null;
        try {
            JsonNode n = JSON.readTree(s.substring(i, j + 1));
            String text = text(n.get("boat_text"));
            if (text == null || text.trim().isEmpty()) text = text(n.get("comment"));
            if (text == null || text.trim().isEmpty()) return null;
            Map<String, String> out = new LinkedHashMap<>();
            out.put("text", text.trim());
            out.put("motion", text(n.get("motion")));
            out.put("context", text(n.get("context")));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 去掉引号、前缀、换行等杂质；剔除明显是思考过程的句式 */
    private String cleanupComment(String text) {
        String s = text.trim();
        s = s.replace("\n", " ").replaceAll("\\s+", " ");
        s = s.replaceAll("^[\"'“”「『]+|[\"'“”」』]+$", "").trim();
        s = s.replaceAll("^(阿罗娜|阿洛娜)\\s*[:：]\\s*", "").trim();
        // 思考过程特征句式 → 直接判为无效（返回空串，由调用方丢弃）
        String[] thinkingMarkers = {"用户想让我", "用户希望我", "我需要先", "我先看看", "作为阿罗娜，我", "让我想想", "根据要求", "按照指令", "首先，我", "思考过程", "这个画面显示的是老师"};
        for (String m : thinkingMarkers) {
            if (s.contains(m)) return "";
        }
        return s;
    }

    /** 按字数上限截断，尽量在标点处断开 */
    private String trimToChars(String text, int maxChars) {
        String head = text.substring(0, Math.min(text.length(), maxChars));
        int cut = -1;
        for (int k = head.length() - 1; k >= Math.max(0, head.length() - 6); k--) {
            char ch = head.charAt(k);
            if ("。！？!?…，,".indexOf(ch) >= 0) { cut = k; break; }
        }
        return cut > 0 ? head.substring(0, cut + 1) : head;
    }

    private static String text(JsonNode n) { return n == null || n.isNull() ? "" : n.asText(""); }

    // ===== 图像工具 =====

    private BufferedImage toSmallGray(BufferedImage src) {
        BufferedImage small = new BufferedImage(SMALL_W, SMALL_H, BufferedImage.TYPE_BYTE_GRAY);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, SMALL_W, SMALL_H, null);
        g.dispose();
        return small;
    }

    /** 两帧平均绝对差（0~255） */
    private int diffScore(BufferedImage a, BufferedImage b) {
        if (a == null || b == null) return 255;
        long sum = 0;
        for (int y = 0; y < SMALL_H; y++) {
            for (int x = 0; x < SMALL_W; x++) {
                int va = a.getRaster().getSample(x, y, 0);
                int vb = b.getRaster().getSample(x, y, 0);
                sum += Math.abs(va - vb);
            }
        }
        return (int) (sum / (SMALL_W * SMALL_H));
    }

    /** 降到 800px 宽 → JPEG → base64（与桌面视觉保持一致，实测 1000px 会被模型拒答） */
    private String toJpegBase64(BufferedImage src) throws Exception {
        BufferedImage img = src;
        int w = src.getWidth(), h = src.getHeight();
        if (w > SHOT_WIDTH) {
            int th = (int) ((double) h * SHOT_WIDTH / w);
            Image scaled = src.getScaledInstance(SHOT_WIDTH, th, Image.SCALE_SMOOTH);
            BufferedImage resized = new BufferedImage(SHOT_WIDTH, th, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(scaled, 0, 0, null);
            g.dispose();
            img = resized;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    /** 清理超过留存时长的点评截图 */
    private void cleanupOldShots(int keepMinutes) {
        try {
            File dir = new File(System.getProperty("user.dir"), "desktop_vision_log");
            if (!dir.isDirectory()) return;
            long deadline = System.currentTimeMillis() - keepMinutes * 60_000L;
            File[] files = dir.listFiles((d, n) -> n.startsWith("commentary_") && n.endsWith(".jpg"));
            if (files == null) return;
            int removed = 0;
            for (File f : files) {
                if (f.lastModified() < deadline && f.delete()) removed++;
            }
            if (removed > 0) System.out.println("[陪玩点评] 清理过期截图 " + removed + " 张（保留 " + keepMinutes + " 分钟）");
        } catch (Exception ignored) {}
    }
}
