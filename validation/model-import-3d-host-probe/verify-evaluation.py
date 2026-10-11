"""Independent fixed N3b calculated-form oracle; no Java constants or interpolation implementation imported."""
import copy
import math
import ntpath
import re

JOGL_SHA = '7dbedb4ba89d9744aa8f8e3710436ef349547058c78e5b3beb813b27b382b0b9'
BASE = [[16, 24], [272, 24], [272, 152], [16, 152]]
POSITIVE = [[48, 40], [304, 40], [304, 168], [48, 168]]
NEGATIVE = [[-4, 64], [252, 64], [252, 192], [-4, 192]]
# Frozen sample answers, independent of both the fixture writer and native interpolation.
SAMPLES = [
    ('baseline', 1, BASE), ('written', 1, POSITIVE), ('undone', 1, BASE), ('redone', 1, POSITIVE),
    ('positiveMid', .5, [[32, 32], [288, 32], [288, 160], [32, 160]]),
    ('defaultAfterPositive', 0, BASE), ('negativeEnd', -1, NEGATIVE),
    ('negativeMid', -.5, [[6, 44], [262, 44], [262, 172], [6, 172]]), ('defaultFinal', 0, BASE),
]
CHECKS = {name for name, _, _ in SAMPLES} | {'canvas', 'fixture', 'singleUndo', 'restored'}
PROPERTIES = {'sun.java2d.opengl', 'jogl.gljpanel.noogl'}


def require(value, message):
    if not value:
        raise ValueError('N3b: ' + message)


def number(value):
    return type(value) in (float, int) and math.isfinite(value)


def text(value):
    return isinstance(value, str) and bool(value.strip())


def parameter_values(state):
    require(set(state) == {'updateVersion', 'parameters'}, 'parameter state fields')
    require(type(state['updateVersion']) is int and -(2**31) <= state['updateVersion'] < 2**31, 'native signed counter')
    values = {}
    for row in state['parameters']:
        require(set(row) == {'guid', 'id', 'value'} and text(row['guid']) and text(row['id']) and number(row['value']), 'parameter row')
        require(row['guid'] not in values, 'duplicate parameter GUID')
        values[row['guid']] = (row['id'], row['value'])
    return values


def observation(o, baseline):
    require(set(o) == {'artMeshId', 'sourceGuid', 'live', 'consumed', 'passEntryTimeMillis', 'calculated'}, 'observation fields')
    require(o['artMeshId'] == baseline['artMeshId'] and o['sourceGuid'] == baseline['sourceGuid'], 'sample object identity')
    require(type(o['passEntryTimeMillis']) is int and o['passEntryTimeMillis'] >= -1, 'pass entry time')
    live, consumed = parameter_values(o['live']), parameter_values(o['consumed'])
    f = o['calculated']
    if f is not None:
        require(set(f) == {'positions', 'drawOrder', 'coordinateType', 'interpolated'}, 'calculated fields')
        require(type(f['drawOrder']) is int and type(f['interpolated']) is bool and text(f['coordinateType']), 'calculated metadata')
        require(isinstance(f['positions'], list) and len(f['positions']) == 4
                and all(isinstance(p, list) and len(p) == 2 and all(number(v) for v in p) for p in f['positions']), 'calculated positions')
    return live, consumed


def history(h, initial):
    require(set(h) == {'availability', 'documentBindingId', 'managerBindingId', 'generation', 'revision', 'position', 'entries', 'entryIds'}, 'history fields')
    require(h['availability'] == 'AVAILABLE', 'history unavailable')
    for k in ('documentBindingId', 'managerBindingId', 'generation'):
        require(h[k] == initial[k], 'history identity changed: ' + k)
    require(all(type(h[k]) is int and h[k] >= 0 for k in ('generation', 'revision', 'position')), 'history counters')
    require(len(h['entries']) == len(h['entryIds']) and 0 <= h['position'] <= len(h['entries']), 'history bounds')
    require(all(text(v) for v in h['entryIds']) and len(set(h['entryIds'])) == len(h['entryIds']), 'history entry identities')


