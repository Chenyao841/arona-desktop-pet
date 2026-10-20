package com.cy.qq;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 图片留底 + 按需重读（P4+）。
 *
 * <p>解决什么问题：视觉模型一次只给一句 90 字描述，细节丢光；之后被问"图里她衣服什么颜色"
 * 就答不出来。所以收到图时把**缩放后的图**留底并编号（{@code #12}），历史里只写
 * {@code 〔图#12：一句话描述〕}；后面有人追问时，拿**当前的问题当提示词**重读原图，
 * 只把这次问到的细节补进这一轮上下文。
 *
 * <p>为什么存缩放后的图而不是 URL：QQ 图床地址会过期，几小时后重下就 404 了。
 * 800px JPEG 约 20KB，一个会话留 20 张 ≈ 400KB，可接受。
 *
 * <p>作用域与对话一致：群 → {@code g{群号}}，私聊 → {@code p{QQ号}}，互不串台。
 */
@Component
public class QqImageMemory {

    /** 每个会话最多留几张 */
    private static final int PER_SESSION = 20;
    /** 留底有效期：6 小时（跨天基本没人再问） */
    private static final long TTL_MS = 6 * 3600_000L;
    /** 全局上限，超了清掉最旧的会话 */
    private static final int GLOBAL_MAX = 400;

    public static final class Stored {
        public final int id;
        public final String base64;
        public final String mime;
        public final String firstDesc;
        public final long ts;

        Stored(int id, String base64, String mime, String firstDesc, long ts) {
            this.id = id;
            this.base64 = base64;
            this.mime = mime;
            this.firstDesc = firstDesc;
            this.ts = ts;
        }
    }

    private final Map<String, Deque<Stored>> bySession = new ConcurrentHashMap<>();
    private final Map<Integer, Stored> byId = new ConcurrentHashMap<>();
    private final AtomicInteger seq = new AtomicInteger(0);

    /** 留底一张图，返回编号（写进历史用，比如 #12） */
    public int remember(String sessionKey, QqImageService.Prepared p, String firstDesc) {
        if (sessionKey == null || p == null) return -1;
        int id = seq.incrementAndGet();
        Stored s = new Stored(id, p.base64, p.mime, firstDesc == null ? "" : firstDesc, System.currentTimeMillis());
        Deque<Stored> dq = bySession.computeIfAbsent(sessionKey, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(s);
            while (dq.size() > PER_SESSION) {
                Stored old = dq.pollFirst();
                if (old != null) byId.remove(old.id);
            }
        }
        byId.put(id, s);
        if (byId.size() > GLOBAL_MAX) purgeOldest();
        return id;
    }

    /** 某会话最近的 N 张（新→旧） */
    public List<Stored> recent(String sessionKey, int n) {
        List<Stored> out = new ArrayList<>();
        Deque<Stored> dq = bySession.get(sessionKey);
        if (dq == null) return out;
        long now = System.currentTimeMillis();
        synchronized (dq) {
            for (java.util.Iterator<Stored> it = dq.descendingIterator(); it.hasNext() && out.size() < n; ) {
                Stored s = it.next();
                if (now - s.ts > TTL_MS) break;
                out.add(s);
            }
        }
        return out;
    }

    /** 按编号取（同会话内才允许取，避免跨群串台） */
    public Stored byId(String sessionKey, int id) {
        Stored s = byId.get(id);
        if (s == null) return null;
        if (System.currentTimeMillis() - s.ts > TTL_MS) return null;
        Deque<Stored> dq = bySession.get(sessionKey);
        if (dq == null) return null;
        synchronized (dq) {
            for (Stored x : dq) if (x.id == id) return s;
        }
        return null;
    }

    public int total() {
        return byId.size();
    }

    private void purgeOldest() {
        byId.entrySet().stream()
                .sorted((a, b) -> Long.compare(a.getValue().ts, b.getValue().ts))
                .limit(Math.max(1, byId.size() - GLOBAL_MAX))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(byId::remove);
    }

    /** 会话键：群用 g{群号}，私聊用 p{QQ号} */
    public static String sessionKey(boolean privateChat, long targetId) {
        return (privateChat ? "p" : "g") + targetId;
    }
}
