#!/usr/bin/env python3
"""Check official/template API floors; run with --write after a framework version bump."""
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
    parser.add_argument("--write", action="store_true", help="refresh checked-in API floors")
    args = parser.parse_args()
    expected = f"[{framework_version(ROOT)},1.0.0)"
    modules = sorted((ROOT / "plugins").iterdir()) + [ROOT / "templates/plugin-template"]
    stale = []
    for module in modules:
        descriptor = module / "src/main/resources/META-INF/turboism/plugin.json"
        if not descriptor.is_file():
            continue
        declared = json.loads(descriptor.read_text(encoding="utf-8"))["turboismApi"]
        if not API_RANGE.fullmatch(declared):
            raise ValueError(f"{descriptor}: expected a half-open SDK API range")
        for path in [descriptor, *sorted(module.glob("README*.md"))]:
            text = path.read_text(encoding="utf-8")
            # Restrict replacements to turboismApi and the displayed SDK API range.
            lines = text.splitlines(keepends=True)
            changed = False
            for index, line in enumerate(lines):
                if "turboismApi" in line or "SDK API" in line or "Turboism API" in line:
                    updated = API_RANGE.sub(expected, line)
                    if updated != line:
                        lines[index] = updated
                        changed = True
            if changed:
                stale.append(str(path.relative_to(ROOT)))
                if args.write:
                    path.write_text("".join(lines), encoding="utf-8")
    if stale and not args.write:
        print("API floors differ from the framework; run python3 scripts/sync_plugin_api.py --write:")
        print("\n".join(stale))
        return 1
    print(f"Plugin SDK API ranges match {expected} ({'updated' if args.write else 'checked'}).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
