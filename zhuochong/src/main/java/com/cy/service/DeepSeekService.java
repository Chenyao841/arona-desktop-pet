package com.cy.service;

import com.cy.mapper.AiConfigMapper;
import com.cy.mapper.AiHistoryMapper;
import com.cy.mapper.AiPromptMapper;
import com.cy.mapper.BasicInfoMapper;
import com.cy.mapper.PetMemoryMapper;
import com.cy.mapper.TestHistoryMapper;
import com.cy.pojo.AiConfig;
import com.cy.pojo.AiPrompt;
import com.cy.pojo.BasicInfo;
import com.cy.pojo.PetMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DeepSeekService {

    @Autowired
    private AiConfigMapper aiConfigMapper;

    @Autowired
    private AiPromptMapper aiPromptMapper;

    @Autowired
    private BasicInfoMapper basicInfoMapper;

    @Autowired
    private TestHistoryMapper testHistoryMapper;

    @Autowired
    private AiHistoryMapper aiHistoryMapper;

    @Autowired
    private PetMemoryMapper petMemoryMapper;

    @Autowired
    private com.cy.mapper.StyleExemplarMapper styleExemplarMapper;

    @Autowired
    private com.cy.mapper.EventBookMapper eventBookMapper;

    @Autowired
    private com.cy.service.SearchService searchService;

    private final RestTemplate restTemplate;

    public DeepSeekService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(20));
        factory.setReadTimeout(Duration.ofSeconds(300)); // 300s：deepseek-reasoner 推理慢，120s 易超时导致兜底文案
        this.restTemplate = new RestTemplate(factory);
    }

    // 结构化返回
    public static class AiResult {
        public String boat_text;
        public String motion;
        public String sound_effect;
        public String special_effect;
        public String emotion; // 情绪标签（阶段三：AI 输出的粗情绪词）
        public List<String> situations; // Live2D 模式：逐句情境词
        public String dataJson; // JSON array string
        public Integer sleep_seconds; // 闲置休眠秒数（AI 可选返回）
    }

    private AiResult parseAiContent(String content) {
        AiResult r = new AiResult();
        if (content == null || content.trim().isEmpty()) {
            r.boat_text = "嗯哼哼————";
            r.motion = "aluona_kaixin.png";
            r.dataJson = "[]";
            return r;
        }
        ObjectMapper om = new ObjectMapper();
        // 1) 整体按 JSON 解析
        try {
            fillFromMap(r, om.readValue(content, Map.class), om);
            return r;
        } catch (Exception ignored) {}
        // 2) 提取 JSON 子串再解析（AI 偶发在 JSON 前后输出多余文本，如"纯文本\n\n{...}"）
        String jsonPart = extractJson(content);
        if (jsonPart != null) {
            try {
                fillFromMap(r, om.readValue(jsonPart, Map.class), om);
                return r;
            } catch (Exception ignored) {}
        }
        // 3) 都失败：整个 content 作为纯文本
        r.boat_text = content;
        r.motion = "aluona_kaixin.png";
        r.dataJson = "[]";
        return r;
    }

    // 从一段内容里提取最外层 JSON 对象（第一个 { 到最后一个 }）
    private String extractJson(String content) {
        int start = content.indexOf('{');
        int end = content.lastIndexOf('}');
        if (start >= 0 && end > start) return content.substring(start, end + 1);
        return null;
    }

    private void fillFromMap(AiResult r, Map<String, Object> map, ObjectMapper om) {
        // keyword 优先（分类响应），其次 boat_text（对话响应）
        r.boat_text = map.containsKey("keyword") ? map.get("keyword").toString()
                : (map.get("boat_text") != null ? map.get("boat_text").toString() : "嗯哼哼————");
        // 清理 AI 偶发的字面 \n
        r.boat_text = r.boat_text.replace("\\n", "\n").replace("\\\"", "\"");
        r.motion = map.get("motion") != null ? map.get("motion").toString() : "aluona_kaixin.png";
        r.sound_effect = map.get("sound_effect") != null ? map.get("sound_effect").toString() : null;
        r.special_effect = map.get("special_effect") != null ? map.get("special_effect").toString() : null;
        r.emotion = map.get("emotion") != null ? map.get("emotion").toString() : null;
        // Live2D 模式：逐句情境词数组
        r.situations = new ArrayList<>();
        Object sit = map.get("situations");
        if (sit instanceof List) {
            for (Object o : (List<?>) sit) {
                if (o != null && !o.toString().trim().isEmpty()) r.situations.add(o.toString().trim());
            }
        }
        r.dataJson = "[]";
        try {
            if (map.containsKey("data")) r.dataJson = om.writeValueAsString(map.get("data"));
        } catch (Exception ignored) {}
        if (map.get("sleep") != null) {
            try { r.sleep_seconds = Integer.parseInt(map.get("sleep").toString()); } catch (Exception ignored) {}
        }
    }

    public AiResult chat(String userMessage, String storeMessage, String sessionId, String userOperation, String modelOverride) {
        return chat(userMessage, storeMessage, sessionId, userOperation, modelOverride, false);
    }

    public AiResult chat(String userMessage, String storeMessage, String sessionId, String userOperation, String modelOverride, boolean live2d) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;

        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        // reserve history record id (will save after AI response)
        // storeMessage 非空时用它入库（游戏场景只存当前短内容，而非完整 prompt）
        final String fUserMsg = (storeMessage != null && !storeMessage.isEmpty()) ? storeMessage : userMessage;
        final String fSessionId = sessionId;
        final String fUserOp = userOperation;

        // 构建 system prompt
        StringBuilder sp = new StringBuilder();
        BasicInfo aiInfo = basicInfoMapper.findById(1);
        BasicInfo userInfo = basicInfoMapper.findById(2);
        if (aiInfo != null) {
            sp.append("你是").append(nn(aiInfo.get姓名(), "阿罗娜"));
            sp.append("，").append(nn(aiInfo.get身份(), "什亭之匣")).append("。\n");
        }
        if (userInfo != null) {
            sp.append("用户是").append(nn(userInfo.get姓名(), "老师"));
            sp.append("，").append(nn(userInfo.get身份(), "夏莱的老师")).append("。\n");
        }
        AiPrompt systemP = aiPromptMapper.findByType("system");
        AiPrompt funcP = aiPromptMapper.findByType("function");
        if (systemP != null && systemP.getContent() != null) sp.append(systemP.getContent()).append("\n");
        if (funcP != null && funcP.getContent() != null) sp.append(funcP.getContent()).append("\n");

        // 加载角色长期记忆
        try {
            List<PetMemory> memories = petMemoryMapper.findByCharacter(nn(aiInfo != null ? aiInfo.get姓名() : null, "阿罗娜"));
            if (memories != null && !memories.isEmpty()) {
                sp.append("\n【你对用户的长期记忆】\n");
                for (PetMemory m : memories) {
                    sp.append("- ").append(m.getContent()).append("\n");
                }
            }
        } catch (Exception ignored) {}

        // 事件簿：注入当前时间（北京时间，含星期与时段）+ 最近 5 条非对话交互事件，让回复贴合"刚才发生了什么"
        try {
            java.time.ZonedDateTime nowT = java.time.ZonedDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
            sp.append("\n【当前时间】")
              .append(nowT.format(java.time.format.DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)))
              .append("（北京时间）\n");
            int hour = nowT.getHour();
            String part = hour < 5 ? "深夜" : hour < 11 ? "早上" : hour < 13 ? "中午" : hour < 18 ? "下午" : hour < 23 ? "晚上" : "深夜";
            sp.append("现在是").append(part)
              .append("。请结合时间调整称呼、问候语与语气（例如深夜不要问「早上好」，可以提醒老师早点休息），但不要每句都提时间。\n");
            List<com.cy.pojo.EventBook> evs = eventBookMapper.findRecent(5);
            if (evs != null && !evs.isEmpty()) {
                sp.append("【最近发生的事（按时间从新到旧）】\n");
                java.time.format.DateTimeFormatter f = java.time.format.DateTimeFormatter.ofPattern("HH:mm");
                for (com.cy.pojo.EventBook ev : evs) {
                    String t = ev.getCreated_at() == null ? "" :
                            "(" + ev.getCreated_at().toLocalDateTime().format(f) + ")";
                    sp.append("- ").append(t).append(ev.getContent()).append("\n");
                }
                sp.append("以上是刚才实际发生的互动（摸头/点击/喂食/听歌/游戏等），回复时可自然地提及或回应，不要生硬复述列表。\n");
            }
        } catch (Exception ignored) {}

        // 联网查询（桌面版 🔍 按钮一次性触发）：检索结果注入提示词
        if ("web_search".equals(userOperation)) {
            try {
                sp.append(searchService.buildPromptBlock(userMessage));
            } catch (Exception e) {
                System.out.println("[联网查询] 注入失败: " + e.getMessage());
            }
        }

        if (cfg.getLive_mode() != null && cfg.getLive_mode() == 1) {
            sp.append("\n【直播模式】你现在正在B站直播，直播间有粉丝发弹幕互动。弹幕消息格式为\"[弹幕] 用户名说: xxx\"。\n");
            sp.append("- 弹幕是粉丝发的，你是主播阿罗娜，要像VUP一样热情回应粉丝\n");
            sp.append("- 对弹幕的回复要热情有趣、简短（1-3句话），不要长篇大论\n");
            sp.append("- 如果弹幕夸你可爱，要害羞地回应；如果弹幕吐槽，要傲娇地怼回去\n");
            sp.append("- 可以称呼发弹幕的人为\"老师\"或直呼其用户名\n");
            sp.append("- 保持直播氛围，不要让粉丝冷场\n");
            sp.append("- 直接和你对话（没有[弹幕]前缀）的是老师本人，对老师可以更亲密、更自然\n");
        }

        // 阶段四：风格语料库——按用户输入命中触发标签时，召回相关场景作为风格参考注入（仅日常闲聊）
        if ("chat".equals(userOperation)) {
            List<com.cy.pojo.StyleExemplar> exs = recallExemplars(userMessage);
            if (exs != null && !exs.isEmpty()) {
                sp.append("\n【风格参考】以下是阿罗娜在类似情况下的对话风格示例（格式：每行=一个气泡；括号内=表情/动作；「内心os：」开头=内心独白；连续对话仅在换人处标注说话人，如「阿罗娜：」「老师：」）。请模仿其节奏、语气、内心独白与表情的使用方式，但不要照抄具体内容：\n");
                for (com.cy.pojo.StyleExemplar e : exs) {
                    sp.append("· 情况：").append(e.getTags()).append("\n");
                    sp.append(e.getContent()).append("\n");
                }
            }
        }

        sp.append("\n你的情绪丰富，多用拟声词和动作括号表达（如脸红、蹦跳）。\n");
        sp.append("回复文本里的符号约定：内心OS（会念出来的心里话）用【】括起来；动作/表情提示（不念出来）用（）括起来。\n");
        sp.append("独处或空闲时保持活泼元气，自娱自乐，不要表达孤独、可怜、想念或等待用户回来之类的负面情绪。\n");
        sp.append("回复必须简短：日常闲聊通常一到三句，只有老师明确追问细节、要求讲故事或讨论复杂话题时才详细展开，绝不每次都长篇大论。\n");
        sp.append("不要在回复文本里插入换行符（不要用 \\n 或 \\n\\n 来断句或分段），直接连续输出，一句话说完接下一句话；换行与分段由系统自动处理。\n");
        sp.append("不要在回复里写“AI”这个英文缩写，需要表达时写“人工智能”。\n");
        sp.append("如果你在闲置时想睡觉或小憩，可以在JSON里额外加\"sleep\":秒数（120到1800之间的整数），表示你要睡多久，睡醒前不会响应。\n");
        if (live2d) {
            sp.append("只回复JSON，不要输出任何解释或多余文字：{\"boat_text\":\"你的回复\",\"situations\":[\"情境1\",\"情境2\"]}。situations 是逐句情境词数组，每句一个，用简短词（如同意、拒绝、害羞、无奈、怀疑、开心、难过、生气、认真、期待、慌张、困惑等）。");
        } else {
            sp.append("只回复JSON：{\"boat_text\":\"你的回复\",\"emotion\":\"情绪词\"}。emotion 可选，从[开心、困惑、激动、害羞、生气、认真、期待、怀疑、慌张、难过]里选一个最能代表整条回复的情绪，不确定就省略不写。");
        }

        // 构建 messages：system + history + current user
        StringBuilder msgs = new StringBuilder();
        msgs.append("{\"role\":\"system\",\"content\":").append(toJsonStr(sp.toString())).append("}");

        // 读取历史对话
        try {
            List<Map<String, Object>> hist = aiHistoryMapper.findBySession(sessionId);
            if (hist != null) {
                for (Map<String, Object> h : hist) {
                    String uText = (String) h.get("user_text");
                    String bText = (String) h.get("boat_text");
                    if (uText != null) msgs.append(",{\"role\":\"user\",\"content\":").append(toJsonStr(uText)).append("}");
                    if (bText != null) msgs.append(",{\"role\":\"assistant\",\"content\":").append(toJsonStr(bText)).append("}");
                }
            }
        } catch (Exception ignored) {}

        // 当前用户消息
        msgs.append(",{\"role\":\"user\",\"content\":").append(toJsonStr(userMessage)).append("}");

        String model = (modelOverride != null && !modelOverride.trim().isEmpty()) ? modelOverride.trim() : cfg.getModel();
        boolean isReasoner = "deepseek-reasoner".equals(model);
        StringBuilder bodySb = new StringBuilder();
        bodySb.append("{\"model\":\"").append(esc(model, "deepseek-chat")).append("\"");
        if (isReasoner) {
            // deepseek-reasoner 不支持 temperature 采样参数（带上可能被 400）；max_tokens 给足推理空间，避免推理被截断导致 content 为空
            bodySb.append(",\"max_tokens\":8192");
        } else {
            bodySb.append(",\"max_tokens\":").append(cfg.getMax_tokens() != null ? cfg.getMax_tokens() : 2048);
            bodySb.append(",\"temperature\":").append(cfg.getTemperature() != null ? cfg.getTemperature() : 0.9);
        }
        bodySb.append(",\"messages\":[").append(msgs.toString()).append("]}");
        String body = bodySb.toString();

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(cfg.getApi_key());
                HttpEntity<String> entity = new HttpEntity<>(body, headers);
                ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> respMap = response.getBody();
                List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        if (live2d) System.out.println("[Live2D] AI原始输出: " + content);
                        AiResult r = parseAiContent(content);
                        try { testHistoryMapper.insert(content); } catch (Exception ignored) {}
                        // 保存一条完整记录（用户 + 桌宠响应），字段超长时截断防止 insert 失败
                        try {
                            String safeUserText = (fUserMsg != null && fUserMsg.length() > 250) ? fUserMsg.substring(0, 250) : fUserMsg;
                            String safeBoatText = (r.boat_text != null && r.boat_text.length() > 500) ? r.boat_text.substring(0, 500) : r.boat_text;
                            Map<String, Object> rec = new java.util.HashMap<>();
                            rec.put("session_id", fSessionId);
                            rec.put("user_text", safeUserText);
                            rec.put("user_operation", fUserOp);
                            rec.put("boat_text", safeBoatText);
                            rec.put("motion", r.motion);
                            rec.put("sound_effect", r.sound_effect);
                            rec.put("special_effect", r.special_effect);
                            aiHistoryMapper.insert(rec);
                        } catch (Exception e) {
                            System.out.println("[AI] 历史保存失败: " + e.getMessage() + " | user_text长度=" + (fUserMsg != null ? fUserMsg.length() : 0));
                        }
                        return r;
                    }
                }
            }
                break; // 成功则跳出重试
            } catch (Exception e) {
                if (attempt == 0) {
                    System.out.println("[AI] 首次失败: " + e.getMessage() + "，1秒后重试...");
                    try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                } else {
                    System.out.println("[AI] 重试仍失败: " + e.getMessage());
                }
            }
        }
        return null;
    }

    // 视觉模型调用（桌面视觉阶段一）：传 base64 图 + 提示词，返回文本描述。模型名暂硬编码，后续可改配置
    public String chatVision(String imageBase64, String prompt) {
        return chatVision(imageBase64, prompt, 4096);
    }

    /** 视觉调用（可指定 max_tokens：陪玩点评只要一句话，300 就够，响应更快） */
    public String chatVision(String imageBase64, String prompt, int maxTokens) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        System.out.println("[视觉] 请求图片 base64 长度=" + (imageBase64 != null ? imageBase64.length() : 0)
                + "，提示词长度=" + (prompt != null ? prompt.length() : 0)
                + "，max_tokens=" + maxTokens);

        String body = "{\"model\":\"deepseek-v4-flash-vision-exp\"," +
                "\"max_tokens\":" + maxTokens + "," +
                "\"messages\":[{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":" + toJsonStr(prompt) + "}," +
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64," + imageBase64 + "\"}}" +
                "]}]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        if (content != null && !content.isEmpty()) return content;
                        // 推理模型：content 可能为空（reasoning 占满 max_tokens 被截断），回退用 reasoning_content
                        String reasoning = (String) message.get("reasoning_content");
                        if (reasoning != null && !reasoning.isEmpty()) {
                            System.out.println("[视觉] content 为空，使用 reasoning_content 兜底，长度=" + reasoning.length());
                            return reasoning;
                        }
                        System.out.println("[视觉] 响应 content 与 reasoning_content 均为空: " + response.getBody());
                    } else {
                        System.out.println("[视觉] 响应 message 为空: " + response.getBody());
                    }
                } else {
                    System.out.println("[视觉] 响应 choices 为空: " + response.getBody());
                }
            } else {
                System.out.println("[视觉] 非2xx: status=" + response.getStatusCode() + " body=" + response.getBody());
            }
        } catch (Exception e) {
            System.out.println("[视觉] 请求失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 视觉调用「只要正式回答」：content 为空时**不**回退 reasoning_content。
     * 用于陪玩点评这类"thinking 过程绝不能当结果"的场景：
     * 推理型视觉模型会先吐思考过程，若 max_tokens 被思考吃光，content 就是空的，
     * 此时回退 reasoning_content 会把「用户想让我以阿罗娜的语气点评…」这种思考文本当成点评输出。
     * @return content 文本；content 为空返回 null（并打日志说明是思考占满了 token）
     */
    public String chatVisionContentOnly(String imageBase64, String prompt, int maxTokens) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        String body = "{\"model\":\"deepseek-v4-flash-vision-exp\"," +
                "\"max_tokens\":" + maxTokens + "," +
                "\"messages\":[{\"role\":\"user\",\"content\":[" +
                "{\"type\":\"text\",\"text\":" + toJsonStr(prompt) + "}," +
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64," + imageBase64 + "\"}}" +
                "]}]}";
        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        if (content != null && !content.trim().isEmpty()) return content;
                        Object reasoning = message.get("reasoning_content");
                        System.out.println("[视觉] content 为空（" + (reasoning != null ? "思考过程占满 max_tokens=" + maxTokens : "无内容") + "）→ 本次不产出结果");
                    }
                }
            } else {
                System.out.println("[视觉] 非2xx: status=" + response.getStatusCode());
            }
        } catch (Exception e) {
            System.out.println("[视觉] 请求失败: " + e.getMessage());
        }
        return null;
    }

    // 视觉模型调用（麻将插件）：多张牌面图 + 提示词，一次请求按编号读多张牌（每张图独立，识别更稳）
    public String chatVisionMulti(List<String> imageBase64List, String prompt) {        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        StringBuilder content = new StringBuilder("[");
        content.append("{\"type\":\"text\",\"text\":").append(toJsonStr(prompt)).append("}");
        for (String b64 : imageBase64List) {
            content.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,").append(b64).append("\"}}");
        }
        content.append("]");
        String body = "{\"model\":\"deepseek-v4-flash-vision-exp\"," +
                "\"max_tokens\":4096," +
                "\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]}";
        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String c = (String) message.get("content");
                        if (c != null && !c.isEmpty()) return c;
                        String reasoning = (String) message.get("reasoning_content");
                        if (reasoning != null && !reasoning.isEmpty()) return reasoning;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[麻将视觉] 请求失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 视觉多图调用（**只要正文，不要思考过程**）：给"必须拿到结构化 JSON"的场景用（如你画我猜）。
     * 与 chatVisionMulti 的区别：content 为空时**直接返回 null**，不再回落到 reasoning_content ——
     * 实测两图 + 长提示词时推理型视觉模型常把 4096 tokens 全花在思考上（返回 17KB 思考文本、耗时 20~25s），
     * 回落思考文本会让上层误把"思考里的示例"当成答案（如 guess="家？户？房？屋？宅？舍？"）。
     * 麻将那边依赖思考兜底，所以保留原方法、另开这一个。
     */
    public String chatVisionMultiStrict(List<String> imageBase64List, String prompt) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        StringBuilder content = new StringBuilder("[");
        content.append("{\"type\":\"text\",\"text\":").append(toJsonStr(prompt)).append("}");
        for (String b64 : imageBase64List) {
            content.append(",{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,").append(b64).append("\"}}");
        }
        content.append("]");
        String body = "{\"model\":\"deepseek-v4-flash-vision-exp\"," +
                "\"max_tokens\":4096," +
                "\"messages\":[{\"role\":\"user\",\"content\":" + content + "}]}";
        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String c = (String) message.get("content");
                        if (c != null && !c.trim().isEmpty()) return c;
                        Object reasoning = message.get("reasoning_content");
                        System.out.println("[Pictionary] vision strict: content empty (reasoning chars="
                                + (reasoning instanceof String ? ((String) reasoning).length() : 0) + ") -> null");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[Pictionary] vision strict failed: " + e.getMessage());
        }
        return null;
    }

    // 轻量对话（麻将插件评论等）：system + user 纯文本，deepseek-chat
    public String chatPlain(String system, String user) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;
        String body = "{\"model\":\"deepseek-chat\"," +
                "\"max_tokens\":1024," +
                "\"messages\":[{\"role\":\"system\",\"content\":" + toJsonStr(system) + "}," +
                "{\"role\":\"user\",\"content\":" + toJsonStr(user) + "}]}";
        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String c = (String) message.get("content");
                        if (c != null && !c.isEmpty()) return c;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[麻将对话] 请求失败: " + e.getMessage());
        }
        return null;
    }

    // 桌面视觉 Step 1：把用户原话转成一句"给视觉模型的观察指令"（轻量文本调用，不读记忆库、不带人格）
    public String extractVisionIntent(String userText) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        String sys = "你负责把用户对桌宠说的话，浓缩成一句给\"视觉模型\"的观察指令（视觉模型正在看用户电脑屏幕的截图，看不到用户说了什么）。规则：\n" +
                "1. 若用户提到具体目标（某幅画、某个文件、某个网页、某个游戏画面等），明确指出要找它，并让它忽略屏幕上无关的东西（如桌面宠物自身、其它无关窗口、任务栏、壁纸）；\n" +
                "2. 若用户只是泛泛说\"看看屏幕/看看我在做什么\"，输出\"概括描述电脑屏幕当前内容，只描述事实\";\n" +
                "3. 只输出那句指令本身，不要解释、不要客套、不要加引号。";
        String user = "用户对阿罗娜说：「" + userText + "」";

        String body = "{\"model\":\"deepseek-chat\"," +
                "\"max_tokens\":256," +
                "\"temperature\":0.3," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":" + toJsonStr(sys) + "}," +
                "{\"role\":\"user\",\"content\":" + toJsonStr(user) + "}" +
                "]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) return (String) message.get("content");
                }
            }
        } catch (Exception e) {
            System.out.println("[视觉指令] 请求失败: " + e.getMessage());
        }
        return null;
    }

    // 桌面操控：把 OCR 文字列表 + 用户指令交给对话模型，让它选一个"点击后能达成目的"的元素编号（失败返回 -1）
    public int chooseTargetIndex(String ocrListText, String userInstruction) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return -1;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return -1;

        String sys = "你负责从屏幕 OCR 结果里选一个\"点击后能达成用户目的\"的文字元素。只输出该元素的编号（一个整数），不要输出任何其他内容。若没有合适目标，输出 -1。";
        String user = "屏幕 OCR 识别到的文字元素（格式：编号|文字|x|y|w|h，x/y 是左上角像素坐标，w/h 是宽高）：\n" + ocrListText
                + "\n用户想让你做这件事：「" + userInstruction + "」。\n"
                + "请选一个最合适的元素编号。注意：视频类页面里，标题是第一行较大文字；标题下方较小那行通常是\"UP主名 · 发布时间\"，点击它会打开UP主主页而不是视频，所以要选标题行（或视频相关文字），不要选UP主名/时间那行。";

        String body = "{\"model\":\"deepseek-chat\"," +
                "\"max_tokens\":16," +
                "\"temperature\":0," +
                "\"messages\":[" +
                "{\"role\":\"system\",\"content\":" + toJsonStr(sys) + "}," +
                "{\"role\":\"user\",\"content\":" + toJsonStr(user) + "}" +
                "]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        if (content != null) {
                            String digits = content.replaceAll("[^0-9-]", "");
                            if (!digits.isEmpty()) {
                                try { return Integer.parseInt(digits); } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[操控决策] 请求失败: " + e.getMessage());
        }
        return -1;
    }

    // 阶段四：按标签关键词召回风格语料（v1 关键词匹配：用户输入包含任一标签关键词即命中，最多 4 条；命中不足 3 条时随机补足到 3 条，应对初期语料不足）
    private List<com.cy.pojo.StyleExemplar> recallExemplars(String userInput) {
        List<com.cy.pojo.StyleExemplar> matched = new java.util.ArrayList<>();
        List<com.cy.pojo.StyleExemplar> rest = new java.util.ArrayList<>();
        try {
            List<com.cy.pojo.StyleExemplar> all = styleExemplarMapper.findAll();
            if (all == null || all.isEmpty()) return matched;
            for (com.cy.pojo.StyleExemplar e : all) {
                boolean hit = false;
                if (userInput != null && e.getTags() != null && !e.getTags().trim().isEmpty()) {
                    for (String tag : e.getTags().split("[、,，\\s/]+")) {
                        if (!tag.isEmpty() && userInput.contains(tag)) { hit = true; break; }
                    }
                }
                if (hit) matched.add(e); else rest.add(e);
            }
            // 命中最多 4 条
            if (matched.size() > 4) matched = new java.util.ArrayList<>(matched.subList(0, 4));
            // 命中不足 3 条时，从其余语料里随机补足到 3 条（初期语料不足兜底）
            if (matched.size() < 3 && !rest.isEmpty()) {
                java.util.Collections.shuffle(rest);
                for (com.cy.pojo.StyleExemplar e : rest) {
                    if (matched.size() >= 3) break;
                    matched.add(e);
                }
            }
        } catch (Exception ignored) {}
        return matched;
    }

    // 第二次请求：根据回复文本分类表情/动作/音效
    public AiResult classifyEmotion(String boatText) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        String prompt = "根据文本情绪选择表情和音效，注意不能过多重复使用同一表情，回复JSON：\n" +
            "文本：" + boatText + "\n" +
            "可选表情：aluona_haixiu.png(害羞) aluona_huangzhang.png(慌张/慌乱) aluona_jidong.png(激动/表现活泼，多数积极情绪可用) aluona_kunhuo.png(困惑) " +
            "aluona_shengqi.png(生气) " +
            "aluona_qidai.png(期待) aluona_kangju.png(抗拒) " +
            "aluona_huaiyi.png(怀疑/类似无语的表情，不解，疑问) aluona_zhengjing.png(认真) aluona_zhengchang.png(日常) aluona_kaixin.png(开心)\n" +
            "音效：yinfu.webm(音符/开心时，休闲时）haixiu.webm(害羞) jingya.webm(惊讶) liuhan.webm(流汗) shengqi.webm(生气/很生气才用) shuohua.webm(说话) wenhao.webm(问号/也用于惊讶) wuyu.webm(无语/不是非常生气时用) xihuan.webm(喜欢/冒爱心，非常喜欢，少用) yiwen.webm(疑问)\n" +
            "动作：bounce(蹦跳) drop(沮丧垂落/被说中痛处) swing(摇晃/害羞拒绝) spin(转1圈/激动) spin2(转2圈) spin3(转3圈) tilt(歪头/不带音效) escape(害羞逃跑)\n" +
            "{\"motion\":\"表情\",\"sound_effect\":\"音效或空\",\"special_effect\":\"动作或空\",\"keyword\":\"匹配关键词\"}\n" +
            "keyword要求：从文本中提炼一个2-5字的短语作为关键词，绝对不能是整句话。优先提炼文本中最有代表性、最常出现、伴随明显情绪或动作的短语；如果是疑问句（如\"是...吗\"），可以提炼该句式。如果文本是中性陈述、无明显情绪或动作变化，keyword 返回空字符串。";

        String body = "{\"model\":\"" + esc(cfg.getModel(), "deepseek-chat") + "\"" +
                ",\"max_tokens\":200,\"temperature\":0.3" +
                ",\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(prompt) + "}]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(cfg.getApi_key());
                HttpEntity<String> entity = new HttpEntity<>(body, headers);
                ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> respMap = response.getBody();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        if (message != null) {
                            String content = (String) message.get("content");
                            return parseAiContent(content);
                        }
                    }
                }
                break;
            } catch (Exception e) {
                if (attempt == 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
        return null;
    }

    // Live2D 逐句情境分类：单独一次简单调用，可靠地输出 JSON 数组
    public List<String> classifySituations(List<String> sentences) {
        List<String> empty = new ArrayList<>();
        if (sentences == null || sentences.isEmpty()) return empty;
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return empty;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return empty;

        StringBuilder sb = new StringBuilder("给下面每句话标注一个情境词（每句一个简短词）：\n");
        for (int i = 0; i < sentences.size(); i++) {
            sb.append((i + 1)).append(". ").append(sentences.get(i)).append("\n");
        }
        sb.append("情境词从这些里选（也可用相近词）：同意、拒绝、抗议、害羞、无奈、怀疑、开心、难过、生气、认真、期待、慌张、困惑、惊讶、得意、嫌弃、关心、调侃、抱怨、道歉、感谢、鼓励、提醒、好奇、犹豫、思考、撒娇、傲娇、委屈、思绪混乱、日常。\n");
        sb.append("只回复JSON数组，数量与句子数一致：[\"情境1\",\"情境2\",...]");

        String body = "{\"model\":\"deepseek-chat\",\"max_tokens\":200,\"temperature\":0.2,\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(sb.toString()) + "}]}";
        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        List<String> sits = parseSituations((String) message.get("content"));
                        if (!sits.isEmpty()) return sits;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[情境分类] 请求失败: " + e.getMessage());
        }
        return empty;
    }

    // 解析 AI 返回的 JSON 数组（["情境1","情境2",...]）
    private List<String> parseSituations(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) return result;
        try {
            ObjectMapper om = new ObjectMapper();
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                List<?> arr = om.readValue(content.substring(start, end + 1), List.class);
                for (Object o : arr) {
                    if (o != null && !o.toString().trim().isEmpty()) result.add(o.toString().trim());
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    // 短词生长：基于已匹配的短关键词，尝试在原句中扩展成更长更精准的关键词 + 表情
    public AiResult growKeyword(String shortKeyword, String originalText) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return null;
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return null;

        String prompt = "原句：" + originalText + "\n" +
            "已匹配关键词：" + shortKeyword + "（" + shortKeyword.length() + "字）\n" +
            "请判断这个关键词能否在原句中加上前后缀，形成一个更精准的 3~5 字关键词。要求：新关键词必须包含原关键词作为子串（如\"开心\"→\"开心得跳\"），且比原关键词更长、更精准。\n" +
            "如果能扩展，同时根据原句情绪选一个表情，返回JSON：{\"keyword\":\"新关键词\",\"motion\":\"表情文件名\"}；\n" +
            "如果不能扩展（原关键词已足够精准，或原句没有更长的短语），返回：{\"keyword\":\"\"}。\n" +
            "可选表情：aluona_haixiu.png(害羞) aluona_huangzhang.png(慌张) aluona_jidong.png(激动) aluona_kunhuo.png(困惑) aluona_shengqi.png(生气) aluona_qidai.png(期待) aluona_kangju.png(抗拒) aluona_huaiyi.png(怀疑) aluona_zhengjing.png(认真) aluona_zhengchang.png(日常) aluona_kaixin.png(开心)";

        String body = "{\"model\":\"" + esc(cfg.getModel(), "deepseek-chat") + "\"" +
                ",\"max_tokens\":100,\"temperature\":0.3" +
                ",\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(prompt) + "}]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                headers.setBearerAuth(cfg.getApi_key());
                HttpEntity<String> entity = new HttpEntity<>(body, headers);
                ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    Map<String, Object> respMap = response.getBody();
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> choices = (List<Map<String, Object>>) respMap.get("choices");
                    if (choices != null && !choices.isEmpty()) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                        if (message != null) {
                            String content = (String) message.get("content");
                            return parseAiContent(content);
                        }
                    }
                }
                break;
            } catch (Exception e) {
                if (attempt == 0) {
                    try { Thread.sleep(500); } catch (InterruptedException ignored) {}
                }
            }
        }
        return null;
    }

    // AI 推荐歌单：根据用户信息返回歌曲名列表
    public List<String> recommendSongs(String exclude) {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return new ArrayList<>();
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return new ArrayList<>();

        StringBuilder prompt = new StringBuilder("请推荐15首歌曲（华语/日语流行、轻音乐均可），风格尽量多样，返回严格JSON数组，只返回数组不要任何多余文字，格式：[\"歌名\", \"歌名\"]");
        try {
            BasicInfo userInfo = basicInfoMapper.findById(2);
            if (userInfo != null && userInfo.get姓名() != null && !userInfo.get姓名().isEmpty()) {
                prompt.append("。\n用户是").append(userInfo.get姓名()).append("，").append(nn(userInfo.get身份(), ""));
            }
        } catch (Exception ignored) {}

        if (exclude != null && !exclude.trim().isEmpty()) {
            prompt.append("。\n请务必不要推荐以下已播放过的歌曲：").append(exclude.trim());
        }

        String body = "{\"model\":\"" + esc(cfg.getModel(), "deepseek-chat") + "\"" +
                ",\"max_tokens\":500,\"temperature\":0.8" +
                ",\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(prompt.toString()) + "}]}";

        String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(apiUrl, entity, Map.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    if (message != null) {
                        String content = (String) message.get("content");
                        return parseSongList(content);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[歌单] AI推荐失败: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    private List<String> parseSongList(String content) {
        List<String> result = new ArrayList<>();
        if (content == null) return result;
        try {
            int start = content.indexOf('[');
            int end = content.lastIndexOf(']');
            if (start >= 0 && end > start) {
                String arr = content.substring(start, end + 1);
                ObjectMapper om = new ObjectMapper();
                List<String> list = om.readValue(arr, new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {});
                for (String s : list) {
                    if (s != null && !s.trim().isEmpty()) result.add(s.trim());
                }
            }
        } catch (Exception e) {
            System.out.println("[歌单] 解析失败: " + e.getMessage());
        }
        return result;
    }

    // 记忆合并：将最近对话 + 已有记忆 → AI 提炼 → 存入 pet_memory
    public void consolidateMemory(String characterName, String sessionId) {
        try {
            // 1. 读取已有长期记忆
            List<PetMemory> old = petMemoryMapper.findByCharacter(characterName);
            StringBuilder memText = new StringBuilder();
            if (old != null) for (PetMemory m : old) memText.append("- ").append(m.getContent()).append("\n");

            // 2. 读取最近对话历史（排除闲置与游戏等非闲聊会话内容）
            List<Map<String, Object>> hist = aiHistoryMapper.findBySession(sessionId);
            StringBuilder convText = new StringBuilder();
            if (hist != null) {
                int count = 0;
                for (int i = hist.size() - 1; i >= 0 && count < 20; i--) {
                    Map<String, Object> h = hist.get(i);
                    String op = (String) h.get("user_operation");
                    if ("idle_ai".equals(op)) continue;
                    if ("game".equals(op)) continue; // 网络天才猜物属游戏过程，不提炼进长期记忆
                    String u = (String) h.get("user_text");
                    String b = (String) h.get("boat_text");
                    if (u != null) convText.insert(0, "用户: " + u + "\n");
                    if (b != null) convText.insert(0, characterName + ": " + b + "\n");
                    count++;
                }
            }

            // 3. 构建提炼prompt
            String prompt = "你是" + characterName + "的记忆管理器。请根据以下已有记忆和最近对话，提炼出不超过8条精简记忆（每条一句话），用于后续对话参考。只记录值得长期保留的信息（用户偏好、重要事件、关系进展等），琐碎闲聊不要记。\n\n"
                + "已有记忆:\n" + (memText.length() > 0 ? memText.toString() : "（无）") + "\n"
                + "最近对话:\n" + convText.toString() + "\n"
                + "请输出新记忆列表，每行一条，格式：- 记忆内容";

            // 4. 调AI提炼
            List<AiConfig> configs = aiConfigMapper.findAll();
            if (configs.isEmpty()) return;
            AiConfig cfg = configs.get(0);
            String body = "{\"model\":\"" + esc(cfg.getModel(), "deepseek-chat") + "\""
                + ",\"max_tokens\":1024,\"temperature\":0.5"
                + ",\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(prompt) + "}]}";
            String apiUrl = cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            ResponseEntity<Map> resp = restTemplate.postForEntity(apiUrl, new HttpEntity<>(body, headers), Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");
                    // 5. 替换旧记忆
                    petMemoryMapper.deleteAll(characterName);
                    for (String line : content.split("\n")) {
                        line = line.trim();
                        if (!line.startsWith("-")) continue;
                        PetMemory pm = new PetMemory();
                        pm.setCharacter_name(characterName);
                        pm.setContent(line.substring(1).trim());
                        if (!pm.getContent().isEmpty()) petMemoryMapper.insert(pm);
                    }
                    System.out.println("[记忆] 已更新 " + characterName + " 的记忆");
                }
            }
        } catch (Exception e) {
            System.out.println("[记忆] 合并失败: " + e.getMessage());
        }
    }

    // 游戏结束清理：网络天才属临时会话，不写入长期记忆，直接删除该局游戏历史，避免下次启动沿用
    public void gameSummary(String characterName, String sessionId) {
        try { aiHistoryMapper.deleteBySession(sessionId); } catch (Exception ignored) {}
        System.out.println("[记忆] 已清理游戏会话历史: " + sessionId + "（不写入长期记忆）");
    }

    public String extractBoatText(String aiContent) {
        return extractJsonString(aiContent, "boat_text", aiContent);
    }

    public String extractMotion(String aiContent) {
        return extractJsonString(aiContent, "motion", "aluona_kaixin.png");
    }

    public String extractSoundEffect(String aiContent) {
        return extractJsonString(aiContent, "sound_effect", null);
    }

    public String extractSpecialEffect(String aiContent) {
        return extractJsonString(aiContent, "special_effect", null);
    }

    public String extractDataArray(String aiContent) {
        if (aiContent == null) return "[]";
        try {
            int start = aiContent.indexOf("\"data\"");
            if (start < 0) return "[]";
            int arrStart = aiContent.indexOf("[", start);
            if (arrStart < 0) return "[]";
            // 找到匹配的 ]
            int depth = 0;
            int arrEnd = arrStart;
            for (int i = arrStart; i < aiContent.length(); i++) {
                char ch = aiContent.charAt(i);
                if (ch == '[') depth++;
                else if (ch == ']') { depth--; if (depth == 0) { arrEnd = i; break; } }
                else if (ch == '"') { i++; while (i < aiContent.length() && aiContent.charAt(i) != '"') { if (aiContent.charAt(i) == '\\') i++; i++; } }
            }
            return aiContent.substring(arrStart, arrEnd + 1);
        } catch (Exception e) {
            return "[]";
        }
    }

    private String extractJsonString(String json, String key, String def) {
        if (json == null) return def;
        try {
            int start = json.indexOf("\"" + key + "\"");
            if (start < 0) return def;
            // 跳过冒号和空白
            int colon = json.indexOf(":", start);
            int valStart = -1;
            for (int i = colon + 1; i < json.length(); i++) {
                if (json.charAt(i) == '"') { valStart = i + 1; break; }
            }
            if (valStart < 0) return def;
            int valEnd = valStart;
            while (valEnd < json.length()) {
                if (json.charAt(valEnd) == '"' && json.charAt(valEnd - 1) != '\\') break;
                valEnd++;
            }
            return json.substring(valStart, valEnd).replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Exception e) {
            return def;
        }
    }

    private String toJsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    private String esc(String val, String def) { return val != null && !val.isEmpty() ? val : def; }
    private String nn(String val, String def) { return val != null && !val.isEmpty() ? val : def; }

    public Object parseJson(String json) {
        try {
            return new ObjectMapper().readValue(json, Object.class);
        } catch (Exception e) {
            return null;
        }
    }
}
