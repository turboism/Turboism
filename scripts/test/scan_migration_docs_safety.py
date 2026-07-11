#!/usr/bin/env python3
"""Reject unsafe migration-document clauses and embedded implementation bodies."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

PROHIBITED = tuple(
    re.compile(pattern, re.IGNORECASE)
    for pattern in (
        r"license\s*bypass",
        r"trial\s*bypass",
        r"authorization\s*bypass",
        r"authentication\s*bypass",
        r"crack",
        r"serial\s*key",
        r"keygen",
        r"remove\s*watermark",
        r"disable\s*license",
        r"绕过\s*授权",
        r"绕过\s*许可",
        r"反编译\s*方法体",
    )
)
# A prohibited phrase is allowed only when the current structure ends in one of
# these explicit, direct-denial forms.  This is intentionally a whitelist:
# unrelated negation, an unknown verb, or a scope-breaking coordinator fails
# closed rather than inheriting denial from earlier text.
ENGLISH_DIRECT_DENIAL = re.compile(
    r"(?:^|\s)(?:no|not|forbidden|prohibited|never|must\s+not|do\s+not)"
    r"(?:\s+(?:implement|enable|perform|copy|use|include|allow|support|attempt|"
    r"describe|provide|create|remove|disable|bypass|crack)){0,3}\s*$",
    re.IGNORECASE,
)
CHINESE_DIRECT_DENIAL = re.compile(
    r"(?:^|\s)(?:也)?(?:不得|禁止|严禁|不可|不允许|不应|不能|避免|拒绝|排除)"
    r"(?:复制|实现|进行|提供|支持|允许|尝试|描述|移除|禁用|绕过|包含|使用){0,3}\s*$"
)
SCOPE_BOUNDARY = re.compile(
    r"(?:[，。；;,.!?！？]+|\b(?:but|however|yet|while|when|and|then|whereas|although|though|instead)\b|"
    r"(?:但是|但|然而|不过|却|然后|而是|尽管|同时|并|且|随后))",
    re.IGNORECASE,
)
JAVA_INDICATORS = (
    "public void ", "private void ", "public static ", "private static ",
    "try {", "catch (", "for (", "while (", "if (", "return ",
    "import java.", "import com.live2d", "import dev.turboism",
)


def scan(root: Path) -> list[str]:
    errors: list[str] = []
    for path in sorted(root.rglob("*")):
        if not path.is_file() or path.suffix not in {".md", ".tsv"}:
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        for line_number, line in enumerate(text.splitlines(), start=1):
            for pattern in PROHIBITED:
                for match in pattern.finditer(line):
                    clause_start = 0
                    for boundary in SCOPE_BOUNDARY.finditer(line, 0, match.start()):
                        clause_start = boundary.end()
                    local_prefix = line[clause_start:match.start()]
                    if ENGLISH_DIRECT_DENIAL.search(local_prefix) or CHINESE_DIRECT_DENIAL.search(local_prefix):
                        continue
                    errors.append(f"prohibited keyword in {path}:{line_number}: {match.group(0)}")
        count = sum(text.count(indicator) for indicator in JAVA_INDICATORS)
        if count > 50:
            errors.append(f"{path} contains too many Java body indicators: {count}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("root", type=Path)
    args = parser.parse_args()
    if not args.root.is_dir():
        print(f"FAIL: migration docs directory missing: {args.root}", file=sys.stderr)
        return 1
    errors = scan(args.root)
    for error in errors:
        print(f"FAIL: {error}", file=sys.stderr)
    return 1 if errors else 0


if __name__ == "__main__":
    raise SystemExit(main())
