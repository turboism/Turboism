#!/usr/bin/env python3
"""Extract one version section from CHANGELOG.md for GitHub Releases."""
from __future__ import annotations

import argparse
import re
from pathlib import Path


HEADING = re.compile(r"^## \[([^]]+)](?: - .+)?$")


def extract(text: str, version: str) -> str:
    lines = text.splitlines()
    start = None
    end = len(lines)
    for index, line in enumerate(lines):
        match = HEADING.match(line)
        if not match:
            continue
        if start is None and match.group(1) == version:
            start = index + 1
            continue
        if start is not None:
            end = index
            break
    if start is None:
        raise ValueError(f"changelog has no section for {version}")
    notes = "\n".join(lines[start:end]).strip()
    if not notes:
        raise ValueError(f"changelog section for {version} is empty")
    return notes + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("changelog", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    print(extract(args.changelog.read_text(encoding="utf-8"), args.version), end="")


if __name__ == "__main__":
    main()
