#!/usr/bin/env bash
# Render livekit.yaml + systemd env from .api_key / .api_secret (not committed).
set -euo pipefail
DIR="$(cd "$(dirname "$0")" && pwd)"
KEY_FILE="$DIR/.api_key"
SECRET_FILE="$DIR/.api_secret"
TEMPLATE="$DIR/livekit.yaml"
OUT_YAML="$DIR/livekit.runtime.yaml"
ENV_OUT="$DIR/livekit.env"

if [[ ! -f "$KEY_FILE" || ! -f "$SECRET_FILE" ]]; then
  echo "missing $KEY_FILE or $SECRET_FILE" >&2
  exit 1
fi

API_KEY="$(tr -d '\n' < "$KEY_FILE")"
API_SECRET="$(tr -d '\n' < "$SECRET_FILE")"

# Escape for sed replacement
esc() { printf '%s' "$1" | sed -e 's/[\/&]/\\&/g'; }

sed \
  -e "s/PLACEHOLDER_KEY/$(esc "$API_KEY")/g" \
  -e "s/PLACEHOLDER_SECRET/$(esc "$API_SECRET")/g" \
  "$TEMPLATE" > "$OUT_YAML"
chmod 600 "$OUT_YAML"

umask 077
cat > "$ENV_OUT" <<EOF
LIVEKIT_ENABLED=true
LIVEKIT_HOST=wss://trtc.99chat.vip
LIVEKIT_API_KEY=${API_KEY}
LIVEKIT_API_SECRET=${API_SECRET}
LIVEKIT_WEBHOOK_ENABLED=true
EOF
chmod 600 "$ENV_OUT"
echo "wrote $OUT_YAML and $ENV_OUT"
