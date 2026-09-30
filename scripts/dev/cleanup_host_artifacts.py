#!/usr/bin/env python3
"""Cleanup tool for Proton exact-host validation artifacts and temporary files.

Safely scans, reports, and removes disposable host validation temporary files:
  - Task-scoped Proton prefixes (prefix/ directories)
  - Copied project (.cmo3) and PSD fixture copies in task directories
  - Host and runtime logs (turboism-home/logs/, launcher.out, cubism-console.txt)
  - Build-time manual-test and worktree staging bundles
  - Disposable test fixtures and logs in build/
  - Turboism temporary files in /tmp

Strict safety rules:
  - NEVER removes or traverses the Golden Proton prefix.
  - NEVER modifies or removes immutable source fixtures (.env specified).
  - NEVER modifies or removes Git-tracked source code.
  - NEVER terminates or removes active, running Cubism/Proton sessions.
  - Symlinks are unlinked directly and never traversed into target trees.
"""

from __future__ import annotations

import argparse
import dataclasses
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
from typing import Any, Iterable


class SecurityError(RuntimeError):
    """Raised when an operation would violate safety guardrails."""
    pass


def parse_local_env(env_path: Path) -> dict[str, str]:
    """Parse TURBOISM_* variables from local .env without code execution."""
    if not env_path.is_file():
        return {}
    values: dict[str, str] = {}
    pattern = re.compile(r"^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)=(.*)$")
    for line in env_path.read_text(encoding="utf-8", errors="replace").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        match = pattern.match(line)
        if not match:
            continue
        key, val = match.groups()
        val = val.strip()
        if val.startswith(("\"", "'")) and len(val) >= 2 and val[-1] == val[0]:
            val = val[1:-1]
        values[key] = val
    return values


@dataclasses.dataclass(frozen=True)
class SafetyContext:
    golden_prefix: Path | None
    source_fixtures: tuple[Path, ...]
    source_fixture_inodes: tuple[tuple[int, int], ...]
    repo_root: Path
    host_root: Path
    user_home: Path

    @classmethod
    def create(cls, repo_root: Path, env: dict[str, str]) -> SafetyContext:
        user_home = Path.home().resolve()

        golden_raw = env.get("TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX")
        golden_prefix = Path(golden_raw).resolve() if golden_raw else None

        default_host_root = str(user_home / "TurboismValidation")
        host_root_raw = env.get(
            "TURBOISM_HOST_VALIDATION_HOST_ROOT",
            env.get("TURBOISM_HOST_VALIDATION_REMOTE_ROOT", default_host_root)
        )
        host_root = Path(host_root_raw).resolve()

        # Collect all configured fixture sources
        fixture_paths = []
        fixture_inodes = []
        for key, val in env.items():
            if key.startswith("TURBOISM_HOST_VALIDATION_FIXTURE_") and not key.endswith(("_SHA256", "_NAME")):
                p = Path(val).resolve()
                fixture_paths.append(p)
                try:
                    st = p.stat()
                    fixture_inodes.append((st.st_dev, st.st_ino))
                except OSError:
                    pass

        return cls(
            golden_prefix=golden_prefix,
            source_fixtures=tuple(fixture_paths),
            source_fixture_inodes=tuple(fixture_inodes),
            repo_root=repo_root.resolve(),
            host_root=host_root,
            user_home=user_home,
        )

    def assert_safe_to_delete(self, path: Path) -> None:
        """Assert that path is safe to delete under all red lines."""
        # 1. Critical system roots
        try:
            resolved = path.resolve()
        except OSError as e:
            raise SecurityError(f"Cannot resolve path {path}: {e}")

        # Forbidden exact roots
        forbidden_roots = {
            Path("/"),
            self.user_home,
            Path("/home"),
            Path("/usr"),
            Path("/etc"),
            Path("/var"),
            Path("/dev"),
            Path("/proc"),
            Path("/sys"),
            self.repo_root,
        }
        if resolved in forbidden_roots or path in forbidden_roots:
            raise SecurityError(f"Refusing to delete critical root: {path}")

        # 2. Golden prefix protection
        if self.golden_prefix is not None:
            if resolved == self.golden_prefix or path == self.golden_prefix:
                raise SecurityError(f"Refusing to delete golden prefix: {path}")
            if self.golden_prefix in resolved.parents or resolved in self.golden_prefix.parents:
                raise SecurityError(f"Path overlaps with golden prefix: {path}")

        # 3. Source fixture protection
        if resolved in self.source_fixtures or path in self.source_fixtures:
            raise SecurityError(f"Refusing to delete immutable source fixture: {path}")

        try:
            st = path.lstat()
            if (st.st_dev, st.st_ino) in self.source_fixture_inodes:
                raise SecurityError(f"Path matches inode of immutable source fixture: {path}")
        except OSError:
            pass

        # 4. Git-tracked code protection (inside repo, must only be build/ or ignored paths)
        if self.repo_root in resolved.parents:
            build_dir = self.repo_root / "build"
            if resolved != build_dir and build_dir not in resolved.parents:
                raise SecurityError(f"Refusing to delete repository source file/directory: {path}")

        # 5. Must reside in approved scratch trees
        allowed_ancestors = [self.host_root, self.repo_root / "build", Path("/tmp")]
        in_allowed_tree = any(
            ancestor in resolved.parents or ancestor in path.parents or resolved == ancestor or path == ancestor
            for ancestor in allowed_ancestors
        )
        if not in_allowed_tree:
            raise SecurityError(f"Path is not in an approved cleanup tree: {path}")

        # Cannot delete the host_root directory itself
        if resolved == self.host_root or path == self.host_root:
            raise SecurityError(f"Refusing to delete host root itself: {path}")


