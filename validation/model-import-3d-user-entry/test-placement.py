#!/usr/bin/env python3
"""Negative evidence checks using the recorded native cube affine experiment."""
import copy
import importlib.util
import json
from pathlib import Path
import unittest

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
spec = importlib.util.spec_from_file_location('placement', HERE / 'verify-placement.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class PlacementEvidenceTest(unittest.TestCase):
    def setUp(self):
        base = ROOT / 'build/model-import-3d-offline'
        self.report = json.loads((base / 'cube-placement-native-r2-20261002/native-result/state/report.json').read_text())
        self.reference = json.loads((base / 'cube-xyz-reopen-native-r1-20261002/native-result/state/report.json').read_text())

    def test_recorded_cube_matches_independently_reopened_geometry(self):
        result = module.verify(self.report, self.reference)
        self.assertEqual(result['meshes'], 6)
        self.assertLess(result['maximumErrorPixels'], .002)

    def test_reject_corrupted_evidence(self):
        def mutate_source(r): r['inputSha256'] = 'foreign'
        def mutate_geometry(r): r['imported']['meshes'][0]['geometrySha256'] = 'foreign'
        def mutate_partial(r): r['placement'][0]['passAfter'].pop()
        def mutate_stale(r): r['placement'][0]['passAfter'] = r['placement'][0]['passBefore']
        def mutate_case(r): r['placement'].pop()
        def mutate_scale(r): r['placement'][1]['scale'] = 1
        def mutate_error(r): r['placement'][0]['maximumErrorPixels'] = .003
        def mutate_nan(r): r['placement'][0]['maximumErrorPixels'] = float('nan')
        def mutate_undo(r): r['undoRestored'] = False
        for mutation in (mutate_source, mutate_geometry, mutate_partial, mutate_stale,
                         mutate_case, mutate_scale, mutate_error, mutate_nan, mutate_undo):
            with self.subTest(mutation=mutation.__name__):
                report = copy.deepcopy(self.report)
                mutation(report)
                with self.assertRaises(ValueError):
                    module.verify(report, self.reference)


if __name__ == '__main__':
    unittest.main()
