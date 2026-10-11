"""Cube oracle controls. Optional producer report is explicitly synthetic, never host acceptance."""
import copy
import json
import os
from pathlib import Path
import runpy
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
ORACLE = runpy.run_path(str(ROOT / 'validation/model-import-3d-host-probe/verify-cube-geometry.py'))


class CubeContractTest(unittest.TestCase):
    def test_predeclared_grid_and_pose_inventory(self):
        keys, poses = ORACLE['KEYS'], ORACLE['poses']()
        self.assertEqual(45, len(keys))
        self.assertEqual((-30, 0, 30), (keys[0], keys[22], keys[-1]))
        self.assertEqual(377, len(poses))
        self.assertEqual(len(poses), len(set(poses)))
        self.assertTrue(all((k, 0) in poses and (0, k) in poses for k in keys))
        self.assertTrue(all((a, b) in poses for a in (-30, 0, 30) for b in (-30, 0, 30)))

    def test_input_identity_is_mandatory(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'wrong.glb'
            path.write_bytes(bytes(1936))
            with self.assertRaisesRegex(ValueError, 'authorized input bytes'):
                ORACLE['cube_source'](path)


@unittest.skipUnless(os.environ.get('TURBOISM_MI3D_CUBE_REPORT') and os.environ.get('TURBOISM_MI3D_CUBE_INPUT'),
                     'requires an explicit synthetic producer report and authorized frozen input')
class CubeProducerOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads(Path(os.environ['TURBOISM_MI3D_CUBE_REPORT']).read_text())
        if cls.report.get('evidenceKind') != 'OFFLINE_FIXTURE':
            raise ValueError('oracle mutation controls must use synthetic evidence')
        cls.input = Path(os.environ['TURBOISM_MI3D_CUBE_INPUT'])

    def test_accepts_complete_producer_report(self):
        ORACLE['verify_cube_geometry'](self.report, self.input)

    def test_rejects_changed_source_or_missing_edge_on_face(self):
        for mutate in (
            lambda e: e['sources'][0][0].__setitem__(0, 123),
            lambda e: e['patches'][0].__setitem__('neutralCollapsedTriangles', []),
            lambda e: e.__setitem__('inputSha256', '0' * 64),
        ):
            with self.subTest(mutate=mutate):
                report = copy.deepcopy(self.report); mutate(report['cubeGeometry'])
                with self.assertRaises(ValueError): ORACLE['verify_cube_geometry'](report, self.input)

    def test_rejects_wrong_geometry_stale_consumption_and_history(self):
        for mutate in (
            lambda s: s['meshes'][0]['actual']['calculated']['positions'][0].__setitem__(0, 999),
            lambda s: s['meshes'][0]['actual']['consumed'].__setitem__('updateVersion',
                s['meshes'][0]['requested']['consumed']['updateVersion']),
            lambda s: s['historyAfter'].__setitem__('position', s['historyAfter']['position'] + 1),
        ):
            with self.subTest(mutate=mutate):
                report = copy.deepcopy(self.report); mutate(report['cubeGeometry']['samples'][0])
                with self.assertRaises(ValueError): ORACLE['verify_cube_geometry'](report, self.input)

    def test_rejects_missing_binding_and_changed_uv(self):
        for field in ('cells', 'uvs'):
            report = copy.deepcopy(self.report); e = report['cubeGeometry']
            for phase in ('before', 'written', 'undone', 'redone', 'afterEvaluation'):
                e[phase][0][field].pop()
            with self.subTest(field=field), self.assertRaises(ValueError):
                ORACLE['verify_cube_geometry'](report, self.input)

    def test_rejects_missing_pose_and_recovery_failure(self):
        for field, value in (('finallyRestored', False), ('samples', self.report['cubeGeometry']['samples'][:-1])):
            report = copy.deepcopy(self.report); report['cubeGeometry'][field] = value
            with self.subTest(field=field), self.assertRaises(ValueError):
                ORACLE['verify_cube_geometry'](report, self.input)


if __name__ == '__main__':
    unittest.main()
