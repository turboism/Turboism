"""Offline safety checks; no host launch or installed files are touched."""
import importlib.util
from pathlib import Path
import tempfile
import unittest
import json
import hashlib
import zipfile
from summarize_native import case

spec = importlib.util.spec_from_file_location("texture_native_runner", Path(__file__).with_name("run_native.py"))
runner = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runner)


class NativePreparationTest(unittest.TestCase):
    def test_only_official_root_literals_are_rebased(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source, output = root / "source.bat", root / "staged.bat"
            original = b'@echo off\r\ncd /d "%~dp0"\r\nset PATH=%PATH%;%~dp0app\\dll64\r\ncom.live2d.cubism.CECubismEditorApp "%~f1"\r\n'
            source.write_bytes(original)
            runner.stage_launcher(source, Path("C:/OfficialHost"), output)
            self.assertEqual(original, source.read_bytes())
            expected = original.replace(b"%~dp0", b"C:\\OfficialHost\\" if runner.os.name == "nt" else b"C:/OfficialHost\\")
            self.assertEqual(expected, output.read_bytes())

    def test_managed_launcher_and_unrecognized_bat_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for content in (b"TURBOISM MANAGED BEGIN %~dp0", b"echo arbitrary", b"launch-cubism-turboism %~dp0"):
                source, output = root / "source.bat", root / "staged.bat"
                source.write_bytes(content)
                with self.assertRaises(ValueError):
                    runner.stage_launcher(source, root, output)
                self.assertFalse(output.exists())

    def test_jvm_options_with_spaces_are_quoted_and_injection_is_rejected(self):
        self.assertEqual('"-Duser.home=C:\\a b" "-javaagent:C:\\c d.jar"',
                         runner.java_options(["-Duser.home=C:\\a b", "-javaagent:C:\\c d.jar"]))
        for bad in ('-Dx=a" -javaagent:bad', '-Dx=a\ncommand', '-Dx=a\rcommand'):
            with self.assertRaises(ValueError):
                runner.java_options([bad])

    @unittest.skipUnless(runner.os.name == "nt", "cmd.exe integration requires Windows")
    def test_cmd_executes_spaced_bat_and_fixture_argument(self):
        with tempfile.TemporaryDirectory(prefix="texture cmd ") as temporary:
            root = Path(temporary)
            bat = root / "official copy.bat"
            bat.write_bytes(b'@echo off\r\nif not "%~f1"=="%~dp0fixture copy.cmo3" exit /b 7\r\necho TEXTURE_CMD_PASS\r\n')
            command = runner.official_command(runner.os.environ["COMSPEC"], str(bat), str(root / "fixture copy.cmo3"))
            completed = runner.subprocess.run(command, capture_output=True, check=True)
            self.assertIn(b"TEXTURE_CMD_PASS", completed.stdout)

    def test_factory_owner_comes_from_frozen_agent_pins_and_rejects_changed_host(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            agent, host = root / "agent.jar", root / "host.jar"
            pins = {}
            versions = (("5000", "5.0.00", "w"), ("5203", "5.2.03", "y"), ("5304", "5.3.04", "A"))
            with zipfile.ZipFile(host, "w") as out:
                for _, dotted, suffix in versions:
                    owner = "com/live2d/graphics3d/shader/" + suffix
                    data = ("fixture-" + suffix).encode()
                    out.writestr(owner + ".class", data)
                    pins[dotted] = {owner: hashlib.sha256(data).hexdigest()}
            with zipfile.ZipFile(agent, "w") as out:
                out.writestr("dev/turboism/adapter/cubism/class-pins/texture-upload-preparation.json", json.dumps(pins))
            for code, _, suffix in versions:
                result = runner.factory_contract(code, host, agent)
                self.assertEqual("com.live2d.graphics3d.shader." + suffix, result["owner"])
            with self.assertRaises(ValueError):
                runner.factory_contract("9999", host, agent)
            with zipfile.ZipFile(host, "w") as out:
                out.writestr("com/live2d/graphics3d/shader/A.class", b"changed")
            with self.assertRaises(ValueError):
                runner.factory_contract("5304", host, agent)

    def test_readiness_uses_nested_runtime_log_directory(self):
        with tempfile.TemporaryDirectory() as temporary:
            home = Path(temporary)
            log = home / "logs/runtime/day/runtime.log"
            log.parent.mkdir(parents=True)
            self.assertFalse(runner.runtime_ready(home))
            log.write_text("Turboism Developer Preview started: host=ACTIVE", encoding="utf-8")
            self.assertTrue(runner.runtime_ready(home))

    def test_crlf_java_properties(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "result.properties"
            path.write_bytes(b"#comment\r\nstatus=PASS\r\ncount=432\r\n")
            self.assertEqual({"status": "PASS", "count": "432"}, runner.properties(path))


class NativeSummaryTest(unittest.TestCase):
    def test_failed_or_shadow_case_is_not_a_performance_sample(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            valid = {"status": "PASS", "cleanup": "safe", "normalExitCode": 0,
                     "inputsUnchanged": True, "copyUnchanged": True, "stagedArtifactsUnchanged": True,
                     "load": {"shadowComparison": "true"}}
            for override in ({"profiling": True}, {"status": "FAIL"}, {"cleanup": "unknown"}, {"normalExitCode": 1},
                             {"copyUnchanged": False}, {}):
                (root / "envelope.json").write_text(json.dumps(dict(valid, **override)), encoding="utf-8")
                with self.assertRaises(AssertionError):
                    case(root)


if __name__ == "__main__":
    unittest.main()
