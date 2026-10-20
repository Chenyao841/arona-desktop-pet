package com.cy.qq;

import com.cy.mapper.AiHistoryMapper;
import com.cy.mapper.PetMemoryMapper;
import com.cy.pojo.PetMemory;
import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 群聊长期记忆（P3：「记忆按群号+QQ号隔离」）。
 *
 * <p>做法：复用 {@code pet_memory} 表，但用**独立的角色名**当命名空间，
 * 这样群聊记忆和桌宠记忆互不污染，也不用改表结构（{@code PetMemoryMapper} 本来按 character_name 分区）。
 *
 * <ul>
 *   <li>{@code qq.memory-scope=group}（默认）→ {@code 阿罗娜-群{群号}}：整群共用一份，像"群里的一个角色"</li>
 *   <li>{@code qq.memory-scope=user} → {@code 阿罗娜-群{群号}-QQ{QQ号}}：每人一份，最细</li>
 *   <li>{@code qq.memory-scope=off} → 不隔离（沿用桌宠记忆，仅调试用）</li>
 * </ul>
 *
 * <p>什么时候提炼：每处理 {@code qq.memory-consolidate-every} 条群消息（默认 20）后台提炼一次，
 * 或者管理员发指令强制提炼。提炼本身也走 DeepSeek，会记账到该群头上。
 */
@Service
public class QqMemoryService {

    @Autowired
    private QqConfig config;

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private PetMemoryMapper petMemoryMapper;

    @Autowired
    private AiHistoryMapper aiHistoryMapper;

    @Autowired
    private QqUsageService usage;

    /** sessionId → 已处理消息数（决定何时提炼） */
    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    /** 记忆命名空间；null = 不隔离 */
    public String characterFor(long groupId, long userId) {
        String scope = config.getMemoryScope();
        if ("off".equalsIgnoreCase(scope)) return null;
        if ("user".equalsIgnoreCase(scope)) return "阿罗娜-群" + groupId + "-QQ" + userId;
        return "阿罗娜-群" + groupId;
    }

    /** 群维度命名空间（管理指令 / 清理用，永远按群算） */
    public String characterForGroup(long groupId) {
        String scope = config.getMemoryScope();
        if ("off".equalsIgnoreCase(scope)) return null;
        return "阿罗娜-群" + groupId;
    }

    /** 每条群消息都调一次；到阈值就在后台提炼 */
    public void afterMessage(long groupId, long userId) {
        int every = config.getMemoryConsolidateEvery();
        if (every <= 0) return;
        String key = "qq_g" + groupId;
        long n = counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        if (n % every != 0) return;
        String character = characterForGroup(groupId);
        if (character == null) return;
        System.out.println("[QQ] 群 " + groupId + " 已积累 " + n + " 条对话，后台提炼长期记忆（角色名 " + character + "）");
        Thread t = new Thread(() -> consolidate(groupId), "qq-memory-" + groupId);
        t.setDaemon(true);
        t.start();
    }

    /** 立即提炼某个群的记忆（异步调用方保证不卡事件处理） */
    public void consolidate(long groupId) {
        String character = characterForGroup(groupId);
        if (character == null) {
            System.out.println("[QQ] 记忆隔离已关闭（qq.memory-scope=off），跳过提炼");
            return;
        }
        usage.beginGroup(groupId);
        try {
            deepSeekService.consolidateMemory(character, "qq_g" + groupId);
        } finally {
            usage.end();
        }
    }

    /** 清空某个群的长期记忆 */
    public boolean forget(long groupId) {
        String character = characterForGroup(groupId);
        if (character == null) return false;
        int n = petMemoryMapper.deleteAll(character);
        System.out.println("[QQ] 已清空群 " + groupId + " 的长期记忆（" + n + " 条）");
        return true;
    }

    /** 当前记忆条数（管理指令展示用） */
    public int countOf(long groupId) {
        String character = characterForGroup(groupId);
        if (character == null) return 0;
        List<PetMemory> list = petMemoryMapper.findByCharacter(character);
        return list == null ? 0 : list.size();
    }

    /** 清空某个群的会话历史（ai_history 里 session_id = qq_g{群号}） */
    public int clearSession(long groupId) {
        int n = aiHistoryMapper.deleteBySession("qq_g" + groupId);
        counters.remove("qq_g" + groupId);
        System.out.println("[QQ] 已清空群 " + groupId + " 的会话历史（" + n + " 条）");
        return n;
    }
}
