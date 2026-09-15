package com.cy.mahjong;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 雀魂牌面/按键模板库：用校准截图（已标注真值）裁剪出各模板灰度图，
 * 同类型多张样本取最优样本匹配，运行时对目标区域做归一化 SSD 匹配。
 * 同一客户端渲染确定性强；换客户端/换皮肤需重新校准。
 * 未命中（阈值以上）的识别由视觉模型兜底。
 */
public class MahjongTemplates {

    /** 模板统一尺寸（灰度）——牌面 96x135（竖牌）；按键可构造为横条（如 96x41） */
    private final int TW, TH;
    /** 匹配阈值：归一化平均绝对差，低于此值判为命中（收紧可避免近形误配） */
    private double threshold = 0.25;

    public MahjongTemplates() { this(96, 135); }

    public MahjongTemplates(int tw, int th) {
        this.TW = Math.max(8, tw);
        this.TH = Math.max(8, th);
    }

    /** label -> 样本列表（同牌型多张；匹配取最优样本，避免跨截图细微对齐差） */
    private Map<Integer, List<double[]>> samples = new HashMap<>();

    public boolean isEmpty() { return samples.isEmpty(); }

    public void setThreshold(double t) { this.threshold = t; }

    /** 添加一张带标签的牌面样本 */
    public void addSample(int label, BufferedImage tileCrop) {
        double[] g = toGray(tileCrop);
        if (g == null) return;
        samples.computeIfAbsent(label, k -> new ArrayList<>()).add(g);
    }

    /** 匹配：返回命中的牌型索引，未命中返回 -1 */
    public int match(BufferedImage crop) {
        if (samples.isEmpty()) return -1;
        double[] g = toGray(crop);
        if (g == null) return -1;
        int best = -1;
        double bestScore = Double.MAX_VALUE;
        for (Map.Entry<Integer, List<double[]>> e : samples.entrySet()) {
            for (double[] s : e.getValue()) {
                double sc = ssdShifted(g, s);
                if (sc < bestScore) { bestScore = sc; best = e.getKey(); }
            }
        }
        return bestScore <= threshold ? best : -1;
    }

    /** 返回最佳匹配分数（调试用） */
    public double matchScore(BufferedImage crop) {
        if (samples.isEmpty()) return Double.MAX_VALUE;
        double[] g = toGray(crop);
        if (g == null) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (List<double[]> list : samples.values()) {
            for (double[] s : list) best = Math.min(best, ssdShifted(g, s));
        }
        return best;
    }

    /** 前 n 个候选 label（按各自最优样本 SSD 升序，不过滤阈值——供"模板初判→视觉比对"管线使用） */
    public int[] topN(BufferedImage crop, int n) {
        if (samples.isEmpty()) return new int[0];
        double[] g = toGray(crop);
        if (g == null) return new int[0];
        List<java.util.Map.Entry<Integer, Double>> list = new ArrayList<>();
        for (java.util.Map.Entry<Integer, List<double[]>> e : samples.entrySet()) {
            double best = Double.MAX_VALUE;
            for (double[] s : e.getValue()) best = Math.min(best, ssdShifted(g, s));
            list.add(new java.util.AbstractMap.SimpleEntry<>(e.getKey(), best));
        }
        list.sort((a, b) -> Double.compare(a.getValue(), b.getValue()));
        int m = Math.min(n, list.size());
        int[] out = new int[m];
        for (int i = 0; i < m; i++) out[i] = list.get(i).getKey();
        return out;
    }

    /** 指定 label 的最优样本匹配分（越低越像；无样本/无图返回 MAX） */
    public double scoreOf(BufferedImage crop, int label) {
        List<double[]> list = samples.get(label);
        if (list == null || list.isEmpty()) return Double.MAX_VALUE;
        double[] g = toGray(crop);
        if (g == null) return Double.MAX_VALUE;
        double best = Double.MAX_VALUE;
        for (double[] s : list) best = Math.min(best, ssdShifted(g, s));
        return best;
    }

    /** 彩色牌面 → 固定尺寸归一化灰度向量（亮度归一化，抗轻微明暗差） */
    private double[] toGray(BufferedImage src) {
        try {
            BufferedImage resized = new BufferedImage(TW, TH, BufferedImage.TYPE_BYTE_GRAY);
            Graphics2D g = resized.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, TW, TH, null);
            g.dispose();
            double[] out = new double[TW * TH];
            double sum = 0;
            for (int i = 0; i < out.length; i++) {
                int v = resized.getRGB(i % TW, i / TW) & 0xFF;
                out[i] = v;
                sum += v;
            }
            double mean = sum / out.length;
            double var = 0;
            for (double v : out) var += (v - mean) * (v - mean);
            double std = Math.sqrt(var / out.length);
            if (std < 1e-6) std = 1;
            for (int i = 0; i < out.length; i++) out[i] = (out[i] - mean) / std;
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** 平移容差匹配：±1px 内取最优 SSD（吸收截图间细微对齐差；太大易误配近形牌） */
    private double ssdShifted(double[] a, double[] b) {
        double best = Double.MAX_VALUE;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                double s = 0;
                int n = 0;
                for (int y = 0; y < TH; y++) {
                    int sy = y + dy;
                    if (sy < 0 || sy >= TH) continue;
                    for (int x = 0; x < TW; x++) {
                        int sx = x + dx;
                        if (sx < 0 || sx >= TW) continue;
                        s += Math.abs(a[y * TW + x] - b[sy * TW + sx]);
                        n++;
                    }
                }
                if (n > 0) {
                    double sc = s / n;
                    if (sc < best) best = sc;
                }
            }
        }
        return best;
    }

    /** 平均绝对差 */
    private double ssd(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }
}
