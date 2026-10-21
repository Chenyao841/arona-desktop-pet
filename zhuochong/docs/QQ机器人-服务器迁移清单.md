# QQ 机器人 · 服务器迁移清单（阿里云 ECS → 轻量应用服务器 Lighthouse）

> 编写：2026-10-20 · 状态：**计划中（≥3 周后进行）**，当前线上仍在 ECS `47.93.180.198`
> 触发原因：ECS 2核8G 价格偏高，计划换 Lighthouse 2核8G（本次只做方案，不执行）

---

## 〇、结论速览

| 问题 | 回答 |
|---|---|
| 技术难度 | **低**。半天～1 天，其中大半时间在等 docker 拉镜像 + QQ 重新验证 |
| 性能会不会受影响 | **几乎没有**。CPU 是 IO 等待型负载（等 LLM API），2 vCPU 差异可忽略；内存 8G 绰绰有余（实际占用约 2.5–3GB） |
| 最大的风险 | **不是服务器，是 NapCat 小号换 IP 的登录风控**（可能要短信/扫码二次验证） |
| 最容易被忽略的坑 | 忘迁 NapCat 的两个卷（`config` + `qq`）、防火墙误开 3001/3306、新机拉不下 GitHub |
| 回滚方案 | 迁移后**先别释放旧 ECS**，观察 1–2 天；出事直接切回旧 IP |

### 性能核对（我们的真实占用）

| 资源 | 占用 | Lighthouse 2C8G |
|---|---|---|
| CPU | 调模型时基本全在等 HTTP；只有发版构建会短时打满 | 够（构建慢几秒） |
| 内存 | JVM `-Xmx1g` + MySQL 容器 ~500MB + NapCat 容器 ~0.6–1GB + OS/docker ~500MB ≈ **2.5–3GB** | 余量充足 |
| 磁盘 | 代码+jar ~200MB、docker 镜像 ~6–8GB、MySQL 数据几十 MB | 40–60GB 够 |
| 网络 | 每条回复 1–5KB、表情包 ~240KB/张、调 LLM 几十 KB/次 → 日均几十 MB | 够，但注意**月流量包**（拉镜像最费） |

> 顺带确认：如果现役 ECS 是**突发性能实例（t5/t6，有 CPU 积分）**，换成 Lighthouse 非突发规格，持续构建反而更稳。
> Lighthouse 是「峰值带宽 + 月流量包」，超出按量计费偏贵 —— **别在月末迁移**（拉 MySQL/NapCat 镜像会吃流量）。

---

## 一、风险清单（按严重度排序）

| # | 风险 | 说明 | 缓解措施 |
|---|---|---|---|
| 1 | **NapCat 小号异地登录风控** | 换机=换公网 IP，QQ 可能要求短信/扫码二次验证，甚至短期限制 | 提前把号养几天；开设备锁、把新 IP 加为常用设备；迁移时人在旁边能立即扫码；**别在群活跃期切** |
| 2 | 忘了迁 NapCat 两个卷 | `/root/napcat/config`（含 OneBot WS token `zc_ws_9f3a`）与 `/root/napcat/qq`（登录态）；漏了要重新扫码+重配 WS | 两目录打成一个 tar 带过去；起来后确认 `curl 127.0.0.1:3001` 仍返回 **426** |
| 3 | 防火墙误开端口 | Lighthouse 防火墙更"傻瓜"，默认开 22/80/443/3389 | **只开 22**；MySQL 已只绑 127.0.0.1；NapCat 端口建议改成 `-p 127.0.0.1:3001:3001`（当前是 `0.0.0.0`，靠防火墙兜底） |
| 4 | 新机拉不下 GitHub | 现役机器就报过 `GnuTLS recv error (-110)` | 用 `deploy/push-jar.ps1`（本机打包 scp，完全不依赖 GitHub），或本机 `git archive` 打 tar 再 scp |
| 5 | Lighthouse 产品能力差异 | 没有 ECS 那套完整安全组/VPC/SLB | 我们只用到「一台机 + systemd + docker」，不受影响；迁移前**先在控制台打快照** |
| 6 | 旧公网 IP 被写死在某处 | NapCat WebUI、MySQL 授权、白名单 | 迁移后全局搜一遍 `47.93.180.198` |
| 7 | 计费形态变化 | Lighthouse 包年包月、不支持按量；到期回收 | 开续费提醒（和文档里"试用实例会回收"同理） |

