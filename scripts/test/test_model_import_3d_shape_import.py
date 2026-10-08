#!/usr/bin/env python3
"""Synthetic B0 shape-import protocol controls; no host, queue, or exact-host evidence."""
import copy
import importlib.util
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True

CHECKS = ['rotationParameterCreated', 'geometryCommitted', 'geometryReadback',
          'keyformsCommitted', 'keyformReadback', 'defaultFormIsZeroPose',
          'keyformUndo', 'keyformRedo', 'undoStable', 'historyPositionRestored']


def load_protocol():
    path = Path(__file__).resolve().parents[1].parent / 'validation/model-import-3d-host-probe/verify-shape-import.py'
    spec = importlib.util.spec_from_file_location('verify_shape_import', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def base_report():
    return {
        'shapeImport': {
            'schemaVersion': 'mi3d-native-b0-v1', 'status': 'PASS', 'failure': None,
            'finallyRestored': True, 'coordinateSpace': 'EDITOR_STORED_FORM',
            'checks': [{'id': c, 'status': 'PASS', 'actual': True,
                        'expected': 'b0 evidence', 'evidenceKind': 'EXACT_HOST'} for c in CHECKS],
            'geometryTransaction': {'outcome': 'COMMITTED', 'diagnosticId': None},
            'keyformTransaction': {'outcome': 'COMMITTED', 'diagnosticId': None},
            'meshes': [
                {'name': 'MI3D_B0_TRI', 'id': 'ArtMeshMI3D_B0_TRI'},
                {'name': 'MI3D_B0_QUAD', 'id': 'ArtMeshMI3D_B0_QUAD'},
            ],
            'bindings': [
                {'mesh': 'ArtMeshMI3D_B0_TRI', 'parameter': 'ParamMI3DB0RotX',
                 'keys': [-30.0, 0.0, 30.0]},
                {'mesh': 'ArtMeshMI3D_B0_QUAD', 'parameter': 'ParamMI3DB0RotX',
                 'keys': [-30.0, 0.0, 30.0]},
            ],
            'protectedDomain': {
                'tri': {'uvs': [[0.2, 0.5], [0.3, 0.1], [0.4, 0.5]],
                        'indices': [0, 1, 2],
                        'sourcePositions': [[692.0, 392.0], [332.0, 392.0], [512.0, 692.0]],
                        'defaultFormPositions': [[692.0, 392.0], [332.0, 392.0], [512.0, 692.0]],
                        'formCount': 3},
                'quad': {'uvs': [[0.6, 0.5], [0.7, 0.1], [0.8, 0.5], [0.9, 0.1]],
                         'indices': [0, 1, 2, 0, 2, 3],
                         'sourcePositions': [[672.0, 352.0], [352.0, 352.0],
                                             [352.0, 672.0], [672.0, 672.0]],
                         'defaultFormPositions': [[672.0, 352.0], [352.0, 352.0],
                                                  [352.0, 672.0], [672.0, 672.0]],
                         'formCount': 3},
            },
            'restoredHistory': {'documentBindingId': 'doc-binding', 'managerBindingId': 'mgr-binding',
                                 'generation': 1, 'revision': 12, 'position': 0, 'entries': []},
            'initialHistory': {'documentBindingId': 'doc-binding', 'managerBindingId': 'mgr-binding',
                                'generation': 1, 'revision': 12, 'position': 0, 'entries': []},
        }
    }


TEXT = {'text': lambda v: isinstance(v, str) and bool(v.strip()) and v == v.strip() and '\x00' not in v}


def attach(r):
    # Test-owned synthetic values; not the verifier's constants or any Java implementation.
    r['shapeImport'] = copy.deepcopy(base_report()['shapeImport'])
    r['deferred'] = [d for d in r['deferred'] if d['id'] != 'shapeImport']
    return r


class ShapeImportProtocolTest(unittest.TestCase):
    def setUp(self):
        self.verify = load_protocol()

    def reject(self, mutation):
        report = base_report()
        mutation(report['shapeImport'])
        with self.assertRaises((ValueError, KeyError, TypeError)):
            self.verify.verify_shape_import(report, TEXT)

    def test_positive_and_missing_section(self):
        self.verify.verify_shape_import(base_report(), TEXT)  # positive control
        with self.assertRaises(ValueError):
            self.verify.verify_shape_import({}, TEXT)

    def test_negative_matrix(self):
        mutations = [
            ('unrun', lambda e: e.update(status='NOT_RUN')),
            ('failed', lambda e: e.update(failure='error')),
            ('restore', lambda e: e.update(finallyRestored=False)),
            ('schema', lambda e: e.update(schemaVersion='mi3d-native-b0-v0')),
            ('space', lambda e: e.update(coordinateSpace='EDITOR_EVALUATED')),
            ('missing_check', lambda e: e['checks'].pop()),
            ('duplicate_check', lambda e: e['checks'].__setitem__(0, e['checks'][1])),
            ('check_failed', lambda e: e['checks'][0].update(status='FAIL')),
            ('evidence_kind', lambda e: e['checks'][0].update(evidenceKind='SYNTHETIC')),
            ('geometry_tx', lambda e: e['geometryTransaction'].update(outcome='ROLLED_BACK')),
            ('keyform_tx', lambda e: e['keyformTransaction'].update(outcome='NO_CHANGE')),
            ('restored_identity', lambda e: e['restoredHistory'].update(documentBindingId='other')),
            ('missing_history_ids', lambda e: e['restoredHistory'].pop('documentBindingId')),
            ('texture_leak', lambda e: e.update(textureIndex=3)),
            ('no_meshes', lambda e: e.pop('meshes')),
            ('mesh_name_only', lambda e: e['meshes'][0].pop('id')),
            ('binding_uses_name', lambda e: e['bindings'][0].update(mesh='MI3D_B0_TRI')),
            ('no_bindings', lambda e: e.pop('bindings')),
            ('one_binding', lambda e: e['bindings'].pop()),
            ('binding_keys', lambda e: e['bindings'][0].update(keys=[-30.0, 30.0])),
            ('binding_parameter', lambda e: e['bindings'][1].update(parameter='ParamOther')),
            ('no_protected', lambda e: e.pop('protectedDomain')),
            ('tri_indices', lambda e: e['protectedDomain']['tri'].update(indices=[0, 2, 1])),
            ('quad_uvs', lambda e: e['protectedDomain']['quad'].update(uvs=[[0.0, 0.0]] * 4)),
            ('diagonal_drift', lambda e: e['protectedDomain']['tri']['sourcePositions']
                .__setitem__(0, [692.09, 392.09])),
            ('default_form_drift', lambda e: e['protectedDomain']['quad']['defaultFormPositions']
                .__setitem__(0, [672.2, 352.0])),
            ('form_count', lambda e: e['protectedDomain']['tri'].update(formCount=4)),
        ]
        for name, mutate in mutations:
            r = base_report()
            mutate(r['shapeImport'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify.verify_shape_import(r, TEXT)

    def test_transport_budget(self):
        r = base_report()
        r['shapeImport']['checks'] = [dict(c, expected='x' * 12000) for c in r['shapeImport']['checks']]
        with self.assertRaises(ValueError):
            self.verify.verify_shape_import(r, TEXT)


if __name__ == '__main__':
    unittest.main()
