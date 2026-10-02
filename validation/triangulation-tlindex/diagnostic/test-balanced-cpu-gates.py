import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('gates', Path(__file__).with_name('balanced-cpu-gates.py'))
gates = importlib.util.module_from_spec(spec)
spec.loader.exec_module(gates)


def fixture():
    return [dict(name=name, arm=arm, sequence=i + 1, jobId=str(i), runId=str(i),
                 lifecyclePass=True, outputMatch=True, cpuUnitsPass=True,
                 wallSeconds=10 if arm == 'baseline' else 9,
                 directCpuSeconds=20 if arm == 'baseline' else 19,
                 sampledCpuSeconds=18, operationPeakRssBytes=1000 * 1024 * 1024,
                 peakAboveBaselineBytes=80 * 1024 * 1024, retained3Minus1Bytes=64 * 1024 * 1024)
            for i, (name, arm) in enumerate(gates.ORDER)]


class GateTests(unittest.TestCase):
    def test_exact_caps_pass_without_production_claim(self):
        report = gates.evaluate(fixture())
        self.assertEqual(report['status'], 'PASS_FIXED_BALANCED_GATES')
        self.assertEqual(report['productionAcceptance'], 'NOT_GRANTED_INSTRUMENTED_PROTOCOL')

    def test_second_pair_failure_cannot_be_hidden_by_aggregate_gain(self):
        legs = fixture()
        legs[1]['directCpuSeconds'] = 10
        legs[2]['directCpuSeconds'] = 20.01
        report = gates.evaluate(legs)
        self.assertLess(report['aggregateCandidateChangePercent']['directCpuSeconds'], 0)
        self.assertEqual(report['status'], 'FAIL_FIXED_BALANCED_GATES')

    def test_each_memory_gate_is_independent(self):
        for key, value in [('operationPeakRssBytes', 1200 * 1024 * 1024 + 1),
                           ('peakAboveBaselineBytes', 80 * 1024 * 1024 + 1),
                           ('retained3Minus1Bytes', 64 * 1024 * 1024 + 1)]:
            legs = fixture()
            legs[1][key] = value
            self.assertEqual(gates.evaluate(legs)['status'], 'FAIL_FIXED_BALANCED_GATES')

    def test_wall_gain_is_required_in_both_pairs(self):
        legs = fixture()
        legs[2]['wallSeconds'] = 10
        self.assertEqual(gates.evaluate(legs)['status'], 'FAIL_FIXED_BALANCED_GATES')

    def test_reject_missing_evidence_bad_numbers_and_reordered_runs(self):
        for field, value in [('lifecyclePass', False), ('outputMatch', False),
                             ('cpuUnitsPass', False), ('directCpuSeconds', float('nan')),
                             ('wallSeconds', 0), ('sequence', 1), ('jobId', '0'), ('runId', '0')]:
            legs = fixture()
            legs[1][field] = value
            with self.assertRaises(ValueError):
                gates.evaluate(legs)
        for legs in [fixture()[:3], list(reversed(fixture())), fixture() + [copy.deepcopy(fixture()[0])]]:
            with self.assertRaises(ValueError):
                gates.evaluate(legs)


if __name__ == '__main__':
    unittest.main()
