package com.cy.qq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

/**
 * 群消息发送 + 分段发言。
 *
 * <p>进度文档 P0：「{@code sendGroupMsg(group_id, text)}；分段发言（按 {@code \n\n} 分段 → 按句切
 * → 每条 ≤300 字 → 间隔 400~800ms 随机）」。切分交给 {@link QqMessageSplitter}，
 * 这里负责「一条条发、中间随机停一下」——像人在群里打字。
 *
 * <p>P1 补上「发送失败重试一次」（NapCat 偶尔回 retcode≠0 / 超时，重试基本就过了）。
 */
@Service
public class QqReplyService {

    @Autowired
    private QqConfig config;

    @Autowired
    private NapCatWsClient client;

    private final Random random = new Random();

    /**
     * 把一条回复按规则切段后发到群里。
     *
     * @return 实际发送成功的条数（0 = 没发出去 / 空文本）
     */
    public int reply(long groupId, String text) {
        return reply(groupId, text, 0L);
    }

    /**
     * 把一条回复按规则切段后发到群里（可带引用回复）。
     *
     * @param replyToMessageId 引用的消息 id；0 = 不引用。只有第一段带引用，后面几段引自己上面那条
     * @return 实际发送成功的条数（0 = 没发出去 / 空文本）
     */
    public int reply(long groupId, String text, long replyToMessageId) {
        List<String> chunks = QqMessageSplitter.split(text, config.getMaxChunkChars(), config.getMaxChunks());
        return replyChunks(groupId, chunks, replyToMessageId);
    }

    /** 已经切好段时直接发（自检接口用） */
    public int replyChunks(long groupId, List<String> chunks) {
        return replyChunks(groupId, chunks, 0L);
    }

    public int replyChunks(long groupId, List<String> chunks, long replyToMessageId) {
        if (chunks == null || chunks.isEmpty()) return 0;
        int ok = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sleepBetweenChunks();
            // 只有第一段引用原消息，避免多段回复刷出一堆引用框
            long quote = (i == 0) ? replyToMessageId : 0L;
            if (sendTextWithRetry(groupId, chunks.get(i), quote)) ok++;
        }
        if (chunks.size() > 1) {
            System.out.println("[QQ] 本次回复分段 " + chunks.size() + " 条，成功 " + ok + " 条");
        }
        return ok;
    }

    /** 发一张表情包（base64 已就绪）；先小停一下，别和最后一条文字挤在一起 */
    public boolean sendSticker(long groupId, String imageBase64) {
        sleepQuietly(randomBetween(config.getChunkDelayMinMs(), config.getChunkDelayMaxMs()));
        int attempts = 1 + config.getSendRetry();
        for (int i = 0; i < attempts; i++) {
            if (client.sendGroupImageBase64(groupId, imageBase64)) return true;
            if (i + 1 < attempts) sleepQuietly(500);
        }
        return false;
    }

    /** 私聊回复（管理员通道）：同样分段，避免一条几百字砸过去 */
    public int replyPrivate(long userId, String text) {
        return replyPrivate(userId, text, 0L);
    }

    public int replyPrivate(long userId, String text, long replyToMessageId) {
        List<String> chunks = QqMessageSplitter.split(text, config.getMaxChunkChars(), config.getMaxChunks());
        if (chunks.isEmpty()) return 0;
        int ok = 0;
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0) sleepBetweenChunks();
            long quote = (i == 0) ? replyToMessageId : 0L;
            if (client.sendPrivateMsg(userId, chunks.get(i), quote)) ok++;
        }
        return ok;
    }

    private boolean sendTextWithRetry(long groupId, String text) {
        return sendTextWithRetry(groupId, text, 0L);
    }

    private boolean sendTextWithRetry(long groupId, String text, long replyToMessageId) {
        int attempts = 1 + config.getSendRetry();
        for (int i = 0; i < attempts; i++) {
            if (client.sendGroupMsg(groupId, text, replyToMessageId)) return true;
            if (i + 1 < attempts) {
                System.out.println("[QQ] 发送失败，500ms 后重试一次");
                sleepQuietly(500);
            }
        }
        return false;
    }

    /** 400~800ms 随机间隔（配置可调），太整齐反而不像人 */
    private void sleepBetweenChunks() {
        sleepQuietly(randomBetween(config.getChunkDelayMinMs(), config.getChunkDelayMaxMs()));
    }

    private int randomBetween(int minCfg, int maxCfg) {
        int min = Math.max(0, minCfg);
        int max = Math.max(min, maxCfg);
        return max > min ? min + random.nextInt(max - min + 1) : min;
    }

    private void sleepQuietly(int ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
