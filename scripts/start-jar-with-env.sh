#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p logs
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
JAVA_BIN="${JAVA_BIN:-/www/server/java/jdk-17.0.8/bin/java}"
JAR="${JAR:-target/server-0.0.1-SNAPSHOT.jar}"
# 现网 :8081 由 systemd 托管时禁止手工拉起，否则 systemd 实例会因端口占用无限重启
if systemctl is-enabled 99chat-main.service >/dev/null 2>&1; then
  echo "99chat-main.service is enabled; use scripts/restart-main-8081.sh (systemd) instead" >&2
  exit 1
fi
pkill -f "$JAR" 2>/dev/null || true
for _ in $(seq 1 45); do
  if ! pgrep -f "$JAR" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
# 中文签名/模板依赖 UTF-8；缺省 ANSI_X3.4 会导致阿里云 SignName 损坏 → isv.INVALID_PARAMETERS
exec nohup "$JAVA_BIN" -jar -Xmx1024M -Xms256M \
  -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
  "$JAR" >> logs/app.log 2>&1 &
