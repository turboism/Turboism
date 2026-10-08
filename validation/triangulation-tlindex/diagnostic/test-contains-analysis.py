import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('analysis', Path(__file__).with_name('analyze-contains-probe.py'))
analysis = importlib.util.module_from_spec(spec)
spec.loader.exec_module(analysis)


class CountersTest(unittest.TestCase):
    def base(self):
        data = {reason: [0, 0, 0] for reason in analysis.REASONS}
        data['identityHit'] = [10, 10, 0]
        data['identityMiss'] = [5, 2, 3]
        return data

    def lines(self, data):
        return ['unrelated host line\n', analysis.MARKER + json.dumps(data) + '\n']

    def test_actual_counts_not_sample_ratio(self):
        result = analysis.counts_from_log(self.lines(self.base()))
        self.assertEqual(result['calls'], 15)
        self.assertEqual(result['fallbackCalls'], 5)
        self.assertEqual(result['identityHitFraction'], 10 / 15)

    def test_missing_or_duplicate(self):
        for lines in ([], self.lines(self.base()) * 2):
            with self.assertRaises(ValueError):
                analysis.counts_from_log(lines)

    def test_host_logger_wrapper(self):
        line = 'ERROR [ ' + analysis.MARKER + json.dumps(self.base())
        line += ' ] at dev.turboism.adapter.cubism.mesh.ContainsDiagnostic (ContainsDiagnostic.kt:19) lambda$static$0()'
        self.assertEqual(analysis.counts_from_log([line])['calls'], 15)
        with self.assertRaises(ValueError):
            analysis.counts_from_log([line + ' unexpected'])

    def test_invalid_or_incomplete(self):
        for row in ([1, 0, 0], [1, 1, 1], [-1, 0, 0], [True, 1, 0], [1, 0]):
            with self.subTest(row=row):
                data = self.base()
                data['dirty'] = row
                with self.assertRaises(ValueError):
                    analysis.counts_from_log(self.lines(data))

    def test_no_execution_schema_and_bookkeeping_failure(self):
        for kind in ('zero', 'schema', 'bookkeeping', 'falseHit'):
            data = self.base()
            if kind == 'zero': data = {reason: [0, 0, 0] for reason in analysis.REASONS}
            if kind == 'schema': del data['dead']
            if kind == 'bookkeeping': data['bookkeepingFailure'] = [1, 1, 0]
            if kind == 'falseHit': data['identityHit'] = [1, 0, 1]
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                analysis.counts_from_log(self.lines(data))


if __name__ == '__main__':
    unittest.main()
