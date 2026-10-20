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
