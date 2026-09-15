package com.cy.pojo;

import lombok.Data;

@Data
public class PetMemory {
    private Integer id;
    private String character_name;
    private String content;
    private java.sql.Timestamp created_at;
    private java.sql.Timestamp updated_at;
}
