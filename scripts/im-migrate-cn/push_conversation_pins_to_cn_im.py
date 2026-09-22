#!/usr/bin/env python3
"""One-shot: push user_conversation_pin rows to China IM recentcontact/top."""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import random
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request
import zlib
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"

# Hard-skip: dismissed / missing on China IM (PLAN lock)
HARD_SKIP_PEERS = frozenset({
    "@TGS#2H6QQ2N5CY",
    "@TGS#2LCYOZN5CZ",
    "@TGS#2WQTOZN5CO",
})


def load_env() -> None:
    for p in (ROOT / "local.env", Path("/www/wwwroot/99chat-server/.env")):
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("'").strip('"'))


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
        self.sdk = int(os.environ["DST_IM_SDK_APP_ID"])
        self.key = os.environ["DST_IM_KEY"]
        base = os.environ.get("DST_IM_REST_BASE", "https://console.tim.qq.com/v4/")
        if not base.endswith("/"):
            base += "/"
        self.base = base
        self.admin = os.environ.get("IM_ADMIN", "administrator")
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


def refuse_unless_china() -> tuple[str, str]:
    sdk = os.environ.get("DST_IM_SDK_APP_ID", "")
    base = os.environ.get("DST_IM_REST_BASE", "")
    if sdk != "1600155864" or "console.tim.qq.com" not in base:
        raise SystemExit("refuse: not China DST IM endpoint")
    return sdk, base


def load_pins() -> list[dict]:
    import subprocess

    sql = (
        "SELECT user_id, chat_type, peer_id, pinned_at "
        "FROM user_conversation_pin ORDER BY user_id, pinned_at ASC;"
    )
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
    out: list[dict] = []
    for line in (proc.stdout or "").splitlines():
        line = line.strip()
        if not line:
            continue
        parts = line.split("\t")
        if len(parts) < 4:
            continue
        out.append({
            "user_id": parts[0],
            "chat_type": parts[1].lower(),
            "peer_id": parts[2],
            "pinned_at": int(parts[3] or 0),
        })
    return out


def chunks(xs: list, n: int):
    for i in range(0, len(xs), n):
        yield xs[i : i + n]


def account_check(im: ImRest, ids: list[str]) -> dict[str, str]:
    status: dict[str, str] = {}
    for batch in chunks(ids, 100):
        raw = im.post(
            "im_open_login_svc/account_check",
            {"CheckItem": [{"UserID": u} for u in batch]},
        )
        if int(raw.get("ErrorCode") or 0) != 0:
            for u in batch:
                status[u] = f"CHECK_ERR:{raw.get('ErrorCode')}"
            time.sleep(0.05)
            continue
        for row in raw.get("ResultItem") or []:
            status[str(row.get("UserID") or "")] = str(row.get("AccountStatus") or "")
        time.sleep(0.05)
    return status


def group_info_codes(im: ImRest, group_ids: list[str]) -> dict[str, int]:
    codes: dict[str, int] = {}
    for batch in chunks(group_ids, 50):
        raw = im.post("group_open_http_svc/get_group_info", {"GroupIdList": batch})
        if int(raw.get("ErrorCode") or 0) != 0:
            for g in batch:
                codes[g] = int(raw.get("ErrorCode") or -1)
            time.sleep(0.05)
            continue
        for g in raw.get("GroupInfo") or []:
            codes[str(g.get("GroupId") or "")] = int(g.get("ErrorCode") or 0)
        for g in batch:
            codes.setdefault(g, 10010)
        time.sleep(0.05)
    return codes


def recent_contact_item(pin: dict) -> dict:
    if pin["chat_type"] == "c2c":
        return {"Type": 1, "To_Account": pin["peer_id"]}
    return {"Type": 2, "GroupId": pin["peer_id"]}


def classify(im: ImRest, pins: list[dict]) -> dict:
    users = sorted({p["user_id"] for p in pins})
    group_peers = sorted({p["peer_id"] for p in pins if p["chat_type"] != "c2c"})
    user_status = account_check(im, users)
    group_codes = group_info_codes(im, group_peers) if group_peers else {}

    push: list[dict] = []
    skip: list[dict] = []
    for p in pins:
        reason = None
        if p["peer_id"] in HARD_SKIP_PEERS:
            reason = "SKIP_HARD_OLD_TGS"
        elif user_status.get(p["user_id"]) != "Imported":
            reason = "SKIP_USER_NOT_IMPORTED"
        elif p["chat_type"] != "c2c":
            code = group_codes.get(p["peer_id"], 10010)
            if code != 0:
                reason = f"SKIP_GROUP_MISSING:{code}"
        if reason:
            skip.append({**p, "reason": reason})
        else:
            push.append(p)

    return {
        "total": len(pins),
        "push_count": len(push),
        "skip_count": len(skip),
        "users": users,
        "user_status": user_status,
        "group_codes": group_codes,
        "push": push,
        "skip": skip,
    }


