package com.cy.qq;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

/**
 * 恶意 @ 黑名单（P4）。一 QQ 一行，全局生效（进黑名单的人在**所有群**都不再被回复）。
 *
 * <p>只有管理员能增删：{@code /ban <QQ> [原因]}、{@code /unban <QQ>}、{@code /blacklist}。
 * 触发条件：同一个 QQ 在「回复延迟窗口」内连续 @ 达到 {@code qq.blacklist-burst} 次（默认 4）。
 */
@Mapper
public interface QqBlacklistMapper {

    @Update("CREATE TABLE IF NOT EXISTS qq_blacklist (" +
            "id BIGINT AUTO_INCREMENT PRIMARY KEY," +
            "qq BIGINT NOT NULL," +
            "reason VARCHAR(200) NOT NULL DEFAULT ''," +
            "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_qq (qq)" +
            ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTableIfAbsent();

    @Select("SELECT qq, reason, created_at AS createdAt FROM qq_blacklist ORDER BY created_at DESC")
    List<Map<String, Object>> findAll();

    @Insert("INSERT INTO qq_blacklist (qq, reason) VALUES (#{qq}, #{reason}) " +
            "ON DUPLICATE KEY UPDATE reason = VALUES(reason)")
    int upsert(@Param("qq") long qq, @Param("reason") String reason);

    @Delete("DELETE FROM qq_blacklist WHERE qq = #{qq}")
    int delete(@Param("qq") long qq);
}
