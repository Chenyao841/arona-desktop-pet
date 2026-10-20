#!/usr/bin/env bash
# ============================================================
# MySQL 定时备份（P3）：mysqldump 全库 → 保留 7 天
#
# 目录约定（和 GitHub 仓库布局一致）：
#   /root/arona                        仓库根
#   /root/arona/zhuochong              Maven 项目（本脚本在这层的 deploy/ 里）
#   /root/zc-secrets/env.qq            密钥（仓库外，600）
#
# 安装（云端 root）：
#   chmod +x /root/arona/zhuochong/deploy/backup-mysql.sh
#   cp /root/arona/zhuochong/deploy/backup-mysql.sh /etc/cron.daily/zc-mysql-backup
#   或 crontab：0 4 * * * bash /root/arona/zhuochong/deploy/backup-mysql.sh >> /root/backup/cron.log 2>&1
# ============================================================
set -uo pipefail

APP_DIR="${ZC_APP_DIR:-/root/arona/zhuochong}"
ENV_FILE="${ZC_ENV_FILE:-/root/zc-secrets/env.qq}"
BACKUP_DIR="${ZC_BACKUP_DIR:-/root/backup}"
KEEP_DAYS="${ZC_BACKUP_KEEP_DAYS:-7}"

# 安全读 env：只认 KEY=VALUE，坏行跳过并告警（一行写错不该让备份/启动整体失败）
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|\#*) continue ;; esac
    case "$line" in
      *=*)
        key="${line%%=*}"; val="${line#*=}"
        case "$key" in
          *[!A-Za-z0-9_]*|'') echo "[backup][warn] $ENV_FILE 里有不合法的行，已跳过：$line" >&2; continue ;;
        esac
        export "$key=$val"
        ;;
      *) echo "[backup][warn] $ENV_FILE 里有不合法的行，已跳过：$line" >&2 ;;
    esac
  done < "$ENV_FILE"
fi

DB_HOST="${ZC_DB_HOST:-127.0.0.1}"
DB_PORT="${ZC_DB_PORT:-3306}"
DB_NAME="${ZC_DB_NAME:-db03}"
DB_USER="${ZC_DB_USER:-root}"
DB_PASS="${ZC_DB_PASSWORD:-}"
MYSQL_CONTAINER="${ZC_MYSQL_CONTAINER:-zc-mysql}"

if [ -z "$DB_PASS" ]; then
  echo "[backup][warn] ZC_DB_PASSWORD 为空（$ENV_FILE 里没填？）—— 备份大概率会认证失败" >&2
fi

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="$BACKUP_DIR/${DB_NAME}-${STAMP}.sql.gz"

if docker ps --format '{{.Names}}' 2>/dev/null | grep -qx "$MYSQL_CONTAINER"; then
  # 走容器内的 mysqldump（宿主机没装客户端也能备份）
  docker exec -e MYSQL_PWD="$DB_PASS" "$MYSQL_CONTAINER" \
    mysqldump -u"$DB_USER" --single-transaction --quick --default-character-set=utf8mb4 "$DB_NAME" \
    | gzip > "$OUT"
else
  MYSQL_PWD="$DB_PASS" mysqldump -h "$DB_HOST" -P "$DB_PORT" -u"$DB_USER" \
    --single-transaction --quick --default-character-set=utf8mb4 "$DB_NAME" \
    | gzip > "$OUT"
fi

# 备份不能是空文件（认证失败时 gzip 会生成一个几十字节的空壳，很容易被忽略）
SIZE_BYTES="$(stat -c '%s' "$OUT" 2>/dev/null || echo 0)"
if [ "$SIZE_BYTES" -lt 1024 ]; then
  echo "[backup][error] 备份文件只有 ${SIZE_BYTES} 字节，说明 mysqldump 失败了：$OUT" >&2
  rm -f "$OUT"
  exit 1
fi

SIZE="$(du -h "$OUT" | cut -f1)"
echo "[backup] $OUT ($SIZE)"

# 只留最近 N 天
find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -type f -mtime +"$KEEP_DAYS" -delete
echo "[backup] 已清理 $KEEP_DAYS 天前的备份，当前保留 $(find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" | wc -l) 份"

# 恢复方法（写在这里省得以后翻笔记）：
#   gunzip < /root/backup/db03-XXXX.sql.gz | docker exec -i zc-mysql mysql -uroot -p db03
