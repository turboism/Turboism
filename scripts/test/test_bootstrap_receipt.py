"""Regression and integration tests for Windows bootstrap receipt initialization (Issue #50).

Verifies the integration of FrameworkUpdateHelper --initialize-receipt into the
first full installation and standard ZIP lifecycle, including cross-layer
consistency, real helper execution with Ed25519 signing and ACL verification,
upgraded receipt collision safety, and configurator propagation.
"""
from __future__ import annotations

import base64
import functools
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/release"))
from turboism_release import framework_update

# Every execution fixture lives in one uniquely named directory created below.
TEMP_FIXTURE_PREFIX = "turboism-bootstrap-test-"

# Generated PowerShell fixtures are written as UTF-8 **with BOM** because Windows PowerShell 5.1
# parses BOM-less scripts with the ANSI codepage and would corrupt non-ASCII (for example Chinese)
# fixture paths. The fixtures never change the console code page: [Console]::OutputEncoding is
# process-global for the whole attached console, so setting it here would leak into the shared
# console of the user and of other tests. Values that may be non-ASCII are written to explicit
# UTF-8 files instead of stdout.


def oem_codepage_encoding() -> str:
    """Console (OEM) codepage Windows PowerShell uses for host output the prologue never sees."""
    if os.name != "nt":
        return "utf-8"
    import ctypes

    return f"cp{int(ctypes.windll.kernel32.GetOEMCP())}"


def decode_console_output(raw: bytes) -> str:
    """Decode captured console bytes without ever replacing characters.

    Test fixtures emit UTF-8 once their prologue has run. Parse/host errors reported by Windows
    PowerShell itself can still arrive in the console OEM codepage, so that codepage is decoded
    strictly as well. Using errors="replace" here would hide the real assertion context.
    """
    try:
        return raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode(oem_codepage_encoding())


def run_console_process(cmd: list[str], *, timeout: int = 120, cwd: Path | None = None) -> subprocess.CompletedProcess:
    """Run a console command and decode both streams strictly (never with errors="replace")."""
    result = subprocess.run(cmd, cwd=cwd, capture_output=True, timeout=timeout)
    return subprocess.CompletedProcess(
        result.args,
        result.returncode,
        decode_console_output(result.stdout),
        decode_console_output(result.stderr),
    )


def write_powershell_fixture(path: Path, body: str) -> None:
    """Write a generated PowerShell script as UTF-8 with BOM (no console code page changes)."""
    path.write_text(body, encoding="utf-8-sig")


def remove_tree_without_following_junctions(root: Path) -> None:
    """Delete this test's own fixture root, unlinking fixture junctions before the tree delete.

    The root must be the absolute directory this test created with tempfile.mkdtemp; anything else
    raises instead of deleting. Directory junctions are unlinked first so the intent stays explicit
    and does not depend on the runtime's shutil.rmtree junction handling; only the junction link
    itself is removed (os.rmdir on a junction never enumerates or deletes its target). A failed
    lstat/rmdir raises rather than silently continuing into a recursive delete.
    """
    root = root.resolve()
    expected_parent = Path(tempfile.gettempdir()).resolve()
    if not root.is_absolute() or root.parent != expected_parent or not root.name.startswith(
        TEMP_FIXTURE_PREFIX
    ):
        raise AssertionError(f"refusing to clean a path that is not this test's fixture root: {root}")
    if not root.exists():
        return
    for current, dirnames, _filenames in os.walk(root, topdown=True):
        for name in list(dirnames):
            path = Path(current) / name
            is_reparse_point = path.lstat().st_file_attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT
            if is_reparse_point:
                os.rmdir(path)
                dirnames.remove(name)
    shutil.rmtree(root)


