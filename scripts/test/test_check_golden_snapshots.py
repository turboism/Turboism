#!/usr/bin/env python3
"""Stdlib runnable suite for scripts/check_golden_snapshots.py.

Run:  python3 scripts/test/test_check_golden_snapshots.py

The manifest gate is the only thing standing between an agent editing a fixture
byte to make a migration pass and a silently reset user installation, so every
rejection path is covered instead of only the happy path: a changed hash, an
unlisted or missing fixture, a symlinked fixture, an escaped/absolute path, a
duplicate path, a bogus provenance and an empty inventory must all fail closed.

Each case runs the real script as a subprocess inside a throwaway tree that
mirrors the repository layout (`scripts/` plus `tests/fixtures/snapshots/`), so
the checker's own path resolution is exercised rather than stubbed.
"""

import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
CHECKER = SCRIPTS_DIR / "check_golden_snapshots.py"
REPO_ROOT = SCRIPTS_DIR.parent
REPO_FIXTURES = REPO_ROOT / "tests" / "fixtures" / "snapshots"
FIXTURE_BYTES = b'{\n  "format": "turboism.runtime.config",\n  "schemaVersion": 1\n}\n'
GOOD_CASE = {
    "id": "runtime-config-v1",
    "family": "runtime-config",
    "origin": "synthetic-format-v1",
    "path": "runtime-config/schema-v1/legacy-user.json",
    "expectation": "global-config-read-and-update-preserve-user-settings",
}
VERIFIED_PROVENANCE = {
    "origin": "verified-release-artifact",
    "releaseTag": "v0.42.0",
    "releaseCommit": "4f75113d7cb269d3e14dde0077d02fb43cd70dad",
    "writer": "RuntimeConfigRepository#read",
    "producerInvocation": "java -cp agent-0.42.0.jar:driver dev.turboism.config.ConfigGen <empty-home>",
    "deidentification": "Generated into an empty scratch home from release defaults().",
}


def sha256(payload):
    return hashlib.sha256(payload).hexdigest()


