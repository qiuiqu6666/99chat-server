#!/usr/bin/env bash
# 从项目根目录加载 .env 后启动（Spring Boot 不自动读 .env，需 export）
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -f .env ]]; then
  set -a
  # shellcheck disable=SC1091
  source .env
  set +a
  echo "Loaded .env (TRONGRID_BASE_URL=${TRONGRID_BASE_URL:-unset})"
else
  echo "Warning: .env not found, copy from .env.example" >&2
fi
exec ./mvnw spring-boot:run "$@"
