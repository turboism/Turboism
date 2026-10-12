#!/usr/bin/env python3
"""Fail when a literal Cubism version set is not in the registry.

Version sets must come from review data, not from convenience literals: a
hand-written ``Set.of("5.2.03", "5.3.02")`` or ``SUPPORTED_VERSIONS`` table
that covers a few versions silently denies the rest. The monotonicity gate
already rejects non-upward-closed sets; this gate goes further and requires
*every* literal version set in production sources to be registered — a new
set can only appear after a conscious decision, never by accident.

Checked sites (the same Java roots as ``check_version_monotonicity.py``):

- ``collection`` — every ``Set.of``/``List.of``/``Map.of*``/``EnumSet.of``/
  ``copyOf`` literal that names at least one exact or ranged version token.
  The registry records the site's file, its sorted version set, and how many
  times that same set is repeated in the file, so duplicating a sanctioned
  set still trips the gate.
- ``file`` — the residual set of exact-version literals and ``CUBISM_*``
  constant references left in a file outside collection literals and
  ``@CubismEditor`` annotations (dispatch maps, switch arms, equals pins).
  Registered per file so a scattered de-facto list cannot grow unchecked.

``@CubismEditor`` annotations are deliberately exempt: they are the required
declaration mechanism, not a workaround.

``scripts/version-set-registry.json`` carries the sanctioned sites. Each
entry names ``kind``, ``path``, ``versions``, an audit ``basis``
(``canonical`` for the admitted-version trust roots, ``verification-derived``
for sets derived from verification records such as selector contracts and
host profiles, ``task-scoped`` for single-version validation lanes, or
``evidence`` for a documented genuine boundary) plus a ``reason``. An entry
that no longer matches a live site fails the check so the list cannot rot.

``--update`` rebuilds the registry from the current sources, preserving the
basis/reason of entries that still match; newly observed sites arrive with
empty basis/reason, which the check rejects until a human justifies them.

Usage:

    check_version_set_registry.py [repo-root]
    check_version_set_registry.py --update [repo-root]
"""
from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_version_monotonicity as monotonicity  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]

REGISTRY = Path("scripts/version-set-registry.json")
REGISTRY_FORMAT = "turboism.version-set-registry/v1"
PREVIEW_WRAPPERS_DIR = Path("scripts/preview")
GENERIC_RUNNER_NAME = "run-cubism-host-validation.sh"
SHELL_COMPACT_VERSION = re.compile(r"\b(5[0-3]\d{2})\b")
SHELL_DOTTED_VERSION = re.compile(r"\b(5\.\d{1,2}\.\d{2})\b")

BASES = ("canonical", "verification-derived", "task-scoped", "evidence")


class RegistryError(Exception):
    """Raised when the registry file cannot be parsed at all."""


def sort_versions(versions: set[str]) -> list[str]:
    return sorted(versions, key=monotonicity.parse_dotted)


def collect_sites(root: Path) -> dict[tuple[str, str], int]:
    """Observed sites: (kind, path, sorted-version-csv) -> occurrence count.

    ``kind`` is ``collection`` for collection literals and ``file`` for the
    per-file residual set.
    """
    admitted = monotonicity.admitted_versions(root)
    sites: dict[tuple[str, str], int] = {}
    for base in monotonicity.java_roots(root):
        if not base.is_dir():
            continue
        for source in sorted(base.rglob("*.java")):
            relative = source.relative_to(root).as_posix()
            if monotonicity.JAVA_EXCLUDE.search(relative):
                continue
            stripped = monotonicity.strip_comments(
                source.read_text(encoding="utf-8")
            )
            spans: list[tuple[int, int]] = []
            for match in monotonicity.ANNOTATION_OPEN.finditer(stripped):
                try:
                    end = monotonicity.match_paren(stripped, match.end() - 1)
                except monotonicity.MonotonicityError:
                    continue
                spans.append((match.start(), end))
            for match in monotonicity.COLLECTION_OPEN.finditer(stripped):
                try:
                    end = monotonicity.match_paren(stripped, match.end() - 1)
                except monotonicity.MonotonicityError:
                    continue
                spans.append((match.start(), end))
                declared = monotonicity.literal_versions(
                    stripped[match.start() : end], admitted
                )
                if declared:
                    key = (
                        "collection",
                        relative,
                        ",".join(sort_versions(declared)),
                    )
                    sites[key] = sites.get(key, 0) + 1
            residual = list(stripped)
            for start, end in spans:
                for i in range(start, end):
                    if residual[i] != "\n":
                        residual[i] = " "
            declared = monotonicity.literal_versions("".join(residual), admitted)
            if declared:
                key = ("file", relative, ",".join(sort_versions(declared)))
                sites[key] = sites.get(key, 0) + 1
    preview_dir = root / PREVIEW_WRAPPERS_DIR
    if preview_dir.is_dir():
        for wrapper in sorted(preview_dir.glob("run-*.sh")):
            if wrapper.name == GENERIC_RUNNER_NAME:
                continue
            relative = wrapper.relative_to(root).as_posix()
            code_lines = [
                line
                for line in wrapper.read_text(encoding="utf-8").splitlines()
                if not line.lstrip().startswith("#")
            ]
            code = "\n".join(code_lines)
            declared: set[str] = set()
            for token in SHELL_COMPACT_VERSION.findall(code):
                dotted = monotonicity.compact_to_dotted(token)
                if dotted in admitted:
                    declared.add(dotted)
            for dotted in SHELL_DOTTED_VERSION.findall(code):
                if dotted in admitted:
                    declared.add(dotted)
            if declared:
                key = ("file", relative, ",".join(sort_versions(declared)))
                sites[key] = sites.get(key, 0) + 1
    return sites


