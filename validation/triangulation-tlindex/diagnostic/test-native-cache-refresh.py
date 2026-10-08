"""Negative controls for sampled caller order and command/EDT ownership."""
import copy
import datetime
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('analyze-native-cache-refresh.py'))
audit = importlib.util.module_from_spec(spec)
spec.loader.exec_module(audit)


def fixture():
    windows, events = [], []
    for cycle in (1, 2, 3):
        start = cycle * 10000
        windows.append(dict(cycle=cycle, startEpochMillis=start, endEpochMillis=start + 1000, javaThreadId=29))
        events.append(dict(type='jdk.ExecutionSample', values=dict(
            startTime=datetime.datetime.fromtimestamp((start + 500) / 1000, datetime.timezone.utc).isoformat(),
            sampledThread={'javaThreadId': 29}, stackTrace={'truncated': False, 'frames': [
                {'method': {'type': {'name': cls.replace('.', '/')}, 'name': name}}
                for cls, name in audit.CHAIN]})))
    return events, windows


class CacheRefreshTest(unittest.TestCase):
    def test_full_chain_and_allocation(self):
        events, windows = fixture()
        extra = copy.deepcopy(events[0]); extra['type'] = 'jdk.ObjectAllocationSample'
        extra['values']['eventThread'] = extra['values'].pop('sampledThread')
        result = audit.audit(events + [extra], windows)
        self.assertTrue(result['fullChainObservedEachCycle'])
        self.assertEqual(result['cycles'][1]['fullChainAllocationSamples'], 1)

    def test_wrong_order_or_missing_caller_does_not_prove_chain(self):
        for change in (lambda f: f.reverse(), lambda f: f.pop(), lambda f: f.clear()):
            events, windows = fixture(); change(events[0]['values']['stackTrace']['frames'])
            self.assertFalse(audit.audit(events, windows)['fullChainObservedEachCycle'])

    def test_other_thread_and_outside_window_excluded(self):
        events, windows = fixture(); extras = copy.deepcopy(events[:2])
        extras[0]['values']['sampledThread']['javaThreadId'] = 1
        extras[1]['values']['startTime'] = '1970-01-01T00:00:00+00:00'
        result = audit.audit(events + extras, windows)
        self.assertEqual(result['excludedSamples'], {'outsideInvocation': 1, 'otherThread': 1})

    def test_truncation_is_reported_without_proving_missing_chain(self):
        events, windows = fixture()
        events[0]['values']['stackTrace'].update(truncated=True, frames=[])
        result = audit.audit(events, windows)
        self.assertEqual(result['cycles'][1]['truncatedStacks'], 1)
        self.assertFalse(result['fullChainObservedEachCycle'])

    def test_invalid_binding_or_missing_samples_rejected(self):
        for change in (lambda e, w: e.pop(), lambda e, w: w[0].update(javaThreadId=1),
                       lambda e, w: w[0].update(endEpochMillis=25000),
                       lambda e, w: e.append({'type': 'jdk.DataLoss', 'values': {}})):
            events, windows = fixture(); change(events, windows)
            with self.assertRaises(ValueError): audit.audit(events, windows)


if __name__ == '__main__':
    unittest.main()
