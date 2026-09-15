package com.cy.gacha;

import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 蔚蓝档案自动抽卡控制器：/ai/gacha/*
 * 由桌宠页面本地指令「帮老师抽个卡」触发（同麻将实操模式）。
 */
@RestController
@RequestMapping("/ai/gacha")
public class GachaController {

    @Autowired
    private GachaService gachaService;

    /** 执行抽卡全流程（需游戏停在抽卡1 界面） */
    @PostMapping("/start")
    public Result start() {
        Map<String, Object> r = gachaService.start();
        return wrap(r);
    }

    @PostMapping("/stop")
    public Result stop() {
        return Result.success(gachaService.stop());
    }

    @GetMapping("/status")
    public Result status() {
        return Result.success(Map.of("running", gachaService.isRunning()));
    }

    private Result wrap(Map<String, Object> r) {
        Boolean ok = (Boolean) r.get("ok");
        if (ok != null && !ok) return Result.error(String.valueOf(r.getOrDefault("message", "失败")));
        return Result.success(r);
    }
}