def configuration(c):
    require(set(c) == {'properties', 'inputArguments', 'classpath', 'joglPath', 'cubismPath', 'joglSha256',
                       'workingDirectory', 'backend', 'backendEvidence'}, 'configuration fields')
    require(c['backend'] == 'OFFSCREEN' and c['backendEvidence'] == 'CONFIGURATION_DERIVED', 'backend evidence misrepresented')
    require(set(c['properties']) == PROPERTIES and all(v in (None, 'false') for v in c['properties'].values()), 'unreviewed rendering properties')
    require(isinstance(c['inputArguments'], list) and all(isinstance(a, str) for a in c['inputArguments']), 'actual JVM arguments missing')
    for arg in c['inputArguments']:
        for p in PROPERTIES:
            if arg == '-D' + p or arg.startswith('-D' + p + '='):
                require(arg == '-D' + p + '=false' and c['properties'][p] == 'false', 'unreviewed startup rendering flag')
    require(text(c['workingDirectory']) and ntpath.isabs(c['workingDirectory']), 'missing working directory')
    require(isinstance(c['classpath'], list) and all(text(p) for p in c['classpath']), 'classpath missing')
    # Native Windows JRE runs the BAT's relative classpath under its installation directory.
    normalized = []
    for p in c['classpath']:
        require('..' not in p.replace('\\', '/').split('/'), 'classpath traversal')
        normalized.append(ntpath.normpath(ntpath.join(c['workingDirectory'], p)))
    jogl = [p for p in normalized if p.replace('\\', '/').endswith('/app/lib/jogl/jogl-all.jar')]
    cubism = [p for p in normalized if p.replace('\\', '/').endswith('/app/lib/Live2D_Cubism.jar')]
    require(len(jogl) == len(cubism) == 1 and c['joglSha256'] == JOGL_SHA, 'unpinned/ambiguous official JOGL')
    require(ntpath.normpath(c['joglPath']) == jogl[0] and ntpath.normpath(c['cubismPath']) == cubism[0], 'classpath artifact binding')
    require(ntpath.dirname(ntpath.dirname(jogl[0])) == ntpath.dirname(cubism[0]), 'mixed official installation')


def verify_console(text_value):
    # Known unrelated startup warnings are not failures. A swallowed native evaluation stack is.
    require(isinstance(text_value, str) and len(text_value) > 0, 'official console missing')
    require(not re.search(r'^\s*at com\.live2d\.(?:cubism\.view\.ay\.|cubism\.doc\.model\.drawable\.CArtMeshForm\.interpolate|cubism\.doc\.model\.ACForm\.getInterpolateResult)',
                          text_value, re.MULTILINE), 'native evaluation exception stack')


