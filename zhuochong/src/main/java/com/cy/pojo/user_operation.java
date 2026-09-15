package com.cy.pojo;

import lombok.Data;

@Data
public class user_operation {
    private String user_name;
    private String user_operation;
    private String user_time;
    private String user_text;
    private String session_id;
    private String boat_text;
    private String motion;
    private String full_prompt;
    private String game_model;
    private Boolean live2d; // true=Live2D 模型模式：AI 输出每句情境（situation）而非情绪词
}
