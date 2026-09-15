package com.cy.pojo;

import lombok.Data;

@Data
public class AiConfig {
    private Integer id;
    private String api_key;
    private String api_url;
    private String tts_url;
    private String model;
    private String refer_wav;
    private String prompt_text;
    private Double tts_speed;
    private Integer sample_steps;
    private Integer max_tokens;
    private Double temperature;
    private String gpt_model_path;
    private String sovits_model_path;
    private Integer live_mode;
    private String bili_cookie;
    private Integer room_id;
    private Integer danmaku_enabled; // 弹幕抓取开关（1 开启 / 0 关闭）
    private String netease_uid; // 网易云 UID（用于获取收藏歌单）
    private Integer mahjong_enabled; // 麻将插件开关（1 开启 / 0 关闭，插件化模块）
    private Integer web_search_enabled; // 联网查询模块开关（1 开启 / 0 关闭；由桌面版 🔍 按钮一次性触发）
    private String web_search_api_key;  // 博查 Bocha Web Search API Key
    private Integer web_search_count;   // 每次注入的搜索结果条数（默认 5）
}
