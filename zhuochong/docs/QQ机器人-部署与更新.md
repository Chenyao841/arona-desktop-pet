# QQ 机器人 · 部署与版本更新

> 更新：2026-10-20 · 配套脚本都在 `zhuochong/deploy/`
> 这份文档回答一个问题：**首次部署怎么搞、以后怎么推新版本**。

## 〇、一页流程图（先看这个）

```
┌─ 本地（Windows，D:\IdeaProjects\zhuochong）─────────────────────┐
│  改代码 → deploy\sync-to-repo.ps1 把改动同步进 git 仓库工作区    │
│        → cd D:\IdeaProjects\zhuochong-github                    │
│        → git add -A && git commit && git tag vX.Y.Z             │
│        → git push origin main --tags                           │
└────────────────────────────────────────────────────────────────┘
                              ↓  GitHub: Chenyao841/arona-desktop-pet
┌─ 服务器（阿里云，/root/arona）──────────────────────────────────┐
│  首次：git clone/pull → 装 Maven → 放密钥 → update.sh          │
│        → 装 systemd 服务 → enable/start → 体检 → 群里冒烟       │
│  以后：bash /root/arona/zhuochong/deploy/update.sh              │
│        （= 备份DB → git reset --hard origin/main → 构建 →      │
│           切 jar → 重启 → 体检 /qq/status → 失败自动回滚）      │
└────────────────────────────────────────────────────────────────┘
                              ↓
                    群里 @ 一下做人工冒烟
```

**服务器上没有「安装程序」这一步**：它是纯源码仓库，靠 `mvn` 构建出 jar，systemd 负责常驻。
所谓「测试」= `update.sh` 的自动体检（连不上 NapCat 直接自动回滚）+ 群里人工冒烟两句。


## 一、先记住三件事（踩过才知道）

| 事实 | 影响 |
|---|---|
| **本地 `D:\IdeaProjects\zhuochong` 不是 git 仓库** | 真正的仓库是 `D:\IdeaProjects\zhuochong-github`（remote = `arona-desktop-pet`）。所以推送前要先同步（用 `deploy/sync-to-repo.ps1`） |
| **GitHub 仓库布局是「仓库根 → `zhuochong/` 子目录」** | 服务器上 Maven 项目在 `/root/arona/zhuochong`，不是 `/root/arona`。`deploy/zhuochong-qq.service`、`qq-bot-start.sh` 已按这个路径写好 |
| **本地 static 有近 2 GB**（GPT-SoVITS 权重 888MB + SoVITS 648MB + live2d 161MB…） | 仓库根 `.gitignore` 已排除这些目录 → **服务器 clone 里没有它们**，所以服务器构建出来的 jar 天然很小（约 40MB，含 6.7MB 表情包）。本地要打包上传则必须加 `-Pcloud` |

另外：`.env` 类文件**没有**被 gitignore → 密钥一律放仓库外 `/root/zc-secrets/env.qq`（`chmod 600`）。

## 二、目录约定（服务器）

```
/root/arona/                       仓库（git clone）
├─ zhuochong/                      Maven 项目（pom.xml）
│  ├─ deploy/                      所有部署脚本
│  │  ├─ update.sh   ★ 一键更新
│  │  ├─ rollback.sh   回滚
│  │  ├─ qq-bot-start.sh           systemd 调的启动脚本
│  │  ├─ zhuochong-qq.service      systemd unit
│  │  ├─ backup-mysql.sh           每日备份
│  │  └─ logrotate-zhuochong.conf  日志轮转
│  └─ target/…jar                  构建产物（不直接用）
├─ zhuochong-electron/             桌面端（云端不跑）
└─ docs/

/root/zc/app/zhuochong.jar         当前生效的 jar（update.sh 放进来）
/root/zc/app/zhuochong.jar.prev    上一个版本（回滚用）
/root/zc-secrets/env.qq            密钥（仓库外，600）
/root/backup/db03-*.sql.gz         每日数据库备份
```

## 三、首次部署清单（照顺序做，可直接复制）

