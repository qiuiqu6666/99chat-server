#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
JAR="${JAR:-target/robot-service.jar}"
pkill -f "$JAR" 2>/dev/null || true
echo "robot-service stopped"
