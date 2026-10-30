"""Ordinary CI source gates only; no Windows skip masquerading as acceptance."""
import copy
import hashlib
import json
import os
import tempfile
import unittest
from pathlib import Path, PureWindowsPath
from unittest.mock import patch

from scripts.dev import build_windows_update_files as native


class WindowsUpdateNativeSourceTest(unittest.TestCase):
    def test_pinned_headers_and_exact_abi(self):
        self.assertEqual(native.check_sources()["abi"], 1)

    def test_fixture_entry_and_ci_bundle_layout(self):
        runner = (native.ROOT / "scripts/dev/test-windows-receipt.ps1").read_text()
        gradle = (native.ROOT / "runtime/build.gradle.kts").read_text()
        self.assertIn("dev.turboism.update.WindowsReceiptNativeFixture", runner)
        self.assertIn("windowsReceiptValidationBundle", runner)
        self.assertIn('archiveFileName.set("windows-receipt-fixture.jar")', gradle)
        self.assertIn('"windows_fixture_probe.c"', (native.ROOT / "scripts/dev/build_windows_update_files.py").read_text())

    def test_drift_is_rejected(self):
        with patch.object(native, "digest", return_value="0" * 64):
            with self.assertRaisesRegex(ValueError, "drift"):
                native.check_sources()

    def test_imports_architecture_timestamp_and_exports_are_required(self):
        policy = native.check_sources()
        pe = "pei-x86-64\nTime/Date stamp\t\t0\n"
        pe += "\n".join("DLL Name: " + name for name in policy["imports"])
        pe += "\n" + "\n".join("Java_dev_turboism_update_WindowsInstallationFiles_" + n for n in native.NATIVES)
        with patch.object(native, "run", return_value=pe):
            native.verify_pe(Path("unused"), "objdump", policy)
        for bad in [pe.replace("pei-x86-64", "pei-i386"), pe.replace("stamp\t\t0", "stamp\t\t1"),
                    pe + "\nDLL Name: evil.dll", pe.replace("_finish", "_unknown")]:
            with self.subTest(pe=bad), patch.object(native, "run", return_value=bad):
                with self.assertRaises(ValueError):
                    native.verify_pe(Path("unused"), "objdump", copy.deepcopy(policy))

    def test_output_outside_build_is_rejected(self):
        with tempfile.TemporaryDirectory() as temp, patch.object(native, "run", side_effect=["x86_64-w64-mingw32", "javac 17"]):
            with self.assertRaisesRegex(ValueError, "build/"):
                native.build("gcc", Path(temp), Path(temp), "objdump")

    def test_source_report_paths_roundtrip_on_windows_and_posix(self):
        root = PureWindowsPath("C:/checkout")
        file = root / "native/update-files/windows_files.c"
        with patch.object(native, "digest", return_value="f" * 64):
            records = json.loads(json.dumps(native.source_records([file], root)))
        self.assertEqual(records, {"native/update-files/windows_files.c": "f" * 64})
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            file = root / "native/update-files/windows_files.c"
            file.parent.mkdir(parents=True)
            file.write_bytes(b"actual-posix-file")
            records = json.loads(json.dumps(native.source_records([file], root)))
            self.assertEqual(records, {"native/update-files/windows_files.c": hashlib.sha256(b"actual-posix-file").hexdigest()})

    def bundle_fixture(self, directory):
        for name in native.BUNDLE_REQUIRED | {"jackson-annotations-2.18.9.jar", "jackson-core-2.18.9.jar", "jackson-databind-2.18.9.jar"}:
            (directory / name).write_bytes(("fixture:" + name).encode())
        return native.seal_bundle(directory)["bundleManifestSha256"]

    def test_complete_bundle_seal_determinism_and_inventory(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            expected = self.bundle_fixture(directory)
            payload = native.verify_bundle(directory, expected)
            self.assertEqual(len(payload["files"]), 7)
            self.assertEqual(native.seal_bundle(directory)["bundleManifestSha256"], expected)
            self.assertEqual({r["path"] for r in payload["files"]}, set(p.name for p in directory.iterdir()) - {native.BUNDLE_MANIFEST})
            self.assertTrue(all("/" not in r["path"] and "\\" not in r["path"] for r in payload["files"]))

    def test_any_bundle_payload_or_manifest_tamper_rejected(self):
        names = list(native.BUNDLE_REQUIRED) + ["jackson-core-2.18.9.jar", native.BUNDLE_MANIFEST]
        for name in names:
            for same_length in (False, True):
                with self.subTest(name=name, same_length=same_length), tempfile.TemporaryDirectory() as temp:
                    directory = Path(temp)
                    expected = self.bundle_fixture(directory)
                    file = directory / name
                    data = file.read_bytes()
                    file.write_bytes(b"!" + data[1:] if same_length else data + b"extra")
                    with self.assertRaises(ValueError):
                        native.verify_bundle(directory, expected)

    def test_probe_self_report_cannot_authorize_replacement(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            expected = self.bundle_fixture(directory)
            probe = directory / "windows-fixture-probe.exe"
            probe.write_bytes(b"replacement")
            (directory / "native-build.json").write_text(json.dumps({"probeSha256": native.digest(probe)}))
            with self.assertRaises(ValueError):
                native.verify_bundle(directory, expected)
            new = native.seal_bundle(directory)
            self.assertNotEqual(new["bundleManifestSha256"], expected)
            with self.assertRaises(ValueError):
                native.verify_bundle(directory, expected)

    def test_extra_missing_directory_link_and_classpath_files_rejected(self):
        for mode in ("extra.exe", "extra.jar", "missing", "directory", "symlink", "hardlink"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                expected = self.bundle_fixture(directory)
                if mode == "missing":
                    (directory / "windows-receipt-fixture.jar").unlink()
                elif mode == "directory":
                    (directory / "nested").mkdir()
                elif mode in ("symlink", "hardlink"):
                    source = directory / "windows-receipt-fixture.jar"
                    extra = directory / "extra.jar"
                    if mode == "symlink": extra.symlink_to(source)
                    else: os.link(source, extra)
                else:
                    (directory / mode).write_bytes(b"unexpected executable/classpath")
                with self.assertRaises(ValueError):
                    native.verify_bundle(directory, expected)
                with self.assertRaises(ValueError):
                    native.seal_bundle(directory)

    def test_even_authenticated_manifest_rejects_escape_alias_and_invalid_bounds(self):
        for mode in ("../escape.jar", "C:/escape.jar", "x\\evil.jar", "x:ads.jar", "duplicate", "case", "size-float", "size-overflow", "unknown-field"):
            with self.subTest(mode=mode), tempfile.TemporaryDirectory() as temp:
                directory = Path(temp)
                self.bundle_fixture(directory)
                manifest = directory / native.BUNDLE_MANIFEST
                payload = json.loads(manifest.read_bytes())
                record = payload["files"][0]
                if mode == "duplicate": payload["files"][1] = record.copy()
                elif mode == "case": record["path"] = record["path"].upper()
                elif mode == "size-float": record["bytes"] = float(record["bytes"])
                elif mode == "size-overflow": record["bytes"] = 1 << 80
                elif mode == "unknown-field": record["extra"] = "ignored?"
                else: record["path"] = mode
                manifest.write_text(json.dumps(payload))
                with self.assertRaises(ValueError):
                    native.verify_bundle(directory, native.digest(manifest))

    def test_runner_preflight_authentication_and_run_report_order(self):
        runner = (native.ROOT / "scripts/dev/test-windows-receipt.ps1").read_text()
        self.assertLess(runner.index("if ($PrepareBundle -and"), runner.index("& $javac"))
        self.assertLess(runner.index("$verified = Verify-Bundle"), runner.index("Invoke-BoundedFixture $java"))
        self.assertIn("if ($BuildNative -ne $PrepareBundle)", runner)
        self.assertIn("[Guid]::NewGuid().ToString('N')", runner)
        self.assertNotIn("!$ancestor.PSIsContainer", runner)
        self.assertIn("if ($quoted.Count -gt 0)", runner)
        self.assertNotIn("(Join-Path $Bundle '*')", runner)
        self.assertNotIn("Get-Content -Raw (Join-Path $Bundle 'native-build.json')", runner)
