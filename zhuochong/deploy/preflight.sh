#!/usr/bin/env bash
# ============================================================
# 部署前环境自检（服务器端，只读不装东西）
#
# 用法：
#   bash /root/arona/zhuochong/deploy/preflight.sh
#
# 它把「已完成的环境」逐项验一遍，缺什么就明确告诉你缺哪个，
# 不会让你重复 apt install。全部通过时退出码 0。
# ============================================================
set -uo pipefail

PROJ_DIR="${ZC_PROJ_DIR:-/root/arona/zhuochong}"
REPO_DIR="${ZC_REPO_DIR:-/root/arona}"
ENV_FILE="${ZC_ENV_FILE:-/root/zc-secrets/env.qq}"
MYSQL_CONTAINER="${ZC_MYSQL_CONTAINER:-zc-mysql}"
NAPCAT_CONTAINER="${ZC_NAPCAT_CONTAINER:-napcat}"
WS_URL="${ZC_QQ_WS_URL:-http://127.0.0.1:3001}"
APP_PORT="${ZC_APP_PORT:-8080}"

OK=0; FAIL=0; WARN=0
pass() { echo -e "  \033[32m✓\033[0m $*"; OK=$((OK+1)); }
fail() { echo -e "  \033[31m✗\033[0m $*"; FAIL=$((FAIL+1)); }
warnx(){ echo -e "  \033[33m!\033[0m $*"; WARN=$((WARN+1)); }
head2(){ echo -e "\n\033[36m== $* ==\033[0m"; }

# 读密钥（有就读，没有不影响自检）
if [ -f "$ENV_FILE" ]; then set -a; . "$ENV_FILE"; set +a; fi

head2 "1. 代码"
if [ -d "$PROJ_DIR" ]; then pass "项目目录存在：$PROJ_DIR"; else fail "项目目录不存在：$PROJ_DIR"; fi
if [ -f "$PROJ_DIR/pom.xml" ]; then pass "pom.xml 存在"; else fail "pom.xml 缺失"; fi
if [ -f "$PROJ_DIR/deploy/update.sh" ]; then pass "deploy/ 脚本齐全"; else fail "deploy/ 缺失（说明代码没拉到最新）"; fi
if [ -d "$PROJ_DIR/src/main/java/com/cy/qq" ]; then
  pass "QQ 模块就位（$(ls "$PROJ_DIR/src/main/java/com/cy/qq" | wc -l) 个源文件）"
else
  fail "com/cy/qq 缺失（代码太旧）"
fi
if [ -d "$PROJ_DIR/src/main/resources/static/qq_emoji" ]; then
  pass "表情包素材就位（$(ls "$PROJ_DIR/src/main/resources/static/qq_emoji" | wc -l) 个文件）"
else
  warnx "qq_emoji 缺失 → 表情包功能不可用"
fi
if command -v git >/dev/null 2>&1 && git -C "$REPO_DIR" rev-parse --git-dir >/dev/null 2>&1; then
  local_rev="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
  remote_rev="$(git -C "$REPO_DIR" rev-parse --short origin/main 2>/dev/null || echo '?')"
  if [ "$local_rev" = "$remote_rev" ]; then pass "代码与 origin/main 一致（$local_rev）"
  else warnx "本地 $local_rev ≠ origin/main $remote_rev（先 git reset --hard origin/main）"; fi
else
  warnx "不是 git 仓库或 git 不可用（用 --no-git 模式部署也行）"
fi

head2 "2. 运行时"
if command -v java >/dev/null 2>&1; then
  jv="$(java -version 2>&1 | head -1)"
  jnum="$(java -version 2>&1 | head -1 | sed -n 's/.*version "\([0-9]*\).*/\1/p')"
  if [ "${jnum:-0}" -ge 17 ] 2>/dev/null; then pass "JDK $jnum（$jv）"; else fail "JDK 版本过低（需要 17+）：$jv"; fi
else
  fail "没有 java（apt install openjdk-17-jdk）"
