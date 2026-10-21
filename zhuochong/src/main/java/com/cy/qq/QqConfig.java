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

    /** 图片留底目录（落盘；重启后编号仍可重读） */
    @Value("${qq.image-store-dir:qq_images}")
    private String imageStoreDir;

    /** 单个群最多留几张图（超出时删最早的） */
    @Value("${qq.image-max-per-group:500}")
    private int imageMaxPerGroup;

    /** 私聊（管理员 QQ）每人最多留几张图 */
    @Value("${qq.image-max-per-user:500}")
    private int imageMaxPerUser;

    /** 全局兜底上限（所有会话加起来的张数），防止群很多时磁盘被吃光 */
    @Value("${qq.image-max-total:3000}")
    private int imageMaxTotal;

    /** 留底有效期（小时），过期自动清理 */
    @Value("${qq.image-ttl-hours:24}")
    private int imageTtlHours;

    /** 跟风复读机总开关（纯代码，不花 token） */
    @Value("${qq.parrot-enabled:true}")
    private boolean parrotEnabled;

    /** 复读概率（%）：同一句话被 N 个不同人重复后，每次触发按这个概率决定复不复读 */
    @Value("${qq.parrot-probability:33}")
    private int parrotProbability;

    /** 需要多少个"不同用户"重复同一句话才算触发 */
    @Value("${qq.parrot-min-users:3}")
    private int parrotMinUsers;

    /** 重复统计窗口（秒）：超过窗口的重复不计数 */
    @Value("${qq.parrot-window-sec:180}")
    private int parrotWindowSec;

    /** 复读成功后，接下来还要跳过几次触发机会（冷却次数） */
    @Value("${qq.parrot-cooldown-triggers:1}")
    private int parrotCooldownTriggers;

    /** 每组每小时最多复读几次（0 = 不限，安全阀防刷屏） */
    @Value("${qq.parrot-max-per-hour:3}")
    private int parrotMaxPerHour;

    /** 参与复读的句子最长字数（太长的不复读） */
    @Value("${qq.parrot-max-chars:60}")
    private int parrotMaxChars;

    /** 护短功能总开关：群友调侃管理员时出来反驳（调 AI） */
    @Value("${qq.defend-enabled:true}")
    private boolean defendEnabled;

    /** 护短一次后，接下来还要跳过几次触发机会（默认 3 次） */
    @Value("${qq.defend-cooldown-triggers:3}")
    private int defendCooldownTriggers;

    /** 每组每小时最多护短几次（护短要调模型，防烧钱；0 = 不限） */
    @Value("${qq.defend-max-per-hour:5}")
    private int defendMaxPerHour;

    /** 调侃性关键词（逗号分隔）：@了管理员 或 提到管理员昵称 + 命中这些词 → 触发护短 */
    @Value("${qq.defend-keywords:菜,菜鸡,菜鸟,笨蛋,傻瓜,憨,鸽,又鸽,摸鱼,划水,偷懒,摆烂,躺平,辣鸡,垃圾,废物,不行,拉胯}")
    private String defendKeywords;

    /**
     * 管理员的称呼（逗号分隔）：群里怎么叫管理员就算"提到他"。
     *
     * <p>为什么单独配：护短主要认**群昵称/群名片**（比如「小羊羔」），而不是 QQ 名。
     * 群名片机器人会自己记（管理员在群里说一句话就登记），这里配的是**额外**叫法，
     * 让"还没见他发过言的群"也能认出来。`/defend alias 小羊羔,小羊` 可运行时改。
     */
    @Value("${qq.defend-admin-alias:小羊羔,小羊}")
    private String defendAdminAlias;

    /**
     * 引申调侃词（逗号分隔）：这些词单独出现**不算**调侃，
     * 但如果管理员刚刚在群里发过言（{@code defend-context-sec} 之内），就当作在说他。
     *
     * <p>典型场景：管理员叫「小羊羔」，群友说「今晚想吃烤羊」——名字没提，但上下文指向他。
     */
    @Value("${qq.defend-mock-words:羊肉,烤羊,涮羊肉,羊肉串,羊排,全羊,羊蝎子}")
    private String defendMockWords;

    /** 上下文窗口（秒）：管理员最近一次发言在这个时间内，引申调侃词才算"在说他" */
    @Value("${qq.defend-context-sec:180}")
    private int defendContextSec;

    /** 发送失败重试次数 */
    @Value("${qq.send-retry:1}")
    private int sendRetry;

    /**
     * 机器人在 QQ 里的显示名（带版本号用，例如「阿罗娜（v0.0.7测试）」）。
     *
     * <p>连上 NapCat 后会和当前昵称比一次：**不一样才改一次**（{@code set_qq_profile}），
     * 一样就什么都不做。留空 = 不动昵称，只保持现状。
     */
    @Value("${qq.nickname:}")
    private String nickname;

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

    public String getImageStoreDir() {
        return (imageStoreDir == null || imageStoreDir.trim().isEmpty()) ? "qq_images" : imageStoreDir.trim();
    }

    public int getImageMaxPerGroup() {
        return Math.max(1, imageMaxPerGroup);
    }

    public int getImageMaxPerUser() {
        return Math.max(1, imageMaxPerUser);
    }

    public int getImageMaxTotal() {
        return Math.max(1, imageMaxTotal);
    }

    public int getImageTtlHours() {
        return Math.max(1, imageTtlHours);
    }

    public boolean isParrotEnabled() {
        return parrotEnabled;
    }

    public int getParrotProbability() {
        return parrotProbability;
    }

    public int getParrotMinUsers() {
        return parrotMinUsers;
    }

    public int getParrotWindowSec() {
        return parrotWindowSec;
    }

    public int getParrotCooldownTriggers() {
        return parrotCooldownTriggers;
    }

    public int getParrotMaxPerHour() {
        return parrotMaxPerHour;
    }

    public int getParrotMaxChars() {
        return Math.max(4, parrotMaxChars);
    }

    public boolean isDefendEnabled() {
        return defendEnabled;
    }

    public int getDefendCooldownTriggers() {
        return Math.max(0, defendCooldownTriggers);
    }

    public int getDefendMaxPerHour() {
        return defendMaxPerHour;
    }

    public Set<String> getDefendKeywords() {
        return splitWords(defendKeywords);
    }

    /** 管理员的额外叫法（群昵称以外的） */
    public Set<String> getDefendAdminAlias() {
        return splitWords(defendAdminAlias);
    }

    /** 引申调侃词：单独出现不算，管理员刚发过言才算 */
    public Set<String> getDefendMockWords() {
        return splitWords(defendMockWords);
    }

    /** 上下文窗口（秒） */
    public int getDefendContextSec() {
        return Math.max(0, defendContextSec);
    }

    /** 逗号/空白分隔的词表 → 集合（保持顺序、去空、去重） */
    private static Set<String> splitWords(String raw) {
        Set<String> out = new LinkedHashSet<>();
        if (raw == null || raw.trim().isEmpty()) return out;
        for (String s : raw.split("[,，\\s]+")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    public int getSendRetry() {
        return Math.max(0, sendRetry);
    }

    /** 机器人 QQ 名（要改成什么）；空 = 不动 */
    public String getNickname() {
        return nickname == null ? "" : nickname.trim();
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
