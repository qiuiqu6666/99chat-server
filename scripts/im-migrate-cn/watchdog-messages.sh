#!/usr/bin/env bash
# 看门狗：断点续跑 import-all-msgs，直到 c2c+group 断点覆盖完成或达到最大轮次。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT"
export JAVA_HOME="/www/server/java/jdk-17.0.8"
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
export MSG_IMPORT_QPS="${MSG_IMPORT_QPS:-10}"

if [[ -f "$ROOT/local.env" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$ROOT/local.env"
  set +a
  export JAVA_HOME="/www/server/java/jdk-17.0.8"
  export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
fi
if [[ -f /www/wwwroot/99chat-server/.env ]]; then
  while IFS= read -r line; do
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    key="${line%%=*}"
    case "$key" in
      DB_HOST|DB_PORT|DB_NAME|DB_USERNAME|DB_PASSWORD)
        if [[ -z "${!key:-}" ]]; then
          export "$line"
        fi
        ;;
    esac
  done < /www/wwwroot/99chat-server/.env
fi

mkdir -p state
LOG=state/msg_import_full.log
C2C_TOTAL="${C2C_TOTAL:-1377}"
GROUP_TOTAL="${GROUP_TOTAL:-5214}"
MAX_ROUNDS="${MAX_ROUNDS:-80}"

echo "[watchdog $(date -Is)] start qps=$MSG_IMPORT_QPS rounds<=$MAX_ROUNDS" | tee -a "$LOG"

for round in $(seq 1 "$MAX_ROUNDS"); do
  c2c=$(wc -l < state/ckpt_msg_c2c.txt 2>/dev/null || echo 0)
  grp=$(wc -l < state/ckpt_msg_group.txt 2>/dev/null || echo 0)
  c2c=${c2c// /}; grp=${grp// /}
  echo "[watchdog $(date -Is)] round=$round ckpt_c2c=$c2c/$C2C_TOTAL ckpt_group=$grp/$GROUP_TOTAL" | tee -a "$LOG"
  if [[ "$c2c" -ge "$C2C_TOTAL" && "$grp" -ge "$GROUP_TOTAL" ]]; then
    echo "[watchdog $(date -Is)] COMPLETE" | tee -a "$LOG"
    exit 0
  fi
  set +e
  ./run-messages.sh import-all-msgs >>"$LOG" 2>&1
  rc=$?
  set -e
  echo "[watchdog $(date -Is)] round=$round exit=$rc" | tee -a "$LOG"
  sleep 2
done
echo "[watchdog $(date -Is)] gave up after $MAX_ROUNDS rounds" | tee -a "$LOG"
exit 1
