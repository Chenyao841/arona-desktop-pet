package com.cy.qq.game;

import com.cy.qq.QqConfig;
import com.cy.qq.QqReplyService;
import com.cy.qq.QqUsageService;
import com.cy.pojo.PictionaryWord;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 你画我猜（QQ 版）编排层：开局 / 换题 / 换难度 / 提交 / 结束 / 超时巡查。
 *
 * <p>三权分立（反作弊的骨架）：
 * <ul>
 *   <li><b>本类</b>：唯一同时知道"答案"和"流程"的地方，但**绝不把答案交给出猜器**</li>
 *   <li>{@link QqPicGuesser}：只拿到"字数 + 历史 + 两张图"，看不到答案</li>
 *   <li>{@link QqPicJudge}：只做"猜的词 vs 答案"的比对，纯代码</li>
 * </ul>
 * 每轮还会把提示词落盘并断言 {@code containsAnswer=false}（日志里有 `containsAnswer=`）。
 *
 * <p>出题规则：词库复用桌宠的 {@code pictionary_word}；<b>同一个群一天之内不重复出题</b>
 * （被"换题"换掉的也算出过），全部题目遍历完之后清空该群当天记录重新开始。
 */
@Service
public class QqPicService {

    /** 画稿目录（也是提示词自证文件的目录） */
    public static final Path SHOT_DIR = QqPicGuesser.SHOT_DIR;

    @Autowired
    private QqConfig config;

    @Autowired
    private QqPicMapper mapper;

    @Autowired
    private QqPicWordBank wordBank;

    @Autowired
    private QqPicGuesser guesser;

    @Autowired
    private QqPicJudge judge;

    @Autowired
    private QqUsageService usage;

    @Autowired
    private QqReplyService replyService;

    /** 进行中的局：key = "group:群号" / "private:QQ号" */
    private final Map<String, QqPicSession> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService ticker;

