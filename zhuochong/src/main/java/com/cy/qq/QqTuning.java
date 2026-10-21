package com.cy.qq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 运行时可调的节奏参数（管理员用指令随时改，重启回到配置默认值）。
 *
 * <ul>
 *   <li>{@code /delay 3}   —— 固定回复延迟（0 = 立刻回）</li>
 *   <li>{@code /cooldown 0} —— 同人冷却（0/off = 关闭冷却）</li>
 *   <li>{@code /quote off} —— 引用回复开关</li>
 * </ul>
 *
 * 为什么单独一个类：这些值事件线程和管理指令线程都要读，放这里比塞进 QqConfig（只读）清晰。
 */
@Component
public class QqTuning {

    @Autowired
    private QqConfig config;

    /** -1 = 用配置文件；0 = 关闭冷却 */
    private volatile int cooldownOverride = -1;
    /** -1 = 用配置文件 */
    private volatile int delayOverride = -1;
    /** null = 用配置文件 */
    private volatile Boolean quoteOverride = null;
    /** -1 = 用配置文件；表情包最小间隔（中间隔几条回复） */
    private volatile int emojiGapOverride = -1;
    /** null = 用配置文件 */
    private volatile Boolean emojiEnabledOverride = null;
    /** 复读机：-1 / null = 用配置文件 */
    private volatile int parrotProbOverride = -1;
    private volatile Boolean parrotEnabledOverride = null;
    /** 护短：null = 用配置文件 */
    private volatile Boolean defendEnabledOverride = null;
    /** 护短的管理员叫法：null = 用配置文件（/defend alias 改的就是这个） */
    private volatile java.util.Set<String> defendAliasOverride = null;

    /** 群友怎么叫管理员才算"提到他"：配置里的 + 运行时改过的（二选一，运行时优先） */
    public java.util.Set<String> defendAliases() {
        java.util.Set<String> v = defendAliasOverride;
        if (v != null) return new java.util.LinkedHashSet<>(v);
        return config.getDefendAdminAlias();
    }

    public void setDefendAliases(java.util.Set<String> aliases) {
        defendAliasOverride = aliases == null ? null : new java.util.LinkedHashSet<>(aliases);
        System.out.println("[QQ] 护短的管理员叫法已改为："
                + (defendAliasOverride == null ? "（恢复配置文件）" : String.join("、", defendAliasOverride)));
    }

    public boolean defendEnabled() {
        Boolean v = defendEnabledOverride;
        return v != null ? v : config.isDefendEnabled();
    }

    public void setDefendEnabled(boolean on) {
        defendEnabledOverride = on;
        System.out.println("[QQ] 护短已" + (on ? "开启" : "关闭"));
    }

    public boolean parrotEnabled() {
        Boolean v = parrotEnabledOverride;
        return v != null ? v : config.isParrotEnabled();
    }

    public void setParrotEnabled(boolean on) {
        parrotEnabledOverride = on;
        System.out.println("[QQ] 跟风复读机已" + (on ? "开启" : "关闭"));
    }

    public int parrotProbability() {
        int v = parrotProbOverride;
        return v >= 0 ? v : Math.max(0, Math.min(100, config.getParrotProbability()));
    }

    public void setParrotProbability(int percent) {
        parrotProbOverride = Math.max(0, Math.min(100, percent));
        System.out.println("[QQ] 复读概率已改为 " + parrotProbOverride + "%");
    }

    public int cooldownSec() {
        int v = cooldownOverride;
        return v >= 0 ? v : Math.max(0, config.getPerUserCooldownSec());
    }

    public void setCooldownSec(int sec) {
        cooldownOverride = Math.max(0, sec);
        System.out.println("[QQ] 同人冷却已改为 " + (cooldownOverride == 0 ? "关闭" : cooldownOverride + "s"));
    }

    public int replyDelaySec() {
        int v = delayOverride;
        return v >= 0 ? v : Math.max(0, config.getReplyDelaySec());
    }

    public void setReplyDelaySec(int sec) {
        delayOverride = Math.max(0, sec);
        System.out.println("[QQ] 固定回复延迟已改为 " + delayOverride + "s");
    }

    public boolean quoteReply() {
        Boolean v = quoteOverride;
        return v != null ? v : config.isQuoteReply();
    }

    public void setQuoteReply(boolean on) {
        quoteOverride = on;
        System.out.println("[QQ] 引用回复已" + (on ? "开启" : "关闭"));
    }

    /** 表情包最小间隔（中间至少隔几条文字回复） */
    public int emojiMinGap() {
        int v = emojiGapOverride;
        return v >= 0 ? v : Math.max(0, config.getEmojiMinGap());
    }

    public void setEmojiMinGap(int gap) {
        emojiGapOverride = Math.max(0, gap);
        System.out.println("[QQ] 表情包间隔已改为：中间至少隔 " + emojiGapOverride + " 条回复");
    }

    public boolean emojiEnabled() {
        Boolean v = emojiEnabledOverride;
        return v != null ? v : config.isEmojiEnabled();
    }

    public void setEmojiEnabled(boolean on) {
        emojiEnabledOverride = on;
        System.out.println("[QQ] 表情包已" + (on ? "开启" : "关闭"));
    }
}
