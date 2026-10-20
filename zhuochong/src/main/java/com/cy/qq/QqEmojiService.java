package com.cy.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群聊表情包（P1）。
 *
 * <p>素材：官方「阿罗娜 - 初始立绘差分」里的头部特写，已缩到 360×360 放在
 * {@code static/qq_emoji/}，文件与情绪的对应关系写在 {@code static/qq_emoji/emoji_map.json}
 * （带人工标注，改标注不用改代码）。
 *
 * <p>为什么不用原来的 {@code aluona/*.png}：那些是全身立绘，缩到表情包尺寸整个人只有一点点，
 * 群里看着像贴了张桌面截图；这批差分本身就是头肩构图，直接当表情包刚好。
 *
 * <p>选图逻辑：优先用 AI 返回的 {@code emotion}；没有就用回复文本里的关键词猜一个；
 * 都没有就落到「默认」（平静微笑）。同一情绪有多张时随机挑，并避免连续两张一样。
 */
@Service
public class QqEmojiService {

    private static final String MAP_RESOURCE = "static/qq_emoji/emoji_map.json";
    private static final String IMG_RESOURCE_PREFIX = "static/qq_emoji/";
    private static final ObjectMapper M = new ObjectMapper();

    @Autowired
    private QqConfig config;

    /** 情绪 → 候选图片文件名 */
    private final Map<String, List<String>> byEmotion = new HashMap<>();
    /** 文件名 → 人工标注的表情内容 */
    private final Map<String, String> labelByFile = new LinkedHashMap<>();
    /** 文件名 → base64（懒加载并缓存：一张约 230KB，全用上也才几 MB） */
    private final Map<String, String> base64Cache = new ConcurrentHashMap<>();

    private String defaultFile = null;
    private volatile String lastPicked = null;
    private final AtomicLong sentCount = new AtomicLong();

    /** 文本关键词 → 情绪（AI 没给 emotion 时兜底） */
    private static final String[][] KEYWORDS = {
            {"开心", "笑|哈哈|嘿嘿|开心|好耶|太好了|喜欢|棒"},
            {"激动", "哇|好厉害|太强了|真的吗|惊了|啊啊"},
            {"害羞", "害羞|脸红|不好意思|讨厌啦|羞"},
            {"生气", "生气|哼|气死|烦|讨厌|不许"},
            {"难过", "难过|哭|呜|对不起|抱歉|可惜|唉"},
            {"慌张", "糟糕|完了|怎么办|来不及|紧张|不好"},
            {"困惑", "咦|诶|欸|为什么|不懂|奇怪|晕"},
            {"期待", "期待|想要|想去|带我|下次|一起"},
            {"怀疑", "真的假的|是吗|确定吗|怀疑|不会吧"},
            {"无语", "无语|好吧|算了|随便|噗"},
            {"温柔", "谢谢|辛苦了|晚安|加油|别担心|乖"},
    };

    @PostConstruct
    public void load() {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MAP_RESOURCE)) {
            if (in == null) {
                System.out.println("[QQ] 找不到表情标注表 " + MAP_RESOURCE + "，表情包功能将不可用");
                return;
            }
            JsonNode root = M.readTree(in);
            defaultFile = root.path("default").asText(null);
            for (JsonNode s : root.path("stickers")) {
                String file = s.path("file").asText(null);
                if (file == null || file.isEmpty()) continue;
                labelByFile.put(file, s.path("label").asText(""));
                for (JsonNode e : s.path("emotions")) {
                    byEmotion.computeIfAbsent(e.asText(), k -> new ArrayList<>()).add(file);
                }
            }
            System.out.println("[QQ] 表情包已加载 " + labelByFile.size() + " 张，覆盖 "
                    + byEmotion.size() + " 个情绪标签（素材：官方立绘差分，见 qq_emoji/emoji_map.json）");
        } catch (Exception e) {
            System.out.println("[QQ] 表情标注表解析失败: " + e.getMessage());
        }
    }

    /**
     * 挑一张表情。
     *
     * @param emotion AI 返回的情绪词（可为空）
     * @param reply   回复文本（emotion 为空时用来猜）
     * @return 图片文件名；没素材时返回 null（调用方跳过发图）
     */
    public String pickSticker(String emotion, String reply) {
        String key = matchEmotion(emotion);
        if (key == null) key = inferFromText(reply);
        List<String> candidates = key == null ? null : byEmotion.get(key);
        if (candidates == null || candidates.isEmpty()) candidates = byEmotion.get("默认");
        if (candidates == null || candidates.isEmpty()) return defaultFile;

        String pick = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        if (candidates.size() > 1 && pick.equals(lastPicked)) {
            pick = candidates.get((candidates.indexOf(pick) + 1) % candidates.size());
        }
        lastPicked = pick;
        return pick;
    }

    /** 表情图的 base64（不含 data: 前缀），读不到返回 null */
    public String base64Of(String file) {
        if (file == null || file.isEmpty()) return null;
        String cached = base64Cache.get(file);
        if (cached != null) return cached;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(IMG_RESOURCE_PREFIX + file)) {
            if (in == null) {
                System.out.println("[QQ] 表情文件不存在: " + IMG_RESOURCE_PREFIX + file);
                return null;
            }
            String b64 = Base64.getEncoder().encodeToString(in.readAllBytes());
            base64Cache.put(file, b64);
            return b64;
        } catch (Exception e) {
            System.out.println("[QQ] 读表情文件失败 " + file + ": " + e.getMessage());
            return null;
        }
    }

    public String labelOf(String file) {
        return file == null ? "" : labelByFile.getOrDefault(file, "");
    }

    /**
     * 这条回复算不算「情绪变化较大」（P4：只有大的才配表情图）。
     *
     * <p>判断顺序：
     * <ol>
     *   <li>AI 给的 {@code emotion} 命中「强情绪集合」（默认 激动/害羞/生气/慌张/难过/开心/震惊/俏皮）→ 算</li>
     *   <li>否则看文本里的强信号：连续感叹号、拟声/感叹词（哈哈、呜呜、哇、啊啊、讨厌…）→ 算</li>
     *   <li>都不满足（认真、怀疑、困惑、无语、温柔、平静这类）→ 不算，这条不发表情</li>
     * </ol>
     * 强情绪集合可以在配置里改（{@code qq.emoji-strong-emotions}）。
     */
    public boolean isStrongEmotion(String emotion, String replyText) {
        java.util.Set<String> strong = config.getEmojiStrongEmotions();
        String key = matchEmotion(emotion);
        if (key != null && (strong.isEmpty() || strong.contains(key))) return true;

        String text = replyText == null ? "" : replyText;
        if (strongMarks(text)) return true;

        // AI 没给 emotion 时，按文本反推的情绪也算一次判断
        String inferred = inferFromText(text);
        return inferred != null && (strong.isEmpty() || strong.contains(inferred));
    }

    /** 文本里的"强情绪信号" */
    private static boolean strongMarks(String text) {
        if (text.isEmpty()) return false;
        int bangs = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '！' || c == '!') bangs++;
        }
        if (bangs >= 2) return true;
        return text.matches("(?s).*(哈哈|嘿嘿|呜呜|啊啊|哇|诶诶|不要|讨厌|救命|太好了|好棒|气死|烦死|吓死|害羞|脸红).*");
    }

    public int size() {
        return labelByFile.size();
    }

    /** 标注表（自检接口用，方便核对每张图到底是什么表情） */
    public Map<String, String> labels() {
        return new LinkedHashMap<>(labelByFile);
    }

    public void countSend() {
        sentCount.incrementAndGet();
    }

    public long getSentCount() {
        return sentCount.get();
    }

    public boolean isEnabled() {
        return config.isEmojiEnabled();
    }
    // ===== 内部 =====

    /** 把 AI 给的情绪词对齐到标注表里的标签（先精确、再包含） */
    private String matchEmotion(String emotion) {
        if (emotion == null) return null;
        String e = emotion.trim().replaceAll("[\\s，,。.、~～!！?？]", "");
        if (e.isEmpty()) return null;
        if (byEmotion.containsKey(e)) return e;
        for (String key : byEmotion.keySet()) {
            if (e.contains(key) || key.contains(e)) return key;
        }
        return null;
    }

    private String inferFromText(String text) {
        if (text == null || text.isEmpty()) return null;
        for (String[] pair : KEYWORDS) {
            if (text.matches("(?s).*(" + pair[1] + ").*")) return pair[0];
        }
        return null;
    }
}