def load_registry(root: Path) -> tuple[list[dict], list[str]]:
    path = root / REGISTRY
    if not path.is_file():
        raise RegistryError(f"{REGISTRY}: registry missing; create it with --update")
    try:
        document = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError as exc:
        raise RegistryError(f"{REGISTRY}: invalid JSON: {exc}") from exc
    if document.get("format") != REGISTRY_FORMAT:
        raise RegistryError(f"{REGISTRY}: unexpected format {document.get('format')!r}")
    entries = document.get("sites")
    if not isinstance(entries, list):
        raise RegistryError(f"{REGISTRY}: 'sites' list not found")
    problems: list[str] = []
    seen: set[tuple[str, str, str]] = set()
    for index, entry in enumerate(entries):
        kind = entry.get("kind")
        versions = entry.get("versions")
        if kind not in ("collection", "file"):
            problems.append(f"site {index}: kind must be 'collection' or 'file'")
        if not isinstance(entry.get("path"), str) or not entry["path"]:
            problems.append(f"site {index}: 'path' missing")
        if not isinstance(versions, list) or not versions:
            problems.append(f"site {index}: 'versions' must be a non-empty list")
        elif versions != sort_versions(set(versions)):
            problems.append(f"site {index}: 'versions' must be sorted")
        if entry.get("basis") not in BASES:
            problems.append(
                f"site {index}: 'basis' must be one of {', '.join(BASES)}"
            )
        if not isinstance(entry.get("reason"), str) or not entry["reason"].strip():
            problems.append(f"site {index}: 'reason' must justify the site")
        count = entry.get("count", 1)
        if kind == "collection" and (not isinstance(count, int) or count < 1):
            problems.append(f"site {index}: 'count' must be a positive integer")
        if kind == "file" and "count" in entry:
            problems.append(f"site {index}: 'file' entries carry no 'count'")
        if isinstance(entry.get("path"), str) and isinstance(versions, list):
            key = (str(kind), entry["path"], ",".join(versions))
            if key in seen:
                problems.append(f"site {index}: duplicate registry entry")
            seen.add(key)
    return entries, problems


def check(root: Path) -> list[str]:
    observed = collect_sites(root)
    entries, problems = load_registry(root)
    failures = list(problems)
    expected: dict[tuple[str, str, str], dict] = {}
    for entry in entries:
        key = (entry["kind"], entry["path"], ",".join(entry["versions"]))
        expected[key] = entry
    for (kind, path, csv), count in sorted(observed.items()):
        entry = expected.get((kind, path, csv))
        if entry is None:
            failures.append(
                f"unregistered {kind} version set at {path}: {csv}; register it in "
                f"{REGISTRY} with a basis and reason, or derive the set from review data"
            )
            continue
        if kind == "collection" and entry.get("count", 1) != count:
            failures.append(
                f"{path}: set {csv} now occurs {count} time(s) but the registry "
                f"declares {entry.get('count', 1)}; update the count or remove the "
                f"new literal"
            )
    for (kind, path, csv), entry in sorted(expected.items()):
        if (kind, path, csv) not in observed:
            failures.append(
                f"stale registry entry: {kind} {path} {csv} is not live; remove "
                f"it from {REGISTRY}"
            )
    return failures


def update(root: Path) -> int:
    observed = collect_sites(root)
    existing: dict[tuple[str, str, str], dict] = {}
    path = root / REGISTRY
    if path.is_file():
        for entry in load_registry(root)[0]:
            existing[(entry["kind"], entry["path"], ",".join(entry["versions"]))] = entry
    sites = []
    pending = 0
    for (kind, site_path, csv), count in sorted(observed.items()):
        previous = existing.get((kind, site_path, csv), {})
        basis = previous.get("basis", "")
        reason = previous.get("reason", "")
        if not basis or not reason:
            pending += 1
        site = {
            "kind": kind,
            "path": site_path,
            "versions": csv.split(","),
            "basis": basis,
            "reason": reason,
        }
        if kind == "collection" and count > 1:
            site["count"] = count
        sites.append(site)
    document = {
        "format": REGISTRY_FORMAT,
        "comment": (
            "Sanctioned literal Cubism version sets in production sources. Every "
            "collection literal or file-level residual set of exact versions must be "
            "registered here with an audit basis and reason; unregistered sites fail "
            "checkVersionSetRegistry, and stale entries fail too. Regenerate with "
            "`python3 scripts/check_version_set_registry.py --update` and fill in the "
            "basis/reason of each new site."
        ),
        "sites": sites,
    }
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(document, ensure_ascii=False, indent=1) + "\n", encoding="utf-8"
    )
    print(
        f"updated {REGISTRY} with {len(sites)} site(s)"
        + (f"; {pending} site(s) still need a basis/reason" if pending else "")
    )
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", nargs="?", default=str(ROOT))
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    try:
        if args.update:
            return update(root)
        failures = check(root)
    except RegistryError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2
    except monotonicity.MonotonicityError as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 2
    for failure in failures:
        print(f"FAIL: {failure}")
    if failures:
        print(f"\n{len(failures)} version-set-registry finding(s)", file=sys.stderr)
        return 1
    print("PASS: every literal Cubism version set is registered")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
