#!/usr/bin/env python3
"""Delete ghost profiles shaped like @TGS#_@TGS#m… (wrong community wrap of migrated m-ids)."""
from __future__ import annotations

import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"
ROLLBACK = STATE / "rollback"


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


def mysql(sql: str) -> str:
    cmd = [
        "mysql",
        f"-h{os.environ.get('DB_HOST', '127.0.0.1')}",
        f"-P{os.environ.get('DB_PORT', '3306')}",
        f"-u{os.environ['DB_USERNAME']}",
        f"-p{os.environ['DB_PASSWORD']}",
        os.environ.get("DB_NAME", "chat99"),
        "-N",
        "-B",
        "-e",
        sql,
    ]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(p.stderr)
    return p.stdout


def main() -> int:
    load_env()
    apply = "--apply" in sys.argv
    rows = mysql(
        "SELECT group_id, dismissed FROM group_profile "
        "WHERE group_id LIKE '@TGS#_@TGS#m%';"
    )
    lines = [ln for ln in rows.splitlines() if ln.strip()]
    print(f"ghost candidates={len(lines)}")
    for ln in lines:
        print(" ", ln)
    if not apply:
        print("dry-run; pass --apply to delete")
        return 0
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    bak = ROLLBACK / f"bad_double_prefix_{ts}"
    bak.mkdir(parents=True, exist_ok=True)
    (bak / "group_profile.tsv").write_text(rows, encoding="utf-8")
    mysql("DELETE FROM group_profile WHERE group_id LIKE '@TGS#_@TGS#m%';")
    left = mysql(
        "SELECT COUNT(*) FROM group_profile WHERE group_id LIKE '@TGS#_@TGS#m%';"
    ).strip()
    print(f"backup={bak} remaining={left}")
    return 0 if left == "0" else 2


if __name__ == "__main__":
    sys.exit(main())