```bash
# 0) 服务器准备（若还没做）
#    Docker + MySQL 容器 zc-mysql + NapCat 容器 + JDK17 —— 见《QQ机器人-云端部署进度.md》

# 1) 【关键】先把代码拉到最新，否则拿不到 deploy/ 里的新脚本
#    已有旧 clone（文档里是 /root/arona）：
git -C /root/arona remote -v                       # 确认是 arona-desktop-pet
git -C /root/arona fetch --prune origin
git -C /root/arona reset --hard origin/main        # 服务器上不手改代码，直接对齐远端
git -C /root/arona log --oneline -3                # 应能看到刚推的 commit
#    还没有 clone 的话：git clone https://github.com/Chenyao841/arona-desktop-pet.git /root/arona

# 2) 装 Maven（服务器上构建需要；也可用 ./mvnw，但它会自己下载 Maven）
apt-get install -y maven && mvn -v
#    国内建议配阿里云镜像 ~/.m2/settings.xml（mirror of central），否则首次构建很慢

# 3) 密钥放仓库外（.env 没被 gitignore，绝不能放仓库里）
mkdir -p /root/zc-secrets
cp /root/arona/zhuochong/deploy/env.qq.example /root/zc-secrets/env.qq
vi /root/zc-secrets/env.qq        # 填 ZC_DB_PASSWORD / ZC_QQ_WS_TOKEN / ZC_QQ_ADMIN_QQ
chmod 600 /root/zc-secrets/env.qq

# 4) 首次构建 → 把 jar 放到槽位 /root/zc/app/zhuochong.jar
bash /root/arona/zhuochong/deploy/update.sh
#    此时服务还没装，脚本会提示"还没装服务"并正常退出（jar 已就位）

# 5) 装 systemd 服务并启动
cp /root/arona/zhuochong/deploy/zhuochong-qq.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now zhuochong-qq
systemctl status zhuochong-qq --no-pager

# 6) 体检
curl -s http://127.0.0.1:8080/qq/status | head -c 500
#    要看到 "connected":true 才算通；不通就看 journalctl -u zhuochong-qq -n 50
#    再发一句：curl -s 'http://127.0.0.1:8080/qq/emoji' | head -c 200

# 7) 定时任务
cp /root/arona/zhuochong/deploy/backup-mysql.sh /etc/cron.daily/zc-mysql-backup
chmod +x /etc/cron.daily/zc-mysql-backup
cp /root/arona/zhuochong/deploy/logrotate-zhuochong.conf /etc/logrotate.d/zhuochong
```

## 四、日常更新（推荐流程：git 推送 + 服务器一键更新）

```
① 本地改代码（D:\IdeaProjects\zhuochong）
      ↓
② 同步进仓库工作区
   powershell -ExecutionPolicy Bypass -File deploy\sync-to-repo.ps1
      ↓
③ 提交 + 推送（脚本会打印 git status，确认没混进密钥/大文件）
   cd D:\IdeaProjects\zhuochong-github
   git add -A; git commit -m "feat: 表情包只在大情绪时发"; git tag v0.5.0; git push origin main --tags
      ↓
④ 服务器上一键更新（备份 → 拉代码 → 构建 → 切 jar → 重启 → 体检 → 失败自动回滚）
   ssh root@47.93.180.198
   bash /root/arona/zhuochong/deploy/update.sh
      ↓
⑤ 群里 @ 机器人做一次冒烟（看到回复就完事）
```

`update.sh` 的失败保护：**体检 80 秒内连不上 NapCat → 自动换回上一个 jar 并重启**，退出码 1。所以哪怕新版本有问题，群里也不会长时间失联（NapCat 那边的 WS 会自己重连）。

不想在服务器上构建（服务器慢/没装 Maven）时，用本地方案：

```powershell
powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -Restart
# 本地 mvn -Pcloud package（排除 2GB 重资源）→ scp 到 /root/zc/app/zhuochong.jar → 重启 → 体检
```

## 五、首次上线后必须做：填模型 Key

`ai_config` 表存在 ≠ 能用。`api_key` 为空时机器人**能连上 QQ 但只会回兜底文案**
（`尚未设置API Key…` / `唔……我这会儿有点走神`），看着像"通了但不会说话"。

> Key 存在数据库里（管理页「基础设置」），**不在** `/root/zc-secrets/env.qq` 里，也不用改 git。
> `DeepSeekService` 每次调用都重新读库，所以**改完不用重启服务**。

### 方式 A：直接 SQL（最快）

```bash
# 从 env 里取库密码（不必手打），查当前状态
PW="$(grep '^ZC_DB_PASSWORD=' /root/zc-secrets/env.qq | cut -d= -f2-)"
docker exec -e MYSQL_PWD="$PW" zc-mysql mysql -uroot -t db03 -e \
  "SELECT id, LEFT(api_key,8) AS key_head, LENGTH(api_key) AS key_len, model, api_url FROM ai_config"

# 写进去（只改 Key，别动 api_url / model）
docker exec -e MYSQL_PWD="$PW" zc-mysql mysql -uroot db03 -e \
  "UPDATE ai_config SET api_key='sk-你自己的Key' WHERE id=1;"
```

