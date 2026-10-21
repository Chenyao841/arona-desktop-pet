package com.cy.qq.game;

/** 一轮猜测记录（游戏记忆就建在这张表上：下次猜之前会把这些回灌给模型） */
public class QqPicGuessRow {

    private long id;
    private long game_id;
    private int round_no;
    private String guess;
    private int correct;
    /** 视觉模型给的"这一版相比上一版改了哪里"（≤128 字） */
    private String changes_desc;
    private String say;
    /** 画稿文件名（qq_pic_shots/ 下） */
    private String shot_path;
    private int tokens;
    private String created_at;

    public long getId() { return id; }
    public void setId(long id) { this.id = id; }

    public long getGame_id() { return game_id; }
    public void setGame_id(long game_id) { this.game_id = game_id; }

    public int getRound_no() { return round_no; }
    public void setRound_no(int round_no) { this.round_no = round_no; }

    public String getGuess() { return guess; }
    public void setGuess(String guess) { this.guess = guess; }

    public int getCorrect() { return correct; }
    public void setCorrect(int correct) { this.correct = correct; }

    public String getChanges_desc() { return changes_desc; }
    public void setChanges_desc(String changes_desc) { this.changes_desc = changes_desc; }

    public String getSay() { return say; }
    public void setSay(String say) { this.say = say; }

    public String getShot_path() { return shot_path; }
    public void setShot_path(String shot_path) { this.shot_path = shot_path; }

    public int getTokens() { return tokens; }
    public void setTokens(int tokens) { this.tokens = tokens; }

    public String getCreated_at() { return created_at; }
    public void setCreated_at(String created_at) { this.created_at = created_at; }
}
