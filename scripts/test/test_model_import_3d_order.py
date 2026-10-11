#!/usr/bin/env python3
"""Synthetic order protocol controls; no host, queue, or exact-host evidence is produced."""
import copy
import importlib.util
import math
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True


def attach(r):
    # Test-owned constants, not imported from the verifier or native probe.
    e = dict(schemaVersion='mi3d-native-n3c-v1', status='PASS', failure=None,
             coordinateSpace='EDITOR_CALCULATED_ROOT_PIXELS', finallyRestored=True)
    old = r['evaluation']
    for key in ('configuration', 'initialCounts', 'restoredCounts', 'fixtureCounts', 'initialCanvas', 'restoredCanvas', 'canvas', 'canvasCommand'):
        e[key] = copy.deepcopy(old[key])
    b = copy.deepcopy(r['gridRedo']); b['sourceGuid'] = 'order-source'; b['artMeshId'] = 'order-mesh'
    for f in b['forms']: f['guid'] = 'order-' + f['guid']
    for c in b['cells']: c['formGuid'] = 'order-' + c['formGuid']
    w = copy.deepcopy(b); w['forms'][7]['drawOrder'] = 1000; w['forms'][1]['drawOrder'] = 0
    e['baseline'] = b; e['expectedWritten'] = w

    def h(pos, size=None, revision=20, branch=False):
        size = pos if size is None else size
        ids = ['order-entry-' + str(i) for i in range(size)]
        if branch and size > 5: ids[5] = 'parameter-branch-entry'
        return dict(availability='AVAILABLE', documentBindingId=old['restoredHistory']['documentBindingId'],
                    managerBindingId=old['restoredHistory']['managerBindingId'], generation=old['restoredHistory']['generation'],
                    revision=revision, position=pos, entries=['order fixture'] * size, entryIds=ids)

    def state(s, hist):
        return copy.deepcopy(dict(snapshot=s, history=hist, parameters=[0, 0]))

    clock = [100]

    def result(name, outcome, before, after):
        hb, ha = copy.deepcopy(before), copy.deepcopy(after)
        hb.update(generation=7, revision=clock[0])
        if outcome == 'COMMITTED': clock[0] += 1
        ha.update(generation=7, revision=clock[0])
        return dict(outcome=outcome, diagnosticId=('authoring.callback-failed: IllegalStateException: deliberate callback failure')
                    if outcome == 'ROLLED_BACK' else None,
                    receipt=dict(transactionId='order-tx-' + name, label='MI3D N3c ' + name,
                                 historyBefore=hb, historyAfter=ha,
                                 historyEntryId=after['entryIds'][after['position'] - 1] if outcome == 'COMMITTED' else None))

    e['initialHistory'] = h(0); e['fixtureHistory'] = h(5)
    hn = h(6, revision=21)
    n = dict(before=state(b, e['fixtureHistory']), written=state(w, hn), undone=state(b, h(5, 6, 22)),
             redone=state(w, h(6, 6, 23)), restored=state(b, h(5, 6, 24)))
    n['result'] = result('noncurrent targets', 'COMMITTED', e['fixtureHistory'], hn); e['nonCurrent'] = n
    e['controls'] = {'noop': dict(before=state(w, hn), after=state(w, hn), result=result('noop', 'NO_CHANGE', hn, hn))}
    rollbacks = []
    moved = [[48, 40], [304, 40], [304, 168], [48, 168]]
    for order_first in (False, True):
        for same in (False, True):
            name = 'rollback-' + str(order_first).lower() + '-' + str(same).lower(); rollbacks.append(name)
            m = copy.deepcopy(w); second = 7 if same else 1
            m['forms'][7 if order_first else second]['drawOrder'] = 600
            m['forms'][second if order_first else 7]['positions'] = copy.deepcopy(moved)
            e['controls'][name] = dict(before=state(w, hn), after=state(w, hn), mutated=state(m, hn), steps=2,
                                      callbackFailure='N3C_ABORT', result=result(name, 'ROLLED_BACK', hn, hn))
    name = 'rollback-triple'; rollbacks.append(name); m = copy.deepcopy(w)
    m['forms'][7]['drawOrder'] = 700; m['forms'][7]['positions'] = copy.deepcopy(moved)
    e['controls'][name] = dict(before=state(w, hn), after=state(w, hn), mutated=state(m, hn), steps=3,
                              callbackFailure='N3C_ABORT', result=result(name, 'ROLLED_BACK', hn, hn))
    exceptions = {**{'range' + str(v): 'java.lang.IllegalArgumentException' for v in (-1, 1001, -2147483648, 2147483647)},
                  'default': 'java.lang.UnsupportedOperationException', 'defaultNoop': 'java.lang.UnsupportedOperationException',
                  'incomplete': 'java.lang.IllegalArgumentException'}
    e['rejections'] = {k: dict(before=state(w, hn), after=state(w, hn), exception=v) for k, v in exceptions.items()}
    e['writeBefore'] = h(6, revision=25, branch=True); e['writeAfter'] = h(7, revision=26, branch=True)
    e['writeResult'] = result('order targets', 'COMMITTED', e['writeBefore'], e['writeAfter'])
    e['restoredHistory'] = h(0, 12, 34, True)
    cases = [('baseline', 1, 500, 6), ('written', 1, 1000, 7), ('undone', 1, 500, 6), ('redone', 1, 1000, 7),
             ('positiveMid', .5, 750, 8), ('defaultAfterPositive', 0, 500, 9), ('negativeEnd', -1, 0, 10),
             ('negativeMid', -.5, 250, 11), ('defaultFinal', 0, 500, 12)]
    e['samples'] = []
    for i, (name, x, order, pos) in enumerate(cases):
        row = copy.deepcopy(old['samples'][i]); row.pop('expectedPositions'); row.update(expectedOrder=order, x=x, y=0)
        hist = h(pos, 7 if name == 'undone' else pos, 25 + i, True)
        row.update(history=hist, readHistoryBefore=copy.deepcopy(hist), readHistoryAfter=copy.deepcopy(hist))
        row['stored'] = copy.deepcopy(b if name in ('baseline', 'undone') else w); row['observedStored'] = copy.deepcopy(row['stored'])
        for key in ('before', 'requested', 'actual', 'lastObservation'):
            obs = row[key]; obs.update(artMeshId=b['artMeshId'], sourceGuid=b['sourceGuid'])
            obs['calculated'].update(positions=copy.deepcopy(b['positions']), drawOrder=order)
        e['samples'].append(row)
    e['checks'] = [dict(id=k, status='PASS', actual=True, evidenceKind='EXACT_HOST') for k in
                   ['canvas', 'fixture', 'singleUndo', 'restored', 'nonCurrentCommitted', 'nonCurrentUndoRedo', 'noop',
                    *rollbacks, *exceptions, *[c[0] for c in cases]]]
    r['order'] = e
    return r


