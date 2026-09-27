#!/usr/bin/env python3
import copy
import unittest
from host_resource_counters import cpu_interval, cpu_snapshot, parse_drm, deduplicate, GpuIntervals

TEXT = '''drm-driver: i915
drm-pdev: 0000:00:02.0
drm-client-id: 7
drm-engine-render: 100000000 ns
drm-resident-system0: 32 KiB
'''


def snapshot(value, second, client='7'):
    data = parse_drm(TEXT.replace('100000000 ns', str(value) + ' ns').replace('id: 7', 'id: ' + client))
    return {'clients': deduplicate([data]), 'complete': True, 'unavailableDescriptors': 0,
            'monotonicNs': second * 1_000_000_000}


class ResourceCounters(unittest.TestCase):
    def test_cpu_units_and_normalization(self):
        a = {'ticks': 100, 'started': 42, 'ticksPerSecond': 100, 'logicalCpus': 12, 'monotonicNs': 0}
        b = dict(a, ticks=340, monotonicNs=2_000_000_000)
        result = cpu_interval(a, b)
        self.assertEqual(120, result['corePercent'])
        self.assertEqual(10, result['machinePercent'])
        self.assertEqual(2.4, result['cpuSeconds'])
        with self.assertRaises(ValueError):
            cpu_interval(a, dict(b, started=43))

    def test_cpu_parser_handles_parenthesized_names(self):
        fields = ['S', '1'] + ['0'] * 18
        fields[11], fields[12], fields[19] = '120', '30', '55'
        self.assertEqual(150, cpu_snapshot('42 (process (with) spaces) ' + ' '.join(fields))['ticks'])

    def test_drm_units_and_missing_are_not_zero(self):
        value = parse_drm(TEXT)
        self.assertEqual(32768, value['residentBytes']['system0'])
        self.assertEqual(100000000, value['enginesNs']['render'])
        self.assertIsNone(parse_drm('pos: 0\n'))
        with self.assertRaises(ValueError):
            parse_drm(TEXT.replace(' ns', ' ms'))

    def test_duplicate_descriptors_count_one_client(self):
        a = parse_drm(TEXT)
        b = parse_drm(TEXT.replace('100000000 ns', '120000000 ns'))
        result = deduplicate([a, b])
        self.assertEqual(1, len(result))
        self.assertEqual(120000000, next(iter(result.values()))['enginesNs']['render'])
        self.assertEqual(32768, next(iter(result.values()))['residentBytes']['system0'])

    def test_gpu_counter_dips_do_not_create_spurious_work(self):
        tracker = GpuIntervals()
        self.assertIsNone(tracker.add(snapshot(100000000, 1)))
        self.assertEqual(10, tracker.add(snapshot(200000000, 2))['percent']['0000:00:02.0/render'])
        self.assertEqual(0, tracker.add(snapshot(180000000, 3))['percent']['0000:00:02.0/render'])
        self.assertEqual(10, tracker.add(snapshot(300000000, 4))['percent']['0000:00:02.0/render'])

    def test_client_change_or_partial_read_is_unavailable(self):
        tracker = GpuIntervals()
        tracker.add(snapshot(100, 1))
        self.assertIsNone(tracker.add(snapshot(200, 2, '8')))
        partial = snapshot(300, 3, '8'); partial['complete'] = False
        self.assertIsNone(tracker.add(partial))
        self.assertIsNone(tracker.add(snapshot(400, 4, '8')))

    def test_capacity_two_and_multiple_clients(self):
        first, second = snapshot(100000000, 1), snapshot(300000000, 2)
        for sample in [first, second]:
            client = next(iter(sample['clients'].values()))
            client['capacity']['render'] = 2
            another = copy.deepcopy(client); another['id'] = '9'
            sample['clients'] = deduplicate([client, another])
        tracker = GpuIntervals(); tracker.add(first)
        self.assertEqual(20, tracker.add(second)['percent']['0000:00:02.0/render'])
        with self.assertRaises(ValueError):
            parse_drm(TEXT + 'drm-engine-capacity-render: 0\n')


if __name__ == '__main__':
    unittest.main()
