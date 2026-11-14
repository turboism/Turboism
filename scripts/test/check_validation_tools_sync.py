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

Status tokens are typed (ProbeTerminal), so this check reads no status literals: a
mistyped token is a compile error. What it still pins is the single publication path —
a probe that appends the counter block, the record block or the terminal line itself
could reorder the tail the Runner and the evidence gate read.

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
    "ProbeTerminal.java": (
        "validation/shared/src/dev/turboism/validation/shared/verdict/ProbeTerminal.java",
    ),
    "ProbeAssertions.java": (
        "validation/shared/src/dev/turboism/validation/shared/verdict/ProbeAssertions.java",
    ),
}

# Host probes that publish a terminal verdict. They must call the shared recorder and
# terminal rule; a local copy would let a run report PASS for blocked, skipped or empty
# evidence again (see specs/066-review-remediation).
PROBE_TERMINAL_SOURCES = {
    "validation/animation-timeline-host-probe/src/dev/turboism/validation/animationtimeline/AnimationTimelineHostValidationPlugin.java": (
        "validation/animation-timeline-host-probe/build.sh",
    ),
    "validation/atlas-polygon-host-probe/src/dev/turboism/validation/atlaspolygon/AtlasPolygonHostValidationPlugin.java": (
        "validation/atlas-polygon-host-probe/build.sh",
    ),
    "validation/clipmask-viewer-host-probe/src/dev/turboism/validation/clipmaskviewer/ClipMaskViewerHostValidationPlugin.java": (
        "validation/clipmask-viewer-host-probe/build.sh",
    ),
    "validation/parameter-batch-transfer-host-probe/src/dev/turboism/validation/parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java": (
        "validation/parameter-batch-transfer-host-probe/build.sh",
    ),
}

SHARED_EVIDENCE_SOURCES = (
    "validation/shared/src/dev/turboism/validation/shared/verdict/ProbeTerminal.java",
    "validation/shared/src/dev/turboism/validation/shared/verdict/ProbeAssertions.java",
)

# A local terminal rule: a method computing the verdict, or a PASS default that stands
# when no assertion ran at all.
LOCAL_TERMINAL_RE = re.compile(
    r"\bcomputeTerminal\s*\(|\bString\s+terminal\s*=\s*\"PASS\""
)

# A local trigger wait. SessionGate.trigger owns the poll loop, the standalone deadline and
# the session turn/finished handling; nine probe copies of the loop had drifted apart before
# the shared gate existed, so a new private copy is rejected here.
LOCAL_TRIGGER_GATE_RE = re.compile(r"\bvoid\s+runWhenFlagged\s*\(")
TRIGGER_GATE_SOURCE = "validation/shared/src/dev/turboism/validation/SessionGate.java"
TRIGGER_GATE_CALL = "SessionGate.trigger("

# Flag-gated probes that must reach the shared gate instead of polling their own trigger.
TRIGGER_GATE_CONSUMERS = {
    "validation/animation-timeline-host-probe/src/dev/turboism/validation/animationtimeline/AnimationTimelineHostValidationPlugin.java": (
        "validation/animation-timeline-host-probe/build.sh",
    ),
    "validation/atlas-polygon-host-probe/src/dev/turboism/validation/atlaspolygon/AtlasPolygonHostValidationPlugin.java": (
        "validation/atlas-polygon-host-probe/build.sh",
    ),
    "validation/bounding-box-overlay-host-probe/src/dev/turboism/validation/boundingbox/BoundingBoxOverlayHostValidationPlugin.java": (
        "validation/bounding-box-overlay-host-probe/build.sh",
    ),
    "validation/clipmask-viewer-host-probe/src/dev/turboism/validation/clipmaskviewer/ClipMaskViewerHostValidationPlugin.java": (
        "validation/clipmask-viewer-host-probe/build.sh",
    ),
    "validation/deformer-rename-host-probe/src/dev/turboism/validation/deformerrename/DeformerRenameHostValidationPlugin.java": (
        "validation/deformer-rename-host-probe/build.sh",
    ),
    "validation/edit-host-probe/src/dev/turboism/validation/edithost/EditHostValidationPlugin.java": (
        "validation/edit-host-probe/build.sh",
    ),
    "validation/parameter-batch-transfer-host-probe/src/dev/turboism/validation/parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java": (
        "validation/parameter-batch-transfer-host-probe/build.sh",
    ),
    "validation/selection-lag-probe/src/dev/turboism/validation/selectionlag/SelectionLagProbePlugin.java": (
        "validation/selection-lag-probe/build.sh",
    ),
    "validation/theme-host-probe/src/dev/turboism/validation/theme/ThemeHostValidationPlugin.java": (
        "validation/theme-host-probe/build.sh",
    ),
}

