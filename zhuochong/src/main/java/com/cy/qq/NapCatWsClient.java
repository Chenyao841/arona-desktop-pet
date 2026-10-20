package com.cy.qq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * NapCat（OneBot 11）正向 WebSocket 客户端 —— P0 的「耳朵 + 嘴巴」。
 *
 * <p>链路：{@code ws://127.0.0.1:3001}（NapCat 里的正向 WS 服务器）
 * → 事件推送 {@code post_type=message} → {@link QqEventRouter}
 * → AI 回复 → {@link #sendGroupMsg(long, String)}（action=send_group_msg）。
 *
 * <p>健壮性（云端最容易踩的坑：掉线后静默失联）：
 * <ul>
 *   <li>自动重连：3s → 6s → 12s … 封顶 {@code qq.reconnect-max-sec}，连上后复位</li>
 *   <li>心跳：每 {@code qq.heartbeat-sec} 秒发一次 {@code get_status} 探活，连续 3 次无响应强制重连</li>
 *   <li>事件在独立线程池处理，AI 调用再慢也不会卡住 WS 读循环</li>
 * </ul>
 *
 * <p>不引入任何新依赖：用 JDK 自带的 {@link HttpClient} / {@link WebSocket}（和 DanmakuService 一致）。
 */
@Component
public class NapCatWsClient {

    private static final ObjectMapper M = new ObjectMapper();

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Autowired
    private QqConfig config;

    /** 事件消费者（由 QqEventRouter 注册，避免与它循环依赖） */
    private volatile Consumer<JsonNode> eventSink;
    /** 连接状态回调（状态页用，可空） */
    private volatile Consumer<Boolean> stateSink;

    private volatile WebSocket ws;
    private volatile boolean connected = false;
    private volatile boolean running = false;
    private volatile long lastEventAt = 0L;
    private volatile long lastConnectAt = 0L;
    private volatile int backoffSec = 3;
    private volatile int missBeats = 0;

    private final AtomicLong echoSeq = new AtomicLong();
    private final AtomicLong eventCount = new AtomicLong();
    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong sendFailCount = new AtomicLong();
    private final AtomicLong reconnectCount = new AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean reconnectPending = new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ConcurrentHashMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final Object sendLock = new Object();

    private ScheduledExecutorService supervisor;
    private ThreadPoolExecutor worker;

    // ===== 生命周期 =====

    /** 应用就绪后再连：保证 mapper / DeepSeekService 都已可用 */
    @EventListener(ApplicationReadyEvent.class)
    public void startIfEnabled() {
        if (!config.isEnabled()) {
            System.out.println("[QQ] 未启用（ZC_QQ_ENABLED=false），不连接 NapCat；桌宠主链路不受影响");
            return;
        }
        start();
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        supervisor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "qq-ws-supervisor");
            t.setDaemon(true);
            return t;
        });
        worker = new ThreadPoolExecutor(2, 8, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
            Thread t = new Thread(r, "qq-worker");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.CallerRunsPolicy());

        System.out.println("[QQ] 启动纳帕猫 WS 客户端 → " + config.maskWsUrl());
        supervisor.execute(this::tryConnect);
        supervisor.scheduleWithFixedDelay(this::heartbeatTick,
                Math.max(10, config.getHeartbeatSec()), Math.max(10, config.getHeartbeatSec()), TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        running = false;
        try {
            WebSocket w = ws;
            ws = null;
            connected = false;
            if (w != null) w.sendClose(WebSocket.NORMAL_CLOSURE, "bye").orTimeout(2, TimeUnit.SECONDS).join();
        } catch (Exception ignored) {
        }
        if (supervisor != null) supervisor.shutdownNow();
        if (worker != null) worker.shutdownNow();
        System.out.println("[QQ] WS 客户端已停止");
    }

    public void setEventListener(Consumer<JsonNode> sink) {
        this.eventSink = sink;
    }

    public void setStateListener(Consumer<Boolean> sink) {
        this.stateSink = sink;
    }

    // ===== 连接 / 重连 =====

    private void tryConnect() {
        if (!running) return;
        try {
            URI uri = URI.create(config.getWsUrl());
            WebSocket.Builder b = http.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(Math.max(3, config.getConnectTimeoutSec())));
            if (config.hasToken()) b.header("Authorization", "Bearer " + config.getWsToken().trim());
            b.buildAsync(uri, new Listener()).get(
                    Math.max(5, config.getConnectTimeoutSec()) + 5L, TimeUnit.SECONDS);
            // 注意：ws 字段在 Listener.onOpen 里赋值 —— buildAsync().get() 返回之前 onOpen 就可能已经跑了，
            // 这里再赋值会把「已建立」误判成「未建立」，紧跟着的 get_login_info 会因 ws==null 而失败。
        } catch (Exception e) {
            String msg = e.getCause() != null ? e.getCause().toString() : e.toString();
            System.out.println("[QQ] 连接失败: " + msg + " → " + backoffSec + "s 后重连");
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running || supervisor == null || supervisor.isShutdown()) return;
        // 只排一个重连任务：onError 与 tryConnect 的 catch 可能同时触发，
        // 叠起来会连出两条 WS（NapCat 那边看到两个客户端，事件会重复推）
        if (!reconnectPending.compareAndSet(false, true)) return;
        int delay = Math.max(1, backoffSec);
        backoffSec = Math.min(Math.max(1, backoffSec) * 2, Math.max(delay, config.getReconnectMaxSec()));
        try {
            supervisor.schedule(() -> {
                reconnectPending.set(false);
                tryConnect();
            }, delay, TimeUnit.SECONDS);
        } catch (Exception e) {
            reconnectPending.set(false);
        }
    }

    /** 心跳：定期 get_status 探活；连续失败就强制重连（NapCat 有时 TCP 还活着但已经不推事件） */
    private void heartbeatTick() {
        if (!running || !connected) return;
        JsonNode r = callApi("get_status", M.createObjectNode(), 8000);
        if (r != null && r.path("retcode").asInt(-1) == 0) {
            missBeats = 0;
            return;
        }
        missBeats++;
        System.out.println("[QQ] 心跳无响应 " + missBeats + "/3");
        if (missBeats >= 3) {
            missBeats = 0;
            forceReconnect("心跳连续失败");
        }
    }

    private void forceReconnect(String reason) {
        System.out.println("[QQ] 主动重连：" + reason);
        WebSocket w = ws;
        ws = null;
        connected = false;
        stateSinkSafe(false);
        try {
            if (w != null) w.abort();
        } catch (Exception ignored) {
        }
        reconnectCount.incrementAndGet();
        scheduleReconnect();
    }

    // ===== WS 监听 =====

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket webSocket) {
            // ★ 连接句柄就在这里落地：这是「连接已建立」的唯一可靠时机
            ws = webSocket;
            connected = true;
            backoffSec = 3;
            missBeats = 0;
            lastConnectAt = System.currentTimeMillis();
            stateSinkSafe(true);
            webSocket.request(Long.MAX_VALUE);
            System.out.println("[QQ] NapCat WS 已连接 ✓");
            // 自检：取登录号（配置里 self-id=0 时自动回填），并确认 token 通过
            ThreadPoolExecutor w = worker;
            if (w != null) {
                w.execute(() -> {
                    JsonNode info = callApi("get_login_info", M.createObjectNode(), 8000);
                    JsonNode data = info == null ? null : info.path("data");
                    if (data != null && data.has("user_id")) {
                        long id = data.path("user_id").asLong(0);
                        if (config.getSelfId() <= 0 && id > 0) {
                            config.setSelfId(id);
                            System.out.println("[QQ] 自动识别机器人 QQ 号: " + id);
                        }
                    } else {
                        System.out.println("[QQ] get_login_info 无响应：token 不匹配或 NapCat 未登录 QQ");
                    }
                });
            }
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                String text = buf.toString();
                buf.setLength(0);
                dispatch(text);
            }
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            // 只处理「当前这条连接」的关闭：旧连接的迟到回调不能把新连接的状态带崩
            if (ws != webSocket) return null;
            connected = false;
            ws = null;
            stateSinkSafe(false);
            System.out.println("[QQ] WS 已断开: " + statusCode + " " + (reason == null ? "" : reason) + " → 准备重连");
            reconnectCount.incrementAndGet();
            scheduleReconnect();
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            if (ws != null && ws != webSocket) return;
            connected = false;
            ws = null;
            stateSinkSafe(false);
            System.out.println("[QQ] WS 错误: " + (error == null ? "unknown" : error.getMessage()) + " → 准备重连");
            reconnectCount.incrementAndGet();
            scheduleReconnect();
        }
    }

    /** 拆包后的完整 JSON 帧：先匹配 API 回执（echo），再交给事件处理 */
    private void dispatch(String text) {
        if (text == null || text.trim().isEmpty()) return;
        JsonNode node;
        try {
            node = M.readTree(text);
        } catch (Exception e) {
            System.out.println("[QQ] 非法 JSON 帧（前120字）: " + head(text));
            return;
        }
        lastEventAt = System.currentTimeMillis();
        if (node.hasNonNull("echo")) {
            String echo = node.get("echo").asText();
            CompletableFuture<JsonNode> f = pending.remove(echo);
            if (f != null) {
                f.complete(node);
                return;
            }
        }
        if (!node.hasNonNull("post_type")) {
            // API 回执没有 echo（极少见）或未知帧：忽略
            return;
        }
        eventCount.incrementAndGet();
        Consumer<JsonNode> sink = eventSink;
        ThreadPoolExecutor w = worker;
        if (sink == null || w == null) return;
        try {
            w.execute(() -> {
                try {
                    sink.accept(node);
                } catch (Exception e) {
                    System.out.println("[QQ] 事件处理异常: " + e);
                }
            });
        } catch (Exception e) {
            System.out.println("[QQ] 事件派发被拒绝（线程池已关闭）: " + e.getMessage());
        }
    }

    // ===== 发送 / 调用 =====

    /**
     * 群消息发送（P0 唯一出口）。
     *
     * @param groupId 群号
     * @param text    文本（调用方负责切段，见 {@link QqMessageSplitter}）
     * @return true = NapCat 回执 retcode=0
     */
    public boolean sendGroupMsg(long groupId, String text) {
        return sendGroupMsg(groupId, text, 0L);
    }

    /**
     * 群消息发送 + 引用回复。
     *
     * @param replyToMessageId 要引用的消息 id；0 = 不引用
     */
    public boolean sendGroupMsg(long groupId, String text, long replyToMessageId) {
        if (text == null || text.isEmpty()) return false;
        ObjectNode params = M.createObjectNode();
        params.put("group_id", groupId);
        ArrayNode arr = params.putArray("message");
        if (replyToMessageId > 0) {
            ObjectNode reply = M.createObjectNode();
            reply.put("type", "reply");
            reply.putObject("data").put("id", String.valueOf(replyToMessageId));
            arr.add(reply);
        }
        ObjectNode seg = M.createObjectNode();
        seg.put("type", "text");
        seg.putObject("data").put("text", text);
        arr.add(seg);
        JsonNode resp = callApi("send_group_msg", params, 15000);
        boolean ok = resp != null && resp.path("retcode").asInt(-1) == 0;
        if (ok) {
            sentCount.incrementAndGet();
            System.out.println("[QQ] → 群 " + groupId + "（" + text.length() + "字"
                    + (replyToMessageId > 0 ? "，引用 " + replyToMessageId : "") + "）: " + head(text));
        } else {
            sendFailCount.incrementAndGet();
            System.out.println("[QQ] ✗ 群 " + groupId + " 发送失败: " + (resp == null ? "无回执（未连接/超时）" : resp.toString()));
        }
        return ok;
    }

    /**
     * 群图片发送（表情包用）。
     *
     * <p>用 {@code base64://} 而不是文件路径：NapCat 跑在 Docker 里，宿主机路径它读不到；
     * base64 走的是同一条 WS，跨机器跨容器都能用。
     *
     * @param imageBase64 图片 base64（不含 data: 前缀）
     */
    public boolean sendGroupImageBase64(long groupId, String imageBase64) {
        if (imageBase64 == null || imageBase64.isEmpty()) return false;
        ObjectNode params = M.createObjectNode();
        params.put("group_id", groupId);
        ObjectNode seg = M.createObjectNode();
        seg.put("type", "image");
        seg.putObject("data").put("file", "base64://" + imageBase64);
        ArrayNode arr = params.putArray("message");
        arr.add(seg);
        JsonNode resp = callApi("send_group_msg", params, 20000);
        boolean ok = resp != null && resp.path("retcode").asInt(-1) == 0;
        if (ok) {
            sentCount.incrementAndGet();
            System.out.println("[QQ] → 群 " + groupId + " 发送图片（base64 " + imageBase64.length() / 1024 + "KB）");
        } else {
            sendFailCount.incrementAndGet();
            System.out.println("[QQ] ✗ 群 " + groupId + " 发图失败: "
                    + (resp == null ? "无回执（未连接/超时）" : resp.toString()));
        }
        return ok;
    }

    /**
     * 私聊发自（P3：管理员通道）。
     *
     * @param replyToMessageId 要引用的消息 id；0 = 不引用
     * @return true = retcode 0
     */
    public boolean sendPrivateMsg(long userId, String text, long replyToMessageId) {
        if (text == null || text.isEmpty()) return false;
        ObjectNode params = M.createObjectNode();
        params.put("user_id", userId);
        ArrayNode arr = params.putArray("message");
        if (replyToMessageId > 0) {
            ObjectNode reply = M.createObjectNode();
            reply.put("type", "reply");
            reply.putObject("data").put("id", String.valueOf(replyToMessageId));
            arr.add(reply);
        }
        ObjectNode seg = M.createObjectNode();
        seg.put("type", "text");
        seg.putObject("data").put("text", text);
        arr.add(seg);
        JsonNode resp = callApi("send_private_msg", params, 15000);
        boolean ok = resp != null && resp.path("retcode").asInt(-1) == 0;
        if (ok) {
            sentCount.incrementAndGet();
            System.out.println("[QQ] → 私聊 " + userId + "（" + text.length() + "字"
                    + (replyToMessageId > 0 ? "，引用" : "") + "）: " + head(text));
        } else {
            sendFailCount.incrementAndGet();
            System.out.println("[QQ] ✗ 私聊 " + userId + " 发送失败: "
                    + (resp == null ? "无回执（未连接/超时）" : resp.toString()));
        }
        return ok;
    }

    /** 通用 OneBot API 调用；返回回执 JSON，超时/未连接返回 null */
    public JsonNode callApi(String action, ObjectNode params, long timeoutMs) {
        WebSocket w = ws;
        if (w == null || !connected) return null;
        String echo = "e" + echoSeq.incrementAndGet();
        ObjectNode req = M.createObjectNode();
        req.put("action", action);
        req.set("params", params == null ? M.createObjectNode() : params);
        req.put("echo", echo);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(echo, future);
        try {
            String payload = M.writeValueAsString(req);
            synchronized (sendLock) {
                w.sendText(payload, true).get(10, TimeUnit.SECONDS);
            }
            return future.get(Math.max(1000, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return null;
        } finally {
            pending.remove(echo);
        }
    }

    // ===== 状态（/qq/status 用） =====

    public boolean isConnected() {
        return connected;
    }

    public long getLastEventAt() {
        return lastEventAt;
    }

    public long getLastConnectAt() {
        return lastConnectAt;
    }

    public long getEventCount() {
        return eventCount.get();
    }

    public long getSentCount() {
        return sentCount.get();
    }

    public long getSendFailCount() {
        return sendFailCount.get();
    }

    public long getReconnectCount() {
        return reconnectCount.get();
    }

    public int getPendingCount() {
        return pending.size();
    }

    private void stateSinkSafe(boolean state) {
        Consumer<Boolean> s = stateSink;
        if (s == null) return;
        try {
            s.accept(state);
        } catch (Exception ignored) {
        }
    }

    private static String head(String s) {
        if (s == null) return "";
        String t = s.replace("\r", " ").replace("\n", " ");
        return t.length() > 120 ? t.substring(0, 120) + "…" : t;
    }
}
