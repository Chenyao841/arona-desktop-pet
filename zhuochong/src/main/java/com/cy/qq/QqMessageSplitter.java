package com.cy.qq;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 分段算法（P0 里唯一值得单独抽出来 + 值得单测的纯函数）。
 *
 * <p>规则（来自进度文档）：按 {@code \n\n} 分段 → 段内按句切 → 每条 ≤ {@code maxLen} 字 →
 * 条数超过 {@code maxChunks} 就截断（最后一条补省略号）。发送间隔由
 * {@link QqReplyService} 负责（400~800ms 随机）。
 *
 * <p>为什么要切：QQ 单条消息太长会被折叠/截断，而且一段一大块在群里看着很闷。
 */
public final class QqMessageSplitter {

    /** 句末标点后断开（保留标点） */
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[。！？!?…；;～~])");

    /** 硬切时优先在这些字符后面断开 */
    private static final String SOFT_BREAK_CHARS = "，,、；;…：: 　\t";

    private QqMessageSplitter() {
    }

    /**
     * @param text      原始回复文本
     * @param maxLen    单条上限（字符数），≤0 时按 300
     * @param maxChunks 最多几条，≤0 表示不限制
     */
    public static List<String> split(String text, int maxLen, int maxChunks) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        int limit = maxLen > 0 ? maxLen : 300;
        String norm = normalize(text);
        if (norm.isEmpty()) return out;

        // 1) 空行分段
        for (String para : norm.split("\n{2,}")) {
            String p = para.trim();
            if (p.isEmpty()) continue;
            // 2) 段内按句切；3) 贪心合并到 ≤ limit；4) 超长句硬切
            StringBuilder cur = new StringBuilder();
            for (String sentence : splitSentences(p)) {
                for (String piece : hardSplit(sentence, limit)) {
                    if (piece.isEmpty()) continue;
                    if (cur.length() == 0) {
                        cur.append(piece);
                    } else if (cur.length() + piece.length() <= limit) {
                        cur.append(piece);
                    } else {
                        out.add(cur.toString());
                        cur.setLength(0);
                        cur.append(piece);
                    }
                }
            }
            if (cur.length() > 0) out.add(cur.toString());
        }

        // 5) 条数上限：话痨截断，最后一条补省略号
        if (maxChunks > 0 && out.size() > maxChunks) {
            List<String> capped = new ArrayList<>(out.subList(0, maxChunks));
            int last = capped.size() - 1;
            capped.set(last, capped.get(last) + "…");
            out = capped;
        }

        out.removeIf(s -> s == null || s.trim().isEmpty());
        return out;
    }

    /** 统一换行、清理零宽字符、把 AI 偶尔输出的字面 \n 还原 */
    private static String normalize(String text) {
        String s = text.replace("\r\n", "\n").replace("\r", "\n");
        s = s.replace("\\n", "\n");
        s = s.replace("\u200b", "").replace("\u200e", "").replace("\ufeff", "");
        s = s.replaceAll("\n{3,}", "\n\n");
        return s.trim();
    }

    private static List<String> splitSentences(String para) {
        List<String> out = new ArrayList<>();
        boolean firstLine = true;
        for (String line : para.split("\n")) {
            String l = line.trim();
            if (l.isEmpty()) continue;
            boolean firstSentenceOfLine = true;
            for (String s : SENTENCE_END.split(l)) {
                String t = s.trim();
                if (t.isEmpty()) continue;
                // ★ 行内换行要保留：管理指令报表（/status、/groups）是一行一条，
                //   合并成一条消息时如果丢掉 \n，群里就会看到一整坨字。
                if (firstSentenceOfLine && !firstLine) t = "\n" + t;
                firstSentenceOfLine = false;
                out.add(t);
            }
            firstLine = false;
        }
        return out;
    }

    /** 单句仍然超长时硬切：尽量在逗号/顿号/空格处断开，切不动就按 limit 硬断 */
    private static List<String> hardSplit(String sentence, int limit) {
        List<String> out = new ArrayList<>();
        // 开头若是"换行前缀"（多行文本合并用），trim 会把它吃掉，先记下来再补回去
        boolean leadingNewline = sentence.startsWith("\n");
        String rest = sentence.trim();
        if (rest.isEmpty()) return out;
        while (rest.length() > limit) {
            int floor = Math.max(limit / 2, limit - 40);
            int cut = -1;
            for (int i = limit; i > floor; i--) {
                if (SOFT_BREAK_CHARS.indexOf(rest.charAt(i - 1)) >= 0) {
                    cut = i;
                    break;
                }
            }
            if (cut <= 0) cut = limit;
            String head = rest.substring(0, cut).trim();
            if (!head.isEmpty()) out.add(head);
            rest = rest.substring(cut).trim();
        }
        if (!rest.isEmpty()) out.add(rest);
        if (leadingNewline && !out.isEmpty()) out.set(0, "\n" + out.get(0));
        return out;
    }
}
