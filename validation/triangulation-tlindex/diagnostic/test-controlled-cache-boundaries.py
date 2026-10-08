import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('audit', Path(__file__).with_name('compare-controlled-cache-boundaries.py'))
audit = importlib.util.module_from_spec(spec); spec.loader.exec_module(audit)


def fixture():
    return [dict(cycle=str(c), sourceIdBase64=str(i), pointCount='3', positionValues='6', indexValues='3',
                 positionsSha256='positions', indicesSha256='indices', positionVersion='3', vertexCacheVersion='3',
                 beforeEdgeVersion='10', commandEdgeVersion='16', indexCacheVersion='16')
            for c in (1, 2, 3) for i in range(711)]


class DifferentialTest(unittest.TestCase):
    def test_process_local_absolute_counter_is_not_geometry(self):
        a = fixture(); b = copy.deepcopy(a)
        for row in b: row.update(beforeEdgeVersion='110', commandEdgeVersion='116', indexCacheVersion='116')
        self.assertEqual(audit.compare(a, b)['mismatchCount'], 0)

    def test_array_source_version_and_cache_differences_are_not_hidden(self):
        for key, value in [('indicesSha256', 'different'), ('sourceIdBase64', 'other'),
                           ('indexValues', '6'), ('positionVersion', '4'), ('commandEdgeVersion', '17'),
                           ('indexCacheVersion', '-1')]:
            a = fixture(); b = copy.deepcopy(a); b[0][key] = value
            self.assertGreater(audit.compare(a, b)['mismatchCount'], 0)

    def test_missing_or_duplicate_sources_rejected(self):
        a = fixture(); b = copy.deepcopy(a); b.pop()
        with self.assertRaises(ValueError): audit.compare(a, b)
        b = copy.deepcopy(a); b[1]['sourceIdBase64'] = b[0]['sourceIdBase64']
        with self.assertRaises(ValueError): audit.compare(a, b)


if __name__ == '__main__': unittest.main()
