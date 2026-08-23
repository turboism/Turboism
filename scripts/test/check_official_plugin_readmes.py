#!/usr/bin/env python3
"""Validates store-ready README coverage for every official plugin module."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
PLUGINS = ROOT / "plugins"
RELEASE_PLUGINS = ROOT / "packaging/release-plugins.txt"
DESCRIPTOR = Path("src/main/resources/META-INF/turboism/plugin.json")
REQUIRED_HEADINGS = (
    "## What it does",
    "## Requirements and compatibility",
    "## Install and enable",
    "## How to use",
    "## Capabilities",
    "## Permissions",
    "## Privacy and data",
    "## Status and limitations",
    "## Troubleshooting",
    "## Support and license",
)
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


def expect(actual: str, expected: str, field: str, errors: list[str]) -> None:
    if actual != expected:
        errors.append(f"front matter {field}={actual!r}; expected {expected!r}")


def validate_module(module: Path) -> list[str]:
    errors: list[str] = []
    descriptor_path = module / DESCRIPTOR
    readme = module / "README.md"
    if not readme.is_file():
        return ["README.md is missing"]
    descriptor = json.loads(descriptor_path.read_text(encoding="utf-8"))
    text = readme.read_text(encoding="utf-8")
    try:
        metadata = parse_front_matter(readme, text)
    except ValueError as failure:
        return [str(failure)]

    expect(metadata["turboismReadmeSchema"], "1", "turboismReadmeSchema", errors)
    expect(metadata["pluginId"], descriptor["id"], "pluginId", errors)
    expect(metadata["version"], descriptor["version"], "version", errors)
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

    expected_role = (
        ("core", "built-in", "bundled") if module.name == "core" else
        ("feature", "preview", "store-candidate")
    )
    actual_role = (metadata["kind"], metadata["status"], metadata["delivery"])
    if actual_role != expected_role:
        errors.append(f"kind/status/delivery={actual_role!r}; expected {expected_role!r}")

    positions: list[int] = []
    for heading in REQUIRED_HEADINGS:
        count = text.count(heading)
        if count != 1:
            errors.append(f"heading {heading!r} occurs {count} times")
        positions.append(text.find(heading))
    if all(position >= 0 for position in positions) and positions != sorted(positions):
        errors.append("required headings are out of order")

    if not re.search(rf"^# {re.escape(descriptor['name'])}$", text, re.MULTILINE):
        errors.append("title does not match descriptor name")
    for placeholder in ("TODO", "TBD", "COMING SOON", "{manifest.", "{plugin"):
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
    if not descriptor["permissions"] and "The manifest declares no permissions." not in text:
        errors.append("empty permission inventory is not stated")
    if not descriptor["capabilities"] and "No capabilities are declared in the plugin manifest." not in text:
        errors.append("empty capability inventory is not stated")

    return errors


def official_modules() -> list[Path]:
    modules: list[Path] = []
    for line in RELEASE_PLUGINS.read_text(encoding="utf-8").splitlines():
        project = line.strip()
        if not project:
            continue
        prefix = ":plugins:"
        if not project.startswith(prefix):
            raise ValueError(f"invalid release plugin project: {project}")
        modules.append(PLUGINS / project.removeprefix(prefix))
    return modules


def main() -> int:
    try:
        modules = official_modules()
    except (OSError, ValueError) as failure:
        print(f"Official plugin README check failed: {failure}", file=sys.stderr)
        return 1
    if not modules:
        print("Official plugin README check failed: release allowlist is empty.", file=sys.stderr)
        return 1
    failures: list[str] = []
    for module in modules:
        if not (module / DESCRIPTOR).is_file():
            failures.append(f"plugins/{module.name}: official descriptor is missing")
            continue
        for error in validate_module(module):
            failures.append(f"plugins/{module.name}/README.md: {error}")
    if failures:
        print("Official plugin README check failed:", file=sys.stderr)
        for failure in failures:
            print(f"- {failure}", file=sys.stderr)
        return 1
    print(f"PASS: {len(modules)} official plugin READMEs match their descriptors")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
