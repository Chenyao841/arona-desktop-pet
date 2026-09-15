package com.cy.pojo;

import lombok.Data;

/** 陪玩点评模式参数（单行配置） */
@Data
public class CommentaryConfig {
    private Integer id;
    private Integer enabled;          // 1 开启（仅记录用户上次选择，实际以桌面端状态按钮为准）
    private Integer interval_sec;     // 两次点评最小间隔（秒）
    private Integer min_change_score; // 画面变化阈值（0~255 平均差），低于则跳过
    private Integer max_streak;       // 连续点评上限
    private Integer silence_sec;      // 达到上限后的静默时长
    private Integer after_talk_sec;   // 刚聊完后的静默
    private Integer voice_on;         // 1 出声
    private Integer volume_percent;   // 本状态音量（相对正常音量的百分比，如 45）
    private Integer skip_percent;     // 每次到点后随机跳过（延长间隔）的概率
    private Integer max_chars;        // 点评字数上限
    private Integer keep_minutes;     // 截图留存分钟数（0=不留）
    private Integer auto_detect;      // 1 允许视觉自动判定语境
    private Integer manual_ttl_min;   // 人工告知语境的保持时长（分钟）
    private Integer max_tokens;       // 点评的 token 预算（推理型视觉模型需留够思考空间，默认 1200）
}
