#!/usr/bin/env bash
# ============================================================
# 阿罗娜 · QQ 机器人启动脚本（服务器端由 systemd 调用）
#
# 目录约定（和 GitHub 仓库布局一致）：
#   /root/arona                       仓库根（git clone 的地方）
#   /root/arona/zhuochong             Maven 项目（pom.xml 在这层）
#   /root/zc/app/zhuochong.jar        「当前生效」的 jar（update.sh 负责放进来，便于回滚）
#   /root/zc-secrets/env.qq           敏感值（仓库外！权限 600）
#
# 手动前台调试：bash /root/arona/zhuochong/deploy/qq-bot-start.sh
# ============================================================
set -euo pipefail

APP_DIR="${ZC_APP_DIR:-/root/arona/zhuochong}"
JAR="${ZC_JAR:-/root/zc/app/zhuochong.jar}"
ENV_FILE="${ZC_ENV_FILE:-/root/zc-secrets/env.qq}"

# 安全读 env：只认 KEY=VALUE 行，坏行跳过并告警。
# 为什么不用 `. "$ENV_FILE"`：一行写错（比如把 token 单独写一行、用了中文冒号）
# 会被 bash 当命令执行 → 整个启动脚本退出 → systemd 无限重启。
if [ -f "$ENV_FILE" ]; then
  while IFS= read -r line || [ -n "$line" ]; do
    line="${line%$'\r'}"
    case "$line" in ''|\#*) continue ;; esac
    case "$line" in
      *=*)
        key="${line%%=*}"; val="${line#*=}"
        case "$key" in
          *[!A-Za-z0-9_]*|'')
            echo "[qq-bot][warn] $ENV_FILE 里有不合法的行，已跳过：$line" >&2
            continue
            ;;
        esac
        # 顺手去掉一层包裹的引号（有人习惯写 KEY='值'，这里不 source，所以得自己剥）
        case "$val" in
          \'*\') val="${val#\'}"; val="${val%\'}" ;;
          \"*\") val="${val#\"}"; val="${val%\"}" ;;
        esac
        export "$key=$val"
        ;;

      *)
        echo "[qq-bot][warn] $ENV_FILE 里有不合法的行，已跳过：$line" >&2
        ;;
    esac
  done < "$ENV_FILE"
else
  echo "[qq-bot][warn] 密钥文件不存在：$ENV_FILE（照 deploy/env.qq.example 建一份）" >&2
fi

# ===== QQ 机器人基础 =====
export ZC_QQ_ENABLED="${ZC_QQ_ENABLED:-true}"
export ZC_QQ_WS_URL="${ZC_QQ_WS_URL:-ws://127.0.0.1:3001}"
export ZC_QQ_SELF_ID="${ZC_QQ_SELF_ID:-0}"             # 0 = 连上后自动识别
export ZC_QQ_TRIGGER_MODE="${ZC_QQ_TRIGGER_MODE:-at}"  # 只理 @，别开 all（烧钱 + 风控）
export ZC_QQ_USER_COOLDOWN="${ZC_QQ_USER_COOLDOWN:-60}"
export ZC_QQ_GROUP_WHITELIST="${ZC_QQ_GROUP_WHITELIST:-}"

# ===== P1：表情包 / 看图 =====
export ZC_QQ_EMOJI="${ZC_QQ_EMOJI:-true}"
export ZC_QQ_EMOJI_GAP="${ZC_QQ_EMOJI_GAP:-5}"
export ZC_QQ_VISION="${ZC_QQ_VISION:-true}"

# ===== P3：管理员 / 记忆隔离 / 额度 =====
# 管理员 QQ 建议写在 .env.qq 里（别放在这个脚本里，脚本要进仓库）
export ZC_QQ_ADMIN_QQ="${ZC_QQ_ADMIN_QQ:-}"
export ZC_QQ_ADMIN_ONLY_PRIVATE="${ZC_QQ_ADMIN_ONLY_PRIVATE:-true}"
export ZC_QQ_MEMORY_SCOPE="${ZC_QQ_MEMORY_SCOPE:-group}"
export ZC_QQ_MEMORY_EVERY="${ZC_QQ_MEMORY_EVERY:-20}"
export ZC_QQ_DAILY_TOKENS="${ZC_QQ_DAILY_TOKENS:-0}"

# ===== P4：回复节奏 / 恶意@防护 / 引用回复 =====
export ZC_QQ_REPLY_DELAY="${ZC_QQ_REPLY_DELAY:-3}"
export ZC_QQ_BLACKLIST_BURST="${ZC_QQ_BLACKLIST_BURST:-4}"
export ZC_QQ_QUOTE="${ZC_QQ_QUOTE:-true}"
export ZC_QQ_AI_THREADS="${ZC_QQ_AI_THREADS:-4}"

# ===== 时区（不设会让"当前时间"注入和事件簿时间错乱）=====
export TZ="${TZ:-Asia/Shanghai}"

echo "[qq-bot] jar=$JAR"
echo "[qq-bot] WS=$ZC_QQ_WS_URL trigger=$ZC_QQ_TRIGGER_MODE delay=${ZC_QQ_REPLY_DELAY}s emoji-gap=$ZC_QQ_EMOJI_GAP"

if [ -z "${ZC_QQ_WS_TOKEN:-}" ]; then
  echo "[qq-bot][warn] ZC_QQ_WS_TOKEN 未设置：NapCat 若配了 token，握手会被拒（日志会出现 401/握手失败）"
fi

if [ ! -f "$JAR" ]; then
  echo "[qq-bot][error] 找不到 jar：$JAR"
  echo "             先跑 bash $(dirname "$0")/update.sh（会构建并把 jar 放到 /root/zc/app/）"
  exit 1
fi

JAVA_OPTS="${ZC_JAVA_OPTS:--Xms256m -Xmx1g -Dfile.encoding=UTF-8}"

# ZC_LOG 非空 → 追加写日志文件（systemd 下留空走 journald）
if [ -n "${ZC_LOG:-}" ]; then
  mkdir -p "$(dirname "$ZC_LOG")"
  echo "[qq-bot] 日志 → $ZC_LOG"
  exec java $JAVA_OPTS -jar "$JAR" >>"$ZC_LOG" 2>&1
else
  exec java $JAVA_OPTS -jar "$JAR"
fi
