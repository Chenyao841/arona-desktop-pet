# 桌宠项目 · 开发索引文档 PROJECT_OVERVIEW.MD

> 本文件是给自己（或后续 AI 协作）在修改/优化前快速回顾项目用的索引，避免每次都要把整个项目翻一遍。
> 覆盖两个文件夹：`zhuochong`（Spring Boot 后端 + 内嵌前端）与 `zhuochong-electron`（Electron 桌面壳）。
> 阅读约定：所有「位置」均以 `zhuochong/` 为根的相对路径；行号来自最近一次整理时的代码快照，修改后可能漂移，以符号名检索为准。

---

## 0. 一句话总览

这是一个 **B 站直播互动型 AI 桌宠**：
- 后端是 **Spring Boot 4.1.0 (Java 17) + MyBatis 注解式 Mapper + MySQL** 的单体服务，跑在 `localhost:8080`。
- 前端是 **原生 HTML/JS/CSS 静态页**，由 Spring 从 `src/main/resources/static` 直接托管。
- 桌宠本体有两种渲染：**PNG 表情图**（默认，`aluona/*.png`）和 **Live2D 模型**（PixiJS + pixi-live2d-display）。
- 能力串联了 4 个外部服务：**DeepSeek API**（对话/情绪分类/记忆）、**GPT-SoVITS**（语音合成，`api.py` @9880）、**网易云 API**（点歌，Node `app.js` @3000）、**B 站直播弹幕**（HTTP 轮询 + WebSocket）。
- Electron 只是一个**透明置顶的无边框窗口**，加载 `http://localhost:8080/桌宠桌面版.html`，负责托盘、全局快捷键、鼠标穿透。

**接下来的主方向：桌宠 Live2D 模型的生动化优化** → 详见第 8 节专题。

---

## 1. 目录结构树

> 已排除 `target/`（编译产物）、`tts_cache/`（语音缓存，数千个 base64 文件）、`node_modules/`、`CubismSDK`/`CubismSdkForWeb-5-r.4`（Live2D 官方 SDK 示例，仅作参考，不参与运行）。

```
zhuochong/
├── pom.xml                          # Maven 构建配置（Spring Boot 4.1.0 / Java 17）
├── mvnw / mvnw.cmd                  # Maven Wrapper
├── 一键启动.bat                      # 启动后端(spring-boot:run) + Electron 桌宠
├── danmaku_test.py                  # 独立 Python 弹幕调试脚本（WebSocket 直连）
├── HELP.md                          # Spring 初始化默认说明（可忽略）
├── sql/
│   └── idle_activity.sql            # 闲置活动库建表 + 100 条种子数据
├── tts_cache/                       # GPT-SoVITS 语音缓存（md5(文本).txt = base64 wav）
├── src/main/java/com/cy/
│   ├── ZhuochongApplication.java    # 启动入口
│   ├── component/
│   │   ├── TtsServerManager.java    # 启动时拉起 GPT-SoVITS api.py
│   │   └── NeteaseStarter.java      # 启动时拉起网易云 API app.js
│   ├── config/
│   │   ├── WebConfig.java           # 拦截器注册 + 白名单（★改接口必看）
│   │   └── MyBatisConfig.java       # 数据源 + @MapperScan（★DB 连接写死在这）
│   ├── conroller/                   # 注意：包名拼写是 conroller（少一个 t）
│   │   ├── AdminController.java     # /login 登录
│   │   ├── AiController.java        # /ai/** 配置/提示词/TTS/弹幕流/规则/音乐/闲置库
│   │   └── ConservationController.java # 对话/反应/选项/聊天/表情/基础信息
│   ├── Interceptor/
│   │   └── Interceptor.java         # JWT 鉴权 + 员工身份写限制
│   ├── mapper/                      # MyBatis 注解式 Mapper（无 XML）
│   │   ├── AdminMapper.java
│   │   ├── AiConfigMapper.java      # ai_config（AI/TTS 唯一配置表）
│   │   ├── AiHistoryMapper.java     # ai_history 会话历史
│   │   ├── AiPromptMapper.java      # ai_prompt 提示词版本库
│   │   ├── BasicInfoMapper.java     # basicinfo 人设（id=1 桌宠 / id=2 用户）
│   │   ├── ConservationMapper.java  # conversation + conversation_choice
│   │   ├── EffectMapper.java        # effect 音效/特效位置
│   │   ├── IdleActivityMapper.java  # idle_activity 闲置活动库
│   │   ├── PetMemoryMapper.java     # pet_memory 长期记忆
│   │   ├── ResponseRuleMapper.java  # response_rule 关键词→表情/音效/动作规则
│   │   └── TestHistoryMapper.java   # test_history 原始 AI 响应
│   ├── pojo/                        # Lombok @Data 数据模型（见第 4 节）
│   ├── service/
│   │   ├── AdminService / _impl
│   │   ├── ConversationService / _impl
│   │   ├── DeepSeekService.java     # 核心：AI 对话/情绪/记忆/歌单
│   │   ├── DanmakuService.java      # B 站弹幕（轮询 + WebSocket + WBI 签名）
│   │   ├── NeteaseService.java      # 网易云搜索/播放/相似/热歌
│   │   └── TtsService.java          # GPT-SoVITS 合成 + md5 缓存
│   └── utils/
│       ├── JWTUTILL.java            # JWT 生成/解析/校验
│       └── CurrentHolder.java       # ThreadLocal 当前用户 id
├── src/main/resources/
│   ├── application.yml              # 端口/TTS/网易云/B站房间号
│   ├── application.properties       # 仅 spring.application.name
│   └── static/                      # ★ 前端全部在此
│       ├── 登录.html                 # 登录页
│       ├── 桌宠管理.html             # 管理后台（对话/规则/语音/闲置库/AI配置）
│       ├── 桌宠桌面版.html            # ★★ 桌宠主界面（Electron 加载它）
│       ├── 桌宠设置.html              # 基础设置（窗口大小/开关/房间号）
│       ├── 桌宠live2d测试.html        # Live2D 最小加载测试页
│       ├── 测试.html
│       ├── 指令手册.md
│       ├── CSS/桌宠管理.css          # 全部样式 + PNG 动作动画 keyframes
│       ├── JS/
│       │   ├── axios.js             # axios 库（本地副本）
│       │   └── 桌宠管理.js           # 管理后台全部逻辑（112KB）
│       ├── aluona/                  # PNG 表情图（16 张，兜底/管理用）
│       ├── live2d/                  # ★★ Live2D 模型与桥接
│       │   ├── live2d-fix-bridge.js # ★ 当前使用的 PixiJS 桥接（表情/动作/眨眼/嘴型）
│       │   ├── live2d-bridge.js     # 旧手写 WebGL 渲染器（已废弃，勿改）
│       │   ├── live2dcubismcore-v4.min.js  # Cubism Core v4
│       │   ├── node_modules/{pixi.js, pixi-live2d-display}  # 本地 vendor
│       │   ├── expressions/         # 17 个 .exp3.json 表情参数文件（猫猫/墨镜/牛奶/抓抓 未使用）
│       │   ├── textures/            # 模型贴图 texture_00/01.png
│       │   ├── *.model3.json / *.moc3 / *.physics3.json / *.cdi3.json / *.vtube.json
│       │   ├── idle.motion3.json    # 闲置循环动作（未被代码引用）
│       │   └── 水饺.motion3.json    # Sleep 睡觉动作（model3.json 的 Sleep 组）
│       ├── soundeffects/            # 11 个 .webm 音效（害羞/惊讶/流汗/生气…）
│       ├── music/                   # 本地音乐文件（含误放进来的 SDK/zip 等杂物）
│       ├── background/              # 登录页背景图/视频
│       ├── bottom/ / Framework/ / Shaders/  # Live2D Cubism 官方 shader/框架（vendor）
│       ├── GPT_weights_v2/          # GPT-SoVITS GPT 权重 .ckpt
│       └── SoVITS_weights_v2/       # GPT-SoVITS SoVITS 权重 .pth
└── src/test/java/.../ZhuochongApplicationTests.java  # 空测试

zhuochong-electron/
├── main.js         # Electron 主进程：窗口/托盘/快捷键/IPC
├── preload.js      # contextBridge 暴露 window.electronAPI
├── package.json    # electron ^33
├── package-lock.json
└── start.bat       # npm start
```

---

## 2. 模块 / 文件职责说明

### 2.1 后端（`src/main/java/com/cy`）

| 文件 | 职责 |
|---|---|
| `ZhuochongApplication.java` | Spring Boot 入口，无额外配置。 |
| `conroller/AdminController.java` | `POST /login`：校验账密 → 发 JWT + 身份/姓名/id，附带一条 login 欢迎反应。 |
| `conroller/AiController.java` | `/ai/**` 大杂烩：AI 配置读写、提示词 CRUD、模型权重列举与切换、TTS（合成/候选/流式/预热）、历史（星标/编辑/删除）、弹幕 SSE 流、规则播种、规则库 CRUD、音乐（本地列表/网易云搜索/URL/推荐/收藏）、记忆合并、游戏总结、闲置活动库 CRUD。 |
| `conroller/ConservationController.java` | 对话与反应核心：`/conversation`、`/reaction`、`/idle`、`/choice[/sub]`、`/talk`（AI 对话主入口）、`/motions`、`/soundeffects/list`、`/effects`、`/basicinfo`、`/seed-game-expressions`。含表情规则匹配、句级切分、短词生长、睡眠兜底等业务逻辑。 |
| `config/WebConfig.java` | 注册拦截器，`addPathPatterns("/**")` + `excludePathPatterns(白名单)`。**新增接口若忘加白名单会被 401 拦截。** |
| `config/MyBatisConfig.java` | HikariCP 数据源 + `@MapperScan("com.cy.mapper")`。**DB 地址/账号密码硬编码于此**。 |
| `Interceptor/Interceptor.java` | 校验 `Authorization: Bearer <token>`；无效 → 401；`员工` 身份的所有 POST/PUT/DELETE → 403（员工只读）。 |
| `service/DeepSeekService.java` | DeepSeek 调用核心：`chat`（组装 system prompt + 历史 + 记忆，返回结构化 AiResult）、`classifyEmotion`（二次调用给回复配表情/音效/动作/关键词）、`growKeyword`（短词扩展）、`consolidateMemory`/`gameSummary`（记忆）、`recommendSongs`（AI 歌单）。 |
| `service/DanmakuService.java` | B 站弹幕：优先 HTTP 轮询（`gethistory`，2 秒一次），WebSocket（`getDanmuInfo`+WBI 签名）用于进入直播间事件；brotli/zlib 解压；监听器模式推给 SSE。 |
| `service/TtsService.java` | 调 GPT-SoVITS `GET /?text=...` 返回 wav，去括号后 md5 缓存到 `tts_cache/`。 |
| `service/NeteaseService.java` | 网易云 API 封装（localhost:3000）。 |
| `service/ConversationService(_impl)` | conversation / conversation_choice 的增删改查 + 随机反应查询；维护 choice_id 分组与自增 id 重整。 |
| `service/AdminService(_impl)` | 登录校验。 |
| `component/TtsServerManager.java` | `ApplicationReadyEvent` 时用配置的 python 启动 GPT-SoVITS `api.py -a 0.0.0.0 -p 9880`（带模型路径）。 |
| `component/NeteaseStarter.java` | 启动时用 node 拉起网易云 `app.js`。 |
| `utils/JWTUTILL.java` | jjwt 0.12.5 生成/解析/校验，密钥写死，有效期 24h。 |
| `utils/CurrentHolder.java` | `ThreadLocal<Integer>` 存当前登录用户 id，拦截器 afterCompletion 清理。 |

### 2.2 前端（`src/main/resources/static`）

| 文件 | 职责 |
|---|---|
| `桌宠桌面版.html` | **桌宠运行时的全部逻辑**（1732 行）：axios 拦截器、容器拖拽/缩放、`playSpeech`（TTS 分段流式）、`playSentenceFlow`（句级表情流）、`playEffects`（音效/动作）、`showResult`、空闲检测 + AI 智能闲置、音乐播放器/网易云队列、网络天才猜物游戏、本地命令匹配、弹幕 SSE 消费、Live2D 模式切换、锁定/鼠标穿透。 |
| `桌宠管理.html` + `JS/桌宠管理.js` | 管理后台：对话表 CRUD、规则库、语音库（TTS 候选试听）、闲置活动库、AI 配置/提示词/模型切换、历史记录（星标）、人设编辑。 |
| `桌宠设置.html` | 本地 `localStorage.pet_settings` 设置（窗口宽/位置/音量/开关）+ 房间号写回 `/ai/config/room-id`。 |
| `登录.html` | 登录 → `localStorage.token/identity/name/userId` → 跳管理页。 |
| `桌宠live2d测试.html` | 最小 Live2D 加载验证页（注意它从 Live2D 官方 CDN 引 cubismcore）。 |
| `CSS/桌宠管理.css` | 样式 + PNG 动作动画 `@keyframes`（pet-bounce/drop/swing/spin/tilt/escape 等）。 |
| `JS/axios.js` | axios 本地副本。 |

### 2.3 Electron（`zhuochong-electron`）

| 文件 | 职责 |
|---|---|
| `main.js` | 透明、无边框、置顶、全屏 workArea 的 `BrowserWindow` 加载 `http://localhost:8080/桌宠桌面版.html`；系统托盘（显示/隐藏、基础设置、管理页、退出）；全局快捷键 `Ctrl+Shift+Z` 锁定/解锁、`Ctrl+Shift+A` 显示/隐藏；IPC `move-window`/`set-ignore-mouse`。 |
| `preload.js` | 通过 `contextBridge` 暴露 `window.electronAPI`（moveWindow/setIgnoreMouse/close/minimize/onToggleLock）。 |

---

## 3. 关键类 / 接口 / 函数签名与位置

### 3.1 后端核心方法

| 签名 | 位置 | 用途 |
|---|---|---|
| `DeepSeekService.AiResult chat(String userMessage, String storeMessage, String sessionId, String userOperation)` | `service/DeepSeekService.java:120` | 主对话。组装人设 + 提示词 + 长期记忆 + 最近 20 条历史 → 调 DeepSeek → 解析 JSON → 存 `ai_history` → 返回 AiResult。 |
| `AiResult classifyEmotion(String boatText)` | 同上 `:255` | 二次调用：给 AI 回复配 `motion/sound_effect/special_effect/keyword`。 |
| `AiResult growKeyword(String shortKeyword, String originalText)` | 同上 `:309` | 2-3 字短词扩展成 3-5 字更精准关键词。 |
| `void consolidateMemory(String characterName, String sessionId)` | 同上 `:423` | 记忆合并（每 10 次对话触发）。 |
| `void gameSummary(String characterName, String sessionId)` | 同上 `:490` | 游戏结束一句话总结入库。 |
| `static class AiResult`（字段 `boat_text/motion/sound_effect/special_effect/dataJson/sleep_seconds`） | 同上 `:56` | 结构化返回体。 |
| `String synthesize(String text)` | `service/TtsService.java:61` | 合成（带缓存）。 |
| `String doSynthesize(String text)` | 同上 `:116` | 实际 GET GPT-SoVITS。 |
| `void start(int roomId)` / `void pollDanmaku()` | `service/DanmakuService.java:118/:147` | 弹幕启动 + HTTP 轮询。 |
| `Result talk(@RequestBody user_operation op)` | `conroller/ConservationController.java:480` | **对话主入口**：规则库命中→否则 AI 对话→表情匹配→句级表情流→后台情绪分类入规则库。 |
| `List<Map<String,String>> buildSentenceFlow(String text, String defaultMotion)` | 同上 `:185` | 按句切分 + 逐句匹配表情。 |
| `String matchMotion(String text, String fallback)` | 同上 `:132` | 规则库长词优先匹配表情。 |

### 3.2 前端核心函数（`static/桌宠桌面版.html`）

| 函数 | 位置 | 用途 |
|---|---|---|
| `playSpeech(text, motion, soundEffect, specialEffect, onComplete)` | `:227` | 统一响应播放：TTS 流式（`/ai/tts/stream`）逐段合成 + 打字机 + 表情/音效/动作。 |
| `playSentenceFlow(flow)` | `:321` | 句级播放：pipeline 预合成下一句，逐句切表情 + 唇形。 |
| `playEffects(soundEffect, specialEffect)` | `:391` | 音效视频 + 动作（Live2D 调 `l2dBridge.playAction`，PNG 调 CSS 动画）。 |
| `showResult(data)` | `:516` | 后端 Result 统一分发：sleep 处理、句级/段级播放、选项渲染。 |
| `initIdle()` | `:686` | 空闲检测 + AI 智能闲置（2 分钟无 AI 触发）。 |
| `buildIdlePrompt(act)` | `:775` | 拼闲置提示词（活动 + 延续 + 直播/音乐场合）。 |
| `handleLocalCommand(text)` | `:938` | 本地命令匹配（音乐/游戏/直播/开关/Live2D 测试）。 |
| `lipTick()/startLipSync()/stopLipSync()` | `:187/:200/:201` | Web Audio 分析 TTS 音频 → `ParamMouthOpenY` 唇形。 |
| `callReaction(userOperation, cb)` | `:582` | 调 `/reaction`。 |

### 3.3 Live2D 桥接（`static/live2d/live2d-fix-bridge.js`，当前生效）

| 成员 | 位置 | 用途 |
|---|---|---|
| `exprMap`（对象字面量） | `:278-297` | **图片表情名 → Live2D 表情文件（可数组组合）映射表**。新增/改表情映射改这里。 |
| `_init()` | `:274-350` | 加载模型（PixiJS `Live2DModel.from`）、表情文件预载、定位模型、启动眨眼呼吸。 |
| `setExpression(motionName)` | `:262` | 重置上一表情参数 + 应用新表情参数。 |
| `playAction(action)` | `:120` | 整体动作：bounce/drop/swing/spin/spin2/spin3/tilt/escape（时长硬编码）。 |
| `playMotion(group, index)` | `:242` | 播放 model3.json 里的 motion（如 `Sleep` 组）。 |
| `stopMotion()` | `:250` | 停止 motion 并把所有参数复位到默认值（清除睡眠残留）。 |
| `setParam(id, val)` | `:41` | 直接设参数值。 |
| `_startIdleAnim()` | `:353` | 呼吸（`ParamBreath` 正弦）+ 眨眼（`ParamEyeLOpen/Ropen`，3~6s 随机，闭眼 120ms）。 |
| `setPivot(px,py)` / `hitTest(x,y)` / `getBounds()` | `:59/:94/:102` | 旋转中心 / 点击命中 / 边界。 |
| `testExpression(name)` / `comboTest(names)` | `:197/:211` | 表情调试工具。 |

