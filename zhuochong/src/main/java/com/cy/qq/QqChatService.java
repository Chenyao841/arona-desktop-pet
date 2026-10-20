package com.cy.qq;

import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * QQ ↔ 桌宠大脑（{@link DeepSeekService}）。
 *
 * <p>P4 起拆成两半，「想」和「说」解耦：
 * <ul>
 *   <li>{@link #thinkGroup}：只要结果（文本 + 情绪），在后台线程跑，不发消息</li>
 *   <li>{@link #deliver}：到点后真正发出去（分段文字 + 表情包 + 记忆计数）</li>
 * </ul>
 * 这样 {@link QqReplyQueue} 就能实现「想得再快也要等固定延迟」「思考超过延迟就立刻发」。
 *
 * <p>三个渠道各走各的上下文，互不污染：
 * <ul>
 *   <li>群聊：{@code sessionId = qq_g{群号}}、{@code userOperation = qq_chat}、
 *       记忆命名空间 {@code 阿罗娜-群{群号}}</li>
 *   <li>私聊（管理员）：{@code sessionId = qq_p{QQ号}}、记忆 {@code 阿罗娜-私聊{QQ号}}</li>
 * </ul>
 */
@Service
public class QqChatService {

    /** 兜底文案（模型挂了 / 没配 Key 时别让群里掉地上） */
    private static final String FALLBACK = "唔……我这会儿有点走神，老师再说一遍？";

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private QqReplyService replyService;

    @Autowired
    private QqImageService imageService;

    @Autowired
    private QqEmojiService emojiService;

    @Autowired
    private QqConfig config;

    @Autowired
    private QqMemoryService memoryService;

    @Autowired
    private QqUsageService usage;

    @Autowired
    private QqTuning tuning;

    @Autowired
    private QqRuntimeState runtime;

    // ===== 思考 =====

    /**
     * 群聊：问模型要一句回复（不发出去）。
     *
     * @param imageUrls 这条消息里的图片（可为空）
     * @param fromAdmin 发话人是不是管理员（是 → 用 qq_chat_admin，称呼「管理员」）
     */
    public QqOutcome thinkGroup(long groupId, long userId, String nickname, String text,
                                List<String> imageUrls, boolean fromAdmin) {
        String speaker = (nickname == null || nickname.trim().isEmpty()) ? ("QQ" + userId) : nickname.trim();
        usage.beginGroup(groupId);
        try {
            String vision = describeImages(imageUrls);
            String userMessage = buildUserMessage(groupId, userId, speaker, text, vision, imageUrls, fromAdmin);
            // ★ 把图片描述一起入库（ai_history.user_text）：否则下一轮问"图里你长什么样"，
            //   模型看不到任何图片信息（历史里只有原文，往往是空的）。
            String storeText = buildStoreText(text, vision);
            DeepSeekService.AiResult r = deepSeekService.chat(
                    userMessage, storeText, groupSessionId(groupId),
                    fromAdmin ? "qq_chat_admin" : "qq_chat", null, false,
                    memoryService.characterFor(groupId, userId));
            String out = (r != null && r.boat_text != null) ? r.boat_text.trim() : "";
            if (out.isEmpty()) out = FALLBACK;
            return new QqOutcome(out, r == null ? null : r.emotion);
        } catch (Exception e) {
            System.out.println("[QQ] 调模型失败: " + e);
            return new QqOutcome(FALLBACK, null, true);
        } finally {
            usage.end();
        }
    }

    /**
     * 私聊（管理员闲聊）：独立会话 + 独立记忆，语气可以比群里更放松。
     */
    public QqOutcome thinkPrivate(long userId, String nickname, String text, List<String> imageUrls) {
        // 私聊的消耗记在负数群号下：不计入"群排行"，但仍算进每日额度
        usage.beginGroup(-userId);
        try {
            String vision = describeImages(imageUrls);
            boolean hasImage = imageUrls != null && !imageUrls.isEmpty();
            StringBuilder um = new StringBuilder();
            um.append("[QQ私聊] ").append(nickname).append("（QQ ").append(userId)
                    .append("，是你的管理员，请称呼他「管理员」）对你说");
            if (!vision.isEmpty()) {
                um.append("，并发来图片（你看到的内容：").append(vision).append("）");
            } else if (hasImage) {
                um.append("，并发来一张图片，但你这次没拿到画面内容（不要凭空描述画面，自然地说一句没看清、让他说说）");
            }
            if (text != null && !text.trim().isEmpty()) um.append("：").append(text);
            else um.append("（没写字）");
            String storeText = buildStoreText(text, vision);
            DeepSeekService.AiResult r = deepSeekService.chat(
                    um.toString(), storeText, privateSessionId(userId), "qq_chat_admin", null, false,
                    "阿罗娜-私聊" + userId);
            String out = (r != null && r.boat_text != null) ? r.boat_text.trim() : "";
            if (out.isEmpty()) out = FALLBACK;
            return new QqOutcome(out, r == null ? null : r.emotion);
        } catch (Exception e) {
            System.out.println("[QQ] 私聊调模型失败: " + e);
            return new QqOutcome(FALLBACK, null, true);
        } finally {
            usage.end();
        }
    }

    // ===== 发送 =====

    /**
     * 到点后真正发出去。
     *
     * @param privateChat true = 私聊（发给 userId）；false = 群聊
     * @param targetId    群聊时是群号；私聊时是 QQ 号
     */
    public void deliver(boolean privateChat, long targetId, long userId, QqOutcome out, long messageId) {
        if (out == null || out.text == null || out.text.isEmpty()) return;
        // 引用回复：带上"在回哪条消息"（管理员可 /quote 关掉）
        long replyTo = tuning.quoteReply() ? messageId : 0L;
        if (privateChat) {
            replyService.replyPrivate(targetId, out.text, replyTo);
            return;
        }
        // 1) 文字（分段 + 随机间隔，可带引用）
        replyService.reply(targetId, out.text, replyTo);
        // 2) 表情包：只有"情绪变化较大 + 已隔够条数"才发（本地固定回复一律不发表情）
        QqRuntimeState.GroupState state = runtime.touch(targetId);
        long replyIndex = state.repliesSinceEmoji.incrementAndGet();
        if (!out.localOnly) sendSticker(state, out.emotion, out.text, replyIndex);
        // 3) 记忆计数：到阈值就后台提炼这个群的长期记忆
        memoryService.afterMessage(targetId, userId);
    }

    /** 会话维度：整群一个 */
    public static String groupSessionId(long groupId) {
        return "qq_g" + groupId;
    }

    /** 私聊会话：每人一个 */
    public static String privateSessionId(long userId) {
        return "qq_p" + userId;
    }

    // ===== 内部 =====

    /** 入库存什么：原文 + 〔图片：看到的描述〕，让后续轮次还记得"他发过这张图" */
    private String buildStoreText(String text, String vision) {
        String t = text == null ? "" : text.trim();
        if (vision == null || vision.trim().isEmpty()) return t;
        String desc = vision.trim();
        if (desc.length() > 100) desc = desc.substring(0, 100) + "…";
        String tag = "〔图片：" + desc + "〕";
        return t.isEmpty() ? tag : t + " " + tag;
    }

    /** 逐张看图；全部失败返回空串。单张失败不影响其它张（图片处理可能抛 Error，例如无 X11 的 AWT 初始化） */
    private String describeImages(List<String> imageUrls) {
        if (imageUrls == null || imageUrls.isEmpty()) return "";
        if (!config.isVisionEnabled()) {
            System.out.println("[QQ] 收到 " + imageUrls.size() + " 张图，但图片识别已关闭（ZC_QQ_VISION=false）");
            return "";
        }
        StringBuilder sb = new StringBuilder();
        int seen = 0;
        for (String url : imageUrls) {
            if (seen >= config.getVisionMaxImages()) {
                System.out.println("[QQ] 图片超过 " + config.getVisionMaxImages() + " 张，其余忽略");
                break;
            }
            String desc = null;
            try {
                desc = imageService.describe(url);
            } catch (Throwable t) {
                // AWTError 这类 Error 也要接住，否则整条回复会直接变成兜底文案
                System.out.println("[QQ] 图片处理异常（" + t.getClass().getSimpleName() + "）: " + t.getMessage());
            }
            if (desc != null && !desc.isEmpty()) {
                seen++;
                sb.append(seen == 1 ? "" : "；").append("第").append(seen).append("张：").append(desc);
            }
        }
        return sb.toString();
    }

    private String buildUserMessage(long groupId, long userId, String speaker, String text, String vision,
                                    List<String> imageUrls, boolean fromAdmin) {
        StringBuilder m = new StringBuilder();
        m.append("[QQ群 ").append(groupId).append("] ").append(speaker)
                .append("（QQ ").append(userId).append(fromAdmin ? "，是你的管理员" : "").append("）@你");
        boolean hasText = text != null && !text.trim().isEmpty();
        boolean hasImage = imageUrls != null && !imageUrls.isEmpty();
        if (!vision.isEmpty()) {
            m.append("，并发来图片（你看到的内容：").append(vision).append("）");
            if (hasText) m.append("，说：").append(text);
            else m.append("，没有配文字");
        } else if (hasImage) {
            // 有图但没拿到描述：明确告诉模型，别让它以为"什么都没发"
            m.append("，并发来一张图片，但你这次没拿到画面内容（不要凭空描述画面，自然地说一句没看清、请他再说说）");
            if (hasText) m.append("，说：").append(text);
            else m.append("，没有配文字");
        } else {
            if (hasText) m.append(" 说：").append(text);
            else m.append("（只 @ 了你，没说话）");
        }
        return m.toString();
    }

    /**
     * 表情包发送闸门（P4）：<b>情绪变化较大</b> + <b>中间已隔够条数</b> 才发。
     *
     * @param replyIndex 这个群自上次发图以来的第几条文字回复（1 = 刚发过图的下一句）
     */
    private void sendSticker(QqRuntimeState.GroupState state, String emotion, String replyText, long replyIndex) {
        if (!tuning.emojiEnabled()) return;

        // 闸门 1：间隔冷却（默认中间至少隔 5 条回复；从没发过图就不受这条限制）
        int gap = tuning.emojiMinGap();
        if (state.emojiSentBefore && replyIndex <= gap) {
            System.out.println("[QQ] 表情包跳过：距上次发图只隔了 " + (replyIndex - 1) + " 条回复（需要 " + gap + " 条）");
            return;
        }
        // 闸门 2：情绪够不够大
        if (!emojiService.isStrongEmotion(emotion, replyText)) {
            System.out.println("[QQ] 表情包跳过：这条情绪偏平（emotion=" + emotion + "）");
            return;
        }
        // 闸门 3（可选）：额外随机抑制
        int p = config.getEmojiProbability();
        if (p <= 0) return;
        if (p < 100 && ThreadLocalRandom.current().nextInt(100) >= p) {
            System.out.println("[QQ] 表情包跳过：概率抑制（" + p + "%）");
            return;
        }

        String file = emojiService.pickSticker(emotion, replyText);
        if (file == null) return;
        String base64 = emojiService.base64Of(file);
        if (base64 == null) return;
        System.out.println("[QQ] 表情包: " + file + "（" + emojiService.labelOf(file) + "）"
                + (emotion == null || emotion.isEmpty() ? " ← 情绪由文本推断" : " ← emotion=" + emotion)
                + "，第 " + replyIndex + " 条回复");
        if (replyService.sendSticker(state.groupId, base64)) {
            emojiService.countSend();
            state.emojiSentBefore = true;
            state.repliesSinceEmoji.set(0); // 发了图就重新计数
        }
    }
}
