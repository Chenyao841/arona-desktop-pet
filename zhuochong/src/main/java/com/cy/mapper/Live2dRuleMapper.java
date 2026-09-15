package com.cy.mapper;

import com.cy.pojo.Live2dRule;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface Live2dRuleMapper {
    @Select("SELECT id, boat_text, situation, expression, action, sound_effect, special_effect FROM live2d_rule ORDER BY id")
    List<Live2dRule> findAll();

    @Insert("INSERT INTO live2d_rule (boat_text, situation, expression, action, sound_effect, special_effect) VALUES (#{boat_text}, #{situation}, #{expression}, #{action}, #{sound_effect}, #{special_effect})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Live2dRule r);

    @Update("UPDATE live2d_rule SET boat_text=#{boat_text}, situation=#{situation}, expression=#{expression}, action=#{action}, sound_effect=#{sound_effect}, special_effect=#{special_effect} WHERE id=#{id}")
    int update(Live2dRule r);

    @Delete("DELETE FROM live2d_rule WHERE id=#{id}")
    int deleteById(Integer id);
}
