#!/usr/bin/env python3
"""Inventory the full version-validation scope without promoting static evidence to support."""

from __future__ import annotations

import argparse
import hashlib
from pathlib import Path
import re
import sys

_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
from turboism_dev.json_io import read_json, write_json
from turboism_dev.paths import repo_root

ROOT = repo_root()
sys.path.insert(0, str(ROOT / "scripts/preview"))
from host_validation_evidence import HOSTS  # noqa: E402


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def semver(code: str) -> str:
    return f"{code[0]}.{code[1]}.{code[2:]}"


def build_inventory(install_root: Path, root: Path = ROOT) -> dict:
    records = []
    capabilities: dict[str, list[str]] = {}
    for path in sorted((root / "build/generated/verification").glob("*.json")):
        record = read_json(path)
        relative = path.relative_to(root).as_posix()
        records.append({
            "path": relative, "sha256": sha256(path),
            "version": record["cubismVersion"], "artifact": record["artifact"],
            "capabilities": record["capabilityIds"],
        })
        for capability in record["capabilityIds"]:
            capabilities.setdefault(capability, []).append(relative)

    service_source = root / "sdk/src/main/java/dev/turboism/sdk/plugin/PluginService.java"
    services = dict(re.findall(r"^\s+([A-Z][A-Z_]+)\((dev\.[\w.]+)\.class\)", service_source.read_text(), re.M))
    plugins = []
    for path in sorted((root / "plugins").glob("*/src/main/resources/META-INF/turboism/plugin.json")):
        descriptor = read_json(path)
        plugins.append({"id": descriptor["id"], "descriptor": path.relative_to(root).as_posix(), "sha256": sha256(path)})

    tasks = read_json(root / "scripts/preview/host-validation-tasks.json")["tasks"]
    scenarios = []
    for name, task in sorted(tasks.items()):
        for variant in sorted(task.get("variants") or {"default": None}):
            scenarios.append({
                "id": f"{name}:{variant}", "task": name, "variant": variant,
                "wrapper": task["command"], "catalogueVersions": task["versions"],
                "coverageMapping": "PENDING_AUDIT",
            })
    registered = {task["command"] for task in tasks.values()}
    wrappers = sorted(path.relative_to(root).as_posix() for path in (root / "scripts/preview").glob("run-*-host-validation.sh"))
    installed = {path.name for path in install_root.iterdir() if path.is_dir() and (path / "app/lib/Live2D_Cubism.jar").is_file()}
    expected_directories = {entry[0] for entry in HOSTS.values()}
    unexpected = sorted(installed - expected_directories)
    if unexpected:
        raise ValueError(f"Uncatalogued installed editors must be investigated: {unexpected}")
    versions = []
    for code, (directory, expected_sha) in sorted(HOSTS.items()):
        version = semver(code)
        editor = install_root / directory / "app/lib/Live2D_Cubism.jar"
        core = editor.with_name("Live2DCubismCore.jar")
        if not editor.is_file() or not core.is_file():
            raise ValueError(f"Missing installed Editor/Core for {version}")
        actual_sha = sha256(editor)
        if actual_sha != expected_sha:
            raise ValueError(f"Installed Editor fingerprint changed: {version}")
        core_sha = sha256(core)
        observed_core = read_json(root / (
            f"compatibility/cubism/core-api/observed/cubism-core-{version}.json"
        ))["artifact"]
        if core_sha != observed_core["sha256"] or core.stat().st_size != observed_core["sizeBytes"]:
            raise ValueError(f"Installed Core fingerprint changed: {version}")
        matched_records = [r for r in records if r["version"] == version]
        static_capabilities = sorted({c for r in matched_records for c in r["capabilities"]})
        versions.append({
            "version": version, "code": code, "installationDirectory": directory,
            "editor": {"sizeBytes": editor.stat().st_size, "sha256": actual_sha},
            "core": {"sizeBytes": core.stat().st_size, "sha256": core_sha},
            "exactRecordCapabilities": static_capabilities,
            "capabilitiesWithoutExactRecord": sorted(set(capabilities) - set(static_capabilities)),
            "verifiedSupported": [], "verifiedUnsupported": [],
            "runtimeCapabilityAudit": "PENDING",
        })
    return {
        "schemaVersion": 1,
        "baselineRevision": "7d7de1de4",
        "complete": False,
        "scopeAudit": "PENDING_API_AND_SCENARIO_COVERAGE_REVIEW",
        "rules": [
            "Static records and service presence do not prove behavior support.",
            "Absent exact records do not prove unsupported: compatible admission must also be investigated.",
            "Catalogue version lists do not prove wrappers accept those versions.",
            "A successful narrow probe cannot promote unrelated capabilities.",
            "Unsupported requires capability-specific negative evidence, not a timeout or missing fixture.",
            "Final support decisions require exact-host assertions and authoritative supervisor cleanup evidence.",
        ],
        "versions": versions,
        "capabilities": dict(sorted(capabilities.items())),
        "services": services,
        "guaranteedContextMembers": ["descriptor", "logger", "paths", "cubism", "permissions", "eventBus", "actions", "menus", "uiScheduler", "diagnostics", "disposableScope"],
        "plugins": plugins,
        "scenarios": scenarios,
        "wrappersWithoutCatalogueTask": sorted(set(wrappers) - registered),
        "sdkSourceFiles": sorted(path.relative_to(root).as_posix() for path in (root / "sdk/src/main/java").rglob("*.java")),
        "records": records,
    }


def render_markdown(document: dict) -> str:
    lines = [
        "# 全安装版本验证台账（进行中）", "",
        "当前表不是最终支持承诺。静态记录、启动成功、验证工具缺口和能力不支持分别记录。",
        "所有能力仍需与实际断言逐项关联；未验证不得填入支持或不支持。", "",
        f"基线：`{document['baselineRevision']}`；{len(document['versions'])} 个安装版本；"
        f"{len(document['capabilities'])} 个静态能力标识；{len(document['services'])} 个可选 SDK 服务；"
        f"{len(document['plugins'])} 个插件；{len(document['scenarios'])} 个目录测试场景。", "",
        "| 版本 | 精确静态记录能力数 | 无精确记录能力数 | 支持能力集 | 不支持能力集 | 状态 |",
        "| --- | ---: | ---: | --- | --- | --- |",
    ]
    for version in document["versions"]:
        lines.append(f"| {version['version']} | {len(version['exactRecordCapabilities'])} | "
                     f"{len(version['capabilitiesWithoutExactRecord'])} | 待逐项验证 | 待负向证据 | 进行中 |")
    lines += ["", "完整能力、SDK 服务、插件、场景及安装摘要见 [inventory.json](inventory.json)。",
              "窄范围实机历史证据见 [验证记录](README.md)，不能直接折算为全能力通过。", "",
              "## 尚未登记为任务的验证脚本", ""]
    lines += [f"- `{path}`" for path in document["wrappersWithoutCatalogueTask"]]
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--install-root", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=ROOT / "validation/cubism-version-matrix")
    args = parser.parse_args()
    document = build_inventory(args.install_root)
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "inventory.json", document, indent=2, ensure_ascii=False)
    (args.output / "verification-table.md").write_text(render_markdown(document))
    print(f"Inventoried {len(document['versions'])} installed versions; runtime coverage remains pending")


if __name__ == "__main__":
    main()
