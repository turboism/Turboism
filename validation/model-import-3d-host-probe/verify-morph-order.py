"""Independent N4b morph draw-order oracle. Frozen literals only; never imports Java constants."""
import copy
import json
import math


def require(condition, message):
    if not condition:
        raise ValueError('N4b: ' + message)


CHECKS = {'canvas', 'baselineMorphKeys', 'fixture',
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
          'staleRead', 'staleOrderWrite', 'stalePositionWrite', 'restored'}
SAMPLES = [('evalBaseline', 1, 0, 500), ('evalWritten', 1, 0, 1000), ('evalUndone', 1, 0, 500),
           ('evalRedone', 1, 0, 1000), ('evalMid', 0.5, 0, 750), ('evalDefault', 0, 0, 500),
           ('evalNWritten', 0, 1, 0), ('evalNMid', 0, 0.5, 250),
           ('truncMid', 0.5, 0, 600), ('truncEnd', 1, 0, 701),
           ('twoKeyZero', 0, 0, 500), ('twoKeyQuarter', 0.25, 0, 399), ('twoKeyHalf', 0.5, 0, 299),
           ('twoKeyThreeQuarter', 0.75, 0, 500), ('twoKeyEnd', 1, 0, 701)]
B = [[16, 24], [272, 24], [272, 152], [16, 152]]
V = [[8, 64], [264, 64], [264, 192], [8, 192]]
UVS = [[0, 0], [1, 0], [1, 1], [0, 1]]
INDICES = [0, 1, 2, 0, 2, 3]
PARAM_IDS = {'X': 'ParamMI3DN4BX', 'Y': 'ParamMI3DN4BY', 'M': 'ParamMI3DN4BM', 'N': 'ParamMI3DN4BN'}
SHARED = ('availability', 'documentBindingId', 'managerBindingId', 'position', 'entries', 'entryIds')
RECEIPT_KEYS = {'transactionId', 'label', 'historyBefore', 'historyAfter', 'historyEntryId'}
RECEIPT_LABEL = 'MI3D N4b '
PARAM_LABEL = 'Set Parameter Value'
REJECTION_NAMES = ('orderRange-1', 'orderRange1001', 'orderRange-2147483648', 'orderRange2147483647',
                   'staleRead', 'staleOrderWrite', 'stalePositionWrite')
UNDO_KEYS = ('state', 'undone1', 'redone1', 'undone2', 'redone2', 'redone3', 'final')
MIXED_CYCLE = ('undone1', 'redone1', 'undone2', 'redone2', 'final')
BANK_MAX_ENTRIES, BANK_MAX_REFS = 256, 512
EXPANDED_MAX = 32 * 1024 * 1024
# Observed complete N4b section is 2,179,847 compact UTF-8 bytes (51 unique banked
# histories); the earlier 1.5 MB estimate was a capacity underestimate, not a
# semantic bound. Keep a fixed 4 MiB ceiling — bounded, still 1/8 of the report cap.
SECTION_MAX = 4 * 1024 * 1024


def _ref_positions():
    """Fixed schema path -> expected domain table. References outside these positions are invalid."""
    S, T = 'FACADE', 'TRANSACTION'

    def st():
        return {'history': S}

    def rcpt(key='result'):
        return {key: {'receipt': {'historyBefore': T, 'historyAfter': T}}}

    def reset():
        return {'reset': {'writes': [{'historyBefore': S, 'historyAfter': S},
                                     {'historyBefore': S, 'historyAfter': S}]}}

    def rolled():
        return {'before': st(), 'after': st(), 'mutated': st(), **rcpt()}

    schema = {
        'initialHistory': S, 'fixtureHistory': S, 'restoredHistory': S,
        'creates': {'before': S, 'after': S, **rcpt()},
        'nonCurrent': {'before': st(), 'midMutation': st(), 'mutated': st(), 'written': st(),
                       'historyBefore': S, 'historyAfter': S, **rcpt(),
                       'noop': {'state': st(), **rcpt()},
                       'undoCycle': {k: st() for k in UNDO_KEYS}},
        'evalOrder': {'before': S, 'anchor': S, 'after': S, **rcpt()},
        'truncation': {**reset(), 'writeBefore': S, 'writeAfter': S, 'written': st(),
                       **rcpt('writeResult')},
        'secondKey': {**reset(), 'createBefore': S, 'createAfter': S, 'writeBefore': S,
                      'writeAfter': S, 'written': st(),
                      **rcpt('createResult'), **rcpt('writeResult')},
        'samples': [{k: S for k in ('actionHistoryBefore', 'actionHistoryAfter', 'history',
                                    'readHistoryBefore', 'readHistoryAfter')}
                    for _ in SAMPLES],
        'controls': {'before': st(), **reset(),
                     **{'pair-%s-%s' % (o, s): rolled()
                        for o in ('false', 'true') for s in ('false', 'true')},
                     'triple': rolled(),
                     'mixedCommit': {'before': st(), 'after': st(), 'mutated': st(), **rcpt(),
                                     **{k: st() for k in MIXED_CYCLE}},
                     'mixedRollback': rolled()},
        'rejections': {name: {'before': st(), 'after': st()} for name in REJECTION_NAMES},
    }

    def flatten(node, prefix):
        for key, sub in node.items():
            if sub in (S, T):
                yield prefix + (key,), sub
            elif isinstance(sub, dict):
                yield from flatten(sub, prefix + (key,))
            elif isinstance(sub, list):
                for i, item in enumerate(sub):
                    yield from flatten(item, prefix + (key, i))

    return dict(flatten(schema, ()))


