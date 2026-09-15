package com.cy.pojo;

import lombok.Data;

/**
 * 你画我猜 · 一局游戏
 * ★ 答案只活在这里（游戏模块）和画板窗口的界面上：
 *   给模型的提示词里永远不出现答案，所以模型不会「读心」作弊；
 *   **不写 pet_memory**（老师定的：答案不进长期记忆）；
 *   但**一局结束时由桌宠页写一条事件簿记录**（只记"猜对/没猜对、难度、轮次、是否一次就中"，**不含答案**），
 *   这样老师随后夸一句「阿罗娜真棒」时，桌宠能从事件簿知道是刚才画猜发挥好被夸；
 *   战绩（输赢/轮次/耗时）只记在本表里。
 */
@Data
public class PictionaryGame {
    private Integer id;
    private String word;        // 答案
    private String aliases;     // 答案别名（判对用）
    private String category;
    private Integer difficulty;
    private String started_at;
    private String ended_at;
    private String result;      // win 猜对 / give_up 老师公布答案 / quit 中途关窗或开新局
    private Integer guess_count;
    private Integer duration_sec;
}
