package com.cy.mahjong;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

/**
 * 麻将插件管线验证（临时测试，用校准截图跑完整链路：检测→读牌→牌效→评论）
 */
@SpringBootTest
public class MahjongVisionTest {

    @Autowired
    private MahjongPluginService service;

    @Test
    public void runCalibration() {
        // 用户提供的真值（w=万,b=筒,t=条）：索引 0-8 万, 9-17 条, 18-26 筒, 27-33 字(东南西北白发中)
        Map<String, int[][]> gt = new java.util.HashMap<>();
        gt.put("自己进行游戏.png", new int[][]{
                {0, 8, 18, 20, 24, 25, 11, 11, 13, 17, 17, 31, 33}, // 手牌13
                {18}, // 摸牌 1b
                {}}) ; // 副露
        gt.put("自己玩1.png", new int[][]{
                {8, 18, 18, 19, 21, 24, 9, 9, 11, 12, 14, 32, 33}, {23}, {}});
        gt.put("自己玩2.png", new int[][]{
                {8, 21, 22, 24, 25, 9, 11, 13, 14, 15, 29, 29, 30}, {16}, {}});
        gt.put("自己玩3.png", new int[][]{
                {0, 20, 21, 23, 24, 26, 9, 12, 12, 15, 27, 28, 29}, {10}, {}});
        gt.put("自己玩4.png", new int[][]{
                {0, 1, 2, 5, 5, 6, 8, 18, 19, 20, 21, 22, 22}, {14}, {}});
        gt.put("自己玩5.png", new int[][]{
                {0, 1, 2, 3, 4, 6, 7, 19, 20, 23, 23, 15, 15}, {0}, {}});
        gt.put("自己玩6.png", new int[][]{
                {0, 21, 24, 25, 26, 11, 12, 13, 15, 17, 30, 31, 32}, {10}, {}});
        for (String name : new String[]{"自己进行游戏.png", "自己玩1.png", "自己玩2.png", "自己玩3.png", "自己玩4.png", "自己玩5.png", "自己玩6.png"}) {
            System.out.println("\n================ 校准图: " + name + " ================");
            Map<String, Object> r = service.analyze(true, name);
            System.out.println("enabled=" + r.get("enabled") + " ok=" + r.get("ok") + " source=" + r.get("source"));
            System.out.println("debug=" + r.get("debug"));
            if (Boolean.FALSE.equals(r.get("ok"))) {
                System.out.println("FAIL: " + r.get("message"));
                continue;
            }
            List<Integer> read = (List<Integer>) r.get("_labels");
            int[][] expect = gt.get(name);
            List<Integer> handRead = new java.util.ArrayList<>();
            for (int i = 0; i < expect[0].length; i++) {
                Integer v = read != null && i < read.size() ? read.get(i) : null;
                handRead.add(v);
                String mark = (v != null && v.equals(expect[0][i])) ? "✔" : "✗";
                System.out.println("  pos" + (i + 1) + ": 读=" + (v == null ? "null" : MahjongPluginService.TILE_NAMES[v])
                        + " 真=" + MahjongPluginService.TILE_NAMES[expect[0][i]] + " " + mark);
            }
            Integer drawnRead = read != null && read.size() > 13 ? read.get(13) : null;
            System.out.println("  摸牌: 读=" + (drawnRead == null ? "null" : MahjongPluginService.TILE_NAMES[drawnRead])
                    + " 真=" + MahjongPluginService.TILE_NAMES[expect[1][0]]);
            System.out.println("  排序校验 sortedOk=" + r.get("sortedOk"));
            System.out.println("  向听=" + r.get("shanten") + " 建议=" + r.get("suggestions"));
            System.out.println("  评论=" + r.get("comment"));
        }
    }

    @Test
    public void testScaleAdaptation() {
        System.out.println("\n===== 尺度适配实验 =====");
        System.out.println("headless=" + java.awt.GraphicsEnvironment.isHeadless());
        Map<String, Object> r = service.testScaleAdaptation();
        System.out.println("result=" + r);
    }

    @Test
    public void testLeaveOneOutAccuracy() {
        System.out.println("\n===== 留一法准确度（其余图建模板，测本图；未覆盖的牌=视觉兜底区） =====");
        Map<String, Object> r = service.leaveOneOutAccuracy();
        System.out.println(r.get("summary"));
        for (Object o : (List<?>) r.get("perFile")) {
            Map<?, ?> m = (Map<?, ?>) o;
            System.out.println(m.get("file") + " 覆盖=" + m.get("cover") + " 全对=" + m.get("hit") + " 覆盖内全对=" + m.get("coverHit"));
            for (Object d : (List<?>) m.get("detail")) {
                Map<?, ?> row = (Map<?, ?>) d;
                if (Boolean.FALSE.equals(row.get("ok"))) {
                    System.out.println("    ✗ pos" + row.get("pos") + ": 读=" + row.get("read") + " 真=" + row.get("truth") + " score=" + row.get("score"));
                }
            }
        }
    }

