package com.cy.component;

import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.File;

@Component
public class NeteaseStarter {

    @Value("${netease.auto-start:false}")
    private boolean autoStart;

    @Value("${netease.node:node}")
    private String nodePath;

    @Value("${netease.app:app.js}")
    private String appPath;

    private Process process;

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!autoStart) return;
        try {
            File appFile = new File(appPath);
            ProcessBuilder pb = new ProcessBuilder(nodePath, appFile.getName());
            pb.directory(appFile.getParentFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
            process = pb.start();
            System.out.println("[网易云API] 已启动, PID: " + process.pid());
        } catch (Exception e) {
            System.out.println("[网易云API] 启动失败: " + e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        if (process != null && process.isAlive()) {
            process.destroy();
            try { process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) {}
            if (process.isAlive()) process.destroyForcibly();
            System.out.println("[网易云API] 已停止");
        }
    }
}
