#!/usr/bin/env python3
"""Validate the two non-overlapping Phase 4 JSON contracts."""

from __future__ import annotations

import argparse
import hashlib
import subprocess
import sys
from pathlib import Path
from typing import Any

from json_contract_validation import (
    COMMIT_RE,
    ID_RE,
    SHA256_RE,
    WORKTREE_ID_RE,
    error,
    require_integer,
    require_object,
    require_relative_path,
    require_string,
    require_string_list,
    require_utc_time,
    strict_fields,
    validate_file,
)

SYNTHETIC_FORMAT = "turboism.synthetic-composition-evidence"
PACKAGING_FORMAT = "turboism.pre-m16-packaging-dryrun-manifest"
PHASE3_COMMIT = "5b42c656a0f10b2959119663610f59f6d98d77fb"
SYNTHETIC_FIELDS = {"format", "schemaVersion", "sourceCommit", "generatedAt", "slices"}
SYNTHETIC_SLICE_FIELDS = {
    "sliceId", "capabilityIds", "adapterSliceId", "staticEvidencePath",
    "reportPath", "testPaths", "trustRootSha256",
}
PACKAGING_FIELDS = {
    "format", "schemaVersion", "worktreeId", "generatedAt", "artifacts",
    "forbiddenEntries", "launcherPlanPath", "installPlanPath", "rollbackPlanPath",
}
ARTIFACT_FIELDS = {"path", "sha256", "size"}
PHASE3_REPORT_PATH = "docs/migration/phase3-synthetic-composition-report.md"
PHASE3_TEST_PATHS = {
    "runtime/src/test/java/dev/turboism/adapter/RuntimeHostAdaptersTrustRootTest.java",
    "runtime/src/test/java/dev/turboism/adapter/host/HostSessionPluginContextIntegrationTest.java",
}
EXPECTED_SLICES = {
    "project-workspace": (
        "adapter.project-workspace.readonly",
        {"cubism.project.read", "cubism.workspace.read"},
        "docs/migration/verification/static/cubism-5.3.02-project-workspace.json",
    ),
    "clipmask": (
        "adapter.clipmask.readonly",
        {"cubism.clipmask.read"},
        "docs/migration/verification/static/cubism-5.3.02-clipmask.json",
    ),
}


def validate_header(document: Any, expected_format: str, allowed: set[str]) -> tuple[dict[str, Any] | None, list[dict[str, str]]]:
    errors: list[dict[str, str]] = []
    if not require_object(document, "$", errors):
        return None, errors
    assert isinstance(document, dict)
    strict_fields(document, allowed, allowed, "$", errors)
    if document.get("format") != expected_format:
        errors.append(error("INVALID_FORMAT", f"format must be {expected_format}", "$.format"))
    if document.get("schemaVersion") != 1 or isinstance(document.get("schemaVersion"), bool):
        errors.append(error("INVALID_SCHEMA_VERSION", "schemaVersion must be integer 1", "$.schemaVersion"))
    return document, errors


def git(repo_root: Path, *args: str, input_bytes: bytes | None = None) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        ["git", "-C", str(repo_root), *args],
        input=input_bytes,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )


def validate_authoritative_git_root(repo_root: Path, source_commit: Any, errors: list[dict[str, str]]) -> bool:
    worktree = git(repo_root, "rev-parse", "--is-inside-work-tree")
    if worktree.returncode != 0 or worktree.stdout.strip() != b"true":
        errors.append(error("NOT_GIT_WORKTREE", "repo-root must be a Git worktree", "$"))
        return False
    if not isinstance(source_commit, str) or COMMIT_RE.fullmatch(source_commit) is None:
        return False
    commit = git(repo_root, "cat-file", "-e", f"{source_commit}^{{commit}}")
    if commit.returncode != 0:
        errors.append(error("MISSING_SOURCE_COMMIT", "sourceCommit is not an available Git commit", "$.sourceCommit"))
        return False
    return True


def commit_file(repo_root: Path, source_commit: str, value: str, path: str, errors: list[dict[str, str]]) -> bytes | None:
    exists = git(repo_root, "cat-file", "-e", f"{source_commit}:{value}")
    if exists.returncode != 0:
        errors.append(error("MISSING_COMMIT_REFERENCE", "canonical path does not exist in sourceCommit", path))
        return None
    content = git(repo_root, "show", f"{source_commit}:{value}")
    if content.returncode != 0:
        errors.append(error("MISSING_COMMIT_REFERENCE", "cannot read canonical path from sourceCommit", path))
        return None
    return content.stdout


def resolve_repo_file(repo_root: Path, value: str, path: str, errors: list[dict[str, str]]) -> Path | None:
    root = repo_root.resolve()
    candidate = (root / value).resolve()
    try:
        candidate.relative_to(root)
    except ValueError:
        errors.append(error("PATH_OUTSIDE_REPOSITORY", "path resolves outside repository root", path))
        return None
    if not candidate.is_file():
        errors.append(error("MISSING_EVIDENCE", "referenced repository file does not exist as a regular file", path))
        return None
    return candidate


