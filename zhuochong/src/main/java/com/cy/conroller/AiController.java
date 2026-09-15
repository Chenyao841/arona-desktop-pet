package com.cy.conroller;

import com.cy.mapper.AiConfigMapper;
import com.cy.mapper.ConservationMapper;
import com.cy.mapper.AiHistoryMapper;
import com.cy.mapper.BasicInfoMapper;
import com.cy.mapper.TtsReferenceMapper;
import com.cy.mapper.Live2dPartMapper;
import com.cy.mapper.Live2dRuleMapper;
import com.cy.pojo.BasicInfo;
import com.cy.mapper.AiPromptMapper;
import com.cy.mapper.ResponseRuleMapper;
import com.cy.mapper.IdleActivityMapper;
import com.cy.mapper.TestHistoryMapper;
import com.cy.pojo.ResponseRule;
import com.cy.pojo.IdleActivity;
import com.cy.pojo.Conservation;
import com.cy.pojo.TtsReference;
import com.cy.pojo.Live2dPart;
import com.cy.pojo.Live2dRule;
import com.cy.service.DanmakuService;
import com.cy.service.DeepSeekService;
import com.cy.service.NeteaseService;
import com.cy.service.TtsService;
import com.cy.pojo.AiConfig;
import com.cy.pojo.AiPrompt;
import com.cy.pojo.Result;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.time.Duration;
import java.util.*;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Autowired
    private AiConfigMapper aiConfigMapper;

    @Autowired
    private AiPromptMapper aiPromptMapper;

    @Autowired
    private TestHistoryMapper testHistoryMapper;

    @Autowired
    private AiHistoryMapper aiHistoryMapper;

    @Autowired
    private ResponseRuleMapper responseRuleMapper;

    @Autowired
    private IdleActivityMapper idleActivityMapper;

    @Autowired
    private TtsService ttsService;

    @Autowired
    private TtsReferenceMapper ttsReferenceMapper;

    @Autowired
    private Live2dPartMapper live2dPartMapper;

    @Autowired
    private Live2dRuleMapper live2dRuleMapper;

    @Autowired
    private ConservationMapper conservationMapper;

    @Autowired
    private DanmakuService danmakuService;

    @Autowired
    private NeteaseService neteaseService;

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private BasicInfoMapper basicInfoMapper;

    @Autowired
    private com.cy.mapper.StyleExemplarMapper styleExemplarMapper;

    @Autowired
    private com.cy.mapper.EventBookMapper eventBookMapper;

    private final RestTemplate restTemplate;

    public AiController() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(120));
        this.restTemplate = new RestTemplate(factory);
    }

    // ===== AI 配置 =====
    @GetMapping("/config")
    public Result listConfig() {
        List<AiConfig> list = aiConfigMapper.findAll();
        return Result.success(list.isEmpty() ? null : list.get(0));
    }

    @PostMapping("/config")
    public Result saveConfig(@RequestBody AiConfig config) {
        List<AiConfig> list = aiConfigMapper.findAll();
        if (list.isEmpty()) {
            aiConfigMapper.insert(config);
        } else {
            config.setId(list.get(0).getId());
            aiConfigMapper.update(config);
        }
        return Result.success(config);
    }

    @PostMapping("/config/room-id")
    public Result saveRoomId(@RequestBody Map<String, Object> req) {
        Integer roomId = req.get("room_id") != null ? Integer.parseInt(req.get("room_id").toString()) : null;
        if (roomId == null || roomId <= 0) return Result.error("房间号无效");
        List<AiConfig> list = aiConfigMapper.findAll();
        if (list.isEmpty()) return Result.error("未配置AI");
        AiConfig cfg = list.get(0);
        cfg.setRoom_id(roomId);
        aiConfigMapper.update(cfg);
        return Result.ok("房间号已更新为 " + roomId);
    }

    @PostMapping("/config/netease-uid")
    public Result saveNeteaseUid(@RequestBody Map<String, Object> req) {
        String uid = req.get("netease_uid") != null ? req.get("netease_uid").toString().trim() : null;
        if (uid == null || uid.isEmpty()) return Result.error("UID不能为空");
        List<AiConfig> list = aiConfigMapper.findAll();
        if (list.isEmpty()) return Result.error("未配置AI");
        AiConfig cfg = list.get(0);
        cfg.setNetease_uid(uid);
        aiConfigMapper.update(cfg);
        return Result.ok("网易云UID已更新");
    }

    // ===== AI 提示词 =====
    @GetMapping("/prompt")
    public Result listPrompt() {
        return Result.success(aiPromptMapper.findAll());
    }

    @GetMapping("/prompt/{id}")
    public Result getPrompt(@PathVariable Integer id) {
        return Result.success(aiPromptMapper.findById(id));
    }

    @PostMapping("/prompt")
    public Result savePrompt(@RequestBody AiPrompt prompt) {
        AiPrompt existing = aiPromptMapper.findByTypeAndVersion(prompt.getPrompt_type(), prompt.getVersion());
        if (existing != null) {
            prompt.setId(existing.getId());
            aiPromptMapper.update(prompt);
        } else {
            aiPromptMapper.insert(prompt);
        }
        return Result.success(prompt);
    }

    @PutMapping("/prompt")
    public Result updatePrompt(@RequestBody AiPrompt prompt) {
        aiPromptMapper.update(prompt);
        return Result.success(prompt);
    }

    @DeleteMapping("/prompt/{id}")
    public Result deletePrompt(@PathVariable Integer id) {
        aiPromptMapper.deleteById(id);
        return Result.ok("删除成功");
    }

    @GetMapping("/prompt/version/{version}")
    public Result getByVersion(@PathVariable Integer version) {
        return Result.success(aiPromptMapper.findByVersion(version));
    }

    @GetMapping("/models")
    public Result listModels() {
        Map<String, Object> result = new LinkedHashMap<>();
        String staticDir = System.getProperty("user.dir") + "/src/main/resources/static";
        File gptDir = new File(staticDir, "GPT_weights_v2");
        File sovitsDir = new File(staticDir, "SoVITS_weights_v2");
        List<Map<String, String>> gptList = new ArrayList<>();
        List<Map<String, String>> sovitsList = new ArrayList<>();
        if (gptDir.exists() && gptDir.isDirectory()) {
            File[] files = gptDir.listFiles((d, n) -> n.endsWith(".ckpt"));
            if (files != null) for (File f : files) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", f.getName());
                m.put("path", f.getAbsolutePath());
                gptList.add(m);
            }
        }
        if (sovitsDir.exists() && sovitsDir.isDirectory()) {
            File[] files = sovitsDir.listFiles((d, n) -> n.endsWith(".pth"));
            if (files != null) for (File f : files) {
                Map<String, String> m = new LinkedHashMap<>();
                m.put("name", f.getName());
                m.put("path", f.getAbsolutePath());
                sovitsList.add(m);
            }
        }
        result.put("gpt", gptList);
        result.put("sovits", sovitsList);
        return Result.success(result);
    }

    @PostMapping("/switch-model")
    public Result switchModel(@RequestBody Map<String, String> req) {
        String gptPath = req.get("gpt_model_path");
        String sovitsPath = req.get("sovits_model_path");
        if (gptPath == null || gptPath.isEmpty() || sovitsPath == null || sovitsPath.isEmpty()) {
            return Result.error("模型路径不能为空");
        }
        List<AiConfig> list = aiConfigMapper.findAll();
        if (list.isEmpty()) return Result.error("未找到AI配置");
        AiConfig cfg = list.get(0);
        String ttsUrl = cfg.getTts_url();
        if (ttsUrl == null || ttsUrl.isEmpty()) return Result.error("TTS地址未配置");
        if (ttsUrl.endsWith("/")) ttsUrl = ttsUrl.substring(0, ttsUrl.length() - 1);

        try {
            Map<String, String> body = new LinkedHashMap<>();
            body.put("gpt_model_path", gptPath);
            body.put("sovits_model_path", sovitsPath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, String>> entity = new HttpEntity<>(body, headers);
            String url = ttsUrl + "/set_model";

            ResponseEntity<Map> resp = restTemplate.exchange(url, HttpMethod.POST, entity, Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                Map respBody = resp.getBody();
                if ((int) respBody.get("code") == 0) {
                    cfg.setGpt_model_path(gptPath);
                    cfg.setSovits_model_path(sovitsPath);
                    aiConfigMapper.update(cfg);
                    return Result.ok("模型切换成功");
                }
                return Result.error("GPT-SoVITS返回: " + respBody.get("message"));
            }
            return Result.error("GPT-SoVITS返回状态: " + resp.getStatusCode().value());
        } catch (Exception e) {
            return Result.error("模型切换异常: " + e.getMessage());
        }
    }

    @PostMapping("/tts")
    public Result synthesizeSpeech(@RequestBody Map<String, String> req) {
        String text = req.get("text");
        if (text == null || text.trim().isEmpty()) return Result.error("文本为空");
        int v = TtsService.DEFAULT_VARIANT;
        try { if (req.get("variant") != null) v = Integer.parseInt(req.get("variant")); } catch (Exception ignored) {}
        String audio = ttsService.synthesize(text, v > 0 ? v : TtsService.DEFAULT_VARIANT);
        return audio != null ? Result.success(audio) : Result.error("TTS 服务不可用");
    }

    @GetMapping("/tts/candidates")
    public Result candidates(@RequestParam String text) {
        if (text == null || text.trim().isEmpty()) return Result.error("文本为空");
        List<String> list = ttsService.synthesizeCandidates(text, 3);
        return list != null && !list.isEmpty() ? Result.success(list) : Result.error("TTS 服务不可用");
    }

    @PostMapping("/tts/select")
    public Result selectCandidate(@RequestBody Map<String, String> req) {
        String text = req.get("text");
        String audio = req.get("audio");
        if (text == null || text.trim().isEmpty() || audio == null || audio.isEmpty()) return Result.error("参数缺失");
        ttsService.saveCandidate(text, audio);
        return Result.ok("已保存");
    }

    /** 语音缓存状态：{count, MB} */
    @GetMapping("/tts/cache")
    public Result ttsCacheStat() {
        long[] s = ttsService.cacheStat();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("count", s[0]);
        m.put("mb", Math.round(s[1] / 1024.0 / 1024.0 * 10) / 10.0);
        return Result.success(m);
    }

    /** 清空语音缓存（换参考音频/模型后强制重新合成用） */
    @PostMapping("/tts/cache/clear")
    public Result ttsCacheClear() {
        int n = ttsService.clearCache();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cleared", n);
        m.put("message", "已清空语音缓存（" + n + " 项），下次试听/说话会重新合成");
        return Result.success(m);
    }

    @PostMapping("/tts/warmup")
    public Result warmup() {
        List<Conservation> list = conservationMapper.findBasicResponses();
        final List<String> pending = new ArrayList<>();
        for (Conservation c : list) {
            if (c.getBoat_text() == null || c.getBoat_text().trim().isEmpty()) continue;
            if (!ttsService.hasCache(c.getBoat_text())) pending.add(c.getBoat_text());
        }
        final int total = pending.size();
        new Thread(() -> {
            int done = 0;
            for (String text : pending) {
                ttsService.synthesize(text);
                done++;
                System.out.println("[语音库] 预热 " + done + "/" + total + ": " + text);
            }
            System.out.println("[语音库] 预热完成，共 " + total + " 条");
        }).start();
        return Result.ok(total > 0 ? ("已开始后台生成 " + total + " 条语音") : "所有语音已缓存，无需生成");
    }

    @GetMapping("/tts/stream")
    public SseEmitter streamTts(@RequestParam String text,
                               @RequestParam(defaultValue = "0") int variant) {
        SseEmitter emitter = new SseEmitter(0L);
        if (text == null || text.trim().isEmpty()) {
            try { emitter.complete(); } catch (Exception ignored) {}
            return emitter;
        }
        final int v = variant > 0 ? variant : TtsService.DEFAULT_VARIANT;
        final List<String> segments;
        if (text.length() < 40) {
            // 短文本（基础交互的固定回复）整段合成，命中语音库预热缓存，避免拆段导致缓存未命中而现场合成出怪声
            segments = new ArrayList<>();
            segments.add(text.trim());
        } else {
            segments = splitSentences(text);
        }
        final ObjectMapper om = new ObjectMapper();
        new Thread(() -> {
            try {
                for (int i = 0; i < segments.size(); i++) {
                    String seg = segments.get(i);
                    String audio = ttsService.synthesize(seg, v);
                    if (audio == null) continue;
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("index", i);
                    payload.put("total", segments.size());
                    payload.put("text", seg);
                    payload.put("audio", audio);
                    emitter.send(SseEmitter.event().data(om.writeValueAsString(payload)));
                }
                emitter.complete();
            } catch (Exception e) {
                try { emitter.completeWithError(e); } catch (Exception ignored) {}
            }
        }).start();
        return emitter;
    }

    /**
     * TTS 变体试听（「语音合成调试」页用）：返回该变体实际送给 GPT-SoVITS 的文本、采样参数、参考音频与音频。
     * variant 1~TtsService.VARIANT_COUNT；0 或不传 = 当前默认变体
     */
    @GetMapping("/tts/ab")
    public Result ttsAb(@RequestParam String text, @RequestParam(defaultValue = "0") int variant) {
        if (text == null || text.trim().isEmpty()) return Result.error("文本为空");
        int v = variant > 0 ? variant : TtsService.DEFAULT_VARIANT;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("variant", v);
        out.put("name", TtsService.variantName(v));
        out.put("text", ttsService.previewText(text, v));
        String[] ref = ttsService.referenceInfo(text, v);
        out.put("referWav", ref[0]);
        out.put("referText", ref[1]);
        out.put("params", (v == 4 || v == 5) ? "top_k=3, top_p=0.85, temperature=0.5" : "top_k=5, top_p=1.0, temperature=0.6");
        out.put("audio", ttsService.synthesize(text, v));
        return Result.success(out);
    }

    /** 变体清单 + 当前默认变体（调试页表头用） */
    @GetMapping("/tts/variants")
    public Result ttsVariants() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int v = 1; v <= TtsService.VARIANT_COUNT; v++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("variant", v);
            m.put("name", TtsService.variantName(v));
            list.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("defaultVariant", TtsService.DEFAULT_VARIANT);
        out.put("list", list);
        return Result.success(out);
    }

    // 把某条基础交互文本的缓存语音设为参考音频
    @PostMapping("/tts/reference/add")
    public Result addReference(@RequestBody Map<String, String> req) {
        String text = req.get("text");
        if (text == null || text.trim().isEmpty()) return Result.error("文本为空");
        String err = ttsService.addReference(text);
        return err == null ? Result.ok("已设为参考音频") : Result.error(err);
    }

    // 参考音频列表
    @GetMapping("/tts/reference/list")
    public Result listReferences() {
        return Result.success(ttsReferenceMapper.findAll());
    }

    // 删除参考音频
    @PostMapping("/tts/reference/delete")
    public Result deleteReference(@RequestBody Map<String, Object> req) {
        Object id = req.get("id");
        if (id == null) return Result.error("缺少 id");
        ttsReferenceMapper.deleteById(Integer.parseInt(id.toString()));
        return Result.ok("已删除");
    }

    // ===== Live2D 情境规则库（阶段重构） =====

    // 部件定义列表（按区域分组返回）
    @GetMapping("/live2d/parts")
    public Result listLive2dParts() {
        List<Live2dPart> parts = live2dPartMapper.findAll();
        Map<String, List<Live2dPart>> grouped = new LinkedHashMap<>();
        for (Live2dPart p : parts) {
            grouped.computeIfAbsent(p.getPart_group(), k -> new ArrayList<>()).add(p);
        }
        return Result.success(grouped);
    }

    // 规则库列表
    @GetMapping("/live2d/rules")
    public Result listLive2dRules() {
        return Result.success(live2dRuleMapper.findAll());
    }

    // 保存规则（有 id 则更新，无 id 则新增）
    @PostMapping("/live2d/rule/save")
    public Result saveLive2dRule(@RequestBody Live2dRule rule) {
        if (rule.getSituation() == null || rule.getSituation().trim().isEmpty()) {
            return Result.error("情境不能为空");
        }
        if (rule.getId() != null && rule.getId() > 0) {
            live2dRuleMapper.update(rule);
        } else {
            live2dRuleMapper.insert(rule);
        }
        return Result.ok("已保存");
    }

    // 删除规则
    @PostMapping("/live2d/rule/delete")
    public Result deleteLive2dRule(@RequestBody Map<String, Object> req) {
        Object id = req.get("id");
        if (id == null) return Result.error("缺少 id");
        live2dRuleMapper.deleteById(Integer.parseInt(id.toString()));
        return Result.ok("已删除");
    }

    // 按句切分文本（句号/问号/感叹号/分号/换行），最小段长避免过碎
    // 括号规则与 ConservationController.splitToSentences 保持一致：（）与【】内部不切句，
    // 且 】 作为句边界，避免「【…！】」的右括号/后续说明被切到下一段
    private List<String> splitSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        text = text.replace("\r\n", "\n").trim();
        StringBuilder cur = new StringBuilder();
        int minLen = 8;
        int psyDepth = 0, actDepth = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            cur.append(ch);
            if (ch == '【') { psyDepth++; continue; }
            if (ch == '】') {
                if (psyDepth > 0) psyDepth--;
                if (psyDepth == 0 && cur.length() >= minLen) {
                    result.add(cur.toString().trim());
                    cur.setLength(0);
                }
                continue;
            }
            if (ch == '（' || ch == '(') { actDepth++; continue; }
            if (ch == '）' || ch == ')') { if (actDepth > 0) actDepth--; continue; }
            if (psyDepth > 0 || actDepth > 0) continue;
            boolean boundary = "。！？!?；;\n".indexOf(ch) >= 0;
            if (boundary && cur.length() >= minLen) {
                result.add(cur.toString().trim());
                cur.setLength(0);
            }
        }
        String last = cur.toString().trim();
        if (!last.isEmpty()) result.add(last);
        if (result.size() >= 2 && result.get(result.size() - 1).length() < minLen) {
            result.set(result.size() - 2, result.get(result.size() - 2) + result.get(result.size() - 1));
            result.remove(result.size() - 1);
        }
        return result;
    }

    @GetMapping("/test/history")
    public Result testHistory() {
        return Result.success(testHistoryMapper.findRecent());
    }

    // ===== AI 历史管理 =====
    @GetMapping("/history")
    public Result listHistory() {
        return Result.success(aiHistoryMapper.findAll());
    }

    @PutMapping("/history/star/{id}")
    public Result toggleStar(@PathVariable Integer id, @RequestParam Integer starred) {
        aiHistoryMapper.updateStarred(id, starred);
        return Result.ok("ok");
    }

    @PutMapping("/history")
    public Result updateHistory(@RequestBody Map<String, Object> record) {
        aiHistoryMapper.update(record);
        return Result.ok("ok");
    }

    // ===== 风格语料库管理（阶段四） =====
    @GetMapping("/style-exemplars")
    public Result listStyleExemplars() {
        return Result.success(styleExemplarMapper.findAll());
    }

    @PostMapping("/style-exemplars")
    public Result addStyleExemplar(@RequestBody Map<String, String> req) {
        String tags = req.get("tags");
        String content = req.get("content");
        if (content == null || content.trim().isEmpty()) return Result.error("语料内容不能为空");
        if (tags == null || tags.trim().isEmpty()) return Result.error("触发标签不能为空");
        com.cy.pojo.StyleExemplar e = new com.cy.pojo.StyleExemplar();
        e.setTags(tags.trim());
        e.setContent(content.trim());
        styleExemplarMapper.insert(e);
        return Result.ok("语料已保存（id=" + e.getId() + "）");
    }

    @DeleteMapping("/style-exemplars/{id}")
    public Result deleteStyleExemplar(@PathVariable Integer id) {
        styleExemplarMapper.deleteById(id);
        return Result.ok("已删除");
    }

    @GetMapping("/danmaku/stream")
    public SseEmitter danmakuStream() {
        SseEmitter emitter = new SseEmitter(0L);
        DanmakuService.DanmakuListener listener = new DanmakuService.DanmakuListener() {
            @Override
            public void onMessage(String username, String message, boolean hasMedal) {
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "danmaku");
                    data.put("username", username);
                    data.put("message", message);
                    data.put("hasMedal", hasMedal);
                    emitter.send(SseEmitter.event().data(data));
                    System.out.println("[弹幕SSE] 已推送: " + username + " -> " + message);
                } catch (Exception e) {
                    System.out.println("[弹幕SSE] 推送失败: " + e.getMessage());
                    emitter.completeWithError(e);
                }
            }
            @Override
            public String id() { return emitter.toString(); }
        };
        DanmakuService.EnterListener enterListener = new DanmakuService.EnterListener() {
            @Override
            public void onEnter(String username, int uid) {
                try {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("type", "enter");
                    data.put("username", username);
                    data.put("uid", uid);
                    emitter.send(SseEmitter.event().data(data));
                    System.out.println("[进入SSE] 已推送: " + username);
                } catch (Exception e) {
                    System.out.println("[进入SSE] 推送失败: " + e.getMessage());
                }
            }
        };
        danmakuService.addListener(listener);
        danmakuService.addEnterListener(enterListener);
        emitter.onCompletion(() -> { danmakuService.removeListener(listener); danmakuService.removeEnterListener(enterListener); });
        emitter.onTimeout(() -> { danmakuService.removeListener(listener); danmakuService.removeEnterListener(enterListener); });
        return emitter;
    }

    @GetMapping("/seed-rules")
    public Result seedRulesGet() { return seedRules(); }

    @PostMapping("/seed-rules")
    public Result seedRules() {
        List<AiConfig> configs = aiConfigMapper.findAll();
        if (configs.isEmpty()) return Result.error("未配置AI");
        AiConfig cfg = configs.get(0);
        if (cfg.getApi_key() == null || cfg.getApi_key().isEmpty()) return Result.error("未配置API Key");

        String prompt = "生成30条阿罗娜的回复，格式：样本回复|关键词|表情|音效|特效\n" +
            "关键词是回复中实际出现的短语（如\"才没有\"\"老师笨蛋\"\"好开心\"）\n" +
            "音效：haixiu.webm(害羞) jingya.webm(惊讶) liuhan.webm(流汗/无语) shengqi.webm(生气) shuohua.webm(说话) wenhao.webm(问号，也可表惊讶) wuyu.webm(无语) xihuan.webm(喜欢) yiwen.webm(疑问) 无\n" +
            "动作：bounce(蹦跳) drop(沮丧垂落) swing(摇晃/害羞拒绝) spin(转1圈) spin2(转2圈) spin3(转3圈) tilt(歪头/困惑) escape(害羞逃跑) 无\n" +
            "可选表情:aluona_haixiu.png aluona_jidong.png（多数积极情绪可用，表现活力） aluona_kunhuo.png" +
            "aluona_shengqi.png aluona_swkl.png（生无可恋：非常生气或假装威胁） aluona_qidai.png（双眼放光，多数时与_qidai相同） " +
            "aluona_kangju.png（脸红咬牙/被作弄了又没有办法） aluona_huaiyi.png（大多数负面情绪可用/怀疑，拒绝，严肃） aluona_zhengjing.png（非常震惊，害怕，双眼空白） aluona_zhengchang.png aluona_kaixin.png\n" +
            "示例：\n" +
            "诶？！才没有偷吃呢！|才没有|aluona_haixiu.png|haixiu.webm|bounce\n" +
            "老师笨蛋！不理你了！|老师笨蛋|aluona_shengqi.png|shengqi.webm|swing\n" +
            "嘿嘿发现超好玩的游戏～|超好玩|aluona_jidong.png|无|无\n" +
            "唔...老师说的有道理...|有道理|aluona_kunhuo.png|wenhao.webm|无\n" +
            "所有30条的关键词必须各不相同！只输出规则行：";

        try {
            String body = "{\"model\":\"" + (cfg.getModel() != null ? cfg.getModel() : "deepseek-chat") + "\"" +
                    ",\"max_tokens\":2048,\"temperature\":0.8" +
                    ",\"messages\":[{\"role\":\"user\",\"content\":" + toJsonStr(prompt) + "}]}";

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(cfg.getApi_key());
            HttpEntity<String> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> resp = restTemplate.postForEntity(
                    cfg.getApi_url() != null ? cfg.getApi_url() : "https://api.deepseek.com/v1/chat/completions",
                    entity, Map.class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> choices = (List<Map<String, Object>>) resp.getBody().get("choices");
                if (choices != null && !choices.isEmpty()) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> msg = (Map<String, Object>) choices.get(0).get("message");
                    String content = (String) msg.get("content");
                    int count = 0;
                    Set<String> seen = new HashSet<>();
                    for (String line : content.split("\n")) {
                        line = line.trim();
                        if (line.isEmpty() || !line.contains("|")) continue;
                        String[] parts = line.split("\\|");
                        if (parts.length >= 3) {
                            String kw = parts[1].trim();
                            if (seen.contains(kw)) continue; // 跳过重复关键词
                            seen.add(kw);
                            ResponseRule rule = new ResponseRule();
                            rule.setSample_text(parts[0].trim());
                            rule.setKeyword(kw);
                            rule.setMotion(parts[2].trim().replaceAll("[^a-zA-Z0-9_.]", ""));
                            if (parts.length >= 4 && !parts[3].trim().equals("无")) rule.setSound_effect(parts[3].trim());
                            if (parts.length >= 5 && !parts[4].trim().equals("无")) rule.setSpecial_effect(parts[4].trim());
                            rule.setWeight(1);
                            try { responseRuleMapper.insertOrUpdate(rule); count++; } catch (Exception ignored) {}
                        }
                    }
                    return Result.ok("播种完成，新增 " + count + " 条规则");
                }
            }
            return Result.error("AI 返回异常");
        } catch (Exception e) {
            return Result.error("播种失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/history/{id}")
    public Result deleteHistory(@PathVariable Integer id) {
        aiHistoryMapper.deleteById(id);
        return Result.ok("ok");
    }

    private String toJsonStr(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }

    // 本地曲库接口已移除（听歌统一走网易云；static/music 目录已删除）

    @GetMapping("/music/search")
    public Result searchMusic(@RequestParam String keyword) {
        return Result.success(neteaseService.search(keyword));
    }

    @GetMapping("/music/url")
    public Result getMusicUrl(@RequestParam String id, @RequestParam(required = false) String level) {
        String url = neteaseService.getPlayUrl(id, level);
        return url != null ? Result.success(url) : Result.error("获取播放链接失败");
    }

    @PostMapping("/consolidate")
    public Result consolidateMemory(@RequestBody Map<String, String> req) {
        String charName = req.get("character");
        if (charName == null || charName.isEmpty()) {
            try {
                BasicInfo aiInfo = basicInfoMapper.findById(1);
                charName = (aiInfo != null && aiInfo.get姓名() != null) ? aiInfo.get姓名() : "阿罗娜";
            } catch (Exception e) { charName = "阿罗娜"; }
        }
        String sid = req.get("session_id") != null ? req.get("session_id") : "default";
        final String cn = charName;
        new Thread(() -> deepSeekService.consolidateMemory(cn, sid)).start();
        return Result.ok("记忆合并已触发");
    }

    @PostMapping("/game-summary")
    public Result gameSummary(@RequestBody Map<String, String> req) {
        String charName = req.get("character");
        if (charName == null || charName.isEmpty()) {
            try {
                BasicInfo aiInfo = basicInfoMapper.findById(1);
                charName = (aiInfo != null && aiInfo.get姓名() != null) ? aiInfo.get姓名() : "阿罗娜";
            } catch (Exception e) { charName = "阿罗娜"; }
        }
        String sid = req.get("session_id") != null ? req.get("session_id") : "default";
        final String cn = charName;
        new Thread(() -> deepSeekService.gameSummary(cn, sid)).start();
        return Result.ok("游戏总结已触发");
    }

    /** 清理遗留的 game 会话历史（桌宠页面启动/游戏前调用，防止中途退出残留堆积） */
    @PostMapping("/game-history/clean")
    public Result cleanGameHistory() {
        try {
            int n = aiHistoryMapper.deleteBySessionPrefix("game_%");
            return Result.ok("已清理遗留游戏会话 " + n + " 条");
        } catch (Exception e) {
            return Result.error("清理失败: " + e.getMessage());
        }
    }

    @GetMapping("/game-expression")
    public Result gameExpression(@RequestParam String scene) {
        Conservation c = conservationMapper.findReaction(scene, null);
        String motion = (c != null && c.getMotion() != null && !c.getMotion().isEmpty())
            ? c.getMotion() : "aluona_zhengchang.png";
        return Result.success(motion);
    }

    @GetMapping("/netease/like")
    public Result likeSong(@RequestParam String id) {
        try {
            String json = restTemplate.getForObject("http://localhost:3000/like?id=" + id, String.class);
            return Result.success(new ObjectMapper().readTree(json));
        } catch (Exception e) {
            return Result.error("收藏失败");
        }
    }

    @GetMapping("/music/recommend")
    public Result recommendMusic() {
        return Result.success(neteaseService.getHotSongs());
    }

    @GetMapping("/music/ai-recommend")
    public Result aiRecommendMusic(@RequestParam(required = false) String exclude) {
        return Result.success(deepSeekService.recommendSongs(exclude));
    }

    @GetMapping("/music/similar")
    public Result similarMusic(@RequestParam String id) {
        return Result.success(neteaseService.getSimilar(id));
    }

    // 播放收藏歌单：读取 netease_uid，从网易云拉取"我喜欢的音乐"歌单曲目
    @GetMapping("/music/favorite")
    public Result favoriteMusic() {
        try {
            List<AiConfig> configs = aiConfigMapper.findAll();
            String uid = (configs != null && !configs.isEmpty()) ? configs.get(0).getNetease_uid() : null;
            if (uid == null || uid.trim().isEmpty()) return Result.error("未配置网易云 UID（ai_config.netease_uid）");
            // 本机 API：/user/playlist?uid= 返回用户歌单对象数组；/playlist/songs?id= 返回 [{id,name,artists}]
            String playlistsJson = restTemplate.getForObject("http://localhost:3000/user/playlist?uid=" + uid.trim(), String.class);
            com.fasterxml.jackson.databind.JsonNode plRoot = new ObjectMapper().readTree(playlistsJson);
            String favId = null;
            for (com.fasterxml.jackson.databind.JsonNode p : plRoot) {
                int specialType = p.has("specialType") ? p.get("specialType").asInt() : 0;
                String name = p.has("name") ? p.get("name").asText() : "";
                if (specialType == 5 || name.contains("我喜欢的音乐")) { favId = p.get("id").asText(); break; }
            }
            if (favId == null) return Result.error("未找到「我喜欢的音乐」歌单");
            String songsJson = restTemplate.getForObject("http://localhost:3000/playlist/songs?id=" + favId, String.class);
            com.fasterxml.jackson.databind.JsonNode songs = new ObjectMapper().readTree(songsJson);
            List<Map<String, Object>> result = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode s : songs) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", s.get("id").asText());
                m.put("name", s.get("name").asText());
                m.put("artist", s.has("artists") ? s.get("artists").asText() : "");
                result.add(m);
            }
            return Result.success(result);
        } catch (Exception e) {
            System.out.println("[音乐] 收藏歌单获取失败: " + e.getMessage());
            return Result.error("获取收藏歌单失败");
        }
    }

    @GetMapping("/rules")
    public Result listRules() { return Result.success(responseRuleMapper.findAll()); }

    @PutMapping("/rules")
    public Result updateRule(@RequestBody Map<String, Object> body) {
        Integer id = (Integer) body.get("id");
        if (id == null) return Result.error("缺少id");
        ResponseRule rule = new ResponseRule();
        rule.setId(id);
        rule.setKeyword((String) body.get("keyword"));
        rule.setMotion((String) body.get("motion"));
        rule.setSound_effect((String) body.get("sound_effect"));
        rule.setSpecial_effect((String) body.get("special_effect"));
        responseRuleMapper.updateById(rule);
        return Result.ok("ok");
    }

    @DeleteMapping("/rules/{id}")
    public Result deleteRule(@PathVariable Integer id) {
        responseRuleMapper.delete(id);
        return Result.ok("ok");
    }

    // ===== 闲置活动库 =====
    @GetMapping("/idle-activities")
    public Result listIdleActivities() { return Result.success(idleActivityMapper.findAll()); }

    @GetMapping("/idle-activities/enabled")
    public Result listEnabledIdleActivities() { return Result.success(idleActivityMapper.findEnabled()); }

    @PostMapping("/idle-activities")
    public Result addIdleActivity(@RequestBody Map<String, Object> body) {
        IdleActivity a = new IdleActivity();
        a.setTheme((String) body.get("theme"));
        a.setCategory((String) body.get("category"));
        a.setEnabled(toInt(body.get("enabled"), 1));
        if (a.getTheme() == null || a.getTheme().trim().isEmpty()) return Result.error("活动描述不能为空");
        if (a.getCategory() == null || a.getCategory().trim().isEmpty()) a.setCategory("simple");
        idleActivityMapper.insert(a);
        return Result.ok("ok");
    }

    @PutMapping("/idle-activities")
    public Result updateIdleActivity(@RequestBody Map<String, Object> body) {
        Integer id = toInt(body.get("id"), null);
        if (id == null) return Result.error("缺少id");
        IdleActivity a = new IdleActivity();
        a.setId(id);
        a.setTheme((String) body.get("theme"));
        a.setCategory((String) body.get("category"));
        a.setEnabled(toInt(body.get("enabled"), 1));
        idleActivityMapper.update(a);
        return Result.ok("ok");
    }

    @DeleteMapping("/idle-activities/{id}")
    public Result deleteIdleActivity(@PathVariable Integer id) {
        idleActivityMapper.delete(id);
        return Result.ok("ok");
    }

    private Integer toInt(Object o, Integer def) {
        if (o == null) return def;
        try { return Integer.parseInt(o.toString()); } catch (Exception e) { return def; }
    }

    // ===== 事件簿：记录"非对话 AI"型交互（摸头/点击/牛奶/听歌/游戏/开关等），供对话上下文引用 =====
    @PostMapping("/event-book")
    public Result addEventBook(@RequestBody Map<String, Object> body) {
        try {
            String type = String.valueOf(body.getOrDefault("event_type", "other"));
            String content = String.valueOf(body.getOrDefault("content", ""));
            if (content.isEmpty()) return Result.error("事件内容为空");
            com.cy.pojo.EventBook e = new com.cy.pojo.EventBook();
            e.setEvent_type(type);
            e.setContent(content);
            eventBookMapper.insert(e);
            eventBookMapper.trim(50); // 保留最近 50 条
            return Result.ok("已记录事件");
        } catch (Exception e) {
            return Result.error("记录失败: " + e.getMessage());
        }
    }

    /** 最近事件（默认 5 条，按时间倒序），供页面/对话上下文展示 */
    @GetMapping("/event-book/recent")
    public Result recentEventBook(@RequestParam(defaultValue = "5") Integer limit) {
        int n = Math.max(1, Math.min(50, limit == null ? 5 : limit));
        return Result.success(eventBookMapper.findRecent(n));
    }

    /** 当前服务器时间（供 AI 感知"现在几点"） */
    @GetMapping("/now")
    public Result now() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("time", java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        return Result.success(out);
    }

    // ===== 桌宠"眼睛高度"比例存取（视线/头部跟随垂直零点用；文件持久化，跨页面/跨源可靠） =====
    private static final File EYE_RATIO_FILE = new File(System.getProperty("user.dir"), "pet_eye_ratio.txt");

    @GetMapping("/pet-eye-ratio")
    public Result getPetEyeRatio() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ratio", null);
        try {
            if (EYE_RATIO_FILE.exists()) {
                String s = new String(java.nio.file.Files.readAllBytes(EYE_RATIO_FILE.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) out.put("ratio", Double.parseDouble(s));
            }
        } catch (Exception e) { /* ignore */ }
        return Result.success(out);
    }

    @PostMapping("/pet-eye-ratio")
    public Result setPetEyeRatio(@RequestBody Map<String, Object> body) {
        try {
            Object v = body.get("ratio");
            double ratio = v instanceof Number ? ((Number) v).doubleValue()
                    : Double.parseDouble(String.valueOf(v));
            java.nio.file.Files.write(EYE_RATIO_FILE.toPath(),
                    String.valueOf(ratio).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Result.ok("已保存眼睛高度 " + Math.round(ratio * 100) + "%");
        } catch (Exception e) {
            return Result.error("保存失败: " + e.getMessage());
        }
    }
}
