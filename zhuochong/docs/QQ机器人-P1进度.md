# QQ 机器人 · P1 进度（图片识别 + 表情包）

> 更新：2026-10-20 · 承接 `QQ机器人-云端部署进度.md` 的 P0（已完成）
> 说明：本次想把本文并进主进度文档的 P1 段，但那个文件当时被别的程序独占写入（`The process cannot access the file`），
> 所以单独落成一份。主文档 P0 段完好，你关掉占用它的程序后可直接把本文内容贴进 `### P1 玩法`。

## 一、本轮范围

| 项 | 状态 |
|---|---|
| 群聊图片识别（取 url → 下载 → 缩图 → 视觉模型） | ✅ 完成 |
| 表情包（头部特写差分，替代原 `aluona/*.png` 全身立绘） | ✅ 完成 |
| 发送失败重试 1 次 | ✅ 顺带补上 |
| 你画我猜 QQ 版 / 网络天才 | ⏸ 按你的要求先不做 |

## 二、图片识别

| 落点 | 说明 |
|---|---|
| `com/cy/qq/QqImageService.java` | 下载（≤10MB，只收 http/https）→ 长边缩到 ≤800px → 转 JPEG（白底，透明图不会变黑）→ `chatVision` 拿一句客观描述 |
| `QqEventRouter` | 收集 `image` 段 url；单条最多看 2 张；只发图不写字也能识别（不会回「嗯？叫我做什么呀」） |
| `QqChatService` | 把描述拼成「并发来图片（你看到的内容：…）」，再进正常对话 |
| 开关 | `ZC_QQ_VISION`（默认开）、`qq.vision-max-edge` / `qq.vision-max-images` |

## 三、表情包（重做素材）

原方案用 `aluona/*.png`（全身立绘）——缩到表情包尺寸后人物只有一点点，群里像贴了张桌面截图。
现改用官方「阿罗娜 - 初始立绘差分」的头肩特写：

- **43 张 → 剔除 4 张重复 → 39 张**，缩到 360×360，存 `src/main/resources/static/qq_emoji/`（6.7 MB）
- 逐张人工标注写进 `static/qq_emoji/emoji_map.json`（文件 → 表情内容 → 情绪标签），明细见 `QQ机器人-表情包标注.md`
- 挑选：AI 的 `emotion` → 同组随机（不连续重复）→ 跟在文字后面发；无 emotion 时按回复关键词推断，再不行落「默认」
- 发送：`send_group_msg` 的 `image` 段 + `base64://`（NapCat 在 Docker 里读不到宿主机路径）
- 开关：`ZC_QQ_EMOJI`、`qq.emoji-probability`（默认 100）
- 为了让表情对得上，`qq_chat` 提示词里已要求模型**必须**返回 `emotion`

重复图判定（已剔除）：

| 文件 | 原因 |
|---|---|
| `arona_spr_Look_01_M.png` | 与 `00.png` MD5 相同 |
| `arona_spr_Look_01_A.png` | 与 `LookEnd_01_A.png` MD5 相同 |
| `arona_spr_Pat_01_A.png` | 与 `PatEnd_01_A.png` MD5 相同 |
| `arona_spr_LookEnd_01_M.png` | 与 `00.png` 视觉无差别（同帧差分） |

## 四、自测（假 NapCat，`tools/fake-onebot.js`）

```
[QQ] 表情包已加载 39 张，覆盖 18 个情绪标签
[QQ] 表情包: 09.png（眯眼轻笑（温和）） ← 情绪由文本推断
[QQ] → 群 999001 发送图片（base64 239KB）
[QQ] 收到 @老师（QQ 555222 / 群 999001）: [图片]  [含 1 张图]
[QQ] 图片已缩放 170KB → 19KB，送视觉模型（181ms）
[QQ] 图片理解: 画面里是一个浅蓝色头发、粉色挑染的动漫少女，在微笑…
[QQ] → 群 999001（57字）: （凑近屏幕仔细看）诶——这个蓝头发的女孩子，跟我的配色有点像呢！…
[QQ] 表情包: 02.png（微微一怔（睁眼小张嘴））
[QQ] → 群 999001 发送图片（base64 233KB）
```

## 五、踩到并修掉的两个坑

1. **JDK HttpClient 默认带 `Upgrade: h2c`**：请求 `http://127.0.0.1:3001/testimg` 时 NapCat 的 HTTP 服务把
   `Upgrade` 头当成 WebSocket 握手，直接回 **401**，图片永远下载失败。→ 抓图客户端必须
   `.version(HttpClient.Version.HTTP_1_1)`（已加，注释写明原因）。
2. **JDK WebSocket 大消息会分片**：240KB 的 base64 表情被拆成 `FIN=0` + 连续帧，自建的假 NapCat 若不按
   FIN 拼接就解析失败（表现为「发送失败：无回执」）。真 NapCat 没这个问题，但测试工具必须补上。

## 六、还没做（P1 剩余 / P2）

- 语音：**已确认不做**（云端没有 GPT-SoVITS，机器人只发文字 + 表情图）
- 引用回复：✅ 已在 P4 做完（见 `QQ机器人-P4进度.md`）
- P2 闲置响应：每群环形缓冲 200 条、≥25 分钟无 @ 才插话、被说「闭嘴」静默 2 小时（按你的要求先跳过）
- P3 运维 + 管理员：✅ 已做完（见 `QQ机器人-P3进度.md`）
