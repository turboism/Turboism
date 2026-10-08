"""Negative controls for memory accounting, release, command binding and missing GC."""
import importlib.util
from pathlib import Path
import unittest


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


audit = module('audit', 'analyze-native-mesh-memory.py')
previous = module('fixtures', 'test-native-mesh-scope-events.py')


def fixture():
    events, windows = previous.fixture()
    for event in events:
        event['type'] = audit.EVENT
        event['values'].update(initialEntries=3, entryLimit=20, bufferCapacity=64,
                               declaredBufferBytes=1792, helperAndSuffixAllocatedBytes=2048)
    events += [{'type': 'jdk.GarbageCollection', 'values': {
        'startTime': '2026-10-03T13:20:00Z', 'duration': 'PT0.01S', 'gcId': 1,
        'name': 'G1New', 'cause': 'G1 Evacuation Pause'}},
        {'type': 'jdk.GCHeapSummary', 'values': {'startTime': '2026-10-03T13:20:00Z',
         'gcId': 1, 'when': 'After GC', 'heapUsed': 1024, 'heapSpace': {'committedSize': 4096}}}]
    return events, windows


class MemoryAuditTest(unittest.TestCase):
    def test_exact_buffer_totals_and_design_estimate(self):
        e, w = fixture(); r = audit.audit(e, w, 1)
        self.assertEqual(r['cycles'][1]['declaredBufferBytes'], 1792)
        self.assertEqual(r['cycles'][1]['hypotheticalInitialSizeBufferBytes'], 1216)
        self.assertEqual(r['garbageCollections'][0]['phase'], 'beforeFirstCommand')

    def test_invalid_memory_or_recording(self):
        changes = [lambda e: e[0]['values'].update(bufferCapacity=32),
                   lambda e: e[0]['values'].update(declaredBufferBytes=1000),
                   lambda e: e[0]['values'].update(helperAndSuffixAllocatedBytes=1),
                   lambda e: e[0]['values'].update(initialEntries=21),
                   lambda e: e[0]['values'].update(initialEntries=True),
                   lambda e: e[0]['values'].update(released=False),
                   lambda e: e.pop(),
                   lambda e: e.append({'type': 'jdk.DataLoss', 'values': {}})]
        for change in changes:
            e, w = fixture(); change(e)
            with self.assertRaises(ValueError): audit.audit(e, w, 1)

    def test_missing_selected_scope_is_not_silently_accepted(self):
        e, w = fixture()
        with self.assertRaises(ValueError): audit.audit(e, w, 711)


if __name__ == '__main__':
    unittest.main()
