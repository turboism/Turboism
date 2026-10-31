"""Regression and integration tests for Windows bootstrap receipt initialization (Issue #50).

Verifies the integration of FrameworkUpdateHelper --initialize-receipt into the
first full installation and standard ZIP lifecycle, including cross-layer
consistency, real helper execution with Ed25519 signing and ACL verification,
upgraded receipt collision safety, and configurator propagation.
"""
from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/release"))
from turboism_release import framework_update


class BootstrapReceiptStaticContractTest(unittest.TestCase):
    """Static contracts governing bootstrap script packaging, layout, and cross-layer consistency."""

    def test_bootstrap_script_exists_and_declares_standard_parameters(self):
        script = ROOT / "packaging/windows-installer/bootstrap-receipt.ps1"
        self.assertTrue(script.is_file(), "bootstrap-receipt.ps1 must exist")
        text = script.read_text(encoding="utf-8")
        self.assertIn("[Alias(\"Home\")]", text)
        self.assertIn("[string]$HomePath", text)
        self.assertIn("[string]$StagePath", text)
        self.assertIn("[string]$JavaPath", text)
        self.assertIn("[string]$DiscoverySnapshot", text)
        self.assertIn("[string]$DiscoverySnapshotSha256", text)
        self.assertIn("[string[]]$HelperPrefixArgs", text)
        self.assertIn("[switch]$Strict", text)
        self.assertIn("[switch]$PassThru", text)
        self.assertIn("--initialize-receipt", text)
        self.assertIn("dev.turboism.update.FrameworkUpdateHelper", text)

    def test_cross_layer_layout_consistency_including_bootstrap_script(self):
        """Cross-layer consistency (Point 7): layout JSON, Java allowlist, Gradle staging, and assemble script."""
        # 1. JSON layout
        layout_file = ROOT / "packaging/framework-update-layout.json"
        layout = json.loads(layout_file.read_text(encoding="utf-8"))
        json_roots = set(layout.get("frameworkRootFiles", []))
        self.assertIn("bootstrap-receipt.ps1", json_roots)
        self.assertEqual(layout["frameworkRootFiles"], sorted(layout["frameworkRootFiles"]),
                         "frameworkRootFiles must remain ASCII sorted")

        # 2. Java UpdateManifestEntry.FRAMEWORK_ROOT_FILES
        java_entry = (ROOT / "runtime/src/main/java/dev/turboism/update/UpdateManifestEntry.java").read_text(encoding="utf-8")
        match = re.search(r"FRAMEWORK_ROOT_FILES\s*=\s*Set\.of\((.*?)\);", java_entry, re.DOTALL)
        self.assertIsNotNone(match, "FRAMEWORK_ROOT_FILES must be present in UpdateManifestEntry.java")
        java_roots = {item.strip().strip('"') for item in match.group(1).split(",") if item.strip()}
        self.assertIn("bootstrap-receipt.ps1", java_roots)
        self.assertEqual(json_roots, java_roots, "JSON layout and Java FRAMEWORK_ROOT_FILES must match exactly")

        # 3. installer.gradle.kts
        gradle = (ROOT / "packaging/java-installer/installer.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"packaging/windows-installer/bootstrap-receipt.ps1"', gradle)
        self.assertIn('from("packaging/windows-installer/bootstrap-receipt.ps1")', gradle)

        # 4. assemble-release.sh core payload
        assemble = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text(encoding="utf-8")
        self.assertIn('("bootstrap-receipt.ps1", stage / "bootstrap-receipt.ps1")', assemble)

    def test_assemble_release_embeds_bootstrap_receipt_and_restricts_dist_assets(self):
        """Point 1 & 2: assemble-release handles shared bootstrap stage and does not leak sidecars to dist."""
        assemble = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text(encoding="utf-8")
        self.assertIn("--bootstrap-receipt-dir", assemble)
        self.assertIn("bootstrap_bundle_dir", assemble)
        self.assertIn("-DBOOTSTRAP_RECEIPT_DIR", assemble)
        # Point 2: public dist must NOT receive manifest/sha256 sidecars
        self.assertNotIn('cp "$bootstrap_stage/manifest.json" "$dist', assemble)
        self.assertNotIn('sha256sum "turboism-$VER-manifest.json"', assemble)

    def test_installer_nsi_handles_setoutpath_and_uninstall(self):
        """Point 3: NSIS uses SetOutPath for bootstrap extraction and cleans up on uninstall."""
        nsi = (ROOT / "packaging/windows-installer/installer.nsi").read_text(encoding="utf-8")
        self.assertIn("!ifdef BOOTSTRAP_RECEIPT_DIR", nsi)
        self.assertIn('SetOutPath "$INSTDIR\\bootstrap"', nsi)
        self.assertIn('File "/oname=manifest.json"', nsi)
        self.assertIn('File "/oname=manifest.json.sig"', nsi)
        self.assertIn('SetOutPath "$INSTDIR"', nsi)
        self.assertIn('RMDir /r "$INSTDIR\\bootstrap"', nsi)
        self.assertIn('Delete "$INSTDIR\\bootstrap-receipt.ps1"', nsi)

    def test_configurator_dispatches_initialize_receipt_and_propagates_failures(self):
        """Point 4 & 5: configurator passes snapshot digest and propagates failures."""
        configurator = (ROOT / "packaging/windows-installer/configure_turboism.ps1").read_text(encoding="utf-8")
        self.assertIn("[switch]$InitializeReceipt", configurator)
        self.assertIn("[string]$ReceiptStage", configurator)
        self.assertIn("[string]$ReceiptJava", configurator)
        self.assertIn("DiscoverySnapshotSha256", configurator)
        self.assertIn("Bootstrap receipt initialization failed with exit code", configurator)


class BootstrapReceiptExecutionTest(unittest.TestCase):
    """Behavioral and integration tests executing bootstrap-receipt.ps1 in isolated environments."""

    def setUp(self):
        self.temp_dir = tempfile.mkdtemp(prefix="turboism-bootstrap-test-")
        self.fixture = Path(self.temp_dir)
        self.bootstrap_script = ROOT / "packaging/windows-installer/bootstrap-receipt.ps1"

    def tearDown(self):
        shutil.rmtree(self.temp_dir, ignore_errors=True)

    def _run_ps(self, args: list[str]) -> subprocess.CompletedProcess:
        cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
               "-File", str(self.bootstrap_script)] + args
        return subprocess.run(cmd, capture_output=True, text=True, errors="replace", timeout=60)

    def test_legacy_install_without_bootstrap_docs_skips_cleanly(self):
        home = self.fixture / "legacy-home"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"synthetic-agent")

        result = self._run_ps(["-HomePath", str(home)])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("No bootstrap documents found; self-update receipt initialization skipped", result.stdout)
        receipt = home / "state/runtime/update/installed-receipt.json"
        self.assertFalse(receipt.exists(), "Legacy install must not create installed-receipt.json")

    def test_legacy_install_with_strict_fails_closed(self):
        home = self.fixture / "legacy-strict"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"synthetic-agent")

        result = self._run_ps(["-HomePath", str(home), "-Strict"])
        self.assertNotEqual(result.returncode, 0)

    def test_upgraded_receipt_with_stale_bootstrap_documents_regression(self):
        """Point 4 regression: existing upgraded receipt must NOT be re-initialized from stale bootstrap docs."""
        home = self.fixture / "stale-home"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"synthetic-agent")

        # Stale 1.0.0 bootstrap documents present in bootstrap directory
        bootstrap_dir = home / "bootstrap"
        bootstrap_dir.mkdir()
        (bootstrap_dir / "manifest.json").write_text('{"version":"1.0.0"}', encoding="utf-8")
        (bootstrap_dir / "manifest.json.sig").write_text("old-signature", encoding="utf-8")

        # Upgraded 1.1.0 receipt already installed on disk
        state_dir = home / "state/runtime/update"
        state_dir.mkdir(parents=True)
        upgraded_receipt = state_dir / "installed-receipt.json"
        upgraded_receipt.write_text('{"schemaVersion":1,"version":"1.1.0","upgraded":true}', encoding="utf-8")
        expected_bytes = upgraded_receipt.read_bytes()

        # 1. Non-strict (default first-launch) mode: skips re-applying old bootstrap
        res_default = self._run_ps(["-HomePath", str(home)])
        self.assertEqual(res_default.returncode, 0, res_default.stderr)
        self.assertIn("Existing installation receipt detected; skipping bootstrap receipt initialization", res_default.stdout)
        self.assertEqual(upgraded_receipt.read_bytes(), expected_bytes, "Upgraded receipt must not be touched")

        # 2. Strict mode: does not silently skip, fails closed with error
        res_strict = self._run_ps(["-HomePath", str(home), "-Strict",
                                   "-JavaPath", "C:\\nonexistent\\java.exe"])
        self.assertNotEqual(res_strict.returncode, 0)
        self.assertEqual(upgraded_receipt.read_bytes(), expected_bytes, "Upgraded receipt must not be replaced")

    def test_real_framework_receipt_initialization_and_tamper_protection(self):
        """Point 6: Integration test calling REAL FrameworkUpdateReceipt/Helper with real Ed25519 signing and ACLs."""
        # Find compiled runtime test and main classes
        wt_id = "agent-issue50-bootstrap-receipt"
        test_classes = ROOT / f"build/worktree/{wt_id}/runtime/classes/java/test"
        main_classes = ROOT / f"build/worktree/{wt_id}/runtime/classes/java/main"
        if not (test_classes / "dev/turboism/update/TestKeyHelperMain.class").is_file():
            self.skipTest("TestKeyHelperMain.class not compiled; run ./gradlew :runtime:compileTestJava")

        # Generate genuine Ed25519 test keypair
        key_pem = self.fixture / "test-key.pem"
        framework_update.openssl(["genpkey", "-algorithm", "Ed25519", "-out", str(key_pem)])
        pub_der = framework_update.openssl(["pkey", "-in", str(key_pem), "-pubout", "-outform", "DER"])
        pub_file = self.fixture / "test-pub.txt"
        pub_file.write_text(base64.b64encode(pub_der).decode("ascii"), encoding="utf-8")
        keyring = self.fixture / "keyring.json"
        keyring.write_text(json.dumps({"local-test": base64.b64encode(pub_der).decode("ascii")}), encoding="utf-8")

        # Set up authentic installation payload matching frameworkRootFiles
        home = self.fixture / "real-home"
        home.mkdir()
        entries = []
        layout_policy = json.loads((ROOT / "packaging/framework-update-layout.json").read_text(encoding="utf-8"))
        for name in layout_policy["frameworkRootFiles"]:
            f = home / name
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(f"payload-bytes:{name}\n".encode("utf-8"))
            digest = hashlib.sha256(f.read_bytes()).hexdigest()
            entries.append({"relativePath": name, "bytes": f.stat().st_size, "sha256": digest, "contentId": "sha256:" + digest})
        graal = home / "graal/lib"
        graal.mkdir(parents=True)
        for lib in ("sdk-1.0.0.jar", "graal-host-1.0.0.jar"):
            jar = graal / lib
            jar.write_bytes(f"graal-lib:{lib}\n".encode("utf-8"))
            jar_digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            entries.append({"relativePath": f"graal/lib/{lib}", "bytes": jar.stat().st_size, "sha256": jar_digest, "contentId": "sha256:" + jar_digest})
        entries.sort(key=lambda e: e["relativePath"])

        manifest_doc = {
            "schemaVersion": 1,
            "keyId": "local-test",
            "version": "1.0.0",
            "channel": "stable",
            "buildNumber": 1,
            "sourceRevision": "a" * 40,
            "minimumUpdaterVersion": "0.1.0",
            "targetPlatform": "windows-x64",
            "entries": entries,
        }
        manifest_bytes = framework_update.canonical_bytes(manifest_doc)
        sig_bytes = framework_update.sign(manifest_bytes, "local-test", key_pem, keyring, self.fixture)

        stage = self.fixture / "real-stage"
        stage.mkdir()
        (stage / "manifest.json").write_bytes(manifest_bytes)
        (stage / "manifest.json.sig").write_bytes(sig_bytes)

        # Assemble full test runtime classpath
        cp_elements = [
            str(test_classes),
            str(main_classes),
            str(ROOT / f"build/worktree/{wt_id}/runtime/resources/main"),
            str(ROOT / f"build/worktree/{wt_id}/sdk/classes/java/main"),
            str(ROOT / f"build/worktree/{wt_id}/core-contract/classes/java/main"),
            str(Path.home() / ".gradle/caches/modules-2/files-2.1/com.fasterxml.jackson.core/jackson-core/2.18.9/ca645ad40f7be06d1a0bbdf9f363d3838b41f40/jackson-core-2.18.9.jar"),
            str(Path.home() / ".gradle/caches/modules-2/files-2.1/com.fasterxml.jackson.core/jackson-databind/2.18.9/93f3c1564ae82c1f5c034883f9ce45782054e04/jackson-databind-2.18.9.jar"),
            str(Path.home() / ".gradle/caches/modules-2/files-2.1/com.fasterxml.jackson.core/jackson-annotations/2.18.9/c19aab861fcdf6e2f185ad84fb23f0a8383097f8/jackson-annotations-2.18.9.jar"),
        ]
        classpath = ";".join(cp_elements)

        # Execute bootstrap-receipt.ps1 with the real TestKeyHelperMain
        init_res = self._run_ps([
            "-HomePath", str(home),
            "-StagePath", str(stage),
            "-Classpath", classpath,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain",
            "-HelperPrefixArgs", str(pub_file),
        ])
        self.assertEqual(init_res.returncode, 0, init_res.stderr)
        self.assertIn("Framework update receipt initialized successfully", init_res.stdout)

        receipt = home / "state/runtime/update/installed-receipt.json"
        self.assertTrue(receipt.is_file(), "Real FrameworkUpdateReceipt must have created installed-receipt.json")
        receipt_data = json.loads(receipt.read_text(encoding="utf-8"))
        self.assertEqual(receipt_data["format"], "turboism-installed-receipt-v1")
        self.assertEqual(receipt_data["layout"], "windows-standard-zip")
        self.assertEqual(receipt_data["manifestSha256"], hashlib.sha256(manifest_bytes).hexdigest())

        # Verify immutable document was published under receipts/<digest>/
        receipt_docs = list((home / "state/runtime/update/receipts").rglob("manifest.json"))
        self.assertEqual(len(receipt_docs), 1, "Exactly one published manifest document expected")
        self.assertEqual(receipt_docs[0].read_bytes(), manifest_bytes)
        published_manifest_data = json.loads(receipt_docs[0].read_text(encoding="utf-8"))
        self.assertEqual(published_manifest_data["version"], "1.0.0")

        # Tampered payload fails closed (Point 6): modifying one payload file fails verification
        (home / "README.txt").write_bytes(b"tampered-bytes-conflict")
        tamper_home = self.fixture / "tamper-real-home"
        shutil.copytree(home, tamper_home)
        (tamper_home / "state/runtime/update/installed-receipt.json").unlink()

        tamper_res = self._run_ps([
            "-HomePath", str(tamper_home),
            "-StagePath", str(stage),
            "-Classpath", classpath,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain",
            "-HelperPrefixArgs", str(pub_file),
            "-Strict",
        ])
        self.assertEqual(tamper_res.returncode, 74, "Real helper must reject tampered payload with exit code 74")
        self.assertFalse((tamper_home / "state/runtime/update/installed-receipt.json").exists())

    def test_zip_builder_embeds_bootstrap_receipt_when_provided(self):
        """Point 1: ZIP builder includes bootstrap/manifest.json and .sig when directory is passed."""
        assemble_text = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text(encoding="utf-8")
        block = assemble_text.split("zip_dir() {", 1)[1].split("<<'PYEOF'\n", 1)[1].split("\nPYEOF", 1)[0]

        stage = self.fixture / "zip-stage"
        stage.mkdir()
        (stage / "turboism-agent.jar").write_bytes(b"agent")
        (stage / "plugins").mkdir()
        (stage / "plugins/acp.jar").write_bytes(b"plugin")
        (stage / "config.template.json").write_text("{}", encoding="utf-8")

        bdir = self.fixture / "zip-bootstrap"
        bdir.mkdir()
        (bdir / "manifest.json").write_text('{"version":"1.0.0"}', encoding="utf-8")
        (bdir / "manifest.json.sig").write_text("test-sig", encoding="utf-8")

        out_zip = self.fixture / "test-package.zip"
        res = subprocess.run([sys.executable, "-", str(stage), str(out_zip), "1.0.0", str(bdir)],
                             input=block, text=True, capture_output=True, check=True)
        self.assertEqual(res.returncode, 0)

        with zipfile.ZipFile(out_zip) as z:
            names = set(z.namelist())
            self.assertIn("bootstrap/manifest.json", names, "bootstrap/manifest.json must be in standard ZIP")
            self.assertIn("bootstrap/manifest.json.sig", names, "bootstrap/manifest.json.sig must be in standard ZIP")
            self.assertEqual(z.read("bootstrap/manifest.json"), b'{"version":"1.0.0"}')
            self.assertEqual(z.read("bootstrap/manifest.json.sig"), b"test-sig")


if __name__ == "__main__":
    unittest.main()
