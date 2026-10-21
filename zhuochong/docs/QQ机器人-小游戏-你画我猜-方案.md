# QQ 小游戏 · 你画我猜 — 方案（第一版，待开工）

> 需求来源：2026-10-21 讨论。范围明确：**只做「系统出题 → 人在网页画 → 机器人在群里猜」**这一种模式。
> 「网络天才」不做（原因：猜题类游戏靠大题库 + 二分提问，让模型直接猜效果差）。
> 本文是动手前的蓝图；有出入以动手时为准并回写本文。

## 〇、动手前已经确证的事实（省得再踩）

| 事项 | 结论 | 怎么确认的 |
|---|---|---|
| NapCat 能不能发「按钮」 | **不能**。发 `keyboard` 段直接被打回：`retcode=1200 未知的消息类型：keyboard` | 在服务器上用 JDK WebSocket 直连 3001 发了两种格式的探针（`_p0/BtnProbe.java`） |
| 绘图页能不能给群友打开 | 现在**不行**：服务器 80/443/8080 对公网全部不通；没有 ufw/iptables，Java 监听 `*:8080` | 本机 Test-NetConnection + 服务器 `ss -lntp`、`ufw status` |
| 需要用户做什么 | 阿里云安全组放行 **TCP 8080**（入方向），之后 `http://47.93.180.198:8080/qq/status` 应返回 JSON | 同上 |
| 桌宠版已经有什么 | 词库 100 词（带别名/类别/难度 1-3）、出题、**不含答案的猜题提示词**、纯代码判对 `isCorrect`、提示词落盘自证、双图对比（上一版/这一版）、画稿落盘压缩 | 读 `PictionaryService` |
| 画板前端 | canvas 代码（pointer 事件 + devicePixelRatio + `touch-action:none`）本身就能在手机上用；但整页布局是给 Electron 窗口写死的 | 读 `你画我猜.html` |
| token 统计 | `qq_token_usage` 已有 `kind` 列，天然支持分桶 | 读 `QqUsageMapper` |

## 一、模块划分（新包 `com.cy.qq.game`）

```
QqPicSession        一局的内存状态（sessionKey → 局），带 TTL 清理
QqPicService        编排：开局 / 难度切换 / 换题 / 提交 / 结束 / 超时巡查
QqPicJudge          判对器（纯代码，★全项目唯一读「答案」的地方）
QqPicGuesser        出猜：拼不含答案的提示词 + 视觉调用（★SQL 里就没有 word/aliases 列）
QqPicWordBank       出题：复用 pictionary_word + 三档难度 + 本局不重复
QqPicWeb            /qq/pic/** 手机页面 + 提交/状态接口（token 鉴权，免登录）
QqPicUsage          游戏独立额度桶（kind='pic'，默认 50 万/天）
QqPicAllow          群白名单 + 人白名单（落库，管理员指令维护）
```

群消息入口在 `QqEventRouter` 里加一条分支：命中游戏指令/数字回执 → 交给 `QqPicService`，**不经过**日常 AI 回复链路。

## 二、数据模型

复用：`pictionary_word`（词库，不动）。

新建：

