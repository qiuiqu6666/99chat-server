#!/usr/bin/env bash
# H3：归档消息导入国内 IM。不改生产配置。
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$ROOT/../.." && pwd)"
cd "$ROOT"

export JAVA_HOME="/www/server/java/jdk-17.0.8"
export PATH="$JAVA_HOME/bin:/usr/bin:/bin"

if [[ -f "$ROOT/local.env" ]]; then
  # shellcheck disable=SC1091
  set -a
  source "$ROOT/local.env"
  set +a
  export JAVA_HOME="/www/server/java/jdk-17.0.8"
  export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
fi

# 可选：从仓库 .env 补 DB_*（不覆盖 local.env 已有值）
if [[ -f "$REPO/.env" ]]; then
  while IFS= read -r line; do
    [[ -z "$line" || "$line" =~ ^# ]] && continue
    key="${line%%=*}"
    case "$key" in
      DB_HOST|DB_PORT|DB_NAME|DB_USERNAME|DB_PASSWORD)
        if [[ -z "${!key:-}" ]]; then
          export "$line"
        fi
        ;;
    esac
  done < "$REPO/.env"
fi

mkdir -p "$ROOT/state"
CP_FILE="$ROOT/state/classpath.txt"
if [[ ! -s "$CP_FILE" ]]; then
  (cd "$REPO" && ./scripts/mvn-jdk17.sh -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE")
fi
CP="$(cat "$CP_FILE")"
OUT="$ROOT/out"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" --release 17 -cp "$CP" -d "$OUT" "$ROOT/ImCnMigrateMessages.java"
exec "$JAVA_HOME/bin/java" -Xms64m -Xmx768m -cp "$OUT:$CP" ImCnMigrateMessages "$@"