`api_url` 应为 `https://api.deepseek.com/v1/chat/completions`（用中转/自建网关就填自己的），
`model` 一般 `deepseek-chat`（`deepseek-reasoner` 也行，但不支持 temperature）。

### 方式 B：SSH 隧道 + 管理页（还能顺带改人设）

8080 没对公网开放（安全组只开 22 ✓），本机开隧道即可：

```bash
ssh -L 8080:127.0.0.1:8080 root@47.93.180.198
```

浏览器开 `http://127.0.0.1:8080/桌宠设置.html` → 「基础设置」填 Key；同一页面能改
`ai_prompt` 人设、`basic_info`（名字/身份）、回答规则库。登录账号见 `application.yml` 的 `login:`（默认 `老师 / 123456`）。

### 验证 Key 生效

群里 @ 小号说一句 → 3 秒后应有正经回复；再查日志：

```bash
journalctl -u zhuochong-qq -n 40 --no-pager | grep -E '\[AI\]|api_key|\[QQ\] 收到'
```

还看到 `[AI] api_key 为空 → 走「未设置 Key」友好提示` 就说明没填对。

### 两个安全/成本提醒

- **备份里含 Key**：`/root/backup/db03-*.sql.gz` 里有 `api_key`，这些文件别随手乱传；`chmod 700 /root/backup` 更稳。
- **视觉模型**：图片理解用硬编码模型名 `deepseek-v4-flash-vision-exp`，与 `api_url`/`api_key` 同一份配置。
  Key 若不支持该模型，图片回复会退化成描述性文字，文字聊天不受影响。

## 六、什么进仓库、什么不进

| 内容 | 进 GitHub？ | 说明 |
|---|---|---|
| `src/main/java/com/cy/qq/**`、`DeepSeekService`、配置、`pom.xml` | ✅ | 全部业务代码 |
| `src/main/resources/static/qq_emoji/**`（39 张 + 标注表，6.7MB） | ✅ | 表情包素材随 jar 走 |
| `static/*.html`、JS、CSS | ✅ | 管理页等 |
| `deploy/**`、`tools/fake-onebot.js`、`docs/**` | ✅ | 脚本与文档 |
| `static/GPT_weights_v2`、`SoVITS_weights_v2`、`live2d`、`background`、`aluona`、`soundeffects`、参考音频、麻将截屏…（≈2GB） | ❌ 仓库根 `.gitignore` 已排除 | 桌面端专用；云端也没有 GPU 跑 SoVITS |
| `target/`、`node_modules/`、`*.log`、`hs_err_pid*` | ❌ | 构建/运行产物 |
| `.env.qq`、`notes.txt`、数据库密码、NapCat token | ❌ **绝不能进** | `.env` 没被 gitignore，所以放在仓库外 `/root/zc-secrets/` |

## 七、推送前先自检（本地，不花云上资源）

```powershell
# 1) 编译 + 单测（分段器）
.\mvnw.cmd -o -DskipTests test-compile
.\mvnw.cmd -o test -Dtest=QqMessageSplitterTest     # 需要 surefire 已缓存；没网就用下面的假 NapCat 回归

# 2) 离线端到端回归（不需要真 NapCat、不需要云服务器）
node tools\fake-onebot.js                            # 一个窗口
# 另一个窗口：
$env:ZC_QQ_ENABLED='true'; $env:ZC_QQ_WS_URL='ws://127.0.0.1:3001'
$env:ZC_QQ_WS_TOKEN='testtoken'; $env:ZC_QQ_SELF_ID='0'; $env:ZC_QQ_ADMIN_QQ='555999'
.\mvnw.cmd -o spring-boot:run
# 看假 NapCat 窗口是否收到 send_group_msg（文字/图片/引用都对）
```

## 八、回滚

| 场景 | 做法 | 耗时 |
|---|---|---|
| 新版本行为不对，想立刻退回 | `bash deploy/rollback.sh`（只换 jar + 重启，不动 git / 数据库） | ~20s |
| 想把代码也退回去 | `git -C /root/arona reset --hard v0.5.0 && bash deploy/update.sh` | 2~3 分钟 |
| 数据库也要退（极少见） | `gunzip < /root/backup/db03-xxx.sql.gz \| docker exec -i zc-mysql mysql -uroot -p db03` | 视数据量 |

