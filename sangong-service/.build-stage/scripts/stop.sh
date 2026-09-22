#!/usr/bin/env bash
# 停止 sangong-service
set -euo pipefail
cd "$(dirname "$0")/.."

PID_FILE=run/sangong-service.pid

if [[ ! -f "$PID_FILE" ]]; then
    echo "not running (no pid file)"
    exit 0
fi

PID=$(cat "$PID_FILE")
if ! kill -0 "$PID" 2>/dev/null; then
    echo "not running (stale pid $PID)"
    rm -f "$PID_FILE"
    exit 0
fi

kill "$PID"
for _ in $(seq 1 30); do
    if ! kill -0 "$PID" 2>/dev/null; then
        rm -f "$PID_FILE"
        echo "stopped (pid $PID)"
        exit 0
    fi
    sleep 1
done

kill -9 "$PID" 2>/dev/null || true
rm -f "$PID_FILE"
echo "force killed (pid $PID)"
