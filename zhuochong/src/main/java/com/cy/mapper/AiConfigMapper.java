package com.cy.mapper;

import com.cy.pojo.AiConfig;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface AiConfigMapper {
    @Select("SELECT * FROM ai_config ORDER BY id")
    List<AiConfig> findAll();
    @Select("SELECT * FROM ai_config WHERE id = #{id}")
    AiConfig findById(Integer id);
    @Insert("INSERT INTO ai_config (api_key, api_url, tts_url, model, refer_wav, prompt_text, tts_speed, sample_steps, max_tokens, temperature, gpt_model_path, sovits_model_path, live_mode, bili_cookie, room_id, danmaku_enabled, netease_uid, mahjong_enabled, web_search_enabled, web_search_api_key, web_search_count) VALUES (#{api_key}, #{api_url}, #{tts_url}, #{model}, #{refer_wav}, #{prompt_text}, #{tts_speed}, #{sample_steps}, #{max_tokens}, #{temperature}, #{gpt_model_path}, #{sovits_model_path}, #{live_mode}, #{bili_cookie}, #{room_id}, #{danmaku_enabled}, #{netease_uid}, #{mahjong_enabled}, #{web_search_enabled}, #{web_search_api_key}, #{web_search_count})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AiConfig c);
    @Update("UPDATE ai_config SET api_key=#{api_key}, api_url=#{api_url}, tts_url=#{tts_url}, model=#{model}, refer_wav=#{refer_wav}, prompt_text=#{prompt_text}, tts_speed=#{tts_speed}, sample_steps=#{sample_steps}, max_tokens=#{max_tokens}, temperature=#{temperature}, gpt_model_path=#{gpt_model_path}, sovits_model_path=#{sovits_model_path}, live_mode=#{live_mode}, bili_cookie=#{bili_cookie}, room_id=#{room_id}, danmaku_enabled=#{danmaku_enabled}, netease_uid=#{netease_uid}, mahjong_enabled=#{mahjong_enabled}, web_search_enabled=#{web_search_enabled}, web_search_api_key=#{web_search_api_key}, web_search_count=#{web_search_count} WHERE id=#{id}")
    int update(AiConfig c);
    @Delete("DELETE FROM ai_config WHERE id = #{id}")
    int deleteById(Integer id);
}
