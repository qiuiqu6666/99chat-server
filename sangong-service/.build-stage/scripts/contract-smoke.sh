#!/usr/bin/env bash
# 契约冒烟：覆盖 PHP routes/web.php 主路径与兼容别名（只读/无害请求）
set -uo pipefail
cd "$(dirname "$0")/.."

PORT=$(grep -E '^SANGONG_PORT=' .env 2>/dev/null | cut -d= -f2)
PORT=${PORT:-8088}
ADMIN_JWT=${MAIN_PRIVILEGED_JWT:-}
CB=$(grep -E '^SANGONG_IM_CALLBACK_KEY=' .env 2>/dev/null | cut -d= -f2)
TENANT=$(grep -E '^IM_GROUP_GAME_ID=' .env 2>/dev/null | cut -d= -f2- | tr -d '"')
BASE="http://127.0.0.1:${PORT}"
FAILED=0
AH=(-H "Authorization: Bearer $ADMIN_JWT" -H "X-Tenant-Id: $TENANT")

if [[ -z "$ADMIN_JWT" ]]; then
    echo "MAIN_PRIVILEGED_JWT is required"
    exit 2
fi

# check <name> <expect:2xx|4xx|NNN> <curl args...>
check() {
    local name=$1
    local expect=$2
    shift 2
    local code
    code=$(curl -s -o /tmp/sangong-contract.out -w '%{http_code}' -m 8 "$@")
    local ok=0
    if [[ "$expect" == "2xx" && "$code" == 2* ]]; then ok=1; fi
    if [[ "$expect" == "4xx" && "$code" == 4* ]]; then ok=1; fi
    if [[ "$expect" == "$code" ]]; then ok=1; fi
    if [[ "$ok" == 1 ]]; then
        echo "PASS ${name} (${code})"
    else
        echo "FAIL ${name} expect=${expect} got=${code}: $(head -c 180 /tmp/sangong-contract.out)"
        FAILED=1
    fi
}

echo "== public =="
check "GET /" 2xx "$BASE/"
check "GET /api/v1/health" 2xx "$BASE/api/v1/health"
check "GET admin/tenants" 2xx -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/tenants"
check "GET /api/v1/settings" 2xx "${AH[@]}" "$BASE/api/v1/settings"
check "POST /api/v1/auth/token missing" 4xx -X POST -H "X-Tenant-Id: $TENANT" -H 'Content-Type: application/json' -d '{}' "$BASE/api/v1/auth/token"

echo "== privileged admin =="
check "admin session" 2xx "${AH[@]}" "$BASE/api/v1/admin/session"
check "admin snapshot" 2xx "${AH[@]}" "$BASE/api/v1/admin/events/snapshot"
check "admin reports users" 2xx "${AH[@]}" "$BASE/api/v1/admin/reports/users"
check "admin user-groups" 2xx "${AH[@]}" "$BASE/api/v1/admin/user-groups"
check "admin current draws" 2xx "${AH[@]}" "$BASE/api/v1/admin/rounds/current/draws"
check "admin betting preview alias" 4xx "${AH[@]}" -H 'Content-Type: application/json' \
  -X POST -d '{}' "$BASE/api/v1/admin/rounds/current/betting/preview"
check "admin reports user-flow" 2xx "${AH[@]}" "$BASE/api/v1/admin/reports/user-flow"

echo "== player jwt guard =="
check "me/balance no auth" 4xx -H "X-Tenant-Id: $TENANT" "$BASE/api/v1/me/balance"
check "rounds/current no auth" 4xx -H "X-Tenant-Id: $TENANT" "$BASE/api/v1/rounds/current"
check "bets no auth" 4xx -H "X-Tenant-Id: $TENANT" -X POST -H 'Content-Type: application/json' -d '{}' "$BASE/api/v1/bets"

echo "== im callback key =="
check "im callback bad key" 4xx -H "X-Im-Callback-Key: wrong" -H 'Content-Type: application/json' \
  -X POST -d '{"CallbackCommand":"Group.CallbackAfterSendMsg"}' "$BASE/api/v1/im/callback"
check "im callback ok shape" 2xx -H "X-Im-Callback-Key: $CB" -H 'Content-Type: application/json' \
  -X POST -d '{"CallbackCommand":"Group.CallbackAfterSendMsg","GroupId":"@noop","MsgBody":[]}' \
  "$BASE/api/v1/im/callback"

echo "== report image endpoints exist =="
for path in users/points-image trend-image bet-image settle-image settle-bill preview-images/send; do
  code=$(curl -s -o /tmp/sangong-contract.out -w '%{http_code}' -m 20 \
    "${AH[@]}" -H 'Content-Type: application/json' \
    -X POST -d '{}' "$BASE/api/v1/admin/reports/$path")
  if [[ "$code" == "404" || "$code" == "501" ]]; then
    echo "FAIL reports/$path (${code}): $(head -c 160 /tmp/sangong-contract.out)"
    FAILED=1
  else
    echo "PASS reports/$path (${code})"
  fi
done

if [[ "$FAILED" == 0 ]]; then
  echo "CONTRACT SMOKE OK"
else
  echo "CONTRACT SMOKE FAILED"
  exit 1
fi
