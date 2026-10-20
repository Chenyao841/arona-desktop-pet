#!/usr/bin/env bash
# ============================================================
# 一键更新部署（服务器端跑）：备份 → 拉代码 → 构建 → 切换 jar → 重启 → 体检 → 失败自动回滚
#
# 用法（服务器上）：
#   bash /root/arona/zhuochong/deploy/update.sh            # 正规流程：git reset 到 origin/main 后构建
#   bash /root/arona/zhuochong/deploy/update.sh --no-git   # 应急：源码已经就位（比如 scp/tar 传上来的），跳过 git
#
# 它做的事：
#   1. 加锁，避免两个部署同时跑
#   2. mysqldump 备份（失败不阻塞，但要看得见）
#   3. git fetch + reset --hard origin/main（本地改动会被丢弃！--no-git 时跳过）
#   4. ./mvnw package -DskipTests（离线优先，缺依赖才联网）
#   5. 把新 jar 放到 /root/zc/app/zhuochong.jar（旧的重命名为 .prev）
#   6. systemctl restart，然后轮询 /qq/status 做体检
#   7. 体检不过 → 自动换回 .prev 并重启，退出码 1
# ============================================================
set -uo pipefail

NO_GIT=0
for arg in "$@"; do
  case "$arg" in
    --no-git|--nogit) NO_GIT=1 ;;
    -h|--help) sed -n '2,20p' "$0"; exit 0 ;;
    *) echo "未知参数：$arg（支持 --no-git）"; exit 2 ;;
  esac
done

REPO_DIR="${ZC_REPO_DIR:-/root/arona}"
PROJ_DIR="$REPO_DIR/zhuochong"
APP_DIR="${ZC_APP_DIR:-/root/zc/app}"
JAR="$APP_DIR/zhuochong.jar"
PREV="$APP_DIR/zhuochong.jar.prev"
SERVICE="${ZC_SERVICE:-zhuochong-qq}"
BRANCH="${ZC_BRANCH:-main}"
HEALTH_URL="http://127.0.0.1:8080/qq/status"
LOCK="/tmp/zc-update.lock"

log()  { echo -e "\033[36m[update]\033[0m $*"; }
ok()   { echo -e "\033[32m[update]\033[0m $*"; }
warn() { echo -e "\033[33m[update]\033[0m $*"; }
die()  { echo -e "\033[31m[update]\033[0m $*"; exit 1; }

# ---------- 1) 加锁 ----------
exec 9>"$LOCK"
if ! flock -n 9; then die "已经有一个 update.sh 在跑（$LOCK）"; fi

[ -d "$PROJ_DIR" ] || die "找不到项目目录 $PROJ_DIR"
mkdir -p "$APP_DIR"

# ---------- 2) 备份数据库（有备份脚本就用，失败只告警）----------
if [ -x "$PROJ_DIR/deploy/backup-mysql.sh" ] || [ -f "$PROJ_DIR/deploy/backup-mysql.sh" ]; then
  log "备份数据库..."
  bash "$PROJ_DIR/deploy/backup-mysql.sh" || warn "数据库备份失败（继续部署，自己心里有数）"
fi

# ---------- 3) 拉代码 ----------
if [ "$NO_GIT" = "1" ]; then
  warn "--no-git：跳过 git 同步，直接用服务器上现有的源码（scp/tar 传上来的那种情况）"
  AFTER_REV="nogit-$(date +%Y%m%d-%H%M%S)"
else
  log "拉取 $BRANCH ..."
  git -C "$REPO_DIR" fetch --prune --tags origin || die "git fetch 失败（网络？）"
  BEFORE_REV="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
  if [ -n "$(git -C "$REPO_DIR" status --porcelain)" ]; then
    warn "工作区有未提交改动，将被 reset 丢弃："
    git -C "$REPO_DIR" status --porcelain | head -20
  fi
  git -C "$REPO_DIR" reset --hard "origin/$BRANCH" || die "git reset 失败"
  AFTER_REV="$(git -C "$REPO_DIR" rev-parse --short HEAD)"
  if [ "$BEFORE_REV" = "$AFTER_REV" ]; then
    log "代码没有变化（$AFTER_REV），仍然继续构建以便重新打包"
  else
    ok "代码 $BEFORE_REV → $AFTER_REV"
    git -C "$REPO_DIR" log --oneline "$BEFORE_REV..$AFTER_REV" | head -10
  fi