fi
if command -v mvn >/dev/null 2>&1; then
  mvnver="$(mvn -v 2>/dev/null | head -1 | sed -n 's/.*Apache Maven \([0-9.]*\).*/\1/p')"
  mmajor="$(echo "${mvnver:-0}" | cut -d. -f2)"
  if [ "${mmajor:-0}" -ge 8 ] 2>/dev/null; then
    pass "mvn 已装（$mvnver）"
  else
    warnx "系统 mvn 是 ${mvnver:-未知}，偏老（Spring Boot 4.x 建议 3.8+）→ 部署会自动优先用 ./mvnw（固定 3.9.16）"
  fi
elif [ -f "$PROJ_DIR/mvnw" ]; then
  warnx "没有系统 mvn，但会用 ./mvnw（固定 Maven 3.9.16，首次需联网下载约 10MB）"
else
  fail "既没有 mvn 也没有 mvnw"
fi
if [ "$(cat /etc/timezone 2>/dev/null || echo '?')" = "Asia/Shanghai" ]; then pass "时区 Asia/Shanghai"
else warnx "时区不是 Asia/Shanghai（当前：$(cat /etc/timezone 2>/dev/null || echo 未知)）→ timedatectl set-timezone Asia/Shanghai"; fi

head2 "3. 依赖服务"
if command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then pass "docker 正常"; else fail "docker 不可用"; fi

container_up() {
  docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$1"
}
if container_up "$MYSQL_CONTAINER"; then pass "MySQL 容器 $MYSQL_CONTAINER 在跑"; else fail "MySQL 容器 $MYSQL_CONTAINER 没起（docker start $MYSQL_CONTAINER）"; fi
if container_up "$NAPCAT_CONTAINER"; then pass "NapCat 容器 $NAPCAT_CONTAINER 在跑"; else fail "NapCat 容器 $NAPCAT_CONTAINER 没起"; fi

# MySQL 数据抽查（文档里记的 43 / 64 / 100）
if container_up "$MYSQL_CONTAINER"; then
  dbq() {
    docker exec -e MYSQL_PWD="${ZC_DB_PASSWORD:-}" "$MYSQL_CONTAINER" \
      mysql -u"${ZC_DB_USER:-root}" -N -B "${ZC_DB_NAME:-db03}" -e "$1" 2>/dev/null
  }
  cv="$(dbq 'SELECT COUNT(*) FROM conversation')"
  cl="$(dbq 'SELECT COUNT(*) FROM command_lib')"
  pw="$(dbq 'SELECT COUNT(*) FROM pictionary_word')"
  # 三个都读不到时，最可能是库密码没配/不对（不是数据丢了）
  if [ -z "$cv" ] && [ -z "$cl" ] && [ -z "$pw" ]; then
    if [ -z "${ZC_DB_PASSWORD:-}" ]; then
      fail "MySQL 查询全部失败：ZC_DB_PASSWORD 为空 → 先建 $ENV_FILE 填数据库密码"
    else
      fail "MySQL 查询全部失败：检查 ZC_DB_PASSWORD / ZC_DB_USER / ZC_DB_NAME（容器里 root 改过密码？）"
    fi
  else
    if [ "${cv:-x}" = "43" ]; then pass "conversation = 43"; else warnx "conversation = ${cv:-读不到}（文档记的是 43）"; fi
    if [ "${cl:-x}" = "64" ]; then pass "command_lib = 64"; else warnx "command_lib = ${cl:-读不到}（文档记的是 64）"; fi
    if [ "${pw:-x}" = "100" ]; then pass "pictionary_word = 100"; else warnx "pictionary_word = ${pw:-读不到}（文档记的是 100）"; fi
    ai="$(dbq 'SELECT COUNT(*) FROM ai_config')"
    if [ "${ai:-0}" -gt 0 ] 2>/dev/null; then
      pass "ai_config 有 $ai 行（模型 Key 已配）"
    else
      warnx "ai_config 空 → 机器人只会回兜底文案（去管理页『基础设置』填 DeepSeek Key，或直接 UPDATE ai_config）"
    fi
  fi
fi

