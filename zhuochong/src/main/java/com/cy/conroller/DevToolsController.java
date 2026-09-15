package com.cy.conroller;

import com.cy.mapper.AiConfigMapper;
import com.cy.mapper.CommandLibMapper;
import com.cy.mapper.Live2dExpressionMapMapper;
import com.cy.pojo.AiConfig;
import com.cy.pojo.CommandLib;
import com.cy.pojo.Live2dExpressionMap;
import com.cy.pojo.Result;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.awt.Desktop;
import java.io.File;
import java.util.*;

/**
 * 开发工具接口（桌宠设置页使用）：
 *  - 指令库 CRUD（含重复校验、首次自动建表并从 command_lib_seed.json 填充）
 *  - Live2D 模型列表 / 表情文件列表 / 表情映射表 CRUD
 *  - 打开各类资源文件夹（语音模型、参考音频、Live2D 模型与表情）
 */
@RestController
@RequestMapping("/ai/dev")
public class DevToolsController {

    @Autowired
    private CommandLibMapper commandLibMapper;

    @Autowired
    private Live2dExpressionMapMapper exprMapMapper;

    @Autowired
    private AiConfigMapper aiConfigMapper;

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * 静态资源根目录：优先 target/classes/static（浏览器实际访问的位置），
     * 不存在时回退 src/main/resources/static（打包/首次未编译场景）。
     */
    private static File staticDir() {
        File target = new File(System.getProperty("user.dir"), "target/classes/static");
        if (target.isDirectory()) return target;
        return new File(System.getProperty("user.dir"), "src/main/resources/static");
    }

    /** 启动即建表；指令库为空时从种子文件填充（首次"浏览项目"结果） */
    @PostConstruct
    public void init() {
        try {
            commandLibMapper.createTableIfAbsent();
            exprMapMapper.createTableIfAbsent();
            if (commandLibMapper.countAll() == 0) {
                seedCommandLib();
            }
            if (exprMapMapper.countAll() == 0) {
                seedExpressionMap();
            }
        } catch (Exception e) {
            System.out.println("[开发工具] 初始化失败: " + e.getMessage());
        }
    }

    private void seedCommandLib() {
        try {
            File f = new File(staticDir(), "command_lib_seed.json");
            if (!f.exists()) { System.out.println("[开发工具] 缺少 command_lib_seed.json"); return; }
            List<Map<String, String>> list = JSON.readValue(f, List.class);
            int n = 0;
            for (Map<String, String> m : list) {
                CommandLib c = new CommandLib();
                c.setModule(m.getOrDefault("module", "base"));
                c.setCommand(m.getOrDefault("command", ""));
                c.setFeature(m.getOrDefault("feature", ""));
                if (!c.getCommand().isEmpty()) { commandLibMapper.insert(c); n++; }
            }
            System.out.println("[开发工具] 指令库已初始化，共 " + n + " 条");
        } catch (Exception e) {
            System.out.println("[开发工具] 指令库种子填充失败: " + e.getMessage());
        }
    }

    /** 表情映射种子：与 bridge 内置 exprMap 一致，供设置页直接编辑 */
    private void seedExpressionMap() {
        String[][] seed = {
                {"aluona_kaixin.png", "正常微笑"},
                {"aluona_feichangxihuan.png", "脸红,眯眼舒适"},
                {"aluona_xihuan.png", "眯眼舒适"},
                {"aluona_qidai.png", "星星眼"},
                {"aluona_jidong.png", "激动闭眼"},
                {"aluona_haixiu.png", "脸红"},
                {"aluona_shengqi.png", "生气,撅嘴"},
                {"aluona_xiufen.png", "生气"},
                {"aluona_kangju.png", "脸红,激动闭眼"},
                {"aluona_youyuan.png", "阴脸"},
                {"aluona_huaiyi.png", "撅嘴"},
                {"aluona_kunhuo.png", "晕眩"},
                {"aluona_zhengjing.png", "晕眩"},
                {"aluona_zhengchang.png", "正常微笑"},
                {"aluona_swkl.png", "阴脸"},
                {"指纹锁", "指纹锁"},
                {"流口水", "流口水"}
        };
        try {
            for (String[] s : seed) {
                Live2dExpressionMap m = new Live2dExpressionMap();
                m.setImage_name(s[0]);
                m.setExpression_files(s[1]);
                exprMapMapper.insert(m);
            }
            System.out.println("[开发工具] 表情映射已初始化，共 " + seed.length + " 条");
        } catch (Exception e) {
            System.out.println("[开发工具] 表情映射种子填充失败: " + e.getMessage());
        }
    }

