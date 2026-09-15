package com.cy.mapper;

import com.cy.pojo.StyleExemplar;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface StyleExemplarMapper {
    @Insert("INSERT INTO style_exemplars (tags, content) VALUES (#{tags}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(StyleExemplar e);

    @Select("SELECT id, tags, content FROM style_exemplars ORDER BY id DESC")
    List<StyleExemplar> findAll();

    @Delete("DELETE FROM style_exemplars WHERE id = #{id}")
    int deleteById(Integer id);
}
