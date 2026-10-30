#!/usr/bin/env python3
"""Tests for product release note and artifact verification tooling."""
from __future__ import annotations

import importlib.util
import io
import json
import hashlib
import base64
import os
import stat
import sys
import subprocess
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


sys.path.insert(0, str(ROOT / "scripts/release"))
from turboism_release import framework_update


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


def sdk_archive(path: Path) -> None:
    """Minimal developer SDK JAR carrying the plugin lifecycle contract class."""
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        output.writestr("dev/turboism/sdk/plugin/TurboismPlugin.class", b"class")


def event_processor_archive(path: Path) -> None:
    """Minimal developer event-processor JAR carrying the annotation processor."""
    with zipfile.ZipFile(path, "w") as output:
        output.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\n\r\n")
        output.writestr(
            "dev/turboism/eventprocessor/SubscribeEventProcessor.class",
            b"class",
        )
        output.writestr(
            "META-INF/services/javax.annotation.processing.Processor",
            "dev.turboism.eventprocessor.SubscribeEventProcessor\n",
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
    PLUGIN_DIRECTORY_REVISION = "8545f9d1d5b36fdc8501a681bd2667a5eb0e8aa1"

    def test_minimum_ci_runs_dev_check_for_pull_requests_and_main(self):
        workflow = (ROOT / ".github/workflows/ci.yml").read_text(encoding="utf-8")
        self.assertRegex(workflow, r"(?m)^  pull_request:\s*$")
        self.assertRegex(workflow, r'(?m)^  push:\n    branches: \["main"\]\s*$')
        self.assertEqual(
            workflow.count(
                "./gradlew --no-daemon devCheck checkCompletedCommit --console=plain"
            ),
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
        publisher = (ROOT / ".github/workflows/release-github-only.yml").read_text(
            encoding="utf-8"
        )
        self.assertFalse((ROOT / ".github/workflows/release-publisher.yml").exists())
        pin = f"PLUGIN_DIRECTORY_REVISION: {self.PLUGIN_DIRECTORY_REVISION}"
        for workflow in candidate_workflows:
            self.assertIn(pin, workflow)
            self.assertIn('candidate["pluginDirectoryRevision"] = revision', workflow)
        self.assertIn(pin, publisher)
        # workflow_dispatch needs a branch ref: dispatch on main, guarded so main may
        # only differ from the pinned revision by earlier catalog publications.
        self.assertIn("--ref main", publisher)
        self.assertIn("compare/${PLUGIN_DIRECTORY_REVISION}...main", publisher)
        self.assertIn('startswith("public/api/v2/") or . == "catalog/v2/catalog.json"', publisher)
        verifier_refs = re.findall(
            r"repository: turboism/turboism-plugin-directory\n\s+ref: ([^\n]+)",
            publisher,
        )
        self.assertEqual(
            verifier_refs,
            ["${{ env.PLUGIN_DIRECTORY_REVISION }}"],
        )
        top_permissions = re.search(
            r"(?m)^permissions:\n((?:  [^\n]+\n)+)", publisher
        )
        self.assertIsNotNone(top_permissions)
        self.assertEqual(
            set(top_permissions.group(1).splitlines()),
            {"  actions: read", "  contents: write"},
        )
        self.assertEqual(publisher.count("contents: write"), 1)

    def test_does_not_claim_unavailable_licensed_evidence(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertNotIn("TURBOISM_LEGACY_EVIDENCE", workflow)
        self.assertIn("./gradlew --no-daemon checkRelease", workflow)

    def test_release_notes_are_extracted_from_changelog(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("scripts/release/extract-release-notes.py", workflow)
        self.assertIn('CHANGELOG.md "${{ steps.version.outputs.version }}"', workflow)
        self.assertIn("build/release-orchestrator/release-notes.md", workflow)
        publisher = (ROOT / ".github/workflows/release-github-only.yml").read_text(encoding="utf-8")
        self.assertNotIn("--generate-notes", workflow + publisher)

    def test_release_workflow_never_clobbers_immutable_assets(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        publisher = (ROOT / ".github/workflows/release-github-only.yml").read_text(encoding="utf-8")
        self.assertNotIn("--clobber", workflow + publisher)
        self.assertIn("scripts/check_remote_hygiene.py --all", workflow)
        self.assertIn("turboism-release.py", workflow)

    def test_all_release_verifier_calls_supply_roster_and_windows_stage(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        candidate = (
            ROOT / "scripts/release/turboism_release/candidate.py"
        ).read_text(encoding="utf-8")
        for text in (workflow,):
            self.assertIn("scripts/release/verify-release.py", text)
            self.assertIn("--release-plugins", text)
            self.assertIn("--windows-stage", text)
        self.assertIn("packaging/release-plugins.txt", candidate)
        self.assertIn('dist.resolve().parent / "staging"', candidate)
        self.assertIn("build/windows-installer/staging", workflow)

    def test_candidate_artifact_includes_windows_stage(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertRegex(
            workflow,
            r"(?s)Upload verified candidate payload.*?build/windows-installer/dist"
            r".*?build/windows-installer/staging",
        )

    def test_product_candidate_is_read_only_dispatch_and_can_build_plugins_too(self):
        workflow = (ROOT / ".github/workflows/release.yml").read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", workflow)
        self.assertNotRegex(workflow, r"(?m)^  push:")
        self.assertIn("expected_source_sha:", workflow)
        self.assertIn('test "$EXPECTED_SOURCE_SHA" = "$GITHUB_SHA"', workflow)
        self.assertRegex(workflow, r"(?m)^permissions:\n  contents: read$")
        self.assertNotRegex(workflow, r"\$\{\{[^}]*\bsecrets[.\[]")
        self.assertNotIn("git push", workflow)
        self.assertNotIn("gh release create", workflow)
        self.assertLess(workflow.index("Verify checksums and release metadata"),
                        workflow.index("promote-github-release.py candidate-tag"))
        self.assertIn("Validate store eligibility policy", workflow)
        self.assertIn("Stage all eligible plugin candidates", workflow)
        self.assertIn("Upload market publication bundle", workflow)
        self.assertIn("--market-dir build/market-release", workflow)
        self.assertIn("needs.preflight.outputs.channel != 'nightly'", workflow)
        self.assertIn("--catalog https://plugin.turboism.dev/api/v2/catalog.json", workflow)

    def test_protected_publisher_correlates_and_verifies_provider(self):
        publisher = (ROOT / ".github/workflows/release-github-only.yml").read_text(encoding="utf-8")
        self.assertIn("publish-plugin-directory-v2.yml", publisher)
        self.assertIn("expected exactly one live Plugin Directory run", publisher)
        self.assertIn('select(.status != "completed" or .conclusion == "success")', publisher)
        self.assertIn("-f source_run_id=", publisher)
        self.assertIn("-f source_sha=", publisher)
        self.assertIn("-f artifact_name=", publisher)
        self.assertIn("-f request_id=", publisher)
        self.assertNotIn("-f plan_id=", publisher)
        self.assertIn("gh run watch", publisher)
        self.assertIn("verify-plugin-publication.py", publisher)
        self.assertIn("--timeout 300", publisher)
        self.assertIn("--verifier-repo build/github-release-fallback/plugin-directory", publisher)
        self.assertIn("curl -L --fail --silent --show-error --compressed", publisher)
        self.assertIn("https://turboism.dev/plugins", publisher)
        self.assertNotIn("turboism/turboism-updates", publisher)
        self.assertNotIn("UPDATES_RELEASE_DISPATCH_TOKEN", publisher)
        self.assertLess(
            publisher.index("Verify public signed catalog and planned plugin bytes"),
            publisher.index("Verify payload then create or resume official tag and Release"),
        )

    def test_windows_zip_writer_uses_a_fixed_timestamp(self):
        script = (
            ROOT / "packaging/windows-installer/assemble-release.sh"
        ).read_text(encoding="utf-8")
        self.assertRegex(script, r"TIMESTAMP\s*=\s*\(1980, 1, 1, 0, 0, 0\)")
        self.assertIn("zipfile.ZipInfo(name, TIMESTAMP)", script)
        self.assertIsNone(re.search(r"\bz\.write\(", script))


class RootReadmeTest(unittest.TestCase):
    EULA_PATHS = {
        "README.md": "packaging/eula/EULA.en.txt",
        "README_zh.md": "EULA.md",
        "README_ja.md": "packaging/eula/EULA.ja.txt",
        "README_ko.md": "packaging/eula/EULA.ko.txt",
    }

    PAGES = {
        "README.md": ["Copyright and notices", "About", "Supported Cubism Editor versions",
                      "Installation", "Development", "Documentation"],
        "README_zh.md": ["版权声明", "项目简介", "支持的 Cubism Editor 版本", "安装", "开发", "文档"],
        "README_ja.md": ["著作権と注意事項", "プロジェクト概要", "対応する Cubism Editor のバージョン",
                         "インストール", "開発", "ドキュメント"],
        "README_ko.md": ["저작권 및 고지", "프로젝트 소개", "지원하는 Cubism Editor 버전", "설치", "개발", "문서"],
    }

    def test_default_english_and_four_way_navigation_at_top(self):
        for filename in self.PAGES:
            with self.subTest(page=filename):
                first_line = (ROOT / filename).read_text(encoding="utf-8").splitlines()[0]
                self.assertEqual(re.findall(r"\]\(([^)]+)\)", first_line), list(self.PAGES))
                for label in ("EN / English", "ZH / 简体中文", "JP / 日本語", "KR / 한국어"):
                    self.assertIn(label, first_line)

    def test_requested_section_order_and_installation_formats(self):
        for filename, headings in self.PAGES.items():
            with self.subTest(page=filename):
                text = (ROOT / filename).read_text(encoding="utf-8")
                self.assertEqual(re.findall(r"(?m)^## (.+)$", text), headings)
                self.assertEqual(re.findall(r"(?m)^### (EXE|ZIP)\b", text), ["EXE", "ZIP"])
                copyright_section = text.split("## " + headings[1], 1)[0]
                self.assertIn("Copyright © 2026 Turboism Contributors", copyright_section)
                self.assertIn("(LICENSE)", copyright_section)
                eula_path = self.EULA_PATHS[filename]
                self.assertIn(f"({eula_path})", copyright_section)
                self.assertTrue((ROOT / eula_path).is_file())
                self.assertIn("Live2D Inc.", copyright_section)

    def test_shared_versions_artifacts_and_development_requirements(self):
        for filename in self.PAGES:
            with self.subTest(page=filename):
                text = (ROOT / filename).read_text(encoding="utf-8")
                self.assertEqual(
                    set(re.findall(r"\*\*(5\.\d+\.\d+.*?)\*\*", text)),
                    {
                        "5.0.00 – 5.0.06",
                        "5.1.00 – 5.1.03",
                        "5.2.00 – 5.2.03",
                        "5.3.00 – 5.3.04",
                    },
                )
                for token in ("Windows x64", "macOS", "Linux", "JDK 17", ".sha256",
                              "turboism-<version>.zip",
                              "TurboismInstaller-<version>.exe",
                              "configure_turboism.ps1", "launch-cubism-turboism.bat",
                              "gradlew.bat", "compileOnly", "com.live2d.*"):
                    self.assertIn(token, text)

    def test_links_exist_and_match_across_languages(self):
        from collections import Counter
        from urllib.parse import urlsplit
        english_links = None
        for filename in self.PAGES:
            links = re.findall(r"\[[^\]]+\]\(([^)]+)\)", (ROOT / filename).read_text(encoding="utf-8"))
            normalized_links = ["EULA" if link == self.EULA_PATHS[filename] else link
                                for link in links]
            if english_links is None:
                english_links = Counter(normalized_links)
            self.assertEqual(Counter(normalized_links), english_links, filename)
            for link in links:
                parsed = urlsplit(link)
                if not parsed.scheme and parsed.path:
                    self.assertTrue((ROOT / parsed.path).exists(), f"{filename}: broken link {link}")
            self.assertIn("https://docs.turboism.dev", links)
            self.assertIn("https://github.com/turboism/Turboism/releases/latest", links)


class ReleaseVerifierTest(unittest.TestCase):
    PLUGINS = ("acp", "mcp")

    def fixture(self, version="0.42.0") -> tuple[Path, Path, Path]:
        root = Path(tempfile.mkdtemp(prefix="release-tooling-"))
        dist = root / "dist"
        stage = root / "stage"
        dist.mkdir()
        stage.mkdir()
        roster = root / "release-plugins.txt"
        roster.write_text(
            ":plugins:acp\n:plugins:core\n:plugins:mcp\n",
            encoding="utf-8",
        )
        thin = dist / f"turboism-{version}-thin.zip"
        full = dist / f"turboism-{version}-full.zip"
        full_exe = dist / f"TurboismInstaller-{version}-full.exe"
        thin_exe = dist / f"TurboismInstaller-{version}-thin.exe"
        staged_sdk = stage / "graal" / "lib" / f"sdk-{version}.jar"
        staged_sdk.parent.mkdir(parents=True)
        sdk = dist / f"turboism-sdk-{version}.jar"
        processor = dist / f"turboism-event-processor-{version}.jar"
        archive(thin, version)
        archive(full, version, self.PLUGINS)
        full_exe.write_bytes(b"exe")
        thin_exe.write_bytes(b"thin")
        sdk_archive(staged_sdk)
        sdk.write_bytes(staged_sdk.read_bytes())
        event_processor_archive(processor)
        for path in (thin, full, full_exe, thin_exe, sdk, processor):
            sidecar(path)
        return dist, roster, stage

    def verify(self, fixture: tuple[Path, Path, Path], version="0.42.0") -> None:
        release.verify(fixture[0], version, fixture[1], fixture[2])

    def standard_fixture(self):
        import hashlib
        import json
        dist, roster, stage = self.fixture()
        version = "0.42.0"
        (dist / f"turboism-{version}-thin.zip").unlink()
        (dist / f"turboism-{version}-thin.zip.sha256").unlink()
        (dist / f"TurboismInstaller-{version}-thin.exe").unlink()
        (dist / f"TurboismInstaller-{version}-thin.exe.sha256").unlink()
        for old, new in ((f"turboism-{version}-full.zip", f"turboism-{version}.zip"),
                         (f"TurboismInstaller-{version}-full.exe", f"TurboismInstaller-{version}.exe")):
            (dist / old).rename(dist / new)
            (dist / (old + ".sha256")).unlink()
        entries = []
        for name in ("js-isolate-windows-amd64-community-25.2.4.jar", "truffle-api-25.2.4.jar"):
            data = name.encode()
            entries.append({"name":name,"bytes":len(data),"sha256":hashlib.sha256(data).hexdigest()})
        pins = json.dumps({"format":"turboism.optional-script-engine","schemaVersion":1,"version":"25.2.4","artifacts":entries}).encode()
        (stage / "script-engine.json").write_bytes(pins)
        with zipfile.ZipFile(dist / f"turboism-{version}.zip", "a") as z:
            z.writestr("script-engine.json", pins)
            z.writestr(f"graal/lib/sdk-{version}.jar", (stage / f"graal/lib/sdk-{version}.jar").read_bytes())
            z.writestr(f"graal/lib/graal-host-{version}.jar", b"host")
            z.writestr("install-script-engine.ps1", b"installer")
        with zipfile.ZipFile(dist / f"turboism-{version}.zip", "a") as z:
            for entry in entries:
                z.writestr("graal/lib/" + entry["name"], entry["name"].encode())
        for path in dist.iterdir():
            if not path.name.endswith(".sha256"):
                sidecar(path)
        return dist, roster, stage

    def test_accepts_standard_layout_with_bundled_pinned_graaljs(self):
        fixture = self.standard_fixture()
        self.verify(fixture)
        self.assertEqual(len(release.artifact_manifest(fixture[0], "0.42.0", fixture[1], fixture[2])["artifacts"]), 8)

    def test_unified_zip_build_receipt_is_verified_without_full_thin_archives(self):
        import base64
        import json
        import sys
        sys.path.insert(0, str(ROOT / "scripts/release"))
        from turboism_release.build_identity import identity, verify_receipt
        dist, _, stage = self.standard_fixture()
        source = "a" * 40
        receipt = dict(identity("0.42.0", source, "123", 1), buildNumber=40)
        (dist.parent / "build-identity.json").write_text(json.dumps(receipt))
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as z:
            z.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 0.42.0\r\nTurboism-Build-Number: 40\r\nTurboism-Source-Revision: " + source + "\r\n\r\n")
        path = dist / "turboism-0.42.0.zip"
        with zipfile.ZipFile(path) as z:
            entries = [(n, z.read(n)) for n in z.namelist()]
        with zipfile.ZipFile(path, "w") as z:
            for n, value in entries:
                z.writestr(n, data.getvalue() if n == "turboism-agent.jar" else value)
        class Ledger:
            def api(self, path):
                return {"encoding":"base64", "content":base64.b64encode(json.dumps(receipt).encode()).decode()}
        self.assertEqual(verify_receipt(Ledger(), stage, dist.parent, "0.42.0", source, "123", 1), receipt)
        receipt["buildNumber"] = 41
        with self.assertRaisesRegex(ValueError, "differs from allocated"):
            verify_receipt(Ledger(), stage, dist.parent, "0.42.0", source, "123", 1)

    def test_standard_rejects_engine_tampering_even_with_updated_outer_checksum(self):
        fixture = self.standard_fixture()
        path = fixture[0] / "turboism-0.42.0.zip"
        with zipfile.ZipFile(path) as z:
            entries = [(n, z.read(n)) for n in z.namelist()]
        with zipfile.ZipFile(path, "w") as z:
            for n, data in entries:
                z.writestr(n, b"tampered" if n.endswith("truffle-api-25.2.4.jar") else data)
        sidecar(path)
        with self.assertRaisesRegex(ValueError, "differs from pin"):
            self.verify(fixture)

    def test_standard_rejects_mixed_legacy_and_missing_package(self):
        fixture = self.standard_fixture()
        component = fixture[0] / "turboism-0.42.0.zip"
        component.unlink()
        with self.assertRaises(ValueError):
            self.verify(fixture)
        fixture = self.standard_fixture()
        (fixture[0] / "turboism-0.42.0-thin.zip").write_bytes(b"old")
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_accepts_exact_release(self):
        self.verify(self.fixture())

    def test_emits_manifest_outside_exact_dist_contract(self):
        dist, roster, stage = self.fixture()
        manifest = release.artifact_manifest(dist, "0.42.0", roster, stage)
        self.assertEqual(manifest["format"], "turboism.framework-artifacts")
        self.assertEqual(len(manifest["artifacts"]), 12)
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
            "turboism-0.42.0-thin.zip",
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

    def test_rejects_thin_plugin(self):
        fixture = self.fixture()
        self.rewrite_archive(
            fixture,
            "turboism-0.42.0-thin.zip",
            plugins=("acp",),
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
            fixture[0] / "TurboismInstaller-0.42.0-full.exe"
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
        artifact = fixture[0] / "TurboismInstaller-0.42.0-full.exe"
        artifact.with_name(artifact.name + ".sha256").write_text(
            f"{release.sha256(artifact)}  build/windows-installer/dist/{artifact.name}\n",
            encoding="utf-8",
        )
        with self.assertRaises(ValueError):
            self.verify(fixture)

    def test_plugin_publication_verifier_marks_artifact_downloads_as_verification(self):
        from unittest import mock

        plugin_verifier = load(
            "verify_plugin_publication",
            ROOT / "scripts/release/verify-plugin-publication.py",
        )
        response = mock.MagicMock()
        response.__enter__.return_value.read.return_value = b"jar-bytes"
        with mock.patch.object(
            plugin_verifier.urllib.request, "urlopen", return_value=response
        ) as urlopen:
            data = plugin_verifier.download_artifact(
                "https://github.com/turboism/Turboism/releases/download/plugin-v1/plugin.jar",
                9,
            )
        self.assertEqual(data, b"jar-bytes")
        request = urlopen.call_args.args[0]
        self.assertEqual(
            request.get_header("X-turboism-download-purpose"), "verification"
        )

    def test_rejects_event_processor_missing_service_or_class(self):
        for entries in (
            {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n"},
            {
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
                "dev/turboism/eventprocessor/SubscribeEventProcessor.class": b"class",
            },
            {
                "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
                "dev/turboism/eventprocessor/SubscribeEventProcessor.class": b"class",
                "META-INF/services/javax.annotation.processing.Processor": b"dev.example.Other\n",
            },
        ):
            fixture = self.fixture()
            processor = fixture[0] / "turboism-event-processor-0.42.0.jar"
            with zipfile.ZipFile(processor, "w") as output:
                for name, data in entries.items():
                    output.writestr(name, data)
            sidecar(processor)
            with self.assertRaises(ValueError):
                self.verify(fixture)

class FrameworkUpdateToolTest(unittest.TestCase):
    """Offline trust-bound build fixtures; no product signing material or network."""

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="framework-sign-test-")
        self.addCleanup(self.temporary.cleanup)
        self.home = Path(self.temporary.name)
        self.identity = {"version": "0.68.0", "channel": "stable", "buildNumber": 50,
                         "sourceRevision": "0123456789abcdef0123456789abcdef01234567"}
        self.key = self.home / "temporary.pem"
        framework_update.openssl(["genpkey", "-algorithm", "Ed25519", "-out", str(self.key)])
        self.key.chmod(0o600)
        public = framework_update.openssl(["pkey", "-in", str(self.key), "-pubout", "-outform", "DER"])
        self.keyring = self.home / "public-keys.json"
        self.keyring.write_text(json.dumps({"offline-test": base64.b64encode(public).decode("ascii")}))
        self.payloads = {name: (name + "\n").encode("ascii") for name in
                         json.loads(framework_update.POLICY.read_text())["frameworkRootFiles"]}
        self.payloads["turboism-agent.jar"] = self.agent()
        self.payloads["graal/lib/sdk-0.68.0.jar"] = b"sdk"
        self.payloads["graal/lib/graal-host-0.68.0.jar"] = b"host"
        self.payloads["graal/lib/polyglot-25.2.4.jar"] = b"dependency"
        self.excluded = {"config.json": b"user-config-seed", "installed-build.json": b"installation-state",
                         "plugins/acp.jar": b"independent-catalog-plugin"}
        self.archive = self.home / "turboism-0.68.0.zip"
        self.candidate = self.home / "candidate.json"
        self.receipt = self.home / "build-identity.json"
        self.inventory = self.home / "framework-payload-inventory.json"
        self.rebuild()

    def agent(self):
        data = io.BytesIO()
        fields = {"Manifest-Version": "1.0", "Implementation-Version": self.identity["version"],
                  "Turboism-Version": self.identity["version"], "Turboism-Channel": self.identity["channel"],
                  "Turboism-Build-Number": str(self.identity["buildNumber"]),
                  "Turboism-Source-Revision": self.identity["sourceRevision"]}
        properties = {**self.identity, "buildKind": "ci", "dirty": "false"}
        with zipfile.ZipFile(data, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "\r\n".join(f"{k}: {v}" for k, v in fields.items()) + "\r\n\r\n")
            jar.writestr("META-INF/turboism/framework-version.properties", "\n".join(f"{k}={v}" for k, v in properties.items()) + "\n")
        return data.getvalue()

    def rebuild(self):
        with zipfile.ZipFile(self.archive, "w", zipfile.ZIP_DEFLATED) as archive_file:
            for name, data in sorted({**self.payloads, **self.excluded}.items()):
                archive_file.writestr(name, data)
        self.bind_archive()
        self.receipt.write_text(json.dumps({"schemaVersion": 1, **self.identity, "runId": "123", "runAttempt": 1}))
        entries = [{"relativePath": name, "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                   for name, data in sorted(self.payloads.items())]
        self.inventory.write_text(json.dumps({"format": "turboism.framework-payload-inventory", "schemaVersion": 1,
                                             "layout": "windows-standard-zip", "identity": self.identity,
                                             "files": entries, "excludedPaths": sorted(self.excluded)}))

    def bind_archive(self):
        self.candidate.write_text(json.dumps({"format": "turboism.release-candidate", "schemaVersion": 1,
                                             "source": {"repository": "turboism/Turboism", "revision": self.identity["sourceRevision"], "tag": None},
                                             "framework": {"version": self.identity["version"], "artifacts": [
                                                 {"name": self.archive.name, "size": self.archive.stat().st_size,
                                                  "sha256": hashlib.sha256(self.archive.read_bytes()).hexdigest()}]}}))

    def generate(self, output=None, **changes):
        args = dict(candidate=self.candidate, receipt=self.receipt, inventory=self.inventory, archive=self.archive,
                    identity=self.identity, key_id="offline-test", private_key=self.key, trusted_keys=self.keyring,
                    minimum_updater="0.67.0", output=output or self.home / "out")
        args.update(changes)
        return framework_update.generate(**args)

    def test_actual_zip_builder_and_staging_source_agree_with_inventory(self):
        # Derive root payloads from real staging source, NOT from update layout policy.
        staging = (ROOT / "packaging/java-installer/installer.gradle.kts").read_text()
        staging = staging.split("val stageInstallerPayload by tasks.registering {", 1)[1].split("// Opt-in source-bound", 1)[0]
        roots = {}
        for source in re.findall(r'from\("([^"$]+)"\)', staging):
            target = {"LICENSE": "LICENSE.txt"}.get(source, Path(source).name)
            roots[target] = (ROOT / source).read_bytes()
        for template, target in re.findall(r'"(README[^" ]+\.template)" to "([^"]+)"', staging):
            roots[target] = (ROOT / "packaging/windows-installer" / template).read_bytes().replace(b"__VERSION__", self.identity["version"].encode())
        roots["turboism-agent.jar"] = self.agent()
        # Installer-only files are deliberately stripped by the real ZIP builder.
        roots["config.template.json"] = (ROOT / "packaging/windows-installer/config.template.json").read_bytes()
        roster = (ROOT / "packaging/release-plugins.txt").read_text().splitlines()
        self.excluded = {**self.excluded, **{"plugins/" + n.split(":")[-1] + ".jar": b"catalog-only" for n in roster}}
        self.payloads["graal/lib/graal-host-0.68.0-feat-issue-50.jar"] = self.payloads.pop("graal/lib/graal-host-0.68.0.jar")
        self.assertIn("archiveClassifier.set(resolvedWorktreeId)",
                      (ROOT / "buildSrc/src/main/kotlin/turboism.java-conventions.gradle.kts").read_text())
        stage = self.home / "stage"
        for name, data in {**roots, **{n: d for n, d in self.payloads.items() if n.startswith("graal/lib/")},
                           **{n: d for n, d in self.excluded.items() if n.startswith("plugins/")}}.items():
            path = stage / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
        source = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text()
        block = source.split("zip_dir() {", 1)[1].split("<<'PYEOF'\n", 1)[1].split("\nPYEOF", 1)[0]
        subprocess.run([sys.executable, "-", str(stage), str(self.archive), self.identity["version"]],
                       input=block, text=True, check=True, capture_output=True)
        with zipfile.ZipFile(self.archive) as actual:
            self.payloads = {n: actual.read(n) for n in actual.namelist()
                             if n not in self.excluded}
            self.excluded = {n: actual.read(n) for n in self.excluded}
            self.assertTrue(all(not i.extra and not i.is_dir() for i in actual.infolist()))
        self.assertEqual(set(self.payloads) - {n for n in self.payloads if n.startswith("graal/lib/")},
                         set(json.loads(framework_update.POLICY.read_text())["frameworkRootFiles"]))
        original = self.archive.read_bytes()
        self.rebuild()  # Write matching inventory/receipt; restore REAL ZIP bytes afterwards.
        self.archive.write_bytes(original)
        self.bind_archive()
        self.generate()
        # Roster is independent catalog inventory, not an update ownership allowlist.
        roster = (ROOT / "packaging/release-plugins.txt").read_text().splitlines()
        self.assertTrue(all(re.fullmatch(r":plugins:[a-z0-9-]+", n) for n in roster))
        self.assertIn('pluginModuleNames.map { "plugins/$it.jar" }',
                      (ROOT / "packaging/java-installer/installer.gradle.kts").read_text())
        self.assertIn('implementation(project(":sdk"))', (ROOT / "graal-host/build.gradle.kts").read_text())

    def test_protocol_rejects_java_incompatible_path_and_numeric_boundaries(self):
        for name in ("graal/lib/foo..bar.jar", "graal/lib/NUL.jar", "graal/lib/" + "a" * 230 + ".jar"):
            with self.subTest(path=name), self.assertRaises(ValueError):
                framework_update.path_name(name)
        for value in (True, 0, -1, 9007199254740992):
            with self.subTest(build=value), self.assertRaises(ValueError):
                self.generate(identity={**self.identity, "buildNumber": value})
        for version in ("2147483648.0.0", "0.068.0", "0.68.0-beta.01", "0.68.1٠"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                self.generate(identity={**self.identity, "version": version})
        for updater in ("2147483648.0.0", "00.68.0", "0.68"):
            with self.subTest(updater=updater), self.assertRaises(ValueError):
                self.generate(minimum_updater=updater)

    def test_deterministic_output_and_immutable_object_reuse(self):
        first = self.generate()
        output = self.home / "out"
        before = {p.relative_to(output): (p.read_bytes(), p.stat().st_mtime_ns) for p in output.rglob("*") if p.is_file()}
        self.assertEqual(first, self.generate())
        after = {p.relative_to(output): (p.read_bytes(), p.stat().st_mtime_ns) for p in output.rglob("*") if p.is_file()}
        self.assertEqual(before, after)
        self.generate(self.home / "second")
        self.assertEqual({p: data[0] for p, data in before.items()},
                         {p.relative_to(self.home / "second"): p.read_bytes() for p in (self.home / "second").rglob("*") if p.is_file()})
        # Change one legitimate build output and its trusted inventory/candidate, retaining old objects.
        self.payloads["README.txt"] += b"updated"
        self.rebuild()
        second = self.generate(self.home / "changed")
        old = {o["sha256"] for o in first["objects"]}
        new = {o["sha256"] for o in second["objects"]}
        self.assertEqual(len(old - new), 1)
        self.assertEqual(len(new - old), 1)
        self.assertEqual(len(old & new), len(old) - 1)
        # Add a second release to the shared object store; unchanged objects retain mtimes.
        self.identity = {**self.identity, "version": "0.68.1", "buildNumber": 51}
        self.archive = self.home / "turboism-0.68.1.zip"
        self.payloads["turboism-agent.jar"] = self.agent()
        for module in ("sdk", "graal-host"):
            self.payloads[f"graal/lib/{module}-0.68.1.jar"] = self.payloads.pop(f"graal/lib/{module}-0.68.0.jar")
        self.rebuild()
        self.generate(output)
        for relative, (data, timestamp) in before.items():
            self.assertEqual(data, (output / relative).read_bytes())
            self.assertEqual(timestamp, (output / relative).stat().st_mtime_ns)

    def test_cli_executes_real_tool_and_masks_invalid_key_diagnostics(self):
        command = [sys.executable, str(ROOT / "scripts/release/build-framework-update.py"),
                   "--candidate", str(self.candidate), "--receipt", str(self.receipt),
                   "--inventory", str(self.inventory), "--archive", str(self.archive),
                   "--version", self.identity["version"], "--channel", self.identity["channel"],
                   "--build-number", str(self.identity["buildNumber"]),
                   "--source-revision", self.identity["sourceRevision"], "--minimum-updater", "0.67.0",
                   "--key-id", "offline-test", "--private-key", str(self.key),
                   "--trusted-keys", str(self.keyring), "--output", str(self.home / "cli-out")]
        result = subprocess.run(command, capture_output=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        command[command.index("--key-id") + 1] = "unknown"
        failed = subprocess.run(command, capture_output=True, timeout=30)
        self.assertEqual(failed.returncode, 1)
        self.assertIn(b"Offline framework signing failed", failed.stderr)
        self.assertNotIn(self.key.read_bytes(), failed.stdout + failed.stderr)
        self.assertNotIn(str(self.key).encode(), failed.stdout + failed.stderr)

    def test_complete_framework_payload_and_explicit_exclusions(self):
        self.generate()
        manifest = json.loads((self.home / "out/turboism-0.68.0-manifest.json").read_bytes())
        self.assertEqual({e["relativePath"] for e in manifest["entries"]}, set(self.payloads))
        self.assertFalse({e["relativePath"] for e in manifest["entries"]} & set(self.excluded))
        self.assertEqual(len(manifest["entries"]), len(self.payloads))
        self.assertNotIn(b"user-config-seed", b"".join(p.read_bytes() for p in (self.home / "out/objects").rglob("payload")))
        for name, data in {**self.payloads, **self.excluded}.items():
            with zipfile.ZipFile(self.archive) as archive_file:
                self.assertEqual(data, archive_file.read(name))

    def test_wrong_identity_and_embedded_metadata_fail_before_output(self):
        for field, value in {"version": "0.69.0", "channel": "beta", "buildNumber": 51, "sourceRevision": "f" * 40}.items():
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.generate(identity={**self.identity, field: value})
        doc = json.loads(self.receipt.read_text())
        doc["sourceRevision"] = "f" * 40
        self.receipt.write_text(json.dumps(doc))
        with self.assertRaises(ValueError):
            self.generate()
        self.rebuild()
        self.payloads["turboism-agent.jar"] = agent("0.68.0")  # Old unbound JAR metadata is insufficient.
        self.rebuild()
        with self.assertRaises((ValueError, KeyError)):
            self.generate()
        self.assertFalse((self.home / "out").exists())

    def test_tamper_missing_unknown_and_dangerous_paths_fail_closed(self):
        original = self.archive.read_bytes()
        self.archive.write_bytes(original + b"tamper")
        with self.assertRaises(ValueError):
            self.generate()
        self.rebuild()
        cases = ["missing", "extra", "link", "alias", "unsafe", "case-alias"]
        for case in cases:
            self.rebuild()
            with zipfile.ZipFile(self.archive) as source:
                contents = {n: source.read(n) for n in source.namelist()}
            with zipfile.ZipFile(self.archive, "w") as target:
                for name, data in contents.items():
                    if case != "missing" or name != "README.txt":
                        target.writestr(name, data)
                if case == "extra":
                    target.writestr("project.cmo3", b"user-project")
                elif case == "link":
                    info = zipfile.ZipInfo("graal/lib/link.jar")
                    info.create_system = 3
                    info.external_attr = (stat.S_IFLNK | 0o777) << 16
                    target.writestr(info, b"README.txt")
                elif case == "alias":
                    target.writestr("graal/lib/./a.jar", b"alias")
                elif case == "unsafe":
                    target.writestr("../outside", b"escape")
                elif case == "case-alias":
                    target.writestr("readme.txt", b"alias")
            self.bind_archive()  # Archive digest alone must not authorize contents.
            with self.subTest(case=case), self.assertRaises(ValueError):
                self.generate()
        self.assertFalse((self.home / "out").exists())

    def test_unknown_ownership_and_untrusted_payload_changes_fail(self):
        for name in ("plugins/acp.jar", "config.json", "unknown-helper.exe", "graal/lib/CON.jar"):
            self.rebuild()
            doc = json.loads(self.inventory.read_text())
            doc["files"].append({"relativePath": name, "bytes": 1, "sha256": "a" * 64})
            self.inventory.write_text(json.dumps(doc))
            with self.subTest(name=name), self.assertRaises(ValueError):
                self.generate()
        self.rebuild()
        self.payloads["README.txt"] = b"modified-after-build-inventory"
        with zipfile.ZipFile(self.archive, "w") as archive_file:
            for name, data in {**self.payloads, **self.excluded}.items():
                archive_file.writestr(name, data)
        self.bind_archive()
        with self.assertRaises(ValueError):
            self.generate()

    def test_links_hardlinks_limits_and_signing_material_fail(self):
        for field in ("archive", "inventory", "receipt", "candidate", "private_key", "trusted_keys"):
            original = {"private_key": self.key, "trusted_keys": self.keyring}.get(field, getattr(self, field, None))
            link = self.home / (field + "-link")
            link.symlink_to(original)
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.generate(**{field: link})
        hardlink = self.home / "hardlink"
        os.link(self.archive, hardlink)
        try:
            with self.assertRaises(ValueError):
                self.generate()
        finally:
            hardlink.unlink()
        with self.assertRaises(ValueError):
            self.generate(key_id="unknown")
        with self.assertRaises((ValueError, OSError)):
            self.generate(private_key=self.home / "missing")
        wrong = self.home / "wrong.pem"
        framework_update.openssl(["genpkey", "-algorithm", "Ed25519", "-out", str(wrong)])
        wrong.chmod(0o600)
        with self.assertRaises(ValueError):
            self.generate(private_key=wrong)
        doc = json.loads(self.inventory.read_text())
        doc["files"][0]["bytes"] = framework_update.MAX_FILE + 1
        self.inventory.write_text(json.dumps(doc))
        with self.assertRaises(ValueError):
            self.generate()
        doc["files"] *= framework_update.MAX_ENTRIES
        self.inventory.write_text(json.dumps(doc))
        with self.assertRaises(ValueError):
            self.generate()
        self.assertFalse((self.home / "out").exists())

    def test_output_conflict_is_rejected_before_changes_and_no_key_is_exported(self):
        self.generate()
        output = self.home / "out"
        obj = next((output / "objects").rglob("payload"))
        obj.write_bytes(b"conflict")
        before = {p.relative_to(output): p.read_bytes() for p in output.rglob("*") if p.is_file()}
        with self.assertRaises(ValueError):
            self.generate()
        self.assertEqual(before, {p.relative_to(output): p.read_bytes() for p in output.rglob("*") if p.is_file()})
        self.assertFalse(any(b"PRIVATE KEY" in data for data in before.values()))


def write_framework_java_fixture(target: Path):
    """Called by the existing Java verifier test: real Python/OpenSSL production-tool output."""
    fixture = FrameworkUpdateToolTest("test_complete_framework_payload_and_explicit_exclusions")
    fixture.setUp()
    try:
        fixture.generate(target)
        (target / "public-keys.json").write_bytes(fixture.keyring.read_bytes())
    finally:
        fixture.doCleanups()


if __name__ == "__main__":
    unittest.main(verbosity=2)
