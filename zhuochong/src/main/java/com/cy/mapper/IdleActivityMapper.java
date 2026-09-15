package com.cy.mapper;

import com.cy.pojo.IdleActivity;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface IdleActivityMapper {
    @Select("SELECT * FROM idle_activity ORDER BY id")
    List<IdleActivity> findAll();

    @Select("SELECT * FROM idle_activity WHERE enabled = 1 ORDER BY id")
    List<IdleActivity> findEnabled();

    @Insert("INSERT INTO idle_activity (theme, category, enabled) VALUES (#{theme}, #{category}, #{enabled})")
    int insert(IdleActivity activity);

    @Update("UPDATE idle_activity SET theme=#{theme}, category=#{category}, enabled=#{enabled} WHERE id=#{id}")
    int update(IdleActivity activity);

    @Delete("DELETE FROM idle_activity WHERE id = #{id}")
    int delete(Integer id);
}
