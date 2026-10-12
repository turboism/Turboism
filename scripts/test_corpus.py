#!/usr/bin/env python3
"""Test corpus management tool for Turboism.

Manages test assets (cmo3, psd, glb, etc.) across versioned release archives and
git-tracked fixtures. Pure Python standard library.

Commands:
  pack <dataset> --source <dir> --out <dir>
    Create a deterministic, reproducible tar.gz archive from source files.
    Validates members against manifest, rejects unmanifested files.

  fetch <dataset|--all> [--cache DIR] [--copy] [--from-file FILE] [--force]
    Download/load release archive, verify archive and member SHA-256 hashes,
    unpack into shared cache, and link or copy into repo target paths.

  verify [<dataset>|--all]
    Verify local existence, byte counts, and SHA-256 hashes of dataset files.

  list
    List registered datasets, storage type, license, sizes, and local status.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import shutil
import sys
import tarfile
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def default_repo_root() -> Path:
    return Path(__file__).resolve().parents[1]


def default_manifest_path(repo_root: Path | None = None) -> Path:
    root = repo_root or default_repo_root()
    return root / "test-assets" / "corpus.json"


def default_cache_dir() -> Path:
    xdg = os.environ.get("XDG_CACHE_HOME")
    if xdg:
        return Path(xdg) / "turboism" / "test-corpus"
    return Path.home() / ".cache" / "turboism" / "test-corpus"


def compute_file_sha256(path: Path | str) -> str:
    hasher = hashlib.sha256()
    with open(path, "rb") as f:
        while chunk := f.read(65536):
            hasher.update(chunk)
    return hasher.hexdigest()


def load_manifest(manifest_path: Path) -> dict[str, Any]:
    if not manifest_path.exists():
        raise FileNotFoundError(f"Manifest not found: {manifest_path}")
    with open(manifest_path, "r", encoding="utf-8") as f:
        return json.load(f)


def find_dataset(manifest: dict[str, Any], dataset_id: str) -> dict[str, Any]:
    for ds in manifest.get("datasets", []):
        if ds.get("id") == dataset_id or ds.get("datasetId") == dataset_id:
            return ds
    available = [ds.get("id") for ds in manifest.get("datasets", [])]
    raise ValueError(
        f"Dataset '{dataset_id}' not found in manifest. Available datasets: {', '.join(available)}"
    )


def is_safe_relative_path(path_str: str) -> bool:
    posix = Path(path_str)
    if posix.is_absolute():
        return False
    parts = posix.parts
    if ".." in parts or "." in parts:
        return False
    return True


def is_safe_destination(base_dir: Path, target_path: Path) -> bool:
    try:
        resolved_base = base_dir.resolve()
        resolved_target = target_path.resolve()
        return resolved_target == resolved_base or resolved_base in resolved_target.parents
    except Exception:
        return False


# ---------------------------------------------------------------------------
# Pack
# ---------------------------------------------------------------------------

def pack_dataset(
    manifest_path: Path,
    dataset_id: str,
    source_dir: Path,
    out_dir: Path,
) -> tuple[Path, int, str]:
    manifest = load_manifest(manifest_path)
    dataset = find_dataset(manifest, dataset_id)

    if dataset.get("storage") != "release":
        raise ValueError(f"Dataset '{dataset_id}' has storage='{dataset.get('storage')}', only 'release' datasets can be packed.")

    if not source_dir.is_dir():
        raise FileNotFoundError(f"Source directory does not exist: {source_dir}")

    expected_files: dict[str, dict[str, Any]] = {
        f["path"]: f for f in dataset.get("files", [])
    }
    if not expected_files:
        raise ValueError(f"No files defined in manifest for dataset '{dataset_id}'")

    # Scan all files under source_dir (skip hidden files and repo documentation *.md unless in manifest)
    found_files: set[str] = set()
    for root, _, files in os.walk(source_dir):
        for f in files:
            if f.startswith("."):
                continue
            full = Path(root) / f
            rel = full.relative_to(source_dir).as_posix()
            if f.endswith(".md") and rel not in expected_files:
                continue
            found_files.add(rel)

    # 1. Reject unmanifested files
    unmanifested = sorted(found_files - expected_files.keys())
    if unmanifested:
        raise ValueError(
            f"Source directory contains files not listed in manifest: {', '.join(unmanifested)}"
        )

    # 2. Check for missing files
    missing = sorted(expected_files.keys() - found_files)
    if missing:
        raise FileNotFoundError(
            f"Source directory is missing files required by manifest: {', '.join(missing)}"
        )

    # 3. Check hashes and sizes before packing
    for rel_path, meta in expected_files.items():
        file_path = source_dir / rel_path
        actual_size = file_path.stat().st_size
        expected_size = meta.get("bytes")
        if expected_size is not None and actual_size != expected_size:
            raise ValueError(
                f"Size mismatch for '{rel_path}': expected {expected_size} bytes, got {actual_size} bytes"
            )

        actual_sha256 = compute_file_sha256(file_path)
        expected_sha256 = meta.get("sha256")
        if expected_sha256 and actual_sha256.lower() != expected_sha256.lower():
            raise ValueError(
                f"SHA-256 mismatch for '{rel_path}': expected {expected_sha256}, got {actual_sha256}"
            )

    # 4. Pack deterministically into tar.gz
    archive_name = dataset.get("release", {}).get("archive")
    if not archive_name:
        archive_name = f"{dataset['id']}-v{dataset.get('version', '1')}.tar.gz"

    out_dir.mkdir(parents=True, exist_ok=True)
    out_archive = out_dir / archive_name

    sorted_paths = sorted(expected_files.keys())

    with open(out_archive, "wb") as f_out:
        with gzip.GzipFile(filename="", mode="wb", fileobj=f_out, mtime=0) as gz_out:
            with tarfile.open(mode="w", fileobj=gz_out) as tar:
                for rel_path in sorted_paths:
                    full_path = source_dir / rel_path
                    ti = tarfile.TarInfo(name=rel_path)
                    ti.size = full_path.stat().st_size
                    ti.mtime = 0
                    ti.uid = 0
                    ti.gid = 0
                    ti.uname = ""
                    ti.gname = ""
                    ti.mode = 0o644
                    ti.type = tarfile.REGTYPE
                    with open(full_path, "rb") as f_in:
                        tar.addfile(ti, f_in)

    archive_bytes = out_archive.stat().st_size
    archive_sha256 = compute_file_sha256(out_archive)

    return out_archive, archive_bytes, archive_sha256


# ---------------------------------------------------------------------------
# Fetch
# ---------------------------------------------------------------------------

def fetch_dataset(
    manifest_path: Path,
    dataset_id: str,
    repo_root: Path,
    cache_base_dir: Path | None = None,
    from_file: Path | None = None,
    copy_mode: bool = False,
    force: bool = False,
) -> None:
    manifest = load_manifest(manifest_path)
    dataset = find_dataset(manifest, dataset_id)

    if dataset.get("storage") != "release":
        print(f"Dataset '{dataset_id}' storage is '{dataset.get('storage')}'; skipping release download.")
        return

    dataset_ver = f"{dataset['id']}@{dataset.get('version', '1')}"
    base_cache = cache_base_dir or default_cache_dir()
    cache_dir = base_cache / dataset_ver
    cache_dir.mkdir(parents=True, exist_ok=True)

    release_info = dataset.get("release", {})
    expected_archive_sha256 = release_info.get("sha256", "").strip().lower()
    archive_name = release_info.get("archive", f"{dataset['id']}.tar.gz")
    archive_path = cache_dir / archive_name

    # Step 1: Obtain archive
    if from_file:
        if not from_file.exists():
            raise FileNotFoundError(f"Local archive file not found: {from_file}")
        shutil.copy2(from_file, archive_path)
    else:
        tag = release_info.get("tag")
        if not tag:
            raise ValueError(f"No release tag defined for dataset '{dataset_id}'")
        repo = manifest.get("repository", "turboism/Turboism")
        url = f"https://github.com/{repo}/releases/download/{tag}/{archive_name}"

        # Download if not present or hash mismatch
        need_download = True
        if archive_path.exists() and not force:
            if expected_archive_sha256:
                if compute_file_sha256(archive_path).lower() == expected_archive_sha256:
                    need_download = False

        if need_download:
            print(f"Downloading {url} ...")
            try:
                urllib.request.urlretrieve(url, archive_path)
            except urllib.error.URLError as e:
                raise RuntimeError(f"Failed to download test corpus from {url}: {e}") from e

    # Verify archive SHA-256
    if expected_archive_sha256:
        actual_archive_sha256 = compute_file_sha256(archive_path).lower()
        if actual_archive_sha256 != expected_archive_sha256:
            raise ValueError(
                f"Archive SHA-256 mismatch for {archive_path.name}:\n"
                f"  Expected: {expected_archive_sha256}\n"
                f"  Actual:   {actual_archive_sha256}"
            )

    # Step 2: Unpack into cache directory with path traversal protection
    extracted_marker = cache_dir / ".extracted_sha256"
    need_extract = force or not extracted_marker.exists()
    if not need_extract:
        with open(extracted_marker, "r", encoding="utf-8") as f:
            if f.read().strip() != expected_archive_sha256:
                need_extract = True

    if need_extract:
        with tarfile.open(archive_path, "r:gz") as tar:
            for member in tar.getmembers():
                if not is_safe_relative_path(member.name):
                    raise ValueError(f"Path traversal detected in archive member: {member.name}")
                dest_candidate = (cache_dir / member.name).resolve()
                if not is_safe_destination(cache_dir, dest_candidate):
                    raise ValueError(f"Path traversal detected resolving: {member.name}")
                if not (member.isreg() or member.isdir()):
                    raise ValueError(f"Unsupported archive member type for {member.name}")

            # Safe to extract
            tar.extractall(path=cache_dir)

        # Write extracted marker
        with open(extracted_marker, "w", encoding="utf-8") as f:
            f.write(expected_archive_sha256 or compute_file_sha256(archive_path))

    # Step 3: Verify extracted files against manifest
    for meta in dataset.get("files", []):
        rel_path = meta["path"]
        if not is_safe_relative_path(rel_path):
            raise ValueError(f"Unsafe path in manifest: {rel_path}")
        cached_file = cache_dir / rel_path
        if not cached_file.exists():
            raise FileNotFoundError(f"Extracted member missing from cache: {cached_file}")

        actual_size = cached_file.stat().st_size
        expected_size = meta.get("bytes")
        if expected_size is not None and actual_size != expected_size:
            raise ValueError(
                f"Cached file size mismatch for '{rel_path}': expected {expected_size}, got {actual_size}"
            )

        expected_hash = meta.get("sha256", "").strip().lower()
        actual_hash = compute_file_sha256(cached_file).lower()
        if expected_hash and actual_hash != expected_hash:
            raise ValueError(
                f"Cached file SHA-256 mismatch for '{rel_path}': expected {expected_hash}, got {actual_hash}"
            )

    # Step 4: Materialize into repo targetDir (symlink or copy)
    target_rel = dataset.get("targetDir")
    if not target_rel:
        raise ValueError(f"No targetDir defined for dataset '{dataset_id}'")
    target_base = (repo_root / target_rel).resolve()
    if not is_safe_destination(repo_root, target_base):
        raise ValueError(f"Target dir {target_rel} resolves outside repository root")

    target_base.mkdir(parents=True, exist_ok=True)

    linked_count = 0
    copied_count = 0
    skipped_count = 0

    for meta in dataset.get("files", []):
        rel_path = meta["path"]
        if not is_safe_relative_path(rel_path):
            raise ValueError(f"Destination path resolves outside target directory: {rel_path}")

        cached_file = (cache_dir / rel_path).resolve()
        dest_file = target_base / rel_path
        dest_parent = dest_file.parent.resolve()
        if not is_safe_destination(target_base, dest_parent):
            raise ValueError(f"Destination path resolves outside target directory: {rel_path}")

        dest_file.parent.mkdir(parents=True, exist_ok=True)

        expected_hash = meta.get("sha256", "").strip().lower()

        # Check existing destination
        if dest_file.is_symlink() or dest_file.exists():
            if not force:
                try:
                    if dest_file.is_symlink() and dest_file.resolve() == cached_file:
                        skipped_count += 1
                        continue
                    if dest_file.is_file() and compute_file_sha256(dest_file).lower() == expected_hash:
                        skipped_count += 1
                        continue
                except OSError:
                    pass
            # Remove stale or mismatched destination
            if dest_file.is_symlink() or dest_file.is_file():
                dest_file.unlink()

        if copy_mode:
            shutil.copy2(cached_file, dest_file)
            copied_count += 1
        else:
            # Create symlink
            os.symlink(cached_file, dest_file)
            linked_count += 1

    print(
        f"Dataset '{dataset_id}': materialized {linked_count} symlinks, "
        f"{copied_count} copies, {skipped_count} skipped (cache: {cache_dir})"
    )


# ---------------------------------------------------------------------------
# Verify
# ---------------------------------------------------------------------------

def verify_dataset(
    manifest_path: Path,
    dataset_id: str | None,
    repo_root: Path,
) -> bool:
    manifest = load_manifest(manifest_path)
    datasets = manifest.get("datasets", [])
    if dataset_id:
        datasets = [find_dataset(manifest, dataset_id)]

    all_passed = True

    for ds in datasets:
        ds_id = ds.get("id")
        storage = ds.get("storage")
        target_rel = ds.get("targetDir")
        target_base = repo_root / target_rel

        files = ds.get("files", [])
        total = len(files)
        ok_count = 0
        missing_count = 0
        mismatch_count = 0

        print(f"\n--- Dataset: {ds_id}@{ds.get('version', '1')} (storage: {storage}) ---")
        for f in files:
            rel = f["path"]
            file_path = target_base / rel
            expected_size = f.get("bytes")
            expected_sha256 = f.get("sha256", "").strip().lower()

            if not file_path.exists():
                print(f"  [MISSING]   {rel}")
                missing_count += 1
                all_passed = False
                continue

            try:
                actual_size = file_path.stat().st_size
                if expected_size is not None and actual_size != expected_size:
                    print(f"  [SIZE FAIL] {rel} (expected {expected_size}, got {actual_size})")
                    mismatch_count += 1
                    all_passed = False
                    continue

                actual_sha256 = compute_file_sha256(file_path).lower()
                if expected_sha256 and actual_sha256 != expected_sha256:
                    print(f"  [HASH FAIL] {rel} (expected {expected_sha256[:12]}..., got {actual_sha256[:12]}...)")
                    mismatch_count += 1
                    all_passed = False
                    continue

                ok_count += 1
            except OSError as e:
                print(f"  [ERR]       {rel}: {e}")
                mismatch_count += 1
                all_passed = False

        print(f"Result: {ok_count}/{total} OK (missing: {missing_count}, mismatched: {mismatch_count})")

    return all_passed


# ---------------------------------------------------------------------------
# List
# ---------------------------------------------------------------------------

def list_datasets(manifest_path: Path, repo_root: Path) -> None:
    manifest = load_manifest(manifest_path)
    datasets = manifest.get("datasets", [])

    print(f"{'ID':<24} {'VER':<5} {'STORAGE':<8} {'LICENSE':<10} {'FILES':<6} {'TOTAL BYTES':<14} {'STATUS':<12}")
    print("-" * 85)

    for ds in datasets:
        ds_id = ds.get("id", "")
        version = str(ds.get("version", "1"))
        storage = ds.get("storage", "")
        license_spdx = ds.get("license", "")
        files = ds.get("files", [])
        total_bytes = sum(f.get("bytes", 0) for f in files)

        target_base = repo_root / ds.get("targetDir", "")
        present = sum(1 for f in files if (target_base / f["path"]).exists())

        if storage == "git":
            status = "git-tracked" if present == len(files) else "git-partial"
        else:
            if present == len(files):
                status = "ready"
            elif present == 0:
                status = "not-fetched"
            else:
                status = f"partial({present}/{len(files)})"

        print(f"{ds_id:<24} {version:<5} {storage:<8} {license_spdx:<10} {len(files):<6} {total_bytes:<14} {status:<12}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Turboism test corpus manager")
    parser.add_argument(
        "--manifest",
        type=Path,
        default=None,
        help="Path to corpus.json (default: test-assets/corpus.json)",
    )
    parser.add_argument(
        "--repo",
        type=Path,
        default=None,
        help="Repository root (default: auto-detected)",
    )

    subparsers = parser.add_subparsers(dest="command", required=True)

    # pack
    pack_p = subparsers.add_parser("pack", help="Pack dataset into deterministic release archive")
    pack_p.add_argument("dataset", help="Dataset ID to pack")
    pack_p.add_argument("--source", type=Path, required=True, help="Directory containing source files")
    pack_p.add_argument("--out", type=Path, required=True, help="Output directory for archive")

    # fetch
    fetch_p = subparsers.add_parser("fetch", help="Fetch dataset archive into cache and materialize in repo")
    fetch_p.add_argument("dataset", nargs="?", default=None, help="Dataset ID or omit if using --all")
    fetch_p.add_argument("--all", dest="all_datasets", action="store_true", help="Fetch all release datasets")
    fetch_p.add_argument("--cache", type=Path, default=None, help="Base cache directory")
    fetch_p.add_argument("--copy", action="store_true", help="Copy files instead of creating symlinks")
    fetch_p.add_argument("--from-file", type=Path, default=None, help="Use local archive instead of downloading")
    fetch_p.add_argument("--force", action="store_true", help="Force re-fetch/re-extraction")

    # verify
    verify_p = subparsers.add_parser("verify", help="Verify local files against manifest")
    verify_p.add_argument("dataset", nargs="?", default=None, help="Dataset ID or omit if using --all")
    verify_p.add_argument("--all", dest="all_datasets", action="store_true", help="Verify all datasets")

    # list
    subparsers.add_parser("list", help="List all datasets and their status")

    args = parser.parse_args(argv)

    repo_root = args.repo or default_repo_root()
    manifest_path = args.manifest or default_manifest_path(repo_root)

    try:
        if args.command == "pack":
            out_file, out_bytes, out_sha256 = pack_dataset(
                manifest_path=manifest_path,
                dataset_id=args.dataset,
                source_dir=args.source,
                out_dir=args.out,
            )
            print("Pack successful:")
            print(f"  Archive: {out_file}")
            print(f"  Bytes:   {out_bytes}")
            print(f"  SHA-256: {out_sha256}")
            return 0

        elif args.command == "fetch":
            manifest = load_manifest(manifest_path)
            if args.all_datasets:
                targets = [ds["id"] for ds in manifest.get("datasets", []) if ds.get("storage") == "release"]
            elif args.dataset:
                targets = [args.dataset]
            else:
                parser.error("Specify a dataset or --all for fetch")
                return 1

            for target in targets:
                fetch_dataset(
                    manifest_path=manifest_path,
                    dataset_id=target,
                    repo_root=repo_root,
                    cache_base_dir=args.cache,
                    from_file=args.from_file,
                    copy_mode=args.copy,
                    force=args.force,
                )
            return 0

        elif args.command == "verify":
            target = None if (args.all_datasets or not args.dataset) else args.dataset
            ok = verify_dataset(
                manifest_path=manifest_path,
                dataset_id=target,
                repo_root=repo_root,
            )
            return 0 if ok else 1

        elif args.command == "list":
            list_datasets(manifest_path=manifest_path, repo_root=repo_root)
            return 0

    except Exception as e:
        print(f"Error: {e}", file=sys.stderr)
        return 1

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
