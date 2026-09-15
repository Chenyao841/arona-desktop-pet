package com.cy.mapper;

import com.cy.pojo.CommandLib;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface CommandLibMapper {

    /** 首次使用时自动建表 */
    @Update("CREATE TABLE IF NOT EXISTS command_lib (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "module VARCHAR(40) NOT NULL," +
            "command VARCHAR(100) NOT NULL," +
            "feature VARCHAR(300) NOT NULL DEFAULT ''," +
            "enabled TINYINT NOT NULL DEFAULT 1," +
            "created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP," +
            "UNIQUE KEY uk_command (command)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTableIfAbsent();

    @Select("SELECT COUNT(*) FROM command_lib")
    int countAll();

    @Select("SELECT id, module, command, feature, enabled, created_at FROM command_lib ORDER BY module, id")
    List<CommandLib> findAll();

    @Select("SELECT id, module, command, feature, enabled, created_at FROM command_lib WHERE module = #{module} ORDER BY id")
    List<CommandLib> findByModule(@Param("module") String module);

    @Insert("INSERT INTO command_lib (module, command, feature, enabled) VALUES (#{module}, #{command}, #{feature}, 1)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CommandLib c);

    @Update("UPDATE command_lib SET module=#{module}, command=#{command}, feature=#{feature}, enabled=#{enabled} WHERE id=#{id}")
    int update(CommandLib c);

    @Delete("DELETE FROM command_lib WHERE id = #{id}")
    int delete(@Param("id") Integer id);
}