def validate_synthetic(
    document: Any, *, authoritative: bool = False, repo_root: Path | None = None,
) -> list[dict[str, str]]:
    document, errors = validate_header(document, SYNTHETIC_FORMAT, SYNTHETIC_FIELDS)
    if document is None:
        return errors
    if document.get("sourceCommit") != PHASE3_COMMIT:
        errors.append(error("INVALID_SOURCE_COMMIT", f"sourceCommit must be the Phase 3 commit {PHASE3_COMMIT}", "$.sourceCommit"))
    elif not COMMIT_RE.fullmatch(document["sourceCommit"]):
        errors.append(error("INVALID_SOURCE_COMMIT", "sourceCommit must be a full lowercase Git commit", "$.sourceCommit"))
    require_utc_time(document.get("generatedAt"), "$.generatedAt", errors)
    slices = document.get("slices")
    if not isinstance(slices, list) or len(slices) != 2:
        errors.append(error("INVALID_SLICE_SET", "slices must contain exactly the two Phase 3 read slices", "$.slices"))
        return errors
    authoritative_git_ready = False
    source_commit = document.get("sourceCommit")
    if authoritative and repo_root is not None:
        authoritative_git_ready = validate_authoritative_git_root(repo_root, source_commit, errors)
    seen: set[str] = set()
    for index, item in enumerate(slices):
        path = f"$.slices[{index}]"
        if not require_object(item, path, errors):
            continue
        assert isinstance(item, dict)
        strict_fields(item, SYNTHETIC_SLICE_FIELDS, SYNTHETIC_SLICE_FIELDS, path, errors)
        slice_id = item.get("sliceId")
        require_string(slice_id, f"{path}.sliceId", errors, ID_RE)
        if isinstance(slice_id, str):
            if slice_id in seen:
                errors.append(error("DUPLICATE_VALUE", "sliceId must be unique", f"{path}.sliceId"))
            seen.add(slice_id)
        require_string(item.get("adapterSliceId"), f"{path}.adapterSliceId", errors, ID_RE)
        require_string_list(item.get("capabilityIds"), f"{path}.capabilityIds", errors, item_validator=lambda value, item_path, target: require_string(value, item_path, target, ID_RE))
        for field in ("staticEvidencePath", "reportPath"):
            require_relative_path(item.get(field), f"{path}.{field}", errors)
        require_string_list(item.get("testPaths"), f"{path}.testPaths", errors, item_validator=require_relative_path)
        require_string(item.get("trustRootSha256"), f"{path}.trustRootSha256", errors, SHA256_RE)
        expected = EXPECTED_SLICES.get(slice_id) if isinstance(slice_id, str) else None
        if expected is None:
            errors.append(error("UNKNOWN_SLICE", "unknown Phase 3 sliceId", f"{path}.sliceId"))
        else:
            adapter_id, capability_ids, static_evidence_path = expected
            if item.get("adapterSliceId") != adapter_id:
                errors.append(error("TRUST_ROOT_CONFLATION", f"{slice_id} must use {adapter_id}", f"{path}.adapterSliceId"))
            actual_capabilities = item.get("capabilityIds")
            if isinstance(actual_capabilities, list) and set(actual_capabilities) != capability_ids:
                errors.append(error("TRUST_ROOT_CONFLATION", f"{slice_id} capability set is not exact", f"{path}.capabilityIds"))
            if item.get("staticEvidencePath") != static_evidence_path:
                errors.append(error("INVALID_CANONICAL_PATH", f"{slice_id} must use canonical static evidence path {static_evidence_path}", f"{path}.staticEvidencePath"))
            if item.get("reportPath") != PHASE3_REPORT_PATH:
                errors.append(error("INVALID_CANONICAL_PATH", f"reportPath must be {PHASE3_REPORT_PATH}", f"{path}.reportPath"))
            actual_tests = item.get("testPaths")
            if isinstance(actual_tests, list) and set(actual_tests) != PHASE3_TEST_PATHS:
                errors.append(error("INVALID_CANONICAL_PATH", "testPaths must be the exact Phase 3 focused test set", f"{path}.testPaths"))
            if authoritative and repo_root is not None:
                referenced_paths = [("staticEvidencePath", item.get("staticEvidencePath")), ("reportPath", item.get("reportPath"))]
                if isinstance(actual_tests, list):
                    referenced_paths.extend((f"testPaths[{test_index}]", test_path) for test_index, test_path in enumerate(actual_tests))
                resolved_static: Path | None = None
                committed_static: bytes | None = None
                for field, referenced in referenced_paths:
                    if isinstance(referenced, str):
                        resolved = resolve_repo_file(repo_root, referenced, f"{path}.{field}", errors)
                        committed = None
                        if authoritative_git_ready and isinstance(source_commit, str):
                            committed = commit_file(repo_root, source_commit, referenced, f"{path}.{field}", errors)
                        if field == "staticEvidencePath":
                            resolved_static = resolved
                            committed_static = committed
                digest = item.get("trustRootSha256")
                if isinstance(digest, str) and SHA256_RE.fullmatch(digest):
                    if resolved_static is not None:
                        actual_digest = hashlib.sha256(resolved_static.read_bytes()).hexdigest()
                        if actual_digest != digest:
                            errors.append(error("TRUST_ROOT_DIGEST_MISMATCH", "trustRootSha256 does not match current staticEvidencePath bytes", f"{path}.trustRootSha256"))
                    if committed_static is not None:
                        committed_digest = hashlib.sha256(committed_static).hexdigest()
                        if committed_digest != digest:
                            errors.append(error("TRUST_ROOT_COMMIT_DIGEST_MISMATCH", "trustRootSha256 does not match staticEvidencePath bytes in sourceCommit", f"{path}.trustRootSha256"))
    if seen != set(EXPECTED_SLICES):
        errors.append(error("INVALID_SLICE_SET", "both independent Phase 3 slices are required", "$.slices"))
    return errors