    // ===== 指令库 =====

    @GetMapping("/command-lib")
    public Result listCommandLib(@RequestParam(required = false) String module) {
        return Result.success(module == null || module.isEmpty()
                ? commandLibMapper.findAll() : commandLibMapper.findByModule(module));
    }

    @PostMapping("/command-lib")
    public Result addCommandLib(@RequestBody CommandLib body) {
        String conflict = checkConflict(body.getCommand(), null);
        if (conflict != null) return Result.error("指令已存在：" + conflict + "（请修改后再保存）");
        if (body.getCommand() == null || body.getCommand().trim().isEmpty()) return Result.error("指令不能为空");
        body.setEnabled(1);
        commandLibMapper.insert(body);
        return Result.success(body);
    }

    @PutMapping("/command-lib")
    public Result updateCommandLib(@RequestBody CommandLib body) {
        if (body.getId() == null) return Result.error("缺少 id");
        if (body.getCommand() == null || body.getCommand().trim().isEmpty()) return Result.error("指令不能为空");
        String conflict = checkConflict(body.getCommand(), body.getId());
        if (conflict != null) return Result.error("指令已存在：" + conflict + "（请修改后再保存）");
        if (body.getEnabled() == null) body.setEnabled(1);
        commandLibMapper.update(body);
        return Result.success(body);
    }

    @DeleteMapping("/command-lib/{id}")
    public Result deleteCommandLib(@PathVariable Integer id) {
        commandLibMapper.delete(id);
        return Result.ok("已删除");
    }

    /** 指令词重复校验：按 | 拆分逐个比对（id 非空时排除自身），返回首个冲突词或 null */
    private String checkConflict(String command, Integer selfId) {
        if (command == null || command.trim().isEmpty()) return null;
        Set<String> mine = splitCommands(command);
        for (CommandLib other : commandLibMapper.findAll()) {
            if (selfId != null && selfId.equals(other.getId())) continue;
            Set<String> theirs = splitCommands(other.getCommand());
            for (String w : mine) {
                if (theirs.contains(w)) return w + "（已被模块 " + other.getModule() + " 使用）";
            }
        }
        return null;
    }

