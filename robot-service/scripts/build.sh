#!/usr/bin/env bash
# 构建 robot-service 可执行 JAR（复用主仓 mvnw 与 JDK17）
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/www/server/java/jdk-17.0.8}"
export PATH="$JAVA_HOME/bin:$PATH"
exec ../mvnw -f pom.xml clean package "$@"
