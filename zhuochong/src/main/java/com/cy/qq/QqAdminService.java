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

    @Autowired
    private QqImageStore imageStore;

    @Autowired
    private QqParrotService parrot;

    @Autowired
    private QqDefendService defend;

    @Autowired
    private com.cy.mapper.AiPromptMapper aiPromptMapper;

    @Autowired
    private com.cy.mapper.StyleExemplarMapper styleExemplarMapper;

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
            case "prompt": case "提示词":
            case "style": case "语料":
            case "note": case "笔记": case "记住这条":
            case "notes": case "记忆": case "记忆列表":
            case "parrot": case "复读": case "复读机":
                return true;
            case "defend": case "护短": case "护犊子":
                return true;
            case "nick": case "名字": case "昵称": case "改名":
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

            case "prompt": case "提示词":
                return promptCommand(parts);

            case "style": case "语料":
                return styleReport();

            case "parrot": case "复读": case "复读机":
                return parrotReport(parts);

            case "defend": case "护短": case "护犊子":
                return defendReport(parts, fromGroup);
            case "nick": case "名字": case "昵称": case "改名":
                return nickCommand(parts, fromGroup);

            case "note": case "笔记": case "记住这条":
                // /note <内容>            钉一条长期记忆（永久保留）
                // /note del <id>          删掉一条
                if (parts.length >= 3 && ("del".equalsIgnoreCase(parts[2]) || "删".equals(parts[2]) || "删除".equals(parts[2]))) {
                    int id = (int) parseLong(parts[3 - 1], -1);
                    if (id <= 0) return "用法：/note del <id>（id 用 /notes 查）";
                    boolean ok = memory.deleteNote(id);
                    return ok ? "已删除记忆 #" + id : "没找到 #" + id;
                }
                if (parts.length < 2) {
                    return "用法：\n/note 你最该记住的一句话（永久保留，自动提炼不会清掉）\n"
                            + "/notes 看当前记忆\n/note del <id> 删除";
                }
                String noteText = rest(parts, 1);
                String ch = memoryScopeCharacter(fromGroup, userId);
                if (ch == null) return "记忆隔离已关闭（qq.memory-scope=off），无法写入群记忆";
                if (!memory.pinNote(ch, noteText)) return "写入失败（内容为空？）";
                return "已记住（角色名 " + ch + "）：" + noteText + "\n下次对话就会带上；/notes 可查看";

            case "notes": case "记忆": case "记忆列表": {
                String ch2 = memoryScopeCharacter(fromGroup, userId);
                if (ch2 == null) return "记忆隔离已关闭（qq.memory-scope=off）";
                java.util.List<com.cy.pojo.PetMemory> list = memory.listNotes(ch2);
                if (list.isEmpty()) return "当前还没有记忆（角色名 " + ch2 + "）。用 /note 内容 钉一条。";
                StringBuilder sb = new StringBuilder("【记忆】").append(ch2).append("，共 ").append(list.size()).append(" 条\n");
                int i = 1;
                for (com.cy.pojo.PetMemory m : list) {
                    if (i > MAX_LINES) break;
                    String c = m.getContent() == null ? "" : m.getContent();
                    if (c.length() > 60) c = c.substring(0, 60) + "…";
                    sb.append(i++).append(". #").append(m.getId()).append(' ').append(c).append('\n');
                }
                sb.append("置顶的（[置顶] 开头）不会被自动提炼清掉；/note del <id> 删除");
                return sb.toString();
            }

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

    // ===== 提示词 / 风格语料（管理员查询与修改） =====

    /**
     * <pre>
     * /prompt                列出所有提示词（type / 版本 / 字数 / 开头）
     * /prompt show system    看某条全文（自动分段；超长会提示去管理页）
     * /prompt set system 内容 整条覆盖（谨慎，长度上限 4000 字）
     * /prompt add system 内容 追加一行（最常用，比如加一条群规）
     * </pre>
     */
    private String promptCommand(String[] parts) {
        if (parts.length < 2) return promptList();
        String sub = parts[1].toLowerCase();
        if ("show".equals(sub) || "看".equals(sub) || "查看".equals(sub)) {
            if (parts.length < 3) return "用法：/prompt show system|function";
            return promptShow(parts[2]);
        }
        if ("set".equals(sub) || "改".equals(sub) || "覆盖".equals(sub)) {
            if (parts.length < 4) return "用法：/prompt set system 你要设定的完整内容";
            return promptWrite(parts[2], rest(parts, 3), false);
        }
        if ("add".equals(sub) || "追加".equals(sub)) {
            if (parts.length < 4) return "用法：/prompt add system 要追加的一行";
            return promptWrite(parts[2], rest(parts, 3), true);
        }
        // 兼容 /prompt system 直接看
        return promptShow(parts[1]);
    }

    private String promptList() {
        List<com.cy.pojo.AiPrompt> all = aiPromptMapper.findAll();
        if (all == null || all.isEmpty()) return "库里没有任何提示词（ai_prompt 为空），可以去管理页「基础设置」新建";
        StringBuilder sb = new StringBuilder("【提示词】（桌宠和 QQ 共用这一套）\n");
        for (com.cy.pojo.AiPrompt p : all) {
            String c = p.getContent() == null ? "" : p.getContent();
            String head2 = c.replace("\n", " ");
            if (head2.length() > 50) head2 = head2.substring(0, 50) + "…";
            sb.append("- ").append(p.getPrompt_type()).append("（v").append(p.getVersion())
                    .append("，").append(c.length()).append(" 字）").append(head2).append('\n');
        }
        sb.append("查看：/prompt show system　修改：/prompt set system 内容　追加：/prompt add system 内容\n");
        sb.append("提示：system/qq_function 是 QQ 和桌宠共用的；function（桌面功能清单）在 QQ 里默认不注入，"
                + "需要 QQ 专用能力清单就 /prompt set qq_function 内容");
        return sb.toString();
    }

    private String promptShow(String type) {
        com.cy.pojo.AiPrompt p = aiPromptMapper.findByType(type);
        if (p == null) return "没有 prompt_type=" + type + " 的提示词。可用：" + typesJoined();
        String c = p.getContent() == null ? "" : p.getContent();
        String tail = "\n（v" + p.getVersion() + "，" + c.length() + " 字）";
        // QQ 单次回复最多 6 段 × 300 字 ≈ 1800，超出会被截断，这里提前说明
        if (c.length() > 1500) {
            return c.substring(0, 1500) + "\n…（后面还有 " + (c.length() - 1500)
                    + " 字没发，完整内容请去管理页「基础设置」查看/编辑）" + tail;
        }
        return c + tail;
    }

    private String promptWrite(String type, String content, boolean append) {
        if (content == null || content.trim().isEmpty()) return "内容不能为空";
        if (content.length() > 4000) return "内容太长（" + content.length() + " 字，上限 4000），请去管理页编辑";
        com.cy.pojo.AiPrompt p = aiPromptMapper.findByType(type);
        if (p == null) {
            if (append) return "还没有 prompt_type=" + type + " 的提示词；先 /prompt set " + type + " 内容 创建它";
            // set 时不存在就新建（管理员可直接建 qq_function 这份 QQ 专用能力清单）
            com.cy.pojo.AiPrompt fresh = new com.cy.pojo.AiPrompt();
            fresh.setPrompt_type(type);
            fresh.setContent(content);
            fresh.setVersion(1);
            aiPromptMapper.insert(fresh);
            System.out.println("[QQ] 管理员新建提示词 " + type + "（" + content.length() + " 字）");
            return "已新建提示词 " + type + "（" + content.length() + " 字，v1）"
                    + ("qq_function".equals(type)
                        ? "\nQQ 群里会优先用这一份能力清单（替代桌宠的 function）" : "")
                    + "\n下一条消息开始生效；要核对就 /prompt show " + type;
        }
        String old = p.getContent() == null ? "" : p.getContent();
        String neu = append ? (old.isEmpty() ? content : old + "\n" + content) : content;
        p.setContent(neu);
        aiPromptMapper.update(p);
        System.out.println("[QQ] 管理员修改提示词 " + type + "（" + old.length() + " → " + neu.length() + " 字）");
        return "已" + (append ? "追加到" : "覆盖") + "提示词 " + type + "（现在 " + neu.length() + " 字，v"
                + p.getVersion() + "）\n下一条消息开始生效；要核对就 /prompt show " + type;
    }

    private String typesJoined() {
        StringBuilder sb = new StringBuilder();
        for (com.cy.pojo.AiPrompt x : aiPromptMapper.findAll()) {
            if (sb.length() > 0) sb.append(" / ");
            sb.append(x.getPrompt_type());
        }
        return sb.length() == 0 ? "（空）" : sb.toString();
    }

    private String styleReport() {
        int n = 0;
        try {
            List<com.cy.pojo.StyleExemplar> all = styleExemplarMapper.findAll();
            if (all != null) n = all.size();
        } catch (Exception ignored) {
        }
        return "【风格语料】共 " + n + " 条（表 style_exemplar）\n"
                + "· 桌宠闲聊：按输入命中标签召回，注入「【风格参考】」段\n"
                + "· QQ 群聊：默认也注入（和桌宠同一套语料），可用 ai.style-for-qq=false 关掉\n"
                + "· 语料本身请在管理页「基础设置/风格语料」维护（QQ 里不支持批量编辑）";
    }

    /** 跟风复读机：概况 + 运行时调整（纯代码实现，不消耗 token） */
    private String parrotReport(String[] parts) {
        if (parts.length >= 2) {
            String sub = parts[1].toLowerCase();
            if ("on".equals(sub) || "开".equals(sub)) {
                tuning.setParrotEnabled(true);
                return "跟风复读机已开启（概率 " + tuning.parrotProbability() + "%）";
            }
            if ("off".equals(sub) || "关".equals(sub)) {
                tuning.setParrotEnabled(false);
                return "跟风复读机已关闭";
            }
            if ("prob".equals(sub) || "概率".equals(sub)) {
                if (parts.length < 3) return "当前概率 " + tuning.parrotProbability() + "%\n用法：/parrot prob 33";
                int p = (int) parseLong(parts[2], -1);
                if (p < 0 || p > 100) return "概率范围 0~100，例：/parrot prob 33";
                tuning.setParrotProbability(p);
                return "复读概率已设为 " + p + "%（0 = 永不复读，100 = 必复读）";
            }
            return "用法：/parrot [on|off|prob <0-100>]";
        }
        return "【跟风复读机】" + (tuning.parrotEnabled() ? "开启" : "关闭")
                + "，概率 " + tuning.parrotProbability() + "%\n"
                + "规则：同一句话被 " + config.getParrotMinUsers() + " 个不同人重复 → 按概率复读一次；"
                + "没中就等下一个不同人再判定；同一句复读过就不再复读\n"
                + "冷却：复读成功后跳过 " + config.getParrotCooldownTriggers() + " 次触发机会\n"
                + "窗口：" + config.getParrotWindowSec() + "s 内算重复；安全阀 " + config.getParrotMaxPerHour()
                + " 次/小时；超过 " + config.getParrotMaxChars() + " 字不参与\n"
                + "跟踪中的句子：" + parrot.trackingCount() + "\n"
                + "调整：/parrot on|off、/parrot prob 33";
    }

    /** 护短：概况 + 运行时开关（触发时才调 AI，默认每人 3 次冷却、每小时 5 次上限） */
    private String defendReport(String[] parts, long fromGroup) {
        if (parts.length >= 2) {
            String sub = parts[1].toLowerCase();
            if ("on".equals(sub) || "开".equals(sub)) {
                tuning.setDefendEnabled(true);
                return "护短已开启：群友调侃管理员时会站出来反驳";
            }
            if ("off".equals(sub) || "关".equals(sub)) {
                tuning.setDefendEnabled(false);
                return "护短已关闭（不会再主动反驳）";
            }
            if ("keywords".equals(sub) || "关键词".equals(sub)) {
                return "当前调侃关键词：" + String.join("、", config.getDefendKeywords());
            }
            if ("alias".equals(sub) || "别名".equals(sub) || "叫法".equals(sub) || "称呼".equals(sub)
                    || "昵称".equals(sub)) {
                return aliasCommand(parts);
            }
            if ("mock".equals(sub) || "引申".equals(sub) || "引申词".equals(sub)) {
                return "当前引申调侃词（管理员刚发过言才算）："
                        + String.join("、", config.getDefendMockWords())
                        + "\n上下文窗口：" + config.getDefendContextSec() + "s"
                        + "\n改这两个用环境变量 ZC_QQ_DEFEND_MOCK / ZC_QQ_DEFEND_CONTEXT_SEC";
            }
            return "用法：/defend [on|off|keywords|alias <叫法…>|mock]";
        }
        StringBuilder sb = new StringBuilder("【护短】");
        sb.append(tuning.defendEnabled() ? "开启" : "关闭");
        sb.append("\n触发：① @管理员 + 调侃词  ② 叫了管理员的名字 + 调侃语气"
                + "  ③ 引申词（羊肉/烤羊…）+ 管理员刚发过言");
        sb.append("\n管理员的叫法：").append(joinOr(tuning.defendAliases(), "（没配）"));
        sb.append("\n引申调侃词：").append(joinOr(config.getDefendMockWords(), "（没配）"))
                .append("（上下文 ").append(config.getDefendContextSec()).append("s 内有效）");
        sb.append("\n冷却：护短一次后跳过 ").append(config.getDefendCooldownTriggers()).append(" 次触发机会");
        sb.append("\n安全阀：每群每小时最多 ").append(config.getDefendMaxPerHour()).append(" 次（要调 AI，防烧钱）");
        sb.append("\n调侃关键词：").append(String.join("、", config.getDefendKeywords()));
        if (fromGroup > 0) {
            sb.append("\n本群状态：冷却剩 ").append(defend.cooldownOf(fromGroup))
                    .append(" 次，这一小时已护短 ").append(defend.defendedLastHour(fromGroup)).append(" 次");
            java.util.Set<String> nicks = defend.knownAdminNicks(fromGroup);
            sb.append("\n本群自动记住的管理员群名片：")
                    .append(nicks.isEmpty() ? "（还没有，管理员在本群说句话就会记下）" : String.join("、", nicks));
            long ago = defend.adminSpokeAgoSec(fromGroup);
            sb.append("\n管理员上次发言：")
                    .append(ago < 0 ? "还没在这个群说过话" : ago + "s 前")
                    .append(defend.adminSpokeWithin(fromGroup) ? "（引申词判定生效中）" : "");
        }
        sb.append("\n调整：/defend on|off、/defend alias 小羊羔,小羊、/defend keywords");
        return sb.toString();
    }

    /** /defend alias <叫法…>：改「群友怎么叫管理员才算提到他」 */
    private String aliasCommand(String[] parts) {
        if (parts.length < 3) {
            return "当前管理员叫法：" + joinOr(tuning.defendAliases(), "（没配）")
                    + "\n自动登记的群名片也会算（管理员在本群说话就记下）"
                    + "\n用法：/defend alias 小羊羔,小羊   （逗号分隔，留空或 reset 恢复配置）";
        }
        String raw = rest(parts, 2);
        if ("reset".equalsIgnoreCase(raw) || "重置".equals(raw) || "clear".equalsIgnoreCase(raw)) {
            tuning.setDefendAliases(null);
            return "管理员叫法已恢复配置文件："
                    + joinOr(config.getDefendAdminAlias(), "（空）");
        }
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String s : raw.split("[,，、\\s]+")) {
            String w = s.trim();
            if (w.length() >= 2) set.add(w);
        }
        if (set.isEmpty()) return "至少给一个 2 字以上的叫法，例：/defend alias 小羊羔,小羊";
        tuning.setDefendAliases(set);
        return "管理员叫法已改为：" + String.join("、", set)
                + "\n（这些词出现在消息里就算在说管理员，配上调侃词/语气才护短）"
                + "\n重启会回到配置文件；要永久生效就写进 env.qq：ZC_QQ_DEFEND_ALIAS=" + String.join(",", set);
    }

    private static String joinOr(java.util.Set<String> set, String empty) {
        return set == null || set.isEmpty() ? empty : String.join("、", set);
    }

    /**
     * 改机器人自己的名字（版本号）。
     *
     * <pre>
     * /nick                         看现在的 QQ 名 + 配置里要改成什么
     * /nick 阿罗娜（v0.0.7测试）      立刻改 QQ 昵称（NapCat set_qq_profile）
     * /nick card 阿罗娜（v0.0.7测试）  只改「当前这个群」里我的群名片
     * </pre>
     *
     * 注意：改 QQ 昵称会写进账号资料，不是"仅本次生效"；重启后由 {@code ZC_QQ_NICKNAME}
     * 再对一次（一样就不动，所以不会反复改）。
     */
    private String nickCommand(String[] parts, long fromGroup) {
        if (parts.length < 2) {
            String cur = client.getSelfNickname();
            String want = config.getNickname();
            return "【机器人名字】\n当前 QQ 名：" + (cur.isEmpty() ? "（还没拿到，等连上 NapCat）" : cur)
                    + "\n配置目标（ZC_QQ_NICKNAME）：" + (want.isEmpty() ? "（未设置，不会自动改）" : want)
                    + (want.isEmpty() || cur.isEmpty() ? "" : (want.equals(cur) ? "\n状态：一致 ✓" : "\n状态：不一致，重启会改一次"))
                    + "\n\n改法：\n/nick 阿罗娜（v0.0.7测试）       改 QQ 昵称（立刻生效）"
                    + "\n/nick card 阿罗娜（v0.0.7测试）  只改本群群名片"
                    + "\n上传时也可以带一句：push-jar.ps1 -Nick \"阿罗娜（v0.0.7测试）\"";
        }
        String sub = parts[1].toLowerCase();
        if ("card".equals(sub) || "群名片".equals(sub) || "本群".equals(sub)) {
            if (fromGroup <= 0) return "群名片只能在群里改：在群里发 /nick card 新名字";
            String name = rest(parts, 2);
            if (name.isEmpty()) return "用法：/nick card 阿罗娜（v0.0.7测试）";
            com.fasterxml.jackson.databind.JsonNode r = client.setSelfGroupCard(fromGroup, name);
            return com.cy.qq.NapCatWsClient.isOk(r)
                    ? "本群群名片已改为「" + name + "」"
                    : "改群名片失败：" + (r == null ? "没连上 NapCat" : r.toString())
                    + "\n（有些群不允许机器人改自己的名片，那就只能改 QQ 名）";
        }
        String name = rest(parts, 1);
        if (name.isEmpty()) return "用法：/nick 阿罗娜（v0.0.7测试）";
        com.fasterxml.jackson.databind.JsonNode r = client.setProfileNickname(name);
        if (!com.cy.qq.NapCatWsClient.isOk(r)) {
            return "改昵称失败：" + (r == null ? "没连上 NapCat" : r.toString())
                    + "\n如果 NapCat 版本不认 set_qq_profile，就手动在 QQ 里改，"
                    + "改完 /nick 会显示当前名字";
        }
        return "已改名为「" + name + "」\n（群里的旧消息不会变，新消息用新名字）"
                + "\n要让重启后保持一致，把 env.qq 里的 ZC_QQ_NICKNAME 也改成这个";
    }

    private static String rest(String[] parts, int from) {
        if (parts.length <= from) return "";
        return String.join(" ", java.util.Arrays.copyOfRange(parts, from, parts.length)).trim();
    }

    /** 记忆写哪个命名空间：群里 → 阿罗娜-群{群号}；私聊 → 阿罗娜-私聊{QQ号} */
    private String memoryScopeCharacter(long fromGroup, long userId) {
        if (fromGroup > 0) return memory.characterForGroup(fromGroup);
        return memory.characterForPrivate(userId);
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
                + "\n/prompt [show|set|add] <system|function> 查看/修改人设提示词"
                + "\n/style 风格语料概况（QQ 是否注入）"
                + "\n/note <内容> 钉一条长期记忆；/notes 查看；/note del <id> 删除"
                + "\n/parrot [on|off|prob 33] 跟风复读机（同句 3 人 → 按概率复读）"
                + "\n/defend [on|off|alias 小羊羔,小羊|keywords] 护短（群友调侃管理员时站出来反驳）"
                + "\n/nick [新名字|card 新名字] 改机器人 QQ 名 / 本群群名片（版本号用）"
                + "\n（只有管理员 QQ 能用；私聊除指令外可以直接和我聊天）";
    }

    private String status() {
        StringBuilder sb = new StringBuilder("【运行状态】\n");
        sb.append("连接：").append(client.isConnected() ? "在线 ✓" : "离线 ✗").append('\n');
        sb.append("自身 QQ：").append(config.getSelfId() <= 0 ? "未识别" : String.valueOf(config.getSelfId()))
                .append("（名字：").append(client.getSelfNickname().isEmpty() ? "未知" : client.getSelfNickname())
                .append("）").append('\n');
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
        sb.append("主动接话：复读机 ").append(tuning.parrotEnabled() ? "开" : "关")
                .append("（概率 ").append(tuning.parrotProbability()).append("%），护短 ")
                .append(tuning.defendEnabled() ? "开" : "关")
                .append("（冷却 ").append(config.getDefendCooldownTriggers()).append(" 次触发，上限 ")
                .append(config.getDefendMaxPerHour()).append(" 次/时）\n");
        sb.append("黑名单：").append(blacklist.size()).append(" 人\n");
        sb.append("图片留底：").append(imageStore.totalCount()).append(" 张（")
                .append(Math.round(imageStore.totalBytes() / 1024.0)).append(" KB）\n");
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
            sb.append("  图片留底 ").append(imageStore.count(QqImageStore.sessionKey(false, g.groupId))).append(" 张");
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
