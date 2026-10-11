#!/usr/bin/env python3
"""Synthetic-fixture tests for host_validation_triage; no host launches."""
from __future__ import annotations

import json
import sqlite3
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "preview"))
import host_validation_triage as triage

JOB_COLUMNS = ("sequence INTEGER PRIMARY KEY AUTOINCREMENT, job_id TEXT UNIQUE, "
               "request_key TEXT UNIQUE, prepared_id TEXT, digest TEXT, "
               "timeout_seconds INTEGER, state TEXT, reason TEXT DEFAULT '', "
               "created_at REAL, updated_at REAL, cancel_requested INTEGER DEFAULT 0, "
               "attempt_id TEXT, run_id TEXT, identity_json TEXT, evidence_json TEXT")

ADMISSION_LOG = """\
2026-10-08T17:08:29Z [INFO] [runtime] Starting Turboism at Z:\\home\\run
2026-10-08T17:08:42Z [ERROR] [host-session] Host adapter connect threw
java.lang.IllegalArgumentException: verification record fields do not match the reviewed manifest
\tat dev.turboism.mapping.verification.PinnedVerifiedResolverWorkflow.requireManifestRecord(PinnedVerifiedResolverWorkflow.java:130)
\tat dev.turboism.adapter.host.VerifiedHostAdapterConnector.connect(VerifiedHostAdapterConnector.java:473)
\tat java.base/java.lang.Thread.run(Unknown Source)
2026-10-08T17:08:42Z [ERROR] [runtime] Turboism preview startup failed
java.lang.IllegalStateException: Cubism host admission failed before plugin loading
\tat dev.turboism.preview.PreviewRuntime.start(PreviewRuntime.java:561)
\tat java.base/java.lang.Thread.run(Unknown Source)
"""

EDT_LOG = """\
2026-10-09T03:17:15Z [ERROR] [turboism.core] Shell close stage failed safely: SHELL_SCOPE_CLOSE_FAILED
dev.turboism.ui.host.EdtDispatchException: workspace layout EDT operation was not accepted by the EDT within 1000ms
\tat dev.turboism.ui.host.EdtDispatch.dispatch(EdtDispatch.java:163)
\tat dev.turboism.ui.host.EdtDispatch.call(EdtDispatch.java:120)
\tat java.base/java.lang.Thread.run(Unknown Source)
java.lang.IllegalStateException: Runtime-owned shell failed to start
\tat dev.turboism.preview.PreviewRuntime.start(PreviewRuntime.java:641)
caused by dev.turboism.ui.host.EdtDispatchException: embedded-panel EDT operation was not accepted by the EDT within 1000ms
\tat dev.turboism.ui.host.EdtDispatch.dispatch(EdtDispatch.java:163)
\tat java.base/java.lang.Thread.run(Unknown Source)
"""

PROBE_DRIFT_LOG = """\
2026-10-08T21:55:04Z [INFO] [dev.turboism.validation.startup] STARTUP_SUPPRESSION_VALIDATION_READY hostVersion=unknown
2026-10-08T21:55:05Z [WARN] [dev.turboism.validation.startup] STARTUP_SUPPRESSION_VALIDATION_FAILURE splash-suppressed invocation failed: java.lang.NoSuchMethodException: com.live2d.cubism.CECubismEditorApp.e()
2026-10-08T21:55:05Z [INFO] [dev.turboism.validation.startup] STARTUP_SUPPRESSION_VALIDATION_RESULT status=FAIL hostVersion=unknown failures=1
"""

FREE_MARKERS_LOG = """\
2026-10-08T16:00:00Z [INFO] [agent] TASK_STARTUP_ACTION START_PRO_TRIAL_SELECTED
2026-10-08T16:00:00Z [INFO] [agent] TASK_STARTUP_ACTION PRO_TRIAL_ACTIVATED_ACKNOWLEDGED
2026-10-08T16:00:00Z [INFO] [agent] TASK_STARTUP_ACTION HOME_NEWS_DIALOG_CLOSED
2026-10-08T16:00:00Z [INFO] [agent] TASK_STARTUP_ACTION ANNOUNCEMENT_STAGE_HIDDEN
2026-10-08T16:00:00Z [INFO] [agent] TASK_STARTUP_ACTION START_FREE_SELECTED
2026-10-08T16:00:01Z [INFO] [agent] TASK_STARTUP_ACTION FREE_WELCOME_CONFIRMED
2026-10-08T16:01:00Z [INFO] [agent] TASK_STARTUP_ACTION FREE_PARAMETER_LIMIT_ACKNOWLEDGED
"""