> 旧桥接 `live2d/live2d-bridge.js` 是手写 WebGL 直绘，**已被 `live2d-fix-bridge.js` 取代**，`桌宠桌面版.html` 只引入后者，不要改旧的。

---

## 4. 数据模型 / 状态管理

### 4.1 数据库（MySQL `db03`，见 `MyBatisConfig.java:20`）

| 表 | 关键字段 | 说明 |
|---|---|---|
| `adminlist` | id, name, username, userkey, identity | 管理员/员工账号（identity 区分权限）。 |
| `ai_config` | api_key, api_url, tts_url, model, refer_wav, prompt_text, tts_speed, sample_steps, max_tokens, temperature, gpt_model_path, sovits_model_path, live_mode, bili_cookie, room_id, danmaku_enabled | **全局唯一 AI/TTS 配置，单行（id=1）**。 |
| `ai_prompt` | prompt_type, content, version | 提示词版本库（type=system/function）。 |
| `ai_history` | session_id, user_text, user_operation, boat_text, motion, sound_effect, special_effect, starred | 会话历史（AI 上下文 + 管理页展示）。 |
| `test_history` | response | 原始 AI 响应（调试用）。 |
| `conversation` | user_id, user_text, boat_text, motion, choice_id, user_operation, role, sound_effect, special_effect | 预置对话/反应库（`role='basic'` 为系统预置，随机命中）。 |
| `conversation_choice` | choice_id, user_operation, choice_text, choice_action, boat_text, motion, next_choice_id, visible, sound_effect, special_effect | 选项树（choice_id 分组 + next_choice_id 串联子选项）。 |
| `response_rule` | keyword, motion, sound_effect, special_effect, action, weight, sample_text, grow_count, cooldown | **关键词 → 表情/音效/动作规则库**（AI 自动播种 + 手工维护）。 |
| `idle_activity` | theme, category, enabled | 闲置活动库（continuable/simple/sleep）。 |
| `pet_memory` | character_name, content | 长期记忆。 |
| `basicinfo` | id, 姓名, 身份, 表情存放地址 | 人设（id=1 桌宠，id=2 用户）。 |
| `effect` | soundeffect, location, live2d_location | 音效/特效的屏幕位置（CSS `key:value;` 字符串）。 |

> 只有 `sql/idle_activity.sql` 一个建表脚本；其余表结构未随仓库导出，建表以 Mapper 的 `@Insert/@Select` 字段与 POJO 为准。

### 4.2 后端统一返回信封 `Result`（`pojo/Result.java`）

- `code`：1 成功 / 0 失败。
- `message`：文本回复（桌宠说的话）。
- `motion`：表情（PNG 文件名如 `aluona_kaixin.png`，或 Live2D 表情键）。
- `soundEffect` / `specialEffect`：音效 / 动作。
- `sleepSeconds`：AI 返回的休眠秒数。
- `data`：附加数据（登录信息、选项数组、歌单等）。
- `segments`：段级（`\n\n`）文本流；`sentences`：句级表情流 `[{text, motion}]`。

### 4.3 前端状态（`桌宠桌面版.html` 顶部全局变量）

- `reactiveMap`：隐藏式选项 `{ userOperation: choiceObj }`。
- `currentChoices`：当前可见选项数组。
- `textSegments`：`\n\n` 分段待播队列。
- `talkSessionId`：AI 会话 id（页面加载时间戳）。
- `useLive2D` / `isLiveMode` / `idleEnabled` / `ttsEnabled` / `choiceTextMode`：功能开关。
- `idleActivities` / `currentActivity`：闲置活动库缓存与延续状态。
- `game`：网络天才游戏状态机。
- `neteaseQueue` / `musicPlayer`：音乐队列与播放器。
- `localStorage`：`token/identity/name/userId`（登录态）、`pet_settings`（设置）、`idle_current_activity`（闲置延续）。

---

## 5. 配置 / 构建 / 运行

### 5.1 配置文件

- `application.yml`：
  - `server.port=8080`
  - `tts.python` / `tts.api-dir` / `tts.auto-start` → GPT-SoVITS 路径（**写死本机绝对路径，换机器必改**）
  - `netease.node` / `netease.app` / `netease.auto-start` → 网易云 API（`D:\NeteaseCloudMusicApi\app.js`）
  - `bili.room-id=4198805` → 弹幕默认房间（实际以 `ai_config.room_id` 为准）
- `application.properties`：仅 `spring.application.name`。
- **数据库连接不在 yml，而在 `config/MyBatisConfig.java:20`**：`jdbc:mysql://localhost:3306/db03`，root / CHANGE_ME_DB_PASSWORD。
- `pom.xml`：Spring Boot `4.1.0` parent，依赖 webmvc / mybatis-spring-boot-starter 3.0.3 / mysql-connector-j / lombok / jjwt 0.12.5 / jackson / brotli-dec。**无 devtools（无热重载）**。

### 5.2 构建 / 运行

```bash
# 后端（在 zhuochong/ 下）
./mvnw spring-boot:run        # 开发运行（Windows: mvnw.cmd spring-boot:run）
# 或用本机 Maven: mvn spring-boot:run

# 桌宠桌面壳（在 zhuochong-electron/ 下）
npm start

# 一键启动（zhuochong/一键启动.bat）会先起后端，15 秒后起 Electron
```

访问入口：`http://localhost:8080/桌宠管理.html`（管理后台）、`http://localhost:8080/登录.html`。

### 5.3 启动依赖顺序

1. MySQL 8（`db03` 库）需已在运行。
2. GPT-SoVITS（`tts.auto-start=true` 时由后端自动拉起 `api.py` @9880）；网易云 API（`netease.auto-start=true` 时自动拉起 @3000）。
3. 后端 8080。
4. Electron 加载 8080 的桌宠页。

---

## 6. 常见修改点（改什么 → 改哪里）

| 需求 | 涉及文件 |
|---|---|
| **新增后端接口** | 对应 `conroller/*.java` + **`config/WebConfig.java` 白名单**（忘加会被 401/403 拦） |
| **改 AI 人设/性格/回复风格** | `ai_prompt` 表（管理页可改）或 `DeepSeekService.chat` 里的硬编码 prompt（`:171-176`） |
| **新增/调整表情（PNG）** | 放 `static/aluona/`；若同时要 Live2D 生效，改 `live2d-fix-bridge.js` 的 `exprMap`（`:278`） |
| **新增/调整 Live2D 表情参数** | `static/live2d/expressions/*.exp3.json` + `exprMap` |
| **调 AI 情绪分类可选表情/音效/动作词表** | `DeepSeekService.classifyEmotion` 的 prompt（`:261-270`）+ `AiController.seedRules` prompt（`:403-415`） |
| **改 TTS 音色/语速/采样** | `ai_config` 表（管理页「AI 设置」）或 `TtsService.doSynthesize` 参数（`:130-142`） |
| **改弹幕房间 / 直播开关** | `ai_config.room_id/live_mode/danmaku_enabled`（管理页或 `/ai/config`） |
| **改 Electron 窗口行为/快捷键** | `zhuochong-electron/main.js` |
| **改桌宠 UI/交互逻辑** | `static/桌宠桌面版.html`（运行时）或 `static/JS/桌宠管理.js`（后台） |
| **改 PNG 动作动画** | `static/CSS/桌宠管理.css` 的 `@keyframes` |
| **Live2D 生动化** | 见第 8 节专题 |

---

## 7. 注意事项 / 已知坑点（★ 每次开发前必读，持续更新）

1. **★ 新接口必须加拦截器白名单**：`WebConfig.addInterceptors` 的 `excludePathPatterns` 是唯一放行列表，路径匹配不到的新接口会被 `Interceptor` 拦下（无 token → 401）。白名单里 `/ai/**`、`/talk`、`/reaction`、`/idle`、`/conversation/**`、`/choice/**`、`/effects`、`/basicinfo`、`/basic-responses`、`/seed-game-expressions` 及所有静态资源/HTML 已放行；其它路径（如 `/login` 之外的新接口）都要记得加。
2. **员工身份全局只读**：`Interceptor.java:35` 对所有 `员工` 身份的 POST/PUT/DELETE 直接 403，即使加了白名单也写不了。给员工开写权限要改这里。
3. **包名拼写错误**：`com.cy.conroller`（应为 controller）、`com.cy.Interceptor`（大写 I）、`JWTUTILL`（双 L）。新建类时沿用或注意引用，别写「正确」拼写导致 import 不到。
4. **数据库连接硬编码**：不在 yml，在 `MyBatisConfig.java`。换库/改密码要改代码重编译。
5. **外部服务路径硬编码**：`application.yml` 的 `tts.python/api-dir`、`netease.node/app` 是本机绝对路径；换机器/换目录必改。
6. **文件系统扫描依赖 `user.dir`**：`AiController` 的 `listModels()`（`GPT_weights_v2`/`SoVITS_weights_v2`）和 `scanMusicFiles()`（`music/`）用 `System.getProperty("user.dir") + "/src/main/resources/static"` 直接扫源码目录；`TtsService` 缓存目录是 `user.dir/tts_cache`。**必须从 `zhuochong/` 根目录启动**，打成 jar 部署时这些路径会失效（jar 内没有 `src/`）。
7. **静态资源需要 rebuild 才生效（无热重载）**：前端 HTML/JS/CSS 由 Spring 从 classpath（`target/classes/static`）托管。改 `src/main/resources/static/**` 后要执行资源拷贝（IDEA 里 Rebuild / `mvnw process-resources` 或重新 `spring-boot:run`），再刷新 Electron 窗口（可托盘「显示/隐藏」或重开）才看到新代码。**Java 改动必须重编译重启后端。**
8. **TTS 缓存不会自动失效**：`tts_cache/` 按「去括号后文本」的 md5 缓存 base64 音频。改了 `refer_wav`、音色模型、语速后，旧文本仍命中旧缓存；要重录需删 `tts_cache/` 对应文件或整个目录。 **（已修好，2026-09-13）**：缓存键现在是 `v<变体>|<音色指纹>|<文本>`，指纹含实际参考音频（含语境参考）+参考文本+模型+语速+sample_steps+参考音频的大小与修改时间，所以换音色/模型/语速会自动走新合成；另有 `POST /ai/tts/cache/clear` 与设置页「🧹 清空语音缓存」可强制全清。
9. **已移除的 PNG 素材**：`aluona_nanguo.png`、`aluona_xianqi.png` 已从项目移除、不再调用；原残留引用已全部替换为 `aluona_huaiyi.png`（`桌宠桌面版.html`、`桌宠管理.js`，以及 `live2d-fix-bridge.js` 的 `exprMap` 中删除了 `aluona_xianqi` 键）。
10. **弹幕只捕获 `/` 开头的文本**：`DanmakuService.pollDanmaku()`（`:178`）只转发以 `/` 开头的弹幕（去掉 `/` 后推送）；WebSocket 路径只解析 `INTERACT_WORD`（进入直播间），**没有解析 `DANMU_MSG`（普通弹幕）**。若要让 AI 回应普通弹幕文本，需要补 DANMU_MSG 解析（可参考 `danmaku_test.py:107-108`）。
11. **Live2D 模型文件名带时间戳且硬编码**：`live2d-fix-bridge.js:_init()`（`:339`）、`live2d-bridge.js`、`桌宠live2d测试.html` 都写死了 `阿洛娜4_backup2025_0815_2142_backup2025_1017_2227.model3.json`。换模型文件要同步改这些引用。
12. **`桌宠live2d测试.html` 的 cubismcore 走 CDN**，主页面 `桌宠桌面版.html` 走本地 `live2dcubismcore-v4.min.js`；离线调试用主页面。
13. **`Result.reaction` 未显式设 `code` 的坑**：所有构造器都设了 code，注意 `reactionFail` 返回 code=0（前端当失败处理）。
14. **`conversation`/`conversation_choice` 自增 id 重整**：`ConversationService_impl` 每次增删会 `reorderIds()`+`resetAutoIncrement()`，多线程/并发下可能有边界问题，改这里要小心。
15. **`soundeffects/list` 用 classpath 扫描、`motions/aluona` 同理**，而音乐/模型用文件系统扫描——两套资源读取方式并存，新增静态资源时注意对应接口用哪种。
16. **没有统一异常处理 / 无 CORS 配置**：接口异常常被 `catch(Exception){}` 吞掉，排查时先看控制台 `[xxx]` 前缀日志（`[AI]`/`[TTS]`/`[弹幕]`/`[L2D]` 等）。前端同源访问，暂无跨域问题。
17. **★ 所有 `.bat` 必须 CRLF 行尾 + 中文用 GBK 编码**：cmd.exe 按「CRLF = 2 字节」推算文件内偏移，纯 LF 的批处理会**读错位**——把行尾的半个词当成命令执行，报一堆 `'xxx' 不是内部或外部命令`（`启动DSH-Web.bat` 就这么翻车过一次，表现为「未找到 DSH」）。写批处理一律用 `[System.IO.File]::WriteAllBytes($p, [Text.Encoding]::GetEncoding(936).GetBytes($text))`，并在开头加 `chcp 936 >nul`；改完务必回读确认真有 `$crlf -gt 0` 且裸 LF 为 0。
18. **`.ps1` 必须带 UTF-8 BOM**：终端里跑的是 Windows PowerShell 5.1，无 BOM 的 UTF-8 脚本会被按 GBK 解释，中文注释打乱后可能吃掉引号导致整个脚本语法错误（`图标/生成图标.ps1` 踩过）。用 `write` 工具改过 `.ps1` 后记得补回 BOM。
19. **桌面快捷方式图标换图不生效**：Explorer 的图标缓存按「图标文件路径」做键，同一路径换内容后它常继续用旧图（按 F5 也不变）。换图时把新 ICO 写到**新路径**（本项目统一放 `图标/ico/`）再重指快捷方式，必要时执行 `ie4uinit.exe -show` 重建缓存。图标本体也别图省事全用 PNG 压缩条目——Windows 只对 256 尺寸官方支持 PNG 内嵌，小尺寸要用 DIB(BMP)。
20. **Electron 单实例 + 拉起「有界面的命令行」两个坑**（`zhuochong-electron/main.js`）：
    - 重复启动：用 `app.requestSingleInstanceLock()`，抢不到锁的进程要 `app.quit(); return;`（CommonJS 模块顶层 `return` 合法），**必须 return**，否则后面的 `app.whenReady()` 还会继续建窗口；提醒由已在运行的实例在 `second-instance` 事件里弹。
    - 拉起带日志窗口的后端：**不要** `spawn('启动后端.bat', { detached: true })` —— libuv 会给这种子进程加 `DETACHED_PROCESS`，子进程没有控制台，日志窗口根本不会出现（表现为「一键启动后端没反应」）。正确写法是 `spawn('powershell.exe', ['-NoProfile','-WindowStyle','Hidden','-Command', "Start-Process -FilePath '<bat>' -WorkingDirectory '<dir>'"])`，由 ShellExecute 开出独立窗口。
    - 后端存活探测用 `127.0.0.1` 而不是 `localhost`：Node 可能先解析到 IPv6 `::1`，而 Spring Boot 只监听 IPv4；判定口径是「有任意 HTTP 响应（含 401/403）即算活着」，只有连不上才算没启动。
    - **原生 dialog 会被置顶窗口盖住**：桌宠窗口是 `alwaysOnTop: true, 'screen-saver'` 的全屏透明层，`dialog.showMessageBox` 默认会出现在它**后面**，用户点不到按钮。要么走 `safeDialog()`（弹之前 `setAlwaysOnTop(false)`、关掉后恢复），要么干脆把交互做进窗口内的页面里。
    - **阻塞式提示别占用全屏置顶窗口**：最早的「等待后端」页把整屏都盖住了，既挡住桌面又没有关闭入口。现在做法是等待期间把窗口 `setBounds` 缩成小卡片（470×210、居中偏上、`-webkit-app-region: drag` 可拖、右上角 ✕ = `win.hide()`），按钮做在卡片页面里、通过 preload 的 `backend-action` / `backend-status` 两个 IPC 通道与主进程通信；后端就绪后再 `setBounds(fullBounds)` 还原成全屏覆盖层。
21. **PowerShell 里拼数组别inline用 `+`**：`@('echo x > "' + $p + '"')` 会因为**逗号优先级高于加号**被拆成三个元素，生成的 `.bat` 直接变成坏文件（表现为 cmd 报「找不到指定的路径 / '"' 不是内部或外部命令」）。要么每行先赋值再放进数组，要么用 here-string。
22. **等待卡片页改动要三个文件同步**：卡片是独立的本地页面 `zhuochong-electron/等待后端.html`（`win.loadFile` 加载），按钮与状态靠 `zhuochong-electron/preload.js` 暴露的 `backendAction/onBackendStatus`，主进程在 `main.js` 的 `ipcMain.on('backend-action')` 里处理。加按钮要同时改这三处。
23. **★ 区域点击穿透的「恢复」不能只靠 mousemove 转发**：桌宠页 `updatePass()`（`桌宠桌面版.html` 约 1711 行）的模型是「鼠标不在桌宠区域 → 整窗点击穿透；移回桌宠区域 → 恢复捕获」。但**恢复那一步完全依赖 Electron 的 `setIgnoreMouseEvents(true,{forward:true})` 把 mousemove 转发进页面**，这条转发实测会失效——一旦失效，窗口就永久停在整窗穿透上：对话框 / 模型 / 右下角按钮 / 眼部跟踪全部没反应，只有 Ctrl+Shift+Z 能救回来（它直接 `setIgnoreMouse(false)`）。现在加了不依赖转发的兜底：
    - 主进程每 100ms 读 `screen.getCursorScreenPoint()`（DIP，减掉窗口原点换成页面 client 坐标）推 `pet:cursor`；`isLocked`（用户锁定 / 麻将 `/lock`）或等待卡片显示期间不推。
    - 页面收到后拿真实坐标重新调 `updatePass()` 恢复捕获，并打 `[PASS] 兜底恢复捕获` 日志（主进程侧日志是 `[MAIN] 区域穿透 -> true/false`）。
    - **穿透通道必须区分开**：区域穿透走新 IPC `set-pass-through`（主进程记作 `passThrough`），用户锁定 / 麻将 `/lock` 走原来的 `set-ignore-mouse`（主进程记作 `isLocked`）；主进程下发时用 `isLocked || passThrough`，否则兜底会把锁定状态顶掉（麻将自动打牌时鼠标操控会被桌宠吃掉）。
    - 页面 `updatePass()` 里也加了「锁定时直接 return」的守卫：锁定时穿透状态只由锁定逻辑管，避免两套逻辑互相打架。
