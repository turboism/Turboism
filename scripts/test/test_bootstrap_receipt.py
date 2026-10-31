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


def resolve_worktree_id() -> str:
    res = subprocess.run(["bash", str(ROOT / "scripts/dev/worktree-id.sh"), "--resolve"],
                         capture_output=True, text=True, check=True)
    return res.stdout.strip()


def find_cached_jar(group: str, artifact: str) -> Path:
    gradle_user_home = Path(os.environ.get("GRADLE_USER_HOME", Path.home() / ".gradle"))
    cache_root = gradle_user_home / "caches/modules-2/files-2.1"
    matches = [p for p in cache_root.glob(f"{group}/{artifact}/**/*.jar")
               if not p.name.endswith("-sources.jar") and not p.name.endswith("-javadoc.jar")]
    if not matches:
        raise FileNotFoundError(f"Dependency {group}:{artifact} not found in Gradle cache")
    return matches[0]


def ensure_runtime_test_classes(wt_id: str) -> tuple[Path, Path]:
    test_classes = ROOT / f"build/worktree/{wt_id}/runtime/classes/java/test"
    main_classes = ROOT / f"build/worktree/{wt_id}/runtime/classes/java/main"
    helper_class = test_classes / "dev/turboism/update/TestKeyHelperMain.class"
    if not helper_class.is_file():
        gradlew = "gradlew.bat" if os.name == "nt" else "./gradlew"
        cmd = ["cmd", "/c", gradlew] if os.name == "nt" else [gradlew]
        cmd += [":runtime:compileTestJava", "--console=plain"]
        subprocess.run(cmd, cwd=ROOT, check=True)
    if not helper_class.is_file():
        raise FileNotFoundError(f"TestKeyHelperMain.class could not be built at {helper_class}")
    return test_classes, main_classes


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
        if os.name != "nt":
            self.skipTest("PowerShell bootstrap receipt execution tests require Windows NT")
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

    def test_clean_environment_finds_java_from_cubism_installations(self):
        """Point 1: Cleared PATH/JAVA_HOME/GRAAL finds Java strictly from cubism-installations.json."""
        current_java = shutil.which("java.exe") or shutil.which("java")
        self.assertIsNotNone(current_java, "java.exe must be installed for behavior test")
        real_jdk = Path(current_java).resolve().parents[1]

        home = self.fixture / "cubism-env-home"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"synthetic-agent")

        fake_cubism = self.fixture / "FakeCubism"
        fake_app = fake_cubism / "app"
        fake_app.mkdir(parents=True)
        fake_jre = fake_app / "jre"
        subprocess.run(["cmd", "/c", "mklink", "/J", str(fake_jre), str(real_jdk)], check=True)
        fake_lib = fake_app / "lib"
        fake_lib.mkdir()
        (fake_cubism / "CubismEditor5.bat").write_text("@rem dummy", encoding="utf-8")
        (fake_lib / "Live2D_Cubism.jar").write_bytes(b"dummy")

        cubism_state_doc = {
            "format": "turboism.cubism.installation-state",
            "schemaVersion": 1,
            "installations": [
                {
                    "root": str(fake_cubism),
                    "version": "5.1.00",
                    "selected": True
                }
            ],
            "managedShortcuts": []
        }
        (home / "cubism-installations.json").write_text(json.dumps(cubism_state_doc), encoding="utf-8")

        # Place bootstrap docs so Find-RunnableJava is invoked
        b_dir = home / "bootstrap"
        b_dir.mkdir()
        (b_dir / "manifest.json").write_text("{}", encoding="utf-8")
        (b_dir / "manifest.json.sig").write_text("sig", encoding="utf-8")

        runner = self.fixture / "run-clean-env.ps1"
        runner.write_text(
            f'[Environment]::SetEnvironmentVariable("JAVA_HOME", $null, "Process")\n'
            f'[Environment]::SetEnvironmentVariable("GRAALVM_HOME", $null, "Process")\n'
            f'[Environment]::SetEnvironmentVariable("TURBOISM_GRAALVM_HOME", $null, "Process")\n'
            f'[Environment]::SetEnvironmentVariable("PATH", "C:\\Windows\\System32", "Process")\n'
            f'. "{self.bootstrap_script}"\n'
            f'$found = Find-RunnableJava -TurboismHome "{home}"\n'
            f'[Console]::Out.WriteLine($found)\n',
            encoding="utf-8"
        )

        res = subprocess.run(["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                              "-File", str(runner)], capture_output=True, text=True, errors="replace", check=True)
        self.assertIn(str(fake_cubism).lower(), res.stdout.lower())

        subprocess.run(["cmd", "/c", "rmdir", str(fake_jre)], check=True)

    def test_private_acl_purges_everyone_and_rejects_junctions(self):
        """Point 2: Private ACL setup purges explicit Everyone, preserves sentinel, and throws on junction."""
        home = self.fixture / "acl-home"
        home.mkdir()
        state_dir = home / "state"
        state_dir.mkdir()
        sentinel = state_dir / "user-sentinel.txt"
        sentinel.write_text("user-preserved-data", encoding="utf-8")

        # Add explicit Everyone Allow to home/state
        everyone_script = self.fixture / "add-everyone.ps1"
        everyone_script.write_text(
            f'$d = New-Object System.IO.DirectoryInfo("{state_dir}")\n'
            f'$acl = $d.GetAccessControl()\n'
            f'$sid = New-Object System.Security.Principal.SecurityIdentifier("S-1-1-0")\n'
            f'$rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")\n'
            f'$acl.AddAccessRule($rule)\n'
            f'$d.SetAccessControl($acl)\n',
            encoding="utf-8"
        )
        subprocess.run(["powershell", "-NoProfile", "-File", str(everyone_script)], check=True)

        # Run Initialize-PrivateStateNamespace via dot-source
        init_script = self.fixture / "run-init-acl.ps1"
        init_script.write_text(
            f'. "{self.bootstrap_script}"\n'
            f'Initialize-PrivateStateNamespace -TurboismHome "{home}"\n',
            encoding="utf-8"
        )
        subprocess.run(["powershell", "-NoProfile", "-File", str(init_script)], check=True)

        # Verify sentinel is intact
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "user-preserved-data")

        # Verify Everyone Allow was purged
        check_script = self.fixture / "check-acl.ps1"
        check_script.write_text(
            f'$d = New-Object System.IO.DirectoryInfo("{state_dir}")\n'
            f'$hasEveryone = $false\n'
            f'foreach ($r in $d.GetAccessControl().GetAccessRules($true, $false, [System.Security.Principal.SecurityIdentifier])) {{\n'
            f'  if ($r.IdentityReference.Value -eq "S-1-1-0") {{ $hasEveryone = $true }}\n'
            f'}}\n'
            f'[Console]::Out.WriteLine($hasEveryone)\n',
            encoding="utf-8"
        )
        res = subprocess.run(["powershell", "-NoProfile", "-File", str(check_script)],
                             capture_output=True, text=True, check=True)
        self.assertEqual(res.stdout.strip().lower(), "false", "Explicit Everyone Allow must be purged")

        # Junction test: state junction to external dir must throw and not modify external dir
        ext_home = self.fixture / "junc-home"
        ext_home.mkdir()
        ext_target = self.fixture / "external-target"
        ext_target.mkdir()
        ext_file = ext_target / "ext.txt"
        ext_file.write_text("untouched", encoding="utf-8")

        junc_state = ext_home / "state"
        subprocess.run(["cmd", "/c", "mklink", "/J", str(junc_state), str(ext_target)], check=True)

        junc_runner = self.fixture / "run-junc.ps1"
        junc_runner.write_text(
            f'. "{self.bootstrap_script}"\n'
            f'Initialize-PrivateStateNamespace -TurboismHome "{ext_home}"\n',
            encoding="utf-8"
        )
        junc_res = subprocess.run(["powershell", "-NoProfile", "-File", str(junc_runner)],
                                  capture_output=True, text=True, errors="replace")
        self.assertNotEqual(junc_res.returncode, 0, "Initialize-PrivateStateNamespace must throw on junction")
        self.assertEqual(ext_file.read_text(encoding="utf-8"), "untouched")
        subprocess.run(["cmd", "/c", "rmdir", str(junc_state)], check=True)

    def test_upgraded_receipt_with_stale_bootstrap_documents_regression(self):
        """Point 4 & 5: existing upgraded receipt skips stale bootstrap docs; reports SKIPPED_EXISTING_RECEIPT."""
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
        res_default = self._run_ps(["-HomePath", str(home), "-PassThru"])
        self.assertEqual(res_default.returncode, 0, res_default.stderr)
        self.assertIn("Existing installation receipt detected; skipping bootstrap receipt initialization", res_default.stdout)
        self.assertIn("SKIPPED_EXISTING_RECEIPT", res_default.stdout)
        self.assertEqual(upgraded_receipt.read_bytes(), expected_bytes, "Upgraded receipt must not be touched")

        # 2. Strict mode: does not silently skip, fails closed with error
        res_strict = self._run_ps(["-HomePath", str(home), "-Strict",
                                   "-JavaPath", "C:\\nonexistent\\java.exe"])
        self.assertNotEqual(res_strict.returncode, 0)
        self.assertEqual(upgraded_receipt.read_bytes(), expected_bytes, "Upgraded receipt must not be replaced")

    def test_real_framework_receipt_initialization_and_tamper_protection(self):
        """Point 6: Integration test calling REAL FrameworkUpdateReceipt/Helper with real Ed25519 signing and ACLs."""
        wt_id = resolve_worktree_id()
        test_classes, main_classes = ensure_runtime_test_classes(wt_id)

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

        # Assemble full test runtime classpath dynamically (Point 3)
        cp_elements = [
            str(test_classes),
            str(main_classes),
            str(ROOT / f"build/worktree/{wt_id}/runtime/resources/main"),
            str(ROOT / f"build/worktree/{wt_id}/sdk/classes/java/main"),
            str(ROOT / f"build/worktree/{wt_id}/core-contract/classes/java/main"),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-core")),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-databind")),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-annotations")),
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

    def test_real_different_receipt_collision_protection(self):
        """Point 4: First initialize manifest v1, then attempt initialization with manifest v2.
        -Strict must fail closed with exit code 74 and NOT replace original index or documents.
        Non-strict must detect existing receipt, report SKIPPED_EXISTING_RECEIPT, and preserve index."""
        wt_id = resolve_worktree_id()
        test_classes, main_classes = ensure_runtime_test_classes(wt_id)

        key_pem = self.fixture / "diff-key.pem"
        framework_update.openssl(["genpkey", "-algorithm", "Ed25519", "-out", str(key_pem)])
        pub_der = framework_update.openssl(["pkey", "-in", str(key_pem), "-pubout", "-outform", "DER"])
        pub_file = self.fixture / "diff-pub.txt"
        pub_file.write_text(base64.b64encode(pub_der).decode("ascii"), encoding="utf-8")
        keyring = self.fixture / "diff-keyring.json"
        keyring.write_text(json.dumps({"local-test": base64.b64encode(pub_der).decode("ascii")}), encoding="utf-8")

        home = self.fixture / "diff-real-home"
        home.mkdir()
        entries = []
        layout_policy = json.loads((ROOT / "packaging/framework-update-layout.json").read_text(encoding="utf-8"))
        for name in layout_policy["frameworkRootFiles"]:
            f = home / name
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(f"payload:{name}\n".encode("utf-8"))
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

        # Sign manifest v1 (version 1.0.0)
        doc_v1 = {
            "schemaVersion": 1, "keyId": "local-test", "version": "1.0.0",
            "channel": "stable", "buildNumber": 1, "sourceRevision": "a" * 40,
            "minimumUpdaterVersion": "0.1.0", "targetPlatform": "windows-x64", "entries": entries
        }
        bytes_v1 = framework_update.canonical_bytes(doc_v1)
        sig_v1 = framework_update.sign(bytes_v1, "local-test", key_pem, keyring, self.fixture)

        stage_v1 = self.fixture / "stage-v1"
        stage_v1.mkdir()
        (stage_v1 / "manifest.json").write_bytes(bytes_v1)
        (stage_v1 / "manifest.json.sig").write_bytes(sig_v1)

        cp = ";".join([
            str(test_classes), str(main_classes),
            str(ROOT / f"build/worktree/{wt_id}/runtime/resources/main"),
            str(ROOT / f"build/worktree/{wt_id}/sdk/classes/java/main"),
            str(ROOT / f"build/worktree/{wt_id}/core-contract/classes/java/main"),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-core")),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-databind")),
            str(find_cached_jar("com.fasterxml.jackson.core", "jackson-annotations")),
        ])

        # Initialize v1
        res_v1 = self._run_ps([
            "-HomePath", str(home), "-StagePath", str(stage_v1), "-Classpath", cp,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file)
        ])
        self.assertEqual(res_v1.returncode, 0, res_v1.stderr)
        installed_receipt = home / "state/runtime/update/installed-receipt.json"
        self.assertTrue(installed_receipt.is_file())
        receipt_bytes_v1 = installed_receipt.read_bytes()

        # Generate genuine signed manifest v2 (version 2.0.0) with matching layout for v2.0.0
        for lib in ("sdk-2.0.0.jar", "graal-host-2.0.0.jar"):
            jar = graal / lib
            jar.write_bytes(f"graal-lib:{lib}\n".encode("utf-8"))

        entries_v2 = []
        for name in layout_policy["frameworkRootFiles"]:
            f = home / name
            digest = hashlib.sha256(f.read_bytes()).hexdigest()
            entries_v2.append({"relativePath": name, "bytes": f.stat().st_size, "sha256": digest, "contentId": "sha256:" + digest})
        for lib in ("sdk-2.0.0.jar", "graal-host-2.0.0.jar"):
            jar = graal / lib
            jar_digest = hashlib.sha256(jar.read_bytes()).hexdigest()
            entries_v2.append({"relativePath": f"graal/lib/{lib}", "bytes": jar.stat().st_size, "sha256": jar_digest, "contentId": "sha256:" + jar_digest})
        entries_v2.sort(key=lambda e: e["relativePath"])

        doc_v2 = {
            "schemaVersion": 1, "keyId": "local-test", "version": "2.0.0",
            "channel": "stable", "buildNumber": 2, "sourceRevision": "b" * 40,
            "minimumUpdaterVersion": "0.1.0", "targetPlatform": "windows-x64", "entries": entries_v2
        }
        bytes_v2 = framework_update.canonical_bytes(doc_v2)
        sig_v2 = framework_update.sign(bytes_v2, "local-test", key_pem, keyring, self.fixture)

        stage_v2 = self.fixture / "stage-v2"
        stage_v2.mkdir()
        (stage_v2 / "manifest.json").write_bytes(bytes_v2)
        (stage_v2 / "manifest.json.sig").write_bytes(sig_v2)

        # 1. Non-strict mode: detects existing receipt, skips re-bootstrap cleanly
        res_auto = self._run_ps([
            "-HomePath", str(home), "-StagePath", str(stage_v2), "-Classpath", cp,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file),
            "-PassThru"
        ])
        self.assertEqual(res_auto.returncode, 0)
        self.assertIn("SKIPPED_EXISTING_RECEIPT", res_auto.stdout)
        self.assertEqual(installed_receipt.read_bytes(), receipt_bytes_v1, "Non-strict mode must not replace existing receipt")

        # 2. Strict mode: invokes helper, helper strictly rejects collision with different receipt (exit 74)
        res_strict = self._run_ps([
            "-HomePath", str(home), "-StagePath", str(stage_v2), "-Classpath", cp,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file),
            "-Strict"
        ])
        self.assertEqual(res_strict.returncode, 74, f"Real helper must reject different receipt with exit code 74 (err: {res_strict.stderr})")
        self.assertIn("An existing different receipt must not be silently replaced", res_strict.stderr)
        self.assertEqual(installed_receipt.read_bytes(), receipt_bytes_v1, "Strict mode must NOT replace existing receipt")

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
