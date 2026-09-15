package com.cy.component;

import com.cy.mapper.AiConfigMapper;
import com.cy.pojo.AiConfig;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.List;

@Component
public class TtsServerManager {

    @Value("${tts.auto-start:false}")
    private boolean autoStart;

    @Value("${tts.python:}")
    private String pythonPath;

    @Value("${tts.api-dir:}")
    private String apiDir;

    @Autowired
    private AiConfigMapper aiConfigMapper;

    private Process process;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoStart) return;
        try {
            List<AiConfig> configs = aiConfigMapper.findAll();
            if (configs.isEmpty()) {
                System.out.println("[TTS] 未找到AI配置，跳过自动启动");
                return;
            }
            AiConfig cfg = configs.get(0);
            String gpt = cfg.getGpt_model_path();
            String sovits = cfg.getSovits_model_path();

            ProcessBuilder pb = new ProcessBuilder(
                    pythonPath, "api.py",
                    "-a", "0.0.0.0",
                    "-p", "9880"
            );
            if (gpt != null && !gpt.isEmpty()) {
                pb.command().add("-g");
                pb.command().add(gpt);
            }
            if (sovits != null && !sovits.isEmpty()) {
                pb.command().add("-s");
                pb.command().add(sovits);
            }
            pb.directory(new File(apiDir));
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            System.out.println("[TTS] api.py 已启动, PID: " + process.pid());
        } catch (Exception e) {
            System.out.println("[TTS] 启动失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) {}
            if (process.isAlive()) process.destroyForcibly();
            System.out.println("[TTS] api.py 已停止");
        }
    }
}
