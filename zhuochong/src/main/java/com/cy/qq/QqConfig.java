package com.cy.qq;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * QQ 机器人配置（P0）。
 *
 * <p>所有值走环境变量，敏感值（WS token）<b>不写进仓库</b>：
 * <ul>
 *   <li>{@code ZC_QQ_ENABLED}   总开关，默认 false —— 不开就不连 NapCat，桌宠主链路完全不受影响</li>
 *   <li>{@code ZC_QQ_WS_URL}    NapCat 正向 WebSocket 地址，默认 ws://127.0.0.1:3001</li>
 *   <li>{@code ZC_QQ_WS_TOKEN}  OneBot 11 的 access token（NapCat WebUI 里配的那个）</li>
 *   <li>{@code ZC_QQ_SELF_ID}   机器人自己的 QQ 号，留 0 则连上后自动用 get_login_info 取</li>
 * </ul>
 * 本地测试在「环境变量配置.bat」里改，云端在 systemd unit / sh 脚本里 export。
 */
@Component
public class QqConfig {

    /** 总开关 */
    @Value("${qq.enabled:false}")
    private boolean enabled;

    /** NapCat 正向 WS 地址 */
    @Value("${qq.ws-url:ws://127.0.0.1:3001}")
    private String wsUrl;

    /** OneBot 11 access token（Authorization: Bearer <token>） */
    @Value("${qq.ws-token:}")
    private String wsToken;

    /** 机器人自己的 QQ 号；0 = 连上后自动探测（连上后由 WS 线程回填，故 volatile） */
    @Value("${qq.self-id:0}")
    private volatile long selfId;

    /** 触发方式：at（默认，只响应 @ 自己）/ all（全量消息，风控风险高，P0 不推荐） */
    @Value("${qq.trigger-mode:at}")
    private String triggerMode;

    /** 同一个人两次响应之间的最短间隔（秒） */
    @Value("${qq.per-user-cooldown-sec:60}")
    private int perUserCooldownSec;

    /** 单条消息最大字数（超过就切段） */
    @Value("${qq.max-chunk-chars:300}")
    private int maxChunkChars;

    /** 分段之间的最短间隔（毫秒） */
    @Value("${qq.chunk-delay-min-ms:400}")
    private int chunkDelayMinMs;

    /** 分段之间的最长间隔（毫秒） */
    @Value("${qq.chunk-delay-max-ms:800}")
    private int chunkDelayMaxMs;

    /** 一次回复最多发几条，防止模型话痨刷屏 */
    @Value("${qq.max-chunks:6}")
    private int maxChunks;

    /** 心跳间隔（秒）：定期 get_status 探活，连续失败即重连 */
    @Value("${qq.heartbeat-sec:30}")
    private int heartbeatSec;

    /** 连接超时（秒） */
    @Value("${qq.connect-timeout-sec:10}")
    private int connectTimeoutSec;

    /** 重连退避上限（秒）：3 → 6 → 12 → … 封顶 */
    @Value("${qq.reconnect-max-sec:60}")
    private int reconnectMaxSec;

    /** 群白名单（逗号分隔）；空 = 不限制 */
    @Value("${qq.group-whitelist:}")
    private String groupWhitelist;

    /** 是否在回复后跟一张表情包 */
    @Value("${qq.emoji-enabled:true}")
    private boolean emojiEnabled;

    /** 发表情包的概率（%）—— 只对"已通过强情绪判断 + 冷却"的回复再随机抑制一次，100 = 不抑制 */
    @Value("${qq.emoji-probability:100}")
    private int emojiProbability;

    /** 表情包最小间隔：中间至少要隔多少条纯文字回复，才允许再发一张 */
    @Value("${qq.emoji-min-gap:5}")
    private int emojiMinGap;

    /** 哪些情绪算"情绪变化较大"（逗号分隔）；不在这个集合里的情绪不发表情 */
    @Value("${qq.emoji-strong-emotions:激动,害羞,生气,慌张,难过,开心,震惊,俏皮}")
    private String emojiStrongEmotions;

