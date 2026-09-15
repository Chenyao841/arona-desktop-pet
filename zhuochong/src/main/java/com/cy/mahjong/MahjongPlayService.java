package com.cy.mahjong;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 麻将实操（自动打牌）服务：开局（截屏或手动注入）→ 状态机跟踪手牌 →
 * 轮询摸牌槽位检测进张 → 模板识别摸到的牌 → 向听/进张决策 → 按槽位点击出牌。
 * 回合内零全屏视觉（仅初始/副露后重同步）；点击前不遮挡（用户自行挪开桌宠对话框）。
 */
@Service
public class MahjongPlayService {

    @Autowired
    private MahjongPluginService plugin;

    @Autowired
    private com.cy.service.DeepSeekService deepSeekService;

    /** 固定槽位（2560 物理坐标，来自校准截屏检测；运行时按屏幕比例映射到实机坐标） */
    private static final int[][] FIXED_SLOTS_2560 = {
            {297, 434}, {435, 572}, {573, 710}, {711, 848}, {849, 986}, {987, 1124}, {1125, 1262},
            {1263, 1400}, {1401, 1538}, {1539, 1676}, {1677, 1814}, {1815, 1952}, {1953, 2090}, {2091, 2228}
    };
    /** 摸牌位（固定，与手牌之间有间隔）：2560 坐标 [x1,x2,y1,y2]，由人工标注验证（摸牌61%亮/未摸牌0%亮） */
    private static final int[] FIXED_DRAWN_2560 = {1967, 2116, 1298, 1528};
    /** 操作选项键位槽（2560 基准）[x1,x2,y1,y2]：键1=右起第一个（永远=跳过），键2/键3 在其左；各键同高同宽 */
    private static final int[][] FIXED_BTN_SLOTS_2560 = {
            {1589, 1890, 1117, 1245},  // 键1 跳过
            {1197, 1523, 1117, 1245},  // 键2（立直 / 自摸 / 和 / 碰）
            {802, 1126, 1117, 1245}    // 键3（与键2 自摸共存时的立直）
    };
    private volatile int[][] btnSlots2560 = cloneSlots(FIXED_BTN_SLOTS_2560);
    /** 阶段二动作状态 */
    private volatile boolean riichiArmed = false; // 已点立直键，待打出最后一张（或下一张摸牌）完成立直
    private volatile long postWinUntil = 0;       // 胡牌后结算窗口：短暂存帧供校准，随后停表
    private volatile boolean winWasTsumo = false;
    private volatile boolean roundEnded = false;  // 本局结束（我方未胡）：手牌区亮度骤降判定
    private volatile int endStreak = 0;           // 手牌区消失连续轮询计数
    private volatile boolean awaitNextRound = false; // 用户点「下一局」：检测到新配牌自动继续
    private volatile int handBackStreak = 0;      // 局终后手牌区重现计数（awaitNextRound 时自动恢复用）
    private volatile String[] pendingDialog = null; // 待弹出快捷选项（下一局/先暂停），由页面状态轮询弹出
    private volatile int dialogSeq = 0;           // 选项版本号（页面据此检测新选项）
    // —— 自动开局待确认（打把麻将 → 播报手牌 → 用户 无误/有误+正确牌型）——
    private volatile boolean awaitingConfirm = false;
    private volatile List<Integer> pendingHand13 = null;
    private volatile Integer pendingDrawn = null;
    private volatile BufferedImage pendingFrame = null; // 开局读取的整帧（有误纠正时补素材库）
    private volatile java.util.List<MahjongPluginService.Rect> pendingSlotRectsLive = null; // 读取时用的13槽 live 坐标（按槽切错题本）
    private volatile String pendingReadDesc = "";        // 读成的手牌描述（错读对照记录用）
    // —— 合作模式（默认关）：吃碰杠不再自动跳过，请求并等待用户指令 ——
    private volatile boolean coopMode = false;
    private volatile boolean coopWaiting = false;        // 当前有可碰/吃/杠反应条，等待用户指令
    private volatile boolean discardAfterMeld = false;   // 桌宠代点吃/碰后：摸牌区空，需按剩余手牌直接弃一张
    private volatile long meldActAt = 0;                 // 最近一次副露操作时刻（弃牌前稍等动画）
    private volatile long lastBarActAt = 0;       // 上次操作键点击时间（防 300ms 轮询重复点键）
    private volatile long lastBtnDiagLog = 0;     // 上次"键位无法识别"诊断日志时间（节流）
    private volatile long barSeenAt = 0;          // 选项栏首次出现时刻（滑入稳定窗，防第一帧误判/误点）
    /** 标注区域文件（人工标注页面写入，运行时优先于固定值） */
    private static final File REGIONS_FILE = new File(System.getProperty("user.dir"),
            "src/main/resources/static/麻将参考截屏/regions.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private volatile int[] drawnRegion2560 = FIXED_DRAWN_2560.clone();
    /** 桌宠模型区域（标注「桌宠」），用于摸牌区/扫描自动避让；-1=未标注 */
    private volatile int petX1_2560 = -1;
    /** 手牌槽位来自人工标注（手牌区 13 等分）时为 true，initGeometry 不再覆盖 */
    private volatile boolean annotatedHand = false;
    private static final int FIXED_BAND_Y1_2560 = 1319, FIXED_BAND_Y2_2560 = 1512;
    private static final int GEOM_W = 2560, GEOM_H = 1600; // 几何基准尺度（校准图物理分辨率）

    private static int[][] cloneSlots(int[][] src) {
        int[][] out = new int[src.length][];
        for (int i = 0; i < src.length; i++) out[i] = src[i].clone();
        return out;
    }

    private final MahjongGameState state = new MahjongGameState();
    private volatile boolean playing = false;
    private volatile boolean armed = false;      // 摸牌槽为空、等待进张
    private volatile boolean needsResync = false; // 副露后需要重读画面
    /** 槽位几何（2560 基准坐标）；实机点击/裁牌按 scaleX/scaleY 映射 */
    private List<MahjongPluginService.Rect> slots2560 = new ArrayList<>();
    private int bandY1 = FIXED_BAND_Y1_2560, bandY2 = FIXED_BAND_Y2_2560;
    private double scaleX = 1.0, scaleY = 1.0;
    private volatile String lastAction = "";
    private volatile String lastHandDump = "";
    private volatile long lastClickAt = 0;
    private volatile long discardDoneAt = 0;  // 上次出牌点击时刻：出牌动画/残影冷却用（期间摸牌位内容不可信）
    private volatile boolean sawEmptyAfterClick = false;
    private volatile boolean autoStuck = false; // 连续误判后暂停自动操作
    private int brightUnreadableCount = 0;      // 摸牌位持续亮但读不出牌型的计数
    private volatile int lastDiscardedTile = -1;
    private volatile int lastClickX = -1, lastClickY = -1;
    private volatile int discardRetries = 0;
    private volatile int ambiguityStage = 0; // 摸牌识别不确定分级：0=正常 / 1=首次不确定(静默等稳定) / 2=待自动重试 / 3=已提示手动报牌(20s慢重试)
    private volatile long ambiguousSince = 0;
    private int brightStable = 0;            // 摸牌位连续亮轮询数（进张动画稳定后再读，避免裁到移动中的牌）
    private volatile long armedSince = 0;    // 进入"等待摸牌"的时刻（播报等待秒数用）
    private volatile boolean screenAnomaly = false; // 画面健全性异常中（游戏不在前台/被遮挡）
    // —— 摸牌读取记录（供「误报 X」错题库 / 诊断；保留最近若干次，滞后几回合也能标对）——
    private volatile int lastReadTile = -99;         // 最近一次读取结果（-2=不确定）
    private volatile long lastReadAt = 0;
    private volatile String lastReadPath = "";       // 读取路径：模板高置信/参考比对/双确认/模板兜底/无法确定
    private volatile String lastReadTop = "";        // 模板前3候选:牌名(分)
    private volatile BufferedImage lastCropImage = null; // 该次读取的裁图（误报时存入错题库）
    private final List<ReadRecord> readHistory = java.util.Collections.synchronizedList(new ArrayList<>());
    private static final int READ_HISTORY_MAX = 12;

    /** 一次摸牌读取记录（误报匹配用） */
    private static class ReadRecord {
        final long time; final int tile; final String path; final String top; final BufferedImage crop;
        volatile boolean marked; // 已被「误报」认领
        ReadRecord(long time, int tile, String path, String top, BufferedImage crop) {
            this.time = time; this.tile = tile; this.path = path; this.top = top; this.crop = crop;
        }
    }

    private static final File MISREPORT_DIR = new File(System.getProperty("user.dir"),
            "src/main/resources/static/麻将错题截屏");
    private static final Object MIS_LOCK = new Object(); // 错题记录文件写入锁（防并发写串行）
    private volatile boolean resyncPending = false;     // 刚点过出牌，待槽空后以屏幕为真值重读手牌（自愈摸牌误读漂移）
    private int emptyStreak = 0;                        // 槽空连续轮询数（出牌动画/手牌重排稳定后触发重同步）
    private ScheduledExecutorService poller = null;
    private Robot robot = null;

    // ===== 启动 =====
    public Map<String, Object> start(boolean manual, String tilesText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            stop();
            riichiArmed = false;
            postWinUntil = 0;
            lastBarActAt = 0;
            loadRegions(); // 应用人工标注区域（摸牌区/操作键位等）
            if (manual) {
                Map<String, Object> parsed = parseManualTiles(tilesText);
                if (parsed.get("error") != null) { out.put("ok", false); out.put("message", parsed.get("error")); return out; }
                @SuppressWarnings("unchecked")
                List<Integer> hand13 = (List<Integer>) parsed.get("hand");
                Integer drawn = (Integer) parsed.get("drawn");
                state.init(hand13, drawn);
                boolean geo = initGeometry();
                return launchPlay("手动开局：手牌=" + dumpHand() + (drawn != null ? " 摸牌=" + plugin.tileName(drawn) : "")
                        + (geo ? "（槽位 " + slots2560.size() + "）" : "（未检测到手牌区：请确认雀魂在前台）"));
            }
            // —— 自动开局：读牌 → 进入待确认（不启动轮询/不出牌），用户确认后才开始 ——
            Object[] got = readOpeningHand();
            if (got == null) {
                out.put("ok", false);
                out.put("message", "开局读牌重试后仍不成功（读不齐或牌序混乱，多为单张误读/把摸牌混入手牌）——请手动输入：打把麻将 123/456/789/东南西+北（+后为摸牌）");
                return out;
            }
            @SuppressWarnings("unchecked")
            List<Integer> hand13 = (List<Integer>) got[0];
            Integer drawn = (Integer) got[1];
            awaitingConfirm = true;
            pendingHand13 = new ArrayList<>(hand13);
            pendingDrawn = drawn;
            // pendingFrame/pendingSlotRectsLive 已由 readOpeningHand 记录（同一读取帧）
            StringBuilder sb = new StringBuilder();
            for (Integer t : hand13) sb.append(plugin.tileName(t)).append(" ");
            pendingReadDesc = sb.toString().trim() + (drawn != null ? " +摸牌" + plugin.tileName(drawn) : "");
            String msg = "我读到的手牌：" + pendingReadDesc
                    + "。无误请说「无误」开始；有误请说「有误 +正确牌型」（例：有误 123/456/789/东南西+北）";
            System.out.println("[麻将实操] " + msg);
            out.put("ok", true);
            out.put("awaitingConfirm", true);
            out.put("message", msg);
            return out;
        } catch (Exception e) {
            e.printStackTrace();
            out.put("ok", false);
            out.put("message", "启动异常: " + e.getMessage());
            return out;
        }
    }

