package com.cy.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface AiHistoryMapper {
    @Insert("INSERT INTO ai_history (session_id, user_text, user_operation, boat_text, motion, sound_effect, special_effect) " +
            "VALUES (#{session_id}, #{user_text}, #{user_operation}, #{boat_text}, #{motion}, #{sound_effect}, #{special_effect})")
    int insert(Map<String, Object> record);

    @Select("SELECT user_text, boat_text FROM (SELECT id, user_text, boat_text FROM ai_history WHERE session_id = #{sessionId} ORDER BY id DESC LIMIT 20) t ORDER BY id ASC")
    List<Map<String, Object>> findBySession(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM ai_history WHERE session_id = #{sessionId}")
    int deleteBySession(@Param("sessionId") String sessionId);

    @Delete("DELETE FROM ai_history WHERE session_id LIKE #{prefix}")
    int deleteBySessionPrefix(@Param("prefix") String prefix);

    @Select("SELECT * FROM ai_history ORDER BY id DESC")
    List<Map<String, Object>> findAll();

    @Select("SELECT * FROM ai_history WHERE starred = 1 ORDER BY id DESC")
    List<Map<String, Object>> findStarred();

    @Update("UPDATE ai_history SET starred = #{starred} WHERE id = #{id}")
    int updateStarred(@Param("id") Integer id, @Param("starred") Integer starred);

    @Update("UPDATE ai_history SET user_operation=#{user_operation}, user_text=#{user_text}, boat_text=#{boat_text}, motion=#{motion}, sound_effect=#{sound_effect}, special_effect=#{special_effect} WHERE id=#{id}")
    int update(Map<String, Object> record);

    @Delete("DELETE FROM ai_history WHERE id = #{id}")
    int deleteById(@Param("id") Integer id);
}