### 更稳的迁移姿势（推荐）：把两个风险拆开

先在**新机只跑后端**，让它连**旧机的 NapCat**（临时把旧机 3001 对公网开、加 token 白名单），验证代码侧完全正常；
确认无误后再迁 NapCat 本体。这样「环境搭建」和「QQ 风控」不会同时出问题。

---

## 二、迁移步骤（照抄命令，逐步验证）

变量约定：
- 旧机 `OLD=47.93.180.198`，新机 `NEW=<新公网IP>`
- 仓库 `/root/arona`，项目 `/root/arona/zhuochong`，密钥 `/root/zc-secrets/env.qq`
- jar 槽位 `/root/zc/app/zhuochong.jar`（`.prev` 为回滚用）

### 第 0 步：旧机准备（不停服）

```bash
# 0.1 控制台给旧 ECS 打一个快照（回滚最后一道保险）

# 0.2 备份数据库（脚本会保留 7 天）
bash /root/arona/zhuochong/deploy/backup-mysql.sh
ls -lh /root/backup/db03-*.sql.gz | tail -1

# 0.3 打包 NapCat 的两个卷（config 含 WS token，qq 含登录态）
tar -czf /root/napcat-vol.tgz -C /root napcat/config napcat/qq
ls -lh /root/napcat-vol.tgz

# 0.4 记下当前配置基线，迁移后对比
cat /root/zc-secrets/env.qq | sed 's/\(PASSWORD=\).*/\1***/; s/\(TOKEN=\).*/\1***/'
curl -s http://127.0.0.1:8080/qq/status | head -c 400
docker exec -e MYSQL_PWD="$(grep '^ZC_DB_PASSWORD=' /root/zc-secrets/env.qq | cut -d= -f2-)" \
  zc-mysql mysql -uroot -N -B db03 -e \
  "SELECT (SELECT COUNT(*) FROM conversation),(SELECT COUNT(*) FROM command_lib),(SELECT COUNT(*) FROM pictionary_word);"
# 期望：43  64  100

# 0.5 把该带的文件推到新机（此时新机已能 ssh）
scp /root/backup/db03-*.sql.gz /root/napcat-vol.tgz $NEW:/root/
scp /root/zc-secrets/env.qq $NEW:/root/env.qq        # 注意：密钥文件，落地后立刻 chmod 600
```

### 第 1 步：新机装环境

```bash
# 1.1 基础软件（注意：enable 和 start 分开写，踩过 --now 被当单元名的坑）
apt update && apt install -y docker.io openjdk-17-jdk git curl
systemctl enable docker
systemctl start docker
docker run --rm hello-world            # 验证 docker

# 1.2 docker 镜像加速（国内直连 Docker Hub 会超时）
mkdir -p /etc/docker
cat > /etc/docker/daemon.json <<'EOF'
{ "registry-mirrors": ["https://docker.mirrors.ustc.edu.cn", "https://hub-mirror.c.163.com"] }
EOF
systemctl restart docker

# 1.3 时区（不设会导致"当前时间"注入和事件簿时间错乱）
timedatectl set-timezone Asia/Shanghai
date

# 1.4 密钥落地（仓库外 + 600）
mkdir -p /root/zc-secrets && chmod 700 /root/zc-secrets
mv /root/env.qq /root/zc-secrets/env.qq && chmod 600 /root/zc-secrets/env.qq
sed -i 's/\r$//' /root/zc-secrets/env.qq      # 防 Windows 换行混入
```

### 第 2 步：恢复 MySQL