@dataclasses.dataclass
class ArtifactItem:
    kind: str  # 'prefix', 'fixture-copy', 'logs', 'build-bundle', 'tmp-file', 'task-dir'
    path: Path
    apparent_bytes: int
    reason: str


def compute_path_size(path: Path) -> int:
    """Compute apparent bytes without following symlinks."""
    try:
        st = path.lstat()
    except OSError:
        return 0
    if stat.S_ISLNK(st.st_mode) or stat.S_ISREG(st.st_mode):
        return st.st_size
    if not stat.S_ISDIR(st.st_mode):
        return 0

    total = 0
    try:
        for root, dirs, files in os.walk(str(path), followlinks=False):
            for f in files:
                p = Path(root) / f
                try:
                    total += p.lstat().st_size
                except OSError:
                    pass
    except OSError:
        pass
    return total


def is_pid_alive(pid: int) -> bool:
    if pid <= 0:
        return False
    try:
        os.kill(pid, 0)
        return True
    except ProcessLookupError:
        return False
    except PermissionError:
        return True


def is_task_dir_active(task_dir: Path) -> bool:
    """Check if task_dir belongs to an actively running validation process."""
    evidence_dir = task_dir / "evidence"
    pid_file = evidence_dir / "wrapper.pid"
    if pid_file.is_file():
        try:
            pid = int(pid_file.read_text().strip())
            if is_pid_alive(pid):
                return True
        except (ValueError, OSError):
            pass
    return False


def find_task_directories(host_root: Path) -> list[Path]:
    """Find all task run directories in host_root across varying directory depths (1 to 3).

    A directory is treated as a task directory if it contains any task run signature:
    - launch.sh or launch.bat
    - evidence/ directory
    - prefix/ directory
    - turboism-home/ directory
    - turboism-agent.jar file
    """
    task_dirs: list[Path] = []
    if not host_root.is_dir():
        return task_dirs

    def scan(current_dir: Path, depth: int) -> None:
        if depth > 3:
            return
        try:
            entries = [p for p in current_dir.iterdir() if p.is_dir() and not p.name.startswith(".")]
        except OSError:
            return

        for p in entries:
            is_task = (
                (p / "launch.sh").is_file()
                or (p / "launch.bat").is_file()
                or (p / "evidence").is_dir()
                or (p / "prefix").is_dir()
                or (p / "turboism-home").is_dir()
                or (p / "turboism-agent.jar").is_file()
            )
            if is_task:
                task_dirs.append(p)
            else:
                scan(p, depth + 1)

    scan(host_root, 1)
    return task_dirs


