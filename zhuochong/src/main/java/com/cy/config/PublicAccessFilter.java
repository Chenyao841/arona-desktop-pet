package com.cy.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公网入口闸门：<b>服务器本机以外的请求，只允许访问明确列出来的前缀</b>。
 *
 * <p>为什么需要它：8080 为了「群友打开你画我猜绘图页」对公网放开了，
 * 但桌宠那一整套 HTTP 接口历史上是**无鉴权**的（`WebConfig` 的白名单为了让桌宠页面直接跑，
 * 排除了 `/talk`、`/ai/pictionary/**`、`/basicinfo`、各种 `.html` 等）。
 * 一旦 8080 对 `0.0.0.0/0` 开放，这些就变成"任何人 POST /talk 就能刷你的模型余额"。
 *
 * <p>所以分成两个世界：
 * <ul>
 *   <li><b>本机（127.0.0.1 / ::1）</b>：不受限，桌宠、管理页、运维 curl 照旧</li>
 *   <li><b>公网</b>：只有 {@code qq.public-paths} 里的前缀能过（默认 {@code /qq_pic}、{@code /qq/status}），
 *       其余一律 401</li>
 * </ul>
 *
 * <p>小游戏的页面、静态资源、接口全部放在 {@code /qq_pic/**} 一个前缀下，
 * 这样白名单就一条，不用再单独对外开放 `/JS/**`、`/CSS/**` 之类。
 *
 * <p>开关：{@code qq.public-open=true} 可整体关掉本过滤器（不推荐，只在排障时用）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicAccessFilter extends OncePerRequestFilter {

    @Value("${qq.public-open:false}")
    private boolean publicOpen;

    /** 公网可访问的前缀（逗号分隔）。默认只放小游戏页面 + 健康检查 */
    @Value("${qq.public-paths:/qq_pic,/qq/status}")
    private String publicPaths;

    /** 401 日志节流：每 60 秒最多打一行，免得被人扫端口刷日志 */
    private final AtomicLong lastLogAt = new AtomicLong();
    private final AtomicLong blockedCount = new AtomicLong();

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        if (publicOpen) {
            chain.doFilter(req, res);
            return;
        }
        // 预检请求放过去：它不带业务数据，卡它会只让前端出现莫名其妙的 CORS 报错
        if ("OPTIONS".equalsIgnoreCase(req.getMethod())) {
            chain.doFilter(req, res);
            return;
        }
        if (isLoopback(req.getRemoteAddr()) || isPublicPath(pathOf(req))) {
            chain.doFilter(req, res);
            return;
        }

        long n = blockedCount.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastLogAt.get();
        if (now - last > 60_000L && lastLogAt.compareAndSet(last, now)) {
            System.out.println("[安全] 已拦截公网访问非公开接口（累计 " + n + " 次），本次: "
                    + req.getMethod() + " " + req.getRequestURI() + " from " + req.getRemoteAddr());
        }

        res.setStatus(401);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"code\":0,\"message\":\"该接口仅允许在本机访问\"}");
    }

    /** 去掉 contextPath 后的请求路径 */
    private static String pathOf(HttpServletRequest req) {
        String uri = req.getRequestURI();
        String ctx = req.getContextPath();
        if (ctx != null && !ctx.isEmpty() && uri.startsWith(ctx)) uri = uri.substring(ctx.length());
        return uri.isEmpty() ? "/" : uri;
    }

    private boolean isPublicPath(String path) {
        for (String raw : publicPaths.split("[,，\\s]+")) {
            String p = raw.trim();
            if (p.isEmpty()) continue;
            if (path.equals(p) || path.startsWith(p.endsWith("/") ? p : p + "/")) return true;
            // 允许写成 /qq/pic/** 这种 Ant 风格
            if (p.endsWith("/**") && path.startsWith(p.substring(0, p.length() - 3))) return true;
        }
        return false;
    }

    /** 是否来自服务器本机 */
    public static boolean isLoopback(String addr) {
        if (addr == null) return false;
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)
                || "localhost".equalsIgnoreCase(addr);
    }

}
