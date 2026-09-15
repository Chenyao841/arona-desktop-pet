package com.cy.pojo;

import lombok.Data;

/**
 * 你画我猜 · 单轮猜测记录（桌宠的「游戏记忆」就建在这张表上）
 * 每轮保存：桌宠猜了什么、它自己怎么说的、它说画面「变了哪里」、对错、本轮画稿文件名。
 * 下一轮把这些回灌进提示词 → 桌宠能记住「我已经猜过 XXX」「这次比上次画多了什么」。
 */
@Data
public class PictionaryGuess {
    private Integer id;
    private Integer game_id;
    private Integer round_no;      // 第几轮（老师第几次提交画作）
    private String guess;          // 桌宠猜的词
    private String say;            // 桌宠这轮说的话
    private String changes_desc;   // 桌宠看出的「与上一次相比变了什么」
    private Integer correct;       // 1 猜对
    private String shot;           // 本轮画稿文件名（pictionary_shots/ 下）
    private String created_at;
}