24. **★ 主进程 console.log 一律写 ASCII**：桌宠是用 `start.bat` → `npm start` 拉起的，那个控制台窗口是中文 Windows 的 GBK 码页(936)，而 Electron 往 stdout 写的是 UTF-8 字节 —— 中文日志必然显示成「`[MAIN] 鍖哄煙绌块€?-> true`」这种乱码。要中文就只放在页面里（DevTools 控制台是 UTF-8，`[PASS] 兜底恢复捕获` 那种），主进程侧全部用英文；排查穿透/光标问题时把 `main.js` 顶部的 `DEBUG_PASS` 打开（默认关，否则和桌宠交互一次就刷两行）。
25. **★ 鼠标跟踪会被「点击穿透」冻住（视线跳变的根因）**：`follow` 模式的眼球/头部目标完全由 `_mx/_my` 算出，而 `_mx/_my` 只在 `mousemove` 时更新。窗口一旦进入整窗穿透，页面就（不可靠地）收不到 `mousemove` → 坐标冻结 → 视线「突然丢」；等鼠标回到桌宠上时又用新坐标硬追 → **眼部参数跳变**。现在三条一起解决：
    - 主进程兜底轮询的 `pet:cursor` 里多带一个 `focused`，页面据此调 `l2dBridge.setCursor(x,y)`（穿透状态下也能持续喂坐标，跟踪不断）与 `setCursorFocus(on)`；
    - `_lookTick` 的 follow 分支加了「平滑重新捕获」：坐标断档（>300ms）后重新收到位置时，用与「空闲随机视线」完全相同的 `_easeInOut` 缓动滑过去（0.32~0.94s，距离越远越久），不再指数硬追；`followCursor()` 进入跟踪时也先 `markCursorStale()`，保证进入跟踪这一步也是缓动；
    - **断档判定必须在更新时间戳之前**（先算 `Date.now() - _mxT` 再写 `_mxT`），否则「恢复后的第一帧」永远不算断档，过渡就永远不触发（踩过）。
    - 规则：老师还没去点桌面（窗口仍有焦点）→ 一直保持跟踪，包括鼠标移出桌宠区域之后；点了桌面（窗口失焦）→ 冻结视线并标记断档，回来时走缓动，不跳。
26. **★ 区域穿越的两个「手感」约定（都踩过坑）**：
    - **规则改成「收得住，才放得开」**：鼠标离开桌宠区域**不立刻**放开捕获（旧行为会让视线跟随断掉、状态看着像丢了），只有在桌宠区域外**单击一下**才放开。实现：`mousedown` 落在空白处时记 `outsideClickCandidate = !isPetZone(...)`，`mousemove` 超过阈值就 `dragMoved = true` 取消候选（保证还能拖窗口），`mouseup` 时「候选 && 没拖」→ `releasePassThrough()`。代价：放开那一次点击被桌宠窗口吃掉（老师确认接受）。恢复捕获仍是位置驱动：`updatePass` 只在鼠标回到桌宠区域时收回。
    - **「回到看着老师」也要缓动**：原来用每帧 `×0.93` 的指数衰减，约 0.3 秒就把眼睛从逛视位置拉回正中——鼠标进入桌宠区触发 hover 反应、桌宠开口说话（`gazeOnUser → holdGaze`）时看起来就是「猛地甩过去」。现在归位过渡与逛视共用同一套 `_easeInOut`（0.38~0.9s，`_backActive/_backFrom/_backDur`）。排查用 `l2dBridge._dbgGaze = true`，会打印 `[L2D] 归位过渡 / 跟随重新捕获` 的时长。
27. **★ 隐藏窗口启动（不闪命令提示符）用 VBS，不要用 PowerShell**：`WshShell.Run("cmd /c xxx >> log 2>&1", 0, False)` 的窗口样式 `0` 才真正「无窗口」，而且 WSH 起来时连一帧控制台都不闪；`powershell -WindowStyle Hidden` 会先建控制台再隐藏，实测偶尔闪一下。注意几点：
    - `.vbs` 要存成 **GBK(ANSI) + CRLF**（跟 `.bat` 一个道理）；`sh.CurrentDirectory = dir` 配相对路径（`启动后端.bat >> logs\backend.log`），这样路径里有空格也不会被拆开；
    - 窗口藏起来后日志只能看文件：`zhuochong-electron\logs\electron.log` 与 `logs\backend.log`（VBS 里做了 4MB 上限，启动时超了就删），托盘菜单加了「📂 打开日志目录」；
    - 想看着窗口调试就手动双击 `启动后端.bat` / `start.bat`（这两个保持可见输出，不参与隐藏流程）；
    - 桌面快捷方式「桌面桌宠」指向 `启动桌宠.vbs`（隐藏），`start.bat` 保留作调试入口。
28. **★ 往 `static/` 下新增「运行期写入」的目录，必须配 WebConfig 文件系统直出**：classpath 里的 `static/**` 是**打包时拷进 `target/classes`** 的（坑点 7），所以 Java 往 `src/main/resources/static/xxx` 写文件后，HTTP 访问 `/xxx/...` **看不到**，要 rebuild 才生效。正确做法（照 `麻将错题截屏` 抄）：`registry.addResourceHandler("/目录/**").addResourceLocations("file:" + 绝对路径 + "/")`，**同时**把 `/目录/**` 加进 `Interceptor` 白名单。「区域标注工具」导入的图片就走这条：落到 `static/标注图片/`，导入即可用、免 rebuild。
29. **★ 基础设置窗口是「无边框透明 + 背景图」窗口，改它要三处一起改**：
    - `main.js`：`settingsWindowOptions()`（`frame:false` + `transparent:true` + `resizable:false` + `useContentSize:true`，尺寸按背景图 **1024×1024 正方形**、按屏幕可用高度自适应并居中）。窗口有两个入口，**都要走同一个函数**：托盘菜单 `基础设置` → `openPetSettings()`；页面里 `window.open('桌宠设置.html', ...)` → `web-contents-created` 里判断 URL 后 `overrideBrowserWindowOptions`（否则 window.open 出来的还是带系统标题栏的普通窗口）。
    - `桌宠设置.html`：`body` 铺 `background/login_beijing.png` 且 `background-size:100% 100%`（窗口是正方形，所以不会变形）；`.app` 用**绝对定位百分比**落在图上标注出的「内容区域」（left 11.035% / top 30.566% / width 74.805% / height 40.430%）。**换图或重新标注后这几个百分比要同步改**。标题行 `#titleRow` 在窗口最顶部那条带（图上该区域本来就是全透明）背景透明 + `-webkit-app-region: drag`，`─ ✕` 自绘并走 `close-window` / `minimize-window` IPC（`main.js` 里用 `BrowserWindow.fromWebContents(event.sender)` 处理，别写死主窗口 `win`）。
    - **不许出现左右滑动条**：`.page` 的 `max-width` 必须是 `100%`（原来是固定 `880px`，窗口变窄后会横向溢出），`table.tbl` 要 `table-layout: fixed` + `overflow-wrap: anywhere`，`.content` 用 `overflow-x: hidden` 只留纵向滚动。
    - **动画的「屏幕外」要用 `display.bounds`（整块屏幕，含任务栏区），不要用 `workArea`**：workArea 不含任务栏，按它算的话窗口会停在任务栏那一带 = 没真正出屏/开始就在屏内。窗口位置记忆写在 `userData/settings-window.json`（`{scale,x,y}`），拖窗时防抖保存、**动画期间不记录**，还原时 `clampSettingsPos()` 夹回屏幕内（至少留 120px 可见）。
    - 背景图是亮色（内容区 RGB≈209~254），所以面板用深色半透明 `rgba(12,14,20,.85)` 保证浅色文字可读；想多露出图片就调这个透明度。
30. **★ 新增静态页面必须登记进 `WebConfig` 的 `excludePathPatterns` 白名单**：JWT 拦截器是 `addPathPatterns("/**")`，会连静态页一起拦；没登记的页面直接 **401**（本次画板页 `你画我猜.html` 实测：浏览器地址栏打开 401，Electron 画板窗口里就显示一张「未授权」页 —— 现象很像"窗口没加载出页面"，其实是鉴权拦了）。中文文件名原样写即可（`"/你画我猜.html"`）。运行期写入的目录另需 `addResourceHandlers` 文件系统直出（见第 28 条）。
31. **★ 两个小坑（本次都踩了）**：
    - 种子数据的**字段分隔符不能和「多值字段」的内部符号撞**：词库按 `词|别名|类别|难度` 播种时，别名本身是 `|` 分隔的多值（`猫|小猫|猫咪`），`split("\\|")` 就把类别/难度整体挤错位（难度统计变成 48/32/20、类别里冒出"小白兔"）；改成 `;` 分字段后正常。**约定：多值字段内部用 `|`，种子行字段之间用 `;`。**
    - PowerShell 5.1 的 `Invoke-WebRequest` 对**没有 charset 的 `application/json`** 会按 Latin-1 解码 → 汉字全乱、字符串比较必失败（自测脚本因此把题目「房子」判成"不可画"、22 次都没触发猜对分支）；自测脚本统一改用 `WebClient` + `Encoding = UTF8` 读写。浏览器里的 `fetch`/axios 不受影响（JSON 固定按 UTF-8 解析）。

---

## 8. Live2D 生动化优化专题（下一步主方向）

### 8.1 当前已实现的「生动」机制（都在 `live2d/live2d-fix-bridge.js`）

| 机制 | 实现位置 | 现状/可优化点 |
|---|---|---|
| 呼吸 | `_startIdleAnim` → `ParamBreath` 正弦（`:357-361`） | 频率/幅度硬编码（`0.05` 步进、50ms 定时），可参数化。 |
| 眨眼 | `_startIdleAnim` → `ParamEyeLOpen/Ropen`（`:364-381`） | 直接 0/1 硬切，无过渡；间隔 3~6s、闭眼 120ms 硬编码。可改平滑过渡 + 更自然的随机分布。 |
| 唇形同步 | `桌宠桌面版.html` 的 `lipTick()` → `ParamMouthOpenY`（`:187-198`） | 基于 TTS 音频 RMS 驱动；注意模型嘴部参数可能是 `ParamMouthOpenY` 或 `Param10`（见坑点 18），需按模型实际参数校准。 |
| 表情 | `exprMap`（`:278`）+ `expressions/*.exp3.json` + `setExpression` | 表情切换是「置 0 旧参数 + 设新参数」，无渐变过渡，切换可能突兀；可加参数平滑插值。 |
| 整体动作 | `playAction`（`:120-194`） | bounce/drop/swing/spin/spin2/spin3/tilt/escape，时长与曲线硬编码；可加更多动作、缓动函数、与语音时长的联动。 |
| 睡眠 motion | `playMotion('Sleep', 0)` + `stopMotion` | 使用 `水饺.motion3.json`；`idle.motion3.json` 未被引用，可作为闲置循环动作接入。 |
| 物理/角度 | 模型自带 `*.physics3.json`、`cdi3.json` | 由 pixi-live2d-display 自动处理，`autoInteract:false` 关闭了自动交互（点击追踪/视线跟随）。 |

### 8.2 生动化可做的方向（建议优先级）

1. **接入闲置循环 motion**：把 `live2d/idle.motion3.json` 挂到 model3.json 或代码里作为 idle 循环，取代纯静态站立。
2. **视线跟随鼠标**：`autoInteract:false` 关闭了视线/身体朝向追踪；可开启或手动实现（`ParamAngleX/Y`、`ParamBodyAngleX/Y`、`ParamEyeBallX/Y`），配合 `hitTest` 做注视感。
3. **表情/动作平滑过渡**：为 `setExpression`/`playAction`/`setParam` 加缓动插值（当前是瞬时跳变），显著提升「活」的感觉。
4. **更自然的眨眼/呼吸**：眨眼改 0→1 平滑过渡（如 80ms 缓动），呼吸幅度随状态（兴奋/睡觉）变化。
5. **动作与语音联动**：`playAction` 时长目前固定，可让它匹配 TTS 音频时长（如说话时轻晃身体）。
6. **唇形校准**：确认模型嘴部参数 ID（用 `listParams()` 在控制台打印），统一 `ParamMouthOpenY` 与 `Param10` 的使用。

### 8.3 生动化相关文件地图

- 模型加载/定位/缩放：`live2d-fix-bridge.js:_init`（`:334-349`）。
- 表情映射与文件：`live2d-fix-bridge.js:exprMap` + `live2d/expressions/*`。
- 动作曲线：`live2d-fix-bridge.js:playAction`。
- 呼吸/眨眼：`live2d-fix-bridge.js:_startIdleAnim`。
- 嘴型：`桌宠桌面版.html`（`initLipSync`/`lipTick`/`startLipSync`/`stopLipSync`）。
- 触发入口：`桌宠桌面版.html:playEffects`（动作/音效）与 `showResult`（sleep/句级表情流）。
- 调试工具：对话输入 `参数`（列参数）、`表情 名`、`组合 A B`、`中心点 x y`、`测N`、`口大/口小`、动作词（蹦跳/歪头…）。

### 8.4 生动化开发的坑点预告

18. **嘴部参数 ID 待确认**：代码里唇形写 `ParamMouthOpenY`，但 `expressions/正常微笑.exp3.json`、`撅嘴.exp3.json`、`嘴变形*` 用的是 `Param10`，且测试命令「口大/口小」也设 `Param10`。改唇形前先 `listParams()` 确认真实 ID，避免改了无效参数。
19. **表情文件与 exprMap 键名不一致会静默失效**：`exprMap` 的 value 必须与 `expressions/` 里 `.exp3.json` 的文件名（去扩展名）完全一致（含中文），否则 `fetch` 404 后该表情静默失效（`:305-309` 吞异常）。
20. **动作/表情在 PNG 与 Live2D 两套逻辑并存**：改 Live2D 生动化时别破坏 PNG 兜底模式（`useLive2D=false` 时走 CSS 动画 + `petImg.src`）。
21. **部分表情测试命令引用了不存在的 exp 文件**：`桌宠桌面版.html` 的 `expNames` 列表（`:1153`）含 `挤眼/螺旋/嘴变形/嘴变形2/眯眼`，`张嘴/闭嘴` 也调 `mouthTest('嘴变形2'/'嘴变形')`，但 `expressions/` 目录里**没有这 5 个文件**（只有 `激动闭眼/眯眼舒适/正常微笑` 等 17 个）。执行这些测试命令会 `fetch` 404 静默失败。补表情文件或改测试命令时注意。
22. **未使用的表情文件（模型素材缺失，暂保留）**：`expressions/` 里 `猫猫`/`墨镜`/`牛奶`/`抓抓` 4 个 `.exp3.json` 因对应模型素材缺失暂未接入生产表情系统 `exprMap`（`live2d-fix-bridge.js:278`），当前不调用；保留在 `桌宠桌面版.html:1153` 的测试命令 `expNames` 列表里，待素材补全后再接入。

---

## 9. 响应风格优化方案（蓝图 v1）

> 目标：让桌宠响应文本向《蔚蓝档案》阿罗娜的对话风格靠齐。纯方案，分阶段实施，与用户确认后再落地。

### 决策记录（已确认）
- **D1 内心OS 发声**：同音色朗读 + 视觉区分（括号/文字变暗/OS角标 + 表情切换）；架构预留 `refer_wav` 口子，日后有轻声素材可无损升级气声。
- **D2 语料注入**：关键词标签 + SQL 匹配（检索式注入 v1），后续可升级语义检索（embedding）。
- **D3 歌单翻页**：翻页不朗读、不打字机；`←→` 只在确实分页时显示。
- **D4 结构化节拍**：不让 AI 直接出完整数组（AI 会惰性：不分句/情绪变化小/常用默认表情），改「AI 出轻协议 → 后端组装节拍数组」。

### 核心架构（三层职责分离）
- **AI（创意层）**：文本 + 粗情绪标签(≈10 个：开心/困惑/激动/害羞/生气/认真/期待/怀疑/无语/难过) + 符号区分 `【内心OS】`/`（动作）`。
- **后端（确定性层）**：切句 / 标签→表情池映射 / 表情轮换冷却 / OS·动作分类 / 组装节拍数组。
- **前端（展示层）**：日常 = 3 行分段多气泡；功能列表 = 分页 `←→`。

### 分模块落点
1. **换行治理**：`.dialog-text` 的 `white-space: pre-line` → `normal`；段内 `\n` 归并空格，仅 `\n\n` 作气泡边界；同步改 `DeepSeekService.chat()` 里「超30字用两个换行分段」提示词（`:174`）。
2. **双层展示**：日常 ≤3 行、超出「一段说完 → 下一段」；功能列表（点歌/搜歌/曲库）`boat_text` 置空只回 `data`，前端分页（每页 5-8 条，条目对齐，`←→` 仅多页时显示）。
3. **AI 轻协议**：情绪只给粗标签，表情名/切句一律不给 AI。
4. **后端组装节拍**：标签→表情池（1 标签对多表情，轮换 + 相邻句不重复），规则库仅兜底。
5. **风格对齐**：system 设定 1319 字 → 压缩 ~200 字标签化 + 节拍模板（反应句→逞强句→内心OS）+ 用户画像「老师=能力强、值得信赖但不善表达的大人」。
6. **风格语料库**：新表 + 管理后台批量粘贴导入，每条打触发标签，SQL 匹配召回 2-4 条做 few-shot。