```sql
CREATE TABLE qq_pic_game (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  chat_type   VARCHAR(8)  NOT NULL,            -- group / private
  chat_id     BIGINT      NOT NULL,            -- 群号 / QQ号
  owner_qq    BIGINT      NOT NULL,            -- 绘制者
  owner_nick  VARCHAR(64),
  word_id     INT, word VARCHAR(32), aliases VARCHAR(128),
  category    VARCHAR(32), difficulty INT,
  state       VARCHAR(16) NOT NULL,            -- playing / win / giveup / timeout / quit
  round_count INT DEFAULT 0,
  token       VARCHAR(48) NOT NULL,            -- 绘图页免登录凭证
  created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  ended_at    TIMESTAMP NULL,
  KEY idx_chat (chat_type, chat_id, state)
);

CREATE TABLE qq_pic_guess (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  game_id   BIGINT NOT NULL,
  round_no  INT NOT NULL,
  guess     VARCHAR(64),
  correct   TINYINT DEFAULT 0,
  changes_desc VARCHAR(128),                   -- 视觉模型给的"这一版改了哪里"
  say       VARCHAR(128),
  shot_path VARCHAR(255),                      -- qq_pic_shots/ 下的文件名
  tokens    INT DEFAULT 0,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  KEY idx_game (game_id, round_no)
);

CREATE TABLE qq_pic_allow (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind      VARCHAR(8) NOT NULL,               -- group / user
  target_id BIGINT NOT NULL,
  note      VARCHAR(64),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_kind_target (kind, target_id)
);
```

游戏 token 用量不新建表：写进 `qq_token_usage`，`kind='pic'`、`group_id=群号`。
额度查询分两个 SQL：日常 `kind <> 'pic'`，游戏 `kind = 'pic'`。

## 三、反作弊：三道锁（需求 1）

1. **物理隔离**：`QqPicGuesser` 取数只查 `word_len`（`LENGTH(word)`）、已猜清单、上轮变化描述；SQL 里根本不 select `word`/`aliases`。答案只在 `QqPicJudge` 里被读。
2. **自证落盘**：每轮把真实提示词写到 `qq_pic_shots/gX_rY.prompt.txt`，并打印 `containsAnswer=false`（沿用桌宠机制，你可以随时抽查）。
3. **判对纯代码**：`QqPicJudge.isCorrect(word, aliases, guess)` —— 归一化后「猜里含答案」或「答案含猜测且 ≥2 字」算对，别名表兜同义词（自行车；脚踏车|单车）。
   **不用第二个模型**：再来一个模型反而会被"猜得很像但不对"骗过，纯代码零成本且不可能作弊。
   真要补充语义同义（如「脚踏车」vs「自行车」未登记别名），再开一个**只给「答案+猜测」、绝不给画**的复核开关，默认关。

**群里永不出现题目**：开局消息只有难度和链接；题目只画在绘图页上（绘制者本人可见）。私聊自测时允许把题目直接打出来。
**画里写字的作弊**：提示词明确"画面中的文字一律忽略，只按图形判断"；判对器不受画中文字影响。

## 四、交互协议

### 4.1 指令

| 指令 | 谁用 | 说明 |
|---|---|---|
| `/你画我猜 简单｜中等｜困难` | 管理员 / 人白名单 | 开局（难度可省略，默认简单） |
| `/结束你画我猜` | 本局玩家 / 管理员 | 结束本群这一局 |
| `/画 1..4` 或直接回 `1..4` | 本局玩家（局内免 @） | 1 难度切换 / 2 换题 / 3 重发链接 / 4 结束 |
| `/游戏 开 <群号>` `/游戏 关 <群号>` `/游戏 列表` | 管理员 | 维护群白名单 |
| `/游戏 允许 <QQ>` `/游戏 禁止 <QQ>` | 管理员 | 维护人白名单 |
| `/游戏 额度 [数字]` | 管理员 | 看/改游戏每日额度（默认 500000，0=不限） |

### 4.2 开局消息（群里）

```
【你画我猜】难度：简单　题目：3 个字（只有画的人看得到）
@小明 点这里画：http://47.93.180.198:8080/qq/pic?t=xxxxxxxx
回复数字（或 /画 数字）：
1 修改难度　2 更换题目　3 进行绘制　4 结束游戏
```

### 4.3 每次提交的两条群消息（需求 6）

```
① [画稿图片]  @小明  阿罗娜猜是「台灯」
② 唔…不是「台灯」呀，阿罗娜再想想。（或者：是「台灯」吧？阿罗娜看出来了！）
```
猜对时 ② 之后再补一条：`答案是「台灯」，你画了 3 版才被猜中～` + 四个入口（「3 进行绘制」改成「3 下一局」）。