    /** 读当前屏幕手牌（analyze + 模板补齐空槽 + 最多3次重试），返回 {hand13, drawn}；失败返回 null */
    /**
     * 开局读牌：纯模板优先（与摸牌区同款机制）——把 13 个手牌槽位逐一裁图 → 模板匹配（秒级、零 AI 依赖）。
     * 模板漏读的个别槽位用单张视觉补读（AI 不可用/欠费时自动跳过，不影响纯模板路径）；
     * 牌序校验（雀魂手牌恒有序）通过才算成功；最多重试 3 次。
     */
    private Object[] readOpeningHand() {
        initGeometry(); // 设置 scaleX/Y 与槽位（标注优先/检测兜底）；无缩放时 2560 坐标会错误映射到实机
        if (slots2560.size() < 13) {
            System.out.println("[麻将实操] 槽位几何不足（" + slots2560.size() + "），无法模板读开局");
            return null;
        }
        List<MahjongPluginService.Rect> slotRects = new ArrayList<>(slots2560.subList(0, 13));
        for (int attempt = 1; attempt <= 3; attempt++) {
            BufferedImage screen = captureScreen();
            if (screen == null) {
                sleepBrief(1100);
                continue;
            }
            List<Integer> raw13 = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                MahjongPluginService.Rect live = toLiveRect(slotRects.get(i));
                BufferedImage crop = plugin.cropForPlay(screen, live, 200);
                int t = crop == null ? -1 : plugin.matchTile(crop);
                raw13.add(t >= 0 ? t : null);
            }
            // 摸牌位（第14张，若本回合已摸）：固定摸牌框模板读
            Integer dd = null;
            MahjongPluginService.Rect drr = drawnRectLive();
            if (drr != null) {
                BufferedImage crop = plugin.cropForPlay(screen, drr, 200);
                if (crop != null) {
                    int t = plugin.matchTile(crop);
                    if (t >= 0) dd = t;
                }
            }
            int good = 0;
            for (Integer v : raw13) if (v != null) good++;
            if (good < 13) {
                // 模板漏读的槽位：单张视觉补读（AI 欠费/不可用时返回 null，不影响纯模板路径）
                for (int i = 0; i < 13; i++) {
                    if (raw13.get(i) != null) continue;
                    try {
                        MahjongPluginService.Rect live = toLiveRect(slotRects.get(i));
                        BufferedImage crop = plugin.cropForPlay(screen, live, 200);
                        if (crop == null) continue;
                        String b64 = plugin.toJpegBase64Public(crop);
                        String resp = deepSeekService.chatVision(b64,
                                "这是一张《雀魂麻将》手牌单张的放大图。请只回答牌名（如：三万/九筒/五条/白/中）。不要任何其他文字。");
                        Integer tv = resp == null ? null : plugin.parseTileToken(extractTileName(resp));
                        if (tv != null) {
                            raw13.set(i, tv);
                            System.out.println("[麻将实操] 槽位" + i + " 模板未中，视觉补读为 " + plugin.tileName(tv));
                        }
                    } catch (Exception e) { /* AI 不可用则跳过 */ }
                }
            }
            good = 0;
            for (Integer v : raw13) if (v != null && v >= 0 && v < 34) good++;
            if (good == 13) {
                // 牌序校验：雀魂手牌恒为有序（万→筒→条→字升序）；降序=读错（混入摸牌/错位）→ 判失败重试
                if (!plugin.isSortedHand(raw13)) {
                    StringBuilder sbBad = new StringBuilder();
                    for (Integer v : raw13) sbBad.append(v == null ? "?" : plugin.tileName(v)).append(" ");
                    System.out.println("[麻将实操] 开局读牌牌序混乱（读错）：" + sbBad.toString().trim());
                } else {
                    List<Integer> hand13 = new ArrayList<>();
                    for (Integer v : raw13) hand13.add(v);
                    // 记录本次用的槽位（live 坐标）与画面，供「有误+正确牌型」时按槽切错题本
                    pendingSlotRectsLive = new ArrayList<>();
                    for (int i = 0; i < 13; i++) pendingSlotRectsLive.add(toLiveRect(slotRects.get(i)));
                    try { pendingFrame = screen; } catch (Exception e2) { pendingFrame = null; }
                    System.out.println("[麻将实操] 开局读牌成功（模板为主）：13张 第" + attempt + "次"
                            + (dd != null ? "，摸牌=" + plugin.tileName(dd) : "（无摸牌）"));
                    return new Object[]{hand13, dd};
                }
            } else {
                System.out.println("[麻将实操] 开局读牌第" + attempt + "次不完整（有效 " + good + "/13），稍候重试…");
            }
            sleepBrief(1200);
        }
        return null;
    }

    private void sleepBrief(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
    }

    /** 下一局读牌→确认提示（供 nextRound 配牌出现后调用；不清空已有 pending 冲突，调用前应清理） */
    private boolean readNextAndAskConfirm() {
        Object[] got = readOpeningHand();
        if (got == null) return false;
        @SuppressWarnings("unchecked")
        List<Integer> hand13 = (List<Integer>) got[0];
        Integer drawn = (Integer) got[1];
        state.init(hand13, drawn); // 清上轮数据
        awaitingConfirm = true;
        roundEnded = false; // 已读到配牌，离开局终待命；确认前由 awaitingConfirm 拦停动作
        pendingHand13 = new ArrayList<>(hand13);
        pendingDrawn = drawn;
        // pendingFrame/pendingSlotRectsLive 已由 readOpeningHand 记录
        StringBuilder sb = new StringBuilder();
        for (Integer t : hand13) sb.append(plugin.tileName(t)).append(" ");
        pendingReadDesc = sb.toString().trim() + (drawn != null ? " +摸牌" + plugin.tileName(drawn) : "");
        String msg = "下一局手牌：" + pendingReadDesc
                + "。无误请说「无误」开始；有误请说「有误 +正确牌型」（例：有误 123/456/789/东南西+北）";
        lastAction = msg;
        System.out.println("[麻将实操] " + msg);
        return true;
    }

    /** 开局启动公共尾部：清确认态 → 开轮询 → 若已带摸牌立即开局出牌 */
    private Map<String, Object> launchPlay(String msg) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (slots2560.isEmpty()) initGeometry();
        awaitingConfirm = false;
        pendingHand13 = null;
        pendingDrawn = null;
        pendingFrame = null;
        pendingSlotRectsLive = null;
        pendingReadDesc = "";
        discardAfterMeld = false;
        roundEnded = false;
        awaitNextRound = false;
        endStreak = 0;
        handBackStreak = 0;
        out.put("ok", true);
        out.put("playing", true);
        out.put("message", msg);
        playing = true;
        autoStuck = false;
        lastAction = msg;
        checkPetControl(); // 开局检查桌宠控制端口（点击穿透依赖它）
        if (poller == null) startPoller(); // 已运行的（下一局续打等）不重复起轮询
        if (state.drawn != null && !slots2560.isEmpty()) {
            actDiscard("开局");
        } else if (state.drawn != null) {
            lastAction = "槽位几何未就绪（请确认雀魂在前台后重试）";
        }
        System.out.println("[麻将实操] " + msg);
        return out;
    }

    /** 开局确认「无误」：以读取结果开局 */
    public Map<String, Object> confirmOk() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!awaitingConfirm || pendingHand13 == null) {
                out.put("ok", false);
                out.put("message", "当前没有待确认的开局（请先说「打把麻将」）");
                return out;
            }
            List<Integer> hand13 = new ArrayList<>(pendingHand13);
            Integer drawn = pendingDrawn;
            state.init(hand13, drawn);
            boolean geo = initGeometry();
            return launchPlay("开局确认：手牌=" + dumpHand() + (drawn != null ? " 摸牌=" + plugin.tileName(drawn) : "")
                    + (geo ? "（槽位 " + slots2560.size() + "）" : "（槽位几何未就绪）"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "确认失败: " + e.getMessage());
        }
        return out;
    }

    /** 开局确认「有误 + 正确牌型」：纠正帧+真值入素材库、错读对照入错题本，并以正确牌型开局 */
    public Map<String, Object> confirmWrong(String tilesText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!awaitingConfirm) {
                out.put("ok", false);
                out.put("message", "当前没有待确认的开局（请先说「打把麻将」）");
                return out;
            }
            Map<String, Object> parsed = parseManualTiles(tilesText);
            if (parsed.get("error") != null) { out.put("ok", false); out.put("message", parsed.get("error")); return out; }
            @SuppressWarnings("unchecked")
            List<Integer> hand13 = (List<Integer>) parsed.get("hand");
            Integer drawn = (Integer) parsed.get("drawn");
            try { saveCalibrationFrame(hand13, drawn); } catch (Exception e3) {
                System.out.println("[麻将实操] 开局纠正素材保存失败: " + e3.getMessage());
            }
            // 按 13 槽切分错题本：每槽裁图 + 正确牌名（按屏幕排序对齐槽位）→ 模板库按槽视图自学习
            try { saveSlotMisreports(hand13); } catch (Exception e6) {
                System.out.println("[麻将实操] 开局按槽错题保存失败: " + e6.getMessage());
            }
            try {
                StringBuilder rb = new StringBuilder();
                for (Integer t : hand13) rb.append(plugin.tileName(t)).append(" ");
                if (drawn != null) rb.append("+").append(plugin.tileName(drawn));
                String line = String.format(java.util.Locale.ROOT,
                        "[%s] 开局误读：读成=%s | 实际=%s",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()),
                        pendingReadDesc, rb.toString().trim());
                appendMisrecord(line);
                System.out.println("[麻将实操] 错题库记录: " + line);
            } catch (Exception e4) { /* ignore */ }
            state.init(hand13, drawn);
            boolean geo = initGeometry();
            return launchPlay("已按正确牌型开局：手牌=" + dumpHand() + (drawn != null ? " 摸牌=" + plugin.tileName(drawn) : "")
                    + (geo ? "（槽位 " + slots2560.size() + "）" : ""));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "纠正失败: " + e.getMessage());
        }
        return out;
    }

    /** 纠正帧 + 真值 → 参考截屏目录（自己玩N.png + 自动校准.txt），模板库重建时自动学习 */
    private void saveCalibrationFrame(List<Integer> hand13, Integer drawn) throws Exception {
        if (pendingFrame == null) return;
        File dir = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将参考截屏");
        if (!dir.exists()) return;
        int next = 7;
        File[] fs = dir.listFiles((d, n) -> n != null && n.matches("自己玩\\d+\\.png"));
        if (fs != null) {
            for (File f : fs) {
                java.util.regex.Matcher mm = java.util.regex.Pattern.compile("自己玩(\\d+)\\.png").matcher(f.getName());
                if (mm.find()) next = Math.max(next, Integer.parseInt(mm.group(1)) + 1);
            }
        }
        File png = new File(dir, "自己玩" + next + ".png");
        ImageIO.write(pendingFrame, "png", png);
        StringBuilder sb = new StringBuilder();
        for (Integer t : hand13) sb.append(plugin.tileName(t)).append(" ");
        String line = next + "：" + sb.toString().trim() + (drawn != null ? "+" + plugin.tileName(drawn) : "");
        File txt = new File(dir, "自动校准.txt");
        synchronized (MIS_LOCK) {
            try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(txt, true), java.nio.charset.StandardCharsets.UTF_8)) {
                w.write(line + System.lineSeparator());
            }
        }
        System.out.println("[麻将实操] 开局纠正已入素材库: " + png.getName() + " <- " + line);
    }

    /** 按 13 槽切错题本：用读取帧 + 读取时槽位，把每槽裁图按"正确牌名（屏幕排序对齐）"存为
     *  手牌槽_{i}（{牌名}）_{ts}.png —— 模板库重建时按槽视图自学习（对齐固定槽位，几何一致） */
    private void saveSlotMisreports(List<Integer> correctHand13) throws Exception {
        if (pendingFrame == null || pendingSlotRectsLive == null || pendingSlotRectsLive.size() < 13) return;
        List<Integer> sorted = new ArrayList<>(correctHand13);
        sorted.sort((a, b) -> Integer.compare(MahjongPluginService.sortKey(a), MahjongPluginService.sortKey(b)));
        if (sorted.size() != 13) return;
        if (!MISREPORT_DIR.exists()) MISREPORT_DIR.mkdirs();
        String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
        int saved = 0;
        for (int i = 0; i < 13; i++) {
            MahjongPluginService.Rect live = pendingSlotRectsLive.get(i);
            BufferedImage crop = plugin.cropForPlay(pendingFrame, live, 200);
            if (crop == null) continue;
            File f = new File(MISREPORT_DIR, "手牌槽_" + i + "（" + plugin.tileName(sorted.get(i)) + "）_" + ts + ".png");
            ImageIO.write(crop, "png", f);
            saved++;
        }
        System.out.println("[麻将实操] 开局按槽错题保存 " + saved + "/13 张（手牌槽_i（牌名）_ts.png）");
    }

    /** 用稳定手牌模板按槽补齐 analyze 视觉漏读的空槽（slotRects 为原生坐标，0 起与手牌槽对齐） */
    private void fillNullHandSlots(Map<String, Object> r, List<Integer> raw13) {
        try {
            Object sr = r.get("slotRects");
            if (!(sr instanceof List)) return;
            List<?> rects = (List<?>) sr;
            BufferedImage scr = captureScreen();
            if (scr == null) return;
            for (int i = 0; i < raw13.size() && i < rects.size(); i++) {
                Integer cur = raw13.get(i);
                if (cur != null && cur >= 0 && cur < 34) continue;
                Object ro = rects.get(i);
                if (!(ro instanceof List) || ((List<?>) ro).size() < 4) continue;
                List<?> rc = (List<?>) ro;
                MahjongPluginService.Rect rect = new MahjongPluginService.Rect(
                        ((Number) rc.get(0)).intValue(), ((Number) rc.get(2)).intValue(),
                        ((Number) rc.get(1)).intValue(), ((Number) rc.get(3)).intValue());
                BufferedImage crop = plugin.cropForPlay(scr, rect, 200);
                if (crop != null) {
                    int t = plugin.matchTile(crop);
                    if (t >= 0) {
                        raw13.set(i, t);
                        System.out.println("[麻将实操] 槽位" + i + " 视觉漏读，模板补齐为 " + plugin.tileName(t));
                    }
                }
            }
        } catch (Exception e) {
            /* 补齐失败则走重试 */
        }
    }

    // ===== 几何：人工标注优先；否则运行时升采样检测，固定槽位兜底 =====
    private boolean initGeometry() {
        try {
            BufferedImage screen = captureScreen();
            if (screen == null) return false;
            scaleX = screen.getWidth() / (double) GEOM_W;
            scaleY = screen.getHeight() / (double) GEOM_H;
            if (annotatedHand) {
                // 手牌槽位来自人工标注（loadRegions 已设置），保留；仅更新缩放比例。
                // 副露后行按原网格左对齐压缩（右端槽空置），索引 0..手牌数-1 仍对齐网格 → 标注槽继续可用
                System.out.println("[麻将实操] 几何：使用人工标注手牌区（槽位 " + slots2560.size() + "）");
                return !slots2560.isEmpty();
            }
            BufferedImage big = plugin.resize(screen, GEOM_W); // 升采样回 2560（该尺度检测已验证稳定）
            MahjongPluginService.Detection det = plugin.detectTiles(big);
            List<MahjongPluginService.Rect> detSlots = new ArrayList<>(det.handSlots);
            if (detSlots.size() > 14) detSlots = new ArrayList<>(detSlots.subList(0, 14));
            if (detSlots.size() >= 13 && detSlots.size() <= 15) {
                slots2560 = detSlots;
                bandY1 = det.bandY1;
                bandY2 = det.bandY2;
                System.out.println("[麻将实操] 运行时几何（2560检测）：槽位 " + slots2560.size() + " band=[" + bandY1 + "," + bandY2 + "]");
            } else {
                slots2560 = fixedSlots();
                System.out.println("[麻将实操] 运行时几何检测不稳（" + detSlots.size() + "槽），使用固定槽位 14");
            }
            return !slots2560.isEmpty();
        } catch (Exception e) {
            slots2560 = fixedSlots();
            System.out.println("[麻将实操] 几何检测异常，使用固定槽位: " + e.getMessage());
            return !slots2560.isEmpty();
        }
    }

    private List<MahjongPluginService.Rect> fixedSlots() {
        List<MahjongPluginService.Rect> out = new ArrayList<>();
        for (int[] s : FIXED_SLOTS_2560) out.add(new MahjongPluginService.Rect(s[0], bandY1, s[1], bandY2));
        return out;
    }

    /** 桌宠控制端口健康检查：点击穿透依赖 Electron 壳的 3081 控制端口 */
    private void checkPetControl() {
        try {
            URL url = new URL("http://127.0.0.1:3081/ping");
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");
            con.setConnectTimeout(400);
            con.setReadTimeout(400);
            int code = con.getResponseCode();
            con.disconnect();
            if (code == 200) {
                System.out.println("[麻将实操] 桌宠控制端口 OK（点击穿透可用）");
            } else {
                System.out.println("[麻将实操] 桌宠控制端口响应异常(code=" + code + ")");
            }
        } catch (Exception e) {
            System.out.println("[麻将实操] 桌宠控制端口不可达——请重启桌宠 Electron 壳（否则点击会被桌宠窗口吞掉）: " + e.getMessage());
        }
    }

    // ===== 手动牌型解析 =====
    // 格式1（旧）："9w 1b 1b 2b 4b 7b 1t 1t 3t 4t 6t 发 中+6b"
    // 格式2（紧凑）："123/456/789/东南西+北" 四段=万/筒/条/字；某类无牌时该段留空但保留'/'（如 //123456789/东南西+北 = 无万无筒、1-9条）
    private Map<String, Object> parseManualTiles(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Integer> hand = new ArrayList<>();
        Integer drawn = null;
        if (text != null && text.contains("/")) {
            String t2 = text.trim();
            int plus = t2.indexOf('+');
            String main = plus >= 0 ? t2.substring(0, plus) : t2;
            String drawnTxt = plus >= 0 ? t2.substring(plus + 1).trim() : "";
            String[] seg = main.split("/", -1);
            if (seg.length < 3) {
                out.put("error", "紧凑牌型需四段（万/筒/条/字）用 / 分隔，如：123/456/789/东南西+北；某类无牌时该段留空但保留'/'（如 //123456789/东南西+北）");
                return out;
            }
            int[] bases = {0, 18, 9}; // 万/筒/条 的 label 基数
            for (int i = 0; i < 3; i++) {
                String s = seg[i].trim();
                for (int k = 0; k < s.length(); k++) {
                    char ch = s.charAt(k);
                    if (ch >= '1' && ch <= '9') hand.add(bases[i] + (ch - '1'));
                }
            }
            String hon = seg.length >= 4 ? seg[3].trim() : "";
            for (int k = 0; k < hon.length(); k++) {
                Integer t = plugin.parseTileToken(String.valueOf(hon.charAt(k)));
                if (t != null) hand.add(t);
            }
            if (!drawnTxt.isEmpty()) drawn = plugin.parseTileToken(drawnTxt);
        } else {
            if (text != null) {
                for (String tok : text.split("[\\s，,；;+]+")) {
                    if (tok.isEmpty()) continue;
                    Integer t = plugin.parseTileToken(tok);
                    if (t == null) continue;
                    if (hand.size() < 13) hand.add(t);
                }
            }
            // 末尾 +X 为摸牌
            if (text != null) {
                Matcher m = Pattern.compile("\\+\\s*([^\\s，,；;]+)").matcher(text);
                if (m.find()) drawn = plugin.parseTileToken(m.group(1));
            }
        }
        if (hand.size() != 13) {
            out.put("error", "手牌数量不对（" + hand.size() + "/13）。格式如：打把麻将 123/456/789/东南西+北（四段=万/筒/条/字，空段保留/）或 打把麻将 9w 1b 1b 2b 4b 7b 1t 1t 3t 4t 6t 发 中+6b");
            return out;
        }
        out.put("hand", hand);
        out.put("drawn", drawn);
        return out;
    }

    /** 用 analyze 结果初始化状态（几何统一走 initGeometry；过滤读取中的 null/非法标签，避免排序 NPE） */
    @SuppressWarnings("unchecked")
    private void applyAnalyzeResult(Map<String, Object> r) {
        List<Integer> labels = (List<Integer>) r.get("_labels");
        Integer drawn = null;
        List<Integer> hand13 = new ArrayList<>();
        if (labels != null) {
            for (int i = 0; i < Math.min(13, labels.size()); i++) {
                Integer v = labels.get(i);
                if (v != null && v >= 0 && v < 34) hand13.add(v);
            }
            if (labels.size() >= 14) {
                Integer v = labels.get(13);
                if (v != null && v >= 0 && v < 34) drawn = v;
            }
        }
        if (hand13.isEmpty()) {
            throw new IllegalStateException("开局读牌为空（牌面识别失败），建议用手动注入：打把麻将 9w 1b ...");
        }
        state.init(hand13, drawn);
    }

    // ===== 轮询 =====
    private void startPoller() {
        poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mahjong-play");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(this::poll, 300, 300, TimeUnit.MILLISECONDS);
    }

    private void poll() {
        try {
            if (!playing) return;
            if (autoStuck) return; // 连续误判后暂停自动操作，等人工介入
            if (state.drawn != null) return; // 已有摸牌未处理（上一轮点击可能失败，等人工介入）
            if (awaitingConfirm) return; // 开局/下一局待用户确认：不做任何自动动作
            // 副露（桌宠代点吃/碰）后：摸牌区空，需按剩余手牌直接弃一张（先等副露动画稳定）
            if (discardAfterMeld) {
                if (System.currentTimeMillis() - meldActAt < 1500) return;
                discardAfterMeld = false;
                meldDiscardAfterMeld();
                return;
            }
            BufferedImage screen = captureScreen();
            if (screen == null) return;
            // 画面健全性：麻将桌面是暗色（整帧暗占比高）；纯白/过亮说明游戏没在前台
            double darkRatio = frameDarkRatio(screen);
            if (darkRatio < 0.10) {
                pollCount++;
                lastClickAt = 0; // 画面不可信时不触发点击校验
                if (!screenAnomaly) {
                    screenAnomaly = true;
                    lastAction = "画面异常（疑似游戏不在前台或被遮挡，暗占比 " + Math.round(darkRatio * 100)
                            + "%）——暂停摸牌检测；把麻将窗口切到前台后会自动恢复";
                    System.out.println("[麻将实操] " + lastAction);
                    savePollFrame(screen, "other");
                } else if (pollCount % 30 == 1) {
                    savePollFrame(screen, "other");
                }
                return;
            }
            if (screenAnomaly) {
                screenAnomaly = false;
                lastAction = "画面恢复正常，继续检测摸牌";
                System.out.println("[麻将实操] " + lastAction);
            }
            // ===== 胡牌后结算窗口：短暂存帧（供结算按钮素材校准）→ 进入"胡牌待命"，弹「下一局/先暂停」 =====
            if (postWinUntil > 0) {
                if (System.currentTimeMillis() < postWinUntil) {
                    if (pollCount % 4 == 0) savePollFrame(screen, "win");
                    return;
                }
                postWinUntil = 0;
                roundEnded = true; // 复用局终待命：不自动续打，等待用户选择
                lastAction = (winWasTsumo ? "自摸！" : "胡牌！") + "对局结束——请选择「下一局」继续或「先暂停」";
                System.out.println("[麻将实操] " + lastAction);
                offerNextOrPause();
                return;
            }
            // ===== 单局结束判定（我方未胡）：手牌区亮比骤降（局中≈68%，局终≈2%）持续约3.6s =====
            if (roundEnded) {
                // 局终待命。仅当用户点过「下一局」（awaitNextRound）且检测到新配牌 → 读下一局手牌并询问确认
                if (awaitNextRound) {
                    double hb = handRegionBrightRatio(screen);
                    if (hb > 30) {
                        handBackStreak++;
                        if (handBackStreak >= 6) {
                            handBackStreak = 0;
                            awaitNextRound = false;
                            if (!readNextAndAskConfirm()) {
                                lastAction = "检测到新配牌但读牌不完整——请说「打把麻将」或手动输入牌型";
                                System.out.println("[麻将实操] " + lastAction);
                            }
                        }
                    } else {
                        handBackStreak = 0;
                    }
                }
                return;
            }
            double handBright = handRegionBrightRatio(screen);
            if (handBright < 8.0 && System.currentTimeMillis() - discardDoneAt > 3000) {
                endStreak++;
                if (endStreak >= 12) { // ~3.6s 手牌区持续消失 → 本局结束（保留本局数据，不做删除）
                    endStreak = 0;
                    roundEnded = true;
                    lastAction = "本局结束（我方未胡）——数据已保留；请选择「下一局」继续或「先暂停」";
                    System.out.println("[麻将实操] " + lastAction);
                    savePollFrame(screen, "end");
                    offerNextOrPause();
                    return;
                }
            } else {
                endStreak = 0;
            }
            // ===== 阶段二：操作选项栏（跳过/和/自摸/立直；键1=跳过 永远右起第一）=====
            long barNow = System.currentTimeMillis();
            int[] bar = detectActionBar(screen);
            if (bar[0] != BAR_NONE) {
                if (barSeenAt == 0) barSeenAt = barNow;
                // 稳定窗：选项栏刚出现/滑入时不识别不点击（首帧常裁到半个按钮/空档，曾导致把立直误跳过）
                if (barNow - barSeenAt < 700) return;
                // 防 300ms 轮询重复点键
                if (barNow - lastBarActAt < 800) return;
                barSeenAt = 0; // 稳定，进入动作
                if (bar[0] == BAR_WIN) {
                    // 和/自摸：总是点（识别不确定不会走到这里，会落 skip）
                    winWasTsumo = bar[2] == MahjongPluginService.BTN_TSUMO;
                    clickBtnSlot(bar[1]);
                    lastBarActAt = barNow;
                    lastAction = (winWasTsumo ? "自摸！" : "荣和！") + "胡牌！";
                    System.out.println("[麻将实操] " + lastAction);
                    postWinUntil = System.currentTimeMillis() + 8000;
                    return;
                }
                if (bar[0] == BAR_RIICHI && !state.riichi && !riichiArmed) {
                    // ===== 立直两段式：先读摸牌/定舍牌 → 点立直键 → 等游戏变暗 → 点舍牌 =====
                    // 关键：点立直后除可舍牌外全部变暗，对暗牌读/点都会失败——
                    // 必须在点立直【之前】截取摸牌区真值并算好要打的牌
                    lastBarActAt = barNow;
                    riichiArmed = true;
                    discardRetries = 0;
                    MahjongPluginService.Rect drr2 = drawnRectLive();
                    boolean drawnHere = drr2 != null && drawnTilePresent(screen);
                    if (drawnHere) {
                        // 路径A（常规）：立直前先读摸牌（此刻牌未变暗）并播报。
                        // 摸牌读得出 → 按牌效推荐（向听=0 的可舍牌）点舍牌（状态经人工核对准确时必然合法且最优）；
                        // 摸牌读不清 → 不盲点，跳过本次立直（宁可错过立直机会，不点错）
                        int t = readDrawnTile(screen);
                        Integer readTile = t >= 0 ? t : null;
                        state.drawn = readTile;
                        if (readTile == null) {
                            // 读不清：跳过立直（点跳过键不点立直键）
                            riichiArmed = false;
                            clickBtnSlot(BAR_BTN_SKIP);
                            lastBarActAt = barNow;
                            state.drawn = null;
                            lastAction = "摸牌读不清，本次暂不立直（已跳过）";
                            System.out.println("[麻将实操] " + lastAction);
                            return;
                        }
                        // 计算推荐舍牌（hand13 + 摸牌，取向听=0 的首选）
                        int chosenTile = -1;
                        try {
                            List<Integer> meldTiles = new ArrayList<>();
                            for (int[] mm : state.melds) for (int x : mm) meldTiles.add(x);
                            List<Map<String, Object>> sugs = plugin.recommendDiscard(new ArrayList<>(state.hand), readTile, meldTiles);
                            for (Map<String, Object> sug : sugs) {
                                Object st = sug.get("shanten");
                                if (st instanceof Number && ((Number) st).intValue() == 0) {
                                    chosenTile = ((Number) sug.get("idx")).intValue();
                                    break;
                                }
                            }
                        } catch (Exception ex) {
                            System.out.println("[麻将实操] 立直舍牌计算异常: " + ex.getMessage());
                        }
                        if (chosenTile < 0) {
                            // 理论不应发生（游戏已给立直键=必有可舍听牌）；兜底点最亮可舍位
                            System.out.println("[麻将实操] 未算出向听0舍牌，用最亮可舍位兜底");
                            int litSlot = findLitDiscardSlot(screen);
                            if (litSlot < 0) {
                                riichiArmed = false;
                                lastAction = "立直舍牌计算失败——请手动点击要打出的牌，或说「先暂停」";
                                System.out.println("[麻将实操] " + lastAction);
                                return;
                            }
                            chosenTile = litSlot >= state.hand.size() && state.drawn != null
                                    ? state.drawn : state.hand.get(Math.min(litSlot, state.hand.size() - 1));
                        }
                        int slot = (chosenTile == readTile) ? state.hand.size() : state.hand.indexOf(chosenTile);
                        if (slot < 0) slot = state.hand.size();
                        clickBtnSlot(bar[1]); // 1) 点立直键
                        lastAction = "摸到" + plugin.tileName(readTile) + "，已点立直，准备打" + plugin.tileName(chosenTile) + "…";
                        System.out.println("[麻将实操] " + lastAction);
                        try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                        clickDiscardSlot(slot); // 2) 点按牌效算好的舍牌（可舍牌未被游戏变暗，点击必然生效）
                        // 3) 状态更新并进入立直（此后游戏自动模切）
                        try {
                            if (state.hand.contains(chosenTile)
                                    || (state.drawn != null && state.drawn.equals(chosenTile))) {
                                state.discard(chosenTile);
                            }
                        } catch (Exception ex) {
                            System.out.println("[麻将实操] 立直状态更新异常: " + ex.getMessage());
                        }
                        state.drawn = null;
                        state.riichi = true;
                        riichiArmed = false;
                        lastAction = "立直！打出" + plugin.tileName(chosenTile) + "（游戏自动模切，等待和牌）";
                        System.out.println("[麻将实操] " + lastAction);
                    } else {
                        // 路径B（罕见）：立直键出现在摸牌前 → 先点立直键，摸牌后走常规流程完成舍牌
                        clickBtnSlot(bar[1]);
                        lastBarActAt = barNow;
                        lastAction = "已点立直，等待摸牌后舍牌…";
                        System.out.println("[麻将实操] " + lastAction);
                    }
                    // 不 return：下方 state.riichi 早退块会校验舍牌点击是否生效
                } else if (bar[0] == BAR_SKIP && !riichiArmed) {
                    if (coopMode) {
                        // 合作模式：不自动跳过——弹「跳过」快捷键并等待用户指令（碰/吃/杠/跳过）
                        if (!coopWaiting) {
                            coopWaiting = true;
                            offerSkipOnly();
                            lastAction = "合作模式：检测到可碰/吃/杠等操作——请指示：碰 X / 杠 X / 吃 响应牌+手牌两张（如：吃 三万 二万 四万）/ 跳过";
                            System.out.println("[麻将实操] " + lastAction);
                        }
                        return; // 等待用户指令，不做任何点击
                    }
                    // 非合作：碰/吃/暗杠等 → 点跳过；riichiArmed（立直点到舍牌之间）绝不点跳过
                    clickBtnSlot(BAR_BTN_SKIP);
                    lastBarActAt = barNow;
                    lastAction = state.riichi ? "跳过（暗杠不杠）" : "跳过（不碰/不吃/不胡）";
                    System.out.println("[麻将实操] " + lastAction);
                    return;
                } else if (bar[0] == BAR_SKIP && riichiArmed) {
                    System.out.println("[麻将实操] 立直舍牌前忽略跳过键（避免误取消立直）");
                }
            } else {
                barSeenAt = 0;
                if (coopWaiting) {
                    coopWaiting = false; // 选项栏消失 → 合作待命解除（用户已手动处理或已超时）
                    clearDialog();
                }
            }
            // 立直后：游戏自动模切（自动出牌）。校验立直舍牌点击生效：空槽=成功；失败先重试原坐标，
            // 仍失败改点摸牌位兜底（目标牌可能被判不可舍而变暗）；随后纯等待和牌
            if (state.riichi) {
                if (lastClickAt > 0 && !sawEmptyAfterClick) {
                    long rs = System.currentTimeMillis() - lastClickAt;
                    MahjongPluginService.Rect rr = drawnRectLive();
                    boolean empty = rr == null || !isDrawnSlotFilled(screen, rr);
                    if (empty) { sawEmptyAfterClick = true; lastClickAt = 0; }
                    else if (rs > 2000 && discardRetries == 0) {
                        discardRetries = 1;
                        retryDiscardClick();
                        lastClickAt = System.currentTimeMillis();
                        System.out.println("[麻将实操] 立直舍牌点击疑似未生效，重试一次");
                    } else if (rs > 5000 && discardRetries == 1 && lastClickX >= 0) {
                        // 之前点的可能是暗牌（被判不可舍）→ 改点"最亮的可舍牌"兜底
                        discardRetries = 2;
                        int lit = findLitDiscardSlot(screen);
                        if (lit >= 0) {
                            clickDiscardSlot(lit);
                            System.out.println("[麻将实操] 立直舍牌重选最亮可舍位（槽" + lit + "）");
                        } else {
                            System.out.println("[麻将实操] 立直舍牌多次失败——请人工打出可舍牌");
                        }
                    }
                }
                return;
            }
            MahjongPluginService.Rect drawnRect = drawnRectLive();
            boolean bright = drawnRect != null && drawnTilePresent(screen);
            if (!bright && armed) {
                // 固定区域未检出进张时，动态扫描手牌右缘兜底（容忍几何轻微漂移/裁区边缘差异）
                MahjongPluginService.Rect dyn = locateDrawnTile(screen);
                if (dyn != null) bright = true;
            }
            // 出牌动画/残影冷却：点完牌后 ~2.2s 内，摸牌位出现内容多是出牌残影/点击特效，
            // 不当作新摸牌（不读不判）——避免"刚出完牌就报摸牌区读不出来"；冷却后再出现内容默认=新摸牌
            if (bright && discardDoneAt > 0 && System.currentTimeMillis() - discardDoneAt < 2200) {
                return;
            }
            pollCount++;
            if (pollCount % 30 == 1) {
                // 周期存档上下文 + 打印摸牌槽亮占比，便于诊断
                savePollFrame(screen, "poll");
                System.out.println("[麻将实操] 轮询诊断: 摸牌位亮占比=" + (drawnRect == null ? "-" : Math.round(drawnSlotBrightRatio(screen, drawnRect) * 100) / 100.0)
                        + "% bright=" + bright + " 槽位=" + slots2560.size() + " hand=" + state.hand.size());
            }
            if (!bright) {
                // 槽位空：出牌已生效 / 等待摸牌
                if (lastClickAt > 0) { sawEmptyAfterClick = true; discardRetries = 0; lastClickAt = 0; }
                brightUnreadableCount = 0;
                ambiguityStage = 0; // 摸牌已离开，不确定性解除
                ambiguousSince = 0;
                brightStable = 0;
                armed = true;
                if (armedSince == 0) armedSince = System.currentTimeMillis();
                long waitSec = (System.currentTimeMillis() - armedSince) / 1000;
                if (waitSec >= 60 && waitSec % 60 == 0) {
                    // 每60秒轻量播报一次等待状态（避免频繁插话；读牌/出牌播报不受影响）
                    lastAction = "等待摸牌中（已 " + waitSec + " 秒），轮到我出牌会自动处理";
                }
                if (sawEmptyAfterClick && resyncPending) {
                    // 出牌后槽空：等手牌重排稳定（约0.9s，3次轮询）后以屏幕为真值重读 13 张，
                    // 自愈摸牌误读造成的手牌漂移（屏幕是最终真值）
                    emptyStreak++;
                    if (emptyStreak >= 3) {
                        resyncPending = false;
                        emptyStreak = 0;
                        resyncHand(screen);
                    }
                } else {
                    emptyStreak = 0;
                }
                return;
            }
            // 进张动画期先等牌面稳定（约0.9s）：移动中的牌会被裁成错位图导致视觉误读
            if (armed) {
                if (brightStable < 3) { brightStable++; return; }
            }
            brightStable = 0;
            armedSince = 0; // 已检测到进张，等待计时清零
            // 槽位亮：必须读得出牌型才算"摸到牌"（防止把桌宠模型当牌）
            // 等待期（stage1 静默等稳定 / stage3 已提示慢重试）不发起识别请求，避免空耗视觉
            long nowMs = System.currentTimeMillis();
            if (ambiguityStage == 1 && nowMs - ambiguousSince < 3000) return;
            if (ambiguityStage == 3 && nowMs - ambiguousSince < 20000) return;
            int t = readDrawnTile(screen);
            if (t == -2) {
                if (ambiguityStage == 0) {
                    // 首次不确定：不立即打扰用户——可能是进张动画/灯光瞬变，静默等 3s 后自动重试
                    ambiguityStage = 1;
                    ambiguousSince = System.currentTimeMillis();
                    return;
                }
                if (ambiguityStage == 1) {
                    // 3s 已过（动画必然结束）→ 下一轮 poll 自动重读一次
                    ambiguityStage = 2;
                    return;
                }
                if (ambiguityStage == 2) {
                    // 稳定后重试仍失败 → 提示手动报牌，进入 20s 慢重试
                    ambiguityStage = 3;
                    ambiguousSince = System.currentTimeMillis();
                    brightUnreadableCount = 0;
                    lastAction = "摸牌连续识别不确定（模板与视觉比对都无法确定这张牌）——请说「摸牌 X」手动报出你看到的牌（如：摸牌 九万）；约20秒后我会自动再试一次";
                    System.out.println("[麻将实操] " + lastAction);
                    savePollFrame(screen, "other");
                    return;
                }
                // stage 3：20s 已过（上面早退未触发）→ 允许下一轮 poll 重读一次
                ambiguityStage = 2;
                return;
            }
            ambiguityStage = 0;
            ambiguousSince = 0;
            if (t < 0) {
                brightUnreadableCount++;
                // 提示时机：首次/第12次/此后每300次(约90s)再提示一次——避免长时间静默无响应
                if (brightUnreadableCount == 1 || brightUnreadableCount == 12 || brightUnreadableCount % 300 == 0) {
                    lastAction = brightUnreadableCount == 1
                            ? "摸牌位有内容但暂时无法识别（等待稳定）"
                            : "摸牌位持续有内容但无法识别——疑似桌宠/特效遮挡摸牌位；若确为摸牌请用「摸牌 X」手动报牌（我会截图存档）";
                    System.out.println("[麻将实操] " + lastAction);
                    savePollFrame(screen, "other");
                }
                return; // 不当作摸牌，继续等待
            }
            brightUnreadableCount = 0;
            long now = System.currentTimeMillis();
            if (!armed && lastClickAt > 0 && !sawEmptyAfterClick) {
                // 出牌后一直没观察到空位：可能出牌失败（同张还在），或动画期后已到下一摸牌
                if (now - lastClickAt < 2000) return; // 出牌动画期，等待
                if (t == lastDiscardedTile && discardRetries < 2) {
                    discardRetries++;
                    lastAction = "出牌疑似未生效（摸牌位仍是" + plugin.tileName(t) + "），重试点击（第" + discardRetries + "次）";
                    System.out.println("[麻将实操] " + lastAction);
                    retryDiscardClick();
                    lastClickAt = now;
                    return;
                }
                if (t == lastDiscardedTile) {
                    lastAction = "出牌连续未生效（摸牌位仍是" + plugin.tileName(t) + "），已暂停自动操作——请移动桌宠/确认麻将窗口后说「结束麻将」重开，或手动打出后说「摸牌 X」继续";
                    System.out.println("[麻将实操] " + lastAction);
                    autoStuck = true;
                    savePollFrame(screen, "other");
                    lastClickAt = 0;
                    discardRetries = 0;
                    return;
                }
                // 不同牌 → 已到下一摸牌，按新摸牌处理
            }
            // 正常读取 → 出牌
            armed = false;
            savePollFrame(screen, "draw"); // 摸牌现场存档
            if (needsResync) {
                needsResync = false;
                initGeometry(); // 副露后布局变化 → 重新定位槽位
                drawnRect = drawnRectLive();
            }
            state.drawn = t;
            actDiscard("进张");
        } catch (Exception e) {
            System.out.println("[麻将实操] 轮询异常: " + e.getMessage());
        }
    }

    /**
     * 读摸牌牌面（分级管线）：
     *  1. 定位并裁出摸牌牌面（动态定位优先，固定摸牌位兜底）；
     *  2. 模板初判：前3候选，若第一名高置信（分差大）→ 直接采用（省视觉、抗误读）；
     *  3. 参考比对：实拍图 + 候选牌型的彩色参考牌面，一次请求让视觉模型判断哪张最像（模板给候选，视觉做仲裁）；
     *  4. 视觉双读兜底：无参考/比对失败时，直接读两次，一致才采用；
     *  5. 全失败返回 -2（由轮询做分级重试/提示手动报牌）。
     */
    private int readDrawnTile(BufferedImage screen) {
        // 1) 定位摸牌牌面：优先用人工标注的固定摸牌区（与标注/模板几何一致，避免动态扫描裁到桌面）；
        //    固定区未检出（进张经动态兜底）时才动态扫描
        MahjongPluginService.Rect dr = drawnRectLive();
        MahjongPluginService.Rect loc = (dr != null && drawnTilePresent(screen)) ? dr : locateDrawnTile(screen);
        if (loc == null) loc = dr;
        if (loc == null) return -1;
        BufferedImage crop = plugin.cropForPlay(screen, loc, 240);
        if (crop == null) return -1;
        // 裁图质量门：暗占比>70% = 疑似裁到桌面/点击特效/遮挡 → 先换动态定位重裁一次（真牌可能在框外），
        // 仍暗才拒绝读取（拒绝时走 -1 由轮询计数升级提示，不静默当无牌干等）
        if (cropDarkFrac(crop) > 0.70) {
            MahjongPluginService.Rect loc2 = locateDrawnTile(screen);
            if (loc2 != null) {
                BufferedImage crop2 = plugin.cropForPlay(screen, loc2, 240);
                if (crop2 != null && cropDarkFrac(crop2) <= 0.70) {
                    loc = loc2;
                    crop = crop2;
                }
            }
            if (cropDarkFrac(crop) > 0.70) {
                System.out.println("[麻将实操] 摸牌裁图疑似非牌面（暗占比 " + Math.round(cropDarkFrac(crop) * 100)
                        + "%），放弃读取——请确认摸牌位是否被遮挡，可用「摸牌 X」手动报牌");
                return -1;
            }
        }
        // 模板前3候选串（读取记录用）
        int[] top = plugin.matchTopCandidates(crop, 3);
        StringBuilder topStr = new StringBuilder();
        for (int i = 0; i < top.length; i++) {
            if (i > 0) topStr.append(",");
            topStr.append(plugin.tileName(top[i])).append("(")
                    .append(Math.round(plugin.matchScoreOf(crop, top[i]) * 100) / 100.0).append(")");
        }
        // 2) 模板初判（放宽直出门槛：多数摸牌纯模板即定，秒级响应，省 AI 调用）
        if (top.length > 0) {
            double s0 = plugin.matchScoreOf(crop, top[0]);
            double s1 = top.length > 1 ? plugin.matchScoreOf(crop, top[1]) : Double.MAX_VALUE;
            if (s0 <= 0.18 && s1 - s0 >= 0.08) {
                System.out.println("[麻将实操] 摸牌模板高置信: " + plugin.tileName(top[0])
                        + "（分差 " + Math.round((s1 - s0) * 100) / 100.0 + "）");
                return finishRead("模板高置信", top[0], crop, topStr.toString());
            }
            // 3) 参考比对：实拍图 + 参考牌面（模板前3候选；参考位未满补荣誉牌，覆盖模板库缺样的白板等）
            List<Integer> cands = new ArrayList<>();
            for (int i = 0; i < top.length && cands.size() < 3; i++) {
                if (plugin.referenceB64(top[i]) != null && !cands.contains(top[i])) cands.add(top[i]);
            }
            for (int h = 27; h <= 33 && cands.size() < 3; h++) {
                if (!cands.contains(h) && plugin.referenceB64(h) != null) cands.add(h);
            }
            if (!cands.isEmpty()) {
                List<String> imgs = new ArrayList<>();
                imgs.add(plugin.toJpegBase64Public(crop));
                StringBuilder desc = new StringBuilder();
                for (int k = 0; k < cands.size(); k++) {
                    imgs.add(plugin.referenceB64(cands.get(k)));
                    desc.append("图").append(k + 2).append("=").append(plugin.tileName(cands.get(k))).append("；");
                }
                String prompt = "图1是《雀魂麻将》玩家刚摸到的真实牌面放大图（可能是红宝牌：红色五万/五筒/五条，数值仍是5）。"
                        + "图2/3/4是候选参考牌面：" + desc + " 请判断图1与哪张参考图是同一张牌：只回答 图N（N为参考图编号）或直接回答牌名（如：七万/白/中）。若都不像，回答：不像。不要解释。";
                String r = deepSeekService.chatVisionMulti(imgs, prompt);
                Integer cmp = parseCompareAnswer(r, cands);
                if (cmp != null) {
                    System.out.println("[麻将实操] 摸牌参考比对: 模板首猜" + plugin.tileName(top[0])
                            + " 视觉判定" + plugin.tileName(cmp));
                    return finishRead("参考比对", cmp, crop, topStr.toString());
                }
                System.out.println("[麻将实操] 摸牌参考比对无结论（模板首猜" + plugin.tileName(top[0]) + "）");
            }
            // 3.5) 比对无结论但模板首猜尚可（≤0.22）→ 直接采纳模板（省掉两次视觉双读；配合回合重同步自愈）
            double s0b = plugin.matchScoreOf(crop, top[0]);
            if (s0b <= 0.22) {
                System.out.println("[麻将实操] 摸牌模板确认(参考比对无结论): " + plugin.tileName(top[0]));
                return finishRead("模板确认", top[0], crop, topStr.toString());
            }
        }
        // 4) 视觉双读兜底：两次一致才采用（仅在模板与比对都无法确定时，响应会慢属预期）
        String b64 = plugin.toJpegBase64Public(crop);
        String prompt = "这是一张《雀魂麻将》日麻牌的放大图（玩家摸到的那张）。注意红宝牌：红色五万/五筒/五条数值仍是5，"
                + "请回答其牌名（如红色五万仍答 五万）。请只回答牌名，"
                + "格式如：三万/九筒/五条/二筒/东/南/西/北/白/发/中/一万。不要任何其他文字。";
        Integer v1 = null, v2 = null;
        if (b64 != null) {
            String r1 = deepSeekService.chatVision(b64, prompt);
            v1 = r1 == null ? null : plugin.parseTileToken(extractTileName(r1));
            String r2 = deepSeekService.chatVision(b64, prompt);
            v2 = r2 == null ? null : plugin.parseTileToken(extractTileName(r2));
            if (v1 != null && v1.equals(v2)) {
                System.out.println("[麻将实操] 摸牌视觉识别(双确认): " + plugin.tileName(v1));
                return finishRead("视觉双确认", v1, crop, topStr.toString());
            }
            System.out.println("[麻将实操] 摸牌视觉不一致: " + (v1 == null ? "null" : plugin.tileName(v1))
                    + " vs " + (v2 == null ? "null" : plugin.tileName(v2)) + "，转模板/人工");
        }
        // 模板兜底：与任一视觉一致则采用
        int t = plugin.matchTile(crop);
        if (v1 != null && t == v1) return finishRead("模板+视觉1", v1, crop, topStr.toString());
        if (v2 != null && t == v2) return finishRead("模板+视觉2", v2, crop, topStr.toString());
        if (t >= 0) {
            System.out.println("[麻将实操] 摸牌模板识别: " + plugin.tileName(t));
            return finishRead("模板兜底", t, crop, topStr.toString());
        }
        return finishRead("无法确定", -2, crop, topStr.toString()); // → 轮询分级处理（静默重试→提示手动报牌）
    }

    /** 记录一次摸牌读取结果（供「误报 X」错题库）：更新最近读取 + 推入历史环（裁图保留内存，误报时写入错题库） */
    private int finishRead(String path, int t, BufferedImage crop, String topStr) {
        lastReadTile = t;
        lastReadAt = System.currentTimeMillis();
        lastReadPath = path;
        lastReadTop = topStr == null ? "" : topStr;
        lastCropImage = crop;
        synchronized (readHistory) {
            readHistory.add(new ReadRecord(lastReadAt, t, path, lastReadTop, crop));
            while (readHistory.size() > READ_HISTORY_MAX) readHistory.remove(0);
        }
        return t;
    }

    /** 解析参考比对回复：优先牌名；其次 图N（图1=实拍，图2起对应 cands）；限定在候选牌型内（防止单次幻觉带崩） */
    private Integer parseCompareAnswer(String resp, List<Integer> cands) {
        if (resp == null) return null;
        String s = resp.trim();
        if (s.isEmpty() || s.contains("不像") || s.contains("无法判断") || s.contains("不确定")) return null;
        // 牌名（模型可能直接回答候选牌名）
        String name = extractTileName(s);
        if (!name.isEmpty()) {
            Integer t = plugin.parseTileToken(name);
            if (t != null && cands.contains(t)) return t;
        }
        // 图N 形式
        Matcher m = Pattern.compile("图\\s*([2-9])").matcher(s);
        if (m.find()) {
            int idx = Integer.parseInt(m.group(1)) - 2;
            if (idx >= 0 && idx < cands.size()) return cands.get(idx);
        }
        // 文本内含候选牌名（如 "更像 图3（七万）"）
        for (Integer c : cands) if (s.contains(plugin.tileName(c))) return c;
        return null;
    }

    /**
     * 手牌重同步：出牌后从屏幕重读 13 张手牌并替换状态（屏幕为真值，自愈摸牌误读造成的漂移）。
     * 需在手牌稳定（摸牌槽空、非动画）时调用。
     */
    private void resyncHand(BufferedImage screen) {
        try {
            if (!state.melds.isEmpty()) return; // 副露后布局变化，屏幕重读不可靠——手牌由 吃/碰回报 维护
            if (slots2560.size() < 13) return;
            List<Integer> read = new ArrayList<>();
            for (int i = 0; i < 13; i++) {
                MahjongPluginService.Rect live = toLiveRect(slots2560.get(i));
                BufferedImage crop = plugin.cropForPlay(screen, live, 200);
                int t = crop == null ? -1 : plugin.matchTile(crop);
                read.add(t);
            }
            long good = read.stream().filter(x -> x != null && x >= 0).count();
            // 出牌后手牌应恰好 13 张：必须 13/13 全中才替换，部分命中会丢牌（宁可跳过保现状）
            if (good == 13 && state.hand.size() == 13) {
                List<Integer> newHand = new ArrayList<>();
                for (Integer t : read) if (t != null && t >= 0) newHand.add(t);
                state.hand.clear();
                state.hand.addAll(newHand);
                state.sortHand();
                System.out.println("[麻将实操] 手牌重同步(屏幕真值): " + dumpHand());
            } else {
                System.out.println("[麻将实操] 手牌重同步跳过（识别 " + good + "/13，需全中才替换，避免丢牌）");
            }
        } catch (Exception e) {
            System.out.println("[麻将实操] 手牌重同步异常: " + e.getMessage());
        }
    }

    /** 从视觉模型回复中提取单个牌名 token */
    private String extractTileName(String resp) {
        if (resp == null) return "";
        // 取第一个可能的牌名（中/东南西北白发/数字+万条筒）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?:[一二三四五六七八九][万条筒])|(?:[1-9][万条筒mspMSPwbtWBT])|(?:东|南|西|北|白|发|中)")
                .matcher(resp);
        return m.find() ? m.group() : resp.trim();
    }

    private int pollCount = 0;

    private void savePollFrame(BufferedImage screen, String tag) {
        try {
            File dir = new File(System.getProperty("user.dir"), "desktop_vision_log");
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, "mahjong_poll_" + tag + "_" + System.currentTimeMillis() + ".png");
            ImageIO.write(screen, "png", f);
        } catch (Exception e) { /* 忽略 */ }
    }

    // ===== 出牌决策 + 点击 =====
    private void actDiscard(String reason) {
        try {
            if (state.riichi) {
                // 立直后游戏自动模切（自动出牌），不点击任何牌；只等 和/自摸 键（选项栏流程处理）
                lastAction = "立直中（游戏自动模切），等待和牌";
                return;
            }
            Integer d = state.drawn;
            if (d == null) return;
            boolean finalizeRiichi = riichiArmed && !state.riichi; // 本次打出后立直完成
            int discardIdx;
            int shanten = -1, ukeire = -1;
            {
                // 副露牌转平铺列表（供评估）
                List<Integer> meldTiles = new ArrayList<>();
                for (int[] m : state.melds) for (int t : m) meldTiles.add(t);
                List<Map<String, Object>> sugs = plugin.recommendDiscard(new ArrayList<>(state.hand), d, meldTiles);
                if (sugs.isEmpty()) {
                    if (finalizeRiichi) riichiArmed = false;
                    lastAction = "出牌建议为空（异常）";
                    return;
                }
                Map<String, Object> best = sugs.get(0);
                discardIdx = (Integer) best.get("idx");
                shanten = (Integer) best.get("shanten");
                ukeire = (Integer) best.get("ukeire");
            }
            int slot = state.discard(discardIdx);
            int clickX, clickY;
            if (slot >= state.hand.size()) {
                // 打出的是摸牌：用固定摸牌位（与手牌间隔）；固定位点击失败时动态兜底
                MahjongPluginService.Rect dr = drawnRectLive();
                clickX = (dr.x1 + dr.x2) / 2;
                clickY = (dr.y1 + dr.y2) / 2;
                if (clickX < 0) {
                    MahjongPluginService.Rect loc = locateDrawnTile(captureScreen());
                    if (loc != null) {
                        clickX = (loc.x1 + loc.x2) / 2;
                        clickY = (loc.y1 + loc.y2) / 2;
                    } else {
                        clickX = slotCenterX(slot);
                        clickY = bandCenterY();
                    }
                }
            } else {
                clickX = slotCenterX(slot);
                clickY = bandCenterY();
            }
            if (clickX < 0) {
                if (finalizeRiichi) riichiArmed = false;
                lastAction = "槽位坐标缺失，无法点击";
                return;
            }
            lastDiscardedTile = discardIdx;
            lastClickX = clickX;
            lastClickY = clickY;
            armed = false; // 点击即视为处理中（防止 -2 手动报牌后，轮询把同张再当新摸牌重复出牌）
            // 播报前置：先让状态接口拿到动作播报，页面轮询立即播报，再执行物理点击
            String actMsg;
            if (finalizeRiichi) {
                state.riichi = true;
                riichiArmed = false;
                actMsg = "立直！打出" + plugin.tileName(discardIdx) + "（此后游戏自动模切，只等和牌）";
            } else {
                actMsg = (reason.equals("开局") ? "开局" : "摸到" + plugin.tileName(d)) + "，打出" + plugin.tileName(discardIdx);
            }
            // 立直相关不附向听；普通出牌附（向听/进张）
            if (state.riichi) {
                lastAction = actMsg;
            } else {
                lastAction = actMsg + "（向听" + shanten + " 进张" + ukeire + "）";
            }
            lastHandDump = dumpHand();
            System.out.println("[麻将实操] " + lastAction + " | " + lastHandDump);
            click(clickX, clickY);
            discardDoneAt = System.currentTimeMillis(); // 出牌动画/残影冷却起点
            lastClickAt = System.currentTimeMillis();
            sawEmptyAfterClick = false;
            discardRetries = 0;
            resyncPending = true; // 出牌点击已发出 → 槽空后以屏幕真值重同步手牌
            emptyStreak = 0;
        } catch (Exception e) {
            lastAction = "出牌异常: " + e.getMessage();
            System.out.println("[麻将实操] " + lastAction);
        }
    }

    // ===== 阶段二：操作选项栏检测（跳过/和/自摸/立直）=====
    private static final int BAR_NONE = 0, BAR_SKIP = 1, BAR_WIN = 2, BAR_RIICHI = 3;
    private static final int BAR_BTN_SKIP = 0, BAR_BTN2 = 1, BAR_BTN3 = 2;

    /** 键位槽 2560 基准坐标（null=未标注/不存在） */
    private int[] btnBox2560(int idx) {
        if (btnSlots2560 == null || idx < 0 || idx >= btnSlots2560.length || btnSlots2560[idx] == null) return null;
        return btnSlots2560[idx];
    }

    /** 键位槽是否有按钮：无按钮=桌面（亮≈0%），有按钮亮≈8-15%（live 降采样后以 6% 为界） */
    private boolean btnSlotFilled(BufferedImage screen, int idx) {
        int[] b = btnBox2560(idx);
        if (b == null) return false;
        MahjongPluginService.Rect live = new MahjongPluginService.Rect(
                (int) Math.round(b[0] * scaleX), (int) Math.round(b[2] * scaleY),
                (int) Math.round(b[1] * scaleX), (int) Math.round(b[3] * scaleY));
        return drawnSlotBrightRatio(screen, live) > 6;
    }

    /** 识别键位槽内容：-2=无按钮 / BTN_* / -1=有内容但无法识别 */
    private int classifyBtnSlot(BufferedImage screen, int idx) {
        if (!btnSlotFilled(screen, idx)) return -2;
        int[] b = btnBox2560(idx);
        if (b == null) return -2;
        MahjongPluginService.Rect live = new MahjongPluginService.Rect(
                (int) Math.round(b[0] * scaleX), (int) Math.round(b[2] * scaleY),
                (int) Math.round(b[1] * scaleX), (int) Math.round(b[3] * scaleY));
        BufferedImage crop = plugin.cropForPlay(screen, live, 48);
        if (crop == null) return -1;
        int m = plugin.matchActionBtn(crop);
        if (m < 0) {
            // 诊断（节流）：记录键2/键3 有内容但模板未命中的情况
            long nowLog = System.currentTimeMillis();
            if (nowLog - lastBtnDiagLog > 5000) {
                lastBtnDiagLog = nowLog;
                System.out.println("[麻将实操] 选项栏键" + (idx + 1) + "有内容但模板未命中（前3: "
                        + topNames(plugin.actionBtnTopN(crop, 3)) + "）");
            }
        }
        return m;
    }

    private String topNames(int[] labels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < labels.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(plugin.actionBtnName(labels[i]));
        }
        return sb.toString();
    }

    /** 检测操作选项栏 → {动作, 目标键位idx, 匹配按键label}；无选项栏返回 {BAR_NONE,-1,-1}
     *  键1=跳过 在有操作时永远存在且为右起第一（含立直后的和/自摸）→ 以键1为选项栏存在判据 */
    private int[] detectActionBar(BufferedImage screen) {
        if (!btnSlotFilled(screen, BAR_BTN_SKIP)) return new int[]{BAR_NONE, -1, -1};
        int s2 = classifyBtnSlot(screen, BAR_BTN2);
        int s3 = classifyBtnSlot(screen, BAR_BTN3);
        // 和/自摸必在键2（需求：存在和/自摸时显示在第二个键位）
        if (s2 == MahjongPluginService.BTN_HU || s2 == MahjongPluginService.BTN_TSUMO) {
            return new int[]{BAR_WIN, BAR_BTN2, s2};
        }
        if (s2 == MahjongPluginService.BTN_RIICHI) return new int[]{BAR_RIICHI, BAR_BTN2, s2};
        if (s3 == MahjongPluginService.BTN_RIICHI) return new int[]{BAR_RIICHI, BAR_BTN3, s3};
        // 其余（碰/吃等，或无法识别）→ 跳过
        return new int[]{BAR_SKIP, BAR_BTN_SKIP, MahjongPluginService.BTN_SKIP};
    }

    /** 点最亮的舍牌位：立直选牌阶段 亮的=游戏判定可舍的牌（其余已变暗）。
     *  返回槽位 0..12（手牌）或 13（摸牌位）；找不到返回 -1。不依赖读牌，杜绝点暗牌卡死 */
    private int findLitDiscardSlot(BufferedImage screen) {
        double best = -1;
        int bestSlot = -1;
        for (int i = 0; i < 13 && i < slots2560.size(); i++) {
            MahjongPluginService.Rect live = toLiveRect(slots2560.get(i));
            double r = drawnSlotBrightRatio(screen, live);
            if (r > best) { best = r; bestSlot = i; }
        }
        MahjongPluginService.Rect dr = drawnRectLive();
        if (dr != null) {
            double r = drawnSlotBrightRatio(screen, dr);
            if (r > best) { best = r; bestSlot = 13; }
        }
        return bestSlot;
    }

    /** 裁图暗像素占比（阈值60；真实牌面通常<20%，点击特效/桌布废图>80%） */
    private double cropDarkFrac(BufferedImage img) {
        try {
            int dark = 0, n = 0;
            int w = img.getWidth(), h = img.getHeight();
            for (int y = 0; y < h; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    int rgb = img.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum < 60) dark++;
                    n++;
                }
            }
            return n == 0 ? 1 : dark / (double) n;
        } catch (Exception e) {
            return 1;
        }
    }

    /** 点击某键位槽中心（实机坐标） */
    private void clickBtnSlot(int idx) {
        int[] b = btnBox2560(idx);
        if (b == null) return;
        click((int) Math.round((b[0] + b[1]) / 2.0 * scaleX), (int) Math.round((b[2] + b[3]) / 2.0 * scaleY));
    }

    /** 副露（吃/碰，桌宠代点）后自动弃牌：摸牌区空，按剩余手牌（含副露）选一张打出。
     *  手牌行按原网格左对齐压缩：槽 0..手牌数-1 即该牌在排序手牌中的位置 */
    private void meldDiscardAfterMeld() {
        try {
            if (state.hand.isEmpty()) return;
            List<Integer> meldTiles = new ArrayList<>();
            for (int[] m : state.melds) for (int x : m) meldTiles.add(x);
            List<Map<String, Object>> sugs = plugin.recommendDiscard(new ArrayList<>(state.hand), null, meldTiles);
            if (sugs.isEmpty()) {
                lastAction = "副露后出牌建议为空——请手动打出一张（可说「出牌 X」告知）";
                System.out.println("[麻将实操] " + lastAction);
                return;
            }
            int tile = (Integer) sugs.get(0).get("idx");
            int shanten = (Integer) sugs.get(0).get("shanten");
            int ukeire = (Integer) sugs.get(0).get("ukeire");
            if (!state.hand.contains(tile)) {
                lastAction = "副露后推荐牌不在手——请手动打出一张（可说「出牌 X」告知）";
                System.out.println("[麻将实操] " + lastAction);
                return;
            }
            if (state.hand.size() > slots2560.size()) {
                lastAction = "副露后手牌数多于槽位（" + state.hand.size() + "/" + slots2560.size()
                        + "）——请手动打出后用「出牌 X」告知";
                System.out.println("[麻将实操] " + lastAction);
                return;
            }
            // state.hand 已排序：槽位 = 该牌在排序手牌中的位置（左对齐网格）
            int clickSlot = state.hand.indexOf(tile);
            if (clickSlot < 0) {
                lastAction = "副露后找不到要打的牌——请手动打出后用「出牌 X」告知";
                System.out.println("[麻将实操] " + lastAction);
                return;
            }
            clickDiscardSlot(clickSlot);
            state.discard(tile);
            lastAction = "（副露后）打出" + plugin.tileName(tile) + "（向听" + shanten + " 进张" + ukeire + "）";
            System.out.println("[麻将实操] " + lastAction + " | " + dumpHand());
        } catch (Exception e) {
            lastAction = "副露后自动弃牌异常: " + e.getMessage();
            System.out.println("[麻将实操] " + lastAction);
        }
    }

    /** 「出牌 X」：用户手动打出某张后告知桌宠（免去重新检测），从手牌移除并继续 */
    public Map<String, Object> discardManual(String tileText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!playing) { out.put("ok", false); out.put("message", "未在对局中"); return out; }
            Integer tile = plugin.parseTileToken(tileText);
            if (tile == null) { out.put("ok", false); out.put("message", "无法解析牌：" + tileText); return out; }
            int idx = state.hand.indexOf(tile);
            if (idx < 0) { out.put("ok", false); out.put("message", "手牌中没有" + plugin.tileName(tile) + "可出（当前手牌：" + dumpHand() + "）"); return out; }
            state.hand.remove(idx);
            state.sortHand();
            lastAction = "你打出了" + plugin.tileName(tile) + "（已同步） | " + dumpHand();
            System.out.println("[麻将实操] " + lastAction);
            out.put("ok", true);
            out.put("message", "已同步出牌：" + plugin.tileName(tile));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "同步失败: " + e.getMessage());
        }
        return out;
    }

    /** 点击舍牌槽位（slot=0..12 手牌槽，≥hand.size()=摸牌位）；记录 lastClick 供校验/重试（立直舍牌用） */
    private void clickDiscardSlot(int slot) {        int clickX = -1, clickY = -1;
        if (slot >= state.hand.size()) {
            MahjongPluginService.Rect dr = drawnRectLive();
            if (dr != null) { clickX = (dr.x1 + dr.x2) / 2; clickY = (dr.y1 + dr.y2) / 2; }
        } else if (slot >= 0) {
            clickX = slotCenterX(slot);
            clickY = bandCenterY();
        }
        if (clickX < 0) return;
        lastClickX = clickX;
        lastClickY = clickY;
        click(clickX, clickY);
        discardDoneAt = System.currentTimeMillis(); // 出牌动画/残影冷却起点
        lastClickAt = System.currentTimeMillis();
        sawEmptyAfterClick = false;
        discardRetries = 0;
    }

    // ===== 吃/碰回报（用户手动操作后更新牌库记忆） =====
    public Map<String, Object> chiReport(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!playing) { out.put("ok", false); out.put("message", "未在对局中"); return out; }
            // 兼容 "吃 四条 五条 六条" 与 "四条 五条 吃 六条"
            String t = text == null ? "" : text.trim();
            Matcher m = Pattern.compile("吃").matcher(t);
            List<Integer> tiles = new ArrayList<>();
            for (String tok : t.split("[\\s，,；;吃]+")) {
                if (tok.isEmpty()) continue;
                Integer ti = plugin.parseTileToken(tok);
                if (ti != null) tiles.add(ti);
            }
            if (tiles.size() != 3) { out.put("ok", false); out.put("message", "吃回报需要3张牌，如：吃 四条 五条 六条"); return out; }
            // 吃到的牌=与手牌不同的一张（搭子两张在手牌中）
            int eaten = -1, a = -1, b = -1;
            for (Integer ti : tiles) {
                if (state.hand.contains(ti)) {
                    if (a < 0) a = ti; else if (b < 0) b = ti;
                } else {
                    eaten = ti;
                }
            }
            if (eaten < 0 || a < 0 || b < 0) { out.put("ok", false); out.put("message", "吃回报格式不符（应包含手牌两张+吃到的牌一张）"); return out; }
            state.recordChi(a, b, eaten);
            needsResync = true;
            out.put("ok", true);
            out.put("message", "已记录吃：" + plugin.tileName(a) + plugin.tileName(b) + "吃" + plugin.tileName(eaten) + " | " + dumpHand());
            System.out.println("[麻将实操] " + out.get("message"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "吃回报异常: " + e.getMessage());
        }
        return out;
    }

    /** 手动报摸牌（模板识别失败时兜底） */
    /** 错题记录追加（同步锁防并发写串行，UTF-8） */
    private void appendMisrecord(String line) {
        try {
            if (!MISREPORT_DIR.exists()) MISREPORT_DIR.mkdirs();
            File txt = new File(MISREPORT_DIR, "麻将错题记录.txt");
            synchronized (MIS_LOCK) {
                try (java.io.OutputStreamWriter w = new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(txt, true), java.nio.charset.StandardCharsets.UTF_8)) {
                    w.write(line + System.lineSeparator());
                }
            }
        } catch (Exception e) {
            System.out.println("[麻将实操] 错题记录写入失败: " + e.getMessage());
        }
    }

    public Map<String, Object> reportDraw(String tileText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Integer t = plugin.parseTileToken(tileText);
            if (t == null) { out.put("ok", false); out.put("message", "无法解析牌：" + tileText); return out; }
            if (state.drawn != null) { out.put("ok", false); out.put("message", "已有摸牌未处理：" + plugin.tileName(state.drawn)); return out; }
            // 手动报牌 = 自动识别长时间失败后的解围 → 快照当前摸牌位入错题本（标注"长时间未识别"），
            // 供人工核对当时摸牌位显示了什么（遮挡/点击特效/裁图错位/真牌没读到）
            try {
                BufferedImage scr = captureScreen();
                if (scr != null) {
                    savePollFrame(scr, "report"); // 整帧上下文
                    if (!MISREPORT_DIR.exists()) MISREPORT_DIR.mkdirs();
                    MahjongPluginService.Rect box = drawnRectLive();
                    BufferedImage crop = box != null ? plugin.cropForPlay(scr, box, 240) : null;
                    String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                    File cropFile = null;
                    if (crop != null) {
                        cropFile = new File(MISREPORT_DIR,
                                "误报_（" + plugin.tileName(t) + "）错读为（未识别）_" + ts + ".png");
                        ImageIO.write(crop, "png", cropFile);
                    }
                    String line = String.format(java.util.Locale.ROOT,
                            "[%s] 实际=%s | 误读=长时间未识别(手动报牌) | 读取路径=手动报牌 | 模板Top=- | 裁图=%s",
                            new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()),
                            plugin.tileName(t), cropFile != null ? cropFile.getName() : "（无裁图）");
                    appendMisrecord(line);
                    System.out.println("[麻将实操] 错题库记录: " + line);
                }
            } catch (Exception e) {
                System.out.println("[麻将实操] 手动报牌快照失败: " + e.getMessage());
            }
            state.drawn = t;
            autoStuck = false;
            ambiguityStage = 0; // 用户已手动报牌，解除等待/不确定性
            ambiguousSince = 0;
            out.put("ok", true);
            out.put("message", "已记录摸牌：" + plugin.tileName(t));
            actDiscard("手动报牌");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "报牌异常: " + e.getMessage());
        }
        return out;
    }

    /** 「误报 X」：人工指出某次摸牌读取有误、实际牌=X（桌宠误读为了其他牌或未读出）。
     *  从历史读取记录中找最近一次"读成别的牌"的回合（滞后几回合也能标对），
     *  实拍裁图存入 static/麻将错题截屏/，并在 麻将错题记录.txt 追加一条说明，供人工核对误判原因。 */
    public Map<String, Object> misreport(String tileText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Integer actual = plugin.parseTileToken(tileText);
            if (actual == null) { out.put("ok", false); out.put("message", "无法解析牌：" + tileText); return out; }
            long now = System.currentTimeMillis();
            // 从最新往旧找：10分钟内、未被认领、且当时读的不是 X（=那次把 X 读错/没读出）
            ReadRecord hit = null;
            boolean sawCorrectX = false;
            synchronized (readHistory) {
                for (int i = readHistory.size() - 1; i >= 0; i--) {
                    ReadRecord r = readHistory.get(i);
                    if (now - r.time > 10 * 60 * 1000L) break;
                    if (r.tile == actual) { sawCorrectX = true; continue; }
                    if (!r.marked) { hit = r; break; }
                }
                if (hit != null) hit.marked = true;
            }
            if (hit == null) {
                out.put("ok", false);
                out.put("message", sawCorrectX
                        ? "近10分钟内没有" + plugin.tileName(actual) + "被读错的记录——最近一次读到它就是" + plugin.tileName(actual) + "，是不是记错回合了？"
                        : "近10分钟内没有可标记的读取记录（先让桌宠读一次摸牌，读错后说「误报 X」）");
                return out;
            }
            String reported = hit.tile >= 0 ? plugin.tileName(hit.tile) : "未读出";
            String timeTxt = new java.text.SimpleDateFormat("HH:mm:ss").format(new java.util.Date(hit.time));
            if (!MISREPORT_DIR.exists()) MISREPORT_DIR.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date(hit.time));
            File cropFile = null;
            if (hit.crop != null) {
                // 文件名自含对照：误报_（实际牌）错读为（误读牌）_时间.png，便于直接按文件维护/清理
                cropFile = new File(MISREPORT_DIR,
                        "误报_（" + plugin.tileName(actual) + "）错读为（" + reported + "）_" + ts + ".png");
                ImageIO.write(hit.crop, "png", cropFile);
            }
            File txt = new File(MISREPORT_DIR, "麻将错题记录.txt");
            String line = String.format(java.util.Locale.ROOT,
                    "[%s] 实际=%s | 误读=%s | 读取路径=%s | 模板Top=%s | 裁图=%s",
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(hit.time)),
                    plugin.tileName(actual), reported, hit.path, hit.top,
                    cropFile != null ? cropFile.getName() : "（无裁图）");
            appendMisrecord(line);
            System.out.println("[麻将实操] 错题库记录: " + line);
            out.put("ok", true);
            out.put("message", "已记录误报：实际=" + plugin.tileName(actual) + "，那次(" + timeTxt + ")误读=" + reported
                    + (cropFile != null ? "，裁图→麻将错题截屏/" + cropFile.getName() : "")
                    + "（麻将错题记录.txt 已追加）");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "误报记录失败: " + e.getMessage());
        }
        return out;
    }

    // ===== 单局结束判定 =====
    /** 弹出「下一局 / 先暂停」快捷选项（页面状态轮询展示） */
    private void offerNextOrPause() {
        pendingDialog = new String[]{"下一局", "先暂停"};
        dialogSeq++;
    }

    /** 关闭快捷选项 */
    private void clearDialog() {
        pendingDialog = null;
        dialogSeq++;
    }

    /** 弹出仅含「跳过」的快捷选项（合作模式等待人工决策时，便于一键跳过） */
    private void offerSkipOnly() {
        pendingDialog = new String[]{"跳过"};
        dialogSeq++;
    }
    /** 手牌区亮占比（局中≈60-70%，局终结算≈2%；实时降采样后仍远高于阈值差） */
    private double handRegionBrightRatio(BufferedImage screen) {
        try {
            if (slots2560.size() < 2 || screen == null) return 100;
            int x1 = slots2560.get(0).x1;
            int x2 = slots2560.get(slots2560.size() - 1).x2;
            MahjongPluginService.Rect live = new MahjongPluginService.Rect(
                    (int) Math.round(x1 * scaleX), (int) Math.round(bandY1 * scaleY),
                    (int) Math.round(x2 * scaleX), (int) Math.round(bandY2 * scaleY));
            return drawnSlotBrightRatio(screen, live);
        } catch (Exception e) {
            return 100;
        }
    }

    /** 快捷选项/语音「下一局」：清除上轮数据 → 等游戏下一局配牌出现后自动读牌并【再次询问确认】（等同再次「打把麻将」）；
     *  若配牌已在屏则立即读牌询问 */
    public Map<String, Object> nextRound() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!roundEnded) { out.put("ok", false); out.put("message", "当前不在局终/胡牌待续状态"); return out; }
            state.init(new ArrayList<Integer>(), null); // 清除上轮游戏数据
            awaitNextRound = true;
            roundEnded = true; // 待配牌阶段仍处于"局终待命"，配牌出现后由轮询触发读牌询问
            handBackStreak = 0;
            clearDialog();
            lastAction = "已清除上轮数据——请点游戏里的「下一局」开始配牌，配好后我会自动读牌并再次跟你确认；要先退出可说「先暂停」";
            System.out.println("[麻将实操] " + lastAction);
            // 若配牌已在屏（已开好新局）→ 立即读牌询问
            BufferedImage scr = captureScreen();
            if (scr != null && handRegionBrightRatio(scr) > 30) {
                awaitNextRound = false;
                if (!readNextAndAskConfirm()) {
                    lastAction = "检测到新配牌但读牌不成功（读不齐或牌序混乱）——请说「打把麻将」或手动输入牌型";
                    System.out.println("[麻将实操] " + lastAction);
                }
            }
            out.put("ok", true);
            out.put("message", "已确认下一局（配牌出现后会自动读牌确认）");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "请求失败: " + e.getMessage());
        }
        return out;
    }

    /** 快捷选项/语音「先暂停」：停止自动打牌（本局数据保留） */
    public Map<String, Object> pauseMatch() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            playing = false;
            roundEnded = false;
            awaitNextRound = false;
            endStreak = 0;
            handBackStreak = 0;
            clearDialog();
            lastAction = "已暂停麻将模式（数据保留）——需要时说「打把麻将」";
            System.out.println("[麻将实操] " + lastAction);
            out.put("ok", true);
            out.put("message", "已暂停麻将模式");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "暂停失败: " + e.getMessage());
        }
        return out;
    }

    /** 「继续对局」：用户明确指令继续——先快照当前帧入错题库（局终误判/续局样本），再以屏幕为真值重新同步后恢复。
     *  覆盖局终误判（游戏仍在进行）与已开新局两种场景；屏幕同步保证不沿用旧局状态乱出牌 */
    public Map<String, Object> continueMatch() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!roundEnded) { out.put("ok", false); out.put("message", "当前不在局终判定状态，无需继续"); return out; }
            // 1) 快照当前帧（供校准局终阈值/核对续局画面）
            BufferedImage scr = captureScreen();
            if (scr != null) {
                if (!MISREPORT_DIR.exists()) MISREPORT_DIR.mkdirs();
                String ts = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss").format(new java.util.Date());
                File f = new File(MISREPORT_DIR, "局终误判_" + ts + ".png");
                try { ImageIO.write(scr, "png", f); } catch (Exception e2) { /* ignore */ }
                String line = String.format(java.util.Locale.ROOT,
                        "[%s] 局终后用户指令继续对局 | 手牌区亮比=%.0f%% | 帧=%s",
                        new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date()),
                        handRegionBrightRatio(scr), f.getName());
                appendMisrecord(line);
                System.out.println("[麻将实操] 错题库记录: " + line);
            }
            // 2) 以屏幕为真值重新同步（等同「打把麻将」的开局读牌：纯模板、秒级；不沿用旧局状态）
            roundEnded = false;
            endStreak = 0;
            awaitNextRound = false;
            handBackStreak = 0;
            clearDialog();
            riichiArmed = false;
            Object[] got = readOpeningHand(); // 无手牌/读不齐会重试后返回 null
            if (got == null) {
                roundEnded = true; // 仍无手牌：说明还在结算/大厅画面，保持局终待命
                out.put("ok", false);
                out.put("message", "屏幕未检测到手牌——请先处理结算（点「下一局」或回到牌局）再试「继续对局」");
                return out;
            }
            @SuppressWarnings("unchecked")
            List<Integer> hand13 = (List<Integer>) got[0];
            Integer drawn = (Integer) got[1];
            state.init(hand13, drawn);
            lastAction = "已按屏幕重新同步继续对局：" + dumpHand()
                    + (drawn != null ? "，摸牌=" + plugin.tileName(drawn) : "");
            System.out.println("[麻将实操] " + lastAction);
            if (drawn != null) actDiscard("开局");
            out.put("ok", true);
            out.put("message", "已继续对局（按屏幕重新同步）");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "继续对局失败: " + e.getMessage());
        }
        return out;
    }

    // ===== 合作模式（吃碰杠交用户决策）=====
    /** 开关合作模式（默认关） */
    public Map<String, Object> toggleCoop() {
        Map<String, Object> out = new LinkedHashMap<>();
        coopMode = !coopMode;
        if (!coopMode) {
            coopWaiting = false;
            clearDialog();
        }
        out.put("ok", true);
        out.put("coopMode", coopMode);
        out.put("message", coopMode ? "合作模式已打开：遇到吃/碰/杠会等你指示（碰 X / 杠 X / 吃 ABC / 跳过）" : "合作模式已关闭：吃碰杠恢复自动跳过");
        System.out.println("[麻将实操] " + out.get("message"));
        return out;
    }

    /** 合作指令：跳过 / 碰 X / 杠 X / 吃 ABC（点击对应按钮并记录/交还用户） */
    public Map<String, Object> coopAction(String text) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!coopMode) { out.put("ok", false); out.put("message", "合作模式未开启（说「打开合作模式」）"); return out; }
            if (!coopWaiting) { out.put("ok", false); out.put("message", "当前没有等待中的碰/吃/杠反应"); return out; }
            String t = text == null ? "" : text.trim();
            if (t.isEmpty()) { out.put("ok", false); out.put("message", "指令为空"); return out; }
            if (t.equals("跳过")) {
                clickBtnSlot(BAR_BTN_SKIP);
                coopWaiting = false;
                clearDialog();
                lastAction = "已跳过（合作模式）";
                System.out.println("[麻将实操] " + lastAction);
                out.put("ok", true); out.put("message", "已跳过");
                return out;
            }
            // 碰/杠：解析单张
            java.util.regex.Matcher pm = java.util.regex.Pattern.compile("^(碰|杠|碰了|杠了)\\s*(.+)$").matcher(t);
            if (pm.find()) {
                String act = pm.group(1).startsWith("碰") ? "碰" : "杠";
                Integer tile = plugin.parseTileToken(pm.group(2).trim());
                if (tile == null) { out.put("ok", false); out.put("message", "无法解析牌型：" + pm.group(2)); return out; }
                int cnt = 0;
                for (Integer x : state.hand) if (x != null && x.equals(tile)) cnt++;
                if (act.equals("碰") && cnt < 2) { out.put("ok", false); out.put("message", "手牌不足两张" + plugin.tileName(tile) + "，无法碰"); return out; }
                if (act.equals("杠") && cnt < 3) { out.put("ok", false); out.put("message", "手牌不足三张" + plugin.tileName(tile) + "，无法杠"); return out; }
                // 点击：优先键2（反应操作首个位置）。若键2被识别为"碰"而用户要"杠"（碰杠并存），提示手动
                if (act.equals("杠")) {
                    BufferedImage scr = captureScreen();
                    int s2 = scr == null ? -1 : classifyBtnSlot(scr, BAR_BTN2);
                    if (s2 == MahjongPluginService.BTN_PON) {
                        out.put("ok", false);
                        out.put("message", "该反应首个按钮是碰（可能碰杠并存，杠位置未标注）——请手动杠后说「杠回报 X」同步（碰杠回报指令待补）");
                        return out;
                    }
                }
                clickBtnSlot(BAR_BTN2);
                if (act.equals("碰")) state.recordPon(tile); else state.recordKan(tile);
                needsResync = true; // 副露后布局变化
                coopWaiting = false;
                clearDialog();
                if (act.equals("碰")) {
                    // 碰后无摸牌：摸牌区空，需按剩余手牌直接弃一张
                    discardAfterMeld = true;
                    meldActAt = System.currentTimeMillis();
                }
                lastAction = "已" + act + plugin.tileName(tile) + "（合作模式）";
                System.out.println("[麻将实操] " + lastAction);
                out.put("ok", true); out.put("message", lastAction + "，继续自动打牌");
                return out;
            }
            // 吃：格式「吃 响应牌 手牌1 手牌2」（第一张=对方弃牌/响应牌，后两张=手牌搭子）
            java.util.regex.Matcher em = java.util.regex.Pattern.compile("^(吃|吃了)\\s*(.+)$").matcher(t);
            if (em.find()) {
                String body = em.group(2);
                List<Integer> tiles = new ArrayList<>();
                for (String tok : body.split("[\\s，,；;]+")) {
                    Integer ti = plugin.parseTileToken(tok);
                    if (ti != null) tiles.add(ti);
                }
                if (tiles.size() != 3) { out.put("ok", false); out.put("message", "吃需三张：第一张响应牌 + 两张手牌搭子，如：吃 三万 二万 四万"); return out; }
                Integer eaten = tiles.get(0);
                Integer a = tiles.get(1);
                Integer b = tiles.get(2);
                // 校验：a、b 两张在手
                List<Integer> tmp = new ArrayList<>(state.hand);
                if (a == null || b == null || !tmp.remove(a) || !tmp.remove(b)) {
                    out.put("ok", false);
                    out.put("message", "手牌中没有可吃的两张搭子（" + plugin.tileName(a) + " " + plugin.tileName(b) + "）");
                    return out;
                }
                if (!isRunThree(eaten, a, b)) {
                    out.put("ok", false);
                    out.put("message", "不是连续顺子：" + plugin.tileName(eaten) + " " + plugin.tileName(a) + " " + plugin.tileName(b));
                    return out;
                }
                // 组合唯一性：以 eaten 为响应牌，手牌里能组成几个顺子
                int ways = chiWays(state.hand, eaten);
                BufferedImage scr = captureScreen();
                int s2 = scr == null ? -1 : classifyBtnSlot(scr, BAR_BTN2);
                if (s2 == MahjongPluginService.BTN_PON) {
                    out.put("ok", false);
                    out.put("message", "该反应首个按钮是碰而非吃（吃可能不在键2）——请手动吃后说「吃回报 A B C」同步");
                    return out;
                }
                clickBtnSlot(BAR_BTN2);
                coopWaiting = false;
                clearDialog();
                if (ways == 1) {
                    // 唯一组合：点吃键后游戏自动完成该顺子 → 直接记录
                    state.recordChi(a, b, eaten);
                    needsResync = true;
                    coopWaiting = false;
                    // 吃后无摸牌：摸牌区空，需按剩余手牌直接弃一张
                    discardAfterMeld = true;
                    meldActAt = System.currentTimeMillis();
                    lastAction = "已吃（" + plugin.tileName(a) + plugin.tileName(b) + " 吃 " + plugin.tileName(eaten) + "）";
                    System.out.println("[麻将实操] " + lastAction);
                    out.put("ok", true); out.put("message", lastAction + "，继续自动打牌");
                } else {
                    // 多个吃组合且可能遮挡牌池 → 交还用户点选，完成后「吃回报 A B C」同步（响应牌第一张）
                    lastAction = "已点吃键（可组合 " + ways + " 种）——请在游戏里点选你要的组合；完成后说「吃回报 响应牌 手牌1 手牌2」同步";
                    System.out.println("[麻将实操] " + lastAction);
                    out.put("ok", true); out.put("message", lastAction);
                }
                return out;
            }
            out.put("ok", false);
            out.put("message", "无法识别的合作指令（支持：跳过 / 碰 X / 杠 X / 吃 响应牌+两张手牌）");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "合作指令执行失败: " + e.getMessage());
        }
        return out;
    }

    /** 三张是否同花色连续顺子（允许任意顺序输入） */
    private boolean isRunThree(Integer x, Integer y, Integer z) {
        if (x == null || y == null || z == null) return false;
        int sx = x / 9, sy = y / 9, sz = z / 9;
        if (sx != sy || sy != sz || sx > 2) return false; // 0-2 花色（万/条/筒），字牌不可吃
        int[] r = {x % 9, y % 9, z % 9};
        java.util.Arrays.sort(r);
        return r[0] != r[1] && r[1] != r[2] && r[2] - r[0] == 2;
    }

    /** 以响应牌 eaten 为中心，当前手牌里可组成的顺子数量（判断吃组合是否唯一） */
    private int chiWays(List<Integer> hand, Integer eaten) {
        if (eaten == null || eaten / 9 > 2) return 0;
        int suit = eaten / 9, rank = eaten % 9;
        int[][] pairs = {{rank - 2, rank - 1}, {rank - 1, rank + 1}, {rank + 1, rank + 2}};
        int ways = 0;
        for (int[] p : pairs) {
            if (p[0] < 0 || p[1] > 8) continue;
            int ta = suit * 9 + p[0], tb = suit * 9 + p[1];
            List<Integer> tmp = new ArrayList<>(hand);
            if (tmp.remove(Integer.valueOf(ta)) && tmp.remove(Integer.valueOf(tb))) ways++;
        }
        return ways;
    }

    /** 碰回报（手动碰后同步牌库，同吃回报） */
    public Map<String, Object> ponReport(String tileText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!playing) { out.put("ok", false); out.put("message", "未在对局中"); return out; }
            Integer t = plugin.parseTileToken(tileText);
            if (t == null) { out.put("ok", false); out.put("message", "无法解析牌：" + tileText); return out; }
            state.recordPon(t);
            needsResync = true;
            out.put("ok", true);
            out.put("message", "已记录碰：" + plugin.tileName(t) + " | " + dumpHand());
            System.out.println("[麻将实操] " + out.get("message"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "碰回报异常: " + e.getMessage());
        }
        return out;
    }

    /** 杠回报（手动杠后同步牌库；明/暗/加杠统一记 meld） */
    public Map<String, Object> kanReport(String tileText) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (!playing) { out.put("ok", false); out.put("message", "未在对局中"); return out; }
            Integer t = plugin.parseTileToken(tileText);
            if (t == null) { out.put("ok", false); out.put("message", "无法解析牌：" + tileText); return out; }
            state.recordKan(t);
            needsResync = true;
            out.put("ok", true);
            out.put("message", "已记录杠：" + plugin.tileName(t) + " | " + dumpHand());
            System.out.println("[麻将实操] " + out.get("message"));
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "杠回报异常: " + e.getMessage());
        }
        return out;
    }

    // ===== 状态/停止 =====
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("playing", playing);
        out.put("riichi", state.riichi);
        out.put("hand", dumpHand());
        out.put("meldCount", state.meldCount());
        out.put("lastAction", lastAction);
        out.put("dialog", pendingDialog == null ? new String[0] : pendingDialog);
        out.put("dialogSeq", dialogSeq);
        out.put("coopMode", coopMode);
        out.put("coopWaiting", coopWaiting);
        return out;
    }

    public void stop() {
        playing = false;
        armed = false;
        autoStuck = false;
        ambiguityStage = 0;
        ambiguousSince = 0;
        resyncPending = false;
        emptyStreak = 0;
        armedSince = 0;
        screenAnomaly = false;
        riichiArmed = false;
        postWinUntil = 0;
        lastBarActAt = 0;
        roundEnded = false;
        endStreak = 0;
        awaitNextRound = false;
        handBackStreak = 0;
        pendingDialog = null;
        awaitingConfirm = false;
        pendingHand13 = null;
        pendingDrawn = null;
        pendingFrame = null;
        pendingSlotRectsLive = null;
        pendingReadDesc = "";
        coopWaiting = false;
        discardAfterMeld = false;
        if (poller != null) { poller.shutdownNow(); poller = null; }
    }

    private String dumpHand() {
        StringBuilder sb = new StringBuilder();
        for (Integer i : state.hand) sb.append(plugin.tileName(i)).append(" ");
        return sb.toString().trim();
    }

    // ===== 屏幕/点击工具 =====
    private BufferedImage captureScreen() {
        try {
            Rectangle rect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            return new Robot().createScreenCapture(rect);
        } catch (Exception e) {
            System.out.println("[麻将实操] 截屏失败: " + e.getMessage());
            return null;
        }
    }

    /** 整帧暗像素占比（判断是否麻将桌面；纯白/桌面壁纸会很低） */
    private double frameDarkRatio(BufferedImage screen) {
        try {
            int dark = 0, total = 0;
            for (int y = 0; y < screen.getHeight(); y += 12) {
                for (int x = 0; x < screen.getWidth(); x += 12) {
                    int rgb = screen.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum < 90) dark++;
                    total++;
                }
            }
            return total == 0 ? 0 : (double) dark / total;
        } catch (Exception e) {
            return 0;
        }
    }

    private double drawnSlotBrightRatio(BufferedImage screen, MahjongPluginService.Rect slot) {
        try {
            int x1 = Math.max(0, slot.x1), y1 = Math.max(0, slot.y1);
            int x2 = Math.min(screen.getWidth() - 1, slot.x2), y2 = Math.min(screen.getHeight() - 1, slot.y2);
            int bright = 0, total = 0;
            for (int y = y1; y <= y2; y += 3) {
                for (int x = x1; x <= x2; x += 3) {
                    int rgb = screen.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum > 140) bright++;
                    total++;
                }
            }
            return total == 0 ? 0 : bright * 100.0 / total;
        } catch (Exception e) {
            return 0;
        }
    }

    private boolean isDrawnSlotFilled(BufferedImage screen, MahjongPluginService.Rect slot) {
        return drawnSlotBrightRatio(screen, slot) > 3; // 已摸牌实测 ~10%，阈值 3% 防实机降采样差异
    }

    private void click(int x, int y) {
        try {
            lockPetWindow(); // 点击前让桌宠窗口点击穿透，避免点击被吞
            if (robot == null) robot = new Robot();
            robot.mouseMove(x, y);
            robot.delay(80);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(60);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            System.out.println("[麻将实操] 点击 (" + x + "," + y + ")");
            robot.delay(120);
            unlockPetWindow(); // 点击完成恢复桌宠可交互
            // 关键：把鼠标移离牌区（悬停会让雀魂把该牌置为"选中"并上移约1/4，干扰后续读牌/手牌检测）
            try {
                Rectangle scr = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                robot.mouseMove(scr.width / 2, scr.height / 2);
            } catch (Exception e2) { /* ignore */ }
        } catch (Exception e) {
            System.out.println("[麻将实操] 点击失败: " + e.getMessage());
            unlockPetWindow();
        }
    }

    /** 摸牌位是否有牌：亮块占比（快）或 固定框模板命中（仿按键检测——纯模板，抗亮度/边缘差异，稳定快速） */
    private boolean drawnTilePresent(BufferedImage screen) {
        MahjongPluginService.Rect dr = drawnRectLive();
        if (dr == null) return false;
        if (isDrawnSlotFilled(screen, dr)) return true;
        // 亮块不足但内容像牌 → 用固定框裁图做模板判定（桌面背景不会命中任何牌）
        BufferedImage crop = plugin.cropForPlay(screen, dr, 200);
        return crop != null && plugin.matchTile(crop) >= 0;
    }

    /** 摸牌位实机矩形（固定值映射；若有人工标注的"摸牌区"则优先用标注值；并避开桌宠区域） */
    private MahjongPluginService.Rect drawnRectLive() {
        int[] dr = drawnRegion2560;
        // 副露/手牌缩短后：手牌行按原网格左对齐压缩，摸牌位紧跟最后一张手牌（手牌区与摸牌区间距不变）
        // → 用原 13 槽网格末牌右缘 + 原始间距动态左移摸牌框
        if (state.hand.size() < 13 && slots2560.size() >= 13 && !state.hand.isEmpty()) {
            int n = state.hand.size(); // 隐蔽手牌数（0..12）
            MahjongPluginService.Rect last = slots2560.get(n - 1);
            int gap = drawnRegion2560[0] - slots2560.get(12).x2; // 原间距（2560 基准）
            if (gap < 0) gap = 30;
            int w = drawnRegion2560[1] - drawnRegion2560[0];
            int nx1 = last.x2 + gap;
            dr = new int[]{nx1, nx1 + w, drawnRegion2560[2], drawnRegion2560[3]};
        }
        int x2 = dr[1];
        if (petX1_2560 > 0 && petX1_2560 - 3 < x2) x2 = petX1_2560 - 3; // 右界钳到桌宠左缘前
        return new MahjongPluginService.Rect(
                (int) Math.round(dr[0] * scaleX),
                (int) Math.round(dr[2] * scaleY),
                (int) Math.round(x2 * scaleX),
                (int) Math.round(dr[3] * scaleY));
    }

    // ===== 人工标注区域（regions.json） =====
    /** 读取标注区域：优先"（桌宠截屏）"实机帧标注，应用 摸牌区/手牌区/桌宠 */
    public void loadRegions() {
        try {
            if (!REGIONS_FILE.exists()) return;
            Map<String, Object> m = JSON.readValue(REGIONS_FILE, Map.class);
            int[] drawn = regionOf(m, "摸牌区（桌宠截屏）");
            if (drawn == null) drawn = regionOf(m, "摸牌区");
            int[] hand = regionOf(m, "手牌区（桌宠截屏）");
            if (hand == null) hand = regionOf(m, "手牌区");
            int[] pet = regionOf(m, "同高度下桌宠模型区（桌宠截屏）");
            if (pet == null) pet = regionOf(m, "桌宠");
            if (drawn != null) {
                drawnRegion2560 = drawn;
                System.out.println("[麻将实操] 使用人工标注摸牌区: [" + drawn[0] + "," + drawn[1] + "]x[" + drawn[2] + "," + drawn[3] + "]");
            }
            if (pet != null) {
                petX1_2560 = pet[0];
                System.out.println("[麻将实操] 桌宠区域 x1=" + petX1_2560 + "（摸牌区/扫描自动避让）");
            }
            // 逐槽人工标注优先：检测到 13 个「手牌槽N」或「摸牌区N」（N=0..12）区域 → 按 N 逐槽使用
            // （比 手牌区 13 等分更准：每槽 x 独立贴合牌缘，避免重合/大小不一）
            boolean perSlotUsed = false;
            java.util.TreeMap<Integer, int[]> slotRegs = new java.util.TreeMap<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!(e.getValue() instanceof Map)) continue;
                java.util.regex.Matcher sm = java.util.regex.Pattern.compile("^(?:手牌槽|摸牌区)(\\d+)$").matcher(e.getKey());
                if (!sm.find()) continue;
                int idx = Integer.parseInt(sm.group(1));
                if (idx < 0 || idx > 12) continue;
                int[] b = regionOf(m, e.getKey());
                if (b != null) slotRegs.put(idx, b);
            }
            if (slotRegs.size() == 13) {
                List<MahjongPluginService.Rect> slots = new ArrayList<>();
                for (int i = 0; i < 13; i++) {
                    int[] b = slotRegs.get(i);
                    slots.add(new MahjongPluginService.Rect(b[0], b[2], b[1], b[3]));
                }
                slots2560 = slots;
                int[] b0 = slotRegs.get(0);
                bandY1 = b0[2];
                bandY2 = b0[3];
                annotatedHand = true;
                perSlotUsed = true;
                System.out.println("[麻将实操] 使用逐槽人工标注（13槽）：第1槽x[" + slotRegs.get(0)[0] + "," + slotRegs.get(0)[1]
                        + "] 第13槽x[" + slotRegs.get(12)[0] + "," + slotRegs.get(12)[1] + "] y[" + bandY1 + "," + bandY2 + "]");
            }
            if (!perSlotUsed && hand != null) {
                // 手牌 13 等分；高度统一用摸牌区的 y（x 只定槽位）
                int span = hand[1] - hand[0];
                int tw = Math.max(40, span / 13);
                int y1 = drawn != null ? drawn[2] : hand[2];
                int y2 = drawn != null ? drawn[3] : hand[3];
                List<MahjongPluginService.Rect> slots = new ArrayList<>();
                for (int i = 0; i < 13; i++) {
                    slots.add(new MahjongPluginService.Rect(hand[0] + i * tw, y1, hand[0] + (i + 1) * tw, y2));
                }
                slots2560 = slots;
                bandY1 = y1;
                bandY2 = y2;
                annotatedHand = true;
                System.out.println("[麻将实操] 使用人工标注手牌区 13 等分（高度取摸牌区 y）");
            }
            // 操作选项键位（键1=跳过（右起第一），键2/键3 在其左；高度统一取键1框）
            int[] skipB = regionOf(m, "跳过（键位1）");
            if (skipB == null) skipB = regionOf(m, "跳过");
            int[] box2 = regionOf(m, "立直（键位2）");
            if (box2 == null) box2 = regionOf(m, "自摸（键位2）");
            if (box2 == null) box2 = regionOf(m, "胡（键位2）");
            int[] box3 = regionOf(m, "立直（键位3）");
            if (skipB != null) {
                int y1b = skipB[2], y2b = skipB[3];
                btnSlots2560[0] = new int[]{skipB[0], skipB[1], y1b, y2b};
                if (box2 != null) btnSlots2560[1] = new int[]{box2[0], box2[1], y1b, y2b};
                if (box3 != null) btnSlots2560[2] = new int[]{box3[0], box3[1], y1b, y2b};
                System.out.println("[麻将实操] 使用人工标注操作键位：键1(跳过)x[" + btnSlots2560[0][0] + "," + btnSlots2560[0][1]
                        + "] 键2x[" + btnSlots2560[1][0] + "," + btnSlots2560[1][1]
                        + "] 键3x[" + (btnSlots2560[2] == null ? "-" : btnSlots2560[2][0] + "," + btnSlots2560[2][1]) + "]");
            }
        } catch (Exception e) {
            System.out.println("[麻将实操] 读取 regions.json 失败: " + e.getMessage());
        }
    }

    private int[] regionOf(Map<String, Object> m, String name) {
        try {
            Object r = m.get(name);
            if (r instanceof Map) {
                Map<?, ?> reg = (Map<?, ?>) r;
                return new int[]{
                        ((Number) reg.get("x1")).intValue(), ((Number) reg.get("x2")).intValue(),
                        ((Number) reg.get("y1")).intValue(), ((Number) reg.get("y2")).intValue()
                };
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /** 读取全部标注区域（UTF-8） */
    public Map<String, Object> getRegions() {
        try {
            if (REGIONS_FILE.exists()) {
                String content = new String(java.nio.file.Files.readAllBytes(REGIONS_FILE.toPath()),
                        java.nio.charset.StandardCharsets.UTF_8);
                return JSON.readValue(content, Map.class);
            }
        } catch (Exception e) {
            System.out.println("[麻将实操] 读取 regions.json 失败: " + e.getMessage());
        }
        return new LinkedHashMap<>();
    }

    /**
     * 保存/更新一个标注区域。
     * 传入的 x1..y2 是**原图像素坐标**：
     *   · x1..y2 落库时按宽度等比换算成 2560 基准（麻将/抽卡运行时读的就是这一套，保持不变）；
     *   · 原图像素坐标原样存进 px 字段 —— 标注页读回来是无损的原始像素，
     *     所以非 2560 宽、甚至长宽比完全不同的设计图（如「基础设置」背景图）也能精确往返、不丢精度。
     * imageHeight 只作记录：换算只按宽度等比，长宽比不会被拉变形。
     */
    public Map<String, Object> saveRegion(String name, int x1, int y1, int x2, int y2, int imageWidth, int imageHeight) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (name == null || name.trim().isEmpty()) { out.put("ok", false); out.put("message", "区域名不能为空"); return out; }
            if (x2 <= x1 || y2 <= y1) { out.put("ok", false); out.put("message", "区域无效（宽高必须为正）"); return out; }
            double s = 2560.0 / Math.max(1, imageWidth);
            Map<String, Object> reg = new LinkedHashMap<>();
            reg.put("x1", (int) Math.round(x1 * s));
            reg.put("y1", (int) Math.round(y1 * s));
            reg.put("x2", (int) Math.round(x2 * s));
            reg.put("y2", (int) Math.round(y2 * s));
            reg.put("imageWidth", imageWidth);
            reg.put("imageHeight", imageHeight);
            Map<String, Object> px = new LinkedHashMap<>();
            px.put("x1", x1); px.put("y1", y1); px.put("x2", x2); px.put("y2", y2);
            reg.put("px", px);   // 原图像素坐标（无损，标注页按它显示/回填）
            Map<String, Object> all = getRegions();
            all.put(name, reg);
            writeRegions(all);
            loadRegions(); // 立即生效
            out.put("ok", true);
            out.put("message", "已保存区域「" + name + "」像素 [" + x1 + "," + y1 + "]→[" + x2 + "," + y2 + "]"
                    + " ＝ 2560基准 [" + reg.get("x1") + "," + reg.get("y1") + "]→[" + reg.get("x2") + "," + reg.get("y2") + "]");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "保存失败: " + e.getMessage());
        }
        return out;
    }

    /**
     * 标注页「导入图片」落盘：把浏览器读到的 dataURL 写到 static/标注图片/。
     * 该目录在 WebConfig 里是**文件系统直出**（跟麻将错题截屏一样），所以导完立刻能访问、不用 rebuild，
     * 下次打开标注页也会出现在下拉框的「导入的图片」分组里。
     */
    public Map<String, Object> saveRegionImage(String fileName, String dataUrl) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            if (dataUrl == null || !dataUrl.startsWith("data:image/")) {
                out.put("ok", false); out.put("message", "不是有效的图片数据"); return out;
            }
            int comma = dataUrl.indexOf(',');
            if (comma < 0) { out.put("ok", false); out.put("message", "图片数据格式不对"); return out; }
            String head = dataUrl.substring(0, comma).toLowerCase();
            String ext = head.contains("jpeg") || head.contains("jpg") ? "jpg"
                    : head.contains("webp") ? "webp"
                    : head.contains("gif") ? "gif"
                    : head.contains("bmp") ? "bmp" : "png";
            byte[] bytes = java.util.Base64.getDecoder().decode(dataUrl.substring(comma + 1));
            if (bytes.length == 0) { out.put("ok", false); out.put("message", "图片内容为空"); return out; }
            if (bytes.length > 16 * 1024 * 1024) { out.put("ok", false); out.put("message", "图片太大（超过 16MB）"); return out; }
            // 魔数校验：只认真正的图片，避免把随便一个文件塞进资源目录
            boolean magic = bytes.length > 12 && (
                       (bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G')
                    || (bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8)
                    || (bytes[0] == 'G' && bytes[1] == 'I' && bytes[2] == 'F')
                    || (bytes[0] == 'B' && bytes[1] == 'M')
                    || (bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                        && bytes[8] == 'W' && bytes[9] == 'E'));
            if (!magic) { out.put("ok", false); out.put("message", "无法识别的图片格式（支持 png / jpg / gif / bmp / webp）"); return out; }

            String base = fileName == null ? "" : fileName.trim();
            int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
            if (slash >= 0) base = base.substring(slash + 1);
            base = base.replaceAll("[\\\\/:*?\"<>|]", "_");
            int dot = base.lastIndexOf('.');
            String stem = dot > 0 ? base.substring(0, dot) : base;
            if (stem.isEmpty()) stem = "导入图片";
            if (stem.length() > 60) stem = stem.substring(0, 60);
            String finalName = stem + "." + ext;

            File dir = new File(System.getProperty("user.dir"), "src/main/resources/static/标注图片");
            if (!dir.exists() && !dir.mkdirs()) { out.put("ok", false); out.put("message", "创建目录失败: " + dir); return out; }
            File f = new File(dir, finalName);
            java.nio.file.Files.write(f.toPath(), bytes);   // 同名直接覆盖：重新导入同一张图不留垃圾

            out.put("ok", true);
            out.put("name", finalName);
            out.put("url", "/标注图片/" + java.net.URLEncoder.encode(finalName, "UTF-8").replace("+", "%20"));
            out.put("message", "已保存 标注图片/" + finalName + "（" + (bytes.length / 1024) + " KB）");
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "保存图片失败: " + e.getMessage());
        }
        return out;
    }

    /** 删除一个标注区域 */
    public Map<String, Object> deleteRegion(String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            Map<String, Object> all = getRegions();
            if (all.remove(name) != null) {
                writeRegions(all);
                loadRegions();
                out.put("ok", true);
                out.put("message", "已删除区域「" + name + "」");
            } else {
                out.put("ok", false);
                out.put("message", "区域不存在");
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", "删除失败: " + e.getMessage());
        }
        return out;
    }

    /** 以 UTF-8 写入 regions.json（避免中文名乱码） */
    private void writeRegions(Map<String, Object> all) throws Exception {
        String json = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(all);
        java.nio.file.Files.write(REGIONS_FILE.toPath(), json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 标注页可选的图片清单：{name,url,group}（分组：麻将参考截屏 / 实机截屏 / 导入的图片） */
    public List<Map<String, Object>> calibrationFiles() {
        List<Map<String, Object>> out = new ArrayList<>();
        File dir = new File(System.getProperty("user.dir"), "src/main/resources/static/麻将参考截屏");
        File[] fs = dir.listFiles((d, n) -> n.endsWith(".png"));
        if (fs != null) {
            java.util.Arrays.sort(fs);
            for (File f : fs) out.add(imgEntry(f.getName(), "/麻将参考截屏/", "麻将参考截屏"));
        }
        File log = new File(System.getProperty("user.dir"), "desktop_vision_log");
        File[] ls = log.listFiles((d, n) -> n.startsWith("mahjong_") && n.endsWith(".png"));
        if (ls != null) {
            java.util.Arrays.sort(ls, (a, b) -> b.getName().compareTo(a.getName()));
            int n = 0;
            for (File f : ls) { if (n >= 40) break; n++; out.add(imgEntry(f.getName(), "/desktop_vision_log/", "实机截屏")); }
        }
        // 标注页「导入图片」落盘的目录（文件系统直出，免 rebuild）；新导入的排前面
        File anno = new File(System.getProperty("user.dir"), "src/main/resources/static/标注图片");
        File[] as = anno.listFiles((d, n) -> n != null && n.matches("(?i).+\\.(png|jpg|jpeg|gif|bmp|webp)"));
        if (as != null) {
            java.util.Arrays.sort(as, (a, b) -> b.getName().compareTo(a.getName()));
            for (File f : as) out.add(imgEntry(f.getName(), "/标注图片/", "导入的图片"));
        }
        return out;
    }

    /** 构造一条图片清单项（URL 用 URLEncoder，中文名也能访问） */
    private Map<String, Object> imgEntry(String name, String prefix, String group) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("group", group);
        try {
            m.put("url", prefix + java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20"));
        } catch (Exception e) {
            m.put("url", prefix + name);
        }
        return m;
    }

    /** 动态定位摸牌牌：摸牌与手牌同高度（手牌带 y 内），从最后手牌槽右缘之后扫描最长的牌面亮块（避开桌宠） */
    private MahjongPluginService.Rect locateDrawnTile(BufferedImage screen) {
        try {
            if (slots2560.isEmpty() || screen == null) return null;
            // 与手牌同高度：y 取手牌带（上下略扩）
            int yTop = Math.max(0, (int) Math.round(bandY1 * scaleY) - 20);
            int yBot = Math.min(screen.getHeight() - 1, (int) Math.round(bandY2 * scaleY) + 20);
            int xStart = (int) Math.round(slots2560.get(slots2560.size() - 1).x2 * scaleX) + 40;
            int xEnd = screen.getWidth() - 1;
            if (petX1_2560 > 0) xEnd = Math.min(xEnd, (int) Math.round(petX1_2560 * scaleX) - 3); // 避开桌宠
            if (xStart >= xEnd || yBot <= yTop) return null;
            List<int[]> runs = new ArrayList<>();
            int gs = -1;
            for (int x = xStart; x <= xEnd; x++) {
                int c = 0, n = 0;
                for (int y = yTop; y <= yBot; y += 2) {
                    n++;
                    int rgb = screen.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum > 140) c++;
                }
                boolean on = n > 0 && c * 100 / n > 40;
                if (on && gs < 0) gs = x;
                if (!on && gs >= 0) {
                    if (x - gs >= 55) runs.add(new int[]{gs, x - 1});
                    gs = -1;
                }
            }
            if (gs >= 0 && xEnd - gs + 1 >= 55) runs.add(new int[]{gs, xEnd});
            if (runs.isEmpty()) return null;
            int[] best = runs.get(0);
            for (int[] r : runs) if (r[1] - r[0] > best[1] - best[0]) best = r;
            // 精修 y：x 亮块内逐行统计亮像素，取牌面主体行（上下桌面/阴影不裁入，保证与模板同规格）
            int ry1 = -1, ry2 = -1;
            int xStep = Math.max(1, (best[1] - best[0]) / 40);
            for (int y = yTop; y <= yBot; y++) {
                int c = 0, n = 0;
                for (int x = best[0]; x <= best[1]; x += xStep) {
                    n++;
                    int rgb = screen.getRGB(x, y);
                    int lum = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    if (lum > 140) c++;
                }
                if (n > 0 && c * 100 / n > 35) { if (ry1 < 0) ry1 = y; ry2 = y; }
            }
            if (ry1 < 0 || ry2 < 0) return new MahjongPluginService.Rect(best[0], yTop, best[1], yBot); // 兜底整带
            int pad = 6;
            int fy1 = Math.max(yTop, ry1 - pad);
            int fy2 = Math.min(yBot, ry2 + pad);
            return new MahjongPluginService.Rect(best[0], fy1, best[1], fy2);
        } catch (Exception e) {
            return null;
        }
    }

    /** 点击疑似未生效时重试（点上次实际点击的坐标；click 内部会先锁定桌宠穿透） */
    private void retryDiscardClick() {
        try {
            if (lastClickX < 0 || lastClickY < 0) return;
            click(lastClickX, lastClickY);
            discardDoneAt = System.currentTimeMillis();
        } catch (Exception e) {
            System.out.println("[麻将实操] 重试点击异常: " + e.getMessage());
        }
    }

    /** 控制桌宠窗口点击穿透（Electron 控制端口 3081；桌宠未运行则忽略） */
    private void lockPetWindow() { petControl("lock"); }
    private void unlockPetWindow() { petControl("unlock"); }

    private void petControl(String action) {
        try {
            URL url = new URL("http://127.0.0.1:3081/" + action);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(400);
            con.setReadTimeout(400);
            con.getResponseCode();
            con.disconnect();
        } catch (Exception e) {
            System.out.println("[麻将实操] 桌宠控制(" + action + ")失败(忽略): " + e.getMessage());
        }
    }

    private int slotCenterX(int slot) {
        if (slot < 0 || slot >= slots2560.size()) return -1;
        MahjongPluginService.Rect r = slots2560.get(slot);
        return (int) Math.round(((r.x1 + r.x2) / 2.0) * scaleX);
    }

    private int bandCenterY() {
        return (int) Math.round(((bandY1 + bandY2) / 2.0) * scaleY);
    }

    /** 2560 基准坐标 → 实机坐标 */
    private MahjongPluginService.Rect toLiveRect(MahjongPluginService.Rect r2560) {
        int x1 = (int) Math.round(r2560.x1 * scaleX);
        int x2 = (int) Math.round(r2560.x2 * scaleX);
        int y1 = (int) Math.round(r2560.y1 * scaleY);
        int y2 = (int) Math.round(r2560.y2 * scaleY);
        return new MahjongPluginService.Rect(x1, y1, x2, y2);
    }

    /** 摸牌槽位：手牌左对齐 → 手牌之后的位置（无副露时=第14槽） */
    private MahjongPluginService.Rect drawnSlot2560() {
        if (slots2560.isEmpty()) return null;
        int idx = Math.min(state.hand.size(), slots2560.size() - 1);
        return slots2560.get(idx);
    }
}