### 实施分期
- **阶段一（快见效）**：换行治理 + 双层展示（3 行分段 + 歌单分页 + 搜歌去文本化）。✅ 已完成
- **阶段二（prompt 层）**：精简设定 + 节拍模板 + 用户画像。✅ 已完成（system 提示词已替换，见 changelog）
- **阶段三（确定性组装）**：AI 轻协议 + 标签→表情池 + 多样性轮换 + 内心OS 视觉区分。✅ 已完成（做法 A：AI 情绪词→1:1 表情映射 + 相邻不重复 + 内心OS 发声；见 changelog）
- **阶段四（语料库）**：语料表 + 导入页 + 关键词检索注入。✅ 已完成（`style_exemplars` 表 + 管理后台「风格语料库」面板 + 标签关键词召回注入；见 changelog）

### 阶段一实现细节（已落地）
- **换行治理**：`桌宠桌面版.html` 内联样式加 `#dialogText { white-space: normal; word-break: break-word; overflow-wrap: break-word; }`（不再因 `\n` 硬换行）；新增 `stripNewlines()` 在 `typewriterText`/`typewriterAppend` 入口去掉 `\r\n`；`DeepSeekService.java:174` 提示词改为「不要插入换行符断句/分段，连续输出，系统自动换行分段」。
- **3 行分段 + 自动连续**：新增 `measureLines()`（隐藏测量元素，用 `scrollHeight/lineHeight` 实测文本在当前对话框宽度下自然折成几行；宽度未知时按每行约15字兜底）；`playSentenceFlow` 重写为「按句累积，实测『拼接后整段文本』的折行行数 ≤ 3 才放当前气泡，否则整句移下一气泡，气泡内逐句切表情 + TTS，气泡间清空并自动连续输出」。规则：一句 1 行可占满三句；长句自然折行不得把气泡撑到第 4 行（会整句移下一气泡）。
- **句末标点修正**：`splitToSentences` 切句字符从 `。！？!?；;…～~` 改为 `。！？!?；;`（去掉 `…～~`，它们不是句末而是语气/犹豫符，旧逻辑会把「午睡吗～那阿罗娜…」拦腰切错）。
- **搜歌/曲库去文本化 + 歌单分页**：`handleLocalCommand` 的「网易云点歌」与「曲库/推荐」移除 `quickSay` 文本列表与 `choiceTextMode` 分支，只调 `renderSongChoices()`；新增 `songPage`/`SONG_PAGE_SIZE=5`/`renderSongChoices`/`songPagePrev`/`songPageNext`，`#dialogBox` 内新增 `#dialogPager`（◀ 页码 ▶，仅多页时显示）；`doChoice` 的 `netease_play` 分支改用 `choice.song`（分页后页内索引 ≠ 全量索引）。
- **注意**：单句超长（>3 行无标点）仍会自然折行不强制切（病态输入，靠 prompt 约束短句）；「序号选歌」仍按全量列表 1-8，未与分页联动（遗留小项）。

---

## 10. 游戏操控专题（蓝图）

> 目标：让桌宠/智能体获得"看屏幕 → 理解 → 操控"能力，最终能玩回合制游戏（扫雷/贪吃蛇 → 月圆之夜/杀戮尖塔2 这类 roguelike 卡牌）。分阶段推进，阶段一先跑通"桌面视觉"。

### 阶段一：桌面视觉（看屏幕 + 理解 + 回应）
- **能力**：桌宠截屏 → 视觉模型理解 → 以角色口吻回应。
- **两步法**：视觉模型 `deepseek-v4-flash-vision-exp`（DeepSeek 已上线，OpenAI 兼容、可传图）负责"读盘面/描述"；文本模型（现有 `chat()`）负责"以阿罗娜人设回应"，复用阶段二~四的风格成果。
- **两种触发**：
  1. 闲置 idle（实时监测）：2 分钟无调用时，按概率截屏 + 视觉理解 + 评论"老师在做什么"（比凭空编活动更真实）。
  2. 对话触发（按需看）：用户说"看得出来我在做什么吗/看看我在干嘛"等 → 本地命令识别 → 截屏 → 回应。
- **截屏**：`java.awt.Robot.createScreenCapture()`，JDK 自带、无需新软件/新进程。
- **落点**：`DeepSeekService` 新增 `chatVision(imageBase64, prompt)`；新增后端接口（如 `/ai/desktop-vision`）；前端仅 `showResult`。
- **注意**：截图需降采样/压缩（缩到约 800px 宽 JPEG）；截图会发 DeepSeek，需隐私开关与提示；识别失败给"看不懂"兜底。

### 待确认决策（见实现前询问）
- 隐私开关默认值；截图范围（整个屏幕 vs 前台活动窗口）。

### 后续阶段（规划）
- 阶段二：视觉 → 决策 → 键鼠操控（扫雷/贪吃蛇）。
- 阶段三：卡牌游戏（月圆之夜/杀戮尖塔2），需视觉读盘 + 游戏知识库 + 状态记忆。

---

## 11. 更新日志（后续开发实时在此追加）

> 格式建议：`YYYY-MM-DD | 变更/踩坑 | 影响文件 | 一句话结论`

- 2026-08-17 | 网易云放歌 + 闲置响应优化 | `桌宠桌面版.html`、`AiController.java`、`DeepSeekService.java` | ①推荐跨会话去重（新增 `playedHistory`，`/ai/music/ai-recommend` 传 `exclude` 让 AI 排除已播）②播放卡顿超 10s 自动重取直链续播（最多 2 次后切歌）③`getUrl` 失败标记 `bad` 并自动跳过 + 音乐链路 `[音乐]` 日志 ④听歌时闲置「音乐提示 / 活动提示」随机二选一，消除「哼歌+睡觉」同框冲突
- 2026-08-17 | 点歌文案修正 + Live2D 表情渐变 | `桌宠桌面版.html`、`live2d-fix-bridge.js` | ①网易云/曲库点歌按钮不再误报「正在播放」，改由 `add()` 输出「已加入队列/正在播放」正确文案 ②`setExpression` 增加 260ms easeInOutQuad 参数渐变（新增 `_getParam`/`_animateExprParams`），替换瞬时切换
- 2026-08-17 | 播放卡顿逻辑重写 | `桌宠桌面版.html` | 卡顿判定从「`waiting` 事件即时重连」改为「连续 12s 无播放进度（基于 `timeupdate`）才重连」；修复重连自身 `waiting` 事件把 `stallPos` 清零导致 seek 到 0 的 bug；新增 `recovering` 标志在重连期间忽略自身缓冲事件
- 2026-08-17 | 网易云音质降档测试 | 外部 `D:\NeteaseCloudMusicApi\app.js` | `/url` 默认音质由「exhigh→lossless→hires→standard 取最高」改为「`higher`(192k) 优先、回退 `standard`(128k)」，支持 `?level=` 覆盖；用于缓解播放频繁卡顿（若仍卡可降 `standard`）
- 2026-08-17 | 网易云音质可在基础设置调节 | `桌宠设置.html`、`桌宠桌面版.html`、`AiController.java`、`NeteaseService.java` | 设置页新增「网易云音质」下拉（standard/higher/exhigh/lossless，默认 higher），经 `localStorage.pet_settings.neteaseLevel` → `/ai/music/url?level=` → `NeteaseService.getPlayUrl(id,level)` → `localhost:3000/url?id&level` 透传到 app.js
- 2026-08-17 | 桌面版选项按钮改左侧竖向小按钮 | `桌宠桌面版.html` | `#choicesWrapper` 由居中改为 `left:6%/top:18%` 左侧竖向排列；`.choice-btn` 桌面覆盖为 200px 宽、15px 字号、最多 8 个（`renderChoices` 里 `slice(0,8)`）
- 2026-08-17 | 选项按钮按 Live2D 坐标动态定位 | `live2d-fix-bridge.js`、`桌宠桌面版.html` | 新增 `getBasePosition()`；`positionChoices()` 用模型基准左边缘计算按钮 `right`，贴到桌宠左侧（20px 间距），去掉 `max-height/overflow` 滚动条
- 2026-08-17 | 对话框单独隐藏 + 拖动改移动 | `zhuochong-electron/main.js`、`preload.js`、`桌宠桌面版.html` | 新增 `Ctrl+Shift+D` 快捷键与托盘菜单「隐藏/显示对话框」，只切 `#dialogBox` 不影响选项按钮；对话框去掉 `right/bottom` 锚定改为 `top` 锚定 + 固定 440px 宽，拖动即移动、内容多时向下增高（移除 resize-se）
- 2026-08-17 | Live2D 模型移动能力（X/Y 偏移） | `live2d-fix-bridge.js`、`桌宠桌面版.html`、`桌宠设置.html` | 新增 `_offsetX/_offsetY` 与 `setBaseOffset(ox,oy)`，`_getBase/getBasePosition/_init` 均叠加偏移；设置页新增「Live2D X/Y 偏移」输入，经 `localStorage.pet_settings.live2dX/Y` 应用，选项按钮随模型位置联动
- 2026-08-17 | 对话框宽度可拖边框调节 + 设置项 + 左右内边距 | `桌宠桌面版.html`、`桌宠设置.html` | 新增右侧 `.resize-e` 手柄实现宽度拖拽（`resize-w` 模式，松开持久化 `pet_settings.dialogWidth`）；设置页新增「对话框宽度」输入；`#dialogBox` 左右内边距调整为 22px
- 2026-08-17 | 对话框左侧边框拖拽调节内边距（左右对称） | `桌宠桌面版.html`、`桌宠设置.html` | 新增左侧 `.resize-w` 手柄（`resize-p` 模式）拖拽同步调节 `paddingLeft/paddingRight`（对称，上限 `(宽-120)/2`，松开持久化 `pet_settings.dialogPadding`）；设置页新增「对话框内边距」输入
- 2026-08-17 | 选项按钮布局编辑 + 按钮样式优化 | `桌宠桌面版.html` | 新增右下角 ⚙ 齿轮键切换编辑模式：进入后可拖动 `#choicesWrapper` 位置、拖 `.choices-resize` 拉按钮宽度（`--btn-w`），退出持久化 `pet_settings.choicesX/Y/BtnW`；按钮固定等宽、左右 `2em` 内边距、超长换行且各行居中
- 2026-08-17 | 选项按钮 1-8 数字键快捷选择 | `桌宠桌面版.html` | 选项区显示且未锁定时，按 `1`~`8` 对应第 1~8 个选项触发；输入框中/锁定时不拦截
- 2026-08-17 | 选项选中缓冲动画 + hover 放大 1.2 | `桌宠桌面版.html` | 鼠标/键盘选中统一走 `onChoiceClick`：加 `.selected`（`choiceSelect` 动画 从当前值→1.2(15%)→1.0(100%)，`ease-in-out`）+ `choiceBufferIdx` 防重复，缓冲后 `doChoice` 触发响应并关选项；`hover` 放大由 1.05 提到 1.2；缓冲时长统一为 `CHOICE_BUFFER_MS` 常量（1000ms），并写入 CSS 变量 `--choice-buffer`
- 2026-08-17 | 修复 Live2D 表情残留（舒服闭眼不消失） | `live2d-fix-bridge.js` | `setExpression` 改为每次复位所有表情参数（不依赖 `_prevExp`），再应用新表情；`stopMotion` 增加取消 `_exprAnimRaf` 与清空 `_prevExp`。根因：睡眠动作 `水饺.motion3.json` 为 `Loop:true` 且控制 `Param20`（与「眯眼舒适」同参数），旧逻辑只重置上一个表情参数，导致 `Param20` 残留
- 2026-08-17 | 游戏进行中屏蔽 hover 响应 | `桌宠桌面版.html` | PNG `mouseenter` 与 Live2D `mousemove` hover 分支增加 `if (game && game.active) return;`，避免游戏快捷选项按钮被 hover 响应清掉；click 响应不屏蔽
- 2026-08-17 | 网络天才游戏规则细化 | `桌宠桌面版.html`、`桌宠设置.html`、`user_operation.java`、`DeepSeekService.java`、`ConservationController.java` | ①游戏模型可切换（设置页「游戏模型」下拉 deepseek-reasoner/deepseek-chat，默认 reasoner），经 `pet_settings.gameModel` → `/talk` 的 `game_model` 字段 → `user_operation.game_model` → `DeepSeekService.chat(..., modelOverride)` 透传覆盖模型 ②快捷选项从「是/不是/不确定」改为「是/不是/不是但类似/不确定不知道」4 项 ③AI 出题走大方向提问+候选集合/排除清单结构化 hint（`gameContextSuffix` 里 `{type,content}`），每轮提问次数可配（命令 `网络天才 [简单|困难] [我出题|你出题] [N]`，N=每轮提问次数默认 20、上限 50，仅作用于 用户出题(ai_guess)）④去掉「猜错上限」，改为每轮结束弹「对/错 + 继续/公布答案」（`waitingContinue` 分支识别输入 是/继续 与 不是/公布答案；`endGame` 增加 `user_reveal` 分支）⑤「提醒：是/不是」解析后结构化存入当前 `game.hints`（不进 `pet_memory`）；「不确定不知道」不计数 ⑥user_guess（智能体出题）只把 AI 回答词汇同步到 4 个新选项，不加提问/计数/时长约束
- 2026-08-25 | 游戏「不确定不知道」兜底修复 + 防重复询问 | `DeepSeekService.java`、`桌宠桌面版.html` | ①根因：游戏默认 deepseek-reasoner，但 `chat()` 无条件带 `temperature`（reasoner 不支持）且读超时仅 120s，reasoner 推理慢/参数不兼容 → 请求失败 → `chat()` 返回 null → 落到 `unknown_text` 兜底「您在说什么呢，老师？」。修复：reasoner 时跳过 `temperature`、`max_tokens` 提到 8192（避免推理被截断致 content 空）、读超时 120s→300s ②防重复：新增 `cleanQuestion()`（只去开头动作括号+结尾标点，保留「是不是/吗」等提问词），`recordAiTurn` 记录问题改用它（旧 `cleanItem` 误删「是/是不是」前缀导致 asked 记录残缺）；`gameContextSuffix` 的已问清单改为带编号「1.… 2.…」并加「绝对禁止重复提问、逐个排除」；`startGame` 提示词第 3 条补「同一方向只问一次、换不同维度提问（用途/材质/大小/场景/来源/是否人造等）」
- 2026-08-25 | 响应风格阶段一：换行治理 + 3 行分段 + 歌单分页 | `桌宠桌面版.html`、`DeepSeekService.java` | ①换行治理：`#dialogText` 改 `white-space: normal` + 新增 `stripNewlines()`（打字机入口去 `\r\n`），`DeepSeekService` 提示词去掉「超30字用两个换行分段」改为「不插换行、连续输出」 ②3 行分段：新增 `measureLines()`（隐藏元素 + scrollHeight/lineHeight 实测句占行数），`playSentenceFlow` 重写为「按句打包成 ≤3 行气泡（已占行数+句行数≤3 才放入，否则整句移下一气泡），气泡间清空自动连续输出」 ③歌单分页：搜歌/曲库去文本化（移除 quickSay 列表与 choiceTextMode 分支），新增 `renderSongChoices`/`songPagePrev`/`songPageNext` + `#dialogPager`（◀ 页码 ▶，仅多页时显示，翻页不朗读），`doChoice` 的 `netease_play` 改 `choice.song`
- 2026-08-26 | 分行修正：句末标点 + 拼接测行 | `ConservationController.java`、`桌宠桌面版.html` | ①`splitToSentences` 切句字符去掉 `…～~`（它们不是句末，旧逻辑把「午睡吗～那阿罗娜…」拦腰切错） ②`playSentenceFlow` 打包从「累加各句行数」改为「实测拼接后整段文本行数 ≤3」（累加会高估，句子拼接时共享一行）；`measureLines` 增加宽度为 0 时按每行 15 字兜底
- 2026-08-26 | 分行修复：measureLines 改字符估算 | `桌宠桌面版.html` | `measureLines` 原先用「隐藏元素 scrollHeight/lineHeight」实测行数，在 Electron 环境返回偏小导致 3 行气泡不切分（文本仍自然折成 4 行）。改为按 CJK 全角字符宽度估算：`ceil(字符数 / floor(对话框宽度/字号))`，实时跟随对话框宽度，稳定可靠
- 2026-08-26 | 分行修复：3 行拆分按参考宽度（上限 440px） | `桌宠桌面版.html` | `measureLines` 的参考宽度改为 `min(实际宽度, 440)`。根因：统计时若对话框被拉宽到 1000px+，每行可容纳字数变大，长文本就不超过 3 行，导致 3 行拆分永不触发。改为按默认对话框宽度（440px 上限）判定行数，即便对话框被拉宽，长响应也会正常切成多气泡
- 2026-08-26 | 分行：改回实际宽度 + 句子整句换行不拆词 | `桌宠桌面版.html` | ①撤掉上一条「440px 上限」，改回按对话框实际宽度算行容量（避免宽框下过度切分成"每句话一个气泡"）②新增 `typewriterAppendSentence()`：逐句作为独立 `<span>`，短句(≤1行)用 `white-space: nowrap` 让其整句换行、不拆「不过/保证」这类词，长句(>1行)用 `normal` 内部折行；`playSentenceFlow` 两个路径改用它，`playSpeech` 流式仍用原 `typewriterAppend`
- 2026-08-26 | 打字机预排版 + 对话框固定三行高度 | `桌宠桌面版.html` | ①新增 `charsPerLineOf()`/`lineCharCount`，`typewriterAppendSentence` 打字前预判当前行是否放得下整句，放不下先插 `<br>` 再打字，消除"挤满才跳行闪烁" ②`#dialogText` 内联样式加 `min-height: 4.8em`（=3 行 × 1.6 行高），内容不足 3 行也保留三行高度，对话框高度不再随内容伸缩
- 2026-08-26 | 分句恢复 ～…~： 切句，避免长句超 3 行气泡 | `ConservationController.java` | 之前去掉 `～…~` 切句后，一句话可能长达 50+ 字（如「原来是性格差异的问题呀～那阿罗娜…啦！」52 字 = 4 行），单个气泡超 3 行上限。恢复 `。！？!?；;…～~：` 切句（加 `：`），长句在语气/犹豫/引述符处断开，配合「短句<5字合并」避免过碎，使每句 ≤3 行、气泡合规
- 2026-08-26 | 响应风格阶段二：精简设定 + 节拍模板 + 用户画像 | `ai_prompt` 表(system id6)、`DeepSeekService.java`、`sql/update_system_prompt_phase2.sql` | ①system 提示词整体替换为新精简设定（核心设定/关于用户=用户画像/与老师关系/外在形态偏好/核心性格，957 字，全程用「人工智能」替代 AI）②新增「节拍模板」（先反应→再逞强/补充→偶尔内心OS）③末尾加「这是初步设定，允许随对话进行拓展」④`DeepSeekService` 硬编码删掉「傲娇粘人」（新性格为元气/易炸毛）并新增「不要写 AI，写人工智能」约束（避免 TTS 把 AI 读成 A I）
- 2026-08-26 | 响应风格阶段三：AI 情绪标签 + 表情兜底 + 相邻不重复 + 内心OS 发声 | `DeepSeekService.java`、`ConservationController.java`、`TtsService.java` | ①AI 输出新增可选 `emotion` 字段（开心/困惑/激动/害羞/生气/认真/期待/怀疑/慌张/难过 10 个语义情绪词），`AiResult` 增加 `emotion` 并解析 ②`ConservationController` 新增 `EMOTION_MOTION` 1:1 映射（情绪词→表情文件），`buildSentenceFlow` 表情选择顺序改为「规则库 → AI 情绪兜底 → 句末语气兜底」，并加「相邻句不重复」（与上句同表情改用默认/日常表情）③提示词约定「内心OS 用【】、动作提示用（）」；`TtsService.cleanText` 新增去掉【】括号但保留内容朗读（内心OS 会念出来，（）动作仍不念）④动作/音效仍走规则库，未纳入 AI 标签
- 2026-08-26 | 分句：连续符号整体切分 + 加 — 为切分符 | `ConservationController.java` | `splitToSentences` 遇到连续切分符号（如「！？」「————」）时，旧逻辑逐个切，会把后一个符号（？、—）拆到下一句开头。改为「下一个字符也是切分符时暂不切，等最后一个符号后再切」，连续符号整体留在句尾；并把 `—`（长音破折号）加入切分字符
- 2026-08-26 | 响应风格阶段四：风格语料库 + 关键词检索注入 | `style_exemplars` 表、`StyleExemplar.java`、`StyleExemplarMapper.java`、`DeepSeekService.java`、`AiController.java`、`桌宠管理.html`、`JS/桌宠管理.js` | ①新增 `style_exemplars` 表（id/tags/content），一条语料 = 一个场景多行台词 + 一组概括性标签 ②管理后台新增「风格语料库」面板：粘贴场景台词（可含【内心OS】/（动作））+ 填概括性标签（如「被戳穿、偷懒、被抓包」），支持列表/删除 ③`DeepSeekService.chat()` 对 `user_operation=chat` 按「用户输入包含任一标签关键词」召回最多 4 条，注入 system prompt 作「风格参考」（模仿节奏/语气/符号用法，不照抄内容）
- 2026-08-26 | 语料格式约定补充 | `DeepSeekService.java`、`JS/桌宠管理.js` | 明确语料为多轮对话场景：每行=一个气泡、括号内=表情/动作、「内心os：」开头=内心独白、连续对话仅在换人处标说话人（如「阿罗娜：」）。注入提示词改为解释此格式，导入框 placeholder 同步更新
- 2026-08-26 | 语料召回最少参考数 | `DeepSeekService.java` | `recallExemplars` 命中不足 3 条（含 0 命中）时，从其余语料里随机补足到 3 条，应对初期语料数量不足；命中最多仍 4 条
- 2026-08-26 | TTS 回读修复：短句按去括号长度合并 | `ConservationController.java` | GPT-SoVITS 对过短文本（如响应开头「诶？！」）会回读参考音频（参考是招呼语「您回来啦，我等您很久了！」）。`splitToSentences` 的「短句<5字合并」改为按**去括号后实际朗读长度**判断是否过短，过短语气词自动并入下一句一起合成，避免单独送 TTS 触发回读
- 2026-08-26 | 游戏操控·阶段一：桌面视觉 | `DeepSeekService.java`、`ConservationController.java`、`桌宠桌面版.html`、`桌宠设置.html` | ①`DeepSeekService` 新增 `chatVision(base64, prompt)`（调 `deepseek-v4-flash-vision-exp`，OpenAI 兼容传图）②`ConservationController` 新增 `captureScreenBase64()`（`java.awt.Robot` 截全屏→降采样 800px→JPEG base64）+ `/talk` 的 `desktop_vision` 分支（截屏→视觉描述→转为普通 chat 回应）③前端 `pet_settings.desktopVision` 开关（默认关）+ 设置页「桌面视觉（截屏发给 AI）」开关 ④两种触发：对话说「看得出来我在做什么/看看我屏幕」等；闲置时按 30% 概率截屏评论
- 2026-08-26 | 桌面视觉测试日志 | `ConservationController.java` | 桌面视觉每次调用在 `desktop_vision_log/` 目录下保存 3 个文件（同时间戳）：`screen_<ts>.jpg`（传给视觉模型的截图）、`desc_<ts>.txt`（视觉解读文本）、`response_<ts>.txt`（最终回应），用于排查「图片解读与回应分离」问题
- 2026-08-26 | 桌面视觉截屏失败修复 | `application.yml` | `java.awt.Robot` 截屏报「headless environment」：Spring Boot 默认把 `java.awt.headless` 设 true。在 `spring.main.headless: false` 关闭 headless，Robot 才能截屏
- 2026-08-26 | 桌面视觉截屏失败修复（二） | `ZhuochongApplication.java` | `spring.main.headless: false` 未生效（属性绑定晚于 `configureHeadlessProperty()`），改用最可靠方式：在 `main()` 里 `SpringApplication.run()` 之前 `System.setProperty("java.awt.headless", "false")`
- 2026-08-26 | 气泡四行 + 漏读修复 | `桌宠桌面版.html` | ①打包行数改用 `countPackedLines()`：模拟「短句 nowrap 整句换行 + 长句 normal 内部折行」的实际行数，修 `nowrap` 短句留空隙导致纯字符数估算偏低、气泡超 3 行的问题 ②`synth()` 失败重试一次（GPT-SoVITS 并发偶发返回空导致漏读某句）