# A result tail written outside the shared publication: the counter block, an
# assertion-record block, or a terminal line appended straight to the report. Probes
# publish through ProbeAssertions.appendPublication, which owns that order.
LOCAL_PUBLICATION_RE = re.compile(
    r"assertions\.(?:appendCounters|appendIndexed|appendNamed)\s*\(|"
    r'report\.append\(\s*"(?:status|terminal)='
)
PUBLICATION_CALL = "appendPublication("


# A copy renamed to a different basename would still declare the same class.
CLASS_RE = re.compile(
    r"\b(?:final\s+)?class\s+(RelocateJar|FixtureLoader|CodeSourceUrl|ProbeTerminal|ProbeAssertions)\b"
)


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
            allowed = CANONICAL.get(declared, ())
            if relative not in allowed:
                violations.append(
                    f"{relative}: declares class {match.group(1)} outside the allowed set; "
                    f"the single source lives at {CANONICAL[declared][0]}"
                )

    for relative, build_scripts in sorted(PROBE_TERMINAL_SOURCES.items()):
        source = root / relative
        if not source.is_file():
            violations.append(f"{relative}: probe source is missing")
            continue
        text = source.read_text(encoding="utf-8")
        if "dev.turboism.validation.shared.verdict.ProbeAssertions" not in text:
            violations.append(
                f"{relative}: does not record assertions through the shared "
                "dev.turboism.validation.shared.verdict.ProbeAssertions"
            )
        local = LOCAL_TERMINAL_RE.search(text)
        if local:
            violations.append(
                f"{relative}: computes a local terminal verdict ({local.group(0)}); "
                "call ProbeAssertions.terminal() instead"
            )
        local = LOCAL_PUBLICATION_RE.search(text)
        if local:
            violations.append(
                f"{relative}: writes a result tail outside the shared publication "
                f"({local.group(0).strip()}); call ProbeAssertions.appendPublication"
            )
        publications = text.count(PUBLICATION_CALL)
        if publications != 1:
            violations.append(
                f"{relative}: expected exactly one ProbeAssertions.appendPublication "
                f"call, found {publications}"
            )
        for build in build_scripts:
            build_path = root / build
            build_text = build_path.read_text(encoding="utf-8") if build_path.is_file() else ""
            for shared in SHARED_EVIDENCE_SOURCES:
                if shared not in build_text:
                    violations.append(
                        f"{build}: does not compile the shared evidence source {shared}"
                    )

    violations += trigger_gate_findings(root)
    return violations


def trigger_gate_findings(root: Path) -> list[str]:
    """Rejects a private trigger-wait copy and a consumer that skips the shared gate."""
    violations = []
    base = root / ROOT
    canonical = root / TRIGGER_GATE_SOURCE
    if not canonical.is_file():
        violations.append(f"{TRIGGER_GATE_SOURCE}: shared trigger gate is missing")
    for path in sorted(base.rglob("*.java")):
        relative = path.relative_to(root).as_posix()
        if relative == TRIGGER_GATE_SOURCE:
            continue
        match = LOCAL_TRIGGER_GATE_RE.search(path.read_text(encoding="utf-8"))
        if match:
            violations.append(
                f"{relative}: declares a local trigger wait ({match.group(0).strip()}); "
                "call SessionGate.trigger(...) instead"
            )

    for relative, build_scripts in sorted(TRIGGER_GATE_CONSUMERS.items()):
        source = root / relative
        if not source.is_file():
            violations.append(f"{relative}: flag-gated probe source is missing")
            continue
        text = source.read_text(encoding="utf-8")
        if TRIGGER_GATE_CALL not in text:
            violations.append(
                f"{relative}: does not wait through the shared gate; "
                f"call {TRIGGER_GATE_CALL}...).await()"
            )
        for build in build_scripts:
            build_path = root / build
            build_text = build_path.read_text(encoding="utf-8") if build_path.is_file() else ""
            if TRIGGER_GATE_SOURCE not in build_text:
                violations.append(
                    f"{build}: does not compile the shared trigger gate {TRIGGER_GATE_SOURCE}"
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
