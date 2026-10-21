package com.cy.qq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图片留底 + 按需重读（落盘版）。
 *
 * <p>解决两个问题：
 * <ol>
 *   <li>视觉模型一次只给一句 90 字描述，细节丢光；之后被问"图里她衣服什么颜色"就答不出来。
 *       → 收到图时把缩放后的图**存到磁盘**并编号（{@code #12}），历史里只写 {@code 〔图#12：一句话描述〕}；
 *       有人追问时拿**当前问题当提示词**重读原图。</li>
 *   <li>图存内存会随图片数量爆内存。→ 图落盘，内存里只留一份轻量索引（几百字节/张）。</li>
 * </ol>
 *
 * <p>容量限制（按你的要求）：
 * <ul>
 *   <li>单个群：{@code qq.image-max-per-group}（默认 500 张）</li>
 *   <li>管理员私聊（每人）：{@code qq.image-max-per-user}（默认 500 张）</li>
 *   <li>全局兜底：{@code qq.image-max-total}（默认 3000 张）——防止群很多时磁盘被吃光</li>
 *   <li>超出时**从最早的开始删**（同时删文件与索引）</li>
 *   <li>另有 {@code qq.image-ttl-hours}（默认 24 小时）过期清理</li>
 * </ul>
 *
 * <p>磁盘布局：
 * <pre>
 * &lt;baseDir&gt;/g{群号}/index.json      索引（id/文件名/mime/首描/时间）
 * &lt;baseDir&gt;/g{群号}/12.jpg           图片本体（800px JPEG ≈ 20KB）
 * &lt;baseDir&gt;/p{QQ号}/...
 * </pre>
 * 服务重启后从 index.json 自动重建索引，编号继续可用。
 */
@Component
public class QqImageStore {

    private static final ObjectMapper M = new ObjectMapper();

    @Autowired
    private QqConfig config;

    /** 索引里的一条（不含图片本体，避免占用堆内存） */
    public static final class Meta {
        public int id;
        public String file;      // 文件名（相对会话目录）
        public String mime;
        public String desc;      // 首次描述（可为空）
        public long ts;

        public Meta() {
        }

        Meta(int id, String file, String mime, String desc, long ts) {
            this.id = id;
            this.file = file;
            this.mime = mime;
            this.desc = desc;
            this.ts = ts;
        }
    }

    /** sessionKey → 索引（新→旧按 ts 排序由插入顺序保证） */
    private final Map<String, List<Meta>> index = new ConcurrentHashMap<>();
    private final Object lock = new Object();
    private int nextId = 1;

    private Path baseDir;

    @PostConstruct
    public void init() {
        baseDir = Paths.get(config.getImageStoreDir()).toAbsolutePath();
        try {
            Files.createDirectories(baseDir);
            System.out.println("[QQ] 图片留底目录: " + baseDir
                    + "（单群上限 " + config.getImageMaxPerGroup()
                    + " 张，私聊上限 " + config.getImageMaxPerUser() + " 张）");
        } catch (Exception e) {
            System.out.println("[QQ] 图片留底目录创建失败（将退化为不可留底）: " + e.getMessage());
            return;
        }
        loadAll();
    }

    // ===== 对外 =====

    /**
     * 落盘并登记一张图。
     *
     * @return 编号（写进历史用，比如 #12）；失败返回 -1
     */
    public int save(String sessionKey, QqImageService.Prepared p, String desc) {
        if (sessionKey == null || p == null || baseDir == null) return -1;
        synchronized (lock) {
            int id = nextId++;
            String ext = extOf(p.mime);
            String fileName = id + ext;
            try {
                Path dir = baseDir.resolve(sessionKey);
                Files.createDirectories(dir);
                Files.write(dir.resolve(fileName), Base64.getDecoder().decode(p.base64));

                Meta m = new Meta(id, fileName, p.mime, desc == null ? "" : desc, System.currentTimeMillis());
                List<Meta> list = index.computeIfAbsent(sessionKey, k -> new ArrayList<>());
                list.add(m);
                enforceLimits(sessionKey, list);
                persistIndex(sessionKey, list);
                return id;
            } catch (Exception e) {
                System.out.println("[QQ] 图片落盘失败: " + e.getMessage());
                return -1;
            }
        }
    }

    /** 同会话内按编号找索引 */
    public Meta meta(String sessionKey, int id) {
        List<Meta> list = index.get(sessionKey);
        if (list == null) return null;
        synchronized (lock) {
            for (Meta m : list) {
                if (m.id == id && !expired(m)) return m;
            }
        }
        return null;
    }

    /** 某会话最近的 N 张（新→旧） */
    public List<Meta> recent(String sessionKey, int n) {
        List<Meta> out = new ArrayList<>();
        List<Meta> list = index.get(sessionKey);
        if (list == null) return out;
        synchronized (lock) {
            for (int i = list.size() - 1; i >= 0 && out.size() < n; i--) {
                Meta m = list.get(i);
                if (!expired(m)) out.add(m);
            }
        }
        return out;
    }

    /** 把索引里的图读成可送模型的 base64；文件没了返回 null */
    public QqImageService.Prepared load(String sessionKey, Meta m) {
        if (m == null || baseDir == null) return null;
        try {
            Path f = baseDir.resolve(sessionKey).resolve(m.file);
            if (!Files.exists(f)) return null;
            byte[] raw = Files.readAllBytes(f);
            return QqImageService.Prepared.fromBase64(Base64.getEncoder().encodeToString(raw),
                    m.mime == null || m.mime.isEmpty() ? "image/jpeg" : m.mime);
        } catch (Exception e) {
            System.out.println("[QQ] 读取留底图片失败: " + e.getMessage());
            return null;
        }
    }

    public int count(String sessionKey) {
        List<Meta> list = index.get(sessionKey);
        return list == null ? 0 : list.size();
    }

    public int totalCount() {
        int n = 0;
        for (List<Meta> l : index.values()) n += l.size();
        return n;
    }

    public long totalBytes() {
        if (baseDir == null) return 0;
        long sum = 0;
        File[] dirs = baseDir.toFile().listFiles();
        if (dirs == null) return 0;
        for (File d : dirs) {
            File[] fs = d.listFiles();
            if (fs == null) continue;
            for (File f : fs) if (f.isFile() && !f.getName().endsWith(".json")) sum += f.length();
        }
        return sum;
    }

    // ===== 内部 =====

    private boolean expired(Meta m) {
        long ttl = Math.max(1, config.getImageTtlHours()) * 3600_000L;
        return System.currentTimeMillis() - m.ts > ttl;
    }

    /** 超限/过期清理：从最早的开始删（文件 + 索引） */
    private void enforceLimits(String sessionKey, List<Meta> list) {
        int limit = sessionKey.startsWith("p") ? config.getImageMaxPerUser() : config.getImageMaxPerGroup();

        // 1) 过期 + 单会话超限
        list.sort(Comparator.comparingLong(a -> a.ts));
        int i = 0;
        while (i < list.size()) {
            Meta m = list.get(i);
            boolean tooMany = list.size() - i > limit;
            if (expired(m) || tooMany) {
                deleteFile(sessionKey, m);
                list.remove(i);
                continue;
            }
            i++;
        }

        // 2) 全局兜底（文件总数为上限时，删全局最早的）
        int total = totalCount();
        int maxTotal = Math.max(limit, config.getImageMaxTotal());
        while (total > maxTotal) {
            String oldestKey = null;
            Meta oldest = null;
            for (Map.Entry<String, List<Meta>> e : index.entrySet()) {
                List<Meta> l = e.getValue();
                if (l.isEmpty()) continue;
                Meta first = l.get(0);
                if (oldest == null || first.ts < oldest.ts) {
                    oldest = first;
                    oldestKey = e.getKey();
                }
            }
            if (oldest == null || oldestKey == null) break;
            deleteFile(oldestKey, oldest);
            index.get(oldestKey).remove(oldest);
            total--;
        }
    }

    private void deleteFile(String sessionKey, Meta m) {
        try {
            Files.deleteIfExists(baseDir.resolve(sessionKey).resolve(m.file));
        } catch (Exception ignored) {
        }
    }

    private void persistIndex(String sessionKey, List<Meta> list) {
        try {
            Path dir = baseDir.resolve(sessionKey);
            Path tmp = dir.resolve("index.json.tmp");
            Files.write(tmp, M.writeValueAsBytes(list));
            Files.move(tmp, dir.resolve("index.json"), StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) {
            System.out.println("[QQ] 图片索引写入失败: " + e.getMessage());
        }
    }

    /** 启动时从磁盘恢复索引（重启后编号仍可重读） */
    private void loadAll() {
        File[] dirs = baseDir.toFile().listFiles(File::isDirectory);
        if (dirs == null) return;
        int sessions = 0, metas = 0;
        int maxId = 0;
        for (File d : dirs) {
            String key = d.getName();
            File idx = new File(d, "index.json");
            if (!idx.exists()) continue;
            try {
                List<Meta> list = M.readValue(idx, new TypeReference<List<Meta>>() {
                });
                List<Meta> kept = new ArrayList<>();
                for (Meta m : list) {
                    if (m == null || m.file == null) continue;
                    if (!new File(d, m.file).exists()) continue;   // 文件没了就丢掉
                    if (expired(m)) {
                        deleteFile(key, m);
                        continue;
                    }
                    kept.add(m);
                    if (m.id > maxId) maxId = m.id;
                }
                if (!kept.isEmpty()) {
                    kept.sort(Comparator.comparingLong(a -> a.ts));
                    index.put(key, kept);
                    sessions++;
                    metas += kept.size();
                }
                persistIndex(key, kept);
            } catch (Exception e) {
                System.out.println("[QQ] 恢复图片索引失败 " + key + ": " + e.getMessage());
            }
        }
        nextId = maxId + 1;
        if (metas > 0) System.out.println("[QQ] 已从磁盘恢复图片留底： " + sessions + " 个会话 / " + metas + " 张");
    }

    private static String extOf(String mime) {
        if (mime == null) return ".jpg";
        if (mime.contains("png")) return ".png";
        if (mime.contains("webp")) return ".webp";
        if (mime.contains("gif")) return ".gif";
        return ".jpg";
    }

    /** 会话键：群用 g{群号}，私聊用 p{QQ号} */
    public static String sessionKey(boolean privateChat, long targetId) {
        return (privateChat ? "p" : "g") + targetId;
    }
}
