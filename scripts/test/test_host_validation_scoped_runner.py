#!/usr/bin/env python3
"""Actual scoped Runner protocol with synthetic files and non-Cubism launchers.

Identity rejection must stop before launch. The timeout case uses a private test
SOURCE hash override and a sleep-only launcher; the production reviewed hashes
and all official installations remain untouched. Admission cases use a synthetic
runtime log and a recording client, through the same scope and finalizer. No case
is host acceptance.
"""
from pathlib import Path
import json
import os
import pwd
import shutil
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

    def test_mesa_rejects_unconfirmed_deferred_before_trigger_client_and_pass(self):
        for deferred in ("INACTIVE", "UNKNOWN", "COMPLETE", "MISSING"):
            with self.subTest(deferred=deferred):
                self.scenario(timeout=False, deferred=deferred, mesa=True)

    def test_mesa_with_active_deferred_allows_trigger_client_and_pass(self):
        self.scenario(timeout=False, deferred="ACTIVE", mesa=True)

    def test_mesa_off_does_not_require_deferred_activation(self):
        for deferred in ("INACTIVE", "MISSING"):
            with self.subTest(deferred=deferred):
                self.scenario(timeout=False, deferred=deferred, mesa=False)

    def scenario(self, *, timeout, cancel=False, deferred=None, mesa=False):
        parent = pwd.getpwuid(os.getuid()).pw_dir
        with tempfile.TemporaryDirectory(prefix=".tq-test-", dir=parent) as temporary:
            root = Path(temporary)
            store = queue.Store(root / "queue")
            source = root / "source"
            preview = source / "scripts/preview"
            preview.mkdir(parents=True)
            tools = Path(queue.__file__).parent
            for name in ("host_validation.py", "host_validation_queue.py", "host_validation_containment.py",
                         "host_validation_evidence.py", "host_validation_retention.py", "host-validation-env.sh", "host-validation-transport.sh",
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
            if timeout or deferred is not None:
                # Only the test SOURCE fixture is altered, never production identity authority.
                runner_source = preview / "run-cubism-host-validation.sh"
                runner_source.write_text(runner_source.read_text().replace(
                    "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                    queue.file_digest(editor / "app/lib/Live2D_Cubism.jar")))
                (golden / "pfx/drive_c/windows/system32").mkdir(parents=True)
                poison.write_text(f"#!/bin/sh\ntouch '{marker}'\nsleep 30\nexit 97\n")
            if deferred is not None:
                # The supervisor imports the captured SOURCE module, not this process.
                # Match its synthetic host hash without changing production authority.
                evidence_source = preview / "host_validation_evidence.py"
                evidence_source.write_text(evidence_source.read_text().replace(
                    "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                    queue.file_digest(editor / "app/lib/Live2D_Cubism.jar")))
                # Use the admitted MCP client protocol without relaxing its inventory,
                # fixture or containment checks. Both programs only write test files.
                client = preview / "mcp-host-validation-client.py"
                client.write_text(
                    "from pathlib import Path\nimport sys\n"
                    "home = Path(sys.argv[1])\n"
                    "assert (home / 'state/trigger').is_file(), 'client ran before trigger'\n"
                    "(home / 'state/client-ran').write_text('ran\\n')\n")
                marker_line = ("" if deferred == "MISSING" else
                               "TURBOISM_DEFERRED_GL_ERROR_CHECK installation=COMPLETE" if deferred == "COMPLETE" else
                               "TURBOISM_DEFERRED_GL_ERROR_CHECK deferred=" + deferred)
                poison.write_text(
                    f"#!{sys.executable}\nfrom pathlib import Path\nimport os, time\n"
                    "task = Path(os.environ['TURBOISM_HOST_VALIDATION_TASK_DIR'])\n"
                    "home = task / 'turboism-home'\n"
                    "(home / 'logs/runtime').mkdir(parents=True, exist_ok=True)\n"
                    f"Path({str(marker)!r}).write_text('synthetic launcher only')\n"
                    # A preexisting PASS cannot override failed runtime admission.
                    "(home / 'state/mcp-host-validation.properties').write_text('status=PASS\\n')\n"
                    f"(home / 'logs/runtime/test.log').write_text({(marker_line + chr(10) + 'TEST_READY' + chr(10))!r})\n"
                    "deadline = time.monotonic() + 30\n"
                    "while not (home / 'state/client-ran').exists() and time.monotonic() < deadline:\n"
                    "    time.sleep(0.01)\n")
            environment = {key: value for key, value in os.environ.items() if not key.startswith("TURBOISM_")}
            environment["TURBOISM_ENV_FILE"] = "/dev/null"
            request_dir = root / "request"
            command = ["bash", str(preview / "run-cubism-host-validation.sh"), "--name", "scoped-test",
                "--version", "5302", "--bundle-root", str(bundle), "--agent", str(agent),
                "--aux-agent", str(agent) + ":probe.jar", "--fixture-host", str(fixture),
                "--golden-prefix", str(golden), "--host-root", str(root / "host"),
                "--proton-wrapper", str(poison), "--proton-runner", str(poison),
                "--result-file", "state/result.txt", "--prepare-dir", str(request_dir),
                # Timeout/cancel fixtures test containment, not GL hook admission.
                # Explicitly opt out; the deferred cases below opt in separately.
                "--jvm-option", "-Dturboism.optimization.mesaGlThread=" + str(mesa).lower()]
            task_spec = "scoped-test:5302"
            if deferred is not None:
                command[command.index("--name") + 1] = "mcp"
                command[command.index("--result-file") + 1] = "state/mcp-host-validation.properties"
                command += ["--client-script", str(client) + ":mcp-host-validation-client.py",
                            "--client-python", str(Path(sys.executable).resolve()),
                            "--require-fixture-unchanged", "--trigger", "state/trigger",
                            "--ready-marker", "TEST_READY", "--poll-seconds", "1"]
                task_spec = "mcp:5302"
            prepared_result = subprocess.run(command, env=environment, capture_output=True, text=True)
            self.assertEqual(0, prepared_result.returncode, prepared_result.stderr)
            descriptor = queue.PreparedStore(store).capture(json.loads((request_dir / "runner-request.json").read_text()),
                                                          source, task_spec)
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
            if deferred is not None:
                job_dir = store.root / "jobs" / job["job_id"]
                evidence = job_dir / "evidence"
                # Preserve diagnostics even if a fixture/setup assertion fails.
                output = None
                if destination := os.environ.get("TURBOISM_TEST_EVIDENCE_DIR"):
                    output = Path(destination) / ("mesa-" + str(int(mesa)) + "-" + deferred.lower())
                    output.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(job_dir / "runner.log", output / "runner.log")
                    shutil.copytree(evidence, output / "evidence", dirs_exist_ok=True)
                lifecycle = evidence / "final-task/lifecycle-result.json"
                self.assertTrue(lifecycle.is_file(), log + "\n" + repr(result))
                preliminary = json.loads(lifecycle.read_text())
                task = Path(preliminary["details"]["taskDir"])
                home = task / "turboism-home"
                admitted = not mesa or deferred == "ACTIVE"
                observed = {
                    "mesa": mesa, "deferred": deferred, "terminalState": result["terminalState"],
                    "trigger": (home / "state/trigger").exists(),
                    "client": (home / "state/client-ran").exists(),
                    "cleanup": result["cleanup"],
                    "validationComplete": preliminary["details"]["validationComplete"],
                }
                # Optional test-only artifact sink; never changes the Runner's inputs.
                if output is not None:
                    (output / "observed.json").write_text(json.dumps(observed, indent=2) + "\n")
                print(json.dumps(observed, sort_keys=True), flush=True)
                self.assertEqual("safe", result["cleanup"], result)
                self.assertEqual("safe", result["containment"]["cleanup"], result)
                self.assertTrue(marker.exists(), log)
                self.assertEqual(admitted, observed["trigger"], log)
                self.assertEqual(admitted, observed["client"], log)
                self.assertEqual(admitted, observed["validationComplete"], log)
                self.assertEqual("succeeded" if admitted else "failed", result["terminalState"], log)
                self.assertEqual("PASS" if admitted else "FAIL", result["validationStatus"], result)
                self.assertEqual(0 if admitted else 1, preliminary["details"]["exitCode"], log)
                self.assertEqual(admitted, "terminal PASS observed" in log, log)
                self.assertEqual(b"synthetic model, never opened", fixture.read_bytes())
                self.assertEqual(fixture.read_bytes(), (task / (job["run_id"] + ".cmo3")).read_bytes())
                if mesa:
                    properties = dict(line.split("=", 1) for line in
                                      (evidence / "deferred-check.properties").read_text().splitlines())
                    state = deferred.lower() if deferred in ("ACTIVE", "INACTIVE") else "unknown"
                    self.assertEqual(state, properties["deferredCheck"])
                    if not admitted:
                        self.assertIn("refusing validation workload", log)
                        self.assertTrue(result["containment"]["kernelProof"]["cgroupKillWritten"])
                        self.assertTrue((task / "prefix").is_dir(), "failed admission must retain its prefix")
                else:
                    self.assertFalse((evidence / "deferred-check.properties").exists())
                return
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