def verify_evaluation(r, verify_snapshot):
    e = r['evaluation']
    require(e['schemaVersion'] == 'mi3d-native-n3b-v1' and e['status'] == 'PASS' and e['failure'] is None, 'unexecuted/failed evaluation')
    require(e['coordinateSpace'] == 'EDITOR_CALCULATED_ROOT_PIXELS' and e['finallyRestored'] is True, 'scope/restoration')
    configuration(e['configuration'])
    checks = e['checks']
    require(len(checks) == len(CHECKS) and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST' for c in checks), 'nonpassing check')
    initial = e['initialHistory']; history(initial, initial)
    require({k: initial[k] for k in ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position')}
            == {k: r['historyRestored'][k] for k in ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position')}, 'N2 root binding')
    require(e['initialCounts'] == r['restoredCounts'] == e['restoredCounts'], 'root model counts')
    require(e['initialCounts']['meshes'] == e['initialCounts']['deformers'] == 0, 'fixture not blank')
    require(e['fixtureCounts'] == {**e['initialCounts'], 'meshes': 1, 'parameters': e['initialCounts']['parameters'] + 2}, 'fixture counts')
    require(e['initialCanvas'] == e['restoredCanvas'], 'canvas not restored')
    for c in (e['initialCanvas'], e['canvas'], e['restoredCanvas']):
        require(set(c) == {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'} and all(number(v) for v in c.values()), 'canvas metrics')
        require(c['width'] > 0 and c['height'] > 0 and c['pixelsPerUnit'] > 0, 'invalid canvas')
    require(e['canvasCommand'] == 'EXECUTED' and e['canvas']['width'] == e['canvas']['height'] == 1024, '1024 canvas not established')
    baseline = e['baseline']; verify_snapshot(baseline, grid=True)
    require(baseline['sourceGuid'] != r['gridRedo']['sourceGuid'], 'old N2 source reused')
    require(len(baseline['forms']) == 9 and len({c['formGuid'] for c in baseline['cells']}) == 9, 'aliased fixture')
    require(not ({f['guid'] for f in baseline['forms']} & {f['guid'] for f in r['gridRedo']['forms']}), 'old N2 form reused')
    axes = {a['id']: a['guid'] for a in baseline['axes']}
    gx, gy = axes['ParamMI3DN2X'], axes['ParamMI3DN2Y']
    targets = {}
    for cell in baseline['cells']:
        t = {c['guid']: c['value'] for c in cell['coordinates']}
        if t[gy] == 0: targets[t[gx]] = cell['formGuid']
    expected_written = copy.deepcopy(baseline)
    for form in expected_written['forms']:
        if form['guid'] == targets[1]: form['positions'] = copy.deepcopy(POSITIVE)
        if form['guid'] == targets[-1]: form['positions'] = copy.deepcopy(NEGATIVE)
    require(e['expectedWritten'] == expected_written, 'writer target oracle differs')
    orders = {f['drawOrder'] for f in baseline['forms']}; require(len(orders) == 1, 'fixture orders not uniform')
    for h in (e['fixtureHistory'], e['writeBefore'], e['writeAfter'], e['restoredHistory']): history(h, initial)
    before, after = e['writeBefore'], e['writeAfter']
    require(e['writeOutcome'] == 'COMMITTED' and after['position'] == before['position'] + 1, 'not one committed entry')
    require(after['entryIds'][:before['position']] == before['entryIds'][:before['position']]
            and e['writeEntryId'] == after['entryIds'][after['position'] - 1] and text(e['writeEntryId']), 'write entry identity')
    require(e['restoredHistory']['position'] == initial['position'], 'root history position not restored')
    samples = e['samples']; require([s['id'] for s in samples] == [s[0] for s in SAMPLES], 'sample inventory/order')
    stable_parameters = None
    for row, (name, x, expected) in zip(samples, SAMPLES):
        require(row['status'] == 'PASS' and row['x'] == x and row['y'] == 0 and row['expectedPositions'] == expected, 'sample intent/result: ' + name)
        require(row['waitReason'] == 'CONSUMED' and row['waitLimitMillis'] == 15000, 'wrong wait protocol')
        require(type(row['attempts']) is int and row['attempts'] > 0 and type(row['elapsedMillis']) is int
                and 0 <= row['elapsedMillis'] < 15000, 'unbounded/timeout sample')
        for key in ('history', 'readHistoryBefore', 'readHistoryAfter'): history(row[key], initial)
        require(row['history'] == row['readHistoryBefore'] == row['readHistoryAfter'], 'read/wait changes history')
        expected_stored = baseline if name in ('baseline', 'undone') else expected_written
        require(row['stored'] == row['observedStored'] == expected_stored, 'stored state polluted: ' + name)
        requested, actual = row['requested'], row['actual']
        observation(row['before'], baseline)
        live_requested, _ = observation(requested, baseline); live, consumed = observation(actual, baseline)
        require(actual == row['lastObservation'], 'accepted result differs from first consumed observation')
        require(actual['passEntryTimeMillis'] >= 0 and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'], 'no new consumed pass')
        require(live == consumed == live_requested, 'values not consumed')
        require(live[gx] == ('ParamMI3DN2X', x) and live[gy] == ('ParamMI3DN2Y', 0), 'requested tuple not active')
        stable = {k: v for k, v in live.items() if k != gx}
        if stable_parameters is None: stable_parameters = stable
        require(stable == stable_parameters and len(live) == e['fixtureCounts']['parameters'], 'unrelated live parameters changed')
        f = actual['calculated']; require(f is not None and f['interpolated'] is True, 'no interpolated calculated form')
        require(f['coordinateType'] in ('DeformerLocal', 'Canvas') and f['drawOrder'] in orders, 'calculated metadata')
        error = max(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(f['positions'], expected))
        require(error <= .1 and number(row['maxErrorPixels']) and abs(row['maxErrorPixels'] - error) < 1e-5, 'calculated vertex error >0.1 pixels')
    by_name = {s['id']: s for s in samples}
    require(by_name['baseline']['history'] == before and by_name['written']['history'] == after, 'write anchors')
    require(by_name['undone']['history']['position'] == before['position']
            and by_name['redone']['history']['position'] == after['position'], 'evaluated Undo/Redo positions')
    require(by_name['undone']['history']['entryIds'] == by_name['redone']['history']['entryIds'] == after['entryIds'], 'Undo/Redo entry identity')