def resolve_verified_jdk_home() -> Path:
    """Resolve a JDK home that really contains bin/java(.exe).

    C:\\Program Files\\Common Files\\Oracle\\Java\\javapath\\java.exe is a launcher shim whose
    parents[1] is not a JDK home, so the shim path is never treated as one. Preference order:
    a verified JAVA_HOME, the java.home the resolved java reports itself, then the executable's own
    bin parent (which is correct for a real JDK java.exe).
    """
    java_binary = "java.exe" if os.name == "nt" else "java"
    candidates: list[Path] = []
    java_home = os.environ.get("JAVA_HOME")
    if java_home:
        candidates.append(Path(java_home))
    java_executable = shutil.which("java.exe") or shutil.which("java")
    if java_executable:
        try:
            probe = run_console_process([java_executable, "-XshowSettings:properties", "-version"], timeout=120)
            match = re.search(r"^\s*java\.home\s*=\s*(.+?)\s*$", probe.stdout + probe.stderr, re.MULTILINE)
            if match:
                candidates.append(Path(match.group(1)))
        except (OSError, subprocess.SubprocessError):
            pass
        candidates.append(Path(java_executable).resolve().parent.parent)
    tried: list[str] = []
    for candidate in candidates:
        tried.append(str(candidate))
        if (candidate / "bin" / java_binary).is_file():
            return candidate
    raise AssertionError("No verified JDK home with bin/java found; tried: " + ", ".join(tried))


@functools.lru_cache(maxsize=1)
def resolve_bootstrap_receipt_test_classpath() -> str:
    """Returns the exact test runtime classpath.

    When the gating Gradle task has already exported the classpath, it passes the export file via
    TURBOISM_BOOTSTRAP_RECEIPT_TEST_CLASSPATH_FILE so this test never starts a nested, competing
    Gradle build inside the gate. Independent runs fall back to invoking the export task below.
    """
    exported_file = os.environ.get("TURBOISM_BOOTSTRAP_RECEIPT_TEST_CLASSPATH_FILE")
    if exported_file:
        path = Path(exported_file)
        if not path.is_absolute() or not path.is_file():
            raise RuntimeError(f"Bootstrap receipt classpath export is missing: {path}")
        classpath = path.read_text(encoding="utf-8").strip()
        if not classpath:
            raise RuntimeError(f"Bootstrap receipt classpath export is empty: {path}")
        return classpath

    gradlew = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    cmd = [gradlew, ":runtime:writeBootstrapReceiptTestClasspath", "--console=plain"]
    result = run_console_process(cmd, cwd=ROOT, timeout=900)
    if result.returncode:
        raise RuntimeError(f"Gradle classpath export failed:\n{result.stdout}\n{result.stderr}")
    prefix = "BOOTSTRAP_RECEIPT_TEST_CLASSPATH: "
    exported = [line[len(prefix):] for line in result.stdout.splitlines() if line.startswith(prefix)]
    if len(exported) != 1:
        raise RuntimeError(f"Expected one current Gradle classpath export:\n{result.stdout}")
    path = Path(exported[0])
    if not path.is_absolute() or not path.is_file():
        raise RuntimeError(f"Gradle classpath export is missing: {path}")
    classpath = path.read_text(encoding="utf-8").strip()
    if not classpath:
        raise RuntimeError(f"Gradle classpath export is empty: {path}")
    return classpath


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

    def test_zip_builder_embeds_bootstrap_receipt_when_provided(self):
        """Point 1 & 4 (Cross-platform): ZIP builder includes bootstrap/manifest.json and .sig when directory is passed."""
        assemble_text = (ROOT / "packaging/windows-installer/assemble-release.sh").read_text(encoding="utf-8")
        block = assemble_text.split("zip_dir() {", 1)[1].split("<<'PYEOF'\n", 1)[1].split("\nPYEOF", 1)[0]

        with tempfile.TemporaryDirectory(prefix="turboism-zip-test-") as temp:
            fixture = Path(temp)
            stage = fixture / "zip-stage"
            stage.mkdir()
            (stage / "turboism-agent.jar").write_bytes(b"agent")
            (stage / "plugins").mkdir()
            (stage / "plugins/acp.jar").write_bytes(b"plugin")
            (stage / "config.template.json").write_text("{}", encoding="utf-8")

            bdir = fixture / "zip-bootstrap"
            bdir.mkdir()
            (bdir / "manifest.json").write_text('{"version":"1.0.0"}', encoding="utf-8")
            (bdir / "manifest.json.sig").write_text("test-sig", encoding="utf-8")

            out_zip = fixture / "test-package.zip"
            res = subprocess.run([sys.executable, "-", str(stage), str(out_zip), "1.0.0", str(bdir)],
                                 input=block, text=True, capture_output=True, check=True)
            self.assertEqual(res.returncode, 0)

            with zipfile.ZipFile(out_zip) as z:
                names = set(z.namelist())
                self.assertIn("bootstrap/manifest.json", names, "bootstrap/manifest.json must be in standard ZIP")
                self.assertIn("bootstrap/manifest.json.sig", names, "bootstrap/manifest.json.sig must be in standard ZIP")
                self.assertEqual(z.read("bootstrap/manifest.json"), b'{"version":"1.0.0"}')
                self.assertEqual(z.read("bootstrap/manifest.json.sig"), b"test-sig")


