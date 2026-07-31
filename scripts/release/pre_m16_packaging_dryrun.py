#!/usr/bin/env python3
"""Worktree-scoped pre-M16 packaging and safe-mode dry-run (schema v1)."""
from __future__ import annotations

import argparse
import csv
import hashlib
import importlib.util
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
from typing import Any

FORMAT = "turboism.pre-m16-packaging-dryrun-manifest"
TRACE_FORMAT = "turboism.pre-m16-packaging-dryrun-trace"
EXPECTED_PROJECTS = (
    ":plugins", ":plugins:clip-mask", ":plugins:demo", ":plugins:log-filter",
    ":plugins:core", ":plugins:mesh", ":plugins:parameter", ":plugins:perf-opt",
    ":plugins:render-opt", ":plugins:ui-theme", ":runtime", ":sdk", ":testframework", ":tests",
)
EXPECTED_LIB_DIRS = {project: ("plugins" if project == ":plugins" else project.rsplit(":", 1)[-1]) for project in EXPECTED_PROJECTS}
MANIFEST_FIELDS = {
    "format", "schemaVersion", "worktreeId", "generatedAt", "artifacts", "forbiddenEntries",
    "launcherPlanPath", "installPlanPath", "rollbackPlanPath",
}
ARTIFACT_FIELDS = {"path", "sha256", "size"}
CANONICAL_FORBIDDEN_ENTRIES = (
    ".codegraph/", "AGENTS.md", "cubism-ref/", "*latest*.jar", "Live2D_Cubism.jar",
)
FORBIDDEN_SUFFIXES = {".exe", ".dll", ".so", ".dylib"}
TEXT_SCAN_SUFFIXES = {".json", ".md", ".txt", ".properties", ".xml", ".yml", ".yaml"}
PROPRIETARY_MARKERS = (b"com/live2d/", b"Live2D_Cubism", b"cubism-ref/", b"turboism-legacy/")
WORKTREE_RE = re.compile(r"[a-z][a-z0-9-]{2,63}")
SHA256_RE = re.compile(r"[0-9a-f]{64}")
MAX_NESTED_DEPTH = 3
MAX_ARCHIVE_BYTES = 64 * 1024 * 1024
MAX_ENTRY_BYTES = 16 * 1024 * 1024
MAX_COMPRESSION_RATIO = 200


class DryRunError(RuntimeError):
    pass


def canonical_relative(value: str) -> bool:
    pure = PurePosixPath(value)
    return bool(value) and "\\" not in value and not pure.is_absolute() and value == pure.as_posix() and all(
        part not in {"", ".", ".."} for part in value.split("/")
    )


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bytes_digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def git(repo: Path, *args: str, check: bool = True) -> str:
    result = subprocess.run(["git", "-C", str(repo), *args], text=True, capture_output=True)
    if check and result.returncode:
        raise DryRunError(result.stderr.strip() or f"git {' '.join(args)} failed")
    return result.stdout.strip()


def source_provenance(repo: Path) -> dict[str, Any]:
    commit = git(repo, "rev-parse", "HEAD")
    tracked = git(repo, "status", "--porcelain=v1", "--untracked-files=no")
    untracked = [line[3:] for line in git(repo, "status", "--porcelain=v1", "--untracked-files=all").splitlines() if line.startswith("?? ")]
    source_roots = {"runtime", "sdk", "plugins", "testframework", "tests", "gradle"}
    source_files = {"build.gradle.kts", "settings.gradle.kts", "gradlew", "gradlew.bat"}
    source_untracked = sorted(path for path in untracked if path.split("/", 1)[0] in source_roots or path in source_files)
    return {"sourceCommit": commit, "trackedSourceClean": not bool(tracked), "untrackedSourcePaths": source_untracked}


def _reject_symlink_chain(repo: Path, target: Path) -> None:
    repo = repo.absolute()
    target = target.absolute()
    try:
        target.relative_to(repo)
    except ValueError as exc:
        raise DryRunError("target must be inside repository") from exc
    current = repo
    if current.is_symlink():
        raise DryRunError(f"symlink in protected path chain: {current}")
    for part in target.relative_to(repo).parts:
        current = current / part
        if current.is_symlink():
            raise DryRunError(f"symlink in protected path chain: {current}")
    resolved_repo = repo.resolve()
    resolved_target = target.resolve(strict=False)
    try:
        resolved_target.relative_to(resolved_repo)
    except ValueError as exc:
        raise DryRunError("resolved target escapes repository") from exc


def clean_scoped_output(repo: Path, worktree_id: str) -> Path:
    if not WORKTREE_RE.fullmatch(worktree_id):
        raise DryRunError("refusing unsafe scoped cleanup path")
    target = repo / "build" / "worktree" / worktree_id
    _reject_symlink_chain(repo, target)
    if target.exists():
        if not target.is_dir():
            raise DryRunError("cleanup target must be a directory")
        shutil.rmtree(target)
    target.mkdir(parents=True)
    _reject_symlink_chain(repo, target)
    return target


def build_commands(repo: Path, worktree_id: str) -> list[list[str]]:
    return [[str(repo / "gradlew"), *(f"{project}:jar" for project in EXPECTED_PROJECTS), f"-PturboismWorktreeId={worktree_id}", "--no-daemon"]]


