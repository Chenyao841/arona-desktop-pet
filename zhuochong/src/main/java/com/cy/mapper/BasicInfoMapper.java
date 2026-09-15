package com.cy.mapper;

import com.cy.pojo.BasicInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.*;

@Mapper
public interface BasicInfoMapper {
    @Select("SELECT * FROM basicinfo WHERE id = #{id}")
    BasicInfo findById(@Param("id") Integer id);

    @Update("UPDATE basicinfo SET 姓名=#{姓名}, 身份=#{身份} WHERE id=#{id}")
    int update(BasicInfo info);
}
