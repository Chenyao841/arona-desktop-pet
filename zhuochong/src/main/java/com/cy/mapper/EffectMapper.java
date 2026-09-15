package com.cy.mapper;

import com.cy.pojo.Effect;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface EffectMapper {
    @Select("SELECT * FROM effect ORDER BY id")
    List<Effect> findAll();

    @Select("SELECT * FROM effect WHERE id = #{id}")
    Effect findById(@Param("id") Integer id);

    @Update("UPDATE effect SET live2d_location = #{live2dLocation} WHERE id = #{id}")
    int updateLive2dLocation(@Param("id") Integer id, @Param("live2dLocation") String live2dLocation);
}