def scan_worktree_artifacts(worktree_id: str, context: SafetyContext) -> list[ArtifactItem]:
    """Scan all artifacts associated with a specific worktree id."""
    items: list[ArtifactItem] = []

    # 1. build/manual-test/<worktree_id>
    manual_test_dir = context.repo_root / "build" / "manual-test" / worktree_id
    if manual_test_dir.is_dir():
        items.append(ArtifactItem(
            kind="build-bundle",
            path=manual_test_dir,
            apparent_bytes=compute_path_size(manual_test_dir),
            reason=f"Worktree {worktree_id} manual-test build staging",
        ))

    # 2. build/worktree/<worktree_id>
    build_worktree_dir = context.repo_root / "build" / "worktree" / worktree_id
    if build_worktree_dir.is_dir():
        items.append(ArtifactItem(
            kind="build-bundle",
            path=build_worktree_dir,
            apparent_bytes=compute_path_size(build_worktree_dir),
            reason=f"Worktree {worktree_id} build output",
        ))

    # 3. Host validation task directories referencing worktree_id
    if context.host_root.is_dir():
        for item in find_task_directories(context.host_root):
            # Check if this task references worktree_id
            launch_sh = item / "launch.sh"
            launch_bat = item / "launch.bat"
            evidence_dir = item / "evidence"
            referenced = False

            if worktree_id in item.name:
                referenced = True

            if not referenced and launch_sh.is_file():
                try:
                    content = launch_sh.read_text(errors="replace")
                    if worktree_id in content:
                        referenced = True
                except OSError:
                    pass

            if not referenced and launch_bat.is_file():
                try:
                    content = launch_bat.read_text(errors="replace")
                    if worktree_id in content:
                        referenced = True
                except OSError:
                    pass

            if not referenced and evidence_dir.is_dir():
                try:
                    for ev_file in evidence_dir.glob("*.json"):
                        if worktree_id in ev_file.read_text(errors="replace"):
                            referenced = True
                            break
                except OSError:
                    pass

            if referenced:
                if is_task_dir_active(item):
                    continue  # active, skip
                # Add prefix
                prefix = item / "prefix"
                if prefix.exists():
                    items.append(ArtifactItem(
                        kind="prefix",
                        path=prefix,
                        apparent_bytes=compute_path_size(prefix),
                        reason=f"Worktree {worktree_id} task Proton prefix",
                    ))
                # Add copied fixtures
                try:
                    for f in item.iterdir():
                        if f.is_file() and f.suffix in {".cmo3", ".psd"}:
                            items.append(ArtifactItem(
                                kind="fixture-copy",
                                path=f,
                                apparent_bytes=compute_path_size(f),
                                reason=f"Worktree {worktree_id} task fixture copy",
                            ))
                except OSError:
                    pass
                # Add logs
                logs_dir = item / "turboism-home" / "logs"
                if logs_dir.is_dir():
                    items.append(ArtifactItem(
                        kind="logs",
                        path=logs_dir,
                        apparent_bytes=compute_path_size(logs_dir),
                        reason=f"Worktree {worktree_id} task logs",
                    ))

    return items


def scan_terminal_host_artifacts(context: SafetyContext) -> list[ArtifactItem]:
    """Scan all finished/terminal host validation task directories for cleanable files."""
    items: list[ArtifactItem] = []
    if not context.host_root.is_dir():
        return items

    for task_dir in find_task_directories(context.host_root):
        if is_task_dir_active(task_dir):
            continue

        # 1. Proton prefix (primary disk consumer, 1.8G+)
        prefix = task_dir / "prefix"
        if prefix.exists():
            items.append(ArtifactItem(
                kind="prefix",
                path=prefix,
                apparent_bytes=compute_path_size(prefix),
                reason=f"Terminal task {task_dir.name} Proton prefix",
            ))

        # 2. Copied fixtures (.cmo3, .psd)
        try:
            for entry in task_dir.iterdir():
                if entry.is_file() and entry.suffix in {".cmo3", ".psd"}:
                    items.append(ArtifactItem(
                        kind="fixture-copy",
                        path=entry,
                        apparent_bytes=compute_path_size(entry),
                        reason=f"Terminal task {task_dir.name} fixture copy",
                    ))
        except OSError:
            pass

        # 3. Turboism logs
        logs_dir = task_dir / "turboism-home" / "logs"
        if logs_dir.is_dir():
            items.append(ArtifactItem(
                kind="logs",
                path=logs_dir,
                apparent_bytes=compute_path_size(logs_dir),
                reason=f"Terminal task {task_dir.name} Proton/runtime logs",
            ))

        # 4. If terminal, check staging agent jar
        agent_jar = task_dir / "turboism-agent.jar"
        if agent_jar.is_file():
            items.append(ArtifactItem(
                kind="build-bundle",
                path=agent_jar,
                apparent_bytes=compute_path_size(agent_jar),
                reason=f"Terminal task {task_dir.name} staged agent JAR",
            ))

    return items


