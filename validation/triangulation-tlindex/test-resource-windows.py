"""Small deterministic checks for measurement accounting, without launching a host."""
import csv
import datetime
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("windows", Path(__file__).with_name("analyze-resource-windows.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class WindowAccountingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.markers = Path(self.temp.name) / "markers.tsv"
        self.samples = Path(self.temp.name) / "samples.jsonl"
        self.rows = []
        seconds = 0
        for phase, operation, duration in [("baseline", 0, 30)] + [
                item for n in range(1, 4) for item in (("operation", n, 5), ("retained", n, 30))]:
            for suffix, elapsed in (("start", seconds), ("end", seconds + duration)):
                # Distinct monotonic boundaries even when wall-clock milliseconds coincide.
                self.rows.append([phase + "-" + suffix, operation, 100000 + elapsed * 1000,
                                  1000000000 + elapsed * 1000000000 + len(self.rows), 1024])
            seconds += duration
        self.observations = []
        for t in range(seconds + 1):
            records = [{"pid": 1, "startTicks": "10", "role": "host-java",
                        "userTicks": t * 80, "systemTicks": t * 20,
                        "rssBytes": 1000 + t, "pssBytes": 800 + t}]
            if 10 <= t <= 20:
                records.append({"pid": 2, "startTicks": "20", "role": "task-auxiliary",
                                "userTicks": (t - 10) * 100, "systemTicks": 0,
                                "rssBytes": 50, "pssBytes": 40})
            self.observations.append({"epochMs": 100000 + t * 1000, "cgroup": "/test",
                                      "cgroupInode": 10, "ticksPerSecond": 100, "records": records})

    def analyze(self, jfr=None):
        with self.markers.open("w") as stream:
            writer = csv.writer(stream, delimiter="\t")
            writer.writerow(["phase", "operation", "epochMillis", "monotonicNanos", "heapUsedBytes"])
            writer.writerows(self.rows)
        self.samples.write_text("".join(json.dumps(r) + "\n" for r in self.observations))
        return module.analyze(self.markers, self.samples, jfr)

    def test_cpu_and_retention_accounting(self):
        report = self.analyze()
        baseline = report["windows"][0]
        self.assertEqual(baseline["roles"]["host-java"]["cpuSeconds"], 30)
        self.assertEqual(baseline["roles"]["host-java"]["averageCpuPercentOneCore"], 100)
        self.assertEqual(baseline["roles"]["task-auxiliary"]["cpuSeconds"], 10)
        self.assertEqual(baseline["roles"]["task-auxiliary"]["identityTransitions"], 2)
        self.assertEqual(report["lastMinusFirstRetainedJavaMedianRssBytes"], 70)
        self.assertEqual(report["performanceAcceptance"], "NOT_DECIDED")

    def test_missing_pss_is_unknown_not_zero(self):
        del self.observations[5]["records"][0]["pssBytes"]
        java = self.analyze()["windows"][0]["roles"]["host-java"]
        self.assertIsNone(java["pssPeakBytes"])
        self.assertEqual(java["pssMissingRecords"], 1)

    def test_pid_reuse_rejected(self):
        self.observations[5]["records"][0]["startTicks"] = "11"
        with self.assertRaisesRegex(ValueError, "Java process identity"):
            self.analyze()

    def test_short_observation_rejected(self):
        self.rows[1][3] -= 1000000000
        with self.assertRaisesRegex(ValueError, "shorter than thirty"):
            self.analyze()

    def test_missing_operation_rejected(self):
        self.rows.pop()
        with self.assertRaisesRegex(ValueError, "incomplete resource"):
            self.analyze()

    def test_missing_java_sample_rejected(self):
        self.observations[5]["records"] = []
        with self.assertRaisesRegex(ValueError, "missing Java sample"):
            self.analyze()

    def test_jfr_target_is_bound_to_each_operation(self):
        jfr = Path(self.temp.name) / "execution.json"
        # One target sample in operation 1, one outside all driver windows.
        events = []
        for epoch in (132, 1000):
            events.append({"type": "jdk.ExecutionSample", "values": {
                "startTime": datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).isoformat(),
                "stackTrace": {"frames": [{"method": {"type": {"name": name}}} for name in (
                    "dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex",
                    "com/live2d/graphics3d/editableMesh/triangulation/TriangleList")]}}})
        jfr.write_text(json.dumps({"recording": {"events": events}}))
        operations = [w for w in self.analyze(jfr)["windows"] if w["phase"] == "operation"]
        self.assertEqual(operations[0]["targetExecution"], {
            "status": "OBSERVED", "triangulationSamples": 1, "productionIndexSamples": 1,
            "unknownFrames": 0, "samplesWithUnknownFrames": 0})
        self.assertEqual([w["targetExecution"]["status"] for w in operations[1:]],
                         ["NOT_OBSERVED", "NOT_OBSERVED"])

    def test_unknown_method_frames_are_reported_without_inventing_execution(self):
        jfr = Path(self.temp.name) / "unknown.json"
        events = [{"type": "jdk.ExecutionSample", "values": {
            "startTime": datetime.datetime.fromtimestamp(epoch, datetime.timezone.utc).isoformat(),
            "stackTrace": {"frames": frames}}} for epoch, frames in (
                (132, [{"method": None}, {"method": {"type": {"name":
                    "com/live2d/graphics3d/editableMesh/triangulation/h"}}}]),
                (167, [{"method": None}, {"method": {"type": None}}]))]
        jfr.write_text(json.dumps({"recording": {"events": events}}))
        operations = [w["targetExecution"] for w in self.analyze(jfr)["windows"]
                      if w["phase"] == "operation"]
        self.assertEqual(operations[0]["status"], "OBSERVED")
        self.assertEqual(operations[0]["unknownFrames"], 1)
        self.assertEqual(operations[1]["status"], "NOT_OBSERVED")
        self.assertEqual(operations[1]["unknownFrames"], 2)
        self.assertEqual(operations[1]["samplesWithUnknownFrames"], 1)

    def test_streaming_jfr_across_chunks(self):
        path = Path(self.temp.name) / "large.json"
        events = [{"text": "长字符串" * 40000}, {"type": "second"}]
        path.write_text(json.dumps({"recording": {"events": events}}, ensure_ascii=False))
        self.assertEqual(list(module.jfr_events(path)), events)

    def test_streaming_jfr_rejects_truncation_and_trailing_data(self):
        path = Path(self.temp.name) / "invalid.json"
        for value in ('{"recording":{"events":[{"unfinished":',
                      '{"recording":{"events":[]}} trailing'):
            path.write_text(value)
            with self.assertRaises(ValueError):
                list(module.jfr_events(path))


if __name__ == "__main__":
    unittest.main()
