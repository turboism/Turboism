#!/usr/bin/env python3
"""Verify host-validation task versions and wrapper single-source-of-truth gates.

Enforces two invariants across exact-host validation tooling:

1. Manifest derivation: every task's ``versions`` in
   ``scripts/preview/host-validation-tasks.json`` must equal the intersection
   of admitted versions in ``compatibility/cubism/compatibility-matrix.json``
   for the capabilities and optimization class-pins the task depends on (or a
   sanctioned site in ``scripts/version-set-registry.json``).
2. Wrapper single source of truth: ``scripts/preview/run-*.sh`` task wrappers
   must not embed literal Cubism version enumerations unless registered in
   ``scripts/version-set-registry.json`` with an audit basis and reason.

Usage:
    check_host_validation_versions.py [repo-root]
    check_host_validation_versions.py --update [repo-root]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

from turboism_dev.json_io import dump_json, read_json
from turboism_dev.paths import repo_root
from turboism_dev.versions import dotted_to_compact

sys.path.insert(0, str(Path(__file__).resolve().parent / "preview"))
import check_version_monotonicity as monotonicity  # noqa: E402
import check_version_set_registry as registry  # noqa: E402
from host_validation_static_gates import TASK_REQUIRED_CAPABILITIES  # noqa: E402

ROOT = repo_root()
MATRIX_PATH = Path("compatibility/cubism/compatibility-matrix.json")
MANIFEST_PATH = Path("scripts/preview/host-validation-tasks.json")

# Task -> explicit capability dependencies when taskGroupAliases/taskPluginAliases
# either do not cover the task or name a broader documentation group.
TASK_EXPLICIT_CAPABILITIES: dict[str, tuple[str, ...]] = {
    "atlas-image": ("cubism.texture-atlas.data-model-hook",),
    "backup-interactive": (
        "cubism.autobackup.backup",
        "cubism.autobackup.settings",
    ),
    "bounding-box-overlay": (
        "cubism.editor-ui.bounding-box-overlay-button",
        "cubism.editor-model.read",
    ),
    "boundingbox-warp-mirror": (
        "cubism.editor-model.warp-mirror",
        "cubism.editor-ui.bounding-box-overlay-button",
    ),
    "dialog-automation": ("cubism.editor-model.read",),
    "edit-protocol": (
        "cubism.integration.edit-api-bridge.dispatch",
        "cubism.integration.external-app-settings.edit-toggle",
    ),
    "external-psd-edit": (
        "cubism.editor-model.psd.raw-image.export",
        "cubism.editor-model.psd.raw-image.replace",
        "cubism.editor-model.psd.snapshot.read",
    ),
    "external-psd-fixture-preparation": (
        "cubism.editor-model.psd.raw-image.export",
        "cubism.editor-model.psd.raw-image.replace",
        "cubism.editor-model.psd.snapshot.read",
    ),
    "graal-script": (
        "cubism.editor-model.read",
        "cubism.editor-model.write",
    ),
    "host-locale": ("cubism.editor-model.read",),
    "mcp": (
        "cubism.editor-model.read",
        "cubism.editor-model.write",
        "cubism.editor-history.read",
        "cubism.editor-history.move",
        "cubism.workspace.control",
    ),
    "mesh-edit": ("cubism.mesh-tool.session",),
    "model-import-3d": (
        "cubism.editor-model.object-hierarchy.art-mesh.create",
        "cubism.editor-model.art-mesh.write",
        "cubism.editor-model.art-mesh.parameter-bindings.write",
        "cubism.editor-model.texture.register-bind",
        "cubism.editor-model.render.read",
    ),
    "model-import-3d-user-entry": (
        "cubism.editor-model.object-hierarchy.art-mesh.create",
        "cubism.editor-model.art-mesh.write",
    ),
    "official-core": ("cubism.core.owned-moc.read",),
    "official-viewer": ("cubism.core.owned-moc.read",),
    "plugin-management-chooser": ("cubism.editor-model.read",),
    "resource": ("turboism.performance.stats.read",),
    "semantic-history": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "semantic-history-native-ui": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "semantic-history-primary": (
        "cubism.editor-history.move",
        "cubism.editor-history.read",
        "cubism.editor-history.semantic-read",
    ),
    "separate-save-path": ("cubism.project.read",),
    "session-standard": (
        "cubism.editor-model.texture.read",
        "cubism.editor-model.psd.raw-image.export",
        "ui.status.notify",
        "cubism.editor-model.art-mesh-clip-mask.read",
        "cubism.project.read",
        "cubism.core.owned-moc.read",
    ),
    "settings-page": ("ui.status.notify",),
    "theme": (
        "cubism.editor-model.native-control-appearance.read",
        "cubism.editor-model.native-control-appearance.write",
    ),
    "update-check": (
        "cubism.startup.suppression",
        "ui.canvas.hint",
    ),
    "warp-deformer-alt-symmetry": ("cubism.editor-model.warp-mirror",),
}

# Task -> optimization/hook class-pin table in compatibility-matrix.json.
TASK_CLASS_PINS: dict[str, tuple[str, ...]] = {
    "boundingbox-warp-mirror": ("warp-alt-mirror",),
    "float-array": ("float-array-parse",),
    "image-archive": ("image-archive-reuse",),
    "incremental-update": ("incremental-update",),
    "mesh-edit": ("mesh-mirror",),
    "model-import-3d-user-entry": ("mesh-interpolation",),
    "model-update-skip": ("model-update-skip",),
    "startup-suppression": ("startup-suppression",),
    "texture-upload": ("texture-upload-preparation",),
    "warp-deformer-alt-symmetry": ("warp-alt-mirror",),
    "warp-position": ("warp-position-projection",),
}


def derive_all_task_versions(
    root: Path = ROOT,
) -> tuple[dict[str, list[str]], list[str]]:
    """Return ({task_name: [compact_versions, ...]}, problems)."""
    problems: list[str] = []
    matrix_file = root / MATRIX_PATH
    manifest_file = root / MANIFEST_PATH
    try:
        matrix = read_json(matrix_file)
    except (OSError, Exception) as exc:
        return {}, [f"{MATRIX_PATH}: cannot read compatibility matrix: {exc}"]
    try:
        manifest = read_json(manifest_file)
    except (OSError, Exception) as exc:
        return {}, [f"{MANIFEST_PATH}: cannot read task manifest: {exc}"]

    reviewed: list[str] = list(matrix.get("reviewedVersions", []))
    reviewed_set = set(reviewed)
    cap_admitted: dict[str, set[str]] = {
        row["id"]: set(row.get("admitted", []))
        for row in matrix.get("capabilities", [])
    }
    group_caps: dict[str, list[str]] = {
        row["id"]: list(row.get("capabilities", []))
        for row in matrix.get("capabilityGroups", [])
    }
    plugin_supported: dict[str, set[str]] = {
        row["directory"]: set(row.get("supportedVersions", []))
        for row in matrix.get("plugins", [])
    }
    pin_versions: dict[str, set[str]] = {
        row["name"]: set(row.get("versions", []))
        for row in matrix.get("classPins", [])
    }
    task_plugin_aliases: dict[str, str] = dict(
        matrix.get("taskPluginAliases", {})
    )
    task_group_aliases: dict[str, str] = dict(
        matrix.get("taskGroupAliases", {})
    )

    derived: dict[str, list[str]] = {}
    for task_name in sorted(manifest.get("tasks", {})):
        admitted = set(reviewed_set)
        matched_source = False

        for cap in TASK_REQUIRED_CAPABILITIES.get(task_name, ()):
            if cap not in cap_admitted:
                problems.append(
                    f"task {task_name}: required capability {cap!r} missing from {MATRIX_PATH}"
                )
            else:
                admitted &= cap_admitted[cap]
                matched_source = True

        if task_name in TASK_EXPLICIT_CAPABILITIES:
            for cap in TASK_EXPLICIT_CAPABILITIES[task_name]:
                if cap not in cap_admitted:
                    problems.append(
                        f"task {task_name}: capability {cap!r} missing from {MATRIX_PATH}"
                    )
                else:
                    admitted &= cap_admitted[cap]
                    matched_source = True
        elif task_name in task_group_aliases:
            group = task_group_aliases[task_name]
            caps = group_caps.get(group, [])
            if not caps:
                problems.append(
                    f"task {task_name}: group {group!r} has no capabilities in {MATRIX_PATH}"
                )
            for cap in caps:
                admitted &= cap_admitted.get(cap, set())
                matched_source = True

        if task_name in task_plugin_aliases:
            plugin_dir = task_plugin_aliases[task_name]
            if plugin_dir not in plugin_supported:
                problems.append(
                    f"task {task_name}: plugin {plugin_dir!r} missing from {MATRIX_PATH}"
                )
            else:
                admitted &= plugin_supported[plugin_dir]
                matched_source = True

        for pin_name in TASK_CLASS_PINS.get(task_name, ()):
            if pin_name not in pin_versions:
                problems.append(
                    f"task {task_name}: classPin {pin_name!r} missing from {MATRIX_PATH}"
                )
            else:
                admitted &= pin_versions[pin_name]
                matched_source = True

        if not matched_source:
            problems.append(
                f"task {task_name}: no capability, plugin, or classPin mapping declared"
            )

        ordered_dotted = sorted(admitted, key=monotonicity.parse_dotted)
        derived[task_name] = [dotted_to_compact(v) for v in ordered_dotted]

    return derived, problems


def derive_task_versions(task_name: str, root: Path = ROOT) -> tuple[str, ...]:
    """Return the derived compact version tuple for ``task_name``."""
    derived, problems = derive_all_task_versions(root)
    if problems:
        raise RuntimeError("; ".join(problems))
    return tuple(derived[task_name])


def check(root: Path = ROOT) -> list[str]:
    failures: list[str] = []
    derived, problems = derive_all_task_versions(root)
    failures.extend(problems)

    manifest_file = root / MANIFEST_PATH
    try:
        manifest = read_json(manifest_file)
    except (OSError, Exception) as exc:
        return [f"{MANIFEST_PATH}: cannot read task manifest: {exc}"]

    registry_entries: dict[tuple[str, str], dict] = {}
    if (root / registry.REGISTRY).is_file():
        entries, reg_problems = registry.load_registry(root)
        failures.extend(reg_problems)
        for entry in entries:
            registry_entries[(entry["kind"], entry["path"])] = entry

    for task_name, task_data in sorted(manifest.get("tasks", {}).items()):
        actual = list(task_data.get("versions", []))
        expected = derived.get(task_name, [])
        if actual != expected:
            reg_key = ("file", f"{MANIFEST_PATH.as_posix()}#{task_name}")
            reg_entry = registry_entries.get(reg_key)
            expected_dotted = [
                monotonicity.compact_to_dotted(v) for v in actual
            ]
            if (
                reg_entry is None
                or reg_entry.get("versions") != expected_dotted
            ):
                failures.append(
                    f"{MANIFEST_PATH}: task {task_name!r} versions {actual} != "
                    f"derived admitted versions {expected}"
                )

    admitted = monotonicity.admitted_versions(root)
    preview_dir = root / registry.PREVIEW_WRAPPERS_DIR
    if preview_dir.is_dir():
        for wrapper in sorted(preview_dir.glob("run-*.sh")):
            if wrapper.name == registry.GENERIC_RUNNER_NAME:
                continue
            relative = wrapper.relative_to(root).as_posix()
            code_lines = [
                line
                for line in wrapper.read_text(encoding="utf-8").splitlines()
                if not line.lstrip().startswith("#")
            ]
            code = "\n".join(code_lines)
            found: set[str] = set()
            for token in registry.SHELL_COMPACT_VERSION.findall(code):
                dotted = monotonicity.compact_to_dotted(token)
                if dotted in admitted:
                    found.add(dotted)
            for dotted in registry.SHELL_DOTTED_VERSION.findall(code):
                if dotted in admitted:
                    found.add(dotted)
            if found:
                sorted_found = registry.sort_versions(found)
                reg_entry = registry_entries.get(("file", relative))
                if (
                    reg_entry is None
                    or reg_entry.get("versions") != sorted_found
                ):
                    failures.append(
                        f"unregistered wrapper version enumeration at {relative}: "
                        f"{','.join(sorted_found)}; use host-validation-env.sh "
                        f"manifest helpers or register an evidence-backed site in "
                        f"{registry.REGISTRY}"
                    )

    return failures


def update(root: Path = ROOT) -> int:
    derived, problems = derive_all_task_versions(root)
    if problems:
        for problem in problems:
            print(f"FAIL: {problem}", file=sys.stderr)
        return 2
    manifest_file = root / MANIFEST_PATH
    manifest = read_json(manifest_file)
    for task_name, task_data in manifest.get("tasks", {}).items():
        if task_name in derived:
            task_data["versions"] = derived[task_name]
    manifest_file.write_text(
        dump_json(manifest, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    print(f"updated {MANIFEST_PATH} with {len(derived)} derived task(s)")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("root", nargs="?", default=str(ROOT))
    parser.add_argument("--update", action="store_true")
    args = parser.parse_args(argv)
    root = Path(args.root).resolve()
    if args.update:
        return update(root)
    failures = check(root)
    for failure in failures:
        print(f"FAIL: {failure}")
    if failures:
        print(
            f"\n{len(failures)} host-validation version finding(s)",
            file=sys.stderr,
        )
        return 1
    print(
        "PASS: manifest task versions match derived capability admission and "
        "wrappers contain no unregistered version enumerations"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
