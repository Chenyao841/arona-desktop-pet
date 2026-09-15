package com.cy.pojo;

import lombok.Data;

/** Live2D 表情映射：图片表情名 → Live2D 表情文件（可多个组合，逗号分隔） */
@Data
public class Live2dExpressionMap {
    private Integer id;
    private String image_name;
    private String expression_files;
}
