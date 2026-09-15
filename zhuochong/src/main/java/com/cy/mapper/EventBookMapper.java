package com.cy.mapper;

import com.cy.pojo.EventBook;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface EventBookMapper {

    @Insert("INSERT INTO event_book (event_type, content) VALUES (#{event_type}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(EventBook e);

    /** 最近 N 条事件（按时间倒序，AI 注入用） */
    @Select("SELECT id, event_type, content, created_at FROM event_book ORDER BY id DESC LIMIT #{limit}")
    List<EventBook> findRecent(int limit);

    /** 保留最近 N 条，其余删除（事件簿设 50 条上限） */
    @Delete("DELETE FROM event_book WHERE id <= (SELECT id FROM (SELECT COALESCE(MAX(id),0) - #{keep} AS id FROM event_book) t)")
    int trim(int keep);
}
