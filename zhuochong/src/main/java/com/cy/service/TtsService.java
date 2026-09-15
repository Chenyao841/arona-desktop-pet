package com.cy.service;

import com.cy.mapper.AiConfigMapper;
import com.cy.mapper.TtsReferenceMapper;
import com.cy.pojo.AiConfig;
import com.cy.pojo.TtsReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Service
public class TtsService {

    @Autowired
    private AiConfigMapper aiConfigMapper;

    @Autowired
    private TtsReferenceMapper ttsReferenceMapper;

    private final RestTemplate restTemplate;
    private final File cacheDir;
    private final Map<String, String> memCache = new java.util.concurrent.ConcurrentHashMap<>();

    public TtsService() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(30));
        this.restTemplate = new RestTemplate(factory);
        this.cacheDir = new File(System.getProperty("user.dir"), "tts_cache");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    // 去掉括号内动作描述（TTS 不朗读）；去掉【】内心OS的括号标记但保留内容朗读
    private String cleanText(String text) {
        if (text == null) return "";
        String clean = text.replaceAll("[（(][^）)]*[）)]", "");
        clean = clean.replaceAll("【([^】]*)】", "$1");
        clean = clean.replaceAll("\\[([^\\]]*)\\]", "$1");
        // ～/~ 会被 GPT-SoVITS 文本前端映射成"…"=长音记号（text/chinese2.py rep_map），
        // 导致句首语气词（啊～/哦～）被拉成"啊————"并吞掉后续内容；统一换成逗号短停顿
        clean = clean.replace("～", "，").replace("~", "，");
        return clean;
    }

    // ===== TTS 文本变体：解决「句首语气词被拉长音 + 吞掉后文」=====
    // 根因：GPT-SoVITS 的 GPT 阶段对「单字语气词 + 强停顿(!)」容易生成过多语义码，
    //       而 SoVITS 解码时音频长度取语义码数（models.decode: y_lengths = codes.size(2)*2），
    //       于是「哇」被拉长、后面的字被压缩吞掉。文本前端把 ！映射成强停顿 "!"，～/…映射成长音 "…"。
    // 因此提供多种对策，可在「语音合成调试」页试听对比后选定（默认变体见 DEFAULT_VARIANT）。
    private static final String INTERJECTIONS = "哇啊诶唉咦哦噢呀哈嘿嗯唔哎哟喔嗷";
    private static final String PUNC_AFTER = "！!～~…—－、,，。.；;：:";

    public static final int VARIANT_COUNT = 6;
    public static final int DEFAULT_VARIANT = 2;   // 默认：句首语气词后的强停顿改成逗号

    public static String variantName(int v) {
        switch (v) {
            case 1: return "1 · 现状（基线）";
            case 2: return "2 · 语气词后强停顿改逗号（哇！→哇，）";
            case 3: return "3 · 去掉句首语气词";
            case 4: return "4 · 同 2 + 低随机参数(top_k=3,top_p=0.85,t=0.5)";
            case 5: return "5 · 同 1 + 低随机参数（隔离参数影响）";
            case 6: return "6 · 同 2 + 改用 ai_config 默认参考音频（隔离参考音频影响）";
            default: return "变体" + v;
        }
    }

    /** 句首语气词后的强停顿改成逗号：'哇！老师晚上好' → '哇，老师晚上好' */
    private static String softenLeadingInterjection(String s) {
        if (s == null || s.isEmpty()) return s;
        int n = 0;
        while (n < s.length() && n < 2 && INTERJECTIONS.indexOf(s.charAt(n)) >= 0) n++;
        if (n == 0 || n >= s.length()) return s;
        char p = s.charAt(n);
        if (PUNC_AFTER.indexOf(p) < 0) return s;   // 语气词后面不是标点（如"哇塞"）→ 不动
        return s.substring(0, n) + "，" + s.substring(n + 1).replaceFirst("^[！!～~…—－]+", "");
    }

    /** 去掉句首语气词及其标点：'哇！老师晚上好' → '老师晚上好' */
    private static String stripLeadingInterjection(String s) {
        if (s == null || s.isEmpty()) return s;
        int n = 0;
        while (n < s.length() && n < 2 && INTERJECTIONS.indexOf(s.charAt(n)) >= 0) n++;
        if (n == 0 || n >= s.length()) return s;
        if (PUNC_AFTER.indexOf(s.charAt(n)) < 0) return s;
        String rest = s.substring(n + 1).replaceFirst("^[！!～~…—－、,，]+", "").trim();
        return rest.isEmpty() ? s : rest;
    }

    /** 计算某变体实际送给 GPT-SoVITS 的文本 */
    public String previewText(String raw, int variant) {
        String clean = cleanText(raw);
        switch (variant) {
            case 2: case 4: case 6: return softenLeadingInterjection(clean);
            case 3: return stripLeadingInterjection(clean);
            default: return clean;
        }
    }

    /** 采样参数 {top_k, top_p, temperature}；变体 4/5 用低随机，其余保持现状 */
    private static double[] sampleParams(int variant) {
        if (variant == 4 || variant == 5) return new double[]{3, 0.85, 0.5};
        return new double[]{5, 1.0, 0.6};
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(s.hashCode());
        }
    }

    private File cacheFile(String key) {
        return new File(cacheDir, key + ".txt");
    }

    /**
     * 音色指纹：把「实际会用的参考音频 + 参考文本 + 模型 + 语速 + 采样步数」压成一个签名，参与缓存键。
     * 为什么需要：缓存原来只按文本 md5，换了参考音频/模型/语速后同一句话仍然命中旧音频 ——
     * 试听和「合成变体」里听起来没有任何变化，根本没法对比音质。
     * 参考音频还带上文件大小与修改时间，这样「同路径换了内容」也能识别出来。
     */
    private String voiceSig(String rawText, int variant) {
        try {
            AiConfig cfg = firstConfig();
            if (cfg == null) return "nocfg";
            String refWav = strip(cfg.getRefer_wav());
            String refText = strip(cfg.getPrompt_text() != null ? cfg.getPrompt_text() : "");
            if (variant != 6 && rawText != null) {
                String[] ref = resolveReference(rawText);   // 与 doSynthesize 同一套语境参考逻辑
                if (ref != null) { refWav = ref[0]; refText = ref[1]; }
            }
            StringBuilder sb = new StringBuilder();
            sb.append(refWav).append('|').append(refText).append('|')
              .append(cfg.getGpt_model_path()).append('|').append(cfg.getSovits_model_path()).append('|')
              .append(cfg.getTts_speed()).append('|').append(cfg.getSample_steps());
            try {
                File rf = new File(refWav == null ? "" : refWav);
                if (rf.isFile()) sb.append('|').append(rf.length()).append('@').append(rf.lastModified());
            } catch (Exception ignored) { }
            return md5(sb.toString());
        } catch (Exception e) {
            return "sigerr";
        }
    }

    /** 统一缓存键：变体 + 音色指纹 + 文本（任何一项变了都不会命中旧音频） */
    private String cacheKey(String rawText, String clean, int variant) {
        return md5("v" + variant + "|" + voiceSig(rawText, variant) + "|" + clean);
    }

    /**
     * 清空语音缓存（内存 + tts_cache 目录），返回清理的条数。
     * 用途：换参考音频/模型后想强制重新合成、或缓存目录太大时清理。
     */
    public int clearCache() {
        int n = memCache.size();
        memCache.clear();
        try {
            File[] fs = cacheDir.listFiles((d, name) -> name != null && name.endsWith(".txt"));
            if (fs != null) {
                for (File f : fs) { if (f.delete()) n++; }
            }
        } catch (Exception e) {
            System.out.println("[TTS] 清空缓存失败: " + e.getMessage());
        }
        System.out.println("[TTS] 语音缓存已清空，共清理 " + n + " 项");
        return n;
    }

    /** 当前缓存文件数 / 占用字节（设置页显示用） */
    public long[] cacheStat() {
        long count = 0, bytes = 0;
        try {
            File[] fs = cacheDir.listFiles((d, name) -> name != null && name.endsWith(".txt"));
            if (fs != null) for (File f : fs) { count++; bytes += f.length(); }
        } catch (Exception ignored) { }
        return new long[]{ count, bytes };
    }

    // 命中缓存直接返回，否则合成并写入缓存
    public String synthesize(String text) {
        return synthesize(text, DEFAULT_VARIANT);
    }

    /** 指定变体合成（变体参与缓存键，切换变体不会命中旧音频） */
    public String synthesize(String text, int variant) {
        String clean = previewText(text, variant);
        if (clean.trim().isEmpty()) return null;
        String key = cacheKey(text, clean, variant);

        String hit = memCache.get(key);
        if (hit != null) return hit;

        File f = cacheFile(key);
        if (f.exists()) {
            try {
                hit = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                if (hit != null && !hit.isEmpty()) {
                    memCache.put(key, hit);
                    return hit;
                }
            } catch (Exception ignored) {}
        }

        String base64 = doSynthesize(clean, text, variant);
        if (base64 != null && !base64.isEmpty()) {
            memCache.put(key, base64);
            try { Files.write(f.toPath(), base64.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
        }
        return base64;
    }

    // 生成 n 个候选（不写入缓存，每次随机合成）
    public List<String> synthesizeCandidates(String text, int n) {
        String clean = previewText(text, DEFAULT_VARIANT);
        List<String> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            String b = doSynthesize(clean, text, DEFAULT_VARIANT);
            if (b != null) list.add(b);
        }
        return list;
    }

    // 保存选中的候选为正式缓存（覆盖旧缓存）
    public void saveCandidate(String text, String base64) {
        String clean = previewText(text, DEFAULT_VARIANT);
        if (clean.trim().isEmpty() || base64 == null || base64.isEmpty()) return;
        String key = cacheKey(text, clean, DEFAULT_VARIANT);
        memCache.put(key, base64);
        try { Files.write(cacheFile(key).toPath(), base64.getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    // 是否已有缓存
    public boolean hasCache(String text) {
        String clean = previewText(text, DEFAULT_VARIANT);
        String key = cacheKey(text, clean, DEFAULT_VARIANT);
        return memCache.containsKey(key) || cacheFile(key).exists();
    }

    /** 试听用：返回该变体实际使用的参考音频信息（文件名 / 提示文本） */
    public String[] referenceInfo(String text, int variant) {
        AiConfig cfg = firstConfig();
        if (cfg == null) return new String[]{"", ""};
        String refWav = strip(cfg.getRefer_wav());
        String refText = strip(cfg.getPrompt_text() != null ? cfg.getPrompt_text() : "");
        if (variant != 6) {
            String[] ref = resolveReference(text);
            if (ref != null) { refWav = ref[0]; refText = ref[1]; }
        }
        return new String[]{ new File(refWav == null ? "" : refWav).getName(), refText == null ? "" : refText };
    }

    private AiConfig firstConfig() {
        List<AiConfig> configs = aiConfigMapper.findAll();
        return configs.isEmpty() ? null : configs.get(0);
    }

    // 实际调用 GPT-SoVITS 合成（originalText 用于按语境选择参考音频，如【】内心OS→气声参考）
    private String doSynthesize(String text, String originalText, int variant) {
        AiConfig cfg = firstConfig();
        if (cfg == null) return null;
        if (cfg.getTts_url() == null || cfg.getTts_url().isEmpty()) return null;

        try {
            String baseUrl = cfg.getTts_url();
            if (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);

            // 参考音频：按语境选择对应参考音频；无匹配则回退 ai_config 单段（变体 6 强制用 ai_config 默认参考）
            String refWav = strip(cfg.getRefer_wav());
            String refText = strip(cfg.getPrompt_text() != null ? cfg.getPrompt_text() : "");
            if (variant != 6) {
                String[] ref = resolveReference(originalText);
                if (ref != null) {
                    refWav = ref[0];
                    refText = ref[1];
                }
            }
            if (refWav == null || refWav.isEmpty()) return null;

            double[] sp = sampleParams(variant);

            StringBuilder sb = new StringBuilder(baseUrl).append("/?");
            sb.append("refer_wav_path=").append(enc(refWav));
            sb.append("&prompt_text=").append(enc(refText));
            sb.append("&prompt_language=").append(enc("中文"));
            sb.append("&text=").append(enc(text));
            sb.append("&text_language=").append(enc("中文"));
            sb.append("&speed=").append(cfg.getTts_speed() != null ? cfg.getTts_speed() : 1.0);
            sb.append("&sample_steps=").append(cfg.getSample_steps() != null ? cfg.getSample_steps() : 32);
            sb.append("&top_k=").append((int) sp[0]);
            sb.append("&top_p=").append(sp[1]);
            sb.append("&temperature=").append(sp[2]);
            sb.append("&if_sr=false");

            URI uri = new URI(sb.toString());

            HttpHeaders headers = new HttpHeaders();
            headers.set("accept", "application/json");
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> resp = restTemplate.exchange(uri, HttpMethod.GET, entity, byte[].class);

            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody() != null) {
                return Base64.getEncoder().encodeToString(resp.getBody());
            }
        } catch (Exception e) {
            System.out.println("[TTS] 请求失败: " + e.getMessage());
        }
        return null;
    }

    // 语境 → 参考音频文件（拼音名，位于 static/参考音频）
    private static final Map<String, String> TTS_CTX = new LinkedHashMap<>();
    static {
        TTS_CTX.put("抱怨", "baoyuan");
        TTS_CTX.put("感慨", "gankai");
        TTS_CTX.put("担心", "danxin");
        TTS_CTX.put("慌张", "huangzhang");
        TTS_CTX.put("工作", "gongzuo");
        TTS_CTX.put("鼓励", "guli");
        TTS_CTX.put("日常", "richang");
        TTS_CTX.put("心理活动", "xinlihuodong");
        TTS_CTX.put("询问", "xunwen");
    }

    // 按原文大致语境分类（粗糙规则版：心理活动【】与询问?是硬规则，其余靠关键词）
    private String classifyTtsContext(String text) {
        if (text == null || text.isEmpty()) return "日常";
        if (text.contains("【")) return "心理活动";   // 内心OS：气声参考
        if (text.contains("？") || text.contains("?")) return "询问";
        if (text.contains("唉") || text.contains("烦") || text.contains("讨厌") || text.contains("真是") || text.contains("辛苦")) return "抱怨";
        if (text.contains("担心") || text.contains("怕") || text.contains("小心") || text.contains("健康") || text.contains("万一")) return "担心";
        if (text.contains("怎么办") || text.contains("糟糕") || text.contains("不好") || text.contains("完了") || text.contains("那么多")) return "慌张";
        if (text.contains("工作") || text.contains("任务") || text.contains("文件") || text.contains("日程") || text.contains("处理") || text.contains("安排")) return "工作";
        if (text.contains("加油") || text.contains("可以的") || text.contains("相信自己") || text.contains("没问题") || text.contains("棒")) return "鼓励";
        if (text.contains("真") || text.contains("啊") || text.contains("呀") || text.contains("广袤") || text.contains("辽阔")) return "感慨";
        return "日常";
    }

    // 解析该语境的参考音频路径 + 提示文本；失败返回 null（回退 ai_config 单段）
    private String[] resolveReference(String originalText) {
        try {
            String ctx = classifyTtsContext(originalText);
            String pinyin = TTS_CTX.get(ctx);
            if (pinyin == null) return null;
            ClassPathResource wavRes = new ClassPathResource("static/参考音频/" + pinyin + ".wav");
            if (!wavRes.exists()) return null;
            String wavPath = wavRes.getFile().getAbsolutePath();
            String promptText = "";
            ClassPathResource txtRes = new ClassPathResource("static/参考音频/" + ctx + ".txt");
            if (txtRes.exists()) {
                try (java.io.InputStream is = txtRes.getInputStream()) {
                    promptText = new String(is.readAllBytes(), StandardCharsets.UTF_8).trim();
                }
            }
            return new String[]{ wavPath, promptText };
        } catch (Exception e) {
            System.out.println("[TTS] 参考音频解析失败: " + e.getMessage());
            return null;
        }
    }

    // 取多段参考音频：tts_reference 里有启用的参考时，单段直接用、多段用 ffmpeg 拼接成一段；否则返回 null（回退 ai_config 单段）
    private String[] buildReference(String defaultWav, String defaultText) {
        try {
            List<TtsReference> refs = ttsReferenceMapper.findEnabled();
            if (refs == null || refs.isEmpty()) return null;
            List<TtsReference> valid = new ArrayList<>();
            for (TtsReference r : refs) {
                if (r.getWav_path() != null && !r.getWav_path().isEmpty() && new File(r.getWav_path()).exists()) {
                    valid.add(r);
                }
            }
            if (valid.isEmpty()) return null;
            if (valid.size() == 1) {
                return new String[]{ valid.get(0).getWav_path(), valid.get(0).getPrompt_text() };
            }
            // 多段：ffmpeg 拼接音频 + 拼接文本
            File dir = new File(System.getProperty("user.dir"), "tts_reference");
            dir.mkdirs();
            File out = new File(dir, "combined_" + System.currentTimeMillis() + ".wav");
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-y");
            for (TtsReference r : valid) {
                cmd.add("-i");
                cmd.add(r.getWav_path());
            }
            StringBuilder fc = new StringBuilder();
            for (int i = 0; i < valid.size(); i++) fc.append("[").append(i).append(":a]");
            fc.append("concat=n=").append(valid.size()).append(":v=0:a=1[out]");
            cmd.add("-filter_complex");
            cmd.add(fc.toString());
            cmd.add("-map");
            cmd.add("[out]");
            cmd.add("-ar");
            cmd.add("32000");
            cmd.add("-ac");
            cmd.add("1");
            cmd.add(out.getAbsolutePath());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
            if (!out.exists() || out.length() == 0) return null;
            StringBuilder tb = new StringBuilder();
            for (TtsReference r : valid) {
                String t = r.getPrompt_text() != null ? r.getPrompt_text().trim() : "";
                tb.append(t);
                if (!t.isEmpty() && !t.endsWith("。") && !t.endsWith("！") && !t.endsWith("？") && !t.endsWith(".")) tb.append("。");
            }
            return new String[]{ out.getAbsolutePath(), tb.toString() };
        } catch (Exception e) {
            System.out.println("[TTS] 参考音频拼接失败: " + e.getMessage());
            return null;
        }
    }

    // 把某条文本的缓存语音（base64 WAV）落盘并作为参考音频存入 tts_reference；返回 null=成功，否则返回错误信息
    public String addReference(String text) {
        String clean = cleanText(text);
        if (clean.trim().isEmpty()) return "文本为空";
        String base64 = synthesize(clean);
        if (base64 == null || base64.isEmpty()) return "未找到该文本的合成语音，请先在语音库中生成/试听一次";
        try {
            File dir = new File(System.getProperty("user.dir"), "tts_reference");
            dir.mkdirs();
            File f = new File(dir, "ref_" + md5(clean) + ".wav");
            Files.write(f.toPath(), Base64.getDecoder().decode(base64));
            for (TtsReference r : ttsReferenceMapper.findAll()) {
                if (clean.equals(r.getPrompt_text())) return "该文本已是参考音频，无需重复添加";
            }
            TtsReference ref = new TtsReference();
            ref.setWav_path(f.getAbsolutePath());
            ref.setPrompt_text(clean);
            ttsReferenceMapper.insert(ref);
            return null;
        } catch (Exception e) {
            System.out.println("[TTS] 添加参考音频失败: " + e.getMessage());
            return "保存参考音频失败: " + e.getMessage();
        }
    }

    private String enc(String val) {
        return URLEncoder.encode(val, StandardCharsets.UTF_8);
    }

    private String strip(String val) {
        if (val == null) return "";
        return val.replace("\"", "").replace("'", "").trim();
    }
}
