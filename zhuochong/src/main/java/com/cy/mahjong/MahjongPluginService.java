package com.cy.mahjong;

import com.cy.mapper.AiConfigMapper;
import com.cy.pojo.AiConfig;
import com.cy.service.DeepSeekService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 麻将插件（独立模块，可开关）：目标《雀魂麻将》日麻。
 * 第一阶段：截屏（或读校准图）→ 区域锚定 + 槽位切分 → 视觉模型逐张读牌 → 日麻牌效分析（向听/进张）→ AI 评论。
 * 关闭（ai_config.mahjong_enabled=0）时 analyze 直接返回，不截屏、不调任何外部服务。
 */
@Service
public class MahjongPluginService {

    @Autowired
    private AiConfigMapper aiConfigMapper;

    @Autowired
    private DeepSeekService deepSeekService;

    /** 34 种牌：0-8 万(1m-9m)，9-17 条(1s-9s)，18-26 筒(1p-9p)，27-33 字(东南西北白发中=1z-7z) */
    static final String[] TILE_NAMES = {
            "一万","二万","三万","四万","五万","六万","七万","八万","九万",
            "一条","二条","三条","四条","五条","六条","七条","八条","九条",
            "一筒","二筒","三筒","四筒","五筒","六筒","七筒","八筒","九筒",
            "东","南","西","北","白","发","中"
    };
    /** 归一化检测宽度（保留常量说明：几何检测在原生分辨率做，避免降采样洗掉牌间窄间隙） */
    private static final int BRIGHT = 150; // 亮像素阈值（牌面）
    private static final int DARK = 90;    // 暗像素阈值（间隙/背景）

