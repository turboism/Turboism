#!/usr/bin/env python3
"""Tests for product release note and artifact verification tooling."""
from __future__ import annotations

import importlib.util
import io
import re
import tempfile
import unittest
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


notes = load("extract_release_notes", ROOT / "scripts/release/extract-release-notes.py")
release = load("verify_release", ROOT / "scripts/release/verify-release.py")


def agent(version: str) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as archive:
        archive.writestr(
            "META-INF/MANIFEST.MF",
            "Manifest-Version: 1.0\r\nImplementation-Version: " + version + "\r\n\r\n",
        )
    return output.getvalue()


def archive(path: Path, version: str, full: bool) -> None:
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("turboism-agent.jar", agent(version))
        output.writestr("README.txt", f"Turboism {version}\n")
        if full:
            output.writestr("plugins/example.jar", b"plugin")


def sidecar(path: Path) -> None:
    path.with_name(path.name + ".sha256").write_text(
        f"{release.sha256(path)}  build/windows-installer/dist/{path.name}\n",
        encoding="utf-8",
    )


class ReleaseNotesTest(unittest.TestCase):
    def test_extracts_exact_section(self):
        text = "# Changelog\n\n## [Unreleased]\n\nNext\n\n## [0.42.0] - 2026-08-25\n\nBody\n\n## [0.41.0] - 2026-08-01\n\nOld\n"
        self.assertEqual(notes.extract(text, "0.42.0"), "Body\n")

    def test_rejects_missing_section(self):
        with self.assertRaises(ValueError):
            notes.extract("# Changelog\n", "0.42.0")


class ReleaseWorkflowTest(unittest.TestCase):
    def test_does_not_claim_unavailable_licensed_evidence(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertNotIn("TURBOISM_LEGACY_EVIDENCE", workflow)
        self.assertIn("./gradlew --no-daemon checkRelease", workflow)

    def test_windows_zip_writer_uses_a_fixed_timestamp(self):
        script = (
            ROOT / "packaging/windows-installer/assemble-release.sh"
        ).read_text(encoding="utf-8")
        self.assertRegex(script, r"TIMESTAMP\s*=\s*\(1980, 1, 1, 0, 0, 0\)")
        self.assertIn("zipfile.ZipInfo(name, TIMESTAMP)", script)
        self.assertIsNone(re.search(r"\bz\.write\(", script))


class ReleaseVerifierTest(unittest.TestCase):
    def fixture(self, version="0.42.0") -> Path:
        dist = Path(tempfile.mkdtemp(prefix="release-tooling-"))
        lite = dist / f"turboism-{version}-lite.zip"
        full = dist / f"turboism-{version}-full.zip"
        exe = dist / f"TurboismInstaller-{version}.exe"
        jar = dist / f"TurboismInstaller-{version}.jar"
        archive(lite, version, False)
        archive(full, version, True)
        exe.write_bytes(b"exe")
        jar.write_bytes(b"jar")
        for path in (lite, full, exe, jar):
            sidecar(path)
        return dist

    def test_accepts_exact_release(self):
        release.verify(self.fixture(), "0.42.0")

    def test_rejects_snapshot_agent(self):
        dist = self.fixture()
        lite = dist / "turboism-0.42.0-lite.zip"
        archive(lite, "0.42.0-SNAPSHOT", False)
        sidecar(lite)
        with self.assertRaises(ValueError):
            release.verify(dist, "0.42.0")

    def test_rejects_extra_artifact(self):
        dist = self.fixture()
        (dist / "unexpected.txt").write_text("x", encoding="utf-8")
        with self.assertRaises(ValueError):
            release.verify(dist, "0.42.0")


if __name__ == "__main__":
    unittest.main(verbosity=2)