def scan_build_disposables(context: SafetyContext) -> list[ArtifactItem]:
    """Scan disposable PSD/CMO3 files and test logs directly in build/."""
    items: list[ArtifactItem] = []
    build_dir = context.repo_root / "build"
    if not build_dir.is_dir():
        return items

    try:
        for entry in build_dir.iterdir():
            if entry.is_file():
                if entry.suffix in {".psd", ".cmo3"} or (entry.suffix == ".log" and entry.name.startswith("texture-postmerge-")):
                    items.append(ArtifactItem(
                        kind="fixture-copy" if entry.suffix in {".psd", ".cmo3"} else "logs",
                        path=entry,
                        apparent_bytes=compute_path_size(entry),
                        reason="Disposable test fixture/log in build directory",
                    ))
    except OSError:
        pass
    return items


def scan_tmp_artifacts(context: SafetyContext) -> list[ArtifactItem]:
    """Scan temporary files created by Turboism in /tmp."""
    items: list[ArtifactItem] = []
    tmp_dir = Path("/tmp")
    current_uid = os.getuid()

    try:
        for entry in tmp_dir.iterdir():
            if entry.name.startswith(("turboism-", "cubism-", "e880-cubism-")):
                try:
                    st = entry.lstat()
                    if st.st_uid == current_uid:
                        items.append(ArtifactItem(
                            kind="tmp-file",
                            path=entry,
                            apparent_bytes=compute_path_size(entry),
                            reason="Turboism temporary file/directory in /tmp",
                        ))
                except OSError:
                    pass
    except OSError:
        pass
    return items


def safe_delete_path(path: Path, context: SafetyContext) -> None:
    """Safely delete path, refusing to follow directory symlinks."""
    context.assert_safe_to_delete(path)

    try:
        st = path.lstat()
    except FileNotFoundError:
        return

    if stat.S_ISLNK(st.st_mode) or stat.S_ISREG(st.st_mode):
        path.unlink(missing_ok=True)
    elif stat.S_ISDIR(st.st_mode):
        # Remove tree without following symlinks into outside directories
        def on_rm_error(func, p, exc_info):
            try:
                os.chmod(p, 0o700)
                func(p)
            except OSError:
                pass
        shutil.rmtree(str(path), onerror=on_rm_error)


def format_bytes(num_bytes: int) -> str:
    for unit in ("B", "KB", "MB", "GB"):
        if abs(num_bytes) < 1024.0:
            return f"{num_bytes:3.1f} {unit}"
        num_bytes /= 1024.0
    return f"{num_bytes:.1f} TB"


