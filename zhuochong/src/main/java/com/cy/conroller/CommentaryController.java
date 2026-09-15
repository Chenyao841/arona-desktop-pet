package com.cy.conroller;

import com.cy.pojo.CommentaryConfig;
import com.cy.pojo.CommentaryContext;
import com.cy.pojo.Result;
import com.cy.service.CommentaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 陪玩点评模式接口。
 * 正式链路：桌面版 🎬 按钮/指令进入 → 定时调用 POST /ai/commentary/once → 后端截屏 + 变化检测 +
 * 一步法视觉点评 → 返回 {text, motion, context} → 桌面端复用 showResult 播放（文字/表情/短句 TTS）。
 */
@RestController
@RequestMapping("/ai/commentary")
public class CommentaryController {

    @Autowired
    private CommentaryService commentaryService;

    /** 执行一次点评；body.context 为人工告知的语境 code（可空 → 自动判定兜底） */
    @PostMapping("/once")
    public Result once(@RequestBody(required = false) Map<String, String> body) {
        String ctx = body == null ? null : body.get("context");
        Map<String, Object> r = commentaryService.once(ctx);
        return Result.success(r);
    }

    /** 进入模式时调用：清空上一次的画面基准与上一句点评 */
    @PostMapping("/reset")
    public Result reset() {
        commentaryService.resetBaseline();
        return Result.ok("已重置");
    }

    /** 参数 + 语境列表 */
    @GetMapping("/config")
    public Result config() {
        java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("config", commentaryService.config());
        out.put("contexts", commentaryService.contexts());
        return Result.success(out);
    }

    @PutMapping("/config")
    public Result saveConfig(@RequestBody CommentaryConfig body) {
        CommentaryConfig cur = commentaryService.config();
        if (cur.getId() == null) return Result.error("配置不存在");
        body.setId(cur.getId());
        if (body.getEnabled() == null) body.setEnabled(cur.getEnabled());
        commentaryService.saveConfig(body);
        return Result.success(commentaryService.config());
    }

    @PutMapping("/context")
    public Result saveContext(@RequestBody CommentaryContext body) {
        if (body.getId() == null) return Result.error("缺少 id");
        commentaryService.saveContext(body);
        return Result.success(body);
    }
}
