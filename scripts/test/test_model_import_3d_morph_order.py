#!/usr/bin/env python3
"""Synthetic N4b morph draw-order protocol controls; no host, queue, or exact-host evidence is produced."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import unittest

sys.dont_write_bytecode = True

B = [[16, 24], [272, 24], [272, 152], [16, 152]]
V = [[8, 64], [264, 64], [264, 192], [8, 192]]
CHECKS = ['canvas', 'baselineMorphKeys', 'fixture',
          'createsCommitted', 'createsSeed', 'createsCounts',
          'nonCurrentCommitted', 'nonCurrentHandleValid', 'nonCurrentNoop', 'nonCurrentStale',
          'nonCurrentRedo1', 'nonCurrentUndo1', 'nonCurrentRedo2', 'nonCurrentUndo2', 'nonCurrentFinal',
          'evalBaseline', 'evalWritten', 'evalWriteCommitted', 'evalUndone', 'evalRedone',
          'evalMid', 'evalDefault', 'evalNWritten', 'evalNMid',
          'truncReset', 'truncWriteCommitted', 'truncMid', 'truncEnd',
          'secondKeyReset', 'halfCreateCommitted', 'halfSeed', 'halfCounts', 'halfWriteCommitted',
          'twoKeyZero', 'twoKeyQuarter', 'twoKeyHalf', 'twoKeyThreeQuarter', 'twoKeyEnd',
          'controlsReset',
          'pair-false-false', 'pair-false-true', 'pair-true-false', 'pair-true-true',
          'triple', 'mixedCommitted', 'mixedSeed', 'mixedCounts',
          'mixedUndo1', 'mixedRedo1', 'mixedUndo2', 'mixedRedo2', 'mixedFinal',
          'mixedRollback', 'mixedStaleN',
          'orderRange-1', 'orderRange1001', 'orderRange-2147483648', 'orderRange2147483647',
          'staleRead', 'staleOrderWrite', 'stalePositionWrite', 'restored']
SAMPLES = [('evalBaseline', 1, 0, 500), ('evalWritten', 1, 0, 1000), ('evalUndone', 1, 0, 500),
           ('evalRedone', 1, 0, 1000), ('evalMid', 0.5, 0, 750), ('evalDefault', 0, 0, 500),
           ('evalNWritten', 0, 1, 0), ('evalNMid', 0, 0.5, 250),
           ('truncMid', 0.5, 0, 600), ('truncEnd', 1, 0, 701),
           ('twoKeyZero', 0, 0, 500), ('twoKeyQuarter', 0.25, 0, 399), ('twoKeyHalf', 0.5, 0, 299),
           ('twoKeyThreeQuarter', 0.75, 0, 500), ('twoKeyEnd', 1, 0, 701)]
REJECTS = {
    'orderRange-1': 'java.lang.IllegalArgumentException',
    'orderRange1001': 'java.lang.IllegalArgumentException',
    'orderRange-2147483648': 'java.lang.IllegalArgumentException',
    'orderRange2147483647': 'java.lang.IllegalArgumentException',
    'staleRead': 'java.lang.IllegalStateException',
    'staleOrderWrite': 'java.lang.IllegalStateException',
    'stalePositionWrite': 'java.lang.IllegalStateException',
}
PARAM_IDS = {'X': 'ParamMI3DN4BX', 'Y': 'ParamMI3DN4BY', 'M': 'ParamMI3DN4BM', 'N': 'ParamMI3DN4BN'}
FM, FN, FH, FQ, FQ2 = 'n4b-fm', 'n4b-fn', 'n4b-fh', 'n4b-fq', 'n4b-fq2'


def attach(r):
    # Test-owned synthetic values; not the verifier's constants or any Java implementation.
    e = dict(schemaVersion='mi3d-native-n4b-v2', status='PASS', failure=None,
             coordinateSpace='EDITOR_CALCULATED_ROOT_PIXELS', finallyRestored=True)
    old = r['morph']
    for k in ('configuration', 'initialCounts', 'restoredCounts', 'initialCanvas', 'restoredCanvas',
              'canvas', 'canvasCommand'):
        e[k] = copy.deepcopy(old[k])
    e['fixtureCounts'] = {**e['initialCounts'], 'meshes': 1, 'parameters': e['initialCounts']['parameters'] + 4}
    b = copy.deepcopy(old['baseline']); b['sourceGuid'] = 'n4b-source'; b['artMeshId'] = 'n4b-mesh'
    for f in b['forms']:
        f['guid'] = 'n4b-' + f['guid']
    for c in b['cells']:
        c['formGuid'] = 'n4b-' + c['formGuid']
    for a in b['axes']:
        a['id'] = 'ParamMI3DN4B' + a['id'][-1]; a['guid'] = 'n4b-' + a['guid']
    for c in b['cells']:
        for v in c['coordinates']:
            v['guid'] = 'n4b-' + v['guid']
    e['baseline'] = b; e['baselineMorphKeys'] = []
    axes = {a['id']: a['guid'] for a in b['axes']}
    gx, gy = axes[PARAM_IDS['X']], axes[PARAM_IDS['Y']]
    gm, gn = 'n4b-gm', 'n4b-gn'
    e['morphParameters'] = {'X': {'id': PARAM_IDS['X'], 'guid': gx}, 'Y': {'id': PARAM_IDS['Y'], 'guid': gy},
                            'M': {'id': PARAM_IDS['M'], 'guid': gm}, 'N': {'id': PARAM_IDS['N'], 'guid': gn}}

    def key(guid, value, form):
        return {'parameterGuid': guid, 'keyValue': value, 'formGuid': form, 'basicIdentity': 'UNKNOWN'}

    def form(guid, positions, order):
        return {'guid': guid, 'positions': copy.deepcopy(positions), 'drawOrder': order}

    def expect(snapshot, forms, keys):
        s = copy.deepcopy(snapshot); s['forms'] += forms; s['morphKeys'] += keys; return s

    def ordered(snapshot, guid, order):
        s = copy.deepcopy(snapshot)
        for f in s['forms']:
            if f['guid'] == guid:
                f['drawOrder'] = order
        return s

    def moved(snapshot, guid, positions):
        s = copy.deepcopy(snapshot)
        for f in s['forms']:
            if f['guid'] == guid:
                f['positions'] = copy.deepcopy(positions)
        return s

    created = expect(b, [form(FM, B, 500), form(FN, B, 500)], [key(gm, 1, FM), key(gn, 1, FN)])
    eval_written = ordered(ordered(created, FM, 1000), FN, 0)
    trunc_written = ordered(ordered(created, FM, 701), FN, 0)
    with_half = expect(trunc_written, [form(FH, B, 600)], [key(gm, 0.5, FH)])
    half_written = ordered(with_half, FH, 299)
    mixed = ordered(expect(half_written, [form(FQ, V, 700)], [key(gm, 0.25, FQ)]), FM, 900)
    mixed_rb = ordered(expect(half_written, [form(FQ2, V, 700)], [key(gm, 0.25, FQ2)]), FM, 900)

    bank = {}

    def ref(domain, hist):
        for ref_id, (d, value) in bank.items():
            if d == domain and value == hist:
                return {'historyRef': ref_id, 'historyDomain': domain}
        ref_id = 'h%d' % len(bank)
        bank[ref_id] = (domain, copy.deepcopy(hist))
        return {'historyRef': ref_id, 'historyDomain': domain}

    # Live facade history tracker: real phase sequence with branches, tails and local revision.
    doc = old['restoredHistory']['documentBindingId']
    mgr = old['restoredHistory']['managerBindingId']
    gen = old['restoredHistory']['generation']
    live = {'rev': 80, 'entries': [], 'entryIds': [], 'position': 0, 'seq': 0}

    def snap_h():
        return dict(availability='AVAILABLE', documentBindingId=doc, managerBindingId=mgr,
                    generation=gen, revision=live['rev'], position=live['position'],
                    entries=list(live['entries']), entryIds=list(live['entryIds']))

    def commit(label):
        # New branch keeps the applied prefix, appends one entry, and cuts the redo tail.
        live['seq'] += 1
        live['entries'] = live['entries'][:live['position']] + [label]
        live['entryIds'] = live['entryIds'][:live['position']] + ['n4b-entry-%d' % live['seq']]
        live['position'] += 1
        live['rev'] += 1
        return snap_h()

    def tx(label):
        return commit('MI3D N4b ' + label)

    def param():
        return commit('Turboism: Set Parameter Value')

    def jump(position):
        live['position'] = position
        live['rev'] += 1
        return snap_h()

    def state(snapshot, hist, parameters=(0, 0, 0, 0)):
        return dict(snapshot=copy.deepcopy(snapshot), history=ref('FACADE', hist),
                    parameters=list(parameters))

    clock = [400]

    def result(name, outcome, before, after):
        hb, ha = copy.deepcopy(before), copy.deepcopy(after)
        hb.update(generation=7, revision=clock[0])
        if outcome == 'COMMITTED':
            clock[0] += 1
        ha.update(generation=7, revision=clock[0])
        return dict(outcome=outcome, diagnosticId=('authoring.callback-failed: IllegalStateException: deliberate callback failure')
                    if outcome == 'ROLLED_BACK' else None,
                    receipt=dict(transactionId='n4b-tx-' + name, label='MI3D N4b ' + name,
                                 historyBefore=ref('TRANSACTION', hb), historyAfter=ref('TRANSACTION', ha),
                                 historyEntryId=after['entryIds'][after['position'] - 1] if outcome == 'COMMITTED' else None))

    initial = snap_h()
    e['initialHistory'] = ref('FACADE', initial)
    for i in range(6):
        commit('n4b fixture setup %d' % i)
    fixture_hist = snap_h()
    e['fixtureHistory'] = ref('FACADE', fixture_hist)
    c_before = snap_h()
    c_after = tx('create M1 N1')
    e['creates'] = dict(
        result=result('create M1 N1', 'COMMITTED', c_before, c_after), steps=2,
        seedM={'formGuid': FM, 'positions': copy.deepcopy(B), 'drawOrder': 500},
        seedN={'formGuid': FN, 'positions': copy.deepcopy(B), 'drawOrder': 500},
        triples=[{'parameterGuid': gm, 'keyValue': 1, 'formGuid': FM},
                 {'parameterGuid': gn, 'keyValue': 1, 'formGuid': FN}],
        before=ref('FACADE', c_before), after=ref('FACADE', c_after), snapshot=copy.deepcopy(created))
    nc_before, nc_after = c_after, tx('noncurrent order')
    nc_result = result('noncurrent order', 'COMMITTED', nc_before, nc_after)
    # Java order: first undo (state), then redone/undone per round, then redone3 and final undo.
    nc_state = jump(7)
    nc_r1, nc_u1 = jump(8), jump(7)
    nc_r2, nc_u2 = jump(8), jump(7)
    nc_r3, nc_final = jump(8), jump(7)
    e['nonCurrent'] = dict(
        before=state(created, nc_before),
        midMutation=state(ordered(created, FM, 1000), nc_before),
        mutated=state(eval_written, nc_before),
        result=nc_result,
        historyBefore=ref('FACADE', nc_before), historyAfter=ref('FACADE', nc_after),
        written=state(eval_written, nc_after), handleAfterCommit='VALID',
        noop=dict(result=result('noop', 'NO_CHANGE', nc_after, nc_after),
                  state=state(eval_written, nc_after), handle='VALID'),
        undoCycle=dict(
            oldHandle='STALE', detached={'formGuid': FM, 'positions': copy.deepcopy(B), 'drawOrder': 1000},
            fresh={'formGuid': FM, 'positions': copy.deepcopy(B), 'drawOrder': 500},
            freshHandle={'parameterGuid': gm, 'keyValue': 1},
            state=state(created, nc_state),
            undone1=state(created, nc_u1), redone1=state(eval_written, nc_r1),
            undone2=state(created, nc_u2), redone2=state(eval_written, nc_r2),
            redone3=state(eval_written, nc_r3), final=state(created, nc_final)))
    # Positions: 6=fixture, 7=create, 8=nonCurrent. The final undo keeps the redo tail attached.
    # Every action records its own before/after anchors in program order.
    action_for = {}
    eval_baseline_before = snap_h()
    eo_before = param()
    action_for['evalBaseline'] = (eval_baseline_before, eo_before, [0, 0, 0, 0])
    eval_written_before = snap_h()
    eo_after = tx('evaluated order')
    action_for['evalWritten'] = (eval_written_before, eo_after, [0, 0, 1, 0])
    e['evalOrder'] = dict(before=ref('FACADE', eo_before), anchor=ref('FACADE', eo_before),
                          after=ref('FACADE', eo_after),
                          result=result('evaluated order', 'COMMITTED', eo_before, eo_after))
    eval_undone_before = snap_h(); eval_undone_after = jump(8)
    action_for['evalUndone'] = (eval_undone_before, eval_undone_after, [0, 0, 1, 0])
    eval_redone_before = snap_h(); eval_redone_after = jump(9)
    action_for['evalRedone'] = (eval_redone_before, eval_redone_after, [0, 0, 1, 0])
    for name, params in (('evalMid', [0, 0, 1, 0]), ('evalDefault', [0, 0, 0.5, 0]),
                         ('evalNWritten', [0, 0, 0, 0]), ('evalNMid', [0, 0, 0, 1])):
        before = snap_h()
        action_for[name] = (before, param(), params)
    tr_reset_before = snap_h()
    tr_reset_mid = snap_h()  # M is already 0: the same-value write commits nothing.
    tr_reset_after = param()  # N .5 -> 0 commits its own parameter entry.
    tr_after = tx('truncating order')
    e['truncation'] = dict(
        reset=dict(parametersBefore=[0, 0, 0, 0.5],
                   writes=[dict(parameter='M', historyBefore=ref('FACADE', tr_reset_before),
                                historyAfter=ref('FACADE', tr_reset_mid)),
                           dict(parameter='N', historyBefore=ref('FACADE', tr_reset_mid),
                                historyAfter=ref('FACADE', tr_reset_after))],
                   parametersAfter=[0, 0, 0, 0]),
        writeBefore=ref('FACADE', tr_reset_after), writeAfter=ref('FACADE', tr_after),
        writeResult=result('truncating order', 'COMMITTED', tr_reset_after, tr_after),
        written=state(trunc_written, tr_after))
    for name, params in (('truncMid', [0, 0, 0, 0]), ('truncEnd', [0, 0, 0.5, 0])):
        before = snap_h()
        action_for[name] = (before, param(), params)
    sk_reset_before = snap_h()
    sk_reset_mid = param()  # M 1 -> 0 commits its own parameter entry.
    sk_reset_after = snap_h()  # N is already 0: nothing commits.
    hc_after = tx('create M.5')
    hw_after = tx('write M.5 order')
    e['secondKey'] = dict(
        reset=dict(parametersBefore=[0, 0, 1, 0],
                   writes=[dict(parameter='M', historyBefore=ref('FACADE', sk_reset_before),
                                historyAfter=ref('FACADE', sk_reset_mid)),
                           dict(parameter='N', historyBefore=ref('FACADE', sk_reset_mid),
                                historyAfter=ref('FACADE', sk_reset_after))],
                   parametersAfter=[0, 0, 0, 0]),
        createBefore=ref('FACADE', sk_reset_after), createAfter=ref('FACADE', hc_after),
        createResult=result('create M.5', 'COMMITTED', sk_reset_after, hc_after),
        seed={'formGuid': FH, 'positions': copy.deepcopy(B), 'drawOrder': 600},
        triple={'parameterGuid': gm, 'keyValue': 0.5, 'formGuid': FH}, snapshot=copy.deepcopy(with_half),
        writeBefore=ref('FACADE', hc_after), writeAfter=ref('FACADE', hw_after),
        writeResult=result('write M.5 order', 'COMMITTED', hc_after, hw_after),
        written=state(half_written, hw_after))
    for name, params, changed in (('twoKeyZero', [0, 0, 0, 0], False),
                                  ('twoKeyQuarter', [0, 0, 0, 0], True),
                                  ('twoKeyHalf', [0, 0, 0.25, 0], True),
                                  ('twoKeyThreeQuarter', [0, 0, 0.5, 0], True),
                                  ('twoKeyEnd', [0, 0, 0.75, 0], True)):
        before = snap_h()
        action_for[name] = (before, param() if changed else snap_h(), params)
    ct_reset_before = snap_h()
    ct_reset_mid = param()  # M 1 -> 0 commits its own parameter entry.
    ct_reset_after = snap_h()
    controls_hist = ct_reset_after
    controls = dict(
        reset=dict(parametersBefore=[0, 0, 1, 0],
                   writes=[dict(parameter='M', historyBefore=ref('FACADE', ct_reset_before),
                                historyAfter=ref('FACADE', ct_reset_mid)),
                           dict(parameter='N', historyBefore=ref('FACADE', ct_reset_mid),
                                historyAfter=ref('FACADE', ct_reset_after))],
                   parametersAfter=[0, 0, 0, 0]),
        before=state(half_written, controls_hist))
    for order_first in (False, True):
        for same in (False, True):
            name = 'pair-%s-%s' % (str(order_first).lower(), str(same).lower())
            controls[name] = dict(
                before=state(half_written, controls_hist), after=state(half_written, controls_hist),
                mutated=state(moved(ordered(half_written, FM, 800), FM if same else FN, V), controls_hist),
                callbackFailure='N4B_ABORT', steps=2,
                result=result(name, 'ROLLED_BACK', controls_hist, controls_hist))
    controls['triple'] = dict(
        before=state(half_written, controls_hist), after=state(half_written, controls_hist),
        mutated=state(ordered(half_written, FM, 900), controls_hist),
        callbackFailure='N4B_ABORT', steps=3,
        result=result('triple', 'ROLLED_BACK', controls_hist, controls_hist))
    mc_after = tx('mixed member')
    controls['mixedCommit'] = dict(
        before=state(half_written, controls_hist), after=state(mixed, mc_after),
        mutated=state(mixed, controls_hist), steps=4,
        result=result('mixed member', 'COMMITTED', controls_hist, mc_after),
        seed={'formGuid': FQ, 'positions': copy.deepcopy(B), 'drawOrder': 399},
        triple={'parameterGuid': gm, 'keyValue': 0.25, 'formGuid': FQ},
        undone1=state(half_written, jump(mc_after['position'] - 1)),
        redone1=state(mixed, jump(mc_after['position'])),
        undone2=state(half_written, jump(mc_after['position'] - 1)),
        redone2=state(mixed, jump(mc_after['position'])),
        final=state(half_written, jump(mc_after['position'] - 1)))
    controls['mixedRollback'] = dict(
        before=state(half_written, snap_h()), after=state(half_written, snap_h()),
        mutated=state(mixed_rb, snap_h()),
        seed={'formGuid': FQ2, 'positions': copy.deepcopy(B), 'drawOrder': 399},
        triple={'parameterGuid': gm, 'keyValue': 0.25, 'formGuid': FQ2},
        callbackFailure='N4B_ABORT', steps=4,
        result=result('mixed rollback', 'ROLLED_BACK', snap_h(), snap_h()),
        preFailure=dict(nHandle='VALID',
                        handle={'parameterGuid': gn, 'keyValue': 1},
                        detached={'formGuid': FN, 'positions': copy.deepcopy(B), 'drawOrder': 0}),
        postFailure=dict(nHandle='STALE',
                         relooked={'formGuid': FN, 'positions': copy.deepcopy(B), 'drawOrder': 0},
                         relookedHandle={'parameterGuid': gn, 'keyValue': 1},
                         detached={'formGuid': FN, 'positions': copy.deepcopy(B), 'drawOrder': 0}))
    e['controls'] = controls
    e['rejections'] = {k: dict(before=state(half_written, snap_h()),
                               after=state(half_written, snap_h()), exception=v)
                       for k, v in REJECTS.items()}
    e['restoredHistory'] = ref('FACADE', jump(0))
    stored_for = {'evalBaseline': created, 'evalWritten': eval_written, 'evalUndone': created,
                  'evalRedone': eval_written, 'evalMid': eval_written, 'evalDefault': eval_written,
                  'evalNWritten': eval_written, 'evalNMid': eval_written,
                  'truncMid': trunc_written, 'truncEnd': trunc_written,
                  'twoKeyZero': half_written, 'twoKeyQuarter': half_written, 'twoKeyHalf': half_written,
                  'twoKeyThreeQuarter': half_written, 'twoKeyEnd': half_written}
    params_after = {'evalBaseline': [0, 0, 1, 0], 'evalWritten': [0, 0, 1, 0],
                    'evalUndone': [0, 0, 1, 0], 'evalRedone': [0, 0, 1, 0],
                    'evalMid': [0, 0, 0.5, 0], 'evalDefault': [0, 0, 0, 0],
                    'evalNWritten': [0, 0, 0, 1], 'evalNMid': [0, 0, 0, 0.5],
                    'truncMid': [0, 0, 0.5, 0], 'truncEnd': [0, 0, 1, 0],
                    'twoKeyZero': [0, 0, 0, 0], 'twoKeyQuarter': [0, 0, 0.25, 0],
                    'twoKeyHalf': [0, 0, 0.5, 0], 'twoKeyThreeQuarter': [0, 0, 0.75, 0],
                    'twoKeyEnd': [0, 0, 1, 0]}
    e['samples'] = []

    def observed(m, n, step, consumed_version, order):
        return observed_values([0, 0, m, n], step, consumed_version, order)

    def observed_values(values, step, consumed_version, order):
        x, y, m, n = values
        params = [{'guid': gx, 'id': PARAM_IDS['X'], 'value': x}, {'guid': gy, 'id': PARAM_IDS['Y'], 'value': y},
                  {'guid': gm, 'id': PARAM_IDS['M'], 'value': m}, {'guid': gn, 'id': PARAM_IDS['N'], 'value': n}]
        return dict(artMeshId=b['artMeshId'], sourceGuid=b['sourceGuid'], passEntryTimeMillis=200 + step,
                    live=dict(updateVersion=900 + step, parameters=copy.deepcopy(params)),
                    consumed=dict(updateVersion=consumed_version, parameters=copy.deepcopy(params)),
                    calculated=dict(positions=copy.deepcopy(B), drawOrder=order,
                                    coordinateType='DeformerLocal', interpolated=True))

    for i, (name, m, n, order) in enumerate(SAMPLES):
        stored = stored_for[name]
        action_before, action_after, params_before = action_for[name]
        # The pre-action observation carries the real pre-action live values, not the post-action ones.
        before = observed_values(params_before, i, 49 + i, 500)
        requested = observed(m, n, i, 50 + i, 500); actual = observed(m, n, i, 51 + i, order)
        e['samples'].append(dict(
            id=name, m=m, x=0, y=0, n=n, status='PASS', expectedOrder=order,
            expectedPositions=copy.deepcopy(B), waitLimitMillis=15000,
            before=before, requested=requested, actual=actual,
            lastObservation=copy.deepcopy(actual), attempts=2, elapsedMillis=25, waitReason='CONSUMED',
            maxErrorPixels=0, stored=copy.deepcopy(stored), observedStored=copy.deepcopy(stored),
            parametersBefore=params_before, parametersAfter=params_after[name],
            actionHistoryBefore=ref('FACADE', action_before),
            actionHistoryAfter=ref('FACADE', action_after),
            history=ref('FACADE', action_after),
            readHistoryBefore=ref('FACADE', action_after),
            readHistoryAfter=ref('FACADE', action_after)))
    e['checks'] = [dict(id=k, status='PASS', actual=True, evidenceKind='EXACT_HOST') for k in CHECKS]
    e['historyBank'] = {ref_id: {'historyDomain': domain, 'history': value}
                        for ref_id, (domain, value) in bank.items()}
    r['morphOrder'] = e
    return r


def load_protocol():
    spec = importlib.util.spec_from_file_location('mi3d_protocol_for_morph_order', Path(__file__).with_name('test_model_import_3d_host_validation.py'))
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module); return module


def history_of(section, node):
    return section['historyBank'][node['historyRef']]['history']


def canonical_bank(section):
    """Repacks every explicit ref through complete bank values; drops orphans, dedupes by value."""
    old = section['historyBank']
    bank, by_value = {}, {}

    def walk(node):
        if isinstance(node, dict):
            if set(node) == {'historyRef', 'historyDomain'}:
                entry = old[node['historyRef']]
                sig = json.dumps(entry, sort_keys=True, ensure_ascii=False, separators=(',', ':'))
                if sig not in by_value:
                    ref_id = 'rb%d' % len(bank)
                    by_value[sig] = ref_id
                    bank[ref_id] = copy.deepcopy(entry)
                return dict(historyRef=by_value[sig], historyDomain=node['historyDomain'])
            return {k: walk(v) for k, v in node.items() if k != 'historyBank'}
        if isinstance(node, list):
            return [walk(v) for v in node]
        return node

    rebuilt = walk(section)
    rebuilt['historyBank'] = bank
    section.clear()
    section.update(rebuilt)


def change_history(section, path, mutate):
    """Mutates one referenced history in place via a fresh bank id, then repacks the bank."""
    node = section
    for key in path:
        node = node[key]
    value = copy.deepcopy(section['historyBank'][node['historyRef']])
    mutate(value['history'])
    ref_id = 'mut%d' % len(section['historyBank'])
    section['historyBank'][ref_id] = value
    node['historyRef'] = ref_id
    canonical_bank(section)


class MorphOrderProtocolTest(unittest.TestCase):
    def setUp(self):
        self.protocol = load_protocol(); self.verify = self.protocol.VERIFY
        self.fixture = attach(self.protocol.ordinary_fixture())

    def test_positive_and_missing_morph_order(self):
        self.verify.verify_native(self.fixture, 'synthetic')
        r = copy.deepcopy(self.fixture); r.pop('morphOrder')
        with self.assertRaises((ValueError, KeyError, TypeError)):
            self.verify.verify_native(r, 'synthetic')
        r = copy.deepcopy(self.fixture)
        r['morphOrder']['schemaVersion'] = 'mi3d-native-n4b-v1'
        with self.assertRaises((ValueError, KeyError, TypeError)):
            self.verify.verify_native(r, 'synthetic')

    def reject(self, mutation, reason=None):
        r = copy.deepcopy(self.fixture)
        mutation(r['morphOrder'])
        try:
            self.verify.verify_native(r, 'synthetic')
        except (ValueError, KeyError, TypeError) as error:
            if reason is not None:
                self.assertIn(reason, str(error))
            return str(error)
        self.fail('mutation accepted')

    def test_morph_order_negative_matrix(self):
        e = self.fixture['morphOrder']
        mutations = [
            ('unrun', lambda e: e.update(status='NOT_RUN'), None),
            ('failed', lambda e: e.update(failure='error'), None),
            ('receipt_diagnostic_foreign', lambda e: e['controls']['triple']['result'].update(
                diagnosticId='authoring.foreign-code: IllegalStateException: x'), None),
            ('receipt_diagnostic_wrong_type', lambda e: e['controls']['triple']['result'].update(diagnosticId=7), None),
            ('restore', lambda e: e.update(finallyRestored=False), None),
            ('missing_check', lambda e: e['checks'].pop(), None),
            ('duplicate_check', lambda e: e['checks'].__setitem__(0, e['checks'][1]), None),
            ('check_failed', lambda e: e['checks'][0].update(status='FAIL'), None),
            ('missing_sample', lambda e: e['samples'].pop(), None),
            ('sample_order', lambda e: e['samples'].reverse(), None),
            ('materialized_base', lambda e: e['baselineMorphKeys'].append({'parameterGuid': 'gm'}), None),
            ('materialized_key', lambda e: e['baseline']['morphKeys'].append({'parameterGuid': 'gm'}), None),
            ('old_source', lambda e: e['baseline'].update(sourceGuid='morph-source'), None),
            ('old_form', lambda e: e['baseline']['forms'][0].update(guid='morph-fU'), None),
            ('parameter_count', lambda e: e['fixtureCounts'].update(parameters=e['fixtureCounts']['parameters'] - 1), None),
            ('guid_alias', lambda e: e['morphParameters']['N'].update(guid=e['morphParameters']['M']['guid']), None),
            ('id_as_guid', lambda e: e['morphParameters']['M'].update(guid=PARAM_IDS['M']), None),
            ('axis_guid', lambda e: e['morphParameters']['M'].update(guid=e['morphParameters']['X']['guid']), None),
            ('axis_id', lambda e: e['baseline']['axes'][0].update(id='ParamMI3DN2X'), None),
            ('baseline_order', lambda e: e['baseline']['forms'][0].update(drawOrder=600), None),
            ('baseline_cell', lambda e: e['baseline']['cells'].pop(), None),
            ('seed_order', lambda e: e['creates']['seedM'].update(drawOrder=600), None),
            ('seed_positions', lambda e: e['creates']['seedN'].update(positions=V), None),
            ('triple_value', lambda e: e['creates']['triples'][0].update(keyValue=0.5), None),
            ('triple_guid', lambda e: e['creates']['triples'][1].update(parameterGuid=e['morphParameters']['M']['guid']), None),
            ('create_outcome', lambda e: e['creates']['result'].update(outcome='NO_CHANGE'), None),
            ('create_steps', lambda e: e['creates'].update(steps=3), None),
            ('create_snapshot', lambda e: e['creates']['snapshot']['morphKeys'][0].update(formGuid='n4b-f-x'), None),
            ('mid_order', lambda e: e['nonCurrent']['midMutation']['snapshot']['forms'][9].update(drawOrder=999), None),
            ('mutated_order', lambda e: e['nonCurrent']['mutated']['snapshot']['forms'][10].update(drawOrder=5), None),
            ('noncurrent_outcome', lambda e: e['nonCurrent']['result'].update(outcome='NO_CHANGE'), None),
            ('handle_stale', lambda e: e['nonCurrent'].update(handleAfterCommit='STALE'), None),
            ('noop_outcome', lambda e: e['nonCurrent']['noop']['result'].update(outcome='COMMITTED'), None),
            ('noop_handle', lambda e: e['nonCurrent']['noop'].update(handle='STALE'), None),
            ('noop_state', lambda e: e['nonCurrent']['noop']['state']['snapshot']['forms'][9].update(drawOrder=0), None),
            ('stale_valid', lambda e: e['nonCurrent']['undoCycle'].update(oldHandle='VALID'), None),
            ('fresh_drift', lambda e: e['nonCurrent']['undoCycle']['fresh'].update(drawOrder=700), None),
            ('detached_drift', lambda e: e['nonCurrent']['undoCycle']['detached'].update(drawOrder=0), None),
            ('fresh_handle', lambda e: e['nonCurrent']['undoCycle']['freshHandle'].update(
                parameterGuid=e['morphParameters']['N']['guid']), None),
            ('undo_snapshot', lambda e: e['nonCurrent']['undoCycle']['undone1']['snapshot']['forms'][9].update(drawOrder=1000), None),
            ('sample_order_wrong', lambda e: (e['samples'][4]['actual']['calculated'].update(drawOrder=749),
                                              e['samples'][4]['lastObservation']['calculated'].update(drawOrder=749)), None),
            ('sample_stored', lambda e: e['samples'][5].update(stored=e['samples'][5]['stored'] | {'morphKeys': []}), None),
            ('sample_positions', lambda e: (e['samples'][8]['actual']['calculated']['positions'][0].__setitem__(0, 999),
                                            e['samples'][8]['lastObservation']['calculated']['positions'][0].__setitem__(0, 999)), None),
            ('sample_first_consumed', lambda e: (e['samples'][0]['actual']['consumed'].update(
                updateVersion=e['samples'][0]['requested']['consumed']['updateVersion']),
                e['samples'][0]['lastObservation']['consumed'].update(
                    updateVersion=e['samples'][0]['requested']['consumed']['updateVersion'])), None),
            ('trunc_seed', lambda e: e['secondKey']['seed'].update(drawOrder=601), None),
            ('half_seed', lambda e: e['secondKey']['seed'].update(positions=V), None),
            ('half_write', lambda e: e['secondKey']['written']['snapshot']['forms'][11].update(drawOrder=300), None),
            ('twokey_quarter', lambda e: (e['samples'][11]['actual']['calculated'].update(drawOrder=400),
                                         e['samples'][11]['lastObservation']['calculated'].update(drawOrder=400)), None),
            ('pair_outcome', lambda e: e['controls']['pair-false-false']['result'].update(outcome='COMMITTED'), None),
            ('pair_steps', lambda e: e['controls']['pair-true-true'].update(steps=3), None),
            ('pair_sentinel', lambda e: e['controls']['pair-false-true'].update(callbackFailure='WRONG'), None),
            ('pair_mutated', lambda e: e['controls']['pair-true-false']['mutated']['snapshot']['forms'][10].update(positions=B), None),
            ('pair_restore', lambda e: e['controls']['pair-false-false']['after']['snapshot']['forms'][9].update(drawOrder=800), None),
            ('triple_steps', lambda e: e['controls']['triple'].update(steps=2), None),
            ('triple_mutated', lambda e: e['controls']['triple']['mutated']['snapshot']['forms'][9].update(drawOrder=700), None),
            ('mixed_steps', lambda e: e['controls']['mixedCommit'].update(steps=3), None),
            ('mixed_seed', lambda e: e['controls']['mixedCommit']['seed'].update(drawOrder=400), None),
            ('mixed_undo', lambda e: e['controls']['mixedCommit']['undone1']['snapshot']['morphKeys'].pop(), None),
            ('mixed_rb_outcome', lambda e: e['controls']['mixedRollback']['result'].update(outcome='COMMITTED'), None),
            ('mixed_rb_pre', lambda e: e['controls']['mixedRollback']['preFailure'].update(nHandle='STALE'), None),
            ('mixed_rb_post', lambda e: e['controls']['mixedRollback']['postFailure'].update(nHandle='VALID'), None),
            ('mixed_rb_relooked', lambda e: e['controls']['mixedRollback']['postFailure']['relooked'].update(drawOrder=500), None),
            ('rejection_missing', lambda e: e['rejections'].pop('staleRead'), None),
            ('rejection_class', lambda e: e['rejections']['orderRange-1'].update(exception='java.lang.IllegalStateException'), None),
            ('rejection_state', lambda e: e['rejections']['staleOrderWrite']['after']['snapshot']['forms'][9].update(drawOrder=500), None),
            ('restored_root', lambda e: history_of(e, e['restoredHistory']).update(position=3), None),
            ('counts_drift', lambda e: e['restoredCounts'].update(meshes=1), None),
            # Targeted review replay: each asserts its designated reason.
            ('unmapped_history_key', lambda e: e.update(
                junk={'history': copy.deepcopy(e['initialHistory'])}), 'unmapped schema position'),
            ('unmapped_receipt_shape', lambda e: e.update(
                junk=copy.deepcopy(e['creates']['result']['receipt'])), None),
            ('malformed_bank_value', lambda e: (e['historyBank'].update(
                bad={'historyDomain': 'FACADE', 'history': {'garbage': 1}}),
                e['controls'].update(junk={'historyRef': 'bad', 'historyDomain': 'FACADE'})), None),
            ('malformed_ref_tag', lambda e: e['creates'].update(
                before={'historyRef': 'absent', 'historyDomain': 'FACADE', 'extra': True}),
                'reference tag'),
            ('ref_under_junk', lambda e: e.update(
                junk={'history': {'historyRef': e['initialHistory']['historyRef'],
                                  'historyDomain': 'FACADE'}}), 'unmapped schema position'),
            ('malformed_ref_under_junk', lambda e: e.update(
                junk={'history': {'historyRef': 'x', 'historyDomain': 'FACADE',
                                  'extra': 1}}), 'unmapped schema position'),
            ('rollback_source_damage', lambda e: e['controls']['mixedRollback']['mutated']['snapshot'].update(
                sourceGuid='polluted'), 'unexpected stored'),
            ('inline_at_mapped', lambda e: e['creates'].update(
                before=copy.deepcopy(history_of(e, e['creates']['before']))), 'reference tag'),
            ('receipt_entry_id_ref', lambda e: e['creates']['result']['receipt'].update(
                historyEntryId={'historyRef': 'h0', 'historyDomain': 'TRANSACTION'}), 'unmapped schema position'),
            ('second_key_params', lambda e: e['secondKey']['written'].update(parameters=[0, 0, 1, 0]), None),
            ('rollback_guid_reused', lambda e: (e['controls']['mixedRollback']['triple'].update(formGuid=FQ),
                                               e['controls']['mixedRollback']['seed'].update(formGuid=FQ),
                                               e['controls']['mixedRollback']['mutated']['snapshot'].update(
                                                   forms=[f if f['guid'] != FQ2 else dict(f, guid=FQ)
                                                        for f in e['controls']['mixedRollback']['mutated']['snapshot']['forms']]),
                                               e['controls']['mixedRollback']['mutated']['snapshot'].update(
                                                   morphKeys=[k if k['formGuid'] != FQ2 else dict(k, formGuid=FQ)
                                                              for k in e['controls']['mixedRollback']['mutated']['snapshot']['morphKeys']])),
                'fresh member identity'),
            ('rollback_triple_wrong', lambda e: e['controls']['mixedRollback']['triple'].update(keyValue=0.5), None),
            ('redo_entry_id', lambda e: history_of(
                e, e['controls']['mixedCommit']['redone2']['history'])['entryIds'].__setitem__(-1, 'wrong-entry'),
                'committed entry identity'),
            ('noncurrent_clock_backward', lambda e: change_history(
                e, ['nonCurrent', 'undoCycle', 'redone1', 'history'], lambda h: h.update(revision=0)),
                'undo/redo history identity'),
            ('noncurrent_clock_equal', lambda e: change_history(
                e, ['nonCurrent', 'undoCycle', 'redone1', 'history'],
                lambda h: h.update(revision=history_of(e, e['nonCurrent']['undoCycle']['state']['history'])['revision'])),
                'undo/redo history identity'),
            ('mixed_redo_clock_backward', lambda e: change_history(
                e, ['controls', 'mixedCommit', 'redone1', 'history'], lambda h: h.update(revision=0)),
                'undo/redo history identity'),
            ('before_live_wrong', lambda e: e['samples'][4]['before']['live']['parameters'][2].update(
                value=.875), 'before live parameters'),
            ('before_live_missing', lambda e: e['samples'][4]['before']['live']['parameters'].pop(2),
                'before live parameters'),
            ('before_live_wrong_id', lambda e: e['samples'][4]['before']['live']['parameters'][2].update(
                id='ParamMI3DN4BN'), 'before live parameters'),
            ('parameters_before_forged', lambda e: e['samples'][4].update(
                parametersBefore=[0, 0, 0, 0]), 'sample parameter transition'),
            ('eval_mid_extra_entry', lambda e: history_of(e, e['samples'][4]['actionHistoryAfter']).update(
                entryIds=history_of(e, e['samples'][4]['actionHistoryAfter'])['entryIds'] + ['extra'],
                entries=history_of(e, e['samples'][4]['actionHistoryAfter'])['entries'] + ['extra']), None),
            ('undo_entry_drop', lambda e: history_of(
                e, e['samples'][2]['actionHistoryAfter'])['entryIds'].pop(), None),
            ('param_label_wrong', lambda e: history_of(
                e, e['samples'][4]['actionHistoryAfter'])['entries'].__setitem__(
                    history_of(e, e['samples'][4]['actionHistoryAfter'])['position'] - 1, 'wrong label'), None),
        ]
        for name, mutation, reason in mutations:
            with self.subTest(control=name):
                self.reject(mutation, reason)

    def test_targeted_601_requires_synchronized_observation(self):
        # The 601 defect only reaches the order oracle when actual==lastObservation stays intact.
        def sync_601(e):
            e['samples'][8]['actual']['calculated'].update(drawOrder=601)
            e['samples'][8]['lastObservation']['calculated'].update(drawOrder=601)
        self.reject(sync_601, 'calculated order truncMid')
        self.reject(lambda e: e['samples'][8]['actual']['calculated'].update(drawOrder=601), None)

    def test_history_bank_negative_matrix(self):
        def ref_at(e, node):
            return e['historyBank'][node['historyRef']]
        mutations = [
            ('bank_missing', lambda e: e.pop('historyBank')),
            ('bank_empty', lambda e: e.update(historyBank={})),
            ('bank_orphan', lambda e: e['historyBank'].update(
                orphan={'historyDomain': 'FACADE', 'history': dict(availability='AVAILABLE',
                    documentBindingId='x', managerBindingId='y', generation=1, revision=1,
                    position=0, entries=[], entryIds=[])})),
            ('bank_domain', lambda e: ref_at(e, e['initialHistory']).update(historyDomain='TRANSACTION')),
            ('bank_ref_missing', lambda e: e['initialHistory'].update(historyRef='absent')),
            ('bank_ref_domain', lambda e: e['initialHistory'].update(historyDomain='TRANSACTION')),
            ('bank_nested_ref', lambda e: ref_at(e, e['initialHistory']).update(
                history={'historyRef': 'h0', 'historyDomain': 'FACADE'})),
            ('bank_dup_value', lambda e: e['historyBank'].update(
                dup=copy.deepcopy(ref_at(e, e['initialHistory'])))),
            ('bank_shape', lambda e: ref_at(e, e['initialHistory']).update(extra=1)),
            ('receipt_ref_domain', lambda e: e['creates']['result']['receipt'].update(
                historyBefore={'historyRef': e['initialHistory']['historyRef'], 'historyDomain': 'FACADE'})),
            ('bank_entry_incomplete', lambda e: ref_at(e, e['initialHistory'])['history'].pop('entryIds')),
            ('bank_entry_extra_field', lambda e: ref_at(e, e['initialHistory'])['history'].update(extra=1)),
            ('bank_entry_bad_type', lambda e: ref_at(e, e['initialHistory'])['history'].update(position='7')),
            ('bank_oversized', lambda e: e['historyBank'].update(
                {'x%d' % i: {'historyDomain': 'FACADE', 'history': dict(
                    availability='AVAILABLE', documentBindingId='x', managerBindingId='y',
                    generation=1, revision=1, position=0, entries=['e%d' % i],
                    entryIds=['id%d' % i])} for i in range(257)})),
        ]
        for name, mutation in mutations:
            with self.subTest(control=name):
                self.reject(mutation)

    def _sized_history(self, target_bytes, fill='x'):
        """One complete eight-field history whose compact UTF-8 size is exactly target_bytes."""
        oracle = self.verify.MORPH_ORDER
        history = dict(availability='AVAILABLE', documentBindingId='d', managerBindingId='m',
                       generation=1, revision=1, position=1, entries=[''], entryIds=['giant'])
        overhead = oracle['compact'](history)
        width = len(fill.encode('utf-8'))
        payload = target_bytes - overhead
        history['entries'] = [fill * (payload // width) + 'x' * (payload % width)]
        self.assertEqual(oracle['compact'](history), target_bytes)
        return history

    def _ref_probe(self, history, ref_count, bank_id='big'):
        """A minimal section whose mapped positions carry ref_count references to one bank value."""
        ref = {'historyRef': bank_id, 'historyDomain': 'FACADE'}
        keys = ('actionHistoryBefore', 'actionHistoryAfter', 'history',
                'readHistoryBefore', 'readHistoryAfter')
        samples, remaining = [], ref_count - 1
        while remaining >= len(keys):
            samples.append({k: dict(ref) for k in keys})
            remaining -= len(keys)
        if remaining:
            samples.append({k: dict(ref) for k in keys[:remaining]})
        return {'historyBank': {bank_id: {'historyDomain': 'FACADE', 'history': history}},
                'initialHistory': dict(ref), 'samples': samples}

    def test_transport_budgets_and_utf8(self):
        oracle = self.verify.MORPH_ORDER
        e = self.fixture['morphOrder']
        # Section compact UTF-8 boundary: ==4 MiB accepted; +1 rejected.
        section_max = 4 * 1024 * 1024
        pad = section_max - oracle['compact'](dict(e, pad=''))
        r = copy.deepcopy(e); r['pad'] = 'x' * pad
        self.assertEqual(oracle['compact'](r), section_max)
        oracle['expand_section'](r)
        r = copy.deepcopy(e); r['pad'] = 'x' * (pad + 1)
        with self.assertRaises(ValueError):
            oracle['expand_section'](r)
        # 32 MiB expansion boundary through expand_section (this exercises the transport
        # decoder only; the archive member path is covered by the shared archive tests).
        limit = 32 * 1024 * 1024
        per = 512 * 1024
        history = self._sized_history(per)
        exact = self._ref_probe(history, 64)  # 64 refs x 512 KiB = exactly 32 MiB
        oracle['expand_section'](exact)
        over = self._ref_probe(history, 64)
        over['historyBank']['big2'] = {'historyDomain': 'FACADE',
                                       'history': self._sized_history(per + 1)}
        over['initialHistory'] = {'historyRef': 'big2', 'historyDomain': 'FACADE'}
        self.assertEqual(oracle['compact'](history) * 63 + per + 1, limit + 1)
        # Both unique values must fit the compact budget so this tests expansion, not that earlier guard.
        self.assertLessEqual(oracle['compact'](over), section_max)
        with self.assertRaisesRegex(ValueError, 'history expansion exceeds the 32 MiB decode budget'):
            oracle['expand_section'](over)
        # The fixed schema whitelist maps fewer positions than 512, so the retained-reference
        # budget is unreachable through legal positions alone; the Java 512/513 guard and the
        # 256/257 bank-entry guard remain the authoritative boundary for that layer.
        self.assertLess(len(oracle['REF_POSITIONS']), 512)
        # UTF-8 accounting: equal compact bytes, ASCII vs non-ASCII content — neither may be
        # mischarged. 35 mapped refs x ~900k stay under the expansion budget.
        for char in ('x', '融'):
            probe = self._ref_probe(self._sized_history(900_000, char), 35)
            oracle['expand_section'](probe)
        # And a full section whose compact UTF-8 size fits 4 MiB but whose ASCII-escaped
        # encoding would exceed it must be accepted under ensure_ascii=False accounting.
        base = oracle['compact'](dict(e, pad=''))
        pad_chars = (section_max - base) // 4
        r = copy.deepcopy(e); r['pad'] = '融' * pad_chars
        self.assertGreater(len(json.dumps(r, separators=(',', ':'), ensure_ascii=True)
                               .encode('ascii')), section_max)
        self.assertLessEqual(oracle['compact'](r), section_max)
        oracle['expand_section'](r)

    def _count_refs(self, node):
        if isinstance(node, dict):
            if set(node) == {'historyRef', 'historyDomain'}:
                yield node['historyRef']
            else:
                for v in node.values():
                    yield from self._count_refs(v)
        elif isinstance(node, list):
            for v in node:
                yield from self._count_refs(v)


if __name__ == '__main__':
    unittest.main()