class SnapshotTree:
    """Throwaway repository tree with a valid one-case inventory."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="golden-snapshots-"))
        (self.root / "scripts").mkdir()
        shutil.copy2(CHECKER, self.root / "scripts" / CHECKER.name)
        self.fixtures = self.root / "tests" / "fixtures" / "snapshots"
        self.fixtures.mkdir(parents=True)
        self.checker = self.root / "scripts" / CHECKER.name
        case = dict(GOOD_CASE)
        case["sha256"] = sha256(FIXTURE_BYTES)
        self.write_manifest([case])
        self.write_fixture(case["path"], FIXTURE_BYTES)

    def write_manifest(self, cases):
        payload = {"format": "turboism-persistence-golden-snapshots-v1", "snapshots": cases}
        (self.fixtures / "manifest.json").write_text(json.dumps(payload), encoding="utf-8")

    def write_fixture(self, relative, payload):
        target = self.fixtures / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)

    def case(self, **overrides):
        case = dict(GOOD_CASE)
        case["sha256"] = sha256(FIXTURE_BYTES)
        case.update(overrides)
        return case

    def verified_case(self, **overrides):
        provenance = dict(VERIFIED_PROVENANCE)
        provenance.update(overrides)
        return self.case(**provenance)

    def run(self):
        env = dict(os.environ)
        env.pop("PYTHONPATH", None)
        return subprocess.run(
            [sys.executable, str(self.checker)],
            capture_output=True,
            text=True,
            env=env,
            check=False,
        )

    def cleanup(self):
        shutil.rmtree(self.root, ignore_errors=True)


class GoldenSnapshotManifestTest(unittest.TestCase):
    def setUp(self):
        self.tree = SnapshotTree()
        self.addCleanup(self.tree.cleanup)

    def assert_rejected(self, result, fragment):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(fragment, result.stderr)

    def test_pristine_inventory_passes(self):
        result = self.tree.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1 immutable fixtures verified", result.stdout)

    def test_changed_fixture_bytes_are_rejected(self):
        self.tree.write_fixture(GOOD_CASE["path"], FIXTURE_BYTES + b" ")
        self.assert_rejected(self.tree.run(), "snapshot hash changed")

    def test_unlisted_fixture_is_rejected(self):
        self.tree.write_fixture("typed-config/schema-v1/stray.cfg", b"schemaVersion=1\n")
        self.assert_rejected(self.tree.run(), "inventory mismatch")

    def test_missing_fixture_is_rejected(self):
        (self.tree.fixtures / GOOD_CASE["path"]).unlink()
        self.assert_rejected(self.tree.run(), "missing or unsafe fixture")

    def test_symlinked_fixture_is_rejected(self):
        target = self.tree.fixtures / GOOD_CASE["path"]
        outside = self.tree.root / "outside.json"
        outside.write_bytes(FIXTURE_BYTES)
        target.unlink()
        target.symlink_to(outside)
        self.assert_rejected(self.tree.run(), "missing or unsafe fixture")

    def test_escaped_manifest_path_is_rejected(self):
        self.tree.write_manifest([self.tree.case(path="../escaped.json")])
        self.assert_rejected(self.tree.run(), "invalid snapshot id/path")

    def test_absolute_manifest_path_is_rejected(self):
        self.tree.write_manifest([self.tree.case(path="/etc/passwd")])
        self.assert_rejected(self.tree.run(), "invalid snapshot id/path")

    def test_windows_separator_path_is_rejected(self):
        self.tree.write_manifest([self.tree.case(path="runtime-config\\schema-v1\\legacy-user.json")])
        self.assert_rejected(self.tree.run(), "invalid snapshot id/path")

    def test_duplicate_fixture_path_is_rejected(self):
        self.tree.write_manifest([self.tree.case(), self.tree.case(id="runtime-config-v1-again")])
        self.assert_rejected(self.tree.run(), "duplicate snapshot path")

    def test_unknown_provenance_is_rejected(self):
        self.tree.write_manifest([self.tree.case(origin="invented-release")])
        self.assert_rejected(self.tree.run(), "invalid snapshot provenance")

    def test_verified_release_artifact_with_provenance_passes(self):
        self.tree.write_manifest([self.tree.verified_case()])
        result = self.tree.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("1 immutable fixtures verified", result.stdout)

    def test_verified_release_artifact_missing_provenance_is_rejected(self):
        for field in ("releaseTag", "releaseCommit", "writer", "producerInvocation", "deidentification"):
            with self.subTest(field=field):
                case = self.tree.verified_case()
                del case[field]
                self.tree.write_manifest([case])
                self.assert_rejected(self.tree.run(), "missing verified provenance")

    def test_verified_release_artifact_blank_provenance_is_rejected(self):
        self.tree.write_manifest([self.tree.verified_case(writer="   ")])
        self.assert_rejected(self.tree.run(), "missing verified provenance")

    def test_verified_release_artifact_malformed_tag_is_rejected(self):
        self.tree.write_manifest([self.tree.verified_case(releaseTag="0.42.0")])
        self.assert_rejected(self.tree.run(), "invalid releaseTag")

    def test_verified_release_artifact_malformed_commit_is_rejected(self):
        self.tree.write_manifest([self.tree.verified_case(releaseCommit="not-a-commit")])
        self.assert_rejected(self.tree.run(), "invalid releaseCommit")

    def test_document_path_traversal_is_rejected(self):
        self.tree.write_manifest([self.tree.verified_case(documentPath="../escaped.cfg")])
        self.assert_rejected(self.tree.run(), "invalid documentPath")

    def test_family_mismatch_is_rejected(self):
        self.tree.write_manifest([self.tree.case(family="typed-config")])
        self.assert_rejected(self.tree.run(), "invalid snapshot provenance")

    def test_missing_expectation_is_rejected(self):
        case = self.tree.case()
        del case["expectation"]
        self.tree.write_manifest([case])
        self.assert_rejected(self.tree.run(), "missing compatibility oracle")

    def test_empty_inventory_is_rejected(self):
        self.tree.write_manifest([])
        self.assert_rejected(self.tree.run(), "at least one case")

    def test_missing_manifest_is_rejected(self):
        (self.tree.fixtures / "manifest.json").unlink()
        self.assert_rejected(self.tree.run(), "No such file")

    def test_repository_inventory_still_passes(self):
        """The tracked manifest must stay valid; a synthetic-only suite would drift."""
        result = subprocess.run(
            [sys.executable, str(CHECKER)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = json.loads((REPO_FIXTURES / "manifest.json").read_text(encoding="utf-8"))
        self.assertIn(f"{len(manifest['snapshots'])} immutable fixtures verified", result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
