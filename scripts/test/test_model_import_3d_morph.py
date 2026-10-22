#!/usr/bin/env python3
"""Synthetic N4a morph protocol controls; no host, queue, or exact-host evidence is produced."""
import copy
import json
import math
import sys
import unittest

sys.dont_write_bytecode = True

BASE = [[16, 24], [272, 24], [272, 152], [16, 152]]
U = [[48, 40], [304, 40], [304, 168], [48, 168]]
H = [[32, 32], [288, 32], [288, 160], [32, 160]]
U2 = [[80, 56], [336, 56], [336, 184], [80, 184]]
V = [[8, 64], [264, 64], [264, 192], [8, 192]]
W = [[96, 8], [352, 8], [352, 136], [96, 136]]
SEED25 = [[24, 28], [280, 28], [280, 156], [24, 156]]
SEED75 = [[56, 44], [312, 44], [312, 172], [56, 172]]
REJECTS = {
    'createZero': 'java.lang.IllegalArgumentException',
    'createNegative': 'java.lang.IllegalArgumentException',
    'createAboveOne': 'java.lang.IllegalArgumentException',
    'createNaN': 'java.lang.IllegalArgumentException',
    'createInfinity': 'java.lang.IllegalArgumentException',
    'createDuplicate': 'java.lang.IllegalArgumentException',
    'createMissingParameter': 'java.util.NoSuchElementException',
    'lookupZero': 'java.lang.IllegalArgumentException',
    'lookupMissing': 'java.util.NoSuchElementException',
    'lookupOrdinary': 'java.lang.UnsupportedOperationException',
    'wrongVertexCount': 'java.lang.IllegalArgumentException',
    'staleRead': 'java.lang.IllegalStateException',
    'staleWrite': 'java.lang.IllegalStateException',
    'createEpsilonNear': 'java.lang.IllegalArgumentException',
}
CHECKS = ['canvas', 'fixture', 'baselineMorphKeys',
          'createCommitted', 'createCounts', 'createSeed', 'createTriple',
          'baseline', 'written', 'undone', 'redone', 'mid', 'zero',
          'undoneStale', 'redoneStale', 'baseIdentity',
          'isolationCreateCommitted', 'isolationCreateSeed', 'isolationCreateCounts', 'isolationCreateTriple',
          'isolationWriteCommitted', 'isolationWriteUndo', 'isolationWriteRedo',
          'isolatedWritten', 'isolatedMid', 'isolatedZero',
          'createNCommitted', 'createNCounts', 'createNSeed', 'createNTriple',
          'committedCommitted', 'committedSteps', 'committedSeed25', 'committedSeed75', 'committedCounts',
          'committedTriples', 'committedUndo1', 'committedRedo1', 'committedUndo2', 'committedRedo2',
          'committedFinal', 'rollbackFourStep', 'rollbackStaleN',
          'noop', 'positionWriteCommitted', 'positionWriteValidN', 'positionWriteStaleN',
          *REJECTS, 'restored']


