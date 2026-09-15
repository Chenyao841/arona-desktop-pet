package com.cy.pojo;

import lombok.Data;

@Data
public class AiPrompt {
    private Integer id;
    private String prompt_type;
    private String content;
    private Integer version;
    private String updated_at;
}