def artifact_path_is_scoped(value: Any, worktree_id: Any, path: str, errors: list[dict[str, str]]) -> None:
    if not require_relative_path(value, path, errors) or not isinstance(value, str) or not isinstance(worktree_id, str):
        return
    prefix = f"build/worktree/{worktree_id}/"
    if not value.startswith(prefix) or not value.endswith(f"-{worktree_id}.jar"):
        errors.append(error("INVALID_ARTIFACT_PATH", f"artifact path must start with {prefix} and end with -{worktree_id}.jar", path))


def validate_packaging(document: Any, *, authoritative: bool = False, repo_root: Path | None = None) -> list[dict[str, str]]:
    document, errors = validate_header(document, PACKAGING_FORMAT, PACKAGING_FIELDS)
    if document is None:
        return errors
    worktree_id = document.get("worktreeId")
    require_string(worktree_id, "$.worktreeId", errors, WORKTREE_ID_RE)
    require_utc_time(document.get("generatedAt"), "$.generatedAt", errors)
    artifacts = document.get("artifacts")
    if not isinstance(artifacts, list) or not artifacts:
        errors.append(error("TYPE_MISMATCH", "artifacts must be a non-empty array", "$.artifacts"))
    else:
        paths: set[str] = set()
        for index, item in enumerate(artifacts):
            path = f"$.artifacts[{index}]"
            if not require_object(item, path, errors):
                continue
            assert isinstance(item, dict)
            strict_fields(item, ARTIFACT_FIELDS, ARTIFACT_FIELDS, path, errors)
            artifact_path_is_scoped(item.get("path"), worktree_id, f"{path}.path", errors)
            require_string(item.get("sha256"), f"{path}.sha256", errors, SHA256_RE)
            require_integer(item.get("size"), f"{path}.size", errors, minimum=1)
            if isinstance(item.get("path"), str):
                if item["path"] in paths:
                    errors.append(error("DUPLICATE_VALUE", "artifact paths must be unique", f"{path}.path"))
                paths.add(item["path"])
    require_string_list(document.get("forbiddenEntries"), "$.forbiddenEntries", errors)
    for field in ("launcherPlanPath", "installPlanPath", "rollbackPlanPath"):
        require_relative_path(document.get(field), f"$.{field}", errors)
    if authoritative and repo_root is not None:
        try:
            from pre_m16_packaging_dryrun import manifest_errors
            for message in manifest_errors(document, repo_root):
                errors.append(error("AUTHORITATIVE_PACKAGING_MISMATCH", message, "$"))
        except ImportError as exc:
            errors.append(error("VALIDATOR_IMPORT_FAILED", str(exc), "$"))
    return errors


VALIDATORS = {"synthetic": validate_synthetic, "packaging": validate_packaging}


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("contract", choices=sorted(VALIDATORS))
    parser.add_argument("path", type=Path)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument("--authoritative", action="store_true", help="bind synthetic references and digests to repository files")
    mode.add_argument("--fixture-mode", action="store_true", help="validate schema/semantics without repository file binding")
    parser.add_argument("--repo-root", type=Path)
    args = parser.parse_args()
    if args.authoritative and args.repo_root is None:
        parser.error("--authoritative requires --repo-root")
    if args.repo_root is not None and not args.authoritative:
        parser.error("--repo-root requires --authoritative")
    if args.contract == "synthetic":
        validator = lambda document: validate_synthetic(
            document, authoritative=args.authoritative, repo_root=args.repo_root,
        )
    else:
        if args.authoritative:
            sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "release"))
        validator = lambda document: validate_packaging(
            document, authoritative=args.authoritative, repo_root=args.repo_root,
        )
    errors = validate_file(args.path, validator)
    for item in errors:
        print(f"{item['severity']} {item['code']} {item['path']}: {item['message']}", file=sys.stderr)
    if errors:
        return 1
    print(f"PASS: {args.contract} contract ({args.path})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
