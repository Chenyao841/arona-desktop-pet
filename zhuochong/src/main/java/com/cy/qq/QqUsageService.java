package com.cy.qq;

import com.cy.service.DeepSeekService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * QQ 机器人 token 用量统计 + 每日限额（P3）。
 *
 * <p>怎么拿到用量：注册成 {@link DeepSeekService.UsageListener}，每次模型调用完都会回调
 * 「谁、多少 token」。QQ 侧在处理一条群消息前用 {@link #beginGroup(long)} 把「当前正在服务哪个群」
 * 放进 ThreadLocal（QQ 的事件处理本来就是一条排队一条串行），回调时就知道该记到哪个群头上。
 * 没有 QQ 上下文的调用（桌宠端）记到 {@code group_id = 0}，不计入机器人额度。
 *
 * <p>额度用超了会怎样：{@link #isQuotaExceeded()} 为 true 时，群消息不再调模型，
 * 直接回一句「今天额度用完了」——这是唯一能真正防住「群里刷屏把余额刷空」的闸门。
 */
@Service
public class QqUsageService implements DeepSeekService.UsageListener {

    @Autowired
    private QqUsageMapper mapper;

    @Autowired
    private QqConfig config;

    @Autowired
    private DeepSeekService deepSeekService;

    /** 额度报警用（私聊通知管理员）；只在超阈值时发，不会有循环 */
    @Autowired
    private QqReplyService replyService;

    /** 今天已发过的报警（key = 日期:阈值），跨天自动清空 */
    private final java.util.Set<String> alertsSent = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** 当前线程正在服务哪个群（0 = 桌宠端 / 无上下文） */
    private final ThreadLocal<Long> currentGroup = new ThreadLocal<>();

    /** 今日已用（本地累加，避免每条消息都查库；启动时从库里读一次） */
    private final AtomicLong todayTokens = new AtomicLong();
    /** 管理指令在运行时改的额度（0 = 不限；-1 = 未设置，用配置默认值） */
    private volatile long runtimeLimit = -1;
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong todayCalls = new AtomicLong();
    private volatile String today = "";

    @PostConstruct
    public void init() {
        // 注册成用量监听器（含桌宠端调用，桌宠记 group_id=0）
        deepSeekService.setUsageListener(this);
        try {
            mapper.createTableIfAbsent();
            today = todayStr();
            long t = mapper.todayQqTokens();
            todayTokens.set(t);
            System.out.println("[QQ] token 用量表就绪，今日已用 " + t + " tokens"
                    + (limit() > 0 ? "，限额 " + limit() : "，未设限额"));
        } catch (Exception e) {
            System.out.println("[QQ] token 用量表初始化失败（额度功能不可用）: " + e.getMessage());
        }
    }

    // ===== 上下文 =====

    public void beginGroup(long groupId) {
        currentGroup.set(groupId);
    }

    public void end() {
        currentGroup.remove();
    }

    @Override
    public void onUsage(String kind, String model, int promptTokens, int completionTokens) {
        Long g = currentGroup.get();
        long groupId = (g == null ? 0L : g);
        try {
            mapper.add(groupId, kind == null ? "chat" : kind, promptTokens, completionTokens);
        } catch (Exception e) {
            System.out.println("[QQ] 用量入库失败: " + e.getMessage());
        }
        rolloverIfNeeded();
        int used = promptTokens + completionTokens;
        totalCalls.incrementAndGet();
        processTokens.addAndGet(used);
        if (groupId != 0) {
            todayCalls.incrementAndGet();
            long now = todayTokens.addAndGet(used);
            checkQuotaAlerts(now);
            if (limit() > 0 && now > limit()) {
                System.out.println("[QQ] ⚠ 今日 token 已超限：" + now + " / " + limit() + "，暂停模型调用");
            }
        }
    }

    /**
     * 额度报警（新）：用量达到 {@code qq.quota-alert-percent}% 时，以及用满 100% 时，
     * 各私聊提醒一次管理员（每天每个阈值只提醒一次）。限额为 0（不限）时不提醒。
     */
    private void checkQuotaAlerts(long used) {
        long lim = limit();
        if (lim <= 0) return;
        int warnPct = config.getQuotaAlertPercent();
        try {
            if (warnPct > 0 && used * 100 >= lim * warnPct) {
                sendQuotaAlertOnce(today + ":warn",
                        "【额度提醒】今天的 token 已用到 " + pct(used, lim) + "%（" + used + " / " + lim + "）。\n"
                                + "要调高就回我：/limit 400000（0 = 不限）");
            }
            if (used >= lim) {
                sendQuotaAlertOnce(today + ":full",
                        "【额度已用尽】今天 " + lim + " tokens 用完了，群里再 @ 我只会回固定说明，不再调模型。\n"
                                + "调高：/limit 400000　　取消上限：/limit off");
            }
        } catch (Exception e) {
            System.out.println("[QQ] 额度报警发送失败: " + e.getMessage());
        }
    }

    private int pct(long used, long lim) {
        return lim <= 0 ? 0 : (int) Math.min(999, used * 100 / lim);
    }

    private void sendQuotaAlertOnce(String key, String text) {
        if (!alertsSent.add(key)) return; // 今天这个阈值已经提醒过
        int sent = 0;
        for (Long admin : config.getAdminQqs()) {
            if (replyService.replyPrivate(admin, text) > 0) sent++;
        }
        System.out.println("[QQ] 额度报警已发给 " + sent + " 位管理员（" + key + "）");
    }

    /** 跨天自动清零本地计数（服务不重启也不会把昨天的用量算今天） */
    private void rolloverIfNeeded() {
        String t = todayStr();
        if (!t.equals(today)) {
            today = t;
            todayTokens.set(0);
            todayCalls.set(0);
            alertsSent.clear(); // 跨天重置报警，新的一天重新提醒
        }
    }

    // ===== 额度 =====

    /** 生效中的每日上限（0 = 不限） */
    public long limit() {
        long rt = runtimeLimit;
        return rt >= 0 ? rt : Math.max(0, config.getDailyTokenLimit());
    }

    public boolean isQuotaExceeded() {
        long lim = limit();
        return lim > 0 && todayTokens.get() >= lim;
    }

    public long getTodayTokens() {
        rolloverIfNeeded();
        return todayTokens.get();
    }

    public long getTodayCalls() {
        rolloverIfNeeded();
        return todayCalls.get();
    }

    public long getTotalCalls() {
        return totalCalls.get();
    }

    /** 运行时改限额（管理指令用）；0 = 不限 */
    public void setLimit(long value) {
        runtimeLimit = Math.max(0, value);
        System.out.println("[QQ] 每日 token 限额已改为 " + (runtimeLimit == 0 ? "不限" : runtimeLimit));
    }

    /** 手动重置今日计数（配合「客户刚充值」这种场景） */
    public void resetTodayCounter() {
        todayTokens.set(0);
        todayCalls.set(0);
    }

    // ===== 报表（管理指令用） =====

    public Map<String, Object> summary() {
        Map<String, Object> m = new LinkedHashMap<>();
        rolloverIfNeeded();
        m.put("今日消耗", getTodayTokens());
        m.put("今日调用", getTodayCalls());
        m.put("每日限额", limit() == 0 ? "不限" : limit());
        m.put("剩余", limit() == 0 ? "不限" : Math.max(0, limit() - getTodayTokens()));
        try {
            m.put("历史消耗", mapper.totalQqTokens());
        } catch (Exception ignored) {
        }
        long tc = totalCalls.get();
        m.put("本次运行调用", tc);
        m.put("本次运行token", totalTokensLocal());
        return m;
    }

    /** 本次运行累计（含桌宠端） */
    private final AtomicLong processTokens = new AtomicLong();

    private long totalTokensLocal() {
        return processTokens.get();
    }

    public List<Map<String, Object>> todayByGroup() {
        try {
            return mapper.todayByGroup();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> todayByKind() {
        try {
            return mapper.todayByKind();
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> last7Days() {
        try {
            return mapper.last7Days();
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String todayStr() {
        return java.time.LocalDate.now().toString();
    }
}
