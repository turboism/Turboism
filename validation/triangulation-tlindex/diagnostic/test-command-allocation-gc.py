#!/usr/bin/env python3
"""Regression checks for command-window attribution and GC interval clipping."""
import csv
import datetime
import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('analyze-command-allocation-gc.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def event(kind, millis, **values):
    stamp = datetime.datetime.fromtimestamp(millis / 1000, datetime.timezone.utc).isoformat()
    return {'type': kind, 'values': dict(startTime=stamp, **values)}


class AttributionTest(unittest.TestCase):
    def test_duration_and_timezone(self):
        self.assertEqual(3723.5, audit.seconds('PT1H2M3.5S'))
        self.assertEqual(0, audit.seconds('PT0S'))
        self.assertEqual(audit.epoch('2026-10-03T08:00:00+08:00'),
                         audit.epoch('2026-10-03T00:00:00+00:00'))
        with self.assertRaises(ValueError):
            audit.seconds('2ms')

    def test_window_attribution_and_pause_crossing_boundary(self):
        window = dict(cycle=1, startEpochMillis=1000, endEpochMillis=2000)
        frame = {'method': {'type': {'name': 'com/live2d/graphics3d/editableMesh/triangulation/h'},
                            'name': 'd', 'descriptor': '()V'}}
        samples = [event('jdk.ObjectAllocationSample', t, weight=weight,
                         objectClass={'name': 'Vector'}, stackTrace={'frames': [frame]})
                   for t, weight in ((999, 500), (1000, 10), (1500, 20), (2000, 30), (2001, 800))]
        samples += [event('jdk.GCPhasePause', 900, duration='PT0.2S'),
                    event('jdk.GCPhasePause', 1900, duration='PT0.3S'),
                    event('jdk.GCPhasePause', 2500, duration='PT1S'),
                    event('jdk.GCHeapSummary', 500, gcId=1, when='After GC', heapUsed=42,
                          heapSpace={'committedSize': 100})]
        result = audit.summarize(samples, [window])[0]
        self.assertEqual(3, result['eventCounts']['jdk.ObjectAllocationSample'])
        self.assertEqual(60, result['allocationSampleWeightBytes']['triangulationStack'])
        self.assertEqual({'Vector': 60}, result['triangulationAllocationClasses'])
        self.assertAlmostEqual(0.2, result['gcPauseOverlapSeconds'])
        self.assertEqual(500, result['nearestPriorHeapObservation']['ageMillis'])
        self.assertEqual([], result['heapObservations'])

    def test_missing_stack_and_negative_weight(self):
        window = dict(cycle=1, startEpochMillis=0, endEpochMillis=1000)
        sample = event('jdk.ObjectAllocationSample', 100, weight=42)
        result = audit.summarize([sample], [window])[0]
        self.assertEqual({'all': 42, 'emptyStack': 42}, result['allocationSampleWeightBytes'])
        self.assertIsNone(result['nearestPriorHeapObservation'])
        sample['values']['weight'] = -1
        with self.assertRaises(ValueError):
            audit.summarize([sample], [window])

    def test_duplicate_and_overlapping_markers_refused(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'markers.tsv'
            rows = [dict(phase=phase, operation=cycle, epochMillis=cycle * 1000 + offset)
                    for cycle in (1, 2, 3)
                    for phase, offset in (('auto-connect-start', 0), ('auto-connect-returned', 500))]

            def write():
                with path.open('w') as stream:
                    writer = csv.DictWriter(stream, fieldnames=['phase', 'operation', 'epochMillis'], delimiter='\t')
                    writer.writeheader()
                    writer.writerows(rows)

            write()
            self.assertEqual(3, len(audit.windows(path)))
            rows.append(rows[0])
            write()
            with self.assertRaises(ValueError):
                audit.windows(path)
            rows.pop()
            rows[2]['epochMillis'] = 1400
            write()
            with self.assertRaises(ValueError):
                audit.windows(path)


if __name__ == '__main__':
    unittest.main()
