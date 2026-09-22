#!/usr/bin/env python3
"""Delete China IM numeric UserIDs in [from,to] via account_check / account_delete."""
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
from pathlib import Path

ROOT = Path(__file__).resolve().parent
STATE = ROOT / "state"
DEFAULT_FROM = 100000
DEFAULT_TO = 100628  # inclusive


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


def chunks(xs: list[str], n: int):
    for i in range(0, len(xs), n):
        yield xs[i : i + n]


def account_check(im: ImRest, ids: list[str]) -> dict:
    imported: list[str] = []
    not_imported: list[str] = []
    other: list[dict] = []
    for batch in chunks(ids, 100):
        items = [{"UserID": u} for u in batch]
        raw = im.post("im_open_login_svc/account_check", {"CheckItem": items})
        if int(raw.get("ErrorCode") or 0) != 0:
            other.append({"batch": batch[:3], "resp": raw})
            time.sleep(0.05)
            continue
        for row in raw.get("ResultItem") or []:
            uid = str(row.get("UserID") or "")
            status = str(row.get("AccountStatus") or "").strip()
            if status == "Imported":
                imported.append(uid)
            else:
                # NotImported or unknown → treat as not imported
                not_imported.append(uid)
        time.sleep(0.05)
    return {
        "imported": imported,
        "not_imported": not_imported,
        "imported_count": len(imported),
        "not_imported_count": len(not_imported),
        "errors": other,
        "imported_sample": imported[:20],
        "not_imported_sample": not_imported[:20],
    }


def account_delete(im: ImRest, ids: list[str], log_path: Path) -> dict:
    ok = fail = 0
    fails: list[str] = []
    with log_path.open("w", encoding="utf-8") as logf:
        for batch in chunks(ids, 100):
            items = [{"UserID": u} for u in batch]
            raw = None
            for attempt in range(3):
                raw = im.post("im_open_login_svc/account_delete", {"DeleteItem": items})
                if int(raw.get("ErrorCode") or 0) == 0:
                    break
                time.sleep(0.2 * (attempt + 1))
            err = int(raw.get("ErrorCode") or 0) if raw else -1
            row = {"batch_size": len(batch), "first": batch[0], "last": batch[-1], "ErrorCode": err, "raw": raw}
            # Per-item results if present
            result_items = (raw or {}).get("ResultItem") or []
            batch_fail = []
            if err != 0 and not result_items:
                fail += len(batch)
                fails.extend(batch)
                batch_fail = batch
            else:
                if result_items:
                    for it in result_items:
                        uid = str(it.get("UserID") or "")
                        rc = int(it.get("ResultCode") or 0)
                        # 0 = deleted; 70107 = already not imported → cleanup success
                        if rc == 0 or rc == 70107:
                            ok += 1
                        else:
                            fail += 1
                            fails.append(uid)
                            batch_fail.append(uid)
                else:
                    ok += len(batch)
            row["batch_fail"] = batch_fail
            logf.write(json.dumps(row, ensure_ascii=False) + "\n")
            logf.flush()
            print(f"delete batch {batch[0]}..{batch[-1]} err={err} fail_items={len(batch_fail)}", flush=True)
            time.sleep(0.08)
    return {"ok": ok, "fail": fail, "fail_ids": fails}


def main() -> int:
    load_env()
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--apply", action="store_true")
    ap.add_argument("--verify", action="store_true")
    ap.add_argument("--from", dest="from_id", type=int, default=DEFAULT_FROM)
    ap.add_argument("--to", dest="to_id", type=int, default=DEFAULT_TO)
    ap.add_argument("--check-business", nargs="*", default=None,
                    help="extra non-numeric userIds to confirm still imported")
    args = ap.parse_args()
    if not (args.dry_run or args.apply or args.verify):
        print("specify --dry-run and/or --apply and/or --verify", flush=True)
        return 2
    if args.from_id > args.to_id:
        print("invalid range", flush=True)
        return 2

    sdk = os.environ.get("DST_IM_SDK_APP_ID", "")
    base = os.environ.get("DST_IM_REST_BASE", "")
    print(f"sdkAppId={sdk} restBase={base} range={args.from_id}..{args.to_id}", flush=True)
    if sdk != "1600155864" or "console.tim.qq.com" not in base:
        print("refuse: not China DST IM endpoint", flush=True)
        return 3

    ids = [str(i) for i in range(args.from_id, args.to_id + 1)]
    print(f"count={len(ids)}", flush=True)
    im = ImRest()
    STATE.mkdir(parents=True, exist_ok=True)
    tag = f"{args.from_id}_{args.to_id}"

    if args.dry_run:
        report = account_check(im, ids)
        out = {
            "sdkAppId": sdk,
            "restBase": base,
            "from": args.from_id,
            "to": args.to_id,
            "total": len(ids),
            "imported_count": report["imported_count"],
            "not_imported_count": report["not_imported_count"],
            "imported_sample": report["imported_sample"],
            "not_imported_sample": report["not_imported_sample"],
            "errors": report["errors"][:5],
        }
        path = STATE / f"delete_numeric_{tag}_dry_run.json"
        path.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps({k: out[k] for k in out if k != "errors"}, ensure_ascii=False), flush=True)
        print(f"wrote {path}", flush=True)

    if args.apply:
        log_path = STATE / f"delete_numeric_{tag}_apply.jsonl"
        summary = account_delete(im, ids, log_path)
        fail_path = STATE / f"delete_numeric_{tag}_fail.txt"
        fail_path.write_text("\n".join(summary["fail_ids"]) + ("\n" if summary["fail_ids"] else ""), encoding="utf-8")
        summ_path = STATE / f"delete_numeric_{tag}_apply_summary.json"
        summ = {
            "total": len(ids),
            "ok": summary["ok"],
            "fail": summary["fail"],
            "fail_count": len(summary["fail_ids"]),
        }
        summ_path.write_text(json.dumps(summ, indent=2) + "\n", encoding="utf-8")
        print(json.dumps(summ, ensure_ascii=False), flush=True)

    if args.verify:
        report = account_check(im, ids)
        biz = args.check_business or []
        biz_report = account_check(im, biz) if biz else None
        # sample endpoints
        sample_ids = sorted(set([str(args.from_id), str(args.to_id), str((args.from_id + args.to_id) // 2)]
                                + random.sample(ids, min(20, len(ids)))))
        sample = account_check(im, sample_ids)
        verdict = {
            "total": len(ids),
            "still_imported_count": report["imported_count"],
            "not_imported_count": report["not_imported_count"],
            "still_imported_sample": report["imported_sample"],
            "sample_check": {
                "ids": sample_ids[:23],
                "imported_count": sample["imported_count"],
                "not_imported_count": sample["not_imported_count"],
            },
            "business_check": None if biz_report is None else {
                "ids": biz,
                "imported_count": biz_report["imported_count"],
                "imported": biz_report["imported"],
                "not_imported": biz_report["not_imported"],
            },
        }
        path = STATE / f"delete_numeric_{tag}_verify.json"
        path.write_text(json.dumps(verdict, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(json.dumps(verdict, ensure_ascii=False), flush=True)
        print(f"wrote {path}", flush=True)

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