    @PostConstruct
    public void init() {
        try {
            mapper.createGameTable();
            mapper.createGuessTable();
            mapper.createAllowTable();
            mapper.createDailyWordTable();
            Files.createDirectories(SHOT_DIR);
            System.out.println("[你画我猜] 表就绪；词库 " + mapper.countEnabledWords() + " 个（"
                    + "简单 " + mapper.countEnabledWordsByDifficulty(1) + " / 中等 "
                    + mapper.countEnabledWordsByDifficulty(2) + " / 困难 "
                    + mapper.countEnabledWordsByDifficulty(3) + "）；开关 "
                    + (config.isPicEnabled() ? "开" : "关（管理员用 /游戏 开 <群号> 指定可玩群）"));
        } catch (Exception e) {
            System.out.println("[你画我猜] 初始化失败（游戏不可用）: " + e.getMessage());
        }
        // 超时巡查：每 30 秒看一遍有没有超时的局
        ticker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-pic-ticker");
            t.setDaemon(true);
            return t;
        });
        ticker.scheduleWithFixedDelay(this::tickTimeout, 30, 30, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void shutdown() {
        if (ticker != null) ticker.shutdownNow();
    }

    // ============================================================
    //  权限 / 开关
    // ============================================================

    /** 总闸（kill switch）：配置文件默认开，运行时可以 /游戏 总开关 off 一把关掉 */
    private volatile Boolean enabledOverride = null;

    public boolean picEnabled() {
        Boolean v = enabledOverride;
        return v != null ? v : config.isPicEnabled();
    }

    public void setPicEnabled(boolean on) {
        enabledOverride = on;
        System.out.println("[你画我猜] 总开关已" + (on ? "打开" : "关闭"));
    }

    /**
     * 这个群允许玩吗。
     *
     * <p>注意"默认关闭"是这么实现的：<b>群白名单为空 = 哪个群都不能玩</b>，
     * 管理员用 {@code /游戏 开 <群号>} 一个个开放。总闸只是紧急刹车。
     */
    public boolean groupAllowed(long groupId) {
        if (!picEnabled()) return false;
        return mapper.isAllowed("group", groupId) > 0;
    }

    /** 谁能开局：管理员 + 人白名单 */
    public boolean canStart(long userId) {
        if (userId <= 0) return false;
        if (config.getAdminQqs().contains(userId)) return true;
        return mapper.isAllowed("user", userId) > 0;
    }

    public boolean isAdmin(long userId) {
        return config.getAdminQqs().contains(userId);
    }

    // ============================================================
    //  开局
    // ============================================================

    /** 一次操作要发出去的消息 */
    public static class Out {
        public boolean ok;
        /** 纯文本回复（分段发） */
        public final List<String> lines = new ArrayList<>();
        /** 带图气泡：画稿路径（null = 这条不发） */
        public String imagePath;
        /** 带图气泡里 @ 谁 */
        public long atQq;
        /** 带图气泡里的文字（猜测结论） */
        public String guessText;
        /** 第二气泡：反应文本 */
        public String reaction;
        /** 第三气泡：公布答案 / 结算 */
        public String after;
        /** 本局是否已经结束 */
        public boolean ended;

        static Out error(String msg) {
            Out o = new Out();
            o.ok = false;
            o.lines.add(msg);
            return o;
        }

        static Out text(String... ls) {
            Out o = new Out();
            o.ok = true;
            for (String l : ls) if (l != null && !l.isEmpty()) o.lines.add(l);
            return o;
        }
    }

    /**
     * 开一局。
     *
     * @param chatType group / private
     * @param chatId   群号 / QQ号
     * @param ownerQq  绘制者
     */
    public Out start(String chatType, long chatId, long ownerQq, String ownerNick, int difficulty) {
        if (!picEnabled()) {
            return Out.error("小游戏总开关是关的（管理员发 /游戏 总开关 on 打开）");
        }
        if ("group".equals(chatType) && !groupAllowed(chatId)) {
            return Out.error("这个群还没开放小游戏，管理员发 /游戏 开 " + chatId + " 才能玩");
        }
        if (!canStart(ownerQq)) {
            return Out.error("你不在小游戏白名单里，让管理员发：/游戏 允许 " + ownerQq);
        }
        String key = chatType + ":" + chatId;
        QqPicSession exist = sessions.get(key);
        if (exist != null && !exist.finished()) {
            return Out.error("现在还有一局没结束（" + exist.ownerNick() + " 在画）。"
                    + (exist.ownerQq() == ownerQq ? "你发 /画 4 可以结束它" : "等 ta 玩完或让 ta /画 4 结束"));
        }
        if (usage.isPicQuotaExceeded()) {
            return Out.error(config.getPicQuotaReply());
        }

        int d = difficulty <= 0 ? 1 : Math.min(3, difficulty);
        QqPicGameRow w = pickWord(chatId, d, new LinkedHashSet<>());
        if (w == null) {
            return Out.error("词库里没有可出的题了，管理员用 /题库 加 <词> 补几个吧～");
        }

        QqPicGameRow row = new QqPicGameRow();
        row.setChat_type(chatType);
        row.setChat_id(chatId);
        row.setOwner_qq(ownerQq);
        row.setOwner_nick(ownerNick == null ? "" : ownerNick);
        row.setWord_id(w.getWord_id());
        row.setWord(w.getWord());
        row.setAliases(w.getAliases());
        row.setCategory(w.getCategory());
        row.setDifficulty(d);
        row.setToken(newToken());
        mapper.insertGame(row);

        QqPicSession s = QqPicSession.fromRow(row);
        sessions.put(key, s);
        System.out.println("[你画我猜] 开局 #" + row.getId() + " 群/私聊=" + key + " 绘制者=" + s.ownerNick()
                + " 题目=" + row.getWord() + "（" + row.getCategory() + "/难度" + d + "）");

        Out o = Out.text(openLine(s));
        return o;
    }

    /** 开局那段文案（群里不发题目，只发难度和链接） */
    public String openLine(QqPicSession s) {
        StringBuilder sb = new StringBuilder();
        sb.append("【你画我猜】难度：").append(QqPicWordBank.difficultyName(s.difficulty())).append('\n');
        if (s.isGroup()) {
            sb.append("题目只有点进去画画的人看得到（").append(s.word().length()).append(" 个字）\n");
            sb.append("点这里画：").append(pageUrl(s)).append('\n');
            sb.append("回复数字（或 /画 数字）：\n");
        } else {
            // 私聊是管理员自测，直接把题目打出来省事
            sb.append("题目：「").append(s.word()).append("」（").append(s.category()).append("）\n");
            sb.append("点这里画：").append(pageUrl(s)).append('\n');
        }
        sb.append(menuLine(false));
        return sb.toString();
    }

    public String pageUrl(QqPicSession s) {
        return config.getPicBaseUrl() + "/qq_pic/?t=" + s.token();
    }

    /** 四个入口（没有真按钮，只能数字回执） */
    public static String menuLine(boolean afterWin) {
        return "1 修改难度　2 更换题目　3 " + (afterWin ? "下一局" : "进行绘制") + "　4 结束游戏";
    }

    // ============================================================
    //  换题 / 换难度
    // ============================================================

    /**
     * 换题（换难度也走这里，因为换难度必然换题，不然能靠 1↔2↔3 无限刷题）。
     *
     * @param userId 谁点的（冷却按人算；管理员无限制）
     */
    public Out nextWord(long userId, int newDifficulty) {
        QqPicSession s = byUser(userId);
        if (s == null) return Out.error("当前没有进行中的局。先发 /你画我猜 简单|中等|困难 开一局吧～");
        if (s.finished()) return Out.error("这一局已经结束了，发 /画 3 开下一局吧");

        // 冷却：每人 N 秒一次，管理员不限
        if (!isAdmin(userId)) {
            long cd = config.getPicNextCooldownSec() * 1000L;
            long left = cd - (System.currentTimeMillis() - s.lastSwitchAt());
            if (cd > 0 && s.lastSwitchAt() > 0 && left > 0) {
                return Out.error("换题冷却中，还要等 " + ((left + 999) / 1000) + " 秒（每人 " + config.getPicNextCooldownSec() + " 秒一次）");
            }
        }

        int d = newDifficulty > 0 ? Math.min(3, newDifficulty) : s.difficulty();
        QqPicGameRow w = pickWord(s.chatId(), d, new LinkedHashSet<>(s.issuedWordIds()));
        if (w == null) return Out.error("这个难度今天已经出完了（本群当天不重复出题），换个难度试试？");

        s.setWord(w);
        s.markSwitch();
        mapper.replaceWord(s.gameId(), w);
        mapper.clearGuesses(s.gameId());
        System.out.println("[你画我猜] 换题 #" + s.gameId() + " → " + w.getWord()
                + "（难度" + d + "，操作者 " + userId + "）");

        Out o = Out.text("【换题了】难度：" + QqPicWordBank.difficultyName(d)
                + (s.isGroup() ? "" : "\n题目：「" + w.getWord() + "」（" + w.getCategory() + "）")
                + "\n重新点链接画（旧画作已作废）：" + pageUrl(s)
                + "\n" + menuLine(false));
        return o;
    }

    // ============================================================
    //  提交一张画 → 猜 → 判
    // ============================================================

    public Out submit(QqPicSession s, byte[] imageBytes) {
        if (s == null) return Out.error("找不到这一局（链接过期了？回群里发 /画 3 重新拿链接）");
        if (s.finished()) return Out.error("这一局已经结束了");
        synchronized (s.submitLock()) {
            if (s.finished()) return Out.error("这一局已经结束了");
            if (usage.isPicQuotaExceeded()) return Out.error(config.getPicQuotaReply());

            BufferedImage img = decode(imageBytes);
            if (img == null) return Out.error("这张画没收到，再点一次「画好了」？");

            List<QqPicGuessRow> history = mapper.findGuesses(s.gameId());
            int round = history.size() + 1;
            if (config.getPicMaxRounds() > 0 && round > config.getPicMaxRounds()) {
                finish(s, "timeout", "超轮数");
                return summaryOut(s, "这一局已经猜了 " + config.getPicMaxRounds() + " 轮啦，先揭晓答案吧～");
            }

            String shotName = saveShot(s.gameId(), round, img);
            String curB64 = base64OfJpeg(img);
            File prevShot = shotFile(s.gameId(), round - 1);
            boolean hasPrev = prevShot.exists() && prevShot.length() > 0;

            List<String> imgs = new ArrayList<>();
            if (hasPrev) {
                String prevB64 = base64OfFile(prevShot);
                if (prevB64 != null) {
                    imgs.add(prevB64);
                    imgs.add(curB64);
                } else {
                    hasPrev = false;
                    imgs.add(curB64);
                }
            } else {
                imgs.add(curB64);
            }

            // ★ 出猜器：只给"字数 + 历史 + 图"，答案不进入这一层
            String prompt = guesser.buildPrompt(s.word().length(), round, history, hasPrev);
            // ★ 自证：提示词落盘 + 断言不含答案（能拿到答案的只有这一层，所以断言放在这里）
            boolean leak = prompt.contains(s.word());
            System.out.println("[你画我猜] #" + s.gameId() + " 第" + round + "轮 prompt "
                    + prompt.length() + " 字，containsAnswer=" + leak);
            QqPicGuesser.dumpPrompt(s.gameId(), round, prompt);
            if (leak) {
                System.out.println("[你画我猜] ⚠ 提示词里出现了答案！已改用最小提示词重试（这是 bug，请上报）");
                prompt = guesser.retryPrompt();
            }

            String raw;
            usage.beginPic(s.chatId());
            try {
                raw = guesser.ask(imgs, prompt);
            } finally {
                usage.end();
            }
            QqPicGuesser.dumpRaw(s.gameId(), round, "1", raw);
            QqPicGuesser.Guess g = guesser.parse(raw);

            if (g.guess.isEmpty()) {
                // 模型没给出一个像样的词：不算一轮，人可以重画（不亏次数、不算重复题）
                usage.beginPic(s.chatId());
                try {
                    raw = guesser.ask(List.of(curB64), guesser.retryPrompt());
                } finally {
                    usage.end();
                }
                QqPicGuesser.dumpRaw(s.gameId(), round, "2-retry", raw);
                g = guesser.parse(raw);
            }
            if (g.guess.isEmpty()) {
                System.out.println("[你画我猜] #" + s.gameId() + " 第" + round + "轮 两次都没猜出词，不计轮");
                return Out.error(g.say.isEmpty() ? "唔…我这会儿看不清，把线条画粗一点再试试？" : g.say);
            }

            boolean correct = judge.isCorrect(s.word(), s.aliases(), g.guess);

            QqPicGuessRow row = new QqPicGuessRow();
            row.setGame_id(s.gameId());
            row.setRound_no(round);
            row.setGuess(g.guess);
            row.setCorrect(correct ? 1 : 0);
            row.setChanges_desc(cut(g.changes, 100));
            row.setSay(cut(g.say, 100));
            row.setShot_path(shotName);
            try {
                mapper.insertGuess(row);
                mapper.incRound(s.gameId());
            } catch (Exception e) {
                System.out.println("[你画我猜] 记猜测失败: " + e.getMessage());
            }

            Out o = new Out();
            o.ok = true;
            o.imagePath = SHOT_DIR.resolve(shotName).toString();
            o.atQq = s.ownerQq();
            o.guessText = g.say.isEmpty() ? ("我猜是「" + g.guess + "」") : cut(g.say, 40);
            System.out.println("[你画我猜] #" + s.gameId() + " 第" + round + "轮 猜=" + g.guess
                    + " 判=" + (correct ? "对" : "错") + " 变化=" + g.changes);

            if (correct) {
                finish(s, "win", "猜中");
                o.reaction = "是「" + g.guess + "」吧？我看出来了！";
                o.after = "🎉 猜对啦！答案就是「" + s.word() + "」，你画了 " + round + " 版\n"
                        + menuLine(true) + "\n（发 /画 3 开下一局）";
                o.ended = true;
            } else {
                o.reaction = wrongReaction(g.guess, round);
                if (config.getPicMaxRounds() > 0 && round >= config.getPicMaxRounds()) {
                    finish(s, "timeout", "到轮数上限");
                    o.after = "（已经 " + round + " 轮了，先揭晓答案吧）答案是「" + s.word() + "」\n" + menuLine(true);
                    o.ended = true;
                }
            }
            return o;
        }
    }

    private String wrongReaction(String guess, int round) {
        String[] tpl = {
                "唔…不是「%s」呀，你再改两笔？",
                "诶，猜错了吗？那我再仔细看看…",
                "唔…「%s」好像不对，再加点细节？",
                "差一点点？你动动哪块，我盯着呢。",
                "不是吗…那我重新看看这版画。"
        };
        String t = String.format(tpl[Math.abs(round * 31 + guess.hashCode()) % tpl.length], guess);
        return t;
    }

    // ============================================================
    //  结束
    // ============================================================

    /** 手动结束（玩家自己或管理员） */
    public Out endByUser(long userId) {
        QqPicSession s = byUser(userId);
        if (s == null) return Out.error("当前没有进行中的局");
        if (s.finished()) return Out.error("这一局已经结束了");
        if (s.ownerQq() != userId && !isAdmin(userId)) {
            return Out.error("只有画的人和管理员能结束这一局哦");
        }
        finish(s, "giveup", "手动结束");
        return summaryOut(s, "好，这一局先到这儿～");
    }

    /** 揭晓答案 */
    private Out summaryOut(QqPicSession s, String lead) {
        Out o = new Out();
        o.ok = true;
        o.ended = true;
        int rounds = mapper.countGuesses(s.gameId());
        o.lines.add(lead + "\n答案是「" + s.word() + "」" + (rounds > 0 ? "（猜了 " + rounds + " 轮）" : "")
                + "\n" + menuLine(true));
        return o;
    }

    private void finish(QqPicSession s, String state, String why) {
        s.finish();
        sessions.remove(s.key(), s);
        try {
            mapper.endGame(s.gameId(), state, mapper.countGuesses(s.gameId()));
        } catch (Exception e) {
            System.out.println("[你画我猜] 结束落库失败: " + e.getMessage());
        }
        System.out.println("[你画我猜] #" + s.gameId() + " 结束（" + state + " / " + why + "），答案 " + s.word());
    }

    /** 超时巡查：到点自动结束并公布答案 */
    private void tickTimeout() {
        try {
            long limitMs = config.getPicTimeoutMin() * 60_000L;
            for (QqPicSession s : new ArrayList<>(sessions.values())) {
                if (s.finished()) continue;
                if (System.currentTimeMillis() - s.startedAt() < limitMs) continue;
                String ans = s.word();
                finish(s, "timeout", "超时 " + config.getPicTimeoutMin() + " 分钟");
                String text = "⏰ 时间到（" + config.getPicTimeoutMin() + " 分钟），先揭晓答案："
                        + (s.isGroup() ? "「" + ans + "」" : "「" + ans + "」") + "\n" + menuLine(true);
                sendPlain(s, text);
            }
        } catch (Exception e) {
            System.out.println("[你画我猜] 超时巡查异常: " + e.getMessage());
        }
    }

    private void sendPlain(QqPicSession s, String text) {
        try {
            if (s.isGroup()) replyService.reply(s.chatId(), text, 0L);
            else replyService.replyPrivate(s.chatId(), text);
        } catch (Exception e) {
            System.out.println("[你画我猜] 超时通知发送失败: " + e.getMessage());
        }
    }

    // ============================================================
    //  页面侧
    // ============================================================

    public QqPicSession byToken(String token) {
        if (token == null || token.isEmpty()) return null;
        for (QqPicSession s : sessions.values()) {
            if (token.equals(s.token())) return restore(s);
        }
        try {
            QqPicGameRow row = mapper.findByToken(token);
            if (row == null) return null;
            QqPicSession s = QqPicSession.fromRow(row);
            if (s.finished()) return s;          // 已结束的局也给页面（要显示"被猜中了"）
            sessions.put(s.key(), s);
            return s;
        } catch (Exception e) {
            return null;
        }
    }

    private QqPicSession restore(QqPicSession s) { return s; }

    public QqPicSession byChat(String chatType, long chatId) {
        return sessions.get(chatType + ":" + chatId);
    }

    /** 这个人在哪个进行中的局里（群/私聊都认：私聊 key 是 private:QQ号） */
    public QqPicSession byUser(long userId) {
        QqPicSession g = sessions.get("private:" + userId);
        if (g != null && !g.finished()) return g;
        for (QqPicSession s : sessions.values()) {
            if (s.ownerQq() == userId && !s.finished()) return s;
        }
        return g;
    }

    /** 页面轮询用：只暴露这一局该知道的（题目给绘制者本人看，天经地义） */
    public Map<String, Object> stateOf(QqPicSession s) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("ok", true);
        m.put("gameId", s.gameId());
        m.put("playing", !s.finished());
        m.put("difficulty", s.difficulty());
        m.put("difficultyName", QqPicWordBank.difficultyName(s.difficulty()));
        m.put("wordLen", s.word() == null ? 0 : s.word().length());
        m.put("category", s.category());
        m.put("round", mapper.countGuesses(s.gameId()));
        m.put("maxRounds", config.getPicMaxRounds());
        m.put("elapsedSec", s.elapsedSec());
        m.put("timeoutSec", config.getPicTimeoutMin() * 60);
        m.put("owner", s.ownerNick());
        if (s.finished()) {
            // 结束时把答案交给页面（画的人当然该知道），顺便告诉它为什么结束
            m.put("answer", s.word());
        } else {
            m.put("word", s.word());   // 绘制者看题目：不给题目没法画
        }
        List<String> guessed = new ArrayList<>();
        for (QqPicGuessRow h : mapper.findGuesses(s.gameId())) {
            if (h.getGuess() != null && !h.getGuess().isEmpty()) guessed.add(h.getGuess());
        }
        m.put("guesses", guessed);
        return m;
    }

    // ============================================================
    //  出题（同群当天不重复 + 遍历完重置）
    // ============================================================

    /**
     * 出题。排除集合 = 本群今天已出过 ∪ 本局已出过。
     *
     * <p>三种兜底顺序：① 该难度还有没出过的 → 直接用；
     * ② 该难度今天出完了 → 从别的难度里挑一个没出过的（不至于卡死）；
     * ③ 全库都出过一轮了 → <b>清空本群当天记录重新开始</b>（老师要的"遍历之后重置"）。
     */
    public QqPicGameRow pickWord(long chatId, int difficulty, Set<Integer> sessionIssued) {
        Set<Integer> used = new LinkedHashSet<>(mapper.todayUsedWordIds(chatId));
        if (sessionIssued != null) used.addAll(sessionIssued);
        int d = difficulty <= 0 ? 1 : Math.min(3, difficulty);

        PictionaryWord w = mapper.pickWordNotIn(d, used);
        if (w == null) {
            w = mapper.pickAnyWordNotIn(used);
            if (w != null) {
                System.out.println("[你画我猜] 难度" + d + "今天已出完，改从其它难度出题：" + w.getWord());
            }
        }
        if (w == null) {
            mapper.resetTodayWords(chatId);
            used.clear();
            if (sessionIssued != null) used.addAll(sessionIssued);
            System.out.println("[你画我猜] 群/私聊 " + chatId + " 今天全部题目已遍历完 → 重置当天记录");
            w = mapper.pickWordNotIn(d, used);
            if (w == null) w = mapper.pickAnyWordNotIn(used);
        }
        if (w == null) return null;

        QqPicGameRow row = new QqPicGameRow();
        row.setWord_id(w.getId() == null ? 0 : w.getId());
        row.setWord(w.getWord());
        row.setAliases(w.getAliases() == null ? "" : w.getAliases());
        row.setCategory(w.getCategory() == null ? "" : w.getCategory());
        row.setDifficulty(w.getDifficulty() == null ? d : w.getDifficulty());
        // 一"出"就算用过：换题换掉的也算（老师明确要求）
        try {
            mapper.markWordUsedToday(chatId, row.getWord_id());
            mapper.bumpUsed(row.getWord_id());
        } catch (Exception e) {
            System.out.println("[你画我猜] 记录今日已出题失败: " + e.getMessage());
        }
        return row;
    }

    /** 本群今天出过多少题 / 词库总量（管理指令展示） */
    public String todayUsageLine(long chatId) {
        int used = mapper.countTodayUsedWords(chatId);
        int total = mapper.countEnabledWords();
        return "本群今天出过 " + used + " / " + total + " 题" + (total > 0 && used >= total ? "（已遍历完，下一题会重置）" : "");
    }

    public int todayUsed(long chatId) { return mapper.countTodayUsedWords(chatId); }

    public void resetTodayWords(long chatId) { mapper.resetTodayWords(chatId); }

    // ============================================================
    //  图片
    // ============================================================

    private BufferedImage decode(byte[] bytes) {
        try {
            if (bytes == null || bytes.length == 0) return null;
            // 允许前端直接传 data:image/png;base64,... 的整串
            String s = new String(bytes, StandardCharsets.ISO_8859_1);
            if (s.startsWith("data:")) {
                int comma = s.indexOf(',');
                if (comma > 0) bytes = Base64.getDecoder().decode(s.substring(comma + 1));
            }
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
            return img;
        } catch (Exception e) {
            return null;
        }
    }

    /** 落盘（顺带缩到最长边 768，省流量也省视觉 token） */
    private String saveShot(long gameId, int round, BufferedImage src) {
        String name = "g" + gameId + "_r" + round + ".jpg";
        try {
            Files.createDirectories(SHOT_DIR);
            BufferedImage out = scale(src, config.getPicImageMaxWidth());
            javax.imageio.ImageIO.write(out, "jpg", SHOT_DIR.resolve(name).toFile());
        } catch (Exception e) {
            System.out.println("[你画我猜] 画稿落盘失败: " + e.getMessage());
        }
        return name;
    }

    private static BufferedImage scale(BufferedImage src, int maxSide) {
        int w = src.getWidth(), h = src.getHeight();
        double k = Math.min(1.0, Math.min((double) maxSide / Math.max(1, w), (double) maxSide / Math.max(1, h)));
        if (k >= 1.0) return toRgb(src);
        int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static BufferedImage toRgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    private static String base64OfJpeg(BufferedImage img) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            ImageIO.write(toRgb(img), "jpg", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return "";
        }
    }

    private static String base64OfFile(File f) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    private static File shotFile(long gameId, int round) {
        return SHOT_DIR.resolve("g" + gameId + "_r" + round + ".jpg").toFile();
    }

    private static String newToken() {
        byte[] b = new byte[18];
        new java.security.SecureRandom().nextBytes(b);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > n ? t.substring(0, n) : t;
    }

    // ============================================================
    //  管理侧（/题库、/游戏 指令用）
    // ============================================================

    public QqPicMapper mapper() { return mapper; }

    public QqPicWordBank wordBank() { return wordBank; }

    public int sessionCount() { return sessions.size(); }
}
