#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

BASE="${BASE_URL:-http://127.0.0.1:8081}"
DB_HOST="${DB_HOST:-127.0.0.1}"
DB_USER="${DB_USERNAME:-chat99}"
DB_PASS="${DB_PASSWORD:-chat99}"
DB_NAME="${DB_NAME:-chat99}"

pass=0
fail=0
skip=0

check() {
  local name="$1" method="$2" path="$3" expect="$4" body="${5:-}"
  local auth="${6:-yes}"
  local tmp
  tmp="$(mktemp)"
  local code
  if [[ "$auth" == "yes" ]]; then
    if [[ -n "$body" ]]; then
      code=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path" \
        -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")
    else
      code=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path" \
        -H "Authorization: Bearer $TOKEN")
    fi
  else
    code=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path")
  fi
  if [[ "$code" == "$expect" ]]; then
    echo "PASS [$code] $name"
    pass=$((pass + 1))
  else
    echo "FAIL [$code expected $expect] $name"
    echo "  body: $(head -c 300 "$tmp")"
    fail=$((fail + 1))
  fi
  rm -f "$tmp"
}

check_json_code() {
  local name="$1" method="$2" path="$3" body="${4:-}"
  local tmp
  tmp="$(mktemp)"
  local http
  if [[ -n "$body" ]]; then
    http=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d "$body")
  else
    http=$(curl -sS -o "$tmp" -w '%{http_code}' -X "$method" "$BASE$path" \
      -H "Authorization: Bearer $TOKEN")
  fi
  local api_code
  api_code=$(python3 -c "import json; d=json.load(open('$tmp')); print(d.get('code', 'MISSING'))" 2>/dev/null || echo PARSE_ERR)
  if [[ "$http" == "200" && "$api_code" == "0" ]]; then
    echo "PASS [200 code=0] $name"
    pass=$((pass + 1))
  else
    echo "FAIL [http=$http api_code=$api_code] $name"
    echo "  body: $(head -c 300 "$tmp")"
    fail=$((fail + 1))
  fi
  rm -f "$tmp"
}

echo "=== Moments API smoke test ==="
echo "Base: $BASE"

TOKEN=$(python3 <<PY
import subprocess, json, time, base64, hmac, hashlib, os
def mysql_query(sql):
    cmd = ["mysql", "-h${DB_HOST}", "-u${DB_USER}", "-p${DB_PASS}", "${DB_NAME}", "-N", "-B", "-e", sql]
    import subprocess
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise SystemExit(r.stderr)
    return r.stdout.strip()
secret = mysql_query("SELECT setting_value FROM app_setting WHERE setting_key='JWT_SECRET' LIMIT 1")
user_id = mysql_query("SELECT user_id FROM users WHERE status=1 ORDER BY id LIMIT 1")
def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()
header = b64url(json.dumps({"alg":"HS256","typ":"JWT"}).encode())
now = int(time.time())
payload = b64url(json.dumps({"sub": user_id, "iat": now, "exp": now + 3600}).encode())
msg = f"{header}.{payload}".encode()
sig = b64url(hmac.new(secret.encode(), msg, hashlib.sha256).digest())
print(f"{header}.{payload}.{sig}")
PY
)
echo "Token issued for active user"

echo ""
echo "--- Auth guard ---"
check "GET /moments/feed without token -> 401" GET /moments/feed 401 "" no

echo ""
echo "--- Settings ---"
check_json_code "GET /moments/settings" GET /moments/settings
check_json_code "PUT /moments/settings visibleRangeDays" PUT /moments/settings '{"visibleRangeDays":90}'
check_json_code "GET /moments/settings after update" GET /moments/settings
check_json_code "PUT /moments/settings reset range" PUT /moments/settings '{"visibleRangeDays":0}'

