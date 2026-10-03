import base64
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('analyze-native-input-state.py'))
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


def fixture():
    def encoded(s): return base64.b64encode(s.encode()).decode()
    inputs = [dict(cycle=str(c), phase=p, widgetBase64=encoded('null'), subtoolBase64=encoded('POINT_ADD'),
                   viewsBase64=encoded('nativeView')) for c in (1, 2, 3) for p in ('before', 'after')]
    results = [dict(cycle=str(c), sourceIdBase64=encoded(str(i)), pointCount='3', positionValues='6', indexValues='3',
                    indexCacheVersion='-1', beforeEdgeVersion='1', commandEdgeVersion='2',
                    positionVersion='3', vertexCacheVersion='3') for c in (1, 2, 3) for i in range(711)]
    stacks = {'taskId': 'owned-task', 'cpuPayloadFieldsMatched': 30, 'cycles': {
        str(c): {'fullChainExecutionSamples': 0, 'fullChainAllocationSamples': 0} for c in (1, 2, 3)}}
    return inputs, results, stacks


class InputStateTest(unittest.TestCase):
    def test_null_widget_invalid_cache_join_does_not_grant_cause(self):
        i, r, s = fixture(); report = audit.analyze(i, r, s, 'owned-task')
        self.assertEqual(report['cycles'][0]['invalidatedIndexCacheSources'], 711)
        self.assertEqual(report['causation'], 'NOT_PROVEN')

    def test_current_cache_and_observed_chain_are_reported(self):
        i, r, s = fixture()
        for row in r: row['indexCacheVersion'] = '2'
        s['cycles']['1']['fullChainExecutionSamples'] = 10
        report = audit.analyze(i, r, s, 'owned-task')
        self.assertEqual(report['cycles'][0]['currentIndexCacheSources'], 711)
        self.assertEqual(report['cycles'][0]['fullChainExecutionSamples'], 10)

    def test_missing_corrupt_or_wrong_task_evidence_rejected(self):
        for change in (lambda i, r, s: i.pop(), lambda i, r, s: i[0].update(widgetBase64='%%%'),
                       lambda i, r, s: i.reverse(), lambda i, r, s: r.pop(),
                       lambda i, r, s: r[1].update(sourceIdBase64=r[0]['sourceIdBase64']),
                       lambda i, r, s: r[0].update(indexValues='4'),
                       lambda i, r, s: s.update(taskId='other-task')):
            i, r, s = fixture(); change(i, r, s)
            with self.assertRaises(ValueError): audit.analyze(i, r, s, 'owned-task')


if __name__ == '__main__': unittest.main()
