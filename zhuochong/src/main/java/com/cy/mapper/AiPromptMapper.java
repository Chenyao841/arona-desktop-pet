package com.cy.mapper;

import com.cy.pojo.AiPrompt;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AiPromptMapper {
    @Select("SELECT * FROM ai_prompt ORDER BY prompt_type")
    List<AiPrompt> findAll();
    @Select("SELECT * FROM ai_prompt WHERE id = #{id}")
    AiPrompt findById(Integer id);
    @Select("SELECT * FROM ai_prompt WHERE prompt_type = #{type} ORDER BY version DESC LIMIT 1")
    AiPrompt findByType(String type);

    @Select("SELECT * FROM ai_prompt WHERE prompt_type = #{type} AND version = #{version}")
    AiPrompt findByTypeAndVersion(@Param("type") String type, @Param("version") Integer version);
    @Insert("INSERT INTO ai_prompt (prompt_type, content, version) VALUES (#{prompt_type}, #{content}, #{version})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiPrompt p);
    @Update("UPDATE ai_prompt SET content=#{content} WHERE id=#{id}")
    int update(AiPrompt p);
    @Delete("DELETE FROM ai_prompt WHERE id = #{id}")
    int deleteById(Integer id);

    @Select("SELECT * FROM ai_prompt WHERE version = #{version}")
    List<AiPrompt> findByVersion(@Param("version") Integer version);
}
