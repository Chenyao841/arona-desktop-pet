package com.cy.qq;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 回复编排：固定延迟 + 去抖 + 严格顺序 + 恶意 @ 防护（P4）。
 *
 * <p>一条群消息的完整旅程：
 * <pre>
 * 收到 @
 *  ├─ 立刻开始"思考"（调模型），不等
 *  ├─ 发送时刻 = 收到时刻 + qq.reply-delay-sec（默认 3s）→ 想得再快也不早于这个点
 *  └─ 到点后：如果还在想，就等它想完立刻发（思考超过 3s 时总耗时 = 思考耗时）
 * </pre>
 *
 * <p>三条规则都落在这里：
 * <ol>
 *   <li><b>3s 内同一个人又来 @</b>：重置这个人的发送时刻（继续往后推），
 *       <b>思考不重跑、回复只有一条</b>（省钱，也不会连珠炮）。</li>
 *   <li><b>按入栈顺序回复</b>：每个群一条单线程"车道"，任务按到达顺序排队，
 *       前一条没发完，后一条绝不插队（哪怕后一条想得更快）。</li>
 *   <li><b>恶意 @</b>：同一个人在延迟窗口内连续 @ 到 {@code qq.blacklist-burst} 次（默认 4），
 *       直接拉黑、这条也不再回；只有管理员能 {@code /unban}。</li>
 * </ol>
 *
 * <p>私聊（管理员闲聊）走同一套节奏，只是车道键用负数，和群互不影响。
 */
@Service
public class QqReplyQueue {

    /** 等模型结果的上限（思考再慢也不至于卡死） */
    private static final long AI_WAIT_MAX_MS = 120_000L;

    @Autowired
    private QqConfig config;

    @Autowired
    private QqTuning tuning;

    @Autowired
    private QqChatService chatService;

    @Autowired
    private QqBlacklistService blacklist;

    /** 群号（或 -QQ号）→ 单线程车道 */
    private final Map<Long, ExecutorService> lanes = new ConcurrentHashMap<>();
    /** "g{群号}_{QQ号}" 或 "p{QQ号}" → 待发送任务 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** 合并/重置的原子性用 */
    private final Object pendingLock = new Object();

    private ExecutorService thinkers;

    @PostConstruct
    public void init() {
        int n = Math.max(1, config.getAiThreads());
        thinkers = Executors.newFixedThreadPool(n, daemon("qq-think"));
        System.out.println("[QQ] 回复编排就绪：固定延迟 " + tuning.replyDelaySec() + "s，思考线程 " + n
                + "，连续 @" + Math.max(2, config.getBlacklistBurst()) + " 次拉黑");
    }

    @PreDestroy
    public void shutdown() {
        for (Pending p : new ArrayList<>(pending.values())) {
            p.cancelled = true;
            synchronized (p.monitor) {
                p.monitor.notifyAll();
            }
        }
        pending.clear();
        lanes.values().forEach(ExecutorService::shutdownNow);
        lanes.clear();
        if (thinkers != null) thinkers.shutdownNow();
    }

    // ===== 对外入口 =====

    /**
     * 群消息：提交一次「思考 + 定时发送」。
     *
     * @param thinker 思考逻辑（在后台线程跑，返回要发的内容）
     */
    public void submitGroup(long groupId, long userId, String nickname, long messageId, Supplier<QqOutcome> thinker) {
        submit("g" + groupId + "_" + userId, groupId, false, groupId, userId,
                "群 " + groupId + " / " + nickname, messageId, thinker, false);
    }

    /** 私聊（管理员闲聊）：不参与拉黑（管理员不会被自己的机器人拉黑） */
    public void submitPrivate(long userId, String nickname, long messageId, Supplier<QqOutcome> thinker) {
        submit("p" + userId, -userId, true, 0L, userId, "私聊 " + nickname, messageId, thinker, true);
    }

    public int getPendingCount() {
        return pending.size();
    }

    public int getLaneCount() {
        return lanes.size();
    }

    /**
     * 复读机专用：把一段话原样发到群里（不延迟、不等 AI）。
     *
     * <p>用独立车道（{@code PARROT_LANE_BASE + 群号}），不会排在某条 AI 回复后面干等几秒
     * —— 跟风复读本来就该是"立刻接上"的；同时也不会和 AI 的多段回复交错。
     */
    public void submitEcho(long groupId, String text) {
        if (text == null || text.isEmpty()) return;
        lane(PARROT_LANE_BASE + groupId).execute(() -> {
            try {
                chatService.deliverEcho(groupId, text);
            } catch (Exception e) {
                System.out.println("[QQ] 复读发送失败: " + e);
            }
        });
    }

    /**
     * 护短专用：先等固定延迟，再调模型生成反驳，然后引用调侃那句发出去。
     *
     * <p>用独立车道（{@code DEFEND_LANE_BASE + 群号}），不参与"同人去抖/拉黑"那套
     * —— 护短的触发者不是发消息给机器人的人，不该被算进 burst 计数。
     */
    public void submitDefend(long groupId, long messageId, Supplier<QqOutcome> thinker) {
        // 思考立刻开始（和群回复同一套：想得再快也不早于固定延迟发出去）
        CompletableFuture<QqOutcome> ai = CompletableFuture.supplyAsync(thinker, thinkers);
        lane(DEFEND_LANE_BASE + groupId).execute(() -> {
            long deadline = System.currentTimeMillis() + Math.max(0, tuning.replyDelaySec()) * 1000L;
            try {
                long wait = deadline - System.currentTimeMillis();
                if (wait > 0) Thread.sleep(wait);
                QqOutcome out = ai.get(AI_WAIT_MAX_MS, TimeUnit.MILLISECONDS);
                if (out == null) return;
                chatService.deliver(false, groupId, 0L, out, messageId);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                System.out.println("[QQ] 护短回复失败: " + e);
            }
        });
    }