def safe_mode_cases(repo: Path) -> list[dict[str, Any]]:
    gradle = str(repo / "gradlew")
    selector = lambda value: [gradle, ":runtime:test", "--tests", value, "--no-daemon"]
    return [
        {"caseId": "unsupported-version", "reason": "unsupported host versions fail closed", "command": selector("dev.turboism.adapter.ui.UiSurfaceAdapterContractTest.unsupportedVersionAndHostFailuresFailClosed")},
        {"caseId": "missing-stale-evidence", "reason": "authoritative Phase 4 mutation regressions reject missing or stale evidence", "command": [str(repo / "scripts/test/test_phase4_build_gates.sh")]},
        {"caseId": "adapter-unavailable", "reason": "connector rejects unavailable or foreign verified adapter evidence", "command": selector("dev.turboism.adapter.host.VerifiedHostAdapterConnectorTest.rejectsClipMaskEvidenceFromAnotherArtifact")},
        {"caseId": "hash-mismatch", "reason": "pinned resolver rejects artifact digest and size mismatch", "command": selector("dev.turboism.mapping.verification.PinnedVerifiedResolverWorkflowTest.rejectsArtifactDigestAndSizeMismatch")},
        {"caseId": "selector-mismatch", "reason": "pinned resolver rejects explicit capability and alias mismatch", "command": selector("dev.turboism.mapping.verification.PinnedVerifiedResolverWorkflowTest.rejectsCapabilityAndAliasMismatch")},
        {"caseId": "partial-slice-failure", "reason": "host-session composition fails closed for partial slice publication", "command": selector("dev.turboism.adapter.host.HostSessionPluginContextIntegrationTest.failedDualReplacementMakesSameContextSafeAndKeepsFailureSanitized")},
        {"caseId": "explicit-safe-mode", "reason": "typed read adapters expose explicit safe-mode behavior", "command": selector("dev.turboism.adapter.cubism.M14SimulatedReadonlyAdaptersContractTest.projectWorkspaceSafeModeIsUnavailable")},
    ]


def safe_mode_commands(repo: Path) -> list[list[str]]:
    return [case["command"] for case in safe_mode_cases(repo)]