def group_push_by_user(push: list[dict]) -> dict[str, list[dict]]:
    by_user: dict[str, list[dict]] = defaultdict(list)
    for p in push:
        by_user[p["user_id"]].append(p)
    for uid in by_user:
        by_user[uid].sort(key=lambda x: x["pinned_at"])
    return dict(by_user)


def top_body(user_id: str, pins: list[dict]) -> dict:
    return {
        "From_Account": user_id,
        "OperationType": 1,
        "RecentContactItem": [recent_contact_item(p) for p in pins],
    }


def run_dry_run(im: ImRest, pins: list[dict]) -> dict:
    report = classify(im, pins)
    out = {
        "sdkAppId": os.environ.get("DST_IM_SDK_APP_ID"),
        "restBase": os.environ.get("DST_IM_REST_BASE"),
        "total": report["total"],
        "push_count": report["push_count"],
        "skip_count": report["skip_count"],
        "users": report["users"],
        "user_status": report["user_status"],
        "group_codes": report["group_codes"],
        "push": report["push"],
        "skip": report["skip"],
    }
    path = STATE / "push_pins_dry_run.json"
    path.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "total": out["total"],
                "push_count": out["push_count"],
                "skip_count": out["skip_count"],
                "skip_reasons": sorted({s["reason"] for s in out["skip"]}),
            },
            ensure_ascii=False,
        ),
        flush=True,
    )
    print(f"wrote {path}", flush=True)
    return out