<!-- 维护提示：每次改完功能/踩坑后，在上面更新日志追加一行，并在第 7 节对应坑点处补充/修正。 -->

---

## 12. 雀魂麻将插件：现状、边界与决策（2026-09 更新）

> 麻将模块位于 `zhuochong/src/main/java/com/cy/mahjong/`（独立可开关：`ai_config.mahjong_enabled`）。
> 目标游戏《雀魂麻将》日麻 vs 机器人；读取基于「固定槽位/人工标注 + 灰度模板 SSD」为主，AI 视觉仅兜底。

### 12.1 v1 支持能力（当前）
- **视觉**：开局/续局手牌读取 = 逐槽纯模板（13 槽网格 `摸牌区0..12`/`手牌槽N` 标注），牌序校验 + 重试；摸牌读取 = 固定摸牌框（副露后按「网格+固定间距」动态左移）模板/比对；暗裁图/点击特效拒绝
- **自学习**：错题本机制（`麻将错题截屏/`：`误报_（实际）错读为（误读）`、按槽 `手牌槽_i（牌名）`；坏图自动过滤），开局确认流「无误/有误+正确牌型」喂素材
- **自动打牌**：摸牌检测→读取→向听/进张→按槽点击出牌；每回合手牌重同步自愈
- **立直**：自动两段式（先读摸牌定舍牌→点立直→点可舍牌）；立直后自动模切、仅和/自摸
- **和/自摸**：自动胡；识别不确定宁可漏和
- **对局生命周期**：局终判定（手牌区亮度骤降）→ 弹「下一局/先暂停」；`下一局`=清数据→读下局→再确认；Ctrl+Shift+Q 强制停止
- **合作模式（默认关）**：遇吃/碰/杠不自动跳过，弹「跳过」键并等人：`碰 X`/`杠 X`/`吃 响应牌+手牌两张`/`跳过`；手动操作同步 `吃/碰/杠回报`、`出牌 X`

### 12.2 决策：牌池识别暂时搁置（明确不做）
- 原因：雀魂对手牌池（弃牌区）**摆放不严格**（牌间距不一、左右歪斜、立直横置会导致该行后侧牌右移），固定网格/模板方案难以可靠识别。
- 影响：不做整池读取、不追踪对手弃牌；吃/碰/杠等回合外决策改由**合作模式人工判断**承担。
- 恢复条件：除非未来采用更鲁棒的检测（如逐牌目标检测/视觉大模型区域解析），暂不立项。

### 12.3 v1 支持矩阵（一句话）
| 行为 | 支持 | 方式 |
|---|---|---|
| 普通出牌 | ✅ | 全自动 |
| 立直 | ✅ | 全自动（两段式+自动模切） |
| 和/自摸 | ✅ | 全自动 |
| 吃/碰/杠 | ⚠️ 部分 | 仅**合作模式人工指示**（关闭合作=自动跳过） |
| 对手牌池读取 | ❌ | 搁置 |
| 结算按钮自动点击 | ❌ | 用「下一局」确认流 + 人工点击替代 |

---

## 13. 新方向：桌宠表现优化 + 电脑操作能力（规划中，**均未执行**）

> 麻将已到"模板识别天花板"，吃碰杠之外收益有限 → 切换方向到桌宠本体的生动化与桌面自动化。

### 13.1 A. 桌宠模型表现优化（眼部/姿态）
- **鼠标跟随**：Live2D 视线/头部朝鼠标方向（`ParamAngleX/Y`、`ParamEyeBallX/Y`），桌面内平滑追踪 + 频率限流
- **观察桌面**：周期性"扫视"屏幕热区（结合截屏与标注区域），配眨眼/呼吸/歪头自然化
- **点击/输入反馈**：执行点击前先"看向"目标坐标
- **场合眼神**：说话/听歌/思考时视线偏移；麻将等游戏中视线避让操作区（复用桌宠区域标注）
- 基础动画改进：眨眼平滑过渡、呼吸幅度随状态、动作与 TTS 时长联动（衔接第 8 节 8.2）

### 13.2 B. 电脑操作能力（基于坐标 + Robot 点击/键码）
- 打开常用应用/网址：QQ、微信、浏览器、记事本等（**白名单 + 语音指令**）
- 系统调节：音量 / 亮度 / 静音（键码或系统接口）
- 媒体控制：播放/暂停/下一首（接管系统媒体键）
- 窗口管理：显示桌面、最小化/切换、窗口整理
- 剪贴板/文本：复制粘贴当前文本、向指定窗口发送消息（打字）
- 截屏自看：截屏喂视觉模型后描述/应答桌面内容
- 锁屏/睡眠（高影响，需明确确认开关）

### 13.3 C. 更多相似优化思路（发散，待筛选）
- 桌面信息播报：电量 / 网速 / 天气 / 日程 / 倒计时（定时或语音查询）
- 便签 / 待办 / 番茄钟 / 定时提醒（到点播报 + 弹窗）
- 系统通知监听 → 摘要播报
- 鼠标/键盘宏：常用短语、快捷回复、组合操作
- OCR 屏幕文字复制、快速翻译
- 窗口/热键管理助手
- 与麻将联动：操作时自动暂停视线/点击冲突（复用 Electron 3081 锁穿透）

### 13.4 设计红线（实施前必须满足）
1. 电脑操作一律 **白名单 + 开关，默认关闭**；保留 Ctrl+Shift+Q 类全局急停
2. 坐标来源沿用麻将标注工具/热区标注机制
3. 执行前与桌宠窗口点击穿透锁配合，避免自点
4. 本节全部候选为规划，未开始实现

---

