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
