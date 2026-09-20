#!/usr/bin/env python3
"""Exercise the real generated launch template with a harmless local argv recorder.

This never starts Cubism, Proton or Wine and never touches the validation queue.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts/preview/run-cubism-host-validation.sh"


class LaunchTemplateTest(unittest.TestCase):
    def run_template(self, exit_code, focus=False):
        source = RUNNER.read_text()
        templates = re.findall(r'cat > "\$local_tmp/launch\.sh" <<SH\n.*?\nSH\n', source, re.S)
        self.assertEqual(1, len(templates), "expected the one production launch template")
        with tempfile.TemporaryDirectory(prefix="turboism-launch-template-") as temporary:
            base = Path(temporary)
            task = base / "task with spaces"
            evidence = task / "evidence"
            local = base / "local temporary"
            evidence.mkdir(parents=True)
            local.mkdir()
            focus_marker = base / "focus-called"
            niri = base / "niri"
            niri.write_text("#!/usr/bin/env python3\nfrom pathlib import Path\n"
                            + f"Path({str(focus_marker)!r}).write_text('observed')\nprint('[]')\n")
            niri.chmod(0o700)
            recorder = base / "argv recorder"
            recorder.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys, time\nfrom pathlib import Path\n"
                + (f"deadline=time.monotonic()+3\nwhile not Path({str(focus_marker)!r}).exists() and time.monotonic()<deadline: time.sleep(0.01)\n" if focus else "")
                + "print(json.dumps({'argv':sys.argv[1:], 'task':os.environ.get('TURBOISM_HOST_VALIDATION_TASK_DIR'), "
                "'display':os.environ.get('DISPLAY')}))\n"
                "print('recorder stderr', file=sys.stderr)\n"
                f"sys.exit({exit_code})\n"
            )
            recorder.chmod(0o700)
            values = {
                "local_tmp": str(local), "task_dir": str(task), "evidence_dir": str(evidence),
                "display": ":testing-only", "proton_wrapper": str(recorder),
                "task_id": "offline-template-fixture", "focus_editor_window": "1" if focus else "0",
                "prefix_dir": str(task / "prefix"), "proton_runner": str(base / "runner with spaces"),
                "cmd_unix": str(task / "prefix/cmd.exe"), "win_launch": r"Z:\task with spaces\launch.bat",
            }
            generated = subprocess.run(["bash", "-c", templates[0]], env={**os.environ, **values},
                                       capture_output=True, text=True, timeout=10)
            self.assertEqual(0, generated.returncode, generated.stderr)
            launched = subprocess.run(["sh", str(local / "launch.sh")],
                                      env={**os.environ, "PATH": str(base) + os.pathsep + os.environ["PATH"]},
                                      capture_output=True, text=True, timeout=10)
            self.assertEqual(focus, focus_marker.exists(), "desktop helper must be opt-in")
            self.assertEqual(exit_code, launched.returncode)
            self.assertEqual(str(exit_code), (evidence / "wrapper.exit").read_text().strip())
            output = (evidence / "launcher.out").read_text()
            self.assertIn("recorder stderr", output)
            record = next(json.loads(line) for line in output.splitlines() if line.startswith("{"))
            self.assertEqual(str(task), record["task"])
            self.assertEqual(":testing-only", record["display"])
            return record["argv"], values

    def test_normal_launch_does_not_force_full_proton_debug_logging(self):
        argv, values = self.run_template(0)
        self.assertEqual([
            "-p", values["prefix_dir"], "--runner", values["proton_runner"],
            values["cmd_unix"], "/c", values["win_launch"],
        ], argv)

    def test_opt_in_focus_helper_is_reaped_at_launcher_exit(self):
        self.run_template(0, focus=True)

    def test_cleanup_diagnostic_distinguishes_timeout_from_native_exit(self):
        source = RUNNER.read_text()
        functions = re.findall(r'remote_record_wrapper_cleanup\(\) \{\n.*?\n\}', source, re.S)
        self.assertEqual(1, len(functions))
        with tempfile.TemporaryDirectory(prefix="turboism-cleanup-reason-") as temporary:
            for reason in ("native-exit-evidence-observed", "launcher-exit-timeout"):
                recorded = subprocess.run(
                    ["bash", "-c", functions[0] + '\nremote_record_wrapper_cleanup "$reason"'],
                    env={**os.environ, "evidence_dir": temporary, "reason": reason},
                    capture_output=True, text=True, timeout=5,
                )
                self.assertEqual(0, recorded.returncode, recorded.stderr)
                self.assertEqual(
                    f"reason={reason}\ncleanupOwner=supervisor\n",
                    (Path(temporary) / "wrapper.cleanup").read_text(),
                )
        self.assertIn("remote_record_wrapper_cleanup 'launcher-exit-timeout'", source)
        self.assertIn("remote_record_wrapper_cleanup 'native-exit-evidence-observed'", source)

    def test_wrapper_failure_remains_observable(self):
        argv, values = self.run_template(17)
        self.assertEqual([values["cmd_unix"], "/c", values["win_launch"]], argv[-3:])


if __name__ == "__main__":
    unittest.main()