    // ===== 开关 =====
    public boolean isEnabled() {
        try {
            List<AiConfig> list = aiConfigMapper.findAll();
            if (list == null || list.isEmpty()) return false;
            Integer v = list.get(0).getMahjong_enabled();
            return v != null && v == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 返回 null 表示成功，否则为错误信息 */
    public String setEnabled(boolean on) {
        try {
            List<AiConfig> list = aiConfigMapper.findAll();
            if (list == null || list.isEmpty()) return "未配置AI";
            AiConfig cfg = list.get(0);
            cfg.setMahjong_enabled(on ? 1 : 0);
            aiConfigMapper.update(cfg);
            return null;
        } catch (Exception e) {
            return "更新失败: " + e.getMessage();
        }
    }

    // ===== 模板库（由校准截屏 + 真值构建） =====
    private volatile MahjongTemplates templates = new MahjongTemplates();
    private volatile boolean templatesReady = false;
    private volatile long templateFingerprint = Long.MIN_VALUE;
    /** 彩色参考牌面缓存：label → JPEG base64（校准图裁剪，与模板同源；供"模板初判→视觉参考比对"管线） */
    private volatile Map<Integer, String> refJpegs = new HashMap<>();
    // ===== 操作按键模板（阶段二：跳过/立直/自摸/和/碰；键位1=右起第一个=跳过）=====
    public static final int BTN_SKIP = 0, BTN_RIICHI = 1, BTN_TSUMO = 2, BTN_HU = 3, BTN_PON = 4;
    public static final String[] BTN_NAMES = {"跳过", "立直", "自摸", "和", "碰"};
    private static final int BTN_TW = 120, BTN_TH = 48; // 按键横条比例（宽:高≈2.4:1）
    private volatile MahjongTemplates btnTemplates = new MahjongTemplates(BTN_TW, BTN_TH);
    private volatile boolean btnReady = false;
    private volatile long btnFingerprint = Long.MIN_VALUE;
    /** 桌宠截屏目标尺度（逻辑分辨率，如 1707x1067）；headless/未知时为 0=用校准图原生尺度 */
    private volatile int targetW = 0, targetH = 0;

    /** 确定桌宠截屏目标尺度（后端非 headless，可拿到屏幕尺寸；测试环境 headless 回退原生） */
    private void ensureTargetSize() {
        if (targetW != 0) return;
        try {
            Dimension d = Toolkit.getDefaultToolkit().getScreenSize();
            targetW = d.width;
            targetH = d.height;
            System.out.println("[麻将] 桌宠截屏目标尺度: " + targetW + "x" + targetH);
        } catch (Throwable t) {
            targetW = 0; targetH = 0;
            System.out.println("[麻将] 无法获取屏幕尺寸（headless?），模板用校准图原生尺度");
        }
    }

    /** 校准截图真值（硬编码）：手牌13 + 摸牌；格式：万0-8 条9-17 筒18-26 字27-33(东南西北白发中)。
     *  仅支持"自己进行游戏"格式（观战布局不规则，已放弃）。 */
    private static final Map<String, int[]> CALIBRATION_LABELS = new LinkedHashMap<>();
    static {
        CALIBRATION_LABELS.put("自己进行游戏.png", new int[]{0, 8, 18, 20, 24, 25, 11, 11, 13, 17, 17, 31, 33, 18});
    }

    /** 解析 麻将参考截屏/*.txt 中的牌型行（如 "4： 1w 2w ... 5b+6t"），返回 自己玩N.png -> 14张标签（可热更新） */
    private static final Pattern TXT_LINE = Pattern.compile("^\\s*(\\d+)\\s*[：:]\s*(.+)$");

    private Map<String, int[]> parseCalibrationTxt() {
        Map<String, int[]> out = new LinkedHashMap<>();
        try {
            File dir = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将参考截屏");
            File[] txts = dir.listFiles((d, n) -> n.endsWith(".txt"));
            if (txts == null) return out;
            for (File txt : txts) {
                for (String line : java.nio.file.Files.readAllLines(txt.toPath())) {
                    Matcher m = TXT_LINE.matcher(line);
                    if (!m.find()) continue;
                    int idx = Integer.parseInt(m.group(1).trim());
                    if (idx <= 0) continue;
                    List<Integer> hand = new ArrayList<>();
                    Integer drawn = null;
                    String body = m.group(2);
                    // 摸牌格式为 "中+6b"（+跟在最后一张手牌后面）或独立 "+6b"：按 + 拆分
                    for (String tok : body.split("[\\s，,；;]+")) {
                        if (tok.isEmpty()) continue;
                        String[] parts = tok.split("\\+");
                        for (int pi = 0; pi < parts.length; pi++) {
                            String clean = parts[pi].trim();
                            if (clean.isEmpty()) continue;
                            Integer t = parseCalibrationTile(clean);
                            if (t == null) continue;
                            if (pi > 0 || tok.startsWith("+")) drawn = t;
                            else if (hand.size() < 13) hand.add(t);
                        }
                    }
                    if (hand.size() == 13) {
                        int[] arr = drawn != null ? new int[14] : new int[13];
                        for (int i = 0; i < 13; i++) arr[i] = hand.get(i);
                        if (drawn != null) arr[13] = drawn;
                        out.put("自己玩" + idx + ".png", arr);
                        System.out.println("[麻将模板] txt解析: 自己玩" + idx + ".png -> 13手牌"
                                + (drawn != null ? "+摸牌" + TILE_NAMES[drawn] : "（无摸牌）"));
                    } else {
                        System.out.println("[麻将模板] txt解析: 自己玩" + idx + ".png 行不完整（hand=" + hand.size() + "）");
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[麻将模板] txt解析失败: " + e.getMessage());
        }
        return out;
    }

    /** 解析单个牌型 token："9w/1b/1t/发/中/6b"，也支持中文："九万/二筒/三条/北/白/发/中"、"9万/6筒" */
    private Integer parseCalibrationTile(String tok) {
        Matcher m = Pattern.compile("([1-9])\\s*([wbtmspWBTMSP])").matcher(tok);
        if (m.find()) {
            int n = m.group(1).charAt(0) - '0';
            switch (Character.toLowerCase(m.group(2).charAt(0))) {
                case 'w': case 'm': return n - 1;
                case 't': case 's': return 9 + n - 1;
                case 'b': case 'p': return 18 + n - 1;
            }
        }
        // 中文数字+花色：九万 / 三条 / 二筒
        Matcher chn = Pattern.compile("([一二三四五六七八九])\\s*([万条筒])").matcher(tok);
        if (chn.find()) {
            String[] cn = {"一", "二", "三", "四", "五", "六", "七", "八", "九"};
            int n = -1;
            for (int i = 0; i < cn.length; i++) if (cn[i].equals(chn.group(1))) { n = i + 1; break; }
            char suit = chn.group(2).charAt(0);
            if (n > 0 && suit == '万') return n - 1;
            if (n > 0 && suit == '条') return 9 + n - 1;
            if (n > 0 && suit == '筒') return 18 + n - 1;
        }
        // 阿拉伯数字+中文花色：9万 / 6筒
        Matcher an = Pattern.compile("([1-9])\\s*([万条筒])").matcher(tok);
        if (an.find()) {
            int n = an.group(1).charAt(0) - '0';
            char suit = an.group(2).charAt(0);
            if (suit == '万') return n - 1;
            if (suit == '条') return 9 + n - 1;
            if (suit == '筒') return 18 + n - 1;
        }
        switch (tok) {
            case "东": return 27; case "南": return 28; case "西": return 29; case "北": return 30;
            case "白": return 31; case "发": return 32; case "中": return 33;
        }
        return null;
    }

    /** 合并硬编码（原始两张）+ txt解析（自己玩N），txt 优先 */
    private Map<String, int[]> resolveCalibrationLabels() {
        Map<String, int[]> m = new LinkedHashMap<>(CALIBRATION_LABELS);
        m.putAll(parseCalibrationTxt());
        return m;
    }

    /** 校准目录指纹（文件修改时间+大小），用于热更新模板库；含错题库目录（误报样本补库） */
    private long calibrationFingerprint() {
        long fp = 0;
        try {
            File dir = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将参考截屏");
            File[] files = dir.listFiles();
            if (files != null) {
                for (File f : files) fp += f.lastModified() + f.length();
            }
            File mis = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将错题截屏");
            File[] mfs = mis.listFiles();
            if (mfs != null) {
                for (File f : mfs) fp += f.lastModified() + f.length() + 1000003L;
            }
        } catch (Exception e) { /* ignore */ }
        return fp;
    }

    /** 读取 regions.json（人工标注区域，UTF-8） */
    private Map<String, Object> readRegions() {
        try {
            File f = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将参考截屏/regions.json");
            if (!f.exists()) return new LinkedHashMap<>();
            String content = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(content, Map.class);
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    /** 取指定区域的 2560 基准坐标 [x1,x2,y1,y2]，不存在返回 null */
    private int[] regionOf(Map<String, Object> regions, String name) {
        try {
            Object r = regions.get(name);
            if (r instanceof Map) {
                Map<?, ?> m = (Map<?, ?>) r;
                return new int[]{
                        ((Number) m.get("x1")).intValue(), ((Number) m.get("x2")).intValue(),
                        ((Number) m.get("y1")).intValue(), ((Number) m.get("y2")).intValue()
                };
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    private synchronized void ensureTemplates() {
        ensureTargetSize();
        long fp = calibrationFingerprint() * 31 + targetW * 17 + targetH;
        if (templatesReady && fp == templateFingerprint) return;
        try {
            MahjongTemplates fresh = new MahjongTemplates();
            Map<Integer, String> refs = new HashMap<>();
            int total = 0;
            // 模板库必须用校准图自身的检测切分（与校准图牌面对齐，归一化后跨缩放有效）；
            // 人工标注区域仅用于运行时几何（点击/摸牌位），不能直接套到校准图上（两者缩放不同）。
            int[] drawnRegion2560 = regionOf(readRegions(), "摸牌区（桌宠截屏）");
            if (drawnRegion2560 == null) drawnRegion2560 = regionOf(readRegions(), "摸牌区");
            if (drawnRegion2560 == null) drawnRegion2560 = new int[]{1967, 2116, 1298, 1528};
            for (Map.Entry<String, int[]> e : resolveCalibrationLabels().entrySet()) {
                BufferedImage img = loadCalibrationFile(e.getKey());
                if (img == null) continue;
                // 统一到桌宠截屏尺度：校准图（2560物理）降到逻辑分辨率，与实机截屏一致
                BufferedImage work = (targetW > 0 && img.getWidth() != targetW)
                        ? toWidth(img, targetW) : img;
                double s = work.getWidth() / (double) 2560; // 固定坐标（2560基准）→ work 尺度
                int[] labels = e.getValue();
                // 手牌：检测槽位（与校准图牌面对齐）
                Detection det = detectTiles(work);
                List<Rect> handRects = new ArrayList<>(det.handSlots);
                if (handRects.size() > 13) handRects = new ArrayList<>(handRects.subList(0, 13));
                int nHand = Math.min(13, Math.min(handRects.size(), labels.length));
                for (int i = 0; i < nHand; i++) {
                    // 统一 240 高：与实机摸牌裁图（cropForPlay targetH=240）同规格，便于视觉参考比对
                    BufferedImage crop = cropBuffered(work, handRects.get(i), 1.0, 240);
                    if (crop != null) { fresh.addSample(labels[i], crop); total++; refs.putIfAbsent(labels[i], toJpegBase64(crop)); }
                }
                // 摸牌模板（弱兜底，运行时摸牌读取以视觉模型为主）
                if (labels.length >= 14) {
                    Rect dr = new Rect((int) Math.round(drawnRegion2560[0] * s),
                            (int) Math.round(drawnRegion2560[2] * work.getHeight() / 1600.0),
                            (int) Math.round(drawnRegion2560[1] * s),
                            (int) Math.round(drawnRegion2560[3] * work.getHeight() / 1600.0));
                    BufferedImage crop = cropBuffered(work, dr, 1.0, 240);
                    if (crop != null) { fresh.addSample(labels[13], crop); total++; refs.putIfAbsent(labels[13], toJpegBase64(crop)); }
                }
                System.out.println("[麻将模板] " + e.getKey() + " @" + work.getWidth() + "x" + work.getHeight()
                        + " 构建 " + nHand + " 手牌 + 1 摸牌模板（检测槽位）");
            }
            // 错题本自学习：误报裁图（文件名 误报_牌名_ts.png）按实际牌名补进模板库——
            // 仅收录"像真实牌面"的裁图（亮占比≥25% 且 暗占比≤60%）；鼠标点击特效/桌布废图（暗>80%）跳过，
            // 防止坏样本污染模板库（2026-09-02 人工核对发现多数误报裁图实为点击特效+桌布）
            try {
                File misDir = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将错题截屏");
                File[] mfs = misDir.listFiles((d, n) -> n.startsWith("误报_") && n.endsWith(".png"));
                if (mfs != null) {
                    int added = 0, skipped = 0;
                    for (File f : mfs) {
                        String name = f.getName();
                        String tileNm = null;
                        // 新格式：误报_（五万）错读为（二万）_时间.png —— 取第一对全角括号里的实际牌名
                        java.util.regex.Matcher nm = java.util.regex.Pattern
                                .compile("^误报_（([^）]+)）错读为").matcher(name);
                        if (nm.find()) {
                            tileNm = nm.group(1);
                        } else {
                            // 开局按槽错题：手牌槽_3（六万）_时间.png
                            java.util.regex.Matcher sm = java.util.regex.Pattern
                                    .compile("^手牌槽_\\d+（([^）]+)）").matcher(name);
                            if (sm.find()) {
                                tileNm = sm.group(1);
                            } else {
                                // 旧格式：误报_五万_时间.png
                                int u1 = name.indexOf('_'), u2 = name.indexOf('_', u1 + 1);
                                if (u1 >= 0 && u2 > u1) tileNm = name.substring(u1 + 1, u2);
                            }
                        }
                        if (tileNm == null) continue;
                        Integer label = parseTileToken(tileNm);
                        if (label == null) continue;
                        BufferedImage img = ImageIO.read(f);
                        if (img == null) continue;
                        double[] ld = lumDarkBright(img);
                        if (ld[1] < 0.25 || ld[0] > 0.60) { // 不像牌面：暗占太高/亮占太低 → 跳过
                            skipped++;
                            continue;
                        }
                        fresh.addSample(label, img);
                        total++;
                        added++;
                    }
                    if (added + skipped > 0) {
                        System.out.println("[麻将模板] 错题本补入样本 " + added + " 张（跳过不像牌面 " + skipped + " 张）");
                    }
                }
            } catch (Exception ex) {
                System.out.println("[麻将模板] 错题本样本加载失败: " + ex.getMessage());
            }
            this.templates = fresh;
            this.refJpegs = refs;
            this.templateFingerprint = fp;
            this.templatesReady = true;
            System.out.println("[麻将模板] 模板库重建完成 @" + (targetW > 0 ? targetW + "x" + targetH : "原生")
                    + "，共 " + total + " 张样本 / " + refs.size() + " 种彩色参考");
        } catch (Exception ex) {
            System.out.println("[麻将模板] 初始化失败: " + ex.getMessage());
        }
    }

    /** 诊断：各校准图在 原生/1707 两种尺度下的槽位检测结果（含原生槽位坐标，用于固定槽位取均值） */
    public Map<String, Object> geometryReport() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, int[]> e : resolveCalibrationLabels().entrySet()) {
                BufferedImage img = loadCalibrationFile(e.getKey());
                if (img == null) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                Detection det = detectTiles(img);
                row.put("native", det.handSlots.size());
                List<List<Integer>> rects = new ArrayList<>();
                for (Rect r : det.handSlots) rects.add(List.of(r.x1, r.x2));
                row.put("slots2560", rects);
                BufferedImage small = toWidth(img, 1707);
                row.put("s1707", detectTiles(small).handSlots.size());
                out.put(e.getKey(), row);
            }
        } catch (Exception ex) {
            out.put("error", ex.getMessage());
        }
        return out;
    }

    /** 留一法准确度：对每张校准图，用其余图建模板，测该图 14 槽位的模板匹配覆盖与准确率（模拟新对局） */
    public Map<String, Object> leaveOneOutAccuracy() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> perFile = new ArrayList<>();
        int total = 0, covered = 0, coveredHit = 0;
        for (String testName : resolveCalibrationLabels().keySet()) {
            MahjongTemplates tmpl = new MahjongTemplates();
            Map<String, int[]> allLabels = resolveCalibrationLabels();
            for (Map.Entry<String, int[]> be : allLabels.entrySet()) {
                String buildName = be.getKey();
                if (buildName.equals(testName)) continue;
                BufferedImage bimg = loadCalibrationFile(buildName);
                if (bimg == null) continue;
                Detection bdet = detectTiles(bimg);
                int[] blabels = be.getValue();
                for (int i = 0; i < Math.min(bdet.handSlots.size(), blabels.length); i++) {
                    BufferedImage c = cropBuffered(bimg, bdet.handSlots.get(i), 1.0, 200);
                    if (c != null) tmpl.addSample(blabels[i], c);
                }
            }
            BufferedImage timg = loadCalibrationFile(testName);
            Detection tdet = detectTiles(timg);
            int[] tlabels = allLabels.get(testName);
            int fCovered = 0, fCoveredHit = 0, fAllHit = 0;
            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(tdet.handSlots.size(), tlabels.length); i++) {
                BufferedImage c = cropBuffered(timg, tdet.handSlots.get(i), 1.0, 200);
                int pred = tmpl.match(c);
                double score = tmpl.matchScore(c);
                total++;
                boolean ok = pred == tlabels[i];
                if (ok) fAllHit++;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pos", i + 1);
                row.put("read", pred >= 0 ? TILE_NAMES[pred] : "-");
                row.put("truth", TILE_NAMES[tlabels[i]]);
                row.put("score", Math.round(score * 100) / 100.0);
                row.put("ok", ok);
                if (pred >= 0) {
                    covered++;
                    fCovered++;
                    if (ok) { coveredHit++; fCoveredHit++; }
                }
                rows.add(row);
            }
            Map<String, Object> rowF = new LinkedHashMap<>();
            rowF.put("file", testName);
            rowF.put("cover", fCovered + "/14");
            rowF.put("hit", fAllHit + "/14");
            rowF.put("coverHit", fCoveredHit + "/" + fCovered);
            rowF.put("detail", rows);
            perFile.add(rowF);
        }
        out.put("perFile", perFile);
        out.put("summary", "覆盖=" + covered + "/" + total
                + " 覆盖内准确=" + coveredHit + "/" + covered
                + "（未覆盖的牌由视觉模型兜底，单张约60-70%）");
        return out;
    }

    /** 实验：模拟桌宠逻辑分辨率截屏（本机 150% 缩放下为 1707x1067），对比两种模板缩放方向哪个命中高 */
    public Map<String, Object> testScaleAdaptation() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            int LOGICAL_W = 1707, LOGICAL_H = 1067;
            out.put("screen", LOGICAL_W + "x" + LOGICAL_H);
            BufferedImage cal6 = loadCalibrationFile("自己玩6.png");
            if (cal6 == null) { out.put("error", "自己玩6.png 未找到"); return out; }
            int[] truth = {0, 21, 24, 25, 26, 11, 12, 13, 15, 17, 30, 31, 32, 10}; // 自己玩6 真值
            // 模拟桌宠截屏：2560 双三次降到逻辑尺寸
            BufferedImage liveSim = toWidth(cal6, LOGICAL_W);
            out.put("calSlots", detectTiles(cal6).handSlots.size());
            out.put("liveSimSlots", detectTiles(liveSim).handSlots.size());

            // 方向A：模板@2560原生（全部校准图），把 liveSim 升采样回 2560 再匹配
            MahjongTemplates tmplA = new MahjongTemplates();
            for (Map.Entry<String, int[]> e : resolveCalibrationLabels().entrySet()) {
                BufferedImage bimg = loadCalibrationFile(e.getKey());
                if (bimg == null) continue;
                Detection bdet = detectTiles(bimg);
                int[] blabels = e.getValue();
                for (int i = 0; i < Math.min(bdet.handSlots.size(), blabels.length); i++) {
                    BufferedImage c = cropBuffered(bimg, bdet.handSlots.get(i), 1.0, 200);
                    if (c != null) tmplA.addSample(blabels[i], c);
                }
            }
            BufferedImage liveUp = toWidth(liveSim, cal6.getWidth());
            Detection detA = detectTiles(liveUp);
            int hitsA = 0, coverA = 0;
            for (int i = 0; i < Math.min(detA.handSlots.size(), 14); i++) {
                BufferedImage c = cropBuffered(liveUp, detA.handSlots.get(i), 1.0, 200);
                int pred = tmplA.match(c);
                if (pred >= 0) { coverA++; if (pred == truth[i]) hitsA++; }
            }
            out.put("A_模板@2560测升采样图", "覆盖=" + coverA + "/14 命中=" + hitsA + "/14");

            // 方向B：模板@1707（calibration 降到逻辑尺寸），直接测 liveSim
            MahjongTemplates tmplB = new MahjongTemplates();
            BufferedImage calSmall = toWidth(cal6, LOGICAL_W);
            Detection detB = detectTiles(calSmall);
            for (int i = 0; i < Math.min(detB.handSlots.size(), 14); i++) {
                BufferedImage c = cropBuffered(calSmall, detB.handSlots.get(i), 1.0, 200);
                if (c != null) tmplB.addSample(truth[i], c);
            }
            Detection detLive = detectTiles(liveSim);
            int hitsB = 0, coverB = 0;
            for (int i = 0; i < Math.min(detLive.handSlots.size(), 14); i++) {
                BufferedImage c = cropBuffered(liveSim, detLive.handSlots.get(i), 1.0, 200);
                int pred = tmplB.match(c);
                if (pred >= 0) { coverB++; if (pred == truth[i]) hitsB++; }
            }
            out.put("B_模板@1707测1707原图", "覆盖=" + coverB + "/14 命中=" + hitsB + "/14");
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    /** 调试：用一张校准图建模板，对另一张校准图做模板匹配，验证跨图泛化 */
    public Map<String, Object> templateGeneralizationTest(String buildFrom, String testOn) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            MahjongTemplates tmpl = new MahjongTemplates();
            Map<String, int[]> allLabels = resolveCalibrationLabels();
            BufferedImage bimg = loadCalibrationFile(buildFrom);
            Detection bdet = detectTiles(bimg);
            int[] blabels = allLabels.get(buildFrom);
            for (int i = 0; i < Math.min(bdet.handSlots.size(), blabels.length); i++) {
                BufferedImage c = cropBuffered(bimg, bdet.handSlots.get(i), 1.0, 200);
                if (c != null) tmpl.addSample(blabels[i], c);
            }
            BufferedImage timg = loadCalibrationFile(testOn);
            Detection tdet = detectTiles(timg);
            int[] tlabels = allLabels.get(testOn);
            List<Map<String, Object>> rows = new ArrayList<>();
            int hit = 0;
            for (int i = 0; i < Math.min(tdet.handSlots.size(), tlabels.length); i++) {
                BufferedImage c = cropBuffered(timg, tdet.handSlots.get(i), 1.0, 200);
                int pred = tmpl.match(c);
                double score = tmpl.matchScore(c);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pos", i + 1);
                row.put("read", pred >= 0 ? TILE_NAMES[pred] : "null");
                row.put("truth", TILE_NAMES[tlabels[i]]);
                row.put("score", Math.round(score * 100) / 100.0);
                row.put("hit", pred == tlabels[i]);
                if (pred == tlabels[i]) hit++;
                rows.add(row);
            }
            out.put("rows", rows);
            out.put("hit", hit + "/" + rows.size());
        } catch (Exception e) {
            out.put("error", e.getMessage());
        }
        return out;
    }

    // ===== 实操（打牌）辅助 =====
    /** 模板匹配单张牌面（实操摸牌读取用） */
    public int matchTile(BufferedImage crop) {
        ensureTemplates();
        return templates.match(crop);
    }

    /** 模板前 n 候选（SSD 升序，不过滤阈值；供"模板初判→视觉比对"管线） */
    public int[] matchTopCandidates(BufferedImage crop, int n) {
        ensureTemplates();
        return templates.topN(crop, n);
    }

    /** 指定候选的模板匹配分（越低越像；无样本返回 MAX） */
    public double matchScoreOf(BufferedImage crop, int label) {
        ensureTemplates();
        return templates.scoreOf(crop, label);
    }

    /** 该牌型的彩色参考牌面 JPEG base64（建库时从校准图缓存）；无则 null */
    public String referenceB64(int label) {
        return refJpegs.get(label);
    }

    /** 采样统计图片的 暗/亮 像素占比（判断裁图是否为真实牌面；点击特效/桌布废图通常暗>80%） */
    private double[] lumDarkBright(BufferedImage img) {
        int dark = 0, bright = 0, n = 0;
        try {
            int w = img.getWidth(), h = img.getHeight();
            for (int y = 0; y < h; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    int rgb = img.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum < 60) dark++; else if (lum > 150) bright++;
                    n++;
                }
            }
        } catch (Exception e) { /* ignore */ }
        if (n == 0) return new double[]{1, 0};
        return new double[]{dark / (double) n, bright / (double) n};
    }

    // ===== 操作按键模板（阶段二）=====
    /** 构建/刷新按键模板库：素材(立直/立直+自摸/和（胡）/碰) × 键位标注区域 → 各按键灰度模板 */
    private synchronized void ensureBtnTemplates() {
        ensureTargetSize();
        long fp = calibrationFingerprint() * 31 + targetW * 17 + targetH * 7 + 20260901L;
        if (btnReady && fp == btnFingerprint) return;
        try {
            MahjongTemplates fresh = new MahjongTemplates(BTN_TW, BTN_TH);
            fresh.setThreshold(0.20); // 按键文字区分要紧于牌面
            Map<String, Object> regions = readRegions();
            int[] skipBox = regionOf(regions, "跳过（键位1）");
            if (skipBox == null) skipBox = regionOf(regions, "跳过");
            int[] box2r = regionOf(regions, "立直（键位2）");
            if (box2r == null) box2r = regionOf(regions, "自摸（键位2）");
            if (box2r == null) box2r = regionOf(regions, "胡（键位2）");
            int[] box3r = regionOf(regions, "立直（键位3）");
            if (skipBox == null || box2r == null) {
                System.out.println("[麻将按键模板] 缺少键位标注（跳过（键位1）/键位2），请在标注页标注后重启");
                return;
            }
            // 统一高度取"跳过"框（各键同高同宽，见需求）
            int y1 = skipBox[2], y2 = skipBox[3];
            int[] b1 = {skipBox[0], skipBox[1], y1, y2};
            int[] b2 = {box2r[0], box2r[1], y1, y2};
            int[] b3 = box3r == null ? null : new int[]{box3r[0], box3r[1], y1, y2};
            int total = 0;
            // 素材 → 该素材中键位框里实际是什么键（键位固定，按素材区分内容）
            total += addBtnSamples(fresh, "立直.png", new int[][]{b1, b2}, new int[]{BTN_SKIP, BTN_RIICHI});
            total += addBtnSamples(fresh, "立直+自摸.png", new int[][]{b1, b2, b3}, new int[]{BTN_SKIP, BTN_TSUMO, BTN_RIICHI});
            total += addBtnSamples(fresh, "和（胡）.png", new int[][]{b1, b2}, new int[]{BTN_SKIP, BTN_HU});
            total += addBtnSamples(fresh, "碰.png", new int[][]{b1, b2}, new int[]{BTN_SKIP, BTN_PON});
            this.btnTemplates = fresh;
            this.btnFingerprint = fp;
            this.btnReady = true;
            System.out.println("[麻将按键模板] 构建完成，样本=" + total + "（键1=跳过 键2/3 视素材而定）");
        } catch (Exception ex) {
            System.out.println("[麻将按键模板] 初始化失败: " + ex.getMessage());
        }
    }

    /** 从素材文件按 2560 基准键位框裁样本（统一到桌宠截屏尺度，与实机同规格） */
    private int addBtnSamples(MahjongTemplates fresh, String file, int[][] boxes, int[] labels) {
        BufferedImage img = loadCalibrationFile(file);
        if (img == null) return 0;
        BufferedImage work = (targetW > 0 && img.getWidth() != targetW) ? toWidth(img, targetW) : img;
        double s = work.getWidth() / (double) 2560;
        int n = 0;
        for (int i = 0; i < boxes.length && i < labels.length; i++) {
            if (boxes[i] == null) continue;
            Rect r = new Rect((int) Math.round(boxes[i][0] * s), (int) Math.round(boxes[i][2] * s),
                    (int) Math.round(boxes[i][1] * s), (int) Math.round(boxes[i][3] * s));
            BufferedImage crop = cropBuffered(work, r, 1.0, BTN_TH);
            if (crop != null) { fresh.addSample(labels[i], crop); n++; }
        }
        return n;
    }

    /** 按键模板匹配：返回 BTN_* 或 -1（未命中） */
    public int matchActionBtn(BufferedImage crop) {
        ensureBtnTemplates();
        return btnReady ? btnTemplates.match(crop) : -1;
    }

    /** 按键前 n 候选（SSD 升序） */
    public int[] actionBtnTopN(BufferedImage crop, int n) {
        ensureBtnTemplates();
        return btnTemplates.topN(crop, n);
    }

    /** 某按键类型的最优匹配分（越低越像） */
    public double actionBtnScore(BufferedImage crop, int label) {
        ensureBtnTemplates();
        return btnTemplates.scoreOf(crop, label);
    }

    /** 按键类型显示名 */
    public String actionBtnName(int label) {
        return label >= 0 && label < BTN_NAMES.length ? BTN_NAMES[label] : "未知(" + label + ")";
    }

    /** 解析单个牌型 token（"9w/1b/1t/发/中/6b"，实操手动注入用） */
    public Integer parseTileToken(String tok) {
        return parseCalibrationTile(tok == null ? "" : tok.trim());
    }

    public String tileName(int idx) {
        return idx >= 0 && idx < 34 ? TILE_NAMES[idx] : "?";
    }

    /** 实操：裁剪槽位并放大到统一高度（供 MahjongPlayService 读摸牌） */
    BufferedImage cropForPlay(BufferedImage src, Rect normRect, int targetH) {
        return cropBuffered(src, normRect, 1.0, targetH);
    }

    /** 缩放图片到指定宽度（几何检测统一到 2560 尺度用） */
    BufferedImage resize(BufferedImage src, int targetW) {
        return toWidth(src, targetW);
    }

    /** 图片转 JPEG base64（视觉模型读单张牌用） */
    public String toJpegBase64Public(BufferedImage img) {
        return toJpegBase64(img);
    }

    /** 实操出牌建议：返回前3推荐（含 idx 牌型索引 / tile 牌名 / shanten / ukeire） */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> recommendDiscard(List<Integer> hand13, Integer drawn, List<Integer> meldTiles) {
        Map<String, Object> eval = evaluate(hand13, drawn, meldTiles);
        Object s = eval.get("suggestions");
        return s == null ? new ArrayList<>() : (List<Map<String, Object>>) s;
    }

    // ===== 主入口 =====
    public Map<String, Object> analyze(boolean fromFile, String fileName) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (!isEnabled()) {
            result.put("enabled", false);
            result.put("message", "麻将插件未启用（ai_config.mahjong_enabled=0）");
            return result;
        }
        try {
            BufferedImage src = fromFile ? loadCalibrationFile(fileName) : captureScreen();
            if (src == null) { result.put("ok", false); result.put("message", "截屏失败"); return result; }
            result.put("ok", true);
            result.put("enabled", true);
            result.put("source", fromFile ? "校准文件:" + fileName : "实时截屏");
            result.put("imageSize", src.getWidth() + "x" + src.getHeight());

            // 实机截屏：以实际截屏尺寸为目标尺度（模板按此尺度构建，与校准的 2560 物理分辨率对齐）
            if (!fromFile) {
                targetW = src.getWidth();
                targetH = src.getHeight();
                saveLiveFrame(src, fileName);
            } else if (targetW > 0 && src.getWidth() != targetW) {
                // 校准文件路径：统一到桌宠截屏尺度，与实机管线完全一致
                src = toWidth(src, targetW);
                result.put("normalizedTo", src.getWidth() + "x" + src.getHeight());
            }

            // 1. 几何检测（原生分辨率；不降采样——降采样会洗掉牌间窄间隙）
            Detection det = detectTiles(src);
            result.put("debug", det.toDebug());
            // 槽位坐标（原生，实操点击用）：[x1,y1,x2,y2]
            List<List<Integer>> slotRects = new ArrayList<>();
            for (Rect r : det.handSlots) slotRects.add(List.of(r.x1, r.y1, r.x2, r.y2));
            result.put("slotRects", slotRects);
            result.put("bandY", List.of(det.bandY1, det.bandY2));

            // 2. 裁牌 → base64；槽数封顶 14（13手牌+摸牌），右端界面亮块丢弃
            double scale = 1.0;
            List<Rect> handSlots = det.handSlots;
            List<Rect> raised = det.raisedTiles;
            if (handSlots.size() > 14) {
                System.out.println("[麻将] 检测到 " + handSlots.size() + " 个槽，超出 14，截断保留前 14（多余为界面元素？）");
                handSlots = new ArrayList<>(handSlots.subList(0, 14));
            }
            if (handSlots.isEmpty()) {
                result.put("ok", false);
                result.put("message", "没在手牌区找到牌（请确认雀魂在前台且是打牌画面）");
                return result;
            }
            List<BufferedImage> crops = new ArrayList<>();
            List<String> roles = new ArrayList<>(); // 与 crops 一一对应：handN / drawn / meld
            for (Rect r : handSlots) { BufferedImage c = cropBuffered(src, r, scale, 200); if (c != null) { crops.add(c); roles.add("hand"); } }
            for (Rect r : raised)   { BufferedImage c = cropBuffered(src, r, scale, 200); if (c != null) { crops.add(c); roles.add("raised"); } }
            if (crops.isEmpty()) {
                result.put("ok", false);
                result.put("message", "裁牌失败");
                return result;
            }

            // 3. 读牌：模板匹配优先（同客户端渲染，确定性高），未覆盖的牌才走视觉模型兜底
            ensureTemplates();
            List<Integer> labels = new ArrayList<>();
            List<Integer> visionPos = new ArrayList<>();
            List<BufferedImage> visionCrops = new ArrayList<>();
            List<Double> matchScores = new ArrayList<>();
            for (int i = 0; i < crops.size(); i++) {
                int t = templates.match(crops.get(i));
                matchScores.add(templates.matchScore(crops.get(i)));
                if (t >= 0) { labels.add(t); }
                else { labels.add(-1); visionPos.add(i); visionCrops.add(crops.get(i)); }
            }
            result.put("matchScores", matchScores);
            int matched = crops.size() - visionCrops.size();
            System.out.println("[麻将] 模板命中 " + matched + "/" + crops.size());
            if (!visionCrops.isEmpty()) {
                // 视觉兜底：每 5 张一组拼编号小图
                StringBuilder rawAll = new StringBuilder();
                int CHUNK = 5;
                for (int start = 0; start < visionCrops.size(); start += CHUNK) {
                    List<BufferedImage> chunk = visionCrops.subList(start, Math.min(start + CHUNK, visionCrops.size()));
                    int firstNum = visionPos.get(start) + 1;
                    BufferedImage strip = composeStrip(chunk, firstNum);
                    String stripB64 = toJpegBase64(strip);
                    if (stripB64 == null) {
                        result.put("ok", false);
                        result.put("message", "拼图编码失败");
                        return result;
                    }
                    String prompt = "这是一张由 " + chunk.size() + " 张《雀魂麻将》牌面拼成的图，从左到右每张牌正上方标有编号"
                            + "（从 " + firstNum + " 到 " + (firstNum + chunk.size() - 1) + "）。请严格按编号逐张识别牌面，只输出一个JSON数组，"
                            + "长度必须恰好等于 " + chunk.size() + "，例如 [\"3万\",\"9筒\",\"中\",\"5条\"]。"
                            + "允许的牌名：数字+万/条/筒（如 3万、九条、7筒），或数字+m/s/p（3m、9s、7p），字牌用 东/南/西/北/白/发/中。"
                            + "注意区分：一筒是一个大圆圈，九筒是九个圆；一条是一只鸟，九条是九根竹；看清数量再答。看不清的牌对应位置输出 null。"
                            + "不要输出编号以外的任何文字。";
                    String raw = deepSeekService.chatVision(stripB64, prompt);
                    rawAll.append(raw == null ? "(null)\n" : raw + "\n");
                    if (raw == null || raw.trim().isEmpty()) {
                        result.put("ok", false);
                        result.put("message", "视觉模型读牌失败（网络或API问题）");
                        return result;
                    }
                    List<Integer> part = parseLabels(raw, chunk.size());
                    for (int i = 0; i < chunk.size(); i++) {
                        int pos = visionPos.get(start + i);
                        labels.set(pos, i < part.size() ? part.get(i) : null);
                    }
                }
                result.put("rawRead", rawAll.toString());
            }
            // 同牌超过 4 张 = 不可能，判定读牌不可信
            int[] cntCheck = new int[34];
            for (Integer i : labels) if (i != null) cntCheck[i]++;
            boolean unreliable = false;
            for (int v : cntCheck) if (v > 4) { unreliable = true; break; }
            if (unreliable) {
                result.put("ok", false);
                result.put("message", "读牌结果异常（同一张牌超过4张，识别可能出错），请重试或换个角度截屏");
                result.put("labels", labels);
                return result;
            }
            List<Integer> finalLabels = new ArrayList<>(labels);
            result.put("_labels", finalLabels); // 调试/测试用：原始解析索引

            // 4. 组牌：手牌(13) + 摸牌(第14张或raised单张) + 副露
            List<Integer> hand13 = new ArrayList<>(finalLabels.subList(0, Math.min(13, handSlots.size())));
            List<Integer> meldTiles = new ArrayList<>();
            Integer drawn = null;
            if (handSlots.size() >= 14) {
                drawn = finalLabels.get(13);
            } else if (raised.size() == 1) {
                drawn = finalLabels.get(handSlots.size()); // raised 第一张
            }
            for (int i = handSlots.size(); i < finalLabels.size(); i++) meldTiles.add(finalLabels.get(i));
            if (handSlots.size() < 14 && raised.size() > 1) {
                // 多个 raised：最后一个可能是摸牌，其余为副露组
                drawn = finalLabels.get(finalLabels.size() - 1);
                meldTiles = new ArrayList<>(finalLabels.subList(handSlots.size(), finalLabels.size() - 1));
            }

            result.put("hand", labelsToNames(hand13));
            result.put("drawn", drawn == null ? null : TILE_NAMES[drawn]);
            result.put("melds", labelsToNames(meldTiles));
            result.put("meldCount", meldTiles.isEmpty() ? 0 : (meldTiles.size() >= 3 ? meldTiles.size() / 3 : 1));
            // 排序校验：雀魂手牌严格按 万→筒→条→字牌 排序（摸牌除外），违例说明读牌可能出错
            result.put("sortedOk", isSortedHand(hand13));

            // 5. 牌效分析
            Map<String, Object> eval = evaluate(hand13, drawn, meldTiles);
            result.putAll(eval);

            // 6. AI 评论（Arona 人设）
            String comment = comment(eval, hand13, drawn, meldTiles);
            result.put("comment", comment);

            // 日志打印识别结果，便于人工核对准确度
            System.out.println("[麻将] 识别结果 " + (fromFile ? "文件:" + fileName : "实时截屏")
                    + " 模板命中=" + matched + "/" + crops.size()
                    + " | 手牌=" + String.join(" ", labelsToNames(hand13))
                    + (drawn != null ? " | 摸牌=" + TILE_NAMES[drawn] : "")
                    + (meldTiles.isEmpty() ? "" : " | 副露=" + String.join(" ", labelsToNames(meldTiles)))
                    + " | 向听=" + result.get("shanten") + " | 排序校验=" + result.get("sortedOk")
                    + (result.get("comment") != null ? " | 建议评论=" + result.get("comment") : ""));

            return result;
        } catch (Exception e) {
            e.printStackTrace();
            result.put("ok", false);
            result.put("message", "麻将插件异常: " + e.getMessage());
            return result;
        }
    }

