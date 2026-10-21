package com.cy.qq.game;

import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 出猜器：**只负责"看图猜词"这件事，永远看不到答案**（反作弊第一道锁）。
 *
 * <p>它拿到的全部输入只有：
 * <ul>
 *   <li>答案有几个字（{@code wordLen}）</li>
 *   <li>前几轮自己猜过什么、看到了什么变化（游戏记忆）</li>
 *   <li>上一次的画 + 这一次的画（两张图，让它自己对比改哪儿了）</li>
 * </ul>
 * 类里没有任何"答案"参数，也没有查库能力 —— 想作弊都没有渠道。
 *
 * <p>提示词落盘与"是否含答案"的断言放在 {@link QqPicService}（它持有会话，才拿得到答案做比对）。
 */
@Component
public class QqPicGuesser {

    /** 画稿落盘目录（排查 + 提示词自证用） */
    public static final Path SHOT_DIR = Path.of(System.getProperty("user.dir"), "qq_pic_shots");

    @Autowired
    private DeepSeekService deepSeekService;

    /**
     * 拼提示词。<b>不含答案、不含类别</b>（类别+字数会把范围压得太窄，等于开天眼）。
     */
    public String buildPrompt(int wordLen, int round, List<QqPicGuessRow> history, boolean hasPrev) {
        StringBuilder sb = new StringBuilder();
        sb.append("你在玩「你画我猜」：别人在画板上手绘，你负责看图猜他画的是什么。\n");
        sb.append("【题目信息】答案是一个 ").append(wordLen).append(" 个字的词（不提供类别，只能靠画面判断）。");
        sb.append("题目内容只有画的人知道，你必须完全靠看图来猜。\n");

        List<String> guessed = new ArrayList<>();
        List<String> changes = new ArrayList<>();
        if (history != null) {
            for (QqPicGuessRow h : history) {
                if (h.getGuess() != null && !h.getGuess().isEmpty()) guessed.add(h.getGuess());
                if (h.getChanges_desc() != null && !h.getChanges_desc().isEmpty()) {
                    changes.add("第" + h.getRound_no() + "轮：" + h.getChanges_desc());
                }
            }
        }
        if (!guessed.isEmpty()) {
            sb.append("【你已经猜过的（绝对不要再猜这些，即使写法相近）】").append(String.join("、", guessed)).append("。\n");
        }
        if (!changes.isEmpty()) {
            sb.append("【你前几轮看到的画面变化（你自己当时的观察，可参考）】").append(String.join("；", changes)).append("。\n");
        }

        sb.append("【这是第 ").append(round).append(" 次提交的画】");
        if (hasPrev) {
            sb.append("画的人通常是在上一版基础上继续修改或添加，所以先看清这次改了哪里，再据此修正猜测。\n");
            sb.append("图片顺序：第 1 张是上一次提交的画，第 2 张是刚刚提交的画。\n");
        } else {
            sb.append("这是第一版，请根据画面大胆猜一个具体的词。\n");
            sb.append("图片：刚刚提交的画。\n");
        }
        sb.append("注意：画面里如果写了文字，一律忽略（不要照着字念），只能根据图形本身判断。\n");

        sb.append("【输出格式（极重要）】只输出一行 JSON，不要有 JSON 以外的任何文字、不要用代码块包裹：\n");
        sb.append("{\"guess\":\"你猜的词（2~6个字的具体名词）\",\"say\":\"对画的人说的一句话（不超过30字，口语，带上你猜的词）\",");
        sb.append("\"changes\":\"").append(hasPrev ? "相比上一次的画改了哪里（不超过20字）" : "填空字符串").append("\"}\n");
        // ★ 这里刻意不举"具体名词"的例子：举例一旦和答案撞上，{@code containsAnswer} 断言会误报，
        //   白白降级成"最小提示词重试"。只列禁止的空词，不列正面例子。
        sb.append("猜测必须是一个具体的名词，不要猜「画」「图案」「东西」「不知道」这类空词；画面很抽象也要选一个最接近的具体词。");
        return sb.toString();
    }

