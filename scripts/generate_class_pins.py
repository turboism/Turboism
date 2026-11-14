#!/usr/bin/env python3
"""Generate or check reviewed target-class tables from a host JAR.

The hook target tables consumed by ``ClassPinTable`` live as JSON resources under
``runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/``. Each
file names, per reviewed Cubism Editor version, the internal names of the host
classes the hook rewrites or reads. The whole-artifact identity in
``ReviewedHostArtifacts`` plus each hook's method-shape gate admit the bytes, so
no per-class digest is recorded here.

Tables use the compact shape: one ``default`` class list plus ``overrides`` for
the versions that deviate. A reviewed version absent from ``overrides`` inherits
``default``, so a table whose classes never changed carries a single block.
``turboism_dev.class_pins`` owns the read, expand and fold rules for both the
compact and the legacy flat shape.

This tool removes the manual step of locating host classes when a new reviewed
host version arrives: point it at the host JAR and it rewrites the version block
in every selected table. Class keys are JVM internal names (``com/live2d/...``);
the JAR entry checked is ``<key>.class``.

Modes:

- ``update --jar HOST.jar --version X.Y.Z`` writes the version block listing
  every class key known to the selected tables, then folds the table back into
  the compact shape. A missing class aborts that file unless ``--allow-partial``
  is given, in which case found classes are still written and the missing ones
  reported.
- ``check --jar HOST.jar --version X.Y.Z`` verifies every declared class in the
  given version block exists in the JAR and exits non-zero on any missing entry.
  Use this in CI to prove a new host build still carries the targets before
  merging.
- ``--pin NAME`` restricts either mode to ``NAME.json``; repeat for several.
  Default is every ``*.json`` under the target-table directory.
- ``--new NAME --class INTERNAL [--class ...]`` creates a fresh table
  containing only the given version block (implies ``--pin NAME`` and
  ``update`` semantics for that file).
- ``--dry-run`` prints the resulting JSON without writing.

Usage: generate_class_pins.py update --jar cubism.jar --version 5.3.03
       generate_class_pins.py check  --jar cubism.jar --version 5.3.03
       generate_class_pins.py update --jar cubism.jar --version 5.4.00 \
           --new mesh-mirror --class com/live2d/... [--class ...]
"""
from __future__ import annotations

import argparse
import sys
import zipfile
from pathlib import Path

from preview.reviewed_host_artifacts import SOURCE_RELATIVE, load_releases
from turboism_dev import class_pins
from turboism_dev.json_io import dump_json
from turboism_dev.paths import repo_root

ROOT = repo_root()
PIN_DIR = ROOT / "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins"


def admitted_versions(root: Path = ROOT) -> list[str]:
    """Reviewed Editor versions a compact table expands over, oldest first."""
    return [release["version"] for release in load_releases(root / SOURCE_RELATIVE)]


def _load_pin_files(selected: list[str]) -> dict[str, tuple[Path, dict[str, list[str]]]]:
    """Read the selected tables and return name -> (path, expanded version map)."""
    versions = admitted_versions()
    files: dict[str, tuple[Path, dict[str, list[str]]]] = {}
    if selected:
        candidates = [PIN_DIR / f"{name}.json" for name in selected]
    else:
        candidates = sorted(PIN_DIR.glob("*.json"))
    for path in candidates:
        if not path.is_file():
            raise SystemExit(f"target table not found: {path}")
        try:
            files[path.stem] = (path, class_pins.load(path, versions))
        except class_pins.ClassPinError as failure:
            raise SystemExit(str(failure))
    if not files:
        raise SystemExit(f"no target tables selected under {PIN_DIR}")
    return files


def _collect_class_keys(tables: dict[str, dict[str, list[str]]]) -> dict[str, set[str]]:
    """Return table-name -> set of class keys (union across versions)."""
    return {name: {key for block in table.values() for key in block}
            for name, table in tables.items()}


