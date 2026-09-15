package com.cy.service;

import com.cy.mapper.Live2dRuleMapper;
import com.cy.mapper.PictionaryMapper;
import com.cy.mapper.ResponseRuleMapper;
import com.cy.pojo.Conservation;
import com.cy.pojo.Live2dRule;
import com.cy.pojo.PictionaryGame;
import com.cy.pojo.PictionaryGuess;
import com.cy.pojo.PictionaryWord;
import com.cy.pojo.ResponseRule;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 你画我猜（老师画 · 阿罗娜猜）
 *
 * 设计要点（都对应老师提的需求）：
 *  1) 出题走本地词库 pictionary_word（100 个基础词，按难度分级 1/2/3，带别名），不出网、不靠模型想题。
 *  2) 答案**只在本服务里**，提示词永远不含答案 → 视觉模型没法「读心」；判对/判错由本模块自动完成，
 *     不需要老师点选项、也不需要老师输入答案。
 *  3) 游戏记忆：每轮的猜测/说法/「变了哪里」都落库（pictionary_guess），下一轮回灌提示词 →
 *     阿罗娜记得「我已经猜过 XXX」，也能说出「这次和上次的画差在哪里」。
 *  4) 每轮把「上一次的画 + 这一次的画」两张图一起送视觉模型（chatVisionMultiStrict）→ 有「改动」的对比依据。
 *  5) 记忆边界：**不写 pet_memory**（题目答案不落长期记忆）；但**一局结束时由桌宠页写一条事件簿记录**
 *     （只记「猜对/没猜对、难度、轮次、是否一次就中」，**不含答案**）—— 这样老师随后夸一句
 *     「阿罗娜真棒」时，桌宠能从事件簿（【最近发生的事】）知道是因为刚才画猜发挥好被夸。
 */
@Service
public class PictionaryService {

    @Autowired
    private PictionaryMapper mapper;
    @Autowired
    private DeepSeekService deepSeekService;
    /** 场景表情表（conversation 表里 user_operation = pc_* 的行）：给每段话配表情/音效/动作 */
    @Autowired
    private ConversationService conversationService;
    /** 情景规则库（response_rule 250 条）：按文本关键词匹配表情/音效/特效，和正常对话同一套 */
    @Autowired
    private ResponseRuleMapper responseRuleMapper;
    /** 模型规则库（live2d_rule）：Live2D 的表情/动作主要靠它，按「情境」取，管理页可人工配 */
    @Autowired
    private Live2dRuleMapper live2dRuleMapper;

    /**
     * 你画我猜在「模型规则库」里用的情境词（老师可在管理页给每个情境单独配 Live2D 表情/动作/音效）。
     * 这几条会在 init() 里幂等新建，默认值抄的是规则库里语义最接近的现有情境，老师按喜好调即可。
     */
    private static final String SIT_START  = "画猜开场";
    private static final String SIT_GUESS  = "画猜猜测";
    private static final String SIT_WIN    = "画猜猜对";
    private static final String SIT_WRONG  = "画猜猜错";
    private static final String SIT_REVEAL = "画猜公布";

    private static final File SHOT_DIR = new File(System.getProperty("user.dir"), "pictionary_shots");
    private static final int MAX_SHOTS = 300;          // 画稿留存上限，超出删最旧的
    private static final int VISION_MAX_WIDTH = 800;   // 实测 1000px 会被视觉模型拒答

    // ============================================================
    //  初始化：建表 + 词库为空时灌入 100 个基础词
    // ============================================================
    @PostConstruct
    public void init() {
        try {
            mapper.createWordTable();
            mapper.createGameTable();
            mapper.createGuessTable();
            if (mapper.countWords() == 0) {
                seedWords();
                System.out.println("[你画我猜] 词库已初始化");
            }
            System.out.println("[你画我猜] 词库就绪，共 " + mapper.countWords() + " 个词");
            seedScenes();
            seedLive2dSituations();
        } catch (Exception e) {
            System.out.println("[你画我猜] 初始化失败: " + e.getMessage());
        }
    }

    /**
     * 在「模型规则库」（live2d_rule）里新建你画我猜的 5 个情境：
     * 开场白 / 猜测 / 猜对 / 猜错 / 公布答案。已存在就跳过（幂等）。
     * 默认表情抄了现有语义最接近的情境，老师可在管理页「模型规则库」里逐条调 表情部件/动作/音效。
     */
    private void seedLive2dSituations() {
        try {
            Set<String> have = new HashSet<>();
            for (Live2dRule r : live2dRuleMapper.findAll()) {
                if (r.getSituation() == null) continue;
                for (String t : r.getSituation().split("[,;，；]")) have.add(t.trim());
            }
            String[][] rows = {
                    {SIT_START,  "{\"眼部\":\"星星眼\",\"嘴部\":\"正常微笑\"}",    "bounce", "shuohua.webm"},
                    {SIT_GUESS,  "{\"眼部\":\"眯眼舒适\",\"嘴部\":\"撅嘴\"}",        "",       "yiwen.webm"},
                    {SIT_WIN,    "{\"眼部\":\"星星眼\"}",                          "spin2",  "jingya.webm"},
                    {SIT_WRONG,  "{\"嘴部\":\"撅嘴\"}",                            "tilt",   "liuhan.webm"},
                    {SIT_REVEAL, "{\"眼部\":\"眯眼舒适\",\"嘴部\":\"正常微笑\"}",    "swing",  "jingya.webm"}
            };
            int n = 0;
            for (String[] r : rows) {
                if (have.contains(r[0])) continue;
                Live2dRule rule = new Live2dRule();
                rule.setBoat_text("");
                rule.setSituation(r[0]);
                rule.setExpression(r[1]);
                rule.setAction(r[2]);
                rule.setSound_effect(r[3]);
                rule.setSpecial_effect("");
                try { live2dRuleMapper.insert(rule); n++; } catch (Exception e) { /* 单条失败忽略 */ }
            }
            if (n > 0) System.out.println("[Pictionary] live2d situations created: " + n);
        } catch (Exception e) {
            System.out.println("[你画我猜] 模型规则库情境初始化失败: " + e.getMessage());
        }
    }

