package com.cy.qq;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 护短：群友调侃管理员时，站出来反驳（**调 AI**，带冷却）。
 *
 * <p>「在说管理员」怎么判断——三条路，随便命中一条就算指名道姓：
 * <ol>
 *   <li><b>@ 了管理员 QQ</b>（{@code qq.admin-qq}）</li>
 *   <li><b>叫了他的名字</b>：优先用**群昵称/群名片**（管理员在该群说过一句话，
 *       机器人就把他当时用的名片登记下来，比如「小羊羔」），另外还可以用
 *       {@code qq.defend-admin-alias} 额外配叫法（「小羊」「羊羊」之类）</li>
 *   <li><b>引申调侃词 + 上下文</b>：{@code qq.defend-mock-words}（羊肉/烤羊/涮羊肉…）
 *       单独出现不算，但管理员**刚在这个群发过言**（{@code qq.defend-context-sec}，
 *       默认 180s）就算在说他 —— 「今晚想吃烤羊」这种没人点名但懂的都懂</li>
 * </ol>
 *
 * <p>光"提到"还不够，还要有调侃的意思：命中 {@code qq.defend-keywords}（菜/笨蛋/又鸽了/摸鱼…）
 * 或语气标记（就这/不会吧/啧啧/呵呵/笑死/离谱/绷不住…）。
 * 唯一的例外就是第 3 条：引申词本身就是指着他开玩笑，配上上下文就够。
 *
 * <p>冷却（按需求：默认 3 次）：护短一次之后，接下来 {@code qq.defend-cooldown-triggers}
 * 次"触发机会"静默跳过（不消耗 AI、不说话）。
 *
 * <p>额外保护：
 * <ul>
 *   <li>管理员自己调侃自己 → 不护短</li>
 *   <li>每组每小时最多 {@code qq.defend-max-per-hour}（默认 5）次，护短要调模型，防烧钱</li>
 *   <li>命令（/ 开头）、图片占位等不参与</li>
 * </ul>
 */
@Service
public class QqDefendService {

    @Autowired
    private QqConfig config;

    @Autowired
    private QqTuning tuning;

    @Autowired
    private QqChatService chatService;

    @Autowired
    private QqReplyQueue replyQueue;

    /** 群 → (管理员QQ → 最近用过的群名片/昵称)；用来判断"名字出现在消息里" */
    private final Map<Long, Map<Long, String>> adminNicks = new ConcurrentHashMap<>();

    /** 群 → 管理员最近一次发言的时间戳（引申词要看他"刚说过话没有"） */
    private final Map<Long, Long> adminLastSpeakAt = new ConcurrentHashMap<>();

    /** 群 → 最后说话的那个管理员 QQ（引申词触发时用来决定"替谁出头"） */
    private final Map<Long, Long> adminLastSpeaker = new ConcurrentHashMap<>();

    /** 每组还剩几次触发机会要静默跳过 */
    private final Map<Long, Integer> cooldownLeft = new ConcurrentHashMap<>();

    /** 每组最近一小时的护短时间戳（安全阀） */
    private final Map<Long, Deque<Long>> hourStamps = new ConcurrentHashMap<>();

    /**
     * 管理员发言时登记：群名片/昵称 + 发言时间。
     *
     * <p>昵称传的是**群名片优先**（NapCat 的 {@code sender.card}，没填才退回 QQ 昵称），
     * 所以「小羊羔」这种群昵称会被自动记住，不用额外配。
     */
    public void rememberAdminNick(long groupId, long userId, String nickname) {
        if (!isAdmin(userId)) return;
        adminLastSpeakAt.put(groupId, System.currentTimeMillis());
        adminLastSpeaker.put(groupId, userId);
        if (nickname == null || nickname.trim().isEmpty()) return;
        adminNicks.computeIfAbsent(groupId, k -> new ConcurrentHashMap<>())
                .put(userId, nickname.trim());
    }

