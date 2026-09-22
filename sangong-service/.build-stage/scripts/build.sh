#!/usr/bin/env bash
# 构建 sangong-service.jar
set -euo pipefail
cd "$(dirname "$0")/.."

export JAVA_HOME=${JAVA_HOME:-/www/server/java/jdk-17.0.8}
export PATH="$JAVA_HOME/bin:$PATH"

../mvnw -q -DskipTests package
echo "BUILD OK: $(ls -la target/sangong-service.jar | awk '{print $5, $9}')"
