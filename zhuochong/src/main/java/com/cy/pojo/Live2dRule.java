package com.cy.pojo;

import lombok.Data;

@Data
public class Live2dRule {
    private Integer id;
    private String boat_text;
    private String situation;      // 自由文本，多个用 , 或 ; 分隔
    private String expression;     // 表情部件选择 JSON，如 {"眼部":"眯眼舒适","脸部":"脸红"}
    private String action;         // 动作（bounce/drop/escape/... 或空）
    private String sound_effect;   // 音效（soundeffects/xxx.webm 或空）
    private String special_effect; // 特效（可空）
}