def run_command(command: list[str], repo: Path) -> dict[str, Any]:
    started = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    monotonic = time.monotonic()
    result = subprocess.run(command, cwd=repo, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    ended = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    return {
        "cwd": str(repo.resolve()), "command": command, "exitCode": result.returncode,
        "startedAt": started, "endedAt": ended, "durationMillis": round((time.monotonic() - monotonic) * 1000),
        "stdoutSha256": bytes_digest(result.stdout), "stderrSha256": bytes_digest(result.stderr),
    }


def find_forbidden_outputs(repo: Path, scoped_root: Path, worktree_id: str) -> list[str]:
    findings: set[str] = set()
    scoped = scoped_root.resolve()
    for path in repo.rglob("*"):
        if any(part in {".git", ".gradle"} for part in path.relative_to(repo).parts):
            continue
        if path.suffix.lower() != ".jar":
            continue
        rel = path.relative_to(repo).as_posix()
        # Every JAR symlink, including a broken one, is a hard violation regardless of location.
        if path.is_symlink():
            findings.add(rel)
            continue
        if not path.is_file():
            continue
        lower_parts = [part.lower() for part in path.relative_to(repo).parts]
        if "latest" in path.name.lower():
            findings.add(rel)
        if lower_parts[:2] == ["plugins", path.name.lower()] or rel.startswith("plugins/") and len(path.relative_to(repo).parts) == 2:
            findings.add(rel)
        if len(lower_parts) >= 2 and lower_parts[-2:] == ["libs", path.name.lower()] and rel.startswith("build/libs/"):
            findings.add(rel)
        try:
            path.resolve().relative_to(scoped)
        except ValueError:
            if rel.startswith("build/"):
                findings.add(rel)
    return sorted(findings)


def expected_artifact_paths(repo: Path, scoped_root: Path, worktree_id: str) -> dict[str, Path]:
    expected: dict[str, Path] = {}
    for project, directory in EXPECTED_LIB_DIRS.items():
        libs = scoped_root / directory / "libs"
        if libs.is_symlink() or not libs.is_dir():
            raise DryRunError(f"{project}: canonical libs directory missing or symlinked")
        jars = [p for p in libs.iterdir() if p.is_file() and not p.is_symlink() and p.suffix.lower() == ".jar"]
        scoped = [jar for jar in jars if jar.name.endswith(f"-{worktree_id}.jar")]
        if len(scoped) != 1:
            raise DryRunError(f"{project}: expected exactly one canonical regular worktree-scoped jar, found {len(scoped)}")
        expected[project] = scoped[0]
    return expected


def recursive_scoped_jars(scoped_root: Path) -> set[Path]:
    jar_paths = {path for path in scoped_root.rglob("*") if path.suffix.lower() == ".jar"}
    symlinks = sorted(path.as_posix() for path in jar_paths if path.is_symlink())
    if symlinks:
        raise DryRunError(f"scoped jar symlink violation: {symlinks}")
    return {path for path in jar_paths if path.is_file()}


def artifact_inventory(repo: Path, scoped_root: Path, worktree_id: str) -> list[dict[str, Any]]:
    expected = expected_artifact_paths(repo, scoped_root, worktree_id)
    all_jars = recursive_scoped_jars(scoped_root)
    if all_jars != set(expected.values()):
        extras = sorted(path.relative_to(repo).as_posix() for path in all_jars - set(expected.values()))
        missing = sorted(path.relative_to(repo).as_posix() for path in set(expected.values()) - all_jars)
        raise DryRunError(f"artifact set is not exact; extras={extras}; missing={missing}")
    return [{"path": p.relative_to(repo).as_posix(), "sha256": sha256(p), "size": p.stat().st_size} for p in sorted(expected.values())]


def _catalog_match(name: str, catalog: tuple[str, ...]) -> bool:
    lower = name.lower()
    base = PurePosixPath(name).name.lower()
    for rule in catalog:
        rule_lower = rule.lower()
        if rule_lower.startswith("*") and rule_lower.endswith("*") and rule_lower.strip("*") in base:
            return True
        if rule_lower.endswith("/") and lower.startswith(rule_lower):
            return True
        if base == PurePosixPath(rule_lower).name or lower == rule_lower:
            return True
    return False


def _inspect_archive(archive: zipfile.ZipFile, label: str, catalog: tuple[str, ...], depth: int) -> list[dict[str, str]]:
    findings: list[dict[str, str]] = []
    seen: set[str] = set()
    for info in archive.infolist():
        name = info.filename
        display = f"{label}!/{name}" if label else name
        if name in seen:
            findings.append({"kind": "structural", "entry": display, "reason": "duplicate zip entry"})
        seen.add(name)
        if not canonical_relative(name.rstrip("/")):
            findings.append({"kind": "structural", "entry": display, "reason": "non-canonical zip entry"})
        if _catalog_match(name, catalog):
            findings.append({"kind": "name", "entry": display, "reason": "forbidden catalog match"})
        suffix = PurePosixPath(name).suffix.lower()
        compressed = max(info.compress_size, 1)
        if info.file_size > MAX_ENTRY_BYTES or info.file_size / compressed > MAX_COMPRESSION_RATIO:
            findings.append({"kind": "structural", "entry": display, "reason": "archive size/compression limit exceeded"})
            continue
        if info.is_dir():
            continue
        lower_name = name.lower()
        if suffix == ".class" and (lower_name.startswith("com/live2d/") or "/com/live2d/" in lower_name):
            findings.append({"kind": "name", "entry": display, "reason": "forbidden com/live2d class path"})
        elif suffix in FORBIDDEN_SUFFIXES:
            findings.append({"kind": "name", "entry": display, "reason": "forbidden native executable/library suffix"})
        if suffix in TEXT_SCAN_SUFFIXES and info.file_size <= 1024 * 1024:
            data = archive.read(info)
            for marker in PROPRIETARY_MARKERS:
                if marker.lower() in data.lower():
                    findings.append({"kind": "text-limited", "entry": display, "reason": marker.decode("ascii")})
        if suffix == ".jar":
            if depth >= MAX_NESTED_DEPTH:
                findings.append({"kind": "structural", "entry": display, "reason": "nested jar depth limit exceeded"})
                continue
            if info.file_size > MAX_ARCHIVE_BYTES:
                findings.append({"kind": "structural", "entry": display, "reason": "nested jar size limit exceeded"})
                continue
            try:
                import io
                with zipfile.ZipFile(io.BytesIO(archive.read(info))) as nested:
                    findings.extend(_inspect_archive(nested, display, catalog, depth + 1))
            except (OSError, zipfile.BadZipFile) as exc:
                findings.append({"kind": "structural", "entry": display, "reason": f"invalid nested jar: {exc}"})
    return findings


def inspect_zip(path: Path, forbidden_entries: list[str] | tuple[str, ...] = CANONICAL_FORBIDDEN_ENTRIES) -> list[dict[str, str]]:
    catalog = tuple(forbidden_entries)
    try:
        if path.stat().st_size > MAX_ARCHIVE_BYTES:
            return [{"kind": "structural", "entry": path.name, "reason": "archive size limit exceeded"}]
        with zipfile.ZipFile(path) as archive:
            return _inspect_archive(archive, "", catalog, 0)
    except (OSError, zipfile.BadZipFile) as exc:
        return [{"kind": "structural", "entry": path.name, "reason": f"invalid zip: {exc}"}]


def canonical_json_bytes(document: Any) -> bytes:
    return (json.dumps(document, indent=2, sort_keys=True) + "\n").encode("utf-8")


def write_json(path: Path, document: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(canonical_json_bytes(document))


def canonical_dryrun_paths(repo: Path, worktree_id: str) -> dict[str, str]:
    prefix = f"build/worktree/{worktree_id}/dryrun"
    return {
        "launcherPlanPath": f"{prefix}/launcher-plan.json", "installPlanPath": f"{prefix}/install-plan.json",
        "rollbackPlanPath": f"{prefix}/rollback-plan.json",
    }


def plan_documents(repo: Path, scoped_root: Path, worktree_id: str, artifacts: list[dict[str, Any]]) -> dict[str, str]:
    paths = canonical_dryrun_paths(repo, worktree_id)
    artifact_paths = [item["path"] for item in artifacts]
    target = f"build/worktree/{worktree_id}/dryrun/sandbox"
    write_json(repo / paths["launcherPlanPath"], {"mode": "plan-only", "writes": [], "artifacts": artifact_paths})
    write_json(repo / paths["installPlanPath"], {"mode": "sandbox-only", "target": target, "artifacts": artifact_paths})
    write_json(repo / paths["rollbackPlanPath"], {"mode": "sandbox-only", "target": target, "idempotent": True})
    return paths


def _safe_repo_regular_file(repo: Path, relative: str) -> Path | None:
    if not canonical_relative(relative):
        return None
    path = repo / relative
    try:
        path.resolve().relative_to(repo.resolve())
    except ValueError:
        return None
    if path.is_symlink() or not path.is_file():
        return None
    return path


def validate_plan_binding(repo: Path, manifest: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    worktree_id = manifest.get("worktreeId")
    if not isinstance(worktree_id, str):
        return ["plan binding requires worktreeId"]
    expected_paths = canonical_dryrun_paths(repo, worktree_id)
    for field, expected_path in expected_paths.items():
        if manifest.get(field) != expected_path:
            errors.append(f"{field} must be exact canonical dry-run path")
    documents: dict[str, Any] = {}
    for field in expected_paths:
        path = _safe_repo_regular_file(repo, manifest.get(field, ""))
        if path is None:
            errors.append(f"{field} missing, outside repository, or symlinked")
            continue
        try:
            documents[field] = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError) as exc:
            errors.append(f"invalid {field}: {exc}")
    if len(documents) != 3:
        return errors
    expected = [item["path"] for item in manifest["artifacts"]]
    launcher, install, rollback = (documents[field] for field in ("launcherPlanPath", "installPlanPath", "rollbackPlanPath"))
    target = f"build/worktree/{worktree_id}/dryrun/sandbox"
    if launcher != {"mode": "plan-only", "writes": [], "artifacts": expected}:
        errors.append("launcher plan must be exact, write-free, and artifact-bound")
    if install != {"mode": "sandbox-only", "target": target, "artifacts": expected}:
        errors.append("install plan must bind exact canonical sandbox and artifacts")
    if rollback != {"mode": "sandbox-only", "target": target, "idempotent": True}:
        errors.append("rollback plan must bind exact canonical sandbox")
    sandbox = repo / target
    try:
        _reject_symlink_chain(repo, sandbox)
    except DryRunError as exc:
        errors.append(str(exc))
    return errors


def simulate_install_rollback(repo: Path, manifest: dict[str, Any]) -> list[dict[str, Any]]:
    errors = validate_plan_binding(repo, manifest)
    if errors:
        raise DryRunError("unsafe sandbox plan: " + "; ".join(errors))
    target_rel = f"build/worktree/{manifest['worktreeId']}/dryrun/sandbox"
    target = repo / target_rel
    trace: list[dict[str, Any]] = []
    for cycle in (1, 2):
        _reject_symlink_chain(repo, target)
        target.mkdir(parents=True, exist_ok=False)
        for item in manifest["artifacts"]:
            source = _safe_repo_regular_file(repo, item["path"])
            if source is None:
                raise DryRunError("sandbox source is missing, outside, or symlinked")
            shutil.copy2(source, target / source.name)
        trace.append({"cycle": cycle, "action": "install", "count": len(list(target.glob("*.jar"))), "success": True})
        shutil.rmtree(target)
        absent = not target.exists()
        # Exercise rollback again: an already-absent sandbox must remain absent.
        if target.exists():
            shutil.rmtree(target)
        idempotent = not target.exists()
        trace.append({"cycle": cycle, "action": "rollback", "absent": absent, "idempotent": idempotent, "success": absent and idempotent})
    return trace


def manifest_errors(document: Any, repo: Path | None = None) -> list[str]:
    if not isinstance(document, dict):
        return ["manifest must be an object"]
    errors: list[str] = []
    if set(document) != MANIFEST_FIELDS:
        errors.append("manifest must have the strict v1 field set")
    if document.get("format") != FORMAT or document.get("schemaVersion") != 1 or isinstance(document.get("schemaVersion"), bool):
        errors.append("invalid format/schemaVersion")
    worktree_id = document.get("worktreeId")
    if not isinstance(worktree_id, str) or not WORKTREE_RE.fullmatch(worktree_id):
        errors.append("invalid worktreeId")
    if document.get("forbiddenEntries") != list(CANONICAL_FORBIDDEN_ENTRIES):
        errors.append("forbiddenEntries must be the exact authoritative catalog")
    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != len(EXPECTED_PROJECTS):
        errors.append("artifacts must be the exact 14-subproject set")
        artifacts = []
    seen: set[str] = set()
    for item in artifacts:
        if not isinstance(item, dict) or set(item) != ARTIFACT_FIELDS:
            errors.append("artifact must have strict path/sha256/size fields"); continue
        path = item.get("path")
        if not isinstance(path, str) or not canonical_relative(path) or not path.startswith(f"build/worktree/{worktree_id}/") or not path.endswith(f"-{worktree_id}.jar"):
            errors.append(f"unscoped artifact: {path}")
        if path in seen:
            errors.append(f"duplicate artifact: {path}")
        seen.add(path)
        if not isinstance(item.get("sha256"), str) or not SHA256_RE.fullmatch(item["sha256"]) or not isinstance(item.get("size"), int) or isinstance(item.get("size"), bool) or item["size"] <= 0:
            errors.append(f"invalid artifact digest/size: {path}")
        if repo is not None and isinstance(path, str):
            actual = _safe_repo_regular_file(repo, path)
            if actual is None:
                errors.append(f"missing/non-regular/symlink artifact: {path}")
            elif item.get("sha256") != sha256(actual) or item.get("size") != actual.stat().st_size:
                errors.append(f"hash/size mismatch: {path}")
    if repo is not None and isinstance(worktree_id, str) and WORKTREE_RE.fullmatch(worktree_id):
        scoped = repo / "build" / "worktree" / worktree_id
        try:
            actual_paths = {item["path"] for item in artifact_inventory(repo, scoped, worktree_id)}
            if seen != actual_paths:
                errors.append("manifest artifact paths do not equal recursive scoped jar set")
        except DryRunError as exc:
            errors.append(str(exc))
        forbidden = find_forbidden_outputs(repo, scoped, worktree_id)
        if forbidden:
            errors.append(f"unscoped/latest/root artifacts found: {forbidden}")
    expected_plans = canonical_dryrun_paths(repo or Path("."), worktree_id) if isinstance(worktree_id, str) else {}
    for field in ("launcherPlanPath", "installPlanPath", "rollbackPlanPath"):
        if document.get(field) != expected_plans.get(field):
            errors.append(f"invalid canonical {field}")
    return errors


def phase5_ledger_transition(rows: list[dict[str, str]], evidence_refs: list[str]) -> list[dict[str, str]]:
    result = [dict(row) for row in rows]
    by_id = {row["workId"]: row for row in result}
    if "automation.phase5.packaging-dryrun" not in by_id or "tranche.automation.overall" not in by_id:
        raise DryRunError("ledger lacks Phase 5 required rows")
    refs = list(dict.fromkeys(evidence_refs))
    if any(ref.startswith("build/") for ref in refs):
        raise DryRunError("ledger evidenceRefs must be tracked report/scripts, never build evidence")
    by_id["automation.phase5.packaging-dryrun"].update(
        workStatus="COMPLETE", evidenceLevel="VERIFIED_STATIC_FAKE", readinessCeiling="DRY_RUN_READY",
        blockers="real-host and manual install/rollback validation", evidenceRefs=";".join(refs),
    )
    tranche = by_id["tranche.automation.overall"]
    report_refs = [ref for ref in refs if ref.startswith("docs/") and ref.endswith(".md")]
    tranche_refs = list(dict.fromkeys(values(tranche.get("evidenceRefs", "")) + report_refs))
    tranche.update(readinessCeiling="DRY_RUN_READY", nextSlice="automation.phase6.closure", evidenceRefs=";".join(tranche_refs))
    return result


def values(cell: str) -> list[str]:
    return [value.strip() for value in cell.split(";") if value.strip()]


def render_report(manifest_ref: str, trace_ref: str, manifest: dict[str, Any], trace: dict[str, Any]) -> str:
    artifacts = "\n".join(
        f"| `{item['path']}` | {item['size']} | `{item['sha256']}` |" for item in manifest["artifacts"]
    )
    soft = [finding for scan in trace["scans"] for finding in scan["findings"] if finding["kind"] not in {"structural", "name"}]
    soft_lines = "\n".join(
        f"- `{finding['kind']}` `{finding['entry']}`: {finding['reason']} (informational limited-text/heuristic review; not a structural or forbidden-name match)"
        for finding in soft
    ) or "- none"
    commands = "\n".join(
        f"| {index} | `{case['caseId']}` | `{case['commandIndex']}` | {case['result']['exitCode']} | {case['reason']} |"
        for index, case in enumerate(trace["safeModeMatrix"]["cases"], 1)
    )
    simulation = trace["sandboxSimulation"]
    final_absent = all(item.get("absent") and item.get("idempotent") for item in simulation if item["action"] == "rollback")
    return f"""# Phase 5 pre-M16 packaging dry-run report

Bounded result: `COMPLETE / VERIFIED_STATIC_FAKE / DRY_RUN_READY`.

## Evidence identity

- worktree: `{manifest['worktreeId']}`
- H1/source commit: `{trace['sourceCommit']}`
- generated evidence (ignored build output, not ledger evidenceRefs): `{manifest_ref}`, `{trace_ref}`
- manifest SHA-256: `{trace['manifestSha256']}`
- trace SHA-256: `{trace['traceContentSha256']}` (digest of the canonical trace content with this field omitted; it is an integrity summary, not a replay claim)
- process evidence: `processGenerated: true` / self-recorded command outcomes; stdout/stderr digests identify captured bytes only and are not claimed replayable
- scope: pre-M16 dry-run only; not release-ready, production-ready, M14 complete, or M16 complete

## Artifact inventory (14 exact worktree-scoped JARs)

| path | size | SHA-256 |
|---|---:|---|
{artifacts}

## Package scans

- hard findings (`structural` or `name`): **0**
- soft findings and bounded rationale:
{soft_lines}

Native executable/library suffixes are classified as hard forbidden-name findings. The successful run therefore contains none.

## Sandbox and write boundary

- two install/rollback cycles: `{len(simulation) == 4}`
- rollback idempotent and sandbox finally absent: `{final_absent}`
- launcher plan writes: `[]`
- shared latest alias writes: none
- user directory writes: none
- global launcher writes: none
- install/rollback target: worktree-only `build/worktree/{manifest['worktreeId']}/dryrun/sandbox`

## Commands and seven-case safe-mode matrix

| # | case ID | command index | exit | reason |
|---:|---|---:|---:|---|
{commands}

All command records include UTC start/end timestamps, non-negative duration, argv, cwd, exit status, and self-recorded stdout/stderr byte digests. The seven stable IDs cover unsupported version, missing/stale evidence, adapter unavailable, hash mismatch, selector mismatch, partial-slice failure, and explicit safe-mode start/behavior using existing exact tests and the Phase 4 authoritative mutation gate.

## Remaining boundary

Real host, authorized local input, manual GUI/install/rollback, compliance review, and release approval remain pending. No formal user directory or Cubism global launcher was modified.
"""


def trace_errors(trace: Any, repo: Path, manifest: dict[str, Any]) -> list[str]:
    fields = {"format", "schemaVersion", "worktreeId", "sourceCommit", "processGenerated", "manifestSha256", "traceContentSha256", "commands", "scans", "safeModeMatrix", "sandboxSimulation"}
    errors: list[str] = []
    if not isinstance(trace, dict) or set(trace) != fields:
        return ["trace must have strict v1 field set"]
    worktree_id = manifest.get("worktreeId")
    if trace.get("format") != TRACE_FORMAT or trace.get("schemaVersion") != 1 or trace.get("worktreeId") != worktree_id:
        errors.append("invalid trace identity")
    source_commit = trace.get("sourceCommit")
    if not isinstance(source_commit, str) or not re.fullmatch(r"[0-9a-f]{40}", source_commit):
        errors.append("sourceCommit must be full lowercase 40-hex")
    else:
        resolved = git(repo, "rev-parse", "--verify", f"{source_commit}^{{commit}}", check=False)
        head = git(repo, "rev-parse", "HEAD", check=False)
        if resolved != source_commit:
            errors.append("sourceCommit must identify an existing Git commit")
        if head != source_commit:
            errors.append("sourceCommit must exactly match HEAD")
    if trace.get("processGenerated") is not True:
        errors.append("processGenerated must be true")
    if not SHA256_RE.fullmatch(str(trace.get("manifestSha256", ""))) or not SHA256_RE.fullmatch(str(trace.get("traceContentSha256", ""))):
        errors.append("manifest/trace content digests must be lowercase SHA-256")
    if trace.get("manifestSha256") != bytes_digest(canonical_json_bytes(manifest)):
        errors.append("manifestSha256 must equal canonical manifest file bytes")
    trace_without_digest = dict(trace)
    trace_without_digest.pop("traceContentSha256", None)
    if trace.get("traceContentSha256") != bytes_digest(canonical_json_bytes(trace_without_digest)):
        errors.append("traceContentSha256 must equal canonical trace bytes without itself")
    commands = trace.get("commands")
    expected_argv = build_commands(repo, worktree_id) + safe_mode_commands(repo) if isinstance(worktree_id, str) else []
    required_command_fields = {"cwd", "command", "exitCode", "startedAt", "endedAt", "durationMillis", "stdoutSha256", "stderrSha256"}
    if not isinstance(commands, list) or len(commands) != len(expected_argv):
        errors.append("commands must exactly match build_commands + safe_mode_commands count")
        commands = []
    for index, command in enumerate(commands):
        if not isinstance(command, dict) or set(command) != required_command_fields or command.get("cwd") != str(repo.resolve()) or command.get("exitCode") != 0:
            errors.append(f"invalid or unsuccessful command attestation at index {index}"); continue
        if command.get("command") != expected_argv[index]:
            errors.append(f"command argv/order mismatch at index {index}")
        if not SHA256_RE.fullmatch(str(command.get("stdoutSha256", ""))) or not SHA256_RE.fullmatch(str(command.get("stderrSha256", ""))):
            errors.append("invalid stdout/stderr digest")
        try:
            started = datetime.fromisoformat(str(command.get("startedAt", "")).replace("Z", "+00:00"))
            ended = datetime.fromisoformat(str(command.get("endedAt", "")).replace("Z", "+00:00"))
            if started.tzinfo is None or ended.tzinfo is None or ended < started:
                raise ValueError("unordered/non-UTC-aware timestamps")
        except ValueError:
            errors.append(f"invalid command timestamps at index {index}")
        duration = command.get("durationMillis")
        if not isinstance(duration, int) or isinstance(duration, bool) or duration < 0:
            errors.append(f"durationMillis must be a non-negative integer at index {index}")
    safe_indices = list(range(len(build_commands(repo, worktree_id)), len(expected_argv))) if isinstance(worktree_id, str) else []
    matrix = trace.get("safeModeMatrix")
    expected_cases = safe_mode_cases(repo)
    if not isinstance(matrix, dict) or set(matrix) != {"cases"} or not isinstance(matrix.get("cases"), list):
        errors.append("safe-mode matrix must contain exact cases")
    else:
        cases = matrix["cases"]
        if len(cases) != len(expected_cases):
            errors.append("safe-mode matrix must contain exactly seven cases")
        for offset, expected_case in enumerate(expected_cases):
            if offset >= len(cases):
                break
            case = cases[offset]
            command_index = safe_indices[offset] if offset < len(safe_indices) else -1
            expected = {
                "caseId": expected_case["caseId"], "reason": expected_case["reason"],
                "commandIndex": command_index, "result": commands[command_index] if commands and command_index >= 0 else None,
            }
            if case != expected:
                errors.append(f"safe-mode case binding mismatch: {expected_case['caseId']}")
    scans = trace.get("scans")
    expected_scan_paths = [item.get("path") for item in manifest.get("artifacts", []) if isinstance(item, dict)]
    scan_shape_valid = (
        isinstance(scans, list)
        and all(
            isinstance(scan, dict)
            and set(scan) == {"artifact", "findings"}
            and isinstance(scan.get("findings"), list)
            and all(
                isinstance(finding, dict)
                and set(finding) == {"kind", "entry", "reason"}
                and all(isinstance(finding[field], str) for field in ("kind", "entry", "reason"))
                for finding in scan.get("findings", [])
            )
            for scan in scans
        )
    )
    if (not scan_shape_valid or
            [s.get("artifact") for s in scans if isinstance(s, dict)] != expected_scan_paths or
            len(set(expected_scan_paths)) != len(expected_scan_paths)):
        errors.append("artifact scans must equal the unique manifest artifact paths exactly")
    expected_simulation = [
        {"cycle": 1, "action": "install", "count": len(expected_scan_paths), "success": True},
        {"cycle": 1, "action": "rollback", "absent": True, "idempotent": True, "success": True},
        {"cycle": 2, "action": "install", "count": len(expected_scan_paths), "success": True},
        {"cycle": 2, "action": "rollback", "absent": True, "idempotent": True, "success": True},
    ]
    if trace.get("sandboxSimulation") != expected_simulation:
        errors.append("sandbox simulation must be exact two-cycle install/rollback with final absence and idempotent rollback")
    if "recordedOnly" in json.dumps(trace):
        errors.append("recordedOnly trace is forbidden")
    return errors


def run(repo: Path, worktree_id: str) -> Path:
    provenance = source_provenance(repo)
    if not provenance["trackedSourceClean"] or provenance["untrackedSourcePaths"]:
        raise DryRunError(f"source roots must be clean; provenance={provenance}")
    scoped_root = clean_scoped_output(repo, worktree_id)
    command_trace: list[dict[str, Any]] = []
    for command in build_commands(repo, worktree_id):
        command_trace.append(run_command(command, repo))
    if any(item["exitCode"] for item in command_trace):
        raise DryRunError("subproject build failed")
    if find_forbidden_outputs(repo, scoped_root, worktree_id):
        raise DryRunError("unscoped/latest/root build/libs artifacts found")
    artifacts = artifact_inventory(repo, scoped_root, worktree_id)
    plans = plan_documents(repo, scoped_root, worktree_id, artifacts)
    manifest = {
        "format": FORMAT, "schemaVersion": 1, "worktreeId": worktree_id,
        "generatedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "artifacts": artifacts, "forbiddenEntries": list(CANONICAL_FORBIDDEN_ENTRIES), **plans,
    }
    errors = manifest_errors(manifest, repo) + validate_plan_binding(repo, manifest)
    scans = [{"artifact": item["path"], "findings": inspect_zip(repo / item["path"], manifest["forbiddenEntries"])} for item in artifacts]
    if any(
        finding.get("kind") in {"structural", "name"}
        for scan in scans
        for finding in scan["findings"]
    ):
        errors.append("artifact content scan found hard forbidden entries")
    simulation = simulate_install_rollback(repo, manifest) if not errors else []
    for command in safe_mode_commands(repo):
        command_trace.append(run_command(command, repo))
    if any(item["exitCode"] for item in command_trace):
        errors.append("focused safe-mode matrix failed")
    if errors:
        raise DryRunError("; ".join(errors))
    manifest_path = scoped_root / "dryrun" / "manifest.json"
    trace_path = scoped_root / "dryrun" / "trace.json"
    write_json(manifest_path, manifest)
    build_count = len(build_commands(repo, worktree_id))
    cases = safe_mode_cases(repo)
    trace = {
        "format": TRACE_FORMAT, "schemaVersion": 1, "worktreeId": worktree_id, "sourceCommit": provenance["sourceCommit"],
        "processGenerated": True, "manifestSha256": sha256(manifest_path), "traceContentSha256": "0" * 64,
        "commands": command_trace, "scans": scans,
        "safeModeMatrix": {"cases": [
            {"caseId": case["caseId"], "reason": case["reason"], "commandIndex": build_count + index, "result": command_trace[build_count + index]}
            for index, case in enumerate(cases)
        ]},
        "sandboxSimulation": simulation,
    }
    digest_doc = dict(trace); digest_doc.pop("traceContentSha256")
    trace["traceContentSha256"] = bytes_digest(canonical_json_bytes(digest_doc))
    trace_validation = trace_errors(trace, repo, manifest)
    if trace_validation:
        raise DryRunError("invalid generated trace: " + "; ".join(trace_validation))
    write_json(trace_path, trace)
    return manifest_path


def _relative_regular(repo: Path, path: Path, label: str, *, must_exist: bool = True) -> str:
    absolute = path.absolute()
    try:
        relative = absolute.relative_to(repo.absolute()).as_posix()
    except ValueError as exc:
        raise DryRunError(f"{label} must be inside repository") from exc
    if not canonical_relative(relative):
        raise DryRunError(f"{label} must be canonical repository-relative")
    _reject_symlink_chain(repo, absolute)
    if must_exist and (absolute.is_symlink() or not absolute.is_file()):
        raise DryRunError(f"{label} must be an existing regular non-symlink file")
    return relative


def _validate_phase5_ledger(repo: Path, path: Path, evidence_overrides: dict[str, Path] | None = None) -> list[str]:
    validator_path = repo / "scripts/test/validate_automated_tranche_ledger.py"
    spec = importlib.util.spec_from_file_location("phase5_ledger_validator", validator_path)
    if spec is None or spec.loader is None:
        return ["cannot load Phase 5 ledger validator"]
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module.validate(repo, path, target_phase="phase5", evidence_overrides=evidence_overrides)


def _status(repo: Path) -> list[str]:
    result = subprocess.run(
        ["git", "-C", str(repo), "status", "--porcelain=v1", "--untracked-files=all"],
        text=True, capture_output=True,
    )
    if result.returncode:
        raise DryRunError(result.stderr.strip() or "git status failed")
    # Do not strip: the leading index/worktree status columns are semantic.
    return sorted(result.stdout.splitlines())


def _head_blob(repo: Path, relative: str) -> bytes | None:
    result = subprocess.run(["git", "-C", str(repo), "show", f"HEAD:{relative}"], capture_output=True)
    return result.stdout if result.returncode == 0 else None


def _require_finalize_baseline(repo: Path, ledger_ref: str, report_ref: str) -> dict[str, bytes | None]:
    status = _status(repo)
    if status:
        raise DryRunError(f"finalize inputs and repository must be unstaged/staged clean against HEAD: {status}")
    ledger_head = _head_blob(repo, ledger_ref)
    report_head = _head_blob(repo, report_ref)
    ledger_path, report_path = repo / ledger_ref, repo / report_ref
    if ledger_head is None or ledger_path.read_bytes() != ledger_head:
        raise DryRunError("ledger must exist and be clean against HEAD")
    if report_head is None:
        if report_path.exists() or any(line[3:] == report_ref for line in status):
            raise DryRunError("new report must be absent and untracked-clean against HEAD")
    elif not report_path.is_file() or report_path.read_bytes() != report_head:
        raise DryRunError("report must be clean against HEAD")
    return {ledger_ref: ledger_head, report_ref: report_head}


def finalize(repo: Path, manifest_path: Path, trace_path: Path, ledger_path: Path, report_path: Path) -> None:
    manifest_ref = _relative_regular(repo, manifest_path, "manifest")
    trace_ref = _relative_regular(repo, trace_path, "trace")
    ledger_ref = _relative_regular(repo, ledger_path, "ledger")
    report_ref = _relative_regular(repo, report_path, "report", must_exist=False)
    if ledger_ref != "docs/migration/automated-tranche-ledger.tsv":
        raise DryRunError("ledger must be the canonical docs/migration automated tranche ledger")
    if not report_ref.startswith("docs/migration/") or not report_ref.endswith(".md"):
        raise DryRunError("report must be a canonical docs/migration Markdown report")
    baseline = _require_finalize_baseline(repo, ledger_ref, report_ref)
    manifest = json.loads((repo / manifest_ref).read_text(encoding="utf-8"))
    trace = json.loads((repo / trace_ref).read_text(encoding="utf-8"))
    # Hash/size and exact-set validation must happen before content is inspected so the
    # finalization decision is bound to the current artifact bytes, not only to a trace.
    errors = manifest_errors(manifest, repo) + validate_plan_binding(repo, manifest)
    if trace.get("manifestSha256") != sha256(repo / manifest_ref):
        errors.append("manifestSha256 does not match manifest file bytes")
    if (repo / manifest_ref).read_bytes() != canonical_json_bytes(manifest):
        errors.append("manifest file must use canonical JSON bytes")
    if (repo / trace_ref).read_bytes() != canonical_json_bytes(trace):
        errors.append("trace file must use canonical JSON bytes")
    actual_scans: list[dict[str, Any]] = []
    for item in manifest.get("artifacts", []) if isinstance(manifest.get("artifacts"), list) else []:
        if not isinstance(item, dict) or not isinstance(item.get("path"), str):
            continue
        artifact = _safe_repo_regular_file(repo, item["path"])
        if artifact is None:
            continue
        actual_scans.append({
            "artifact": item["path"],
            "findings": inspect_zip(artifact, manifest.get("forbiddenEntries", [])),
        })
    errors.extend(trace_errors(trace, repo, manifest))
    recorded_scans = trace.get("scans") if isinstance(trace, dict) else None
    if recorded_scans != actual_scans:
        errors.append("trace artifact scans do not exactly match current artifact inspection results")
    if any(
        finding.get("kind") in {"structural", "name"}
        for scan in actual_scans
        for finding in scan["findings"]
    ):
        errors.append("current artifact content scan found hard forbidden entries")
    if errors:
        raise DryRunError("cannot finalize invalid dry-run evidence: " + "; ".join(errors))
    with (repo / ledger_ref).open(encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle, delimiter="\t"); fields, rows = reader.fieldnames, list(reader)
    if fields is None:
        raise DryRunError("ledger has no header")
    evidence_refs = [report_ref, "scripts/release/pre_m16_packaging_dryrun.py", "scripts/test/test_pre_m16_packaging_dryrun.py", "scripts/test/test_pre_m16_packaging_dryrun.sh"]
    transitioned = phase5_ledger_transition(rows, evidence_refs)
    report_bytes = render_report(manifest_ref, trace_ref, manifest, trace).encode("utf-8")
    import io

    def render_ledger(target_rows: list[dict[str, str]]) -> bytes:
        stream = io.StringIO(newline="")
        writer = csv.DictWriter(stream, fieldnames=fields, delimiter="\t", lineterminator="\n")
        writer.writeheader(); writer.writerows(target_rows)
        return stream.getvalue().encode("utf-8")

    ledger_bytes = render_ledger(transitioned)
    with tempfile.TemporaryDirectory(dir=repo / "build" if (repo / "build").is_dir() else repo) as temp_dir:
        temp = Path(temp_dir)
        temp_ledger, temp_report = temp / "ledger.tsv", temp / "report.md"
        temp_report.write_bytes(report_bytes)
        # The temporary ledger must still carry the final tracked-style report path.
        # Its existence is checked by the post-replace authoritative validation.
        validation_rows = phase5_ledger_transition(rows, [report_ref, "scripts/release/pre_m16_packaging_dryrun.py", "scripts/test/test_pre_m16_packaging_dryrun.py", "scripts/test/test_pre_m16_packaging_dryrun.sh"])
        temp_ledger.write_bytes(render_ledger(validation_rows))
        validation = _validate_phase5_ledger(repo, temp_ledger, {report_ref: temp_report})
        if validation:
            raise DryRunError("temporary Phase 5 ledger validation failed: " + "; ".join(validation))

    # The validation fixture must be gone before the exact Git-status allowance is checked.
    originals = {repo / ledger_ref: baseline[ledger_ref], repo / report_ref: baseline[report_ref]}
    staged_ledger, staged_report = (repo / ledger_ref).with_name((repo / ledger_ref).name + ".phase5.tmp"), (repo / report_ref).with_name((repo / report_ref).name + ".phase5.tmp")
    staged_ledger.write_bytes(ledger_bytes); staged_report.write_bytes(report_bytes)
    replacements: dict[Path, bytes] = {}

    def replace_if_unchanged(staged: Path, destination: Path, expected: bytes | None, replacement: bytes) -> None:
        current = destination.read_bytes() if destination.exists() else None
        if current != expected:
            raise DryRunError(f"concurrent modification detected before finalize replace: {destination.relative_to(repo)}")
        os.replace(staged, destination)
        replacements[destination] = replacement

    try:
        # Compare immediately before each replace so a detected concurrent user edit is
        # never knowingly overwritten. Rollback below is also digest-guarded.
        replace_if_unchanged(staged_ledger, repo / ledger_ref, baseline[ledger_ref], ledger_bytes)
        replace_if_unchanged(staged_report, repo / report_ref, baseline[report_ref], report_bytes)
        final_validation = _validate_phase5_ledger(repo, repo / ledger_ref)
        if final_validation:
            raise DryRunError("post-replace Phase 5 ledger validation failed: " + "; ".join(final_validation))
        after_status = _status(repo)
        expected_status = {
            ledger_ref: " M",
            report_ref: " M" if baseline[report_ref] is not None else "??",
        }
        actual_status = {line[3:]: line[:2] for line in after_status}
        if actual_status != expected_status:
            raise DryRunError(f"finalize violated exact unstaged-only ledger/report allowance: {after_status}")
        # Status and the validator do not attest file contents. Re-read both destinations
        # immediately before success and require the exact generated bytes. The guarded
        # rollback below deliberately leaves any concurrent replacement untouched.
        final_bytes = {repo / ledger_ref: ledger_bytes, repo / report_ref: report_bytes}
        mismatched = [
            path.relative_to(repo).as_posix()
            for path, expected in final_bytes.items()
            if not path.is_file() or path.is_symlink() or path.read_bytes() != expected
        ]
        if mismatched:
            raise DryRunError(f"concurrent modification detected after final validator/status: {mismatched}")
    except Exception:
        for path, replacement in replacements.items():
            # Never clobber a user write made after our replace. Restore only when the
            # destination digest still proves that it contains the bytes we installed.
            if not path.is_file() or sha256(path) != bytes_digest(replacement):
                continue
            content = originals[path]
            if content is None:
                path.unlink(missing_ok=True)
            else:
                rollback = path.with_name(path.name + ".rollback.tmp")
                rollback.write_bytes(content)
                os.replace(rollback, path)
        staged_ledger.unlink(missing_ok=True); staged_report.unlink(missing_ok=True)
        raise


def main() -> int:
    parser = argparse.ArgumentParser(); parser.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[2])
    sub = parser.add_subparsers(dest="action", required=True)
    run_parser = sub.add_parser("run"); run_parser.add_argument("--worktree-id")
    finalize_parser = sub.add_parser("finalize")
    for name in ("manifest", "trace", "ledger", "report"):
        finalize_parser.add_argument(f"--{name}", type=Path, required=True)
    args = parser.parse_args(); repo = args.repo_root.absolute()
    try:
        if args.action == "run":
            worktree_id = args.worktree_id or (repo / ".turboism-worktree-id").read_text().strip(); print(run(repo, worktree_id))
        else:
            finalize(repo, args.manifest, args.trace, args.ledger, args.report)
        return 0
    except (DryRunError, OSError, ValueError, KeyError, json.JSONDecodeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr); return 1


if __name__ == "__main__":
    raise SystemExit(main())
