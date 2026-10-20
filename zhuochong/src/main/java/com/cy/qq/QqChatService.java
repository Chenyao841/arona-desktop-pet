package com.cy.qq;

import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    @Autowired
    private QqImageMemory imageMemory;

    /** 首次看图的提示词（客观描述，限 90 字） */
    private static final String IMAGE_PROMPT =
            "这是 QQ 群里群友发来的一张图片。请客观、简短地描述你能看到的内容："
                    + "画面主体是什么（人物/动物/物品/截图/表情包）、主要颜色、有没有明显文字（照抄关键文字）。"
                    + "90 字以内，只讲看到的事实，不要评价、不要寒暄。"
                    + "即使不完全确定也要尽力描述；只有当整张图是纯色/全黑/完全空白时才回答「看不清」。";

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
            String sessionKey = QqImageMemory.sessionKey(false, groupId);
            String vision = describeImages(sessionKey, imageUrls);
            String reread = rereadIfAsked(sessionKey, text, imageUrls);
            String userMessage = buildUserMessage(groupId, userId, speaker, text, vision, imageUrls, fromAdmin);
            if (!reread.isEmpty()) userMessage = userMessage + "\n" + reread;
            // 图片描述一起入库：下一轮还能记得"他发过这张图"
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
            String sessionKey = QqImageMemory.sessionKey(true, userId);
            String vision = describeImages(sessionKey, imageUrls);
            String reread = rereadIfAsked(sessionKey, text, imageUrls);
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
            if (!reread.isEmpty()) um.append("\n").append(reread);
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

    /** 逐张看图（并把图留底、编号）；全部失败返回空串。单张失败不影响其它张 */
    private String describeImages(String sessionKey, List<String> imageUrls) {
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
            try {
                QqImageService.Prepared p = imageService.prepare(url);
                if (p == null) continue;
                String desc = imageService.describePrepared(p, IMAGE_PROMPT);
                // 无论有没有描述成功都留底：有描述能立刻用，没描述也还能在追问时重读
                int tag = imageMemory.remember(sessionKey, p, desc);
                if (desc != null && !desc.isEmpty()) {
                    seen++;
                    sb.append(seen == 1 ? "" : "；").append("第").append(seen).append("张〔图#")
                            .append(tag).append("〕：").append(desc);
                    System.out.println("[QQ] 图片理解〔图#" + tag + "〕: "
                            + (desc.length() > 100 ? desc.substring(0, 100) + "…" : desc));
                } else if (tag > 0) {
                    System.out.println("[QQ] 图片已留底〔图#" + tag + "〕，但这次没描述出来（追问时会重读）");
                }
            } catch (Throwable t) {
                // AWTError 这类 Error 也要接住，否则整条回复会变成兜底文案
                System.out.println("[QQ] 图片处理异常（" + t.getClass().getSimpleName() + "）: " + t.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 被追问图片细节时，拿**当前这句话**当提示词重读留底的原图。
     *
     * <p>触发条件：这条消息没带新图 + 文字里提到图/照片/那张/上面那张/#编号。
     *
     * @return 要拼进本轮上下文的补充块；没触发返回空串
     */
    private String rereadIfAsked(String sessionKey, String text, List<String> imageUrls) {
        if (!config.isImageReread() || !config.isVisionEnabled()) return "";
        if (imageUrls != null && !imageUrls.isEmpty()) return "";   // 这条本身带图，用新的就行
        if (text == null || text.trim().isEmpty()) return "";
        String t = text.trim();
        boolean mentionsImage = t.matches("(?s).*(图|照片|相片|截图|表情包|那张|这张|上面那|刚才那|#\\d+).*");
        if (!mentionsImage) return "";

        List<QqImageMemory.Stored> picks = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("#(\\d+)").matcher(t);
        if (m.find()) {
            try {
                QqImageMemory.Stored s = imageMemory.byId(sessionKey, Integer.parseInt(m.group(1)));
                if (s != null) picks.add(s);
            } catch (Exception ignored) {
            }
        }
        if (picks.isEmpty()) picks.addAll(imageMemory.recent(sessionKey, config.getImageRereadMax()));
        if (picks.isEmpty()) return "";

        StringBuilder out = new StringBuilder("〔你重新看了之前那张图，这次是按对方的问题去看的〕\n");
        String ask = "对方现在问的是：「" + t + "」。请针对这个问题观察这张图，"
                + "只回答图里确实看得到的内容（颜色、文字、姿势、物品等），看不到就说看不到。120 字以内。";
        for (QqImageMemory.Stored s : picks) {
            String desc;
            try {
                desc = imageService.describePrepared(new QqImageService.Prepared(s.base64, s.mime), ask);
            } catch (Throwable th) {
                System.out.println("[QQ] 重读图片异常: " + th);
                continue;
            }
            if (desc == null || desc.isEmpty()) continue;
            out.append("· 图#").append(s.id);
            if (s.firstDesc != null && !s.firstDesc.isEmpty()) out.append("（首次看到的是：").append(s.firstDesc).append("）");
            out.append(" → 这次细看：").append(desc).append('\n');
            System.out.println("[QQ] 重读〔图#" + s.id + "〕: " + (desc.length() > 100 ? desc.substring(0, 100) + "…" : desc));
        }
        return out.length() > 30 ? out.toString() : "";
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