    // ===== 截图/加载 =====
    private BufferedImage captureScreen() {
        try {
            Rectangle rect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            return new Robot().createScreenCapture(rect);
        } catch (Exception e) {
            System.out.println("[麻将] 截屏失败: " + e.getMessage());
            return null;
        }
    }

    /** 保存实机截屏到 desktop_vision_log，便于人工核对与诊断 */
    private void saveLiveFrame(BufferedImage img, String fileName) {
        try {
            File dir = new File(System.getProperty("user.dir"), "desktop_vision_log");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "mahjong_live_" + System.currentTimeMillis() + ".png");
            ImageIO.write(img, "png", f);
            System.out.println("[麻将] 实机截屏已保存: " + f.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
        } catch (Exception e) {
            System.out.println("[麻将] 保存截屏失败: " + e.getMessage());
        }
    }

    private BufferedImage loadCalibrationFile(String fileName) {
        try {
            if (fileName == null || fileName.trim().isEmpty()) {
                // 默认用"自己进行游戏"
                fileName = "自己进行游戏.png";
            }
            ClassPathResource res = new ClassPathResource("static/麻将参考截屏/" + fileName);
            if (!res.exists()) {
                // 兼容直接路径
                File f = new File(fileName);
                if (f.exists()) return ImageIO.read(f);
                return null;
            }
            return ImageIO.read(res.getInputStream());
        } catch (Exception e) {
            System.out.println("[麻将] 加载校准图失败: " + e.getMessage());
            return null;
        }
    }

