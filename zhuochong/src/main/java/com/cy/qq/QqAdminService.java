package com.cy.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员体系 + 管理指令（P3 新增需求）。
 *
 * <p>两条硬规则：
 * <ol>
 *   <li><b>私聊只接待管理员</b>：非管理员的私聊一律不回（只写日志），避免被陌生人当客服刷。</li>
 *   <li><b>管理指令要白名单</b>：只认 {@code ZC_QQ_ADMIN_QQ} 里的 QQ 号，
 *       管理员在群里/私聊都能用；普通群友发同样的指令会被挡回去，不花钱也不泄露数据。</li>
 * </ol>
 *
 * <p>指令一览（前缀 {@code /}，也接受 {@code ／} 与中文别名）：
 * <pre>
 * /help                指令清单
 * /status              运行状态（连接、群数、今日 token、额度）
 * /groups              运行中的群聊（群号/消息数/回复数/最后活动/静音/今日消耗）
 * /usage               今日消耗明细（按群、按 chat/vision）+ 最近 7 天
 * /limit 200000        设置每日 token 上限（0 或 off = 不限）
 * /on 123456           开启某群响应    /off 123456  关闭
 * /mute 123456 30      静音该群 30 分钟   /unmute 123456  解除
 * /reset 123456        清空该群会话历史（上下文）
 * /forget 123456       清空该群长期记忆
 * /remember 123456     立刻提炼该群长期记忆
 * /emoji               表情包素材概况
 * </pre>
 */
@Service
public class QqAdminService {

    private static final int MAX_LINES = 40;

    @Autowired
    private QqConfig config;

    @Autowired
    private QqRuntimeState runtime;

    @Autowired
    private QqUsageService usage;

    @Autowired
    private QqMemoryService memory;

    @Autowired
    private QqEmojiService emoji;

    @Autowired
    private NapCatWsClient client;

    @Autowired
    private QqTuning tuning;

    @Autowired
    private QqBlacklistService blacklist;

    @Autowired
    private QqReplyQueue replyQueue;

    // ===== 鉴权 =====

    public boolean isAdmin(long userId) {
        return userId > 0 && config.getAdminQqs().contains(userId);
    }

    /** 私聊是否允许接待这个 QQ（P3：先只放管理员） */
    public boolean canPrivateChat(long userId) {
        return config.isAdminOnlyPrivate() ? isAdmin(userId) : true;
    }

    // ===== 入口 =====

    /**
     * 尝试按管理指令处理。
     *
     * @param text      收到的原始文本（含 / 前缀）
     * @param fromGroup 来源群号；0 = 私聊
     * @param userId    发起人 QQ
     * @return 要回复的文本；不是管理指令（或没权限）返回 null
     */
    public String tryHandle(String text, long fromGroup, long userId) {
        if (text == null) return null;
        String t = text.trim();
        if (!t.startsWith("/") && !t.startsWith("／")) return null;
        String body = t.substring(1).trim();
        if (body.isEmpty()) return null;
        String[] parts = body.split("[\\s，,]+");
        String cmd = parts[0].toLowerCase();
        if (!isAdminCommand(cmd)) return null;
        if (!isAdmin(userId)) {
            System.out.println("[QQ] 非管理员 QQ " + userId + " 试图使用管理指令 /" + cmd + "，已挡下");
            return "你不是这个机器人的管理员哦～";
        }
        System.out.println("[QQ] 管理指令 /" + cmd + "（来自 QQ " + userId + "，"
                + (fromGroup > 0 ? "群 " + fromGroup : "私聊") + "）");
        try {
            return dispatch(cmd, parts, fromGroup, userId);
        } catch (Exception e) {
            System.out.println("[QQ] 管理指令执行失败: " + e);
            return "指令执行出错了：" + e.getMessage();
        }
    }

    private boolean isAdminCommand(String cmd) {
        switch (cmd) {
            case "help": case "帮助": case "菜单":
            case "status": case "状态":
            case "groups": case "群": case "群聊": case "群列表":
            case "usage": case "消耗": case "token": case "用量":
            case "limit": case "限额": case "额度":
            case "on": case "开启":
            case "off": case "关闭":
            case "mute": case "禁言": case "静音":
            case "unmute": case "解禁": case "解除静音":
            case "reset": case "重置":
            case "forget": case "忘记": case "清空记忆":
            case "remember": case "记住": case "提炼":
            case "emoji": case "表情":
            case "delay": case "延迟":
            case "cooldown": case "冷却":
            case "quote": case "引用":
            case "ban": case "拉黑":
            case "unban": case "解黑": case "解除拉黑":
            case "blacklist": case "黑名单":
                return true;
            default:
                return false;
        }
    }