def run_cleanup(
    worktree_id: str | None = None,
    all_terminal: bool = False,
    clean_build_psd: bool = True,
    clean_tmp: bool = False,
    dry_run: bool = False,
    repo_root: Path | None = None,
    env_file: Path | None = None,
) -> dict[str, Any]:
    """Execute cleanup inspection or removal according to arguments."""
    repo = repo_root or Path(__file__).resolve().parents[2]
    env_path = env_file or repo / ".env"
    env = parse_local_env(env_path)
    context = SafetyContext.create(repo, env)

    all_items: list[ArtifactItem] = []
    seen_paths: set[Path] = set()

    def add_items(new_items: Iterable[ArtifactItem]):
        for it in new_items:
            try:
                resolved = it.path.resolve()
            except OSError:
                resolved = it.path
            if resolved not in seen_paths:
                # Pre-validate safety check
                try:
                    context.assert_safe_to_delete(it.path)
                    seen_paths.add(resolved)
                    all_items.append(it)
                except SecurityError as sec_err:
                    print(f"Warning: skipped unsafe candidate {it.path}: {sec_err}", file=sys.stderr)

    if worktree_id:
        add_items(scan_worktree_artifacts(worktree_id, context))

    if all_terminal:
        add_items(scan_terminal_host_artifacts(context))

    if clean_build_psd:
        add_items(scan_build_disposables(context))

    if clean_tmp:
        add_items(scan_tmp_artifacts(context))

    # Calculate summary
    total_bytes = sum(i.apparent_bytes for i in all_items)
    by_kind: dict[str, dict[str, Any]] = {}
    for i in all_items:
        slot = by_kind.setdefault(i.kind, {"count": 0, "apparentBytes": 0})
        slot["count"] += 1
        slot["apparentBytes"] += i.apparent_bytes

    deleted_count = 0
    errors: list[dict[str, str]] = []

    if not dry_run:
        for it in all_items:
            try:
                safe_delete_path(it.path, context)
                deleted_count += 1
            except Exception as e:
                errors.append({"path": str(it.path), "error": str(e)})

    return {
        "schemaVersion": 1,
        "dryRun": dry_run,
        "worktreeId": worktree_id,
        "totalItems": len(all_items),
        "deletedItems": deleted_count,
        "totalApparentBytes": total_bytes,
        "totalApparentFormatted": format_bytes(total_bytes),
        "byKind": by_kind,
        "items": [
            {
                "kind": i.kind,
                "path": str(i.path),
                "apparentBytes": i.apparent_bytes,
                "apparentFormatted": format_bytes(i.apparent_bytes),
                "reason": i.reason,
            }
            for i in all_items
        ],
        "errors": errors,
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Clean disposable Proton host validation artifacts, logs, copied fixtures, and build test bundles."
    )
    parser.add_argument("--worktree", help="Worktree ID to clean specifically")
    parser.add_argument("--all-terminal", action="store_true", help="Clean all finished/terminal host validation task artifacts")
    parser.add_argument("--clean-build-psd", action="store_true", default=True, help="Clean disposable PSD/CMO3 files in project build/")
    parser.add_argument("--no-clean-build-psd", dest="clean_build_psd", action="store_false", help="Do not clean disposable PSD files in build/")
    parser.add_argument("--clean-tmp", action="store_true", help="Clean disposable Turboism artifacts in /tmp")
    parser.add_argument("--dry-run", action="store_true", help="Report what would be cleaned without removing anything")
    parser.add_argument("--json", action="store_true", help="Output results in JSON format")
    parser.add_argument("--quiet", action="store_true", help="Suppress detailed item list")

    args = parser.parse_args(argv)

    if not (args.worktree or args.all_terminal or args.clean_tmp or args.clean_build_psd):
        parser.error("Specify at least one cleanup target: --worktree <id>, --all-terminal, or --clean-tmp")

    result = run_cleanup(
        worktree_id=args.worktree,
        all_terminal=args.all_terminal,
        clean_build_psd=args.clean_build_psd,
        clean_tmp=args.clean_tmp,
        dry_run=args.dry_run,
    )

    if args.json:
        print(json.dumps(result, ensure_ascii=False, indent=2))
        return 0 if not result["errors"] else 1

    mode_str = "[DRY-RUN] " if result["dryRun"] else ""
    print(f"=== {mode_str}Turboism Host Artifact Cleanup Report ===")
    print(f"Total candidates: {result['totalItems']} ({result['totalApparentFormatted']})")
    for kind, details in result["byKind"].items():
        print(f"  - {kind}: {details['count']} items ({format_bytes(details['apparentBytes'])})")

    if not args.quiet and result["items"]:
        print("\nItems:")
        for item in result["items"]:
            action = "Would delete" if result["dryRun"] else "Deleted"
            print(f"  * {action} [{item['kind']}] {item['path']} ({item['apparentFormatted']}) - {item['reason']}")

    if result["errors"]:
        print("\nErrors encountered:")
        for err in result["errors"]:
            print(f"  ! {err['path']}: {err['error']}", file=sys.stderr)
        return 1

    if not result["dryRun"]:
        print(f"\nSuccessfully cleaned {result['deletedItems']} items. Space reclaimed: {result['totalApparentFormatted']}")
    else:
        print("\nDry run complete. No files were removed.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