    /**
     * 群消息入口（只处理"没 @ 机器人自己"的普通闲聊）。
     *
     * @param atAdminQqs 这条消息 @ 到的 QQ 里，属于管理员的那些
     * @return true = 已经安排护短回复
     */
    public boolean onGroupMessage(long groupId, long userId, String nickname, String text,
                                  Set<Long> atAdminQqs, long messageId) {
        if (!tuning.defendEnabled()) return false;
        if (isAdmin(userId)) return false;              // 管理员自己调侃自己，不护短
        if (text == null || text.trim().isEmpty()) return false;
        String t = text.trim();
        if (t.startsWith("/") || t.startsWith("／")) return false;
        if (t.contains("[图片]") || t.contains("[表情]") || t.contains("[语音]")) return false;

        Set<Long> admins = config.getAdminQqs();
        if (admins.isEmpty()) return false;

        // ===== ① 是不是在说管理员 =====
        boolean atAdmin = false;
        long whichAdmin = 0L;
        if (atAdminQqs != null) {
            for (Long q : atAdminQqs) {
                if (admins.contains(q)) {
                    atAdmin = true;
                    whichAdmin = q;
                    break;
                }
            }
        }

        // ② 名字：先看自动登记的群名片，再看配置的额外叫法
        String calledAs = null;
        Map<Long, String> nicks = adminNicks.get(groupId);
        if (nicks != null) {
            for (Map.Entry<Long, String> e : nicks.entrySet()) {
                String n = e.getValue();
                if (n != null && n.length() >= 2 && t.contains(n)) {
                    calledAs = n;
                    if (whichAdmin == 0) whichAdmin = e.getKey();
                    break;
                }
            }
        }
        if (calledAs == null) {
            for (String alias : tuning.defendAliases()) {
                if (alias.length() >= 2 && t.contains(alias)) {
                    calledAs = alias;
                    break;
                }
            }
        }

        // ③ 引申调侃词（羊肉/烤羊…）：得看管理员刚说过话没有
        String mockWord = null;
        for (String w : config.getDefendMockWords()) {
            if (!w.isEmpty() && t.contains(w)) {
                mockWord = w;
                break;
            }
        }
        boolean contextFresh = adminSpokeWithin(groupId);

        // ===== ② 判定 + 有没有调侃的意思 =====
        boolean teasing = isTeasing(t);
        String way;
        if ((atAdmin || calledAs != null) && teasing) {
            way = atAdmin ? "@了管理员" : "叫了管理员「" + calledAs + "」";
        } else if (mockWord != null && (teasing || contextFresh)) {
            way = "提到「" + mockWord + "」" + (teasing ? "" : "，管理员刚发过言（上下文）");
        } else {
            return false;
        }
        if (whichAdmin == 0) {
            Long last = adminLastSpeaker.get(groupId);
            whichAdmin = last != null && admins.contains(last) ? last : admins.iterator().next();
        }

        long now = System.currentTimeMillis();

        // ===== 冷却：护短一次后静默 N 次触发 =====
        int left = cooldownLeft.getOrDefault(groupId, 0);
        if (left > 0) {
            cooldownLeft.put(groupId, left - 1);
            System.out.println("[QQ] 护短跳过：冷却中（还剩 " + (left - 1) + " 次触发机会）");
            return false;
        }

        // ===== 安全阀：每小时上限 =====
        Deque<Long> stamps = hourStamps.computeIfAbsent(groupId, k -> new ArrayDeque<>());
        synchronized (stamps) {
            while (!stamps.isEmpty() && now - stamps.peekFirst() > 3600_000L) stamps.pollFirst();
            if (config.getDefendMaxPerHour() > 0 && stamps.size() >= config.getDefendMaxPerHour()) {
                System.out.println("[QQ] 护短跳过：本群这一小时已护短 " + stamps.size() + " 次（上限 "
                        + config.getDefendMaxPerHour() + "）");
                return false;
            }
            stamps.addLast(now);
        }

        // ===== 安排护短回复（走 AI，带固定延迟）=====
        cooldownLeft.put(groupId, Math.max(0, config.getDefendCooldownTriggers()));
        System.out.println("[QQ] 🛡 管理员被调侃（" + way + "，来自 " + nickname + "）：" + abbrev(t)
                + " → 安排护短（之后静默 " + config.getDefendCooldownTriggers() + " 次触发）");
        final long adminQq = whichAdmin;
        final String fText = t;
        final String fNick = nickname == null ? ("QQ" + userId) : nickname;
        final long fUser = userId;
        replyQueue.submitDefend(groupId, messageId,
                () -> chatService.thinkDefend(groupId, adminQq, fNick, fUser, fText));
        return true;
    }

    /** 调侃性关键词 + 语气标记 */
    boolean isTeasing(String text) {
        String t = text.toLowerCase();
        for (String kw : config.getDefendKeywords()) {
            if (!kw.isEmpty() && t.contains(kw.toLowerCase())) return true;
        }
        return t.matches("(?s).*(就这|不会吧|啧啧|呵呵|笑死|离谱|绷不住|又来了吧|行不行啊|不靠谱).*");
    }

    /** 管理员最近一次发言是不是还在上下文窗口内 */
    public boolean adminSpokeWithin(long groupId) {
        Long at = adminLastSpeakAt.get(groupId);
        if (at == null) return false;
        int win = config.getDefendContextSec();
        if (win <= 0) return false;
        return System.currentTimeMillis() - at <= win * 1000L;
    }

    /** 管理员上一次发言距今多少秒（没说过话返回 -1） */
    public long adminSpokeAgoSec(long groupId) {
        Long at = adminLastSpeakAt.get(groupId);
        if (at == null) return -1L;
        return (System.currentTimeMillis() - at) / 1000L;
    }

    private boolean isAdmin(long userId) {
        return config.getAdminQqs().contains(userId);
    }

    private static String abbrev(String s) {
        if (s == null) return "";
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    // ===== 状态（/status、/defend 用） =====

    public int cooldownOf(long groupId) {
        return cooldownLeft.getOrDefault(groupId, 0);
    }

    public int defendedLastHour(long groupId) {
        Deque<Long> dq = hourStamps.get(groupId);
        if (dq == null) return 0;
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (dq) {
            for (java.util.Iterator<Long> it = dq.descendingIterator(); it.hasNext(); ) {
                if (now - it.next() <= 3600_000L) n++;
                else break;
            }
        }
        return n;
    }

    /** 这个群自动登记到的管理员群名片（「小羊羔」这类） */
    public Set<String> knownAdminNicks(long groupId) {
        Map<Long, String> m = adminNicks.get(groupId);
        return m == null ? new HashSet<>() : new LinkedHashSet<>(m.values());
    }
}
