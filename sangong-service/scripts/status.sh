#!/usr/bin/env bash
# 查看运行状态与健康检查
set -uo pipefail
cd "$(dirname "$0")/.."

PID_FILE=run/sangong-service.pid
PORT=$(grep -E '^SANGONG_PORT=' .env 2>/dev/null | cut -d= -f2)
PORT=${PORT:-8088}

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "RUNNING pid=$(cat "$PID_FILE")"
else
    echo "STOPPED"
fi

echo "health: $(curl -s -m 3 "http://127.0.0.1:${PORT}/api/v1/health" || echo 'no response')"
