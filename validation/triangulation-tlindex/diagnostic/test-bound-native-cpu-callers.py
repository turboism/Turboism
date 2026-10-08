"""Scope controls for caller attribution; real-recording equivalence is separate."""
import datetime
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('callers', Path(__file__).with_name('analyze-bound-native-cpu-callers.py'))
callers = importlib.util.module_from_spec(spec)
spec.loader.exec_module(callers)


def frame(owner, name, descriptor='()V'):
    return {'method': {'type': {'name': owner}, 'name': name, 'descriptor': descriptor}}


def frame_name(value):
    method = value['method']
    return method['type']['name'].replace('/', '.') + '.' + method['name'] + method['descriptor']


def windows():
    return [dict(cycle=i + 1, startEpochMillis=1700000000000.25 + i * 100,
                 endEpochMillis=1700000000010.75 + i * 100, javaThreadId=29)
            for i in range(3)]


def event(millis, frames=None, thread=29, kind='jdk.ExecutionSample', truncated=False):
    return {'type': kind, 'values': {
        'startTime': datetime.datetime.fromtimestamp(millis / 1000, datetime.timezone.utc).isoformat(),
        'sampledThread': {'javaThreadId': thread, 'javaName': 'AWT-EventQueue-0'},
        'stackTrace': {'frames': frames or [], 'truncated': truncated}}}


def controls():
    return [event(1700000000002 + i * 100, [frame('owned/NativeCommand', 'invoke')]) for i in range(3)]


class CallerScopeTests(unittest.TestCase):
    def test_conservative_edges_thread_and_native_event_exclusion(self):
        events = controls() + [
            event(1700000000000.75), event(1700000000010.5),
            event(1700000000003, thread=30), event(1700000000003, kind='jdk.NativeMethodSample'),
            event(1700000000001), event(1700000000010)]
        report = callers.summarize(iter(events), windows(), frame_name)
        self.assertEqual([3, 1, 1], [c['sampleScopes']['all'] for c in report['cycles']])
        self.assertEqual({'outsideInvocationExecutionSamples': 2,
                          'otherThreadExecutionSamples': 1, 'nonExecutionEvents': 1},
                         report['excludedExecutionSamples'])

    def test_leaf_caller_chain_distinguishes_native_and_index_owners(self):
        events = controls() + [event(1700000000003, [
            frame('java/util/Objects', 'checkIndex', '(II)I'),
            frame('java/util/ArrayList', 'get', '(I)Ljava/lang/Object;'),
            frame('com/live2d/graphics3d/editableMesh/triangulation/h', 'a')]),
            event(1700000000004, [
                frame('dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex', 'settle'),
                frame('dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex', 'contains')])]
        cycle = callers.summarize(iter(events), windows(), frame_name)['cycles'][0]
        self.assertEqual(3, cycle['sampleScopes']['all'])
        self.assertEqual(1, cycle['sampleScopes']['indexInclusive'])
        self.assertEqual(1, cycle['sampleScopes']['triangulationInclusive'])
        self.assertIn('java.util.ArrayList.get', cycle['checkIndexLeafCallerSites'][0][0])
        self.assertIn('TriangulationEdgeIndex.contains', cycle['settleLeafCallerSites'][0][0])

    def test_empty_truncated_and_single_pass_stream_accounting(self):
        def stream():
            yield from controls()
            yield event(1700000000004)
            yield event(1700000000005, [frame('owned/NativeCommand', 'invoke')], truncated=True)
        cycle = callers.summarize(stream(), windows(), frame_name)['cycles'][0]
        self.assertEqual(3, cycle['sampleScopes']['all'])
        self.assertEqual(1, cycle['sampleScopes']['emptyStack'])
        self.assertEqual(1, cycle['sampleScopes']['truncatedStack'])
        self.assertEqual(3, sum(count for _, count in cycle['cpuLeafSites']))

    def test_missing_cycle_samples_refused(self):
        with self.assertRaisesRegex(ValueError, 'no bound EDT'):
            callers.summarize(iter(controls()[:2]), windows(), frame_name)

    def test_changed_thread_or_overlapping_windows_refused(self):
        changed = windows()
        changed[1]['javaThreadId'] = 30
        with self.assertRaisesRegex(ValueError, 'same-EDT'):
            callers.summarize(iter(controls()), changed, frame_name)
        changed = windows()
        changed[1]['startEpochMillis'] = changed[0]['endEpochMillis'] - 2
        with self.assertRaisesRegex(ValueError, 'intervals'):
            callers.summarize(iter(controls()), changed, frame_name)


if __name__ == '__main__':
    unittest.main()
