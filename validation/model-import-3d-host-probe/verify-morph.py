"""Independent N4a morph oracle. Frozen literals only; never derives expectations from Java output."""
import copy
import math

BASE = [[16, 24], [272, 24], [272, 152], [16, 152]]
U = [[48, 40], [304, 40], [304, 168], [48, 168]]
H = [[32, 32], [288, 32], [288, 160], [32, 160]]
U2 = [[80, 56], [336, 56], [336, 184], [80, 184]]
V = [[8, 64], [264, 64], [264, 192], [8, 192]]
W = [[96, 8], [352, 8], [352, 136], [96, 136]]
SEED25 = [[24, 28], [280, 28], [280, 156], [24, 156]]
SEED75 = [[56, 44], [312, 44], [312, 172], [56, 172]]
SAMPLES = [('baseline', 1, BASE), ('written', 1, U), ('undone', 1, BASE), ('redone', 1, U),
           ('mid', .5, H), ('zero', 0, BASE),
           ('isolatedWritten', 1, U2), ('isolatedMid', .5, H), ('isolatedZero', 0, BASE)]
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
CHECKS = {s[0] for s in SAMPLES} | set(REJECTS) | {
    'canvas', 'fixture', 'baselineMorphKeys',
    'createCommitted', 'createCounts', 'createSeed', 'createTriple',
    'undoneStale', 'redoneStale', 'baseIdentity',
    'isolationCreateCommitted', 'isolationCreateSeed', 'isolationCreateCounts', 'isolationCreateTriple',
    'isolationWriteCommitted', 'isolationWriteUndo', 'isolationWriteRedo',
    'createNCommitted', 'createNCounts', 'createNSeed', 'createNTriple',
    'committedCommitted', 'committedSteps', 'committedSeed25', 'committedSeed75', 'committedCounts',
    'committedTriples', 'committedUndo1', 'committedRedo1', 'committedUndo2', 'committedRedo2',
    'committedFinal', 'rollbackFourStep', 'rollbackStaleN',
    'noop', 'positionWriteCommitted', 'positionWriteValidN', 'positionWriteStaleN', 'restored'}
SHARED = ('availability', 'documentBindingId', 'managerBindingId', 'position', 'entries', 'entryIds')
RECEIPT_LABEL = 'MI3D N4a '


def require(value, message):
    if not value:
        raise ValueError('N4a: ' + message)


def key(parameter_guid, value, form_guid):
    return {'parameterGuid': parameter_guid, 'formGuid': form_guid, 'keyValue': value, 'basicIdentity': 'UNKNOWN'}


def expect(snapshot, added_forms, added_keys):
    result = copy.deepcopy(snapshot)
    result['forms'] = result['forms'] + added_forms
    result['morphKeys'] = result['morphKeys'] + added_keys
    return result