```bash
PW="$(grep '^ZC_DB_PASSWORD=' /root/zc-secrets/env.qq | cut -d= -f2-)"

docker run -d --name zc-mysql --restart=always \
  -p 127.0.0.1:3306:3306 \
  -v /root/mysql-data:/var/lib/mysql \
  -e MYSQL_ROOT_PASSWORD="$PW" \
  mysql:8 --character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci

# 等 15 秒让 MySQL 初始化完
sleep 15
docker exec -e MYSQL_PWD="$PW" zc-mysql mysql -uroot -e "CREATE DATABASE IF NOT EXISTS db03 DEFAULT CHARSET utf8mb4;"

# 导入（文件名按实际替换）
gunzip < /root/db03-*.sql.gz | docker exec -i -e MYSQL_PWD="$PW" zc-mysql mysql -uroot db03

# 验证三张表的数字必须和旧机一致
docker exec -e MYSQL_PWD="$PW" zc-mysql mysql -uroot -N -B db03 -e \
  "SELECT (SELECT COUNT(*) FROM conversation),(SELECT COUNT(*) FROM command_lib),(SELECT COUNT(*) FROM pictionary_word);"
# 期望：43  64  100
```

### 第 3 步：恢复 NapCat

```bash
tar -xzf /root/napcat-vol.tgz -C /root
ls /root/napcat/config            # 应看到 onebot11_<QQ>.json

# 注意端口发布：3001 只绑本机，避免公网直接可达
docker run -d --name napcat --restart=always \
  -p 3000:3000 -p 127.0.0.1:3001:3001 -p 6099:6099 \
  -v /root/napcat/config:/app/napcat/config \
  -v /root/napcat/qq:/app/napcat/qq \
  mlikiowa/napcat-docker:latest

# 验证：426 = WS 服务在（正常）
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3001

# 确认 token 与配置一致（应当还是 zc_ws_9f3a）
python3 - <<'PY'
import json, glob
for p in glob.glob('/root/napcat/config/onebot11_*.json'):
    d = json.load(open(p, encoding='utf-8'))
    net = d.get('network', d)
    for s in net.get('websocketServers', []) or []:
        print('WS:', s.get('host'), s.get('port'), 'enable=', s.get('enable'), 'token=', repr(s.get('token')), 'fmt=', s.get('messagePostFormat'))
PY
```

> **如果 426 但 `get_login_info` 无响应 / 日志报 401**：说明小号在新 IP 上掉登录了 →
> 打开 `http://<NEW>:6099` 用 WebUI 重新扫码（这一步就是风险 #1，可能还要短信验证）。

### 第 4 步：拿代码 + 起后端

```bash
# 4.1 方案 A：新机能连 GitHub
git clone https://github.com/Chenyao841/arona-desktop-pet.git /root/arona

# 4.1 方案 B：连不上 GitHub（更稳）→ 本机执行，把 jar 直接推上去
#     powershell -File D:\IdeaProjects\zhuochong\deploy\push-jar.ps1 -ServerHost <NEW>
#     （push-jar.ps1 会在本机 -Pcloud 打包成 ~35MB jar 再 scp，不依赖新机网络）

# 4.2 体检（会逐项检查代码/QQ模块/表情包/JDK/时区/docker/容器/MySQL 数据/WS 426/密钥/jar/服务/端口/磁盘）
bash /root/arona/zhuochong/deploy/preflight.sh        # 目标：阻塞 0 项

# 4.3 构建并放 jar（已有 jar 则跳过）
bash /root/arona/zhuochong/deploy/update.sh           # 首次会提示"还没装服务"，正常

# 4.4 装 systemd 服务并启动
cp /root/arona/zhuochong/deploy/zhuochong-qq.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now zhuochong-qq
sleep 12
systemctl status zhuochong-qq --no-pager | head -8
curl -s http://127.0.0.1:8080/qq/status | head -c 400     # 期望 connected:true
```

### 第 5 步：验收（必须逐项过）

```bash
# 5.1 服务与连接
curl -s http://127.0.0.1:8080/qq/status | python3 -m json.tool | head -20
#   connected:true / selfId=2269710979 / emojiCount=39 / adminCount=1

# 5.2 数据没丢
docker exec -e MYSQL_PWD="$PW" zc-mysql mysql -uroot -t db03 -e \
  "SELECT LEFT(api_key,6) api_key, model, api_url FROM ai_config;
   SELECT COUNT(*) AS memories FROM pet_memory;"

# 5.3 群里冒烟（人工）
#   ① @ 它说句话 → 3 秒后带引用的回复
#   ② 发一张图再 @ 它 → 能看到图（日志 [QQ] 图片理解〔图#N〕）
#   ④ 管理员私聊 /status → /usage → /emoji
#   ⑤ 追问图片细节 → 日志出现 [QQ] 重读〔图#N〕（验证图片留底+重读）

# 5.4 看日志有无异常
journalctl -u zhuochong-qq -n 60 --no-pager | grep -E '\[QQ\]|\[视觉\]|ERROR|Exception'
```