    /** 按「情境」词在模型规则库里精确匹配（和 ConservationController.matchLive2dRule 同一套规则：按 , ; 拆词） */
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
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * 你画我猜的「场景表情」：往 conversation 表里补 pc_* 行（每个场景 2 条，取的时候 ORDER BY RAND() 有变化）。
     * 这样每段话都能拿到「表情图 + 音效视频 + 动作」—— 之前是把表情图硬编码在页面里、音效/动作全没有，
     * 表现就是"庆祝时没有表情动作"。表里没有才补（幂等），老师也能在管理页里改。
     *   motion        = aluona/*.png 表情图（Live2D 模式会走 l2dBridge.setExpression）
     *   sound_effect  = soundeffects/*.webm 小特效视频
     *   special_effect= Live2D 动作名：bounce 蹦跳 / swing 左右摇 / spin 旋转 / spin2 / spin3 / tilt 歪头 / drop 下沉 / escape 逃走
     */
    private void seedScenes() {
        if (conversationService == null) return;
        try {
            if (conversationService.findReaction("pc_win", null) != null) return;   // 已种过
            String[][] rows = {
                    {"pc_start", "aluona_qidai.png", "shuohua.webm", ""},
                    {"pc_start", "aluona_kaixin.png", "shuohua.webm", ""},
                    {"pc_guess", "aluona_huaiyi.png", "yiwen.webm", ""},
                    {"pc_guess", "aluona_zhengjing.png", "wenhao.webm", ""},
                    {"pc_win", "aluona_jidong.png", "jingya.webm", "bounce"},
                    {"pc_win", "aluona_feichangxihuan.png", "xihuan.webm", "spin2"},
                    {"pc_wrong", "aluona_kunhuo.png", "liuhan.webm", "tilt"},
                    {"pc_wrong", "aluona_kangju.png", "wuyu.webm", ""},
                    {"pc_reveal", "aluona_huangzhang.png", "jingya.webm", "swing"},
                    {"pc_reveal", "aluona_youyuan.png", "wuyu2.webm", ""}
            };
            int n = 0;
            for (String[] r : rows) {
                Conservation c = new Conservation();
                c.setUser_id("-1");
                c.setUser_text("");
                c.setBoat_text("");
                c.setRole("basic");
                c.setUser_operation(r[0]);
                c.setMotion(r[1]);
                c.setSound_effect(r[2]);
                c.setSpecial_effect(r[3]);
                try { conversationService.addConversation(c); n++; } catch (Exception e) { /* 忽略单条失败 */ }
            }
            System.out.println("[你画我猜] 场景表情已初始化: " + n + " 条");
        } catch (Exception e) {
            System.out.println("[你画我猜] 场景表情初始化失败: " + e.getMessage());
        }
    }

    /**
     * 给一段话配「情境 + 表情 + 动作 + 音效」，取值顺序（老师定的：Live2D 表达主要走模型规则库）：
     *   ① **模型规则库** live2d_rule：按**情境**词精确匹配 → expression 部件 JSON + action 动作 + sound_effect；
     *   ② 还缺的字段 → **情景规则库** response_rule 按文本关键词匹配（长词优先，与正常对话同一套）→ png 表情 / 音效 / 特效动作；
     *   ③ 还缺 → **场景表** conversation 里 pc_* 那几行（PNG 模式的表情图兜底）；
     *   ④ 仍为空 → 默认表情 aluona_zhengchang.png。
     * ★ 关键点：Live2D 的表情/动作由**情境**决定，所以每段话都带一个情境词（画猜开场/猜测/猜对/猜错/公布），
     *   老师在管理页「模型规则库」里改这几行即可调整表情与动作幅度，代码不用动。
     */
    private Map<String, String> reaction(String scene, String situation, String text) {
        String motion = null, expression = null, action = null, sound = null;
        // ① 模型规则库（情境）
        try {
            Live2dRule lr = matchLive2dRule(situation);
            if (lr != null) {
                if (notBlank(lr.getExpression())) expression = lr.getExpression();
                if (notBlank(lr.getAction())) action = lr.getAction();
                if (notBlank(lr.getSound_effect())) sound = lr.getSound_effect();
                if (notBlank(lr.getSpecial_effect())) action = lr.getSpecial_effect().split("[:;]")[0].trim();
            }
        } catch (Exception e) { /* 规则库异常不影响游戏 */ }
        // ② 情景规则库（按文本匹配）
        try {
            if (notBlank(text)) {
                List<ResponseRule> rules = responseRuleMapper.findAll();
                rules.sort((a, b) -> Integer.compare(
                        (b.getKeyword() != null ? b.getKeyword().length() : 0),
                        (a.getKeyword() != null ? a.getKeyword().length() : 0)));
                for (ResponseRule rule : rules) {
                    String kw = rule.getKeyword();
                    if (kw != null && !kw.isEmpty() && text.contains(kw)) {
                        if (motion == null && notBlank(rule.getMotion())) motion = rule.getMotion();
                        if (sound == null && notBlank(rule.getSound_effect())) sound = rule.getSound_effect();
                        if (action == null && notBlank(rule.getSpecial_effect())) {
                            action = rule.getSpecial_effect().split("[:;]")[0].trim();
                        }
                        break;      // 命中第一条就停（与正常对话一致）
                    }
                }
            }
        } catch (Exception e) { /* 规则库异常不影响游戏 */ }
        // ③ 场景表（pc_*）：PNG 模式的表情图**以它为准**（规则库按文本匹配偶尔会命中不相干的关键词，
        //    例如猜错的收尾语里含"工整"却匹配到"激动"）；音效/动作仍只在缺的时候补。
        try {
            Conservation c = conversationService.findReaction(scene, null);
            if (c != null) {
                if (notBlank(c.getMotion())) motion = c.getMotion();
                if (sound == null && notBlank(c.getSound_effect())) sound = c.getSound_effect();
                if (action == null && notBlank(c.getSpecial_effect())) action = c.getSpecial_effect();
            }
        } catch (Exception e) { /* 场景表异常不影响游戏 */ }

        Map<String, String> m = new LinkedHashMap<>();
        m.put("situation", situation == null ? "" : situation);
        m.put("expression", expression == null ? "" : expression);
        m.put("action", action == null ? "" : action);
        m.put("sound", sound == null ? "" : sound);
        m.put("motion", (motion == null || motion.isEmpty()) ? "aluona_zhengchang.png" : motion);
        return m;
    }

