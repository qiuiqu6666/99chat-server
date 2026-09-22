#!/usr/bin/env python3
"""Import city/provider catalog into life_payment_providers.

Usage:
  python3 scripts/import-life-payment-providers.py \\
    --input scripts/data/life-payment-providers-raw.txt \\
    --sql scripts/seed-life-payment-providers.sql \\
    [--mysql]   # also apply to local DB (chat99/chat99@127.0.0.1)

Raw format: city + TAB/spaces + provider name.
Sections separated by blank lines: electric → water → gas.
Rows in the electric section that are not power companies are treated as gas
(e.g. Xi'an gas providers listed before the water section).
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

CITY_PROVINCE = {
    "北京": "北京市",
    "上海": "上海市",
    "天津": "天津市",
    "重庆": "重庆市",
    "石家庄": "河北省",
    "唐山": "河北省",
    "秦皇岛": "河北省",
    "邯郸": "河北省",
    "邢台": "河北省",
    "保定": "河北省",
    "张家口": "河北省",
    "承德": "河北省",
    "沧州": "河北省",
    "廊坊": "河北省",
    "衡水": "河北省",
    "太原": "山西省",
    "大同": "山西省",
    "阳泉": "山西省",
    "长治": "山西省",
    "晋城": "山西省",
    "朔州": "山西省",
    "晋中": "山西省",
    "运城": "山西省",
    "忻州": "山西省",
    "临汾": "山西省",
    "吕梁": "山西省",
    "呼和浩特": "内蒙古自治区",
    "包头": "内蒙古自治区",
    "乌海": "内蒙古自治区",
    "赤峰": "内蒙古自治区",
    "通辽": "内蒙古自治区",
    "鄂尔多斯": "内蒙古自治区",
    "呼伦贝尔": "内蒙古自治区",
    "巴彦淖尔": "内蒙古自治区",
    "乌兰察布": "内蒙古自治区",
    "沈阳": "辽宁省",
    "大连": "辽宁省",
    "鞍山": "辽宁省",
    "抚顺": "辽宁省",
    "本溪": "辽宁省",
    "丹东": "辽宁省",
    "锦州": "辽宁省",
    "营口": "辽宁省",
    "阜新": "辽宁省",
    "辽阳": "辽宁省",
    "盘锦": "辽宁省",
    "铁岭": "辽宁省",
    "朝阳": "辽宁省",
    "葫芦岛": "辽宁省",
    "长春": "吉林省",
    "吉林": "吉林省",
    "四平": "吉林省",
    "辽源": "吉林省",
    "通化": "吉林省",
    "白山": "吉林省",
    "松原": "吉林省",
    "白城": "吉林省",
    "哈尔滨": "黑龙江省",
    "齐齐哈尔": "黑龙江省",
    "鸡西": "黑龙江省",
    "鹤岗": "黑龙江省",
    "双鸭山": "黑龙江省",
    "大庆": "黑龙江省",
    "伊春": "黑龙江省",
    "佳木斯": "黑龙江省",
    "七台河": "黑龙江省",
    "牡丹江": "黑龙江省",
    "黑河": "黑龙江省",
    "绥化": "黑龙江省",
    "南京": "江苏省",
    "无锡": "江苏省",
    "徐州": "江苏省",
    "常州": "江苏省",
    "苏州": "江苏省",
    "南通": "江苏省",
    "连云港": "江苏省",
    "淮安": "江苏省",
    "盐城": "江苏省",
    "扬州": "江苏省",
    "镇江": "江苏省",
    "泰州": "江苏省",
    "宿迁": "江苏省",
    "杭州": "浙江省",
    "宁波": "浙江省",
    "温州": "浙江省",
    "嘉兴": "浙江省",
    "湖州": "浙江省",
    "绍兴": "浙江省",
    "金华": "浙江省",
    "衢州": "浙江省",
    "舟山": "浙江省",
    "台州": "浙江省",
    "丽水": "浙江省",
    "合肥": "安徽省",
    "芜湖": "安徽省",
    "蚌埠": "安徽省",
    "淮南": "安徽省",
    "马鞍山": "安徽省",
    "淮北": "安徽省",
    "铜陵": "安徽省",
    "安庆": "安徽省",
    "黄山": "安徽省",
    "滁州": "安徽省",
    "阜阳": "安徽省",
    "宿州": "安徽省",
    "六安": "安徽省",
    "亳州": "安徽省",
    "池州": "安徽省",
    "宣城": "安徽省",
    "福州": "福建省",
    "厦门": "福建省",
    "莆田": "福建省",
    "三明": "福建省",
    "泉州": "福建省",
    "漳州": "福建省",
    "南平": "福建省",
    "龙岩": "福建省",
    "宁德": "福建省",
    "南昌": "江西省",
    "景德镇": "江西省",
    "萍乡": "江西省",
    "九江": "江西省",
    "新余": "江西省",
    "鹰潭": "江西省",
    "赣州": "江西省",
    "吉安": "江西省",
    "宜春": "江西省",
    "抚州": "江西省",
    "上饶": "江西省",
    "济南": "山东省",
    "青岛": "山东省",
    "淄博": "山东省",
    "枣庄": "山东省",
    "东营": "山东省",
    "烟台": "山东省",
    "潍坊": "山东省",
    "济宁": "山东省",
    "泰安": "山东省",
    "威海": "山东省",
    "日照": "山东省",
    "临沂": "山东省",
    "德州": "山东省",
    "聊城": "山东省",
    "滨州": "山东省",
    "菏泽": "山东省",
    "郑州": "河南省",
    "开封": "河南省",
    "洛阳": "河南省",
    "平顶山": "河南省",
    "安阳": "河南省",
    "鹤壁": "河南省",
    "新乡": "河南省",
    "焦作": "河南省",
    "濮阳": "河南省",
    "许昌": "河南省",
    "漯河": "河南省",
    "三门峡": "河南省",
    "南阳": "河南省",
    "商丘": "河南省",
    "信阳": "河南省",
    "周口": "河南省",
    "驻马店": "河南省",
    "武汉": "湖北省",
    "黄石": "湖北省",
    "十堰": "湖北省",
    "宜昌": "湖北省",
    "襄阳": "湖北省",
    "鄂州": "湖北省",
    "荆门": "湖北省",
    "孝感": "湖北省",
    "荆州": "湖北省",
    "黄冈": "湖北省",
    "咸宁": "湖北省",
    "随州": "湖北省",
    "长沙": "湖南省",
    "株洲": "湖南省",
    "湘潭": "湖南省",
    "衡阳": "湖南省",
    "邵阳": "湖南省",
    "岳阳": "湖南省",
    "常德": "湖南省",
    "张家界": "湖南省",
    "益阳": "湖南省",
    "郴州": "湖南省",
    "永州": "湖南省",
    "怀化": "湖南省",
    "娄底": "湖南省",
    "广州": "广东省",
    "韶关": "广东省",
    "深圳": "广东省",
    "珠海": "广东省",
    "汕头": "广东省",
    "佛山": "广东省",
    "江门": "广东省",
    "湛江": "广东省",
    "茂名": "广东省",
    "肇庆": "广东省",
    "惠州": "广东省",
    "梅州": "广东省",
    "汕尾": "广东省",
    "河源": "广东省",
    "阳江": "广东省",
    "清远": "广东省",
    "东莞": "广东省",
    "中山": "广东省",
    "潮州": "广东省",
    "揭阳": "广东省",
    "云浮": "广东省",
    "南宁": "广西壮族自治区",
    "柳州": "广西壮族自治区",
    "桂林": "广西壮族自治区",
    "梧州": "广西壮族自治区",
    "北海": "广西壮族自治区",
    "防城港": "广西壮族自治区",
    "钦州": "广西壮族自治区",
    "贵港": "广西壮族自治区",
    "玉林": "广西壮族自治区",
    "百色": "广西壮族自治区",
    "贺州": "广西壮族自治区",
    "河池": "广西壮族自治区",
    "来宾": "广西壮族自治区",
    "崇左": "广西壮族自治区",
    "海口": "海南省",
    "三亚": "海南省",
    "儋州": "海南省",
    "成都": "四川省",
    "自贡": "四川省",
    "攀枝花": "四川省",
    "泸州": "四川省",
    "德阳": "四川省",
    "绵阳": "四川省",
    "广元": "四川省",
    "遂宁": "四川省",
    "内江": "四川省",
    "乐山": "四川省",
    "南充": "四川省",
    "眉山": "四川省",
    "宜宾": "四川省",
    "广安": "四川省",
    "达州": "四川省",
    "雅安": "四川省",
    "巴中": "四川省",
    "资阳": "四川省",
    "贵阳": "贵州省",
    "六盘水": "贵州省",
    "遵义": "贵州省",
    "安顺": "贵州省",
    "毕节": "贵州省",
    "铜仁": "贵州省",
    "昆明": "云南省",
    "曲靖": "云南省",
    "玉溪": "云南省",
    "保山": "云南省",
    "昭通": "云南省",
    "丽江": "云南省",
    "普洱": "云南省",
    "临沧": "云南省",
    "拉萨": "西藏自治区",
    "日喀则": "西藏自治区",
    "昌都": "西藏自治区",
    "林芝": "西藏自治区",
    "山南": "西藏自治区",
    "那曲": "西藏自治区",
    "西安": "陕西省",
    "铜川": "陕西省",
    "宝鸡": "陕西省",
    "咸阳": "陕西省",
    "渭南": "陕西省",
    "延安": "陕西省",
    "汉中": "陕西省",
    "榆林": "陕西省",
    "安康": "陕西省",
    "商洛": "陕西省",
    "兰州": "甘肃省",
    "嘉峪关": "甘肃省",
    "金昌": "甘肃省",
    "白银": "甘肃省",
    "天水": "甘肃省",
    "武威": "甘肃省",
    "张掖": "甘肃省",
    "平凉": "甘肃省",
    "酒泉": "甘肃省",
    "庆阳": "甘肃省",
    "定西": "甘肃省",
    "陇南": "甘肃省",
    "西宁": "青海省",
    "海东": "青海省",
    "银川": "宁夏回族自治区",
    "石嘴山": "宁夏回族自治区",
    "吴忠": "宁夏回族自治区",
    "固原": "宁夏回族自治区",
    "中卫": "宁夏回族自治区",
    "乌鲁木齐": "新疆维吾尔自治区",
    "克拉玛依": "新疆维吾尔自治区",
    "吐鲁番": "新疆维吾尔自治区",
    "哈密": "新疆维吾尔自治区",
}

SOURCE = "alipay_catalog_manual"


def parse_line(line: str):
    s = line.strip()
    if not s or s.startswith("<"):
        return None
    if "\t" in s:
        a, b = s.split("\t", 1)
    else:
        parts = re.split(r"\s+", s, maxsplit=1)
        if len(parts) < 2:
            return None
        a, b = parts
    city, name = a.strip(), b.strip()
    if not city or not name:
        return None
    return city, name


def is_electric(name: str) -> bool:
    return any(k in name for k in ("电力", "电网", "供电", "国网", "南网", "内蒙古电力"))


def is_gas(name: str) -> bool:
    return any(k in name for k in ("燃气", "天然气", "煤气", "加气"))


def is_water(name: str) -> bool:
    return any(k in name for k in ("供水", "自来水", "水务", "水费", "水厂"))


def slug_ascii(value: str) -> str:
    return re.sub(r"[^a-z0-9]+", "", (value or "").strip().lower())


def java_hex_hash(value: str) -> str:
    h = 0
    for ch in (value or "").strip():
        h = 31 * h + ord(ch)
        h = ((h + 2**31) % 2**32) - 2**31
    return format(h & 0xFFFFFFFF, "08x")


def provider_code_of(service_type: str, city_code: str, city_name: str, provider_name: str) -> str:
    city_key = (city_code or "").strip() or (city_name or "").strip()
    city_part = slug_ascii(city_key) or java_hex_hash(city_key)
    if len(city_part) > 16:
        city_part = city_part[:16]
    name_key = (provider_name or "").strip()
    name_part = slug_ascii(name_key) or java_hex_hash(name_key)
    if len(name_part) > 24:
        name_part = name_part[:24]
    cleaned = f"{service_type}_{city_part}_{name_part}".lower()
    return cleaned[:60] if cleaned else "provider_x"


def esc(s: str) -> str:
    return (s or "").replace("\\", "\\\\").replace("'", "''")


def parse_catalog(lines: list[str]) -> list[tuple[str, str, str]]:
    items: list[tuple[str, str, str]] = []
    section = "electric"
    blank_seen = 0
    for line in lines:
        if not line.strip():
            blank_seen += 1
            if blank_seen == 1:
                section = "water"
            elif blank_seen == 2:
                section = "gas"
            continue
        parsed = parse_line(line)
        if not parsed:
            continue
        city, name = parsed
        st = section
        if section == "electric" and not is_electric(name):
            st = "gas"
        elif section == "water" and is_gas(name) and not is_water(name):
            st = "gas"
        elif section == "gas" and is_water(name) and not is_gas(name):
            st = "water"
        items.append((st, city, name))

    seen: set[tuple[str, str, str]] = set()
    unique: list[tuple[str, str, str]] = []
    for key in items:
        if key in seen:
            continue
        seen.add(key)
        unique.append(key)
    return unique


def build_rows(unique: list[tuple[str, str, str]]):
    codes: dict[str, tuple[str, str, str]] = {}
    ordered: list[tuple[str, str, str, str]] = []
    collisions = 0
    for st, city, name in unique:
        code = provider_code_of(st, "", city, name)
        if code in codes:
            collisions += 1
            n = 2
            while f"{code[:56]}_{n}" in codes:
                n += 1
            code = f"{code[:56]}_{n}"
        codes[code] = (st, city, name)
        ordered.append((code, st, city, name))
    return ordered, collisions


def write_sql(path: Path, ordered: list[tuple[str, str, str, str]]) -> None:
    now = datetime.now(timezone.utc).strftime("%Y-%m-%d %H:%M:%S.000000")
    rows = []
    for code, st, city, name in ordered:
        prov = CITY_PROVINCE.get(city, "")
        rows.append(
            f"('{esc(st)}','CN','{esc(prov)}','{esc(city)}','','{esc(name)}',"
            f"'{esc(code)}',NULL,'{SOURCE}',1,'{now}','{now}','{now}')"
        )
    parts = [
        "-- Auto-generated life payment providers seed (electric/water/gas)",
        f"-- source: {SOURCE}",
        "SET NAMES utf8mb4;",
        "",
        "CREATE TABLE IF NOT EXISTS life_payment_providers (",
        "  id BIGINT NOT NULL AUTO_INCREMENT,",
        "  service_type VARCHAR(20) NOT NULL,",
        "  country_code VARCHAR(8) NOT NULL DEFAULT 'CN',",
        "  province_name VARCHAR(64) NULL,",
        "  city_name VARCHAR(64) NOT NULL,",
        "  city_code VARCHAR(32) NULL,",
        "  provider_name VARCHAR(128) NOT NULL,",
        "  provider_code VARCHAR(64) NOT NULL,",
        "  provider_alias VARCHAR(128) NULL,",
        "  source VARCHAR(64) NULL,",
        "  enabled TINYINT(1) NOT NULL DEFAULT 1,",
        "  last_captured_at DATETIME(6) NULL,",
        "  created_at DATETIME(6) NOT NULL,",
        "  updated_at DATETIME(6) NOT NULL,",
        "  PRIMARY KEY (id),",
        "  UNIQUE KEY uk_lp_provider_code (provider_code),",
        "  UNIQUE KEY uk_lp_provider_city_name (service_type, city_name, provider_name),",
        "  KEY idx_lp_provider_city (service_type, city_name, enabled)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;",
        "",
        f"DELETE FROM life_payment_providers WHERE source='{SOURCE}';",
        "",
    ]
    batch = 200
    for i in range(0, len(rows), batch):
        chunk = rows[i : i + batch]
        parts.append(
            "INSERT INTO life_payment_providers "
            "(service_type, country_code, province_name, city_name, city_code, provider_name, "
            "provider_code, provider_alias, source, enabled, last_captured_at, created_at, updated_at) VALUES\n"
            + ",\n".join(chunk)
            + "\nON DUPLICATE KEY UPDATE service_type=VALUES(service_type), province_name=VALUES(province_name), "
            "provider_code=VALUES(provider_code), source=VALUES(source), enabled=1, "
            "last_captured_at=VALUES(last_captured_at), updated_at=VALUES(updated_at);"
        )
        parts.append("")
    path.write_text("\n".join(parts), encoding="utf-8")


def main() -> None:
    root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--input",
        type=Path,
        default=root / "scripts/data/life-payment-providers-raw.txt",
    )
    parser.add_argument(
        "--sql",
        type=Path,
        default=root / "scripts/seed-life-payment-providers.sql",
    )
    parser.add_argument(
        "--json",
        type=Path,
        default=root / "scripts/data/life-payment-providers.json",
    )
    parser.add_argument("--mysql", action="store_true", help="Apply SQL to local MySQL")
    parser.add_argument("--db-host", default="127.0.0.1")
    parser.add_argument("--db-port", default="3306")
    parser.add_argument("--db-user", default="chat99")
    parser.add_argument("--db-pass", default="chat99")
    parser.add_argument("--db-name", default="chat99")
    args = parser.parse_args()

    lines = args.input.read_text(encoding="utf-8").splitlines()
    unique = parse_catalog(lines)
    missing = sorted({c for _, c, _ in unique if c not in CITY_PROVINCE})
    if missing:
        raise SystemExit(f"missing province mapping for cities: {missing}")
    ordered, collisions = build_rows(unique)
    write_sql(args.sql, ordered)
    args.json.write_text(
        json.dumps(
            [
                {
                    "service_type": st,
                    "province_name": CITY_PROVINCE.get(city, ""),
                    "city_name": city,
                    "city_code": "",
                    "provider_name": name,
                    "provider_code": code,
                    "source": SOURCE,
                    "enabled": True,
                }
                for code, st, city, name in ordered
            ],
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )
    counts = Counter(st for _, st, _, _ in ordered)
    print(f"providers={len(ordered)} counts={dict(counts)} collisions={collisions}")
    print(f"wrote {args.sql}")
    print(f"wrote {args.json}")
    if args.mysql:
        cmd = [
            "mysql",
            f"-h{args.db_host}",
            f"-P{args.db_port}",
            f"-u{args.db_user}",
            f"-p{args.db_pass}",
            args.db_name,
        ]
        with args.sql.open("rb") as f:
            subprocess.run(cmd, stdin=f, check=True)
        print("mysql import done")


if __name__ == "__main__":
    main()
