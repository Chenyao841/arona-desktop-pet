package com.cy.pojo;

import lombok.Data;

// 风格语料库：一条语料 = 一个场景（多行台词，可含【内心OS】/（动作））+ 一组概括性触发标签
@Data
public class StyleExemplar {
    private Integer id;
    private String tags;   // 概括性标签，用 、,， 空格 等分隔（如「被戳穿、偷懒、被抓包」）
    private String content; // 场景台词，多行
}
