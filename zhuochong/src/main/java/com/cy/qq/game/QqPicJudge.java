package com.cy.qq.game;

import org.springframework.stereotype.Component;

/**
 * 判对器：<b>全项目唯一允许读答案的地方</b>（需求：机器人绝对不能拿着答案去猜）。
 *
 * <p>它是纯代码，不调任何模型、不写库、没有状态：
 * <ul>
 *   <li>归一化后「猜的词里含答案」→ 对（猜"一支台灯"也算对）</li>
 *   <li>「答案里含猜的词」且猜词 ≥2 字 → 对（"台灯" 之于 "护眼台灯"）</li>
 *   <li>别名表命中 → 对（{@code 自行车;脚踏车|单车}，同义词靠这张表，不靠模型）</li>
 * </ul>
 *
 * <p>为什么不用第二个模型来判断："猜得像但不对"是视觉模型最容易犯的错，
 * 再上一个模型只会把这种错当对；纯代码零成本、可复现、不可能作弊。
 * 真需要补语义同义，再开"只给答案+猜测、绝不给画"的复核开关（默认关）。
 */
@Component
public class QqPicJudge {

    /**
     * @param word    答案
     * @param aliases 别名（{@code |} 或 {@code , / 、 ;} 分隔），可空
     * @param guess   模型猜的词
     * @return true = 猜对了
     */
    public boolean isCorrect(String word, String aliases, String guess) {
        String g = norm(guess);
        if (g.isEmpty() || word == null || word.isEmpty()) return false;
        if (matchOne(word, g)) return true;
        if (aliases != null && !aliases.isEmpty()) {
            for (String a : aliases.split("[|,，/、;；]")) {
                if (matchOne(a, g)) return true;
            }
        }
        return false;
    }

    private boolean matchOne(String target, String guessNorm) {
        String t = norm(target);
        if (t.isEmpty() || guessNorm.isEmpty()) return false;
        if (t.equals(guessNorm)) return true;          // 一模一样（单字答案也认）
        // 单字只认"完全一致"：否则答案「自行车」猜个「车」、答案「护眼台灯」猜个「灯」都会误判成对
        if (guessNorm.length() < 2) return false;
        if (guessNorm.contains(t)) return true;        // 猜里含答案（"一支台灯"）
        return t.contains(guessNorm);                  // 说出了答案里的核心词（"台灯"之于"护眼台灯"）
    }

    /** 去掉空白与标点/引号，便于比较 */
    public static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。、？！；：,.?!;:~～「」『』\"'（）()\\[\\]【】]", "").trim();
    }

    /**
     * 猜出来的东西像不像一个"词"：1~8 个汉字/字母数字、不含标点、不是"画/图案/东西"这类空词。
     * （视觉模型不给 JSON 时，会从大白话里抠词，这一步挡掉抠出来的垃圾。）
     */
    public static boolean plausible(String g) {
        if (g == null) return false;
        String s = g.trim().replaceAll("^[「『\"']+|[」』\"']+$", "").trim();
        if (s.isEmpty() || s.length() > 8) return false;
        if (!s.matches("[\\u4e00-\\u9fa5A-Za-z0-9]{1,8}")) return false;
        String[] empty = {"画", "图案", "东西", "图片", "什么", "不确定", "看不清", "看不出来", "未知", "物体", "图形"};
        for (String e : empty) if (e.equals(s)) return false;
        return true;
    }
}
