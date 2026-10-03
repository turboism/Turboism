"""Negative controls for deterministic scope accounting and command binding."""
import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('analyze-native-mesh-scope-events.py'))
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


def fixture():
    base = 1791033600000
    windows = [{'cycle': i, 'startEpochMillis': base + i * 10000,
                'endEpochMillis': base + i * 10000 + 3000, 'javaThreadId': 7} for i in (1, 2, 3)]
    events = [{'type': audit.EVENT, 'values': {
        'startTime': '2026-10-03T13:20:' + str(i * 10 + 1).zfill(2) + 'Z',
        'duration': 'PT1S', 'eventThread': {'javaThreadId': 7},
        'tableCalls': 3, 'hits': 2, 'absent': 1, 'unknown': 0, 'appended': 1,
        'discarded': False, 'released': True}} for i in (1, 2, 3)]
    return events, windows


class ScopeAuditTest(unittest.TestCase):
    def test_counts(self):
        e, w = fixture()
        self.assertEqual(audit.audit(e, w)['cycles'][2]['tableCalls'], 3)

    def test_invalid_evidence(self):
        changes = [lambda e: e.pop(),
                   lambda e: e[0]['values'].update(released=False),
                   lambda e: e[0]['values'].update(tableCalls=4),
                   lambda e: e[0]['values'].update(unknown=1, tableCalls=4),
                   lambda e: e[0]['values'].update(hits=-1),
                   lambda e: e[0]['values']['eventThread'].update(javaThreadId=8),
                   lambda e: e[0]['values'].update(duration='PT10S'),
                   lambda e: e[0]['values'].update(tableCalls=True)]
        for change in changes:
            e, w = fixture(); e = copy.deepcopy(e); change(e)
            with self.assertRaises(ValueError):
                audit.audit(e, w)


if __name__ == '__main__':
    unittest.main()
