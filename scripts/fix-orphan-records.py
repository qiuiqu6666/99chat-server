#!/usr/bin/env python3
"""Move top-level `public record Outer.Inner` blocks back inside their enclosing class."""

import re
from pathlib import Path

ROOT = Path("/www/wwwroot/99chat-server/src/main/java")


def fix_file(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    m = re.search(r"^public (?:class|interface|enum) (\w+)", text, re.M)
    if not m:
        return False
    outer = m.group(1)
    pattern = re.compile(
        rf"^public record {re.escape(outer)}\.(\w+)\((.*?)\) \{{\n}}\n?",
        re.M | re.S,
    )
    records = pattern.findall(text)
    if not records:
        return False
    cleaned = pattern.sub("", text)
    nested = []
    for name, args in records:
        nested.append(f"    public record {name}({args}) {{}}")
    insert = "\n\n" + "\n\n".join(nested) + "\n"
    if not cleaned.rstrip().endswith("}"):
        return False
    fixed = cleaned.rstrip()[:-1] + insert + "}\n"
    path.write_text(fixed, encoding="utf-8")
    print(f"fixed {path} (+{len(nested)} records)")
    return True


def main() -> None:
    count = 0
    for path in ROOT.rglob("*.java"):
        if fix_file(path):
            count += 1
    print(f"done: {count} files")


if __name__ == "__main__":
    main()
