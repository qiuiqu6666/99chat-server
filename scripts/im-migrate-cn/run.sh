#!/usr/bin/env bash
# 旁路迁移：新加坡 IM → 中国 IM（用户+群）。不改生产配置。
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
  # local.env 不得覆盖 JDK
  export JAVA_HOME="/www/server/java/jdk-17.0.8"
  export PATH="$JAVA_HOME/bin:/usr/bin:/bin"
fi

mkdir -p "$ROOT/state"

echo "Resolving Maven classpath (java=$("$JAVA_HOME/bin/java" -version 2>&1 | head -1))..."
CP_FILE="$ROOT/state/classpath.txt"
(cd "$REPO" && ./scripts/mvn-jdk17.sh -q dependency:build-classpath -Dmdep.outputFile="$CP_FILE")
CP="$(cat "$CP_FILE")"

OUT="$ROOT/out"
mkdir -p "$OUT"
"$JAVA_HOME/bin/javac" --release 17 -cp "$CP" -d "$OUT" "$ROOT/ImCnMigrateUsersGroups.java"
exec "$JAVA_HOME/bin/java" -cp "$OUT:$CP" ImCnMigrateUsersGroups "$@"
