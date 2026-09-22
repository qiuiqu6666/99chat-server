#!/usr/bin/env bash
# 启动 sangong-service（后台，PID 记录到 run/sangong-service.pid）
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME=${JAVA_HOME:-/www/server/java/jdk-17.0.8}
export PATH="$JAVA_HOME/bin:$PATH"

PID_FILE=run/sangong-service.pid
mkdir -p run logs

if [[ -f "$PID_FILE" ]] && kill -0 "$(cat "$PID_FILE")" 2>/dev/null; then
    echo "already running, pid=$(cat "$PID_FILE")"
    exit 0
fi

# 加载 .env（导出为进程环境变量）
if [[ -f .env ]]; then
    set -a
    # shellcheck disable=SC1091
    source .env
    set +a
fi

# 启动时截断 stdout.log（logback 已接管全部日志，stdout.log 只留 banner / 异常 System.out）
# 避免历史 500MB+ 累积。已切到 logback-spring.xml，stdout 上不应再有重复日志。
: > logs/stdout.log

# 关闭 Spring Boot banner，避免 stdout.log 里再写 banner
nohup java -Xms256m -Xmx1024m -Dfile.encoding=UTF-8 \
    -Dspring.main.banner-mode=off \
    -jar target/sangong-service.jar \
    >> logs/stdout.log 2>&1 &

echo $! > "$PID_FILE"
echo "started, pid=$(cat "$PID_FILE"), port=${SANGONG_PORT:-8088}"

# 等待健康检查就绪
for i in $(seq 1 30); do
    if curl -sf -m 2 "http://127.0.0.1:${SANGONG_PORT:-8088}/api/v1/health" >/dev/null; then
        echo "health ready"
        exit 0
    fi
    sleep 1
done
echo "warn: health not ready yet; check logs/sangong-service.log" >&2
