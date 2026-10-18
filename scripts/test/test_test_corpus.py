#!/usr/bin/env python3
"""Unit tests for scripts/test_corpus.py.

Covers:
  - Deterministic and reproducible archive packing (byte-for-byte identical)
  - Tamper detection (archive hash mismatch and member hash mismatch)
  - Rejection of path traversal in archives and manifests
  - Rejection of unmanifested files during pack
  - Fetch materialization (symlink and copy modes, idempotent skip)
  - Verification reporting (missing, mismatched, valid)
"""

from __future__ import annotations

import gzip
import hashlib
import json
import shutil
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path

_SCRIPTS_DIR = str(Path(__file__).resolve().parents[1])
if _SCRIPTS_DIR not in sys.path:
    sys.path.insert(0, _SCRIPTS_DIR)
import test_corpus


def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


class TestCorpusToolTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp_dir = tempfile.mkdtemp(prefix="turboism-corpus-test-")
        self.base = Path(self.temp_dir)
        self.repo = self.base / "repo"
        self.repo.mkdir()
        self.cache = self.base / "cache"
        self.cache.mkdir()

        # Create dummy source files
        self.file1_content = b"fixture data 1\n"
        self.file2_content = b"fixture data 2: circle cmo3 payload\n"

        self.file1_sha256 = sha256_bytes(self.file1_content)
        self.file2_sha256 = sha256_bytes(self.file2_content)

        self.manifest_path = self.repo / "test-assets" / "corpus.json"
        self.manifest_path.parent.mkdir(parents=True)

        self.manifest_data = {
            "schemaVersion": 1,
            "repository": "turboism/Turboism",
            "datasets": [
                {
                    "id": "sample-dataset",
                    "version": "1",
                    "datasetId": "sample-dataset@1",
                    "license": "CC0-1.0",
                    "storage": "release",
                    "source": "Synthetic test fixture",
                    "targetDir": "test-assets/sample-dataset",
                    "release": {
                        "tag": "test-corpus-sample-v1",
                        "archive": "sample-dataset-v1.tar.gz",
                        "bytes": 0,
                        "sha256": "",
                    },
                    "usedBy": ["test"],
                    "files": [
                        {
                            "path": "cmo3/sample1.cmo3",
                            "bytes": len(self.file1_content),
                            "sha256": self.file1_sha256,
                        },
                        {
                            "path": "psd/sample2.psd",
                            "bytes": len(self.file2_content),
                            "sha256": self.file2_sha256,
                        },
                    ],
                }
            ],
        }
        self._write_manifest(self.manifest_data)

        self.source_dir = self.base / "source"
        self.source_dir.mkdir()
        (self.source_dir / "cmo3").mkdir()
        (self.source_dir / "psd").mkdir()
        (self.source_dir / "cmo3" / "sample1.cmo3").write_bytes(self.file1_content)
        (self.source_dir / "psd" / "sample2.psd").write_bytes(self.file2_content)

    def tearDown(self) -> None:
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _write_manifest(self, data: dict) -> None:
        with open(self.manifest_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2)

    def test_pack_reproducible(self) -> None:
        """Packing identical source files twice must produce identical byte-for-byte archives."""
        out1 = self.base / "out1"
        out2 = self.base / "out2"

        arc1, size1, hash1 = test_corpus.pack_dataset(
            self.manifest_path, "sample-dataset", self.source_dir, out1
        )
        arc2, size2, hash2 = test_corpus.pack_dataset(
            self.manifest_path, "sample-dataset", self.source_dir, out2
        )

        self.assertEqual(size1, size2)
        self.assertEqual(hash1, hash2)
        self.assertEqual(arc1.read_bytes(), arc2.read_bytes())

    def test_pack_rejects_unmanifested_files(self) -> None:
        """Files in source directory not listed in manifest must be rejected."""
        unmanifested = self.source_dir / "extra.cmo3"
        unmanifested.write_bytes(b"unexpected")

        out = self.base / "out_unmanifested"
        with self.assertRaises(ValueError) as ctx:
            test_corpus.pack_dataset(
                self.manifest_path, "sample-dataset", self.source_dir, out
            )
        self.assertIn("not listed in manifest", str(ctx.exception))
        self.assertIn("extra.cmo3", str(ctx.exception))

    def test_pack_rejects_missing_files(self) -> None:
        """Missing manifest members must raise FileNotFoundError."""
        (self.source_dir / "cmo3" / "sample1.cmo3").unlink()
        out = self.base / "out_missing"
        with self.assertRaises(FileNotFoundError) as ctx:
            test_corpus.pack_dataset(
                self.manifest_path, "sample-dataset", self.source_dir, out
            )
        self.assertIn("missing files", str(ctx.exception))

    def test_pack_rejects_hash_mismatch(self) -> None:
        """Tampered member file in source must be rejected with SHA-256 mismatch."""
        (self.source_dir / "cmo3" / "sample1.cmo3").write_bytes(b"tampered content")
        out = self.base / "out_mismatch"
        with self.assertRaises(ValueError) as ctx:
            test_corpus.pack_dataset(
                self.manifest_path, "sample-dataset", self.source_dir, out
            )
        self.assertTrue(
            "SHA-256 mismatch" in str(ctx.exception)
            or "Size mismatch" in str(ctx.exception)
        )

    def test_fetch_rejects_archive_tamper(self) -> None:
        """Archive whose SHA-256 does not match manifest must be rejected."""
        out = self.base / "out"
        arc, size, hash_val = test_corpus.pack_dataset(
            self.manifest_path, "sample-dataset", self.source_dir, out
        )

        # Update manifest with valid size and a forged hash
        self.manifest_data["datasets"][0]["release"]["bytes"] = size
        self.manifest_data["datasets"][0]["release"]["sha256"] = "0" * 64
        self._write_manifest(self.manifest_data)

        with self.assertRaises(ValueError) as ctx:
            test_corpus.fetch_dataset(
                manifest_path=self.manifest_path,
                dataset_id="sample-dataset",
                repo_root=self.repo,
                cache_base_dir=self.cache,
                from_file=arc,
            )
        self.assertIn("Archive SHA-256 mismatch", str(ctx.exception))

    def test_fetch_rejects_member_tamper(self) -> None:
        """Archive with tampered member must be rejected even if archive hash matches."""
        # Create an archive with altered member content
        tampered_arc = self.base / "tampered.tar.gz"
        with open(tampered_arc, "wb") as f_out:
            with gzip.GzipFile(filename="", mode="wb", fileobj=f_out, mtime=0) as gz:
                with tarfile.open(mode="w", fileobj=gz) as tar:
                    data = b"bad data"
                    ti = tarfile.TarInfo(name="cmo3/sample1.cmo3")
                    ti.size = len(data)
                    ti.mtime = 0
                    tar.addfile(ti, gzip.io.BytesIO(data))
                    data2 = self.file2_content
                    ti2 = tarfile.TarInfo(name="psd/sample2.psd")
                    ti2.size = len(data2)
                    ti2.mtime = 0
                    tar.addfile(ti2, gzip.io.BytesIO(data2))

        # Put this archive's hash in manifest release
        tampered_hash = sha256_bytes(tampered_arc.read_bytes())
        self.manifest_data["datasets"][0]["release"]["bytes"] = tampered_arc.stat().st_size
        self.manifest_data["datasets"][0]["release"]["sha256"] = tampered_hash
        self._write_manifest(self.manifest_data)

        with self.assertRaises(ValueError) as ctx:
            test_corpus.fetch_dataset(
                manifest_path=self.manifest_path,
                dataset_id="sample-dataset",
                repo_root=self.repo,
                cache_base_dir=self.cache,
                from_file=tampered_arc,
            )
        self.assertTrue(
            "Cached file SHA-256 mismatch" in str(ctx.exception)
            or "Cached file size mismatch" in str(ctx.exception)
        )

    def test_fetch_rejects_path_traversal_in_archive(self) -> None:
        """Archive containing path traversal members must be rejected."""
        evil_arc = self.base / "evil.tar.gz"
        with open(evil_arc, "wb") as f_out:
            with gzip.GzipFile(filename="", mode="wb", fileobj=f_out, mtime=0) as gz:
                with tarfile.open(mode="w", fileobj=gz) as tar:
                    data = b"malicious"
                    ti = tarfile.TarInfo(name="../escape.txt")
                    ti.size = len(data)
                    ti.mtime = 0
                    tar.addfile(ti, gzip.io.BytesIO(data))

        evil_hash = sha256_bytes(evil_arc.read_bytes())
        self.manifest_data["datasets"][0]["release"]["sha256"] = evil_hash
        self._write_manifest(self.manifest_data)

        with self.assertRaises(ValueError) as ctx:
            test_corpus.fetch_dataset(
                manifest_path=self.manifest_path,
                dataset_id="sample-dataset",
                repo_root=self.repo,
                cache_base_dir=self.cache,
                from_file=evil_arc,
            )
        self.assertIn("Path traversal", str(ctx.exception))

    def test_fetch_symlink_and_copy_modes_and_verify(self) -> None:
        """fetch creates symlinks by default, copies with --copy, and verify passes."""
        out = self.base / "out_valid"
        arc, size, hash_val = test_corpus.pack_dataset(
            self.manifest_path, "sample-dataset", self.source_dir, out
        )

        self.manifest_data["datasets"][0]["release"]["bytes"] = size
        self.manifest_data["datasets"][0]["release"]["sha256"] = hash_val
        self._write_manifest(self.manifest_data)

        # 1. Fetch with symlinks
        test_corpus.fetch_dataset(
            manifest_path=self.manifest_path,
            dataset_id="sample-dataset",
            repo_root=self.repo,
            cache_base_dir=self.cache,
            from_file=arc,
            copy_mode=False,
        )

        target1 = self.repo / "test-assets" / "sample-dataset" / "cmo3" / "sample1.cmo3"
        target2 = self.repo / "test-assets" / "sample-dataset" / "psd" / "sample2.psd"

        self.assertTrue(target1.is_symlink())
        self.assertTrue(target2.is_symlink())
        self.assertEqual(target1.read_bytes(), self.file1_content)
        self.assertEqual(target2.read_bytes(), self.file2_content)

        # Verify passes
        self.assertTrue(
            test_corpus.verify_dataset(self.manifest_path, "sample-dataset", self.repo)
        )

        # 2. Re-fetch without force is idempotent
        test_corpus.fetch_dataset(
            manifest_path=self.manifest_path,
            dataset_id="sample-dataset",
            repo_root=self.repo,
            cache_base_dir=self.cache,
            from_file=arc,
            copy_mode=False,
        )
        self.assertTrue(target1.is_symlink())

        # 3. Fetch with copy mode replaces symlinks with real files
        test_corpus.fetch_dataset(
            manifest_path=self.manifest_path,
            dataset_id="sample-dataset",
            repo_root=self.repo,
            cache_base_dir=self.cache,
            from_file=arc,
            copy_mode=True,
            force=True,
        )
        self.assertFalse(target1.is_symlink())
        self.assertTrue(target1.is_file())
        self.assertEqual(target1.read_bytes(), self.file1_content)
        self.assertTrue(
            test_corpus.verify_dataset(self.manifest_path, "sample-dataset", self.repo)
        )

    def test_verify_detects_missing_and_tampered_files(self) -> None:
        """verify_dataset returns False when files are missing or tampered."""
        target_dir = self.repo / "test-assets" / "sample-dataset"
        (target_dir / "cmo3").mkdir(parents=True)
        (target_dir / "psd").mkdir(parents=True)

        target1 = target_dir / "cmo3" / "sample1.cmo3"
        target2 = target_dir / "psd" / "sample2.psd"

        # Missing target2
        target1.write_bytes(self.file1_content)
        self.assertFalse(
            test_corpus.verify_dataset(self.manifest_path, "sample-dataset", self.repo)
        )

        # Tampered target2
        target2.write_bytes(b"corrupted")
        self.assertFalse(
            test_corpus.verify_dataset(self.manifest_path, "sample-dataset", self.repo)
        )


if __name__ == "__main__":
    unittest.main()
