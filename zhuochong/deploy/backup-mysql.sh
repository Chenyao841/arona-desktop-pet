#!/usr/bin/env bash
# ============================================================
# MySQL 定时备份（P3）：mysqldump 全库 → 保留 7 天
#
# 安装（云端 root）：
#   chmod +x /root/arona/deploy/backup-mysql.sh
#   cp /root/arona/deploy/backup-mysql.sh /etc/cron.daily/zc-mysql-backup   # 每天自动跑
#   或加 crontab：0 4 * * * /root/arona/deploy/backup-mysql.sh >> /root/backup/cron.log 2>&1
#
# 依赖：mysqldump（容器里也有）与 /root/arona/.env.qq 里的 ZC_DB_*
# ============================================================
set -euo pipefail

APP_DIR="${ZC_APP_DIR:-/root/arona}"
ENV_FILE="${ZC_ENV_FILE:-$APP_DIR/.env.qq}"
BACKUP_DIR="${ZC_BACKUP_DIR:-/root/backup}"
KEEP_DAYS="${ZC_BACKUP_KEEP_DAYS:-7}"

[ -f "$ENV_FILE" ] && { set -a; . "$ENV_FILE"; set +a; }

DB_HOST="${ZC_DB_HOST:-127.0.0.1}"
DB_PORT="${ZC_DB_PORT:-3306}"
DB_NAME="${ZC_DB_NAME:-db03}"
DB_USER="${ZC_DB_USER:-root}"
DB_PASS="${ZC_DB_PASSWORD:-}"
MYSQL_CONTAINER="${ZC_MYSQL_CONTAINER:-zc-mysql}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.sql.gz"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
  # 走容器内的 mysqldump（避免宿主机没装客户端）
  docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
    mysqldump -u"$DB_USER" --single-transaction --quick --default-character-set=utf8mb4 "$DB_NAME" \
    | gzip > "$OUT"
else
  MYSQL_PWD="$DB_PASS" mysqldump -h "$DB_HOST" -P "$DB_PORT" -u"$DB_USER" \
    --single-transaction --quick --default-character-set=utf8mb4 "$DB_NAME" \
    | gzip > "$OUT"
fi

SIZE="$(du -h "$OUT" | cut -f1)"
echo "[backup] $OUT ($SIZE)"

# 只留最近 N 天（按文件名时间戳不需要，直接按修改时间）
find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -type f -mtime +"$KEEP_DAYS" -delete
echo "[backup] 已清理 $KEEP_DAYS 天前的备份，当前保留 $(find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" | wc -l) 份"

# 恢复方法（写在这里省得以后翻笔记）：
#   gunzip < /root/backup/db03-XXXX.sql.gz | docker exec -i zc-mysql mysql -uroot -p db03
