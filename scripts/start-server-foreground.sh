#!/usr/bin/env bash
set -euo pipefail
cd /www/wwwroot/99chat-server
export JAVA_HOME=/www/server/java/jdk-17.0.8
export PATH="$JAVA_HOME/bin:$PATH"

set -a
while IFS= read -r line || [ -n "$line" ]; do
  [[ -z "$line" || "$line" =~ ^[[:space:]]*# ]] && continue
  [[ "$line" != *=* ]] && continue
  key="${line%%=*}"
  [[ "$key" =~ ^[A-Za-z_][A-Za-z0-9_]*$ ]] || continue
  export "$line"
done < .env
set +a

# 中文签名/模板依赖 UTF-8；缺省 ANSI_X3.4 会导致阿里云 SignName 损坏 → isv.INVALID_PARAMETERS
export LANG="${LANG:-C.UTF-8}"
export LC_ALL="${LC_ALL:-C.UTF-8}"
exec "$JAVA_HOME/bin/java" -jar -Xmx1024M -Xms256M \
  -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8 \
  target/server-0.0.1-SNAPSHOT.jar