    private BufferedImage toWidth(BufferedImage src, int targetW) {
        // 保留备用：如需归一化几何检测时使用（双三次，避免洗掉窄间隙）
        int w = src.getWidth(), h = src.getHeight();
        int th = Math.max(1, (int) Math.round(h * (double) targetW / w));
        BufferedImage out = new BufferedImage(targetW, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(src, 0, 0, targetW, th, null);
        g.dispose();
        return out;
    }

    // ===== 几何检测 =====
    static class Rect { int x1, y1, x2, y2; Rect(int x1,int y1,int x2,int y2){this.x1=x1;this.y1=y1;this.x2=x2;this.y2=y2;} int w(){return x2-x1+1;} int h(){return y2-y1+1;} }

    static class Detection {
        List<Rect> handSlots = new ArrayList<>();
        List<Rect> raisedTiles = new ArrayList<>();
        int bandY1, bandY2;
        List<Integer> gapMids = new ArrayList<>();
        int tileW;
        Map<String,Object> toDebug() {
            Map<String,Object> m = new LinkedHashMap<>();
            m.put("band", List.of(bandY1, bandY2));
            m.put("tileW", tileW);
            m.put("gapMids", gapMids);
            List<List<Integer>> hs = new ArrayList<>();
            for (Rect r : handSlots) hs.add(List.of(r.x1, r.x2));
            m.put("handSlots", hs);
            List<List<Integer>> rs = new ArrayList<>();
            for (Rect r : raisedTiles) rs.add(List.of(r.x1, r.x2));
            m.put("raised", rs);
            return m;
        }
    }

    /** 归一化图上检测手牌带 + 槽位 + 右侧抬高牌（摸牌/副露） */
    Detection detectTiles(BufferedImage norm) {
        Detection det = new Detection();
        int W = norm.getWidth(), H = norm.getHeight();
        int y0 = (int) (H * 0.60), y1end = H - 1;

        // 1) 行剖面找手牌带（最亮最宽的连续带；阈值 0.20*W，覆盖 830-945 这类真实手牌带）
        int bestY1 = -1, bestY2 = -1, bestSum = -1;
        int[] rowSum = new int[H];
        for (int y = y0; y <= y1end; y++) {
            int c = 0;
            for (int x = 0; x < W; x += 2) if (isBright(norm, x, y)) c++;
            rowSum[y] = c;
        }
        int runStart = -1;
        for (int y = y0; y <= y1end; y++) {
            boolean on = rowSum[y] > W * 0.15;
            if (on && runStart < 0) runStart = y;
            if (!on && runStart >= 0) {
                int sum = 0; for (int yy = runStart; yy < y; yy++) sum += rowSum[yy];
                if (sum > bestSum) { bestSum = sum; bestY1 = runStart; bestY2 = y - 1; }
                runStart = -1;
            }
        }
        if (runStart >= 0) {
            int sum = 0; for (int yy = runStart; yy <= y1end; yy++) sum += rowSum[yy];
            if (sum > bestSum) { bestSum = sum; bestY1 = runStart; bestY2 = y1end; }
        }
        if (bestY1 < 0 || bestY2 - bestY1 < 40) return det;
        det.bandY1 = bestY1; det.bandY2 = bestY2;
        int b1 = bestY1 + 8, b2 = bestY2 - 8;
        if (b2 - b1 < 20) { b1 = bestY1; b2 = bestY2; }

        // 2) 列剖面：暗像素统计（用于找左右边缘 + 间隙间距估计；在原始分辨率下做，避免降采样洗掉窄间隙）
        int samples = 0; for (int y = b1; y <= b2; y += 2) samples++;
        if (samples < 5) samples = 5;
        int[] darkCount = new int[W];
        for (int x = 0; x < W; x++) {
            int c = 0;
            for (int y = b1; y <= b2; y += 2) if (isDark(norm, x, y)) c++;
            darkCount[x] = c;
        }
        List<int[]> gaps = new ArrayList<>(); // [start,end]
        int gs = -1;
        for (int x = 0; x < W; x++) {
            boolean isGapCol = darkCount[x] >= samples * 0.7;
            if (isGapCol && gs < 0) gs = x;
            if (!isGapCol && gs >= 0) { gaps.add(new int[]{gs, x - 1}); gs = -1; }
        }
        if (gs >= 0) gaps.add(new int[]{gs, W - 1});
        // 左边缘结束 / 右边缘开始（贴边的连续暗区）
        int leftEnd = -1, rightStart = -1;
        for (int[] g : gaps) {
            if (g[0] <= 2 && g[1] - g[0] > 20) { leftEnd = g[1]; break; }
        }
        for (int[] g : gaps) {
            if (g[1] >= W - 3 && g[1] - g[0] > 20) { rightStart = g[0]; break; }
        }
        // 内部间隙（不含贴边大暗区）：窄间隙
        List<int[]> inner = new ArrayList<>();
        for (int[] g : gaps) {
            int gw = g[1] - g[0] + 1;
            boolean touchesEdge = g[0] <= 2 || g[1] >= W - 3;
            if (touchesEdge && gw > 20) continue;
            if (gw <= 2) inner.add(g);
            else if (gw <= 40 && !touchesEdge) inner.add(g);
        }
        if (leftEnd < 0 || rightStart < 0 || inner.size() < 3) { det.handSlots = new ArrayList<>(); return det; }
        int[] mids = new int[inner.size()];
        for (int i = 0; i < inner.size(); i++) mids[i] = (inner.get(i)[0] + inner.get(i)[1]) / 2;
        Arrays.sort(mids);
        for (int m : mids) det.gapMids.add(m);
        int[] diffs = new int[mids.length - 1];
        for (int i = 0; i < diffs.length; i++) diffs[i] = mids[i + 1] - mids[i];
        Arrays.sort(diffs);
        int medianSpacing = Math.max(30, diffs[diffs.length / 2]);

        // 3) 双路槽位：间隙序列法 + 回退网格法，选槽数更接近 13-15 的方案
        //    序列法在 2560 原生尺度稳定；1707 降采样下部分画面间隙变糊，序列法会漏检，用网格法兜底
        List<Rect> fbSlots = (leftEnd >= 0 && rightStart >= 0)
                ? fallbackGridSlots(leftEnd, rightStart, medianSpacing, b1, b2, W)
                : new ArrayList<>();
        List<Rect> runSlots = new ArrayList<>();
        int tileW = Math.max(40, medianSpacing); // 默认用中位间距；run 块内会按真实序列重估
        // 连续间隙序列（真实牌分隔）：排除 UI 亮块与暗色牌面造成的假间隙
        List<Integer> run = new ArrayList<>();
        for (int m : mids) {
            if (run.isEmpty()) { run.add(m); continue; }
            int last = run.get(run.size() - 1);
            int sp = m - last;
            if (sp >= medianSpacing * 0.75 && sp <= medianSpacing * 1.35) run.add(m);
            else if (sp > medianSpacing * 1.35) {
                // 大间隔：可能是副露组/UI 间隔，终止序列（右侧多余亮块不再纳入）
                break;
            }
            // sp 过小（假间隙）→ 跳过
        }
        if (run.size() >= 3) {
            int[] runSp = new int[run.size() - 1];
            for (int i = 0; i < runSp.length; i++) runSp[i] = run.get(i + 1) - run.get(i);
            Arrays.sort(runSp);
            tileW = Math.max(40, runSp[runSp.length / 2]); // 真实牌宽（中位间距）
            det.tileW = tileW;
            int x0 = run.get(0) - tileW;
            // 牌数：序列内间隙数+1；若右侧还有一张（摸牌，位于最后间隙之后），+1
            int n = run.size() + 1;
            int lastGap = run.get(run.size() - 1);
            if (n == 13) {
                // 检查最后间隙右侧是否有牌面亮块（摸牌）
                boolean hasRight = false;
                int scanX1 = Math.min(W - 1, lastGap + tileW / 4);
                int scanX2 = Math.min(W - 1, lastGap + tileW * 2);
                for (int x = scanX1; x <= scanX2; x++) {
                    int c = 0;
                    for (int y = b1; y <= b2; y += 2) if (isBright(norm, x, y)) c++;
                    if (c >= 3) { hasRight = true; break; }
                }
                if (hasRight) n = 14;
            }
            if (n < 10 || n > 16) n = run.size() + 1;
            // 建槽：网格 + 吸附到真实间隙位置
            for (int i = 0; i < n; i++) {
                runSlots.add(new Rect(x0 + i * tileW, b1, x0 + (i + 1) * tileW - 1, b2));
            }
            for (int i = 0; i < n - 1 && i < run.size(); i++) {
                int boundary = x0 + (i + 1) * tileW - 1;
                int g = run.get(i);
                if (Math.abs(g - boundary) <= tileW * 0.45) {
                    runSlots.get(i).x2 = g - 1;
                    runSlots.get(i + 1).x1 = g;
                }
            }
        }
        boolean runOk = runSlots.size() >= 13 && runSlots.size() <= 15;
        boolean fbOk = fbSlots.size() >= 13 && fbSlots.size() <= 15;
        List<Rect> chosen;
        if (runOk && fbOk) chosen = runSlots;            // 都合理：序列法对齐更准
        else if (runOk) chosen = runSlots;
        else if (fbOk) chosen = fbSlots;                 // 序列法漏检（1707 降采样）：网格兜底
        else chosen = runSlots.size() >= fbSlots.size() ? runSlots : fbSlots;
        // 槽数 >14（尾部混入 UI 亮块）：取前 14 并按其跨度重拟合牌宽，消除污染
        if (chosen.size() > 14) {
            int span = chosen.get(13).x2 - chosen.get(0).x1 + 1;
            int tw = Math.max(30, span / 14);
            List<Rect> refit = new ArrayList<>();
            int x0r = chosen.get(0).x1;
            for (int i = 0; i < 14; i++) {
                refit.add(new Rect(x0r + i * tw, b1, x0r + (i + 1) * tw - 1, b2));
            }
            chosen = refit;
            System.out.println("[麻将检测] 槽位 " + (runSlots.size() > 14 ? runSlots.size() : fbSlots.size())
                    + " → 重拟合为 14（牌宽 " + tw + "）");
        }
        System.out.println("[麻将检测] 槽位选择: 序列法=" + runSlots.size() + " 网格法=" + fbSlots.size() + " → 取 " + chosen.size());
        if (chosen.isEmpty()) { det.handSlots = new ArrayList<>(); return det; }
        det.handSlots = chosen;

        // 4) 抬高牌检测：紧贴手牌带上方、右缘右侧的亮块（摸牌/副露）；避开按钮区
        int tileH = Math.max(30, bestY2 - bestY1 + 1);
        int rightEdge = chosen.get(chosen.size() - 1).x2;
        int scanY1 = Math.max(0, bestY1 - (int) (tileH * 0.6));
        int scanY2 = bestY1 - 2;
        List<Rect> blobs = findBrightBlobs(norm, rightEdge + 5, scanY1, W - 1, scanY2, tileW);
        det.raisedTiles = blobs;
        return det;
    }

    /** 回退：边缘+拟合网格法（用于观战等间隙不规则的布局；间隙序列法优先） */
    private List<Rect> fallbackGridSlots(int leftEnd, int rightStart, int medianSpacing, int b1, int b2, int W) {
        List<Rect> out = new ArrayList<>();
        int extent = rightStart - leftEnd - 1;
        if (extent <= 0) return out;
        int bestN = 14, bestResid = Integer.MAX_VALUE;
        for (int n = 12; n <= 16; n++) {
            int tw = extent / n;
            if (tw <= 0) continue;
            int resid = Math.abs(tw - medianSpacing);
            if (resid < bestResid) { bestResid = resid; bestN = n; }
        }
        if (bestResid > medianSpacing * 0.45) bestN = 14;
        int tileW = Math.max(40, extent / bestN);
        for (int i = 0; i < bestN; i++) {
            out.add(new Rect(leftEnd + 1 + i * tileW, b1, leftEnd + (i + 1) * tileW, b2));
        }
        return out;
    }

    /** 在归一化图给定区域内找亮色连通块（用于抬高牌），按 x 聚类；宽度需 ≥0.55 牌宽才视为牌 */
    private List<Rect> findBrightBlobs(BufferedImage img, int x1, int y1, int x2, int y2, int tileW) {
        List<Rect> out = new ArrayList<>();
        if (x2 <= x1 || y2 <= y1) return out;
        int minW = Math.max(30, (int) (tileW * 0.55));
        int[] colHit = new int[x2 - x1 + 1];
        for (int x = x1; x <= x2; x++) {
            int c = 0;
            for (int y = y1; y <= y2; y += 2) if (isBright(img, x, y)) c++;
            colHit[x - x1] = (c >= 2) ? 1 : 0;
        }
        int gs = -1;
        for (int i = 0; i < colHit.length; i++) {
            if (colHit[i] == 1 && gs < 0) gs = i;
            if (colHit[i] == 0 && gs >= 0) {
                if (i - gs >= minW) out.add(new Rect(x1 + gs, y1, x1 + i - 1, y2));
                gs = -1;
            }
        }
        if (gs >= 0 && colHit.length - gs >= minW) out.add(new Rect(x1 + gs, y1, x2, y2));
        return out;
    }

    private boolean isBright(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return false;
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b) > BRIGHT;
    }

