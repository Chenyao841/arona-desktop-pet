package com.cy.pojo;

import lombok.Data;

@Data
public class IdleActivity {
    private Integer id;
    private String theme;
    private String category; // continuable 延续性 / simple 单发难续写 / sleep 睡觉
    private Integer enabled; // 1 启用 / 0 禁用
}
