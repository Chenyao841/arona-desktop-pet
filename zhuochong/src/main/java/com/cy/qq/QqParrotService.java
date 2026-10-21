package com.cy.qq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跟风复读机（主动接话之一，**纯代码，不调用 AI**）。
 *
 * <p>规则（按需求实现）：
 * <ol>
 *   <li>同一句话被**不同用户**重复到 {@code parrot-min-users}（默认 3）次 → 获得一次"触发机会"</li>
 *   <li>触发时按概率 {@code parrot-probability}（默认 33%）决定要不要复读；不 @ 任何人</li>
 *   <li>第 3 次没中，**第 4、5、6…次（每多一个不同用户）继续判定**，直到复读成功或超过窗口</li>
 *   <li>复读前检查：这句话**是否已经复读过了** → 是则永远跳过（不会同一句复读两次）</li>
 *   <li>复读成功后，接下来 {@code parrot-cooldown-triggers}（默认 1）次触发机会**静默跳过**（冷却）</li>
 *   <li>安全阀：每组每小时最多复读 {@code parrot-max-per-hour}（默认 3）次，防刷屏</li>
 * </ol>
 *
 * <p>统计窗口 {@code parrot-window-sec}（默认 180s）：超过窗口的重复不再计数，
 * 所以"半小时前说过一句、现在又有人说"不会凑成 3 次。
 *
 * <p>过滤：命令（/ 开头）、图片/表情/语音占位、过短（<2 字）、过长（> {@code parrot-max-chars}）都不参与复读。
 */
@Service
public class QqParrotService {

    @Autowired
    private QqConfig config;

    @Autowired
    private QqTuning tuning;

    @Autowired
    private QqReplyQueue replyQueue;

    /** 机器人自己最近说过的话（判断"别复读自己"） */
    @Autowired
    private QqBotReplyMemory botReplyMemory;

    private final Random random = new Random();

    /** 同一句话的统计：群 + 归一化文本 → 记录 */
    private static final class Entry {
        final Set<Long> users = new HashSet<>();
        String echoText;      // 首次出现时的原文（复读用这个，最像人）
        long firstTs;
        long lastTs;

        Entry(String echoText, long now) {
            this.echoText = echoText;
            this.firstTs = now;
            this.lastTs = now;
        }
    }

    /** key = groupId + \u0001 + 归一化文本 */
    private final Map<String, Entry> trackers = new ConcurrentHashMap<>();
    /** 已复读过的句子：groupId+文本 → 复读时间（窗口内不再复读） */
    private final Map<String, Long> echoed = new ConcurrentHashMap<>();
    /** 复读成功后的剩余冷却次数：groupId → 还要跳过几次触发 */
    private final Map<Long, Integer> cooldownLeft = new ConcurrentHashMap<>();
    /** 每组最近一小时的复读时间戳（安全阀） */
    private final Map<Long, Deque<Long>> hourStamps = new ConcurrentHashMap<>();

