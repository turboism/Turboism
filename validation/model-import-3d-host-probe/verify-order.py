"""Independent ordinary-order oracle. Shared parsers are read-only; expected values never come from Java."""
import copy
import math

BASE = [[16, 24], [272, 24], [272, 152], [16, 152]]
MOVED = [[48, 40], [304, 40], [304, 168], [48, 168]]
SAMPLES = [('baseline', 1, 500), ('written', 1, 1000), ('undone', 1, 500), ('redone', 1, 1000),
           ('positiveMid', .5, 750), ('defaultAfterPositive', 0, 500), ('negativeEnd', -1, 0),
           ('negativeMid', -.5, 250), ('defaultFinal', 0, 500)]
ROLLBACKS = ['rollback-false-false', 'rollback-false-true', 'rollback-true-false', 'rollback-true-true', 'rollback-triple']
REJECTS = {**{'range' + str(v): 'java.lang.IllegalArgumentException' for v in (-1, 1001, -2147483648, 2147483647)},
           'default': 'java.lang.UnsupportedOperationException', 'defaultNoop': 'java.lang.UnsupportedOperationException',
           'incomplete': 'java.lang.IllegalArgumentException'}
CHECKS = {s[0] for s in SAMPLES} | set(ROLLBACKS) | set(REJECTS) | {
    'canvas', 'fixture', 'singleUndo', 'restored', 'nonCurrentCommitted', 'nonCurrentUndoRedo', 'noop'}
SHARED = ('availability', 'documentBindingId', 'managerBindingId', 'position', 'entries', 'entryIds')


def require(value, message):
    if not value:
        raise ValueError('N3c: ' + message)


