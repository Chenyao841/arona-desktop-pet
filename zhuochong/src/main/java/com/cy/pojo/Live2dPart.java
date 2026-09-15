package com.cy.pojo;

import lombok.Data;

@Data
public class Live2dPart {
    private Integer id;
    private String part_group; // 区域：眼部/嘴部/脸部/手部
    private String part_name;  // 部件名（对应表情）
    private String params;     // 参数 JSON 字符串
}
