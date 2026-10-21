package com.cy.qq.game;

import com.cy.mapper.PictionaryMapper;
import com.cy.pojo.PictionaryWord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 出题：复用桌宠那张词库 {@code pictionary_word}（100 词，带别名/类别/难度 1-3）。
 *
 * <p>为什么用本地词库而不是现爬：
 * <ul>
 *   <li>判对靠别名表（同义词），爬来的词没有别名 → "脚踏车"猜对也被判错</li>
 *   <li>爬源的稳定性、编码、合规都要额外处理，收益却是零</li>
 *   <li>本地词库能按难度分级、能统计 {@code used_count} 让冷门词也有机会</li>
 * </ul>
 * 需要更多题目就往表里加（或让模型离线批量生成、人工过一遍再入库），运行时不联网。
 */
@Component
public class QqPicWordBank {

    @Autowired
    private PictionaryMapper mapper;

    /**
     * 出一题。
     *
     * @param difficulty 1 简单 / 2 中等 / 3 困难
     * @param excludeIds 本局已经出过的词 id（换题时避免重复）
     * @return 题目；词库空则 null
     */
    public QqPicGameRow pick(int difficulty, Set<Integer> excludeIds) {
        int d = Math.max(1, Math.min(3, difficulty));
        // 随机挑，撞上本局出过的就重挑（最多试 6 次，避免死循环）
        for (int i = 0; i < 6; i++) {
            PictionaryWord w = mapper.pickWord(d);
            if (w == null) return null;
            if (excludeIds != null && excludeIds.contains(w.getId())) continue;
            return toRow(w);
        }
        // 6 次都撞：放宽到不限难度再试一次（词库该难度可能只有一两个词）
        PictionaryWord w = mapper.pickWord(0);
        return w == null ? null : toRow(w);
    }

    /** 词总数（管理指令展示用） */
    public int total() {
        try {
            return mapper.countWords();
        } catch (Exception e) {
            return 0;
        }
    }

    // ===== 管理员维护词库（/题库 …）=====

    /** 全部词（含禁用的），按难度/类别排序 */
    public java.util.List<PictionaryWord> all() {
        try {
            return mapper.findAllWords();
        } catch (Exception e) {
            return new java.util.ArrayList<>();
        }
    }

    /** 新增一个词（同名词会被唯一键挡住，异常交给调用方报错） */
    public void save(PictionaryWord w) {
        mapper.insertWord(w);
    }

    public boolean remove(int id) {
        return mapper.deleteWord(id) > 0;
    }

    public boolean setAliases(int id, String aliases) {
        return patch(id, w -> w.setAliases(aliases == null ? "" : aliases.trim()));
    }

    public boolean setDifficulty(int id, int difficulty) {
        int d = Math.max(1, Math.min(3, difficulty));
        return patch(id, w -> w.setDifficulty(d));
    }

    public boolean setEnabled(int id, boolean enabled) {
        return patch(id, w -> w.setEnabled(enabled ? 1 : 0));
    }

    /** 读一行 → 改一个字段 → 写回（省得给每个字段写一条 update） */
    private boolean patch(int id, java.util.function.Consumer<PictionaryWord> change) {
        PictionaryWord w = findById(id);
        if (w == null) return false;
        change.accept(w);
        return mapper.updateWord(w) > 0;
    }

    /** 按 id 找词（跨表查到桌宠的 pictionary_word；本类没有专门依赖，先按全表匹配） */
    public PictionaryWord findById(int id) {
        for (PictionaryWord w : all()) {
            if (w.getId() != null && w.getId() == id) return w;
        }
        return null;
    }

    // ===== 刷题库：让模型生成一批"完全不同"的新词并入库 =====

    @Autowired
    private QqPicWordGen wordGen;

