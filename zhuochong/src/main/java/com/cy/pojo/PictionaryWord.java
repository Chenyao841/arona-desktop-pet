package com.cy.pojo;

import lombok.Data;

/**
 * 你画我猜 · 本地词库
 * 判对规则（老师定的）：**包含即算对 + 别名**
 *   猜的词里含答案 → 对；答案是猜的词的子串且猜的词≥2字 → 也算对；
 *   别名按同样的规则参与判定（如 自行车 的别名 单车/脚踏车）。
 */
@Data
public class PictionaryWord {
    private Integer id;
    private String word;        // 题面（画的人看到的词）
    private String aliases;     // 别名，| 分隔（可空）
    private String category;    // 类别：自然/物品/食物/动物/交通/运动/人物/乐器/建筑/科学
    private Integer difficulty; // 1 简单 2 中等 3 困难
    private Integer enabled;    // 1 参与出题
    private Integer used_count; // 出题次数（出题时优先挑用得少的，避免反复同一批）
    private String last_used;
}
