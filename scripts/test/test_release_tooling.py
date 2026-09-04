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


def archive(
    path: Path,
    version: str,
    plugins: tuple[str, ...] = (),
    extra: tuple[tuple[str, bytes], ...] = (),
) -> None:
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("turboism-agent.jar", agent(version))
        output.writestr("README.txt", f"Turboism {version}\n")
        for plugin in plugins:
            output.writestr(f"plugins/{plugin}.jar", b"plugin")
        for name, content in extra:
            output.writestr(name, content)


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
    PLUGIN_DIRECTORY_REVISION = "c556c90adee0f12b5ce81c9c8108eab8e53aec16"

    def test_minimum_ci_runs_dev_check_for_pull_requests_and_main(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertRegex(workflow, r"(?m)^  pull_request:\s*$")
        self.assertRegex(workflow, r'(?m)^  push:\n    branches: \["main"\]\s*$')
        self.assertEqual(
            workflow.count("./gradlew --no-daemon devCheck --console=plain"),
            1,
        )
        self.assertNotIn("checkIntegration", workflow)
        self.assertNotIn("integration-tests", workflow)
        self.assertRegex(workflow, r"(?m)^permissions:\n  contents: read\s*$")

    def test_plugin_directory_revision_and_publisher_permissions_are_immutable(self):
        candidate_workflows = [
            (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8"),
            (ROOT / ".github/workflows/publish-selected-plugins.yml").read_text(
                encoding="utf-8"
            ),
        ]
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(
            encoding="utf-8"
        )
        pin = f"PLUGIN_DIRECTORY_REVISION: {self.PLUGIN_DIRECTORY_REVISION}"
        for workflow in candidate_workflows:
            self.assertIn(pin, workflow)
            self.assertIn('candidate["pluginDirectoryRevision"] = revision', workflow)
        self.assertIn(pin, publisher)
        self.assertIn("plugin_directory_revision: ${{ steps.plan.outputs.plugin_directory_revision }}", publisher)
        self.assertIn('plan["pluginDirectoryRevision"] = revision', publisher)
        self.assertIn(
            'test "$PLUGIN_DIRECTORY_REVISION" = '
            '"${{ needs.preflight.outputs.plugin_directory_revision }}"',
            publisher,
        )
        self.assertIn('--ref "$PLUGIN_DIRECTORY_REVISION"', publisher)
        verifier_refs = re.findall(
            r"repository: turboism/turboism-plugin-directory\n\s+ref: ([^\n]+)",
            publisher,
        )
        self.assertEqual(
            verifier_refs,
            [
                "${{ env.PLUGIN_DIRECTORY_REVISION }}",
                "${{ needs.preflight.outputs.plugin_directory_revision }}",
            ],
        )
        top_permissions = re.search(
            r"(?m)^permissions:\n((?:  [^\n]+\n)+)", publisher
        )
        self.assertIsNotNone(top_permissions)
        self.assertEqual(
            set(top_permissions.group(1).splitlines()),
            {"  actions: read", "  contents: read"},
        )
        self.assertEqual(publisher.count("contents: write"), 1)
        self.assertRegex(
            publisher,
            r"(?m)^  publish-framework:\n(?:.*\n)*?    permissions:\n"
            r"      actions: read\n      contents: write$",
        )

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

    def test_all_release_verifier_calls_supply_roster_and_windows_stage(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(encoding="utf-8")
        runbook = (ROOT / "RELEASING.md").read_text(encoding="utf-8")
        candidate = (
            ROOT / "scripts/release/turboism_release/candidate.py"
        ).read_text(encoding="utf-8")
        for text in (workflow, publisher, runbook):
            self.assertIn("scripts/release/verify-release.py", text)
            self.assertIn("--release-plugins", text)
            self.assertIn("--windows-stage", text)
        self.assertIn("packaging/release-plugins.txt", candidate)
        self.assertIn('dist.resolve().parent / "staging"', candidate)
        self.assertIn("build/windows-installer/staging", workflow)
        self.assertIn('WINDOWS_STAGE="$(dirname "$DIST")/staging"', publisher)
        self.assertIn("build/windows-installer/staging", runbook)

    def test_candidate_artifact_includes_windows_stage(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertRegex(
            workflow,
            r"(?s)Upload verified candidate payload.*?build/windows-installer/dist"
            r".*?build/windows-installer/staging",
        )

    def test_release_stages_and_dispatches_exact_sdk_documentation(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publisher = (ROOT / ".github/workflows/release-publisher.yml").read_text(encoding="utf-8")
        fallback = (ROOT / ".github/workflows/release-github-only.yml").read_text(encoding="utf-8")
        gradle = (ROOT / "gradle/sdk-docs.gradle.kts").read_text(encoding="utf-8")
        verification = (ROOT / "gradle/verification.gradle.kts").read_text(encoding="utf-8")

        self.assertIn('tasks.registering(Zip::class)', gradle)
        self.assertIn('"sdkDocsBundle"', verification)
        self.assertIn("build/release/sdk-docs", workflow)
        self.assertIn("build/release/sdk-docs-bundle", workflow)
        for dispatch_workflow in (publisher, fallback):
            self.assertIn("DOCS_RELEASE_DISPATCH_TOKEN", dispatch_workflow)
            self.assertIn("sdk-release-published", dispatch_workflow)
            self.assertIn('client_payload[source_sha]', dispatch_workflow)
            self.assertIn('client_payload[version]', dispatch_workflow)
        self.assertIn("needs.preflight.outputs.source_sha", publisher)
        self.assertIn("inputs.source_sha", fallback)

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
        self.assertIn("5.3.03", readme)
        self.assertIn("Current capabilities", readme)
        self.assertIn("CHANGELOG.md", readme)

    def test_public_plugin_readmes_state_complete_host_roster(self):
        readmes = sorted((ROOT / "plugins").glob("*/README*.md"))
        for path in readmes:
            text = path.read_text(encoding="utf-8")
            if "5.2.03" not in text or "5.3.02" not in text:
                continue
            self.assertIn("5.3.03", text, path)
        runtime = (ROOT / "packaging/fx-runtime/README.md").read_text(encoding="utf-8")
        self.assertIn("5.2.03/5.3.02/5.3.03", runtime)

    def test_windows_zip_writer_uses_a_fixed_timestamp(self):
        script = (
            ROOT / "packaging/windows-installer/assemble-release.sh"
        ).read_text(encoding="utf-8")
        self.assertRegex(script, r"TIMESTAMP\s*=\s*\(1980, 1, 1, 0, 0, 0\)")
        self.assertIn("zipfile.ZipInfo(name, TIMESTAMP)", script)
        self.assertIsNone(re.search(r"\bz\.write\(", script))


class ReleaseVerifierTest(unittest.TestCase):
    PLUGINS = ("mcp", "turboism-with-fx")

    def fixture(self, version="0.42.0") -> tuple[Path, Path, Path]:
        root = Path(tempfile.mkdtemp(prefix="release-tooling-"))
        dist = root / "dist"
        stage = root / "stage"
        dist.mkdir()
        stage.mkdir()
        roster = root / "release-plugins.txt"
        roster.write_text(
            ":plugins:core\n:plugins:mcp\n:plugins:turboism-with-fx\n",
            encoding="utf-8",
        )
        lite = dist / f"turboism-{version}-lite.zip"
        full = dist / f"turboism-{version}-full.zip"
        exe = dist / f"TurboismInstaller-{version}.exe"
        jar = dist / f"TurboismInstaller-{version}.jar"
        archive(lite, version)
        archive(full, version, self.PLUGINS)
        exe.write_bytes(b"exe")
        jar.write_bytes(b"jar")
        for path in (lite, full, exe, jar):
            sidecar(path)
        return dist, roster, stage

    def verify(self, fixture: tuple[Path, Path, Path], version="0.42.0") -> None:
        release.verify(fixture[0], version, fixture[1], fixture[2])

    def test_accepts_exact_release(self):
        self.verify(self.fixture())

    def test_emits_manifest_outside_exact_dist_contract(self):
        dist, roster, stage = self.fixture()
        manifest = release.artifact_manifest(dist, "0.42.0", roster, stage)
        self.assertEqual(manifest["format"], "turboism.framework-artifacts")
        self.assertEqual(len(manifest["artifacts"]), 8)
        self.assertEqual(
            sorted(path.name for path in dist.iterdir()),
            sorted(item["name"] for item in manifest["artifacts"]),
        )

    def rewrite_archive(
        self,
        fixture: tuple[Path, Path, Path],
        name: str,
        *,
        version: str = "0.42.0",
        plugins: tuple[str, ...] = (),
        extra: tuple[tuple[str, bytes], ...] = (),
    ) -> None:
        artifact = fixture[0] / name
        archive(artifact, version, plugins, extra)
        sidecar(artifact)

    def test_rejects_snapshot_agent(self):
        fixture = self.fixture()
        self.rewrite_archive(
            fixture,
            "turboism-0.42.0-lite.zip",
            version="0.42.0-SNAPSHOT",
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_full_missing_release_plugin(self):
        fixture = self.fixture()
        self.rewrite_archive(
            fixture,
            "turboism-0.42.0-full.zip",
            plugins=("mcp",),
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_full_unexpected_plugin(self):
        fixture = self.fixture()
        self.rewrite_archive(
            fixture,
            "turboism-0.42.0-full.zip",
            plugins=self.PLUGINS + ("unexpected",),
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_lite_plugin(self):
        fixture = self.fixture()
        self.rewrite_archive(
            fixture,
            "turboism-0.42.0-lite.zip",
            plugins=("turboism-with-fx",),
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_extra_artifact(self):
        fixture = self.fixture()
        (fixture[0] / "unexpected.txt").write_text("x", encoding="utf-8")
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_directory_in_dist(self):
        fixture = self.fixture()
        (fixture[0] / "unexpected").mkdir()
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_symlink_in_dist(self):
        fixture = self.fixture()
        (fixture[0] / "unexpected-link").symlink_to(
            fixture[0] / "TurboismInstaller-0.42.0.exe"
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    @unittest.skipUnless(hasattr(__import__("os"), "mkfifo"), "FIFO unavailable")
    def test_rejects_special_entry_in_dist(self):
        import os

        fixture = self.fixture()
        os.mkfifo(fixture[0] / "unexpected-fifo")
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_rejects_nonportable_sidecar_path(self):
        fixture = self.fixture()
        artifact = fixture[0] / "TurboismInstaller-0.42.0.exe"
        artifact.with_name(artifact.name + ".sha256").write_text(
            f"{release.sha256(artifact)}  build/windows-installer/dist/{artifact.name}\n",
            encoding="utf-8",
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)


if __name__ == "__main__":
    unittest.main(verbosity=2)
