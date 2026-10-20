package com.cy.qq;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 恶意 @ 黑名单（P4）。
 *
 * <p>命中黑名单的 QQ：群里 @ 它一律**静默忽略**（不回、不花 token），
 * 私聊本来就只有管理员能进，管理员不受影响。
 *
 * <p>内存缓存 + 数据库双写：判断走内存（每条消息都查，不能等 SQL），
 * 变更立刻落库（重启后仍然生效）。
 */
@Service
public class QqBlacklistService {

    @Autowired
    private QqBlacklistMapper mapper;

    /** qq → 原因 */
    private final Map<Long, String> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        try {
            mapper.createTableIfAbsent();
            List<Map<String, Object>> rows = mapper.findAll();
            if (rows != null) {
                for (Map<String, Object> r : rows) {
                    Object qq = r.get("qq");
                    if (qq instanceof Number) {
                        cache.put(((Number) qq).longValue(), String.valueOf(r.getOrDefault("reason", "")));
                    }
                }
            }
            System.out.println("[QQ] 黑名单已加载 " + cache.size() + " 人");
        } catch (Exception e) {
            System.out.println("[QQ] 黑名单初始化失败: " + e.getMessage());
        }
    }

    public boolean isBanned(long qq) {
        return qq > 0 && cache.containsKey(qq);
    }

    public int size() {
        return cache.size();
    }

    public String reasonOf(long qq) {
        return cache.get(qq);
    }

    /** 加入黑名单（幂等） */
    public boolean ban(long qq, String reason) {
        if (qq <= 0) return false;
        String r = (reason == null || reason.trim().isEmpty()) ? "管理员拉黑" : reason.trim();
        cache.put(qq, r);
        try {
            mapper.upsert(qq, r);
        } catch (Exception e) {
            System.out.println("[QQ] 黑名单入库失败（内存仍生效）: " + e.getMessage());
        }
        System.out.println("[QQ] 已拉黑 QQ " + qq + "（" + r + "）");
        return true;
    }

    public boolean unban(long qq) {
        if (qq <= 0) return false;
        boolean removed = cache.remove(qq) != null;
        try {
            mapper.delete(qq);
        } catch (Exception e) {
            System.out.println("[QQ] 黑名单删除失败: " + e.getMessage());
        }
        if (removed) System.out.println("[QQ] 已解除黑名单 QQ " + qq);
        return removed;
    }

    /** 快照（管理指令展示用） */
    public Map<Long, String> list() {
        return new LinkedHashMap<>(cache);
    }
}
