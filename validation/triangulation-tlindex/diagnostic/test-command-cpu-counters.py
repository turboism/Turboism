import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('counter', Path(__file__).with_name('analyze-command-cpu-counters.py'))
counter = importlib.util.module_from_spec(spec)
spec.loader.exec_module(counter)


def rows():
    result = []
    for cycle in (1, 2, 3):
        for offset, phase in ((0, 'auto-connect-start'), (1, 'auto-connect-returned')):
            n = (cycle * 2 + offset) * 1_000_000_000
            result.append(dict(phase=phase, operation=str(cycle), processCpuNanos=str(n * 2),
                               readStartNanos=str(n), readEndNanos=str(n + 1000),
                               readStartEpochMillis=str(n // 1_000_000),
                               readEndEpochMillis=str(n // 1_000_000), osName='Windows 10'))
    return result


class CounterEvidenceTest(unittest.TestCase):
    def test_exact_delta_and_read_span(self):
        result = counter.analyze(rows(), True)
        self.assertEqual(6, result['totalCommandProcessCpuSeconds'])
        self.assertEqual(1000, result['maximumReadSpanNanos'])

    def test_unavailable_decreasing_and_read_clock_failures(self):
        for key, value in [('processCpuNanos', '-1'), ('processCpuNanos', '0'),
                           ('readEndNanos', '0'), ('readStartEpochMillis', '999999')]:
            data = rows()
            data[1][key] = value
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                counter.analyze(data, True)

    def test_zero_progress_not_performance_pass(self):
        data = rows()
        data[1]['processCpuNanos'] = data[0]['processCpuNanos']
        with self.assertRaises(ValueError):
            counter.analyze(data, True)

    def test_missing_duplicate_and_unsupported_platform_refused(self):
        for data in (rows()[:-1], rows() + [copy.deepcopy(rows()[-1])],
                     [dict(row, osName='Linux') for row in rows()]):
            with self.assertRaises(ValueError):
                counter.analyze(data, True)

    def test_kernel_process_counter_units_and_wrong_scale_refusal(self):
        data = rows()
        counter.analyze(data, True)
        samples = [{'ticksPerSecond': 100, 'cgroup': 'owned', 'cgroupInode': 42,
                    'records': [dict(role='host-java', pid=123, startTicks='456', userTicks=i * 200,
                                     systemTicks=0, cpuReadStartEpochMillis=i * 1000 - 10,
                                     cpuReadEndEpochMillis=i * 1000 + 10)]} for i in range(1, 10)]
        self.assertEqual('PASS_SAME_PROCESS_NANOSECOND_UNITS',
                         counter.check_kernel_counter_units(data, samples)['status'])
        wrong = [dict(row, processCpuNanos=row['processCpuNanos'] // 1000) for row in data]
        with self.assertRaises(ValueError):
            counter.check_kernel_counter_units(wrong, samples)
        samples[3]['records'][0]['startTicks'] = 'reused'
        with self.assertRaises(ValueError):
            counter.check_kernel_counter_units(data, samples)


if __name__ == '__main__':
    unittest.main()
