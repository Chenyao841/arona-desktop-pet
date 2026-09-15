package com.cy.conroller;

import com.cy.pojo.AiConfig;
import com.cy.pojo.Result;
import com.cy.mapper.AiConfigMapper;
import com.cy.service.SearchService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 联网查询（博查）测试/状态接口 —— 设置窗口「联网查询」页用。
 * 正式链路：桌面版 🔍 按钮 → /talk(user_operation=web_search) → DeepSeekService 调 SearchService 注入提示词。
 */
@RestController
@RequestMapping("/ai/web-search")
public class WebSearchController {

    @Autowired
    private SearchService searchService;

    @Autowired
    private AiConfigMapper aiConfigMapper;

    /** 状态：模块是否开启、是否已配置 Key、条数 */
    @GetMapping("/status")
    public Result status() {
        AiConfig cfg = first();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", cfg != null && cfg.getWeb_search_enabled() != null && cfg.getWeb_search_enabled() == 1);
        m.put("hasKey", cfg != null && cfg.getWeb_search_api_key() != null && !cfg.getWeb_search_api_key().trim().isEmpty());
        m.put("count", cfg == null || cfg.getWeb_search_count() == null ? 5 : cfg.getWeb_search_count());
        m.put("usable", searchService.enabled());
        return Result.success(m);
    }

    /** 直接试搜一条（返回解析后的结果 + 截断的原始响应，方便对不上结构时排查） */
    @GetMapping("/test")
    public Result test(@RequestParam String q,
                       @RequestParam(defaultValue = "0") int count) {
        if (q == null || q.trim().isEmpty()) return Result.error("查询内容为空");
        AiConfig cfg = first();
        int n = count > 0 ? count : (cfg == null || cfg.getWeb_search_count() == null ? 5 : cfg.getWeb_search_count());
        SearchService.SearchResult r = searchService.search(q, n);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", r.ok);
        m.put("error", r.error);
        List<Map<String, Object>> items = new ArrayList<>();
        for (SearchService.Item it : r.items) items.add(it.toMap());
        m.put("items", items);
        m.put("raw", r.raw);
        return Result.success(m);
    }

    private AiConfig first() {
        List<AiConfig> list = aiConfigMapper.findAll();
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }
}
