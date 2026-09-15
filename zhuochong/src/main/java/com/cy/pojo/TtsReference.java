package com.cy.pojo;

import lombok.Data;

@Data
public class TtsReference {
    private Integer id;
    private String wav_path;
    private String prompt_text;
    private Integer enabled;
}
