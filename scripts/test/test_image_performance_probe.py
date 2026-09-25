"""Opt-in image diagnostic reports: strict admission without changing v1 evidence."""
import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location(
    "performance_probe_verifier",
    Path(__file__).resolve().parents[1] / "preview" / "verify-cubism-performance-probe.py",
)
VERIFIER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(VERIFIER)


class ImagePerformanceProbeTest(unittest.TestCase):
    def report(self):
        names = VERIFIER.METRIC_NAMES + VERIFIER.IMAGE_METRICS
        return {
            "format": VERIFIER.REPORT_FORMAT,
            "schemaVersion": 2,
            "cubismVersion": VERIFIER.CUBISM_VERSION,
            "artifactSha256": VERIFIER.ARTIFACT_SHA256,
            "agentSha256": "a" * 64,
            "fixtureSha256": "b" * 64,
            "scenario": "images",
            "capture": {"startEpochMs": 1_700_000_000_000, "endEpochMs": 1_700_000_030_000,
                        "dropped": 0, "failures": 0},
            "measurement": dict(VERIFIER.IMAGE_MEASUREMENT),
            "metrics": {name: {"calls": 1, "sampled": 1, "totalNanos": 10, "maxNanos": 10,
                               "latency": {"samples": 1, "p50UpperBoundNanos": 15,
                                           "p95UpperBoundNanos": 15, "p99UpperBoundNanos": 15}}
                        for name in names},
            "writtenAt": "2026-09-05T10:00:00Z",
        }

    def validate(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"
            path.write_text(json.dumps(report), encoding="utf-8")
            VERIFIER.validate_report(path, "images", "a" * 64, "b" * 64, {
                "scenario_start_epoch_ms": 1_700_000_000_000,
                "scenario_end_epoch_ms": 1_700_000_030_000,
            })

    def test_legacy_evidence_validation_remains_compatible(self):
        VERIFIER.self_test()

    def test_accepts_complete_image_evidence(self):
        self.validate(self.report())

    def test_rejects_wrong_schema_missing_stage_and_unmeasured_claims(self):
        wrong_schema = self.report()
        wrong_schema["schemaVersion"] = 1
        missing = self.report()
        del missing["metrics"]["imageDecode"]
        gpu = self.report()
        gpu["measurement"]["gpuTime"] = "measured"
        other_host = self.report()
        other_host["cubismVersion"] = "5.3.03"
        for report in (wrong_schema, missing, gpu, other_host):
            with self.subTest(report=report), self.assertRaises(VERIFIER.AdmissionError):
                self.validate(report)

    def test_rejects_incomplete_and_invalid_percentile_samples(self):
        for field, value in (("samples", 0), ("samples", True),
                             ("p95UpperBoundNanos", 16), ("p99UpperBoundNanos", 31),
                             ("p50UpperBoundNanos", -1)):
            report = self.report()
            report["metrics"]["imageDecode"]["latency"][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(VERIFIER.AdmissionError):
                self.validate(report)

    def test_rejects_calls_without_the_required_timing_samples(self):
        report = self.report()
        report["metrics"]["imageDecode"] = {
            "calls": 1, "sampled": 0, "totalNanos": 0, "maxNanos": 0,
            "latency": dict.fromkeys(VERIFIER.LATENCY_FIELDS, 0),
        }
        with self.assertRaises(VERIFIER.AdmissionError):
            self.validate(report)

    def test_rejects_capture_with_no_image_work(self):
        report = self.report()
        for name in VERIFIER.IMAGE_METRICS:
            report["metrics"][name] = {
                "calls": 0, "sampled": 0, "totalNanos": 0, "maxNanos": 0,
                "latency": dict.fromkeys(VERIFIER.LATENCY_FIELDS, 0),
            }
        with self.assertRaises(VERIFIER.AdmissionError):
            self.validate(report)

    def test_requires_image_selector_restoration_evidence(self):
        selectors = VERIFIER.SELECTORS + VERIFIER.IMAGE_SELECTORS
        manifest = {
            "format": VERIFIER.ROLLBACK_FORMAT, "schemaVersion": 1,
            "cubismVersion": VERIFIER.CUBISM_VERSION, "artifactSha256": VERIFIER.ARTIFACT_SHA256,
            "runId": "images-01", "variant": "on", "scenario": "images",
            "agentSha256": "a" * 64, "fixtureSha256": "b" * 64,
            "owners": [{"class": owner, "beforeSha256": "1" * 64,
                        "instrumentedSha256": "2" * 64, "afterSha256": "1" * 64,
                        "restorationMatches": 1} for owner in sorted({entry[0] for entry in selectors})],
            "selectors": [{"owner": owner, "method": method, "descriptor": descriptor,
                           "metric": metric, "matches": 1} for owner, method, descriptor, metric in selectors],
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "rollback.json"
            path.write_text(json.dumps(manifest), encoding="utf-8")
            VERIFIER.validate_rollback_manifest(path, "images-01", "on", "images", "a" * 64, "b" * 64)
            missing = copy.deepcopy(manifest)
            missing["selectors"].pop()
            path.write_text(json.dumps(missing), encoding="utf-8")
            with self.assertRaises(VERIFIER.AdmissionError):
                VERIFIER.validate_rollback_manifest(path, "images-01", "on", "images", "a" * 64, "b" * 64)


if __name__ == "__main__":
    unittest.main()
