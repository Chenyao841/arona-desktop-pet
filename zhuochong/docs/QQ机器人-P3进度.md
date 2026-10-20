# QQ 机器人 · P3 进度（运维 + 管理员体系）

> 更新：2026-10-20 · 承接 `QQ机器人-云端部署进度.md`（P0 已完成）与 `QQ机器人-P1进度.md`（图片识别 + 表情包已完成）
> **P2（闲置响应）按你的要求先跳过**，直接做到 P3，并按新需求加了管理员体系。
> 说明：主进度文档写的时候仍被别的程序独占，所以这份单独落；你关掉占用它的程序后把本文并进 `### P3 运维` 即可。

## 一、本轮范围

| 项 | 状态 |
|---|---|
| 管理员 QQ 白名单（`ZC_QQ_ADMIN_QQ`） | ✅ |
| **私聊仅管理员**（其他人私聊一律不回，只写日志） | ✅ |
| 管理员指令：状态 / 群列表 / token 消耗 / 限额 / 开关群 / 静音 / 清上下文 / 清记忆 / 提炼记忆 / 表情概况 | ✅ |
| token 用量统计（按群、按 chat/vision、按天）+ 每日限额闸门 | ✅ |
| 记忆按「群号（+QQ号）」隔离 | ✅ |
| 日志轮转配置 + MySQL 定时备份脚本 | ✅ |

## 二、管理员体系

### 2.1 两条硬规则

1. **私聊只接待管理员**：非管理员私聊 → 只写一行日志，**完全不回复**（避免被陌生人当客服刷，也不暴露机器人存在）。
2. **管理指令走白名单**：只有 `ZC_QQ_ADMIN_QQ` 里的 QQ 号能用；普通群友发同样的 `/groups` 只会收到「你不是这个机器人的管理员哦～」，不花钱、不泄露数据。
   管理员在**群里 @ 机器人**发指令同样有效（`/off` 不带群号 = 操作当前群）。

### 2.2 指令清单

| 指令 | 作用 |
|---|---|
| `/help`（`/帮助` `/菜单`） | 指令清单 |
| `/status`（`/状态`） | 连接状态、自身 QQ、触发方式、运行时长、活跃群数、今日消耗、限额、收发计数、重连次数、记忆隔离模式 |
| `/groups`（`/群` `/群列表`） | **运行中的群聊**：群号《群名》/ 消息数 / 回复数 / 最后活动 / 今日消耗 / 是否关闭·静音 |
| `/usage`（`/消耗` `/token`） | 今日按类型（chat、vision）+ 按群消耗；最近 7 天；历史累计 |
| `/limit 200000`（`/限额`） | 设置每日 token 上限；`/limit off` 或 `0` = 不限 |
| `/on <群号>` / `/off <群号>` | 开 / 关某群响应（关掉后连 @ 都不理，`/on` 恢复） |
| `/mute <群号> [分钟]` / `/unmute <群号>` | 临时安静（默认 30 分钟），期间不发言 |
| `/reset <群号>` | 清空该群会话上下文（`ai_history` 里 `qq_g{群号}`），长期记忆保留 |
| `/forget <群号>` | 清空该群长期记忆 |
| `/remember <群号>` | 立刻提炼该群长期记忆 |
| `/emoji`（`/表情`） | 表情包张数 / 本次已发 / 标注表位置 |

示例（私聊）：

```
/status
【运行状态】
连接：在线 ✓
自身 QQ：123456
触发方式：at（同一人 5s 冷却）
运行时长：0 分钟
活跃群数：1
今日消耗：6344 tokens / 1 次调用
每日上限：不限
已收事件：6，已回消息：3，发送失败：0
重连次数：0
记忆隔离：group（每群一份：阿罗娜-群{群号}）
```

## 三、token 统计与每日限额

- 数据来源：`DeepSeekService` 现在会把每次模型调用的 `usage`（prompt/completion tokens）回调出来；
  QQ 侧注册成监听器，处理消息前用 ThreadLocal 标记「正在服务哪个群」，回调就记到那个群头上。
- 落库：新表 `qq_token_usage`（`usage_date + group_id + kind` 唯一，`ON DUPLICATE KEY UPDATE` 累加），
  启动时自动 `CREATE TABLE IF NOT EXISTS`，不需要手动建表。
  `group_id = 0` 的行是桌宠端消耗（也记，方便看总盘子），**额度只算 QQ 那部分**。
- 闸门：超限后群消息不再调模型，直接回「额度用完了」，指令类（`/status` 等）不受影响。
- 查询：`GET /qq/status`（含 todayTokens / dailyLimit / quotaExceeded）、`GET /qq/usage`（明细，需登录态）。

## 四、记忆隔离

复用 `pet_memory` 表，用**独立角色名**当命名空间（不改表结构）：

