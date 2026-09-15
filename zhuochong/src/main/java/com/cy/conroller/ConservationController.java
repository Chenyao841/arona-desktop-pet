package com.cy.conroller;

import com.cy.mapper.AiHistoryMapper;
import com.cy.mapper.BasicInfoMapper;
import com.cy.mapper.EffectMapper;
import com.cy.mapper.ResponseRuleMapper;
import com.cy.mapper.Live2dRuleMapper;
import com.cy.pojo.BasicInfo;
import com.cy.pojo.Conservation;
import com.cy.pojo.ConversationChoice;
import com.cy.pojo.Effect;
import com.cy.pojo.ResponseRule;
import com.cy.pojo.Live2dRule;
import com.cy.pojo.Result;
import com.cy.pojo.user_operation;
import com.cy.service.ConversationService;
import com.cy.service.DeepSeekService;
import com.cy.service.OcrService;
import com.cy.utils.JWTUTILL;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

@RestController
public class ConservationController {

    @Autowired
    private ConversationService conversationService;

    @Autowired
    private EffectMapper effectMapper;

    @Autowired
    private BasicInfoMapper basicInfoMapper;

    @Autowired
    private AiHistoryMapper aiHistoryMapper;

    @Autowired
    private DeepSeekService deepSeekService;

    @Autowired
    private OcrService ocrService;

    @Autowired
    private ResponseRuleMapper responseRuleMapper;

    @Autowired
    private Live2dRuleMapper live2dRuleMapper;

    private String extractKeyword(String text) {
        if (text == null) return null;
        String[] keywords = {"生气", "难过", "开心", "害羞", "激动", "困惑", "嫌弃", "得意",
                "期待", "抗拒", "怀疑", "认真", "日常", "警告", "提醒", "喜欢", "害怕"};
        for (String kw : keywords) {
            if (text.contains(kw)) return kw;
        }
        // 疑问句式提炼
        if (text.contains("吗") && text.contains("？")) return "是...吗";
        return null; // 提炼不出关键词时返回 null，不创建规则
    }

    // 判断文本是否表达"要睡觉"语义（AI 有时说了睡觉的话却漏掉 sleep 参数，做兜底）
    private boolean isSleepText(String text) {
        if (text == null) return false;
        if (text.contains("睡不着") || text.contains("没睡") || text.contains("不想睡") || text.contains("别睡") || text.contains("失眠")) return false;
        String t = text.toLowerCase();
        return t.contains("睡") || t.contains("打盹") || t.contains("小憩")
                || t.contains("哈欠") || t.contains("zzz") || t.contains("养足精神");
    }

    // 从文本里解析睡眠秒数（如"睡个十分钟"），解析不到则默认 600 秒（十分钟）
    private Integer parseSleepSeconds(String text) {
        if (text == null) return 600;
        java.util.regex.Matcher mh = java.util.regex.Pattern.compile("(\\d+)\\s*小时").matcher(text);
        if (mh.find()) { try { return Math.min(1800, Integer.parseInt(mh.group(1)) * 3600); } catch (Exception ignored) {} }
        java.util.regex.Matcher mm = java.util.regex.Pattern.compile("(\\d+)\\s*分钟").matcher(text);
        if (mm.find()) { try { return Math.min(1800, Math.max(120, Integer.parseInt(mm.group(1)) * 60)); } catch (Exception ignored) {} }
        java.util.regex.Matcher ms = java.util.regex.Pattern.compile("(\\d+)\\s*秒").matcher(text);
        if (ms.find()) { try { return Math.min(1800, Math.max(120, Integer.parseInt(ms.group(1)))); } catch (Exception ignored) {} }
        return 600;
    }

    // 短词生长：命中 2-3 字短词时，按冷却 + 概率调用 AI 把短词扩展成更长更精准的关键词
    private void tryGrowKeyword(ResponseRule rule, String shortKw, String text) {
        try {
            int grow = rule.getGrow_count() != null ? rule.getGrow_count() : 0;
            int cd = rule.getCooldown() != null ? rule.getCooldown() : 0;
            if (cd > 0) {
                // 冷却中：递减冷却计数
                rule.setCooldown(cd - 1);
                responseRuleMapper.updateGrowState(rule);
                return;
            }
            // 概率：生长成功次数越多，触发概率越低（首次必触发一次）
            double p = 1.0 / (1.0 + grow);
            if (Math.random() >= p) return;
            DeepSeekService.AiResult g = deepSeekService.growKeyword(shortKw, text);
            int newGrow = grow;
            if (g != null && g.boat_text != null && !g.boat_text.trim().isEmpty()
                    && g.boat_text.trim().length() >= 3 && g.boat_text.trim().length() <= 5
                    && g.boat_text.contains(shortKw)) {
                String newKw = g.boat_text.trim();
                List<ResponseRule> existing = responseRuleMapper.findByKeyword(newKw);
                boolean exists = existing != null && !existing.isEmpty();
                if (!exists) {
                    ResponseRule newRule = new ResponseRule();
                    newRule.setKeyword(newKw);
                    newRule.setMotion(g.motion != null ? g.motion : rule.getMotion());
                    newRule.setSound_effect(rule.getSound_effect());
                    newRule.setSpecial_effect(rule.getSpecial_effect());
                    newRule.setSample_text(text);
                    responseRuleMapper.insertOrUpdate(newRule);
                    System.out.println("[规则] 短词生长成功: " + shortKw + " → " + newKw);
                }
                newGrow = grow + 1;
            }
            // 无论成功失败，重置冷却为 3 次命中
            rule.setGrow_count(newGrow);
            rule.setCooldown(3);
            responseRuleMapper.updateGrowState(rule);
        } catch (Exception e) {
            System.out.println("[规则] 短词生长异常: " + e.getMessage());
        }
    }

