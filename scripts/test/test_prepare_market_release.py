#!/usr/bin/env python3
"""Unit tests for the selected-plugin market staging CLI and workflow.

Covers the Java .properties subset, manifest/selection fail-closed rules,
staging determinism and atomicity, and static assertions on
.github/workflows/publish-selected-plugins.yml (empty short-circuit before
Java/Gradle/upload/secret/dispatch; no .tplugin, release creation, broad
permissions or Provider contents write).
"""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPT = REPO_ROOT / "scripts/release/prepare-market-release.py"
WORKFLOW = REPO_ROOT / ".github/workflows/publish-selected-plugins.yml"
GIT_SHA = "0" * 40
DESCRIPTOR_ENTRY = "META-INF/turboism/plugin.json"
I18N = "META-INF/turboism/i18n"
BASE_NAME = f"{I18N}/messages"
CATALOGS = {
    "base": f"{BASE_NAME}.properties",
    "en": f"{BASE_NAME}_en.properties",
    "ja": f"{BASE_NAME}_ja.properties",
    "zh-Hans": f"{BASE_NAME}_zh_Hans.properties",
    "zh-Hant": f"{BASE_NAME}_zh_Hant.properties",
    "ko": f"{BASE_NAME}_ko.properties",
}


def load_script():
    """Load the hyphenated script under a valid module name."""
    spec = importlib.util.spec_from_file_location("prepare_market_release", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def run_script(repo_root: Path, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(SCRIPT), "--repo-root", str(repo_root), *args],
        capture_output=True, text=True)


def write_manifest(repo_root: Path, plugins: list) -> Path:
    path = repo_root / "packaging" / "market-plugins.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps({"schemaVersion": 1, "plugins": plugins}) + "\n")
    return path


def make_module(repo_root: Path, name: str, plugin_id: str, requires_cubism: bool,
                version: str = "0.1.0", locales=None, en_description=None):
    """Create a tracked descriptor plus i18n catalogs for a plugin module."""
    locales = locales or ("en", "ja", "ko", "zh-Hans", "zh-Hant")
    resources = repo_root / "plugins" / name / "src/main/resources"
    i18n_dir = resources / I18N
    i18n_dir.mkdir(parents=True)
    descriptor = {
        "format": "turboism.plugin.meta",
        "schemaVersion": 3,
        "id": plugin_id,
        "name": f"{name} plugin",
        "version": version,
        "description": f"{name} description.",
        "entrypoints": [f"dev.turboism.plugin.{name}.Main"],
        "turboismApi": "[0.1.0,0.2.0)",
        "authors": [{"name": "Turboism Contributors"}],
        "license": "Project License",
        "website": "https://turboism.dev",
        "resources": [],
        "i18n": {"baseName": BASE_NAME, "locales": list(locales)},
        "dependencies": [],
        "permissions": [{"id": "turboism.cubism.model.read", "scope": "application",
                         "reason": "fixture"}],
        "capabilities": ["cubism.model.objects.read"],
        "environment": {"requiresCubism": requires_cubism, "ui": "none"},
        "tags": ["fixture", "test"],
        "category": "workflow",
    }
    descriptor_path = resources / DESCRIPTOR_ENTRY
    descriptor_path.write_text(json.dumps(descriptor, indent=2) + "\n")
    (i18n_dir / "messages.properties").write_text(
        f"plugin.name={name} plugin\n"
        f"plugin.description={name} description.\n")
    for locale in locales:
        suffix = "_" + locale.replace("-", "_")
        name_value = locale + " "
        description = en_description if locale == "en" and en_description else (
            f"{name_value}{name} description.")
        (i18n_dir / f"messages{suffix}.properties").write_text(
            f"plugin.name={name_value}{name} plugin\n"
            f"plugin.description={description}\n")
    return descriptor_path