## 14. 更新日志（追加）
- 2026-09-03 | 麻将 v1 收口 + 牌池搁置决策 + 方向切换记录 | `PROJECT_OVERVIEW.md` | 麻将现支持"不吃碰杠全自动立直/和/自摸 + 合作模式人工吃碰杠"；牌池识别搁置；新方向（桌宠表现 + 电脑操作）登记于第 13 节，均未执行
- 2026-09-12 | QQ 群机器人接入方案落档（见第 15 节） | `docs/QQ机器人接入方案.md` | 路线对比（官方平台 vs NapCat）与 P0~P3 分期、上云清单已写好；待定 5 项决策（路线/会话记忆/触发方式/输出形态/记忆隔离），**未开始写码**
- 2026-09-12 | 发布与提速方案落档（见第 16 节）+ 数据库分发件导出 | `docs/发布与提速方案.md`、`db/schema.sql`、`db/seed.sql` | 实测 TTS 单句 1~3s（缓存 0.05~0.28s）、项目体积 5.6GB（其中本地音乐 3.6GB）、确定"桌宠后端不上云"与"提速优先做首句立即显示+TTS 并行"；已导出脱敏种子包（564 条 INSERT）
- 2026-09-12 | 开工：登录改默认账号 + 操作册 + 移除本地音乐 + 首句立即显示 | `AdminController.java`、`application.yml`、`static/操作册.html`、`AiController.java`、`static/JS/桌宠管理.js`、`static/桌宠桌面版.html`、`db/*` | ① 登录不再查 `adminlist`，改配置化默认账号（老师/123456/管理员，见 yml `login.*`），删除 `AdminService`/`AdminService_impl`/`AdminMapper`/`Admin` 四个旧类；② 新增操作册页面（14 分区，设置窗口侧栏入口 + 白名单）；③ 删除 `static/music`（3645MB/7945 文件）与 `/ai/music/list`、`/ai/music/random`，管理页播放源改走网易云；④ `playSpeech`/`playBubbleTts` 改为"响应到达即显示文字+表情，语音随后跟上"（感知延迟 ≈ LLM）；核查确认 TTS 预取/流水线**原本已具备**，未引入并发风险。**后端需重启、页面需 Ctrl+F5 生效**
- 2026-09-13 | 陪玩点评模式 P1 实现（第 17 节） | `CommentaryService.java`、`CommentaryController.java`、`CommentaryMapper.java`、`CommentaryConfig.java`、`CommentaryContext.java`、`DeepSeekService.java`、`static/桌宠桌面版.html`、`static/桌宠设置.html`、`command_lib_seed.json` | ① 表 `commentary_config`（12 项参数）+ `commentary_context`（5 语境提示词，可编辑）；② 一步法视觉点评（`chatVision(...,300)`）+ 64×40 灰度变化检测 + 800px 截图 + 60 分钟自动清理；③ 桌面版 🎬 状态按钮 + 独立计时器（不被鼠标活动清零）+ 本状态音量缩放 45% + 概率延长间隔 35% + 连续 3 句后静默 45s；④ `gazeOnUser()` 在本模式直接 return（不鼠标跟踪、不看回老师）；⑤ 麻将/抽卡/牛奶/调整/说话中一律暂停、普通闲置让路；⑥ 指令 `陪我玩 [语境]`/`别看了`/`点评一下` 已入指令库；⑦ 设置页新增 🎬 陪玩点评（参数 + 语境表 + 试点评）
- 2026-09-13 | 桌面快捷方式（DSH Web / 桌面桌宠）+ 图标体系 | `启动DSH-Web.bat`、`zhuochong-electron/start.bat`、`zhuochong/一键启动.bat`、`图标/生成图标.ps1`、`图标/ico/*.ico`、`zhuochong-electron/main.js` | ① 新增 DSH Web 启动器 bat + 两个桌面快捷方式（图标分别用 `图标/ico/DSH Web.ico`、`图标/ico/桌面桌宠.ico`）；② **修坑：三个 bat 原本是 LF 行尾，cmd 读错位执行导致启动器报「未找到 DSH」**，全部改为 CRLF + GBK + `chcp 936`；③ 新增 `图标/生成图标.ps1`（按非透明外接框裁切 → 等比缩放 → 小尺寸 DIB + 256 用 PNG 的多尺寸 ICO）；④ Electron 托盘图标改为 32×32 专用小图 `图标/托盘.png`（`main.js` 新增 `trayIcon()`，托盘/窗口图标分离）
- 2026-09-13 | 桌面桌宠：单实例检测 + 后端未启动时一键启动 | `zhuochong-electron/main.js`、`zhuochong-electron/启动后端.bat` | ① `app.requestSingleInstanceLock()`：重复双击不再多开进程、不再报端口占用，第二个进程直接退出，由已在运行的实例弹提醒（对话框 + 托盘气泡）并把桌宠窗口叫到最前；② 启动时探测后端（`127.0.0.1:8080/basicinfo`，有响应即算活着）：没起来就先显示「等待后端」卡片页并询问，可选「一键启动后端 / 我自己启动(IDEA) / 先不管」，启动后每 2 秒轮询、最多等 3 分钟，起来自动加载桌宠页；③ 托盘菜单新增「🔌 启动后端服务（未运行时）」与「🔄 重新加载桌宠页面」；④ 控制端口 3081 被占时只记日志不再崩（麻将锁定功能降级）；⑤ 页面加载失败不再显示 Chromium 报错页，改为等待页
- 2026-09-13 | 修复：等待卡片盖住询问框、且无法单独关闭 | `zhuochong-electron/main.js`、`zhuochong-electron/等待后端.html`(新)、`zhuochong-electron/preload.js` | ① 根因：桌宠窗口是全屏 + `screen-saver` 置顶，原生 `dialog.showMessageBox` 出现在它后面，按钮点不到；那张全屏透明等待页又挡住了整个桌面且没有任何关闭入口。② 改为**不用原生对话框**：询问按钮做进卡片页面（「一键启动后端 / 我自己启动(IDEA) / 先关掉」+ 右上角 ✕），等待期把窗口缩成 470×210 小卡片（居中偏上、可拖动、✕ = `win.hide()`），后端就绪再 `setBounds(fullBounds)` 还原全屏；③ 超时/启动失败也改成卡片内提示 + 「再试一次」，不再弹原生框；④ 新增 `safeDialog()`：仍需原生框时（重复启动提醒、拉起脚本失败）先临时 `setAlwaysOnTop(false)` 避免被盖住；⑤ preload 新增 `backendAction`/`onBackendStatus` 两个 IPC 通道
- 2026-09-13 | 修复：未锁定时点击桌面后桌宠彻底失去响应 | `zhuochong-electron/main.js`、`zhuochong-electron/preload.js`、`static/桌宠桌面版.html` | ① 根因：桌宠页的区域穿透逻辑（鼠标离开桌宠→整窗点击穿透，移回→恢复捕获）中，**「恢复」那一步完全依赖 Electron 把 mousemove 转发进页面**（`setIgnoreMouseEvents(true,{forward:true})`），转发失效后窗口就永久停在穿透态 → 对话框 / 模型 / 右下角按钮 / 眼部跟踪全无响应，只有 Ctrl+Shift+Z 能救回（它直接 `setIgnoreMouse(false)`）；② 新增不依赖转发的兜底：主进程每 100ms 读真实光标位置推 `pet:cursor`，页面用真实坐标重新 `updatePass()` 恢复捕获（用户锁定 / 麻将接管 / 等待卡片期间不推，避免顶掉锁定态）；③ 穿透通道拆成两个：区域穿透走新 IPC `set-pass-through`（主进程记作 passThrough），用户锁定与麻将 `/lock` 走原来的 `set-ignore-mouse`（记作 isLocked），主进程按 `isLocked || passThrough` 下发；④ 页面 `updatePass()` 增加「锁定时直接 return」守卫，顺带修掉「锁定时把鼠标移回桌宠会被自动解除穿透」的老毛病
- 2026-09-13 | 桌面桌宠：新增「重启后端服务」+ 修控制台日志乱码 | `zhuochong-electron/main.js`、`zhuochong-electron/停止后端.ps1`(新) | ① 托盘菜单新增 `♻️ 重启后端服务（改了 Java 代码用）`：先跑 `停止后端.ps1`（杀监听 8080 的进程 + 跑 `启动后端.bat` 的命令行窗口，连同 Maven/Java 子进程一起结束，日志窗口不会卡在 pause），等端口释放后重新拉起，起来自动刷新桌宠页；② `checkBackend` 之外补了 `waitBackendDown()`（等端口释放，20 秒超时并给出提示）；③ **修乱码**：主进程所有 `console.log` 改成 ASCII —— 原中文日志在 `npm start` 的 GBK 控制台里显示成「鍖哄煙绌块€?-> true」，且每次与桌宠交互就刷两行；现在穿透日志收进 `DEBUG_PASS` 开关（默认关）
- 2026-09-13 | 视线跟随：「出桌宠区丢跟踪」与「回桌宠区跳变」修复 | `static/live2d/live2d-fix-bridge.js`、`static/桌宠桌面版.html`、`zhuochong-electron/main.js` | ① 根因：follow 模式的眼球/头部目标由 `_mx/_my` 算出，而这两个值只在 `mousemove` 时更新 —— 窗口进入点击穿透后收不到 mousemove，坐标冻结 → 视线「突然丢」，回到桌宠上时用新坐标硬追 → 眼部参数跳变；② 主进程兜底轮询（50ms）改用 `pet:cursor` 附带 `focused`，页面据此持续 `l2dBridge.setCursor()` 喂坐标（穿透期间跟踪不断）+ `setCursorFocus()`（失焦即冻结，不看桌面）；③ `_lookTick` 新增「平滑重新捕获」：断档后恢复时用与「空闲随机视线」同一套 `_easeInOut` 缓动过渡（0.32~0.94s）取代指数硬追，`followCursor()` 进入跟踪时同样先标记断档；④ 断档判定在更新时间戳之前完成，否则恢复后的第一帧不算断档、过渡永不触发
- 2026-09-13 | 区域穿透改为「点桌面才放开」+ 视线归位/回场都走逛视同款缓动 | `static/桌宠桌面版.html`、`static/live2d/live2d-fix-bridge.js`、`static/操作册.html` | ① 穿透规则改为「收得住才放得开」：鼠标移出桌宠区域**不再立刻放开**（视线跟随/状态得以保持），只有**在桌宠区域外单击一下**（`mousedown` 记候选 + `mousemove` 拖动取消 + `mouseup` 判定）才放开，代价是那一次点击被桌宠吃掉（老师确认接受）；鼠标回到桌宠区域自动收回捕获；② 找到并修掉「回桌宠区仍猛地甩过去」的真凶：鼠标进区触发 hover 反应 → 桌宠说话 → `gazeOnUser()→holdGaze()` → 归位用的是每帧 ×0.93 的指数衰减（≈0.3s 从逛视位置拉回正中）；现在归位过渡改用与「空闲随机视线」同一套 `_easeInOut`（0.38~0.9s），`_dbgGaze` 可开关过渡日志；③ 操作册新增「鼠标点桌面（区域点击穿透）」一节说明新规则
- 2026-09-13 | 等待卡片：位置改右下 + 加「—」收起按钮 + 两个命令行窗口改为隐藏启动 | `zhuochong-electron/main.js`、`zhuochong-electron/等待后端.html`、`zhuochong-electron/启动桌宠.vbs`(新)、`zhuochong-electron/启动后端-隐藏.vbs`(新) | ① 卡片初始位置从「居中偏上」改为**贴屏幕右下角**（留 16px）；② 标题栏新增 `—`：只收起卡片、等待继续，后端好了自动把桌宠叫出来；`✕`/「先关掉」语义改为「收起且本轮不再自动等待」（托盘菜单可重新启动），并复位 `backendBooting` 免得卡住托盘；③ 新增 `启动桌宠.vbs` 与 `启动后端-隐藏.vbs`（`WshShell.Run(...,0,...)`）实现**零闪窗启动**，输出重定向到 `logs\electron.log` / `logs\backend.log`（4MB 上限），`startBackend()` 从 PowerShell Start-Process 改为调用 VBS，桌面快捷方式「桌面桌宠」改指 `启动桌宠.vbs`；④ 托盘新增「📂 打开日志目录」；⑤ 卡片超时提示改指向日志文件
- 2026-09-13 | 区域标注工具优化（导入本机图片 / 区域列表收起 / 去掉 File-Edit 菜单） | `static/区域标注工具.html`、`zhuochong-electron/main.js` | ① 新增「📁 导入图片…」：FileReader → dataURL → Image，图片**不上传服务器**，直接进标注区（坐标仍按图片宽度换算到 2560 基准，故建议用与实机同宽高比截图）；同时把图片加载统一抽成 `setImage(img,label)` 供服务端截屏与导入共用；② 「已保存区域」默认**只显示最近修改的一行**，右上角「展开全部 ▾ / 收起 ▴（共 N 个）」按钮控制展开，`regions.json` 本身没有时间戳，所以最近修改用 `localStorage.regionLastSaved` 记录（保存时写入、刷新后仍有效，无记录时退化为 JSON 最后一个），并给该行打「(最近修改)」标记；③ `main.js` 新增 `browser-window-created` → 所有窗口 `setMenuBarVisibility(false)`，修复设置页 `window.open` 打开的标注工具顶部多出 File/Edit 菜单栏的问题（只隐藏不 removeMenu，快捷键仍可用），并用 `web-contents-created` + `setWindowOpenHandler` 给新窗口补图标与 `autoHideMenuBar`
- 2026-09-13 | 区域标注工具：导入图片落盘 + 坐标无损往返（支持非实机宽高比的设计图） | `static/区域标注工具.html`、`mahjong/MahjongPlayService.java`、`mahjong/MahjongController.java`、`config/WebConfig.java`、`static/标注图片/`(新) | ① 「导入图片」改为**先落盘**：新增 `POST /ai/mahjong/regions/upload-image`（dataURL → 魔数校验 → 写入 `static/标注图片/`，同名覆盖，≤16MB），`WebConfig` 给 `/标注图片/**` 配**文件系统直出**资源映射 + 拦截器白名单，所以导入后立刻可访问、免 rebuild，下拉框里也永久可见；② `/calibration-files` 由「字符串名数组」改为 `{name,url,group}`（前端不再靠猜目录，按「麻将参考截屏 / 实机截屏 / 导入的图片」分组显示 optgroup）；③ **坐标无损往返**：`saveRegion` 现在同时存 2560 基准（x1..y2，麻将/抽卡运行时用，保持不变）与**原图像素坐标 `px`**（外加 imageWidth/Height），因此非 2560 宽、长宽比不同的设计图也能精确回到原像素；④ 标注页配套：坐标栏同时显示「像素 + 2560 基准」、新增「📋 复制像素坐标」（JSON，给改 UI 用）、已保存区域**画回图上**（青色细框+名字）便于肉眼核对、每条区域可「载入」回选框微调
- 2026-09-13 | 基础设置窗口换皮肤（login_beijing.png 背景图 + 无边框方形窗口）+ 管理页背景同步更换 | `static/桌宠设置.html`、`zhuochong-electron/main.js`、`static/CSS/桌宠管理.css`、`static/桌宠管理.html`、`static/background/login_beijing.png`(新) | ① 新增 `settingsWindowOptions()`：基础设置窗口改为**无边框 + 透明 + 不可缩放**，尺寸按背景图 1024×1024 做成**正方形**（取屏幕可用高度自适应、居中）；托盘入口与页面 `window.open` 入口统一走它（`web-contents-created` + `setWindowOpenHandler` 覆盖），并新增 `close-window` / `minimize-window` 两个 IPC（按发送方窗口处理）；② 页面 `body` 铺背景图（`100% 100%`，正方形窗口不变形），`.app` 按「区域标注工具」标注的「内容区域」百分比定位（11.035% / 30.566% / 74.805% / 40.430%），标题行 `#titleRow` 位于图片顶部原本透明的带内、背景全透明 + 可拖动 + 自绘 `─ ✕`（普通浏览器里自动隐藏按钮，Esc 也可关）；③ 无横向滚动条：`.page` 由固定 `max-width:880px` 改为 `100%`、`table.tbl` 改 `table-layout:fixed` + 长内容换行、`.content` 仅纵向滚动，并整体紧凑化；④ 管理页 `.history-panel` 背景 `login_box.png` → `login_beijing.png`（`桌宠管理.css` 版本号 v3→v4 以免吃缓存）；⑤ 背景图另存一份到 `static/background/` 作为应用资源（标注用的那份仍在 `标注图片/` 里）；⑥ 标注工具 `saveRegion` 补传 `imageHeight`
- 2026-09-13 | 基础设置窗口改用裁剪后的 login_beijing2.png（浅色主题 + 顶部窗口区固定标题栏）| `static/桌宠设置.html`、`zhuochong-electron/main.js`、`static/background/login_beijing2.png`(新) | ① 换图：`login_beijing2.png`（923×528，已裁掉多余透明边）另存到 `static/background/` 作应用资源；② 窗口尺寸由「正方形」改为**按该图宽高比**（`SETTINGS_ASPECT`（923×528，1:1 显示最清晰，屏幕放不下才等比缩小）；③ 标题行 `#titleRow` 固定进标注的「**顶部窗口区域**」（left 6.717% / top 6.629% / width 83.315% / height 7.197%），区域本身透明、整条可拖动，`─ ✕` 按钮**加了白色背景 + 描边 + 微阴影**（关闭键悬停转红），Esc 仍可关闭；④ 「内容区域2」（left 6.826% / top 13.826% / width 83.099% / height 78.409%）里的 `.app` 改为 **直角**（`border-radius:0`）、**背景透明**（不再压黑色底），配合图片浅色底**全站转浅色主题：黑字**（`#14181f`/`#16324f`），表头用灰底 `#d4e6f1`、卡片 `rgba(255,255,255,.52)`、输入框白底、主色 `#2f6fd0`（整体贴近管理页风格）；⑤ **横竖滚动条都隐藏**（`*::-webkit-scrollbar{display:none}` + `scrollbar-width:none`），内容仍可滚轮滚动；⑥ 内容区变大（767×414）后不再需要之前的紧凑化压榨
- 2026-09-13 | 基础设置窗口微调：标题改大号白字「设置」、拖拽区改为内容区之外、初大小 ×1.5 | `static/桌宠设置.html`、`zhuochong-electron/main.js` | ① 顶部标题由「🎀 桌宠设置」改为**大号白字「设置」**（22px / 字距 2px / 深色描影保证浅蓝底上可读），不再与侧栏主标题重复；② `#titleRow` 左右各加 `2em`（≈两字，44px）内间距，`─ ✕` 相应右移；③ 新增铺满整窗的 `#dragLayer`（`-webkit-app-region: drag`）+ `.app` 设 `no-drag`，**可拖区域 = 「内容区域2」之外的整块图**（顶部带 + 左右边 + 底部带），面板内照常点选；④ 窗口初始尺寸改为 `SETTINGS_SCALE = 1.5`（923×528 → **1385×792**），内容区随之从 767×414 变大到 1151×621
- 2026-09-13 | 基础设置窗口：尺寸可调（页内滑动条实时缩放）+ 出现/关闭动画（下方滑入滑出）+ 默认尺寸回归 1.0× | `zhuochong-electron/main.js`、`zhuochong-electron/preload.js`、`static/桌宠设置.html` | ① 默认尺寸从 1.5× 改回 **1.0×（=背景图原始 923×528，最清晰）**；② 「基础外观」页新增 **`基础设置窗口大小` 滑动条**（0.6×~2.0×）：拖动即时生效（主进程 `set-settings-scale` 以窗口中心为基准等比缩放，窗口自动夹在屏幕内），松手再补发一次落盘；数值存主进程 `userData/settings-window.json`（不是 pet_settings/ai_config，因为它是窗口本身的状态），由 `loadSettingsCfg()` 在 `whenReady` 读取；③ 新增 **出现动画**：窗口先创建在屏幕下沿之外，`did-finish-load`（或 3 秒兜底）后向上滑入屏幕；**关闭动画**：拦截 `close`，先向下滑出屏幕再真正关闭；时长 `SETTINGS_ANIM_MS = 1200`，缓动为时间驱动的二次 ease-in（速度逐渐加快，进出同款）；`before-quit` 置 `app.__quitting` 时跳过关闭动画，避免拖住整体退出；④ preload 暴露 `setSettingsScale` / `getSettingsScale`
- 2026-09-13 | 修正：窗口大小滑动条改为「松手后缩放」（左上角锚定） | `static/桌宠设置.html`、`zhuochong-electron/main.js` | 实时缩放会让窗口带着页面（连同滑块本身）一起动，拖起来追不到滑块。现在 `input` 只更新数字显示、**不缩放窗口**，`change`（松手 / 键盘调整结束）才发 `set-settings-scale`；同时主进程把缩放锚点从「窗口中心」改为**左上角**（`x/y` 保持当前值、超出屏幕才夹回），这样面板左上角与滑块位置基本不动，便于反复微调
- 2026-09-13 | 修基础设置窗口「打开看不见 / 没有入场动画」+ 动画加快一半 | `zhuochong-electron/main.js` | **根因**：桌宠窗口是全屏 + `screen-saver` 级置顶的透明层，且每次失焦都会把自己重新置顶；设置窗口是普通窗口 → 一开出来就被压在它下面，同时 Windows 把「被完全遮挡」的窗口报告给 Chromium，Chromium 便**暂停绘制**它（所以既看不到内容和入场动画）；点一下桌面使设置窗口拿到焦点、脱离遮挡判定后才画出来（B站全屏时同理）。**修法**：① 设置窗口改为与桌宠**同级置顶** `alwaysOnTop:true` + `setAlwaysOnTop(true,'screen-saver')`，并在 `ready-to-show` 时 `moveTop()+focus()`；② `webPreferences.backgroundThrottling:false`，被遮挡也不再暂停绘制；③ 桌宠的 `blur` 处理器在**设置窗口开着时不抢回置顶**（`settingsWinRef` 守卫），避免把设置窗口重新压下去。另外动画时长 `SETTINGS_ANIM_MS` 由 1200ms 改为 **600ms**（加快 0.5 倍），并把移动从 `setBounds` 换成 `setPosition`（只改 y，透明窗口重绘更稳）
- 2026-09-13 | 修三个遗留问题：点桌面被连吞多次 / 选项按钮位置重启重置 / 状态按钮误触发点击响应 | `static/桌宠桌面版.html`、`static/live2d/live2d-fix-bridge.js` | ① **点桌面被吞多次**：原来「算不算点桌面」只在 mousedown 的「空白区」分支里判断，而且用 `isPetZone`（含 Live2D **模型画布外框**）——外框比可见身体大一圈，于是「点桌面 → 放开捕获 → 兜底轮询把旁边那片桌面又当桌宠抢回捕获 → 下一次点击再被吞」循环。现在：把判断提到 mousedown 最前面并改用**只看 UI 热区的 `isPetUiZone()`**（对话框/选项栏/面板/按钮/输入框/锁键），另加 `RELEASE_GRACE_MS = 1200` 宽限期，宽限期内抢回捕获改用新加的 **`l2dBridge.hitTestTight()`**（模型 0.72 倍宽/0.92 上/0.72 下），所以点桌宠**可见身体**仍会立刻恢复捕获（摸头不受影响），而点它旁边那片「看不见的外框区域」不会再把桌面点击吞掉；② **选项按钮栏位置重启后重置**：以前只在「退出编辑模式」那一刻保存 `choicesX/Y/choicesBtnW`，拖完直接重启就丢了 → 新增 `saveChoicesLayout()`，在**拖动/缩放松手时**即持久化（退出编辑模式时再兜底存一次）；③ **点 ⚙「编辑选项按钮位置与大小」/🎯「调整桌宠位置大小」会误触发一次点击桌宠响应**：这两个按钮的 `click` 没有 `stopPropagation()`，事件冒泡到 document 的「点击桌宠」处理器 → 补上 `stopPropagation()`（其它状态按钮本来就有），并在该处理器开头加一道兜底：`e.target.closest('button, input, textarea, select, .lock-btn, #adjustPanel')` 直接 return
- 2026-09-13 | 修对话框/选项栏位置持久化两处问题 | `static/桌宠桌面版.html` | ① **对话框位置不保存**：`#dialogBox` 是 `position:fixed` 且**不在编辑模式也能直接拖**，但之前只持久化了宽度/内边距、没存位置 → 拖动结束时一并写 `dialogX/dialogY`（`[DIALOG] 已保存位置` 日志），开机 `loadLocal` 里还原并清掉 `right/bottom`；② **点 ⚙ 后选项栏跳回初位置**：进入编辑模式时读的是 `wrapper.getBoundingClientRect()`，而选项栏没显示时 rect 全是 `0` → left/top 被写成 0（表现为「回到初位置」）。现在优先用 `choicesManualPos`（保存值/当前可见位置），从没存过才借自动定位（`positionChoices` 在编辑态会直接 return，临时切一下标志位再调）；③ 顺带：开机即套用一次选项栏位置；编辑模式下若当前没有真选项，给 3 个占位按钮便于调位置/宽度，退出编辑时若仍无真选项则清空并隐藏
- 2026-09-13 | 基础设置窗口：位置记忆 + 「屏幕外」改用整块屏幕计算 | `zhuochong-electron/main.js` | ① **位置记忆**（同对话框逻辑）：拖窗口时 `move/moved` 防抖 400ms 写入 `userData/settings-window.json` 的 `x/y`（与 `scale` 同一个文件），关闭前再兜底存一次；下次开窗优先用保存位置（`clampSettingsPos()` 夹回屏幕内、至少留 120px 可见，否则回落居中）。存位置时做「确实在屏幕内」校验，且**动画期间不记录**（`animating` 标志），避免把屏幕外的动画坐标存进配置；② **屏幕外判定改用 `display.bounds`**：原来用 `workArea` 算「下方屏幕外」(`area.y + area.height`)，但 workArea 不含任务栏 —— 底部任务栏那一带其实仍在屏幕上，会出现「滑入前就已经在屏幕里 / 滑出后没完全出屏」。新增 `displayBoundsOf()` / `offScreenYBelow()`，滑入起点与滑出终点统一取**整块屏幕的下沿**（含任务栏区域、多屏时取窗口所在那块屏），保证动画两端都在屏幕外
- 2026-09-13 | TTS 参考音频：下拉选择 / 参考文本编辑 / 试听；基础设置窗口不再挡住资源管理器 | `DevToolsController.java`、`static/桌宠设置.html`、`zhuochong-electron/main.js`、`zhuochong-electron/preload.js` | ① 新增三个后端接口（都在 `/ai/dev`，已被 `/ai/**` 白名单放行）：`GET /refer-audios`（列 `refer_wav` **所在目录**里的 wav/mp3/flac/m4a，含大小与同名 `.lab/.txt/.list` 文本）、`POST /refer-audio/select`（换 `ai_config.refer_wav`；有同名文本就采用，**没有就把 `prompt_text` 清空**并提示去填 —— 文本与音频不一致正是拉长音/吞字的成因；整份回写保留其它字段，并做目录穿越校验）、`GET /refer-audio/audio?name=`（把该目录里的音频直接流给页面做试听）；② 设置页「语音 TTS」新增：参考音频**下拉框**（选中即切换）+ `🔄 刷新列表` + `🔊 试听`（`<audio>` 播后端流）+ `📂 参考音频文件夹`，以及**参考文本输入框** + `💾 应用文本`（写回 `prompt_text`）；选到没有同名文本的音频时输入框会高亮并聚焦；③ **设置窗口不再挡住资源管理器**：新增 `settings-yield-top` IPC → `yieldSettingsTop()`（取消置顶，本窗口重新获得焦点时自动恢复 `screen-saver` 置顶）；页面 `openFolder()` 与「从设置窗口 `window.open` 出的新窗口（标注工具/操作册/语音调试页）」都会先让位
- 2026-09-13 | 修「换参考音频后试听/合成变体无变化」：语音缓存键带上音色指纹 + 清空缓存入口 | `TtsService.java`、`AiController.java`、`static/桌宠设置.html` | **根因**：TTS 缓存键原来只有 `v<变体>|<文本>`（坑点 8 记过），换了参考音频/模型/语速后同一句话仍然命中**旧音频**，试听与「合成变体」听起来完全没变化，没法对比音质。**修法**：新增 `voiceSig(rawText, variant)` —— 把**实际会用的参考音频**（含语境参考 `resolveReference` 的结果）+ 参考文本 + gpt/sovits 模型路径 + 语速 + sample_steps 压成签名，再连同变体与文本组成 `cacheKey()`；参考音频还带上**文件大小与修改时间**，所以「同路径换内容」也能识别。三处缓存键（`synthesize` / `saveCandidate` / `hasCache`）统一走 `cacheKey()`，内存缓存与磁盘缓存同时失效重建。另加 **`GET /ai/tts/cache`（条数/占用）与 `POST /ai/tts/cache/clear`**（`TtsService.clearCache()` 清内存 + `tts_cache/*.txt`），设置页「语音 TTS」新增「🧹 清空语音缓存 / 🔄 刷新占用」一行（实测当前缓存 148 个文件 ≈46.8MB，可随时清）
- 2026-09-13 | 新增：秋招面试备忘录 + 桌宠「自我介绍」指令 | `docs/秋招面试备忘录.txt`(新)、`db/seed.sql`、`static/桌宠桌面版.html` | ① **`docs/秋招面试备忘录.txt`**（27KB / 361 行，带 UTF-8 BOM 便于记事本打开）：面向「AI 应用开发 / 桌面端·客户端 / 全栈」三个岗位，含一句话与 30 秒/2 分钟自述、技术栈与架构数据流、功能清单与两个可深讲亮点、**12 个踩坑故事**（现象→定位→根因→方案→学到，取自第 7 节坑点手册）、分岗位追问预判与答法、三分钟讲述提纲（三版）、量化数据、诚实不足与改进计划、投屏演示脚本；② **桌宠自我介绍**：`conversation` 新增 `user_operation='selfintro'`（221 字，含工具与功能，可在管理页改文案），`桌宠桌面版.html` 的 `handleLocalCommand` 增加触发词（自我介绍/介绍一下你自己/介绍下自己/介绍你自己/讲讲你自己/说说你自己）→ `callReaction('selfintro')` 走既有固定回应+语音链路；③ 指令库新增 module=base 一行（id=65）便于在设置页查看；④ `db/seed.sql` 同步补入这两行，并用临时库 `db03_seedtest` 实测导入通过（conversation=33、command_lib=64、selfintro 1 行）
- 2026-09-13 | 修「长回复只停在第一段、要手点对话框才翻页」：段落改为跟着语音自动翻 | `static/桌宠桌面版.html` | **根因（与鼠标穿透无关）**：`playSpeech()` 把回复按 `\n\n` 分段后只**手动**翻页 —— 显示第一段 + `dialog-clickable`，后续段落必须点对话框才出来（`textSegments.shift()`），而语音却是**整段**通过 `/ai/tts/stream` 朗读的 → 多段回复（如桌宠自我介绍 3 段）会一直停在第一段。**修法**：新增段落显示状态（`speechParts/speechPage/speechPageTimer`）与 `speechPageShow/Advance/Reset/Follow/CancelTimer`：① 首段仍立即显示；② SSE 每送出一句（payload 带 `text`）就调 `speechPageFollow(sentence)`，**按语音进度自动翻到该句所在段落**；③ 另加兜底定时器（`len*260+1200ms`，2.5~14 秒封顶），TTS 关闭/失败/流断也不会卡住；④ 手动点击仍可翻页（`speechPageAdvance()`，并重排兜底定时器）；⑤ `textSegments` 保持同步，旧的分页按键/点击逻辑照旧；⑥ `choicesBox_display_none()` 里清掉定时器，避免选项栏出现后还在偷偷翻段
- 2026-09-14 | 新增「你画我猜」P0（老师画 · 阿罗娜猜）：本地词库出题 + 画板窗口 + 视觉猜画 + 游戏记忆 | `PictionaryService.java`(新)、`PictionaryController.java`(新)、`PictionaryMapper.java`(新)、`PictionaryWord/Game/Guess.java`(新)、`config/WebConfig.java`、`static/你画我猜.html`(新)、`static/桌宠桌面版.html`、`zhuochong-electron/main.js`、`preload.js`、`PROJECT_OVERVIEW.MD` | ① **本地词库出题**：新表 `pictionary_word` 播种 **100 个基础题**（难度 1/2/3 = **40/40/20**，带 `aliases` 别名与 `category` 类别；出题按 `used_count ASC, RAND()` 轮换，避免老抽同几个词）；② **画板窗口** `你画我猜.html`：复用基础设置窗口**同一套**参数（同尺寸比例 + `settingsScale` 缩放 + 位置记忆 `boardX/boardY` + 下方滑入/滑出动画 + `screen-saver` 同级置顶 + `backgroundThrottling:false`），背景图当画板边框 —— **顶部窗口区域**居中大号显示「题目：XX」，**内容区域2** 是**半透明画布**（`rgba(255,255,255,.30)`，不遮住背景图）；工具：细/中/粗笔 + 橡皮 + 撤销 + 清空；③ **判对在游戏模块里自动完成**（老师不用点选项、不用输答案）：`包含即算对 + 别名`（`isCorrect()`，双向子串匹配，别名同样参与）；④ **答案不进提示词**：给视觉模型的提示词只有「几个字 + 类别 + 已猜清单 + 改动要求」，答案只存在后端与画板窗口界面上 → 不存在"模型提前知道答案"；⑤ **游戏记忆**：每轮猜测/说法/**变化描述**落库 `pictionary_guess`，下一轮回灌「你已经猜过的（禁止重复）」与"上几轮观察到的变化"；每轮把**上一张画 + 这一张画**一起送 `chatVisionMulti`，所以桌宠能说出「这次和上次差在哪」（实测：画房子 → 加烟囱 → 猜 `烟囱` + 变化「屋顶加了烟囱，去掉了窗户」）；⑥ **不进长期记忆**：整局只调视觉接口与 `chatPlain`，`op=game` 天然不进 `pet_memory`/`event_book`，战绩只写游戏自己的表（`/ai/pictionary/stats`：局数/猜对率/平均轮次）；⑦ **猜错后的「是否继续」不写进对话框文字**，改用**对话选项按键**（`pictionary_continue / giveup / again / stop`），老师也可以**直接改画再点「画好了」**，不限次数；⑧ 结束由游戏模块给答案（含一句桌宠反应，`chatPlain` 生成、失败兜底模板）；⑨ 修坑：**新静态页要进 `WebConfig` 白名单**（否则画板窗口 401，见第 7 节坑点 30）、**种子行字段分隔符改成 `;`**（别名内的 `|` 会挤错列，见坑点 31）；⑩ 端到端自测（临时脚本，已删）：词库 100/40/40/20 ✓、开局/当前局 ✓、猜错 ✓、猜对 ✓（画苹果 → 猜「苹果」→ `correct=true` 自动收局）、改动识别 ✓、公布答案 ✓、战绩 ✓。**后端已重启、页面需 Ctrl+F5、Electron 需重启（main.js/preload.js 有改动）** | 体验修订（同日）：① **两段式回应**：后端返回 `say`（这一轮「猜是什么」）+ `verdict`（停顿之后的收尾反应），前端 `pcSpeakChain()` 先念 say、**等这句播完再留 750ms 过渡**才念 verdict，最后才弹按键 —— 原来猜对时只念了模型那句「是X吧？」，看着像"没有猜对的响应"；现在猜对必有一句庆祝、猜错必有一句「没猜对」，无语音/流失败时用按字数估算的兜底定时器保证第二句一定接得上；② **画得潦草可以吐槽**：视觉提示词新增 `remark` 字段（≤15 字，画得清楚留空），猜错时拼成「吐槽 + 没猜对」两句短话（5 套模板按轮次轮换）；③ **反作弊自证**：每轮真实提示词原样落盘 `pictionary_shots/gX_rY.prompt.txt`，并打印 ASCII 断言 `containsAnswer=false`（实测 424 字符提示词里只有「几个字 + 类别 + 已猜清单」，确认答案从未进提示词，老师可自行核对）；④ 画板：按钮「画好了，让阿罗娜猜」→**「画好了」**，工具条左右各留**两个字**内间距；⑤ 模型没看清时也通知桌宠说一句（原来只有画板状态栏在变）；⑥ **提示强度改为「只给字数」**（老师定的）：视觉提示词里的类别摘掉（改成「答案是一个 N 个字的词（不提供类别，只能靠画面判断）」）、开局开场白也改成只知道字数（连类别都不说），`/ai/pictionary/hint` 同步只回字数 —— 原因是「类别+字数」会把范围压得太窄（"交通+2字"≈汽车/火车/飞机/轮船），猜起来像开天眼；**类别只在画板窗口给画的人看**，不进任何给模型的文本。实测：开场白「老师画的词有两个字哦…」不含类别、提示词 435 字符、`containsAnswer=false`；⑦ **开窗不再等桌宠开口**（老师实测反馈）：原来开场白那句话是模型生成的（`chatPlain` ~1 秒）却塞在 `/ai/pictionary/start` 里，画板窗口被它拖住才弹出来 —— 现在拆成两个接口：`/start` 只做本地词库出题（**实测 108ms** 返回）→ 前端拿到题目**立刻开窗**，开场白另走 `/ai/pictionary/intro`（实测 975ms）随后到，桌宠边画边说；⑧ 过渡时间 **750ms → 3000ms**（老师定的）；⑨ **修「猜测语音还没播完，庆祝文本就顶到对话框上、还打断猜测语音」**：根因是第二段的兜底定时器按**字数估算**（`1800 + len*260`）—— 估短了就抢先触发，于是 `playSpeech(verdict)` 一边把文本换掉、一边抢用同一个 `ttsAudio` 元素把猜测语音掐了。**修法**：兜底改成**轮询真实播放状态**（`ttsAudio` 曾开始播 && 现在停了 = 这句说完；TTS 关/流失败才退化到按阅读时间估算，并加 25s 硬上限）；同时加 `pcChainToken` 作废旧链（老师可以在上一轮还没说完时又提交一张画，迟到的庆祝语不会冒出来），按键也改成**收尾那句说完之后**才弹；⑩ **修「庆祝响应没有表情动作」**（老师发现）：根因是这一段是**页面本地直连 `playSpeech(text, 表情图)`** —— 表情图硬编码在页面里、音效/动作全没传，等于绕过了「情景规则库」和场景表（正常对话里 `user_op != game` 的回复会遍历 `response_rule` 拿 motion/sound_effect/special_effect；`op=game` 则按场景取表情）。**修法**：后端新增 `reaction(scene, text)` —— ① 先按 **response_rule** 匹配这句话（长词优先，与正常对话同一套逻辑）② 缺的字段用**场景表**补齐（新增 `pc_start / pc_guess / pc_win / pc_wrong / pc_reveal` 五组共 10 行，写在 `conversation` 表、`init()` 幂等补种、管理页可改；每行给 `motion` 表情图 + `sound_effect` 小特效视频 + `special_effect` Live2D 动作 bounce/swing/spin2/tilt 等）③ 仍为空才退回 `aluona_zhengchang.png`；响应里下发 `sayMotion/saySound/sayEffect` 与 `verdictMotion/verdictSound/verdictEffect`，页面按段播放（`playSpeech(text, motion, soundEffect, specialEffect)`）。实测：开场白=期待+shuohua、猜测段=正经+wenhao、猜错段=抗拒+wuyu、公布答案=喜欢+yiwen+特效定位串（多数由规则库命中），猜对分支的 `pc_win` 配了 激动+jingya+bounce / 非常喜欢+xihuan+spin2；⑪ **改为走「模型规则库（情境）」**（老师指出 Live2D 表达主要靠它）：`live2d_rule` 里**新建 5 个情境** —— `画猜开场 / 画猜猜测 / 画猜猜对 / 画猜猜错 / 画猜公布`（`init()` 幂等新建，默认值抄现有语义最接近的情境；老师在**管理页 → 模型规则库**里逐条改 `expression` 部件 JSON / `action` / `sound_effect` 即可调表情与动作幅度，代码不用动）。后端 `reaction(scene, situation, text)` 取值顺序改为：① **模型规则库按情境**取 expression+action+sound_effect → ② 缺的字段用 `response_rule` 按文本匹配补 → ③ PNG 模式的 `motion` **以场景表 pc_* 为准**（规则库按文本匹配偶尔命中不相干词，如猜错的收尾语含"工整"却匹配到"激动"）→ ④ 默认 `aluona_zhengchang.png`。响应下发 `saySituation/sayExpression/sayAction/saySound/sayMotion` 与 `verdict*` 同名字段；页面新增 `pcApplyFx()`：段落播放时 `playSpeech(text, png表情, 音效, 动作)` + `l2dBridge.applySituation(JSON.parse(expression), l2dPartsByRegion)` —— 和正常对话的句级情境流程完全一致。实测：开场=画猜开场(星星眼+微笑/bounce/shuohua)、猜测=画猜猜测(眯眼舒适+撅嘴/yiwen)、猜错=画猜猜错(撅嘴/tilt/liuhan)、公布=画猜公布(眯眼舒适+微笑/swing/jingya)；⑫ **修「选项按键点了没反应、只触发 click 反应」**：桌宠页全局 click 处理器已排除 `button/input/.lock-btn/#adjustPanel`，但对话选项是 `div.choice-btn`，且 Live2D 模式 `hitTest` 按**模型区域**命中 —— 按钮压在模型身上时会被当成"点桌宠"：先 `choicesBox_display_none()` 把选项清空（1 秒后 `doChoice` 读到 null 直接 return），再放一次 `click` 反应 → 表现就是"点了「再来一局」没开新局，只看到 click 回应"。修法：全局 click 的排除选择器加上 `#choicesWrapper`；⑬ **结束不再询问**（老师定的交互）：删掉基于选项按钮的「再来一局 / 先不玩了」（`pcEndChoices` 与 `pictionary_again` 全部移除），一局结束只播两段话不再弹选项，并清掉对局中残留的「继续画/公布答案」；改为在**画板窗口**里放一个「**下一局**」按钮（`#btnNext`，位于「画好了」**左侧**、对局进行中或没有对局时隐藏、一局结束才出现），点它按**上一局难度**直接开新局；另外**一局结束后点画面任意位置** → 弹出「还没有进行中的对局」封面（随机/简单/中等/困难四个按钮）选新难度开局。画板侧把开局逻辑抽成 `startNewGame(diff)` 供封面按钮与「下一局」共用；⑭ **你画我猜接入事件簿**（老师要求：完成一局要计入事件簿，好让"被夸"能对上号）：新增 `pcEventStart/pcEventEnd`，在**开局**时记一条（"老师和你玩起了「你画我猜」，他画你猜（难度 X）"）、**一局结束时**按结果记一条 —— 猜对时特意写明「一次就猜对了 / 猜了 N 次就猜对了 …… **正得意地等着老师夸她**」，公布答案时写「猜了 N 次都没猜对 …… 有点不服气」，中途关画板记「这一局没玩完」。事件簿最近 5 条会注入对话提示词（【最近发生的事】），所以老师随后夸一句「阿罗娜真棒」，桌宠能知道是因为刚才画猜发挥好被夸。★ 仍然**不写 pet_memory**，事件文本里**不含题目答案**（答案只在游戏模块与画板窗口）；⑮ 猜对庆祝语按轮次分档：`winLine()` 在第 1 轮时要求"得意邀功、让老师夸你（例如「一次就猜对啦！快夸我！」这种口气）"，模板兜底文案同步改成「一次就猜对啦！是「X」对不对～ 快夸我！」。实测事件簿写读链路（`POST /ai/event-book` → `/ai/event-book/recent` 可见，会进提示词）正常，测试数据已清理
- 2026-09-14 | 新增：网易云「音乐操控器」独立蓝白面板窗口（第 18 节思路落地） | `static/音乐播放器.html`(新)、`static/桌宠桌面版.html`、`zhuochong-electron/main.js`、`preload.js`、`config/WebConfig.java` | ① **形态**：独立无边框小窗口（560×196），复用基础设置/画板那套（透明 + 位置记忆 `musicX/musicY` + 下方滑入/滑出动画 + screen-saver 同级置顶 + `backgroundThrottling:false`），**播放真身仍在桌宠页**（`<audio>`+`neteaseQueue`），窗口只做遥控器：`musicCmd`（面板→main→桌宠页） / `music-panel-state`（桌宠页→main→面板，每秒一次）；② **六个控件**：上一首（**历史回退栈** `musicHistoryStack`，最多 5 首，播放过的/被跳过的都算，栈空则提示）、□暂停⇄▶继续、下一首、**▽播放列表下拉**（当前队列"正在播+待播"，点任意一首= **插播** `playTemp/playTempIndex`：立刻切过去播、**不动 idx 不改队列顺序**，播完 `onEnded` 回到原队列下一首）、隐藏操作面板（滑出后 `hide()`，音乐继续）、×关闭音乐播放器（先让桌宠页停止播放再关窗）；③ **专辑封面**为蓝白唱片占位（后端 `NeteaseService` 只返回 `{id,name,artist}`，没有 `picUrl`，未扩接口）；④ 仅在**播放类指令**后自动弹出（播放音乐/收藏歌单/点歌搜索/序号点歌/选项按键点歌 五处挂 `showMusicPanel()`），托盘也加了一项「音乐播放器（打开面板）」；⑤ 指令「上一首/上一曲/前一首」从原来的占位提示改为真正回退；`buildMusicIdlePrompt()`/闲置提示改用 `musicCurrentSong()`（插播优先）；⑥ 踩坑记录：`音乐播放器.html` 同样要进 `WebConfig` 白名单（见第 7 节坑点 30），实测 200；`main.js` 里 `musicPos/musicWinRef` 声明位置与 `let` 重复声明冲突（Node 语法检查直接报 `Identifier already declared`），已合并到窗口变量区；⑦ 修「播放音乐（不带歌名）返回『推荐歌单加载失败』」：先逐个探接口排除后端问题（`/ai/music/ai-recommend`、`/search`、`/recommend`、`/similar`、`/favorite` 全 200，含 50 个中文名的长 `exclude` 也 200），定位到前端两处：**(a)** `neteaseQueue.search(kw)`/`getUrl(id)` 在参数为空时 axios 会**丢掉该 query 参数** → 后端 `@RequestParam` 缺参直接 **400**（正是老师控制台那条 `Failed to load resource: 400`），已加空值守卫；**(b)** `fillMore()` 的候选被 `playedHistory`（最多 200 条已播记录）**全部过滤掉** → 队列仍为空 → 报"加载失败"，现在候选全被历史挡掉时**放宽一次去重**兜底入队；顺带 `aiRecommendSongs()` 过滤空串/null 歌名、`fillMore()` 打印来源（similar/ai/hot）与候选数、失败文案提示去控制台搜 `[音乐]`；⑧ 追加两处（老师反馈）：**(a)** 「网易云+歌名 / 点歌 / 点一首」也算播放类指令 → 也挂 `showMusicPanel()`（呼出/重新滑入），至此 6 处呼出挂点：播放音乐、收藏歌单、搜索点歌、网易云点歌、序号点歌、选项按键点歌；**(b)** 修「面板隐藏后再呼出会跑到桌面右下角、不记得上次位置」：根因是 **隐藏动画与位置保存赛跑** —— `hideMusicPanel()` 只做了"向下滑出 + hide()"，而位置保存靠 `setupSettingsWindow` 里 **400ms 防抖**的 `move` 监听，动画期间它可能正好抓到"半出屏"的坐标（或干脆什么都没记），于是 `musicPos` 仍是默认值 → 下次按默认右下角出现。修法：① 隐藏**前**先判断"窗口至少 60% 在屏幕内"就立刻存 `musicPos`（并打日志 `music panel pos saved: x,y`）；② 隐藏动画结束后把窗口**摆回原位**，下次 `show()` 直接就位；③ 面板的位置保存回调加同样的"≥60% 可见才记"闸门，防止动画中途的半出屏坐标写进配置；④ 重新滑入的动画结束后再 `setPosition` 确认一次；⑨ 修「▽列表渲染不全、超出面板被切掉」（老师反馈）：根因是**窗口只有 196px 高**，下拉要长到卡片外面 → 浏览器窗口外的东西根本画不出来（`body{overflow:hidden}` 又补一刀）。做法：`#card` 改为固定高 184px 且**贴窗口顶部**，打开列表时面板页发 `musicPanelResize(true)`（新增 IPC `music-panel-resize`）→ 主进程 `musicPanelResize()` 把窗口**向下延申**到 `MUSIC_H + 276 = 472`（**顶边不动**，符合「▽ 下拉从下方展开」的手感；按屏幕下沿夹取，空间不够也绝不越界），列表用 `top: 40px` 在按钮**下方**展开、`max-height` 按当前可视高度收紧（内部滚动）；收起时机统一走 `setListOpen(false)`（点列表项 / 点别处 / Esc / 面板失焦）→ 收回原高度。位置保存因此改回**顶边锚定**（`musicPos = {x, y}`，延申时顶边不变，直接存即可）。至此该面板的坑：必须进 WebConfig 白名单、`let` 重复声明、隐藏动画与位置保存赛跑、下拉超出窗口被裁；⑩ 两处追加（老师反馈）：**(a)** ▽列表**右侧仍被切** —— 列表宽 320px，按钮原来在「下一首」右边（控件行第 4 个），从那儿向右展开超出了 560px 宽的窗口 → 把「▽列表」**移到控件行最左**（`上一首` 左侧），下拉从按钮左边缘向右展开即完整落在窗口内（160+320 < 560）；**(b)** 面板开着时关掉桌宠，再打开桌宠会发现**播放器/画板还开着**（主进程 `window-all-closed` 不退出、子窗口活着）→ 在桌宠窗口的 `closed` 里 `destroy()` 掉音乐面板与画板窗口（桌宠都没了，不必再演关闭动画）；⑪ 托盘「♻️ 重启后端**+ 桌面桌宠**」（老师要求）：原来只重启后端，而**改了 `main.js`/`preload.js`（主进程、IPC、窗口逻辑）时"重新加载桌宠页面"是不够的**（只刷网页，主进程与 preload 仍是旧的）—— 现在这一条同时做三件事：① 停后端并重新拉起（沿用原流程）；② **清 Chromium 的页面/脚本缓存**（`session.clearCache()` + `clearStorageData({storages:['shadercache','cachestorage','serviceworkers']})`，**保留 localStorage**，播放历史不丢）；③ `app.relaunch()` + `app.exit(0)` 重启 Electron 本体（`relaunch` 的语义是"当前实例退出后再起新进程"，不会撞单实例锁）。新进程带 `--wait-backend`：`bootPetPage()` 里**只等后端就绪、不再 spawn 一次启动脚本**（避免两个 mvn 抢 8080），起来自动切回桌宠页；托盘文案同步改为「♻️ 重启后端 + 桌面桌宠（改 Java / 主进程代码用）」
- 2026-09-15 | 配置外置：数据库连接与本机路径改为「环境变量优先」（分发体验要求） | `config/MyBatisConfig.java`、`src/main/resources/application.yml`、`环境变量配置.bat`(新)、`一键启动.bat`、`zhuochong-electron/启动后端.bat`、`README.md`、`LICENSE`(新，AGPL-3.0) | ① **数据库不再硬编码**：`MyBatisConfig` 按「**环境变量** `ZC_DB_HOST/PORT/NAME/USER/PASSWORD` → `application.yml` 的 `zc.db.*` → 内置默认值」取值，并在启动日志打印实际连接（`[配置] 数据库 host:port/db 用户=xxx`）便于排查；② **本机路径全部外置**：`application.yml` 的 tts.python/api-dir、netease.node/app、bili.room-id 改成 `${ZC_PYTHON:}` 这类占位（留空则不自动启动，避免对端没有该工具时报错）；③ 新增 **`环境变量配置.bat`**（GBK+CRLF，开发机填真实值，发包时由打包脚本替换为 `CHANGE_ME_DB_PASSWORD`）——「改一处就够」，`一键启动.bat` 与 `zhuochong-electron/启动后端.bat` 都会自动 `call` 它（IDEA 启动时需自行填 application.yml 或设系统环境变量）；④ 分享包同步：5 个文件 + 安装说明新增「环境变量」章节，打包自检 **全 PASS**；⑤ **安全事件**：导出真实库时发现 `ai_config` 里存着真实 DeepSeek Key 与 B站 Cookie（已从 `db/seed.sql` 清空；项目尚未 git init、历史无泄露），老师已轮换 Key；⑥ LICENSE 采用 **AGPL-3.0**（含第三方素材例外：角色形象/立绘/模型/配音不在授权范围内），README 许可段落同步
- 2026-09-14 | 分发防护：必做四条落地（水印 / 清个人数据 / 不给密钥 / 免责声明） | `zhuochong/打包工具/`(新：`1-写入水印.ps1`、`2-打包前清理.ps1`、`3-打包前自检.ps1`、`使用说明.txt`)、`README.md`(新，项目根)、`static/桌宠桌面版.html` | ① **一人一包水印**：`1-写入水印.ps1 -Name 体验者` → 生成水印码（如 `260915-A7F3`）写入包内 `版本信息.txt` + `package_meta.json`（可见），并把码编成**零宽字符**（U+200B/U+200C）塞进包内页面 + `package_meta.zw`（界面/源码肉眼看不出，复制或查十六进制可还原），同时在 `打包工具/水印登记.txt` 留档"谁拿了哪一版"，泄露可溯源；② **清个人数据**：`2-打包前清理.ps1 -CleanDb` 清空 `ai_history/pet_memory/event_book/test_history/pictionary_game/pictionary_guess/tts_reference/adminlist`、抹掉 `ai_config` 里的 `api_key/bili_cookie/netease_uid/refer_wav/gpt_model_path/sovits_model_path`、删掉 `basicinfo` 非 1 行；并删除包内 `desktop_vision_log/pictionary_shots/tts_cache/麻将错题截屏/标注图片/logs/target`；③ **去个人化**：包内硬编码的 MySQL 密码（`MyBatisConfig.java`、`操作册.html`）、百度网盘绝对路径与本人姓名（`application.yml`）替换成占位（**只改包那一份**，不动开发目录）—— 这三处是本次实测扫出来的真实泄露点；④ **自检闸门**：`3-打包前自检.ps1` 扫 `sk-`/B站 Cookie/密码/绝对路径/姓名/`C:\Users`、检查痕迹目录是否已空、检查库里密钥字段与个人表是否清零、检查水印与 README 是否齐全，全 PASS 才发（退出码=失败项数）；⑤ **免责声明/使用条款**：项目根新增 `README.md`（含角色版权归原权利方、素材不随代码授权、禁二次分发、水印溯源），桌宠页新增 `showTermsOnce()` —— 首次启动弹一次条款，点「我已知悉」记 `localStorage.terms_accepted_v1`，之后不再弹。**未做**（老师定：离线软件挡不住有心人，不做密钥体系）：Ed25519 离线授权码、asar/混淆、反调试
- 2026-09-13 | 修「自我介绍第一段丢失、直接跳到第二段」：分页改为跟着**播放**翻，不是跟着消息到达 | `static/桌宠桌面版.html` | **根因**：上一版把 `speechPageFollow()` 挂在 SSE `onmessage`（消息到达）上，而语音是**排队播放**的 —— 语音有缓存时（命中仅 0.05~0.28s）7 句几乎同时到齐，显示瞬间就连跳两段，第一段几乎看不到。**修法**：① 翻页改到 `playNext()` 里、`ttsAudio.src` 赋值后调用 `speechPageFollow(item.text)` —— **这句真正开始播放时才切段**，显示与朗读严格同步；② `onmessage` 里只入队，不再翻页；③ 兜底定时器改为**按需启用**（`speechPageFallback`）：只有"没有逐句进度"的场景才挂 —— TTS 关闭（`showText(true)`）、SSE 流失败、整段降级合成（`fallbackFull`），避免定时器抢在语音前面翻页；④ 用 Node 复刻后端 `splitSentences` + 匹配逻辑做离线验证：7 句 → 句3 翻第2段、句6 翻第3段，无提前翻页

---

## 15. QQ 群机器人接入（方案文档，未开工）

思路：把桌宠的"大脑"（`DeepSeekService` 的提示词 + 长期记忆 `pet_memory` + 事件簿 + 风格语料 + `TtsService` 语音 + `aluona/*.png` 表情图）复用到 QQ 群，只替换"耳朵和嘴巴"（接入层），新增独立模块（建议包 `com.cy.qq` + 设置窗口「🐧 QQ 机器人」模块页，默认关闭）。

- 原理差异：桌宠是**界面驱动**（Electron 窗口 → `/talk`），QQ 是**事件驱动**（QQ 服务器推消息 → 后端 → 调发送接口），中间那层 Spring Boot + DeepSeek + MySQL **几乎原样复用**（约 60~70%）
- 路线：① 第三方协议 NapCat/OneBot 11（需 QQ 小号、功能全、有封号风险）② 官方开放平台（合规、需认证审核、能力与频率受限）
- 建议：本机 + NapCat + 小号先跑通（P0 文本闭环）→ P1 图片/语音 → P2 频控/权限/白名单 → P3 按上云清单常驻

完整方案见 → **`docs/QQ机器人接入方案.md`**（含复用地图、`qq_config`/`qq_message_log` 表结构、接口清单、渠道提示词规则、分期验收标准、上云 10 步清单与风险合规）

---

## 16. 发布与提速（方案文档 + 已完成导出）

- 全量方案见 → **`docs/发布与提速方案.md`**：含 TTS 实测耗时（单句 1~3s / 缓存 0.05~0.28s）、项目体积构成、本地音乐移除清单、提速优化清单（首句立即显示 / TTS 并行 / 预热 / 参数调优 / stream_v2pro 评估）、桌宠本机 vs 云端 profile 拆分、分发形态 A(绿色包)/B(云壳)/C(Docker) 对比、素材版权、打包改造 checklist、分期计划
- **已完成导出**（本次新增，可直接用于分发）：
  - `db/schema.sql` —— 20 张表建表语句（MySQL 8，无 BOM）
  - `db/seed.sql` —— 564 条默认内容：`ai_prompt`(人设) / `basicinfo` / `conversation(_choice)` / `response_rule`(本地规则库) / `command_lib` / `live2d_expression_map` / `live2d_rule` / `live2d_part` / `idle_activity` / `style_exemplars` / `effect` / `operation` / `ai_config`（**已脱敏**：Key、Cookie、本机路径、房间号、开关全部清空置 0）
  - 不含：`ai_history` / `test_history` / `pet_memory` / `event_book` / `tts_reference` / `adminlist`（个人数据与运行期数据）
- 结论要点：**桌宠后端不上云**（TTS 用本机 GPU、Robot 截屏与 Electron 控制强绑本机），上云对象是 QQ 机器人（cloud profile）；**提速瓶颈在 TTS 而非本机占用**，优先做"首句立即显示 + 句级并行"

---

## 17. 陪玩点评模式（方案 + P1 已实现）

想法：新增一个"陪玩"状态 —— **短间隔读屏截图 + 一句弹幕风短点评**，像在旁边陪你玩；允许人工告知当前在做什么（B站/麻将/蔚蓝档案…），没告知就自己判断；该状态下**不做鼠标跟踪、点评时也不看回用户**。

方案见 → **`docs/陪玩点评模式方案.md`**（现状冲突点、状态机与语境表、一步法点评流水线、画面变化检测、话痨控制参数、成本估算、配置表与设置页、分期与风险、待确认 6 项）

关键结论（**P1 已实现**）：
- 现有闲置**不能直接复用**：`initIdle()` 里 `mousemove/keydown/click/scroll` 任一都会把计数清零（一直动鼠标就永远不触发），且 AI 闲置有"2 分钟无 AI 调用 + 最小 120s"双重门槛 → 新模式用**独立计时器**，与现有闲置并列且互斥（陪玩模式下普通 AI 闲置让路）
- 点评走**一步法**：`deepseek-v4-flash-vision-exp` 一次调用直接输出点评 JSON（`max_tokens=300`），复用现有 `showResult()` 播放链路
- 省成本/防烦人：**画面变化检测**（64×40 灰度平均差，静止不点评）+ 连续上限 3 句后静默 45s + 概率延长间隔 35% + 短句硬截断 ≤20 字 + **本状态音量缩放到 45%**
- 冲突处理：本模式下 `gazeOnUser()` 直接 return（不看回老师）、不调用 `scheduleIdleFollow()`；麻将自动打牌 / 抽卡 / 牛奶 / 调整 / 选项编辑 / 正在说话 → 一律暂停
- 落地件：表 `commentary_config` + `commentary_context`（5 语境，提示词可在设置页编辑）、`CommentaryService` + `CommentaryController`（`/ai/commentary/once|reset|config|context`）、桌面版右侧 **🎬 按钮** + 指令（`陪我玩 [语境]` / `别看了` / `点评一下`）、设置页 **🎬 陪玩点评**（参数 + 语境表 + 试点评）
- 未做（P2）：`manual_ttl_min` 人工语境到期回落自动判定；与直播模式的优先级联动