    /** 是否识别群里发的图片 */
    @Value("${qq.vision-enabled:true}")
    private boolean visionEnabled;

    /** 送视觉模型前把图片长边缩到这个尺寸以内 */
    @Value("${qq.vision-max-edge:800}")
    private int visionMaxEdge;

    /** 一条消息最多看几张图（多了又慢又贵） */
    @Value("${qq.vision-max-images:2}")
    private int visionMaxImages;

    /** 送视觉模型时给的 max_tokens（推理模型会先用掉一部分在思考上，给小了 content 就是空的） */
    @Value("${qq.vision-max-tokens:4000}")
    private int visionMaxTokens;

    /** 被追问图片细节时，是否重新读一遍留底的原图（false = 只用第一次的 90 字描述） */
    @Value("${qq.image-reread:true}")
    private boolean imageReread;

    /** 一次追问最多重读几张图 */
    @Value("${qq.image-reread-max:2}")
    private int imageRereadMax;

    /** 发送失败重试次数 */
    @Value("${qq.send-retry:1}")
    private int sendRetry;

    /** 管理员 QQ（逗号分隔）：只有这些人能私聊机器人、能用管理指令（P3） */
    @Value("${qq.admin-qq:}")
    private String adminQq;

    /** 是否只让管理员私聊（默认 true；false = 谁私聊都接待，不推荐） */
    @Value("${qq.admin-only-private:true}")
    private boolean adminOnlyPrivate;

    /** 群聊记忆隔离范围：group（每群一份）/ user（每群每人一份）/ off（不隔离） */
    @Value("${qq.memory-scope:group}")
    private String memoryScope;

    /** 每处理多少条群消息后台提炼一次长期记忆（0 = 不自动提炼） */
    @Value("${qq.memory-consolidate-every:20}")
    private int memoryConsolidateEvery;

    /** 每日 token 上限（0 = 不限）；只统计 QQ 群消耗，不含桌宠端 */
    @Value("${qq.daily-token-limit:0}")
    private long dailyTokenLimit;

    /** 额度用到百分之多少时私聊提醒管理员（0 = 不提醒）；用满时无论设多少都会提醒一次 */
    @Value("${qq.quota-alert-percent:80}")
    private int quotaAlertPercent;

    /** 固定回复延迟（秒）：收到 @ 后至少等这么久才回；0 = 立刻回（P4） */
    @Value("${qq.reply-delay-sec:3}")
    private int replyDelaySec;

    /** 延迟窗口内连续 @ 达到这个次数就拉黑（P4） */
    @Value("${qq.blacklist-burst:4}")
    private int blacklistBurst;

    /** 回复时是否引用原消息（P4） */
    @Value("${qq.quote-reply:true}")
    private boolean quoteReply;

    /** 额度用尽时对被 @ 的固定说明（P4） */
    @Value("${qq.quota-reply:今天我说话太多啦，额度用完了……管理员充值后我就回来～}")
    private String quotaReply;

    /** 思考线程池大小（同时最多几条消息在问模型） */
    @Value("${qq.ai-threads:4}")
    private int aiThreads;

    public boolean isEnabled() {
        return enabled;
    }

    public String getWsUrl() {
        return wsUrl;
    }

    public String getWsToken() {
        return wsToken;
    }

    public long getSelfId() {
        return selfId;
    }

    /** 连上后由 get_login_info 自动回填；也允许配置里直接给 */
    public void setSelfId(long selfId) {
        this.selfId = selfId;
    }

    public String getTriggerMode() {
        return triggerMode;
    }

    public boolean isTriggerAll() {
        return "all".equalsIgnoreCase(triggerMode);
    }

    public int getPerUserCooldownSec() {
        return perUserCooldownSec;
    }

    public int getMaxChunkChars() {
        return maxChunkChars;
    }

