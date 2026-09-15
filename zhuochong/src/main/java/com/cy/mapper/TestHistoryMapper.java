package com.cy.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface TestHistoryMapper {
    @Insert("INSERT INTO test_history (response) VALUES (#{response})")
    int insert(@Param("response") String response);

    @Select("SELECT * FROM test_history ORDER BY id DESC LIMIT 5")
    List<Map<String, Object>> findRecent();
}
