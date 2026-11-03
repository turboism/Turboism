#!/usr/bin/env python3
"""Validates README coverage for every first-party plugin module.

Two tiers, because only one of them describes user-visible harm:

* ``README.md`` is the primary document that ships in every plugin JAR and is
  surfaced by the installer. It keeps the full contract: front matter, the
  ordered section set, one mention per declared permission/capability, and a
  changelog entry for the documented version.
* ``README_zh.md`` / ``README_ja.md`` / ``README_ko.md`` are optional
  translations. When present they must be readable, titled, non-empty and must
  not document a version ahead of the descriptor. They are deliberately *not*
  gated on translated heading text, heading order, per-permission tokens or a
  changelog entry: a translator merging or reordering sections is not user harm,
  and enforcing it only produced build breaks that were fixed by rewording
  headings.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
from turboism_dev.json_io import read_json
from turboism_dev.paths import repo_root

ROOT = repo_root()
PLUGINS = ROOT / "plugins"
RELEASE_PLUGINS = ROOT / "packaging/release-plugins.txt"
MARKET_PLUGINS = ROOT / "packaging/market-plugins.txt"
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
PRIMARY_README = "README.md"
# The four README files the Windows installer stages for a release plugin
# (packaging/windows-installer/assemble-release.sh), so they are published
# alongside the JAR when a translator provides them.
LOCALIZED_READMES = ("README_zh.md", "README_ja.md", "README_ko.md")
PRIMARY_HEADINGS = (
    "## What it does", "## Requirements and compatibility", "## Install and enable",
    "## How to use", "## Capabilities", "## Permissions", "## Privacy and data",
    "## Status and limitations", "## Troubleshooting", "## Changelog", "## Support and license",
)
PLACEHOLDERS = ("TODO", "TBD", "COMING SOON", "{manifest.", "{plugin")
# Headings that may legitimately carry an "(minimization)" qualifier.
MINIMIZATION_HEADINGS = {"## Privacy and data"}
ALLOWED_KIND = {"core", "feature", "demo", "migration-shell"}
ALLOWED_STATUS = {"built-in", "preview", "development", "migration-shell"}
ALLOWED_DELIVERY = {"bundled", "store-candidate", "development-only", "unpublished"}
REQUIRED_FRONT_MATTER = {
    "turboismReadmeSchema",
    "pluginId",
    "version",
    "kind",
    "status",
    "delivery",
    "category",
    "tags",
    "turboismApi",
    "requiresCubism",
    "interface",
}


def documented_version_errors(documented: str, current: str) -> list[str]:
    """README text ships inside the plugin JAR, so it records the version of the last content change.

    The descriptor version may move ahead of it without republishing, but never behind.
    """
    try:
        documented_key = tuple(int(part) for part in documented.split("."))
        current_key = tuple(int(part) for part in current.split("."))
    except ValueError:
        return [f"version: README {documented!r} or descriptor {current!r} is not MAJOR.MINOR.PATCH"]
    if len(documented_key) != 3 or len(current_key) != 3 or documented_key > current_key:
        return [f"version: README documents {documented!r}, which is not at or below descriptor {current!r}"]
    return []


def parse_front_matter(path: Path, text: str) -> dict[str, str]:
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        raise ValueError("README must start with YAML-style front matter")
    try:
        end = lines.index("---", 1)
    except ValueError as failure:
        raise ValueError("README front matter is not closed") from failure
    values: dict[str, str] = {}
    for line in lines[1:end]:
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        if ":" not in line:
            raise ValueError(f"invalid front-matter line: {line}")
        key, value = line.split(":", 1)
        key = key.strip()
        value = value.strip().strip('"')
        if key in values:
            raise ValueError(f"duplicate front-matter key: {key}")
        values[key] = value
    missing = REQUIRED_FRONT_MATTER - values.keys()
    if missing:
        raise ValueError("missing front-matter keys: " + ", ".join(sorted(missing)))
    unknown = values.keys() - REQUIRED_FRONT_MATTER
    if unknown:
        raise ValueError("unknown front-matter keys: " + ", ".join(sorted(unknown)))
    return values


def optional_front_matter(text: str) -> dict[str, str]:
    """Return whatever key/value pairs a translation chose to declare.

    A translated README may carry the same front matter as the primary one, a
    subset of it, or none at all; only the keys that are present are meaningful.
    """
    lines = text.splitlines()
    if not lines or lines[0] != "---":
        return {}
    try:
        end = lines.index("---", 1)
    except ValueError as failure:
        raise ValueError("README front matter is not closed") from failure
    values: dict[str, str] = {}
    for line in lines[1:end]:
        if not line.strip() or line.lstrip().startswith("#") or ":" not in line:
            continue
        key, value = line.split(":", 1)
        values[key.strip()] = value.strip().strip('"')
    return values


def expect(actual: str, expected: str, field: str, errors: list[str]) -> None:
    if actual != expected:
        errors.append(f"front matter {field}={actual!r}; expected {expected!r}")


def validate_localized_readme(module: Path, filename: str, descriptor: dict[str, object]) -> list[str]:
    """Optional translation: readable, titled, non-empty, and version-consistent.

    Nothing here depends on translated wording, so a translator may reorder,
    merge or rename sections without breaking the build.
    """
    readme = module / filename
    if not readme.is_file():
        return []
    try:
        text = readme.read_text(encoding="utf-8", errors="strict")
    except UnicodeError:
        return [f"{filename} is not valid UTF-8"]
    errors: list[str] = []
    try:
        metadata = optional_front_matter(text)
    except ValueError as failure:
        return [str(failure)]
    body = [
        line for line in text.splitlines()
        if line.strip() and not line.startswith("#") and not line.startswith("---")
    ]
    if not body:
        errors.append("translated README has no body text")
    if not re.search(r"^# \S.*$", text, re.MULTILINE):
        errors.append("README title is missing")
    for placeholder in PLACEHOLDERS:
        if placeholder.lower() in text.lower():
            errors.append(f"placeholder text remains: {placeholder}")
    if "version" in metadata:
        errors.extend(documented_version_errors(metadata["version"], descriptor["version"]))
    if "pluginId" in metadata:
        expect(metadata["pluginId"], descriptor["id"], "pluginId", errors)
    return errors


def validate_primary_readme(
    module: Path,
    descriptor: dict[str, object],
    expected_role: tuple[str, str, str],
) -> list[str]:
    errors: list[str] = []
    readme = module / PRIMARY_README
    if not readme.is_file():
        return [f"{PRIMARY_README} is missing"]
    try:
        text = readme.read_text(encoding="utf-8", errors="strict")
    except UnicodeError:
        return [f"{PRIMARY_README} is not valid UTF-8"]
    try:
        metadata = parse_front_matter(readme, text)
    except ValueError as failure:
        return [str(failure)]

    expect(metadata["turboismReadmeSchema"], "1", "turboismReadmeSchema", errors)
    expect(metadata["pluginId"], descriptor["id"], "pluginId", errors)
    documented_version = metadata["version"]
    errors.extend(documented_version_errors(documented_version, descriptor["version"]))
    expect(metadata["category"], descriptor["category"], "category", errors)
    expect(metadata["tags"], ", ".join(descriptor["tags"]), "tags", errors)
    expect(metadata["turboismApi"], descriptor["turboismApi"], "turboismApi", errors)
    expect(
        metadata["requiresCubism"],
        str(descriptor["environment"]["requiresCubism"]).lower(),
        "requiresCubism",
        errors,
    )
    expect(metadata["interface"], descriptor["environment"]["ui"], "interface", errors)

    if metadata["kind"] not in ALLOWED_KIND:
        errors.append(f"unsupported kind: {metadata['kind']}")
    if metadata["status"] not in ALLOWED_STATUS:
        errors.append(f"unsupported status: {metadata['status']}")
    if metadata["delivery"] not in ALLOWED_DELIVERY:
        errors.append(f"unsupported delivery: {metadata['delivery']}")
    actual_role = (metadata["kind"], metadata["status"], metadata["delivery"])
    if actual_role != expected_role:
        errors.append(f"kind/status/delivery={actual_role!r}; expected {expected_role!r}")

    positions: list[int] = []
    for heading in PRIMARY_HEADINGS:
        pattern = (
            rf"^{re.escape(heading)}(?: minimization)?$"
            if heading in MINIMIZATION_HEADINGS
            else rf"^{re.escape(heading)}$"
        )
        matches = list(re.finditer(pattern, text, re.MULTILINE))
        count = len(matches)
        if count != 1:
            errors.append(f"heading {heading!r} occurs {count} times")
        positions.append(matches[0].start() if matches else -1)
    if all(position >= 0 for position in positions) and positions != sorted(positions):
        errors.append("required headings are out of order")

    if not re.search(rf"^# {re.escape(descriptor['name'])}$", text, re.MULTILINE):
        errors.append("title does not match descriptor name")
    for placeholder in PLACEHOLDERS:
        if placeholder.lower() in text.lower():
            errors.append(f"placeholder text remains: {placeholder}")

    for permission in descriptor["permissions"]:
        token = f"`{permission['id']}`"
        if text.count(token) != 1:
            errors.append(f"permission {token} must appear exactly once")
    for capability in descriptor["capabilities"]:
        token = f"`{capability}`"
        if text.count(token) != 1:
            errors.append(f"capability {token} must appear exactly once")
    if not descriptor["permissions"]:
        if not re.search(r"The manifest declares no permissions\.", text):
            errors.append("empty permission inventory is not stated")
    if not descriptor["capabilities"]:
        if not re.search(r"No capabilities are declared in the plugin manifest\.", text):
            errors.append("empty capability inventory is not stated")

    plugin_version = documented_version
    if "## Changelog" in text:
        pattern = rf"^###\s+\[?{re.escape(plugin_version)}\]?"
        if not re.search(pattern, text, re.MULTILINE):
            errors.append(f"changelog does not contain entry for documented version {plugin_version}")
    return errors


def store_candidate_module_names() -> set[str]:
    rosters = [RELEASE_PLUGINS]
    if MARKET_PLUGINS.is_file():
        rosters.append(MARKET_PLUGINS)
    names: set[str] = set()
    for roster in rosters:
        for line in roster.read_text(encoding="utf-8").splitlines():
            project = line.strip()
            if project and not project.startswith("#"):
                names.add(project.removeprefix(":plugins:"))
    return names


def validate_module(module: Path) -> list[tuple[str, str]]:
    descriptor = read_json(module / DESCRIPTOR)
    store_candidates = store_candidate_module_names()
    expected_role = (
        ("core", "built-in", "bundled") if module.name == "core" else
        ("feature", "preview", "store-candidate") if module.name in store_candidates else
        ("demo", "development", "development-only") if module.name == "demo" else
        ("feature", "development", "development-only")
    )
    failures = [(PRIMARY_README, error) for error in validate_primary_readme(module, descriptor, expected_role)]
    if module.name in store_candidates:
        failures.extend(
            (filename, error)
            for filename in LOCALIZED_READMES
            for error in validate_localized_readme(module, filename, descriptor)
        )
    return failures


def official_modules() -> list[Path]:
    store_candidates = store_candidate_module_names()
    official: list[Path] = [PLUGINS / name for name in sorted(store_candidates) if (PLUGINS / name).is_dir()]
    all_modules = sorted(
        descriptor.parent.parent.parent.parent.parent.parent
        for descriptor in PLUGINS.glob("*/src/main/resources/META-INF/turboism/plugin.json")
    )
    official_names = {module.name for module in official}
    return official + [module for module in all_modules if module.name not in official_names]


def main() -> int:
    try:
        modules = official_modules()
    except (OSError, ValueError) as failure:
        print(f"First-party plugin README check failed: {failure}", file=sys.stderr)
        return 1
    if not modules:
        print("First-party plugin README check failed: release allowlist is empty.", file=sys.stderr)
        return 1
    failures: list[str] = []
    for module in modules:
        if not (module / DESCRIPTOR).is_file():
            failures.append(f"plugins/{module.name}: official descriptor is missing")
            continue
        for filename, error in validate_module(module):
            failures.append(f"plugins/{module.name}/{filename}: {error}")
    if failures:
        print("First-party plugin README check failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {len(modules)} first-party plugins have a valid primary README (translations optional)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
