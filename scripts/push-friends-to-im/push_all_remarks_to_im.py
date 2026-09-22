#!/usr/bin/env python3
"""One-shot: push all non-empty local remarks (mutual + one-way) to IM SNS.

Usage:
  python3 push_all_remarks_to_im.py --dry-run
  python3 push_all_remarks_to_im.py --apply
  python3 push_all_remarks_to_im.py --verify
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
from collections import defaultdict
from pathlib import Path

import push_mutual_friends_to_im as base

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"

EDGES_PATH = STATE / "push_remarks_edges.jsonl"
DRY_SUMMARY = STATE / "push_remarks_dry_run_summary.json"
APPLY_JSONL = STATE / "push_remarks_apply.jsonl"
APPLY_SUMMARY = STATE / "push_remarks_apply_summary.json"
VERIFY_SUMMARY = STATE / "push_remarks_verify_summary.json"

EDGE_SQL = """
SELECT JSON_OBJECT(
  'owner', a.user_id,
  'peer', a.friend_user_id,
  'remark', IFNULL(a.remark,''),
  'mutual', CASE WHEN b.user_id IS NULL THEN 0 ELSE 1 END
)
FROM user_friend a
LEFT JOIN user_friend b
  ON b.user_id = a.friend_user_id AND b.friend_user_id = a.user_id
 AND b.status = 1 AND b.deleted = 0
WHERE a.status = 1 AND a.deleted = 0 AND a.user_id <> a.friend_user_id
  AND a.remark IS NOT NULL AND TRIM(a.remark) <> ''
