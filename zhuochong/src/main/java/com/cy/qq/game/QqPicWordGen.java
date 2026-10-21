package com.cy.qq.game;

import com.cy.pojo.PictionaryWord;
import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 题库生成器：让模型**离线**吐一批新词，人工确认后进词库（运行时不联网找题）。
 *
 * <p>为什么不让模型在开局时"现场想题"：想题太随机（可能出"量子纠缠"这种没法画的），
 * 而且没有别名表 → 判对只能靠字面。所以做成"一次性生成 + 落库 + 谁都能审"的模式，
 * 之后出题只从库里挑，稳定、可复现、能被 {@code /题库 删} 纠错。
 *
 * <p>"完全不同"是硬约束：把现有词库（含别名）整份塞给模型当黑名单，
 * 落库前**再过滤一次**（模型偶尔不听话，这一步兜住）。
 */
@Component
public class QqPicWordGen {

    /** 一次生成多少词（太多容易超时/被截断） */
    public static final int BATCH = 100;

    @Autowired
    private DeepSeekService deepSeekService;

    /** 生成结果 */
    public static class Result {
        public final List<PictionaryWord> words = new ArrayList<>();
        /** 模型给的原始文本（排查用） */
        public String raw = "";
        /** 模型返回了多少条、被过滤掉多少 */
        public int parsed = 0;
        public int droppedDup = 0;
        public int droppedBad = 0;
        public String error;
    }

    /**
     * 生成一批新词。
     *
     * @param count 目标数量（建议 100；分布按 简单40 / 中等40 / 困难20）
     * @param avoid 现有词（含别名），新词不能和它们重合
     */
    public Result generate(int count, Set<String> avoid) {
        Result r = new Result();
        int n = Math.max(10, Math.min(200, count));
        int easy = (int) Math.round(n * 0.4), mid = (int) Math.round(n * 0.4);
        int hard = Math.max(1, n - easy - mid);

        String system = "你是一个「你画我猜」词库编辑。你只输出 JSON，不解释、不寒暄。";
        StringBuilder user = new StringBuilder();
        user.append("请生成 ").append(n).append(" 个适合「你画我猜」的题目，难度分布：简单 ").append(easy)
                .append(" 个、中等 ").append(mid).append(" 个、困难 ").append(hard).append(" 个。\n");
        user.append("要求：\n");
        user.append("1. 必须是**能用简笔画画出来的具体名词**（名词为主，少量可画的动词/场景），不要抽象概念、不要人名、不要长句。\n");
        user.append("2. 每个词 1~4 个汉字最佳；同一个词只出现一次。\n");
        user.append("3. 每个词给 0~2 个别名（别人可能怎么叫它，用 | 分隔），没有就给空字符串。\n");
        user.append("4. 给一个简短的类别（动物/食物/物品/交通/自然/人物/建筑/运动/乐器/科学/日常 之类）。\n");
        user.append("5. 难度：简单=一眼能认出的常见东西；中等=需要画细节；困难=概念稍微绕一点或组合物。\n");
        user.append("6. **绝对不能**和下面这些已有的词或别名重复（一个都不行，包括近义写法）：\n");
        user.append(joinForPrompt(avoid)).append('\n');
        user.append("输出格式（只输出这个 JSON 数组，不要代码块、不要注释）：\n");
        user.append("[{\"word\":\"台灯\",\"aliases\":\"护眼灯\",\"category\":\"物品\",\"difficulty\":1}, ...]");

        String raw;
        try {
            raw = deepSeekService.chatPlain(system, user.toString(), 6000, "pic");
        } catch (Exception e) {
            r.error = "调模型失败：" + e.getMessage();
            return r;
        }
        r.raw = raw == null ? "" : raw;
        if (r.raw.isEmpty()) {
            r.error = "模型没有返回内容（检查 ai_config 的 key / 网络）";
            return r;
        }

        Set<String> seen = new LinkedHashSet<>();
        for (String a : avoid) seen.add(norm(a));

        Matcher m = Pattern.compile("\\{([^{}]*)\\}").matcher(r.raw);
        while (m.find() && r.words.size() < n) {
            String obj = "{" + m.group(1) + "}";
            String word = field(obj, "word");
            if (word == null || word.isEmpty()) continue;
            r.parsed++;
            String w = word.trim();
            if (!QqPicJudge.plausible(w)) {
                r.droppedBad++;
                continue;
            }
            String key = norm(w);
            if (seen.contains(key)) {
                r.droppedDup++;
                continue;
            }
            String aliases = field(obj, "aliases");
            // 别名里如果撞了已有词也算重复（防止"台灯"别名写成"落地灯"而落地灯已在库）
            boolean aliasDup = false;
            if (aliases != null && !aliases.isEmpty()) {
                for (String a : aliases.split("[|,，/、;；]")) {
                    if (seen.contains(norm(a))) {
                        aliasDup = true;
                        break;
                    }
                }
            }
            if (aliasDup) {
                r.droppedDup++;
                continue;
            }
            seen.add(key);

            PictionaryWord pw = new PictionaryWord();
            pw.setWord(w);
            pw.setAliases(aliases == null ? "" : aliases.trim());
            pw.setCategory(trim(field(obj, "category"), 8));
            pw.setDifficulty(difficultyOf(field(obj, "difficulty")));
            pw.setEnabled(1);
            r.words.add(pw);
        }
        if (r.words.isEmpty()) {
            r.error = "一条都没解析出来（模型输出格式不对）\n原始前 300 字：" + head(r.raw, 300);
        }
        return r;
    }

    // ===== 小工具 =====

    private static String joinForPrompt(Set<String> avoid) {
        if (avoid == null || avoid.isEmpty()) return "（暂无，随便出）";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (String w : avoid) {
            if (i++ > 0) sb.append('、');
            sb.append(w);
            if (i >= 400) { sb.append(" …"); break; }
        }
        return sb.toString();
    }

    private static String norm(String s) {
        return QqPicJudge.norm(s);
    }

    private static String field(String obj, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(obj);
        if (m.find()) return m.group(1);
        // 难度可能给成数字
        m = Pattern.compile("\"" + key + "\"\\s*:\\s*(\\d+)").matcher(obj);
        return m.find() ? m.group(1) : "";
    }

    private static int difficultyOf(String v) {
        if (v == null || v.isEmpty()) return 1;
        try {
            int d = Integer.parseInt(v.trim());
            return d < 1 ? 1 : Math.min(3, d);
        } catch (Exception ignored) {
        }
        int d = QqPicWordBank.parseDifficulty(v);
        return d <= 0 ? 1 : d;
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    private static String head(String s, int n) {
        if (s == null) return "";
        String t = s.replace("\n", " ");
        return t.length() > n ? t.substring(0, n) + "…" : t;
    }
}
