package com.cy.qq;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 事件解析 + 过滤 + 频控 + 分发（P0 的「大脑皮层」）。
 *
 * <p>进度文档 P0 的三条硬规则，都在这一个类里：
 * <ol>
 *   <li><b>只响应 @ 自己</b>的群消息（{@code message.group} 里出现 {@code at.qq == self_id}）</li>
 *   <li><b>同一人 60s 冷却</b>（{@code qq.per-user-cooldown-sec}），冷却期内静默忽略——不回「别刷屏」更省事</li>
 *   <li><b>指令优先</b>：先查 {@code command_lib}，命中就直接回本地文案，不花 token</li>
 * </ol>
 *
 * <p>事件来源 {@link NapCatWsClient}（NapCat 正向 WS 推过来的 OneBot 11 事件）。
 * 解析的字段：{@code raw_message}、{@code message}（array）、{@code sender}。
 */
@Component
public class QqEventRouter {

    /** 单条群消息最长入参（防止有人粘贴一本书烧 token） */
    private static final int MAX_INPUT_CHARS = 500;

    @Autowired
    private QqConfig config;

    @Autowired
    private NapCatWsClient client;

    @Autowired
    private QqCommandService commandService;

    @Autowired
    private QqChatService chatService;

    @Autowired
    private QqReplyService replyService;

    @Autowired
    private QqAdminService adminService;

    @Autowired
    private QqRuntimeState runtime;

    @Autowired
    private QqUsageService usage;

    @Autowired
    private QqReplyQueue replyQueue;

    @Autowired
    private QqBlacklistService blacklist;

    @Autowired
    private QqTuning tuning;

    /** 冷却表：key = "群号_QQ号"，value = 上次响应时间戳 */
    private final Map<String, Long> lastReplyAt = new ConcurrentHashMap<>();

    private final AtomicLong handledCount = new AtomicLong();
    private final AtomicLong ignoredCount = new AtomicLong();
    private volatile long lastGroupMessageAt = 0L;

    @PostConstruct
    void register() {
        client.setEventListener(this::onEvent);
        client.setStateListener(up -> System.out.println("[QQ] 连接状态: " + (up ? "在线 ✓" : "离线 ✗")));
    }

    // ===== 入口 =====

    private void onEvent(JsonNode ev) {
        String postType = ev.path("post_type").asText("");
        switch (postType) {
            case "message":
                handleMessage(ev);
                break;
            case "message_sent":
                // 自己发出去的消息回执，不处理
                break;
            case "meta_event":
                // lifecycle / heartbeat：连接还活着，交给 NapCatWsClient 的探活逻辑
                break;
            case "notice":
            case "request":
                // 进群/退群/加好友等：P0 不处理
                break;
            default:
                break;
        }
    }

    private void handleMessage(JsonNode ev) {
        String messageType = ev.path("message_type").asText("");
        long selfId = config.getSelfId();
        long userId = ev.path("user_id").asLong(0);

        if (selfId > 0 && userId == selfId) return; // 自己的消息

        if ("group".equals(messageType)) {
            long groupId = ev.path("group_id").asLong(0);
            if (groupId <= 0) return;
            handleGroupMessage(ev, groupId, userId);
        } else if ("private".equals(messageType)) {
            handlePrivateMessage(ev, userId);
        }
    }