数据库**迁移策略**：本项目所有新表都用 `CREATE TABLE IF NOT EXISTS` 在启动时自建
（`qq_token_usage`、`qq_blacklist`、`command_lib`、`live2d_expression_map` 都是这路子），
所以正常发版**不需要手工跑 SQL**，回滚也不会因为表结构卡住。
如果以后加了「改列/删列」这种破坏性变更，就在 `update.sh` 的备份之后、重启之前加一条迁移脚本并写进本文。

## 九、版本号与发布纪律

- 语义化版本 + git tag：`v0.4.0`（P0~P1）、`v0.5.0`（P3 管理员体系）、`v0.6.0`（P4 节奏/防刷）……
  `git tag -a v0.6.0 -m "回复节奏 + 防刷 + 私聊闲聊 + 引用回复"`，push 时带 `--tags`。
- 每次发版在 `docs/` 里留一份「本版改了什么 + 怎么验证」，现有 `P1/P3/P4进度.md` 就是这个作用。
- **密钥永远不进仓库**：`.env.qq`、`notes.txt`、`bili_cookie`、`api_key` 只在服务器 `/root/zc-secrets/`。
- 只在服务器上跑 `update.sh`，**不在服务器上手改代码**（`update.sh` 用 `reset --hard`，手改会被覆盖）。

### 9.1 发版时顺带改「机器人 QQ 名」（版本号）

机器人现在的显示名带版本，例如 `阿罗娜（v0.0.6测试）`。改名有两条路，都写好了：

**A. 发布时一起改（推荐）**

```powershell
# 本地：自动拼成 阿罗娜（v0.0.7测试），写进服务器 env，重启后应用连上 NapCat 自己改一次
powershell -ExecutionPolicy Bypass -File deploy\push-jar.ps1 -NickVersion 0.0.7

# 名字想完全自定义（中文要过 cmd，建议用 .bat 转发）：
deploy\发布到服务器.bat -Nick "阿罗娜（v0.0.7测试）"
```

```bash
# 服务器走 git 拉取那条路时：
bash /root/arona/zhuochong/deploy/update.sh --nick-version 0.0.7
bash /root/arona/zhuochong/deploy/update.sh --nick "阿罗娜（v0.0.7测试）"
```

**B. 不发版，临时改**

```
/nick                       看当前 QQ 名 + 配置里要改成什么
/nick 阿罗娜（v0.0.7测试）    立刻改（NapCat set_qq_profile）
/nick card 阿罗娜（v0.0.7测试） 只改本群群名片
```

**机制**：配置项 `ZC_QQ_NICKNAME`（→ `qq.nickname`）。连上 NapCat 拿到 `get_login_info` 的
当前昵称后**比一次**：不一样才调一次 `set_qq_profile`，一样就什么都不做。
所以重启/重连都不会反复改名；改名失败（老版本 NapCat 不认这个 action）会打日志，
连续失败 3 次就停下不再试，这时用 `/nick card`（`set_group_card`，兼容性更好）。

> 注意：群里**显示的是群名片优先**——想让版本号在某个群显眼，`/nick card` 更稳；
> 想全局都变，才需要改 QQ 名。

## 十、常见问题

| 现象 | 原因 / 处理 |
|---|---|
| `update.sh` 报「已经有一个 update.sh 在跑」 | 有锁 `/tmp/zc-update.lock`；确认没有别的部署在跑后 `rm -f /tmp/zc-update.lock` |
| 体检一直「服务已起但没连上 NapCat」 | ① NapCat 容器没起/没登录 ② token 与 `ZC_QQ_WS_TOKEN` 不一致 ③ 3001 端口 `host` 不是 `0.0.0.0` |
| 构建卡在下载依赖 | 给 Maven 配阿里云镜像（`~/.m2/settings.xml`），或改用 `push-jar.ps1` 本地打包上传 |
| jar 特别大（>1GB） | 用了本地打包但没加 `-Pcloud` |
| `sync-to-repo.ps1` 说没有变化 | 改动还没保存，或同步路径参数不对 |
| 改了 `ZC_QQ_NICKNAME` 但 QQ 名没变 | 看日志有没有 `set_qq_profile` 的 retcode：老版本 NapCat 可能不支持该 action → 用 `/nick card 新名字` 改群名片（群里显示的就是群名片），或手动在 QQ 里改一次 |
| 服务器 `git pull` 冲突 | `update.sh` 用 `reset --hard origin/main`，本地改动会被丢弃；要保留就先 commit/stash |
