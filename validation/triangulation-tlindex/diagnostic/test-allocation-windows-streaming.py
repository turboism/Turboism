#!/usr/bin/env python3
import copy
import runpy
import tracemalloc
import unittest
from pathlib import Path

streaming = runpy.run_path(str(Path(__file__).with_name('analyze-allocation-windows-streaming.py')))
old = streaming['HELPERS']['summarize']
windows = [dict(startEpochMillis=1000, endEpochMillis=2000),
           dict(startEpochMillis=2000, endEpochMillis=3000)]


def allocation(weight=23):
    return {'type': 'jdk.ObjectAllocationSample', 'values': {
        'startTime': '1970-01-01T00:00:02+00:00', 'weight': weight,
        'objectClass': {'name': 'java/util/ArrayList'},
        'stackTrace': {'frames': [
            {'method': {'type': {'name': 'dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex'},
                        'name': 'put', 'descriptor': '()V'}},
            {'method': {'type': {'name': 'com/live2d/graphics3d/editableMesh/triangulation/h'},
                        'name': 'a', 'descriptor': '()V'}}]}}}


class StreamingTests(unittest.TestCase):
    def test_matches_original_with_boundaries_empty_stack_heap_and_gc(self):
        empty = allocation(11)
        empty['values']['stackTrace'] = None
        events = [allocation(), empty,
                  {'type': 'jdk.GCHeapSummary', 'values': {
                      'startTime': '1970-01-01T00:00:00.5+00:00', 'gcId': 1,
                      'when': 'After GC', 'heapUsed': 10, 'heapSpace': {'committedSize': 20}}},
                  {'type': 'jdk.GCPhasePause', 'values': {
                      'startTime': '1970-01-01T00:00:01.9+00:00', 'duration': 'PT0.2S'}}]
        self.assertEqual(streaming['summarize'](iter(events), windows), old(events, windows))

    def test_negative_weight_refused(self):
        with self.assertRaises(ValueError):
            streaming['summarize'](iter([allocation(-1)]), windows)

    def test_execution_export_refused_instead_of_accumulating_stacks(self):
        with self.assertRaises(ValueError):
            streaming['summarize'](iter([{'type': 'jdk.ExecutionSample', 'values': {}}]), windows)

    def test_large_one_pass_source_does_not_retain_event_objects(self):
        # Fresh nested dicts force a list-loading implementation to retain memory.
        sample = allocation()
        tracemalloc.start()
        try:
            result = streaming['summarize']((copy.deepcopy(sample) for _ in range(30000)), windows)
            _, peak = tracemalloc.get_traced_memory()
        finally:
            tracemalloc.stop()
        self.assertEqual(result[0]['eventCounts']['jdk.ObjectAllocationSample'], 30000)
        self.assertEqual(result[1]['allocationSampleWeightBytes']['all'], 30000*23)
        self.assertLess(peak, 4*1024*1024, 'fresh allocation events must not accumulate')


if __name__ == '__main__':
    unittest.main()
