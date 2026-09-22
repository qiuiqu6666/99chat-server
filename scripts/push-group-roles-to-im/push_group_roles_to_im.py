#!/usr/bin/env python3
"""One-shot: push local group_member roles to Tencent IM (local is authority).

Usage:
  python3 push_group_roles_to_im.py --dry-run
  python3 push_group_roles_to_im.py --apply --delay-ms 50
  python3 push_group_roles_to_im.py --verify --verify-sample 100
  python3 push_group_roles_to_im.py --dry-run --group-id '@TGS#2GTWUPRUA'
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import ssl
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"
SERVER_ENV = Path("/www/wwwroot/99chat-server/.env")

SUMMARY_PATH = STATE / "push_roles_dry_run_summary.json"
ROWS_PATH = STATE / "push_roles_rows.jsonl"
APPLY_JSONL = STATE / "push_roles_apply.jsonl"
APPLY_SUMMARY = STATE / "push_roles_apply_summary.json"
VERIFY_SUMMARY = STATE / "push_roles_verify_summary.json"

DEFAULT_REST_BASE = "https://adminapisgp.im.qcloud.com/v4/"

ROLE_OWNER = 400
ROLE_ADMIN = 300
ROLE_MEMBER = 200

LOCAL_TO_IM = {
    ROLE_ADMIN: "Admin",
    ROLE_MEMBER: "Member",
}


def load_env() -> None:
    for p in (ROOT / "local.env", SERVER_ENV):
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("'").strip('"'))


def utc_now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def mysql_query(sql: str) -> list[list[str]]:
    cmd = [
        "mysql",
        "-h", os.environ.get("DB_HOST", "127.0.0.1"),
        "-P", os.environ.get("DB_PORT", "3306"),
        "-u", os.environ["DB_USERNAME"],
        f"-p{os.environ['DB_PASSWORD']}",
        os.environ["DB_NAME"],
        "-N", "-B", "-e", sql,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, check=False)
    if proc.returncode != 0:
        err = (proc.stderr or "").strip()
        raise RuntimeError(f"mysql failed: {err}")
    rows: list[list[str]] = []
    for line in (proc.stdout or "").splitlines():
        line = line.rstrip("\n")
        if not line:
            continue
        rows.append(line.split("\t"))
    return rows


def mysql_scalar(sql: str) -> int:
    rows = mysql_query(sql)
    if not rows or not rows[0]:
        return 0
    return int(rows[0][0] or 0)


def load_im_credentials() -> tuple[int, str, str, str]:
    sdk_raw = (os.environ.get("IM_SDK_APP_ID") or "").strip()
    key = (os.environ.get("IM_KEY") or "").strip()
    if not sdk_raw or not key:
        rows = mysql_query(
            "SELECT setting_key, setting_value FROM app_setting "
            "WHERE setting_key IN ('IM_SDK_APP_ID','IM_KEY')"
        )
        for k, v in rows:
            if k == "IM_SDK_APP_ID" and not sdk_raw:
                sdk_raw = (v or "").strip()
            elif k == "IM_KEY" and not key:
                key = (v or "").strip()
    if not sdk_raw or not key:
        raise SystemExit("IM credentials missing: set IM_SDK_APP_ID/IM_KEY or app_setting")
    sdk = int(sdk_raw)
    base = (os.environ.get("IM_REST_BASE_URL") or DEFAULT_REST_BASE).strip()
    if not base.endswith("/"):
        base += "/"
    admin = (
        os.environ.get("IM_REST_ADMIN_ACCOUNT")
        or os.environ.get("IM_ADMIN")
        or "administrator"
    ).strip()
    return sdk, key, base, admin


def gen_user_sig(sdk_app_id: int, key: str, identifier: str, expire: int = 86400) -> str:
    curr = int(time.time())
    raw = (
        f"TLS.identifier:{identifier}\n"
        f"TLS.sdkappid:{sdk_app_id}\n"
        f"TLS.time:{curr}\n"
        f"TLS.expire:{expire}\n"
    )
    sig = base64.b64encode(
        hmac.new(key.encode("utf-8"), raw.encode("utf-8"), hashlib.sha256).digest()
    ).decode("utf-8")
    doc = {
        "TLS.ver": "2.0",
        "TLS.identifier": str(identifier),
        "TLS.sdkappid": int(sdk_app_id),
        "TLS.expire": int(expire),
        "TLS.time": curr,
        "TLS.sig": sig,
    }
    compressed = zlib.compress(json.dumps(doc, separators=(",", ":")).encode("utf-8"))
    b64 = base64.b64encode(compressed).decode("utf-8")
    return b64.replace("+", "*").replace("/", "-").replace("=", "_")


class ImRest:
    def __init__(self) -> None:
        self.sdk, self.key, self.base, self.admin = load_im_credentials()
        self._sig = gen_user_sig(self.sdk, self.key, self.admin)
        self._ctx = ssl.create_default_context()

    def post(self, path: str, body: dict) -> dict:
        rnd = random.randint(0, 2_147_483_647)
        qs = urllib.parse.urlencode({
            "sdkappid": self.sdk,
            "identifier": self.admin,
            "usersig": self._sig,
            "random": rnd,
            "contenttype": "json",
        })
        url = f"{self.base}{path}?{qs}"
        data = json.dumps(body, ensure_ascii=False).encode("utf-8")
        req = urllib.request.Request(
            url, data=data, method="POST",
            headers={"Content-Type": "application/json"},
        )
        try:
            with urllib.request.urlopen(req, timeout=60, context=self._ctx) as resp:
                return json.loads(resp.read().decode("utf-8") or "{}")
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8") if e.fp else ""
            try:
                parsed = json.loads(raw or "{}")
            except Exception:
                parsed = {"ErrorCode": -1, "ErrorInfo": raw or str(e)}
            parsed.setdefault("httpStatus", e.code)
            return parsed


def escape_sql(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


def load_rows(group_id: str | None = None, max_rows: int | None = None) -> list[dict]:
    where_group = ""
    if group_id:
        where_group = f" AND gm.group_id = '{escape_sql(group_id)}' "
    sql = (
        "SELECT gm.group_id, gm.user_id, gm.role, IFNULL(gp.owner_user_id,'') "
        "FROM group_member gm "
        "INNER JOIN group_profile gp ON gp.group_id = gm.group_id "
        "WHERE (gp.dismissed = 0 OR gp.dismissed IS NULL) "
        "  AND gm.deleted = 0 "
        f"{where_group}"
        "ORDER BY gm.group_id, gm.role DESC, gm.user_id"
    )
    if max_rows is not None and max_rows > 0:
        sql += f" LIMIT {int(max_rows)}"
    out: list[dict] = []
    for parts in mysql_query(sql):
        if len(parts) < 4:
            continue
        gid, uid, role_raw, owner = parts[0], parts[1], parts[2], parts[3]
        try:
            role = int(role_raw)
        except ValueError:
            continue
        action = "skip_owner" if role == ROLE_OWNER else (
            "write" if role in LOCAL_TO_IM else "skip_other"
        )
        out.append({
            "group_id": gid,
            "user_id": uid,
            "role": role,
            "im_role": LOCAL_TO_IM.get(role),
            "owner_user_id": owner or None,
            "action": action,
            "owner_mismatch": bool(
                role == ROLE_OWNER and owner and owner != uid
            ),
        })
    return out


def run_dry_run(group_id: str | None, max_rows: int | None) -> dict:
    STATE.mkdir(parents=True, exist_ok=True)
    rows = load_rows(group_id=group_id, max_rows=max_rows)
    groups = {r["group_id"] for r in rows}
    owner_skip = sum(1 for r in rows if r["action"] == "skip_owner")
    other_skip = sum(1 for r in rows if r["action"] == "skip_other")
    admin_write = sum(1 for r in rows if r["action"] == "write" and r["role"] == ROLE_ADMIN)
    member_write = sum(1 for r in rows if r["action"] == "write" and r["role"] == ROLE_MEMBER)
    owner_mismatch = sum(1 for r in rows if r.get("owner_mismatch"))

    with ROWS_PATH.open("w", encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    summary = {
        "generated_at": utc_now(),
        "group_id_filter": group_id,
        "group_count": len(groups),
        "member_rows": len(rows),
        "owner_skip": owner_skip,
        "admin_write": admin_write,
        "member_write": member_write,
        "write_planned": admin_write + member_write,
        "skip_other": other_skip,
        "owner_mismatch_count": owner_mismatch,
        "max_rows": max_rows,
        "rows_sample": rows[:50],
        "rows_path": str(ROWS_PATH),
    }
    SUMMARY_PATH.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"dry-run groups={summary['group_count']} members={summary['member_rows']} "
        f"write={summary['write_planned']} (admin={admin_write} member={member_write}) "
        f"owner_skip={owner_skip} owner_mismatch={owner_mismatch}",
        flush=True,
    )
    print(f"wrote {SUMMARY_PATH}", flush=True)
    print(f"wrote {ROWS_PATH}", flush=True)
    return summary


def modify_role(im: ImRest, group_id: str, user_id: str, im_role: str) -> int:
    resp = im.post(
        "group_open_http_svc/modify_group_member_info",
        {
            "GroupId": group_id,
            "Member_Account": user_id,
            "Role": im_role,
        },
    )
    code = resp.get("ErrorCode")
    return int(code) if isinstance(code, int) else -1


def run_apply(delay_ms: int, max_rows: int | None, fail_fast: bool,
              group_id: str | None = None) -> dict:
    if not SUMMARY_PATH.exists() or not ROWS_PATH.exists():
        raise SystemExit("refuse apply: run --dry-run first (missing dry-run products)")
    im = ImRest()
    planned = 0
    ok = 0
    fail = 0
    skipped = 0
    started = time.time()
    APPLY_JSONL.write_text("", encoding="utf-8")
    with ROWS_PATH.open(encoding="utf-8") as src, APPLY_JSONL.open("a", encoding="utf-8") as out:
        for i, line in enumerate(src, 1):
            row = json.loads(line)
            if group_id and row.get("group_id") != group_id:
                continue
            if row.get("action") != "write":
                skipped += 1
                continue
            planned += 1
            if max_rows is not None and planned > max_rows:
                planned -= 1
                break
            gid = row["group_id"]
            uid = row["user_id"]
            im_role = row["im_role"]
            code = modify_role(im, gid, uid, im_role)
            success = code == 0
            rec = {
                "group_id": gid,
                "user_id": uid,
                "im_role": im_role,
                "ok": success,
                "code": code,
            }
            out.write(json.dumps(rec, ensure_ascii=False) + "\n")
            if success:
                ok += 1
            else:
                fail += 1
                print(f"FAIL {gid}|{uid} role={im_role} code={code}", flush=True)
                if fail_fast:
                    break
            if planned % 200 == 0:
                print(f"[{planned}] ok={ok} fail={fail}", flush=True)
            if delay_ms > 0:
                time.sleep(delay_ms / 1000.0)
    duration_ms = int((time.time() - started) * 1000)
    summary = {
        "generated_at": utc_now(),
        "group_id_filter": group_id,
        "ok": ok,
        "fail": fail,
        "skipped": skipped,
        "planned_writes": planned,
        "duration_ms": duration_ms,
        "delay_ms": delay_ms,
        "rest_base": im.base,
        "sdk_app_id": im.sdk,
    }
    APPLY_SUMMARY.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"apply ok={ok} fail={fail} skipped={skipped} duration_ms={duration_ms}",
        flush=True,
    )
    print(f"wrote {APPLY_JSONL}", flush=True)
    print(f"wrote {APPLY_SUMMARY}", flush=True)
    return summary


def get_member_role(im: ImRest, group_id: str, user_id: str) -> str | None:
    resp = im.post(
        "group_open_http_svc/get_group_member_info",
        {
            "GroupId": group_id,
            "MemberList": [user_id],
            "MemberInfoFilter": ["Role"],
        },
    )
    if resp.get("ErrorCode") not in (0, None):
        return None
    for item in resp.get("MemberList") or []:
        if item.get("Member_Account") == user_id:
            return item.get("Role")
    return None


def run_verify(sample: int | None, delay_ms: int) -> dict:
    if not ROWS_PATH.exists():
        raise SystemExit("missing dry-run rows; run --dry-run first")
    rows = [
        json.loads(line)
        for line in ROWS_PATH.read_text(encoding="utf-8").splitlines()
        if line.strip()
    ]
    writes = [r for r in rows if r.get("action") == "write"]
    if sample is not None and sample > 0 and sample < len(writes):
        # deterministic head sample for repeatability
        writes = writes[:sample]
    im = ImRest()
    ok = 0
    fail = 0
    details = []
    for r in writes:
        got = get_member_role(im, r["group_id"], r["user_id"])
        match = got == r["im_role"]
        if match:
            ok += 1
        else:
            fail += 1
            details.append({
                "group_id": r["group_id"],
                "user_id": r["user_id"],
                "expect": r["im_role"],
                "got": got,
            })
        if delay_ms > 0:
            time.sleep(delay_ms / 1000.0)
    summary = {
        "generated_at": utc_now(),
        "checked": len(writes),
        "ok": ok,
        "fail": fail,
        "failures_sample": details[:50],
    }
    VERIFY_SUMMARY.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(f"verify checked={len(writes)} ok={ok} fail={fail}", flush=True)
    print(f"wrote {VERIFY_SUMMARY}", flush=True)
    return summary


def main() -> None:
    ap = argparse.ArgumentParser(description="Push local group roles to IM")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--delay-ms", type=int, default=50)
    ap.add_argument("--max-rows", type=int, default=0, help="0 = no limit (apply: max writes)")
    ap.add_argument("--fail-fast", action="store_true")
    ap.add_argument("--verify-sample", type=int, default=0, help="0 = all write rows from dry-run")
    ap.add_argument("--group-id", type=str, default="", help="optional single group filter")
    args = ap.parse_args()

    if not (args.dry_run or args.apply or args.verify):
        print("specify --dry-run and/or --apply and/or --verify", file=sys.stderr)
        sys.exit(2)

    load_env()
    max_rows = args.max_rows if args.max_rows > 0 else None
    group_id = args.group_id.strip() or None
    sample = args.verify_sample if args.verify_sample > 0 else None

    if args.dry_run:
        run_dry_run(group_id=group_id, max_rows=max_rows)
    if args.apply:
        run_apply(
            delay_ms=args.delay_ms,
            max_rows=max_rows,
            fail_fast=args.fail_fast,
            group_id=group_id,
        )
    if args.verify:
        run_verify(sample=sample, delay_ms=args.delay_ms)


if __name__ == "__main__":
    main()