UNKNOWN_LOG = """\
2026-10-08T16:00:00Z [ERROR] [runtime] Turboism preview startup failed
java.lang.IllegalStateException: RUNTIME_CONFIG_INVALID
\tat dev.turboism.config.RuntimeConfigRepository.failure(RuntimeConfigRepository.java:88)
\tat java.base/java.lang.Thread.run(Unknown Source)
"""

MODEL_WAIT_LOG = """\
2026-10-10T10:46:42Z [WARN] [dev.turboism.validation.clipmask-viewer] CLIPMASK_VIEWER_MODEL_WAIT elapsedMs=60027 lastFailure=java.lang.IllegalStateException
2026-10-10T10:50:42Z [ERROR] [dev.turboism.validation.clipmask-viewer] CLIPMASK_VIEWER_MATRIX_FAILED java.lang.IllegalStateException: No active model with drawables was observed within 240000 ms
java.lang.IllegalStateException: No active model with drawables was observed within 240000 ms
\tat dev.turboism.validation.clipmaskviewer.ClipMaskViewerHostValidationPlugin.awaitActiveModel(ClipMaskViewerHostValidationPlugin.java:250)
\tat dev.turboism.validation.clipmaskviewer.ClipMaskViewerHostValidationPlugin.runMatrix(ClipMaskViewerHostValidationPlugin.java:140)
\tat java.base/java.lang.Thread.run(Unknown Source)
2026-10-10T10:50:45Z [INFO] [dev.turboism.validation.clipmask-viewer] CLIPMASK_VIEWER_MATRIX_RESULT status=FAIL
"""

CLIPMASK_BLOCKED_LOG = """\
2026-10-10T09:00:42Z [INFO] [dev.turboism.validation.clipmask-viewer] CLIPMASK_VIEWER_MATRIX_RESULT status=BLOCKED modelId=41f72ac0-bd7f-4431-8011-be1ee7131e8a meshCount=16 recordCount=0
"""


def make_root(tmp: Path) -> tuple[Path, Path, Path]:
    (tmp / "jobs").mkdir()
    (tmp / "prepared").mkdir()
    db = sqlite3.connect(tmp / "queue.sqlite3")
    db.executescript(f"CREATE TABLE metadata(version INTEGER NOT NULL);"
                     f"INSERT INTO metadata VALUES(1);"
                     f"CREATE TABLE jobs({JOB_COLUMNS});")
    db.commit()
    db.close()
    return tmp / "queue.sqlite3", tmp / "jobs", tmp / "prepared"


def add_job(tmp: Path, *, job_id: str, request_key: str, state: str,
            outcome: dict | None = None, spec: str | None = None,
            log: str = "", result_props: str | None = None) -> None:
    evidence = tmp / "jobs" / job_id / "evidence"
    evidence.mkdir(parents=True, exist_ok=True)
    (evidence / "turboism.log").write_text(log, encoding="utf-8")
    if result_props is not None:
        (evidence / "result").mkdir(exist_ok=True)
        (evidence / "result" / "probe-result.properties").write_text(
            result_props, encoding="utf-8")
    if spec:
        prepared = tmp / "prepared" / ("p-" + job_id)
        prepared.mkdir(parents=True, exist_ok=True)
        (prepared / "prepared.json").write_text(json.dumps({"taskSpec": spec}))
    db = sqlite3.connect(tmp / "queue.sqlite3")
    db.execute(
        "INSERT INTO jobs(job_id,request_key,prepared_id,digest,state,run_id,"
        "evidence_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?,0,0)",
        (job_id, request_key, "p-" + job_id if spec else "none", "d" * 8, state,
         outcome.get("runId") if outcome else None,
         json.dumps(outcome) if outcome else None))
    db.commit()
    db.close()