    private Set<String> splitCommands(String raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split("[|｜]")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    // ===== Live2D 模型 / 表情 =====

    /** 顶层主模型列表（static/live2d/*.model3.json，不递归，排除 SDK 示例） */
    @GetMapping("/live2d/models")
    public Result listLive2dModels() {
        List<Map<String, Object>> out = new ArrayList<>();
        File dir = new File(staticDir(), "live2d");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".model3.json"));
        if (files != null) {
            for (File f : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("file", f.getName());
                m.put("sizeMB", Math.round(f.length() / 1024.0 / 1024.0 * 10) / 10.0);
                out.add(m);
            }
        }
        return Result.success(out);
    }

    /** 表情文件列表（static/live2d/expressions/*.exp3.json，返回不含扩展名的名字） */
    @GetMapping("/live2d/expressions")
    public Result listLive2dExpressions() {
        List<String> out = new ArrayList<>();
        File dir = new File(staticDir(), "live2d/expressions");
        File[] files = dir.listFiles((d, n) -> n.endsWith(".exp3.json"));
        if (files != null) {
            for (File f : files) out.add(f.getName().replace(".exp3.json", ""));
            Collections.sort(out);
        }
        return Result.success(out);
    }

    @GetMapping("/live2d/expression-map")
    public Result listExpressionMap() {
        return Result.success(exprMapMapper.findAll());
    }

    @PostMapping("/live2d/expression-map")
    public Result addExpressionMap(@RequestBody Live2dExpressionMap body) {
        if (isBlank(body.getImage_name()) || isBlank(body.getExpression_files())) return Result.error("图片名与表情文件不能为空");
        try {
            exprMapMapper.insert(body);
            return Result.success(body);
        } catch (Exception e) {
            return Result.error("保存失败（图片名可能已存在）: " + e.getMessage());
        }
    }

    @PutMapping("/live2d/expression-map")
    public Result updateExpressionMap(@RequestBody Live2dExpressionMap body) {
        if (body.getId() == null) return Result.error("缺少 id");
        if (isBlank(body.getImage_name()) || isBlank(body.getExpression_files())) return Result.error("图片名与表情文件不能为空");
        exprMapMapper.update(body);
        return Result.success(body);
    }

    @DeleteMapping("/live2d/expression-map/{id}")
    public Result deleteExpressionMap(@PathVariable Integer id) {
        exprMapMapper.delete(id);
        return Result.ok("已删除");
    }

    // ===== 参考音频（TTS）=====

    /** 参考音频所在目录：配置里 refer_wav 的父目录（未配置则返回 null） */
    private File referDir() {
        List<AiConfig> cfgs = aiConfigMapper.findAll();
        if (cfgs == null || cfgs.isEmpty()) return null;
        String rw = cfgs.get(0).getRefer_wav();
        if (isBlank(rw)) return null;
        File wav = new File(rw.trim());
        File dir = wav.getParentFile();
        return (dir != null && dir.isDirectory()) ? dir : null;
    }

    /** 读同名 .lab / .txt 作为参考文本（GPT-SoVITS 的常见约定），没有就返回空串 */
    private String labTextOf(File wav) {
        String base = wav.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String[] exts = { ".lab", ".txt", ".list" };
        for (String ext : exts) {
            File f = new File(wav.getParentFile(), base + ext);
            if (f.isFile()) {
                try {
                    String s = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                            java.nio.charset.StandardCharsets.UTF_8).trim();
                    if (!s.isEmpty()) return s.replaceAll("\\s+", " ");
                } catch (Exception e) { /* 忽略，继续试下一个后缀 */ }
            }
        }
        return "";
    }

    /** 参考音频清单：{dir, current, promptText, items:[{name,path,sizeKB,promptText}]} */
    @GetMapping("/refer-audios")
    public Result listReferAudios() {
        List<AiConfig> cfgs = aiConfigMapper.findAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configCount", cfgs == null ? 0 : cfgs.size());
        String current = (cfgs != null && !cfgs.isEmpty()) ? cfgs.get(0).getRefer_wav() : null;
        out.put("current", current == null ? "" : current);
        String promptText = (cfgs != null && !cfgs.isEmpty() && cfgs.get(0).getPrompt_text() != null)
                ? cfgs.get(0).getPrompt_text() : "";
        out.put("promptText", promptText);
        File dir = referDir();
        if (dir == null) {
            out.put("dir", "");
            out.put("items", new ArrayList<>());
            out.put("message", isBlank(current)
                    ? "还没配置参考音频路径（先在「AI 设定」页填 refer_wav，或把 wav 放好后再来刷新）"
                    : "参考音频所在目录不存在：" + (current == null ? "" : current));
            return Result.success(out);
        }
        out.put("dir", dir.getAbsolutePath());
        List<Map<String, Object>> items = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> {
            String l = n.toLowerCase();
            return l.endsWith(".wav") || l.endsWith(".mp3") || l.endsWith(".flac") || l.endsWith(".m4a");
        });
        if (files != null) {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", f.getName());
                m.put("path", f.getAbsolutePath());
                m.put("sizeKB", Math.round(f.length() / 1024.0 * 10) / 10.0);
                m.put("promptText", labTextOf(f));
                items.add(m);
            }
        }
        out.put("items", items);
        return Result.success(out);
    }

    /**
     * 选定参考音频：把 ai_config.refer_wav 换成该文件；withText=true 时顺带把
     * 同名 .lab/.txt 的文本写进 prompt_text（省得手抄）。
     * 注意整份回写，保留直播/弹幕等其它字段。
     */
    @PostMapping("/refer-audio/select")
    public Result selectReferAudio(@RequestBody Map<String, Object> req) {
        String name = req.get("name") == null ? "" : String.valueOf(req.get("name"));
        boolean withText = !"false".equals(String.valueOf(req.get("withText")));
        File dir = referDir();
        if (dir == null) return Result.error("参考音频目录不存在（先在「AI 设定」页配置 refer_wav 路径）");
        File f = new File(dir, name);
        // 防目录穿越：只允许 refer 目录下的文件
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath())) return Result.error("非法的文件名");
        } catch (Exception e) { return Result.error("路径解析失败: " + e.getMessage()); }
        if (!f.isFile()) return Result.error("文件不存在：" + f.getAbsolutePath());

        List<AiConfig> cfgs = aiConfigMapper.findAll();
        if (cfgs == null || cfgs.isEmpty()) return Result.error("ai_config 里没有配置记录");
        AiConfig c = cfgs.get(0);
        c.setRefer_wav(f.getAbsolutePath());
        String lab = labTextOf(f);
        if (withText) {
            if (!lab.isEmpty()) {
                c.setPrompt_text(lab);          // 有同名 .lab/.txt → 直接采用
            } else {
                // 没有同名文本 → 清空。宁可空着让老师填，也别留着上一段音频的文本：
                // 文本与音频不一致正是「拉长音/吞字」的成因。
                c.setPrompt_text("");
            }
        }
        aiConfigMapper.update(c);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("refer_wav", c.getRefer_wav());
        out.put("prompt_text", c.getPrompt_text());
        out.put("labFound", !lab.isEmpty());
        out.put("message", "已切换参考音频：" + f.getName()
                + (withText && !lab.isEmpty() ? "（参考文本已按同名 .lab/.txt 更新）"
                   : "（未找到同名 .lab/.txt，参考文本已清空，请在「参考文本」里填写这段音频的内容）"));
        return Result.success(out);
    }

    /** 试听：把 refer 目录里的音频文件直接流给页面（<audio> 播放） */
    @GetMapping("/refer-audio/audio")
    public org.springframework.http.ResponseEntity<byte[]> referAudio(@RequestParam("name") String name) {
        File dir = referDir();
        if (dir == null) return org.springframework.http.ResponseEntity.notFound().build();
        File f = new File(dir, name);
        try {
            if (!f.getCanonicalPath().startsWith(dir.getCanonicalPath()) || !f.isFile()) {
                return org.springframework.http.ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
            String lower = name.toLowerCase();
            String type = lower.endsWith(".mp3") ? "audio/mpeg"
                    : lower.endsWith(".flac") ? "audio/flac"
                    : lower.endsWith(".m4a") ? "audio/mp4" : "audio/wav";
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", type)
                    .header("Cache-Control", "no-store")
                    .body(bytes);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
    }


    // ===== 打开文件夹 =====

    /** type: gpt(语音GPT模型) / sovits(SoVITS模型) / refer(参考音频) / live2d(模型目录) / expressions(表情目录) */
    @PostMapping("/open-folder")
    public Result openFolder(@RequestBody Map<String, String> req) {
        String type = req.getOrDefault("type", "");
        File dir = null;
        switch (type) {
            case "gpt": dir = new File(staticDir(), "GPT_weights_v2"); break;
            case "sovits": dir = new File(staticDir(), "SoVITS_weights_v2"); break;
            case "live2d": dir = new File(staticDir(), "live2d"); break;
            case "expressions": dir = new File(staticDir(), "live2d/expressions"); break;
            case "refer": {
                List<AiConfig> cfgs = aiConfigMapper.findAll();
                if (cfgs != null && !cfgs.isEmpty() && cfgs.get(0).getRefer_wav() != null) {
                    File wav = new File(cfgs.get(0).getRefer_wav());
                    dir = wav.getParentFile();
                }
                break;
            }
            default: return Result.error("未知的文件夹类型: " + type);
        }
        if (dir == null || !dir.exists()) {
            return Result.error("目录不存在：" + (dir == null ? "（未配置参考音频路径）" : dir.getAbsolutePath()));
        }
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir);
            else return Result.error("当前环境不支持打开文件夹");
            return Result.ok("已打开：" + dir.getAbsolutePath());
        } catch (Exception e) {
            return Result.error("打开失败: " + e.getMessage() + "（路径：" + dir.getAbsolutePath() + "）");
        }
    }

    private boolean isBlank(String s) { return s == null || s.trim().isEmpty(); }
}
