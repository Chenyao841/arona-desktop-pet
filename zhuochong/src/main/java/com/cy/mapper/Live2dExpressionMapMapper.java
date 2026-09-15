package com.cy.mapper;

import com.cy.pojo.Live2dExpressionMap;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface Live2dExpressionMapMapper {

    @Update("CREATE TABLE IF NOT EXISTS live2d_expression_map (" +
            "id INT AUTO_INCREMENT PRIMARY KEY," +
            "image_name VARCHAR(150) NOT NULL," +
            "expression_files VARCHAR(300) NOT NULL," +
            "UNIQUE KEY uk_image (image_name)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4")
    void createTableIfAbsent();

    @Select("SELECT COUNT(*) FROM live2d_expression_map")
    int countAll();

    @Select("SELECT id, image_name, expression_files FROM live2d_expression_map ORDER BY id")
    List<Live2dExpressionMap> findAll();

    @Insert("INSERT INTO live2d_expression_map (image_name, expression_files) VALUES (#{image_name}, #{expression_files})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Live2dExpressionMap m);

    @Update("UPDATE live2d_expression_map SET image_name=#{image_name}, expression_files=#{expression_files} WHERE id=#{id}")
    int update(Live2dExpressionMap m);

    @Delete("DELETE FROM live2d_expression_map WHERE id = #{id}")
    int delete(@Param("id") Integer id);
}
