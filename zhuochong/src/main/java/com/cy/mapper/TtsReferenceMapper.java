package com.cy.mapper;

import com.cy.pojo.TtsReference;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface TtsReferenceMapper {
    @Select("SELECT id, wav_path, prompt_text, enabled FROM tts_reference WHERE enabled = 1 ORDER BY id")
    List<TtsReference> findEnabled();

    @Select("SELECT id, wav_path, prompt_text, enabled FROM tts_reference ORDER BY id")
    List<TtsReference> findAll();

    @Insert("INSERT INTO tts_reference (wav_path, prompt_text, enabled) VALUES (#{wav_path}, #{prompt_text}, 1)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(TtsReference r);

    @Update("UPDATE tts_reference SET enabled=#{enabled} WHERE id=#{id}")
    int updateEnabled(@Param("id") Integer id, @Param("enabled") Integer enabled);

    @Delete("DELETE FROM tts_reference WHERE id=#{id}")
    int deleteById(Integer id);
}
