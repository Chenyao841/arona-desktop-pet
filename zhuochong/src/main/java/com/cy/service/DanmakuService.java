package com.cy.service;

import com.cy.mapper.AiConfigMapper;
import com.cy.pojo.AiConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.brotli.dec.BrotliInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.zip.Inflater;

@Service
public class DanmakuService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final RestTemplate rest;
    private final List<DanmakuListener> listeners = new CopyOnWriteArrayList<>();
    private final List<EnterListener> enterListeners = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService scheduler;
    private ScheduledExecutorService wsHeartbeat;
    private WebSocket ws;
    private int realRoomId;
    private volatile boolean wsStarted = false;

    @Value("${bili.room-id:32359735}")
    private int configRoomId;

    @Autowired
    private AiConfigMapper aiConfigMapper;

    // 优先从数据库 ai_config 读房间号，读不到用 application.yml 默认值
    private int getRoomId() {
        try {
            List<AiConfig> configs = aiConfigMapper.findAll();
            if (configs != null && !configs.isEmpty() && configs.get(0).getRoom_id() != null) {
                return configs.get(0).getRoom_id();
            }
        } catch (Exception ignored) {}
        return configRoomId;
    }

    // 弹幕抓取开关：关闭时不启动轮询/WebSocket，避免日志挤压
    private boolean isDanmakuEnabled() {
        try {
            List<AiConfig> configs = aiConfigMapper.findAll();
            if (configs != null && !configs.isEmpty() && configs.get(0).getDanmaku_enabled() != null) {
                return configs.get(0).getDanmaku_enabled() == 1;
            }
        } catch (Exception ignored) {}
        return true;
    }

    public DanmakuService() {
        rest = new RestTemplate();
        rest.getInterceptors().add((req, body, exec) -> {
            req.getHeaders().set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            req.getHeaders().set("Referer", "https://live.bilibili.com/");
            return exec.execute(req, body);
        });
    }

    public interface DanmakuListener {
        void onMessage(String username, String message, boolean hasMedal);
        String id();
    }

    public interface EnterListener {
        void onEnter(String username, int uid);
    }

    public void addListener(DanmakuListener l) {
        listeners.add(l);
        startIfNeeded();
    }

    public void removeListener(DanmakuListener l) {
        listeners.remove(l);
    }

    public void addEnterListener(EnterListener l) {
        enterListeners.add(l);
        startWebSocketIfNeeded();
    }

    public void removeEnterListener(EnterListener l) {
        enterListeners.remove(l);
    }

    private volatile boolean started = false;
    private synchronized void startIfNeeded() {
        if (started) return;
        if (!isDanmakuEnabled()) {
            System.out.println("[弹幕] 弹幕抓取已关闭（基础设置中可开启）");
            return;
        }
        started = true;
        new Thread(() -> start(getRoomId())).start();
    }

    private void start(int roomId) {
        try {
            String infoUrl = "https://api.live.bilibili.com/room/v1/Room/get_info?room_id=" + roomId;
            String infoJson = rest.getForObject(infoUrl, String.class);
            JsonNode info = MAPPER.readTree(infoJson);
            JsonNode data = info.get("data");
            if (data == null || data.get("room_id") == null) {
                System.out.println("[弹幕] 房间不存在或未开播");
                started = false;
                return;
            }
            realRoomId = data.get("room_id").asInt();
            System.out.println("[弹幕] 房间号: " + realRoomId + "，启动HTTP轮询");
            startPolling();
        } catch (Exception e) {
            System.out.println("[弹幕] 获取房间信息失败: " + e.getMessage());
            started = false;
        }
    }

    private int pollCount = 0;
    private boolean seeded = false;
    private String lastTimeline = "";
    private void startPolling() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        System.out.println("[弹幕] HTTP轮询已启动，每2秒查询一次");
        scheduler.scheduleAtFixedRate(this::pollDanmaku, 2, 2, TimeUnit.SECONDS);
    }

    private void pollDanmaku() {
        try {
            pollCount++;
            if (pollCount % 20 == 0) System.out.println("[弹幕] 轮询心跳 #" + pollCount + " 监听数:" + listeners.size());
            String url = "https://api.live.bilibili.com/xlive/web-room/v1/dM/gethistory?roomid=" + realRoomId;
            String json = rest.getForObject(url, String.class);
            JsonNode root = MAPPER.readTree(json);
            if (root.get("code").asInt() != 0) {
                System.out.println("[弹幕] API错误 #" + pollCount + " code=" + root.get("code").asInt());
                return;
            }
            JsonNode data = root.get("data");
            if (data == null) return;
            JsonNode msgs = data.has("room") ? data.get("room") : data.has("admin") ? data.get("admin") : null;
            if (msgs == null || !msgs.isArray()) {
                if (pollCount % 10 == 1) System.out.println("[弹幕] 无消息 #" + pollCount);
                return;
            }
            if (pollCount == 1) System.out.println("[弹幕] 首轮拉取到 " + msgs.size() + " 条弹幕历史");
            if (!seeded && msgs.size() > 0) {
                JsonNode first = msgs.get(msgs.size() - 1);
                lastTimeline = first.has("timeline") ? first.get("timeline").asText() : "";
                seeded = true;
                System.out.println("[弹幕] 种子时间: " + lastTimeline);
            }
            int caught = 0;
            for (int i = msgs.size() - 1; i >= 0; i--) {
                JsonNode msg = msgs.get(i);
                String timeline = msg.has("timeline") ? msg.get("timeline").asText() : "";
                if (!lastTimeline.isEmpty() && timeline.compareTo(lastTimeline) <= 0) continue;
                String text = msg.get("text").asText();
                if (text == null || !text.startsWith("/")) continue;
                text = text.substring(1);
                lastTimeline = timeline;
                String uname = msg.has("nickname") ? msg.get("nickname").asText() : "观众";
                caught++;
                System.out.println("[弹幕] 捕获: " + uname + " -> " + text);
                boolean hasMedal = msg.has("medal") && msg.get("medal").size() > 0;
                for (DanmakuListener l : listeners) {
                    try { l.onMessage(uname, text, hasMedal); } catch (Exception ignored) {}
                }
            }
            if (pollCount % 30 == 1) System.out.println("[弹幕] #" + pollCount + " 捕获:" + caught + " cursor:" + lastTimeline.substring(0, Math.min(16, lastTimeline.length())));
        } catch (Exception e) {
            System.out.println("[弹幕] 轮询异常: " + e.getMessage());
        }
    }

    // ===== 进入直播间 WebSocket =====
    private synchronized void startWebSocketIfNeeded() {
        if (wsStarted) return;
        if (!isDanmakuEnabled()) {
            System.out.println("[弹幕] 弹幕抓取已关闭（基础设置中可开启）");
            return;
        }
        wsStarted = true;
        new Thread(() -> startWebSocket(getRoomId())).start();
    }

    private void startWebSocket(int roomId) {
        try {
            // 带上 B站 cookie（getDanmuInfo 需要登录态）
            String cookie = null;
            try {
                List<AiConfig> configs = aiConfigMapper.findAll();
                if (configs != null && !configs.isEmpty()) cookie = configs.get(0).getBili_cookie();
            } catch (Exception ignored) {}

            // WBI 签名（getDanmuInfo 风控，需要 w_rid + wts）
            String[] wbi = getWbiKeys(cookie);
            String mixinKey = getMixinKey(wbi[0], wbi[1]);
            int uid = wbi.length > 2 ? Integer.parseInt(wbi[2]) : 0;
            long wts = System.currentTimeMillis() / 1000;
            String wRid = md5("id=" + roomId + "&wts=" + wts + mixinKey);
            System.out.println("[弹幕] WBI: mixinKey=" + mixinKey + " wts=" + wts + " wRid=" + wRid);
            String danmuUrl = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?id=" + roomId + "&wts=" + wts + "&w_rid=" + wRid;

            String json = httpGet(danmuUrl, cookie, "https://live.bilibili.com/" + roomId);
            JsonNode root = MAPPER.readTree(json);
            int code = root.has("code") ? root.get("code").asInt() : -1;
            JsonNode data = root.get("data");
            if (code != 0 || data == null || !data.has("token")) {
                System.out.println("[弹幕] 获取弹幕token失败 code=" + code + " 返回=" + (json != null && json.length() > 120 ? json.substring(0, 120) : json));
                wsStarted = false;
                return;
            }
            String token = data.get("token").asText();
            JsonNode hostList = data.get("host_list");
            if (hostList == null || hostList.size() == 0) {
                System.out.println("[弹幕] 无可用弹幕服务器");
                wsStarted = false;
                return;
            }
            JsonNode firstHost = hostList.get(0);
            String host = firstHost.get("host").asText();
            // 优先用 wss_port（SSL 443），否则用 port
            int port = firstHost.has("wss_port") ? firstHost.get("wss_port").asInt() : firstHost.get("port").asInt();
            String wsUrl = "wss://" + host + ":" + port + "/sub";
            System.out.println("[弹幕] WebSocket 连接: " + wsUrl);

            ws = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    @Override
                    public void onOpen(WebSocket webSocket) {
                        System.out.println("[弹幕] WebSocket 已连接，发送认证");
                        webSocket.sendBinary(ByteBuffer.wrap(buildAuthPacket(roomId, uid, token)), true);
                        webSocket.request(1);
                    }
                    @Override
                    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                        handlePacket(data);
                        webSocket.request(1);
                        return null;
                    }
                    @Override
                    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                        System.out.println("[弹幕] WebSocket 关闭: " + statusCode + " " + reason);
                        wsStarted = false;
                        return null;
                    }
                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        System.out.println("[弹幕] WebSocket 错误: " + error.getMessage());
                        wsStarted = false;
                    }
                }).join();

            wsHeartbeat = Executors.newSingleThreadScheduledExecutor();
            wsHeartbeat.scheduleAtFixedRate(() -> {
                try {
                    if (ws != null) { ws.sendBinary(ByteBuffer.wrap(buildHeartbeatPacket()), true); System.out.println("[弹幕] 心跳已发送"); }
                } catch (Exception ignored) {}
            }, 0, 30, TimeUnit.SECONDS);

        } catch (Exception e) {
            System.out.println("[弹幕] WebSocket 启动失败: " + e.getMessage());
            wsStarted = false;
        }
    }

    // ===== WBI 签名 =====
    private static final int[] MIXIN_TAB = {46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,27,43,5,49,33,9,42,19,29,28,14,39,12,38,41,13,37,48,7,16,24,55,40,61,26,17,0,1,60,51,30,4,22,25,54,21,56,59,6,63,57,62,11,36,20,34,44,52};

    private String getMixinKey(String imgKey, String subKey) {
        String orig = imgKey + subKey;
        StringBuilder sb = new StringBuilder();
        for (int i : MIXIN_TAB) {
            if (i < orig.length()) sb.append(orig.charAt(i));
        }
        return sb.substring(0, Math.min(32, sb.length()));
    }

    private String md5(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    // 用 HttpURLConnection 直接发 GET（绕过 RestTemplate 对 Cookie 头的处理）
    private String httpGet(String url, String cookie, String referer) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
            conn.setRequestProperty("Referer", referer != null ? referer : "https://www.bilibili.com/");
            conn.setRequestProperty("Origin", "https://live.bilibili.com");
            if (cookie != null && !cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            int respCode = conn.getResponseCode();
            try (java.io.InputStream in = (respCode == 200) ? conn.getInputStream() : conn.getErrorStream()) {
                String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                System.out.println("[弹幕] httpGet code=" + respCode + " 返回前120=" + (body.length() > 120 ? body.substring(0, 120) : body));
                return body;
            }
        } catch (Exception e) {
            System.out.println("[弹幕] httpGet 异常: " + e.getMessage());
            return null;
        }
    }

    private String[] getWbiKeys(String cookie) {
        try {
            String body = httpGet("https://api.bilibili.com/x/web-interface/nav", cookie, "https://www.bilibili.com/");
            JsonNode data = MAPPER.readTree(body).get("data");
            JsonNode wbi = data.get("wbi_img");
            String imgUrl = wbi.get("img_url").asText();
            String subUrl = wbi.get("sub_url").asText();
            String imgKey = imgUrl.substring(imgUrl.lastIndexOf('/') + 1).replace(".png", "");
            String subKey = subUrl.substring(subUrl.lastIndexOf('/') + 1).replace(".png", "");
            int uid = data.has("mid") ? data.get("mid").asInt() : 0;
            return new String[]{imgKey, subKey, String.valueOf(uid)};
        } catch (Exception e) {
            return new String[]{"7cd084941338484aae1ad9425b84077c", "4932caff0ff746eab6f01bf08b70ac45", "0"};
        }
    }

    private byte[] buildAuthPacket(int roomId, int uid, String token) {
        String authJson = "{\"uid\":" + uid + ",\"roomid\":" + roomId + ",\"protover\":3,\"platform\":\"web\",\"type\":2,\"key\":\"" + token + "\"}";
        System.out.println("[弹幕] 认证包: " + authJson);
        return buildPacket(1, 7, 1, authJson.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] buildHeartbeatPacket() {
        return buildPacket(1, 2, 1, "[object Object]".getBytes(StandardCharsets.UTF_8));
    }

    private byte[] buildPacket(int protover, int op, int seq, byte[] body) {
        int headerLen = 16;
        int totalLen = headerLen + body.length;
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.BIG_ENDIAN);
        buf.putInt(totalLen);
        buf.putShort((short) headerLen);
        buf.putShort((short) protover);
        buf.putInt(op);
        buf.putInt(seq);
        buf.put(body);
        return buf.array();
    }

    private void handlePacket(ByteBuffer data) {
        data.order(ByteOrder.BIG_ENDIAN);
        while (data.remaining() >= 16) {
            int totalLen = data.getInt();
            short headerLen = data.getShort();
            short protover = data.getShort();
            int op = data.getInt();
            data.getInt(); // seq
            int bodyLen = totalLen - headerLen;
            if (bodyLen < 0 || bodyLen > data.remaining()) break;
            byte[] body = new byte[bodyLen];
            data.get(body);

            if (op == 8) {
                System.out.println("[弹幕] WebSocket 认证成功，body=" + new String(body, StandardCharsets.UTF_8));
            } else if (op == 3) {
                System.out.println("[弹幕] WebSocket 心跳回复");
            } else if (op == 5) {
                byte[] jsonBytes;
                if (protover == 3) jsonBytes = decompressBrotli(body);
                else if (protover == 2) jsonBytes = decompressZlib(body);
                else jsonBytes = body;
                parseMessages(jsonBytes);
            } else {
                System.out.println("[弹幕] 收到其他包 op=" + op + " protover=" + protover + " len=" + bodyLen);
            }
        }
    }

    private byte[] decompressBrotli(byte[] compressed) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            BrotliInputStream in = new BrotliInputStream(new java.io.ByteArrayInputStream(compressed));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            in.close();
            byte[] result = out.toByteArray();
            System.out.println("[弹幕] brotli解压: " + compressed.length + " -> " + result.length);
            return result;
        } catch (Exception e) {
            System.out.println("[弹幕] brotli解压失败: " + e.getMessage());
            return compressed;
        }
    }

    private byte[] decompressZlib(byte[] compressed) {
        try {
            Inflater inflater = new Inflater();
            inflater.setInput(compressed);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n <= 0) break;
                out.write(buf, 0, n);
            }
            inflater.end();
            return out.toByteArray();
        } catch (Exception e) {
            return compressed;
        }
    }

    private void parseMessages(byte[] jsonBytes) {
        String text = new String(jsonBytes, StandardCharsets.UTF_8);
        String[] parts = text.split("(?<=\\})(?=\\{)");
        for (String part : parts) {
            try {
                JsonNode node = MAPPER.readTree(part);
                String cmd = node.has("cmd") ? node.get("cmd").asText() : "";
                if ("INTERACT_WORD".equals(cmd)) {
                    JsonNode d = node.get("data");
                    if (d != null && d.has("uname")) {
                        String uname = d.get("uname").asText();
                        int uid = d.has("uid") ? d.get("uid").asInt() : 0;
                        System.out.println("[弹幕] 进入直播间: " + uname);
                        for (EnterListener l : enterListeners) {
                            try { l.onEnter(uname, uid); } catch (Exception ignored) {}
                        }
                    }
                } else if ("INTERACT_WORD_V2".equals(cmd)) {
                    JsonNode d = node.get("data");
                    String pb = d != null && d.has("pb") ? d.get("pb").asText() : null;
                    String uname = pb != null ? extractUnameFromPb(pb) : null;
                    if (uname != null && !uname.isEmpty()) {
                        System.out.println("[弹幕] 进入直播间: " + uname);
                        for (EnterListener l : enterListeners) {
                            try { l.onEnter(uname, 0); } catch (Exception ignored) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
    }

    // 从 INTERACT_WORD_V2 的 pb(protobuf base64) 里提取用户名（field 2 = string）
    private String extractUnameFromPb(String pbBase64) {
        try {
            byte[] pb = java.util.Base64.getDecoder().decode(pbBase64);
            int i = 0;
            while (i < pb.length) {
                int tag = 0, shift = 0;
                while (i < pb.length) {
                    byte b = pb[i++];
                    tag |= (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) break;
                    shift += 7;
                }
                int fieldNum = tag >> 3;
                int wireType = tag & 0x07;
                if (wireType == 0) {
                    while (i < pb.length && (pb[i] & 0x80) != 0) i++;
                    i++;
                } else if (wireType == 2) {
                    int len = 0;
                    shift = 0;
                    while (i < pb.length) {
                        byte b = pb[i++];
                        len |= (b & 0x7F) << shift;
                        if ((b & 0x80) == 0) break;
                        shift += 7;
                    }
                    if (fieldNum == 2 && len > 0 && i + len <= pb.length) {
                        return new String(pb, i, len, StandardCharsets.UTF_8);
                    }
                    i += len;
                } else if (wireType == 5) {
                    i += 4;
                } else if (wireType == 1) {
                    i += 8;
                } else {
                    break;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    @PreDestroy
    public void stopPolling() {
        if (scheduler != null) scheduler.shutdownNow();
        if (wsHeartbeat != null) wsHeartbeat.shutdownNow();
    }
}
