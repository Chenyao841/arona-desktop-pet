package com.cy.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Umi-OCR HTTP 服务客户端（127.0.0.1:9881/api/ocr）
 * 返回 [{text,x,y,w,h,score}, ...]，坐标为「发送图片」的像素空间（由调用方按比例映射回真实屏幕）
 */
@Service
public class OcrService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OcrService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(60)); // OCR 识别可能较慢
        this.restTemplate = new RestTemplate(factory);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> ocr(String imageBase64) {
        try {
            Map<String, Object> req = new HashMap<>();
            req.put("base64", imageBase64);
            // 明确参数：data.format=dict（返回含坐标的原始字典）；maxSideLen=1024（与 Java 侧截图缩放对齐）
            Map<String, Object> options = new HashMap<>();
            options.put("data.format", "dict");
            options.put("ocr.maxSideLen", 1024);
            req.put("options", options);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(req, headers);
            // 用 String 接收：Umi-OCR 返回 JSON 内容但 Content-Type 是 text/html，直接按 Map 解析会失败
            ResponseEntity<String> resp = restTemplate.postForEntity("http://127.0.0.1:9881/api/ocr", entity, String.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Map<String, Object> body = objectMapper.readValue(resp.getBody(), Map.class);
                Object code = body.get("code");
                if (code instanceof Number && ((Number) code).intValue() == 100) {
                    List<Map<String, Object>> data = (List<Map<String, Object>>) body.get("data");
                    List<Map<String, Object>> result = new ArrayList<>();
                    if (data != null) {
                        for (Map<String, Object> item : data) {
                            List<List<Number>> box = (List<List<Number>>) item.get("box");
                            if (box == null || box.size() < 4) continue;
                            double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
                            double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
                            for (List<Number> p : box) {
                                if (p == null || p.size() < 2) continue;
                                double px = p.get(0).doubleValue();
                                double py = p.get(1).doubleValue();
                                minX = Math.min(minX, px);
                                minY = Math.min(minY, py);
                                maxX = Math.max(maxX, px);
                                maxY = Math.max(maxY, py);
                            }
                            Map<String, Object> out = new HashMap<>();
                            out.put("text", item.get("text") != null ? item.get("text").toString() : "");
                            out.put("x", (int) Math.round(minX));
                            out.put("y", (int) Math.round(minY));
                            out.put("w", (int) Math.round(maxX - minX));
                            out.put("h", (int) Math.round(maxY - minY));
                            out.put("score", item.get("score") != null ? item.get("score") : 0);
                            result.add(out);
                        }
                    }
                    return result;
                } else {
                    System.out.println("[OCR] Umi-OCR 返回 code=" + code + " data=" + body.get("data"));
                }
            }
        } catch (Exception e) {
            System.out.println("[OCR] 请求失败: " + e.getMessage());
        }
        return null;
    }
}