class BootstrapReceiptExecutionTest(unittest.TestCase):
    """Behavioral and integration tests executing bootstrap-receipt.ps1 in isolated Windows environments."""

    def setUp(self):
        if os.name != "nt":
            self.skipTest("PowerShell bootstrap receipt execution tests require Windows NT")
        self.temp_dir = tempfile.mkdtemp(prefix=TEMP_FIXTURE_PREFIX)
        self.fixture = Path(self.temp_dir)
        self.bootstrap_script = ROOT / "packaging/windows-installer/bootstrap-receipt.ps1"

    def tearDown(self):
        # Junction fixtures point at real host JDKs; never let a recursive delete follow them.
        remove_tree_without_following_junctions(Path(self.temp_dir))

    def _run_ps(self, args: list[str]) -> subprocess.CompletedProcess:
        cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
               "-File", str(self.bootstrap_script)] + args
        return run_console_process(cmd, timeout=120)

    def _run_ps_fixture(self, name: str, body: str, *, timeout: int = 120) -> subprocess.CompletedProcess:
        path = self.fixture / name
        write_powershell_fixture(path, body)
        cmd = ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", str(path)]
        return run_console_process(cmd, timeout=timeout)

    def _create_junction(self, link: Path, target: Path) -> None:
        result = run_console_process(["cmd", "/c", "mklink", "/J", str(link), str(target)])
        self.assertEqual(result.returncode, 0,
                         f"mklink /J failed: stdout={result.stdout!r} stderr={result.stderr!r}")

    def _remove_junction(self, link: Path) -> None:
        # os.rmdir removes the reparse point itself and never touches the junction target.
        if link.exists() or link.is_symlink():
            try:
                os.rmdir(link)
            except OSError as exc:
                raise AssertionError(f"could not remove junction fixture {link}: {exc}") from exc

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
        # A PATH java.exe is often a launcher shim (Oracle javapath), whose parents[1] is not a JDK.
        # Resolve a verified JDK home whose bin/java really exists before building the fixture.
        jdk_home = resolve_verified_jdk_home()
        self.assertTrue((jdk_home / "bin" / "java.exe").is_file(),
                        f"fixture JDK home must contain bin/java.exe: {jdk_home}")

        home = self.fixture / "cubism-env-home"
        home.mkdir()
        (home / "turboism-agent.jar").write_bytes(b"synthetic-agent")

        fake_cubism = self.fixture / "FakeCubism"
        fake_app = fake_cubism / "app"
        fake_app.mkdir(parents=True)
        fake_jre = fake_app / "jre"
        self._create_junction(fake_jre, jdk_home)
        try:
            self.assertTrue((fake_jre / "bin" / "java.exe").is_file(),
                            f"fixture JRE junction must expose a real java.exe (jdk home: {jdk_home})")
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

            found_file = self.fixture / "found-java.txt"
            res = self._run_ps_fixture(
                "run-clean-env.ps1",
                '[Environment]::SetEnvironmentVariable("JAVA_HOME", $null, "Process")\n'
                '[Environment]::SetEnvironmentVariable("GRAALVM_HOME", $null, "Process")\n'
                '[Environment]::SetEnvironmentVariable("TURBOISM_GRAALVM_HOME", $null, "Process")\n'
                '[Environment]::SetEnvironmentVariable("PATH", "C:\\Windows\\System32", "Process")\n'
                f'. "{self.bootstrap_script}"\n'
                f'$found = Find-RunnableJava -TurboismHome "{home}"\n'
                # The resolved path may contain non-ASCII characters; carry it through an explicit
                # UTF-8 file instead of the console (whose code page is shared process-globally).
                f'[System.IO.File]::WriteAllText("{found_file}", [string]$found, (New-Object System.Text.UTF8Encoding($false)))\n',
            )
            self.assertEqual(res.returncode, 0,
                             f"Find-RunnableJava runner failed.\nstdout:\n{res.stdout}\nstderr:\n{res.stderr}")
            found = found_file.read_text(encoding="utf-8") if found_file.is_file() else ""
            self.assertIn(str(fake_cubism).lower(), found.lower(),
                          f"Find-RunnableJava must select the state-selected Cubism JRE "
                          f"(fixture JDK home: {jdk_home}, resolved: {found!r}).\n"
                          f"stdout:\n{res.stdout}\nstderr:\n{res.stderr}")
        finally:
            # Remove the junction before the fixture tree is deleted so no recursive delete can
            # ever follow it into the real JDK installation.
            self._remove_junction(fake_jre)

    def test_owned_modify_only_directory_does_not_require_write_owner(self):
        """DACL preparation must not try to re-set ownership on ordinary owned data-drive directories."""
        home = self.fixture / "modify-only-home"
        state = home / "state"
        state.mkdir(parents=True)
        sentinel = state / "user-sentinel.txt"
        sentinel.write_bytes(b"preserved")
        result = self._run_ps_fixture(
            "modify-only.ps1",
            '$ErrorActionPreference="Stop"\n'
            f'. "{self.bootstrap_script}"\n'
            f'$dir = New-Object IO.DirectoryInfo("{state}")\n'
            '$acl = $dir.GetAccessControl()\n'
            '$ownerBefore = $acl.GetOwner([Security.Principal.SecurityIdentifier])\n'
            '$acl.SetAccessRuleProtection($true,$false)\n'
            'foreach($r in @($acl.GetAccessRules($true,$false,[Security.Principal.SecurityIdentifier]))) { [void]$acl.RemoveAccessRuleSpecific($r) }\n'
            '$user = [Security.Principal.WindowsIdentity]::GetCurrent().User\n'
            '$allow = New-Object Security.AccessControl.FileSystemAccessRule($user,"Modify","ContainerInherit,ObjectInherit","None","Allow")\n'
            '$deny = New-Object Security.AccessControl.FileSystemAccessRule($user,"TakeOwnership","None","None","Deny")\n'
            '$acl.AddAccessRule($allow); $acl.AddAccessRule($deny); $dir.SetAccessControl($acl)\n'
            f'Initialize-PrivateStateNamespace -TurboismHome "{home}"\n'
            '$ownerAfter = $dir.GetAccessControl().GetOwner([Security.Principal.SecurityIdentifier])\n'
            'if (!$ownerBefore.Equals($ownerAfter)) { throw "Owner changed" }\n'
            'Write-Output "DACL_ONLY_PASS"\n',
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("DACL_ONLY_PASS", result.stdout)
        self.assertEqual(sentinel.read_bytes(), b"preserved")

    def test_private_acl_purges_everyone_and_rejects_junctions(self):
        """Point 2: Private ACL setup purges explicit Everyone, preserves sentinel, and verifies external SDDL unchanged."""
        home = self.fixture / "acl-home"
        home.mkdir()
        state_dir = home / "state"
        state_dir.mkdir()
        sentinel = state_dir / "user-sentinel.txt"
        sentinel.write_text("user-preserved-data", encoding="utf-8")

        # Add explicit Everyone Allow to home/state
        everyone = self._run_ps_fixture(
            "add-everyone.ps1",
            f'$d = New-Object System.IO.DirectoryInfo("{state_dir}")\n'
            '$acl = $d.GetAccessControl()\n'
            '$sid = New-Object System.Security.Principal.SecurityIdentifier("S-1-1-0")\n'
            '$rule = New-Object System.Security.AccessControl.FileSystemAccessRule($sid, "FullControl", "ContainerInherit,ObjectInherit", "None", "Allow")\n'
            '$acl.AddAccessRule($rule)\n'
            '$d.SetAccessControl($acl)\n',
        )
        self.assertEqual(everyone.returncode, 0,
                         f"stdout:\n{everyone.stdout}\nstderr:\n{everyone.stderr}")

        # Run Initialize-PrivateStateNamespace via dot-source
        init_res = self._run_ps_fixture(
            "run-init-acl.ps1",
            f'. "{self.bootstrap_script}"\n'
            f'Initialize-PrivateStateNamespace -TurboismHome "{home}"\n',
        )
        self.assertEqual(init_res.returncode, 0,
                         f"stdout:\n{init_res.stdout}\nstderr:\n{init_res.stderr}")

        # Verify sentinel is intact
        self.assertEqual(sentinel.read_text(encoding="utf-8"), "user-preserved-data")

        # Verify Everyone Allow was purged
        res = self._run_ps_fixture(
            "check-acl.ps1",
            f'$d = New-Object System.IO.DirectoryInfo("{state_dir}")\n'
            '$hasEveryone = $false\n'
            'foreach ($r in $d.GetAccessControl().GetAccessRules($true, $false, [System.Security.Principal.SecurityIdentifier])) {\n'
            '  if ($r.IdentityReference.Value -eq "S-1-1-0") { $hasEveryone = $true }\n'
            '}\n'
            '[Console]::Out.WriteLine($hasEveryone)\n',
        )
        self.assertEqual(res.returncode, 0, f"stdout:\n{res.stdout}\nstderr:\n{res.stderr}")
        self.assertEqual(res.stdout.strip().lower(), "false", "Explicit Everyone Allow must be purged")

        # Junction test: state junction to external dir must throw and not modify external SDDL or content
        ext_home = self.fixture / "junc-home"
        ext_home.mkdir()
        ext_target = self.fixture / "external-target"
        ext_target.mkdir()
        ext_file = ext_target / "ext.txt"
        ext_file.write_text("untouched", encoding="utf-8")

        # Capture SDDL of externalTarget before junction attempt
        get_sddl_body = (
            f'$sddl = (Get-Acl -LiteralPath "{ext_target}").Sddl\n'
            '[Console]::Out.WriteLine($sddl)\n'
        )
        sddl_before = self._run_ps_fixture("get-sddl.ps1", get_sddl_body)
        self.assertEqual(sddl_before.returncode, 0, sddl_before.stderr)
        sddl_before_value = sddl_before.stdout.strip()

        junc_state = ext_home / "state"
        self._create_junction(junc_state, ext_target)
        try:
            junc_res = self._run_ps_fixture(
                "run-junc.ps1",
                f'. "{self.bootstrap_script}"\n'
                f'Initialize-PrivateStateNamespace -TurboismHome "{ext_home}"\n',
            )
            self.assertNotEqual(junc_res.returncode, 0, "Initialize-PrivateStateNamespace must throw on junction")
            self.assertEqual(ext_file.read_text(encoding="utf-8"), "untouched")

            sddl_after = self._run_ps_fixture("get-sddl.ps1", get_sddl_body)
            self.assertEqual(sddl_after.returncode, 0, sddl_after.stderr)
            self.assertEqual(sddl_after.stdout.strip(), sddl_before_value,
                             "External target SDDL must be completely unmodified through junction")
        finally:
            self._remove_junction(junc_state)

    def test_managed_zip_launch_bootstrap_wiring_and_skip_guards(self):
        """Execute the REAL launcher bootstrap orchestration, not a copied block of its source.

        The launcher calls Invoke-TurboismBootstrapReceiptInitialization in
        cubism-launch-common.ps1; this test dot-sources that real function so the skip guards are
        exercised as production code. ProbeOnly gating lives in the launcher itself and is proven
        behaviorally by packaging/windows-installer/test-launch-lease.ps1, which runs the real
        launcher against an isolated home and asserts the bootstrap script is only invoked for
        non-Probe launches.
        """
        launcher = (ROOT / "packaging/windows-installer/launch-cubism-turboism.ps1").read_text(encoding="utf-8")
        common = ROOT / "packaging/windows-installer/cubism-launch-common.ps1"
        self.assertIn("Invoke-TurboismBootstrapReceiptInitialization", launcher,
                      "the launcher must call the shared bootstrap orchestration function")
        self.assertLess(
            launcher.index("if (!$ProbeOnly) {"),
            launcher.index("Invoke-TurboismBootstrapReceiptInitialization"),
            "bootstrap receipt initialization must stay inside the non-Probe launch branch",
        )
        self.assertIn("function Invoke-TurboismBootstrapReceiptInitialization",
                      common.read_text(encoding="utf-8"))

        home = self.fixture / "launcher-home"
        home.mkdir()
        docs = home / "bootstrap"
        docs.mkdir()
        (docs / "manifest.json").write_text("{}", encoding="utf-8")
        (docs / "manifest.json.sig").write_text("fixture", encoding="utf-8")
        marker = home / "invoked.txt"
        (home / "bootstrap-receipt.ps1").write_text(
            'param($HomePath, $StagePath, $JavaPath, [switch]$PassThru)\n'
            'Set-Content -LiteralPath (Join-Path $HomePath "invoked.txt") -Value $JavaPath\n'
            '[pscustomobject]@{Success=$false; ExitCode=70}\n', encoding="utf-8")
        journal = home / "state/runtime/update/helper/journal.json"
        receipt = home / "state/runtime/update/installed-receipt.json"

        def run():
            result = self._run_ps_fixture(
                "launcher-bootstrap-wiring.ps1",
                f'. "{common}"\n'
                f"$invoked = Invoke-TurboismBootstrapReceiptInitialization -TurboismHome '{home}' "
                f"-UpdateJournal '{journal}' -UpdateJava 'selected-java' 3>$null\n"
                'Write-Output ("INVOKED=" + $invoked)\n',
            )
            self.assertEqual(result.returncode, 0,
                             f"real bootstrap orchestration failed.\nstdout:\n{result.stdout}\nstderr:\n{result.stderr}")
            self.assertIn("INVOKED=", result.stdout, result.stdout + result.stderr)
            return "INVOKED=True" in result.stdout

        self.assertTrue(run(), "the first standard-ZIP launch must invoke the package bootstrap documents")
        self.assertEqual(marker.read_text().strip(), "selected-java")

        marker.unlink()
        receipt.parent.mkdir(parents=True)
        receipt.write_bytes(b"existing receipt must remain untouched")
        self.assertFalse(run(), "an existing installation receipt must skip re-bootstrapping")
        self.assertFalse(marker.exists(), "Existing receipts must not be reinitialized")
        self.assertEqual(receipt.read_bytes(), b"existing receipt must remain untouched")
        receipt.unlink()
        journal.parent.mkdir(parents=True)
        journal.write_bytes(b"recovery takes precedence")
        self.assertFalse(run(), "a pending recovery journal must take precedence over bootstrapping")
        self.assertFalse(marker.exists(), "Recovery must not bootstrap a missing receipt")
        journal.unlink()
        (docs / "manifest.json.sig").unlink()
        self.assertFalse(run(), "incomplete package documents must not trigger discovery elsewhere")
        self.assertFalse(marker.exists(), "Missing package documents must not trigger discovery elsewhere")

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
        """Point 1 & 6: Integration test calling REAL FrameworkUpdateReceipt/Helper with real Ed25519 signing and ACLs."""
        classpath = resolve_bootstrap_receipt_test_classpath()

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
        """Point 3 & 4: Initialize v1, then attempt v2.
        -Strict must fail closed with exit 74 and NOT replace original index or published documents.
        Non-strict must detect existing receipt, report SKIPPED_EXISTING_RECEIPT, and preserve index and documents."""
        classpath = resolve_bootstrap_receipt_test_classpath()

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

        # Initialize v1
        res_v1 = self._run_ps([
            "-HomePath", str(home), "-StagePath", str(stage_v1), "-Classpath", classpath,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file)
        ])
        self.assertEqual(res_v1.returncode, 0, res_v1.stderr)
        installed_receipt = home / "state/runtime/update/installed-receipt.json"
        self.assertTrue(installed_receipt.is_file())
        receipt_bytes_v1 = installed_receipt.read_bytes()

        # Point 3: Snapshot published manifest and signature bytes for v1
        digest_v1 = hashlib.sha256(bytes_v1).hexdigest()
        published_v1_manifest = home / f"state/runtime/update/receipts/{digest_v1}/manifest.json"
        published_v1_sig = home / f"state/runtime/update/receipts/{digest_v1}/manifest.json.sig"
        self.assertTrue(published_v1_manifest.is_file())
        self.assertTrue(published_v1_sig.is_file())
        pub_manifest_v1_bytes = published_v1_manifest.read_bytes()
        pub_sig_v1_bytes = published_v1_sig.read_bytes()

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
            "-HomePath", str(home), "-StagePath", str(stage_v2), "-Classpath", classpath,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file),
            "-PassThru"
        ])
        self.assertEqual(res_auto.returncode, 0)
        self.assertIn("SKIPPED_EXISTING_RECEIPT", res_auto.stdout)
        self.assertEqual(installed_receipt.read_bytes(), receipt_bytes_v1, "Non-strict mode must not replace existing receipt index")
        self.assertEqual(published_v1_manifest.read_bytes(), pub_manifest_v1_bytes, "Published v1 manifest must remain untouched")
        self.assertEqual(published_v1_sig.read_bytes(), pub_sig_v1_bytes, "Published v1 sig must remain untouched")

        # 2. Strict mode: invokes helper, helper strictly rejects collision with different receipt (exit 74)
        res_strict = self._run_ps([
            "-HomePath", str(home), "-StagePath", str(stage_v2), "-Classpath", classpath,
            "-HelperClass", "dev.turboism.update.TestKeyHelperMain", "-HelperPrefixArgs", str(pub_file),
            "-Strict"
        ])
        self.assertEqual(res_strict.returncode, 74, f"Real helper must reject different receipt with exit code 74 (err: {res_strict.stderr})")
        self.assertIn("An existing different receipt must not be silently replaced", res_strict.stderr)
        # Point 3: Verify index AND published documents are 100% byte-identical
        self.assertEqual(installed_receipt.read_bytes(), receipt_bytes_v1, "Strict mode must NOT replace existing receipt index")
        self.assertEqual(published_v1_manifest.read_bytes(), pub_manifest_v1_bytes, "Published v1 manifest must not be replaced on strict failure")
        self.assertEqual(published_v1_sig.read_bytes(), pub_sig_v1_bytes, "Published v1 sig must not be replaced on strict failure")


if __name__ == "__main__":
    unittest.main()
