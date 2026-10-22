#!/usr/bin/env python3
"""Synthetic N3b verifier tests. No host execution, queues, or fabricated exact-host evidence files."""
import copy
import json
import math
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]


def attach(r):
    # Independent data fixture, not the verifier's constants or any Java implementation.
    e = {'schemaVersion': 'mi3d-native-n3b-v1', 'status': 'PASS', 'failure': None,
         'coordinateSpace': 'EDITOR_CALCULATED_ROOT_PIXELS', 'finallyRestored': True}
    directory = r'C:\Program Files\Live2D Cubism 5.3'
    e['configuration'] = dict(properties={'sun.java2d.opengl': None, 'jogl.gljpanel.noogl': None},
        inputArguments=['-Dsun.java2d.d3d=false'], workingDirectory=directory,
        classpath=[r'app\lib\Live2D_Cubism.jar', r'app\lib\jogl\jogl-all.jar'],
        joglPath=directory + r'\app\lib\jogl\jogl-all.jar', cubismPath=directory + r'\app\lib\Live2D_Cubism.jar',
        joglSha256='7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9',
        backend='OFFSCREEN', backendEvidence='CONFIGURATION_DERIVED')
    e['initialCounts'] = copy.deepcopy(r['restoredCounts']); e['restoredCounts'] = copy.deepcopy(e['initialCounts'])
    e['fixtureCounts'] = {**e['initialCounts'], 'meshes': 1, 'parameters': e['initialCounts']['parameters'] + 2}
    e['initialCanvas'] = dict(width=2000, height=1000, originX=1000, originY=500, pixelsPerUnit=1000)
    e['restoredCanvas'] = copy.deepcopy(e['initialCanvas'])
    e['canvas'] = dict(width=1024, height=1024, originX=512, originY=512, pixelsPerUnit=1000)
    e['canvasCommand'] = 'EXECUTED'
    b = copy.deepcopy(r['gridRedo']); b['sourceGuid'] = 'evaluation-source'; b['artMeshId'] = 'evaluation-mesh'
    for f in b['forms']: f['guid'] = 'evaluation-' + f['guid']
    for c in b['cells']: c['formGuid'] = 'evaluation-' + c['formGuid']
    e['baseline'] = b; written = copy.deepcopy(b)
    positive = [[48, 40], [304, 40], [304, 168], [48, 168]]
    negative = [[-4, 64], [252, 64], [252, 192], [-4, 192]]
    written['forms'][7]['positions'] = positive; written['forms'][1]['positions'] = negative
    e['expectedWritten'] = written
    def h(position, size=None, revision=1):
        size = position if size is None else size
        return dict(availability='AVAILABLE', generation=r['historyRestored']['generation'], revision=revision, position=position,
            documentBindingId=r['historyRestored']['documentBindingId'], managerBindingId=r['historyRestored']['managerBindingId'],
            entries=['fixture entry'] * size, entryIds=['eval-entry-' + str(i) for i in range(size)])
    e['initialHistory'] = h(0); e['fixtureHistory'] = h(5); e['writeBefore'] = h(6); e['writeAfter'] = h(7)
    e['restoredHistory'] = h(0, 12, 12); e['writeOutcome'] = 'COMMITTED'; e['writeEntryId'] = 'eval-entry-6'
    samples = [('baseline', 1, b['positions'], 6), ('written', 1, positive, 7), ('undone', 1, b['positions'], 6),
               ('redone', 1, positive, 7), ('positiveMid', .5, [[32, 32], [288, 32], [288, 160], [32, 160]], 8),
               ('defaultAfterPositive', 0, b['positions'], 9), ('negativeEnd', -1, negative, 10),
               ('negativeMid', -.5, [[6, 44], [262, 44], [262, 172], [6, 172]], 11), ('defaultFinal', 0, b['positions'], 12)]
    def observed(x, step, consumed_version, positions):
        params = [{'guid': 'gx', 'id': 'ParamMI3DN2X', 'value': x}, {'guid': 'gy', 'id': 'ParamMI3DN2Y', 'value': 0}]
        return dict(artMeshId=b['artMeshId'], sourceGuid=b['sourceGuid'], passEntryTimeMillis=100 + step,
            live=dict(updateVersion=900 + step, parameters=copy.deepcopy(params)),
            consumed=dict(updateVersion=consumed_version, parameters=copy.deepcopy(params)),
            calculated=dict(positions=copy.deepcopy(positions), drawOrder=500, coordinateType='DeformerLocal', interpolated=True))
    e['samples'] = []
    for i, (name, x, expected, pos) in enumerate(samples):
        stored = b if name in ('baseline', 'undone') else written
        requested = observed(x, i, 10 + i, b['positions']); actual = observed(x, i, 11 + i, expected)
        hist = h(pos, 7 if name == 'undone' else pos)
        row = dict(id=name, x=x, y=0, status='PASS', expectedPositions=copy.deepcopy(expected), waitLimitMillis=15000,
            before=copy.deepcopy(requested), requested=requested, actual=actual, lastObservation=copy.deepcopy(actual),
            attempts=2, elapsedMillis=25, waitReason='CONSUMED', maxErrorPixels=0,
            stored=copy.deepcopy(stored), observedStored=copy.deepcopy(stored), history=hist,
            readHistoryBefore=copy.deepcopy(hist), readHistoryAfter=copy.deepcopy(hist))
        e['samples'].append(row)
    e['checks'] = [dict(id=k, status='PASS', actual=True, expected='synthetic assertion', evidenceKind='EXACT_HOST')
                   for k in ['canvas', 'fixture', 'singleUndo', *[s[0] for s in samples], 'restored']]
    r['evaluation'] = e
    return r