    private String dispatch(String cmd, String[] parts, long fromGroup, long userId) {
        switch (cmd) {
            case "help": case "帮助": case "菜单":
                return helpText();

            case "status": case "状态":
                return status();

            case "groups": case "群": case "群聊": case "群列表":
                return groups();

            case "usage": case "消耗": case "token": case "用量":
                return usageReport();

            case "limit": case "限额": case "额度": {
                if (parts.length < 2) return "当前每日上限：" + humanLimit() + "\n用法：/limit 200000（0 或 off = 不限）";
                String raw = parts[1].toLowerCase();
                if ("off".equals(raw) || "0".equals(raw) || "不限".equals(raw)) {
                    usage.setLimit(0);
                    return "已将每日 token 上限设为【不限】";
                }
                long n = parseLong(raw, -1);
                if (n <= 0) return "数字看不懂：/" + cmd + " " + parts[1] + "，例：/limit 200000";
                usage.setLimit(n);
                return "已将每日 token 上限设为 " + n + "（今日已用 " + usage.getTodayTokens() + "）";
            }

            case "on": case "开启": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/on <群号>";
                runtime.setDisabled(target, false);
                return "已开启群 " + target + " 的响应";
            }

            case "off": case "关闭": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/off <群号>";
                runtime.setDisabled(target, true);
                return "已关闭群 " + target + " 的响应（只 @ 也不理，/on 可恢复）";
            }

