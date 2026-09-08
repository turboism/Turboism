#!/usr/bin/env python3
"""Actual scoped Runner protocol with synthetic files and non-Cubism launchers.

Identity rejection must stop before launch. The timeout case uses a private test
SOURCE hash override and a sleep-only launcher; the production reviewed hashes
and all official installations remain untouched. Neither case is host acceptance.
"""
from pathlib import Path
import json
import os
import pwd
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_queue as queue


@unittest.skipUnless(sys.platform.startswith("linux"), "BLOCKED: scoped Runner requires Linux")
class ScopedRunnerIntegrationTest(unittest.TestCase):
    def test_real_admission_scope_and_finalizer_fail_before_poisoned_host_launch(self):
        self.scenario(timeout=False)

    def test_timeout_reclaims_only_the_contained_sleep_launcher(self):
        self.scenario(timeout=True)

    def test_cancel_reclaims_only_the_contained_sleep_launcher(self):
        self.scenario(timeout=True, cancel=True)

    def scenario(self, *, timeout, cancel=False):
        parent = pwd.getpwuid(os.getuid()).pw_dir if timeout else None
        with tempfile.TemporaryDirectory(prefix=".tq-test-", dir=parent) as temporary:
            root = Path(temporary)
            store = queue.Store(root / "queue")
            source = root / "source"
            preview = source / "scripts/preview"
            preview.mkdir(parents=True)
            tools = Path(queue.__file__).parent
            for name in ("host_validation.py", "host_validation_queue.py", "host_validation_containment.py",
                         "host_validation_evidence.py", "host-validation-env.sh", "host-validation-transport.sh",
                         "run-cubism-host-validation.sh", "archive-cubism-host-evidence.sh"):
                queue.copy_verified(tools / name, preview / name)
            # A test-only SOURCE fixture override, included in the prepared digest.
            # No production option or ambient HOME override can select a test queue.
            with (preview / "host_validation_queue.py").open("a") as stream:
                stream.write(f"\n# Isolated test source fixture, never deployed.\ndef account_root():\n    return Path({str(store.root)!r})\n")
            subprocess.run(["git", "init", "-q", str(source)], check=True)
            subprocess.run(["git", "add", "."], cwd=source, check=True)
            subprocess.run(["git", "-c", "user.name=Test", "-c", "user.email=test@example.invalid",
                "-c", "core.hooksPath=/dev/null", "-c", "commit.gpgsign=false", "commit", "-qm", "test fixture"],
                cwd=source, check=True)
            bundle = root / "bundle"
            bundle.mkdir()
            agent = bundle / "agent.jar"
            agent.write_bytes(b"not executable Java")
            fixture = root / "fixture.cmo3"
            fixture.write_bytes(b"synthetic model, never opened")
            golden = root / "synthetic-golden"
            editor = golden / "pfx/drive_c/Program Files/Live2D Cubism 5.3"
            (editor / "app/lib").mkdir(parents=True)
            (editor / "app/lib/Live2D_Cubism.jar").write_bytes(b"deliberately wrong reviewed hash")
            (editor / "CubismEditor5.bat").write_text("not an executable BAT")
            poison = root / "poison-launcher"
            marker = root / "must-not-launch"
            poison.write_text(f"#!/bin/sh\ntouch '{marker}'\nexit 97\n")
            poison.chmod(0o700)
            if timeout:
                # Only the test SOURCE fixture is altered, never production identity authority.
                runner_source = preview / "run-cubism-host-validation.sh"
                runner_source.write_text(runner_source.read_text().replace(
                    "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                    queue.file_digest(editor / "app/lib/Live2D_Cubism.jar")))
                (golden / "pfx/drive_c/windows/system32").mkdir(parents=True)
                poison.write_text(f"#!/bin/sh\ntouch '{marker}'\nsleep 30\nexit 97\n")
            environment = {key: value for key, value in os.environ.items() if not key.startswith("TURBOISM_")}
            environment["TURBOISM_ENV_FILE"] = "/dev/null"
            request_dir = root / "request"
            command = ["bash", str(preview / "run-cubism-host-validation.sh"), "--name", "scoped-test",
                "--version", "5302", "--bundle-root", str(bundle), "--agent", str(agent),
                "--aux-agent", str(agent) + ":probe.jar", "--fixture-host", str(fixture),
                "--golden-prefix", str(golden), "--host-root", str(root / "host"),
                "--proton-wrapper", str(poison), "--proton-runner", str(poison),
                "--result-file", "state/result.txt", "--prepare-dir", str(request_dir)]
            prepared_result = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertEqual(0, prepared_result.returncode, prepared_result.stderr)
            descriptor = queue.PreparedStore(store).capture(json.loads((request_dir / "runner-request.json").read_text()),
                                                          source, "scoped-test:5302")
            store.submit(descriptor["digest"], descriptor["digest"], "scoped-test", 20 if cancel else (2 if timeout else 20))
            job = store.claim()
            store.acknowledge(job["job_id"], job["attempt_id"], queue.process_identity(os.getpid()))
            finished = threading.Event()
            def request_cancel():
                deadline = time.monotonic() + 10
                while not finished.wait(0.02) and time.monotonic() < deadline:
                    if marker.exists():
                        store.cancel(job["job_id"])
                        return
            canceller = threading.Thread(target=request_cancel) if cancel else None
            if canceller is not None:
                canceller.start()
            try:
                with queue.FileLock(store.root / "admission.lock") as lock, mock.patch.object(queue, "account_root", return_value=store.root):
                    result = queue.RunnerBackend().run(store, job, lock.fd)
            finally:
                finished.set()
                if canceller is not None:
                    canceller.join(timeout=2)
            log = (store.root / "jobs" / job["job_id"] / "runner.log").read_text()
            self.assertEqual("cancelled" if cancel else ("timed_out" if timeout else "failed"), result["terminalState"], log)
            self.assertEqual("safe", result["cleanup"], result)
            self.assertNotEqual("PASS", result["validationStatus"])
            self.assertEqual(job["run_id"], result["runId"])
            self.assertIn("identity", log.lower())
            self.assertEqual(timeout, marker.exists(), log)
            if timeout:
                self.assertTrue(result["containment"]["kernelProof"]["cgroupKillWritten"])
            self.assertEqual(b"synthetic model, never opened", fixture.read_bytes())
            self.assertEqual("safe", result["containment"]["cleanup"])
            # Exercise the final-lifecycle-only crash window using real kernel proof.
            self.assertFalse((store.root / "jobs" / job["job_id"] / "outcome.json").exists())
            self.assertEqual(result, queue.durable_outcome(store, job))


if __name__ == "__main__":
    unittest.main(verbosity=2)
