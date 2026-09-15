package com.cy.conroller;

import com.cy.pojo.PictionaryWord;
import com.cy.pojo.Result;
import com.cy.service.PictionaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 你画我猜（老师画 · 阿罗娜猜）
 * ★ 答案只在 service 里，接口返回给**画板窗口**（给画的人看）和**桌宠页**（结束时交给桌宠做反应），
 *   从不进入给大模型的对话提示词 → 不会作弊；判对由后端自动完成，无需老师点选项、无需输入答案。
 */
@RestController
@RequestMapping("/ai/pictionary")
public class PictionaryController {

    @Autowired
    private PictionaryService service;

    /** 开局（本地词库出题）：difficulty 0=不限 / 1 简单 / 2 中等 / 3 困难 */
    @PostMapping("/start")
    public Result start(@RequestBody(required = false) Map<String, Object> req) {
        int d = 0;
        try {
            if (req != null && req.get("difficulty") != null) d = Integer.parseInt(String.valueOf(req.get("difficulty")));
        } catch (Exception e) { d = 0; }
        Map<String, Object> r = service.start(d);
        if (Boolean.FALSE.equals(r.get("ok"))) return Result.error(String.valueOf(r.get("say")));
        return Result.success(r);
    }

    /** 开场白：单独取（要调一次文本模型，1~2 秒）—— 开局接口不生成它，好让画板窗口立刻弹出来 */
    @GetMapping("/intro")
    public Result intro(@RequestParam("game_id") int gameId) {
        return Result.success(service.introSay(gameId));
    }

    /** 画板窗口打开时拉当前局（关掉再打开能接着画） */
    @GetMapping("/current")
    public Result current() {
        return Result.success(service.current());
    }

    /** 提交一张画让阿罗娜猜（image = data:image/png;base64,...） */
    @PostMapping("/guess")
    public Result guess(@RequestBody Map<String, Object> req) {
        int gameId = intOf(req.get("game_id"), 0);
        String image = req.get("image") == null ? "" : String.valueOf(req.get("image"));
        if (gameId <= 0) return Result.error("缺少 game_id");
        return Result.success(service.guess(gameId, image));
    }

    /** 老师点「公布答案」 */
    @PostMapping("/giveup")
    public Result giveUp(@RequestBody Map<String, Object> req) {
        return Result.success(service.giveUp(intOf(req.get("game_id"), 0)));
    }

    /** 画板关闭 → 本局作废 */
    @PostMapping("/quit")
    public Result quit(@RequestBody Map<String, Object> req) {
        return Result.success(service.quit(intOf(req.get("game_id"), 0)));
    }

    // ===== 管理页：词库 + 战绩 =====
    @GetMapping("/words")
    public Result words() { return Result.success(service.words()); }

    @PostMapping("/word/save")
    public Result saveWord(@RequestBody PictionaryWord w) {
        if (w.getWord() == null || w.getWord().trim().isEmpty()) return Result.error("词不能为空");
        if (w.getEnabled() == null) w.setEnabled(1);
        if (w.getDifficulty() == null) w.setDifficulty(1);
        if (w.getAliases() == null) w.setAliases("");
        if (w.getCategory() == null) w.setCategory("");
        service.saveWord(w);
        return Result.success("已保存");
    }

    @PostMapping("/word/delete")
    public Result deleteWord(@RequestBody Map<String, Object> req) {
        service.deleteWord(intOf(req.get("id"), 0));
        return Result.success("已删除");
    }

    @GetMapping("/stats")
    public Result stats() { return Result.success(service.stats()); }

    private int intOf(Object o, int def) {
        try { return o == null ? def : Integer.parseInt(String.valueOf(o)); } catch (Exception e) { return def; }
    }

    /** 供桌宠页拼开场白用：★ 只给字数（老师定的提示强度，类别不给桌宠、只给画板窗口） */
    @GetMapping("/hint")
    public Result hint(@RequestParam int game_id) {
        Map<String, Object> r = service.current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wordLen", r.get("wordLen"));
        m.put("difficulty", r.get("difficulty"));
        m.put("gameId", r.get("gameId"));
        return Result.success(m);
    }
}
