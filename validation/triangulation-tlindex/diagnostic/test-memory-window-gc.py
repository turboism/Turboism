#!/usr/bin/env python3
import csv
import datetime
import json
from pathlib import Path
import runpy
import tempfile
import subprocess
import sys
import unittest

api = runpy.run_path(str(Path(__file__).with_name('analyze-memory-window-gc.py')))


def event(kind, millis, **values):
    timestamp = datetime.datetime.fromtimestamp(millis/1000, datetime.timezone.utc).isoformat()
    return {'type': kind, 'values': {'startTime': timestamp, **values}}


def heap(millis, used=10, committed=20):
    return event('jdk.GCHeapSummary', millis, gcId=1, when='After GC', heapUsed=used,
                 heapSpace={'committedSize': committed})


class MemoryWindowTests(unittest.TestCase):
    def test_shared_boundary_counted_once_and_prior_age_preserved(self):
        bounds = [dict(startEpochMillis=1000, endEpochMillis=2000),
                  dict(startEpochMillis=2000, endEpochMillis=3000)]
        result = api['summarize']([heap(500), heap(2000), heap(3500)], bounds)
        self.assertEqual(result[0]['heapObservations'], [])
        self.assertEqual(result[0]['nearestPriorHeapObservation']['ageMillis'], 500)
        self.assertEqual(result[0]['nearestFollowingHeapObservation']['delayMillis'], 0)
        self.assertEqual(len(result[1]['heapObservations']), 1)
        self.assertEqual(result[1]['nearestPriorHeapObservation']['ageMillis'], 1500)

    def test_gc_crossing_boundary_is_clipped_and_duration_is_separate(self):
        events = [event('jdk.GCPhasePause', 900, duration='PT0.3S', gcId=1),
                  event('jdk.GarbageCollection', 900, duration='PT0.3S', gcId=1,
                        name='G1 Young', cause='Allocation Failure')]
        r = api['summarize'](events, [dict(startEpochMillis=1000, endEpochMillis=1100)])[0]
        self.assertAlmostEqual(r['gcPauseOverlapSeconds'], .1)
        self.assertEqual(r['gcEventsOverlappingOrStartingInside'][0]['durationSeconds'], .3)
        self.assertFalse(r['gcEventsOverlappingOrStartingInside'][0]['startsInside'])
        self.assertIsNone(r['nearestPriorHeapObservation'])

    def test_missing_observations_remain_missing(self):
        r = api['summarize']([], [dict(startEpochMillis=1, endEpochMillis=2)])[0]
        self.assertIsNone(r['nearestPriorHeapObservation'])
        self.assertIsNone(r['nearestFollowingHeapObservation'])

    def test_invalid_data_refused(self):
        for events in ([heap(1, used=-1)], [heap(1, used=21)], [event('jdk.ObjectAllocationSample', 1)]):
            with self.assertRaises(ValueError):
                api['summarize'](events, [dict(startEpochMillis=1, endEpochMillis=2)])

    def test_marker_windows_and_duplicates(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory)/'markers.tsv'
            rows = [('mesh-baseline-start', 0), ('mesh-baseline-end', 0)]
            for cycle in (1, 2, 3):
                rows += [(phase, cycle) for phase in ('auto-connect-start', 'auto-connect-returned',
                                                     'mesh-retained-start', 'mesh-retained-end')]
            def write(extra=False):
                with p.open('w') as stream:
                    writer = csv.writer(stream, delimiter='\t')
                    writer.writerow(['phase', 'operation', 'epochMillis'])
                    for i, (phase, cycle) in enumerate(rows):
                        writer.writerow([phase, cycle, 1000+i*1000])
                    if extra:
                        writer.writerow(['mesh-retained-end', 3, 15000])
            write()
            self.assertEqual(len(api['windows'](p)), 10)
            write(True)
            with self.assertRaises(ValueError):
                api['windows'](p)

    def test_cli_standard_envelope_and_refuses_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            markers, events, output = (root/n for n in ('markers.tsv', 'events.json', 'review.json'))
            rows = [('mesh-baseline-start', 0), ('mesh-baseline-end', 0)]
            for cycle in (1, 2, 3):
                rows += [(phase, cycle) for phase in ('auto-connect-start', 'auto-connect-returned',
                                                     'mesh-retained-start', 'mesh-retained-end')]
            with markers.open('w') as stream:
                writer = csv.writer(stream, delimiter='\t')
                writer.writerow(['phase', 'operation', 'epochMillis'])
                for i, (phase, cycle) in enumerate(rows):
                    writer.writerow([phase, cycle, 1000+i*1000])
            events.write_text(json.dumps({'recording': {'events': [heap(500)]}}))
            command = [sys.executable, str(Path(__file__).with_name('analyze-memory-window-gc.py')),
                       '--markers', str(markers), '--events', str(events), '--output', str(output)]
            first = subprocess.run(command, capture_output=True, text=True)
            self.assertEqual(first.returncode, 0, first.stderr)
            original = output.read_bytes()
            self.assertEqual(len(json.loads(original)['windows']), 10)
            second = subprocess.run(command, capture_output=True, text=True)
            self.assertNotEqual(second.returncode, 0)
            self.assertEqual(output.read_bytes(), original)


if __name__ == '__main__':
    unittest.main()