### 第 6 步：运维配置 + 收尾

```bash
# 6.1 定时备份 + 日志轮转
cp /root/arona/zhuochong/deploy/backup-mysql.sh /etc/cron.daily/zc-mysql-backup
chmod +x /etc/cron.daily/zc-mysql-backup
cp /root/arona/zhuochong/deploy/logrotate-zhuochong.conf /etc/logrotate.d/zhuochong
chmod 700 /root/backup          # 备份里有 api_key

# 6.2 Lighthouse 防火墙（控制台）：只留 22
#     如需临时排查再开 6099（NapCat WebUI），用完立刻删
#     3001/3306/8080 一律不对公网开放

# 6.3 全局搜残留旧 IP
grep -rn "47.93.180.198" /root/arona/zhuochong/deploy /root/zc-secrets 2>/dev/null

# 6.4 观察 1–2 天没问题后，再释放旧 ECS（在那之前旧机就是最快的回滚）
```

---

## 三、回滚（任何一步失败）

| 场景 | 做法 |
|---|---|
| 新机整体不通 | 群里先静默；把 NapCat 关掉避免"两个号同时在线"；DNS/使用方仍指向旧 IP，重启旧机服务即可 |
| 新机后端有问题、NapCat 已迁 | 旧机重新开容器（卷还在）：`docker start zc-mysql napcat && systemctl start zhuochong-qq`；新机 `systemctl stop zhuochong-qq` |
| 只换了 jar 出问题 | `bash /root/arona/zhuochong/deploy/rollback.sh`（换回 `.prev`） |
| 数据出错 | 用 `db03-*.sql.gz` 重导：`gunzip < xxx.sql.gz \| docker exec -i zc-mysql mysql -uroot -p db03` |

**切记**：迁移期间**不要**让新旧两台同时连同一个 NapCat 小号跑后端（会出现重复回复、冷却/黑名单状态错乱）。

---

## 四、迁移前后的对比核对表

| 项 | 旧机值 | 新机值 | 一致？ |
|---|---|---|---|
| conversation / command_lib / pictionary_word | 43 / 64 / 100 | | ☐ |
| `ai_config.api_key` 前 6 位 | `sk-xxx` | | ☐ |
| `ai_config.api_url` / `model` | deepseek 官方 / deepseek-chat | | ☐ |
| emojiCount（表情包张数） | 39 | | ☐ |
| `ZC_QQ_WS_TOKEN` | `zc_ws_9f3a` | | ☐ |
| `/qq/status` connected | true | | ☐ |
| `selfId` | 2269710979 | | ☐ |
| `pet_memory` 条数 | 记一下 | | ☐ |
| 群里 @ 回复 / 发图 / 管理员指令 | 正常 | | ☐ |

---

## 五、顺手要改的小事（迁移时一起做）

1. **NapCat 端口只绑本机**：`-p 127.0.0.1:3001:3001`（现在发布到 `0.0.0.0`，只靠防火墙挡着）。
2. **MySQL 容器加启动参数**：`--character-set-server=utf8mb4 --collation-server=utf8mb4_general_ci`（避免中文乱码隐患）。
3. **`mvn` 别 apt 装**：项目自带 `./mvnw`（固定 Maven 3.9.16）；apt 的 3.6.3 对 Spring Boot 4.x 偏老。
4. **迁移时若新机网络差**：直接在本地用 `deploy/push-jar.ps1` 推 jar，跳过新机上的 mvn 构建。

---

## 六、相关文档

- `QQ机器人-部署与更新.md` — 日常发版、回滚、目录约定、Key 配置
- `QQ机器人-云端部署进度.md` — 当前线上环境与踩过的坑
- `QQ机器人-P3进度.md` / `P4进度.md` — 功能与指令清单
