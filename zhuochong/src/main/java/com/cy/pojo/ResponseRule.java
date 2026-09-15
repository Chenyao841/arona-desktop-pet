package com.cy.pojo;

import lombok.Data;

@Data
public class ResponseRule {
    private Integer id;
    private String keyword;
    private String motion;
    private String sound_effect;
    private String special_effect;
    private String action;
    private Integer weight;
    private String sample_text;
    private Integer grow_count; // 短词生长成功次数（越大生长概率越低）
    private Integer cooldown;  // 生长触发冷却剩余命中次数（>0 表示冷却中）
}