    /**
     * 刷新题库：生成 {@code count} 个和现有词库（含别名）完全不重合的新词，追加进词库。
     *
     * <p>为什么是"追加"不是"替换"：词库和桌宠共用一张表，直接删会连带把桌宠的题换掉；
     * 想清理旧的用 {@code /题库 删 <id>} 或 {@code /题库 禁用 <id>}。
     *
     * @return 给管理员看的结果文本
     */
    public String refresh(int count) {
        java.util.Set<String> avoid = new java.util.LinkedHashSet<>();
        for (PictionaryWord w : all()) {
            if (w.getWord() != null && !w.getWord().trim().isEmpty()) avoid.add(w.getWord().trim());
            if (w.getAliases() != null) {
                for (String a : w.getAliases().split("[|,，/、;；]")) {
                    if (!a.trim().isEmpty()) avoid.add(a.trim());
                }
            }
        }
        int before = avoid.isEmpty() ? 0 : mapper.countWords();
        System.out.println("[你画我猜] 开始刷新题库：目标 " + count + " 个，现有 " + before + " 个词（含别名共 "
                + avoid.size() + " 个黑名单）");

        QqPicWordGen.Result r = wordGen.generate(count, avoid);
        if (r.error != null && r.words.isEmpty()) {
            System.out.println("[你画我猜] 刷新题库失败: " + r.error);
            return "❌ 刷新失败：" + r.error;
        }

        int ok = 0, dup = 0, easy = 0, mid = 0, hard = 0;
        StringBuilder failed = new StringBuilder();
        for (PictionaryWord w : r.words) {
            try {
                mapper.insertWord(w);
                ok++;
                int d = w.getDifficulty() == null ? 1 : w.getDifficulty();
                if (d <= 1) easy++;
                else if (d == 2) mid++;
                else hard++;
            } catch (Exception e) {
                dup++;
                if (failed.length() < 80) failed.append(w.getWord()).append(' ');
            }
        }
        int after = mapper.countWords();
        System.out.println("[你画我猜] 刷新题库完成：新增 " + ok + "（简单 " + easy + " / 中等 " + mid
                + " / 困难 " + hard + "），重复跳过 " + (dup + r.droppedDup) + "，格式不合格 " + r.droppedBad
                + "；词库 " + before + " → " + after);

        StringBuilder sb = new StringBuilder("✅ 题库刷新完成\n");
        sb.append("新增 ").append(ok).append(" 个（简单 ").append(easy).append(" / 中等 ").append(mid)
                .append(" / 困难 ").append(hard).append("）\n");
        if (r.droppedDup + dup > 0) sb.append("跳过重复 ").append(r.droppedDup + dup).append(" 个");
        if (r.droppedBad > 0) sb.append(sb.charAt(sb.length() - 1) == '\n' ? "" : "，").append("格式不合格 ").append(r.droppedBad).append(" 个");
        if (r.droppedDup + dup > 0 || r.droppedBad > 0) sb.append('\n');
        if (failed.length() > 0) sb.append("入库失败（多半是同名）：").append(failed.toString().trim()).append('\n');
        sb.append("词库共 ").append(after).append(" 个词\n");
        sb.append("看新词：/题库 列表　不满意：/题库 删 <id> 或 /题库 禁用 <id>");
        return sb.toString();
    }

    private QqPicGameRow toRow(PictionaryWord w) {
        QqPicGameRow r = new QqPicGameRow();
        r.setWord_id(w.getId() == null ? 0 : w.getId().intValue());
        r.setWord(w.getWord());
        r.setAliases(w.getAliases() == null ? "" : w.getAliases());
        r.setCategory(w.getCategory() == null ? "" : w.getCategory());
        r.setDifficulty(w.getDifficulty() == null ? 1 : w.getDifficulty());
        try {
            mapper.bumpUsed(w.getId());     // 让冷门词也有机会被抽到
        } catch (Exception ignored) {
        }
        return r;
    }

    /** 难度中文名 */
    public static String difficultyName(int d) {
        switch (d) {
            case 1: return "简单";
            case 2: return "中等";
            case 3: return "困难";
            default: return "不限";
        }
    }

    /** 指令里的难度词 → 1/2/3（认不出来返回 0） */
    public static int parseDifficulty(String s) {
        if (s == null) return 0;
        String t = s.trim().toLowerCase();
        if (t.isEmpty()) return 0;
        if (t.startsWith("简单") || t.startsWith("易") || t.equals("1") || t.startsWith("easy")) return 1;
        if (t.startsWith("中等") || t.startsWith("中") || t.equals("2") || t.startsWith("normal")) return 2;
        if (t.startsWith("困难") || t.startsWith("难") || t.equals("3") || t.startsWith("hard")) return 3;
        return 0;
    }
}