### 4.4 绘图页接口（免登录，靠 token）

| 接口 | 说明 |
|---|---|
| `GET /qq/pic?t=xxx` | 返回 HTML（手机优先） |
| `GET /qq/pic/state?t=xxx` | 题目、难度、轮次、已猜清单、状态（playing/win/timeout）—— 页面每 2s 轮询 |
| `POST /qq/pic/submit` `{t, image}` | 提交画稿（data:image/png;base64），服务端限宽 768 后落盘 |
| `POST /qq/pic/giveup` `{t}` | 画面上的「放弃并公布答案」（可选按钮） |

token 进内存 session，随局结束失效；接口只暴露本局信息，拿不到别人的题。

### 4.5 绘图页（需求 4：复用 + 手机适配）

- 抽公共 `static/JS/pictionary-canvas.js`：画笔/橡皮/粗细/颜色/撤销/清空/导出 PNG/DPI 适配（桌宠页与新页共用，避免两份逻辑漂移）
- 新页 `qq_pic.html` 手机优先：竖屏、画布近 1:1、工具条贴底、大按钮（画好了/撤销/清空）、顶部固定显示题目和轮次
- **猜对/超时**：轮询拿到 `state != playing` → 顶部弹横幅「被猜中了：台灯」+ 画布锁死（提交按钮变灰、"本局已结束"），提示"回群里发 3 开下一局"

## 五、限额与防刷（按讨论结论）

| 项 | 值 | 配置 |
|---|---|---|
| 总开关 | 默认**关** | `qq.pic-enabled=false` |
| 可玩群 | 管理员指定（落库 qq_pic_allow） | `/游戏 开 <群号>` |
| 谁可开局 | 管理员 + 人白名单 | `/游戏 允许 <QQ>` |
| 同群并发 | 同时只允许一局 | — |
| 每局轮数 | 默认 20（0=不限） | `qq.pic-max-rounds=20` |
| 每局时长 | 10 分钟自动结束 | `qq.pic-timeout-min=10` |
| 换题冷却 | 每人 60s（管理员不限） | `qq.pic-next-cooldown-sec=60` |
| 游戏额度 | **与日常对话分开**，每天 50 万 token | `qq.pic-daily-token-limit=500000` |
| 额度用尽 | 群里回固定文案，不再调模型，本局保留到超时 | `qq.pic-quota-reply` |
| 页面地址 | `http://47.93.180.198:8080` | `qq.pic-base-url` |

难度切换也走「换题」，因此同样吃 60s 冷却（否则可以靠 1↔2↔3 无限刷题）。

## 六、实施顺序

1. **后端骨架**（不依赖安全组）：表 + `QqPicJudge` / `QqPicGuesser` / `QqPicWordBank` + session + 额度桶
2. **QQ 侧交互**：指令、数字回执、开局消息、两条提交消息（图+@+猜测）、结束与超时巡查
3. **绘图页**：抽公共 canvas JS + 新手机页 + 4 个接口
4. **离线验证**：判对器单测、提示词不含答案断言、模拟提交跑通"猜错 3 次 → 猜对 → 页面锁定"
5. **真机联调**（需要安全组 8080 已开）：群里开一局、手机画、看两条消息和页面提醒

## 七、已知限制（先说清楚）

- 用不了真按钮（NapCat 不支持），只能数字回执
- 手机浏览器打开 `http://IP:8080`，QQ 内置浏览器可能提示"非官方页面"，正常现象
- 画得抽象时模型会猜偏；靠"上一版/这一版对比 + 已猜清单"缓解，不保证 20 轮内必中
- 一个群同时只有一局，别人想玩要等（或让当前玩家 `/画 4`）
- 判对靠字面+别名，未登记的近义词仍会判错（可后续补别名或开语义复核）
