package com.cy.mahjong;

import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 麻将插件控制器（独立模块）：/ai/mahjong/*
 * 插件关闭时所有接口返回"未启用"，不影响原有功能。
 */
@RestController
@RequestMapping("/ai/mahjong")
public class MahjongController {

    @Autowired
    private MahjongPluginService mahjongService;

    @Autowired
    private MahjongPlayService playService;

    /** 插件状态 */
    @GetMapping("/status")
    public Result status() {
        return Result.success(Map.of("enabled", mahjongService.isEnabled()));
    }

    /** 实时分析：截屏 → 读牌 → 牌效建议（手动指令触发） */
    @PostMapping("/analyze")
    public Result analyze() {
        Map<String, Object> r = mahjongService.analyze(false, null);
        return wrap(r);
    }

    /** 校准文件分析：读 static/麻将参考截屏/{name}（测试/校准用，不截屏） */
    @PostMapping("/analyze-file")
    public Result analyzeFile(@RequestParam(defaultValue = "自己进行游戏.png") String name) {
        Map<String, Object> r = mahjongService.analyze(true, name);
        return wrap(r);
    }

    /** 开关（独立于原有 ai_config 保存流程，只动 mahjong_enabled 一列） */
    @PostMapping("/toggle")
    public Result toggle(@RequestBody Map<String, Object> req) {
        boolean on = req.get("enabled") == null || Boolean.parseBoolean(String.valueOf(req.get("enabled")));
        String msg = mahjongService.setEnabled(on);
        if (msg != null) return Result.error(msg);
        return Result.ok(on ? "麻将插件已开启" : "麻将插件已关闭");
    }

    // ===== 实操（自动打牌）=====
    /** 开局：tiles 为空=截屏读牌；非空=手动注入（"9w 1b ... 发 中+6b"） */
    @PostMapping("/play/start")
    public Result playStart(@RequestParam(required = false) String tiles) {
        boolean manual = tiles != null && !tiles.trim().isEmpty();
        return Result.success(playService.start(manual, tiles));
    }

    @PostMapping("/play/stop")
    public Result playStop() {
        playService.stop();
        return Result.ok("已停止");
    }

    @GetMapping("/play/status")
    public Result playStatus() {
        return Result.success(playService.status());
    }

    /** 吃回报："吃 四条 五条 六条" 或 "四条 五条 吃 六条"（用户手动吃后更新牌库） */
    @PostMapping("/play/chi")
    public Result playChi(@RequestBody Map<String, Object> req) {
        return Result.success(playService.chiReport(String.valueOf(req.get("text"))));
    }

    /** 手动报摸牌（模板识别失败时兜底）："摸牌 六条" */
    @PostMapping("/play/report-draw")
    public Result playReportDraw(@RequestBody Map<String, Object> req) {
        return Result.success(playService.reportDraw(String.valueOf(req.get("tile"))));
    }

    /** 「误报 X」：人工指出最近一次摸牌读取有误、实际牌=X → 存入 麻将错题截屏 + 记录txt */
    @PostMapping("/play/misreport")
    public Result playMisreport(@RequestBody Map<String, Object> req) {
        return Result.success(playService.misreport(String.valueOf(req.get("tile"))));
    }

    /** 「继续对局」：局终判定有误 → 恢复轮询并将误判帧存入错题库 */
    @PostMapping("/play/continue-match")
    public Result playContinueMatch() {
        return Result.success(playService.continueMatch());
    }

    /** 「下一局」（快捷选项/语音）：局终/胡牌后确认继续，检测到新配牌自动恢复 */
    @PostMapping("/play/next-round")
    public Result playNextRound() {
        return Result.success(playService.nextRound());
    }

    /** 「先暂停」（快捷选项/语音）：停止自动打牌（数据保留） */
    @PostMapping("/play/pause")
    public Result playPause() {
        return Result.success(playService.pauseMatch());
    }

