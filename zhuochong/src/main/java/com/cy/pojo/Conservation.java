package com.cy.pojo;

import lombok.Data;

@Data
public class Conservation {
    private Integer id;
    private String user_id;
    private String user_text;
    private String boat_text;
    private String motion;
    private Integer choice_id;
    private String user_operation;
    private String role;
    private String sound_effect;
    private String special_effect;
}