    /**
     * 群消息入口（只统计"没 @ 机器人"的普通闲聊）。
     *
     * @return true = 这条消息触发了复读并已发出
     */
    public boolean onGroupMessage(long groupId, long userId, String rawText) {
        if (!tuning.parrotEnabled()) return false;
        String norm = normalize(rawText);
        if (norm == null) return false;

        long now = System.currentTimeMillis();
        long window = Math.max(10, config.getParrotWindowSec()) * 1000L;
        int needUsers = Math.max(2, config.getParrotMinUsers());

        String key = groupId + "\u0001" + norm;
        Entry e = trackers.computeIfAbsent(key, k -> new Entry(rawText.trim(), now));
        synchronized (e) {
            e.users.add(userId);
            e.lastTs = now;
            if (e.users.size() < needUsers) return false;

            // ===== 到达门槛：获得一次触发机会 =====
            // 1) 已经复读过的句子，永远不再复读
            Long lastEcho = echoed.get(key);
            if (lastEcho != null && now - lastEcho <= window) {
                System.out.println("[QQ] 复读跳过：这句话已经复读过了（" + abbrev(norm) + "）");
                return false;
            }
            // 1.5) 这句话本来就是**机器人自己刚说过的** → 不复读（不然等于自己重复自己）
            //      例：它回了「笑死，你这是CPU过载了」，群里开始刷「笑死」——这时不该跟风。
            if (botReplyMemory.saidRecently(groupId, norm)) {
                System.out.println("[QQ] 复读跳过：这句是我自己刚才说的（" + abbrev(norm) + "）");
                return false;
            }
            // 2) 冷却：上次复读成功后要静默 N 次
            int left = cooldownLeft.getOrDefault(groupId, 0);
            if (left > 0) {
                cooldownLeft.put(groupId, left - 1);
                System.out.println("[QQ] 复读跳过：冷却中（还剩 " + (left - 1) + " 次触发机会）");
                return false;
            }
            // 3) 安全阀：每小时上限
            Deque<Long> stamps = hourStamps.computeIfAbsent(groupId, k -> new ArrayDeque<>());
            synchronized (stamps) {
                while (!stamps.isEmpty() && now - stamps.peekFirst() > 3600_000L) stamps.pollFirst();
                if (config.getParrotMaxPerHour() > 0 && stamps.size() >= config.getParrotMaxPerHour()) {
                    System.out.println("[QQ] 复读跳过：本群这一小时已复读 " + stamps.size() + " 次（上限 "
                            + config.getParrotMaxPerHour() + "）");
                    return false;
                }
            }
            // 4) 概率判定
            int p = Math.max(0, Math.min(100, tuning.parrotProbability()));
            int roll = random.nextInt(100);
            if (roll >= p) {
                System.out.println("[QQ] 复读未命中：概率 " + p + "%，这次掷出 " + roll
                        + "（" + e.users.size() + " 个人重复了「" + abbrev(norm) + "」，下次还有人重复会继续判定）");
                return false;
            }

            // ===== 复读 =====
            String out = e.echoText == null || e.echoText.isEmpty() ? norm : e.echoText;
            if (out.length() > config.getParrotMaxChars()) out = out.substring(0, config.getParrotMaxChars());
            replyQueue.submitEcho(groupId, out);
            echoed.put(key, now);
            synchronized (stamps) {
                stamps.addLast(now);
            }
            cooldownLeft.put(groupId, Math.max(0, config.getParrotCooldownTriggers()));
            System.out.println("[QQ] 🔁 复读：「" + abbrev(out) + "」（" + e.users.size() + " 人重复，概率 "
                    + p + "% 命中，掷出 " + roll + "；之后静默 " + config.getParrotCooldownTriggers() + " 次触发）");
            return true;
        }
    }

    /** 轻量清理：窗口外的统计/复读记录定时丢弃（在调用点顺手做，不另起线程） */
    public void pruneIfNeeded() {
        if (trackers.size() < 800) return;
        long now = System.currentTimeMillis();
        long window = Math.max(10, config.getParrotWindowSec()) * 1000L;
        trackers.entrySet().removeIf(en -> now - en.getValue().lastTs > window * 2);
        echoed.entrySet().removeIf(en -> now - en.getValue() > window * 2);
    }

    /**
     * 归一化 + 过滤。返回 null 表示这句话不参与复读。
     *
     * <p>去首尾标点、压缩空白，这样「666」「666！」「666 ~~」算同一句。
     */
    static String normalize(String raw) {
        if (raw == null) return null;
        String t = raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) return null;
        // 命令、图片/表情/语音占位不参与
        if (t.startsWith("/") || t.startsWith("／")) return null;
        if (t.contains("[图片]") || t.contains("[表情]") || t.contains("[语音]") || t.contains("[CQ:")) return null;
        // 去首尾标点/符号
        t = t.replaceAll("^[\\p{Punct}\\s，。！？；：、~～…·]+", "")
                .replaceAll("[\\p{Punct}\\s，。！？；：、~～…·]+$", "")
                .trim();
        if (t.length() < 2) return null;
        // 至少含一个汉字/字母/数字，纯符号不参与
        if (!t.matches("(?s).*[\\p{IsHan}A-Za-z0-9].*")) return null;
        return t;
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 30 ? s.substring(0, 30) + "…" : s;
    }

    // ===== 状态（/status、/parrot 用） =====

    public int trackingCount() {
        return trackers.size();
    }

    public int cooldownOf(long groupId) {
        return cooldownLeft.getOrDefault(groupId, 0);
    }

    public int echoedLastHour(long groupId) {
        Deque<Long> dq = hourStamps.get(groupId);
        if (dq == null) return 0;
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (dq) {
            for (Iterator<Long> it = dq.descendingIterator(); it.hasNext(); ) {
                if (now - it.next() <= 3600_000L) n++;
                else break;
            }
        }
        return n;
    }
}
