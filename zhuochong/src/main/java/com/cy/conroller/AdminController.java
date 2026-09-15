package com.cy.conroller;

import com.cy.pojo.Conservation;
import com.cy.pojo.Result;
import com.cy.service.ConversationService;
import com.cy.utils.JWTUTILL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录：本地默认账号（配置在 application.yml 的 login.*），不读数据库。
 * 原因：本项目为个人本地使用；原先按 adminlist 表逐行比对账号密码，既依赖数据
 * （分发时名单不入包 → 收包的人永远登不进），也没有必要。
 * 改为配置化默认账号后：收包的人用默认账号即可进管理页，想改就在 yml 里改。
 * 注意：identity 只要不是「员工」就拥有写权限（管理页 isAdmin() 与后端 Interceptor 都按此判断）。
 * 旧实现（AdminService / AdminMapper / adminlist 表）已不再参与登录，保留表结构兼容历史数据。
 */
@RestController
public class AdminController {

    @Value("${login.username:老师}")
    private String cfgUsername;

    @Value("${login.password:123456}")
    private String cfgPassword;

    @Value("${login.name:老师}")
    private String cfgName;

    @Value("${login.identity:管理员}")
    private String cfgIdentity;

    @Autowired
    private ConversationService conversationService;

    @PostMapping("/login")
    public Result login(@RequestParam String username,
                        @RequestParam String userkey) {
        if (username == null || username.trim().isEmpty()) {
            return Result.error("用户名不能为空！");
        }
        if (userkey == null || userkey.trim().isEmpty()) {
            return Result.error("密码不能为空！");
        }
        // 常量时间比较，避免通过响应时间推测密码
        boolean ok = safeEquals(username.trim(), cfgUsername) & safeEquals(userkey.trim(), cfgPassword);
        if (!ok) {
            return Result.error("用户名或密码有误！");
        }
        String identity = (cfgIdentity == null || cfgIdentity.trim().isEmpty()) ? "管理员" : cfgIdentity.trim();
        String name = (cfgName == null || cfgName.trim().isEmpty()) ? cfgUsername : cfgName.trim();
        String token = JWTUTILL.generateToken(1, cfgUsername, identity);
        Map<String, Object> loginData = new HashMap<>();
        loginData.put("token", token);
        loginData.put("identity", identity);
        loginData.put("name", name);
        loginData.put("id", 1);
        Conservation reaction = conversationService.findReaction("login", null);
        if (reaction != null) {
            return Result.reaction(reaction.getBoat_text(), reaction.getMotion(), loginData);
        }
        return Result.success(loginData);
    }

    private static boolean safeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
