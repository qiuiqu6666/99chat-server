#!/usr/bin/env python3
"""One-shot: push mutual active user_friend edges (+ non-empty remarks) to IM SNS.

Usage:
  python3 push_mutual_friends_to_im.py --dry-run
  python3 push_mutual_friends_to_im.py --apply
  python3 push_mutual_friends_to_im.py --verify
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

SUMMARY_PATH = STATE / "push_friends_dry_run_summary.json"
PAIRS_PATH = STATE / "push_friends_pairs.jsonl"
APPLY_JSONL = STATE / "push_friends_apply.jsonl"
APPLY_SUMMARY = STATE / "push_friends_apply_summary.json"
VERIFY_SUMMARY = STATE / "push_friends_verify_summary.json"

REMARK_MAX = 100
DEFAULT_REST_BASE = "https://adminapisgp.im.qcloud.com/v4/"


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


def normalize_remark(raw: str | None) -> str | None:
    if raw is None:
        return None
    text = raw.strip()
    if not text or text.upper() == "NULL":
        return None
    if len(text) > REMARK_MAX:
        text = text[:REMARK_MAX]
    return text


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


def load_pairs(max_pairs: int | None = None) -> list[dict]:
    sql = (
        "SELECT a.user_id, a.friend_user_id, "
        "IFNULL(a.remark,''), IFNULL(b.remark,'') "
        "FROM user_friend a "
        "INNER JOIN user_friend b "
        "  ON b.user_id = a.friend_user_id AND b.friend_user_id = a.user_id "
        "WHERE a.status = 1 AND a.deleted = 0 "
        "  AND b.status = 1 AND b.deleted = 0 "
        "  AND a.user_id < a.friend_user_id "
        "  AND a.user_id <> a.friend_user_id "
        "ORDER BY a.user_id, a.friend_user_id"
    )
    if max_pairs is not None and max_pairs > 0:
        sql += f" LIMIT {int(max_pairs)}"
    rows = mysql_query(sql)
    out: list[dict] = []
    for parts in rows:
        if len(parts) < 4:
            continue
        user_a, user_b = parts[0].strip(), parts[1].strip()
        if not user_a or not user_b or user_a == user_b:
            continue
        remark_ab = normalize_remark(parts[2])
        remark_ba = normalize_remark(parts[3])
        out.append({
            "user_a": user_a,
            "user_b": user_b,
            "remark_ab": remark_ab,
            "remark_ba": remark_ba,
            "write_remark_ab": remark_ab is not None,
            "write_remark_ba": remark_ba is not None,
        })
    return out


def count_active_edges() -> int:
    return mysql_scalar(
        "SELECT COUNT(*) FROM user_friend "
        "WHERE status = 1 AND deleted = 0 AND user_id <> friend_user_id"
    )


def run_dry_run(max_pairs: int | None = None) -> dict:
    STATE.mkdir(parents=True, exist_ok=True)
    pairs = load_pairs(max_pairs=max_pairs)
    remark_write = 0
    remark_skip_empty = 0
    for p in pairs:
        if p["write_remark_ab"]:
            remark_write += 1
        else:
            remark_skip_empty += 1
        if p["write_remark_ba"]:
            remark_write += 1
        else:
            remark_skip_empty += 1

    with PAIRS_PATH.open("w", encoding="utf-8") as f:
        for p in pairs:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")

    summary = {
        "generated_at": utc_now(),
        "mutual_pair_count": len(pairs),
        "friend_add_planned": len(pairs),
        "remark_write_count": remark_write,
        "remark_skip_empty_count": remark_skip_empty,
        "db_active_edge_count": count_active_edges(),
        "max_pairs": max_pairs,
        "pairs_sample": pairs[:50],
        "pairs_path": str(PAIRS_PATH),
    }
    SUMMARY_PATH.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"dry-run pairs={summary['mutual_pair_count']} "
        f"remark_writes={summary['remark_write_count']} "
        f"empty_skips={summary['remark_skip_empty_count']} "
        f"active_edges={summary['db_active_edge_count']}",
        flush=True,
    )
    print(f"wrote {SUMMARY_PATH}", flush=True)
    print(f"wrote {PAIRS_PATH}", flush=True)
    return summary


def load_pairs_from_state() -> list[dict]:
    if not PAIRS_PATH.exists():
        raise SystemExit(f"missing dry-run product: {PAIRS_PATH}; run --dry-run first")
    out: list[dict] = []
    for line in PAIRS_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        out.append(json.loads(line))
    return out


def sleep_ms(ms: int) -> None:
    if ms > 0:
        time.sleep(ms / 1000.0)


def friend_add_result_code(resp: dict, to_account: str) -> int:
    top = int(resp.get("ErrorCode") or 0)
    if top != 0:
        return top
    for item in resp.get("ResultItem") or []:
        if str(item.get("To_Account") or "") != to_account:
            continue
        return int(item.get("ResultCode") or 0)
    return 0


def friend_update_result_code(resp: dict, to_account: str) -> int:
    top = int(resp.get("ErrorCode") or 0)
    if top != 0:
        return top
    for item in resp.get("ResultItem") or []:
        if str(item.get("To_Account") or "") != to_account:
            continue
        return int(item.get("ResultCode") or 0)
    return 0


def add_friend_both(im: ImRest, user_a: str, user_b: str) -> int:
    body = {
        "From_Account": user_a,
        "AddFriendItem": [{
            "To_Account": user_b,
            "AddSource": "AddSource_Type_Server",
        }],
        "AddType": "Add_Type_Both",
        "ForceAddFlags": 1,
    }
    resp = im.post("sns/friend_add", body)
    return friend_add_result_code(resp, user_b)


def update_remark(im: ImRest, owner: str, peer: str, remark: str) -> int:
    body = {
        "From_Account": owner,
        "UpdateItem": [{
            "To_Account": peer,
            "SnsItem": [{"Tag": "Tag_SNS_IM_Remark", "Value": remark}],
        }],
    }
    resp = im.post("sns/friend_update", body)
    return friend_update_result_code(resp, peer)


def run_apply(delay_ms: int, max_pairs: int | None, fail_fast: bool) -> dict:
    if not SUMMARY_PATH.exists() or not PAIRS_PATH.exists():
        raise SystemExit("refuse apply: run --dry-run first (missing dry-run products)")
    pairs = load_pairs_from_state()
    if max_pairs is not None and max_pairs > 0:
        pairs = pairs[:max_pairs]

    im = ImRest()
    started = time.time()
    ok_pairs = 0
    fail_pairs = 0
    remark_ok = 0
    remark_fail = 0

    STATE.mkdir(parents=True, exist_ok=True)
    with APPLY_JSONL.open("w", encoding="utf-8") as out:
        for idx, p in enumerate(pairs, 1):
            user_a = p["user_a"]
            user_b = p["user_b"]
            row = {
                "pair": f"{user_a}|{user_b}",
                "user_a": user_a,
                "user_b": user_b,
                "add_ok": False,
                "add_code": None,
                "remark_ab_ok": None,
                "remark_ba_ok": None,
                "error": None,
            }
            try:
                code = add_friend_both(im, user_a, user_b)
                row["add_code"] = code
                row["add_ok"] = code in (0, 30001)
                sleep_ms(delay_ms)
                if not row["add_ok"]:
                    row["error"] = f"friend_add code={code}"
                    fail_pairs += 1
                    out.write(json.dumps(row, ensure_ascii=False) + "\n")
                    print(f"[{idx}/{len(pairs)}] FAIL add {user_a}->{user_b} code={code}", flush=True)
                    if fail_fast:
                        break
                    continue

                if p.get("write_remark_ab") and p.get("remark_ab"):
                    rc = update_remark(im, user_a, user_b, p["remark_ab"])
                    row["remark_ab_ok"] = rc == 0
                    if rc == 0:
                        remark_ok += 1
                    else:
                        remark_fail += 1
                        row["error"] = f"remark_ab code={rc}"
                    sleep_ms(delay_ms)
                if p.get("write_remark_ba") and p.get("remark_ba"):
                    rc = update_remark(im, user_b, user_a, p["remark_ba"])
                    row["remark_ba_ok"] = rc == 0
                    if rc == 0:
                        remark_ok += 1
                    else:
                        remark_fail += 1
                        err = f"remark_ba code={rc}"
                        row["error"] = f"{row['error']}; {err}" if row["error"] else err
                    sleep_ms(delay_ms)

                if row["error"]:
                    fail_pairs += 1
                    print(f"[{idx}/{len(pairs)}] PARTIAL {user_a}|{user_b} {row['error']}", flush=True)
                    if fail_fast:
                        out.write(json.dumps(row, ensure_ascii=False) + "\n")
                        break
                else:
                    ok_pairs += 1
                    if idx % 50 == 0 or idx == len(pairs):
                        print(f"[{idx}/{len(pairs)}] ok {user_a}|{user_b}", flush=True)
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
            except Exception as e:
                row["error"] = str(e)
                fail_pairs += 1
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                print(f"[{idx}/{len(pairs)}] ERROR {user_a}|{user_b} {e}", flush=True)
                if fail_fast:
                    break

    summary = {
        "generated_at": utc_now(),
        "ok_pairs": ok_pairs,
        "fail_pairs": fail_pairs,
        "remark_ok": remark_ok,
        "remark_fail": remark_fail,
        "duration_ms": int((time.time() - started) * 1000),
        "processed": ok_pairs + fail_pairs,
        "planned": len(pairs),
        "delay_ms": delay_ms,
        "rest_base": im.base,
        "sdk_app_id": im.sdk,
    }
    APPLY_SUMMARY.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"apply ok_pairs={ok_pairs} fail_pairs={fail_pairs} "
        f"remark_ok={remark_ok} remark_fail={remark_fail} "
        f"duration_ms={summary['duration_ms']}",
        flush=True,
    )
    print(f"wrote {APPLY_JSONL}", flush=True)
    print(f"wrote {APPLY_SUMMARY}", flush=True)
    return summary


def friend_check(im: ImRest, from_account: str, to_account: str) -> str:
    resp = im.post("sns/friend_check", {
        "From_Account": from_account,
        "To_Account": [to_account],
        "CheckType": "CheckResult_Type_Both",
    })
    if int(resp.get("ErrorCode") or 0) != 0:
        return f"ERR:{resp.get('ErrorCode')}"
    for item in resp.get("InfoItem") or []:
        if str(item.get("To_Account") or "") != to_account:
            continue
        return str(item.get("Relation") or item.get("CheckResult") or "")
    for item in resp.get("ResultItem") or []:
        if str(item.get("To_Account") or "") != to_account:
            continue
        return str(item.get("Relation") or item.get("ResultInfo") or item.get("ResultCode") or "")
    return "NO_ITEM"


def get_remark(im: ImRest, owner: str, peer: str) -> str | None:
    resp = im.post("sns/friend_get_list", {
        "From_Account": owner,
        "To_Account": [peer],
        "TagList": ["Tag_SNS_IM_Remark"],
    })
    if int(resp.get("ErrorCode") or 0) != 0:
        return None
    for item in resp.get("InfoItem") or resp.get("FriendList") or []:
        if str(item.get("To_Account") or "") != peer:
            continue
        for vi in item.get("SnsProfileItem") or item.get("ValueItem") or []:
            if str(vi.get("Tag") or "") == "Tag_SNS_IM_Remark":
                val = vi.get("Value")
                if val is None:
                    return None
                text = str(val).strip()
                return text or None
    return None


def run_verify(sample: int | None, delay_ms: int) -> dict:
    pairs = load_pairs_from_state()
    if sample is not None and sample > 0:
        pairs = pairs[:sample]
    im = ImRest()
    relation_ok = 0
    relation_fail = 0
    remark_ok = 0
    remark_fail = 0
    remark_skip = 0
    details: list[dict] = []

    for idx, p in enumerate(pairs, 1):
        user_a, user_b = p["user_a"], p["user_b"]
        rel = friend_check(im, user_a, user_b)
        sleep_ms(delay_ms)
        rel_ok = "CheckResult_Type_BothWay" in rel or rel in (
            "CheckResult_Type_BothWay",
            "CheckResult_Type_AWithB",
        ) or "Both" in rel
        # Accept common TIM values that indicate mutual friendship.
        if rel in ("CheckResult_Type_BothWay",) or "BothWay" in rel:
            rel_ok = True
        if rel_ok:
            relation_ok += 1
        else:
            relation_fail += 1

        row = {"pair": f"{user_a}|{user_b}", "relation": rel, "relation_ok": rel_ok}

        if p.get("write_remark_ab") and p.get("remark_ab"):
            got = get_remark(im, user_a, user_b)
            sleep_ms(delay_ms)
            ok = got == p["remark_ab"]
            row["remark_ab_got"] = got
            row["remark_ab_ok"] = ok
            if ok:
                remark_ok += 1
            else:
                remark_fail += 1
        else:
            remark_skip += 1

        if p.get("write_remark_ba") and p.get("remark_ba"):
            got = get_remark(im, user_b, user_a)
            sleep_ms(delay_ms)
            ok = got == p["remark_ba"]
            row["remark_ba_got"] = got
            row["remark_ba_ok"] = ok
            if ok:
                remark_ok += 1
            else:
                remark_fail += 1
        else:
            remark_skip += 1

        details.append(row)
        if idx % 50 == 0 or idx == len(pairs):
            print(f"verify [{idx}/{len(pairs)}] relation_ok={relation_ok} fail={relation_fail}", flush=True)

    summary = {
        "generated_at": utc_now(),
        "checked_pairs": len(pairs),
        "relation_ok": relation_ok,
        "relation_fail": relation_fail,
        "remark_ok": remark_ok,
        "remark_fail": remark_fail,
        "remark_skip_empty": remark_skip,
        "sample": sample,
        "details_sample": details[:50],
    }
    VERIFY_SUMMARY.write_text(
        json.dumps(summary, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"verify pairs={len(pairs)} relation_ok={relation_ok} relation_fail={relation_fail} "
        f"remark_ok={remark_ok} remark_fail={remark_fail}",
        flush=True,
    )
    print(f"wrote {VERIFY_SUMMARY}", flush=True)
    return summary


def main() -> None:
    load_env()
    ap = argparse.ArgumentParser(description="Push mutual friends + remarks to IM SNS")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--delay-ms", type=int, default=100)
    ap.add_argument("--max-pairs", type=int, default=0, help="0 = no limit")
    ap.add_argument("--fail-fast", action="store_true")
    ap.add_argument("--verify-sample", type=int, default=0, help="0 = all pairs from dry-run")
    args = ap.parse_args()

    if not (args.dry_run or args.apply or args.verify):
        print("specify --dry-run and/or --apply and/or --verify", file=sys.stderr)
        sys.exit(2)

    max_pairs = args.max_pairs if args.max_pairs > 0 else None
    sample = args.verify_sample if args.verify_sample > 0 else None

    if args.dry_run:
        run_dry_run(max_pairs=max_pairs)
    if args.apply:
        run_apply(delay_ms=args.delay_ms, max_pairs=max_pairs, fail_fast=args.fail_fast)
    if args.verify:
        run_verify(sample=sample, delay_ms=args.delay_ms)


if __name__ == "__main__":
    main()
