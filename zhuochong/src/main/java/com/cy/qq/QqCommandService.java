package com.cy.qq;

import com.cy.mapper.CommandLibMapper;
import com.cy.pojo.CommandLib;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 群内指令优先（进度文档 P0：「指令优先」）。
 *
 * <p>意思很直白：<b>先查指令库，命中就不花钱问模型</b>。
 * 指令库（{@code command_lib} 表，设置页可编辑）里每条有 {@code module / command / feature}，
 * {@code command} 允许用 {@code |} 写同义词。
 *
 * <p>匹配方式：
 * <ul>
 *   <li>带前缀 {@code /指令} 或 {@code ／指令} —— 群友明确在敲指令</li>
 *   <li>整句就是一个指令词（{@code 点歌}）—— 也算指令优先</li>
 * </ul>
 *
 * <p>P0 阶段真正执行功能的是桌面端，所以命中后回一句「这是哪个模块的指令 + 做什么 + QQ 端还没接」，
 * 既不误导群友、也不烧 token；P1/P2 再把各模块挂到 QQ 上。
 */
@Service
public class QqCommandService {

    /** 指令库模块名 → 中文（只说人话） */
    private static final java.util.Map<String, String> MODULE_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("music", "音乐"), java.util.Map.entry("mahjong", "麻将"),
            java.util.Map.entry("gacha", "抽卡"), java.util.Map.entry("live", "直播"),
            java.util.Map.entry("live2d", "立绘"), java.util.Map.entry("game", "小游戏"),
            java.util.Map.entry("base", "基础"), java.util.Map.entry("vision", "看屏幕"),
            java.util.Map.entry("tts", "语音"), java.util.Map.entry("qq", "QQ 群"),
            java.util.Map.entry("search", "联网")
    );

    @Autowired
    private CommandLibMapper commandLibMapper;

    /**
     * 尝试按指令处理。
     *
     * @return 需要直接回的文本；不是指令则返回 {@code null}（交给 AI）
     */
    public String tryHandle(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty()) return null;
        boolean explicit = t.startsWith("/") || t.startsWith("／");
        String body = explicit ? t.substring(1).trim() : t;
        if (body.isEmpty()) return explicit ? "指令后面要跟点内容哦，比如 /点歌" : null;

        String head = body.split("[\\s，,]+")[0];
        CommandLib hit = find(head);
        if (hit == null) {
            // 不带 / 的普通聊天不当作「不存在的指令」，继续走 AI
            return explicit ? "没有「" + head + "」这个指令呢（群里的指令走指令库，不花钱问模型）" : null;
        }
        String module = hit.getModule() == null ? "" : hit.getModule();
        String moduleName = MODULE_NAMES.getOrDefault(module, module);
        String feature = hit.getFeature() == null ? "" : hit.getFeature().trim();
        StringBuilder sb = new StringBuilder();
        sb.append("「").append(head).append("」");
        if (!moduleName.isEmpty()) sb.append("属于").append(moduleName).append("模块");
        if (!feature.isEmpty()) sb.append("：").append(feature);
        sb.append("。这个功能在桌面端才能用，QQ 群里还没接上～");
        System.out.println("[QQ] 指令命中: /" + head + "（模块 " + module + "）");
        return sb.toString();
    }

    private CommandLib find(String word) {
        if (word == null || word.isEmpty()) return null;
        List<CommandLib> all;
        try {
            all = commandLibMapper.findAll();
        } catch (Exception e) {
            System.out.println("[QQ] 读指令库失败（继续走 AI）: " + e.getMessage());
            return null;
        }
        if (all == null) return null;
        for (CommandLib c : all) {
            if (c == null || c.getCommand() == null) continue;
            if (c.getEnabled() != null && c.getEnabled() == 0) continue;
            for (String alias : c.getCommand().split("[|｜]")) {
                if (alias.trim().equalsIgnoreCase(word)) return c;
            }
        }
        return null;
    }
}
