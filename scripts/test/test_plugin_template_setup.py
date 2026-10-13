#!/usr/bin/env python3
"""Offline tests for templates/plugin-template/scripts/setup.sh and setup.ps1."""
from __future__ import annotations

import hashlib
import http.server
import json
import os
import shutil
import socketserver
import subprocess
import tempfile
import threading
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TEMPLATE_DIR = ROOT / "templates/plugin-template"


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def make_fake_sdk_jar(path: Path) -> bytes:
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        zf.writestr("dev/turboism/sdk/plugin/TurboismPlugin.class", b"fake-sdk-class")
    return path.read_bytes()


def make_fake_processor_jar(path: Path) -> bytes:
    with zipfile.ZipFile(path, "w") as zf:
        zf.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        zf.writestr(
            "dev/turboism/eventprocessor/SubscribeEventProcessor.class",
            b"fake-processor-class",
        )
        zf.writestr(
            "META-INF/services/javax.annotation.processing.Processor",
            "dev.turboism.eventprocessor.SubscribeEventProcessor\n",
        )
    return path.read_bytes()


class QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args: object) -> None:
        pass


class PluginTemplateSetupTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = tempfile.TemporaryDirectory(prefix="turboism-template-setup-test-")
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)

    def copy_template(self, name: str = "project") -> Path:
        dest = self.work / name
        for src in sorted(TEMPLATE_DIR.rglob("*")):
            if not src.is_file():
                continue
            rel = src.relative_to(TEMPLATE_DIR)
            if rel.parts[0] in ("build", ".gradle"):
                continue
            if rel.parts[0] == "libs" and (
                rel.name.endswith(".jar") or rel.name.endswith(".sha256")
            ):
                continue
            target = dest / rel
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, target)
        return dest

    def populate_release_fixture(
        self,
        serve_root: Path,
        version: str = "0.67.0",
        channel: str = "stable",
        compat_versions: list[str] | None = None,
        tamper_sdk_hash: bool = False,
        tamper_proc_hash: bool = False,
        include_sdk: bool = True,
        include_proc: bool = True,
        sdk_jar_source: Path | None = None,
        proc_jar_source: Path | None = None,
    ) -> None:
        tag = f"v{version}"
        dl_dir = serve_root / "releases/download" / tag
        dl_dir.mkdir(parents=True, exist_ok=True)

        sdk_name = f"turboism-sdk-{version}.jar"
        proc_name = f"turboism-event-processor-{version}.jar"
        sdk_path = dl_dir / sdk_name
        proc_path = dl_dir / proc_name

        if include_sdk:
            if sdk_jar_source is not None:
                sdk_bytes = sdk_jar_source.read_bytes()
                sdk_path.write_bytes(sdk_bytes)
            else:
                sdk_bytes = make_fake_sdk_jar(sdk_path)
            sdk_hash = "0" * 64 if tamper_sdk_hash else sha256_hex(sdk_bytes)
            (dl_dir / f"{sdk_name}.sha256").write_text(
                f"{sdk_hash}  {sdk_name}\n", encoding="utf-8"
            )

        if include_proc:
            if proc_jar_source is not None:
                proc_bytes = proc_jar_source.read_bytes()
                proc_path.write_bytes(proc_bytes)
            else:
                proc_bytes = make_fake_processor_jar(proc_path)
            proc_hash = "0" * 64 if tamper_proc_hash else sha256_hex(proc_bytes)
            (dl_dir / f"{proc_name}.sha256").write_text(
                f"{proc_hash}  {proc_name}\n", encoding="utf-8"
            )

        if compat_versions is None:
            compat_versions = ["5.2.03", "5.3.02", "5.3.03", "5.3.04"]

        api_dir = serve_root / "v1/releases"
        api_dir.mkdir(parents=True, exist_ok=True)
        release_doc = {
            "schemaVersion": 1,
            "channel": channel,
            "status": "ready",
            "release": {
                "version": version,
                "tag": tag,
                "channel": channel,
                "compatibility": {
                    "cubismEditor": {
                        "versions": compat_versions,
                        "ranges": [{"from": compat_versions[0], "to": compat_versions[-1]}],
                    },
                    "platforms": ["windows-x64"],
                    "matrixUrl": "https://turboism.dev/docs/use/compatibility",
                },
            },
        }
        (api_dir / f"{channel}.json").write_text(
            json.dumps(release_doc), encoding="utf-8"
        )

        gh_dir = serve_root / "repos/turboism/Turboism/releases"
        gh_dir.mkdir(parents=True, exist_ok=True)
        marker = json.dumps(
            {
                "schemaVersion": 1,
                "cubismEditor": {
                    "versions": compat_versions,
                    "ranges": [{"from": compat_versions[0], "to": compat_versions[-1]}],
                },
                "platforms": ["windows-x64"],
                "matrixUrl": "https://turboism.dev/docs/use/compatibility",
            }
        )
        gh_release = {
            "id": 1,
            "tag_name": tag,
            "draft": False,
            "prerelease": channel != "stable",
            "body": f"Release notes\n<!-- turboism-compatibility-v1 {marker} -->\n",
        }
        (gh_dir / "latest").write_text(json.dumps(gh_release), encoding="utf-8")
        tags_dir = gh_dir / "tags"
        tags_dir.mkdir(parents=True, exist_ok=True)
        (tags_dir / tag).write_text(json.dumps(gh_release), encoding="utf-8")

    def test_file_override_rename_and_idempotency(self) -> None:
        serve = self.work / "serve"
        self.populate_release_fixture(
            serve,
            version="0.67.0",
            channel="stable",
            compat_versions=["5.2.03", "5.3.02", "5.3.03", "5.3.04"],
        )
        project = self.copy_template("renamed-project")
        env = os.environ.copy()
        env["TURBOISM_SETUP_RELEASE_API_URL"] = f"file://{serve}/v1/releases"
        env["TURBOISM_SETUP_GITHUB_API_URL"] = f"file://{serve}/repos/turboism/Turboism/releases"
        env["TURBOISM_SETUP_DOWNLOAD_BASE_URL"] = f"file://{serve}/releases/download"

        cmd = [
            "bash",
            str(project / "scripts/setup.sh"),
            "--channel",
            "stable",
            "--id",
            "com.acme.symmetry-helper",
            "--name",
            "Symmetry Helper",
            "--package",
            "com.acme.symmetry",
            "--author",
            "Ada Lovelace",
            "--requires-cubism",
            "true",
            "--yes",
        ]
        res = subprocess.run(cmd, cwd=project, env=env, capture_output=True, text=True)
        self.assertEqual(res.returncode, 0, res.stderr + "\n" + res.stdout)

        # Verify JARs and sidecars in libs/
        self.assertTrue((project / "libs/turboism-sdk-0.67.0.jar").is_file())
        self.assertTrue((project / "libs/turboism-event-processor-0.67.0.jar").is_file())
        self.assertIn(
            "turboismSdkVersion=0.67.0",
            (project / "gradle.properties").read_text(encoding="utf-8"),
        )

        # Verify Java source rename and old directory cleanup
        new_java = project / "src/main/java/com/acme/symmetry/SymmetryHelperPlugin.java"
        self.assertTrue(new_java.is_file())
        self.assertFalse((project / "src/main/java/dev").exists())
        java_text = new_java.read_text(encoding="utf-8")
        self.assertIn("package com.acme.symmetry;", java_text)
        self.assertIn("public class SymmetryHelperPlugin implements TurboismPlugin", java_text)

        # Verify plugin.json
        plugin_json = json.loads(
            (project / "src/main/resources/META-INF/turboism/plugin.json").read_text(
                encoding="utf-8"
            )
        )
        self.assertEqual(plugin_json["id"], "com.acme.symmetry-helper")
        self.assertEqual(plugin_json["name"], "Symmetry Helper")
        self.assertEqual(
            plugin_json["entrypoints"], ["com.acme.symmetry.SymmetryHelperPlugin"]
        )
        self.assertEqual(plugin_json["authors"], [{"name": "Ada Lovelace"}])
        self.assertTrue(plugin_json["environment"]["requiresCubism"])

        # Verify i18n
        for prop_name in ("messages.properties", "messages_en.properties"):
            prop_text = (
                project / "src/main/resources/META-INF/turboism/i18n" / prop_name
            ).read_text(encoding="utf-8")
            self.assertIn("plugin.name=Symmetry Helper", prop_text)

        # Verify cubism-versions.json uses compatibility from release API
        cubism_json = json.loads(
            (
                project / "src/main/resources/META-INF/turboism/cubism-versions.json"
            ).read_text(encoding="utf-8")
        )
        self.assertEqual(cubism_json["format"], "turboism.plugin-cubism-versions/v1")
        self.assertEqual(cubism_json["plugin"], "com.acme.symmetry-helper")
        self.assertEqual(
            cubism_json["supportedVersions"],
            ["5.2.03", "5.3.02", "5.3.03", "5.3.04"],
        )

        # Re-run setup idempotently with a newer version and updated name
        self.populate_release_fixture(
            serve,
            version="0.67.1",
            channel="stable",
            compat_versions=["5.3.02", "5.3.03", "5.3.04"],
        )
        res2 = subprocess.run(cmd, cwd=project, env=env, capture_output=True, text=True)
        self.assertEqual(res2.returncode, 0, res2.stderr + "\n" + res2.stdout)
        self.assertTrue((project / "libs/turboism-sdk-0.67.1.jar").is_file())
        self.assertFalse((project / "libs/turboism-sdk-0.67.0.jar").exists())
        self.assertTrue((project / "libs/turboism-event-processor-0.67.1.jar").is_file())
        self.assertFalse((project / "libs/turboism-event-processor-0.67.0.jar").exists())

    def test_http_fixture_github_fallback_and_hash_mismatch_rejection(self) -> None:
        serve = self.work / "http-serve"
        self.populate_release_fixture(
            serve,
            version="0.67.0-beta.2",
            channel="beta",
            compat_versions=["5.0.00", "5.3.04"],
            tamper_proc_hash=True,
        )

        handler = lambda *a, **kw: QuietHandler(*a, directory=str(serve), **kw)  # noqa: E731
        with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
            port = httpd.server_address[1]
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                project = self.copy_template("hash-mismatch-project")
                env = os.environ.copy()
                # Point release API at a missing path so it falls back to GitHub API
                env["TURBOISM_SETUP_RELEASE_API_URL"] = f"http://127.0.0.1:{port}/unavailable"
                env["TURBOISM_SETUP_GITHUB_API_URL"] = (
                    f"http://127.0.0.1:{port}/repos/turboism/Turboism/releases"
                )
                env["TURBOISM_SETUP_DOWNLOAD_BASE_URL"] = (
                    f"http://127.0.0.1:{port}/releases/download"
                )

                res = subprocess.run(
                    [
                        "bash",
                        str(project / "scripts/setup.sh"),
                        "--version",
                        "0.67.0-beta.2",
                        "--yes",
                    ],
                    cwd=project,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(res.returncode, 0)
                self.assertIn("SHA-256 mismatch", res.stderr)
                self.assertEqual(list((project / "libs").glob("*.jar")), [])
            finally:
                httpd.shutdown()

    def test_sdk_only_release_warns_and_configures_without_processor(self) -> None:
        serve = self.work / "sdk-only-serve"
        self.populate_release_fixture(
            serve,
            version="0.67.0-beta.1",
            channel="beta",
            compat_versions=["5.0.00", "5.3.04"],
            include_sdk=True,
            include_proc=False,
        )
        handler = lambda *a, **kw: QuietHandler(*a, directory=str(serve), **kw)  # noqa: E731
        with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
            port = httpd.server_address[1]
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                project = self.copy_template("sdk-only-project")
                env = os.environ.copy()
                env["TURBOISM_SETUP_RELEASE_API_URL"] = f"http://127.0.0.1:{port}/v1/releases"
                env["TURBOISM_SETUP_GITHUB_API_URL"] = (
                    f"http://127.0.0.1:{port}/repos/turboism/Turboism/releases"
                )
                env["TURBOISM_SETUP_DOWNLOAD_BASE_URL"] = (
                    f"http://127.0.0.1:{port}/releases/download"
                )
                res = subprocess.run(
                    ["bash", str(project / "scripts/setup.sh"), "--channel", "beta", "--yes"],
                    cwd=project,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(res.returncode, 0, res.stderr + "\n" + res.stdout)
                self.assertIn("@SubscribeEvent catalogs will not be generated", res.stderr)
                self.assertIn("use EventBus#subscribe", res.stderr)
                self.assertTrue((project / "libs/turboism-sdk-0.67.0-beta.1.jar").is_file())
                self.assertEqual(
                    list((project / "libs").glob("turboism-event-processor-*.jar")), []
                )
            finally:
                httpd.shutdown()

    def test_release_without_sdk_fails_with_actionable_message_and_ci_fallback_works(self) -> None:
        serve = self.work / "no-sdk-serve"
        # stable 0.44.0 has no SDK jar; beta 0.67.0-beta.1 has SDK only
        self.populate_release_fixture(
            serve,
            version="0.44.0",
            channel="stable",
            compat_versions=["5.2.03", "5.3.02", "5.3.03"],
            include_sdk=False,
            include_proc=False,
        )
        self.populate_release_fixture(
            serve,
            version="0.67.0-beta.1",
            channel="beta",
            compat_versions=["5.0.00", "5.3.04"],
            include_sdk=True,
            include_proc=False,
        )
        handler = lambda *a, **kw: QuietHandler(*a, directory=str(serve), **kw)  # noqa: E731
        with socketserver.TCPServer(("127.0.0.1", 0), handler) as httpd:
            port = httpd.server_address[1]
            thread = threading.Thread(target=httpd.serve_forever, daemon=True)
            thread.start()
            try:
                project = self.copy_template("no-sdk-project")
                env = os.environ.copy()
                env["TURBOISM_SETUP_RELEASE_API_URL"] = f"http://127.0.0.1:{port}/v1/releases"
                env["TURBOISM_SETUP_GITHUB_API_URL"] = (
                    f"http://127.0.0.1:{port}/repos/turboism/Turboism/releases"
                )
                env["TURBOISM_SETUP_DOWNLOAD_BASE_URL"] = (
                    f"http://127.0.0.1:{port}/releases/download"
                )
                res = subprocess.run(
                    ["bash", str(project / "scripts/setup.sh"), "--channel", "stable", "--yes"],
                    cwd=project,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertNotEqual(res.returncode, 0)
                self.assertIn("predates standalone SDK distribution", res.stderr)
                self.assertIn(
                    "use --channel beta or --version <release with SDK>", res.stderr
                )
                self.assertEqual(list((project / "libs").glob("*.jar")), [])

                # Verify the CI workflow's stable -> beta fallback logic against this fixture
                ci_cmd = (
                    'set -euo pipefail; LOG="$(mktemp)"; trap \'rm -f "$LOG"\' EXIT; '
                    'if ./scripts/setup.sh --channel stable --yes 2>&1 | tee "$LOG"; then exit 0; fi; '
                    'if grep -Fq "predates standalone SDK distribution" "$LOG"; then '
                    './scripts/setup.sh --channel beta --yes; else exit 1; fi'
                )
                ci_res = subprocess.run(
                    ["bash", "-c", ci_cmd],
                    cwd=project,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(ci_res.returncode, 0, ci_res.stderr + "\n" + ci_res.stdout)
                self.assertTrue((project / "libs/turboism-sdk-0.67.0-beta.1.jar").is_file())
            finally:
                httpd.shutdown()

    def test_powershell_script_contract_parity(self) -> None:
        ps1 = (TEMPLATE_DIR / "scripts/setup.ps1").read_text(encoding="utf-8")
        for token in (
            "https://api.turboism.dev/v1/releases",
            "https://api.github.com/repos/turboism/Turboism/releases",
            "turboism-sdk-$ResolvedVersion.jar",
            "turboism-event-processor-$ResolvedVersion.jar",
            "Get-FileHash -LiteralPath $JarFile -Algorithm SHA256",
            "META-INF/turboism/cubism-versions.json",
            "turboism.plugin-cubism-versions/v1",
            "turboismSdkVersion=$ResolvedVersion",
            "Invoke-OptionalDownload",
            "predates standalone SDK distribution",
            "use --channel beta or --version <release with SDK>",
            "@SubscribeEvent catalogs will not be generated; use EventBus#subscribe, or rerun setup after a release that ships turboism-event-processor.",
        ):
            self.assertIn(token, ps1)
        self.assertNotIn("X-Turboism-Download-Purpose", ps1)
        sh = (TEMPLATE_DIR / "scripts/setup.sh").read_text(encoding="utf-8")
        self.assertNotIn("X-Turboism-Download-Purpose", sh)
        workflow = (TEMPLATE_DIR / ".github/workflows/build.yml").read_text(encoding="utf-8")
        self.assertIn("./scripts/setup.sh --channel stable --yes", workflow)
        self.assertIn("predates standalone SDK distribution", workflow)
        self.assertIn("./scripts/setup.sh --channel beta --yes", workflow)


if __name__ == "__main__":
    unittest.main(verbosity=2)