    /**
     * 私聊（P3/P4）：<b>只接待管理员</b>，其它人一律不回（只写一行日志，免得被陌生人当客服刷）。
     *
     * <p>管理员的私聊分两种走法：
     * <ul>
     *   <li>以 {@code /} 开头且是管理指令 → 立刻执行、立刻回（不排队不加延迟）</li>
     *   <li>其它内容 → 当作正经闲聊，走 AI（独立会话 {@code qq_p{QQ号}} + 独立记忆
     *       {@code 阿罗娜-私聊{QQ号}}），节奏与群里一致（固定延迟、可被引用）</li>
     * </ul>
     */
    private void handlePrivateMessage(JsonNode ev, long userId) {
        long messageId = parseMessageId(ev);
        JsonNode sender = ev.path("sender");
        String nickname = sender.path("nickname").asText("QQ" + userId);

        String text;
        List<String> imageUrls = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        JsonNode message = ev.get("message");
        if (message != null && message.isArray()) {
            for (JsonNode seg : message) {
                String type = seg.path("type").asText("");
                JsonNode data = seg.path("data");
                if ("text".equals(type)) {
                    body.append(data.path("text").asText(""));
                } else if ("image".equals(type)) {
                    body.append("[图片]");
                    String url = data.path("url").asText("");
                    if (url.isEmpty()) url = data.path("file").asText("");
                    if (!url.isEmpty()) imageUrls.add(url);
                }
            }
            text = body.toString().trim();
        } else {
            text = stripCqCodes(ev.path("raw_message").asText("")).trim();
        }
        String raw = ev.path("raw_message").asText("");

        if (!adminService.canPrivateChat(userId)) {
            ignoredCount.incrementAndGet();
            System.out.println("[QQ] 私聊（非管理员，不回复）QQ " + userId + ": " + head(raw));
            return;
        }

        // 1) 管理指令：立刻执行（管理员要求"快捷调整"，不能等 3s）
        String reply = adminService.tryHandle(raw, 0L, userId);
        if (reply != null) {
            replyService.replyPrivate(userId, reply, tuning.quoteReply() ? messageId : 0L);
            return;
        }

        // 2) 闲聊：走 AI，节奏与群里一致
        System.out.println("[QQ] 私聊（管理员 " + nickname + "）闲聊: " + head(text));
        if (text.isEmpty() && imageUrls.isEmpty()) {
            replyService.replyPrivate(userId, "嗯？叫我做什么呀～", tuning.quoteReply() ? messageId : 0L);
            return;
        }
        if (usage.isQuotaExceeded()) {
            replyService.replyPrivate(userId, config.getQuotaReply(), tuning.quoteReply() ? messageId : 0L);
            return;
        }
        final String fText = text;
        final List<String> fImages = imageUrls;
        replyQueue.submitPrivate(userId, nickname, messageId,
                () -> chatService.thinkPrivate(userId, nickname, fText, fImages));
    }