def make_jar(module_dir: Path, descriptor_bytes: bytes, catalogs: dict, module: str,
             pad: int = 0) -> Path:
    libs = module_dir / "libs"
    libs.mkdir(parents=True, exist_ok=True)
    jar = libs / f"{module}-0.42.0-SNAPSHOT-market-publish.jar"
    with zipfile.ZipFile(jar, "w") as archive:
        archive.writestr(DESCRIPTOR_ENTRY, descriptor_bytes)
        archive.writestr("dev/turboism/plugin/fixture/Main.class", b"fixture")
        for name, data in catalogs.items():
            archive.writestr(name, data)
        if pad:
            archive.writestr("pad.bin", b"\0" * pad)
    return jar


class Fixture:
    """Synthetic repository: settings, two selectable modules, built JARs."""

    def __init__(self, root: Path, build_id: str = "market-publish"):
        self.root = root
        self.build_id = build_id
        (root / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\n'
            'include("plugins:mcp", "plugins:backup", "plugins:metrics", "plugins:core")\n')
        self.mcp_descriptor = make_module(
            root, "mcp", "dev.turboism.plugin.mcp", requires_cubism=True,
            en_description="Loopback MCP server for \\u0054urboism \\\n  automation.")
        self.backup_descriptor = make_module(
            root, "backup", "dev.turboism.plugin.backup", requires_cubism=True)
        self.metrics_descriptor = make_module(
            root, "metrics", "dev.turboism.plugin.metrics", requires_cubism=False)
        self.write_manifest([
            {"project": ":plugins:backup", "channel": "stable",
             "cubismVersions": ["5.2.03", "5.3.02"],
             "repository": "https://example.invalid/backup",
             "support": "https://example.invalid/backup/support"},
            {"project": ":plugins:mcp", "channel": "preview",
             "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/mcp",
             "support": "https://example.invalid/mcp/support"},
        ])
        self.build_modules([("mcp", self.mcp_descriptor.read_bytes()),
                            ("backup", self.backup_descriptor.read_bytes())])

    def write_manifest(self, plugins: list) -> Path:
        return write_manifest(self.root, plugins)

    def build_modules(self, modules) -> None:
        build = self.root / "build" / self.build_id
        for module, descriptor_bytes in modules:
            catalogs = {}
            for locale, name in CATALOGS.items():
                path = self.root / "plugins" / module / "src/main/resources" / name
                catalogs[name] = path.read_bytes()
            make_jar(build / module, descriptor_bytes, catalogs, module)

    def catalog_text(self, module: str, locale: str) -> str:
        return (self.root / "plugins" / module / "src/main/resources" /
                CATALOGS[locale]).read_text()


class PropertiesTest(unittest.TestCase):
    def test_java_parity_cases(self):
        pmr = load_script()
        cases = {
            "a = b": {"a": "b"},
            "a  b": {"a": "b"},
            "a =b": {"a": "b"},
            "a b=c": {"a": "b=c"},
            "a = b = c": {"a": "b = c"},
            "a=b=c=d": {"a": "b=c=d"},
            "a\\ b=1": {"a b": "1"},
            "a=b ": {"a": "b "},
            "a=x\\ny": {"a": "x\ny"},
            "a=\\u26A0x": {"a": "\u26a0x"},
            "a=\\d": {"a": "d"},
            "  a=b": {"a": "b"},
            "\ta=b": {"a": "b"},
            "# comment\n! other\nk=v": {"k": "v"},
            "a=x\\\n  y=2\nb=3": {"a": "xy=2", "b": "3"},
            "k=v \\\n   tail\nb=3": {"k": "v tail", "b": "3"},
            "a=b\f=c": {"a": "b\f=c"},
            "a=b\r=cc": {"a": "b", "": "cc"},
            "a=b\r\nc=d": {"a": "b", "c": "d"},
        }
        for source, expected in cases.items():
            self.assertEqual(pmr.parse_java_properties(source), expected, source)

    def test_malformed_unicode_escape_rejected(self):
        pmr = load_script()
        with self.assertRaises(ValueError):
            pmr.parse_java_properties("a=\\u12")


class ManifestValidationTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.fixture = Fixture(self.tmp)

    def tearDown(self):
        self._tmp.cleanup()

    def plan(self, plugins: list, expect_ok=True):
        manifest = self.fixture.write_manifest(plugins)
        result = run_script(self.tmp, "--manifest", str(manifest), "--plan")
        if expect_ok:
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), f"selected={len(plugins)}")
        return result

    def test_empty_manifest_accepted(self):
        manifest = self.fixture.write_manifest([])
        result = run_script(self.tmp, "--manifest", str(manifest), "--plan")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "selected=0")
        # staging an empty selection also succeeds without output or build root
        result = run_script(self.tmp, "--manifest", str(manifest),
                            "--output", str(self.tmp / "out"))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "selected=0")
        self.assertFalse((self.tmp / "out").exists())

    def test_real_repo_empty_manifest_plan(self):
        result = run_script(REPO_ROOT, "--manifest",
                            str(REPO_ROOT / "packaging/market-plugins.json"), "--plan")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "selected=0")

    def test_duplicate_entries_rejected(self):
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_unsorted_entries_rejected(self):
        result = self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
            {"project": ":plugins:backup", "channel": "stable",
             "cubismVersions": ["5.2.03"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)
        self.assertIn("ASCII-sorted", result.stderr)

    def test_unknown_and_non_plugin_projects_rejected(self):
        for project in (":plugins:nope", ":runtime", "plugins:mcp"):
            self.plan([
                {"project": project, "channel": "stable", "cubismVersions": ["5.3.02"],
                 "repository": "https://example.invalid/a",
                 "support": "https://example.invalid/b"},
            ], expect_ok=False)

    def test_core_rejected(self):
        self.plan([
            {"project": ":plugins:core", "channel": "stable", "cubismVersions": [],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_retired_id_rejected(self):
        make_module(self.tmp, "retired", "dev.turboism.plugin.logfilter", False)
        (self.tmp / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\ninclude("plugins:retired")\n')
        self.plan([
            {"project": ":plugins:retired", "channel": "stable", "cubismVersions": [],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_duplicate_descriptor_ids_rejected(self):
        make_module(self.tmp, "mcp2", "dev.turboism.plugin.mcp", True)
        (self.tmp / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\ninclude("plugins:mcp", "plugins:mcp2")\n')
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
            {"project": ":plugins:mcp2", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_channel_rejected(self):
        self.plan([
            {"project": ":plugins:mcp", "channel": "beta", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_urls_rejected(self):
        for url in ("http://example.invalid/a", "example.invalid/a", "https://",
                    "https://exa mple.invalid/a"):
            self.plan([
                {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
                 "repository": url, "support": "https://example.invalid/b"},
            ], expect_ok=False)
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "http://example.invalid/b"},
        ], expect_ok=False)

    def test_cubism_versions_policy(self):
        # requiresCubism=true without versions
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": [],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)
        # requiresCubism=false with versions
        self.plan([
            {"project": ":plugins:metrics", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)
        # non-strict version string
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable",
             "cubismVersions": ["5.3"], "repository": "https://example.invalid/a",
             "support": "https://example.invalid/b"},
        ], expect_ok=False)
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable",
             "cubismVersions": ["5.3.02.1"], "repository": "https://example.invalid/a",
             "support": "https://example.invalid/b"},
        ], expect_ok=False)
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable",
             "cubismVersions": ["5.3.02-SNAPSHOT"], "repository": "https://example.invalid/a",
             "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_descriptor_version_strict(self):
        make_module(self.tmp, "loose", "dev.turboism.plugin.loose", True, version="0.1.0-SNAPSHOT")
        (self.tmp / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\ninclude("plugins:loose")\n')
        self.plan([
            {"project": ":plugins:loose", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_schema_v2_rejected(self):
        path = self.fixture.mcp_descriptor
        text = path.read_text().replace('"schemaVersion": 3', '"schemaVersion": 2')
        path.write_text(text)
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_missing_category_or_tags_rejected(self):
        path = self.fixture.mcp_descriptor
        text = path.read_text().replace('"category": "workflow",', '')
        path.write_text(text)
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)
        self.fixture.write_manifest([])  # reset below
        make_module(self.tmp, "notags", "dev.turboism.plugin.notags", False)
        tagless = self.tmp / "plugins/notags/src/main/resources/META-INF/turboism/plugin.json"
        tagless.write_text(tagless.read_text().replace(
            '"tags": ["fixture", "test"],\n', ''))
        (self.tmp / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\ninclude("plugins:notags")\n')
        self.plan([
            {"project": ":plugins:notags", "channel": "stable", "cubismVersions": [],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_missing_required_locale_rejected(self):
        make_module(self.tmp, "noja", "dev.turboism.plugin.noja", False,
                    locales=("en", "zh-Hans"))
        (self.tmp / "settings.gradle.kts").write_text(
            'rootProject.name = "fixture"\ninclude("plugins:noja")\n')
        self.plan([
            {"project": ":plugins:noja", "channel": "stable", "cubismVersions": [],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_incomplete_i18n_rejected(self):
        (self.tmp / "plugins/mcp/src/main/resources/META-INF/turboism/i18n/"
                   "messages_zh_Hans.properties").write_text("plugin.name=仅名称\n")
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)
        self.fixture.write_manifest([])
        (self.tmp / "plugins/mcp/src/main/resources/META-INF/turboism/i18n/"
                   "messages_ja.properties").write_text("plugin.description=説明\n")
        self.plan([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ], expect_ok=False)

    def test_unknown_manifest_keys_rejected(self):
        path = self.tmp / "packaging/market-plugins.json"
        path.write_text('{"schemaVersion": 1, "plugins": [], "trust": "official"}\n')
        result = run_script(self.tmp, "--manifest", str(path), "--plan")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown manifest keys", result.stderr)
        path.write_text(json.dumps({
            "schemaVersion": 1,
            "plugins": [{"project": ":plugins:mcp", "channel": "stable",
                         "cubismVersions": ["5.3.02"], "repository": "https://example.invalid/a",
                         "support": "https://example.invalid/b", "trust": "official"}]}) + "\n")
        result = run_script(self.tmp, "--manifest", str(path), "--plan")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unknown entry keys", result.stderr)

    def test_bad_schema_version_rejected(self):
        path = self.tmp / "packaging/market-plugins.json"
        path.write_text('{"schemaVersion": 2, "plugins": []}\n')
        result = run_script(self.tmp, "--manifest", str(path), "--plan")
        self.assertNotEqual(result.returncode, 0)


class StagingTest(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.fixture = Fixture(self.tmp)
        self.manifest = self.fixture.root / "packaging/market-plugins.json"
        self.output = self.tmp / "release"
        self.build_root = self.tmp / "build" / "market-publish"

    def tearDown(self):
        self._tmp.cleanup()

    def stage(self, expect_ok=True, *extra):
        result = run_script(self.tmp, "--manifest", str(self.manifest),
                            "--build-root", str(self.build_root),
                            "--output", str(self.output),
                            "--source-revision", GIT_SHA, *extra)
        if expect_ok:
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(result.stdout.strip(), "selected=2")
        return result

    def test_stage_outputs_and_sidecar(self):
        self.stage()
        names = sorted(path.name for path in self.output.iterdir())
        self.assertEqual(names, ["backup-0.1.0.jar", "market-release.json", "mcp-0.1.0.jar"])
        sidecar = json.loads((self.output / "market-release.json").read_text())
        self.assertEqual(sidecar["schemaVersion"], 1)
        self.assertEqual(sidecar["format"], "turboism.market-release")
        self.assertEqual(sidecar["source"], {"revision": GIT_SHA})
        artifacts = sidecar["artifacts"]
        self.assertEqual([a["project"] for a in artifacts],
                         [":plugins:backup", ":plugins:mcp"])
        mcp = artifacts[1]
        self.assertEqual(mcp["module"], "mcp")
        self.assertEqual(mcp["asset"], "mcp-0.1.0.jar")
        self.assertEqual(mcp["size"],
                         (self.output / "mcp-0.1.0.jar").stat().st_size)
        self.assertEqual(mcp["sha256"], hashlib.sha256(
            (self.output / "mcp-0.1.0.jar").read_bytes()).hexdigest())
        self.assertEqual(mcp["descriptorSha256"], hashlib.sha256(
            self.fixture.mcp_descriptor.read_bytes()).hexdigest())
        self.assertEqual(mcp["policy"]["channel"], "preview")
        self.assertEqual(mcp["policy"]["cubismVersions"], ["5.3.02"])
        self.assertEqual(mcp["descriptor"]["id"], "dev.turboism.plugin.mcp")
        self.assertEqual(mcp["descriptor"]["version"], "0.1.0")
        self.assertEqual(mcp["descriptor"]["category"], "workflow")
        self.assertEqual(mcp["descriptor"]["tags"], ["fixture", "test"])
        self.assertEqual(mcp["descriptor"]["turboismApi"], "[0.1.0,0.2.0)")
        self.assertEqual(mcp["descriptor"]["environment"],
                         {"requiresCubism": True, "ui": "none"})
        self.assertEqual(mcp["descriptor"]["author"], "Turboism Contributors")
        self.assertEqual(mcp["localizations"]["en"]["name"], "en mcp plugin")
        self.assertEqual(mcp["localizations"]["en"]["description"],
                         "Loopback MCP server for Turboism automation.")
        self.assertEqual(mcp["localizations"]["zh-Hans"]["name"], "zh-Hans mcp plugin")
        self.assertEqual(mcp["localizations"]["ja"]["name"], "ja mcp plugin")
        backup = artifacts[0]
        self.assertEqual(backup["policy"]["channel"], "stable")
        self.assertEqual(backup["policy"]["cubismVersions"], ["5.2.03", "5.3.02"])

    def test_byte_identical_rerun(self):
        self.stage()
        first = {p.name: p.read_bytes() for p in self.output.iterdir()}
        self.stage()
        second = {p.name: p.read_bytes() for p in self.output.iterdir()}
        self.assertEqual(first, second)
        self.assertEqual(sorted(first), ["backup-0.1.0.jar", "market-release.json",
                                         "mcp-0.1.0.jar"])

    def test_deterministic_across_directories(self):
        self.stage()
        other = self.tmp / "release-other"
        result = run_script(self.tmp, "--manifest", str(self.manifest),
                            "--build-root", str(self.build_root),
                            "--output", str(other),
                            "--source-revision", GIT_SHA)
        self.assertEqual(result.returncode, 0, result.stderr)
        for name in ("mcp-0.1.0.jar", "market-release.json"):
            self.assertEqual((self.output / name).read_bytes(), (other / name).read_bytes())

    def test_ambiguous_jars_rejected_and_output_untouched(self):
        self.output.mkdir(parents=True)
        marker = self.output / "marker"
        marker.write_text("keep")
        extra = self.tmp / "build/market-publish/mcp/libs/extra.jar"
        shutil.copy(self.tmp / "build/market-publish/mcp/libs/mcp-0.42.0-SNAPSHOT-market-publish.jar",
                    extra)
        result = self.stage(expect_ok=False)
        self.assertIn("ambiguous", result.stderr)
        self.assertEqual(marker.read_text(), "keep")
        self.assertEqual(sorted(p.name for p in self.output.iterdir()), ["marker"])
        self.assertFalse(any(self.tmp.glob("release.tmp-*")))

    def test_symlink_jar_rejected(self):
        libs = self.tmp / "build/market-publish/mcp/libs"
        target = libs / "mcp-0.42.0-SNAPSHOT-market-publish.jar"
        link = libs / "link.jar"
        os.symlink(target, link)
        target.unlink()
        result = self.stage(expect_ok=False)
        self.assertIn("symlink JAR is not allowed", result.stderr)

    def test_oversized_jar_rejected(self):
        libs = self.tmp / "build/market-publish/mcp/libs"
        for stale in libs.glob("*.jar"):
            stale.unlink()
        make_jar(self.tmp / "build/market-publish/mcp",
                 self.fixture.mcp_descriptor.read_bytes(),
                 {}, "mcp", pad=17 * 1024 * 1024)
        result = self.stage(expect_ok=False)
        self.assertIn("exceeds", result.stderr)
        self.assertFalse(self.output.exists())

    def test_missing_jar_rejected(self):
        shutil.rmtree(self.tmp / "build/market-publish/backup")
        result = self.stage(expect_ok=False)
        self.assertIn("backup", result.stderr)
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.exists())

    def test_embedded_descriptor_mismatch_rejected(self):
        libs = self.tmp / "build/market-publish/mcp/libs"
        jar = libs / "mcp-0.42.0-SNAPSHOT-market-publish.jar"
        rewritten = self.tmp / "rewritten.jar"
        with zipfile.ZipFile(jar) as source:
            with zipfile.ZipFile(rewritten, "w") as target:
                for info in source.infolist():
                    data = source.read(info.filename)
                    if info.filename == DESCRIPTOR_ENTRY:
                        data = data.replace(b'"version": "0.1.0"', b'"version": "9.9.9"')
                    target.writestr(info, data)
        jar.unlink()
        rewritten.rename(jar)
        result = self.stage(expect_ok=False)
        self.assertIn("does not match tracked descriptor", result.stderr)
        self.assertFalse(self.output.exists())

    def test_embedded_i18n_incomplete_rejected(self):
        libs = self.tmp / "build/market-publish/mcp/libs"
        jar = libs / "mcp-0.42.0-SNAPSHOT-market-publish.jar"
        rewritten = self.tmp / "rewritten.jar"
        with zipfile.ZipFile(jar) as source:
            with zipfile.ZipFile(rewritten, "w") as target:
                for info in source.infolist():
                    data = source.read(info.filename)
                    if info.filename == CATALOGS["ja"]:
                        data = data.replace(b"plugin.description=", b"other.key=")
                    target.writestr(info, data)
        jar.unlink()
        rewritten.rename(jar)
        result = self.stage(expect_ok=False)
        self.assertIn("plugin.description", result.stderr)
        self.assertFalse(self.output.exists())

    def test_missing_conditional_args_rejected(self):
        manifest = self.fixture.write_manifest([
            {"project": ":plugins:mcp", "channel": "stable", "cubismVersions": ["5.3.02"],
             "repository": "https://example.invalid/a", "support": "https://example.invalid/b"},
        ])
        for missing, expected in (("--output", "--output"),
                                  ("--build-root", "--build-root")):
            args = ["--manifest", str(manifest), "--source-revision", GIT_SHA]
            if missing == "--output":
                args += ["--build-root", str(self.tmp / "build")]
            else:
                args += ["--output", str(self.tmp / "out")]
            result = run_script(self.tmp, *args)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn(f"error: {expected}", result.stderr)
            self.assertNotIn("Traceback", result.stderr)
        # both missing
        result = run_script(self.tmp, "--manifest", str(manifest),
                            "--source-revision", GIT_SHA)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("error: --build-root", result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        self.assertFalse((self.tmp / "out").exists())
    def test_malformed_revision_rejected(self):
        result = run_script(self.tmp, "--manifest", str(self.manifest),
                            "--build-root", str(self.build_root),
                            "--output", str(self.output),
                            "--source-revision", "not-a-sha")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("source-revision", result.stderr)

    def test_failure_leaves_no_partial_output(self):
        shutil.rmtree(self.tmp / "build/market-publish/backup")
        result = self.stage(expect_ok=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.output.exists())
        leftovers = list(self.tmp.glob("release.tmp-*"))
        self.assertEqual(leftovers, [])


class WorkflowStaticTest(unittest.TestCase):
    def test_workflow_contract(self):
        text = WORKFLOW.read_text()
        # Trigger: push to main with relevant paths only
        self.assertIn("push:", text)
        self.assertIn("branches:", text)
        self.assertIn("main", text)
        self.assertIn("paths:", text)
        self.assertNotIn("workflow_dispatch", text)
        # Narrow permission: contents read only, no write-all
        self.assertIn("contents: read", text)
        self.assertNotIn("contents: write", text)
        self.assertNotIn("permissions: write-all", text)
        # Exact event SHA checkout with an immutable pinned action
        self.assertIn("ref: ${{ github.sha }}", text)
        self.assertRegex(text, r"actions/checkout@[0-9a-f]{40}")
        self.assertRegex(text, r"actions/setup-java@[0-9a-f]{40}")
        self.assertRegex(text, r"actions/upload-artifact@[0-9a-f]{40}")
        # Plan step gates every later step (empty short-circuit)
        plan_index = text.index("id: plan")
        guard = "steps.plan.outputs.selected != '0'"
        for step in ("Set up Java", "validatePluginMeta verifyFirstPartyPluginMetadata",
                     "Stage selected", "Upload staged", "Dispatch"):
            step_index = text.index(step)
            self.assertGreater(step_index, plan_index, step)
            self.assertIn(guard, text)
        self.assertLess(text.index("--plan"), text.index("setup-java"))
        # Fixed publication worktree id and exactly the existing gates
        self.assertIn("TURBOISM_WORKTREE_ID: market-publish", text)
        self.assertIn("validatePluginMeta verifyFirstPartyPluginMetadata", text)
        # Artifact contract: short retention, run-id-scoped name
        self.assertIn("retention-days: 1", text)
        # Artifact contract: short retention, exact provider-accepted name
        # turboism-market-release-<sha>-<run_id>-<run_attempt> used verbatim in
        # both the upload step and the Provider dispatch artifact_name input;
        # never a run-id-only form (a re-run keeps the same run id) and never
        # the pre-repair selected-plugins name.
        self.assertIn("retention-days: 1", text)
        attempt_name = (
            "turboism-market-release-${{ github.sha }}-${{ github.run_id }}-${{ github.run_attempt }}")
        self.assertEqual(text.count(attempt_name), 2)
        self.assertNotRegex(
            text, r"turboism-market-release-\$\{\{ github\.run_id \}\}(?!-\$\{\{ github\.run_attempt \}\})")
        self.assertNotIn("selected-plugins-", text)
        # Dispatch: provider workflow, main ref, run id/sha/artifact inputs,
        # single narrow secret, no source-side release creation
        self.assertIn("turboism/turboism-plugin-directory", text)
        self.assertIn("publish-plugin-directory-v2.yml/dispatches", text)
        self.assertIn("source_run_id", text)
        self.assertIn("source_sha", text)
        self.assertIn("artifact_name", text)
        self.assertEqual(text.count("secrets."), 1)
        self.assertIn("secrets.PLUGIN_DIRECTORY_DISPATCH_TOKEN", text)
        self.assertNotIn("github.token", text)
        # No .tplugin, no legacy packager, no release endpoints
        self.assertNotIn(".tplugin", text)
        self.assertNotIn("package-plugin.py", text)
        for forbidden in ("gh release", "create-release", "uploads/releases",
                          "releases/", "workflow_dispatch"):
            self.assertNotIn(forbidden, text)

    def test_workflow_does_not_build_extra_gates(self):
        text = WORKFLOW.read_text()
        self.assertNotIn("checkRelease", text)
        self.assertNotIn("checkIntegration", text)


if __name__ == "__main__":
    unittest.main()