            case "mute": case "禁言": case "静音": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/mute <群号> [分钟]，默认 30 分钟";
                long minutes = parts.length >= 3 ? parseLong(parts[2], 30) : 30;
                if (minutes <= 0) minutes = 30;
                runtime.mute(target, minutes);
                return "已让群 " + target + " 安静 " + minutes + " 分钟（期间不发言，/unmute 可提前解除）";
            }

            case "unmute": case "解禁": case "解除静音": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/unmute <群号>";
                runtime.unmute(target);
                return "已解除群 " + target + " 的静音";
            }

            case "reset": case "重置": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/reset <群号>";
                int cleared = memory.clearSession(target);
                return "已清空群 " + target + " 的会话上下文（" + cleared + " 条），长期记忆还在；"
                        + "要一起清就再发 /forget " + target;
            }

            case "forget": case "忘记": case "清空记忆": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/forget <群号>";
                if ("off".equalsIgnoreCase(config.getMemoryScope())) {
                    return "记忆隔离当前是关闭状态（qq.memory-scope=off），没有可清的群记忆";
                }
                memory.forget(target);
                return "已清空群 " + target + " 的长期记忆";
            }

            case "remember": case "记住": case "提炼": {
                long target = argGroup(parts, fromGroup);
                if (target <= 0) return "用法：/remember <群号>";
                memory.consolidate(target);
                return "已提炼群 " + target + " 的长期记忆，现有 " + memory.countOf(target) + " 条";
            }

            case "emoji": case "表情":
                return emojiReport(parts);

            case "delay": case "延迟": {
                if (parts.length < 2) return "当前固定回复延迟：" + tuning.replyDelaySec() + "s\n用法：/delay 3（0 = 立刻回）";
                int sec = (int) parseLong(parts[1], -1);
                if (sec < 0 || sec > 120) return "范围 0~120 秒，例：/delay 3";
                tuning.setReplyDelaySec(sec);
                return "固定回复延迟已设为 " + sec + "s" + (sec == 0 ? "（立刻回）" : "");
            }

            case "cooldown": case "冷却": {
                if (parts.length < 2) {
                    int cur = tuning.cooldownSec();
                    return "当前同人冷却：" + (cur == 0 ? "关闭" : cur + "s")
                            + "\n用法：/cooldown 60 或 /cooldown 0（关闭冷却）";
                }
                String raw = parts[1].toLowerCase();
                if ("off".equals(raw) || "关闭".equals(raw)) {
                    tuning.setCooldownSec(0);
                    return "已【关闭】同人冷却（同一人可连续对话，靠 /delay 控制节奏）";
                }
                int sec = (int) parseLong(raw, -1);
                if (sec < 0 || sec > 3600) return "范围 0~3600 秒，例：/cooldown 60，/cooldown 0 关闭";
                tuning.setCooldownSec(sec);
                return sec == 0 ? "已【关闭】同人冷却" : "同人冷却已设为 " + sec + "s";
            }

            case "quote": case "引用": {
                if (parts.length < 2) return "当前引用回复：" + (tuning.quoteReply() ? "开启" : "关闭") + "\n用法：/quote on|off";
                boolean on = !("off".equalsIgnoreCase(parts[1]) || "0".equals(parts[1]) || "关闭".equals(parts[1]));
                tuning.setQuoteReply(on);
                return "引用回复已" + (on ? "开启（回复会带上原消息引用）" : "关闭");
            }

            case "ban": case "拉黑": {
                long qq = parts.length >= 2 ? parseLong(parts[1], -1) : -1;
                if (qq <= 0) return "用法：/ban <QQ号> [原因]";
                String reason = parts.length >= 3 ? String.join(" ", java.util.Arrays.copyOfRange(parts, 2, parts.length)) : "管理员拉黑";
                blacklist.ban(qq, reason);
                return "已把 QQ " + qq + " 加入黑名单（原因：" + reason + "）\n该 QQ 在所有群的 @ 都会被静默忽略";
            }

            case "unban": case "解黑": case "解除拉黑": {
                long qq = parts.length >= 2 ? parseLong(parts[1], -1) : -1;
                if (qq <= 0) return "用法：/unban <QQ号>";
                boolean removed = blacklist.unban(qq);
                return removed ? "已把 QQ " + qq + " 移出黑名单" : "QQ " + qq + " 本来就不在黑名单里";
            }

            case "blacklist": case "黑名单": {
                java.util.Map<Long, String> all = blacklist.list();
                if (all.isEmpty()) return "黑名单是空的～";
                StringBuilder sb = new StringBuilder("【黑名单】共 ").append(all.size()).append(" 人\n");
                int i = 1;
                for (java.util.Map.Entry<Long, String> e : all.entrySet()) {
                    if (i > MAX_LINES) break;
                    sb.append(i++).append(". ").append(e.getKey()).append("  ").append(e.getValue()).append('\n');
                }
                sb.append("（/unban <QQ号> 解除）");
                return sb.toString();
            }

            default:
                return null;
        }
    }

    // ===== 各指令实现 =====

    /** 指令清单（私聊兜底与 /help 共用） */
    public String helpText() {
        return "【阿罗娜 · 管理指令】"
                + "\n/status 运行状态"
                + "\n/groups 运行中的群聊"
                + "\n/usage 今日 token 消耗"
                + "\n/limit <数字|off> 每日 token 上限"
                + "\n/delay <秒> 固定回复延迟（0=立刻回）"
                + "\n/cooldown <秒|0> 同人冷却（0=关闭）"
                + "\n/quote on|off 引用回复开关"
                + "\n/on <群号> /off <群号> 开关某群响应"
                + "\n/mute <群号> [分钟] /unmute <群号> 临时安静"
                + "\n/reset <群号> 清空该群上下文"
                + "\n/forget <群号> 清空该群长期记忆"
                + "\n/remember <群号> 立刻提炼长期记忆"
                + "\n/ban <QQ> [原因] /unban <QQ> /blacklist 黑名单管理"
                + "\n/emoji [gap 5|on|off] 表情包概况与调整"
                + "\n（只有管理员 QQ 能用；私聊除指令外可以直接和我聊天）";
    }

    private String status() {
        StringBuilder sb = new StringBuilder("【运行状态】\n");
        sb.append("连接：").append(client.isConnected() ? "在线 ✓" : "离线 ✗").append('\n');
        sb.append("自身 QQ：").append(config.getSelfId() <= 0 ? "未识别" : String.valueOf(config.getSelfId())).append('\n');
        sb.append("触发方式：").append(config.getTriggerMode())
                .append("（同一人 ").append(config.getPerUserCooldownSec()).append("s 冷却）\n");
        sb.append("运行时长：").append(runtime.getUptimeMinutes()).append(" 分钟\n");
        sb.append("活跃群数：").append(runtime.groupCount()).append('\n');
        sb.append("今日消耗：").append(usage.getTodayTokens()).append(" tokens / ")
                .append(usage.getTodayCalls()).append(" 次调用\n");
        sb.append("每日上限：").append(humanLimit()).append('\n');
        sb.append("已收事件：").append(client.getEventCount())
                .append("，已回消息：").append(client.getSentCount())
                .append("，发送失败：").append(client.getSendFailCount()).append('\n');
        sb.append("重连次数：").append(client.getReconnectCount()).append('\n');
        sb.append("回复延迟：").append(tuning.replyDelaySec()).append("s，同人冷却：")
                .append(tuning.cooldownSec() == 0 ? "关闭" : tuning.cooldownSec() + "s")
                .append("，引用：").append(tuning.quoteReply() ? "开" : "关").append('\n');
        sb.append("排队中回复：").append(replyQueue.getPendingCount())
                .append(" 条（车道 ").append(replyQueue.getLaneCount()).append(" 个）\n");
        sb.append("黑名单：").append(blacklist.size()).append(" 人\n");
        sb.append("表情包：").append(tuning.emojiEnabled() ? "开" : "关")
                .append("（强情绪 + 隔 ").append(tuning.emojiMinGap()).append(" 条回复）\n");
        sb.append("记忆隔离：").append(config.getMemoryScope()).append("（").append(memoryHint()).append("）");
        return sb.toString();
    }

    private String groups() {
        List<QqRuntimeState.GroupState> list = runtime.activeGroups(MAX_LINES);
        if (list.isEmpty()) return "还没有看到任何群消息～（有人 @ 我一下就会出现）";
        Map<Long, Long> todayByGroup = new LinkedHashMap<>();
        for (Map<String, Object> row : usage.todayByGroup()) {
            Object g = row.get("groupId");
            Object t = row.get("totalTokens");
            if (g instanceof Number && t instanceof Number) {
                todayByGroup.put(((Number) g).longValue(), ((Number) t).longValue());
            }
        }
        StringBuilder sb = new StringBuilder("【运行中的群聊】共 ").append(runtime.groupCount()).append(" 个\n");
        int i = 1;
        for (QqRuntimeState.GroupState g : list) {
            sb.append(i++).append(". ").append(g.groupId);
            if (!g.groupName.isEmpty()) sb.append("《").append(g.groupName).append("》");
            sb.append("  消息 ").append(g.messageCount.get()).append(" / 回复 ").append(g.replyCount.get());
            sb.append("  最后活动 ").append(QqRuntimeState.ago(g.lastMessageAt));
            sb.append("  今日 ").append(todayByGroup.getOrDefault(g.groupId, 0L)).append(" tokens");
            if (g.disabled) sb.append("  【已关闭】");
            if (g.isMuted()) sb.append("  【静音中】");
            sb.append('\n');
        }
        sb.append("（列表上限 ").append(MAX_LINES).append(" 个）");
        return sb.toString();
    }

    private String usageReport() {
        StringBuilder sb = new StringBuilder("【token 消耗】\n");
        sb.append("今日：").append(usage.getTodayTokens()).append(" tokens / ")
                .append(usage.getTodayCalls()).append(" 次，上限 ").append(humanLimit()).append('\n');
        List<Map<String, Object>> byKind = usage.todayByKind();
        if (!byKind.isEmpty()) {
            sb.append("按类型：");
            for (Map<String, Object> row : byKind) {
                sb.append(row.get("kind")).append(' ').append(row.get("totalTokens"))
                        .append("（").append(row.get("calls")).append("次） ");
            }
            sb.append('\n');
        }
        List<Map<String, Object>> byGroup = usage.todayByGroup();
        if (!byGroup.isEmpty()) {
            sb.append("按群：\n");
            int i = 1;
            for (Map<String, Object> row : byGroup) {
                if (i > 10) break;
                sb.append("  ").append(i++).append(". 群 ").append(row.get("groupId"))
                        .append(' ').append(row.get("totalTokens")).append(" tokens（")
                        .append(row.get("calls")).append(" 次）\n");
            }
        }
        List<Map<String, Object>> days = usage.last7Days();
        if (!days.isEmpty()) {
            sb.append("最近 7 天（含桌宠端）：");
            for (Map<String, Object> row : days) {
                sb.append(row.get("usageDate")).append('=').append(row.get("totalTokens")).append("  ");
            }
            sb.append('\n');
        }
        Map<String, Object> sum = usage.summary();
        sb.append("历史累计（仅 QQ）：").append(sum.get("历史消耗")).append(" tokens");
        return sb.toString();
    }

    /**
     * 表情包概况 + 运行时可调：
     * <pre>
     * /emoji            概况（开关、间隔、强情绪集合、已发数）
     * /emoji gap 5      中间至少隔 5 条回复才允许再发
     * /emoji on|off     开关表情包
     * </pre>
     */
    private String emojiReport(String[] parts) {
        if (parts.length >= 2) {
            String sub = parts[1].toLowerCase();
            if ("on".equals(sub) || "开".equals(sub)) {
                tuning.setEmojiEnabled(true);
                return "表情包已开启（强情绪 + 隔 " + tuning.emojiMinGap() + " 条回复才发）";
            }
            if ("off".equals(sub) || "关".equals(sub)) {
                tuning.setEmojiEnabled(false);
                return "表情包已关闭（只发文字）";
            }
            if ("gap".equals(sub) || "间隔".equals(sub)) {
                if (parts.length < 3) return "当前间隔：中间至少隔 " + tuning.emojiMinGap() + " 条回复\n用法：/emoji gap 5";
                int gap = (int) parseLong(parts[2], -1);
                if (gap < 0 || gap > 200) return "范围 0~200，例：/emoji gap 5（0 = 不限间隔）";
                tuning.setEmojiMinGap(gap);
                return "表情包间隔已设为：中间至少隔 " + gap + " 条回复";
            }
            return "用法：/emoji [gap <次数> | on | off]";
        }
        return "【表情包】" + (tuning.emojiEnabled() ? "开启" : "关闭")
                + "，素材 " + emoji.size() + " 张（头部特写差分）\n"
                + "发放条件：① 回复情绪变化较大 ② 中间至少隔 " + tuning.emojiMinGap() + " 条回复\n"
                + "强情绪：激动/害羞/生气/慌张/难过/开心/震惊/俏皮（或文本里连续感叹号、拟声词）\n"
                + "本次运行已发：" + emoji.getSentCount() + " 张\n"
                + "调整：/emoji gap 5 /emoji on /emoji off\n"
                + "核对：GET /qq/emoji（标注表）";
    }

    // ===== 小工具 =====

    /** 取指令里的群号；没给就用当前群（群里发 /off 不写群号 = 操作本群） */
    private long argGroup(String[] parts, long fromGroup) {
        if (parts.length >= 2) {
            long g = parseLong(parts[1], -1);
            if (g > 0) return g;
        }
        return fromGroup;
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }

    private String humanLimit() {
        long l = usage.limit();
        return l <= 0 ? "不限" : String.valueOf(l);
    }

    private String memoryHint() {
        String scope = config.getMemoryScope();
        if ("off".equalsIgnoreCase(scope)) return "群聊与桌宠共用记忆，不推荐";
        if ("user".equalsIgnoreCase(scope)) return "每群每号一份：阿罗娜-群{群号}-QQ{QQ号}";
        return "每群一份：阿罗娜-群{群号}";
    }

    /** 给 /qq/status 用的管理面快照 */
    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("adminCount", config.getAdminQqs().size());
        m.put("adminOnlyPrivate", config.isAdminOnlyPrivate());
        m.put("memoryScope", config.getMemoryScope());
        m.put("todayTokens", usage.getTodayTokens());
        m.put("todayCalls", usage.getTodayCalls());
        m.put("dailyLimit", usage.limit());
        m.put("quotaExceeded", usage.isQuotaExceeded());
        m.put("activeGroups", runtime.groupCount());
        m.put("uptimeMinutes", runtime.getUptimeMinutes());
        m.put("replyDelaySec", tuning.replyDelaySec());
        m.put("cooldownSec", tuning.cooldownSec());
        m.put("quoteReply", tuning.quoteReply());
        m.put("blacklistSize", blacklist.size());
        m.put("pendingReplies", replyQueue.getPendingCount());
        m.put("emojiEnabled", tuning.emojiEnabled());
        m.put("emojiMinGap", tuning.emojiMinGap());
        return m;
    }

    /** 给群补一个群名（懒加载，失败就算了） */
    public void enrichGroupName(long groupId) {
        QqRuntimeState.GroupState st = runtime.get(groupId);
        if (st == null || !st.groupName.isEmpty()) return;
        if (!client.isConnected()) return;
        ObjectNode p = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        p.put("group_id", groupId);
        p.put("no_cache", false);
        JsonNode resp = client.callApi("get_group_info", p, 5000);
        if (resp == null) return;
        JsonNode data = resp.path("data");
        String name = data.path("group_name").asText("");
        if (!name.isEmpty()) {
            st.groupName = name;
            System.out.println("[QQ] 群名: " + groupId + " → " + name);
        }
    }
}