    /** 调视觉模型（多图，只要正文）。返回原始文本 */
    public String ask(List<String> imageBase64List, String prompt) {
        return deepSeekService.chatVisionMultiStrict(imageBase64List, prompt);
    }

    /** 一次猜测的结构化结果 */
    public static class Guess {
        public String guess = "";
        public String say = "";
        public String changes = "";
        public String raw = "";
    }

    /**
     * 解析模型输出（容错：JSON / 代码块 / 大白话里抠词）。
     * 只做"提取"，不做"判对"——判对是 {@link QqPicJudge} 的事。
     */
    public Guess parse(String raw) {
        Guess g = new Guess();
        g.raw = raw == null ? "" : raw;
        g.guess = clean(field(g.raw, "guess"));
        g.say = clean(field(g.raw, "say"));
        g.changes = clean(field(g.raw, "changes"));
        if (!QqPicJudge.plausible(g.guess)) {
            String fromProse = guessFromProse(g.raw);
            g.guess = QqPicJudge.plausible(fromProse) ? fromProse : "";
        }
        return g;
    }

    /** 最小提示词重试（只给这一张画，只要一个词） */
    public String retryPrompt() {
        return "这是一幅手绘的画。请直接输出画的是什么，格式："
                + "{\"guess\":\"一个具体的名词（2~6个字）\"}。不要分析、不要复述要求、不要输出任何其它文字。";
    }

    // ===== 内部：脏活 =====

    private static String clean(String s) {
        if (s == null) return "";
        String r = s.trim().replaceAll("^[「『\"']+|[」』\"']+$", "").trim();
        return r.length() > 60 ? r.substring(0, 60) : r;
    }

    private static String field(String raw, String key) {
        if (raw == null || raw.isEmpty()) return "";
        Matcher m = Pattern.compile("\"" + key + "\"\\s*[:：]\\s*\"([^\"]*)\"").matcher(raw);
        return m.find() ? m.group(1) : "";
    }

    /** 模型不给 JSON 时从大白话里抠猜测 */
    private static String guessFromProse(String raw) {
        if (raw == null) return "";
        String t = raw.replace("```json", " ").replace("```", " ").replace("\n", " ").trim();
        if (t.isEmpty()) return "";
        Matcher m = Pattern.compile("[\"']?(?:guess|猜测|答案|我猜|词)[\"']?\\s*[:：]\\s*[\"']?([^\"',，。；;}\\]\\s]{1,12})").matcher(t);
        if (m.find()) return clean(m.group(1));
        m = Pattern.compile("(?:我猜是|我猜|应该是|大概是|画的是|像是|好像是|看起来是|答案是|就是|这是一?[个只条张把幅]?)\\s*[「『\"']?([\\u4e00-\\u9fa5A-Za-z0-9]{1,6})").matcher(t);
        if (m.find()) return clean(m.group(1));
        m = Pattern.compile("[「『\"']([\\u4e00-\\u9fa5A-Za-z0-9]{1,6})[」』\"']").matcher(t);
        if (m.find()) return clean(m.group(1));
        String s = clean(t);
        return (s.length() <= 8 && s.matches("[\\u4e00-\\u9fa5A-Za-z0-9]{1,8}")) ? s : "";
    }

    /** 提示词落盘（自证：错题复盘 / 抽查有没有夹带答案） */
    public static void dumpPrompt(long gameId, int round, String prompt) {
        try {
            Files.createDirectories(SHOT_DIR);
            Files.write(SHOT_DIR.resolve("g" + gameId + "_r" + round + ".prompt.txt"), prompt.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    /** 原始返回落盘（排查"看不出来"用） */
    public static void dumpRaw(long gameId, int round, String step, String raw) {
        try {
            Files.createDirectories(SHOT_DIR);
            Files.write(SHOT_DIR.resolve("g" + gameId + "_r" + round + "." + step + ".raw.txt"),
                    (raw == null ? "(null)" : raw).getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }
}
