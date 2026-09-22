#!/usr/bin/env python3
"""Bind+enable privileged m… groups to a machine code via robot-service APIs."""
from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"
DEFAULT_CODE = "GZKH-DJ3M-VKSB"


def load_env() -> None:
    for p in (Path("/www/wwwroot/99chat-server/.env"), ROOT / "local.env"):
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("'").strip('"'))


def mysql_robot(sql: str) -> str:
    cmd = [
        "mysql",
        f"-h{os.environ['ROBOT_DB_HOST']}",
        f"-P{os.environ.get('ROBOT_DB_PORT', '3306')}",
        f"-u{os.environ['ROBOT_DB_USERNAME']}",
        f"-p{os.environ['ROBOT_DB_PASSWORD']}",
        os.environ["ROBOT_DB_NAME"],
        "-N",
        "-B",
        "-e",
        sql,
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(p.stderr.strip() or "mysql failed")
    return p.stdout


def http_json(method: str, url: str, headers: dict[str, str], body: dict | None) -> tuple[int, dict]:
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, method=method)
    for k, v in headers.items():
        req.add_header(k, v)
    if body is not None:
        req.add_header("Content-Type", "application/json; charset=utf-8")
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            raw = resp.read().decode("utf-8") or "{}"
            return resp.status, json.loads(raw)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8") or "{}"
        try:
            parsed = json.loads(raw)
        except Exception:
            parsed = {"message": raw, "success": False}
        return e.code, parsed


def probe_secret() -> str:
    return (os.environ.get("TELEGRAM_PROBE_SECRET") or os.environ.get("ROBOT_SYNC_SECRET") or "").strip()


def force_unbind_group(group_id: str) -> None:
    gid = group_id.replace("'", "''")
    mysql_robot(f"DELETE FROM robot_group_binding WHERE im_group_id='{gid}'")


def process_one(base: str, code: str, secret: str, group_id: str) -> dict:
    headers_bind = {"X-Machine-Code": code}
    headers_en = {"X-Probe-Secret": secret}
    conflict_resolved = False

    status, bind_body = http_json(
        "POST", f"{base}/api/internal/robot-machines/bind-group",
        headers_bind, {"groupId": group_id})
    msg = str(bind_body.get("message") or bind_body.get("code") or "")
    if status == 409 or "GROUP_ALREADY_BOUND" in msg or "GROUP_ALREADY_BOUND" in json.dumps(bind_body):
        force_unbind_group(group_id)
        conflict_resolved = True
        status, bind_body = http_json(
            "POST", f"{base}/api/internal/robot-machines/bind-group",
            headers_bind, {"groupId": group_id})

    if status >= 400 or bind_body.get("success") is False:
        return {
            "groupId": group_id,
            "ok": False,
            "step": "bind",
            "http": status,
            "body": bind_body,
            "conflict_resolved": conflict_resolved,
        }

    status2, en_body = http_json(
        "POST", f"{base}/api/internal/robot-machines/enable-for-group",
        headers_en, {"groupId": group_id, "robotId": code})
    if status2 >= 400 or en_body.get("success") is False:
        return {
            "groupId": group_id,
            "ok": False,
            "step": "enable",
            "http": status2,
            "body": en_body,
            "conflict_resolved": conflict_resolved,
        }

    return {
        "groupId": group_id,
        "ok": True,
        "step": "done",
        "http": status2,
        "enabled": en_body.get("enabled"),
        "machineCode": en_body.get("machineCode"),
        "conflict_resolved": conflict_resolved,
    }


def load_done(log_path: Path) -> set[str]:
    done: set[str] = set()
    if not log_path.exists():
        return done
    for line in log_path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        try:
            row = json.loads(line)
        except Exception:
            continue
        if row.get("ok") and row.get("groupId"):
            done.add(row["groupId"])
    return done


def main() -> int:
    load_env()
    code = DEFAULT_CODE
    base = (os.environ.get("ROBOT_SERVICE_URL") or "http://127.0.0.1:8091").rstrip("/")
    secret = probe_secret()
    if not secret:
        print("missing probe secret", file=sys.stderr)
        return 2

    ids_path = STATE / "privilege_m_ids_GZKH.txt"
    ids = [ln.strip() for ln in ids_path.read_text(encoding="utf-8").splitlines() if ln.strip()]
    limit = None
    resume = "--resume" in sys.argv
    for i, arg in enumerate(sys.argv):
        if arg == "--limit" and i + 1 < len(sys.argv):
            limit = int(sys.argv[i + 1])
    if limit is not None:
        ids = ids[:limit]

    log_path = STATE / "rebind_GZKH_apply.jsonl"
    if not resume and limit is None and log_path.exists():
        # full run without resume starts fresh unless --resume
        pass
    done = load_done(log_path) if resume else (load_done(log_path) if limit is not None and log_path.exists() else set())
    if not resume and limit is None:
        done = set()
        log_path.write_text("", encoding="utf-8")

    ok = fail = conflict = 0
    t0 = time.time()
    with log_path.open("a", encoding="utf-8") as logf:
        for idx, gid in enumerate(ids, 1):
            if gid in done:
                continue
            row = process_one(base, code, secret, gid)
            logf.write(json.dumps(row, ensure_ascii=False) + "\n")
            logf.flush()
            if row.get("ok"):
                ok += 1
                if row.get("conflict_resolved"):
                    conflict += 1
            else:
                fail += 1
                print(f"FAIL {gid} {row}", flush=True)
            if idx % 100 == 0 or idx == len(ids):
                print(
                    f"progress {idx}/{len(ids)} ok={ok} fail={fail} conflict_resolved={conflict}",
                    flush=True,
                )
            time.sleep(0.01)

    summary = {
        "total_input": len(ids),
        "ok": ok,
        "fail": fail,
        "conflict_resolved": conflict,
        "skipped_done": len(done),
        "elapsed_s": round(time.time() - t0, 1),
        "log": str(log_path),
    }
    (STATE / "rebind_GZKH_apply_summary.json").write_text(
        json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, ensure_ascii=False))
    return 0 if fail == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
