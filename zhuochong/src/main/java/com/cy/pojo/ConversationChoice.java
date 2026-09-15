package com.cy.pojo;

import lombok.Data;

@Data
public class ConversationChoice {
    private Integer id;
    private Integer choiceId;
    private String userOperation;
    private String choiceText;
    private String choiceAction;
    private String boatText;
    private String motion;
    private Integer nextChoiceId;
    private Integer visible;
    private String soundEffect;
    private String specialEffect;
}
