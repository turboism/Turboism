#!/usr/bin/env python3
"""Capture only this offline probe's build inputs; no host dependencies or cleanup."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys

PROBE = Path(__file__).resolve().parents[1]
REPO = PROBE.parents[1]
REL = re.compile(r"[A-Za-z0-9_./$-]+")


def checked(path):
    p = Path(os.path.abspath(path))
    if ".." in Path(path).parts:
        raise ValueError("dot component")
    for component in (p, *p.parents):
        if component.is_symlink():
            raise ValueError(f"symlink: {component}")
    return p


def digest(data):
    return hashlib.sha256(data).hexdigest()


def exclusive(path, data):
    with path.open("xb") as f:
        f.write(data)
    path.chmod(0o444)


def manifest(root, suffixes):
    rows = []
    for p in sorted(root.rglob("*"), key=lambda p: p.relative_to(root).as_posix()):
        if p.is_symlink():
            raise ValueError(f"input symlink: {p}")
        if not p.is_file() or p.suffix not in suffixes:
            continue
        rel = p.relative_to(root).as_posix()
        if not REL.fullmatch(rel) or any(x in (".", "..") for x in Path(rel).parts):
            raise ValueError(f"invalid relative input: {rel}")
        rows.append(f"{digest(p.read_bytes())}  {rel}\n")
    if not rows:
        raise ValueError("empty input manifest")
    return "".join(rows).encode("ascii")


def git(*args):
    cp = subprocess.run(["git", "-C", str(REPO), *args], capture_output=True, timeout=10)
    return cp.stdout if cp.returncode == 0 else None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("snapshot", "finish"))
    parser.add_argument("run")
    parser.add_argument("--compiler")
    args = parser.parse_args()
    run = checked(args.run)
    if (run.parent != REPO / "build/model-import-3d-offline/runs"
            or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", run.name)
            or (run / ".terminal").exists()
            or (run / ".terminal").is_symlink()
            or checked(run / ".offline-run").read_bytes() != b"t006-offline-run-v1\n"):
        raise ValueError("unowned/sealed run")
    if args.mode == "snapshot":
        inputs = run / "inputs"
        inputs.mkdir()  # Exclusive, including empty-directory rejection.
        before = manifest(PROBE, {".java", ".sh", ".py", ".md"})
        for line in before.decode("ascii").splitlines():
            sha, rel = line.split("  ", 1)
            data = (PROBE / rel).read_bytes()
            if digest(data) != sha:
                raise ValueError("source changed while snapshotting")
            dest = inputs / rel
            dest.parent.mkdir(parents=True, exist_ok=True)
            exclusive(dest, data)
        if manifest(PROBE, {".java", ".sh", ".py", ".md"}) != before:
            raise ValueError("source changed during snapshot")
        exclusive(run / "sources.sha256", before)
        # Disposable test copies may be outside a Git root: never borrow an ancestor's HEAD.
        top = git("rev-parse", "--show-toplevel")
        observed = top is not None and Path(os.fsdecode(top).strip()).resolve() == REPO
        head = git("rev-parse", "HEAD") if observed else None
        status = git("status", "--porcelain=v1", "-uall") if observed else None
        head_text = head.decode("ascii").strip() if head else "unobserved"
        if head_text != "unobserved" and not re.fullmatch(r"[0-9a-f]{40}", head_text):
            raise ValueError("invalid Git HEAD")
        exclusive(run / "git-head.txt", (head_text + "\n").encode("ascii"))
        exclusive(run / "git-status.txt", status if status is not None else b"unobserved\n")
    else:
        if not args.compiler or not re.fullmatch(r"17\.[0-9A-Za-z.+_-]+", args.compiler):
            raise ValueError("compiler identity missing/not JDK17")
        sources = checked(run / "sources.sha256").read_bytes()
        if manifest(checked(run / "inputs"), {".java", ".sh", ".py", ".md"}) != sources:
            raise ValueError("snapshot drift after compilation")
        classes = manifest(checked(run / "classes"), {".class"})
        exclusive(run / "classes.sha256", classes)
        source_sha, status_sha = digest(sources), digest((run / "git-status.txt").read_bytes())
        fields = {
            "head": (run / "git-head.txt").read_text().strip(),
            "gitStatusSha256": status_sha,
            "sourceSha256": source_sha,
            "sourceFiles": str(len(sources.splitlines())),
            "classesSha256": digest(classes),
            "compilerVersion": args.compiler,
            "worktreeDirtyFingerprint": digest((status_sha + "\n" + source_sha + "\n").encode("ascii")),
            "runDirectoryId": run.name,
        }
        exclusive(run / "build.properties", "".join(f"{k}={v}\n" for k, v in fields.items()).encode("ascii"))
    print(f"{args.mode}: {run}")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, subprocess.SubprocessError) as e:
        print(f"build provenance refused: {e}", file=sys.stderr)
        raise SystemExit(3)
