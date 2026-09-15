package com.cy.config;

import com.cy.Interceptor.Interceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.io.File;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 桌宠实机截屏日志目录（标注页加载用）
        String logDir = "file:" + new File(System.getProperty("user.dir"), "desktop_vision_log").getAbsolutePath().replace('\\', '/') + "/";
        registry.addResourceHandler("/desktop_vision_log/**").addResourceLocations(logDir);
        // 麻将错题库（误报裁图 + 记录txt）：写入 src/main/resources/static 下，文件系统直出便于人工核对
        String misDir = "file:" + new File(System.getProperty("user.dir"),
                "src/main/resources/static/麻将错题截屏").getAbsolutePath().replace('\\', '/') + "/";
        registry.addResourceHandler("/麻将错题截屏/**").addResourceLocations(misDir);
        // 区域标注工具「导入图片」的落盘目录：同样文件系统直出 —— 导入后立刻可访问，不用 rebuild
        String annoDir = "file:" + new File(System.getProperty("user.dir"),
                "src/main/resources/static/标注图片").getAbsolutePath().replace('\\', '/') + "/";
        registry.addResourceHandler("/标注图片/**").addResourceLocations(annoDir);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Interceptor())
                .addPathPatterns("/**")
                .excludePathPatterns(
                        "/login",
                        "/conversation/**",
                        "/reaction",
                        "/idle",
                        "/choice/**",
                        "/登录.html",
                        "/桌宠.html",
                        "/桌宠管理.html",
                        "/测试.html",
                        "/桌宠桌面版.html",
                        "/桌宠设置.html",
                        "/你画我猜.html",
                        "/音乐播放器.html",
                        "/桌宠live2d测试.html",
                        "/桌宠标注.html",
                        "/区域标注工具.html",
                        "/语音合成调试.html",
                        "/操作册.html",
                        "/麻将参考截屏/**",
                        "/desktop_vision_log/**",
                        "/麻将错题截屏/**",
                        "/标注图片/**",
                        "/live2d/**",
                        "/JS/**",
                        "/Shaders/**",
                        "/Framework/**",
                        "/CSS/**",
                        "/background/**",
                        "/bottom/**",
                        "/aluona/**",
                        "/soundeffects/**",
                        "/music/**",
                        "/motions/**",
                        "/effects/**",
                        "/talk",
                        "/basicinfo",
                        "/basic-responses",
                        "/seed-game-expressions",
                        "/ai/**",
                        "/error",
                        "/favicon.ico"
                );
    }
}
