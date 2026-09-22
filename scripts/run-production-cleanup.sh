#!/usr/bin/env bash
# 生产上线前清理：MySQL 测试数据 + Redis + 腾讯 IM 账号/群组
# 保留 App 用户：99Chat、99Messenger；保留 Admin 后台账号与系统配置
set -euo pipefail
cd "$(dirname "$0")/.."

if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
fi

DB_HOST="${DB_HOST:-127.0.0.1}"
DB_PORT="${DB_PORT:-3306}"
DB_NAME="${DB_NAME:-chat99}"
DB_USERNAME="${DB_USERNAME:-chat99}"
DB_PASSWORD="${DB_PASSWORD:-chat99}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
JAR="${JAR:-target/server-0.0.1-SNAPSHOT.jar}"
TLS_JAR="${TLS_JAR:-/root/.m2/repository/com/github/tencentyun/tls-sig-api-v2/2.0/tls-sig-api-v2-2.0.jar}"
JAVA_BIN="${JAVA_BIN:-/www/server/java/jdk-17.0.8/bin/java}"

echo "==> 1/5 停止 Java 服务"
pkill -f "$JAR" 2>/dev/null || true
sleep 2

echo "==> 2/5 导出待删 IM 用户（SQL 执行前）"
mkdir -p scripts
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" -N -e \
  "SELECT user_id FROM users WHERE user_id NOT IN ('99Chat','99Messenger')" \
  > scripts/im-users-to-delete.txt
echo "    待删 IM 账号: $(wc -l < scripts/im-users-to-delete.txt)"

echo "==> 3/5 执行 MySQL 清理"
mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USERNAME" -p"$DB_PASSWORD" "$DB_NAME" < scripts/production-cleanup.sql

echo "==> 4/5 清空 Redis"
if command -v redis-cli >/dev/null 2>&1; then
  redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT" FLUSHDB
  echo "    Redis FLUSHDB done"
else
  echo "    redis-cli 未找到，跳过"
fi

echo "==> 5/5 清理腾讯 IM（群组 + 账号）"
CP=$(./mvnw -q dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
IM_CP="$TLS_JAR:$CP:scripts"
"$JAVA_BIN" -cp "$IM_CP" ImProductionCleanup 2>&1 || {
  echo "    IM 清理失败，可稍后手动重跑: java -cp \"$IM_CP\" ImProductionCleanup"
}

echo "==> 重启服务"
bash scripts/start-jar-with-env.sh
echo "==> 生产清理完成"
