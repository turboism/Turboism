#!/usr/bin/env python3
"""Validates README coverage for every first-party plugin module."""

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
README_VARIANTS = {
    "README.md": (
        "## What it does", "## Requirements and compatibility", "## Install and enable",
        "## How to use", "## Capabilities", "## Permissions", "## Privacy and data",
        "## Status and limitations", "## Troubleshooting", "## Changelog", "## Support and license",
    ),
    "README_zh.md": (
        "## 功能概述", "## 要求与兼容性", "## 安装与启用", "## 使用方法", "## 功能能力",
        "## 权限", "## 隐私与数据", "## 状态与限制", "## 故障排除", "## 更新日志", "## 支持与许可证",
    ),
    "README_ja.md": (
        "## 機能概要", "## 要件と互換性", "## インストールと有効化", "## 使い方", "## 機能",
        "## 権限", "## プライバシーとデータ", "## 状態と制限", "## トラブルシューティング", "## 変更履歴",
        "## サポートとライセンス",
    ),
    "README_ko.md": (
        "## 기능 개요", "## 요구 사항 및 호환성", "## 설치 및 활성화", "## 사용 방법", "## 기능",
        "## 권한", "## 개인정보 및 데이터", "## 상태 및 제한 사항", "## 문제 해결", "## 변경 이력",
        "## 지원 및 라이선스",
    ),
}
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


def expect(actual: str, expected: str, field: str, errors: list[str]) -> None:
    if actual != expected:
        errors.append(f"front matter {field}={actual!r}; expected {expected!r}")


def validate_readme(
    module: Path,
    descriptor: dict[str, object],
    filename: str,
    headings: tuple[str, ...],
    expected_role: tuple[str, str, str],
) -> list[str]:
    errors: list[str] = []
    readme = module / filename
    if not readme.is_file():
        return [f"{filename} is missing"]
    try:
        text = readme.read_text(encoding="utf-8", errors="strict")
    except UnicodeError:
        return [f"{filename} is not valid UTF-8"]
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
    for heading in headings:
        pattern = (
            rf"^{re.escape(heading)}(?: minimization)?$"
            if heading in ("## Privacy and data", "## 개인정보 및 데이터")
            else rf"^{re.escape(heading)}$"
        )
        matches = list(re.finditer(pattern, text, re.MULTILINE))
        count = len(matches)
        if count != 1:
            errors.append(f"heading {heading!r} occurs {count} times")
        positions.append(matches[0].start() if matches else -1)
    if all(position >= 0 for position in positions) and positions != sorted(positions):
        errors.append("required headings are out of order")

    if filename == "README.md":
        if not re.search(rf"^# {re.escape(descriptor['name'])}$", text, re.MULTILINE):
            errors.append("title does not match descriptor name")
    elif not re.search(r"^# \S.*$", text, re.MULTILINE):
        errors.append("README title is missing")
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
    if not descriptor["permissions"]:
        empty_permissions = {
            "README.md": r"The manifest declares no permissions\.",
            "README.zh_Hans.md": r"(?:未声明|不声明|没有声明|无).*(?:权限)",
            "README_zh.md": r"(?:未声明|不声明|没有声明|无).*(?:权限)",
            "README_ja.md": r"(?:権限).*(?:宣言されていません|宣言していません|ありません)",
            "README_ko.md": r"(?:권한).*(?:선언되지 않았습니다|선언하지 않았습니다|없습니다)",
        }
        if not re.search(empty_permissions[filename], text):
            errors.append("empty permission inventory is not stated")
    if not descriptor["capabilities"]:
        empty_capabilities = {
            "README.md": r"No capabilities are declared in the plugin manifest\.",
            "README_zh.md": r"(?:未声明|不声明|没有声明|无).*(?:功能能力|能力)",
            "README_ja.md": r"(?:機能|ケイパビリティ).*(?:宣言されていません|宣言していません|ありません)",
            "README_ko.md": r"(?:기능|역량|capability).*(?:선언되지 않았습니다|선언하지 않았습니다|없습니다)",
        }
        if not re.search(empty_capabilities[filename], text):
            errors.append("empty capability inventory is not stated")

    plugin_version = documented_version
    changelog_heading = {
        "README.md": "## Changelog",
        "README_zh.md": "## 更新日志",
        "README_ja.md": "## 変更履歴",
        "README_ko.md": "## 변경 이력",
    }.get(filename)
    if changelog_heading and changelog_heading in text:
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
    variants = README_VARIANTS if module.name in store_candidates else {"README.md": README_VARIANTS["README.md"]}
    return [
        (filename, error)
        for filename, headings in variants.items()
        for error in validate_readme(module, descriptor, filename, headings, expected_role)
    ]


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
    print(f"PASS: {len(modules)} first-party plugins have valid localized README contracts")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
