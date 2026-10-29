#!/usr/bin/env python3
"""Validate plugin-owned API ranges and synchronize their README declarations."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from turboism_dev.versions import framework_version

ROOT = Path(__file__).resolve().parents[1]
API_RANGE = re.compile(r"\[[0-9]+\.[0-9]+\.[0-9]+,[0-9]+\.[0-9]+\.[0-9]+\)")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true", help="synchronize README ranges to plugin metadata")
    args = parser.parse_args()
    current = tuple(map(int, framework_version(ROOT).split(".")))
    modules = sorted((ROOT / "plugins").iterdir()) + [ROOT / "templates/plugin-template"]
    stale = []
    for module in modules:
        descriptor = module / "src/main/resources/META-INF/turboism/plugin.json"
        if not descriptor.is_file():
            continue
        data = json.loads(descriptor.read_text(encoding="utf-8"))
        declared = data["turboismApi"]
        if not API_RANGE.fullmatch(declared):
            raise ValueError(f"{descriptor}: expected a half-open SDK API range")
        lower, upper = [tuple(map(int, value.split("."))) for value in declared[1:-1].split(",")]
        if not lower <= current < upper:
            raise ValueError(f"{descriptor}: SDK API range {declared} does not include framework {framework_version(ROOT)}")
        for dep in data.get("dependencies", ()):
            dep_range = dep.get("version", "")
            if API_RANGE.fullmatch(dep_range):
                dep_lower, dep_upper = [tuple(map(int, value.split("."))) for value in dep_range[1:-1].split(",")]
                if not dep_lower <= current < dep_upper:
                    raise ValueError(
                        f"{descriptor}: dependency {dep.get('id')} range {dep_range} does not include framework {framework_version(ROOT)}"
                    )
        for path in sorted(module.glob("README*.md")):
            text = path.read_text(encoding="utf-8")
            # Restrict replacements to turboismApi and the displayed SDK API range.
            lines = text.splitlines(keepends=True)
            changed = False
            for index, line in enumerate(lines):
                if "turboismApi" in line or "SDK API" in line or "Turboism API" in line:
                    updated = API_RANGE.sub(declared, line)
                    if updated != line:
                        lines[index] = updated
                        changed = True
            if changed:
                stale.append(str(path.relative_to(ROOT)))
                if args.write:
                    path.write_text("".join(lines), encoding="utf-8")
    if stale and not args.write:
        print("README API ranges differ from plugin metadata; run python3 scripts/sync_plugin_api.py --write:")
        print("\n".join(stale))
        return 1
    print(f"Plugin SDK API ranges are valid; READMEs {'updated' if args.write else 'checked'} against plugin metadata.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
