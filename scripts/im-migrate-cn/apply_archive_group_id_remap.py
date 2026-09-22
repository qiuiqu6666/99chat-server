#!/usr/bin/env python3
"""H1: remap archive chat_message_* group_id/msg_key + leftover conversation projection.
Does NOT touch app_setting / IM keys.

Strategy (fast):
- Equality JOIN on group_id for src->dst
- For dirty msg_key (group already dst, key still src-prefixed): rewrite in Python batches
"""
from __future__ import annotations

import argparse
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
    "group_conversation_recent",
    "group_change_event",
    "group_join_application",
    "group_member",
    "group_profile",
    "group_settings",
    "group_system_notice",
    "user_owned_group",
    "wallet_red_packet",
    "wallet_red_packet_claim_notice",
    "chat_complaint",
]

PEER_ID_TABLES = [
    "user_c2c_conversation_recent",
    "user_conversation_archive",
    "user_conversation_folder_member",
    "user_conversation_notify",
    "user_conversation_pin",
]


def load_env() -> None:
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
        raise SystemExit(f"mysql failed:\n{p.stderr}\nSQL:\n{sql[:800]}")
    return p.stdout


def esc(s: str) -> str:
    return s.replace("\\", "\\\\").replace("'", "''")


def load_map() -> tuple[dict[str, str], dict[str, str]]:
    src_to_dst: dict[str, str] = {}
    dst_to_src: dict[str, str] = {}
    for line in MAP_FILE.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        d = json.loads(line)
        src, dst = d["srcGroupId"], d["dstGroupId"]
        if src == dst:
            continue
        if src not in src_to_dst:
            src_to_dst[src] = dst
            dst_to_src[dst] = src
    return src_to_dst, dst_to_src


def list_message_tables() -> list[str]:
    out = run_sql(
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema=DATABASE() AND table_name REGEXP '^chat_message_[0-9]{6}$' "
        "ORDER BY table_name;",
        batch=True,
    )
    return [ln.strip() for ln in out.splitlines() if ln.strip()]


def table_exists(name: str) -> bool:
    n = run_sql(
        f"SELECT COUNT(*) FROM information_schema.TABLES "
        f"WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='{esc(name)}';",
        batch=True,
    ).strip()
    return n != "0"


def ensure_remap_loaded(src_to_dst: dict[str, str]) -> None:
    run_sql(
        "CREATE TABLE IF NOT EXISTS im_group_id_remap ("
        "src_group_id VARCHAR(128) PRIMARY KEY,"
        "dst_group_id VARCHAR(128) NOT NULL,"
        "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP"
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin;"
    )
    run_sql("TRUNCATE TABLE im_group_id_remap;")
    chunk: list[str] = []
    n = 0
    for src, dst in src_to_dst.items():
        chunk.append(f"('{esc(src)}','{esc(dst)}')")
        n += 1
        if len(chunk) >= 500:
            run_sql(
                "INSERT INTO im_group_id_remap(src_group_id,dst_group_id) VALUES "
                + ",".join(chunk)
            )
            chunk.clear()
            print(f"  remap insert {n}/{len(src_to_dst)}")
    if chunk:
        run_sql(
            "INSERT INTO im_group_id_remap(src_group_id,dst_group_id) VALUES "
            + ",".join(chunk)
        )
    cnt = run_sql("SELECT COUNT(*) FROM im_group_id_remap;", batch=True).strip()
    print(f"im_group_id_remap rows={cnt}")


def dry_stats(table: str) -> dict:
    gid_need = run_sql(
        f"SELECT COUNT(*) FROM `{table}` t "
        f"INNER JOIN im_group_id_remap r "
        f"ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin;",
        batch=True,
    ).strip()
    dirty = run_sql(
        f"SELECT COUNT(*) FROM `{table}` "
        f"WHERE group_id IS NOT NULL AND group_id != '' "
        f"AND msg_key LIKE '@TGS#%' "
        f"AND msg_key NOT LIKE CONCAT(group_id, ':%') "
        f"AND (group_id LIKE 'm%' OR group_id REGEXP '^@TGS#_m');",
        batch=True,
    ).strip()
    leftover = run_sql(
        f"SELECT COUNT(*) FROM `{table}` WHERE group_id REGEXP '^@TGS#' "
        f"AND group_id NOT REGEXP '^@TGS#_m';",
        batch=True,
    ).strip()
    return {
        "table": table,
        "group_id_rows": int(gid_need or 0),
        "dirty_msg_key_rows": int(dirty or 0),
        "leftover_src_group_id": int(leftover or 0),
    }


