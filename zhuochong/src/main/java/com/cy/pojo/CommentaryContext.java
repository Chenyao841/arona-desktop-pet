package com.cy.pojo;

import lombok.Data;

/** 陪玩点评的"语境"（B站/麻将/蔚蓝档案/办公/其它），prompt 为拼进视觉提示词的风格要求 */
@Data
public class CommentaryContext {
    private Integer id;
    private String code;     // bilibili / mahjong / bluearchive / work / other
    private String label;    // 中文名（设置页显示、给模型看的语境名）
    private String prompt;   // 该语境的风格与限制
    private Integer enabled; // 1 参与自动判定
}
