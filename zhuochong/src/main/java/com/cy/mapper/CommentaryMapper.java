package com.cy.mapper;

import com.cy.pojo.CommentaryConfig;
import com.cy.pojo.CommentaryContext;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CommentaryMapper {

    // ===== 建表（首次运行自建，并从默认值填充） =====
    @Update("CREATE TABLE IF NOT EXISTS commentary_config (" +
            "id INT PRIMARY KEY AUTO_INCREMENT," +
            "enabled TINYINT DEFAULT 0," +
            "interval_sec INT DEFAULT 12," +
            "min_change_score INT DEFAULT 3," +
            "max_streak INT DEFAULT 3," +
            "silence_sec INT DEFAULT 45," +
            "after_talk_sec INT DEFAULT 20," +
            "voice_on TINYINT DEFAULT 1," +
            "volume_percent INT DEFAULT 45," +
            "skip_percent INT DEFAULT 35," +
            "max_chars INT DEFAULT 20," +
            "keep_minutes INT DEFAULT 60," +
            "auto_detect TINYINT DEFAULT 1," +
            "manual_ttl_min INT DEFAULT 15," +
            "max_tokens INT DEFAULT 1200" +
            ")")
    void createConfigTable();

    @Update("CREATE TABLE IF NOT EXISTS commentary_context (" +
            "id INT PRIMARY KEY AUTO_INCREMENT," +
            "code VARCHAR(20) UNIQUE," +
            "label VARCHAR(40)," +
            "prompt TEXT," +
            "enabled TINYINT DEFAULT 1" +
            ")")
    void createContextTable();

    /** 老表补列（首次升级用；列已存在时 MySQL 会报错，由调用方 catch 忽略） */
    @Update("ALTER TABLE commentary_config ADD COLUMN max_tokens INT DEFAULT 1200")
    void addMaxTokensColumn();

    // ===== 参数 =====
    @Select("SELECT * FROM commentary_config ORDER BY id LIMIT 1")
    CommentaryConfig findConfig();

    @Update("UPDATE commentary_config SET enabled=#{enabled}, interval_sec=#{interval_sec}, min_change_score=#{min_change_score}, " +
            "max_streak=#{max_streak}, silence_sec=#{silence_sec}, after_talk_sec=#{after_talk_sec}, voice_on=#{voice_on}, " +
            "volume_percent=#{volume_percent}, skip_percent=#{skip_percent}, max_chars=#{max_chars}, keep_minutes=#{keep_minutes}, " +
            "auto_detect=#{auto_detect}, manual_ttl_min=#{manual_ttl_min}, max_tokens=#{max_tokens} WHERE id=#{id}")
    int updateConfig(CommentaryConfig c);

    @Insert("INSERT INTO commentary_config (enabled, interval_sec, min_change_score, max_streak, silence_sec, after_talk_sec, " +
            "voice_on, volume_percent, skip_percent, max_chars, keep_minutes, auto_detect, manual_ttl_min, max_tokens) VALUES (" +
            "#{enabled}, #{interval_sec}, #{min_change_score}, #{max_streak}, #{silence_sec}, #{after_talk_sec}, " +
            "#{voice_on}, #{volume_percent}, #{skip_percent}, #{max_chars}, #{keep_minutes}, #{auto_detect}, #{manual_ttl_min}, #{max_tokens})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertConfig(CommentaryConfig c);

    // ===== 语境 =====
    @Select("SELECT * FROM commentary_context ORDER BY id")
    List<CommentaryContext> findAllContexts();

    @Select("SELECT * FROM commentary_context WHERE code = #{code} LIMIT 1")
    CommentaryContext findByCode(String code);

    @Insert("INSERT INTO commentary_context (code, label, prompt, enabled) VALUES (#{code}, #{label}, #{prompt}, #{enabled})")
    int insertContext(CommentaryContext c);

    @Update("UPDATE commentary_context SET label=#{label}, prompt=#{prompt}, enabled=#{enabled} WHERE id=#{id}")
    int updateContext(CommentaryContext c);
}
