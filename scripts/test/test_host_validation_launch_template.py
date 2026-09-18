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
    def run_template(self, exit_code):
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
            recorder = base / "argv recorder"
            recorder.write_text(
                "#!/usr/bin/env python3\n"
                "import json, os, sys\n"
                "print(json.dumps({'argv':sys.argv[1:], 'task':os.environ.get('TURBOISM_HOST_VALIDATION_TASK_DIR'), "
                "'display':os.environ.get('DISPLAY')}))\n"
                "print('recorder stderr', file=sys.stderr)\n"
                f"sys.exit({exit_code})\n"
            )
            recorder.chmod(0o700)
            values = {
                "local_tmp": str(local), "task_dir": str(task), "evidence_dir": str(evidence),
                "display": ":testing-only", "proton_wrapper": str(recorder),
                "prefix_dir": str(task / "prefix"), "proton_runner": str(base / "runner with spaces"),
                "cmd_unix": str(task / "prefix/cmd.exe"), "win_launch": r"Z:\task with spaces\launch.bat",
            }
            generated = subprocess.run(["bash", "-c", templates[0]], env={**os.environ, **values},
                                       capture_output=True, text=True, timeout=10)
            self.assertEqual(0, generated.returncode, generated.stderr)
            launched = subprocess.run(["sh", str(local / "launch.sh")],
                                      capture_output=True, text=True, timeout=10)
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

    def test_wrapper_failure_remains_observable(self):
        argv, values = self.run_template(17)
        self.assertEqual([values["cmd_unix"], "/c", values["win_launch"]], argv[-3:])


if __name__ == "__main__":
    unittest.main()