"""


def scheduled_sync_enabled() -> bool:
    raw = (os.environ.get("USER_FRIEND_SYNC_SCHEDULED") or "false").strip().lower()
    return raw in ("1", "true", "yes", "on")


def load_edges_from_db() -> list[dict]:
    rows = base.mysql_query(EDGE_SQL)
    out: list[dict] = []
    for parts in rows:
        raw = parts[0] if parts else ""
        try:
            obj = json.loads(raw)
        except Exception:
            continue
        owner = str(obj.get("owner") or "").strip()
        peer = str(obj.get("peer") or "").strip()
        if not owner or not peer or owner == peer:
            continue
        remark = base.normalize_remark(obj.get("remark"))
        if not remark:
            continue
        mutual = int(obj.get("mutual") or 0) == 1
        out.append({
            "owner": owner,
            "peer": peer,
            "remark": remark,
            "mutual": mutual,
            "add_type": "Add_Type_Both" if mutual else "Add_Type_Single",
        })
    return out


def pair_key(a: str, b: str) -> str:
    x, y = (a, b) if a < b else (b, a)
    return f"{x}|{y}"


def run_dry_run(max_edges: int | None) -> dict:
    STATE.mkdir(parents=True, exist_ok=True)
    edges = load_edges_from_db()
    if max_edges is not None and max_edges > 0:
        edges = edges[:max_edges]
    mutual_n = sum(1 for e in edges if e["mutual"])
    oneway_n = len(edges) - mutual_n
    both_pairs = {pair_key(e["owner"], e["peer"]) for e in edges if e["mutual"]}
    owners = {e["owner"] for e in edges}
    with EDGES_PATH.open("w", encoding="utf-8") as f:
        for e in edges:
            f.write(json.dumps(e, ensure_ascii=False) + "\n")
    summary = {
        "generated_at": base.utc_now(),
        "nonempty_edge_count": len(edges),
        "mutual_edge_count": mutual_n,
        "oneway_edge_count": oneway_n,
        "both_add_pair_count": len(both_pairs),
        "friend_add_planned": len(both_pairs) + oneway_n,
        "remark_write_planned": len(edges),
        "owner_count": len(owners),
        "max_edges": max_edges,
        "edges_path": str(EDGES_PATH),
    }
    DRY_SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"dry-run nonempty={summary['nonempty_edge_count']} "
        f"mutual={mutual_n} oneway={oneway_n} "
        f"both_pairs={summary['both_add_pair_count']} "
        f"friend_adds={summary['friend_add_planned']} "
        f"remark_writes={summary['remark_write_planned']} "
        f"owners={summary['owner_count']}",
        flush=True,
    )
    print(f"wrote {DRY_SUMMARY}", flush=True)
    print(f"wrote {EDGES_PATH}", flush=True)
    return summary


def load_edges_from_state() -> list[dict]:
    if not EDGES_PATH.exists():
        raise SystemExit(f"missing dry-run product: {EDGES_PATH}; run --dry-run first")
    out: list[dict] = []
    for line in EDGES_PATH.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line:
            continue
        out.append(json.loads(line))
    return out


def add_friend(im: base.ImRest, owner: str, peer: str, add_type: str) -> int:
    body = {
        "From_Account": owner,
        "AddFriendItem": [{
            "To_Account": peer,
            "AddSource": "AddSource_Type_Server",
        }],
        "AddType": add_type,
        "ForceAddFlags": 1,
    }
    resp = im.post("sns/friend_add", body)
    return base.friend_add_result_code(resp, peer)


def run_apply(delay_ms: int, max_edges: int | None, fail_fast: bool) -> dict:
    if not DRY_SUMMARY.exists() or not EDGES_PATH.exists():
        raise SystemExit("refuse apply: run --dry-run first (missing dry-run products)")
    if scheduled_sync_enabled():
        raise SystemExit("refuse apply: USER_FRIEND_SYNC_SCHEDULED is on")
    edges = load_edges_from_state()
    if max_edges is not None and max_edges > 0:
        edges = edges[:max_edges]

    im = base.ImRest()
    started = time.time()
    add_ok = 0
    add_fail = 0
    remark_ok = 0
    remark_fail = 0
    skipped_remark = 0
    both_result: dict[str, bool] = {}

    STATE.mkdir(parents=True, exist_ok=True)
    with APPLY_JSONL.open("w", encoding="utf-8") as out:
        for idx, e in enumerate(edges, 1):
            owner = e["owner"]
            peer = e["peer"]
            remark = e["remark"]
            mutual = bool(e.get("mutual"))
            add_type = e.get("add_type") or ("Add_Type_Both" if mutual else "Add_Type_Single")
            row = {
                "owner": owner,
                "peer": peer,
                "mutual": mutual,
                "add_type": add_type,
                "add_ok": False,
                "add_code": None,
                "remark_ok": None,
                "error": None,
            }
            try:
                friendship_ok = False
                if mutual:
                    key = pair_key(owner, peer)
                    if key in both_result:
                        friendship_ok = both_result[key]
                        row["add_ok"] = friendship_ok
                        row["add_code"] = 0 if friendship_ok else -1
                    else:
                        code = add_friend(im, owner, peer, "Add_Type_Both")
                        base.sleep_ms(delay_ms)
                        friendship_ok = code in (0, 30001)
                        both_result[key] = friendship_ok
                        row["add_ok"] = friendship_ok
                        row["add_code"] = code
                        if friendship_ok:
                            add_ok += 1
                        else:
                            add_fail += 1
                            row["error"] = f"friend_add Both code={code}"
                else:
                    code = add_friend(im, owner, peer, "Add_Type_Single")
                    base.sleep_ms(delay_ms)
                    friendship_ok = code in (0, 30001)
                    row["add_ok"] = friendship_ok
                    row["add_code"] = code
                    if friendship_ok:
                        add_ok += 1
                    else:
                        add_fail += 1
                        row["error"] = f"friend_add Single code={code}"

                if not friendship_ok:
                    skipped_remark += 1
                    out.write(json.dumps(row, ensure_ascii=False) + "\n")
                    print(f"[{idx}/{len(edges)}] FAIL add {owner}->{peer} {row['error']}", flush=True)
                    if fail_fast:
                        break
                    continue

                rc = base.update_remark(im, owner, peer, remark)
                base.sleep_ms(delay_ms)
                row["remark_ok"] = rc == 0
                if rc == 0:
                    remark_ok += 1
                else:
                    remark_fail += 1
                    err = f"remark code={rc}"
                    row["error"] = f"{row['error']}; {err}" if row["error"] else err
                    print(f"[{idx}/{len(edges)}] FAIL remark {owner}->{peer} {err}", flush=True)
                    if fail_fast:
                        out.write(json.dumps(row, ensure_ascii=False) + "\n")
                        break
                if idx % 50 == 0 or idx == len(edges):
                    print(
                        f"[{idx}/{len(edges)}] remark_ok={remark_ok} "
                        f"remark_fail={remark_fail} add_fail={add_fail}",
                        flush=True,
                    )
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
            except Exception as ex:
                row["error"] = str(ex)
                remark_fail += 1
                out.write(json.dumps(row, ensure_ascii=False) + "\n")
                print(f"[{idx}/{len(edges)}] ERROR {owner}->{peer} {ex}", flush=True)
                if fail_fast:
                    break

    summary = {
        "generated_at": base.utc_now(),
        "processed": remark_ok + remark_fail + skipped_remark,
        "planned": len(edges),
        "add_ok": add_ok,
        "add_fail": add_fail,
        "remark_ok": remark_ok,
        "remark_fail": remark_fail,
        "skipped_remark_after_add_fail": skipped_remark,
        "duration_ms": int((time.time() - started) * 1000),
        "delay_ms": delay_ms,
        "rest_base": im.base,
        "sdk_app_id": im.sdk,
    }
    APPLY_SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"apply add_ok={add_ok} add_fail={add_fail} "
        f"remark_ok={remark_ok} remark_fail={remark_fail} "
        f"skipped_remark={skipped_remark} duration_ms={summary['duration_ms']}",
        flush=True,
    )
    print(f"wrote {APPLY_JSONL}", flush=True)
    print(f"wrote {APPLY_SUMMARY}", flush=True)
    return summary


def parse_im_remark(item: dict) -> str:
    for key in ("SnsProfileItem", "ValueItem"):
        for vi in item.get(key) or []:
            if str(vi.get("Tag") or "") == "Tag_SNS_IM_Remark":
                val = vi.get("Value")
                if val is None:
                    return ""
                return str(val).strip()
    return ""


def friend_get_list(im: base.ImRest, owner: str, peers: list[str], delay_ms: int) -> dict[str, dict]:
    out: dict[str, dict] = {}
    batch = 100
    for i in range(0, len(peers), batch):
        chunk = peers[i : i + batch]
        resp = im.post("sns/friend_get_list", {
            "From_Account": owner,
            "To_Account": chunk,
            "TagList": ["Tag_SNS_IM_Remark"],
        })
        top = int(resp.get("ErrorCode") or 0)
        if top != 0:
            for peer in chunk:
                out[peer] = {"ok": False, "error": top, "remark": None, "result_code": top}
            base.sleep_ms(delay_ms)
            continue
        seen: set[str] = set()
        for item in resp.get("InfoItem") or resp.get("FriendList") or []:
            peer = str(item.get("To_Account") or "")
            if not peer:
                continue
            seen.add(peer)
            rc = int(item.get("ResultCode") or 0)
            out[peer] = {
                "ok": rc == 0,
                "error": None if rc == 0 else rc,
                "remark": parse_im_remark(item) if rc == 0 else None,
                "result_code": rc,
            }
        for peer in chunk:
            if peer not in seen:
                out[peer] = {"ok": False, "error": "NO_ITEM", "remark": None, "result_code": None}
        base.sleep_ms(delay_ms)
    return out


def run_verify(delay_ms: int, max_edges: int | None) -> dict:
    edges = load_edges_from_state()
    if max_edges is not None and max_edges > 0:
        edges = edges[:max_edges]
    im = base.ImRest()
    by_owner: dict[str, list[str]] = defaultdict(list)
    expected: dict[tuple[str, str], dict] = {}
    for e in edges:
        by_owner[e["owner"]].append(e["peer"])
        expected[(e["owner"], e["peer"])] = e

    remark_ok = 0
    remark_fail = 0
    not_friend = 0
    samples: list[dict] = []
    owners = sorted(by_owner)
    started = time.time()
    im_map: dict[tuple[str, str], dict] = {}
    for idx, owner in enumerate(owners, 1):
        peers = sorted(set(by_owner[owner]))
        got = friend_get_list(im, owner, peers, delay_ms)
        for peer, info in got.items():
            im_map[(owner, peer)] = info
        if idx % 20 == 0 or idx == len(owners):
            print(f"verify owners [{idx}/{len(owners)}]", flush=True)

    for e in edges:
        owner, peer = e["owner"], e["peer"]
        info = im_map.get((owner, peer), {"ok": False, "error": "MISSING", "remark": None})
        want = e["remark"]
        if not info.get("ok"):
            not_friend += 1
            remark_fail += 1
            bucket = "not_friend"
            got = None
        else:
            got = (info.get("remark") or "").strip() or None
            got_n = got or ""
            if got_n == want:
                remark_ok += 1
                bucket = "ok"
            else:
                remark_fail += 1
                bucket = "mismatch" if got_n else "im_empty"
        if bucket != "ok" and len(samples) < 50:
            samples.append({
                "owner": owner,
                "peer": peer,
                "mutual": bool(e.get("mutual")),
                "local": want,
                "im": got,
                "im_error": info.get("error"),
                "bucket": bucket,
            })

    summary = {
        "generated_at": base.utc_now(),
        "checked_edges": len(edges),
        "remark_ok": remark_ok,
        "remark_fail": remark_fail,
        "not_friend": not_friend,
        "duration_ms": int((time.time() - started) * 1000),
        "samples": samples,
        "sdk_app_id": im.sdk,
        "rest_base": im.base,
    }
    VERIFY_SUMMARY.write_text(json.dumps(summary, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(
        f"verify edges={len(edges)} remark_ok={remark_ok} "
        f"remark_fail={remark_fail} not_friend={not_friend}",
        flush=True,
    )
    print(f"wrote {VERIFY_SUMMARY}", flush=True)
    return summary


def main() -> None:
    base.load_env()
    ap = argparse.ArgumentParser(description="Push all non-empty remarks (mutual + one-way) to IM SNS")
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--delay-ms", type=int, default=50)
    ap.add_argument("--max-edges", type=int, default=0, help="0 = no limit")
    ap.add_argument("--fail-fast", action="store_true")
    args = ap.parse_args()

    if not (args.dry_run or args.apply or args.verify):
        print("specify --dry-run and/or --apply and/or --verify", file=sys.stderr)
        sys.exit(2)

    max_edges = args.max_edges if args.max_edges > 0 else None
    if args.dry_run:
        run_dry_run(max_edges=max_edges)
    if args.apply:
        run_apply(delay_ms=args.delay_ms, max_edges=max_edges, fail_fast=args.fail_fast)
    if args.verify:
        run_verify(delay_ms=args.delay_ms, max_edges=max_edges)


if __name__ == "__main__":
    main()
