#!/usr/bin/env python3
"""Post-containment evidence tests use synthetic files, never a Cubism installation."""
from pathlib import Path
import copy
import sys
import tempfile
import unittest
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_evidence as evidence
from host_validation_queue import QueueError, file_digest


class EvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.prepared = self.root / "prepared"
        self.prepared.mkdir()
        self.source = self.prepared / "fixture.cmo3"
        self.source.write_bytes(b"synthetic fixture")
        self.agent = self.prepared / "agent.jar"
        self.agent.write_bytes(b"synthetic agent")
        self.job = {"job_id": "job", "attempt_id": "attempt", "run_id": "queue-test", "digest": "digest"}
        self.identity = {"jobId": "job", "attemptId": "attempt", "runId": "queue-test", "preparedDigest": "digest"}
        self.proof = {"schemaVersion": 1, **self.identity, "cleanup": "safe", "testOnly": True}
        self.descriptor = {"hostDependencies": [], "argv": ["--name", "test", "--version", "5302", "--run-label", "one",
            "--host-root", str(self.root / "host"), "--golden-prefix", str(self.root / "synthetic-golden"),
            "--fixture-host", str(self.source), "--agent", str(self.agent),
            "--result-file", "state/result.txt", "--result-pass-line", "status=PASS", "--result-fail-line", "status=FAIL"]}
        self.paths = evidence.layout(self.descriptor, self.prepared, self.job)
        self.paths["fixture"].parent.mkdir(parents=True)
        self.paths["fixture"].write_bytes(self.source.read_bytes())
        (self.paths["task"] / "turboism-agent.jar").write_bytes(self.agent.read_bytes())
        for directory in (self.paths["golden"], self.paths["cloned"]):
            (directory / "app/lib").mkdir(parents=True)
            (directory / "app/lib/Live2D_Cubism.jar").write_bytes(b"synthetic host, never executable")
            (directory / "CubismEditor5.bat").write_bytes(b"synthetic BAT, never executable")
        fake_hash = file_digest(self.paths["golden"] / "app/lib/Live2D_Cubism.jar")
        patch = mock.patch.dict(evidence.HOSTS, {"5302": ("Live2D Cubism 5.3", fake_hash)})
        patch.start()
        self.addCleanup(patch.stop)
        self.preliminary = {"schemaVersion": 1, **self.identity, "cleanup": "unknown", "validationStatus": "UNKNOWN",
            "identityVerified": True, "fixtureUnchanged": True, "normalExit": True,
            "details": {"taskDir": str(self.paths["task"]), "cleanupOwner": "supervisor", "validationComplete": True,
                "goldenUnchanged": True, "sourceFixtureBeforeSha256": file_digest(self.source),
                "fixtureBeforeSha256": file_digest(self.paths["fixture"]),
                "goldenBatBeforeSha256": file_digest(self.paths["golden"] / "CubismEditor5.bat"),
                "clonedBatBeforeSha256": file_digest(self.paths["cloned"] / "CubismEditor5.bat")}}
        self.directory = self.root / "job"
        self.result = self.paths["task"] / "turboism-home/state/result.txt"
        self.result.parent.mkdir(parents=True)
        self.result.write_bytes(b"status=PASS\r\n")
        archiver = self.prepared / "tool/scripts/preview/archive-cubism-host-evidence.sh"
        archiver.parent.mkdir(parents=True)
        archiver.write_bytes(Path(evidence.__file__).with_name(archiver.name).read_bytes())

    def finish(self, raw=None, proof=None, exit_code=0, requested=None):
        return evidence.finalize(self.job, self.descriptor, self.prepared, self.directory,
            self.preliminary if raw is None else raw, self.proof if proof is None else proof, exit_code, requested)

    def test_safe_proof_plus_post_cleanup_checks_finalizes_and_removes_only_task_prefix(self):
        outside = self.root / "outside"
        outside.mkdir()
        (outside / "keep").write_text("must survive")
        (self.paths["prefix"] / "external-link").symlink_to(outside, target_is_directory=True)
        result = self.finish()
        self.assertEqual("succeeded", result["terminalState"])
        self.assertEqual("PASS", result["validationStatus"])
        self.assertFalse(self.paths["prefix"].exists())
        self.assertTrue((outside / "keep").is_file())
        self.assertTrue(self.paths["golden"].is_dir())
        self.assertTrue((self.directory / "evidence/lifecycle-result.json").is_file())

    def test_unknown_or_mismatched_proof_never_deletes_prefix(self):
        for proof in ({**self.proof, "cleanup": "unknown"}, {**self.proof, "attemptId": "other"}):
            with self.assertRaises(QueueError):
                self.finish(proof=proof)
        self.assertTrue(self.paths["prefix"].is_dir())
        self.assertFalse(self.directory.exists())

    def test_late_conflicting_or_missing_terminal_is_not_pass(self):
        for contents in (b"status=FAIL\n", b"status=PASS\r\nstatus=FAIL\r\n", b"prefix-status=PASS\n", b""):
            with self.subTest(contents=contents):
                self.result.write_bytes(contents)
                result = self.finish()
                self.assertEqual("failed", result["terminalState"])
                self.assertTrue(self.paths["prefix"].exists())
        self.result.unlink()
        self.assertEqual("failed", self.finish()["terminalState"])

    def test_late_failure_marker_overrides_terminal_pass(self):
        self.descriptor["argv"] += ["--failure-marker", "ASSERTION_FAILED"]
        log = self.paths["task"] / "turboism-home/logs/runtime/day/runtime.log"
        log.parent.mkdir(parents=True)
        log.write_text("ASSERTION_FAILED after earlier PASS\n")
        self.assertEqual("failed", self.finish()["terminalState"])

    def test_marker_terminal_is_rechecked_after_cleanup(self):
        self.descriptor["argv"] += ["--result-marker", "MATRIX_PASS", "--failure-marker", "MATRIX_FAIL"]
        log = self.paths["task"] / "turboism-home/logs/runtime/day/runtime.log"
        log.parent.mkdir(parents=True)
        log.write_text("MATRIX_PASS\nMATRIX_FAIL\n")
        self.assertEqual("failed", self.finish()["terminalState"])
        log.write_text("MATRIX_PASS\n")
        self.assertEqual("succeeded", self.finish()["terminalState"])
    def test_preliminary_pass_and_exit_zero_alone_are_not_final_pass(self):
        for change in ({"normalExit": False}, {"details": {**self.preliminary["details"], "validationComplete": False}}):
            raw = {**self.preliminary, **change, "validationStatus": "PASS"}
            result = self.finish(raw=raw)
            self.assertEqual("failed", result["terminalState"])
            self.assertTrue(self.paths["prefix"].is_dir())
        self.assertEqual("failed", self.finish(exit_code=2)["terminalState"])

    def test_fixture_modified_by_late_child_is_caught_after_container_cleanup(self):
        self.paths["fixture"].write_bytes(b"late write after preliminary verification")
        result = self.finish()
        self.assertFalse(result["fixtureUnchanged"])
        self.assertEqual("failed", result["terminalState"])
        self.assertEqual("safe", result["cleanup"])
        self.assertTrue(self.paths["prefix"].is_dir())

    def test_staged_agent_modified_after_preliminary_check_is_not_pass(self):
        (self.paths["task"] / "turboism-agent.jar").write_bytes(b"changed")
        result = self.finish()
        self.assertFalse(result["identityVerified"])
        self.assertEqual("failed", result["terminalState"])

    def test_host_runtime_modified_after_launch_is_not_pass(self):
        runtime = self.root / "proton"
        runtime.write_bytes(b"fixed runtime")
        self.descriptor["hostDependencies"] = [{"path": str(runtime), "sha256": file_digest(runtime)}]
        runtime.write_bytes(b"changed runtime")
        result = self.finish()
        self.assertEqual("failed", result["terminalState"])
        self.assertFalse(result["identityVerified"])
        self.assertTrue(self.paths["prefix"].is_dir())

    def test_boolean_schema_is_not_a_version(self):
        with self.assertRaises(QueueError):
            self.finish(proof={**self.proof, "schemaVersion": True})
        with self.assertRaises(QueueError):
            self.finish(raw={**self.preliminary, "schemaVersion": True})
    def test_wrong_task_path_and_symlink_prefix_are_rejected(self):
        raw = copy.deepcopy(self.preliminary)
        raw["details"]["taskDir"] = str(self.root)
        with self.assertRaises(QueueError):
            self.finish(raw=raw)
        # Do not delete the existing synthetic prefix; point a different job at a link.
        self.job["run_id"] = "another"
        other = self.paths["task"].parent / "another"
        other.symlink_to(self.paths["task"], target_is_directory=True)
        with self.assertRaises(QueueError):
            evidence.layout(self.descriptor, self.prepared, self.job)

    def test_missing_preliminary_after_scoped_cancel_does_not_invent_pass(self):
        result = evidence.finalize(self.job, self.descriptor, self.prepared, self.directory,
            None, self.proof, None, "cancelled")
        self.assertEqual("cancelled", result["terminalState"])
        self.assertEqual("UNKNOWN", result["validationStatus"])
        self.assertFalse(result["identityVerified"])
        self.assertTrue(self.paths["prefix"].is_dir())


if __name__ == "__main__":
    unittest.main()