def attach(r):
    # Test-owned synthetic values; not the verifier's constants or any Java implementation.
    e = dict(schemaVersion='mi3d-native-n4a-v1', status='PASS', failure=None,
             coordinateSpace='EDITOR_CALCULATED_ROOT_PIXELS', finallyRestored=True)
    old = r['order']
    for k in ('configuration', 'initialCounts', 'restoredCounts', 'initialCanvas', 'restoredCanvas',
              'canvas', 'canvasCommand'):
        e[k] = copy.deepcopy(old[k])
    e['fixtureCounts'] = {**e['initialCounts'], 'meshes': 1, 'parameters': e['initialCounts']['parameters'] + 4}
    b = copy.deepcopy(r['gridRedo']); b['sourceGuid'] = 'morph-source'; b['artMeshId'] = 'morph-mesh'
    for f in b['forms']:
        f['guid'] = 'morph-' + f['guid']
    for c in b['cells']:
        c['formGuid'] = 'morph-' + c['formGuid']
    e['baseline'] = b; e['baselineMorphKeys'] = []
    axes = {a['id']: a['guid'] for a in b['axes']}; gx, gy = axes['ParamMI3DN2X'], axes['ParamMI3DN2Y']
    gm, gn = 'morph-gm', 'morph-gn'
    e['morphParameters'] = {'X': {'id': 'ParamMI3DN2X', 'guid': gx}, 'Y': {'id': 'ParamMI3DN2Y', 'guid': gy},
                            'M': {'id': 'ParamMI3DN4M', 'guid': gm}, 'N': {'id': 'ParamMI3DN4N', 'guid': gn}}
    default = [c for c in b['cells'] if {v['guid']: v['value'] for v in c['coordinates']} == {gx: 0, gy: 0}][0]
    e['baseSnapshot'] = {'formGuid': default['formGuid'], 'positions': copy.deepcopy(BASE), 'drawOrder': 500}
    fU, fH, fN, fV, fW = 'morph-fU', 'morph-fH', 'morph-fN', 'morph-fV', 'morph-fW'

    def key(guid, value, form):
        return {'parameterGuid': guid, 'keyValue': value, 'formGuid': form, 'basicIdentity': 'UNKNOWN'}

    def form(guid, positions):
        return {'guid': guid, 'positions': copy.deepcopy(positions), 'drawOrder': 500}

    def expect(snapshot, forms, keys):
        s = copy.deepcopy(snapshot); s['forms'] += forms; s['morphKeys'] += keys; return s

    written = expect(b, [form(fU, U)], [key(gm, 1, fU)])
    half = expect(written, [form(fH, H)], [key(gm, .5, fH)])
    isolated = copy.deepcopy(half)
    for f in isolated['forms']:
        if f['guid'] == fU:
            f['positions'] = copy.deepcopy(U2)
    with_n = expect(isolated, [form(fN, BASE)], [key(gn, 1, fN)])
    committed = expect(with_n, [form(fV, V), form(fW, W)], [key(gm, .25, fV), key(gm, .75, fW)])
    written_v = copy.deepcopy(with_n)
    for f in written_v['forms']:
        if f['guid'] == fU:
            f['positions'] = copy.deepcopy(V)

    def h(position, size=None, revision=40):
        size = position if size is None else size
        return dict(availability='AVAILABLE', documentBindingId=old['restoredHistory']['documentBindingId'],
                    managerBindingId=old['restoredHistory']['managerBindingId'],
                    generation=old['restoredHistory']['generation'], revision=revision, position=position,
                    entries=['morph fixture'] * size, entryIds=['morph-entry-' + str(i) for i in range(size)])

    def state(snapshot, hist):
        return dict(snapshot=copy.deepcopy(snapshot), history=hist, parameters=[0, 0, 0, 0])

    clock = [300]

    def result(name, outcome, before, after):
        hb, ha = copy.deepcopy(before), copy.deepcopy(after)
        hb.update(generation=7, revision=clock[0])
        if outcome == 'COMMITTED':
            clock[0] += 1
        ha.update(generation=7, revision=clock[0])
        return dict(outcome=outcome, diagnosticId=('authoring.callback-failed: IllegalStateException: deliberate callback failure')
                    if outcome == 'ROLLED_BACK' else None,
                    receipt=dict(transactionId='morph-tx-' + name, label='MI3D N4a ' + name,
                                 historyBefore=hb, historyAfter=ha,
                                 historyEntryId=after['entryIds'][after['position'] - 1] if outcome == 'COMMITTED' else None))

    e['initialHistory'] = h(0); e['fixtureHistory'] = h(6)
    lin_before, lin_after = h(7, revision=41), h(8, revision=42)
    e['linearCreate'] = dict(
        result=result('create M1 write U', 'COMMITTED', lin_before, lin_after),
        seed={'formGuid': fU, 'positions': copy.deepcopy(BASE), 'drawOrder': 500},
        writtenForm={'formGuid': fU, 'positions': copy.deepcopy(U), 'drawOrder': 500},
        handle={'parameterGuid': gm, 'keyValue': 1},
        triple={'parameterGuid': gm, 'keyValue': 1, 'formGuid': fU},
        before=lin_before, after=lin_after, snapshot=copy.deepcopy(written))
    e['undoneHandle'] = dict(oldHandle='STALE')
    e['redoneHandle'] = dict(oldHandle='STALE', fresh={'formGuid': fU, 'positions': copy.deepcopy(U), 'drawOrder': 500},
                             freshHandle={'parameterGuid': gm, 'keyValue': 1},
                             detached={'formGuid': fU, 'positions': copy.deepcopy(U), 'drawOrder': 500})
    ic_before, ic_after = h(10, revision=45), h(11, revision=46)
    iso_create = dict(result=result('create M.5', 'COMMITTED', ic_before, ic_after),
                      seed={'formGuid': fH, 'positions': copy.deepcopy(H), 'drawOrder': 500},
                      triple={'parameterGuid': gm, 'keyValue': .5, 'formGuid': fH},
                      before=ic_before, after=ic_after, snapshot=copy.deepcopy(half))
    iw = dict(before=state(half, h(11, revision=47)), written=state(isolated, h(12, revision=48)),
              undone=state(half, h(11, 12, 49)), redone=state(isolated, h(12, 12, 50)))
    iw['result'] = result('write U2', 'COMMITTED', iw['before']['history'], iw['written']['history'])
    e['isolation'] = dict(create=iso_create, write=iw)
    cn_before, cn_after = h(15, revision=53), h(16, revision=54)
    create_n = dict(result=result('create N1', 'COMMITTED', cn_before, cn_after),
                    seed={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500},
                    triple={'parameterGuid': gn, 'keyValue': 1, 'formGuid': fN},
                    before=cn_before, after=cn_after, snapshot=copy.deepcopy(with_n))
    cc_before, cc_after = h(16, revision=55), h(17, revision=56)
    cc = dict(result=result('four-step', 'COMMITTED', cc_before, cc_after), steps=4,
              seed25={'formGuid': fV, 'positions': copy.deepcopy(SEED25), 'drawOrder': 500},
              seed75={'formGuid': fW, 'positions': copy.deepcopy(SEED75), 'drawOrder': 500},
              triples=[{'parameterGuid': gm, 'keyValue': .25, 'formGuid': fV},
                       {'parameterGuid': gm, 'keyValue': .75, 'formGuid': fW}],
              before=state(with_n, cc_before), after=state(committed, cc_after),
              mutated=dict(snapshot=copy.deepcopy(committed), history=copy.deepcopy(cc_before), parameters=[0, 0, 0, 0]),
              undone1=state(with_n, h(16, 17, 57)), redone1=state(committed, h(17, 17, 58)),
              undone2=state(with_n, h(16, 17, 59)), redone2=state(committed, h(17, 17, 60)),
              final=state(with_n, h(16, 17, 61)))
    rb_hist = h(16, 17, 62)
    rb_mutated = copy.deepcopy(committed)
    rb_mutated['morphKeys'] = rb_mutated['morphKeys'][:3] + [key(gm, .25, 'rb-fV'), key(gm, .75, 'rb-fW')]
    rb_mutated['forms'] = rb_mutated['forms'][:12] + [form('rb-fV', V), form('rb-fW', W)]
    rb = dict(result=result('four-step rollback', 'ROLLED_BACK', rb_hist, rb_hist), steps=4,
              seed25={'formGuid': 'rb-fV', 'positions': copy.deepcopy(SEED25), 'drawOrder': 500},
              seed75={'formGuid': 'rb-fW', 'positions': copy.deepcopy(SEED75), 'drawOrder': 500},
              before=state(with_n, rb_hist), after=state(with_n, copy.deepcopy(rb_hist)),
              mutated=dict(snapshot=rb_mutated, history=copy.deepcopy(rb_hist), parameters=[0, 0, 0, 0]),
              callbackFailure='N4A_ABORT',
              preFailure=dict(nHandle='VALID',
                              handle={'parameterGuid': gn, 'keyValue': 1},
                              detached={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500}),
              postFailure=dict(nHandle='STALE',
                               relooked={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500},
                               relookedHandle={'parameterGuid': gn, 'keyValue': 1},
                               detached={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500}))
    noop_hist = h(16, 17, 63)
    pw_hist, undo_hist = h(17, 18, 64), h(16, 18, 65)
    pw = dict(detachedN={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500},
              detachedNHandle={'parameterGuid': gn, 'keyValue': 1},
              noop=dict(result=result('noop', 'NO_CHANGE', noop_hist, noop_hist),
                        before=state(with_n, noop_hist), after=state(with_n, copy.deepcopy(noop_hist)), nHandle='VALID'),
              result=result('position write', 'COMMITTED', noop_hist, pw_hist),
              written=state(written_v, pw_hist), nHandleAfterCommit='VALID',
              writeUndo=dict(nHandle='STALE',
                             relooked={'formGuid': fN, 'positions': copy.deepcopy(BASE), 'drawOrder': 500},
                             relookedHandle={'parameterGuid': gn, 'keyValue': 1},
                             state=state(with_n, undo_hist)))
    e['controls'] = dict(before=state(isolated, h(15, revision=52)), createN=create_n, committed=cc,
                        rolledBack=rb, positionWrite=pw)
    rej_hist = h(16, 18, 66)
    e['rejections'] = {k: dict(before=state(with_n, copy.deepcopy(rej_hist)),
                               after=state(with_n, copy.deepcopy(rej_hist)), exception=v)
                       for k, v in REJECTS.items()}
    e['restoredHistory'] = h(0, 18, 70)
    cases = [('baseline', 1, BASE, lin_before), ('written', 1, U, lin_after), ('undone', 1, BASE, h(7, 8, 43)),
             ('redone', 1, U, lin_after), ('mid', .5, H, h(9, revision=44)), ('zero', 0, BASE, h(10, 10, 44)),
             ('isolatedWritten', 1, U2, h(13, revision=51)), ('isolatedMid', .5, H, h(14, revision=51)),
             ('isolatedZero', 0, BASE, h(15, revision=51))]
    e['samples'] = []

    def observed(value, step, consumed_version, positions):
        params = [{'guid': gx, 'id': 'ParamMI3DN2X', 'value': 0}, {'guid': gy, 'id': 'ParamMI3DN2Y', 'value': 0},
                  {'guid': gm, 'id': 'ParamMI3DN4M', 'value': value}, {'guid': gn, 'id': 'ParamMI3DN4N', 'value': 0}]
        return dict(artMeshId=b['artMeshId'], sourceGuid=b['sourceGuid'], passEntryTimeMillis=200 + step,
                    live=dict(updateVersion=900 + step, parameters=copy.deepcopy(params)),
                    consumed=dict(updateVersion=consumed_version, parameters=copy.deepcopy(params)),
                    calculated=dict(positions=copy.deepcopy(positions), drawOrder=500,
                                    coordinateType='DeformerLocal', interpolated=True))

    for i, (name, value, expected, hist) in enumerate(cases):
        stored = b if name in ('baseline', 'undone') else (written if name in ('written', 'redone', 'mid', 'zero') else isolated)
        requested = observed(value, i, 30 + i, b['positions']); actual = observed(value, i, 31 + i, expected)
        row = dict(id=name, m=value, x=0, y=0, n=0, status='PASS', expectedPositions=copy.deepcopy(expected),
                   waitLimitMillis=15000, before=copy.deepcopy(requested), requested=requested, actual=actual,
                   lastObservation=copy.deepcopy(actual), attempts=2, elapsedMillis=25, waitReason='CONSUMED',
                   maxErrorPixels=0, stored=copy.deepcopy(stored), observedStored=copy.deepcopy(stored),
                   history=hist, readHistoryBefore=copy.deepcopy(hist), readHistoryAfter=copy.deepcopy(hist))
        e['samples'].append(row)
    e['checks'] = [dict(id=k, status='PASS', actual=True, evidenceKind='EXACT_HOST') for k in CHECKS]
    r['morph'] = e
    return r