REF_POSITIONS = _ref_positions()
HISTORY_FIELDS = {'availability', 'documentBindingId', 'managerBindingId', 'generation',
                  'revision', 'position', 'entries', 'entryIds'}


def compact(value):
    return len(json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))


def is_ref(value):
    return isinstance(value, dict) and set(value) == {'historyRef', 'historyDomain'}


def no_refs(value):
    if isinstance(value, dict):
        if set(value) & {'historyRef', 'historyDomain'}:
            return False
        return all(no_refs(v) for v in value.values())
    if isinstance(value, list):
        return all(no_refs(v) for v in value)
    return True


def history_shape(h):
    return (isinstance(h, dict) and set(h) == HISTORY_FIELDS
            and isinstance(h['availability'], str)
            and isinstance(h['documentBindingId'], str)
            and isinstance(h['managerBindingId'], str)
            and all(type(h[k]) is int and h[k] >= 0 for k in ('generation', 'revision', 'position'))
            and isinstance(h['entries'], list) and all(isinstance(v, str) for v in h['entries'])
            and isinstance(h['entryIds'], list)
            and all(v is None or isinstance(v, str) for v in h['entryIds'])
            and len(h['entries']) == len(h['entryIds']) and h['position'] <= len(h['entries']))


def expand_section(e):
    """Validates historyBank, then expands only fixed schema positions. Returns an expanded copy."""
    require(compact(e) <= SECTION_MAX, 'morphOrder section exceeds the 4 MiB transport budget')
    bank = e.get('historyBank')
    require(isinstance(bank, dict) and 0 < len(bank) <= BANK_MAX_ENTRIES,
            'historyBank missing/empty/oversized')
    seen = set()
    for ref_id, entry in bank.items():
        require(isinstance(ref_id, str) and ref_id, 'historyBank id must be a non-empty string')
        require(isinstance(entry, dict) and set(entry) == {'historyDomain', 'history'},
                'historyBank entry must be exactly historyDomain+history')
        require(entry['historyDomain'] in ('FACADE', 'TRANSACTION'), 'historyBank domain unknown')
        require(history_shape(entry['history']) and no_refs(entry['history']),
                'historyBank value must be one complete eight-field inline history')
        signature = (entry['historyDomain'],
                     json.dumps(entry['history'], sort_keys=True, separators=(',', ':')))
        require(signature not in seen, 'duplicate historyBank value within one domain')
        seen.add(signature)
    expanded = {k: v for k, v in e.items() if k != 'historyBank'}
    used, counters = set(), {'refs': 0, 'bytes': 0}

    def walk(node, path):
        if isinstance(node, dict):
            keys = set(node)
            if path in REF_POSITIONS:
                require(keys == {'historyRef', 'historyDomain'},
                        'mapped history position must be a reference tag')
                require(node['historyDomain'] == REF_POSITIONS[path],
                        'historyRef domain does not match its fixed schema position')
                entry = bank.get(node['historyRef'])
                require(entry is not None, 'historyRef points outside historyBank')
                require(entry['historyDomain'] == REF_POSITIONS[path],
                        'historyRef domain does not match bank entry domain')
                used.add(node['historyRef'])
                counters['refs'] += 1
                counters['bytes'] += compact(entry['history'])
                require(counters['refs'] <= BANK_MAX_REFS, 'historyRef count exceeds the 512 budget')
                require(counters['bytes'] <= EXPANDED_MAX,
                        'history expansion exceeds the 32 MiB decode budget')
                return copy.deepcopy(entry['history'])
            require(not (keys & {'historyRef', 'historyDomain'}),
                    'historyRef at an unmapped schema position')
            return {k: walk(v, path + (k,)) for k, v in node.items()}
        if isinstance(node, list):
            return [walk(v, path + (i,)) for i, v in enumerate(node)]
        return node

    expanded = walk(expanded, ())
    require(used == set(bank), 'orphan historyBank entries')
    return expanded


def form(guid, positions, order):
    return {'guid': guid, 'positions': [list(p) for p in positions], 'drawOrder': order}


def expect(snapshot, added_forms, added_keys):
    out = copy.deepcopy(snapshot)
    out['forms'] = out['forms'] + added_forms
    out['morphKeys'] = out['morphKeys'] + added_keys
    return out


def key(param_guid, value, form_guid):
    return {'parameterGuid': param_guid, 'keyValue': value, 'formGuid': form_guid,
            'basicIdentity': 'UNKNOWN'}


def ordered(snapshot, guid, order):
    keyed = [k for k in snapshot['morphKeys'] if k['formGuid'] == guid]
    require(len(keyed) == 1 and keyed[0]['keyValue'] is not None, 'oracle morph order target is ambiguous')
    require(not any(c['formGuid'] == guid for c in snapshot['cells']), 'oracle target is a cell form')
    out = copy.deepcopy(snapshot)
    found = [f for f in out['forms'] if f['guid'] == guid]
    require(len(found) == 1, 'oracle morph order target missing')
    found[0]['drawOrder'] = order
    return out


def moved(snapshot, guid, positions):
    out = copy.deepcopy(snapshot)
    found = [f for f in out['forms'] if f['guid'] == guid]
    require(len(found) == 1, 'oracle morph position target missing')
    found[0]['positions'] = [list(p) for p in positions]
    return out