    private void handleGroupMessage(JsonNode ev, long groupId, long userId) {
        lastGroupMessageAt = System.currentTimeMillis();

        // P3：登记群运行态（`/groups` 就是看这张表）
        QqRuntimeState.GroupState state = runtime.touch(groupId);
        state.messageCount.incrementAndGet();
        state.lastMessageAt = System.currentTimeMillis();
        if (state.messageCount.get() == 1) {
            // 第一次见到这个群：顺手问一下群名，让 /groups 好看一点（失败无所谓）
            try {
                adminService.enrichGroupName(groupId);
            } catch (Exception ignored) {
            }
        }

        if (!allowedGroup(groupId)) {
            ignoredCount.incrementAndGet();
            System.out.println("[QQ] 群 " + groupId + " 不在白名单，忽略");
            return;
        }

        long selfId = config.getSelfId();
        if (selfId <= 0) {
            System.out.println("[QQ] 还不知道自己的 QQ 号（ZC_QQ_SELF_ID 未配且 get_login_info 未返回），无法判断 @，忽略本条");
            ignoredCount.incrementAndGet();
            return;
        }

        // ===== 解析消息体 =====
        boolean atMe = false;
        StringBuilder body = new StringBuilder();
        List<String> imageUrls = new ArrayList<>();
        String rawMessage = ev.path("raw_message").asText("");
        JsonNode message = ev.get("message");
        if (message != null && message.isArray()) {
            for (JsonNode seg : message) {
                String type = seg.path("type").asText("");
                JsonNode data = seg.path("data");
                switch (type) {
                    case "at": {
                        String qq = data.path("qq").asText("");
                        if (qq.equals(String.valueOf(selfId))) {
                            atMe = true;
                        } else if (!qq.isEmpty() && !"all".equals(qq)) {
                            String name = data.path("name").asText(qq);
                            body.append('@').append(name).append(' ');
                        }
                        break;
                    }
                    case "text":
                        body.append(data.path("text").asText(""));
                        break;
                    case "image":
                        body.append("[图片]");
                        // P1：图片识别要的是 url（NapCat 给的图床/本地 HTTP 地址）
                        String imgUrl = data.path("url").asText("");
                        if (imgUrl.isEmpty()) imgUrl = data.path("file").asText("");
                        if (!imgUrl.isEmpty()) imageUrls.add(imgUrl);
                        break;
                    case "face":
                        body.append("[表情]");
                        break;
                    case "record":
                        body.append("[语音]");
                        break;
                    case "reply":
                        break; // 引用的原文不参与语义
                    default:
                        break;
                }
            }
        } else {
            // 兜底：NapCat 配成 messagePostFormat=string 时只有 CQ 码字符串
            String atTag = "[CQ:at,qq=" + selfId + "]";
            atMe = rawMessage.contains(atTag);
            body.append(stripCqCodes(rawMessage.replace(atTag, "")));
        }

        if (!atMe && !config.isTriggerAll()) {
            ignoredCount.incrementAndGet();
            return; // 只响应 @ 自己
        }

        String text = body.toString().trim().replaceAll("\\s{2,}", " ");
        if (text.length() > MAX_INPUT_CHARS) text = text.substring(0, MAX_INPUT_CHARS);

        JsonNode sender = ev.path("sender");
        String nickname = sender.path("card").asText("");
        if (nickname.trim().isEmpty()) nickname = sender.path("nickname").asText("");
        if (nickname.trim().isEmpty()) nickname = "QQ" + userId;
        long messageId = parseMessageId(ev);

        // ===== P4 黑名单：被拉黑的人 @ 一律静默忽略（管理员不算，免得把自己锁死） =====
        if (blacklist.isBanned(userId) && !adminService.isAdmin(userId)) {
            ignoredCount.incrementAndGet();
            System.out.println("[QQ] 黑名单用户 @" + nickname + "（QQ " + userId + "）已忽略，原因："
                    + blacklist.reasonOf(userId));
            return;
        }

        // ===== P3 群级开关：管理员关掉或静音的群，连管理员指令都不回（除 /on 外），彻底安静 =====
        if (state.disabled || state.isMuted()) {
            String adm = adminService.isAdmin(userId) ? adminService.tryHandle(text, groupId, userId) : null;
            if (adm != null) {
                replyService.reply(groupId, adm, quote(messageId));
                state.replyCount.incrementAndGet();
            } else {
                ignoredCount.incrementAndGet();
                System.out.println("[QQ] 群 " + groupId + (state.disabled ? " 已被关闭" : " 静音中") + "，忽略: " + head(text));
            }
            return;
        }

        // ===== 管理指令优先：立刻回，不排队、不加延迟（管理员要"快捷"） =====
        String adminReply = adminService.tryHandle(text, groupId, userId);
        if (adminReply != null) {
            state.replyCount.incrementAndGet();
            handledCount.incrementAndGet();
            replyService.reply(groupId, adminReply, quote(messageId));
            return;
        }

        // ===== 冷却：同一人 N 秒内只理一次（管理员可 /cooldown 0 关掉） =====
        String cdKey = groupId + "_" + userId;
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0, tuning.cooldownSec()) * 1000L;
        Long last = lastReplyAt.get(cdKey);
        if (last != null && cooldownMs > 0 && now - last < cooldownMs) {
            long leftSec = (cooldownMs - (now - last)) / 1000 + 1;
            ignoredCount.incrementAndGet();
            System.out.println("[QQ] 冷却中（" + nickname + " 还要等 " + leftSec + "s），忽略: " + head(text));
            return;
        }

