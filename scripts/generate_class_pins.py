#!/usr/bin/env python3
"""Generate or check reviewed-class SHA-256 pin files from a host JAR.

The hook pin tables consumed by ``ClassPinTable`` live as JSON resources under
``runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins/``. Each
file maps a Cubism Editor version to ``class-internal-name -> sha256`` entries.

This tool removes the manual step of hashing host classes when a new reviewed
host version arrives: point it at the host JAR and it rewrites the version
block in every selected pin file. Class keys are JVM internal names
(``com/live2d/...``); the JAR entry hashed is ``<key>.class``.

Modes:

- ``update --jar HOST.jar --version X.Y.Z`` computes SHA-256 for every class
  key known to the selected pin files and writes the version block. Other
  version blocks are preserved byte-for-byte in semantics (re-serialised with
  the same 2-space format). A missing class aborts that file unless
  ``--allow-partial`` is given, in which case found classes are still written
  and the missing ones reported.
- ``check --jar HOST.jar --version X.Y.Z`` verifies every pinned class in the
  given version block against the JAR and exits non-zero on any mismatch or
  missing entry. Use this in CI to prove a new host build still matches the
  pins before merging.
- ``--pin NAME`` restricts either mode to ``NAME.json``; repeat for several.
  Default is every ``*.json`` under the pin directory.
- ``--new NAME --class INTERNAL [--class ...]`` creates a fresh pin file
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
import hashlib
import json
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PIN_DIR = ROOT / "runtime/src/main/resources/dev/turboism/adapter/cubism/class-pins"


def _load_pin_files(selected: list[str]) -> dict[str, tuple[Path, dict]]:
    files: dict[str, tuple[Path, dict]] = {}
    if selected:
        candidates = [PIN_DIR / f"{name}.json" for name in selected]
    else:
        candidates = sorted(PIN_DIR.glob("*.json"))
    for path in candidates:
        if not path.is_file():
            raise SystemExit(f"pin file not found: {path}")
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
        except json.JSONDecodeError as failure:
            raise SystemExit(f"pin file {path} is not valid JSON: {failure}")
        if not isinstance(data, dict):
            raise SystemExit(f"pin file {path} must contain a top-level object")
        files[path.stem] = (path, data)
    if not files:
        raise SystemExit(f"no pin files selected under {PIN_DIR}")
    return files


def _collect_class_keys(tables: dict[str, dict], extra: list[str]) -> dict[str, set[str]]:
    """Return pin-name -> set of class keys (union across versions, plus extras)."""
    keys: dict[str, set[str]] = {}
    for name, data in tables.items():
        collected: set[str] = set()
        for version, block in data.items():
            if not isinstance(block, dict):
                raise SystemExit(
                    f"pin file {name}.json version '{version}' is not an object")
            collected.update(block)
        keys[name] = collected
    return keys


def _sha256_members(jar: zipfile.ZipFile, internal_names: set[str]) -> tuple[dict[str, str], list[str]]:
    """Hash ``<name>.class`` entries; returns (digests, missing names)."""
    names = jar.namelist()
    digests: dict[str, str] = {}
    missing: list[str] = []
    for internal in sorted(internal_names):
        entry = internal + ".class"
        if entry not in names:
            missing.append(internal)
            continue
        digests[internal] = hashlib.sha256(jar.read(entry)).hexdigest()
    return digests, missing


def _write_table(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2) + "\n", encoding="utf-8")


def _cmd_update(args: argparse.Namespace) -> int:
    if args.new:
        table_path = PIN_DIR / f"{args.new}.json"
        data = {}
        if table_path.is_file():
            data = json.loads(table_path.read_text(encoding="utf-8"))
        tables = {args.new: (table_path, data)}
        class_keys = {args.new: set(args.classes)}
        if not args.classes:
            raise SystemExit("--new requires at least one --class entry")
    else:
        tables = _load_pin_files(args.pin)
        class_keys = _collect_class_keys(
            {name: data for name, (path, data) in tables.items()}, [])

    with zipfile.ZipFile(args.jar) as jar:
        exit_code = 0
        for name, (path, data) in tables.items():
            digests, missing = _sha256_members(jar, class_keys[name])
            if missing:
                print(f"{name}: {len(missing)} class(es) absent from JAR:", file=sys.stderr)
                for internal in missing:
                    print(f"  missing {internal}.class", file=sys.stderr)
                if not args.allow_partial:
                    print(f"{name}: skipped (use --allow-partial to write found classes)",
                          file=sys.stderr)
                    exit_code = 1
                    continue
            block = {key: digests[key] for key in sorted(digests)}
            updated = dict(data)
            updated[args.version] = block
            # Deterministic order: version keys sorted ascending.
            ordered = {v: updated[v] for v in sorted(updated)}
            if args.dry_run:
                print(f"--- {path} ---")
                print(json.dumps(ordered, indent=2))
            else:
                _write_table(path, ordered)
                print(f"{name}: wrote {len(block)} pin(s) for {args.version} -> {path}")
        return exit_code


def _cmd_check(args: argparse.Namespace) -> int:
    tables = _load_pin_files(args.pin)
    failures = 0
    with zipfile.ZipFile(args.jar) as jar:
        for name, (path, data) in tables.items():
            block = data.get(args.version)
            if block is None:
                print(f"{name}: no pins recorded for version {args.version}", file=sys.stderr)
                failures += 1
                continue
            digests, missing = _sha256_members(jar, set(block))
            for internal in missing:
                print(f"{name}: missing {internal}.class", file=sys.stderr)
                failures += 1
            for internal, expected in sorted(block.items()):
                observed = digests.get(internal)
                if observed is not None and observed != expected:
                    print(
                        f"{name}: MISMATCH {internal}\n"
                        f"  expected {expected}\n"
                        f"  observed {observed}",
                        file=sys.stderr)
                    failures += 1
            if failures == 0:
                print(f"{name}: {len(block)} pin(s) verified for {args.version}")
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
                        help="pin file base name to process; repeatable; default is all")
    parser.add_argument("--new", metavar="NAME",
                        help="create a new pin file NAME.json (requires --class)")
    parser.add_argument("--class", dest="classes", action="append", default=[],
                        help="internal class name to pin when using --new; repeatable")
    parser.add_argument("--allow-partial", action="store_true",
                        help="write pins for classes found even if others are missing")
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