fi

# ---------- 4) 构建 ----------
cd "$PROJ_DIR" || die "进不去 $PROJ_DIR"
# 优先用项目自带的 mvnw：它固定 Maven 3.9.16（.mvn/wrapper 里写着），
# 而 apt 装的 mvn 可能是 3.6.x —— 太老，Spring Boot 4.x 会拒绝构建。
if [ -n "${ZC_MVN:-}" ]; then
  MVN="$ZC_MVN"
elif [ -f ./mvnw ]; then
  chmod +x ./mvnw 2>/dev/null || true
  MVN=./mvnw
elif command -v mvn >/dev/null 2>&1; then
  MVN=mvn
else
  die "既没有 ./mvnw 也没有 mvn"
fi
log "构建中（$MVN -DskipTests package，Maven wrapper 固定 3.9.16）..."
BUILT_JAR="$PROJ_DIR/target/zhuochong-0.0.1-SNAPSHOT.jar"
rm -f "$BUILT_JAR"
if ! "$MVN" -B -DskipTests package -Dmaven.test.skip=true; then
  die "构建失败，未动线上 jar（线上仍是旧版本）"
fi
[ -f "$BUILT_JAR" ] || die "构建完成但没找到 $BUILT_JAR"
ok "构建成功：$(du -h "$BUILT_JAR" | cut -f1)"

# ---------- 5) 切换 jar（旧版留作回滚）----------
if [ -f "$JAR" ]; then
  cp -f "$JAR" "$PREV"
  log "旧 jar 已备份为 $PREV"
fi
install -m 644 "$BUILT_JAR" "$JAR" || die "复制 jar 失败"
ok "已切换到新 jar：$JAR"

# ---------- 6) 重启 ----------
if ! systemctl list-unit-files 2>/dev/null | grep -q "^${SERVICE}\.service"; then
  warn "系统里还没有 $SERVICE 服务（首次部署？）"
  warn "jar 已就位：$JAR"
  warn "装服务：cp $PROJ_DIR/deploy/zhuochong-qq.service /etc/systemd/system/ ; systemctl daemon-reload ; systemctl enable --now $SERVICE"
  exit 0
fi

log "重启 $SERVICE ..."
systemctl restart "$SERVICE" || die "systemctl restart 失败"

# ---------- 7) 体检 ----------
health_check() {
  local tries=40
  for i in $(seq 1 $tries); do
    local body
    body="$(curl -s --max-time 3 "$HEALTH_URL" 2>/dev/null || true)"
    if echo "$body" | grep -q '"code":1'; then
      if echo "$body" | grep -q '"connected":true'; then
        ok "体检通过：WS 已连上 NapCat"
        echo "$body" | python3 -m json.tool 2>/dev/null | sed -n '1,30p' || echo "$body"
        return 0
      fi
      # 服务起来了但还没连上（NapCat 正在登录 / token 不对）
      if [ $((i % 8)) -eq 0 ]; then
        warn "服务已起，但 NapCat WS 还没连上（第 ${i} 次）；看日志：journalctl -u $SERVICE -n 30"
      fi
    fi
    sleep 2
  done
  return 1
}

if health_check; then
  ok "部署完成：$AFTER_REV（jar 已生效）"
  exit 0
fi

# ---------- 失败 → 自动回滚 ----------
warn "新版本体检失败（80s 内没连上 NapCat），开始回滚..."
if [ -f "$PREV" ]; then
  install -m 644 "$PREV" "$JAR"
  systemctl restart "$SERVICE"
  warn "已回滚到上一个 jar。请检查：journalctl -u $SERVICE -n 80 --no-pager"
else
  warn "没有可回滚的旧 jar，需要人工介入"
fi
exit 1