    /** 护短车道基数 */
    private static final long DEFEND_LANE_BASE = -2_000_000_000_000L;
    /** 复读车道基数：和群号 / 私聊（-QQ号）区分开，避免撞键 */
    private static final long PARROT_LANE_BASE = -1_000_000_000_000L;

    // ===== 内部编排 =====

    private void submit(String key, long laneKey, boolean privateChat, long groupId, long userId,
                        String label, long messageId, Supplier<QqOutcome> thinker, boolean immuneToBan) {
        long now = System.currentTimeMillis();
        long delayMs = Math.max(0, tuning.replyDelaySec()) * 1000L;
        Pending fresh = null;

        synchronized (pendingLock) {
            Pending p = pending.get(key);
            if (p == null) {
                p = new Pending(key, groupId, userId, privateChat, label, messageId, now + delayMs);
                p.ai = CompletableFuture.supplyAsync(thinker, thinkers);
                pending.put(key, p);
                fresh = p;
            } else {
                // 窗口内又来一条：合并成"一次回复"
                p.burst++;
                p.messageId = messageId;
                p.label = label;
                int limit = Math.max(2, config.getBlacklistBurst());
                if (!immuneToBan && p.burst >= limit) {
                    p.cancelled = true;
                    pending.remove(key, p);
                    blacklist.ban(userId, "回复延迟窗口内连续 @" + p.burst + " 次");
                    synchronized (p.monitor) {
                        p.monitor.notifyAll();
                    }
                    System.out.println("[QQ] ⚠ 恶意 @：QQ " + userId + " 在延迟窗口内连发 " + p.burst
                            + " 次 → 已拉黑，本条不再回复（管理员可 /unban " + userId + "）");
                    return;
                }
                p.deadline = now + delayMs; // 重置延迟
                synchronized (p.monitor) {
                    p.monitor.notifyAll();
                }
                System.out.println("[QQ] 同一个" + (privateChat ? "人" : "群友") + "在延迟窗口内又发来一条（第 "
                        + p.burst + " 条）→ 延迟重置为 " + tuning.replyDelaySec() + "s，仍只回一条");
                return;
            }
        }

        if (fresh != null) {
            final Pending job = fresh;
            lane(laneKey).execute(() -> process(job));
        }
    }

    /** 单条车道任务：等到发送时刻 → 等思考结果 → 发出去 */
    private void process(Pending p) {
        awaitDeadline(p);
        pending.remove(p.key, p);
        if (p.cancelled) return;

        QqOutcome out = null;
        try {
            out = p.ai.get(AI_WAIT_MAX_MS, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            System.out.println("[QQ] 等待模型结果失败（" + p.label + "）: " + e.getMessage());
        }
        if (p.cancelled) return;
        if (out == null) out = new QqOutcome("唔……我这会儿有点走神，老师再说一遍？", null, true);

        try {
            // 私聊的目标是 QQ 号，群聊的目标是群号
            long targetId = p.privateChat ? p.userId : p.groupId;
            chatService.deliver(p.privateChat, targetId, p.userId, out, p.messageId);
        } catch (Exception e) {
            System.out.println("[QQ] 发送失败（" + p.label + "）: " + e);
        }
    }

    /** 阻塞到发送时刻；期间若被重置延迟（同人又发消息），醒来继续等 */
    private void awaitDeadline(Pending p) {
        synchronized (p.monitor) {
            while (!p.cancelled) {
                long wait = p.deadline - System.currentTimeMillis();
                if (wait <= 0) return;
                try {
                    p.monitor.wait(wait);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private ExecutorService lane(long key) {
        return lanes.computeIfAbsent(key, k -> Executors.newSingleThreadExecutor(daemon("qq-lane-" + k)));
    }

    private static ThreadFactory daemon(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** 一条待发送任务 */
    private static final class Pending {
        final String key;
        final long groupId;
        final long userId;
        final boolean privateChat;
        final Object monitor = new Object();
        /** 最新一条消息的 id（引用回复用） */
        volatile long messageId;
        volatile String label;
        /** 发送时刻（被"同人又发消息"重置） */
        volatile long deadline;
        /** 窗口内收到的条数（第 1 条 = 1） */
        volatile int burst = 1;
        volatile boolean cancelled = false;
        CompletableFuture<QqOutcome> ai;

        Pending(String key, long groupId, long userId, boolean privateChat, String label,
                long messageId, long deadline) {
            this.key = key;
            this.groupId = groupId;
            this.userId = userId;
            this.privateChat = privateChat;
            this.label = label;
            this.messageId = messageId;
            this.deadline = deadline;
        }
    }

    /** 供 /status 展示 */
    public List<String> describePending() {
        List<String> out = new ArrayList<>();
        for (Pending p : pending.values()) {
            long left = Math.max(0, p.deadline - System.currentTimeMillis());
            out.add(p.label + "（" + left + "ms 后发，窗口内 " + p.burst + " 条）");
        }
        return out;
    }
}
