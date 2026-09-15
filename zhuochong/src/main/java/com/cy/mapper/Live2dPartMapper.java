package com.cy.mapper;

import com.cy.pojo.Live2dPart;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface Live2dPartMapper {
    @Select("SELECT id, part_group, part_name, params FROM live2d_part ORDER BY id")
    List<Live2dPart> findAll();
}