def verify_order(r, verify_snapshot, common):
    e = r['order']; initial = e['initialHistory']
    require(e['schemaVersion'] == 'mi3d-native-n3c-v1' and e['status'] == 'PASS' and e['failure'] is None, 'unrun/failed')
    require(e['coordinateSpace'] == 'EDITOR_CALCULATED_ROOT_PIXELS' and e['finallyRestored'] is True, 'scope/restoration')
    common['configuration'](e['configuration'])
    require(e['configuration'] == r['evaluation']['configuration'], 'rendering configuration changed')
    checks = e['checks']
    require(len(checks) == len(CHECKS) and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST' for c in checks), 'check failed')
    for h in (initial, e['fixtureHistory'], e['writeBefore'], e['writeAfter'], e['restoredHistory']):
        common['history'](h, initial)
    for k in ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position'):
        require(initial[k] == r['evaluation']['restoredHistory'][k], 'previous matrix root identity')
    require(e['initialCounts'] == e['restoredCounts'] == r['evaluation']['restoredCounts'], 'counts not restored')
    require(e['initialCounts']['meshes'] == e['initialCounts']['deformers'] == 0, 'not blank')
    require(e['fixtureCounts'] == {**e['initialCounts'], 'meshes': 1, 'parameters': e['initialCounts']['parameters'] + 2}, 'fixture counts')
    require(e['initialCanvas'] == e['restoredCanvas'] == r['evaluation']['restoredCanvas'], 'canvas not restored')
    for canvas in (e['initialCanvas'], e['canvas'], e['restoredCanvas']):
        require(set(canvas) == {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'}
                and all(common['number'](v) for v in canvas.values())
                and canvas['width'] > 0 and canvas['height'] > 0 and canvas['pixelsPerUnit'] > 0, 'canvas metrics')
    require(e['canvasCommand'] == 'EXECUTED' and e['canvas']['width'] == e['canvas']['height'] == 1024, 'canvas setup')
    require(e['restoredHistory']['position'] == initial['position'], 'history root not restored')
    base = e['baseline']; verify_snapshot(base, grid=True)
    require(len(base['forms']) == 9 and len({c['formGuid'] for c in base['cells']}) == 9, 'aliased fixture')
    require(all(f['positions'] == BASE and f['drawOrder'] == 500 for f in base['forms']), 'baseline constants')
    for prior in (r['gridRedo'], r['evaluation']['baseline']):
        require(base['sourceGuid'] != prior['sourceGuid'] and not ({f['guid'] for f in base['forms']}
                & {f['guid'] for f in prior['forms']}), 'old matrix objects reused')
    axes = {a['id']: a['guid'] for a in base['axes']}; gx, gy = axes['ParamMI3DN2X'], axes['ParamMI3DN2Y']
    targets = {}
    for cell in base['cells']:
        values = {v['guid']: v['value'] for v in cell['coordinates']}
        if values[gy] == 0: targets[values[gx]] = cell['formGuid']
    require(set(targets) == {-1, 0, 1} and len(set(targets.values())) == 3, 'target identity')
    written = copy.deepcopy(base)
    for form in written['forms']:
        if form['guid'] == targets[1]: form['drawOrder'] = 1000
        if form['guid'] == targets[-1]: form['drawOrder'] = 0
    require(e['expectedWritten'] == written, 'stored order oracle')

    def state(s, expected):
        require(set(s) == {'snapshot', 'history', 'parameters'}, 'state fields')
        require(s['snapshot'] == expected and s['parameters'] == [0, 0], 'noncurrent stored/parameters')
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
        v = value['receipt']; require(set(v) == {'transactionId', 'label', 'historyBefore', 'historyAfter', 'historyEntryId'}, 'receipt fields')
        require(common['text'](v['transactionId']) and v['label'] == 'MI3D N3c ' + label, 'transaction identity/label')
        for h, observed in ((v['historyBefore'], before), (v['historyAfter'], after)):
            common['history'](h, h)  # Provider-local counters; never compare them to facade counters.
            require({k: h[k] for k in SHARED} == {k: observed[k] for k in SHARED}, 'receipt shared payload')
        if outcome == 'COMMITTED':
            require(after['position'] == before['position'] + 1
                    and after['entryIds'][:before['position']] == before['entryIds'][:before['position']]
                    and v['historyEntryId'] == after['entryIds'][after['position'] - 1]
                    and common['text'](v['historyEntryId']), 'single committed entry')
            require(v['historyAfter']['revision'] > v['historyBefore']['revision'], 'commit local clock')
        else:
            require(v['historyEntryId'] is None and before == after and v['historyBefore'] == v['historyAfter'], 'noncommit changed history')
        receipts.append(v)

    n = e['nonCurrent']
    for name in ('before', 'written', 'undone', 'redone', 'restored'):
        state(n[name], written if name in ('written', 'redone') else base)
    require(n['before']['history'] == e['fixtureHistory'], 'noncurrent fixture anchor')
    receipt(n['result'], 'COMMITTED', n['before']['history'], n['written']['history'], 'noncurrent targets')
    for name in ('undone', 'restored'):
        require(n[name]['history']['position'] == n['before']['history']['position'], 'noncurrent Undo position')
    require(n['redone']['history']['position'] == n['written']['history']['position'], 'noncurrent Redo position')
    require(all(n[name]['history']['entryIds'] == n['written']['history']['entryIds']
                for name in ('undone', 'redone', 'restored')), 'noncurrent Undo entry identity')
    controls = e['controls']; require(set(controls) == {'noop', *ROLLBACKS}, 'control inventory')
    for name in ('noop', *ROLLBACKS):
        row = controls[name]; state(row['before'], written); state(row['after'], written)
        require(row['before'] == row['after'] == n['written'], 'control changed root')
        receipt(row['result'], 'NO_CHANGE' if name == 'noop' else 'ROLLED_BACK',
                row['before']['history'], row['after']['history'], name)
        if name == 'noop': continue
        require(row['callbackFailure'] == 'N3C_ABORT' and type(row['steps']) is int
                and row['steps'] == (3 if name == 'rollback-triple' else 2), 'callback did not reach failure')
        mutated = copy.deepcopy(written)
        order_guid = position_guid = targets[1]; order = 700
        if name != 'rollback-triple':
            _, order_first, same = name.split('-'); order = 600
            second = targets[1 if same == 'true' else -1]
            order_guid, position_guid = (targets[1], second) if order_first == 'true' else (second, targets[1])
        for f in mutated['forms']:
            if f['guid'] == order_guid: f['drawOrder'] = order
            if f['guid'] == position_guid: f['positions'] = copy.deepcopy(MOVED)
        state(row['mutated'], mutated)
        require(row['mutated']['history'] == n['written']['history'], 'in-flight mutation committed early')
    require(set(e['rejections']) == set(REJECTS), 'rejection inventory')
    for name, exception in REJECTS.items():
        row = e['rejections'][name]
        require(row['exception'] == exception and row['before'] == row['after'] == n['written'], 'rejection effects: ' + name)
    receipt(e['writeResult'], 'COMMITTED', e['writeBefore'], e['writeAfter'], 'order targets')
    require(e['writeResult']['receipt']['historyEntryId'] != n['result']['receipt']['historyEntryId'], 'old noncurrent commit reused')
    require(len({v['transactionId'] for v in receipts}) == len(receipts), 'transaction IDs reused')
    clocks = [v[k] for v in receipts for k in ('historyBefore', 'historyAfter')]
    require(len({h['generation'] for h in clocks}) == 1 and all(a['revision'] <= b['revision'] for a, b in zip(clocks, clocks[1:])), 'provider-local clock drift')
    rows = e['samples']; require([s['id'] for s in rows] == [s[0] for s in SAMPLES], 'sample inventory')
    stable = None
    for row, (name, x, order) in zip(rows, SAMPLES):
        require(row['status'] == 'PASS' and row['x'] == x and row['y'] == 0
                and type(row['expectedOrder']) is int and row['expectedOrder'] == order, 'sample intent')
        require(row['waitReason'] == 'CONSUMED' and row['waitLimitMillis'] == 15000
                and type(row['attempts']) is int and row['attempts'] > 0
                and type(row['elapsedMillis']) is int and 0 <= row['elapsedMillis'] < 15000, 'wait protocol')
        for key in ('history', 'readHistoryBefore', 'readHistoryAfter'): common['history'](row[key], initial)
        require(row['history'] == row['readHistoryBefore'] == row['readHistoryAfter'], 'sampling history pollution')
        expected = base if name in ('baseline', 'undone') else written
        require(row['stored'] == row['observedStored'] == expected, 'stored pollution')
        common['observation'](row['before'], base)
        requested, actual = row['requested'], row['actual']
        live0, _ = common['observation'](requested, base); live, consumed = common['observation'](actual, base)
        require(actual == row['lastObservation'] and actual['passEntryTimeMillis'] >= 0
                and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'], 'no first consumed pass')
        require(live == consumed == live0 and live[gx] == ('ParamMI3DN2X', x) and live[gy] == ('ParamMI3DN2Y', 0), 'unconsumed intent')
        remaining = {k: v for k, v in live.items() if k != gx}
        if stable is None: stable = remaining
        require(stable == remaining and len(live) == e['fixtureCounts']['parameters'], 'unrelated parameters changed')
        f = actual['calculated']; require(f is not None and f['interpolated'] is True
            and f['coordinateType'] in ('DeformerLocal', 'Canvas') and f['drawOrder'] == order, 'calculated order')
        error = max(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(f['positions'], BASE))
        require(error <= .1 and common['number'](row['maxErrorPixels']) and abs(error - row['maxErrorPixels']) < 1e-5, 'calculated position pollution')
    rows = {s['id']: s for s in rows}; before, after = e['writeBefore'], e['writeAfter']
    require(rows['baseline']['history'] == before and rows['written']['history'] == after, 'main write anchors')
    require(rows['undone']['history']['position'] == before['position'] and rows['redone']['history']['position'] == after['position'], 'calculated Undo/Redo position')
    require(rows['undone']['history']['entryIds'] == rows['redone']['history']['entryIds'] == after['entryIds'], 'calculated Undo/Redo identities')
