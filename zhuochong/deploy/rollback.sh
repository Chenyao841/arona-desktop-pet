#!/usr/bin/env bash
# ============================================================
# 回滚到上一个 jar（服务器端）
#
# 用法：
#   bash /root/arona/zhuochong/deploy/rollback.sh
#
# 它只换 jar 文件并重启服务，不动 git、不动数据库 —— 所以最快、最安全。
# 想连代码一起回退：git -C /root/arona reset --hard <tag> && bash deploy/update.sh
# ============================================================
set -uo pipefail

APP_DIR="${ZC_APP_DIR:-/root/zc/app}"
JAR="$APP_DIR/zhuochong.jar"
PREV="$APP_DIR/zhuochong.jar.prev"
SERVICE="${ZC_SERVICE:-zhuochong-qq}"
HEALTH_URL="http://127.0.0.1:8080/qq/status"

log() { echo -e "\033[36m[rollback]\033[0m $*"; }
ok()  { echo -e "\033[32m[rollback]\033[0m $*"; }
die() { echo -e "\033[31m[rollback]\033[0m $*"; exit 1; }

[ -f "$PREV" ] || die "没有上一个 jar（$PREV），无法回滚"

log "当前 jar: $(du -h "$JAR" 2>/dev/null | cut -f1)，上一个: $(du -h "$PREV" | cut -f1)"
install -m 644 "$PREV" "$JAR" || die "替换 jar 失败"
systemctl restart "$SERVICE" || die "重启失败"
log "已换回上一个 jar 并重启，等待体检..."

for i in $(seq 1 40); do
  body="$(curl -s --max-time 3 "$HEALTH_URL" 2>/dev/null || true)"
  if echo "$body" | grep -q '"code":1'; then
    if echo "$body" | grep -q '"connected":true'; then
      ok "回滚完成：服务在线且已连上 NapCat"
      exit 0
    fi
  fi
  sleep 2
done

echo -e "\033[33m[rollback]\033[0m 服务起来了但没连上 NapCat，看日志：journalctl -u $SERVICE -n 60 --no-pager"
exit 1