    private boolean isDark(BufferedImage img, int x, int y) {
        if (x < 0 || y < 0 || x >= img.getWidth() || y >= img.getHeight()) return true;
        int rgb = img.getRGB(x, y);
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return (int) (0.299 * r + 0.587 * g + 0.114 * b) < DARK;
    }

    // ===== 裁牌 =====
    /** 裁出归一化矩形对应的原图区域并放大到统一高度 */
    private BufferedImage cropBuffered(BufferedImage src, Rect normRect, double scale, int targetH) {
        try {
            int x1 = (int) Math.floor(normRect.x1 * scale), x2 = (int) Math.ceil(normRect.x2 * scale);
            int y1 = (int) Math.floor(normRect.y1 * scale), y2 = (int) Math.ceil(normRect.y2 * scale);
            x1 = Math.max(0, x1); y1 = Math.max(0, y1);
            x2 = Math.min(src.getWidth() - 1, x2); y2 = Math.min(src.getHeight() - 1, y2);
            if (x2 <= x1 || y2 <= y1) return null;
            BufferedImage crop = src.getSubimage(x1, y1, x2 - x1 + 1, y2 - y1 + 1);
            int tw = Math.max(1, (int) Math.round(crop.getWidth() * (double) targetH / crop.getHeight()));
            BufferedImage big = new BufferedImage(tw, targetH, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = big.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.drawImage(crop, 0, 0, tw, targetH, null);
            g.dispose();
            return big;
        } catch (Exception e) {
            return null;
        }
    }

    /** 把多张牌拼成一张带编号的长条图（编号从 startNum 开始，白字黑底） */
    private BufferedImage composeStrip(List<BufferedImage> tiles, int startNum) {
        int th = 200, numH = 34, gap = 8, pad = 6;
        int tw = 0;
        for (BufferedImage t : tiles) tw += t.getWidth() + gap;
        int W = tw + pad * 2;
        int H = th + numH + pad * 2;
        BufferedImage out = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(new Color(20, 20, 20));
        g.fillRect(0, 0, W, H);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int x = pad;
        for (int i = 0; i < tiles.size(); i++) {
            BufferedImage t = tiles.get(i);
            g.drawImage(t, x, pad + numH, null);
            g.setColor(Color.WHITE);
            g.setFont(new Font("SansSerif", Font.BOLD, 26));
            String num = String.valueOf(startNum + i);
            int nw = g.getFontMetrics().stringWidth(num);
            g.drawString(num, x + (t.getWidth() - nw) / 2, pad + 24);
            x += t.getWidth() + gap;
        }
        g.dispose();
        return out;
    }

    private String toJpegBase64(BufferedImage img) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "jpg", bos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

    // ===== 读牌解析 =====
    static final Pattern P_MSP = Pattern.compile("([1-9])\\s*([mspMSP])");
    static final Pattern P_CHN = Pattern.compile("([一二三四五六七八九])\\s*([万条筒])");
    static final Pattern P_AN = Pattern.compile("([1-9])\\s*([万条筒])");
    static final Pattern P_HON = Pattern.compile("(东|南|西|北|白|发|中)");
    static final Map<Character, Integer> CN_NUM = new HashMap<>();
    static { String[] s = {"一","二","三","四","五","六","七","八","九"}; for (int i = 0; i < 9; i++) CN_NUM.put(s[i].charAt(0), i + 1); }

    List<Integer> parseLabels(String raw, int expect) {
        List<Integer> out = new ArrayList<>();
        if (raw == null) return out;
        Matcher m = Pattern.compile("\\[.*?\\]").matcher(raw);
        String arr = m.find() ? m.group(0) : raw;
        for (String token : arr.split("[,\\[\\]\"'\\s]+")) {
            if (token.isEmpty()) continue;
            Integer idx = parseOneLabel(token);
            if (idx != null) out.add(idx);
        }
        // 兜底：整体 JSON 数组没匹配到就逐 token 找
        if (out.isEmpty() && !arr.equals(raw)) {
            for (String token : raw.split("[,\\[\\]\"'\\s]+")) {
                if (token.isEmpty()) continue;
                Integer idx = parseOneLabel(token);
                if (idx != null) out.add(idx);
            }
        }
        return out;
    }

    Integer parseOneLabel(String token) {
        Matcher m = P_MSP.matcher(token);
        if (m.find()) {
            int n = m.group(1).charAt(0) - '0';
            char suit = Character.toLowerCase(m.group(2).charAt(0));
            if (suit == 'm') return n - 1;
            if (suit == 's') return 9 + n - 1;
            if (suit == 'p') return 18 + n - 1;
        }
        Matcher c = P_CHN.matcher(token);
        if (c.find()) {
            int n = CN_NUM.getOrDefault(c.group(1).charAt(0), -1);
            char suit = c.group(2).charAt(0);
            if (n > 0 && suit == '万') return n - 1;
            if (n > 0 && suit == '条') return 9 + n - 1;
            if (n > 0 && suit == '筒') return 18 + n - 1;
        }
        Matcher an = P_AN.matcher(token);
        if (an.find()) {
            int n = an.group(1).charAt(0) - '0';
            char suit = an.group(2).charAt(0);
            if (suit == '万') return n - 1;
            if (suit == '条') return 9 + n - 1;
            if (suit == '筒') return 18 + n - 1;
        }
        Matcher h = P_HON.matcher(token);
        if (h.find()) {
            switch (h.group(1)) {
                case "东": return 27;
                case "南": return 28;
                case "西": return 29;
                case "北": return 30;
                case "白": return 31;
                case "发": return 32;
                case "中": return 33;
            }
        }
        return null;
    }

    List<String> labelsToNames(List<Integer> labels) {
        List<String> out = new ArrayList<>();
        for (Integer i : labels) if (i != null && i >= 0 && i < 34) out.add(TILE_NAMES[i]);
        return out;
    }

    /** 雀魂手牌排序键：万(0)<筒(1)<条(2)<字(3)，花色内按大小 */
    static int sortKey(int idx) {
        if (idx < 9) return idx;                    // 万 0-8
        if (idx < 18) return 20 + (idx - 9);        // 条 20-28
        if (idx < 27) return 10 + (idx - 18);       // 筒 10-18
        return 30 + (idx - 27);                     // 字 30-36
    }

    /** 13 张手牌是否严格按排序键非降序（含 null 视为不确定，返回 false） */
    boolean isSortedHand(List<Integer> hand) {
        int prev = -1;
        for (Integer i : hand) {
            if (i == null || i < 0 || i >= 34) return false;
            int k = sortKey(i);
            if (k < prev) return false;
            prev = k;
        }
        return true;
    }

    // ===== 牌效分析 =====
    /** 手牌 13 + 摸牌 + 副露牌；返回 shanten/听牌/推荐 */
    Map<String, Object> evaluate(List<Integer> hand13, Integer drawn, List<Integer> meldTiles) {
        Map<String, Object> out = new LinkedHashMap<>();
        int meldSets = meldTiles.size() / 3; // 副露完整组数（近似）
        int[] base = new int[34];
        for (Integer i : hand13) if (i != null) base[i]++;
        // 14 张 = 13手牌 + 摸牌（若有）
        int[] full14 = base.clone();
        if (drawn != null) full14[drawn]++;
        boolean hasDrawn = drawn != null;

        if (!hasDrawn) {
            // 没摸牌：可能是等待/观战无焦点，只报手牌
            int sh = shanten13(base, meldSets);
            out.put("shanten", sh);
            out.put("tenpai", sh <= 0);
            out.put("suggestions", new ArrayList<>());
            out.put("note", "未检测到摸牌位（可能不是你的回合）");
            return out;
        }

        // 每个候选打出牌 → 13 张向听 + 进张
        List<Map<String, Object>> cands = new ArrayList<>();
        for (int d = 0; d < 34; d++) {
            if (full14[d] == 0) continue;
            int[] h13 = full14.clone();
            h13[d]--;
            int sh = shanten13(h13, meldSets);
            int ukeire = ukeire(h13, meldSets);
            cands.add(buildSuggestion(d, sh, ukeire));
        }
        cands.sort((a, b) -> {
            int c = Integer.compare((Integer) a.get("shanten"), (Integer) b.get("shanten"));
            if (c != 0) return c;
            return Integer.compare((Integer) b.get("ukeire"), (Integer) a.get("ukeire"));
        });
        int bestSh = (Integer) cands.get(0).get("shanten");
        List<Map<String, Object>> top = cands.size() > 3 ? new ArrayList<>(cands.subList(0, 3)) : cands;
        out.put("shanten", bestSh);
        out.put("tenpai", bestSh == 0);
        out.put("win", bestSh < 0);
        out.put("suggestions", top);
        return out;
    }

    private Map<String, Object> buildSuggestion(int tileIdx, int shanten, int ukeire) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("idx", tileIdx);
        m.put("tile", TILE_NAMES[tileIdx]);
        m.put("shanten", shanten);
        m.put("ukeire", ukeire);
        return m;
    }