| `qq.memory-scope` | 记忆角色名 | 说明 |
|---|---|---|
| `group`（默认） | `阿罗娜-群{群号}` | 整群共用，像"群里的一个角色"，省 token |
| `user` | `阿罗娜-群{群号}-QQ{QQ号}` | 每群每人一份，最细 |
| `off` | 不隔离（沿用桌宠「阿罗娜」） | 仅调试用，群聊会污染桌宠人设 |

每处理 `qq.memory-consolidate-every` 条群消息（默认 20）就在后台线程提炼一次，也支持 `/remember` 手动触发；
提炼调用同样计入该群 token 消耗。

## 五、运维（P3 原清单）

| 项 | 落点 |
|---|---|
| 日志轮转 | `deploy/logrotate-zhuochong.conf` → 拷到 `/etc/logrotate.d/zhuochong`；NapCat 容器日志用 `--log-opt max-size=20m --log-opt max-file=5` |
| MySQL 定时备份 | `deploy/backup-mysql.sh`（容器内 mysqldump → gzip → 保留 7 天，文件头写了恢复命令）；放进 `/etc/cron.daily/` 或 crontab |
| 常驻 | `deploy/zhuochong-qq.service`（`Restart=always`、`TZ=Asia/Shanghai`） |
| 敏感值 | `deploy/env.qq.example` → 复制成 `/root/arona/.env.qq`（`chmod 600`），管理员 QQ / WS token / 数据库密码都在这里 |
| 到期迁移（试用实例回收） | 见下表 |

**迁移到新服务器清单**（照抄即可）：

1. `bash deploy/backup-mysql.sh` 拿最新 `db03-*.sql.gz` → 新机器 `gunzip < xxx.sql.gz | docker exec -i zc-mysql mysql -uroot -p db03`
2. 新机器装 Docker / JDK17 / clone 仓库 → 拷 `.env.qq`（权限 600）
3. `mvn -DskipTests package` 上传 jar，或直接在新机器构建
4. `cp deploy/zhuochong-qq.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now zhuochong-qq`
5. NapCat 容器起来后重新扫码登录 → 确认 3001 正向 WS 的 `host` 是 `0.0.0.0`、token 与 `.env.qq` 一致
6. `timedatectl set-timezone Asia/Shanghai` + 开 NTP
7. `curl -s 127.0.0.1:8080/qq/status` 看到 `connected:true` 就成了；再在群里 @ 一下验收

## 六、本机自测（假 NapCat，2026-10-20）

```
→ 群 /status（管理员）      ← 【运行状态】连接：在线 ✓ / 今日消耗 / 记忆隔离：group…
→ 群 /groups（管理员）      ← 【运行中的群聊】共 1 个 / 1. 999001《测试群》 消息 6 / 回复 3 …
→ 私聊 /usage（管理员）     ← 【token 消耗】按类型：chat 5942（3次） vision 402（1次）…
→ 私聊 /status（非管理员）  ← （无任何回复 ✓）
→ 私聊 /limit 200000        ← 已将每日 token 上限设为 200000
→ 私聊 /help                ← 指令清单（多行换行正常 ✓）
→ 群 /groups（非管理员）    ← 你不是这个机器人的管理员哦～
每日上限设成 1 时再 @ 闲聊  ← 今天我说话太多啦，额度用完了……（未调用模型 ✓）
```

数据库侧核对：

```
qq_token_usage:  2026-10-20  group 999001  chat    2 次  prompt 3875 / completion 62
                 2026-10-20  group 999001  vision  1 次  prompt 284  / completion 118
pet_memory:      阿罗娜-群999001  6 条   （桌宠的「阿罗娜」0 条，未被污染 ✓）
```

## 七、这轮修掉的两个 bug

1. **mapper 扫不到**：`@MapperScan` 只扫 `com.cy.mapper`，我把 `QqUsageMapper` 放在 `com.cy.qq` → 启动直接失败
   （`No qualifying bean of type 'com.cy.qq.QqUsageMapper'`）。已把扫描范围扩到 `{"com.cy.mapper", "com.cy.qq"}`。
2. **分段器把换行吃掉**：多行文本（管理报表）合并成一条消息时 `\n` 全没了，群里是一整坨字。
   已改成「行间换行保留 + 硬切时补回首部换行」，并加了单测（`QqMessageSplitterTest.多行报表保留换行不被粘连`）。

## 八、还没做

- **P2 闲置响应**（你要先跳过）：每群环形缓冲 200 条、≥25 分钟无 @ 才插话、被说「闭嘴」静默 2 小时
- P1 剩余的语音（`TtsService` → `record` 段）与引用回复
- 管理指令的 Web 化（现在只有 QQ 私聊 + `/qq/status` 自检接口；`桌宠设置页` 里加一个「🐧 QQ 机器人」页可以下一步做）
