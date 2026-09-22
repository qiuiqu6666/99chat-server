#!/usr/bin/env bash
set -uo pipefail
cd /www/wwwroot/99chat-server
mkdir -p logs

# Load .env without aborting on pkill
set -a
# shellcheck disable=SC1091
source ./.env
set +a

if [[ "${LIVEKIT_ENABLED:-}" != "true" ]]; then
  echo "LIVEKIT_ENABLED is not true" >&2
  exit 1
fi

JAR_ABS="/www/wwwroot/99chat-server/target/server-0.0.1-SNAPSHOT.jar"
# Match both absolute and relative jar paths in process cmdline.
pkill -f 'server-0.0.1-SNAPSHOT\.jar' >/dev/null 2>&1 || true
for _ in $(seq 1 45); do
  if ! pgrep -f 'server-0.0.1-SNAPSHOT\.jar' >/dev/null 2>&1; then
    break
  fi
  sleep 1
done
if pgrep -f 'server-0.0.1-SNAPSHOT\.jar' >/dev/null 2>&1; then
  echo "old java still running, sending SIGKILL" >&2
  pkill -9 -f 'server-0.0.1-SNAPSHOT\.jar' >/dev/null 2>&1 || true
  sleep 2
fi

export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
# 中文签名/模板依赖 UTF-8；缺省 ANSI_X3.4 会导致阿里云 SignName 损坏 → isv.INVALID_PARAMETERS
nohup /www/server/java/jdk-17.0.8/bin/java -jar -Xmx1024M -Xms256M \
  -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
  "$JAR_ABS" >> logs/app.log 2>&1 &
echo $! > logs/app.pid
NEW_PID=$(cat logs/app.pid)
echo "started pid=${NEW_PID} LIVEKIT_HOST=${LIVEKIT_HOST}"

for i in $(seq 1 90); do
  if ! kill -0 "$NEW_PID" 2>/dev/null; then
    echo "java exited early" >&2
    tail -n 80 logs/app.log >&2
    exit 1
  fi
  # Ready only when OUR pid owns :8081
  if ss -lntp 2>/dev/null | grep -q "pid=${NEW_PID},.*:8081\|:8081.*pid=${NEW_PID}"; then
    echo "ready on :8081 after ${i}s pid=${NEW_PID}"
    exit 0
  fi
  sleep 1
done
echo "timeout waiting for :8081" >&2
tail -n 80 logs/app.log >&2
exit 1
