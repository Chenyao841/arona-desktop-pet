package com.cy;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class


    
ZhuochongApplication {

    public static void main(String[] args) {
        // 桌面视觉需要 java [zhuochon.awt.Robot 截屏；Spring Boot 默认 headless=true 会致截屏失败，必须显式关掉（在 run 前设置最可靠）
        System.setProperty("java.awt.headless", "false");
        SpringApplication.run(ZhuochongApplication.class, args);
    }

}