    /** 进张数：打出后 13 张手牌里，摸到什么牌能降低向听数 */
    private int ukeire(int[] h13, int meldSets) {
        int base = shanten13(h13, meldSets);
        int sum = 0;
        for (int t = 0; t < 34; t++) {
            if (h13[t] >= 4) continue;
            int[] h = h13.clone();
            h[t]++;
            if (shanten13(h, meldSets) < base) sum += (4 - h13[t]);
        }
        return sum;
    }

    // ===== 向听数 =====
    private int bestShanten = 99;

    /** 测试用公开入口 */
    public int shanten13ForTest(int[] counts, int meldSets) {
        return shanten13(counts, meldSets);
    }

    private int shanten13(int[] counts, int meldSets) {
        int needSets = 4 - meldSets; // 副露组已构成面子
        if (needSets < 0) needSets = 0;
        bestShanten = 99;
        dfsShanten(counts.clone(), 0, 0, 0, false, needSets, 0);
        int normal = bestShanten;
        int chiitoi = chiitoiShanten(counts);
        int kokushi = kokushiShanten(counts);
        return Math.min(normal, Math.min(chiitoi, kokushi));
    }

    private void dfsShanten(int[] c, int idx, int sets, int taatsu, boolean pair, int needSets, int depth) {
        if (depth > 60) return; // 34 个索引推进 + 消费，60 足够
        if (sets + taatsu > needSets) return;
        if (idx >= 34) {
            int sh = (2 * needSets) - 2 * sets - taatsu - (pair ? 1 : 0);
            if (!pair && sets + taatsu < needSets) sh += 1;
            if (sh < bestShanten) bestShanten = sh;
            return;
        }
        int cnt = c[idx];
        if (cnt == 0) { dfsShanten(c, idx + 1, sets, taatsu, pair, needSets, depth + 1); return; }
        // 刻子
        if (cnt >= 3) {
            c[idx] -= 3;
            dfsShanten(c, idx, sets + 1, taatsu, pair, needSets, depth + 1);
            c[idx] = cnt;
        }
        // 顺子（仅数牌）
        if (isNumbered(idx) && cnt >= 1 && idx + 2 < 34 && c[idx + 1] >= 1 && c[idx + 2] >= 1
                && sameSuit(idx, idx + 2)) {
            c[idx]--; c[idx + 1]--; c[idx + 2]--;
            dfsShanten(c, idx, sets + 1, taatsu, pair, needSets, depth + 1);
            c[idx]++; c[idx + 1]++; c[idx + 2]++;
        }
        // 对子（作为雀头）
        if (cnt >= 2 && !pair) {
            c[idx] -= 2;
            dfsShanten(c, idx, sets, taatsu, true, needSets, depth + 1);
            c[idx] = cnt;
        }
        // 对子作为搭子
        if (cnt >= 2) {
            c[idx] -= 2;
            dfsShanten(c, idx, sets, taatsu + 1, pair, needSets, depth + 1);
            c[idx] = cnt;
        }
        // 两面搭子
        if (isNumbered(idx) && cnt >= 1 && idx + 1 < 34 && c[idx + 1] >= 1 && sameSuit(idx, idx + 1)) {
            c[idx]--; c[idx + 1]--;
            dfsShanten(c, idx, sets, taatsu + 1, pair, needSets, depth + 1);
            c[idx]++; c[idx + 1]++;
        }
        // 嵌张搭子
        if (isNumbered(idx) && cnt >= 1 && idx + 2 < 34 && c[idx + 2] >= 1 && sameSuit(idx, idx + 2)) {
            c[idx]--; c[idx + 2]--;
            dfsShanten(c, idx, sets, taatsu + 1, pair, needSets, depth + 1);
            c[idx]++; c[idx + 2]++;
        }
        // 单张浮牌（跳过）
        c[idx]--;
        dfsShanten(c, idx + 1, sets, taatsu, pair, needSets, depth + 1);
        c[idx] = cnt;
    }

