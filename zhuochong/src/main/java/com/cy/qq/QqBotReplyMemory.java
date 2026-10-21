package com.cy.qq;

import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「机器人自己最近说过什么」（每个群一份，短时记忆）。
 *
 * <p>用途：跟风复读机判定前先查一遍 —— 如果群里刷的那句话**本来就是机器人刚说的**，
 * 就别再复读了（否则等于机器人自己重复自己，很傻）。
 *
 * <p>为什么单独做成一个叶子组件（不依赖任何其它 Bean）：
 * {@link QqReplyService} 发送成功后写入、{@link QqParrotService} 判定时读取，
 * 而 QqParrotService → QqReplyQueue → QqChatService → QqReplyService 已经是一条链，
 * 再互相注入就会形成循环依赖（Spring Boot 默认禁止）。放在这里两边都能用，谁也不会绕成环。
 */
@Component
public class QqBotReplyMemory {

    /** 每个群记最近多少条自己发出去的文字 */
    private static final int PER_GROUP = 20;
    /** 记忆有效期：10 分钟（够覆盖"它说完、群里开始刷"的整个过程） */
    private static final long TTL_MS = 10 * 60_000L;

    private static final class Entry {
        final String norm;
        final long ts;

        Entry(String norm, long ts) {
            this.norm = norm;
            this.ts = ts;
        }
    }

    private final Map<Long, Deque<Entry>> byGroup = new ConcurrentHashMap<>();

    /** 机器人往群里发了一段文字（AI 回复、复读、管理指令输出都算） */
    public void remember(long groupId, String text) {
        if (text == null) return;
        String norm = squeeze(text);
        if (norm.isEmpty()) return;
        Deque<Entry> dq = byGroup.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (dq) {
            dq.addLast(new Entry(norm, System.currentTimeMillis()));
            while (dq.size() > PER_GROUP) dq.pollFirst();
        }
    }

    /**
     * 这句话是不是机器人自己刚说过（含"被包含"关系）。
     *
     * <p>为什么用包含而不是相等：机器人通常说一整句「笑死，你这是CPU过载了」，
     * 群友复读的往往只是其中一小段「笑死」。
     */
    public boolean saidRecently(long groupId, String candidate) {
        Deque<Entry> dq = byGroup.get(groupId);
        if (dq == null || candidate == null || candidate.isEmpty()) return false;
        String cand = squeeze(candidate);
        if (cand.isEmpty()) return false;
        long now = System.currentTimeMillis();
        synchronized (dq) {
            for (java.util.Iterator<Entry> it = dq.descendingIterator(); it.hasNext(); ) {
                Entry e = it.next();
                if (now - e.ts > TTL_MS) break;
                if (e.norm.contains(cand) || cand.contains(e.norm)) return true;
            }
        }
        return false;
    }

    public int countOf(long groupId) {
        Deque<Entry> dq = byGroup.get(groupId);
        return dq == null ? 0 : dq.size();
    }

    /** 压缩空白、去首尾空白（用于比对，不改变语义） */
    private static String squeeze(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ");
    }
}