def run_apply(im: ImRest, dry: dict) -> dict:
    by_user = group_push_by_user(dry["push"])
    log_path = STATE / "push_pins_apply.jsonl"
    users_ok = 0
    users_fail = 0
    fail_users: list[str] = []
    with log_path.open("w", encoding="utf-8") as logf:
        for user_id, pins in sorted(by_user.items()):
            body = top_body(user_id, pins)
            raw = im.post("recentcontact/top", body)
            err = int(raw.get("ErrorCode") or 0)
            ok = err == 0 and str(raw.get("ActionStatus") or "") in ("OK", "")
            if ok:
                users_ok += 1
            else:
                users_fail += 1
                fail_users.append(user_id)
            row = {
                "user_id": user_id,
                "items": body["RecentContactItem"],
                "pin_count": len(pins),
                "ErrorCode": err,
                "ErrorInfo": raw.get("ErrorInfo"),
                "ActionStatus": raw.get("ActionStatus"),
                "raw": raw,
            }
            logf.write(json.dumps(row, ensure_ascii=False) + "\n")
            logf.flush()
            print(
                f"top user={user_id} pins={len(pins)} err={err} ok={ok}",
                flush=True,
            )
            time.sleep(0.05)
    summary = {
        "users_total": len(by_user),
        "users_ok": users_ok,
        "users_fail": users_fail,
        "fail_users": fail_users,
        "pins_pushed": len(dry["push"]),
        "pins_skipped": len(dry["skip"]),
    }
    summ_path = STATE / "push_pins_apply_summary.json"
    summ_path.write_text(json.dumps(summary, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    print(f"wrote {log_path}", flush=True)
    print(f"wrote {summ_path}", flush=True)
    return summary


def run_verify(im: ImRest, dry: dict) -> dict:
    apply_path = STATE / "push_pins_apply.jsonl"
    apply_rows = []
    if apply_path.exists():
        for line in apply_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                apply_rows.append(json.loads(line))

    apply_fail = [r for r in apply_rows if int(r.get("ErrorCode") or 0) != 0]
    users = sorted({p["user_id"] for p in dry["push"]})
    peers = sorted({p["peer_id"] for p in dry["push"] if p["chat_type"] != "c2c"})
    user_status = account_check(im, users)
    group_codes = group_info_codes(im, peers) if peers else {}

    by_user = group_push_by_user(dry["push"])
    idempotent_fail: list[dict] = []
    idempotent_ok = 0
    for user_id, pins in sorted(by_user.items()):
        body = top_body(user_id, pins)
        raw = im.post("recentcontact/top", body)
        err = int(raw.get("ErrorCode") or 0)
        if err != 0:
            idempotent_fail.append({
                "user_id": user_id,
                "ErrorCode": err,
                "ErrorInfo": raw.get("ErrorInfo"),
            })
        else:
            idempotent_ok += 1
        time.sleep(0.05)

    not_imported = [u for u in users if user_status.get(u) != "Imported"]
    bad_groups = [g for g, c in group_codes.items() if c != 0]
    passed = (
        not apply_fail
        and not idempotent_fail
        and not not_imported
        and not bad_groups
        and dry["push_count"] == len(dry["push"])
    )

    verdict = {
        "pass": passed,
        "sdkAppId": os.environ.get("DST_IM_SDK_APP_ID"),
        "push_count": dry["push_count"],
        "skip_count": dry["skip_count"],
        "skip": dry["skip"],
        "apply_fail": apply_fail,
        "idempotent_ok": idempotent_ok,
        "idempotent_fail": idempotent_fail,
        "not_imported": not_imported,
        "bad_groups": bad_groups,
        "db_pin_total": dry["total"],
    }

    md_lines = [
        "# push conversation pins → China IM verdict",
        "",
        f"- sdkAppId: `{verdict['sdkAppId']}`",
        f"- pass: **{passed}**",
        f"- db pins: {verdict['db_pin_total']}",
        f"- pushed: {verdict['push_count']}",
        f"- skipped: {verdict['skip_count']}",
        f"- apply_fail: {len(apply_fail)}",
        f"- idempotent_ok: {idempotent_ok}",
        f"- idempotent_fail: {len(idempotent_fail)}",
        "",
        "## skipped",
        "",
    ]
    for s in dry["skip"]:
        md_lines.append(
            f"- `{s['user_id']}` `{s['chat_type']}` `{s['peer_id']}` — {s['reason']}"
        )
    if apply_fail:
        md_lines.extend(["", "## apply failures", ""])
        for r in apply_fail:
            md_lines.append(f"- `{r['user_id']}` ErrorCode={r.get('ErrorCode')} {r.get('ErrorInfo')}")
    if idempotent_fail:
        md_lines.extend(["", "## idempotent re-top failures", ""])
        for r in idempotent_fail:
            md_lines.append(f"- `{r['user_id']}` ErrorCode={r.get('ErrorCode')} {r.get('ErrorInfo')}")
    md_lines.append("")
    path = STATE / "push_pins_verdict.md"
    path.write_text("\n".join(md_lines), encoding="utf-8")
    json_path = STATE / "push_pins_verify.json"
    json_path.write_text(json.dumps(verdict, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({k: verdict[k] for k in (
        "pass", "push_count", "skip_count", "apply_fail", "idempotent_ok",
        "idempotent_fail", "not_imported", "bad_groups", "db_pin_total",
    )}, ensure_ascii=False, default=str), flush=True)
    print(f"wrote {path}", flush=True)
    return verdict


def main() -> int:
    load_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--verify", action="store_true")
    args = ap.parse_args()
    if not (args.dry_run or args.apply or args.verify):
        print("specify --dry-run and/or --apply and/or --verify", flush=True)
        return 2

    sdk, base = refuse_unless_china()
    print(f"sdkAppId={sdk} restBase={base}", flush=True)
    STATE.mkdir(parents=True, exist_ok=True)
    im = ImRest()
    pins = load_pins()
    print(f"db_pins={len(pins)}", flush=True)

    dry = None
    dry_path = STATE / "push_pins_dry_run.json"
    if args.dry_run:
        dry = run_dry_run(im, pins)
    elif dry_path.exists():
        dry = json.loads(dry_path.read_text(encoding="utf-8"))
    else:
        dry = run_dry_run(im, pins)

    if args.apply:
        # refresh classification from current DB+IM before push
        dry = run_dry_run(im, pins)
        run_apply(im, dry)

    if args.verify:
        if not dry_path.exists():
            dry = run_dry_run(im, pins)
        else:
            dry = json.loads(dry_path.read_text(encoding="utf-8"))
        run_verify(im, dry)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
