package com.cy.mapper;

import com.cy.pojo.ResponseRule;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface ResponseRuleMapper {
    @Select("SELECT * FROM response_rule ORDER BY id")
    List<ResponseRule> findAll();

    @Select("SELECT * FROM response_rule WHERE keyword = #{keyword} ORDER BY weight DESC")
    List<ResponseRule> findByKeyword(String keyword);

    @Insert("INSERT INTO response_rule (keyword, motion, sound_effect, special_effect, action, weight, sample_text) VALUES (#{keyword}, #{motion}, #{sound_effect}, #{special_effect}, #{action}, 1, #{sample_text})")
    int insertOrUpdate(ResponseRule rule);

    @Update("UPDATE response_rule SET keyword=#{keyword}, motion=#{motion}, sound_effect=#{sound_effect}, special_effect=#{special_effect} WHERE id=#{id}")
    int updateById(ResponseRule rule);

    @Update("UPDATE response_rule SET grow_count=#{grow_count}, cooldown=#{cooldown} WHERE id=#{id}")
    int updateGrowState(ResponseRule rule);

    @Delete("DELETE FROM response_rule WHERE id = #{id}")
    int delete(Integer id);
}
