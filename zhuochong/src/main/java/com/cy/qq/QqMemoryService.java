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

    /** 管理员用 /note 钉住的事实加这个前缀；自动提炼时会保留它们 */
    public static final String PINNED_PREFIX = "[置顶] ";

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
        consolidateCharacter(characterForGroup(groupId), "qq_g" + groupId, groupId);
    }

    /**
     * 提炼某个角色名的记忆。
     *
     * <p>注意：{@code DeepSeekService.consolidateMemory} 会先 {@code deleteAll} 再写回 AI 提炼结果，
     * 所以这里先把管理员 {@code /note} 钉住的事实快照下来，提炼完再补回去（否则会被清掉）。
     */
    public void consolidateCharacter(String character, String sessionId, long usageGroupId) {
        if (character == null) {
            System.out.println("[QQ] 记忆隔离已关闭（qq.memory-scope=off），跳过提炼");
            return;
        }
        java.util.List<String> pinned = new java.util.ArrayList<>();
        try {
            List<PetMemory> before = petMemoryMapper.findByCharacter(character);
            if (before != null) {
                for (PetMemory m : before) {
                    if (m.getContent() != null && m.getContent().startsWith(PINNED_PREFIX)) pinned.add(m.getContent());
                }
            }
        } catch (Exception e) {
            System.out.println("[QQ] 读取置顶记忆失败: " + e.getMessage());
        }

        usage.beginGroup(usageGroupId);
        try {
            deepSeekService.consolidateMemory(character, sessionId);
        } finally {
            usage.end();
        }

        if (!pinned.isEmpty()) {
            try {
                java.util.Set<String> now = new java.util.HashSet<>();
                List<PetMemory> after = petMemoryMapper.findByCharacter(character);
                if (after != null) for (PetMemory m : after) now.add(m.getContent());
                int restored = 0;
                for (String p : pinned) {
                    if (now.contains(p)) continue;
                    PetMemory m = new PetMemory();
                    m.setCharacter_name(character);
                    m.setContent(p);
                    petMemoryMapper.insert(m);
                    restored++;
                }
                if (restored > 0) System.out.println("[QQ] 提炼后补回 " + restored + " 条置顶记忆（" + character + "）");
            } catch (Exception e) {
                System.out.println("[QQ] 置顶记忆回填失败: " + e.getMessage());
            }
        }
    }

    // ===== 管理员钉住的事实（/note） =====

    /** 私聊记忆角色名 */
    public String characterForPrivate(long userId) {
        return "阿罗娜-私聊" + userId;
    }

    /** 钉一条长期记忆（永久保留，自动提炼不会冲掉） */
    public boolean pinNote(String character, String text) {
        if (character == null || text == null || text.trim().isEmpty()) return false;
        PetMemory m = new PetMemory();
        m.setCharacter_name(character);
        m.setContent(PINNED_PREFIX + text.trim());
        petMemoryMapper.insert(m);
        System.out.println("[QQ] 新增置顶记忆（" + character + "）: " + text.trim());
        return true;
    }

    /** 列出记忆（前置 id，方便删除） */
    public List<PetMemory> listNotes(String character) {
        if (character == null) return java.util.Collections.emptyList();
        List<PetMemory> all = petMemoryMapper.findByCharacter(character);
        return all == null ? java.util.Collections.emptyList() : all;
    }

    /** 删除一条记忆（按 id） */
    public boolean deleteNote(int id) {
        return petMemoryMapper.delete(id) > 0;
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
