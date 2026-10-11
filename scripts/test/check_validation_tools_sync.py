#!/usr/bin/env python3
"""Pin the validation probe toolbox to its single sources so copies cannot fork again.

The probe agents under validation/ compile a small shared toolbox at build time:
the jar relocator, the child-first fixture class loader, and the directory
code-source printer. These files used to live as near-identical per-probe copies
that drifted apart (four diverged RelocateJar variants). Every copy now compiles
from validation/shared/src/, so this check enforces two invariants:

- each canonical file exists at its shared path, and
- no extra file with the same basename or declaring the same class exists
  anywhere else under validation/ (a copy would re-fork the toolbox).

Deliberate exceptions live in EXCEPTIONS: files that share a basename but are a
different implementation by design (documented inline).

Usage: check_validation_tools_sync.py [repo-root] [--report]
"""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = "validation"
SHARED_SRC = "validation/shared/src"

# basename -> every path allowed to carry it, relative to the repo root.
CANONICAL = {
    "RelocateJar.java": (
        "validation/shared/src/dev/turboism/validation/shared/tools/RelocateJar.java",
    ),
    "FixtureLoader.java": (
        "validation/shared/src/dev/turboism/validation/shared/fixture/FixtureLoader.java",
        # meshhash keeps its own package-private loader by design: it defines
        # fixture classes from a byte map (patched bytes), which a URLClassLoader
        # cannot do.
        "validation/mesh-triangulation-hash/src/dev/turboism/validation/meshhash/FixtureLoader.java",
    ),
    "CodeSourceUrl.java": (
        "validation/shared/src/dev/turboism/validation/shared/fixture/CodeSourceUrl.java",
    ),
}

# Files allowed to declare a same-named class because theirs is a different
# mechanism by design (private byte-array loaders inside offline harnesses).
DECLARED_EXCEPTIONS = {
    "FixtureLoader": (
        "validation/atlas-image-kernel-probe/src/dev/turboism/validation/atlasimage/t033/T033OfflineHarness.java",
        "validation/atlas-image-kernel-probe/src/dev/turboism/validation/atlasimage/t035/T035OfflineHarness.java",
    ),
}

# A copy renamed to a different basename would still declare the same class.
CLASS_RE = re.compile(r"\b(?:final\s+)?class\s+(RelocateJar|FixtureLoader|CodeSourceUrl)\b")


def findings(root: Path):
    violations = []
    base = root / ROOT
    if not base.is_dir():
        return [f"{ROOT}: directory not found"]

    for basename, allowed in CANONICAL.items():
        canonical = allowed[0]
        if not (root / canonical).is_file():
            violations.append(f"{canonical}: canonical shared source is missing")
        found = sorted(
            p.relative_to(root).as_posix()
            for p in base.rglob(basename)
            if p.is_file()
        )
        extra = [p for p in found if p not in allowed]
        for path in extra:
            violations.append(
                f"{path}: duplicate {basename} outside the allowed set; "
                f"keep the single source at {canonical}"
            )
        for path in allowed[1:]:
            if not (root / path).is_file():
                violations.append(f"{path}: documented exception file is missing")

    for path in sorted(base.rglob("*.java")):
        relative = path.relative_to(root).as_posix()
        for match in CLASS_RE.finditer(path.read_text(encoding="utf-8")):
            declared = match.group(1) + ".java"
            allowed = CANONICAL.get(declared, ()) + DECLARED_EXCEPTIONS.get(match.group(1), ())
            if relative not in allowed:
                violations.append(
                    f"{relative}: declares class {match.group(1)} outside the allowed set; "
                    f"the single source lives at {CANONICAL[declared][0]}"
                )
    return violations


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("root", nargs="?", default=".")
    parser.add_argument(
        "--report",
        action="store_true",
        help="print findings and exit 0",
    )
    args = parser.parse_args()

    violations = findings(Path(args.root).resolve())
    for violation in violations:
        print(violation)
    if violations and not args.report:
        print(
            f"FAIL: {len(violations)} validation-toolbox sync violation(s); "
            "probe tool sources must live once under validation/shared/src/",
            file=sys.stderr,
        )
        return 1
    print(f"validation-tools-sync: {len(violations)} finding(s)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
