#!/usr/bin/env python3
"""Track when each SDK member first shipped and verify plugin API floors against it.

--write  rebuild compatibility/sdk-api-since.json from the release tags plus the working tree
--check  compare each plugin's `turboismApi` lower bound with the newest SDK member it links
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from turboism_dev.versions import framework_version

ROOT = Path(__file__).resolve().parents[1]
SINCE_FILE = ROOT / "compatibility/sdk-api-since.json"
HELPER = ROOT / "scripts/SdkApiRefs.java"
FORMAT = "turboism.sdk-api-since"
BASELINE = "0.67.0"
TAG = re.compile(r"^v(\d+\.\d+\.\d+)(?:-beta\.(\d+))?$")


def version_key(version: str) -> tuple[int, ...]:
    return tuple(int(part) for part in version.split("."))


def release_tags(tags: list[str]) -> list[tuple[str, str]]:
    """Return (api version, tag) per base from the baseline on, preferring the last tag of each base.

    A member that appears in any tag of a base is available from that base's API version,
    so the last beta (or the stable tag) is the right snapshot. Nightly tags are ignored.
    """
    latest: dict[str, tuple[int, str]] = {}
    for tag in tags:
        match = TAG.match(tag)
        if not match or version_key(match.group(1)) < version_key(BASELINE):
            continue
        rank = 10**9 if match.group(2) is None else int(match.group(2))
        base = match.group(1)
        if base not in latest or rank > latest[base][0]:
            latest[base] = (rank, tag)
    return [(base, latest[base][1]) for base in sorted(latest, key=version_key)]


def java_binary() -> str:
    home = os.environ.get("JAVA_HOME")
    return str(Path(home) / "bin/java") if home else shutil.which("java") or "java"


def run_helper(*args: str) -> list[str]:
    result = subprocess.run([java_binary(), str(HELPER), *args], capture_output=True, text=True, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"SdkApiRefs {args[0]} failed:\n{result.stderr}")
    return result.stdout.splitlines()


def compile_sdk(sources: Path, out: Path) -> None:
    files = [str(path) for path in sources.rglob("*.java")]
    out.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        [str(Path(java_binary()).with_name("javac")), "-nowarn", "-proc:none", "-encoding", "UTF-8", "-d", str(out), *files],
        check=True,
        capture_output=True,
    )


def surface_hash(keys: list[str]) -> str:
    return hashlib.sha256("\n".join(sorted(keys)).encode()).hexdigest()


def class_of(key: str) -> str:
    return key.split("#", 1)[0]


def build_since(snapshots: list[tuple[str, list[str]]]) -> dict:
    """snapshots: ordered (api version, member keys). Keys first seen after the baseline are recorded."""
    first: dict[str, str] = {}
    for version, keys in snapshots:
        for key in keys:
            first.setdefault(key, version)
    classes = {key: v for key, v in first.items() if "#" not in key and v != BASELINE}
    members = {
        key: v for key, v in first.items()
        if "#" in key and v != BASELINE and v != classes.get(class_of(key))
    }
    return {
        "format": FORMAT,
        "baseline": BASELINE,
        "surfaceSha256": surface_hash(snapshots[-1][1]),
        "classes": dict(sorted(classes.items())),
        "members": dict(sorted(members.items())),
    }


def since_of(table: dict, key: str) -> str:
    return table["members"].get(key) or table["classes"].get(class_of(key)) or table["baseline"]


def write() -> int:
    tags = subprocess.run(["git", "tag", "-l", "v*"], cwd=ROOT, capture_output=True, text=True, check=True).stdout.split()
    selected = release_tags(tags)
    if not selected:
        print("No release tags found; fetch tags first (git fetch --tags).", file=sys.stderr)
        return 1
    current = framework_version(ROOT)
    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp)
        snapshots = []
        for base, tag in selected:
            sources = work / f"{tag}-src"
            sources.mkdir()
            archive = subprocess.run(["git", "archive", tag, "sdk/src/main/java"], cwd=ROOT, capture_output=True, check=True).stdout
            subprocess.run(["tar", "-x", "-C", str(sources)], input=archive, check=True)
            out = work / f"{tag}-out"
            compile_sdk(sources / "sdk/src/main/java", out)
            snapshots.append((base, run_helper("enumerate", str(out))))
        head_out = work / "head-out"
        compile_sdk(ROOT / "sdk/src/main/java", head_out)
        snapshots.append((current, run_helper("enumerate", str(head_out))))
    table = build_since(snapshots)
    SINCE_FILE.write_text(json.dumps(table, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Wrote {SINCE_FILE.relative_to(ROOT)}: {len(table['classes'])} classes, {len(table['members'])} members after {BASELINE}.")
    return 0


def declared_lower(plugin: str) -> str:
    descriptor = ROOT / "plugins" / plugin / "src/main/resources/META-INF/turboism/plugin.json"
    api = json.loads(descriptor.read_text(encoding="utf-8"))["turboismApi"]
    return api[1:-1].split(",")[0]


def plugin_floors(table: dict, lines: list[str]) -> dict[str, tuple[str, list[str]]]:
    floors: dict[str, tuple[str, list[str]]] = {}
    for line in lines:
        plugin, owner, candidates = line.split("\t")
        keys = candidates.split(" ")
        resolved = [key for key in keys[1:] if not key.startswith("UNRESOLVED")]
        need = since_of(table, keys[0])
        if resolved:
            need = max(need, min((since_of(table, key) for key in resolved), key=version_key), key=version_key)
        label = resolved[0] if resolved else owner
        best = floors.get(plugin)
        if best is None or version_key(need) > version_key(best[0]):
            floors[plugin] = (need, [label])
        elif need == best[0] and len(best[1]) < 3:
            best[1].append(label)
    return floors


def check(sdk_classes: str, plugins: list[str]) -> int:
    table = json.loads(SINCE_FILE.read_text(encoding="utf-8"))
    if table.get("format") != FORMAT:
        raise ValueError(f"{SINCE_FILE}: unexpected format")
    if surface_hash(run_helper("enumerate", sdk_classes)) != table["surfaceSha256"]:
        print("SDK surface changed since compatibility/sdk-api-since.json was written; "
              "run python3 scripts/sdk_api_since.py --write", file=sys.stderr)
        return 1
    floors = plugin_floors(table, run_helper("refs", sdk_classes, *plugins))
    failures = []
    for entry in plugins:
        plugin = entry.split("=", 1)[0]
        need, culprits = floors.get(plugin, (table["baseline"], []))
        lower = declared_lower(plugin)
        if version_key(lower) < version_key(need):
            failures.append(f"plugins/{plugin}: turboismApi starts at {lower} but it links SDK members "
                            f"added in {need} ({', '.join(culprits)})")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Plugin API floors cover every linked SDK member ({len(plugins)} plugins checked).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--check", action="store_true")
    parser.add_argument("--sdk-classes", help="compiled SDK classes directory (--check)")
    parser.add_argument("--plugin", action="append", default=[], metavar="NAME=CLASSES_DIR")
    args = parser.parse_args()
    if args.write == args.check:
        parser.error("choose exactly one of --write or --check")
    return write() if args.write else check(args.sdk_classes, args.plugin)


if __name__ == "__main__":
    raise SystemExit(main())