def apply_group_id_equality(table: str) -> str:
    run_sql(
        f"UPDATE `{table}` t "
        f"INNER JOIN im_group_id_remap r "
        f"ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
        f"SET t.group_id = r.dst_group_id;"
    )
    return run_sql("SELECT ROW_COUNT();", batch=True).strip()


def apply_dirty_msg_keys(
    table: str,
    dst_to_src: dict[str, str],
    conflict_path: Path,
    backup_dir: Path,
) -> dict:
    """Rewrite msg_key when group_id is already dst but key still uses src prefix."""
    rows = run_sql(
        f"SELECT id, group_id, msg_key FROM `{table}` "
        f"WHERE group_id IS NOT NULL AND group_id != '' "
        f"AND msg_key LIKE '@TGS#%' "
        f"AND msg_key NOT LIKE CONCAT(group_id, ':%') "
        f"AND (group_id LIKE 'm%' OR group_id REGEXP '^@TGS#_m');",
        batch=True,
    )
    bak = backup_dir / f"{table}_dirty_msg_key.tsv"
    bak.write_text(rows, encoding="utf-8")

    updates: list[tuple[int, str, str]] = []  # id, old, new
    conflicts = 0
    skipped_no_colon = 0
    for line in rows.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        rid, gid, mkey = parts[0], parts[1], parts[2]
        colon = mkey.find(":")
        if colon < 0:
            skipped_no_colon += 1
            continue
        # Prefer suffix after first colon; validate prefix is mapped src for this dst
        src = dst_to_src.get(gid)
        if src and mkey.startswith(src + ":"):
            new_key = gid + mkey[len(src) :]
        else:
            # fallback: replace prefix before ':' with group_id
            new_key = gid + mkey[colon:]
        updates.append((int(rid), mkey, new_key))

    # detect conflicts in-memory + DB existing keys
    new_keys = [u[2] for u in updates]
    existing: set[str] = set()
    # chunk check existing
    for i in range(0, len(new_keys), 400):
        chunk = new_keys[i : i + 400]
        in_list = ",".join("'" + esc(k) + "'" for k in chunk)
        out = run_sql(
            f"SELECT msg_key FROM `{table}` WHERE msg_key IN ({in_list});",
            batch=True,
        )
        for k in out.splitlines():
            if k.strip():
                existing.add(k.strip())

    seen_new: set[str] = set()
    apply_list: list[tuple[int, str]] = []
    with conflict_path.open("a", encoding="utf-8") as cf:
        for rid, old, new in updates:
            if new in existing or new in seen_new:
                conflicts += 1
                cf.write(
                    json.dumps(
                        {
                            "table": table,
                            "id": rid,
                            "oldMsgKey": old,
                            "newMsgKey": new,
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                continue
            seen_new.add(new)
            apply_list.append((rid, new))

    updated = 0
    for i in range(0, len(apply_list), 200):
        chunk = apply_list[i : i + 200]
        # CASE UPDATE
        cases = " ".join(
            f"WHEN {rid} THEN '{esc(new)}'" for rid, new in chunk
        )
        ids = ",".join(str(rid) for rid, _ in chunk)
        run_sql(
            f"UPDATE `{table}` SET msg_key = CASE id {cases} END "
            f"WHERE id IN ({ids});"
        )
        updated += len(chunk)
        if updated % 2000 == 0 or i + 200 >= len(apply_list):
            print(f"  {table} dirty msg_key updated {updated}/{len(apply_list)}")

    return {
        "table": table,
        "candidates": len(updates),
        "updated": updated,
        "conflicts": conflicts,
        "skipped_no_colon": skipped_no_colon,
        "backup": str(bak),
    }


def apply_msg_key_via_src_prefix(table: str, conflict_path: Path, backup_dir: Path) -> dict:
    """For rows whose msg_key still starts with src (even if group_id already changed).
    Uses equality on computed prefix via remap: join where msg_key = concat(src, ':', ...)
    Fast path: only rows still having group_id=src already handled; this covers leftover
    by scanning msg_key prefix against remap in SQL with equality on LEFT part — still hard.

    Instead: SELECT rows WHERE msg_key LIKE '@TGS#%' AND msg_key NOT LIKE 'm%' 
    AND msg_key NOT LIKE '@TGS#_m%' — then Python map.
    """
    rows = run_sql(
        f"SELECT id, group_id, msg_key FROM `{table}` "
        f"WHERE msg_key LIKE '@TGS#%' "
        f"AND msg_key NOT LIKE '@TGS#_m%';",
        batch=True,
    )
    # Filter in Python using remap loaded globally via im_group_id_remap query once
    remap_out = run_sql("SELECT src_group_id, dst_group_id FROM im_group_id_remap;", batch=True)
    src_to_dst = {}
    for line in remap_out.splitlines():
        if not line.strip():
            continue
        a, b = line.split("\t", 1)
        src_to_dst[a] = b

    bak = backup_dir / f"{table}_srcprefix_msg_key.tsv"
    candidates: list[tuple[int, str, str, str]] = []  # id, gid, old, new
    for line in rows.splitlines():
        if not line.strip():
            continue
        parts = line.split("\t")
        if len(parts) < 3:
            continue
        rid, gid, mkey = parts[0], parts[1], parts[2]
        colon = mkey.find(":")
        if colon < 0:
            continue
        prefix = mkey[:colon]
        dst = src_to_dst.get(prefix)
        if not dst:
            continue
        new_key = dst + mkey[colon:]
        if new_key == mkey:
            continue
        candidates.append((int(rid), gid, mkey, new_key))

    bak.write_text(
        "\n".join(f"{a}\t{b}\t{c}\t{d}" for a, b, c, d in candidates) + ("\n" if candidates else ""),
        encoding="utf-8",
    )

    existing: set[str] = set()
    new_keys = [c[3] for c in candidates]
    for i in range(0, len(new_keys), 400):
        chunk = new_keys[i : i + 400]
        if not chunk:
            continue
        in_list = ",".join("'" + esc(k) + "'" for k in chunk)
        out = run_sql(
            f"SELECT msg_key FROM `{table}` WHERE msg_key IN ({in_list});",
            batch=True,
        )
        for k in out.splitlines():
            if k.strip():
                existing.add(k.strip())

    seen: set[str] = set()
    apply_list: list[tuple[int, str]] = []
    conflicts = 0
    with conflict_path.open("a", encoding="utf-8") as cf:
        for rid, gid, old, new in candidates:
            if new in existing or new in seen:
                conflicts += 1
                cf.write(
                    json.dumps(
                        {
                            "table": table,
                            "id": rid,
                            "groupId": gid,
                            "oldMsgKey": old,
                            "newMsgKey": new,
                        },
                        ensure_ascii=False,
                    )
                    + "\n"
                )
                continue
            seen.add(new)
            apply_list.append((rid, new))

    updated = 0
    for i in range(0, len(apply_list), 200):
        chunk = apply_list[i : i + 200]
        cases = " ".join(f"WHEN {rid} THEN '{esc(new)}'" for rid, new in chunk)
        ids = ",".join(str(rid) for rid, _ in chunk)
        run_sql(
            f"UPDATE `{table}` SET msg_key = CASE id {cases} END WHERE id IN ({ids});"
        )
        updated += len(chunk)
        if updated % 2000 == 0 or i + 200 >= len(apply_list):
            print(f"  {table} srcprefix msg_key updated {updated}/{len(apply_list)}")

    return {
        "table": table,
        "candidates": len(candidates),
        "updated": updated,
        "conflicts": conflicts,
        "backup": str(bak),
    }


def apply_projection() -> None:
    run_sql("SET FOREIGN_KEY_CHECKS=0;")
    for table in GROUP_ID_TABLES:
        if not table_exists(table):
            print(f"skip missing {table}")
            continue
        run_sql(
            f"UPDATE `{table}` t "
            f"INNER JOIN im_group_id_remap r "
            f"ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
            f"LEFT JOIN `{table}` d "
            f"ON d.group_id COLLATE utf8mb4_bin = r.dst_group_id COLLATE utf8mb4_bin "
            f"SET t.group_id = r.dst_group_id "
            f"WHERE d.group_id IS NULL;"
        )
        aff = run_sql("SELECT ROW_COUNT();", batch=True).strip()
        run_sql(
            f"DELETE t FROM `{table}` t "
            f"INNER JOIN im_group_id_remap r "
            f"ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
            f"INNER JOIN `{table}` d "
            f"ON d.group_id COLLATE utf8mb4_bin = r.dst_group_id COLLATE utf8mb4_bin;"
        )
        deleted = run_sql("SELECT ROW_COUNT();", batch=True).strip()
        print(f"UPDATE {table}.group_id affected~={aff} deleted_src_dup~={deleted}")
    for table in PEER_ID_TABLES:
        if not table_exists(table):
            print(f"skip missing {table}")
            continue
        # Best-effort; composite PK conflicts are skipped via ignore of failed batch if any
        try:
            run_sql(
                f"UPDATE `{table}` t "
                f"INNER JOIN im_group_id_remap r "
                f"ON t.peer_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin "
                f"SET t.peer_id = r.dst_group_id;"
            )
            aff = run_sql("SELECT ROW_COUNT();", batch=True).strip()
            print(f"UPDATE {table}.peer_id affected~={aff}")
        except SystemExit as e:
            print(f"WARN peer update {table}: {e}")
    run_sql("SET FOREIGN_KEY_CHECKS=1;")


def write_unmapped(tables: list[str], out: Path) -> int:
    lines: list[str] = []
    for table in tables:
        rows = run_sql(
            f"SELECT DISTINCT group_id FROM `{table}` "
            f"WHERE group_id REGEXP '^@TGS#' AND group_id NOT REGEXP '^@TGS#_m' "
            f"AND group_id NOT IN (SELECT src_group_id FROM im_group_id_remap) "
            f"AND group_id NOT IN (SELECT dst_group_id FROM im_group_id_remap);",
            batch=True,
        )
        for gid in rows.splitlines():
            gid = gid.strip()
            if gid:
                lines.append(f"{table}\t{gid}")
    out.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    return len(lines)


def main() -> int:
    load_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true")
    ap.add_argument(
        "--projection-only",
        action="store_true",
        help="only remap leftover group_id/peer_id projection tables",
    )
    args = ap.parse_args()

    src_to_dst, dst_to_src = load_map()
    print(f"loaded map pairs={len(src_to_dst)}")
    ensure_remap_loaded(src_to_dst)

    tables = list_message_tables()
    if not args.projection_only:
        print(f"message tables: {tables}")
        for t in tables:
            print(f"DRY {dry_stats(t)}")

    proj = run_sql(
        "SELECT COUNT(*) FROM group_conversation_recent t "
        "INNER JOIN im_group_id_remap r "
        "ON t.group_id COLLATE utf8mb4_bin = r.src_group_id COLLATE utf8mb4_bin;",
        batch=True,
    ).strip()
    print(f"DRY group_conversation_recent remapable={proj}")

    if not args.apply:
        print("dry-run only; pass --apply to write")
        return 0

    if not args.projection_only:
        ts = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_dir = ROLLBACK / f"archive_remap_{ts}"
        backup_dir.mkdir(parents=True, exist_ok=True)
        conflict_path = STATE / "msg_key_conflicts.jsonl"
        conflict_path.write_text("", encoding="utf-8")
        print(f"backup dir {backup_dir}")

        for t in tables:
            gid_aff = apply_group_id_equality(t)
            print(f"APPLY {t} group_id_updated={gid_aff}")
            r1 = apply_dirty_msg_keys(t, dst_to_src, conflict_path, backup_dir)
            print(f"APPLY dirty {r1}")
            r2 = apply_msg_key_via_src_prefix(t, conflict_path, backup_dir)
            print(f"APPLY srcprefix {r2}")

    apply_projection()
    unmapped = STATE / "unmapped_archive_group_ids.tsv"
    n = write_unmapped(tables, unmapped)
    print(f"wrote {unmapped} lines={n}")

    if not args.projection_only:
        for t in tables:
            print(f"POST {dry_stats(t)}")

    left = run_sql(
        "SELECT COUNT(*) FROM group_profile WHERE group_id REGEXP '^@TGS#' "
        "AND group_id NOT REGEXP '^@TGS#_m' "
        "AND group_id IN (SELECT src_group_id FROM im_group_id_remap);",
        batch=True,
    ).strip()
    print(f"POST group_profile still_src_in_map={left}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
