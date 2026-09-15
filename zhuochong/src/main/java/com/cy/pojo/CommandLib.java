package com.cy.pojo;

import lombok.Data;

import java.sql.Timestamp;

/** 指令库：项目内所有本地指令（按模块归类，可在设置页编辑，数据驱动生效） */
@Data
public class CommandLib {
    private Integer id;
    private String module;    // 模块：music/mahjong/gacha/live/live2d/game/base/vision/tts
    private String command;   // 指令词（多个同义词用 | 分隔）
    private String feature;   // 功能说明
    private Integer enabled;  // 1 启用 / 0 停用
    private Timestamp created_at;
}