    public int getChunkDelayMinMs() {
        return chunkDelayMinMs;
    }

    public int getChunkDelayMaxMs() {
        return chunkDelayMaxMs;
    }

    public int getMaxChunks() {
        return maxChunks;
    }

    public int getHeartbeatSec() {
        return heartbeatSec;
    }

    public int getConnectTimeoutSec() {
        return connectTimeoutSec;
    }

    public int getReconnectMaxSec() {
        return reconnectMaxSec;
    }

    /** 白名单群号集合；空集合表示不限制 */
    public Set<Long> getGroupWhitelist() {
        if (groupWhitelist == null || groupWhitelist.trim().isEmpty()) return Collections.emptySet();
        Set<Long> out = new LinkedHashSet<>();
        for (String s : groupWhitelist.split("[,，\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                System.out.println("[QQ] 群白名单里的值不是数字，已忽略: " + t);
            }
        }
        return out;
    }

    /** 日志用：隐藏 token */
    public String maskWsUrl() {
        return wsUrl + (hasToken() ? "（token 已配置）" : "（无 token）");
    }

    public boolean hasToken() {
        return wsToken != null && !wsToken.trim().isEmpty();
    }

    public boolean isEmojiEnabled() {
        return emojiEnabled;
    }

    public int getEmojiProbability() {
        return emojiProbability;
    }

    public int getEmojiMinGap() {
        return Math.max(0, emojiMinGap);
    }

    /** "情绪变化较大"的情绪标签集合；空集合 = 不按情绪过滤（退回老行为） */
    public Set<String> getEmojiStrongEmotions() {
        if (emojiStrongEmotions == null || emojiStrongEmotions.trim().isEmpty()) return Collections.emptySet();
        Set<String> out = new LinkedHashSet<>();
        for (String s : emojiStrongEmotions.split("[,，\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public boolean isVisionEnabled() {
        return visionEnabled;
    }

    public int getVisionMaxEdge() {
        return visionMaxEdge;
    }

    public int getVisionMaxImages() {
        return Math.max(1, visionMaxImages);
    }

    public int getVisionMaxTokens() {
        return Math.max(200, visionMaxTokens);
    }

    public boolean isImageReread() {
        return imageReread;
    }

    public int getImageRereadMax() {
        return Math.max(1, imageRereadMax);
    }

    public int getSendRetry() {
        return Math.max(0, sendRetry);
    }

    /** 管理员 QQ 集合；空集合 = 没有任何管理员（管理指令全部失效、私聊一律不接待） */
    public Set<Long> getAdminQqs() {
        if (adminQq == null || adminQq.trim().isEmpty()) return Collections.emptySet();
        Set<Long> out = new LinkedHashSet<>();
        for (String s : adminQq.split("[,，\\s]+")) {
            String t = s.trim();
            if (t.isEmpty()) continue;
            try {
                out.add(Long.parseLong(t));
            } catch (NumberFormatException ignored) {
                System.out.println("[QQ] 管理员列表里的值不是数字，已忽略: " + t);
            }
        }
        return out;
    }

    public boolean isAdminOnlyPrivate() {
        return adminOnlyPrivate;
    }

    public String getMemoryScope() {
        return memoryScope == null ? "group" : memoryScope.trim();
    }

    public int getMemoryConsolidateEvery() {
        return memoryConsolidateEvery;
    }

    public long getDailyTokenLimit() {
        return dailyTokenLimit;
    }

    public int getQuotaAlertPercent() {
        return quotaAlertPercent;
    }

    public int getReplyDelaySec() {
        return replyDelaySec;
    }

    public int getBlacklistBurst() {
        return blacklistBurst;
    }

    public boolean isQuoteReply() {
        return quoteReply;
    }

    public String getQuotaReply() {
        return (quotaReply == null || quotaReply.trim().isEmpty())
                ? "今天我说话太多啦，额度用完了……"
                : quotaReply.trim();
    }

    public int getAiThreads() {
        return aiThreads;
    }
}