        // ===== 指令优先（命中就不花 token） =====
        String commandReply = commandService.tryHandle(text);
        if (commandReply != null) {
            markCooldown(cdKey, now);
            handledCount.incrementAndGet();
            state.replyCount.incrementAndGet();
            replyService.reply(groupId, commandReply, quote(messageId));
            return;
        }

        markCooldown(cdKey, now);
        state.replyCount.incrementAndGet();

        // ===== 空 @：固定卖萌（也走延迟，别像机器人秒回） =====
        if (text.isEmpty() && imageUrls.isEmpty()) {
            handledCount.incrementAndGet();
            final long mid = messageId;
            replyQueue.submitGroup(groupId, userId, nickname, mid,
                    () -> new QqOutcome("嗯？叫我做什么呀～", null, true));
            return;
        }

        // ===== 每日额度闸门：超了就回固定说明，不再调模型 =====
        if (usage.isQuotaExceeded()) {
            ignoredCount.incrementAndGet();
            System.out.println("[QQ] 今日 token 已达上限 " + usage.limit() + "，跳过模型调用（群 " + groupId + "）");
            final long mid = messageId;
            replyQueue.submitGroup(groupId, userId, nickname, mid,
                    () -> new QqOutcome(config.getQuotaReply(), null, true));
            return;
        }

        // ===== 走 AI：思考立刻开始，发送交给 QqReplyQueue（固定延迟 + 顺序 + 去抖） =====
        handledCount.incrementAndGet();
        final boolean fromAdmin = adminService.isAdmin(userId);
        System.out.println("[QQ] 收到 @" + nickname + "（QQ " + userId + (fromAdmin ? "，管理员" : "")
                + " / 群 " + groupId + "）: " + head(text)
                + (imageUrls.isEmpty() ? "" : "  [含 " + imageUrls.size() + " 张图]")
                + "（" + tuning.replyDelaySec() + "s 后回复）");
        final String fText = text;
        final List<String> fImages = imageUrls;
        final String fNick = nickname;
        replyQueue.submitGroup(groupId, userId, nickname, messageId,
                () -> chatService.thinkGroup(groupId, userId, fNick, fText, fImages, fromAdmin));
    }

    // ===== 小工具 =====

    private boolean allowedGroup(long groupId) {
        java.util.Set<Long> white = config.getGroupWhitelist();
        return white.isEmpty() || white.contains(groupId);
    }

    /** 引用回复的目标 id（管理员可 /quote 关掉） */
    private long quote(long messageId) {
        return tuning.quoteReply() ? messageId : 0L;
    }

    /** 消息 id 可能是数字也可能是字符串，统一成长整型；拿不到返回 0 */
    private static long parseMessageId(JsonNode ev) {
        JsonNode n = ev.get("message_id");
        if (n == null || n.isNull()) return 0L;
        if (n.isNumber()) return n.asLong(0L);
        try {
            return Long.parseLong(n.asText("0"));
        } catch (Exception e) {
            return 0L;
        }
    }

    private void markCooldown(String key, long now) {
        lastReplyAt.put(key, now);
        // 轻量清理：表大了就顺手把过期的丢掉，避免常驻内存缓慢增长
        if (lastReplyAt.size() > 2000) {
            long expireMs = Math.max(1, config.getPerUserCooldownSec()) * 1000L * 10;
            lastReplyAt.entrySet().removeIf(e -> now - e.getValue() > expireMs);
        }
    }

    /** 从 CQ 码字符串里去掉所有 CQ 标签，留下纯文本 */
    private static String stripCqCodes(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("\\[CQ:[^]]*]", "");
    }

    private static String head(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ");
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }

    // ===== 状态（/qq/status 用） =====

    public long getHandledCount() {
        return handledCount.get();
    }

    public long getIgnoredCount() {
        return ignoredCount.get();
    }

    public long getLastGroupMessageAt() {
        return lastGroupMessageAt;
    }

    public int getCooldownSize() {
        return lastReplyAt.size();
    }
}