    private boolean notBlank(String s) { return s != null && !s.trim().isEmpty(); }

    /** 把「第一段话」的情境/表情/音效/动作塞进响应（saySituation / sayExpression / sayAction / saySound / sayMotion） */
    private void putSayEffects(Map<String, Object> into, String scene, String situation, String text) {
        Map<String, String> fx = reaction(scene, situation, text);
        into.put("saySituation", fx.get("situation"));
        into.put("sayExpression", fx.get("expression"));
        into.put("sayAction", fx.get("action"));
        into.put("saySound", fx.get("sound"));
        into.put("sayMotion", fx.get("motion"));
    }

    /** 把「收尾那段话」的情境/表情/音效/动作塞进响应（verdict* 前缀） */
    private void putVerdictEffects(Map<String, Object> into, String scene, String situation, String text) {
        Map<String, String> fx = reaction(scene, situation, text);
        into.put("verdictSituation", fx.get("situation"));
        into.put("verdictExpression", fx.get("expression"));
        into.put("verdictAction", fx.get("action"));
        into.put("verdictSound", fx.get("sound"));
        into.put("verdictMotion", fx.get("motion"));
    }

    /**
     * 100 个基础题：词;别名;类别;难度
     * ★ 字段分隔符用「;」而不是「|」：别名本身是用「|」分隔的多值（如 猫|小猫|猫咪），
     *   两者用同一个符号会把类别/难度挤错位（踩过一次）。
     * 难度 1 简单 40 / 2 中等 40 / 3 困难 20
     */
    private void seedWords() {
        String[] rows = {
                // ---------- 难度 1：简单（40） ----------
                "太阳;日头;自然;1", "月亮;月牙;自然;1", "星星;繁星;自然;1", "云;白云|云朵;自然;1", "雨;下雨|雨滴;自然;1",
                "树;大树|树木;自然;1", "花;花朵|鲜花;自然;1", "草;小草|青草;自然;1", "山;大山|高山;自然;1", "河;小河|河流;自然;1",
                "房子;屋子|房屋;物品;1", "门;大门|房门;物品;1", "窗;窗户|窗子;物品;1", "桌子;书桌|饭桌;物品;1", "椅子;凳子;物品;1",
                "床;;物品;1", "伞;雨伞|太阳伞;物品;1", "钥匙;;物品;1", "书;书本|书籍;物品;1", "笔;铅笔|钢笔;物品;1",
                "杯子;水杯|茶杯;物品;1", "碗;饭碗|汤碗;物品;1", "盘子;碟子;物品;1", "勺子;汤匙|调羹;物品;1",
                "苹果;红苹果;食物;1", "香蕉;;食物;1", "西瓜;;食物;1", "梨;梨子;食物;1", "葡萄;一串葡萄;食物;1",
                "鱼;小鱼|金鱼;动物;1", "鸟;小鸟|飞鸟;动物;1", "猫;小猫|猫咪;动物;1", "狗;小狗|狗狗;动物;1", "猪;小猪|胖猪;动物;1",
                "牛;奶牛|水牛;动物;1", "羊;小羊|绵羊;动物;1", "马;小马|骏马;动物;1", "兔子;小兔|小白兔;动物;1", "蛇;小蛇|蟒蛇;动物;1",
                "乌龟;小乌龟|海龟;动物;1",

                // ---------- 难度 2：中等（40） ----------
                "自行车;单车|脚踏车;交通;2", "汽车;小汽车|轿车;交通;2", "火车;列车|高铁;交通;2", "飞机;客机|航班;交通;2",
                "船;小船|轮船;交通;2", "火箭;;交通;2", "红绿灯;信号灯|交通灯;交通;2", "路标;指示牌;交通;2",
                "眼镜;墨镜;物品;2", "口罩;;物品;2", "帽子;;物品;2", "围巾;围脖;物品;2", "手表;腕表;物品;2", "手机;;物品;2",
                "电脑;计算机;物品;2", "电视;电视机;物品;2", "冰箱;电冰箱;物品;2", "洗衣机;;物品;2", "台灯;电灯;物品;2",
                "风扇;电风扇;物品;2", "剪刀;;物品;2", "锤子;铁锤;物品;2", "梯子;;物品;2", "扫把;扫帚;物品;2",
                "雨衣;雨披;物品;2", "书包;背包;物品;2", "铅笔盒;文具盒;物品;2",
                "篮球;;运动;2", "足球;;运动;2", "羽毛球拍;羽毛球|球拍;运动;2", "风筝;;运动;2", "气球;;运动;2",
                "生日蛋糕;蛋糕;食物;2", "冰淇淋;冰激凌|雪糕;食物;2", "汉堡;汉堡包;食物;2", "面条;拉面;食物;2",
                "饺子;水饺;食物;2", "火锅;;食物;2", "包子;;食物;2", "粽子;;食物;2",

                // ---------- 难度 3：困难（20） ----------
                "龙;中国龙;人物;3", "恐龙;霸王龙;动物;3", "机器人;机械人;人物;3", "外星人;;人物;3", "超人;;人物;3",
                "圣诞老人;圣诞公公;人物;3", "美人鱼;;人物;3", "独角兽;;人物;3",
                "钢琴;;乐器;3", "吉他;;乐器;3", "小提琴;;乐器;3", "鼓;架子鼓;乐器;3",
                "显微镜;;科学;3", "望远镜;;科学;3", "地球仪;;科学;3", "沙漏;;科学;3", "指南针;;科学;3",
                "灯塔;;建筑;3", "摩天轮;;建筑;3", "过山车;;建筑;3"
        };
        int ok = 0;
        for (String row : rows) {
            String[] p = row.split(";", -1);
            if (p.length < 4) continue;
            PictionaryWord w = new PictionaryWord();
            w.setWord(p[0]);
            w.setAliases(p[1]);
            w.setCategory(p[2]);
            try { w.setDifficulty(Integer.parseInt(p[3])); } catch (Exception e) { w.setDifficulty(1); }
            try { mapper.insertWord(w); ok++; } catch (Exception e) { /* 唯一键冲突（重复导入）忽略 */ }
        }
        System.out.println("[你画我猜] 词库播种完成，共 " + ok + " 个词");
    }