    /** 开局确认「无误」：以读取结果开局 */
    @PostMapping("/play/confirm-ok")
    public Result playConfirmOk() {
        return Result.success(playService.confirmOk());
    }

    /** 开局确认「有误 + 正确牌型」：入素材库/错题本并以正确牌型开局 */
    @PostMapping("/play/confirm-wrong")
    public Result playConfirmWrong(@RequestBody Map<String, Object> req) {
        return Result.success(playService.confirmWrong(String.valueOf(req.get("tiles"))));
    }

    /** 合作模式开关（吃碰杠等用户决策） */
    @PostMapping("/play/coop-toggle")
    public Result playCoopToggle() {
        return Result.success(playService.toggleCoop());
    }

    /** 合作指令：跳过 / 碰 X / 杠 X / 吃 ABC */
    @PostMapping("/play/coop-action")
    public Result playCoopAction(@RequestBody Map<String, Object> req) {
        return Result.success(playService.coopAction(String.valueOf(req.get("text"))));
    }

    /** 碰回报（手动碰后同步） */
    @PostMapping("/play/pon-report")
    public Result playPonReport(@RequestBody Map<String, Object> req) {
        return Result.success(playService.ponReport(String.valueOf(req.get("tile"))));
    }

    /** 杠回报（手动杠后同步） */
    @PostMapping("/play/kan-report")
    public Result playKanReport(@RequestBody Map<String, Object> req) {
        return Result.success(playService.kanReport(String.valueOf(req.get("tile"))));
    }

    /** 「出牌 X」：用户手动打出某张后告知桌宠，同步手牌并继续 */
    @PostMapping("/play/discard-manual")
    public Result playDiscardManual(@RequestBody Map<String, Object> req) {
        return Result.success(playService.discardManual(String.valueOf(req.get("tile"))));
    }

    // ===== 人工标注区域（区域标注工具.html）=====
    @GetMapping("/regions")
    public Result getRegions() {
        return Result.success(playService.getRegions());
    }

    @PostMapping("/regions/save")
    public Result saveRegion(@RequestBody Map<String, Object> req) {
        String name = String.valueOf(req.get("name"));
        int x1 = req.get("x1") == null ? 0 : Integer.parseInt(String.valueOf(req.get("x1")));
        int y1 = req.get("y1") == null ? 0 : Integer.parseInt(String.valueOf(req.get("y1")));
        int x2 = req.get("x2") == null ? 0 : Integer.parseInt(String.valueOf(req.get("x2")));
        int y2 = req.get("y2") == null ? 0 : Integer.parseInt(String.valueOf(req.get("y2")));
        int iw = req.get("imageWidth") == null ? 2560 : Integer.parseInt(String.valueOf(req.get("imageWidth")));
        int ih = req.get("imageHeight") == null ? 0 : Integer.parseInt(String.valueOf(req.get("imageHeight")));
        return Result.success(playService.saveRegion(name, x1, y1, x2, y2, iw, ih));
    }

    /** 标注页「导入图片」：把本机图片落盘到 static/标注图片/，返回可访问 URL */
    @PostMapping("/regions/upload-image")
    public Result uploadRegionImage(@RequestBody Map<String, Object> req) {
        return Result.success(playService.saveRegionImage(
                String.valueOf(req.get("name")), String.valueOf(req.get("dataUrl"))));
    }

    @PostMapping("/regions/delete")
    public Result deleteRegion(@RequestBody Map<String, Object> req) {
        return Result.success(playService.deleteRegion(String.valueOf(req.get("name"))));
    }

    @GetMapping("/calibration-files")
    public Result calibrationFiles() {
        return Result.success(playService.calibrationFiles());
    }

    private Result wrap(Map<String, Object> r) {
        Boolean ok = (Boolean) r.get("ok");
        if (ok != null && !ok) return Result.error(String.valueOf(r.getOrDefault("message", "失败")));
        return Result.success(r);
    }
}
