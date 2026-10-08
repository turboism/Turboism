#!/usr/bin/env python3
"""CPU interval attribution checks, including delayed batch timestamps and refusal cases."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('analyze-command-cpu-boundaries.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def samples():
    return [{'epochMs': i * 1000, 'ticksPerSecond': 100, 'cgroup': 'owned', 'cgroupInode': 42,
             'records': [{'role': 'host-java', 'pid': 123, 'startTicks': '456',
                          'userTicks': i * 100, 'systemTicks': 0}]} for i in range(10)]


def command():
    return dict(cycle=1, startEpochMillis=1500, endEpochMillis=5500)


class CpuBoundaryTest(unittest.TestCase):
    def test_delayed_batch_needs_extra_outer_observation(self):
        result = audit.summarize(samples(), [command()])
        self.assertEqual(3, result['originalInsideCpuSeconds'])
        self.assertEqual(2, result['conditionalLowerCpuSeconds'])
        self.assertEqual(6, result['conditionalUpperCpuSeconds'])
        self.assertEqual(1000, result['omittedWallMillis'])
        self.assertEqual([1000, 3000, 5000, 7000], result['commands'][0]['outerBatchEpochs'])

    def test_exact_timestamp_still_requires_next_batch(self):
        window = dict(cycle=1, startEpochMillis=2000, endEpochMillis=6000)
        result = audit.summarize(samples(), [window])
        self.assertEqual(4, result['originalInsideCpuSeconds'])
        self.assertEqual(3, result['conditionalLowerCpuSeconds'])
        self.assertEqual(5, result['conditionalUpperCpuSeconds'])
        self.assertEqual(0, result['omittedWallMillis'])

    def test_counter_identity_and_clock_failures(self):
        for mutation in ('identity', 'frequency', 'group', 'user', 'system', 'epoch', 'ambiguous'):
            with self.subTest(mutation=mutation):
                rows = samples()
                if mutation == 'identity':
                    rows[5]['records'][0]['startTicks'] = 'reused'
                elif mutation == 'frequency':
                    rows[5]['ticksPerSecond'] = 200
                elif mutation == 'group':
                    rows[5]['cgroupInode'] = 43
                elif mutation == 'user':
                    rows[5]['records'][0]['userTicks'] = 0
                elif mutation == 'system':
                    rows[5]['records'][0]['systemTicks'] = -1
                elif mutation == 'epoch':
                    rows[5]['epochMs'] = rows[4]['epochMs']
                else:
                    rows[5]['records'].append(copy.deepcopy(rows[5]['records'][0]))
                with self.assertRaises(ValueError):
                    audit.summarize(rows, [command()])

    def test_terminal_exit_batch_excluded_but_internal_gap_refused(self):
        rows = samples()
        rows[-1]['records'] = []
        self.assertEqual(1, audit.summarize(rows, [command()])['terminalNoHostBatchesExcluded'])
        rows[4]['records'] = []
        with self.assertRaises(ValueError):
            audit.summarize(rows, [command()])

    def test_missing_outer_boundary_refused(self):
        for start, end in ((-1, 5000), (1000, 8500)):
            with self.subTest(start=start, end=end), self.assertRaises(ValueError):
                audit.summarize(samples(), [dict(cycle=1, startEpochMillis=start, endEpochMillis=end)])

    def test_short_or_overlapping_windows_refused(self):
        for windows in ([dict(cycle=1, startEpochMillis=1500, endEpochMillis=1600)],
                        [command(), dict(cycle=2, startEpochMillis=5000, endEpochMillis=7000)]):
            with self.assertRaises(ValueError):
                audit.summarize(samples(), windows)


if __name__ == '__main__':
    unittest.main()