    // ============================================================
    //  开局：本地词库出题
    // ============================================================
    public Map<String, Object> start(int difficulty) {
        Map<String, Object> m = new LinkedHashMap<>();
        PictionaryWord w = mapper.pickWord(difficulty);
        if (w == null) {
            m.put("ok", false);
            m.put("say", "词库里还没词呢，老师先去「桌宠管理」里加几个吧～");
            return m;
        }
        mapper.abandonOpenGames();       // 上一局没收尾的置为 quit，避免 current 读到脏数据
        PictionaryGame g = new PictionaryGame();
        g.setWord(w.getWord());
        g.setAliases(w.getAliases());
        g.setCategory(w.getCategory());
        g.setDifficulty(w.getDifficulty());
        mapper.insertGame(g);
        mapper.bumpUsed(w.getId());

        m.put("ok", true);
        m.put("active", true);
        m.put("gameId", g.getId());
        m.put("word", w.getWord());
        m.put("wordLen", w.getWord().length());
        m.put("category", w.getCategory());
        m.put("difficulty", w.getDifficulty());
        m.put("round", 0);
        m.put("guesses", new ArrayList<String>());
        // ★ 开场白**不在这里生成**：那句话要调一次文本模型（1~2 秒），塞在本接口里会把画板窗口的开窗一起拖住。
        //   前端拿到本响应就立刻开窗，再单独请求 /ai/pictionary/intro 取开场白（见 introSay()）。
        System.out.println("[你画我猜] 开局 #" + g.getId() + " 题目=" + w.getWord()
                + "（" + w.getCategory() + " / 难度" + w.getDifficulty() + "）");
        return m;
    }

    /**
     * 开场白（单独接口取）：★ 只依据「几个字」，不含类别、不含答案。
     * 之所以和 start() 分开：这句话要调一次文本模型（1~2 秒），放在开局接口里会把画板开窗拖住 ——
     * 老师的顺序要求是「拿到题目就先把画板弹出来」，开场白随后到即可。
     */
    public Map<String, Object> introSay(int gameId) {
        Map<String, Object> m = new LinkedHashMap<>();
        PictionaryGame g = mapper.findGame(gameId);
        String say = (g == null) ? "好耶！老师画吧，阿罗娜看着呢～" : introLine(g.getWord().length());
        m.put("ok", g != null);
        m.put("say", say);
        putSayEffects(m, "pc_start", SIT_START, say);      // 开局的表情/音效/动作（场景表 pc_start，规则库可覆盖）
        return m;
    }

    /** 画板窗口打开时拉取当前局（关掉画板再打开也能接着画） */
    public Map<String, Object> current() {
        PictionaryGame g = mapper.findOpenGame();
        if (g == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", false);
            return m;
        }
        return gameState(g, mapper.findGuesses(g.getId()));
    }

    private Map<String, Object> gameState(PictionaryGame g, List<PictionaryGuess> history) {
        List<String> guessed = new ArrayList<>();
        for (PictionaryGuess h : history) if (h.getGuess() != null && !h.getGuess().isEmpty()) guessed.add(h.getGuess());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("active", g.getEnded_at() == null);
        m.put("gameId", g.getId());
        m.put("word", g.getWord());
        m.put("wordLen", g.getWord().length());
        m.put("category", g.getCategory());
        m.put("difficulty", g.getDifficulty());
        m.put("round", history.size());
        m.put("guesses", guessed);
        m.put("result", g.getResult());
        if (g.getEnded_at() != null) m.put("answer", g.getWord());
        return m;
    }

