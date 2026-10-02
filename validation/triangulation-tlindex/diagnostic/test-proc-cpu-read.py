import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('cpu', Path(__file__).with_name('proc-cpu-read.py'))
cpu = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cpu)


class ProcCpuReadTest(unittest.TestCase):
    def test_actual_process_identity_counters_and_read_range(self):
        a = cpu.stat(os.getpid())
        b = cpu.stat(os.getpid())
        self.assertEqual(a['startTicks'], b['startTicks'])
        self.assertGreaterEqual(b['userTicks'], a['userTicks'])
        self.assertGreaterEqual(b['systemTicks'], a['systemTicks'])
        self.assertLessEqual(a['cpuReadStartEpochMillis'], a['cpuReadEndEpochMillis'])
        self.assertLessEqual(a['cpuReadStartMonotonicNanos'], a['cpuReadEndMonotonicNanos'])
        self.assertLessEqual(a['cpuReadEndMonotonicNanos'], b['cpuReadStartMonotonicNanos'])

    def test_spaces_and_parentheses_in_process_name(self):
        fields = ['S'] + ['0'] * 23
        fields[11], fields[12], fields[19], fields[21] = '31', '41', '999', '2'
        text = '123 (space ) parenthesis) ' + ' '.join(fields)
        with patch.object(Path, 'read_text', return_value=text):
            value = cpu.stat(123)
        self.assertEqual((31, 41, '999'), (value['userTicks'], value['systemTicks'], value['startTicks']))

    def test_clock_failure_is_not_a_valid_zero_cpu_read(self):
        with patch.object(cpu.time, 'monotonic_ns', side_effect=[2, 1]):
            with self.assertRaises(ValueError):
                cpu.stat(os.getpid())


if __name__ == '__main__':
    unittest.main()
