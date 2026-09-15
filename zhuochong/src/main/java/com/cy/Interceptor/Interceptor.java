package com.cy.Interceptor;

import com.cy.utils.CurrentHolder;
import com.cy.utils.JWTUTILL;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

public class Interceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) {
            token = token.substring(7);
        }

        if (token == null || !JWTUTILL.validateToken(token)) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(401);
            response.getWriter().write("{\"code\":0,\"message\":\"当前未登录，请前往登录页面登录\"}");
            return false;
        }

        request.setAttribute("username", JWTUTILL.getUsername(token));
        request.setAttribute("identity", JWTUTILL.getIdentity(token));
        CurrentHolder.setCurrentID(JWTUTILL.getUserId(token));

        String identity = JWTUTILL.getIdentity(token);
        String method = request.getMethod();
        if ("员工".equals(identity) && ("POST".equals(method) || "PUT".equals(method) || "DELETE".equals(method))) {
            response.setContentType("application/json;charset=UTF-8");
            response.setStatus(403);
            response.getWriter().write("{\"code\":0,\"message\":\"权限不足！您当前为员工身份，无法执行此操作\"}");
            return false;
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        CurrentHolder.clear();
    }
}
