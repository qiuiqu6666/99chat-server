#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME="${JAVA_HOME:-/www/server/java/jdk-17.0.8}"
export PATH="$JAVA_HOME/bin:$PATH"
exec ./mvnw "$@"
