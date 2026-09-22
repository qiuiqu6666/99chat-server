#!/usr/bin/env python3
"""G-DB cutover: remap group IDs + switch IM app_setting. Secrets via env only."""
from __future__ import annotations

import json
import os
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"
ROLLBACK = STATE / "rollback"
MAP_FILE = STATE / "group_id_map.jsonl"

GROUP_ID_TABLES = [
    "chat_complaint",
    "chat_message_202606",
    "chat_message_202607",
    "chat_message_202608",
    "group_change_event",
    "group_conversation_recent",
    "group_join_application",
    "group_member",
    "group_profile",
    "group_settings",
    "group_system_notice",
    "user_owned_group",
    "wallet_red_packet",
    "wallet_red_packet_claim_notice",
]

PEER_ID_TABLES = [
    "user_c2c_conversation_recent",  # may have no group rows; still safe JOIN
    "user_conversation_archive",
    "user_conversation_folder_member",
    "user_conversation_notify",
    "user_conversation_pin",
]


def env(name: str, default: str | None = None) -> str:
    v = os.environ.get(name, default)
    if v is None or v == "":
        raise SystemExit(f"missing env {name}")
    return v


def mysql_args() -> list[str]:
    return [
        "mysql",
        f"-h{env('DB_HOST', '127.0.0.1')}",
        f"-P{env('DB_PORT', '3306')}",
        f"-u{env('DB_USERNAME')}",
        f"-p{env('DB_PASSWORD')}",
        env("DB_NAME", "chat99"),
    ]


def run_sql(sql: str, *, batch: bool = False) -> str:
    cmd = mysql_args() + (["-N", "-B"] if batch else []) + ["-e", sql]
    p = subprocess.run(cmd, capture_output=True, text=True)
    if p.returncode != 0:
        raise SystemExit(f"mysql failed:\n{p.stderr}\nSQL:\n{sql[:500]}")
    return p.stdout


def load_map() -> list[tuple[str, str]]:
    rows: list[tuple[str, str]] = []
    seen: set[str] = set()
    for line in MAP_FILE.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        src, dst = d["srcGroupId"], d["dstGroupId"]
        if src in seen:
            continue
        seen.add(src)
        rows.append((src, dst))
    return rows


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


def main() -> int:
    ROLLBACK.mkdir(parents=True, exist_ok=True)
    ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    pairs = load_map()
    print(f"loaded map pairs={len(pairs)}")

    # 1) backup IM settings
    out = run_sql(
        "SELECT setting_key, setting_value FROM app_setting "
        "WHERE setting_key IN ('IM_SDK_APP_ID','IM_KEY');",
        batch=True,
    )
    backup_settings = ROLLBACK / f"app_setting_im_{ts}.tsv"
    backup_settings.write_text(out, encoding="utf-8")
    print(f"backed up IM settings -> {backup_settings}")

    # 2) remap table
    run_sql(
        "CREATE TABLE IF NOT EXISTS im_group_id_remap ("
        "src_group_id VARCHAR(128) PRIMARY KEY,"
        "dst_group_id VARCHAR(128) NOT NULL,"
        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;"
    )
    run_sql("TRUNCATE TABLE im_group_id_remap;")

    # load in chunks
    chunk: list[str] = []
    for i, (src, dst) in enumerate(pairs, 1):
        chunk.append(f"('{esc(src)}','{esc(dst)}')")
        if len(chunk) >= 500:
            run_sql(
                "INSERT INTO im_group_id_remap(src_group_id,dst_group_id) VALUES "
                + ",".join(chunk)
            )
            chunk.clear()
            print(f"  remap insert {i}/{len(pairs)}")
    if chunk:
        run_sql(
            "INSERT INTO im_group_id_remap(src_group_id,dst_group_id) VALUES "
            + ",".join(chunk)
        )
    cnt = run_sql("SELECT COUNT(*) FROM im_group_id_remap;", batch=True).strip()
    print(f"im_group_id_remap rows={cnt}")

    # 3) UPDATE group_id / peer_id under FK off
    run_sql("SET FOREIGN_KEY_CHECKS=0;")
    for table in GROUP_ID_TABLES:
        exists = run_sql(
            f"SELECT COUNT(*) FROM information_schema.TABLES "
            f"WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}';",
            batch=True,
        ).strip()
        if exists == "0":
            print(f"skip missing table {table}")
            continue
        sql = (
            f"UPDATE {table} t "
            f"INNER JOIN im_group_id_remap r "
            f"ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
            f"SET t.group_id = r.dst_group_id;"
        )
        # mysql CLI doesn't print affected rows easily; use ROW_COUNT via select
        run_sql(sql)
        affected = run_sql("SELECT ROW_COUNT();", batch=True).strip()
        print(f"UPDATE {table}.group_id affected~={affected}")

    for table in PEER_ID_TABLES:
        exists = run_sql(
            f"SELECT COUNT(*) FROM information_schema.TABLES "
            f"WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{table}';",
            batch=True,
        ).strip()
        if exists == "0":
            print(f"skip missing table {table}")
            continue
        sql = (
            f"UPDATE {table} t "
            f"INNER JOIN im_group_id_remap r "
            f"ON t.peer_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
            f"SET t.peer_id = r.dst_group_id;"
        )
        run_sql(sql)
        affected = run_sql("SELECT ROW_COUNT();", batch=True).strip()
        print(f"UPDATE {table}.peer_id affected~={affected}")
    run_sql("SET FOREIGN_KEY_CHECKS=1;")

    # leftover @TGS# in group_profile
    left = run_sql(
        "SELECT COUNT(*) FROM group_profile WHERE group_id LIKE '@TGS#%';",
        batch=True,
    ).strip()
    print(f"group_profile remaining @TGS#={left}")

    # 4) switch app_setting
    new_sdk = env("DST_IM_SDK_APP_ID", "1600155864")
    new_key = env("DST_IM_KEY")
    run_sql(
        f"UPDATE app_setting SET setting_value='{esc(new_sdk)}' "
        f"WHERE setting_key='IM_SDK_APP_ID';"
    )
    run_sql(
        f"UPDATE app_setting SET setting_value='{esc(new_key)}' "
        f"WHERE setting_key='IM_KEY';"
    )
    verify = run_sql(
        "SELECT setting_key, setting_value FROM app_setting "
        "WHERE setting_key='IM_SDK_APP_ID';",
        batch=True,
    ).strip()
    print(f"app_setting now: {verify}")
    print("IM_KEY updated (value not printed)")
    return 0


if __name__ == "__main__":
    # load main .env then overlay DST from migrate local.env
    for p in (
        Path("/www/wwwroot/99chat-server/.env"),
        ROOT / "local.env",
    ):
        if not p.exists():
            continue
        for line in p.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip("'").strip('"'))
    # force DST from local.env if present
    local = ROOT / "local.env"
    if local.exists():
        for line in local.read_text(encoding="utf-8").splitlines():
            if line.startswith("DST_IM_"):
                k, v = line.split("=", 1)
                os.environ[k.strip()] = v.strip().strip("'").strip('"')
    sys.exit(main())