# NapCat 正向 WS：GET 应返回 426
code="$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 "$WS_URL" 2>/dev/null)"
if [ "$code" = "426" ]; then pass "NapCat WS 3001 返回 426（服务在，等 WS 握手）"
elif [ -z "$code" ] || [ "$code" = "000" ]; then fail "连不上 $WS_URL（NapCat 没起 / 端口不对 / host 不是 0.0.0.0）"
else warnx "$WS_URL 返回 $code（预期 426）"; fi

head2 "4. 应用"
if [ -f "$ENV_FILE" ]; then
  perm="$(stat -c '%a' "$ENV_FILE" 2>/dev/null)"
  [ "$perm" = "600" ] && pass "密钥文件存在且权限 600：$ENV_FILE" || warnx "密钥文件权限是 $perm（建议 chmod 600）"
  [ -n "${ZC_QQ_WS_TOKEN:-}" ] && pass "ZC_QQ_WS_TOKEN 已配置" || warnx "ZC_QQ_WS_TOKEN 为空（NapCat 配了 token 就会握手失败）"
  [ -n "${ZC_QQ_ADMIN_QQ:-}" ] && pass "ZC_QQ_ADMIN_QQ = $ZC_QQ_ADMIN_QQ" || warnx "ZC_QQ_ADMIN_QQ 为空（管理指令和私聊闲聊都用不了）"
  [ -n "${ZC_DB_PASSWORD:-}" ] && pass "ZC_DB_PASSWORD 已配置" || warnx "ZC_DB_PASSWORD 为空"
else
  fail "密钥文件不存在：$ENV_FILE（cp deploy/env.qq.example 过去再填）"
fi

if [ -f /root/zc/app/zhuochong.jar ]; then
  pass "jar 已就位（$(du -h /root/zc/app/zhuochong.jar | cut -f1)）"
else
  warnx "还没有 jar（bash deploy/update.sh 会构建）"
fi

if systemctl list-unit-files 2>/dev/null | grep -q '^zhuochong-qq\.service'; then
  st="$(systemctl is-active zhuochong-qq 2>/dev/null)"
  [ "$st" = "active" ] && pass "systemd 服务 zhuochong-qq 运行中" || warnx "服务已安装但当前是 $st（systemctl start zhuochong-qq）"
else
  warnx "systemd 服务还没装（cp deploy/zhuochong-qq.service /etc/systemd/system/ && systemctl daemon-reload && systemctl enable --now zhuochong-qq）"
fi

if ss -ltnp 2>/dev/null | grep -q ":$APP_PORT "; then
  body="$(curl -s --max-time 3 "http://127.0.0.1:$APP_PORT/qq/status" 2>/dev/null)"
  if echo "$body" | grep -q '"connected":true'; then pass "端口 $APP_PORT 在跑且 /qq/status 显示 connected:true"
  elif [ -n "$body" ]; then warnx "端口 $APP_PORT 在跑，但 WS 还没连上 NapCat"
  else warnx "端口 $APP_PORT 被别的进程占用（不是我们的服务？）"; fi
else
  warnx "端口 $APP_PORT 没人监听（服务还没起）"
fi

head2 "5. 磁盘与备份"
avail="$(df -Pm /root | awk 'NR==2{print $4}')"
if [ "${avail:-0}" -ge 2048 ]; then pass "根分区剩余 ${avail}MB"; else warnx "根分区只剩 ${avail}MB（jar 构建 + 备份需要空间）"; fi
if [ -d /root/backup ]; then pass "备份目录存在（$(ls /root/backup 2>/dev/null | wc -l) 份）"; else warnx "备份目录 /root/backup 还不存在（backup-mysql.sh 会建）"; fi

echo ""
echo -e "\033[36m===== 自检结果 =====\033[0m"
echo -e "  通过 \033[32m$OK\033[0m 项 / 警告 \033[33m$WARN\033[0m 项 / 阻塞 \033[31m$FAIL\033[0m 项"
if [ "$FAIL" -gt 0 ]; then
  echo -e "  \033[31m有阻塞项，修掉再跑 update.sh\033[0m"
  exit 1
fi
echo -e "  \033[32m没有阻塞项，可以直接：bash $PROJ_DIR/deploy/update.sh\033[0m"
exit 0
