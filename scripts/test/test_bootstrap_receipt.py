"""Regression and behavioral unit tests for Windows bootstrap receipt initialization (Issue #50).

Verifies the integration of FrameworkUpdateHelper --initialize-receipt into the
first full installation and standard ZIP lifecycle, including legacy package
fallback, tamper/collision protection, and configurator/launcher dispatch.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


class BootstrapReceiptStaticContractTest(unittest.TestCase):
    """Static contracts governing bootstrap script packaging and layout."""

    def test_bootstrap_script_exists_and_declares_standard_parameters(self):
        script = ROOT / "packaging/windows-installer/bootstrap-receipt.ps1"
        self.assertTrue(script.is_file(), "bootstrap-receipt.ps1 must exist")
        text = script.read_text(encoding="utf-8")
        self.assertIn("[Alias(\"Home\")]", text)
        self.assertIn("[string]$HomePath", text)
        self.assertIn("[string]$StagePath", text)
        self.assertIn("[string]$JavaPath", text)
        self.assertIn("[string]$DiscoverySnapshot", text)
        self.assertIn("[switch]$Strict", text)
        self.assertIn("[switch]$PassThru", text)
        self.assertIn("--initialize-receipt", text)
        self.assertIn("dev.turboism.update.FrameworkUpdateHelper", text)

    def test_installer_gradle_declares_bootstrap_script(self):
        gradle = (ROOT / "packaging/java-installer/installer.gradle.kts").read_text(encoding="utf-8")
        self.assertIn('"packaging/windows-installer/bootstrap-receipt.ps1"', gradle)
        self.assertIn('from("packaging/windows-installer/bootstrap-receipt.ps1")', gradle)

    def test_framework_layout_contains_bootstrap_script_sorted(self):
        layout_file = ROOT / "packaging/framework-update-layout.json"
        layout = json.loads(layout_file.read_text(encoding="utf-8"))
        roots = layout.get("frameworkRootFiles", [])
        self.assertIn("bootstrap-receipt.ps1", roots)
        self.assertEqual(roots, sorted(roots), "frameworkRootFiles must remain ASCII sorted")

    def test_assemble_release_embeds_and_unpacks_bootstrap_receipt(self):
        assemble = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text(encoding="utf-8")
        self.assertIn('("bootstrap-receipt.ps1", stage / "bootstrap-receipt.ps1")', assemble)
        self.assertIn("--bootstrap-receipt-dir", assemble)
        self.assertIn("-DBOOTSTRAP_RECEIPT_DIR", assemble)

    def test_installer_nsi_handles_conditional_bootstrap_and_uninstall(self):
        nsi = (ROOT / "packaging/windows-installer/installer.nsi").read_text(encoding="utf-8")
        self.assertIn("!ifdef BOOTSTRAP_RECEIPT_DIR", nsi)
        self.assertIn('Delete "$INSTDIR\\bootstrap-receipt.ps1"', nsi)

    def test_configurator_dispatches_initialize_receipt(self):
        configurator = (ROOT / "packaging/windows-installer/configure_turboism.ps1").read_text(encoding="utf-8")
        self.assertIn("[switch]$InitializeReceipt", configurator)
        self.assertIn("[string]$ReceiptStage", configurator)
        self.assertIn("bootstrap-receipt.ps1", configurator)


class BootstrapReceiptExecutionTest(unittest.TestCase):
    """Behavioral tests executing bootstrap-receipt.ps1 in isolated temp directories."""

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

    def test_receipt_initialization_with_mock_helper_succeeds(self):
        # Compile a synthetic mock helper to verify the exact helper CLI contract
        java_source = (
            "import java.nio.charset.StandardCharsets;\n"
            "import java.nio.file.Files;\n"
            "import java.nio.file.Path;\n"
            "public class MockHelperMain {\n"
            "    public static void main(String[] args) throws Exception {\n"
            "        Path root = null, stage = null, work = null;\n"
            "        boolean init = false;\n"
            "        for (int i = 0; i < args.length; i++) {\n"
            "            if (\"--root\".equals(args[i])) root = Path.of(args[++i]);\n"
            "            else if (\"--stage\".equals(args[i])) stage = Path.of(args[++i]);\n"
            "            else if (\"--work\".equals(args[i])) work = Path.of(args[++i]);\n"
            "            else if (\"--initialize-receipt\".equals(args[i])) init = true;\n"
            "        }\n"
            "        if (root == null || stage == null || work == null || !init) {\n"
            "            System.err.println(\"USAGE ERROR\"); System.exit(64);\n"
            "        }\n"
            "        Path manifest = stage.resolve(\"manifest.json\");\n"
            "        Path sig = stage.resolve(\"manifest.json.sig\");\n"
            "        if (!Files.isRegularFile(manifest) || !Files.isRegularFile(sig)) {\n"
            "            System.err.println(\"MISSING DOCS\"); System.exit(70);\n"
            "        }\n"
            "        String m = Files.readString(manifest, StandardCharsets.UTF_8);\n"
            "        if (m.contains(\"TAMPER\")) { System.err.println(\"TAMPER\"); System.exit(74); }\n"
            "        Path idx = root.resolve(\"state/runtime/update/installed-receipt.json\");\n"
            "        if (Files.exists(idx)) {\n"
            "            String cur = Files.readString(idx, StandardCharsets.UTF_8);\n"
            "            if (cur.contains(\"DIFFERENT\")) {\n"
            "                System.err.println(\"Existing different receipt\"); System.exit(74);\n"
            "            }\n"
            "        }\n"
            "        Files.createDirectories(idx.getParent());\n"
            "        Files.writeString(idx, \"{\\\"version\\\":\\\"1.0.0\\\"}\", StandardCharsets.UTF_8);\n"
            "        Path docs = root.resolve(\"state/runtime/update/receipts/doc-sha\");\n"
            "        Files.createDirectories(docs);\n"
            "        Files.copy(manifest, docs.resolve(\"manifest.json\"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);\n"
            "        Files.copy(sig, docs.resolve(\"manifest.json.sig\"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);\n"
            "        System.out.println(\"RECEIPT_INITIALIZED: 1.0.0\");\n"
            "        System.exit(10);\n"  # EXIT_NOTHING from helper
            "    }\n"
            "}\n"
        )
        src = self.fixture / "MockHelperMain.java"
        src.write_text(java_source, encoding="utf-8")
        compiled = subprocess.run(["javac", "-d", str(self.fixture), str(src)], capture_output=True)
        if compiled.returncode != 0:
            self.skipTest("javac unavailable or failed to compile mock helper")

        home = self.fixture / "app-home"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"agent-payload")
        stage = self.fixture / "stage"
        stage.mkdir()
        (stage / "manifest.json").write_text('{"version":"1.0.0"}', encoding="utf-8")
        (stage / "manifest.json.sig").write_text("signature", encoding="utf-8")

        result = self._run_ps([
            "-HomePath", str(home),
            "-StagePath", str(stage),
            "-Classpath", str(self.fixture),
            "-HelperClass", "MockHelperMain",
        ])
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("Framework update receipt initialized successfully", result.stdout)

        receipt = home / "state/runtime/update/installed-receipt.json"
        self.assertTrue(receipt.is_file(), "installed-receipt.json must be created")
        self.assertEqual(json.loads(receipt.read_text(encoding="utf-8")), {"version": "1.0.0"})

        doc = home / "state/runtime/update/receipts/doc-sha/manifest.json"
        self.assertTrue(doc.is_file(), "immutable receipt document must be published")

        # Idempotency: second run succeeds
        idem = self._run_ps([
            "-HomePath", str(home),
            "-StagePath", str(stage),
            "-Classpath", str(self.fixture),
            "-HelperClass", "MockHelperMain",
        ])
        self.assertEqual(idem.returncode, 0, idem.stderr)
        self.assertIn("Framework update receipt initialized successfully", idem.stdout)

        # Tampered payload fails closed (exit 74)
        tamper_stage = self.fixture / "tamper-stage"
        tamper_stage.mkdir()
        (tamper_stage / "manifest.json").write_text('{"version":"1.0.0","tag":"TAMPER"}', encoding="utf-8")
        (tamper_stage / "manifest.json.sig").write_text("sig", encoding="utf-8")
        tamper_home = self.fixture / "tamper-home"
        tamper_home.mkdir()
        (tamper_home / "turboism-agent.jar").write_bytes(b"agent")

        tamper_result = self._run_ps([
            "-HomePath", str(tamper_home),
            "-StagePath", str(tamper_stage),
            "-Classpath", str(self.fixture),
            "-HelperClass", "MockHelperMain",
        ])
        self.assertEqual(tamper_result.returncode, 74)
        self.assertFalse((tamper_home / "state/runtime/update/installed-receipt.json").exists())

        # Existing different receipt fails closed and is NOT silently replaced
        diff_home = self.fixture / "diff-home"
        diff_state = diff_home / "state/runtime/update"
        diff_state.mkdir(parents=True)
        (diff_home / "turboism-agent.jar").write_bytes(b"agent")
        diff_receipt = diff_state / "installed-receipt.json"
        diff_receipt.write_text('{"version":"0.9.0","DIFFERENT":true}', encoding="utf-8")
        original_bytes = diff_receipt.read_bytes()

        diff_result = self._run_ps([
            "-HomePath", str(diff_home),
            "-StagePath", str(stage),
            "-Classpath", str(self.fixture),
            "-HelperClass", "MockHelperMain",
        ])
        self.assertEqual(diff_result.returncode, 74)
        self.assertEqual(diff_receipt.read_bytes(), original_bytes, "different receipt must NOT be silently replaced")


if __name__ == "__main__":
    unittest.main()
