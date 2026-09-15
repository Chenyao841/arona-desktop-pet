package com.cy.pojo;

import lombok.Data;

import java.sql.Timestamp;

@Data
public class EventBook {
    private Integer id;
    private String event_type;   // 事件类型：rub/click/milk/music/game/live/...
    private String content;      // 事件描述（给 AI 看的中文）
    private Timestamp created_at;
}
