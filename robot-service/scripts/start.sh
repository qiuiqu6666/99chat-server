#!/usr/bin/env bash
# 启动 robot-service（读取 robot-service/.env，独立于主服务进程）
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p logs data/agent-export
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi
export JWT_SECRET="${JWT_SECRET:-dev-local-jwt-secret-please-change-in-prod-1234567890}"
export ROBOT_SYNC_SECRET="${ROBOT_SYNC_SECRET:-0807c9cab655a70241ec601c2c62f49adfbd304673b268ed08119e1e61c5ea86}"
JAVA_BIN="${JAVA_BIN:-/www/server/java/jdk-17.0.8/bin/java}"
JAR="${JAR:-target/robot-service.jar}"
pkill -f "$JAR" 2>/dev/null || true
for _ in $(seq 1 45); do
  if ! pgrep -f "$JAR" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
exec nohup "$JAVA_BIN" -jar -Xmx512M -Xms128M "$JAR" >> logs/startup.log 2>&1 &