def load_protocol():
    from scripts.test import test_model_import_3d_host_validation
    return test_model_import_3d_host_validation


class MorphProtocolTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.protocol = load_protocol(); cls.verify = cls.protocol.VERIFY
        # JSON copies model the wire boundary and avoid repeatedly building unrelated matrices.
        cls.fixture_json = json.dumps(cls.protocol.ordinary_fixture())

    def setUp(self):
        self.fixture = json.loads(self.fixture_json)

    def verify_section(self, report):
        return self.verify.MORPH['verify_morph'](report, self.verify.snapshot, self.verify.EVALUATION)

    def test_positive_and_missing_morph(self):
        self.verify.verify_native(self.fixture, 'synthetic')
        r = json.loads(self.fixture_json); r.pop('morph')
        with self.assertRaises((ValueError, KeyError, TypeError)):
            self.verify.verify_native(r, 'synthetic')

    def test_morph_negative_matrix(self):
        mutations = [
            ('unrun', lambda e: e.update(status='NOT_RUN')), ('failed', lambda e: e.update(failure='error')),
            ('receipt_diagnostic_foreign', lambda e: e['controls']['rolledBack']['result'].update(
                diagnosticId='authoring.foreign-code: IllegalStateException: x')),
            ('receipt_diagnostic_wrong_type', lambda e: e['controls']['rolledBack']['result'].update(diagnosticId=7)),
            ('restore', lambda e: e.update(finallyRestored=False)), ('missing_check', lambda e: e['checks'].pop()),
            ('duplicate_check', lambda e: e['checks'].__setitem__(0, e['checks'][1])),
            ('missing_sample', lambda e: e['samples'].pop()), ('sample_order', lambda e: e['samples'].reverse()),
            ('materialized_base', lambda e: e['baselineMorphKeys'].append({'parameterGuid': 'gm'})),
            ('materialized_key', lambda e: e['baseline']['morphKeys'].append({'parameterGuid': 'gm'})),
            ('old_source', lambda e: e['baseline'].update(sourceGuid='order-source')),
            ('old_form', lambda e: e['baseline']['forms'][0].update(guid='order-form-0')),
            ('parameter_count', lambda e: e['fixtureCounts'].update(parameters=e['fixtureCounts']['parameters'] - 1)),
            ('guid_alias', lambda e: e['morphParameters']['N'].update(guid=e['morphParameters']['M']['guid'])),
            ('id_as_guid', lambda e: e['morphParameters']['M'].update(guid='ParamMI3DN4M')),
            ('axis_guid', lambda e: e['morphParameters']['M'].update(guid='gx')),
            ('wrong_seed', lambda e: e['linearCreate']['seed'].update(positions=SEED25)),
            ('delta_positions', lambda e: e['linearCreate']['writtenForm'].update(
                positions=[[32, 16], [32, 16], [32, 16], [32, 16]])),
            ('wrong_triple', lambda e: e['linearCreate']['triple'].update(keyValue=.5)),
            ('snapshot_alias', lambda e: e['linearCreate']['snapshot']['morphKeys'][0].update(formGuid='morph-1:0')),
            ('undone_valid', lambda e: e['undoneHandle'].update(oldHandle='VALID')),
            ('undone_missing', lambda e: e.pop('undoneHandle')),
            ('valid_stale', lambda e: e['redoneHandle'].update(oldHandle='VALID')),
            ('detached_drift', lambda e: e['redoneHandle']['detached'].update(positions=H)),
            ('base_pollution', lambda e: e['baseSnapshot'].update(positions=U)),
            ('half_seed', lambda e: e['isolation']['create']['seed'].update(positions=BASE)),
            ('retained_key', lambda e: e['isolation']['create']['snapshot']['forms'][9].update(positions=H)),
            ('write_isolation', lambda e: e['isolation']['write']['written']['snapshot']['forms'][10].update(positions=U2)),
            ('write_undo', lambda e: e['isolation']['write']['undone']['snapshot']['forms'][9].update(positions=U2)),
            ('n_seed', lambda e: e['controls']['createN']['seed'].update(positions=H)),
            ('committed_steps', lambda e: e['controls']['committed'].update(steps=3)),
            ('seed25', lambda e: e['controls']['committed']['seed25'].update(positions=BASE)),
            ('seed75', lambda e: e['controls']['committed']['seed75'].update(positions=H)),
            ('committed_counts', lambda e: e['controls']['committed']['after']['snapshot']['morphKeys'].pop()),
            ('committed_undo', lambda e: e['controls']['committed']['undone1']['snapshot']['morphKeys'].pop()),
            ('committed_final', lambda e: e['controls']['committed']['final']['history'].update(position=17)),
            ('no_sentinel', lambda e: e['controls']['rolledBack'].update(callbackFailure='NOT_THROWN')),
            ('rollback_outcome', lambda e: e['controls']['rolledBack']['result'].update(outcome='COMMITTED')),
            ('rollback_state', lambda e: e['controls']['rolledBack']['after']['snapshot']['morphKeys'].pop()),
            ('pre_stale', lambda e: e['controls']['rolledBack']['preFailure'].update(nHandle='STALE')),
            ('pre_missing', lambda e: e['controls']['rolledBack'].pop('preFailure')),
            ('pre_handle', lambda e: e['controls']['rolledBack']['preFailure']['handle'].update(parameterGuid='gm')),
            ('post_valid', lambda e: e['controls']['rolledBack']['postFailure'].update(nHandle='VALID')),
            ('post_missing', lambda e: e['controls']['rolledBack'].pop('postFailure')),
            ('relooked_drift_rb', lambda e: e['controls']['rolledBack']['postFailure']['relooked'].update(positions=H)),
            ('relooked_handle_rb', lambda e: e['controls']['rolledBack']['postFailure'].update(
                relookedHandle={'parameterGuid': 'gm', 'keyValue': 1})),
            ('rollback_mutated', lambda e: e['controls']['rolledBack']['mutated']['snapshot']['morphKeys'][3].update(keyValue=.5)),
            ('early_commit', lambda e: e['controls']['rolledBack']['mutated']['history'].update(position=17)),
            ('noop_stale', lambda e: e['controls']['positionWrite']['noop'].update(nHandle='STALE')),
            ('noop_outcome', lambda e: e['controls']['positionWrite']['noop']['result'].update(outcome='COMMITTED')),
            ('noop_changed', lambda e: e['controls']['positionWrite']['noop']['after']['history'].update(revision=99)),
            ('commit_stale', lambda e: e['controls']['positionWrite'].update(nHandleAfterCommit='STALE')),
            ('commit_missing', lambda e: e['controls']['positionWrite'].pop('nHandleAfterCommit')),
            ('relooked_drift', lambda e: e['controls']['positionWrite']['writeUndo'].update(
                relooked={'formGuid': 'other', 'positions': BASE, 'drawOrder': 500})),
            ('relooked_handle', lambda e: e['controls']['positionWrite']['writeUndo'].update(
                relookedHandle={'parameterGuid': 'gm', 'keyValue': .5})),
            ('rejection_type', lambda e: e['rejections']['createDuplicate'].update(exception='java.util.NoSuchElementException')),
            ('rejection_state', lambda e: e['rejections']['lookupMissing']['after']['history'].update(revision=88)),
            ('rejection_missing', lambda e: e['rejections'].pop('staleWrite')),
            ('receipt_label', lambda e: e['linearCreate']['result']['receipt'].update(label='MI3D N3c wrong')),
            ('receipt_binding', lambda e: e['linearCreate']['result']['receipt']['historyAfter'].update(managerBindingId='other')),
            ('receipt_tip', lambda e: e['linearCreate']['result']['receipt'].update(historyEntryId='wrong')),
            ('receipt_counter', lambda e: e['controls']['positionWrite']['result']['receipt']['historyAfter'].update(revision=-1)),
            ('receipt_clock', lambda e: e['isolation']['write']['result']['receipt']['historyBefore'].update(revision=999)),
            ('receipt_generation', lambda e: e['controls']['createN']['result']['receipt']['historyBefore'].update(generation=99)),
            ('canvas', lambda e: e['canvas'].update(width=2048)),
            ('restored_counts', lambda e: e['restoredCounts'].update(meshes=1)),
            ('restored_position', lambda e: e['restoredHistory'].update(position=5)),
            ('parameters', lambda e: e['controls']['committed']['before'].update(parameters=[0, 0, 1, 0])),
        ]
        for name, mutate in mutations:
            r = json.loads(self.fixture_json); mutate(r['morph'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify_section(r)

    def test_rollback_oracle_targets_the_injected_defect(self):
        # Positive control first: the corrected fixture must pass before any mutation counts.
        self.verify.verify_native(self.fixture, 'synthetic')
        cases = [
            ('source_positions', lambda s: s['mutated']['snapshot']['positions'][0].__setitem__(0, -999),
             'with_n plus its own two members'),
            ('source_uvs', lambda s: s['mutated']['snapshot']['uvs'][0].__setitem__(0, -1),
             'with_n plus its own two members'),
            ('source_indices', lambda s: s['mutated']['snapshot']['indices'].__setitem__(0, 9),
             'with_n plus its own two members'),
            ('default_form', lambda s: s['mutated']['snapshot']['forms'][0]['positions'][0].__setitem__(0, -999),
             'with_n plus its own two members'),
            ('old_form', lambda s: s['mutated']['snapshot']['forms'][9].update(positions=H),
             'with_n plus its own two members'),
            ('old_key', lambda s: s['mutated']['snapshot']['morphKeys'][0].update(keyValue=.5),
             'member set'),
            ('axis', lambda s: s['mutated']['snapshot']['axes'][0].update(minimum=-2),
             'with_n plus its own two members'),
            ('cell', lambda s: s['mutated']['snapshot']['cells'][0].update(formGuid='morph-fN'),
             'with_n plus its own two members'),
            ('sliders', lambda s: s['mutated'].__setitem__('parameters', [0, 0, 1, 1]),
             'mutated parameters'),
            ('new_form_order', lambda s: s['mutated']['snapshot']['forms'][-1].__setitem__('drawOrder', 777),
             'with_n plus its own two members'),
            ('duplicate_member_guid', lambda s: s['mutated']['snapshot']['morphKeys'][4].__setitem__(
                'formGuid', s['mutated']['snapshot']['morphKeys'][3]['formGuid']),
             'new member identity'),
            ('aliased_member_guid', lambda s: s['mutated']['snapshot']['morphKeys'][3].__setitem__('formGuid', 'morph-fN'),
             'reused'),
            ('seed25_identity', lambda s: s['seed25'].__setitem__('formGuid', 'wrong-seed-guid'),
             '.25 seed identity'),
            ('seed25_positions', lambda s: s['seed25'].update(positions=BASE), '.25 seed identity'),
            ('seed75_order', lambda s: s['seed75'].__setitem__('drawOrder', 777), '.75 seed identity'),
            ('extra_form', lambda s: s['mutated']['snapshot']['forms'].append(
                {'guid': 'extra', 'positions': BASE, 'drawOrder': 500}), 'with_n plus its own two members'),
            ('missing_key', lambda s: s['mutated']['snapshot']['morphKeys'].pop(), 'member set'),
            ('duplicate_form_guid', lambda s: s['mutated']['snapshot']['forms'][-1].__setitem__('guid', 'morph-fN'),
             'with_n plus its own two members'),
        ]
        for name, mutate, reason in cases:
            r = json.loads(self.fixture_json)
            mutate(r['morph']['controls']['rolledBack'])
            with self.subTest(name=name):
                try:
                    self.verify_section(r)
                except ValueError as error:
                    self.assertIn(reason, str(error), name + ' rejected for the wrong reason: ' + str(error))
                else:
                    self.fail(name + ' was wrongly accepted')

    def test_first_consumed_morph_cannot_be_waited_away(self):
        changes = [
            ('position', lambda s: s['actual']['calculated']['positions'][0].__setitem__(0, 100)),
            ('expected', lambda s: s.update(expectedPositions=BASE)),
            ('form', lambda s: s['actual'].update(calculated=None)),
            ('stale', lambda s: s['actual']['consumed'].update(updateVersion=s['requested']['consumed']['updateVersion'])),
            ('stamp', lambda s: s['actual'].update(passEntryTimeMillis=-1)),
            ('live_m', lambda s: s['actual']['live']['parameters'][2].update(value=0)),
            ('live_n', lambda s: s['actual']['live']['parameters'][3].update(value=1)),
            ('live_x', lambda s: s['actual']['live']['parameters'][0].update(value=1)),
            ('consumed', lambda s: s['actual']['consumed']['parameters'][2].update(value=0)),
            ('timeout', lambda s: s.update(waitReason='TIMEOUT')), ('budget', lambda s: s.update(elapsedMillis=15000)),
            ('attempts', lambda s: s.update(attempts=0)), ('source', lambda s: s['actual'].update(sourceGuid='other')),
            ('history', lambda s: s['readHistoryAfter'].update(revision=100)),
            ('stored', lambda s: s['observedStored']['morphKeys'].pop()),
            ('order', lambda s: s['actual']['calculated'].update(drawOrder=600)),
            ('space', lambda s: s['actual']['calculated'].update(coordinateType='Basic Coord')),
            ('interpolated', lambda s: s['actual']['calculated'].update(interpolated=False)),
            ('intent', lambda s: s.update(m=0)),
        ]
        for name, mutate in changes:
            r = json.loads(self.fixture_json); s = r['morph']['samples'][1]; mutate(s)
            s['lastObservation'] = copy.deepcopy(s['actual'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify_section(r)
        r = json.loads(self.fixture_json); r['morph']['samples'][1]['lastObservation']['calculated']['positions'][0] = [0, 0]
        with self.assertRaises(ValueError):
            self.verify_section(r)

    def test_tolerant_positions_and_independent_clocks(self):
        r = json.loads(self.fixture_json); s = r['morph']['samples'][1]
        s['actual']['calculated']['positions'][0] = [48.05, 40.05]; s['maxErrorPixels'] = math.hypot(.05, .05)
        s['lastObservation'] = copy.deepcopy(s['actual']); self.verify_section(r)
        s['actual']['calculated']['positions'][0] = [48.08, 40.08]; s['maxErrorPixels'] = math.hypot(.08, .08)
        s['lastObservation'] = copy.deepcopy(s['actual'])
        with self.assertRaises(ValueError): self.verify_section(r)
        r = json.loads(self.fixture_json)
        for tx in (r['morph']['linearCreate']['result'], r['morph']['isolation']['create']['result'],
                   r['morph']['isolation']['write']['result'], r['morph']['controls']['createN']['result'],
                   r['morph']['controls']['committed']['result'], r['morph']['controls']['rolledBack']['result'],
                   r['morph']['controls']['positionWrite']['noop']['result'], r['morph']['controls']['positionWrite']['result']):
            for side in ('historyBefore', 'historyAfter'):
                tx['receipt'][side]['revision'] += 1000  # Provider-local clocks never compare to facade counters.
        self.verify_section(r)


if __name__ == '__main__':
    unittest.main()
