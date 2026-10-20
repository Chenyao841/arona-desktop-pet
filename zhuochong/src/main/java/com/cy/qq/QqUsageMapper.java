package com.cy.qq;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * QQ 机器人 token 用量（P3）。
 *
 * <p>按「日期 + 群号 + 类型」聚合，行数很少（一天 × 群数 × 2 种类型），
 * 明细交给已有的 {@code ai_history}，这里只做额度与统计。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code group_id > 0} —— 真实群聊消耗</li>
 *   <li>{@code group_id < 0} —— 管理员私聊消耗（记成 -QQ号，不参与"群排行"，但算进每日额度）</li>
 *   <li>{@code group_id = 0} —— 桌宠端消耗（也记，方便看总盘子；<b>不计入机器人额度</b>）</li>
 * </ul>
 */
@Mapper
public interface QqUsageMapper {

    @Update("CREATE TABLE IF NOT EXISTS qq_token_usage (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "usage_date DATE NOT NULL," +
            "group_id BIGINT NOT NULL DEFAULT 0," +
            "kind VARCHAR(20) NOT NULL DEFAULT 'chat'," +
            "calls INT NOT NULL DEFAULT 0," +
            "prompt_tokens BIGINT NOT NULL DEFAULT 0," +
            "completion_tokens BIGINT NOT NULL DEFAULT 0," +
            "updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_day_group_kind (usage_date, group_id, kind)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTableIfAbsent();

    @Insert("INSERT INTO qq_token_usage (usage_date, group_id, kind, calls, prompt_tokens, completion_tokens) " +
            "VALUES (CURDATE(), #{groupId}, #{kind}, 1, #{prompt}, #{completion}) " +
            "ON DUPLICATE KEY UPDATE calls = calls + 1, " +
            "prompt_tokens = prompt_tokens + VALUES(prompt_tokens), " +
            "completion_tokens = completion_tokens + VALUES(completion_tokens)")
    int add(@Param("groupId") long groupId, @Param("kind") String kind,
            @Param("prompt") int prompt, @Param("completion") int completion);

    /** 今天机器人消耗的总 token（群聊 + 管理员私聊，不含桌宠端） */
    @Select("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM qq_token_usage " +
            "WHERE usage_date = CURDATE() AND group_id <> 0")
    long todayQqTokens();

    /** 有史以来机器人消耗的总 token */
    @Select("SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) FROM qq_token_usage WHERE group_id <> 0")
    long totalQqTokens();

    /** 今天各群的消耗（只列真实群聊） */
    @Select("SELECT group_id AS groupId, SUM(calls) AS calls, " +
            "SUM(prompt_tokens) AS promptTokens, SUM(completion_tokens) AS completionTokens, " +
            "SUM(prompt_tokens + completion_tokens) AS totalTokens " +
            "FROM qq_token_usage WHERE usage_date = CURDATE() AND group_id > 0 " +
            "GROUP BY group_id ORDER BY totalTokens DESC")
    List<Map<String, Object>> todayByGroup();

    /** 今天按类型（chat / vision）的消耗，含私聊 */
    @Select("SELECT kind, SUM(calls) AS calls, SUM(prompt_tokens + completion_tokens) AS totalTokens " +
            "FROM qq_token_usage WHERE usage_date = CURDATE() AND group_id <> 0 GROUP BY kind")
    List<Map<String, Object>> todayByKind();

    /** 最近 7 天（含桌宠，方便看整体趋势） */
    @Select("SELECT usage_date AS usageDate, SUM(prompt_tokens + completion_tokens) AS totalTokens " +
            "FROM qq_token_usage GROUP BY usage_date ORDER BY usage_date DESC LIMIT 7")
    List<Map<String, Object>> last7Days();
}