    // ============================================================
    //  猜一轮：老师提交一张画 → 阿罗娜看图猜 → 本模块自动判对
    // ============================================================
    public Map<String, Object> guess(int gameId, String imageDataUrl) {
        Map<String, Object> m = new LinkedHashMap<>();
        PictionaryGame g = mapper.findGame(gameId);
        if (g == null) {
            m.put("ok", false);
            m.put("say", "诶…阿罗娜找不到这一局了，老师重新开一局好不好？");
            return m;
        }
        if (g.getEnded_at() != null) {
            m.put("ok", false);
            m.put("ended", true);
            m.put("answer", g.getWord());
            m.put("say", "这一局已经结束啦～答案是「" + g.getWord() + "」哦。");
            return m;
        }
        BufferedImage img = decodeImage(imageDataUrl);
        if (img == null) {
            m.put("ok", false);
            m.put("failed", true);
            String gotNone = "唔…这张画阿罗娜没收到，老师再点一次「画好了」？";
            m.put("say", gotNone);
            putSayEffects(m, "pc_guess", SIT_GUESS, gotNone);
            return m;
        }

        List<PictionaryGuess> history = mapper.findGuesses(gameId);
        int round = history.size() + 1;

        // 本轮画稿存档（同时给下一轮做「上一张画」的对比源）
        String curB64 = toJpegBase64(img);
        String shotName = saveShot(gameId, round, img);
        File prevShot = shotFile(gameId, round - 1);

        List<String> imgs = new ArrayList<>();
        boolean hasPrev = prevShot.exists() && prevShot.length() > 0;
        String prompt;
        if (hasPrev) {
            String prevB64 = base64Of(prevShot);
            if (prevB64 != null) {
                imgs.add(prevB64);      // 图1 = 上一次的画
                imgs.add(curB64);       // 图2 = 这一次的画
            } else {
                hasPrev = false;
                imgs.add(curB64);
            }
        } else {
            imgs.add(curB64);
        }
        prompt = buildPrompt(g, round, history, hasPrev);
        // ★ 反作弊自证：本轮真实提示词原样落盘 + 断言不含答案（老师可自己打开 gX_rY.prompt.txt 核对）
        dumpPrompt(gameId, round, prompt, g.getWord());

        String raw = deepSeekService.chatVisionMultiStrict(imgs, prompt);
        String guess = clean(field(raw, "guess"));
        String say = clean(field(raw, "say"));
        String changes = clean(field(raw, "changes"));
        String remark = clean(field(raw, "remark"));
        dumpRaw(gameId, round, "1", raw);

        // ★ 双保险（实测：两图 + 长提示词时推理型视觉模型常只给思考过程、不给 JSON）：
        //   1) 从大白话里抠猜测，并**校验像不像一个词**（挡掉从思考文本里抠出来的
        //      "家？户？房？屋？宅？舍？" 这种垃圾）；
        //   2) 还不行就用**最小提示词重试一次**（只给这一张画、只要求一个词）。
        if (!plausibleGuess(guess)) {
            String fromProse = guessFromProse(raw);
            System.out.println("[Pictionary] round " + round + " json-miss(first) prose='" + fromProse + "'");
            guess = plausibleGuess(fromProse) ? fromProse : "";
        }
        if (guess.isEmpty()) {
            String retryPrompt = "这是老师手绘的一幅画。请**直接输出**他画的是什么，格式："
                    + "{\"guess\":\"一个具体的名词（2~6个字）\"}。不要分析、不要复述要求、不要输出任何其它文字。";
            String raw2 = deepSeekService.chatVisionMultiStrict(Collections.singletonList(curB64), retryPrompt);
            dumpRaw(gameId, round, "2-retry", raw2);
            System.out.println("[Pictionary] round " + round + " retry once with minimal prompt");
            String g2 = clean(field(raw2, "guess"));
            if (!plausibleGuess(g2)) {
                String p2 = guessFromProse(raw2);
                g2 = plausibleGuess(p2) ? p2 : "";
            }
            guess = g2;
            if (say.isEmpty()) say = clean(field(raw2, "say"));
            if (changes.isEmpty()) changes = clean(field(raw2, "changes"));
        }

        if (guess.isEmpty()) {
            // 两次都没给出猜测（超时/空回复）：不算一轮，老师重画不亏次数
            System.out.println("[Pictionary] round " + round + " no guess after retry");
            m.put("ok", false);
            m.put("failed", true);
            m.put("round", history.size());
            m.put("guesses", guessedList(history));
            String failLine = say.isEmpty() ? "唔…阿罗娜看不太清楚，老师把线条画粗一点点再试试？" : say;
            m.put("say", failLine);
            putSayEffects(m, "pc_guess", SIT_GUESS, failLine);
            return m;
        }

        boolean correct = isCorrect(g.getWord(), g.getAliases(), guess);

        PictionaryGuess row = new PictionaryGuess();
        row.setGame_id(gameId);
        row.setRound_no(round);
        row.setGuess(guess);
        row.setSay(say);
        row.setChanges_desc(changes);
        row.setCorrect(correct ? 1 : 0);
        row.setShot(shotName);
        try { mapper.insertGuess(row); } catch (Exception e) { System.out.println("[你画我猜] 记猜测失败: " + e.getMessage()); }
        mapper.incGuess(gameId);

        m.put("ok", true);
        m.put("correct", correct);
        m.put("guess", guess);
        m.put("changes", changes);
        m.put("round", round);
        m.put("ended", false);
        m.put("wordLen", g.getWord().length());
        m.put("difficulty", g.getDifficulty());

        if (correct) {
            mapper.endGame(gameId, "win", round);
            m.put("ended", true);
            m.put("answer", g.getWord());
            // 两段式：say=这一轮"猜是什么"，verdict=停顿之后的收尾反应（前端先念 say、隔一会儿再念 verdict）
            String sayLine = say.isEmpty() ? ("是「" + guess + "」吧？阿罗娜看出来了！") : say;
            String verdictLine = winLine(g.getWord(), round);
            m.put("say", sayLine);
            m.put("verdict", verdictLine);
            putSayEffects(m, "pc_guess", SIT_GUESS, sayLine);        // 场景/规则库给的表情·音效·动作
            putVerdictEffects(m, "pc_win", SIT_WIN, verdictLine);
            System.out.println("[你画我猜] #" + gameId + " 第" + round + "轮猜对：" + guess + "（答案 " + g.getWord() + "）");
        } else {
            String sayLine = say.isEmpty() ? ("唔…阿罗娜觉得这画的是「" + guess + "」，不对吗？") : say;
            String verdictLine = wrongVerdict(guess, remark, round);
            m.put("say", sayLine);
            m.put("verdict", verdictLine);
            putSayEffects(m, "pc_guess", SIT_GUESS, sayLine);
            putVerdictEffects(m, "pc_wrong", SIT_WRONG, verdictLine);
            System.out.println("[你画我猜] #" + gameId + " 第" + round + "轮猜错：" + guess
                    + "（答案不在提示词里；变化：" + changes + "；吐槽：" + remark + "）");
        }
        m.put("remark", remark);
        m.put("guesses", guessedList(mapper.findGuesses(gameId)));
        return m;
    }

    /** 老师点「公布答案」：本模块结束这局并把答案交给桌宠做反应 */
    public Map<String, Object> giveUp(int gameId) {
        Map<String, Object> m = new LinkedHashMap<>();
        PictionaryGame g = mapper.findGame(gameId);
        if (g == null) {
            m.put("ok", false);
            m.put("say", "诶…阿罗娜找不到这一局了。");
            return m;
        }
        List<PictionaryGuess> history = mapper.findGuesses(gameId);
        boolean already = g.getEnded_at() != null;
        if (!already) mapper.endGame(gameId, "give_up", history.size());
        m.put("ok", true);
        m.put("ended", true);
        m.put("answer", g.getWord());
        m.put("round", history.size());
        m.put("guesses", guessedList(history));
        String revealSay = already ? ("答案是「" + g.getWord() + "」呀，阿罗娜刚不是说过嘛～")
                : revealLine(g, history);
        m.put("say", revealSay);
        putSayEffects(m, "pc_reveal", SIT_REVEAL, revealSay);     // 公布答案的表情/音效/动作
        System.out.println("[你画我猜] #" + gameId + " 公布答案：" + g.getWord() + "（猜了 " + history.size() + " 轮）");
        return m;
    }