def verify_morph(r, verify_snapshot, common):
    m = r['morph']; initial = m['initialHistory']
    require(m['schemaVersion'] == 'mi3d-native-n4a-v1' and m['status'] == 'PASS' and m['failure'] is None, 'unrun/failed')
    require(m['coordinateSpace'] == 'EDITOR_CALCULATED_ROOT_PIXELS' and m['finallyRestored'] is True, 'scope/restoration')
    common['configuration'](m['configuration'])
    require(m['configuration'] == r['order']['configuration'], 'rendering configuration changed')
    checks = m['checks']
    require(len(checks) == len(CHECKS) and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST' for c in checks), 'check failed')
    for k in ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position'):
        require(initial[k] == r['order']['restoredHistory'][k], 'previous matrix root identity')
    require(m['initialCounts'] == m['restoredCounts'] == r['order']['restoredCounts'], 'counts not restored')
    require(m['initialCounts']['meshes'] == m['initialCounts']['deformers'] == 0, 'not blank')
    require(m['fixtureCounts'] == {**m['initialCounts'], 'meshes': 1,
            'parameters': m['initialCounts']['parameters'] + 4}, 'fixture counts')
    require(m['initialCanvas'] == m['restoredCanvas'] == r['order']['restoredCanvas'], 'canvas not restored')
    for canvas in (m['initialCanvas'], m['canvas'], m['restoredCanvas']):
        require(set(canvas) == {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'}
                and all(common['number'](v) for v in canvas.values())
                and canvas['width'] > 0 and canvas['height'] > 0 and canvas['pixelsPerUnit'] > 0, 'canvas metrics')
    require(m['canvasCommand'] == 'EXECUTED' and m['canvas']['width'] == m['canvas']['height'] == 1024, 'canvas setup')
    require(m['restoredHistory']['position'] == initial['position'], 'history root not restored')
    for h in (initial, m['fixtureHistory'], m['restoredHistory']):
        common['history'](h, initial)
    base = m['baseline']; verify_snapshot(base, grid=True)
    require(len(base['forms']) == 9 and len({c['formGuid'] for c in base['cells']}) == 9, 'aliased fixture')
    require(all(f['positions'] == BASE and f['drawOrder'] == 500 for f in base['forms']), 'baseline constants')
    require(base['morphKeys'] == [] and m['baselineMorphKeys'] == [], 'host materialized an unknown morph target')
    for prior in (r['gridRedo'], r['evaluation']['baseline'], r['order']['baseline']):
        require(base['sourceGuid'] != prior['sourceGuid']
                and not ({f['guid'] for f in base['forms']} & {f['guid'] for f in prior['forms']}), 'old matrix objects reused')
    axes = {a['id']: a['guid'] for a in base['axes']}; gx, gy = axes['ParamMI3DN2X'], axes['ParamMI3DN2Y']
    params = m['morphParameters']
    require(set(params) == {'X', 'Y', 'M', 'N'} and params['X'] == {'id': 'ParamMI3DN2X', 'guid': gx}
            and params['Y'] == {'id': 'ParamMI3DN2Y', 'guid': gy}, 'axis GUID record differs from bound axes')
    require(params['M']['id'] == 'ParamMI3DN4M' and params['N']['id'] == 'ParamMI3DN4N', 'morph parameter ids')
    gm, gn = params['M']['guid'], params['N']['guid']
    require(all(common['text'](g) for g in (gm, gn)) and gm != gn and gm not in (gx, gy) and gn not in (gx, gy),
            'morph GUID identity')
    default = [c for c in base['cells'] if {v['guid']: v['value'] for v in c['coordinates']} == {gx: 0, gy: 0}]
    require(len(default) == 1, 'default tuple')
    default_form = [f for f in base['forms'] if f['guid'] == default[0]['formGuid']]
    require(len(default_form) == 1, 'default form')
    require(m['baseSnapshot'] == {'formGuid': default_form[0]['guid'], 'positions': BASE, 'drawOrder': 500},
            'read-only base differs from the ordinary default tuple form')

    def state(s, expected):
        require(set(s) == {'snapshot', 'history', 'parameters'}, 'state fields')
        require(s['snapshot'] == expected and s['parameters'] == [0, 0, 0, 0], 'noncurrent stored/parameters')
        common['history'](s['history'], initial)

    receipts = []

    def receipt(value, outcome, before, after, label):
        require(set(value) == {'outcome', 'diagnosticId', 'receipt'} and value['outcome'] == outcome, 'typed outcome')
        # Host r3: the diagnostic may carry the concrete callback failure after the
        # stable code token ("authoring.callback-failed: <Type>: <message>"); the
        # bare-code form stays legal. Never accept a foreign code token.
        did = value['diagnosticId']
        require(outcome != 'ROLLED_BACK' and did is None
                or outcome == 'ROLLED_BACK' and (did == 'authoring.callback-failed'
                                                 or (isinstance(did, str) and did.startswith('authoring.callback-failed: '))),
                'diagnostic')
        v = value['receipt']
        require(set(v) == {'transactionId', 'label', 'historyBefore', 'historyAfter', 'historyEntryId'}, 'receipt fields')
        require(common['text'](v['transactionId']) and v['label'] == RECEIPT_LABEL + label, 'transaction identity/label')
        for h, observed in ((v['historyBefore'], before), (v['historyAfter'], after)):
            common['history'](h, h)  # Provider-local counters; never compared to facade counters.
            require({k: h[k] for k in SHARED} == {k: observed[k] for k in SHARED}, 'receipt shared payload')
        if outcome == 'COMMITTED':
            require(after['position'] == before['position'] + 1
                    and after['entryIds'][:before['position']] == before['entryIds'][:before['position']]
                    and v['historyEntryId'] == after['entryIds'][after['position'] - 1]
                    and common['text'](v['historyEntryId']), 'single committed entry')
            require(v['historyAfter']['revision'] > v['historyBefore']['revision'], 'commit local clock')
        else:
            require(v['historyEntryId'] is None and before == after and v['historyBefore'] == v['historyAfter'],
                    'noncommit changed history')
        receipts.append(v)

    def triple(row, guid, value):
        require(set(row) == {'parameterGuid', 'keyValue', 'formGuid'}
                and row['parameterGuid'] == guid and row['keyValue'] == value and common['text'](row['formGuid']),
                'member triple identity')
        return row['formGuid']

    def fresh(guid, used):
        require(guid not in used, 'member form GUID reused')
        return used | {guid}

    # Linear create: one execute owns create(M,1) plus the absolute U write.
    lin = m['linearCreate']
    receipt(lin['result'], 'COMMITTED', lin['before'], lin['after'], 'create M1 write U')
    for h in (lin['before'], lin['after']):
        common['history'](h, initial)
    fU = triple(lin['triple'], gm, 1)
    require(fU not in {f['guid'] for f in base['forms']}, 'member form GUID reused')
    require(lin['seed'] == {'formGuid': fU, 'positions': BASE, 'drawOrder': 500}, 'first seed is not the base form')
    require(lin['writtenForm'] == {'formGuid': fU, 'positions': U, 'drawOrder': 500}, 'absolute stored target differs')
    require(lin['handle'] == {'parameterGuid': gm, 'keyValue': 1}, 'created handle identity')
    written = expect(base, [{'guid': fU, 'positions': U, 'drawOrder': 500}], [key(gm, 1, fU)])
    require(lin['snapshot'] == written, 'written snapshot')
    uh = m['undoneHandle']
    require(uh == {'oldHandle': 'STALE'}, 'member-deleted state must already reject the old handle')
    rh = m['redoneHandle']
    require(rh['oldHandle'] == 'STALE', 'snapshot-restored member must reject its old handle')
    require(rh['fresh'] == {'formGuid': fU, 'positions': U, 'drawOrder': 500}
            and rh['detached'] == rh['fresh'] and rh['freshHandle'] == {'parameterGuid': gm, 'keyValue': 1},
            'relookup/detached form mismatch')

    # Two-key isolation: (M,.5) is created only after the linear matrix, then (M,1) alone moves to U2.
    iso = m['isolation']; create = iso['create']
    receipt(create['result'], 'COMMITTED', create['before'], create['after'], 'create M.5')
    for h in (create['before'], create['after']):
        common['history'](h, initial)
    fH = triple(create['triple'], gm, .5)
    used = fresh(fH, {f['guid'] for f in base['forms']} | {fU})
    require(create['seed'] == {'formGuid': fH, 'positions': H, 'drawOrder': 500}, 'second key seed must interpolate to H')
    half = expect(written, [{'guid': fH, 'positions': H, 'drawOrder': 500}], [key(gm, .5, fH)])
    require(create['snapshot'] == half, 'isolation create snapshot')
    isolated = copy.deepcopy(half)
    for f in isolated['forms']:
        if f['guid'] == fU:
            f['positions'] = copy.deepcopy(U2)
    write = iso['write']
    for name, expected in (('before', half), ('written', isolated), ('undone', half), ('redone', isolated)):
        state(write[name], expected)
    receipt(write['result'], 'COMMITTED', write['before']['history'], write['written']['history'], 'write U2')
    require(write['undone']['history']['position'] == write['before']['history']['position']
            and write['redone']['history']['position'] == write['written']['history']['position'], 'isolation undo positions')
    require(write['undone']['history']['entryIds'] == write['redone']['history']['entryIds']
            == write['written']['history']['entryIds']
            and write['written']['history']['entryIds'][:write['before']['history']['position']]
            == write['before']['history']['entryIds'], 'isolation undo identities')

    # Member transaction controls at M=N=0.
    controls = m['controls']
    state(controls['before'], isolated)
    create_n = controls['createN']
    receipt(create_n['result'], 'COMMITTED', create_n['before'], create_n['after'], 'create N1')
    for h in (create_n['before'], create_n['after']):
        common['history'](h, initial)
    fN = triple(create_n['triple'], gn, 1); used = fresh(fN, used)
    require(create_n['seed'] == {'formGuid': fN, 'positions': BASE, 'drawOrder': 500}, 'unrelated target seed is the base')
    with_n = expect(isolated, [{'guid': fN, 'positions': BASE, 'drawOrder': 500}], [key(gn, 1, fN)])
    require(create_n['snapshot'] == with_n, 'createN snapshot')
    c = controls['committed']
    require(c['steps'] == 4 and len(c['triples']) == 2, 'four recorded member steps')
    fV = triple(c['triples'][0], gm, .25); fW = triple(c['triples'][1], gm, .75)
    used = fresh(fV, used); used = fresh(fW, used)
    require(c['seed25'] == {'formGuid': fV, 'positions': SEED25, 'drawOrder': 500}, 'mandatory .25 seed')
    require(c['seed75'] == {'formGuid': fW, 'positions': SEED75, 'drawOrder': 500}, 'mandatory .75 seed')
    committed = expect(with_n, [{'guid': fV, 'positions': V, 'drawOrder': 500},
                                {'guid': fW, 'positions': W, 'drawOrder': 500}],
                       [key(gm, .25, fV), key(gm, .75, fW)])
    state(c['before'], with_n); state(c['after'], committed); state(c['mutated'], committed)
    require(c['mutated']['history'] == c['before']['history'], 'in-flight mutation committed early')
    receipt(c['result'], 'COMMITTED', c['before']['history'], c['after']['history'], 'four-step')
    for name in ('undone1', 'undone2', 'final'):
        state(c[name], with_n)
        require(c[name]['history']['position'] == c['before']['history']['position']
                and c[name]['history']['entryIds'] == c['after']['history']['entryIds'], 'committed Undo identity')
    for name in ('redone1', 'redone2'):
        state(c[name], committed)
        require(c[name]['history']['position'] == c['after']['history']['position']
                and c[name]['history']['entryIds'] == c['after']['history']['entryIds'], 'committed Redo identity')
    rb = controls['rolledBack']
    receipt(rb['result'], 'ROLLED_BACK', rb['before']['history'], rb['after']['history'], 'four-step rollback')
    require(rb['steps'] == 4 and rb['callbackFailure'] == 'N4A_ABORT', 'callback did not reach sentinel')
    state(rb['before'], with_n); state(rb['after'], with_n)
    require(rb['before'] == rb['after'], 'compensation must restore before')
    # Causal handle proof: the tested handle must be acquired after the final in-flight
    # membership mutation and still be valid before the sentinel; a stale preFailure means
    # the probe captured the handle at the wrong lifecycle stage and proves nothing.
    pre = rb['preFailure']
    require(set(pre) == {'nHandle', 'handle', 'detached'} and pre['nHandle'] == 'VALID',
            'pre-failure handle was not freshly valid')
    require(pre['handle'] == {'parameterGuid': gn, 'keyValue': 1}
            and pre['detached'] == {'formGuid': fN, 'positions': BASE, 'drawOrder': 500},
            'pre-failure handle identity')
    post = rb['postFailure']
    require(set(post) == {'nHandle', 'relooked', 'relookedHandle', 'detached'} and post['nHandle'] == 'STALE',
            'compensation must stale the tested handle')
    require(post['relooked'] == pre['detached'] and post['detached'] == pre['detached']
            and post['relookedHandle'] == pre['handle'], 'relookup/detached after rollback differs')
    # Complete transient oracle: the mutated snapshot must equal the verified with_n state
    # plus exactly the two members this transaction's own triples identify — source geometry,
    # UVs, indices, axes, cells, every old form, every old key and draw orders included.
    mutated = rb['mutated']
    require(set(mutated) == {'snapshot', 'history', 'parameters'}, 'rollback mutated fields')
    require(mutated['history'] == rb['before']['history'], 'in-flight mutation committed early')
    require(mutated['parameters'] == [0, 0, 0, 0], 'rollback mutated parameters')
    mk = mutated['snapshot']['morphKeys']
    require(len(mk) == 5 and mk[:3] == with_n['morphKeys'], 'rollback mutated member set')
    require(mk[3]['parameterGuid'] == gm and mk[3]['keyValue'] == .25
            and mk[4]['parameterGuid'] == gm and mk[4]['keyValue'] == .75, 'rollback triples')
    fRV, fRW = mk[3]['formGuid'], mk[4]['formGuid']
    require(all(common['text'](g) for g in (fRV, fRW)) and fRV != fRW, 'rollback new member identity')
    used = fresh(fRV, used); used = fresh(fRW, used)
    rolled_mutated = expect(with_n, [{'guid': fRV, 'positions': V, 'drawOrder': 500},
                                     {'guid': fRW, 'positions': W, 'drawOrder': 500}],
                            [key(gm, .25, fRV), key(gm, .75, fRW)])
    require(mutated['snapshot'] == rolled_mutated,
            'rollback mutated differs from complete with_n plus its own two members')
    require(rb['seed25'] == {'formGuid': fRV, 'positions': SEED25, 'drawOrder': 500},
            'rollback .25 seed identity/content')
    require(rb['seed75'] == {'formGuid': fRW, 'positions': SEED75, 'drawOrder': 500},
            'rollback .75 seed identity/content')
    pw = controls['positionWrite']
    require(pw['detachedN'] == {'formGuid': fN, 'positions': BASE, 'drawOrder': 500}, 'detached target form')
    require(pw['detachedNHandle'] == {'parameterGuid': gn, 'keyValue': 1}, 'tested handle identity')
    noop = pw['noop']
    state(noop['before'], with_n); state(noop['after'], with_n)
    require(noop['before'] == noop['after'] and noop['nHandle'] == 'VALID', 'no-change must not stale handles')
    receipt(noop['result'], 'NO_CHANGE', noop['before']['history'], noop['after']['history'], 'noop')
    written_v = copy.deepcopy(with_n)
    for f in written_v['forms']:
        if f['guid'] == fU:
            f['positions'] = copy.deepcopy(V)
    state(pw['written'], written_v)
    receipt(pw['result'], 'COMMITTED', noop['after']['history'], pw['written']['history'], 'position write')
    # The same tested handle must survive the normal commit; only Undo may stale it.
    require(pw['nHandleAfterCommit'] == 'VALID', 'normal commit must not stale the tested handle')
    undo = pw['writeUndo']
    state(undo['state'], with_n)
    require(undo['nHandle'] == 'STALE' and undo['relooked'] == pw['detachedN']
            and undo['relookedHandle'] == pw['detachedNHandle'], 'write Undo must stale all handles')
    require(undo['state']['history']['position'] == noop['after']['history']['position']
            and undo['state']['history']['entryIds'] == pw['written']['history']['entryIds'], 'write Undo identity')
    require(set(m['rejections']) == set(REJECTS), 'rejection inventory')
    rejected = None
    for name, exception in REJECTS.items():
        row = m['rejections'][name]
        require(set(row) == {'before', 'after', 'exception'} and row['exception'] == exception
                and row['before'] == row['after'], 'rejection effects: ' + name)
        state(row['before'], with_n)
        if rejected is None:
            rejected = row['before']
        require(row['before'] == rejected, 'rejection changed history detail')
    require(len({v['transactionId'] for v in receipts}) == len(receipts), 'transaction IDs reused')
    clocks = [v[k] for v in receipts for k in ('historyBefore', 'historyAfter')]
    require(len({h['generation'] for h in clocks}) == 1
            and all(a['revision'] <= b['revision'] for a, b in zip(clocks, clocks[1:])), 'provider-local clock drift')

    rows = m['samples']; require([s['id'] for s in rows] == [s[0] for s in SAMPLES], 'sample inventory')
    stable = None
    for row, (name, value, expected) in zip(rows, SAMPLES):
        require(row['status'] == 'PASS' and row['m'] == value and row['x'] == 0 and row['y'] == 0 and row['n'] == 0
                and row['expectedPositions'] == expected, 'sample intent')
        require(row['waitReason'] == 'CONSUMED' and row['waitLimitMillis'] == 15000
                and type(row['attempts']) is int and row['attempts'] > 0
                and type(row['elapsedMillis']) is int and 0 <= row['elapsedMillis'] < 15000, 'wait protocol')
        for k in ('history', 'readHistoryBefore', 'readHistoryAfter'):
            common['history'](row[k], initial)
        require(row['history'] == row['readHistoryBefore'] == row['readHistoryAfter'], 'sampling history pollution')
        expected_stored = base if name in ('baseline', 'undone') else (
            written if name in ('written', 'redone', 'mid', 'zero') else isolated)
        require(row['stored'] == row['observedStored'] == expected_stored, 'stored pollution')
        common['observation'](row['before'], base)
        requested, actual = row['requested'], row['actual']
        live0, _ = common['observation'](requested, base); live, consumed = common['observation'](actual, base)
        require(actual == row['lastObservation'] and actual['passEntryTimeMillis'] >= 0
                and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'], 'no first consumed pass')
        require(live == consumed == live0 and live[gx] == ('ParamMI3DN2X', 0) and live[gy] == ('ParamMI3DN2Y', 0)
                and live[gm] == ('ParamMI3DN4M', value) and live[gn] == ('ParamMI3DN4N', 0), 'unconsumed intent')
        remaining = {k: v for k, v in live.items() if k != gm}
        if stable is None:
            stable = remaining
        require(stable == remaining and len(live) == m['fixtureCounts']['parameters'], 'unrelated parameters changed')
        f = actual['calculated']
        require(f is not None and f['interpolated'] is True
                and f['coordinateType'] in ('DeformerLocal', 'Canvas') and f['drawOrder'] == 500, 'calculated metadata')
        error = max(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(f['positions'], expected))
        require(error <= .1 and common['number'](row['maxErrorPixels'])
                and abs(row['maxErrorPixels'] - error) < 1e-5, 'calculated vertex error >0.1 pixels')
    by = {s['id']: s for s in rows}
    require(by['baseline']['history'] == lin['before'] and by['written']['history'] == lin['after'], 'create anchors')
    require(by['undone']['history']['position'] == lin['before']['position']
            and by['redone']['history']['position'] == lin['after']['position'], 'evaluated Undo/Redo positions')
    require(by['undone']['history']['entryIds'] == by['redone']['history']['entryIds'] == lin['after']['entryIds'],
            'Undo/Redo entry identity')
