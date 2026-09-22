#!/usr/bin/env python3
"""为生活缴费 Worker 设备生成专用 token 并写入数据库（仅存 SHA-256 哈希）。"""

from __future__ import annotations

import argparse
import hashlib
import os
import secrets
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT / "scripts" / "data" / "life-payment-worker-tokens.env"

WORKERS = [
    {
        "worker_id": "lp-worker-electric-01",
        "device_id": "device-elec-01",
        "device_name": "电费燃气机-01",
        "support_service_types": "electric,gas",
        "remark": "专用：电费/燃气",
    },
    {
        "worker_id": "lp-worker-water-01",
        "device_id": "device-water-01",
        "device_name": "水费机-01",
        "support_service_types": "water",
        "remark": "专用：水费",
    },
    {
        "worker_id": "lp-worker-mobile-01",
        "device_id": "device-mobile-01",
        "device_name": "话费机-01",
        "support_service_types": "mobile",
        "remark": "专用：手机充值",
    },
    {
        "worker_id": "lp-worker-multi-01",
        "device_id": "device-multi-01",
        "device_name": "全能备用机-01",
        "support_service_types": "mobile,water,electric,gas",
        "remark": "备用：全业务",
    },
]


def generate_token() -> str:
    return "lpw_" + secrets.token_hex(24)


def hash_token(plain: str) -> str:
    return hashlib.sha256(plain.encode("utf-8")).hexdigest()


def mysql_connect(args):
    try:
        import pymysql
    except ImportError:
        return None

    return pymysql.connect(
        host=args.mysql_host,
        port=args.mysql_port,
        user=args.mysql_user,
        password=args.mysql_password,
        database=args.mysql_db,
        charset="utf8mb4",
        autocommit=False,
    )


def mysql_exec_file(args, sql: str) -> None:
    import subprocess

    cmd = [
        "mysql",
        f"-h{args.mysql_host}",
        f"-P{args.mysql_port}",
        f"-u{args.mysql_user}",
        f"-p{args.mysql_password}",
        args.mysql_db,
    ]
    subprocess.run(cmd, input=sql.encode("utf-8"), check=True)


def ensure_schema(conn) -> None:
    with conn.cursor() as cur:
        cur.execute(
            """
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'life_payment_worker_devices'
              AND COLUMN_NAME = 'worker_token_hash'
            """
        )
        if cur.fetchone()[0] == 0:
            cur.execute(
                """
                ALTER TABLE life_payment_worker_devices
                  ADD COLUMN worker_token_hash VARCHAR(64) NULL AFTER remark
                """
            )
        cur.execute(
            """
            SELECT COUNT(*) FROM information_schema.STATISTICS
            WHERE TABLE_SCHEMA = DATABASE()
              AND TABLE_NAME = 'life_payment_worker_devices'
              AND INDEX_NAME = 'uk_lp_worker_token_hash'
            """
        )
        if cur.fetchone()[0] == 0:
            cur.execute(
                """
                ALTER TABLE life_payment_worker_devices
                  ADD UNIQUE KEY uk_lp_worker_token_hash (worker_token_hash)
                """
            )
    conn.commit()


def upsert_worker(conn, worker: dict, token_hash: str) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.%f")
    with conn.cursor() as cur:
        cur.execute(
            "SELECT id FROM life_payment_worker_devices WHERE worker_id = %s",
            (worker["worker_id"],),
        )
        row = cur.fetchone()
        if row:
            cur.execute(
                """
                UPDATE life_payment_worker_devices
                SET device_id = %s,
                    device_name = %s,
                    support_service_types = %s,
                    status = 'offline',
                    remark = %s,
                    worker_token_hash = %s,
                    updated_at = %s
                WHERE worker_id = %s
                """,
                (
                    worker["device_id"],
                    worker["device_name"],
                    worker["support_service_types"],
                    worker["remark"],
                    token_hash,
                    now,
                    worker["worker_id"],
                ),
            )
        else:
            cur.execute(
                """
                INSERT INTO life_payment_worker_devices (
                  worker_id, device_id, device_name, support_service_types,
                  status, remark, worker_token_hash, created_at, updated_at
                ) VALUES (%s, %s, %s, %s, 'offline', %s, %s, %s, %s)
                """,
                (
                    worker["worker_id"],
                    worker["device_id"],
                    worker["device_name"],
                    worker["support_service_types"],
                    worker["remark"],
                    token_hash,
                    now,
                    now,
                ),
            )


