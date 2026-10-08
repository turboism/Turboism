"""Protect caller attribution from enclosing-frame and overloaded-method errors."""
import datetime
import importlib.util
import io
import json
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("allocation_sites", Path(__file__).with_name(
    "analyze-edge-allocation-sites.py"))
sites = importlib.util.module_from_spec(spec)
spec.loader.exec_module(sites)
EPOCH = 1767225600000


def frame(owner, name="c", descriptor="()V", bci=301):
    return {"method": {"type": {"name": owner}, "name": name, "descriptor": descriptor},
            "bytecodeIndex": bci}


def event(weight, stack=(), owner=sites.EDGE, offset=0):
    time = datetime.datetime.fromtimestamp((EPOCH + offset) / 1000, datetime.timezone.utc)
    return {"type": "jdk.ObjectAllocationSample", "values": {
        "startTime": time.isoformat(), "weight": weight, "objectClass": {"name": owner},
        "stackTrace": {"frames": list(stack)}}}


class AttributionTest(unittest.TestCase):
    def analyze(self, events, end=EPOCH):
        raw = io.StringIO()
        report = sites.analyze(events, EPOCH, end, raw)
        return report, [json.loads(line) for line in raw.getvalue().splitlines()]

    def test_direct_allocations_do_not_include_delegated_allocations(self):
        r, raw = self.analyze([
            event(10, [frame(sites.EDGE, "<init>", "(II)V", 3), frame(sites.HOST)]),
            event(20, [frame(sites.HOST, "a", "(I)V", 9), frame(sites.HOST)]),
            event(30), event(40, [frame(sites.HOST)], sites.EDGE + "Other")])
        self.assertEqual(r["weights"]["edgeDirectHostCEstimatedWeightBytes"], 10)
        self.assertEqual(r["weights"]["edgeInclusiveHostCEstimatedWeightBytes"], 30)
        self.assertEqual(r["weights"]["edgeUnresolvedCallerEstimatedWeightBytes"], 30)
        self.assertEqual(r["weights"]["edgeEstimatedWeightBytes"], 60)
        self.assertEqual(r["weights"]["allClassesEstimatedWeightBytes"], 100)
        self.assertEqual(len(raw), 3)

    def test_overloaded_host_method_is_not_selected_as_c_void(self):
        r, _ = self.analyze([event(10, [frame(sites.HOST, descriptor="(I)V")])])
        self.assertEqual(r["edgeDirectHostCWeightPercent"], 0)
        self.assertNotIn("edgeEventsWithInclusiveHostC", r["counts"])

    def test_window_includes_both_boundaries_and_excludes_neighbors(self):
        r, raw = self.analyze([event(1, offset=-1), event(2), event(4, offset=10),
                               event(8, offset=11)], EPOCH + 10)
        self.assertEqual(r["counts"]["allAllocationEvents"], 4)
        self.assertEqual(r["counts"]["firstAllocationEvents"], 2)
        self.assertEqual(r["weights"]["edgeEstimatedWeightBytes"], 6)
        self.assertEqual([v["estimatedWeightBytes"] for v in raw], [2, 4])

    def test_internal_names_normalize_and_non_constructor_edge_frames_remain(self):
        r, _ = self.analyze([event(10, [frame(sites.EDGE.replace(".", "/"), "make", "()V")],
                                   sites.EDGE.replace(".", "/"))])
        self.assertEqual(r["allocationCallerSites"][0]["frame"][:2], [sites.EDGE, "make"])

    def test_invalid_weights_and_wrong_event_types_reject(self):
        for weight in (-1, 1.5, True):
            with self.subTest(weight=weight), self.assertRaises(ValueError):
                self.analyze([event(weight)])
        wrong = event(1)
        wrong["type"] = "jdk.ExecutionSample"
        with self.assertRaises(ValueError):
            self.analyze([wrong])


if __name__ == "__main__":
    unittest.main()
