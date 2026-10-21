package com.cy.qq.game;

import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 你画我猜的绘图页接口（**免登录，靠 token 鉴权**）。
 *
 * <p>为什么要免登录：画的人是群里的普通群友，不可能给他开后台账号。
 * token 是开局时生成的一串随机值，只对"这一局 + 这个绘制者"有效，随局结束失效；
 * 接口只暴露本局信息，拿别人的 token 也换不到别的题。
 *
 * <p>公网暴露面：只有 {@code /qq_pic/**} 是放行的（见 {@code PublicAccessFilter}），
 * 所以这个前缀下的每个接口都必须是"只靠 token 就能安全服务"的。
 */
@RestController
@RequestMapping("/qq_pic")
public class QqPicWebController {

    @Autowired
    private QqPicService service;

    @Autowired
    private QqPicCommands commands;

    /**
     * 画板页面本体。
     *
     * <p>为什么不用静态目录的"欢迎页"：Spring Boot 只会把 classpath 根下的 index.html 当欢迎页，
     * {@code static/qq_pic/index.html} 对 {@code /qq_pic/} 不会自动生效（Spring 6 起尾斜杠也不再自动匹配），
     * 所以这里显式把文件吐出来，`/qq_pic` 和 `/qq_pic/` 都认。
     */
    @GetMapping({"", "/"})
    public void page(jakarta.servlet.http.HttpServletResponse res) throws java.io.IOException {
        res.setContentType("text/html;charset=UTF-8");
        res.setHeader("Cache-Control", "no-store");
        org.springframework.core.io.ClassPathResource html =
                new org.springframework.core.io.ClassPathResource("static/qq_pic/index.html");
        try (java.io.InputStream in = html.getInputStream()) {
            in.transferTo(res.getOutputStream());
        }
    }
    /** 页面轮询：题目、难度、轮次、已猜清单、是否已结束 */
    @GetMapping("/api/state")
    public Result state(@RequestParam String t) {
        QqPicSession s = service.byToken(t);
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("ended", true);
            m.put("reason", "这一局找不到了（链接过期或已被结束），回群里发 /画 3 重新拿链接");
            return Result.success(m);
        }
        return Result.success(service.stateOf(s));
    }

    /**
     * 提交一张画：让机器人看图猜。
     *
     * @param req {@code {t: token, image: "data:image/png;base64,..."}}
     */
    @PostMapping("/api/submit")
    public Result submit(@RequestBody Map<String, Object> req) {
        String t = req.get("t") == null ? "" : String.valueOf(req.get("t"));
        String image = req.get("image") == null ? "" : String.valueOf(req.get("image"));
        QqPicSession s = service.byToken(t);
        if (s == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("ended", true);
            m.put("reason", "这一局找不到了，回群里发 /画 3 重新拿链接");
            return Result.success(m);
        }
        if (s.finished()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("ended", true);
            m.put("answer", s.word());
            m.put("reason", "这一局已经结束了");
            return Result.success(m);
        }
        QqPicService.Out out = service.submit(s, image.getBytes(StandardCharsets.ISO_8859_1));
        // 回执直接发到群里/私聊（图 + @ + 结论，然后反应文本）
        try {
            commands.sendOut(out, s.chatType(), s.chatId());
        } catch (Exception e) {
            System.out.println("[你画我猜] 提交回执发送失败: " + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", out.ok);
        m.put("ended", out.ended || s.finished());
        if (!out.ok) m.put("reason", out.lines.isEmpty() ? "提交失败" : out.lines.get(0));
        if (out.ended || s.finished()) m.put("answer", s.word());
        return Result.success(m);
    }

    /** 画的人主动放弃：揭晓答案，群里同步 */
    @PostMapping("/api/giveup")
    public Result giveup(@RequestBody Map<String, Object> req) {
        String t = req.get("t") == null ? "" : String.valueOf(req.get("t"));
        QqPicSession s = service.byToken(t);
        if (s == null) return Result.error("这一局找不到了");
        QqPicService.Out out = service.endByUser(s.ownerQq());
        try {
            commands.sendOut(out, s.chatType(), s.chatId());
        } catch (Exception ignored) {
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("ended", true);
        m.put("answer", s.word());
        return Result.success(m);
    }
}