class TriageFixture(unittest.TestCase):
    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp = Path(self._tmp.name)
        self.db, self.jobs_root, self.prepared_root = make_root(self.tmp)
        self.rules = triage.load_rules()

    def tearDown(self):
        self._tmp.cleanup()

    def classify(self, job_id: str) -> dict:
        row = triage.iter_rows(self.db, job_ids=[job_id])[0]
        return triage.triage_row(row, self.rules, jobs_root=self.jobs_root,
                                 prepared_root=self.prepared_root,
                                 validation_root=None)

    def test_admission_manifest_mismatch(self):
        add_job(self.tmp, job_id="j-admit", request_key="cov3-backup-5200",
                state="failed", spec="backup:5200",
                outcome={"terminalState": "failed", "validationStatus": "FAIL",
                         "runId": "queue-" + "a" * 32},
                log=ADMISSION_LOG)
        record = self.classify("j-admit")
        self.assertEqual(record["category"], "ADMISSION_STATIC")
        self.assertEqual(record["ruleId"], "admission-manifest-record")
        self.assertEqual(record["signature"]["class"], "java.lang.IllegalArgumentException")
        self.assertIn("requireManifestRecord", record["signature"]["frame"])
        self.assertEqual(record["spec"], "backup:5200")

    def test_infra_edt_dispatch(self):
        add_job(self.tmp, job_id="j-edt", request_key="r-anim-5300",
                state="failed", spec="animation-timeline:5300@assisted",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=EDT_LOG)
        record = self.classify("j-edt")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["signature"]["class"],
                         "dev.turboism.ui.host.EdtDispatchException")
        self.assertEqual(record["signature"]["frame"],
                         "dev.turboism.ui.host.EdtDispatch.dispatch")
        self.assertEqual(record["variant"], "assisted")

    def test_caused_by_chain_feeds_classification(self):
        log = ("java.lang.IllegalStateException: Runtime-owned shell failed to start\n"
               "\tat dev.turboism.preview.PreviewRuntime.start(PreviewRuntime.java:641)\n"
               "caused by dev.turboism.ui.host.EdtDispatchException: embedded-panel EDT "
               "operation was not accepted by the EDT within 1000ms\n"
               "\tat dev.turboism.ui.host.EdtDispatch.dispatch(EdtDispatch.java:163)\n")
        add_job(self.tmp, job_id="j-cause", request_key="r-cause", state="failed",
                spec="theme:5301", outcome={"terminalState": "failed",
                "validationStatus": "FAIL"}, log=log)
        record = self.classify("j-cause")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["signature"]["causes"][0]["class"],
                         "dev.turboism.ui.host.EdtDispatchException")

    def test_probe_drift_inline_marker(self):
        add_job(self.tmp, job_id="j-drift", request_key="cov3-startup-5002",
                state="failed", spec="startup-suppression:5002",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=PROBE_DRIFT_LOG)
        record = self.classify("j-drift")
        self.assertEqual(record["category"], "PROBE_DRIFT")
        self.assertEqual(record["ruleId"], "probe-missing-member")

    def test_capability_boundary_from_result_props(self):
        add_job(self.tmp, job_id="j-cap", request_key="cov3-backup-5100",
                state="failed", spec="backup:5100",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=FREE_MARKERS_LOG +
                "2026-10-08T16:56:26Z [WARN] [dev.turboism.validation.backup] BACKUP_VALIDATION_FAILURE dirty via Parameter.setValue failed: java.lang.IllegalStateException\n",
                result_props="status=FAIL\nfailures=1\nfailure.0=dirty via Parameter.setValue failed: java.lang.IllegalStateException\n")
        record = self.classify("j-cap")
        self.assertEqual(record["category"], "CAPABILITY_BOUNDARY")
        self.assertEqual(record["ruleId"], "capability-parameter-write")

    def test_free_markers_are_not_failure_evidence(self):
        add_job(self.tmp, job_id="j-pass", request_key="r-free-5300",
                state="succeeded", spec="backup:5300",
                outcome={"terminalState": "succeeded", "validationStatus": "PASS"},
                log=FREE_MARKERS_LOG)
        record = self.classify("j-pass")
        self.assertEqual(record["category"], "PASS")
        self.assertFalse(record["evidence"]["failureMarkers"])
        add_job(self.tmp, job_id="j-free-fail", request_key="r-free-fail",
                state="failed", spec="backup:5301",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=FREE_MARKERS_LOG)
        record = self.classify("j-free-fail")
        # FREE markers must never become failure evidence: with nothing else
        # present the job is NO_EVIDENCE, not an UNKNOWN failure.
        self.assertEqual(record["category"], "NO_EVIDENCE")
        self.assertFalse(record["evidence"]["failureMarkers"])

    def test_queue_timeout_is_infra(self):
        add_job(self.tmp, job_id="j-to", request_key="r-x", state="timed_out",
                spec="theme:5200")
        record = self.classify("j-to")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["ruleId"], "infra-queue-timeout")

    def test_unknown_reports_signature(self):
        add_job(self.tmp, job_id="j-unk", request_key="r-unk", state="failed",
                spec="theme:5300",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=UNKNOWN_LOG)
        record = self.classify("j-unk")
        self.assertEqual(record["category"], "UNKNOWN")
        self.assertEqual(record["signature"]["class"], "java.lang.IllegalStateException")
        self.assertIn("RUNTIME_CONFIG_INVALID", record["signature"]["message"])

    def test_retry_plan_skips_specs_with_later_pass(self):
        add_job(self.tmp, job_id="j-1", request_key="k-1", state="failed",
                spec="theme:5200", outcome={"terminalState": "failed",
                "validationStatus": "FAIL"}, log=EDT_LOG)
        add_job(self.tmp, job_id="j-2", request_key="k-2", state="succeeded",
                spec="theme:5200", outcome={"terminalState": "succeeded",
                "validationStatus": "PASS"})
        add_job(self.tmp, job_id="j-3", request_key="k-3", state="failed",
                spec="theme:5201", outcome={"terminalState": "failed",
                "validationStatus": "FAIL"}, log=EDT_LOG)
        rows = triage.iter_rows(self.db, terminal_only=True)
        records = [triage.triage_row(r, self.rules, jobs_root=self.jobs_root,
                                     prepared_root=self.prepared_root,
                                     validation_root=None) for r in rows]
        plan = triage.retry_plan(records, records)
        self.assertEqual(plan["specs"], ["theme:5201"])

    def test_backupnow_artifact_absence_is_capability_boundary(self):
        log = ("2026-10-09T03:22:07Z [INFO] [dev.turboism.validation.backup] "
               "BACKUP_DIRTIED parameter=ParamAngleX after=-30.0\n"
               "2026-10-09T03:23:08Z [WARN] [dev.turboism.validation.backup] "
               "auto-backup backupNow:failed java.lang.IllegalStateException\n"
               "2026-10-09T03:23:08Z [WARN] [dev.turboism.validation.backup] "
               "BACKUP_VALIDATION_FAILURE backupNow failed: IllegalStateException\n"
               "2026-10-09T03:23:08Z [INFO] [dev.turboism.validation.backup] "
               "BACKUP_VALIDATION_RESULT status=FAIL hostVersion=5.2.00 failures=1\n")
        add_job(self.tmp, job_id="j-bn", request_key="cov3-retry3-backup_5200",
                state="failed", spec="backup:5200",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=log)
        record = self.classify("j-bn")
        self.assertEqual(record["category"], "CAPABILITY_BOUNDARY")
        self.assertEqual(record["ruleId"], "capability-backup-artifact-absent")

    def test_standalone_psd_export_saved_unverified_is_capability_boundary(self):
        log = ("2026-10-11T13:01:40Z [INFO] [dev.turboism.validation.textures] "
               "TEXTURE_RELATIONS_SMOKE_RESULT status=FAIL\n")
        props = (
            "status=FAIL\n"
            "export.status=EXPORTED\n"
            "export.diagnostic=PSD_NATIVE_EXPORT;status\\=SAVED_UNVERIFIED;"
            "integrity\\=UNAVAILABLE;readable\\=true;structure\\=false\n"
            "actual=java.lang.IllegalStateException\\: Native readable export "
            "without a runtime-issued edit handle was not obtained\n"
        )
        add_job(self.tmp, job_id="j-psd",
                request_key="refresh-r1-psd-export-observation_5200_assisted",
                state="failed", spec="psd-export-observation:5200@assisted",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=log, result_props=props)
        record = self.classify("j-psd")
        self.assertEqual(record["category"], "CAPABILITY_BOUNDARY")
        self.assertEqual(record["ruleId"], "capability-psd-export-saved-unverified")

    def test_deferred_check_unobserved_is_infra(self):
        # Runner refused before Java produced a runtime log: evidence-only
        # classification beats NO_EVIDENCE.
        add_job(self.tmp, job_id="j-def", request_key="rep-r1-core-acquisition_5304",
                state="failed", spec="core-acquisition:5304@standard",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"})
        (self.jobs_root / "j-def" / "evidence" / "deferred-check.properties").write_text(
            "schemaVersion=1\nmesaGlThreadEffective=1\ndeferredCheck=unknown\n",
            encoding="utf-8")
        (self.jobs_root / "j-def" / "evidence" / "launch-diagnostics.properties").write_text(
            "schemaVersion=1\nwrapperExit=ABSENT\nruntimeLogPresent=0\n",
            encoding="utf-8")
        record = self.classify("j-def")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["ruleId"], "infra-deferred-check-unobserved")
        self.assertEqual(record["evidence"]["properties"]["deferredCheck"], "unknown")

    def test_deferred_check_inactive_is_admission(self):
        add_job(self.tmp, job_id="j-din", request_key="r-deferred-inactive",
                state="failed", spec="core-acquisition:5203@standard",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"})
        (self.jobs_root / "j-din" / "evidence" / "deferred-check.properties").write_text(
            "schemaVersion=1\nmesaGlThreadEffective=1\ndeferredCheck=inactive\n",
            encoding="utf-8")
        record = self.classify("j-din")
        self.assertEqual(record["category"], "ADMISSION_STATIC")
        self.assertEqual(record["ruleId"], "admission-deferred-check-inactive")

    def test_model_await_timeout_variant_is_infra(self):
        log = ("2026-10-10T04:27:39Z [ERROR] [dev.turboism.validation.selection-lag] "
               "SELECTION_LAG_PROBE_FAILED java.lang.IllegalStateException: "
               "No active model with drawables within 240000 ms\n"
               "java.lang.IllegalStateException: No active model with drawables within 240000 ms\n"
               "\tat dev.turboism.validation.selectionlag.SelectionLagProbePlugin.awaitActiveModel(SelectionLagProbePlugin.java:224)\n"
               "\tat java.base/java.lang.Thread.run(Unknown Source)\n")
        add_job(self.tmp, job_id="j-mdl", request_key="rep-r1-selection-lag_5304",
                state="failed", spec="selection-lag:5304@default",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=log)
        record = self.classify("j-mdl")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["ruleId"], "infra-model-ready-timeout")

    def test_session_startup_timeout_is_infra(self):
        log = ("2026-10-10T05:00:52Z [WARN] [dev.turboism.validation.session] SESSION_STARTUP_TIMEOUT\n"
               "2026-10-10T05:00:52Z [INFO] [dev.turboism.validation.session] SESSION_END status=FAIL exitCode=2\n"
               "2026-10-10T05:00:52Z [INFO] [dev.turboism.validation.session] SESSION_RESULT status=FAIL steps=5\n")
        add_job(self.tmp, job_id="j-ssto", request_key="rep-r1-session-standard_5304",
                state="failed", spec="session-standard:5304@standard",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=log)
        record = self.classify("j-ssto")
        self.assertEqual(record["category"], "INFRA_RETRYABLE")
        self.assertEqual(record["ruleId"], "infra-marker-timeout")

    def test_no_evidence_reclaimed(self):
        # No turboism.log anywhere: nothing to attribute.
        add_job(self.tmp, job_id="j-gone", request_key="r-gone", state="failed",
                spec="theme:5100",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"})
        record = self.classify("j-gone")
        self.assertEqual(record["category"], "NO_EVIDENCE")
        self.assertIn("reclaimed", record["notes"][0])

    def test_no_evidence_truncated_log(self):
        # Log exists but ends mid-bootstrap with no verdict signal.
        add_job(self.tmp, job_id="j-trunc", request_key="r-trunc", state="failed",
                spec="backup:5201",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=FREE_MARKERS_LOG +
                "2026-10-09T03:24:03Z [INFO] [bootstrap] hook installed\n")
        record = self.classify("j-trunc")
        self.assertEqual(record["category"], "NO_EVIDENCE")
        self.assertIn("verdict signal", record["notes"][0])

    def test_verdict_pass_then_fail_is_unknown_not_no_evidence(self):
        add_job(self.tmp, job_id="j-pvf", request_key="r-pvf", state="failed",
                spec="backup:5300",
                outcome={"terminalState": "failed", "validationStatus": "FAIL",
                         "details": {"postContainmentChecks": {
                             "terminalResult": {"mode": "file", "passed": True,
                                                "failureMarkers": []}}}},
                log="2026-10-09T03:36:07Z [INFO] [dev.turboism.validation.backup] "
                    "BACKUP_VALIDATION_RESULT status=PASS failures=0\n")
        record = self.classify("j-pvf")
        self.assertEqual(record["category"], "UNKNOWN")
        self.assertEqual(record["ruleId"], "verdict-pass-then-fail")

    def test_session_steps_map_back_to_task_specs(self):
        add_job(self.tmp, job_id="j-sess", request_key="cov3-session-5304",
                state="succeeded", spec="session-standard:5304@standard",
                outcome={"terminalState": "succeeded", "validationStatus": "PASS"})
        result_dir = self.tmp / "jobs" / "j-sess" / "evidence" / "result"
        result_dir.mkdir(parents=True, exist_ok=True)
        (result_dir / "session-result.properties").write_text(
            "status=PASS\n"
            "steps=2\n"
            "step.0.name=texture-relations\n"
            "step.0.spec=texture-relations\\:5304@standard\n"
            "step.0.status=PASS\n"
            "step.1.name=core-owned-moc\n"
            "step.1.spec=core-owned-moc\\:5304\n"
            "step.1.status=NOT_RUN\n",
            encoding="utf-8")
        row = triage.iter_rows(self.db, job_ids=["j-sess"])[0]
        steps = triage.session_steps(row, jobs_root=self.jobs_root,
                                     prepared_root=self.prepared_root,
                                     validation_root=None)
        self.assertEqual([s["task"] for s in steps],
                         ["texture-relations", "core-owned-moc"])
        self.assertEqual(steps[0]["version"], "5304")
        self.assertEqual(steps[0]["variant"], "standard")
        self.assertIsNone(steps[1]["variant"])
        self.assertEqual(steps[1]["status"], "NOT_RUN")

    def test_non_session_job_has_no_steps(self):
        add_job(self.tmp, job_id="j-plain", request_key="r-plain",
                state="succeeded", spec="backup:5200",
                outcome={"terminalState": "succeeded", "validationStatus": "PASS"})
        row = triage.iter_rows(self.db, job_ids=["j-plain"])[0]
        self.assertEqual(triage.session_steps(
            row, jobs_root=self.jobs_root, prepared_root=self.prepared_root,
            validation_root=None), [])

    def _records(self) -> list:
        rows = triage.iter_rows(self.db, terminal_only=True)
        return [triage.triage_row(row, self.rules, jobs_root=self.jobs_root,
                                  prepared_root=self.prepared_root,
                                  validation_root=None) for row in rows]

    def test_systemic_signature_across_versions(self):
        for index, version in enumerate(("5000", "5100", "5101", "5200")):
            add_job(self.tmp, job_id=f"j-sys-{index}",
                    request_key=f"r-cmv-{version}", state="failed",
                    spec=f"clipmask-viewer:{version}@assisted",
                    outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                    log=MODEL_WAIT_LOG)
        records = self._records()
        triage.apply_systemic(records, records)
        for record in records:
            self.assertEqual(record["category"], "SYSTEMIC")
            self.assertEqual(record["underlying"], "INFRA_RETRYABLE")
        plan = triage.retry_plan(records, records)
        self.assertEqual(plan["specs"], [])

    def test_systemic_needs_no_task_pass(self):
        for index, version in enumerate(("5000", "5100", "5101", "5200")):
            add_job(self.tmp, job_id=f"j-sys-{index}",
                    request_key=f"r-cmv-{version}", state="failed",
                    spec=f"clipmask-viewer:{version}@assisted",
                    outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                    log=MODEL_WAIT_LOG)
        add_job(self.tmp, job_id="j-pass-5302", request_key="r-cmv-5302",
                state="succeeded", spec="clipmask-viewer:5302@assisted",
                outcome={"terminalState": "succeeded", "validationStatus": "PASS"})
        records = self._records()
        triage.apply_systemic(records, records)
        self.assertTrue(all(record["category"] != "SYSTEMIC" for record in records))
        plan = triage.retry_plan(records, records)
        self.assertNotEqual(plan["specs"], [])

    def test_systemic_threshold_not_met(self):
        for index, version in enumerate(("5000", "5100", "5101")):
            add_job(self.tmp, job_id=f"j-few-{index}",
                    request_key=f"r-few-{version}", state="failed",
                    spec=f"clipmask-viewer:{version}@assisted",
                    outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                    log=MODEL_WAIT_LOG)
        records = self._records()
        triage.apply_systemic(records, records)
        self.assertTrue(all(record["category"] == "INFRA_RETRYABLE"
                            for record in records))
        plan = triage.retry_plan(records, records)
        self.assertEqual(len(plan["specs"]), 3)

    def test_systemic_requires_same_signature(self):
        for index, version in enumerate(("5000", "5100", "5101")):
            add_job(self.tmp, job_id=f"j-mix-{index}",
                    request_key=f"r-mix-{version}", state="failed",
                    spec=f"clipmask-viewer:{version}@assisted",
                    outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                    log=MODEL_WAIT_LOG)
        add_job(self.tmp, job_id="j-mix-3", request_key="r-mix-5200",
                state="failed", spec="clipmask-viewer:5200@assisted",
                outcome={"terminalState": "failed", "validationStatus": "FAIL"},
                log=UNKNOWN_LOG)
        records = self._records()
        triage.apply_systemic(records, records)
        categories = {record["version"]: record["category"] for record in records}
        self.assertEqual(categories["5200"], "UNKNOWN")
        self.assertTrue(all(categories[v] == "INFRA_RETRYABLE"
                            for v in ("5000", "5100", "5101")))

    def test_clipmask_blocked_is_fixture_unsuitable(self):
        add_job(self.tmp, job_id="j-blk", request_key="r-cmv-5000",
                state="failed", spec="clipmask-viewer:5000@assisted",
                outcome={"terminalState": "failed", "validationStatus": "BLOCKED"},
                log=CLIPMASK_BLOCKED_LOG)
        record = self.classify("j-blk")
        self.assertEqual(record["category"], "FIXTURE_UNSUITABLE")
        self.assertEqual(record["ruleId"], "fixture-no-clip-mask-records")

    def test_normalize_message(self):
        text = ("No active model observed within 240000 ms at "
                "Z:\\home\\rain\\x\\queue-0123456789abcdef0123456789abcdef "
                "run 41f72ac0-bd7f-4431-8011-be1ee7131e8a")
        normalized = triage.normalize_message(text)
        self.assertNotIn("240000", normalized)
        self.assertNotIn("rain", normalized)
        self.assertIn("<uuid>", normalized)
        self.assertIn("<path>", normalized)

    def test_rules_file_wellformed(self):
        rules = triage.load_rules()
        ids = set()
        for rule in rules["rules"]:
            self.assertIn(rule["category"], triage.CATEGORIES)
            self.assertIsInstance(rule["when"], dict)
            self.assertNotIn(rule["id"], ids)
            ids.add(rule["id"])

    def test_inline_exception_mention_is_not_a_block(self):
        log = ('2026-01-01T00:00:00Z [WARN] [bootstrap] Turboism hook disabled '
               'safely: java.lang.IllegalArgumentException\n'
               '2026-01-01T00:00:01Z [INFO] [bootstrap] done\n')
        self.assertIsNone(triage.extract_signature(log))


if __name__ == "__main__":
    unittest.main()