    @Test
    public void testTemplateGeneralization() {
        System.out.println("\n===== 模板泛化：从[观看]构建，匹配[自己] =====");
        Map<String, Object> r1 = service.templateGeneralizationTest("观看他人直播.png", "自己进行游戏.png");
        System.out.println("命中: " + r1.get("hit"));
        for (Object o : (List<?>) r1.get("rows")) System.out.println(o);
        System.out.println("\n===== 模板泛化：从[自己]构建，匹配[观看] =====");
        Map<String, Object> r2 = service.templateGeneralizationTest("自己进行游戏.png", "观看他人直播.png");
        System.out.println("命中: " + r2.get("hit"));
        for (Object o : (List<?>) r2.get("rows")) System.out.println(o);
    }

    @Test
    public void testGeometryReport() {
        System.out.println("\n===== 几何检测诊断（原生 vs 1707） =====");
        Map<String, Object> r = service.geometryReport();
        System.out.println(r);
    }

    @Test
    public void testPlayStateMachine() {
        MahjongGameState s = new MahjongGameState();
        // 开局：9w 1b 1b 2b 4b 7b 1t 1t 3t 4t 6t 发 中 + 摸6b
        List<Integer> hand13 = new java.util.ArrayList<>(java.util.Arrays.asList(8, 18, 18, 19, 21, 24, 9, 9, 11, 12, 14, 32, 33));
        s.init(hand13, 23);
        System.out.println("开局: " + s);
        // 打出摸牌6b → 位置=13
        int pos = s.discard(23);
        System.out.println("打摸牌6b pos=" + pos + " (期望13)，状态: " + s);
        assert pos == 13 && s.drawn == null && s.handSize() == 13;
        // 摸5b 并打出 手牌9w（摸牌并入）
        s.draw(22);
        int pos2 = s.discard(8);
        System.out.println("摸5b后打9w pos=" + pos2 + " (期望0)，状态: " + s);
        assert pos2 == 0 && s.drawn == null && s.hand.contains(22) && s.handSize() == 13;
        // 吃回报：吃 二筒 四筒（手牌有），吃的牌=三筒
        System.out.println("吃前手牌: " + s);
        s.recordChi(19, 21, 20);
        System.out.println("吃(2b4b吃3b)后: " + s);
        assert s.handSize() == 11 && s.meldCount() == 1;
        // 碰回报：碰 1t（手牌两张1t）
        s.recordPon(9);
        System.out.println("碰1t后: " + s);
        assert s.handSize() == 9 && s.meldCount() == 2;
        System.out.println("状态机测试通过 ✓");
    }

    @Test
    public void testShantenBasics() {
        // 基础向听验证：3面子+雀头+搭子(13张)=听牌 0
        int[] h1 = new int[34];
        // 123m 456m 789m + 33p(雀头) + 45s(搭子)
        int[][] tiles = {{0,1,2},{3,4,5},{6,7,8},{20,20},{12,13}};
        for (int[] t : tiles) for (int i : t) h1[i]++;
        System.out.println("shanten(3面子+雀头+搭子)=" + service.shanten13ForTest(h1, 0) + " (期望0)");
        // 4面子+单张 = 听牌 0
        int[] h2 = new int[34];
        int[][] t2 = {{0,1,2},{3,4,5},{6,7,8},{9,10,11},{27}};
        for (int[] t : t2) for (int i : t) h2[i]++;
        System.out.println("shanten(4面子+单张)=" + service.shanten13ForTest(h2, 0) + " (期望0)");
        // 3面子+雀头+2搭子(不可能13张)... 用 3面子+2搭子无雀头 → 1
        int[] h3 = new int[34];
        int[][] t3 = {{0,1,2},{3,4,5},{6,7,8},{9,10},{18,19}};
        for (int[] t : t3) for (int i : t) h3[i]++;
        System.out.println("shanten(3面子+2搭子无雀头)=" + service.shanten13ForTest(h3, 0) + " (期望1)");
        // chiitoi：6对+1单 = 0
        int[] h4 = new int[34];
        int[][] t4 = {{0,0},{5,5},{10,10},{15,15},{20,20},{25,25},{30}};
        for (int[] t : t4) for (int i : t) h4[i]++;
        System.out.println("shanten(6对+1单)=" + service.shanten13ForTest(h4, 0) + " (期望0)");
        // chiitoi：5对+三张同牌 = 1
        int[] h6 = new int[34];
        int[][] t6 = {{0,0,0},{5,5},{10,10},{15,15},{20,20},{25,25}};
        for (int[] t : t6) for (int i : t) h6[i]++;
        System.out.println("shanten(5对+三张)=" + service.shanten13ForTest(h6, 0) + " (期望1)");
        // 国士：11种+1对 = 0
        int[] h5 = new int[34];
        int[] kinds = {0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31};
        for (int k : kinds) h5[k] = 1;
        h5[33] = 2;
        System.out.println("shanten(国士11种+中对)=" + service.shanten13ForTest(h5, 0) + " (期望0)");
    }
}