    /** 画板窗口被关掉 → 这局作废 */
    public Map<String, Object> quit(int gameId) {
        Map<String, Object> m = new LinkedHashMap<>();
        PictionaryGame g = mapper.findGame(gameId);
        if (g != null && g.getEnded_at() == null) {
            mapper.endGame(gameId, "quit", mapper.findGuesses(gameId).size());
        }
        m.put("ok", true);
        m.put("ended", true);
        if (g != null) m.put("answer", g.getWord());
        return m;
    }

    private List<String> guessedList(List<PictionaryGuess> history) {
        List<String> guessed = new ArrayList<>();
        for (PictionaryGuess h : history) if (h.getGuess() != null && !h.getGuess().isEmpty()) guessed.add(h.getGuess());
        return guessed;
    }

    // ============================================================
    //  提示词：★ 永远不含答案（只给字数/类别/已猜清单/改动要求）
    // ============================================================
    private String buildPrompt(PictionaryGame g, int round, List<PictionaryGuess> history, boolean hasPrev) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是阿罗娜，正在陪老师玩「你画我猜」：老师在画板上手绘，你负责看图猜他画的是什么。\n");
        // ★ 提示强度（老师定的）：只给**字数**，不给类别 —— 类别+字数会把范围压得太窄（"交通+2字"≈汽车/火车/飞机/轮船），
        //   猜起来像开了天眼。类别只在画板窗口给画的人看，不进任何给模型的提示词。
        sb.append("【题目信息】答案是一个 ").append(g.getWord().length()).append(" 个字的词（不提供类别，只能靠画面判断）。");
        sb.append("题目内容只有老师知道，你必须完全靠看图来猜。\n");
        List<String> guessed = guessedList(history);
        if (!guessed.isEmpty()) {
            sb.append("【你已经猜过的（绝对不要再猜这些，即使写法相近）】");
            sb.append(String.join("、", guessed)).append("。\n");
            List<String> changes = new ArrayList<>();
            for (PictionaryGuess h : history) {
                if (h.getChanges_desc() != null && !h.getChanges_desc().isEmpty()) {
                    changes.add("第" + h.getRound_no() + "轮：" + h.getChanges_desc());
                }
            }
            if (!changes.isEmpty()) {
                sb.append("【你前几轮看到的画面变化（你自己当时的观察，可参考）】");
                sb.append(String.join("；", changes)).append("。\n");
            }
        }
        sb.append("【这是老师第 ").append(round).append(" 次提交画作】老师每次提交前都可能在上一版画上继续修改或添加，");
        if (hasPrev) sb.append("所以你要先看清这次相比上一次改了哪里，再据此修正你的猜测。");
        else sb.append("这是第一版，请根据画面大胆猜一个具体的词。");
        sb.append("\n");
        if (hasPrev) sb.append("图片顺序：第 1 张是老师上一次提交的画，第 2 张是老师刚刚提交的画。\n");
        else sb.append("图片：老师刚刚提交的画。\n");
        sb.append("【输出格式（极重要）】只输出一行 JSON，不要有 JSON 以外的任何文字、不要用代码块包裹：\n");
        sb.append("{\"guess\":\"你猜的词（2~6个字的具体名词）\",\"say\":\"对老师说的一句话（不超过30字，口语，带上你猜的词）\",");
        sb.append("\"changes\":\"").append(hasPrev ? "相比上一次的画改了哪里（不超过20字）" : "填空字符串")
                .append("\",");
        sb.append("\"remark\":\"顺手吐槽老师这幅画一句（不超过15字，可以像「画得有点潦草」「太抽象啦」这样可爱地吐槽；"
                + "画得清楚就填空字符串）\"}\n");
        sb.append("猜测必须是具体名词（如「台灯」「自行车」），不要猜「画」「图案」「东西」这类空词；");
        sb.append("如果画面很抽象，也请选一个最接近的具体词来猜。");
        return sb.toString();
    }

    // ============================================================
    //  判对：包含即算对 + 别名（老师定的规则）
    // ============================================================
    public boolean isCorrect(String word, String aliases, String guess) {
        String g = norm(guess);
        if (g.isEmpty() || word == null) return false;
        if (matchOne(word, g)) return true;
        if (aliases != null && !aliases.isEmpty()) {
            for (String a : aliases.split("[|,，/、;；]")) {
                if (matchOne(a, g)) return true;
            }
        }
        return false;
    }

    private boolean matchOne(String target, String guessNorm) {
        String t = norm(target);
        if (t.isEmpty() || guessNorm.isEmpty()) return false;
        if (guessNorm.contains(t)) return true;                                  // 猜的词里含答案 → 对
        return t.contains(guessNorm) && guessNorm.length() >= 2;                 // 说出了核心词 → 也算对
    }

    private String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。、？！；：,.?!;:~～「」『』\"'（）()\\[\\]【】]", "").trim();
    }

    private String clean(String s) {
        if (s == null) return "";
        String r = s.trim().replaceAll("^[「『\"']+|[」』\"']+$", "").trim();
        if (r.length() > 60) r = r.substring(0, 60);
        return r;
    }

    /** 从模型回复里取一个字段（容错：代码块 / 多余文字 / 缺字段） */
    private String field(String raw, String key) {
        if (raw == null || raw.isEmpty()) return "";
        Pattern p = Pattern.compile("\"" + key + "\"\\s*[:：]\\s*\"([^\"]*)\"");
        Matcher m = p.matcher(raw);
        if (m.find()) return m.group(1);
        return "";
    }

    /** 校验"猜出来的东西"像不像一个词：1~8 个汉字/字母数字、不含标点（挡掉 "家？户？房？屋？宅？舍？" 这类）、不是空词 */
    private boolean plausibleGuess(String g) {
        if (g == null) return false;
        String s = clean(g);
        if (s.isEmpty() || s.length() > 8) return false;
        if (!s.matches("[\\u4e00-\\u9fa5A-Za-z0-9]{1,8}")) return false;
        String[] empty = {"画", "图案", "东西", "图片", "什么", "不确定", "看不清", "看不出来", "未知", "物体", "图形"};
        for (String e : empty) if (e.equals(s)) return false;
        return true;
    }

    /**
     * 模型没按 JSON 输出时，从大白话里抠出猜测词（多图+长提示词时偶尔会这样）。
     * 依次尝试：花括号里的 guess/猜测/答案 → "我猜是X / 画的是X / 应该是X …" → 「X」 → 整句就是一个词。
     */
    private String guessFromProse(String raw) {
        if (raw == null) return "";
        String t = raw.replace("```json", " ").replace("```", " ").replace("\n", " ").trim();
        if (t.isEmpty()) return "";
        Matcher m = Pattern.compile("[\"']?(?:guess|猜测|答案|我猜|词)[\"']?\\s*[:：]\\s*[\"']?([^\"',，。；;}\\]\\s]{1,12})").matcher(t);
        if (m.find()) return clean(m.group(1));
        m = Pattern.compile("(?:我猜是|我猜|应该是|大概是|画的是|像是|好像是|看起来是|答案是|就是|这是一?[个只条张把幅]?)\\s*[「『\"']?([\\u4e00-\\u9fa5A-Za-z0-9]{1,6})").matcher(t);
        if (m.find()) return clean(m.group(1));
        m = Pattern.compile("[「『\"']([\\u4e00-\\u9fa5A-Za-z0-9]{1,6})[」』\"']").matcher(t);
        if (m.find()) return clean(m.group(1));
        String s = clean(t);
        if (s.length() <= 8 && s.matches("[\\u4e00-\\u9fa5A-Za-z0-9]{1,8}")) return s;
        return "";
    }

    /** 把模型原始返回落盘（排查"读取失败"用：pictionary_shots/gX_rY_step.raw.txt） */
    private void dumpRaw(int gameId, int round, String step, String raw) {
        try {
            if (!SHOT_DIR.exists()) SHOT_DIR.mkdirs();
            File f = new File(SHOT_DIR, "g" + gameId + "_r" + round + "." + step + ".raw.txt");
            Files.write(f.toPath(), (raw == null ? "(null)" : raw).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) { /* 忽略 */ }
    }

    // ============================================================
    //  结束语（★ 用纯文本接口：不写长期记忆；失败则用模板兜底）
    // ============================================================
    /** 猜错时的收尾反应：先接一句对画的小吐槽（视觉模型给的 remark，可空），再给一句"没猜对"的短话 */
    private static final String[] WRONG_TPL = {
            "唔…不是「%s」呀，阿罗娜再想想。",
            "诶，猜错了吗？那老师再改两笔吧。",
            "唔…看来阿罗娜想岔了，「%s」好像不对。",
            "不是吗…那阿罗娜再仔细看看。",
            "唔…差一点点？老师再给个提示嘛。"
    };

    private String wrongVerdict(String guess, String remark, int round) {
        String t = String.format(WRONG_TPL[Math.abs(round * 31 + (guess == null ? 0 : guess.hashCode())) % WRONG_TPL.length], guess);
        if (remark != null && !remark.isEmpty()) {
            String r = remark.length() > 18 ? remark.substring(0, 18) : remark;
            return r + "。" + t;      // 先吐槽画，再说没猜对（两句都短）
        }
        return t;
    }

    /** ★ 反作弊自证：本轮真实提示词落盘（pictionary_shots/gX_rY.prompt.txt）+ 打印"是否含答案"（ASCII，防控制台乱码） */
    private void dumpPrompt(int gameId, int round, String prompt, String word) {
        try {
            if (!SHOT_DIR.exists()) SHOT_DIR.mkdirs();
            File f = new File(SHOT_DIR, "g" + gameId + "_r" + round + ".prompt.txt");
            Files.write(f.toPath(), prompt.getBytes(StandardCharsets.UTF_8));
            System.out.println("[Pictionary] round " + round + " prompt -> " + f.getName()
                    + " | containsAnswer=" + (word != null && prompt.contains(word))
                    + " | chars=" + prompt.length());
        } catch (Exception e) {
            System.out.println("[Pictionary] prompt dump failed: " + e.getMessage());
        }
    }

    /** 结束语（★ 用纯文本接口：不写长期记忆；失败则用模板兜底） */
    private String revealLine(PictionaryGame g, List<PictionaryGuess> history) {
        String sys = "你是阿罗娜，可爱活泼、有点小骄傲的桌宠少女，称呼用户为「老师」。";
        StringBuilder user = new StringBuilder();
        user.append("你和老师玩「你画我猜」，老师画的答案是「").append(g.getWord()).append("」");
        if (g.getAliases() != null && !g.getAliases().isEmpty()) user.append("（也叫").append(g.getAliases().replace("|", "、")).append("）");
        user.append("；老师一共提交了 ").append(history.size()).append(" 次画作，你都没猜对，现在老师公布了答案。");
        List<String> guessed = guessedList(history);
        if (!guessed.isEmpty()) user.append("你之前猜的是：").append(String.join("、", guessed)).append("。");
        user.append("请用一句话（不超过 30 字）回应：可以惊讶、撒娇、自嘲，也可以小小吐槽老师的画技，");
        user.append("但不要真生气、不要长篇自责。只输出这句话本身，不要引号、不要解释。");
        try {
            String r = deepSeekService.chatPlain(sys, user.toString());
            if (r != null && !r.trim().isEmpty()) {
                String line = clean(r.replace("\n", " "));
                if (!line.isEmpty()) return line;
            }
        } catch (Exception e) {
            System.out.println("[你画我猜] 结束语生成失败: " + e.getMessage());
        }
        return "诶——原来是「" + g.getWord() + "」呀！老师画得也太抽象了，阿罗娜下次一定猜得出来～";
    }

    /**
     * 开局开场白：★ 只说「几个字」，连类别都不给（老师定的提示强度）——
     * 桌宠知道得越少，越只能靠画来猜。类别只在画板窗口给老师自己看。
     */
    private String introLine(int wordLen) {
        try {
            String r = deepSeekService.chatPlain("你是阿罗娜，可爱活泼、有点小骄傲的桌宠少女，称呼用户为「老师」。",
                    "老师要画画让你猜（你画我猜）。你只知道答案是一个 " + wordLen + " 个字的词，不知道类别、更不知道是什么。"
                            + "请用一两句话（总共不超过 30 字）热情地开场：说清题目有几个字、请老师放心画、画好点「画好了」你就猜。"
                            + "不要说类别（你并不知道），不要问老师答案，也不要说你已经知道是什么。只输出这句话本身，不要引号、不要解释。");
            if (r != null && !r.trim().isEmpty()) {
                String line = clean(r.replace("\n", " "));
                if (!line.isEmpty()) return line;
            }
        } catch (Exception e) { /* 兜底 */ }
        return "好耶！老师画吧，阿罗娜看着呢～ 题目是 " + wordLen + " 个字的词，画好点「画好了」就行！";
    }

    private String winLine(String word, int round) {
        try {
            String r = deepSeekService.chatPlain("你是阿罗娜，可爱活泼、有点小骄傲的桌宠少女，称呼用户为「老师」。",
                    "你和老师玩「你画我猜」，" + (round <= 1 ? "你一次就猜对了（老师画的第一版就被你猜中）" : ("你第 " + round + " 次猜对了"))
                            + "，答案是「" + word + "」。请用一句话（不超过 30 字）"
                            + (round <= 1 ? "得意地邀功、让老师夸你（例如「一次就猜对啦！快夸我！」这种口气）" : "庆祝一下，带点得意或邀功的小情绪")
                            + "。只输出这句话本身。");
            if (r != null && !r.trim().isEmpty()) {
                String line = clean(r.replace("\n", " "));
                if (!line.isEmpty()) return line;
            }
        } catch (Exception e) { /* 兜底 */ }
        return (round <= 1 ? "一次就猜对啦！是「" + word + "」对不对～ 快夸我！"
                : "猜对啦！是「" + word + "」～ 阿罗娜很聪明的哦！");
    }

    // ============================================================
    //  画稿：落盘（给下一轮对比用的「上一张画」）+ 转 JPEG base64
    // ============================================================
    private File shotFile(int gameId, int round) {
        return new File(SHOT_DIR, "g" + gameId + "_r" + round + ".jpg");
    }

    private String saveShot(int gameId, int round, BufferedImage img) {
        try {
            if (!SHOT_DIR.exists()) SHOT_DIR.mkdirs();
            File f = shotFile(gameId, round);
            ImageIO.write(toJpeg(img), "jpg", f);
            cleanupOldShots();
            return f.getName();
        } catch (Exception e) {
            System.out.println("[你画我猜] 画稿落盘失败: " + e.getMessage());
            return "";
        }
    }

    private void cleanupOldShots() {
        try {
            File[] fs = SHOT_DIR.listFiles();
            if (fs == null || fs.length <= MAX_SHOTS) return;
            Arrays.sort(fs, Comparator.comparingLong(File::lastModified));
            for (int i = 0; i < fs.length - MAX_SHOTS; i++) fs[i].delete();
        } catch (Exception e) { /* 忽略 */ }
    }

    private String base64Of(File f) {
        try {
            return Base64.getEncoder().encodeToString(Files.readAllBytes(f.toPath()));
        } catch (Exception e) {
            return null;
        }
    }

    /** 前端传的 data:image/png;base64,xxx → BufferedImage（透明底先合成到白底，否则缩图会变黑） */
    private BufferedImage decodeImage(String dataUrl) {
        try {
            if (dataUrl == null || dataUrl.isEmpty()) return null;
            String b64 = dataUrl;
            int idx = b64.indexOf("base64,");
            if (idx >= 0) b64 = b64.substring(idx + 7);
            b64 = b64.replaceAll("\\s", "");
            byte[] bytes = Base64.getDecoder().decode(b64);
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(bytes));
            if (src == null) return null;
            return onWhite(src);
        } catch (Exception e) {
            System.out.println("[你画我猜] 画作解码失败: " + e.getMessage());
            return null;
        }
    }

    private BufferedImage onWhite(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, src.getWidth(), src.getHeight());
        g2.drawImage(src, 0, 0, null);
        g2.dispose();
        return out;
    }

    /** 缩到 ≤800px 宽（实测 1000px 视觉模型会拒答） */
    private BufferedImage scaleDown(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        if (w <= VISION_MAX_WIDTH) return src;
        int nw = VISION_MAX_WIDTH, nh = Math.max(1, (int) Math.round(h * (double) nw / w));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setColor(Color.WHITE);
        g2.fillRect(0, 0, nw, nh);
        g2.drawImage(src, 0, 0, nw, nh, null);
        g2.dispose();
        return out;
    }

    private BufferedImage toJpeg(BufferedImage src) {
        return scaleDown(src);
    }

    private String toJpegBase64(BufferedImage src) {
        try {
            BufferedImage small = scaleDown(onWhite(src));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(small, "jpg", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            System.out.println("[你画我猜] 画作转码失败: " + e.getMessage());
            return "";
        }
    }

    // ============================================================
    //  管理页用：词库增删改 + 战绩
    // ============================================================
    public List<PictionaryWord> words() { return mapper.findAllWords(); }

    public void saveWord(PictionaryWord w) {
        if (w.getId() == null) mapper.insertWord(w);
        else mapper.updateWord(w);
    }

    public void deleteWord(int id) { mapper.deleteWord(id); }

    public Map<String, Object> stats() {
        Map<String, Object> m = new LinkedHashMap<>();
        int fin = mapper.countFinished(), win = mapper.countWin();
        m.put("finished", fin);
        m.put("win", win);
        m.put("giveUp", Math.max(0, fin - win));
        m.put("winRate", fin == 0 ? 0 : Math.round(win * 1000.0 / fin) / 10.0);
        m.put("avgWinRounds", Math.round(mapper.avgWinRounds() * 10) / 10.0);
        m.put("words", mapper.countWords());
        m.put("recent", mapper.findHistory(20));
        return m;
    }
}
