package com.cy.qq.game;

/**
 * 一局 QQ 你画我猜。
 *
 * <p>字段名刻意和数据库列名一致（项目里 mybatis 是 {@code map-underscore-to-camel-case: false}）。
 *
 * <p><b>注意</b>：{@code word}/{@code aliases} 这两列只有 {@link QqPicJudge} 和"给绘制者看的页面"能读，
 * 出猜的 {@link QqPicGuesser} 一律不许碰（反作弊第一道锁）。
 */
public class QqPicGameRow {

    private long id;
    /** group / private */
    private String chat_type;
    private long chat_id;
    /** 绘制者 QQ */
    private long owner_qq;
    private String owner_nick;
    private int word_id;
    private String word;
    private String aliases;
    private String category;
    private int difficulty;
    /** playing / win / giveup / timeout / quit */
    private String state;
    private int round_count;
    /** 绘图页免登录凭证 */
    private String token;
    private String created_at;
    private String ended_at;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public String getChat_type() { return chat_type; }
    public void setChat_type(String chat_type) { this.chat_type = chat_type; }

    public long getChat_id() { return chat_id; }
    public void setChat_id(long chat_id) { this.chat_id = chat_id; }

    public long getOwner_qq() { return owner_qq; }
    public void setOwner_qq(long owner_qq) { this.owner_qq = owner_qq; }

    public String getOwner_nick() { return owner_nick; }
    public void setOwner_nick(String owner_nick) { this.owner_nick = owner_nick; }

    public int getWord_id() { return word_id; }
    public void setWord_id(int word_id) { this.word_id = word_id; }

    public String getWord() { return word; }
    public void setWord(String word) { this.word = word; }

    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public int getDifficulty() { return difficulty; }
    public void setDifficulty(int difficulty) { this.difficulty = difficulty; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public int getRound_count() { return round_count; }
    public void setRound_count(int round_count) { this.round_count = round_count; }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }

    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }

    public String getEnded_at() { return ended_at; }
    public void setEnded_at(String ended_at) { this.ended_at = ended_at; }

    public boolean playing() { return "playing".equals(state); }
}