def load_protocol():
    spec = importlib.util.spec_from_file_location('mi3d_protocol_for_order', Path(__file__).with_name('test_model_import_3d_host_validation.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


class OrderProtocolTest(unittest.TestCase):
    def setUp(self):
        self.protocol = load_protocol(); self.verify = self.protocol.VERIFY
        self.fixture = self.protocol.ordinary_fixture()

    def test_positive_and_missing_order(self):
        self.verify.verify_native(self.fixture, 'synthetic')
        for field in ('order', 'evaluation', 'ordinaryWrite'):
            r = copy.deepcopy(self.fixture); r.pop(field)
            with self.subTest(field=field), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify.verify_native(r, 'synthetic')

    def test_order_negative_matrix(self):
        mutations = [
            ('unrun', lambda e: e.update(status='NOT_RUN')), ('failed', lambda e: e.update(failure='error')),
            ('restore', lambda e: e.update(finallyRestored=False)), ('missing_check', lambda e: e['checks'].pop()),
            ('duplicate_check', lambda e: e['checks'].__setitem__(0, e['checks'][1])),
            ('missing_sample', lambda e: e['samples'].pop()), ('sample_order', lambda e: e['samples'].reverse()),
            ('old_source', lambda e: e['baseline'].update(sourceGuid='evaluation-source')),
            ('default_pollution', lambda e: e['expectedWritten']['forms'][4].update(drawOrder=700)),
            ('target_position', lambda e: e['expectedWritten']['forms'][7]['positions'][0].__setitem__(0, 9)),
            ('canvas_restore', lambda e: e['restoredCanvas'].update(width=9)), ('counts', lambda e: e['restoredCounts'].update(meshes=1)),
            ('configuration', lambda e: e['configuration'].update(backendEvidence='EXACT_HOST')),
            ('prefix_current', lambda e: e['nonCurrent']['written']['parameters'].__setitem__(0, 1)),
            ('prefix_undo', lambda e: e['nonCurrent']['undone']['snapshot']['forms'][7].update(drawOrder=1000)),
            ('prefix_redo_id', lambda e: e['nonCurrent']['redone']['history']['entryIds'].__setitem__(0, 'wrong')),
            ('controls_missing', lambda e: e['controls'].pop('noop')),
            ('missing_throw', lambda e: e['controls']['rollback-triple'].update(callbackFailure='NOT_THROWN')),
            ('partial_callback', lambda e: e['controls']['rollback-triple'].update(steps=2)),
            ('rollback_collateral', lambda e: e['controls']['rollback-true-false']['after']['snapshot']['forms'][1].update(drawOrder=600)),
            ('wrong_intermediate', lambda e: e['controls']['rollback-false-false']['mutated']['snapshot']['forms'][1].update(drawOrder=500)),
            ('early_commit', lambda e: e['controls']['rollback-triple']['mutated']['history'].update(position=7)),
            ('rejection_type', lambda e: e['rejections']['default'].update(exception='java.lang.IllegalArgumentException')),
            ('rejection_history', lambda e: e['rejections']['range-1']['after']['history'].update(revision=88)),
            ('rejection_missing', lambda e: e['rejections'].pop('defaultNoop')),
            ('receipt_outcome', lambda e: e['controls']['noop']['result'].update(outcome='COMMITTED')),
            ('receipt_counter', lambda e: e['writeResult']['receipt']['historyAfter'].update(revision=-1)),
            ('receipt_generation', lambda e: e['writeResult']['receipt']['historyAfter'].update(generation=99)),
            ('receipt_clock_back', lambda e: e['writeResult']['receipt']['historyBefore'].update(revision=0)),
            ('receipt_boolean', lambda e: e['writeResult']['receipt']['historyAfter'].update(revision=True)),
            ('receipt_binding', lambda e: e['writeResult']['receipt']['historyAfter'].update(managerBindingId='other')),
            ('receipt_id', lambda e: e['writeResult']['receipt'].update(transactionId='order-tx-noop')),
            ('receipt_tip', lambda e: e['writeResult']['receipt'].update(historyEntryId='wrong')),
            ('receipt_diagnostic', lambda e: e['controls']['rollback-triple']['result'].update(diagnosticId=None)),
            ('receipt_diagnostic_foreign', lambda e: e['controls']['rollback-triple']['result'].update(
                diagnosticId='authoring.foreign-code: IllegalStateException: x')),
            ('receipt_diagnostic_wrong_type', lambda e: e['controls']['rollback-triple']['result'].update(diagnosticId=7)),
        ]
        for name, mutate in mutations:
            r = copy.deepcopy(self.fixture); mutate(r['order'])
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify.verify_native(r, 'synthetic')

    def test_first_consumed_order_cannot_be_waited_away(self):
        changes = [
            ('order', lambda s: s['actual']['calculated'].update(drawOrder=749)),
            ('position', lambda s: s['actual']['calculated']['positions'][0].__setitem__(0, 100)),
            ('expected', lambda s: s.update(expectedOrder=749)), ('form', lambda s: s['actual'].update(calculated=None)),
            ('stale', lambda s: s['actual']['consumed'].update(updateVersion=s['requested']['consumed']['updateVersion'])),
            ('stamp', lambda s: s['actual'].update(passEntryTimeMillis=-1)),
            ('live', lambda s: s['actual']['live']['parameters'][0].update(value=0)),
            ('consumed', lambda s: s['actual']['consumed']['parameters'][0].update(value=0)),
            ('timeout', lambda s: s.update(waitReason='TIMEOUT')), ('budget', lambda s: s.update(elapsedMillis=15000)),
            ('attempts', lambda s: s.update(attempts=0)), ('source', lambda s: s['actual'].update(sourceGuid='other')),
            ('history', lambda s: s['readHistoryAfter'].update(revision=100)),
            ('stored', lambda s: s['observedStored']['forms'][4].update(drawOrder=600)),
            ('space', lambda s: s['actual']['calculated'].update(coordinateType='Basic Coord')),
        ]
        for name, mutate in changes:
            r = copy.deepcopy(self.fixture); s = r['order']['samples'][4]; mutate(s)
            s['lastObservation'] = copy.deepcopy(s['actual'])  # Re-seal copies; require the semantic oracle to reject.
            with self.subTest(name=name), self.assertRaises((ValueError, KeyError, TypeError)):
                self.verify.verify_native(r, 'synthetic')
        r = copy.deepcopy(self.fixture); r['order']['samples'][4]['lastObservation']['calculated']['drawOrder'] = 751
        with self.assertRaises(ValueError): self.verify.verify_native(r, 'synthetic')

    def test_exact_order_but_tolerant_positions_and_local_clocks(self):
        r = copy.deepcopy(self.fixture); s = r['order']['samples'][4]
        s['actual']['calculated']['positions'][0] = [16.05, 24.05]; s['maxErrorPixels'] = math.hypot(.05, .05)
        s['lastObservation'] = copy.deepcopy(s['actual']); self.verify.verify_native(r, 'synthetic')
        s['actual']['calculated']['positions'][0] = [16.08, 24.08]; s['maxErrorPixels'] = math.hypot(.08, .08)
        s['lastObservation'] = copy.deepcopy(s['actual'])
        with self.assertRaises(ValueError): self.verify.verify_native(r, 'synthetic')


if __name__ == '__main__':
    unittest.main()
