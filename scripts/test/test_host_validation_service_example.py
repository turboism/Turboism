#!/usr/bin/env python3
"""Opt-in service-example regression; executes only a temporary Python stub.

Requires a running systemd user manager. Never starts the queue or Cubism,
installs a unit, reads the private project .env, or changes an existing service.
"""
from pathlib import Path
import json
import shlex
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
VARIABLE = "TURBOISM_HOST_VALIDATION_CHECKOUT"


class ServiceExampleTest(unittest.TestCase):
    def test_environment_file_controls_checkout_without_shell(self):
        source = (ROOT / "scripts/preview/turboism-host-validation.service.example").read_text()
        self.assertIn("EnvironmentFile=/path/to/turboism/.env", source)
        self.assertNotIn("WorkingDirectory=", source)
        command = shlex.split(next(line.removeprefix("ExecStart=") for line in source.splitlines()
                                   if line.startswith("ExecStart=")))
        self.assertEqual(command, ["/usr/bin/env", "--chdir=${" + VARIABLE + "}",
                                   "/usr/bin/python3", "scripts/preview/host_validation.py", "serve"])
        self.assertIn(VARIABLE + "=/path/to/turboism", (ROOT / ".env.example").read_text())
        with tempfile.TemporaryDirectory(prefix="host-service-example-") as temporary:
            base = Path(temporary)
            # Spaces and shell metacharacters must remain literal directory names.
            checkout = base / "checkout with spaces;$literal"
            script = checkout / "scripts/preview/host_validation.py"
            script.parent.mkdir(parents=True)
            script.write_text("import json, os, sys\nprint(json.dumps([os.getcwd(), sys.argv[1:]]))\n")
            env_file = base / ".env"
            env_file.write_text(VARIABLE + '="' + str(checkout) + '"\n')
            unit = base / "host-validation-example-test.service"
            unit.write_text(source.replace("EnvironmentFile=/path/to/turboism/.env",
                                           "EnvironmentFile=" + str(env_file)))
            subprocess.run(["systemd-analyze", "--user", "verify", str(unit)], check=True,
                           capture_output=True, text=True)
            # No shell expansion by Python; the user manager expands the actual ExecStart arguments.
            run = ["systemd-run", "--user", "--wait", "--collect", "--pipe", "--quiet",
                   "--expand-environment=yes", "--property=EnvironmentFile=" + str(env_file)]
            result = subprocess.run(run + command, capture_output=True, text=True, timeout=20)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), [str(checkout), ["serve"]])
            for value in ("", str(base / "nonexistent checkout")):
                env_file.write_text(VARIABLE + '="' + value + '"\n')
                result = subprocess.run(run + command, capture_output=True, text=True, timeout=20)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")
            result = subprocess.run(run + ["--property=UnsetEnvironment=" + VARIABLE] + command,
                                    capture_output=True, text=True, timeout=20)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(result.stdout, "")


if __name__ == "__main__":
    unittest.main(verbosity=2)
