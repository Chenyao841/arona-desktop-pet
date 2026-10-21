package com.cy.qq.game;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 一局的内存会话。
 *
 * <p>⚠ 这个对象里带着**答案**（{@link #word()}），所以只允许三处读它：
 * <ol>
 *   <li>{@link QqPicJudge}（判对）</li>
 *   <li>绘图页（绘制者看题目，天经地义）</li>
 *   <li>日志</li>
 * </ol>
 * 出猜的 {@link QqPicGuesser} 只能拿到"字数 + 历史"，<b>不许</b>接触这个对象。
 */
public class QqPicSession {

    private final long gameId;
    private final String chatType;
    private final long chatId;
    private final long ownerQq;
    private final String ownerNick;
    private final String token;
    private final long startedAt = System.currentTimeMillis();

    private int difficulty;
    private int wordId;
    private String word;
    private String aliases;
    private String category;
    /** 本局已经出过的词 id（换题不重复，且都算"今天用过了"） */
    private final Set<Integer> issuedWordIds = new LinkedHashSet<>();
    /** 上一次"换题/换难度"的时间戳（冷却用） */
    private volatile long lastSwitchAt = 0L;
    /** 被机器人猜中 / 超时 / 手动结束 → 页面锁死 */
    private volatile boolean finished = false;
    /** 提交串行化：同一个人可能连点两次提交 */
    private final Object submitLock = new Object();

    public QqPicSession(long gameId, String chatType, long chatId, long ownerQq, String ownerNick, String token) {
        this.gameId = gameId;
        this.chatType = chatType;
        this.chatId = chatId;
        this.ownerQq = ownerQq;
        this.ownerNick = ownerNick;
        this.token = token;
    }

    /** 从数据库行恢复（重启/页面重连） */
    public static QqPicSession fromRow(QqPicGameRow g) {
        QqPicSession s = new QqPicSession(g.getId(), g.getChat_type(), g.getChat_id(),
                g.getOwner_qq(), g.getOwner_nick(), g.getToken());
        s.difficulty = g.getDifficulty();
        s.wordId = g.getWord_id();
        s.word = g.getWord();
        s.aliases = g.getAliases();
        s.category = g.getCategory();
        if (g.getWord_id() > 0) s.issuedWordIds.add(g.getWord_id());
        s.finished = !g.playing();
        return s;
    }

    public long gameId() { return gameId; }
    public String chatType() { return chatType; }
    public long chatId() { return chatId; }
    public long ownerQq() { return ownerQq; }
    public String ownerNick() { return ownerNick == null ? ("QQ" + ownerQq) : ownerNick; }
    public String token() { return token; }
    public long startedAt() { return startedAt; }

    public int difficulty() { return difficulty; }
    public void setDifficulty(int d) { this.difficulty = d; }

    public int wordId() { return wordId; }
    /** ⚠ 答案：只给判对器和绘图页 */
    public String word() { return word; }
    public String aliases() { return aliases == null ? "" : aliases; }
    public String category() { return category == null ? "" : category; }

    public void setWord(QqPicGameRow w) {
        this.wordId = w.getWord_id();
        this.word = w.getWord();
        this.aliases = w.getAliases();
        this.category = w.getCategory();
        this.difficulty = w.getDifficulty();
        if (this.wordId > 0) issuedWordIds.add(this.wordId);
    }

    public Set<Integer> issuedWordIds() { return issuedWordIds; }

    public long lastSwitchAt() { return lastSwitchAt; }
    public void markSwitch() { this.lastSwitchAt = System.currentTimeMillis(); }

    public boolean finished() { return finished; }
    public void finish() { this.finished = true; }

    public Object submitLock() { return submitLock; }

    /** 本局已进行多少秒 */
    public long elapsedSec() { return (System.currentTimeMillis() - startedAt) / 1000L; }

    /** 会话键：一个群同时只允许一局 */
    public String key() { return chatType + ":" + chatId; }

    public boolean isGroup() { return "group".equals(chatType); }
}
