package com.cy.service;

import com.cy.mapper.AiConfigMapper;
import com.cy.pojo.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 联网查询（博查 Bocha Web Search）。
 * 触发方式：桌面版 🔍 按钮 → 下一条消息带 user_operation=web_search → 由 DeepSeekService 调用本服务检索并注入提示词。
 * 默认关闭（ai_config.web_search_enabled），因为会产生额外费用与延迟。
 */
@Service
public class SearchService {

    /** 博查 Web Search 接口（POST，Bearer 鉴权） */
    private static final String ENDPOINT = "https://api.bochaai.com/v1/web-search";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter BJ = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private AiConfigMapper aiConfigMapper;

    private final RestTemplate restTemplate;

    public SearchService() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(Duration.ofSeconds(6));
        f.setReadTimeout(Duration.ofSeconds(20));
        this.restTemplate = new RestTemplate(f);
    }

    /** 一条检索结果 */
    public static class Item {
        public String title = "";
        public String url = "";
        public String snippet = "";
        public String site = "";
        public String date = "";
        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("title", title); m.put("url", url); m.put("snippet", snippet);
            m.put("site", site); m.put("date", date);
            return m;
        }
    }

    /** 检索结果 + 原始响应（调试用） */
    public static class SearchResult {
        public boolean ok = false;
        public String error = "";
        public String raw = "";
        public List<Item> items = new ArrayList<>();
    }

    private AiConfig cfg() {
        List<AiConfig> list = aiConfigMapper.findAll();
        return (list == null || list.isEmpty()) ? null : list.get(0);
    }

    public boolean enabled() {
        AiConfig c = cfg();
        return c != null && c.getWeb_search_enabled() != null && c.getWeb_search_enabled() == 1
                && c.getWeb_search_api_key() != null && !c.getWeb_search_api_key().trim().isEmpty();
    }

    /** 执行检索；失败时 result.ok=false 且 error 带原因（不抛异常） */
    public SearchResult search(String query, int count) {
        SearchResult out = new SearchResult();
        AiConfig c = cfg();
        if (c == null) { out.error = "未找到 AI 配置"; return out; }
        String key = c.getWeb_search_api_key() == null ? "" : c.getWeb_search_api_key().trim();
        if (key.isEmpty()) { out.error = "未配置博查 API Key"; return out; }
        if (query == null || query.trim().isEmpty()) { out.error = "查询内容为空"; return out; }

        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query.trim());
            body.put("freshness", "noLimit");
            body.put("summary", true);
            body.put("count", Math.max(1, Math.min(20, count)));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + key);
            headers.set("Accept", "application/json");
            HttpEntity<String> entity = new HttpEntity<>(JSON.writeValueAsString(body), headers);

            ResponseEntity<String> resp = restTemplate.exchange(ENDPOINT, HttpMethod.POST, entity, String.class);
            String raw = resp.getBody() == null ? "" : resp.getBody();
            out.raw = raw.length() > 4000 ? raw.substring(0, 4000) + "…（已截断）" : raw;
            if (!resp.getStatusCode().is2xxSuccessful()) {
                out.error = "博查返回状态 " + resp.getStatusCode().value();
                return out;
            }
            JsonNode root = JSON.readTree(raw);
            JsonNode codeNode = root.get("code");
            if (codeNode != null && codeNode.asInt() != 200) {
                out.error = "博查错误：code=" + codeNode.asInt() + " msg=" + text(root.get("msg"));
                return out;
            }
            out.items = parseItems(root);
            out.ok = !out.items.isEmpty();
            if (!out.ok) out.error = "博查返回为空（可看 raw 字段确认响应结构）";
        } catch (Exception e) {
            out.error = "检索请求失败：" + e.getClass().getSimpleName() + " " + e.getMessage();
            System.out.println("[联网查询] " + out.error);
        }
        return out;
    }

    /** 兼容解析：优先 data.webPages.value[](name/summary/snippet/url/dateLastCrawled)，找不到就递归找第一个对象数组 */
    private List<Item> parseItems(JsonNode root) {
        List<Item> out = new ArrayList<>();
        JsonNode value = root.path("data").path("webPages").path("value");
        if (!value.isArray()) value = findFirstArray(root.path("data"));
        if (value == null || !value.isArray()) return out;
        for (JsonNode n : value) {
            Item it = new Item();
            it.title = firstNonEmpty(text(n.get("name")), text(n.get("title")));
            it.url = firstNonEmpty(text(n.get("url")), text(n.get("displayUrl")));
            it.snippet = firstNonEmpty(text(n.get("summary")), text(n.get("snippet")), text(n.get("description")));
            it.site = firstNonEmpty(text(n.get("siteName")), text(n.get("site")));
            it.date = firstNonEmpty(text(n.get("dateLastCrawled")), text(n.get("datePublished")), text(n.get("date")));
            if (it.date.length() > 10) it.date = it.date.substring(0, 10);   // 只留日期部分
            if (!it.snippet.isEmpty() || !it.title.isEmpty()) out.add(it);
        }
        return out;
    }

    private JsonNode findFirstArray(JsonNode node) {
        if (node == null || node.isMissingNode()) return null;
        if (node.isArray() && node.size() > 0 && node.get(0).isObject()) return node;
        Iterator<JsonNode> it = node.elements();
        while (it.hasNext()) {
            JsonNode r = findFirstArray(it.next());
            if (r != null) return r;
        }
        return null;
    }

    private static String text(JsonNode n) { return n == null || n.isNull() ? "" : n.asText(""); }
    private static String firstNonEmpty(String... vals) {
        for (String v : vals) if (v != null && !v.trim().isEmpty()) return v.trim();
        return "";
    }

    /**
     * 生成注入 system prompt 的联网资料块（含使用约束）。
     * 未开启/未配置 Key/检索失败时返回对应的说明块，让桌宠能自然地告诉老师。
     */
    public String buildPromptBlock(String query) {
        AiConfig c = cfg();
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of("Asia/Shanghai"));
        if (c == null || c.getWeb_search_enabled() == null || c.getWeb_search_enabled() != 1) {
            return "\n【联网查询】老师按了 🔍，但联网查询模块当前是关闭的。请告诉老师去「桌宠设置 → 联网查询」里打开开关并填 API Key，这次只能凭你已有的知识回答。\n";
        }
        int count = (c.getWeb_search_count() == null || c.getWeb_search_count() <= 0) ? 5 : c.getWeb_search_count();
        SearchResult r = search(query, count);
        StringBuilder sb = new StringBuilder();
        if (!r.ok) {
            sb.append("\n【联网查询】这次检索没有成功（").append(r.error).append("）。");
            sb.append("请如实告诉老师「我这边没查到」，不要编造具体日期或事实。\n");
            return sb.toString();
        }
        sb.append("\n【联网资料（博查搜索 · 检索于 ").append(now.format(BJ)).append(" 北京时间）】\n");
        int i = 1;
        for (Item it : r.items) {
            sb.append(i++).append(". ");
            if (!it.title.isEmpty()) sb.append("《").append(it.title).append("》 ");
            if (!it.snippet.isEmpty()) sb.append(it.snippet).append(" ");
            if (!it.site.isEmpty()) sb.append("（来源：").append(it.site);
            if (!it.date.isEmpty()) sb.append(" · ").append(it.date);
            sb.append("）\n");
        }
        sb.append("以上是刚检索到的网页资料。请遵守：① 只依据这些资料回答，不要编造资料里没有的信息；")
          .append("② 资料里没有就直说「我这边没查到」；③ 涉及日期要明确说出是哪一天，并说明信息可能有时效性；")
          .append("④ 回答仍要用阿罗娜自己的口吻和语气，不要念稿子、不要罗列网址。\n");
        return sb.toString();
    }
}