def _present_members(jar: zipfile.ZipFile, internal_names: set[str]) -> tuple[list[str], list[str]]:
    """Check ``<name>.class`` entries; returns (present names, missing names)."""
    names = set(jar.namelist())
    present: list[str] = []
    missing: list[str] = []
    for internal in sorted(internal_names):
        (present if internal + ".class" in names else missing).append(internal)
    return present, missing


def _write_table(path: Path, table: dict[str, list[str]]) -> None:
    """Write the compact form of an expanded table."""
    class_pins.write(path, class_pins.fold(table))


def _cmd_update(args: argparse.Namespace) -> int:
    versions = admitted_versions()
    if args.new:
        table_path = PIN_DIR / f"{args.new}.json"
        table: dict[str, list[str]] = {}
        if table_path.is_file():
            table = class_pins.load(table_path, versions)
        tables = {args.new: (table_path, table)}
        class_keys = {args.new: set(args.classes)}
        if not args.classes:
            raise SystemExit("--new requires at least one --class entry")
    else:
        tables = _load_pin_files(args.pin)
        class_keys = _collect_class_keys({name: table for name, (path, table) in tables.items()})

    with zipfile.ZipFile(args.jar) as jar:
        exit_code = 0
        for name, (path, table) in tables.items():
            present, missing = _present_members(jar, class_keys[name])
            if missing:
                print(f"{name}: {len(missing)} class(es) absent from JAR:", file=sys.stderr)
                for internal in missing:
                    print(f"  missing {internal}.class", file=sys.stderr)
                if not args.allow_partial:
                    print(f"{name}: skipped (use --allow-partial to write found classes)",
                          file=sys.stderr)
                    exit_code = 1
                    continue
            block = sorted(present)
            updated = dict(table)
            updated[args.version] = block
            # Deterministic order: version keys sorted ascending.
            ordered = {version: updated[version] for version in sorted(updated)}
            if args.dry_run:
                print(f"--- {path} ---")
                print(dump_json(class_pins.canonical(class_pins.fold(ordered)), indent=2).rstrip("\n"))
            else:
                _write_table(path, ordered)
                print(f"{name}: wrote {len(block)} target(s) for {args.version} -> {path}")
        return exit_code


def _cmd_check(args: argparse.Namespace) -> int:
    tables = _load_pin_files(args.pin)
    failures = 0
    with zipfile.ZipFile(args.jar) as jar:
        for name, (path, table) in tables.items():
            block = table.get(args.version)
            if block is None:
                print(f"{name}: no targets recorded for version {args.version}", file=sys.stderr)
                failures += 1
                continue
            _, missing = _present_members(jar, set(block))
            for internal in missing:
                print(f"{name}: missing {internal}.class", file=sys.stderr)
                failures += 1
            if not missing:
                print(f"{name}: {len(block)} target(s) verified for {args.version}")
    if failures:
        print(f"check failed: {failures} problem(s)", file=sys.stderr)
        return 1
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("mode", choices=("update", "check"))
    parser.add_argument("--jar", required=True, help="path to the host application JAR")
    parser.add_argument("--version", required=True,
                        help="Cubism Editor version label, e.g. 5.3.03")
    parser.add_argument("--pin", action="append", default=[],
                        help="target table base name to process; repeatable; default is all")
    parser.add_argument("--new", metavar="NAME",
                        help="create a new target table NAME.json (requires --class)")
    parser.add_argument("--class", dest="classes", action="append", default=[],
                        help="internal class name to declare when using --new; repeatable")
    parser.add_argument("--allow-partial", action="store_true",
                        help="write targets found even if others are missing")
    parser.add_argument("--dry-run", action="store_true",
                        help="print resulting JSON instead of writing files")
    args = parser.parse_args()

    if not Path(args.jar).is_file():
        raise SystemExit(f"host JAR not found: {args.jar}")
    if args.classes and not args.new:
        raise SystemExit("--class is only valid together with --new")

    if args.mode == "check":
        if args.new:
            raise SystemExit("--new is only valid in update mode")
        return _cmd_check(args)
    return _cmd_update(args)


if __name__ == "__main__":
    raise SystemExit(main())