def verify_morph_order(r, verify_snapshot, common):
    e = r['morphOrder']
    require(isinstance(e, dict), 'morphOrder missing')
    require(e.get('schemaVersion') == 'mi3d-native-n4b-v2', 'morphOrder schema')
    require(e.get('status') == 'PASS' and e.get('failure') is None, 'morphOrder not PASS')
    require(e.get('coordinateSpace') == 'EDITOR_CALCULATED_ROOT_PIXELS'
            and e.get('finallyRestored') is True, 'morphOrder scope/restoration')
    section = expand_section(e)
    common['configuration'](section['configuration'])
    require(section['configuration'] == r['morph']['configuration'], 'rendering configuration changed')
    checks = section['checks']
    require(len(checks) == len(CHECKS) and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST'
                for c in checks), 'check failed')
    initial = section['initialHistory']
    for k in ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position'):
        require(initial[k] == r['morph']['restoredHistory'][k], 'previous matrix root identity')
    require(section['initialCounts'] == section['restoredCounts'] == r['morph']['restoredCounts'],
            'counts not restored')
    require(section['initialCounts']['meshes'] == section['initialCounts']['deformers'] == 0, 'not blank')
    require(section['fixtureCounts'] == {**section['initialCounts'], 'meshes': 1,
            'parameters': section['initialCounts']['parameters'] + 4}, 'fixture counts')
    require(section['initialCanvas'] == section['restoredCanvas'] == r['morph']['restoredCanvas'],
            'canvas not restored')
    for canvas in (section['initialCanvas'], section['canvas'], section['restoredCanvas']):
        require(set(canvas) == {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'}
                and all(common['number'](v) for v in canvas.values())
                and canvas['width'] > 0 and canvas['height'] > 0 and canvas['pixelsPerUnit'] > 0,
                'canvas metrics')
    require(section['canvasCommand'] == 'EXECUTED'
            and section['canvas']['width'] == section['canvas']['height'] == 1024, 'canvas setup')
    require(section['restoredHistory']['position'] == initial['position'], 'history root not restored')
    for h in (initial, section['fixtureHistory'], section['restoredHistory']):
        common['history'](h, initial)
    base = section['baseline']
    require(set(base) == {'artMeshId', 'sourceGuid', 'positions', 'uvs', 'indices',
                          'axes', 'cells', 'forms', 'morphKeys'}, 'baseline fields')
    require(base['positions'] == B and base['uvs'] == UVS and base['indices'] == INDICES,
            'fixed geometry differs')
    require(common['text'](base['artMeshId']) and common['text'](base['sourceGuid']), 'mesh identity')
    require(len(base['forms']) == 9 and len({f['guid'] for f in base['forms']}) == 9
            and len({c['formGuid'] for c in base['cells']}) == 9
            and {c['formGuid'] for c in base['cells']} == {f['guid'] for f in base['forms']},
            'baseline form inventory')
    require(all(f['positions'] == B and f['drawOrder'] == 500 for f in base['forms']),
            'baseline constants')
    require(base['morphKeys'] == [] and section['baselineMorphKeys'] == [],
            'host materialized an unknown morph target')
    by_id = {a['id']: a['guid'] for a in base['axes']}
    require(len(base['axes']) == 2 and set(by_id) == {PARAM_IDS['X'], PARAM_IDS['Y']}
            and len(set(by_id.values())) == 2, 'axis identities')
    for a in base['axes']:
        require(set(a) == {'guid', 'id', 'minimum', 'default', 'maximum', 'keys'}
                and a['minimum'] == -1 and a['default'] == 0 and a['maximum'] == 1
                and sorted(a['keys']) == [-1, 0, 1], 'axis range/keys/default')
    observed = []
    for c in base['cells']:
        require(set(c) == {'formGuid', 'coordinates'} and len(c['coordinates']) == 2, 'cell shape')
        t = {v['guid']: v['value'] for v in c['coordinates']}
        require(set(t) == set(by_id.values()), 'tuple dimensions')
        observed.append((t[by_id[PARAM_IDS['X']]], t[by_id[PARAM_IDS['Y']]]))
    require(len(observed) == 9 and len(set(observed)) == 9
            and {v for pair in observed for v in pair} == {-1, 0, 1}, 'baseline tuple coverage')
    for prior in (r['gridRedo'], r['evaluation']['baseline'], r['order']['baseline'], r['morph']['baseline']):
        require(base['sourceGuid'] != prior['sourceGuid']
                and not ({f['guid'] for f in base['forms']} & {f['guid'] for f in prior['forms']}),
                'old matrix objects reused')
    gx, gy = by_id[PARAM_IDS['X']], by_id[PARAM_IDS['Y']]
    params = section['morphParameters']
    require(set(params) == {'X', 'Y', 'M', 'N'}
            and params['X'] == {'id': PARAM_IDS['X'], 'guid': gx}
            and params['Y'] == {'id': PARAM_IDS['Y'], 'guid': gy}, 'axis GUID record differs')
    require(params['M']['id'] == PARAM_IDS['M'] and params['N']['id'] == PARAM_IDS['N'],
            'morph parameter ids')
    gm, gn = params['M']['guid'], params['N']['guid']
    require(all(params[k]['guid'] != params[k]['id'] for k in params), 'parameter GUID equals its id')
    require(all(common['text'](g) for g in (gm, gn)) and gm != gn and gm not in (gx, gy)
            and gn not in (gx, gy), 'morph GUID identity')
    fixture_history = section['fixtureHistory']

    def state(s, expected, parameters=(0, 0, 0, 0)):
        require(set(s) == {'snapshot', 'history', 'parameters'}, 'state fields')
        require(s['snapshot'] == expected and s['parameters'] == list(parameters),
                'unexpected stored/parameters')
        common['history'](s['history'], initial)

    receipts = []

    def receipt(value, outcome, before, after, label):
        require(set(value) == {'outcome', 'diagnosticId', 'receipt'} and value['outcome'] == outcome,
                'typed outcome')
        # Host r3: the diagnostic may carry the concrete callback failure after the
        # stable code token ("authoring.callback-failed: <Type>: <message>"); the
        # bare-code form stays legal. Never accept a foreign code token.
        did = value['diagnosticId']
        require(outcome != 'ROLLED_BACK' and did is None
                or outcome == 'ROLLED_BACK' and (did == 'authoring.callback-failed'
                                                 or (isinstance(did, str) and did.startswith('authoring.callback-failed: '))),
                'diagnostic')
        v = value['receipt']
        require(set(v) == RECEIPT_KEYS, 'receipt fields')
        require(common['text'](v['transactionId']) and v['label'] == RECEIPT_LABEL + label,
                'transaction identity/label')
        for h, observed_history in ((v['historyBefore'], before), (v['historyAfter'], after)):
            common['history'](h, h)  # Provider-local counters; never compared to facade counters.
            require({k: h[k] for k in SHARED} == {k: observed_history[k] for k in SHARED},
                    'receipt shared payload')
        if outcome == 'COMMITTED':
            require(after['position'] == before['position'] + 1
                    and after['entryIds'][:before['position']] == before['entryIds'][:before['position']]
                    and v['historyEntryId'] == after['entryIds'][after['position'] - 1]
                    and common['text'](v['historyEntryId']), 'single committed entry')
            require(v['historyAfter']['revision'] > v['historyBefore']['revision'], 'commit local clock')
        else:
            require(v['historyEntryId'] is None and before == after
                    and v['historyBefore'] == v['historyAfter'], 'noncommit changed history')
        receipts.append(v)

    def commit_transition(before, after, label=None):
        """A standalone commit: applied prefix kept, exactly one new entry; a redo tail may be cut."""
        require(after['position'] == before['position'] + 1
                and after['entryIds'][:before['position'] + 1]
                == before['entryIds'][:before['position']] + [after['entryIds'][before['position']]]
                and after['entryIds'][before['position']] not in before['entryIds'],
                'not exactly one appended history entry')
        tail = after['entryIds'][before['position'] + 1:]
        require(tail == [] or tail == before['entryIds'][before['position']:],
                'redo tail must be cut or preserved verbatim')
        require(len(after['entries']) == len(after['entryIds']), 'entry payload drift')
        require(after['revision'] > before['revision'], 'local revision must grow on commit')
        if label is not None:
            require(label in after['entries'][before['position']], 'committed entry label')

    def param_transition(before, after):
        commit_transition(before, after, PARAM_LABEL)

    def restore_transition(before, after, position):
        require(after['position'] == position
                and after['entryIds'] == before['entryIds']
                and all(after[k] == before[k] for k in
                        ('availability', 'documentBindingId', 'managerBindingId', 'generation'))
                and after['revision'] > before['revision'], 'undo/redo history identity')

    creates = section['creates']
    receipt(creates['result'], 'COMMITTED', creates['before'], creates['after'], 'create M1 N1')
    require(creates['steps'] == 2, 'create steps')
    common['history'](creates['before'], initial)
    require(creates['before'] == fixture_history, 'create history anchor')
    common['history'](creates['after'], initial)
    triples = creates['triples']
    require(len(triples) == 2 and all(set(t) == {'parameterGuid', 'keyValue', 'formGuid'} for t in triples)
            and triples[0]['parameterGuid'] == gm and triples[0]['keyValue'] == 1
            and triples[1]['parameterGuid'] == gn and triples[1]['keyValue'] == 1
            and all(common['text'](t['formGuid']) for t in triples)
            and triples[0]['formGuid'] != triples[1]['formGuid'], 'create triples')
    fm, fn = triples[0]['formGuid'], triples[1]['formGuid']
    base_forms = {f['guid'] for f in base['forms']}
    require(fm not in base_forms and fn not in base_forms, 'member form GUID reused')
    require(creates['seedM'] == {'formGuid': fm, 'positions': B, 'drawOrder': 500}
            and creates['seedN'] == {'formGuid': fn, 'positions': B, 'drawOrder': 500},
            'member seed order/positions')
    created = expect(base, [form(fm, B, 500), form(fn, B, 500)],
                     [key(gm, 1, fm), key(gn, 1, fn)])
    require(creates['snapshot'] == created, 'create snapshot')
    eval_written = ordered(ordered(created, fm, 1000), fn, 0)
    trunc_written = ordered(ordered(created, fm, 701), fn, 0)

    nc = section['nonCurrent']
    state(nc['before'], created)
    require(nc['before']['history'] == creates['after'], 'nonCurrent history anchor')
    common['history'](nc['historyBefore'], initial)
    require(nc['historyBefore'] == nc['before']['history'], 'nonCurrent pre-transaction history')
    common['history'](nc['historyAfter'], initial)
    receipt(nc['result'], 'COMMITTED', nc['historyBefore'], nc['historyAfter'], 'noncurrent order')
    state(nc['midMutation'], ordered(created, fm, 1000))
    require(nc['midMutation']['history'] == nc['historyBefore'], 'in-flight first write committed early')
    state(nc['mutated'], eval_written)
    require(nc['mutated']['history'] == nc['historyBefore'], 'in-flight writes committed early')
    state(nc['written'], eval_written)
    require(nc['written']['history'] == nc['historyAfter'], 'nonCurrent written history')
    require(nc['handleAfterCommit'] == 'VALID', 'handle must stay VALID after commit')
    noop = nc['noop']
    receipt(noop['result'], 'NO_CHANGE', nc['historyAfter'], nc['historyAfter'], 'noop')
    state(noop['state'], eval_written)
    require(noop['state']['history'] == nc['historyAfter'] and noop['handle'] == 'VALID',
            'no-change polluted state/handle')
    undo = nc['undoCycle']
    require(undo['oldHandle'] == 'STALE', 'member-deleted state must reject the old handle')
    require(undo['detached'] == {'formGuid': fm, 'positions': B, 'drawOrder': 1000}
            and undo['fresh'] == {'formGuid': fm, 'positions': B, 'drawOrder': 500}
            and undo['freshHandle'] == {'parameterGuid': gm, 'keyValue': 1}, 'stale/fresh causality')
    for name, snap, pos in (('state', created, nc['historyBefore']['position']),
                            ('undone1', created, nc['historyBefore']['position']),
                            ('undone2', created, nc['historyBefore']['position']),
                            ('final', created, nc['historyBefore']['position']),
                            ('redone1', eval_written, nc['historyAfter']['position']),
                            ('redone2', eval_written, nc['historyAfter']['position']),
                            ('redone3', eval_written, nc['historyAfter']['position'])):
        state(undo[name], snap)
        require(undo[name]['history']['position'] == pos
                and undo[name]['history']['entryIds'] == nc['historyAfter']['entryIds'],
                'undo/redo position or entry identity')
    # Real Java order: first undo (state), then per round redone then undone, then redone3 and final.
    prev = nc['historyAfter']
    for name, pos in (('state', nc['historyBefore']['position']),
                      ('redone1', nc['historyAfter']['position']),
                      ('undone1', nc['historyBefore']['position']),
                      ('redone2', nc['historyAfter']['position']),
                      ('undone2', nc['historyBefore']['position']),
                      ('redone3', nc['historyAfter']['position']),
                      ('final', nc['historyBefore']['position'])):
        restore_transition(prev, undo[name]['history'], pos)
        prev = undo[name]['history']

    eo = section['evalOrder']
    common['history'](eo['before'], initial)
    common['history'](eo['after'], initial)
    common['history'](eo['anchor'], initial)
    require(eo['anchor'] == eo['before'], 'eval anchor is the pre-write history')
    receipt(eo['result'], 'COMMITTED', eo['before'], eo['after'], 'evaluated order')
    trunc = section['truncation']
    common['history'](trunc['writeBefore'], initial)
    common['history'](trunc['writeAfter'], initial)
    receipt(trunc['writeResult'], 'COMMITTED', trunc['writeBefore'], trunc['writeAfter'],
            'truncating order')
    state(trunc['written'], trunc_written)
    require(trunc['written']['history'] == trunc['writeAfter'], 'trunc write history')
    second = section['secondKey']
    common['history'](second['createBefore'], initial)
    common['history'](second['createAfter'], initial)
    receipt(second['createResult'], 'COMMITTED', second['createBefore'], second['createAfter'],
            'create M.5')
    fh = second['triple']['formGuid']
    require(second['triple'] == {'parameterGuid': gm, 'keyValue': 0.5, 'formGuid': fh}
            and common['text'](fh) and fh not in base_forms | {fm, fn}, 'half triple')
    require(second['seed'] == {'formGuid': fh, 'positions': B, 'drawOrder': 600},
            'asymmetric seed must truncate 600.5 to 600')
    with_half = expect(trunc_written, [form(fh, B, 600)], [key(gm, 0.5, fh)])
    require(second['snapshot'] == with_half, 'half create snapshot')
    common['history'](second['writeBefore'], initial)
    common['history'](second['writeAfter'], initial)
    receipt(second['writeResult'], 'COMMITTED', second['writeBefore'], second['writeAfter'],
            'write M.5 order')
    half_written = ordered(with_half, fh, 299)
    state(second['written'], half_written)
    require(second['written']['history'] == second['writeAfter'], 'half write history')
    stored_for = {'evalBaseline': created, 'evalWritten': eval_written, 'evalUndone': created,
                  'evalRedone': eval_written, 'evalMid': eval_written, 'evalDefault': eval_written,
                  'evalNWritten': eval_written, 'evalNMid': eval_written,
                  'truncMid': trunc_written, 'truncEnd': trunc_written,
                  'twoKeyZero': half_written, 'twoKeyQuarter': half_written, 'twoKeyHalf': half_written,
                  'twoKeyThreeQuarter': half_written, 'twoKeyEnd': half_written}
    # Fixed per-sample parameter states before the action runs (the Java phase sequence).
    params_before = {'evalBaseline': [0, 0, 0, 0], 'evalWritten': [0, 0, 1, 0],
                     'evalUndone': [0, 0, 1, 0], 'evalRedone': [0, 0, 1, 0],
                     'evalMid': [0, 0, 1, 0], 'evalDefault': [0, 0, 0.5, 0],
                     'evalNWritten': [0, 0, 0, 0], 'evalNMid': [0, 0, 0, 1],
                     'truncMid': [0, 0, 0, 0], 'truncEnd': [0, 0, 0.5, 0],
                     'twoKeyZero': [0, 0, 0, 0], 'twoKeyQuarter': [0, 0, 0, 0],
                     'twoKeyHalf': [0, 0, 0.25, 0], 'twoKeyThreeQuarter': [0, 0, 0.5, 0],
                     'twoKeyEnd': [0, 0, 0.75, 0]}
    # Fixed action kinds per sample id; the report's own labels are never trusted.
    actions = {'evalBaseline': ('param', 'M', 1), 'evalWritten': ('order',),
               'evalUndone': ('restore', 'anchor'), 'evalRedone': ('restore', 'after'),
               'evalMid': ('param', 'M', 0.5), 'evalDefault': ('param', 'M', 0),
               'evalNWritten': ('param', 'N', 1), 'evalNMid': ('param', 'N', 0.5),
               'truncMid': ('param', 'M', 0.5), 'truncEnd': ('param', 'M', 1),
               'twoKeyZero': ('param', 'M', 0), 'twoKeyQuarter': ('param', 'M', 0.25),
               'twoKeyHalf': ('param', 'M', 0.5), 'twoKeyThreeQuarter': ('param', 'M', 0.75),
               'twoKeyEnd': ('param', 'M', 1)}
    rows = section['samples']
    require([s['id'] for s in rows] == [s[0] for s in SAMPLES], 'sample inventory')
    for row, (name, m, n, order) in zip(rows, SAMPLES):
        require(row['status'] == 'PASS' and 'failure' not in row and row['x'] == 0 and row['y'] == 0
                and row['m'] == m and row['n'] == n
                and type(row['expectedOrder']) is int and row['expectedOrder'] == order
                and row['expectedPositions'] == B, 'sample intent ' + name)
        require(row['waitReason'] == 'CONSUMED' and row['waitLimitMillis'] == 15000
                and type(row['attempts']) is int and row['attempts'] > 0
                and type(row['elapsedMillis']) is int and 0 <= row['elapsedMillis'] < 15000,
                'wait protocol ' + name)
        for k in ('actionHistoryBefore', 'actionHistoryAfter', 'history',
                  'readHistoryBefore', 'readHistoryAfter'):
            common['history'](row[k], initial)
        require(row['history'] == row['actionHistoryAfter']
                == row['readHistoryBefore'] == row['readHistoryAfter'],
                'sampling history pollution ' + name)
        kind = actions[name]
        if kind[0] == 'param':
            require(row['parametersBefore'] == params_before[name]
                    and row['parametersAfter'] == [0, 0, m, n], 'sample parameter transition ' + name)
            if row['parametersBefore'] == row['parametersAfter']:
                require(row['actionHistoryBefore'] == row['actionHistoryAfter'],
                        'same-value parameter write changed history ' + name)
            else:
                param_transition(row['actionHistoryBefore'], row['actionHistoryAfter'])
        elif kind[0] == 'order':
            require(row['parametersBefore'] == row['parametersAfter'] == params_before[name],
                    'order write changed parameters ' + name)
            commit_transition(row['actionHistoryBefore'], row['actionHistoryAfter'])
        else:
            require(row['parametersBefore'] == row['parametersAfter'] == params_before[name],
                    'restore changed parameters ' + name)
            restore_transition(row['actionHistoryBefore'], row['actionHistoryAfter'],
                               eo[kind[1]]['position'])
        require(row['stored'] == row['observedStored'] == stored_for[name], 'stored pollution ' + name)
        before_live, _ = common['observation'](row['before'], base)
        # Java records parameters() immediately after observe() in one EDT callback: the live
        # parameter values must equal the recorded pre-action values. consumed/calculated may lag.
        require(before_live.get(gx) == (PARAM_IDS['X'], row['parametersBefore'][0])
                and before_live.get(gy) == (PARAM_IDS['Y'], row['parametersBefore'][1])
                and before_live.get(gm) == (PARAM_IDS['M'], row['parametersBefore'][2])
                and before_live.get(gn) == (PARAM_IDS['N'], row['parametersBefore'][3]),
                'before live parameters must match the recorded pre-action values ' + name)
        requested, actual = row['requested'], row['actual']
        live0, _ = common['observation'](requested, base)
        live, consumed = common['observation'](actual, base)
        require(actual == row['lastObservation'] and type(actual['passEntryTimeMillis']) is int
                and actual['passEntryTimeMillis'] >= 0
                and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'],
                'no first consumed pass ' + name)
        require(live == consumed == live0 and len(live) == section['fixtureCounts']['parameters']
                and live[gx] == (PARAM_IDS['X'], 0) and live[gy] == (PARAM_IDS['Y'], 0)
                and live[gm] == (PARAM_IDS['M'], m) and live[gn] == (PARAM_IDS['N'], n),
                'unconsumed intent ' + name)
        f = actual['calculated']
        require(f is not None and f['interpolated'] is True
                and f['coordinateType'] in ('DeformerLocal', 'Canvas')
                and f['drawOrder'] == order, 'calculated order ' + name)
        error = max(math.hypot(a[0] - b[0], a[1] - b[1]) for a, b in zip(f['positions'], B))
        require(error <= .1 and common['number'](row['maxErrorPixels'])
                and abs(error - row['maxErrorPixels']) < 1e-5, 'calculated position pollution ' + name)
    by_id_rows = {s['id']: s for s in rows}
    cursor = undo['final']['history']
    for name in ('evalBaseline', 'evalWritten', 'evalUndone', 'evalRedone',
                 'evalMid', 'evalDefault', 'evalNWritten', 'evalNMid'):
        require(by_id_rows[name]['actionHistoryBefore'] == cursor, 'phase history anchor ' + name)
        cursor = by_id_rows[name]['actionHistoryAfter']
    require(by_id_rows['evalBaseline']['actionHistoryAfter'] == eo['before'],
            'eval baseline anchor')
    require(by_id_rows['evalWritten']['actionHistoryBefore'] == eo['before']
            and by_id_rows['evalWritten']['actionHistoryAfter'] == eo['after'],
            'eval write action anchors')
    require(by_id_rows['evalUndone']['actionHistoryAfter']['position'] == eo['anchor']['position']
            and by_id_rows['evalUndone']['actionHistoryAfter']['entryIds'] == eo['after']['entryIds'],
            'eval undo must restore to the parameter anchor, not the parameter write')

    def reset(row, before_params, expected_writes):
        require(row['parametersBefore'] == list(before_params)
                and row['parametersAfter'] == [0, 0, 0, 0], 'reset parameter transition')
        writes = row['writes']
        require(len(writes) == 2 and [w['parameter'] for w in writes] == ['M', 'N']
                and all(set(w) == {'parameter', 'historyBefore', 'historyAfter'} for w in writes),
                'reset write inventory')
        for w in writes:
            common['history'](w['historyBefore'], initial)
            common['history'](w['historyAfter'], initial)
        require(writes[0]['historyBefore'] == expected_writes[0], 'reset history anchor')
        require(writes[0]['historyAfter'] == writes[1]['historyBefore'], 'reset write chain')
        for w, changed in zip(writes, (before_params[2] != 0, before_params[3] != 0)):
            if changed:
                param_transition(w['historyBefore'], w['historyAfter'])
            else:
                require(w['historyBefore'] == w['historyAfter'],
                        'same-value ' + w['parameter'] + ' reset changed history')
        return writes[1]['historyAfter']

    require(reset(trunc['reset'], (0, 0, 0, 0.5), [cursor]) == trunc['writeBefore'],
            'truncation reset does not lead into the order write')
    require(by_id_rows['truncMid']['actionHistoryBefore'] == trunc['writeAfter']
            and by_id_rows['truncEnd']['actionHistoryBefore']
            == by_id_rows['truncMid']['actionHistoryAfter'], 'trunc sample anchors')
    cursor = by_id_rows['truncEnd']['actionHistoryAfter']
    require(reset(second['reset'], (0, 0, 1, 0), [cursor]) == second['createBefore'],
            'second-key reset does not lead into the create')
    require(second['createAfter'] == second['writeBefore'], 'create/write history chain')
    cursor = second['writeAfter']
    for name in ('twoKeyZero', 'twoKeyQuarter', 'twoKeyHalf', 'twoKeyThreeQuarter', 'twoKeyEnd'):
        require(by_id_rows[name]['actionHistoryBefore'] == cursor, 'twoKey phase anchor ' + name)
        cursor = by_id_rows[name]['actionHistoryAfter']

    controls = section['controls']
    controls_history = reset(controls['reset'], (0, 0, 1, 0), [cursor])
    state(controls['before'], half_written)
    require(controls['before']['history'] == controls_history, 'controls history anchor')
    mutated_same = moved(ordered(half_written, fm, 800), fm, V)
    mutated_diff = moved(ordered(half_written, fm, 800), fn, V)
    for order_first in (False, True):
        for same in (False, True):
            name = 'pair-%s-%s' % (str(order_first).lower(), str(same).lower())
            row = controls[name]
            state(row['before'], half_written)
            state(row['after'], half_written)
            require(row['before']['history'] == row['after']['history'] == controls_history,
                    name + ' rollback changed history')
            receipt(row['result'], 'ROLLED_BACK', row['before']['history'], row['after']['history'],
                    name)
            require(row['callbackFailure'] == 'N4B_ABORT' and type(row['steps']) is int
                    and row['steps'] == 2, name + ' callback did not reach failure')
            state(row['mutated'], mutated_same if same else mutated_diff)
            require(row['mutated']['history'] == controls_history,
                    name + ' in-flight mutation committed early')
    triple_row = controls['triple']
    state(triple_row['before'], half_written)
    state(triple_row['after'], half_written)
    require(triple_row['before']['history'] == triple_row['after']['history'] == controls_history,
            'triple rollback changed history')
    receipt(triple_row['result'], 'ROLLED_BACK', triple_row['before']['history'],
            triple_row['after']['history'], 'triple')
    require(triple_row['callbackFailure'] == 'N4B_ABORT' and type(triple_row['steps']) is int
            and triple_row['steps'] == 3, 'triple callback did not reach failure')
    state(triple_row['mutated'], ordered(half_written, fm, 900))
    require(triple_row['mutated']['history'] == controls_history, 'triple in-flight committed early')
    mc = controls['mixedCommit']
    state(mc['before'], half_written)
    require(mc['before']['history'] == controls_history, 'mixedCommit history anchor')
    receipt(mc['result'], 'COMMITTED', mc['before']['history'], mc['after']['history'],
            'mixed member')
    require(type(mc['steps']) is int and mc['steps'] == 4, 'mixedCommit steps')
    q = mc['triple']
    require(set(q) == {'parameterGuid', 'keyValue', 'formGuid'} and q['parameterGuid'] == gm
            and q['keyValue'] == 0.25 and common['text'](q['formGuid'])
            and q['formGuid'] not in base_forms | {fm, fn, fh}, 'quarter triple')
    fq = q['formGuid']
    require(mc['seed'] == {'formGuid': fq, 'positions': B, 'drawOrder': 399},
            'quarter seed must interpolate to 399')
    mixed = ordered(expect(half_written, [form(fq, V, 700)], [key(gm, 0.25, fq)]), fm, 900)
    state(mc['mutated'], mixed)
    require(mc['mutated']['history'] == controls_history, 'mixedCommit in-flight committed early')
    state(mc['after'], mixed)
    for i in (1, 2):
        state(mc['undone%d' % i], half_written)
        require(mc['undone%d' % i]['history']['position'] == mc['before']['history']['position']
                and mc['undone%d' % i]['history']['entryIds'] == mc['after']['history']['entryIds'],
                'mixedCommit undo identity')
        state(mc['redone%d' % i], mixed)
        require(mc['redone%d' % i]['history']['position'] == mc['after']['history']['position']
                and mc['redone%d' % i]['history']['entryIds'] == mc['after']['history']['entryIds'],
                'mixedCommit redo must restore the committed entry identity')
    state(mc['final'], half_written)
    require(mc['final']['history']['position'] == mc['before']['history']['position']
            and mc['final']['history']['entryIds'] == mc['after']['history']['entryIds'],
            'mixedCommit final identity')
    # Real Java order: committed after, then undone/redone legs, then final undone.
    prev = mc['after']['history']
    for name, pos in (('undone1', mc['before']['history']['position']),
                      ('redone1', mc['after']['history']['position']),
                      ('undone2', mc['before']['history']['position']),
                      ('redone2', mc['after']['history']['position']),
                      ('final', mc['before']['history']['position'])):
        restore_transition(prev, mc[name]['history'], pos)
        prev = mc[name]['history']
    mr = controls['mixedRollback']
    state(mr['before'], half_written)
    state(mr['after'], half_written)
    require(mr['before']['history'] == mr['after']['history']
            and mr['before']['history']['position'] == controls_history['position']
            and mr['before']['history']['entryIds'] == mc['after']['history']['entryIds'],
            'mixedRollback changed history')
    receipt(mr['result'], 'ROLLED_BACK', mr['before']['history'], mr['after']['history'],
            'mixed rollback')
    require(mr['callbackFailure'] == 'N4B_ABORT' and type(mr['steps']) is int and mr['steps'] == 4,
            'mixedRollback steps')
    rq = mr['triple']
    require(set(rq) == {'parameterGuid', 'keyValue', 'formGuid'} and rq['parameterGuid'] == gm
            and rq['keyValue'] == 0.25 and common['text'](rq['formGuid'])
            and rq['formGuid'] not in base_forms | {fm, fn, fh, fq},
            'rolled-back create must carry its own fresh member identity')
    fq2 = rq['formGuid']
    require(mr['seed'] == {'formGuid': fq2, 'positions': B, 'drawOrder': 399},
            'rolled-back quarter seed')
    mixed_rb = ordered(expect(half_written, [form(fq2, V, 700)], [key(gm, 0.25, fq2)]), fm, 900)
    state(mr['mutated'], mixed_rb)
    require(mr['mutated']['history'] == mr['before']['history'],
            'mixedRollback in-flight committed early')
    require(mr['preFailure'] == {'nHandle': 'VALID', 'handle': {'parameterGuid': gn, 'keyValue': 1},
                               'detached': {'formGuid': fn, 'positions': B, 'drawOrder': 0}},
            'pre-failure N handle must be VALID')
    require(mr['postFailure'] == {'nHandle': 'STALE',
                                  'relooked': {'formGuid': fn, 'positions': B, 'drawOrder': 0},
                                  'relookedHandle': {'parameterGuid': gn, 'keyValue': 1},
                                  'detached': {'formGuid': fn, 'positions': B, 'drawOrder': 0}},
            'post-compensation N handle must be STALE')
    exceptions = {'orderRange-1': 'java.lang.IllegalArgumentException',
                  'orderRange1001': 'java.lang.IllegalArgumentException',
                  'orderRange-2147483648': 'java.lang.IllegalArgumentException',
                  'orderRange2147483647': 'java.lang.IllegalArgumentException',
                  'staleRead': 'java.lang.IllegalStateException',
                  'staleOrderWrite': 'java.lang.IllegalStateException',
                  'stalePositionWrite': 'java.lang.IllegalStateException'}
    require(set(section['rejections']) == set(exceptions), 'rejection inventory')
    for name, error in exceptions.items():
        row = section['rejections'][name]
        require(row['exception'] == error, 'rejection ' + name)
        state(row['before'], half_written)
        state(row['after'], half_written)
        require(row['before']['history'] == row['after']['history']
                and row['before']['history']['position'] == controls_history['position']
                and row['before']['history']['entryIds'] == mc['after']['history']['entryIds'],
                'rejection changed state ' + name)
    require(len({v['transactionId'] for v in receipts}) == len(receipts), 'transaction IDs reused')
    clocks = [v[k] for v in receipts for k in ('historyBefore', 'historyAfter')]
    require(len({h['generation'] for h in clocks}) == 1
            and all(a['revision'] <= b['revision'] for a, b in zip(clocks, clocks[1:])),
            'provider-local clock drift')