def main() -> int:
    parser = argparse.ArgumentParser(description="Seed life-payment worker device tokens")
    parser.add_argument("--mysql", action="store_true", help="写入 MySQL")
    parser.add_argument("--mysql-host", default=os.getenv("MYSQL_HOST", "127.0.0.1"))
    parser.add_argument("--mysql-port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")))
    parser.add_argument("--mysql-user", default=os.getenv("MYSQL_USER", "chat99"))
    parser.add_argument("--mysql-password", default=os.getenv("MYSQL_PASSWORD", "chat99"))
    parser.add_argument("--mysql-db", default=os.getenv("MYSQL_DATABASE", "chat99"))
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help="明文 token 输出文件")
    parser.add_argument("--force", action="store_true", help="覆盖已有 token")
    args = parser.parse_args()

    issued: list[tuple[dict, str]] = []
    for worker in WORKERS:
        plain = generate_token()
        issued.append((worker, plain))

    lines = [
        "# 生活缴费 Worker 专用 Token（勿提交 Git）",
        f"# generated_at={datetime.now(timezone.utc).isoformat()}",
        "# Authorization: Bearer <token>",
        "# worker_id 必须与下表 worker_id 一致",
        "",
    ]
    for worker, plain in issued:
        lines.append(f"# {worker['device_name']} | {worker['support_service_types']}")
        lines.append(f"LIFE_PAYMENT_WORKER_{worker['worker_id'].upper().replace('-', '_')}={plain}")
        lines.append("")

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text("\n".join(lines) + "\n", encoding="utf-8")
    print(f"Wrote tokens to {args.out}")

    if not args.mysql:
        print("Dry-run only. Re-run with --mysql to apply.")
        for worker, plain in issued:
            print(f"  {worker['worker_id']}: {plain}")
        return 0

    conn = mysql_connect(args)
    if conn is None:
        sql_parts = ["-- auto-generated by seed-life-payment-workers.py"]
        for worker, plain in issued:
            h = hash_token(plain)
            sql_parts.append(
                f"""
INSERT INTO life_payment_worker_devices
(worker_id, device_id, device_name, support_service_types, status, remark, worker_token_hash, created_at, updated_at)
VALUES ('{worker["worker_id"]}', '{worker["device_id"]}', '{worker["device_name"]}', '{worker["support_service_types"]}',
        'offline', '{worker["remark"]}', '{h}', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
ON DUPLICATE KEY UPDATE
  device_id=VALUES(device_id), device_name=VALUES(device_name), support_service_types=VALUES(support_service_types),
  remark=VALUES(remark), worker_token_hash=VALUES(worker_token_hash), updated_at=UTC_TIMESTAMP(6);
""".strip()
            )
        ensure_schema_sql = """
SET @col_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'life_payment_worker_devices'
    AND COLUMN_NAME = 'worker_token_hash'
);
SET @ddl := IF(@col_exists = 0,
  'ALTER TABLE life_payment_worker_devices ADD COLUMN worker_token_hash VARCHAR(64) NULL AFTER remark',
  'SELECT 1');
PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
"""
        mysql_exec_file(args, ensure_schema_sql + "\n" + "\n".join(sql_parts))
    else:
        existing: set[str] = set()
        try:
            ensure_schema(conn)
            with conn.cursor() as cur:
                cur.execute(
                    """
                    SELECT worker_id FROM life_payment_worker_devices
                    WHERE worker_token_hash IS NOT NULL AND worker_token_hash <> ''
                    """
                )
                existing = {row[0] for row in cur.fetchall()}
            if existing and not args.force:
                blocked = [w["worker_id"] for w in WORKERS if w["worker_id"] in existing]
                if blocked:
                    print(
                        "已有 token 的设备（跳过，使用 --force 轮换）: " + ", ".join(blocked),
                        file=sys.stderr,
                    )
            for worker, plain in issued:
                if worker["worker_id"] in existing and not args.force:
                    continue
                upsert_worker(conn, worker, hash_token(plain))
            conn.commit()
        finally:
            conn.close()

    print("MySQL seed complete.")
    for worker, plain in issued:
        print(f"  {worker['worker_id']}: {plain}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
