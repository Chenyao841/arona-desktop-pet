package com.cy.qq;

import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * QQ 机器人自检接口（进度文档 §7.4「掉线静默失败 → 必须有状态检测」）。
 *
 * <ul>
 *   <li>{@code GET  /qq/status}  连接状态 / 计数 / 最后一条消息时间 —— 已在 WebConfig 里放行，运维可直接 curl</li>
 *   <li>{@code POST /qq/test}    往指定群发一条测试消息（走同一套分段逻辑）；需登录态</li>
 *   <li>{@code GET  /qq/split}   只看分段结果、不真发（调分段规则时用）</li>
 * </ul>
 */
@RestController
public class QqStatusController {

    @Autowired
    private QqConfig config;

    @Autowired
    private NapCatWsClient client;

    @Autowired
    private QqEventRouter router;

    @Autowired
    private QqReplyService replyService;

    @Autowired
    private QqEmojiService emojiService;

    @Autowired
    private QqAdminService adminService;

    @Autowired
    private QqUsageService usageService;

    @GetMapping("/qq/status")
    public Result status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", config.isEnabled());
        m.put("connected", client.isConnected());
        m.put("wsUrl", config.maskWsUrl());
        m.put("selfId", config.getSelfId());
        m.put("triggerMode", config.getTriggerMode());
        m.put("perUserCooldownSec", config.getPerUserCooldownSec());
        m.put("chunkRule", "≤" + config.getMaxChunkChars() + "字/条，间隔 "
                + config.getChunkDelayMinMs() + "~" + config.getChunkDelayMaxMs() + "ms，最多 "
                + config.getMaxChunks() + "条");
        m.put("lastConnectAt", fmt(client.getLastConnectAt()));
        m.put("lastEventAt", fmt(client.getLastEventAt()));
        m.put("lastGroupMessageAt", fmt(router.getLastGroupMessageAt()));
        m.put("eventCount", client.getEventCount());
        m.put("handledCount", router.getHandledCount());
        m.put("ignoredCount", router.getIgnoredCount());
        m.put("sentCount", client.getSentCount());
        m.put("sendFailCount", client.getSendFailCount());
        m.put("reconnectCount", client.getReconnectCount());
        m.put("pendingApiCalls", client.getPendingCount());
        m.put("cooldownEntries", router.getCooldownSize());
        m.put("emojiEnabled", config.isEmojiEnabled());
        m.put("emojiCount", emojiService.size());
        m.put("emojiSent", emojiService.getSentCount());
        m.put("visionEnabled", config.isVisionEnabled());
        m.putAll(adminService.snapshot());
        return Result.success(m);
    }

    /** token 用量明细（P3）：今日按群 / 按类型 / 最近 7 天 */
    @GetMapping("/qq/usage")
    public Result usage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", usageService.summary());
        m.put("todayByGroup", usageService.todayByGroup());
        m.put("todayByKind", usageService.todayByKind());
        m.put("last7Days", usageService.last7Days());
        return Result.success(m);
    }

    /**
     * 表情包标注表：文件名 → 人工标注的表情内容。
     * 想核对「哪张图是哪个表情」时看这里（或直接看 static/qq_emoji/emoji_map.json）。
     */
    @GetMapping("/qq/emoji")
    public Result emoji() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", emojiService.size());
        m.put("dir", "src/main/resources/static/qq_emoji/");
        m.put("labels", emojiService.labels());
        return Result.success(m);
    }

    /** 往群里发一张指定表情（核对素材用；不传 file 就随机挑一张） */
    @PostMapping("/qq/emoji/test")
    public Result emojiTest(@RequestParam long group, @RequestParam(required = false) String file) {
        if (!client.isConnected()) return Result.error("NapCat WS 未连接");
        String pick = (file == null || file.isEmpty()) ? emojiService.pickSticker(null, null) : file;
        String base64 = emojiService.base64Of(pick);
        if (base64 == null) return Result.error("表情文件不存在: " + pick);
        boolean ok = replyService.sendSticker(group, base64);
        if (!ok) return Result.error("发送失败，详见后端日志");
        return Result.success(emojiService.labelOf(pick) + "（" + pick + "）");
    }

    @PostMapping("/qq/test")
    public Result test(@RequestParam long group, @RequestParam String text) {
        if (!client.isConnected()) return Result.error("NapCat WS 未连接（检查 ZC_QQ_ENABLED / ZC_QQ_WS_URL / ZC_QQ_WS_TOKEN）");
        List<String> chunks = QqMessageSplitter.split(text, config.getMaxChunkChars(), config.getMaxChunks());
        int ok = replyService.replyChunks(group, chunks);
        if (ok == 0) return Result.error("发送失败，详见后端日志");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("chunks", chunks.size());
        m.put("ok", ok);
        return Result.success(m);
    }

    @GetMapping("/qq/split")
    public Result split(@RequestParam String text) {
        List<String> chunks = QqMessageSplitter.split(text, config.getMaxChunkChars(), config.getMaxChunks());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", chunks.size());
        m.put("chunks", chunks);
        return Result.success(m);
    }

    private static String fmt(long ts) {
        return ts <= 0 ? "-" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date(ts));
    }
}
