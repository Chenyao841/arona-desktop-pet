package com.cy.qq.game;

import com.cy.qq.QqConfig;
import com.cy.qq.QqReplyService;
import com.cy.qq.QqUsageService;
import com.cy.pojo.PictionaryWord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 你画我猜的 QQ 侧交互：指令解析 + 消息编排（把 {@link QqPicService.Out} 变成真实气泡）。
 *
 * <p>为什么单独一个类：{@code QqEventRouter} 已经够长，这里把"游戏怎么说话"全部收进来，
 * Web 层（页面提交后的回执）也复用同一个 {@link #sendOut}，保证群里看到的格式只有一处定义。
 *
 * <p>群里的交互分两类：
 * <ul>
 *   <li><b>游戏指令</b>（{@code /你画我猜}、{@code /结束你画我猜}、{@code /画 1..4}、裸数字 1..4）—— 免 @，
 *       但仍受"该群是否开放游戏"和白名单约束</li>
 *   <li><b>管理指令</b>（{@code /游戏}、{@code /题库}）—— 只有管理员，且在任何群/私聊都能用（否则没法配置）</li>
 * </ul>
 */
@Component
public class QqPicCommands {

    @Autowired
    private QqPicService service;

    @Autowired
    private QqPicMapper mapper;

    @Autowired
    private QqPicWordBank wordBank;

    @Autowired
    private QqReplyService replyService;

    @Autowired
    private QqConfig config;

    @Autowired
    private QqUsageService usage;

    /**
     * 试着把一条消息当成游戏/管理指令处理。
     *
     * @return true = 已经处理（调用方不要再走 AI 那条路）
     */
    public boolean tryHandle(String chatType, long chatId, long userId, String nickname,
                             String rawText, long messageId) {
        if (rawText == null) return false;
        String text = rawText.trim();
        if (text.isEmpty()) return false;

        // ===== 管理指令：/游戏、/题库（管理员专属，任何场景都可以配）=====
        if (isAdmin(userId)) {
            if (text.startsWith("/游戏") || text.startsWith("/题库")) {
                String reply = adminCommand(text, chatType, chatId);
                if (reply != null) {
                    sendPlain(chatType, chatId, reply);
                    return true;
                }
            }
        }

        // ===== 游戏指令：先看这个群/私聊有没有开放 =====
        boolean allowed = "private".equals(chatType) || service.groupAllowed(chatId);

        if (text.startsWith("/你画我猜") || text.startsWith("/画画") || text.startsWith("/画猜")) {
            if (!allowed) {
                sendPlain(chatType, chatId, "这个群还没开放小游戏，管理员发 /游戏 开 " + chatId + " 才能玩");
                return true;
            }
            String arg = text.replaceFirst("^/(你画我猜|画画|画猜)", "").trim();
            int d = QqPicWordBank.parseDifficulty(arg);
            QqPicService.Out out = service.start(chatType, chatId, userId, nickname, d <= 0 ? 1 : d);
            sendOut(out, chatType, chatId);
            return true;
        }

        if (text.startsWith("/结束你画我猜") || text.startsWith("/结束画猜")) {
            if (!allowed) return false;
            sendOut(service.endByUser(userId), chatType, chatId);
            return true;
        }

        if (text.startsWith("/画") && !text.startsWith("/画我")) {
            if (!allowed) return false;
            String arg = text.replaceFirst("^/画", "").trim();
            handleMenu(arg, chatType, chatId, userId, nickname, messageId);
            return true;
        }

        // ===== 裸数字回执：只有"这个聊天里正在进行、且是本人那一局"才认，免 @ =====
        if (text.matches("^[1-4]$")) {
            QqPicSession s = service.byChat(chatType, chatId);
            if (s != null && !s.finished() && s.ownerQq() == userId) {
                handleMenu(text, chatType, chatId, userId, nickname, messageId);
                return true;
            }
        }
        return false;
    }

    /** 1 难度 / 2 换题 / 3 链接或下一局 / 4 结束 */
    private void handleMenu(String arg, String chatType, long chatId, long userId, String nickname, long messageId) {
        String a = arg == null ? "" : arg.trim();
        QqPicSession s = service.byChat(chatType, chatId);

        // 数字 → 语义
        if ("1".equals(a)) a = "难度";
        else if ("2".equals(a)) a = "换题";
        else if ("3".equals(a)) a = "绘制";
        else if ("4".equals(a)) a = "结束";

        if (a.isEmpty()) {
            sendPlain(chatType, chatId, "游戏指令：\n/你画我猜 简单|中等|困难  开一局\n/画 1 改难度　/画 2 换题　/画 3 链接/下一局　/画 4 结束\n(局内也可以直接回数字 1~4)");
            return;
        }
        if (a.startsWith("难度")) {
            // 循环切换 简单→中等→困难→简单（换难度会换题，同样吃换题冷却）
            int cur = s == null ? 1 : s.difficulty();
            int next = cur >= 3 ? 1 : cur + 1;
            sendOut(service.nextWord(userId, next), chatType, chatId);
            return;
        }
        if (a.startsWith("换题") || a.startsWith("换") || a.startsWith("下一题")) {
            sendOut(service.nextWord(userId, 0), chatType, chatId);
            return;
        }
        if (a.startsWith("绘制") || a.startsWith("画") || a.startsWith("链接") || a.startsWith("下一局")) {
            if (s != null && !s.finished()) {
                sendPlain(chatType, chatId, "画板在这儿：" + service.pageUrl(s)
                        + "\n（题目只有你点开才看得到）");
            } else {
                // 已结束/没有局 → 直接按当前难度开下一局
                int d = s == null ? 1 : s.difficulty();
                sendOut(service.start(chatType, chatId, userId, nickname, d), chatType, chatId);
            }
            return;
        }
        if (a.startsWith("结束") || a.startsWith("退") || a.startsWith("quit")) {
            sendOut(service.endByUser(userId), chatType, chatId);
            return;
        }
        sendPlain(chatType, chatId, "看不懂这个参数，可用：/画 1|2|3|4 或 /画 难度|换题|绘制|结束");
    }

    // ============================================================
    //  发消息（群里两条气泡 + 一条结算）
    // ============================================================

    /** 把一次操作的结果发出去：纯文本 / 画稿+@+结论 / 反应 / 结算 */
    public void sendOut(QqPicService.Out out, String chatType, long chatId) {
        if (out == null) return;
        boolean priv = "private".equals(chatType);
        // ① 画稿 + @绘制者 + 猜测结论（同一个气泡）
        if (out.imagePath != null) {
            replyService.sendDrawResult(priv, chatId, out.imagePath, out.atQq, out.guessText);
        }
        // ② 反应文本（单独气泡）
        if (out.reaction != null && !out.reaction.isEmpty()) {
            sendPlain(chatType, chatId, out.reaction);
        }
        // ③ 结算 / 面板
        if (out.after != null && !out.after.isEmpty()) {
            sendPlain(chatType, chatId, out.after);
        }
        for (String line : out.lines) {
            sendPlain(chatType, chatId, line);
        }
    }

    private void sendPlain(String chatType, long chatId, String text) {
        if (text == null || text.isEmpty()) return;
        if ("private".equals(chatType)) replyService.replyPrivate(chatId, text);
        else replyService.reply(chatId, text, 0L);
    }

    private boolean isAdmin(long userId) {
        return config.getAdminQqs().contains(userId);
    }

    // ============================================================
    //  管理指令：/游戏 …（白名单）/ /题库 …（词库）
    // ============================================================

    private String adminCommand(String text, String chatType, long chatId) {
        String[] parts = text.trim().split("\\s+");
        if (parts.length < 2) {
            return text.startsWith("/游戏") ? gameHelp(chatId) : wordHelp(chatId);
        }
        String sub = parts[1];
        if (text.startsWith("/游戏")) {
            switch (sub) {
                case "开": case "启用": case "on": {
                    long g = parseLong(parts.length > 2 ? parts[2] : "", chatId);
                    if (g <= 0) return "用法：/游戏 开 <群号>（在群里发可以省略群号）";
                    mapper.addAllow("group", g, "管理员开启");
                    return "已开放群 " + g + " 的小游戏（该群白名单成员发 /你画我猜 就能玩）";
                }
                case "关": case "关闭": case "off": {
                    long g = parseLong(parts.length > 2 ? parts[2] : "", chatId);
                    if (g <= 0) return "用法：/游戏 关 <群号>";
                    mapper.delAllow("group", g);
                    return "已关闭群 " + g + " 的小游戏";
                }
                case "允许": case "加人": {
                    long q = parseLong(parts.length > 2 ? parts[2] : "", -1);
                    if (q <= 0) return "用法：/游戏 允许 <QQ号>";
                    mapper.addAllow("user", q, "管理员允许");
                    return "已把 QQ " + q + " 加入小游戏白名单（可以和开一局了）";
                }
                case "禁止": case "删人": {
                    long q = parseLong(parts.length > 2 ? parts[2] : "", -1);
                    if (q <= 0) return "用法：/游戏 禁止 <QQ号>";
                    mapper.delAllow("user", q);
                    return "已把 QQ " + q + " 移出小游戏白名单";
                }
                case "额度": {
                    if (parts.length < 3) {
                        return "游戏每日额度：" + (usage.picLimit() <= 0 ? "不限" : usage.picLimit())
                                + "\n今日游戏已用：" + usage.getPicTodayTokens() + " tokens"
                                + "（与日常对话额度分开算）\n改：/游戏 额度 500000（0 = 不限）";
                    }
                    long v = parseLong(parts[2], -1);
                    if (v < 0) return "用法：/游戏 额度 500000（0 = 不限）";
                    usage.setPicLimit(v);
                    return "游戏每日额度已设为 " + (v == 0 ? "不限" : v);
                }
                case "列表": case "状态":
                    return gameStatus(chatId);
                default:
                    return gameHelp(chatId);
            }
        }

        // ===== /题库 =====
        switch (sub) {
            case "列表": {
                int d = QqPicWordBank.parseDifficulty(parts.length > 2 ? parts[2] : "");
                List<PictionaryWord> all = mapperList(d);
                StringBuilder sb = new StringBuilder("【词库】" + (d > 0 ? QqPicWordBank.difficultyName(d) + " " : "")
                        + "共 " + all.size() + " 条（最多显示 30 条）\n");
                int i = 0;
                for (PictionaryWord w : all) {
                    if (i++ >= 30) { sb.append("……（还有 ").append(all.size() - 30).append(" 条，用难度筛选）"); break; }
                    sb.append("#").append(w.getId()).append(' ').append(w.getWord());
                    if (w.getAliases() != null && !w.getAliases().isEmpty()) sb.append("（").append(w.getAliases()).append("）");
                    sb.append(" [").append(w.getDifficulty()).append("] ");
                    if (w.getEnabled() != null && w.getEnabled() == 0) sb.append("·禁用");
                    sb.append('\n');
                }
                return sb.toString().trim();
            }
            case "加": case "添加": {
                // /题库 加 台灯 | 护眼灯|台灯  | 物品 | 1      （竖线分隔，后三项可省）
                String rest = text.substring(text.indexOf(sub) + sub.length()).trim();
                if (rest.isEmpty()) return "用法：/题库 加 台灯|别名1,别名2|类别|难度\n例：/题库 加 台灯|护眼灯 物品 1";
                String[] seg = rest.split("\\s*[|｜]\\s*|\\s+");
                String word = seg.length > 0 ? seg[0].trim() : "";
                if (word.isEmpty()) return "词不能为空";
                PictionaryWord w = new PictionaryWord();
                w.setWord(word);
                w.setAliases(seg.length > 1 ? seg[1].trim() : "");
                w.setCategory(seg.length > 2 ? seg[2].trim() : "");
                int d = QqPicWordBank.parseDifficulty(seg.length > 3 ? seg[3] : "");
                w.setDifficulty(d > 0 ? d : 1);
                w.setEnabled(1);
                try {
                    wordBank.save(w);
                    return "已加入词库：" + word + (w.getAliases().isEmpty() ? "" : "（别名 " + w.getAliases() + "）")
                            + " 难度" + QqPicWordBank.difficultyName(w.getDifficulty());
                } catch (Exception e) {
                    return "加词失败（可能已有同名词）：" + e.getMessage();
                }
            }
            case "删": case "删除": {
                int id = (int) parseLong(parts.length > 2 ? parts[2] : "", -1);
                if (id <= 0) return "用法：/题库 删 <id>（id 用 /题库 列表 查）";
                return wordBank.remove(id) ? "已删除 #" + id : "没找到 #" + id;
            }
            case "别名": {
                int id = (int) parseLong(parts.length > 2 ? parts[2] : "", -1);
                if (id <= 0 || parts.length < 4) return "用法：/题库 别名 <id> 别名1|别名2|别名3";
                return wordBank.setAliases(id, parts[3]) ? "已更新 #" + id + " 的别名" : "没找到 #" + id;
            }
            case "难度": {
                int id = (int) parseLong(parts.length > 2 ? parts[2] : "", -1);
                int d = QqPicWordBank.parseDifficulty(parts.length > 3 ? parts[3] : "");
                if (id <= 0 || d <= 0) return "用法：/题库 难度 <id> 简单|中等|困难";
                return wordBank.setDifficulty(id, d) ? "已把 #" + id + " 改成" + QqPicWordBank.difficultyName(d) : "没找到 #" + id;
            }
            case "禁用": case "启用": {
                int id = (int) parseLong(parts.length > 2 ? parts[2] : "", -1);
                if (id <= 0) return "用法：/题库 禁用|启用 <id>";
                boolean on = "启用".equals(sub);
                return wordBank.setEnabled(id, on) ? "已" + (on ? "启用" : "禁用") + " #" + id : "没找到 #" + id;
            }
            case "重置": {
                long g = parseLong(parts.length > 2 ? parts[2] : "", chatId);
                if (g <= 0) return "用法：/题库 重置 [群号]（不写就是当前群）";
                service.resetTodayWords(g);
                return "已清空 " + g + " 今天的出题记录（现在可以重新出到相同的题）";
            }
            default:
                return wordHelp(chatId);
        }
    }

    private List<PictionaryWord> mapperList(int d) {
        List<PictionaryWord> all = wordBank.all();
        if (d <= 0) return all;
        java.util.List<PictionaryWord> out = new java.util.ArrayList<>();
        for (PictionaryWord w : all) if (w.getDifficulty() != null && w.getDifficulty() == d) out.add(w);
        return out;
    }

    private String gameHelp(long chatId) {
        return "【小游戏·你画我猜】\n"
                + "/你画我猜 简单|中等|困难   开一局\n"
                + "/结束你画我猜            结束当前局\n"
                + "/画 1|2|3|4            改难度/换题/链接/结束（局内直接回数字也行）\n"
                + "—— 管理 ——\n"
                + "/游戏 开 <群号>           开放某个群\n"
                + "/游戏 关 <群号>           关闭某个群\n"
                + "/游戏 允许 <QQ>           加人白名单（谁能开局）\n"
                + "/游戏 禁止 <QQ>           移出白名单\n"
                + "/游戏 额度 [数字]         看/改游戏每日额度（默认 50 万，独立于日常额度）\n"
                + "/游戏 列表                看当前配置与本群今日进度";
    }

    private String wordHelp(long chatId) {
        return "【题库管理】" + (chatId > 0 ? "（本群 " + service.todayUsageLine(chatId) + "）" : "") + "\n"
                + "/题库                    概况\n"
                + "/题库 列表 [简单|中等|困难]\n"
                + "/题库 加 台灯|护眼灯|物品|1   （词|别名|类别|难度，后三项可省）\n"
                + "/题库 删 <id>\n"
                + "/题库 别名 <id> 别名1|别名2\n"
                + "/题库 难度 <id> 简单|中等|困难\n"
                + "/题库 禁用 <id>　/题库 启用 <id>\n"
                + "/题库 重置 [群号]         清空该群今天的出题记录";
    }

    private String gameStatus(long chatId) {
        StringBuilder sb = new StringBuilder("【小游戏配置】\n");
        sb.append("总开关：").append(config.isPicEnabled() ? "开" : "关").append('\n');
        List<Map<String, Object>> groups = mapper.listAllow("group");
        sb.append("开放群：").append(groups.isEmpty() ? "（无）" : "");
        for (Map<String, Object> g : groups) sb.append(g.get("targetId")).append(' ');
        sb.append("\n开局白名单：");
        List<Map<String, Object>> users = mapper.listAllow("user");
        if (users.isEmpty()) sb.append("（只有管理员）");
        for (Map<String, Object> u : users) sb.append(u.get("targetId")).append(' ');
        sb.append("\n游戏额度：").append(usage.picLimit() <= 0 ? "不限" : usage.picLimit())
                .append("，今日已用 ").append(usage.getPicTodayTokens()).append(" tokens");
        sb.append("\n每局上限：").append(config.getPicMaxRounds()).append(" 轮 / ")
                .append(config.getPicTimeoutMin()).append(" 分钟；换题冷却 ")
                .append(config.getPicNextCooldownSec()).append("s（管理员不限）");
        sb.append("\n词库：").append(mapper.countEnabledWords()).append(" 个（简单 ")
                .append(mapper.countEnabledWordsByDifficulty(1)).append(" / 中等 ")
                .append(mapper.countEnabledWordsByDifficulty(2)).append(" / 困难 ")
                .append(mapper.countEnabledWordsByDifficulty(3)).append("）");
        if (chatId > 0) sb.append('\n').append(service.todayUsageLine(chatId));
        return sb.toString();
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s.trim());
        } catch (Exception e) {
            return def;
        }
    }
}
