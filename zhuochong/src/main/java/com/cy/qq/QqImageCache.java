package com.cy.qq;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「先发图、再 @ 机器人」的图片暂存（P4+）。
 *
 * <p>为什么需要：群里最常见的操作是「先把图发出来，然后 @ 机器人问这是什么」——
 * 这是两条独立消息，纯图那条没有 @ 机器人，本来会被直接忽略（P2 闲置响应还没做），
 * 于是模型只看到"只 @ 了你，没说话"，自然回答"看不到图"。
 *
 * <p>做法：纯图消息按「群 + 人」缓存 {@link #TTL_MS} 毫秒；
 * 同一个人随后 @ 机器人且自己没带图时，把这些图取出来用（一次性，用完即清）。
 */
@Component
public class QqImageCache {

    /** 缓存有效期：2 分钟（一般"发图→@它"都在几秒内） */
    private static final long TTL_MS = 120_000L;

    private static final class Entry {
        final List<String> urls;
        final long ts;

        Entry(List<String> urls, long ts) {
            this.urls = urls;
            this.ts = ts;
        }
    }

    private final Map<String, Entry> cache = new ConcurrentHashMap<>();

    private static String key(long groupId, long userId) {
        return groupId + "_" + userId;
    }

    /** 记下某人刚发的图（覆盖旧的） */
    public void put(long groupId, long userId, List<String> urls) {
        if (urls == null || urls.isEmpty()) return;
        cache.put(key(groupId, userId), new Entry(new ArrayList<>(urls), System.currentTimeMillis()));
        if (cache.size() > 500) purgeExpired();
    }

    /** 取出并用掉（一次性）；没有或过期返回空列表 */
    public List<String> take(long groupId, long userId) {
        Entry e = cache.remove(key(groupId, userId));
        if (e == null) return new ArrayList<>();
        if (System.currentTimeMillis() - e.ts > TTL_MS) return new ArrayList<>();
        return new ArrayList<>(e.urls);
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        cache.entrySet().removeIf(en -> now - en.getValue().ts > TTL_MS);
    }

    public int size() {
        return cache.size();
    }
}