def load_protocol():
    from scripts.test import test_model_import_3d_host_validation
    return test_model_import_3d_host_validation


class EvaluationProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol = load_protocol(); cls.verify = cls.protocol.VERIFY
        # JSON copies model the wire boundary and avoid repeatedly building unrelated matrices.
        cls.fixture_json = json.dumps(cls.protocol.ordinary_fixture())

    def setUp(self):
        self.fixture = json.loads(self.fixture_json)

    def verify_section(self, report):
        return self.verify.EVALUATION['verify_evaluation'](report, self.verify.snapshot)

    def test_positive_requires_all_three_matrices(self):
        self.verify.verify_native(self.fixture, 'synthetic')
        for field in ('evaluation', 'ordinaryWrite'):
            r = json.loads(self.fixture_json); r.pop(field)
            with self.subTest(field=field), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify.verify_native(r, 'synthetic')

    def test_n3b_negative_matrix(self):
        mutations = [
            ('unrun', lambda e: e.update(status='NOT_RUN')), ('no_restore', lambda e: e.update(finallyRestored=False)),
            ('samples_short', lambda e: e['samples'].pop()), ('checks_short', lambda e: e['checks'].pop()),
            ('sample_order', lambda e: e['samples'].reverse()), ('canvas', lambda e: e['canvas'].update(width=2048)),
            ('canvas_restore', lambda e: e['restoredCanvas'].update(originY=0)),
            ('counts', lambda e: e['restoredCounts'].update(meshes=1)),
            ('unreviewed_gl', lambda e: e['configuration']['properties'].update({'sun.java2d.opengl': 'true'})),
            ('changed_jogl', lambda e: e['configuration'].update(joglSha256='0' * 64)),
            ('backend_claim', lambda e: e['configuration'].update(backendEvidence='EXACT_HOST')),
            ('startup_flag', lambda e: e['configuration']['inputArguments'].append('-Dsun.java2d.opengl=true')),
            ('duplicate_jogl', lambda e: e['configuration']['classpath'].append(e['configuration']['classpath'][1])),
            ('mixed_install', lambda e: e['configuration'].update(cubismPath=r'C:\elsewhere\app\lib\Live2D_Cubism.jar')),
            ('write_tip', lambda e: e.update(writeEntryId='wrong')), ('target_pollution', lambda e: e['expectedWritten']['forms'][4]['positions'][0].__setitem__(0, 9)),
        ]
        for name, mutate in mutations:
            r = json.loads(self.fixture_json); mutate(r['evaluation'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify_section(r)
        changes = [
            ('old_consumption', lambda s: s['actual']['consumed'].update(updateVersion=s['requested']['consumed']['updateVersion'])),
            ('no_time', lambda s: s['actual'].update(passEntryTimeMillis=-1)),
            ('no_form', lambda s: s['actual'].update(calculated=None)),
            ('wrong_geometry', lambda s: s['actual']['calculated']['positions'][0].__setitem__(0, 999)),
            ('different_fixation', lambda s: s['lastObservation'].update(passEntryTimeMillis=0)),
            ('wrong_source', lambda s: s['actual'].update(sourceGuid='other')),
            ('not_consumed', lambda s: s['actual']['consumed']['parameters'][0].update(value=0)),
            ('wrong_live', lambda s: s['actual']['live']['parameters'][0].update(value=0)),
            ('duplicate_guid', lambda s: s['actual']['live']['parameters'].append(s['actual']['live']['parameters'][0])),
            ('wrong_order', lambda s: s['actual']['calculated'].update(drawOrder=501)),
            ('not_interpolated', lambda s: s['actual']['calculated'].update(interpolated=False)),
            ('wrong_space', lambda s: s['actual']['calculated'].update(coordinateType='Basic Coord')),
            ('stored_changed', lambda s: s['observedStored']['positions'][0].__setitem__(0, 17)),
            ('history_read_changed', lambda s: s['readHistoryAfter'].update(revision=99)),
            ('timeout', lambda s: s.update(waitReason='TIMEOUT')), ('over_budget', lambda s: s.update(elapsedMillis=15000)),
            ('false_counter', lambda s: s['actual']['consumed'].update(updateVersion=True)),
            ('zero_attempts', lambda s: s.update(attempts=0)), ('forged_expected', lambda s: s['expectedPositions'][0].__setitem__(0, 999)),
        ]
        for name, mutate in changes:
            r = json.loads(self.fixture_json); s = r['evaluation']['samples'][4]; mutate(s)
            # Reseal the ordinary actual/last copy too, so semantic checks (not just consistency) reject.
            if name != 'different_fixation': s['lastObservation'] = copy.deepcopy(s['actual'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify_section(r)

    def test_counter_domains_and_euclidean_tolerance(self):
        r = json.loads(self.fixture_json)
        for s in r['evaluation']['samples']:
            s['requested']['consumed']['updateVersion'] = 2**31 - 1
            s['actual']['consumed']['updateVersion'] = -(2**31)
            s['actual']['live']['updateVersion'] = 8
            s['lastObservation'] = copy.deepcopy(s['actual'])
        self.verify_section(r)
        s = r['evaluation']['samples'][4]; s['actual']['calculated']['positions'][0] = [32.05, 32.05]
        s['maxErrorPixels'] = math.hypot(.05, .05); s['lastObservation'] = copy.deepcopy(s['actual'])
        self.verify_section(r)
        s['actual']['calculated']['positions'][0] = [32.08, 32.08]
        s['maxErrorPixels'] = math.hypot(.08, .08); s['lastObservation'] = copy.deepcopy(s['actual'])
        with self.assertRaises(ValueError): self.verify_section(r)

    def test_native_console_exception_is_not_a_pass(self):
        verify = self.verify.EVALUATION['verify_console']
        verify('Cubism 5.3.02\nSLF4J: NOP logger\nJOCL unavailable\n')
        for bad in ('', 'java.lang.IllegalStateException\n\tat com.live2d.cubism.view.ay.a(ay.java:1)\n',
                    '\tat com.live2d.cubism.doc.model.drawable.CArtMeshForm.interpolate__testImpl(Unknown Source)\n'):
            with self.subTest(text=bad), self.assertRaises(ValueError): verify(bad)


if __name__ == '__main__':
    unittest.main()
