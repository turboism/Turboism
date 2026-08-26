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
audit = load("audit_v042", ROOT / "scripts/release/audit-v0.42.0.py")


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
        f"{release.sha256(path)}  {path.name}\n",
        encoding="utf-8",
    )


class ReleaseNotesTest(unittest.TestCase):
    def test_extracts_exact_section(self):
        text = "# Changelog\n\n## [Unreleased]\n\nNext\n\n## [0.42.0] - 2026-08-25\n\nBody\n\n## [0.41.0] - 2026-08-01\n\nOld\n"
        self.assertEqual(notes.extract(text, "0.42.0"), "Body\n")

    def test_rejects_missing_section(self):
        with self.assertRaises(ValueError):
            notes.extract("# Changelog\n", "0.42.0")


class ReleaseBaselineAuditTest(unittest.TestCase):
    def test_v042_contract_remains_exactly_eight_assets(self):
        self.assertEqual(len(audit.EXPECTED), 8)
        self.assertEqual(
            set(audit.EXPECTED),
            {
                "turboism-0.42.0-full.zip",
                "turboism-0.42.0-full.zip.sha256",
                "turboism-0.42.0-lite.zip",
                "turboism-0.42.0-lite.zip.sha256",
                "TurboismInstaller-0.42.0.exe",
                "TurboismInstaller-0.42.0.exe.sha256",
                "TurboismInstaller-0.42.0.jar",
                "TurboismInstaller-0.42.0.jar.sha256",
            },
        )


class ReleaseWorkflowTest(unittest.TestCase):
    def test_does_not_claim_unavailable_licensed_evidence(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertNotIn("TURBOISM_LEGACY_EVIDENCE", workflow)
        self.assertIn("./gradlew --no-daemon checkRelease", workflow)

    def test_release_notes_are_extracted_from_changelog(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("scripts/release/extract-release-notes.py", workflow)
        self.assertIn('CHANGELOG.md "${{ steps.version.outputs.version }}"', workflow)
        self.assertIn("build/release-orchestrator/release-notes.md", workflow)
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(encoding="utf-8")
        self.assertIn('--notes-file "$NOTES"', publisher)
        self.assertNotIn("--generate-notes", workflow + publisher)

    def test_release_workflow_never_clobbers_immutable_assets(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(encoding="utf-8")
        self.assertNotIn("--clobber", workflow + publisher)
        self.assertIn("scripts/check_remote_hygiene.py --all", workflow)
        self.assertIn("turboism-release.py", workflow)

    def test_product_candidate_is_tag_only_and_can_publish_plugins_too(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertNotIn("workflow_dispatch:", workflow)
        self.assertIn("Validate store eligibility policy", workflow)
        self.assertIn("Stage all eligible plugin candidates", workflow)
        self.assertIn("Upload market publication bundle", workflow)
        self.assertIn("--market-dir build/market-release", workflow)

    def test_protected_publisher_correlates_and_verifies_provider(self):
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(encoding="utf-8")
        self.assertIn(".github/workflows/release.yml", publisher)
        self.assertIn(".github/workflows/publish-selected-plugins.yml", publisher)
        self.assertIn("expected exactly one Plugin Directory run", publisher)
        self.assertIn("gh run watch", publisher)
        self.assertIn("verify-plugin-publication.py", publisher)
        self.assertIn("--plugin-directory-repo build/release-publisher/plugin-directory", publisher)
        self.assertIn("build-updates-manifests.py", publisher)
        self.assertIn("publish-release.yml", publisher)
        self.assertIn("publish-stable-pointer.yml", publisher)
        self.assertIn("publish-plugin-catalog.yml", publisher)
        self.assertIn("turboism-framework-manifest-", publisher)
        self.assertIn("manifest_artifact_name", publisher)
        self.assertIn("gh run download", publisher)
        self.assertIn("turboism-release-plan-${EXPECTED_PLAN_ID}", publisher)
        self.assertIn("RECOVERED=true", publisher)
        self.assertNotIn("--extra-allowed release.json", publisher)
        self.assertNotIn('gh release upload "$TAG" --repo "$GITHUB_REPOSITORY" build/release-publisher/release.json', publisher)
        self.assertIn("contains(fromJSON('[\"plugins\",\"combined\"]'), needs.preflight.outputs.intent)", publisher)
        self.assertNotIn("Plugin Directory deployment must be verified", publisher)
        self.assertNotIn("Updates repository dispatch requires", publisher)

    def test_releasing_runbook_documents_one_command_and_roster_separation(self):
        runbook = (ROOT / "RELEASING.md").read_text(encoding="utf-8")
        self.assertIn("turboism-release.py release", runbook)
        self.assertIn("--candidate-run-id <completed-actions-run-id>", runbook)
        self.assertIn("packaging/release-plugins.txt", runbook)
        self.assertIn("packaging/market-plugins.json", runbook)
        self.assertIn("not thereby published", runbook)

    def test_readme_states_supported_host_versions_and_platform(self):
        readme = (ROOT / "README.md").read_text(encoding="utf-8")
        self.assertIn("Windows x64", readme)
        self.assertIn("5.2.03", readme)
        self.assertIn("5.3.02", readme)
        self.assertIn("Current capabilities", readme)
        self.assertIn("CHANGELOG.md", readme)

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

    def test_emits_manifest_outside_exact_dist_contract(self):
        dist = self.fixture()
        manifest = release.artifact_manifest(dist, "0.42.0")
        self.assertEqual(manifest["format"], "turboism.framework-artifacts")
        self.assertEqual(len(manifest["artifacts"]), 8)
        self.assertEqual(
            sorted(path.name for path in dist.iterdir()),
            sorted(item["name"] for item in manifest["artifacts"]),
        )

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

    def test_rejects_nonportable_sidecar_path(self):
        dist = self.fixture()
        artifact = dist / "TurboismInstaller-0.42.0.exe"
        artifact.with_name(artifact.name + ".sha256").write_text(
            f"{release.sha256(artifact)}  build/windows-installer/dist/{artifact.name}\n",
            encoding="utf-8",
        )
        with self.assertRaises(ValueError):
            release.verify(dist, "0.42.0")


if __name__ == "__main__":
    unittest.main(verbosity=2)
