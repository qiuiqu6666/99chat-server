#!/usr/bin/env bash
# 冒烟测试：健康检查 + 主要只读接口（管理端需主服务特权用户 JWT）
set -uo pipefail
cd "$(dirname "$0")/.."

PORT=$(grep -E '^SANGONG_PORT=' .env 2>/dev/null | cut -d= -f2)
PORT=${PORT:-8088}
ADMIN_JWT=${MAIN_PRIVILEGED_JWT:-}
TENANT=$(grep -E '^IM_GROUP_GAME_ID=' .env 2>/dev/null | cut -d= -f2- | tr -d '"')
BASE="http://127.0.0.1:${PORT}"
H=(-H "Authorization: Bearer $ADMIN_JWT" -H "X-Tenant-Id: $TENANT")

if [[ -z "$ADMIN_JWT" ]]; then
    echo "MAIN_PRIVILEGED_JWT is required"
    exit 2
fi

check() {
    local name=$1; shift
    local code
    code=$(curl -s -o /tmp/sangong-smoke.out -w '%{http_code}' -m 5 "$@")
    if [[ "$code" == 2* ]]; then
        echo "PASS ${name} (${code})"
    else
        echo "FAIL ${name} (${code}): $(head -c 200 /tmp/sangong-smoke.out)"
        FAILED=1
    fi
}

FAILED=0
check "GET /"                       "$BASE/"
check "GET /api/v1/health"          "$BASE/api/v1/health"
check "GET admin/tenants"           -H "Authorization: Bearer $ADMIN_JWT" "$BASE/api/v1/admin/tenants"
check "GET /api/v1/settings"        "${H[@]}" "$BASE/api/v1/settings"
check "GET admin/session"           "${H[@]}" "$BASE/api/v1/admin/session"
check "GET admin/events/snapshot"   "${H[@]}" "$BASE/api/v1/admin/events/snapshot"
check "GET admin/reports/users"     "${H[@]}" "$BASE/api/v1/admin/reports/users"
check "GET admin/user-groups"       "${H[@]}" "$BASE/api/v1/admin/user-groups"

if [[ "$FAILED" == 0 ]]; then
    echo "SMOKE OK"
else
    echo "SMOKE FAILED"
    exit 1
fi