    // 长词优先匹配：返回命中的表情，匹配不到返回 fallback
    private String matchMotion(String text, String fallback) {
        try {
            List<ResponseRule> allRules = responseRuleMapper.findAll();
            allRules.sort((a, b) -> Integer.compare(
                (b.getKeyword() != null ? b.getKeyword().length() : 0),
                (a.getKeyword() != null ? a.getKeyword().length() : 0)));
            for (ResponseRule rule : allRules) {
                String kw = rule.getKeyword();
                if (kw != null && !kw.isEmpty() && text.contains(kw) && rule.getMotion() != null) {
                    return rule.getMotion();
                }
            }
        } catch (Exception e) {}
        return fallback;
    }

    // 按句切分：\n\n 强制切段，段内按句末标点切句（含 ～…~：— 等语气/犹豫/引述/长音符，避免一句话过长超过 3 行气泡），短句(<5字)合并防止表情切换过频
    // 括号规则：① 【】内心OS 内部不切句（否则「！？」会被当句末，把 』 切到下一句开头）；
    //          ② 】 本身作为句边界，让「【…】」完整独立成一句/一行；
    //          ③ （）动作·表情说明内部同样不切句（说明跟随其后台词，不在 ） 处断句）
    private List<String> splitToSentences(String text) {
        List<String> result = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) return result;
        String[] paragraphs = text.split("\\n\\n");
        final String SPLIT = "。！？!?；;…～~：—";
        for (String para : paragraphs) {
            List<String> raw = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            int psyDepth = 0;   // 【】深度：>0 表示在内心OS内部
            int actDepth = 0;   // （）深度：>0 表示在动作/表情说明内部
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                cur.append(ch);
                if (ch == '【') { psyDepth++; continue; }
                if (ch == '】') {
                    if (psyDepth > 0) psyDepth--;
                    if (psyDepth == 0) {
                        // 内心OS 收尾 = 句边界；顺手吃掉紧跟的连续标点，避免「】！」被拆开
                        while (i + 1 < para.length() && SPLIT.indexOf(para.charAt(i + 1)) >= 0) {
                            cur.append(para.charAt(++i));
                        }
                        String s = cur.toString().trim();
                        if (!s.isEmpty()) raw.add(s);
                        cur.setLength(0);
                    }
                    continue;
                }
                if (ch == '（' || ch == '(') { actDepth++; continue; }
                if (ch == '）' || ch == ')') { if (actDepth > 0) actDepth--; continue; }
                if (psyDepth > 0 || actDepth > 0) continue;   // 括号内不做句切分
                if (SPLIT.indexOf(ch) >= 0) {
                    // 连续符号整体切分：下一个字符也是切分符号时暂不切，等最后一个符号后再切，避免符号（如 ！？、————）被拆到下一行开头
                    if (i + 1 < para.length() && SPLIT.indexOf(para.charAt(i + 1)) >= 0) {
                        continue;
                    }
                    String s = cur.toString().trim();
                    if (!s.isEmpty()) raw.add(s);
                    cur.setLength(0);
                }
            }
            String last = cur.toString().trim();
            if (!last.isEmpty()) raw.add(last);
            // 短句合并：按"去括号后"的实际朗读长度累积到 >=5 字才输出（括号内表情/动作不朗读、不参与长度），末尾残留并入最后一句；避免过短语气词（如「诶？！」）单独送 TTS 触发回读
            StringBuilder pending = new StringBuilder();
            for (String s : raw) {
                pending.append(s);
                String spoken = pending.toString().replaceAll("[（(][^）)]*[）)]", "");
                if (spoken.length() >= 5) {
                    result.add(pending.toString());
                    pending.setLength(0);
                }
            }
            if (pending.length() > 0) {
                if (result.isEmpty()) result.add(pending.toString());
                else result.set(result.size() - 1, result.get(result.size() - 1) + pending.toString());
            }
        }
        return result;
    }

    // 情绪词 → 表情文件（1:1 映射，阶段三：AI 语义标签兜底，后续可扩成表情池）
    private static final Map<String, String> EMOTION_MOTION = new HashMap<>();
    static {
        EMOTION_MOTION.put("开心", "aluona_kaixin.png");
        EMOTION_MOTION.put("困惑", "aluona_kunhuo.png");
        EMOTION_MOTION.put("激动", "aluona_jidong.png");
        EMOTION_MOTION.put("害羞", "aluona_haixiu.png");
        EMOTION_MOTION.put("生气", "aluona_shengqi.png");
        EMOTION_MOTION.put("认真", "aluona_zhengjing.png");
        EMOTION_MOTION.put("期待", "aluona_qidai.png");
        EMOTION_MOTION.put("怀疑", "aluona_huaiyi.png");
        EMOTION_MOTION.put("慌张", "aluona_huangzhang.png");
        EMOTION_MOTION.put("难过", "aluona_nanguo.png");
    }
    private String emotionToMotion(String emotion) {
        return emotion != null ? EMOTION_MOTION.get(emotion.trim()) : null;
    }

    // 构建句级表情流：每句先匹配规则库，其次用 AI 情绪词兜底，最后按句末语气/标点兜底；相邻句不重复
    private List<Map<String, String>> buildSentenceFlow(String text, String defaultMotion, String aiEmotion) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> sentences = splitToSentences(text);
        String aiMotion = emotionToMotion(aiEmotion); // AI 情绪词 → 表情（作为规则库未命中时的兜底）
        String prevMotion = null;
        for (String s : sentences) {
            String motion = matchMotion(s, null);
            if (motion == null) {
                if (aiMotion != null) motion = aiMotion;
                else motion = guessMotionByTone(s, defaultMotion);
            }
            // 相邻句不重复：与上句同表情时，改用整体默认表情（或日常表情）避免呆板
            if (motion != null && motion.equals(prevMotion)) {
                motion = (defaultMotion != null && !defaultMotion.equals(prevMotion)) ? defaultMotion : "aluona_zhengchang.png";
            }
            prevMotion = motion;
            Map<String, String> m = new LinkedHashMap<>();
            m.put("text", s);
            m.put("motion", motion);
            result.add(m);
        }
        return result;
    }

    // 按句末语气/标点兜底表情（规则库未命中时，避免所有句子都锁死成同一表情）
    private String guessMotionByTone(String text, String fallback) {
        if (text == null) return fallback;
        if (text.contains("？") || text.contains("?")) return "aluona_kunhuo.png"; // 疑问
        if (text.contains("！") || text.contains("!")) return "aluona_jidong.png"; // 感叹/激动
        if (text.contains("～") || text.contains("~")) return "aluona_kaixin.png"; // 撒娇/开心
        if (text.contains("…")) return "aluona_huaiyi.png"; // 犹豫
        return fallback;
    }

    // Live2D 情境匹配：按 ,; 拆词精确匹配（命中返回规则，未命中返回 null）
    private Live2dRule matchLive2dRule(String situation) {
        if (situation == null || situation.trim().isEmpty()) return null;
        String s = situation.trim();
        try {
            for (Live2dRule r : live2dRuleMapper.findAll()) {
                String st = r.getSituation();
                if (st == null) continue;
                for (String token : st.split("[,;，；]")) {
                    if (token.trim().equals(s)) return r;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // 未命中时新建规则（situation 存词，表情/动作/音效留空，待人工维护）
    private Live2dRule ensureLive2dRule(String situation) {
        try {
            Live2dRule r = new Live2dRule();
            r.setSituation(situation);
            r.setExpression("{}");
            live2dRuleMapper.insert(r);
            return r;
        } catch (Exception ignored) {}
        return null;
    }

    // Live2D 句级流程：逐句 {text, situation, expression, action, sound_effect}
    private List<Map<String, String>> buildLive2dSentenceFlow(String text, List<String> situations) {
        List<Map<String, String>> result = new ArrayList<>();
        List<String> sentences = splitToSentences(text);
        for (int i = 0; i < sentences.size(); i++) {
            String situation = (situations != null && i < situations.size()) ? situations.get(i) : null;
            Live2dRule rule = matchLive2dRule(situation);
            if (rule == null && situation != null && !situation.isEmpty()) {
                rule = ensureLive2dRule(situation);
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("text", sentences.get(i));
            m.put("situation", situation != null ? situation : "");
            m.put("expression", rule != null && rule.getExpression() != null ? rule.getExpression() : "{}");
            m.put("action", rule != null && rule.getAction() != null ? rule.getAction() : "");
            m.put("sound_effect", rule != null && rule.getSound_effect() != null ? rule.getSound_effect() : "");
            result.add(m);
        }
        return result;
    }

    // AI 未给情境时的兜底：按句末语气猜情境词
    private List<String> guessSituations(String text) {
        List<String> result = new ArrayList<>();
        for (String s : splitToSentences(text)) {
            String sit = "日常";
            if (s.contains("？") || s.contains("?")) sit = "困惑";
            else if (s.contains("！") || s.contains("!")) sit = "开心";
            else if (s.contains("～") || s.contains("~")) sit = "开心";
            else if (s.contains("…")) sit = "犹豫";
            result.add(sit);
        }
        return result;
    }

    @GetMapping("/conversation")
    public Result query(@RequestParam("user_id") String userId) {
        List<Conservation> list = conversationService.findByUserId(userId);
        return Result.success(list);
    }

    @GetMapping("/basic-responses")
    public Result listBasicResponses() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Conservation c : conversationService.findBasicResponses()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", "conv_" + c.getId());
            m.put("user_operation", c.getUser_operation());
            m.put("boat_text", c.getBoat_text());
            result.add(m);
        }
        for (ConversationChoice ch : conversationService.findBasicChoices()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", "choice_" + ch.getId());
            m.put("user_operation", ch.getUserOperation());
            m.put("boat_text", ch.getBoatText());
            result.add(m);
        }
        return Result.success(result);
    }

    @PostMapping("/seed-game-expressions")
    public Result seedGameExpressions() {
        // 已有游戏表情则跳过，避免重复
        if (conversationService.findReaction("game_ask", null) != null) {
            return Result.ok("游戏表情已存在，无需重复 seed");
        }
        String[][] data = {
            {"game_ask", "aluona_kunhuo.png"},
            {"game_ask", "aluona_qidai.png"},
            {"game_ask", "aluona_zhengjing.png"},
            {"game_guess", "aluona_jidong.png"},
            {"game_guess", "aluona_huaiyi.png"},
            {"game_guess", "aluona_kaixin.png"},
            {"game_wrong", "aluona_shengqi.png"},
            {"game_wrong", "aluona_kunhuo.png"}
        };
        for (String[] d : data) {
            Conservation c = new Conservation();
            c.setUser_id("-1");
            c.setUser_text("");
            c.setBoat_text("");
            c.setMotion(d[1]);
            c.setUser_operation(d[0]);
            c.setRole("basic");
            conversationService.addConversation(c);
        }
        return Result.ok("已 seed " + data.length + " 条游戏表情");
    }

    @PostMapping("/conversation")
    public Result add(@RequestBody Conservation conservation) {
        conversationService.addConversation(conservation);
        return Result.success(conservation);
    }

    @PutMapping("/conversation")
    public Result update(@RequestBody Conservation conservation, HttpServletRequest request) {
        Conservation origin = conversationService.findById(conservation.getId());
        if (origin != null && isEmployee(request) && !isOwnData(origin.getUser_id(), request)) {
            return Result.error("权限不足！员工只能修改自己的对话数据");
        }
        conversationService.updateConversation(conservation);
        Conservation reaction = conversationService.findReaction("change_conversation", null);
        if (reaction != null) {
            return Result.reaction(reaction.getBoat_text(), reaction.getMotion());
        }
        return Result.ok("对话更新成功！");
    }

    @DeleteMapping("/conversation/{id}")
    public Result delete(@PathVariable Integer id, HttpServletRequest request) {
        Conservation c = conversationService.findById(id);
        if (c != null && isEmployee(request) && !isOwnData(c.getUser_id(), request)) {
            return Result.error("权限不足！员工只能删除自己的对话数据");
        }
        conversationService.deleteConversation(id);
        return Result.ok("对话删除成功！");
    }

    @PostMapping("/reaction")
    public Result reaction(@RequestBody user_operation op) {
        // role=basic 对话行配置（click/hover/rub/milk 等交互的全局响应）
        Conservation c = conversationService.findReaction(op.getUser_operation(), op.getUser_text());
        if (c != null && c.getBoat_text() != null) {
            List<ConversationChoice> choices = conversationService.findChoicesByChoiceId(c.getChoice_id());
            Result r = choices != null && !choices.isEmpty()
                ? Result.reaction(c.getBoat_text(), c.getMotion(), choices)
                : Result.reaction(c.getBoat_text(), c.getMotion());
            r.setSoundEffect(c.getSound_effect());
            r.setSpecialEffect(c.getSpecial_effect());
            return r;
        }
        return Result.reaction("嗯哼哼————", "aluona_kaixin.png");
    }

    @GetMapping("/idle")
    public Result idle() {
        Conservation c = conversationService.findIdle();
        if (c != null && c.getBoat_text() != null) {
            List<ConversationChoice> choices = conversationService.findChoicesByChoiceId(c.getChoice_id());
            Result r = choices != null && !choices.isEmpty()
                ? Result.reaction(c.getBoat_text(), c.getMotion(), choices)
                : Result.reaction(c.getBoat_text(), c.getMotion());
            r.setSoundEffect(c.getSound_effect());
            r.setSpecialEffect(c.getSpecial_effect());
            return r;
        }
        return Result.reaction("嗯哼哼————", "aluona_kaixin.png");
    }

    @PostMapping("/choice")
    public Result addChoice(@RequestParam Integer conversation_id,
                             @RequestParam(defaultValue = "choice") String choice_text,
                             @RequestParam(defaultValue = "act") String choice_action,
                             @RequestParam(defaultValue = "新对话") String boat_text,
                             @RequestParam(defaultValue = "aluona_kaixin.png") String motion) {
        ConversationChoice cc = new ConversationChoice();
        cc.setChoiceText(choice_text);
        cc.setChoiceAction(choice_action);
        cc.setBoatText(boat_text);
        cc.setMotion(motion);
        conversationService.addChoice(conversation_id, cc);
        return Result.ok("选项添加成功！");
    }

    @GetMapping("/choice")
    public Result queryChoices(@RequestParam Integer conversation_id) {
        Conservation conv = conversationService.findById(conversation_id);
        if (conv != null && conv.getChoice_id() != null && conv.getChoice_id() > 0) {
            List<ConversationChoice> choices = conversationService.findChoicesByChoiceId(conv.getChoice_id());
            if (choices != null && !choices.isEmpty()) {
                return Result.success(choices);
            }
        }
        Conservation c = conversationService.findReaction("search_choiceFail", null);
        if (c != null) {
            List<ConversationChoice> failChoices = conversationService.findChoicesByChoiceId(c.getChoice_id());
            return failChoices != null && !failChoices.isEmpty()
                ? Result.reaction(c.getBoat_text(), c.getMotion(), failChoices)
                : Result.reaction(c.getBoat_text(), c.getMotion());
        }
        return Result.reaction("暂无对话选项", "aluona_huaiyi.png");
    }

    @DeleteMapping("/choice/{id}")
    public Result deleteChoice(@PathVariable Integer id) {
        conversationService.deleteChoiceById(id);
        return Result.ok("选项删除成功！");
    }

    @PutMapping("/choice")
    public Result updateChoice(@RequestBody ConversationChoice choice) {
        conversationService.updateChoice(choice);
        Conservation reaction = conversationService.findReaction("change_choice", null);
        if (reaction != null) {
            return Result.reaction(reaction.getBoat_text(), reaction.getMotion());
        }
        return Result.ok("选项更新成功！");
    }

    private boolean isOwnData(String recordUserId, HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        Integer currentId = JWTUTILL.getUserId(token);
        return currentId != null && String.valueOf(currentId).equals(recordUserId);
    }

    private boolean isEmployee(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        if (token != null && token.startsWith("Bearer ")) token = token.substring(7);
        return "员工".equals(JWTUTILL.getIdentity(token));
    }

    @GetMapping("/motions")
    public Result getMotions() {
        List<String> motions = conversationService.findAllMotions();
        return Result.success(motions);
    }

    @GetMapping("/soundeffects/list")
    public Result listSoundEffects() {
        List<String> names = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:static/soundeffects/*");
            for (Resource r : resources) {
                String fn = r.getFilename();
                if (fn != null) names.add(fn);
            }
        } catch (IOException e) {
            return Result.error("加载音效列表失败");
        }
        return Result.success(names);
    }

    @GetMapping("/motions/aluona")
    public Result getAluonaMotions() {
        List<String> names = new ArrayList<>();
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver()
                    .getResources("classpath*:static/aluona/*");
            for (Resource r : resources) {
                String filename = r.getFilename();
                if (filename != null) names.add(filename);
            }
        } catch (IOException e) {
            return Result.error("加载表情列表失败");
        }
        return Result.success(names);
    }

    @GetMapping("/choice/sub")
    public Result querySubChoices(@RequestParam Integer choice_id) {
        List<ConversationChoice> choices = conversationService.findSubChoices(choice_id);
        if (choices != null && !choices.isEmpty()) {
            return Result.success(choices);
        }
        Conservation c = conversationService.findReaction("search_choiceFail", null);
        if (c != null) {
            List<ConversationChoice> failChoices = conversationService.findChoicesByChoiceId(c.getChoice_id());
            return failChoices != null && !failChoices.isEmpty()
                ? Result.reaction(c.getBoat_text(), c.getMotion(), failChoices)
                : Result.reaction(c.getBoat_text(), c.getMotion());
        }
        return Result.reaction("暂无对话选项", "aluona_huaiyi.png");
    }

    @PostMapping("/choice/sub")
    public Result addSubChoice(@RequestParam Integer parent_id,
                                @RequestParam(defaultValue = "choice") String choice_text,
                                @RequestParam(defaultValue = "act") String choice_action,
                                @RequestParam(defaultValue = "新对话") String boat_text,
                                @RequestParam(defaultValue = "aluona_kaixin.png") String motion) {
        ConversationChoice cc = new ConversationChoice();
        cc.setChoiceText(choice_text);
        cc.setChoiceAction(choice_action);
        cc.setBoatText(boat_text);
        cc.setMotion(motion);
        conversationService.addSubChoice(parent_id, cc);
        return Result.ok("子选项添加成功！");
    }

    @GetMapping("/effects")
    public Result getEffects() {
        List<Effect> effects = effectMapper.findAll();
        return Result.success(effects);
    }

    // 保存单个特效的 live2d_location（Live2D 模式的位置/大小参数，由桌面页 🎯 调整写入）
    @PutMapping("/effects/{id}")
    public Result updateEffectLive2d(@PathVariable Integer id, @RequestBody Map<String, Object> body) {
        Object loc = body.get("live2d_location");
        String locStr = (loc == null) ? null : String.valueOf(loc);
        effectMapper.updateLive2dLocation(id, locStr);
        return Result.ok("特效位置已保存");
    }

    @GetMapping("/basicinfo")
    public Result getBasicInfo(@RequestParam(defaultValue = "2") Integer id) {
        BasicInfo info = basicInfoMapper.findById(id);
        return info != null ? Result.success(info) : Result.error("未找到");
    }

    @PutMapping("/basicinfo")
    public Result updateBasicInfo(@RequestBody BasicInfo info) {
        basicInfoMapper.update(info);
        return Result.success(info);
    }

    // 记忆计数器（每个session独立）
    private final Map<String, Integer> memoryCounter = new java.util.concurrent.ConcurrentHashMap<>();

    // 桌面视觉：截全屏 → 降采样到宽 800px → JPEG base64；同时保存到 desktop_vision_log 测试日志（失败返回 null）
    private String captureScreenBase64(String logName) {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screen = robot.createScreenCapture(screenRect);
            int targetW = 800;
            int w = screen.getWidth(), h = screen.getHeight();
            if (w > targetW) {
                int targetH = (int) ((double) h * targetW / w);
                Image scaled = screen.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
                BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(scaled, 0, 0, null);
                g.dispose();
                screen = resized;
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(screen, "jpg", baos);
            byte[] jpeg = baos.toByteArray();
            if (logName != null) {
                try {
                    java.io.File dir = new java.io.File(System.getProperty("user.dir"), "desktop_vision_log");
                    dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, logName + ".jpg").toPath(), jpeg);
                } catch (Exception ignored) {}
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception e) {
            System.out.println("[桌面视觉] 截屏失败: " + e.getMessage());
            return null;
        }
    }

    // 桌面操控：截全屏（原生分辨率，供 OCR 精确识别）→ JPEG base64
    private String captureScreenNativeBase64(String logName) {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screen = robot.createScreenCapture(screenRect);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(screen, "jpg", baos);
            byte[] jpeg = baos.toByteArray();
            if (logName != null) {
                try {
                    java.io.File dir = new java.io.File(System.getProperty("user.dir"), "desktop_vision_log");
                    dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, logName + ".jpg").toPath(), jpeg);
                } catch (Exception ignored) {}
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception e) {
            System.out.println("[桌面操控] 原生截屏失败: " + e.getMessage());
            return null;
        }
    }

    // 桌面操控（OCR）：截全屏 → 缩放到最长边 1024px（与 Umi-OCR maxSideLen 对齐，避免其二次缩放）→ base64；scaleOut 返回 [x缩放, y缩放]
    private String captureScreenForOcr(String logName, double[] scaleOut) {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screen = robot.createScreenCapture(screenRect);
            int nativeW = screen.getWidth(), nativeH = screen.getHeight();
            int targetLong = 1024;
            int targetW, targetH;
            if (nativeW >= nativeH) {
                targetW = Math.min(nativeW, targetLong);
                targetH = (int) Math.round((double) nativeH * targetW / nativeW);
            } else {
                targetH = Math.min(nativeH, targetLong);
                targetW = (int) Math.round((double) nativeW * targetH / nativeH);
            }
            scaleOut[0] = (double) nativeW / targetW;
            scaleOut[1] = (double) nativeH / targetH;
            BufferedImage resized = screen;
            if (targetW != nativeW || targetH != nativeH) {
                Image scaled = screen.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
                resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(scaled, 0, 0, null);
                g.dispose();
            }
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", baos);
            byte[] jpeg = baos.toByteArray();
            if (logName != null) {
                try {
                    java.io.File dir = new java.io.File(System.getProperty("user.dir"), "desktop_vision_log");
                    dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, logName + ".jpg").toPath(), jpeg);
                } catch (Exception ignored) {}
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception e) {
            System.out.println("[桌面操控] OCR 截屏失败: " + e.getMessage());
            return null;
        }
    }

    // 桌面操控（阶段二）：截全屏 → 降采样到宽 1000px → 叠加坐标网格 → base64；scaleOut 返回 [x缩放, y缩放]（图坐标→真实屏幕坐标）
    private String captureScreenWithGrid(String logName, double[] scaleOut) {
        try {
            Robot robot = new Robot();
            Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage screen = robot.createScreenCapture(screenRect);
            int nativeW = screen.getWidth(), nativeH = screen.getHeight();
            int targetW = 800; // 与阶段一 desktop_vision 一致（800px 已验证可被视觉模型接受，1000px 会触发返回空）
            int targetH = (int) ((double) nativeH * targetW / nativeW);
            scaleOut[0] = (double) nativeW / targetW;
            scaleOut[1] = (double) nativeH / targetH;
            Image scaled = screen.getScaledInstance(targetW, targetH, Image.SCALE_SMOOTH);
            BufferedImage resized = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(scaled, 0, 0, null);
            // 叠加半透明红色网格 + 坐标标注，帮助视觉模型输出精确像素坐标
            g.setColor(new java.awt.Color(255, 60, 60, 150));
            g.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 14));
            int step = 100;
            for (int x = 0; x <= targetW; x += step) {
                g.drawLine(x, 0, x, targetH);
                g.drawString(String.valueOf(x), x + 2, 16);
            }
            for (int y = 0; y <= targetH; y += step) {
                g.drawLine(0, y, targetW, y);
                g.drawString(String.valueOf(y), 2, y + 14);
            }
            g.dispose();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(resized, "jpg", baos);
            byte[] jpeg = baos.toByteArray();
            if (logName != null) {
                try {
                    java.io.File dir = new java.io.File(System.getProperty("user.dir"), "desktop_vision_log");
                    dir.mkdirs();
                    java.nio.file.Files.write(new java.io.File(dir, logName + ".jpg").toPath(), jpeg);
                } catch (Exception ignored) {}
            }
            return Base64.getEncoder().encodeToString(jpeg);
        } catch (Exception e) {
            System.out.println("[桌面操控] 截屏失败: " + e.getMessage());
            return null;
        }
    }

    // 桌面操控（阶段二）：鼠标移动到 (x,y) 并左键单击
    @SuppressWarnings("deprecation")
    private boolean clickAt(int x, int y) {
        try {
            Robot robot = new Robot();
            robot.mouseMove(x, y);
            robot.delay(120); // 等光标落位，避免移动后立即点击被吞
            // BUTTON1_MASK(16) 对 Robot 更兼容；BUTTON1_DOWN_MASK(1024) 在部分 JDK 上不触发左键
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_MASK);
            robot.delay(80);  // 按下保持一小段，模拟真实点击
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_MASK);
            robot.delay(50);
            return true;
        } catch (Exception e) {
            System.out.println("[桌面操控] 点击失败: " + e.getMessage());
            return false;
        }
    }

    // 从文本里提取最外层 JSON 对象（第一个 { 到最后一个 }）
    private String extractJsonObject(String s) {
        if (s == null) return "{}";
        int start = s.indexOf('{');
        int end = s.lastIndexOf('}');
        if (start >= 0 && end > start) return s.substring(start, end + 1);
        return "{}";
    }

    // 桌面视觉测试日志：写文本（解读/提示词/回应）
    private void writeVisionText(String logName, String content) {
        try {
            java.io.File dir = new java.io.File(System.getProperty("user.dir"), "desktop_vision_log");
            dir.mkdirs();
            java.nio.file.Files.write(new java.io.File(dir, logName + ".txt").toPath(),
                (content == null ? "" : content).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
    }

    @PostMapping("/talk")
    public Result talk(@RequestBody user_operation op) {
        String userText = op.getUser_text();
        String sessionId = op.getSession_id() != null ? op.getSession_id() : "default";
        String userOp = op.getUser_operation() != null ? op.getUser_operation() : "talk";
        String visionLogTs = null;
        // 桌面视觉：截屏 + 视觉理解 → 转成一条普通 chat（截图/指令/解读写入 desktop_vision_log 测试日志）
        if ("desktop_vision".equals(userOp)) {
            visionLogTs = String.valueOf(System.currentTimeMillis());
            String base64 = captureScreenBase64("screen_" + visionLogTs);
            if (base64 == null) {
                return Result.reaction("唔……我这边看不太清屏幕呢。", "aluona_kunhuo.png");
            }
            // 区分"闲置偷看"（无用户意图，user_text 是占位文本）与"显式指令/按钮"（带用户原话）
            String originalText = (userText != null ? userText.trim() : "");
            boolean idlePeek = originalText.isEmpty() || originalText.contains("看了一眼屏幕");

            String instruction;
            if (idlePeek) {
                // 闲置偷看：告知视觉模型这是一次"偷看老师正在做什么"
                instruction = "这是老师电脑屏幕的截图。阿罗娜正在偷偷看一眼老师现在在做什么。请简要描述老师当前正在做的事（看什么网页/用什么软件/做什么活动），只描述事实、不要评论。若看不清就说\"看不清\"。";
            } else {
                // 显式：先用对话模型把用户原话转成观察指令（带上下文意图），失败则回退泛化指令
                String intent = deepSeekService.extractVisionIntent(originalText);
                instruction = (intent != null && !intent.trim().isEmpty())
                        ? intent.trim()
                        : "概括描述电脑屏幕当前内容，只描述事实。";
            }
            writeVisionText("instr_" + visionLogTs, instruction);

            String desc = deepSeekService.chatVision(base64, instruction);
            writeVisionText("desc_" + visionLogTs, desc);
            if (desc == null || desc.trim().isEmpty()) {
                return Result.reaction("唔……我这边看不太清屏幕呢。", "aluona_kunhuo.png");
            }
            // 显式：保留用户原话入库；偷看：用占位文本入库
            userText = idlePeek ? "（看了一眼屏幕）" : originalText;
            userOp = "chat";
            op.setUser_text(userText);
            op.setUser_operation(userOp);
            if (idlePeek) {
                op.setFull_prompt("【桌面视觉】你刚刚偷偷看了一眼老师的电脑屏幕，看到：" + desc + "。请以阿罗娜的口吻，对老师正在做的事自然地说一两句话（惊讶/好奇/调侃/关心都可以），要简短。");
            } else {
                op.setFull_prompt("【桌面视觉】老师刚才说：「" + originalText + "」并让你看屏幕。你看到：" + desc + "。请以阿罗娜的口吻，结合老师说的话和屏幕内容自然回应，要简短。");
            }
        }
        // 桌面操控（阶段二·OCR）：截屏缩放至最长边1024 → OCR 得文字+坐标 → 映射回真实屏幕 → LLM 选目标 → 鼠标单击 → 直接回报
        if ("mouse_control".equals(userOp)) {
            String mouseLogTs = String.valueOf(System.currentTimeMillis());
            double[] scale = new double[2];
            String base64 = captureScreenForOcr("mouse_screen_" + mouseLogTs, scale);
            if (base64 == null) {
                return Result.reaction("唔……我看不到屏幕呢。", "aluona_kunhuo.png");
            }
            List<Map<String, Object>> items = ocrService.ocr(base64);
            if (items == null) {
                return Result.reaction("唔……OCR 服务没连上呢（请确认 Umi-OCR 的 HTTP 服务已开启）。", "aluona_kunhuo.png");
            }
            if (items.isEmpty()) {
                writeVisionText("mouse_ocr_" + mouseLogTs, "OCR 结果为空");
                return Result.reaction("唔……屏幕上没识别到文字呢。", "aluona_kunhuo.png");
            }
            // 坐标映射回真实屏幕 + 生成 OCR 列表文本（编号|文字|x|y|w|h）
            StringBuilder listText = new StringBuilder();
            int idx = 0;
            for (Map<String, Object> it : items) {
                String text = it.get("text") != null ? it.get("text").toString() : "";
                int nx = (int) Math.round(((Number) it.get("x")).doubleValue() * scale[0]);
                int ny = (int) Math.round(((Number) it.get("y")).doubleValue() * scale[1]);
                int nw = (int) Math.round(((Number) it.get("w")).doubleValue() * scale[0]);
                int nh = (int) Math.round(((Number) it.get("h")).doubleValue() * scale[1]);
                it.put("x", nx);
                it.put("y", ny);
                it.put("w", nw);
                it.put("h", nh);
                listText.append(idx).append("|").append(text).append("|")
                        .append(nx).append("|").append(ny).append("|")
                        .append(nw).append("|").append(nh).append("\n");
                idx++;
            }
            writeVisionText("mouse_ocr_" + mouseLogTs, listText.toString());
            // LLM 决策：选一个要点击的元素
            int pick = deepSeekService.chooseTargetIndex(listText.toString(), userText.trim());
            writeVisionText("mouse_pick_" + mouseLogTs, "pick=" + pick);
            if (pick < 0 || pick >= items.size()) {
                return Result.reaction("唔……我找不到可以点击的目标呢。", "aluona_kunhuo.png");
            }
            Map<String, Object> target = items.get(pick);
            double cx = ((Number) target.get("x")).doubleValue() + ((Number) target.get("w")).doubleValue() / 2.0;
            double cy = ((Number) target.get("y")).doubleValue() + ((Number) target.get("h")).doubleValue() / 2.0;
            int px = (int) Math.round(cx);
            int py = (int) Math.round(cy);
            String title = target.get("text") != null ? target.get("text").toString().trim() : "";
            boolean ok = clickAt(px, py);
            writeVisionText("mouse_click_" + mouseLogTs, "click(" + px + "," + py + ") ok=" + ok + " title=" + title);
            if (ok) {
                String msg = (title != null && !title.isEmpty()) ? "我点开了《" + title + "》～" : "我点了一下那个位置～";
                return Result.reaction(msg, "aluona_kaixin.png");
            }
            return Result.reaction("唔……我点不了那个位置呢。", "aluona_kunhuo.png");
        }
        // 静默记录（仅写入历史，不显示响应）
        if (userText == null || userText.trim().isEmpty()) {
            try {
                Map<String, Object> rec = new HashMap<>();
                rec.put("session_id", sessionId);
                rec.put("user_text", "");
                rec.put("user_operation", userOp);
                rec.put("boat_text", op.getBoat_text());
                rec.put("motion", op.getMotion());
                aiHistoryMapper.insert(rec);
            } catch (Exception ignored) {}
            return Result.success(null);
        }
        Conservation c = conversationService.findTalkReaction(userText.trim());
        if (c != null && c.getBoat_text() != null) {
            List<ConversationChoice> choices = conversationService.findChoicesByChoiceId(c.getChoice_id());
            Result r = choices != null && !choices.isEmpty()
                ? Result.reaction(c.getBoat_text(), c.getMotion(), choices)
                : Result.reaction(c.getBoat_text(), c.getMotion());
            r.setSoundEffect(c.getSound_effect());
            r.setSpecialEffect(c.getSpecial_effect());
            return r;
        }
        // Pass 1: AI 文本回复（不含表情）
        // 游戏场景：full_prompt 作为 AI 输入（含历史上下文），user_text 只作为入库的短内容
        String fullPrompt = op.getFull_prompt();
        String aiInput = (fullPrompt != null && !fullPrompt.isEmpty()) ? fullPrompt : userText;
        boolean live2d = op.getLive2d() != null && op.getLive2d();
        DeepSeekService.AiResult ai = deepSeekService.chat(aiInput, userText.trim(), sessionId, userOp, op.getGame_model(), live2d);
        if (live2d) {
            System.out.println("[Live2D] 收到 live2d 请求，situations=" + (ai != null ? ai.situations : null));
        }
        if (visionLogTs != null && ai != null && ai.boat_text != null) {
            writeVisionText("response_" + visionLogTs, ai.boat_text);
        }
        if (ai != null && ai.boat_text != null) {
            String matchedMotion = "aluona_zhengchang.png", matchedSound = null, matchedEffect = null;
            if ("game".equals(userOp)) {
                // 游戏对话：完全不走规则库，按场景随机取表情
                String gameScene = ai.boat_text.contains("我猜") ? "game_guess" : "game_ask";
                Conservation gameExpr = conversationService.findReaction(gameScene, null);
                matchedMotion = (gameExpr != null && gameExpr.getMotion() != null && !gameExpr.getMotion().isEmpty())
                    ? gameExpr.getMotion() : "aluona_zhengjing.png";
            } else {
                // 正常对话：遍历规则库匹配 AI 回复文本（长词优先，避免短词断章取义）
                Integer matchedRuleId = null;
                String matchedKeyword = null;
                ResponseRule matchedRule = null;
                try {
                    List<ResponseRule> allRules = responseRuleMapper.findAll();
                    allRules.sort((a, b) -> Integer.compare(
                        (b.getKeyword() != null ? b.getKeyword().length() : 0),
                        (a.getKeyword() != null ? a.getKeyword().length() : 0)));
                    for (ResponseRule rule : allRules) {
                        String kw = rule.getKeyword();
                        if (kw != null && !kw.isEmpty() && ai.boat_text.contains(kw)) {
                            if (rule.getMotion() != null) matchedMotion = rule.getMotion();
                            if (rule.getSound_effect() != null) matchedSound = rule.getSound_effect();
                            if (rule.getSpecial_effect() != null) matchedEffect = rule.getSpecial_effect();
                            matchedRuleId = rule.getId();
                            matchedKeyword = kw;
                            matchedRule = rule;
                            break; // 命中第一条就停
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[规则] 匹配异常: " + e.getMessage());
                }
                System.out.println("[规则] 文本长度:" + ai.boat_text.length() + " 命中规则id:" + matchedRuleId + " 关键词:" + matchedKeyword + " 命中表情:" + matchedMotion);
                // 短词生长：命中 2-3 字短词时，按冷却 + 概率在后台尝试扩展成更长更精准的关键词
                if (matchedRule != null && matchedKeyword != null && matchedKeyword.length() >= 2 && matchedKeyword.length() <= 3) {
                    final ResponseRule growRule = matchedRule;
                    final String shortKw = matchedKeyword;
                    final String growText = ai.boat_text;
                    new Thread(() -> tryGrowKeyword(growRule, shortKw, growText)).start();
                }
            }
            // 闲置动作表情（Live2D 静态表情名，关键词映射）
            if ("idle_ai".equals(userOp)) {
                if (ai.boat_text.contains("面板") || ai.boat_text.contains("电脑") || ai.boat_text.contains("浏览")) {
                    matchedMotion = "指纹锁";
                } else if (ai.boat_text.contains("吃什么") || ai.boat_text.contains("盘算") || ai.boat_text.contains("今晚吃") || ai.boat_text.contains("菜单")) {
                    matchedMotion = "流口水";
                }
            }
            Result r = Result.reaction(ai.boat_text, matchedMotion);
            r.setSoundEffect(matchedSound);
            r.setSpecialEffect(matchedEffect);
            // 睡眠兜底：AI 说了睡觉相关的话却漏掉 sleep 参数时，按语义补一个睡眠时长
            Integer sleepSec = ai.sleep_seconds;
            if (sleepSec == null && isSleepText(ai.boat_text)) {
                sleepSec = parseSleepSeconds(ai.boat_text);
            }
            r.setSleepSeconds(sleepSec);

            // 句级切分：Live2D 模式走情境流程；否则按句匹配表情（多句时前端逐句播放）
            if (!"game".equals(userOp)) {
                try {
                    List<Map<String, String>> sentenceFlow;
                    if (live2d) {
                        // AI 给了 situations 就用；没给就单独调一次情境分类（可靠），再兜底猜情境
                        List<String> sits;
                        if (ai.situations != null && !ai.situations.isEmpty()) {
                            sits = ai.situations;
                        } else {
                            try {
                                sits = deepSeekService.classifySituations(splitToSentences(ai.boat_text));
                            } catch (Exception ex) {
                                sits = null;
                            }
                            if (sits == null || sits.isEmpty()) sits = guessSituations(ai.boat_text);
                        }
                        sentenceFlow = buildLive2dSentenceFlow(ai.boat_text, sits);
                    } else {
                        sentenceFlow = buildSentenceFlow(ai.boat_text, matchedMotion, ai.emotion);
                    }
                    if (live2d || sentenceFlow.size() > 1) {
                        r.setSentences(sentenceFlow);
                    }
                } catch (Exception ignored) {}
            }

            // Pass 2（后台）: AI 情绪分类 → 存入规则库
            final String fUserOpForThread = userOp;
            new Thread(() -> {
                try {
                    if ("game".equals(fUserOpForThread)) return; // 游戏对话不录入规则库
                    DeepSeekService.AiResult emo = deepSeekService.classifyEmotion(ai.boat_text);
                    if (emo != null && emo.motion != null && !emo.motion.equals("aluona_kaixin.png")) {
                        ResponseRule rule = new ResponseRule();
                        String kw = emo.boat_text != null && !emo.boat_text.equals("嗯哼哼————") ? emo.boat_text : extractKeyword(ai.boat_text);
                        if (kw == null || kw.trim().isEmpty()) return; // 提炼不出关键词则不创建
                        // 检查是否已存在同关键词
                        boolean exists = false;
                        try {
                            List<ResponseRule> existing = responseRuleMapper.findByKeyword(kw);
                            exists = (existing != null && !existing.isEmpty());
                        } catch (Exception ignored) {}
                        if (!exists) {
                            rule.setKeyword(kw);
                            rule.setMotion(emo.motion);
                            rule.setSound_effect(emo.sound_effect);
                            rule.setSpecial_effect(emo.special_effect);
                            rule.setSample_text(ai.boat_text);
                            try { responseRuleMapper.insertOrUpdate(rule); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            }).start();

            // 记忆计数器：非闲置对话 +1，每10次触发提炼（游戏/系统/桌面操作等不入长期记忆）
            if (!"idle_ai".equals(userOp) && !"system".equals(userOp)
                    && !"game".equals(userOp) && !"remember".equals(userOp)
                    && !"desktop_vision".equals(userOp) && !"mouse_control".equals(userOp)) {
                int count = memoryCounter.merge(sessionId, 1, Integer::sum);
                if (count % 10 == 0) {
                    String charName = "阿罗娜";
                    try {
                        BasicInfo aiInfo = basicInfoMapper.findById(1);
                        if (aiInfo != null && aiInfo.get姓名() != null) charName = aiInfo.get姓名();
                    } catch (Exception ignored) {}
                    final String cn = charName;
                    new Thread(() -> deepSeekService.consolidateMemory(cn, sessionId)).start();
                }
            }

            return r;
        }
        // AI 不可用 → unknown_text 保底
        Conservation fallback = conversationService.findReaction("unknown_text", null);
        if (fallback != null) {
            Result r = Result.reaction(fallback.getBoat_text(), fallback.getMotion());
            r.setSoundEffect(fallback.getSound_effect());
            r.setSpecialEffect(fallback.getSpecial_effect());
            return r;
        }
        return Result.reaction("嗯哼哼————", "aluona_kaixin.png");
    }

    @PostMapping("/reaction/fail")
    public Result reactionFail(@RequestParam String user_operation) {
        Conservation c = conversationService.findReaction(user_operation, null);
        if (c != null) {
            return Result.reactionFail(c.getBoat_text(), c.getMotion());
        }
        return Result.reactionFail("操作失败！", "aluona_swkl.png");
    }
}
