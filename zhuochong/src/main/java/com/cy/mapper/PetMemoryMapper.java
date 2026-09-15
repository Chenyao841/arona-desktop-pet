package com.cy.mapper;

import com.cy.pojo.PetMemory;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface PetMemoryMapper {
    @Select("SELECT * FROM pet_memory WHERE character_name = #{name} ORDER BY id")
    List<PetMemory> findByCharacter(String name);

    /** 全部角色的记忆（管理页「桌宠记忆库」用） */
    @Select("SELECT * FROM pet_memory ORDER BY id")
    List<PetMemory> findAll();

    @Insert("INSERT INTO pet_memory (character_name, content) VALUES (#{character_name}, #{content})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PetMemory m);

    @Update("UPDATE pet_memory SET content = #{content} WHERE id = #{id}")
    int update(PetMemory m);

    @Delete("DELETE FROM pet_memory WHERE id = #{id}")
    int delete(Integer id);

    @Delete("DELETE FROM pet_memory WHERE character_name = #{name}")
    int deleteAll(String name);

    /** 清空所有角色的记忆 */
    @Delete("DELETE FROM pet_memory")
    int deleteEverything();
}