    private boolean isNumbered(int i) { return i < 27; }
    private boolean sameSuit(int a, int b) { return (a < 9 && b < 9) || (a >= 9 && a < 18 && b >= 9 && b < 18) || (a >= 18 && a < 27 && b >= 18 && b < 27); }

    private int chiitoiShanten(int[] c) {
        int pairs = 0, unpaired = 0;
        for (int i = 0; i < 34; i++) {
            if (c[i] >= 2) pairs++;
            else if (c[i] == 1) unpaired++;
        }
        int spare = 0;
        for (int i = 0; i < 34; i++) if (c[i] >= 2) spare += c[i] - 2;
        int sh = 6 - pairs;
        if (unpaired == 0 && spare > 0) sh += 1;
        return sh;
    }

    private int kokushiShanten(int[] c) {
        int[] kinds = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33};
        int distinct = 0;
        boolean hasPair = false;
        for (int k : kinds) {
            if (c[k] > 0) distinct++;
            if (c[k] >= 2) hasPair = true;
        }
        return 13 - distinct - (hasPair ? 1 : 0);
    }

    // ===== AI 评论 =====
    private String comment(Map<String, Object> eval, List<Integer> hand13, Integer drawn, List<Integer> meldTiles) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("手牌:").append(String.join(" ", labelsToNames(hand13)));
            if (drawn != null) sb.append("  摸牌:").append(TILE_NAMES[drawn]);
            if (!meldTiles.isEmpty()) sb.append("  副露:").append(String.join(" ", labelsToNames(meldTiles)));
            sb.append("  向听数:").append(eval.get("shanten"));
            Object win = eval.get("win");
            if (Boolean.TRUE.equals(win)) sb.append("（已和了！）");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sugs = (List<Map<String, Object>>) eval.get("suggestions");
            if (sugs != null && !sugs.isEmpty()) {
                sb.append("  建议打出:");
                for (Map<String, Object> s : sugs) sb.append(s.get("tile")).append("(进张").append(s.get("ukeire")).append(") ");
            }
            String system = "你是桌宠阿罗娜（Blue Archive 的阿罗娜），元气、嘴甜、偶尔吐槽。主人正在玩《雀魂麻将》日麻，你刚看到他的牌。"
                    + "请用 1-3 句中文给出简短点评：明确建议打哪张、为什么（看进张数/向听数/牌型），或夸好牌/指出风险。不要复述整手牌，不要用列表，像朋友说话一样。";
            return deepSeekService.chatPlain(system, sb.toString());
        } catch (Exception e) {
            return null;
        }
    }
}