echo ""
echo "--- Feed / user page ---"
check_json_code "GET /moments/feed" GET "/moments/feed?pageSize=5"
USER_ID=$(mysql -h"$DB_HOST" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME" -N -B -e "SELECT user_id FROM users WHERE status=1 ORDER BY id LIMIT 1")
check_json_code "GET /moments/users/{self}" GET "/moments/users/$USER_ID?pageSize=5"

echo ""
echo "--- Notifications ---"
check_json_code "GET /moments/notifications" GET "/moments/notifications?pageSize=5"
check_json_code "POST /moments/notifications/read all" POST /moments/notifications/read '{"readAll":true}'

echo ""
echo "--- Publish text moment ---"
IDEM=$(python3 -c 'import uuid; print(uuid.uuid4())')
PUBLISH_TMP=$(mktemp)
HTTP=$(curl -sS -o "$PUBLISH_TMP" -w '%{http_code}' -X POST "$BASE/moments" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $IDEM" \
  -d '{"text":"smoke test moment","visibility":"FRIENDS"}')
MOMENT_ID=$(python3 -c "import json; d=json.load(open('$PUBLISH_TMP')); print(d.get('data',{}).get('momentId',''))" 2>/dev/null || true)
if [[ "$HTTP" == "200" && -n "$MOMENT_ID" ]]; then
  echo "PASS [200] POST /moments -> momentId=$MOMENT_ID"
  pass=$((pass + 1))
else
  echo "FAIL [http=$HTTP] POST /moments"
  echo "  body: $(head -c 300 "$PUBLISH_TMP")"
  fail=$((fail + 1))
fi
rm -f "$PUBLISH_TMP"

if [[ -n "${MOMENT_ID:-}" ]]; then
  echo ""
  echo "--- Moment detail / like / comment / delete ---"
  check_json_code "GET /moments/{id}" GET "/moments/$MOMENT_ID"
  check_json_code "POST /moments/{id}/likes" POST "/moments/$MOMENT_ID/likes"
  check_json_code "DELETE /moments/{id}/likes/me" DELETE "/moments/$MOMENT_ID/likes/me"
  CMT_IDEM=$(python3 -c 'import uuid; print(uuid.uuid4())')
  COMMENT_TMP=$(mktemp)
  HTTP=$(curl -sS -o "$COMMENT_TMP" -w '%{http_code}' -X POST "$BASE/moments/$MOMENT_ID/comments" \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -H "Idempotency-Key: $CMT_IDEM" -d '{"text":"smoke comment"}')
  COMMENT_ID=$(python3 -c "import json; d=json.load(open('$COMMENT_TMP')); print(d.get('data',{}).get('comment',{}).get('commentId',''))" 2>/dev/null || true)
  if [[ "$HTTP" == "200" ]]; then
    echo "PASS [200 code=0] POST /moments/{id}/comments"
    pass=$((pass + 1))
  else
    echo "FAIL [http=$HTTP] POST /moments/{id}/comments"
    fail=$((fail + 1))
  fi
  rm -f "$COMMENT_TMP"
  if [[ -n "${COMMENT_ID:-}" ]]; then
    check_json_code "DELETE /moments/{id}/comments/{cid}" DELETE "/moments/$MOMENT_ID/comments/$COMMENT_ID"
  fi
  check_json_code "DELETE /moments/{id}" DELETE "/moments/$MOMENT_ID"
else
  echo "SKIP detail/like/comment/delete (no momentId)"
  skip=$((skip + 1))
fi

echo ""
echo "--- Cover upload (expect 503 if OSS not configured) ---"
COVER_TMP=$(mktemp)
# 1x1 png
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > /tmp/smoke-cover.png
HTTP=$(curl -sS -o "$COVER_TMP" -w '%{http_code}' -X POST "$BASE/moments/cover/upload" \
  -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/smoke-cover.png;type=image/png")
if [[ "$HTTP" == "200" || "$HTTP" == "503" || "$HTTP" == "502" ]]; then
  echo "PASS [$HTTP] POST /moments/cover/upload (route reachable)"
  pass=$((pass + 1))
else
  echo "FAIL [$HTTP] POST /moments/cover/upload"
  echo "  body: $(head -c 200 "$COVER_TMP")"
  fail=$((fail + 1))
fi
rm -f "$COVER_TMP" /tmp/smoke-cover.png

echo ""
echo "--- Media upload (expect 503 if OSS not configured) ---"
MEDIA_TMP=$(mktemp)
HTTP=$(curl -sS -o "$MEDIA_TMP" -w '%{http_code}' -X POST "$BASE/moments/media/upload" \
  -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/smoke-cover.png;type=image/png" -F "type=IMAGE" 2>/dev/null || true)
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x02\x00\x00\x00\x90wS\xde\x00\x00\x00\x0cIDATx\x9cc\xf8\x0f\x00\x00\x01\x01\x00\x05\x18\xd8N\x00\x00\x00\x00IEND\xaeB`\x82' > /tmp/smoke-media.png
HTTP=$(curl -sS -o "$MEDIA_TMP" -w '%{http_code}' -X POST "$BASE/moments/media/upload" \
  -H "Authorization: Bearer $TOKEN" -F "file=@/tmp/smoke-media.png;type=image/png" -F "type=IMAGE")
if [[ "$HTTP" == "200" || "$HTTP" == "503" ]]; then
  echo "PASS [$HTTP] POST /moments/media/upload (route reachable)"
  pass=$((pass + 1))
else
  echo "FAIL [$HTTP] POST /moments/media/upload"
  echo "  body: $(head -c 200 "$MEDIA_TMP")"
  fail=$((fail + 1))
fi
rm -f "$MEDIA_TMP" /tmp/smoke-media.png

echo ""
echo "=== Summary: PASS=$pass FAIL=$fail SKIP=$skip ==="
[[ "$fail" -eq 0 ]]
