package com.cy.qq;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群维度运行态（P3「机器人管理」的地基）。
 *
 * <p>记的都是内存态（重启即清零，便宜且够用）：
 * 每个群收到多少消息、回了多少条、最后一次活动时间、是否被管理员临时关闭 / 静音。
 *
 * <p>「运行中的群聊」= 这个表里的群：只要机器人看到过该群的任意消息就会登记，
 * 但**只有 @ 自己**才会真正回话（P0 的规则不变）。
 */
@Component
public class QqRuntimeState {

    /** 单群运行态 */
    public static class GroupState {
        public final long groupId;
        public volatile String groupName = "";
        public final AtomicLong messageCount = new AtomicLong();
        public final AtomicLong replyCount = new AtomicLong();
        public volatile long lastMessageAt = 0L;
        public volatile long lastReplyAt = 0L;
        /** 关掉：该群完全不响应（白名单之外的另一种「临时闭嘴」） */
        public volatile boolean disabled = false;
        /** 静音到什么时候（毫秒时间戳，0 = 没静音） */
        public volatile long mutedUntil = 0L;
        /** P4：自从上次发表情包之后，已经发过多少条纯文字回复（判断"隔几条才允许再发"） */
        public final AtomicLong repliesSinceEmoji = new AtomicLong(0);
        /** P4：这个群是否已经发过表情包（没发过就不受"间隔"限制，开机第一句也可以是强情绪配图） */
        public volatile boolean emojiSentBefore = false;

        GroupState(long groupId) {
            this.groupId = groupId;
        }

        public boolean isMuted() {
            long mu = mutedUntil;
            return mu > 0 && mu > System.currentTimeMillis();
        }
    }

    private final Map<Long, GroupState> groups = new ConcurrentHashMap<>();
    private final long startedAt = System.currentTimeMillis();

    public GroupState touch(long groupId) {
        return groups.computeIfAbsent(groupId, GroupState::new);
    }

    public GroupState get(long groupId) {
        return groups.get(groupId);
    }

    public boolean isGroupEnabled(long groupId) {
        GroupState s = groups.get(groupId);
        return s == null || !s.disabled;
    }

    public boolean isGroupMuted(long groupId) {
        GroupState s = groups.get(groupId);
        return s != null && s.isMuted();
    }

    public void setDisabled(long groupId, boolean disabled) {
        touch(groupId).disabled = disabled;
    }

    public void mute(long groupId, long minutes) {
        touch(groupId).mutedUntil = System.currentTimeMillis() + Math.max(1, minutes) * 60_000L;
    }

    public void unmute(long groupId) {
        touch(groupId).mutedUntil = 0L;
    }

    /** 按最后活动时间倒序，最多取 limit 个 */
    public List<GroupState> activeGroups(int limit) {
        List<GroupState> list = new ArrayList<>(groups.values());
        list.sort(Comparator.comparingLong((GroupState g) -> g.lastMessageAt).reversed());
        return limit > 0 && list.size() > limit ? list.subList(0, limit) : list;
    }

    public int groupCount() {
        return groups.size();
    }

    public long getStartedAt() {
        return startedAt;
    }

    public long getUptimeMinutes() {
        return (System.currentTimeMillis() - startedAt) / 60_000L;
    }

    /** 「12 分钟前」这种人类说法 */
    public static String ago(long ts) {
        if (ts <= 0) return "-";
        long diff = System.currentTimeMillis() - ts;
        if (diff < 60_000) return "刚刚";
        long min = diff / 60_000;
        if (min < 60) return min + " 分钟前";
        long hour = min / 60;
        if (hour < 24) return hour + " 小时前";
        return (hour / 24) + " 天前";
    }
}
