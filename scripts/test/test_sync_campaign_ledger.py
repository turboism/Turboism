#!/usr/bin/env python3
"""Synthetic-fixture tests for sync_campaign_ledger; never touches the real queue."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import sync_campaign_ledger as ledger_sync
import host_validation_triage as triage
from test_host_validation_triage import add_job, make_root, EDT_LOG, ADMISSION_LOG

TASK_DIR = "/vroot/backup/5200-r1/queue-" + "a" * 32


class SyncFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.db, self.jobs_root, self.prepared_root = make_root(self.tmp)
        self.ledger_path = self.tmp / "campaign-jobs.json"
        self.rules = triage.load_rules()

    def tearDown(self):
        self._tmp.cleanup()

    def write_ledger(self, jobs: list) -> None:
        self.ledger_path.write_text(
            json.dumps({"schemaVersion": 1, "jobs": jobs}, indent=1), encoding="utf-8")

    def run_sync(self, *argv: str) -> int:
        return ledger_sync.main(["--ledger", str(self.ledger_path),
                                 "--queue-db", str(self.db), *argv])

    def rows_records(self):
        rows = triage.iter_rows(self.db, terminal_only=True)
        records = {row["job_id"]: triage.triage_row(
            row, self.rules, jobs_root=self.jobs_root,
            prepared_root=self.prepared_root, validation_root=None)
            for row in rows}
        return rows, records

    def test_new_entry_shape_and_relative_evidence(self):
        self.write_ledger([])
        add_job(self.tmp, job_id="j-1", request_key="cov3-backup-5200",
                state="failed", spec="backup:5200",
                outcome={"terminalState": "failed", "validationStatus": "FAIL",
                         "runId": "queue-" + "a" * 32, "runnerExitCode": 1,
                         "cleanup": "safe",
                         "details": {"taskDir": TASK_DIR,
                                     "evidenceDir": TASK_DIR + "/evidence"}},
                log=ADMISSION_LOG)
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        entry = json.loads(self.ledger_path.read_text())["jobs"][0]
        self.assertEqual(entry["task"], "backup")
        self.assertEqual(entry["version"], "5200")
        self.assertIsNone(entry["variant"])
        self.assertEqual(entry["status"], "FINAL")
        self.assertEqual(entry["result"]["terminalState"], "failed")
        self.assertEqual(entry["result"]["exitCode"], 1)
        self.assertEqual(entry["result"]["evidenceDir"],
                         "backup/5200-r1/queue-" + "a" * 32 + "/evidence")
        self.assertNotIn("/vroot", json.dumps(entry))
        self.assertEqual(entry["triage"]["category"], "ADMISSION_STATIC")
        self.assertEqual(entry["scope"], ledger_sync.SCOPE)

    def test_cancelled_entry_shape(self):
        self.write_ledger([])
        add_job(self.tmp, job_id="j-c", request_key="cov3-fps-5200",
                state="cancelled", spec="fps:5200")
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        entry = json.loads(self.ledger_path.read_text())["jobs"][0]
        self.assertEqual(entry["status"], "CANCELLED")
        self.assertEqual(entry["result"]["terminalState"], "cancelled")
        self.assertIsNone(entry["result"]["validationStatus"])
        self.assertNotIn("triage", entry)

    def test_idempotent_and_update_in_place(self):
        existing = {
            "task": "backup", "version": "5200", "variant": None,
            "jobId": "j-1", "requestKey": "cov3-backup-5200",
            "status": "SUBMITTED", "scope": ledger_sync.SCOPE,
        }
        self.write_ledger([existing])
        add_job(self.tmp, job_id="j-1", request_key="cov3-backup-5200",
                state="failed", spec="backup:5200",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=EDT_LOG)
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        jobs = json.loads(self.ledger_path.read_text())["jobs"]
        self.assertEqual(len(jobs), 1)
        self.assertEqual(jobs[0]["status"], "FINAL")
        self.assertEqual(jobs[0]["triage"]["category"], "INFRA_RETRYABLE")
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        again = json.loads(self.ledger_path.read_text())["jobs"]
        self.assertEqual(jobs, again)

    def test_session_job_expands_to_per_step_entries(self):
        self.write_ledger([])
        add_job(self.tmp, job_id="j-s", request_key="cov3-session-5304",
                state="succeeded", spec="session-standard:5304@standard",
                outcome={"terminalState": "succeeded", "validationStatus": "PASS",
                         "runId": "queue-" + "b" * 32, "runnerExitCode": 0,
                         "cleanup": "safe",
                         "details": {"taskDir": TASK_DIR,
                                     "evidenceDir": TASK_DIR + "/evidence"}})
        result_dir = self.tmp / "jobs" / "j-s" / "evidence" / "result"
        result_dir.mkdir(parents=True, exist_ok=True)
        (result_dir / "session-result.properties").write_text(
            "status=PASS\n"
            "steps=2\n"
            "step.0.name=texture-relations\n"
            "step.0.spec=texture-relations\\:5304@standard\n"
            "step.0.status=PASS\n"
            "step.0.durationMillis=12000\n"
            "step.1.name=core-owned-moc\n"
            "step.1.spec=core-owned-moc\\:5304\n"
            "step.1.status=FAIL\n"
            "step.1.durationMillis=9000\n",
            encoding="utf-8")
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        jobs = json.loads(self.ledger_path.read_text())["jobs"]
        self.assertEqual(len(jobs), 2)
        first, second = jobs
        self.assertEqual(first["task"], "texture-relations")
        self.assertEqual(first["version"], "5304")
        self.assertEqual(first["variant"], "standard")
        self.assertEqual(first["viaSession"], "j-s")
        self.assertEqual(first["requestKey"], "cov3-session-5304#texture-relations")
        self.assertEqual(first["result"]["validationStatus"], "PASS")
        self.assertEqual(first["result"]["stepDurationMillis"], "12000")
        self.assertEqual(second["task"], "core-owned-moc")
        self.assertIsNone(second["variant"])
        self.assertEqual(second["result"]["validationStatus"], "FAIL")
        self.assertEqual(second["viaSession"], "j-s")
        # Re-sync is idempotent on the per-step request keys.
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        again = json.loads(self.ledger_path.read_text())["jobs"]
        self.assertEqual(jobs, again)

    def test_no_absolute_paths_leak(self):
        self.write_ledger([])
        add_job(self.tmp, job_id="j-2", request_key="k-2", state="timed_out",
                spec="theme:5200",
                outcome={"terminalState": "timed_out",
                         "details": {"taskDir": "/home/tester/V/theme/5200-r1/queue-x",
                                     "evidenceDir": "/home/tester/V/theme/5200-r1/queue-x/evidence"}})
        self.assertEqual(self.run_sync("--all-terminal"), 0)
        text = self.ledger_path.read_text()
        self.assertNotIn("/home/tester", text)


if __name__ == "__main__":
    unittest.main()
