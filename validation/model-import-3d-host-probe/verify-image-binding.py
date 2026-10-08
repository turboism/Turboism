"""Independent N5a image registration/binding oracle. Frozen literals only; never imports Java constants."""
import json


def require(condition, message):
    if not condition:
        raise ValueError('N5a: ' + message)


CHECKS = {'registerBindExecuted', 'handleIdentityFields', 'registrationProjection',
          'bindingHandleArtMesh', 'shareSecondMeshBound', 'rebindNoChange',
          'differentImageRejected', 'lockedTargetRejected', 'deformerParentRejected',
          'oversizePngRejected', 'undersizePngRejected', 'rejectionsZeroMutation',
          'undoRestoresProjection', 'redoRestoresProjection', 'handleFieldsStableAcrossRedo',
          'meshSnapshotUnchanged', 'decodeCountersUnavailable', 'historyPositionRestored',
          'finalStateClean', 'boundInputRegistered', 'boundInputUndoUnbound',
          'boundInputRedoSameImage', 'boundInputRebindUnchanged', 'boundInputShareSameImage',
          'boundInputSecondImage', 'boundInputRejectionsUnchanged'}
SAMPLE_KINDS = ['registration', 'undo', 'redo', 'rebind', 'share',
                'rejection', 'rejection', 'rejection', 'rejection', 'rejection', 'decodeCounters']
REJECTIONS = [('differentImage', 'java.lang.IllegalStateException'),
              ('locked', 'java.lang.IllegalStateException'),
              ('deformerParent', 'java.lang.IllegalStateException'),
              ('oversizePng', 'java.lang.IllegalArgumentException'),
              ('undersizePng', 'java.lang.IllegalArgumentException')]
SETUP_IDS = ['createMI3D_N5A_1', 'createMI3D_N5A_2', 'createMI3D_N5A_3', 'createMI3D_N5A_4',
             'createMI3D_N5A_5', 'registerSecondImage', 'lockMesh4', 'createWarp', 'reparentMesh5']
EMPTY_PROJECTION = {'rawImages': [], 'modelImageGroups': [], 'textureAtlases': []}
SHARED = ('availability', 'documentBindingId', 'managerBindingId', 'generation', 'position')
SECTION_MAX = 4 * 1024 * 1024


def compact(value):
    return len(json.dumps(value, separators=(',', ':'), ensure_ascii=False).encode('utf-8'))


def verify_image_binding(r, verify_snapshot, common):
    e = r['textureBinding']
    require(isinstance(e, dict), 'textureBinding section missing')
    require(compact(e) <= SECTION_MAX, 'textureBinding section exceeds the 4 MiB transport budget')
    require(e.get('schemaVersion') == 'mi3d-native-n5a-v1', 'textureBinding schema')
    require(e.get('status') == 'PASS' and e.get('failure') is None, 'textureBinding not PASS')
    require(e.get('finallyRestored') is True, 'textureBinding restoration flag')
    checks = e['checks']
    require(len(checks) == len(CHECKS) and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST'
                for c in checks), 'check failed')
    initial = e['initialHistory']; restored = e['restoredHistory']
    for k in SHARED:
        require(initial[k] == r['historyRestored'][k], 'native history root identity: ' + k)
    for h in (initial, restored):
        common['history'](h, initial)
    require(restored['position'] == initial['position'], 'history position not restored')
    require(e['initialCounts'] == e['restoredCounts'] == r['morphOrder']['restoredCounts'],
            'counts not restored')
    require(e['initialCounts']['meshes'] == e['initialCounts']['deformers'] == 0, 'not blank')
    require(e['fixtureCounts'] == {**e['initialCounts'], 'meshes': 5}, 'fixture counts')
    require(e['initialCanvas'] == e['restoredCanvas'] == r['morphOrder']['restoredCanvas'],
            'canvas not restored')
    setup = e['setupMutations']
    require(isinstance(setup, list) and [s['id'] for s in setup] == SETUP_IDS,
            'setup mutation inventory/order')
    for s in setup:
        require(set(s) == {'id', 'historyBefore', 'historyAfter'}, 'setup mutation fields')
        common['history'](s['historyBefore'], initial); common['history'](s['historyAfter'], initial)
        require(s['historyAfter']['position'] == s['historyBefore']['position'] + 1,
                'setup mutation is not one history entry: ' + s['id'])

    def projection(p):
        require(set(p) == {'rawImages', 'modelImageGroups', 'textureAtlases'}, 'projection fields')
        for t in p['rawImages']:
            require(set(t) == {'id', 'name', 'width', 'height'} and common['text'](t['id'])
                    and common['text'](t['name'])
                    and type(t['width']) is int and type(t['height']) is int, 'raw image row')
        for g in p['modelImageGroups']:
            require(set(g) == {'groupName', 'memo', 'modelImages'} and common['text'](g['groupName'])
                    and isinstance(g['memo'], str), 'group row')
            for i in g['modelImages']:
                require(set(i) == {'id', 'name', 'width', 'height'} and common['text'](i['id'])
                        and common['text'](i['name'])
                        and type(i['width']) is int and type(i['height']) is int, 'model image row')
        for a in p['textureAtlases']:
            require(set(a) == {'id', 'name', 'width', 'height', 'atlasVersion', 'modelImageCount'}
                    and common['text'](a['id']) and common['text'](a['name'])
                    and all(type(a[k]) is int for k in ('width', 'height', 'atlasVersion', 'modelImageCount')),
                    'atlas row')

    def handle(h):
        require(set(h) == {'scope', 'modelId', 'modelGeneration', 'rawImageId', 'modelImageId',
                           'artMeshId'}, 'handle fields')
        require(h['scope'] == 'runtime.editor-model', 'handle authoring scope')
        require(h['modelId'] == r['document']['editorModelId'], 'handle model identity')
        require(type(h['modelGeneration']) is int and h['modelGeneration'] > 0, 'handle generation')
        require(all(common['text'](h[k]) for k in ('rawImageId', 'modelImageId', 'artMeshId')),
                'handle identities')

    def histories(row):
        common['history'](row['historyBefore'], initial); common['history'](row['historyAfter'], initial)

    def bound_input(row, key):
        # The typed native texture-input observation is the actual binding proof:
        # a missing key, an unbound-as-bound claim, a wrong kind, or a value copied
        # from the registration receipt all fail here. artMeshId is the OBSERVED
        # target; requestedArtMeshId is the call context — they must agree.
        require(key in row, 'missing boundInput observation: ' + key)
        obs = row[key]
        require(isinstance(obs, dict) and common['text'](obs.get('artMeshId')),
                'boundInput target identity: ' + key)
        require(obs.get('requestedArtMeshId') == obs['artMeshId'],
                'boundInput observed target differs from the requested target: ' + key)
        bound = obs.get('bound')
        if bound is False:
            require(set(obs) == {'artMeshId', 'requestedArtMeshId', 'bound'},
                    'unbound boundInput carries fabricated fields: ' + key)
            return None
        require(bound == 'ERROR' or bound is True, 'boundInput bound flag: ' + key)
        if bound == 'ERROR':
            require(set(obs) == {'artMeshId', 'requestedArtMeshId', 'bound', 'exception'},
                    'error boundInput carries fabricated fields: ' + key)
            require(common['text'](obs.get('exception')), 'error boundInput needs its exception')
            return 'ERROR'
        require(set(obs) == {'artMeshId', 'requestedArtMeshId', 'bound', 'kind', 'modelId',
                             'modelGeneration', 'modelImageId', 'textureAtlasId'},
                'boundInput fields: ' + key)
        require(obs['kind'] in ('MODEL_IMAGE', 'TEXTURE_ATLAS_REGION'), 'boundInput kind')
        require(obs['modelId'] == r['document']['editorModelId'], 'boundInput model identity')
        require(type(obs['modelGeneration']) is int and obs['modelGeneration'] > 0,
                'boundInput generation')
        if obs['kind'] == 'MODEL_IMAGE':
            require(common['text'](obs['modelImageId']) and obs['textureAtlasId'] is None,
                    'model-image input identity')
        else:
            require(common['text'](obs['textureAtlasId']) and obs['modelImageId'] is None,
                    'atlas input identity')
        return obs

    def required_input(row, key):
        # A required observation is never the ERROR string shape: a read failure on a
        # leg that must observe the input means the probe did not prove its claim.
        obs = bound_input(row, key)
        require(obs != 'ERROR', key + ' required observation failed its read')
        return obs

    def bound_model_image(row, key, mesh_id, image_id, model_generation):
        obs = required_input(row, key)
        require(isinstance(obs, dict), key + ' must observe a bound input')
        require(obs['kind'] == 'MODEL_IMAGE' and obs['artMeshId'] == mesh_id
                and obs['modelImageId'] == image_id
                and obs['modelGeneration'] == model_generation,
                'bound input identity mismatch: ' + key)
        return obs

    def unbound_input(row, key, mesh_id):
        obs = required_input(row, key)
        require(obs is None and row[key]['artMeshId'] == mesh_id,
                key + ' must observe a genuine unbound input on the target')

    def texture_index(sample, *keys):
        # Host finding (run queue-7003eea1): the borrowed publish path resolves the
        # Editor document CModel, which is not a public CubismModel receiver, so the
        # Core textureIndex is never observable through this probe's model access.
        # Any int leg is fabricated data, not an observation: every leg must report
        # 'UNAVAILABLE' with a non-empty reason.
        values = [sample[k] for k in keys]
        require(all(v == 'UNAVAILABLE' for v in values),
                'textureIndex is not observable on the editor path; legs must report UNAVAILABLE')
        require(common['text'](sample.get('reason')), 'UNAVAILABLE textureIndex needs a reason')

    samples = e['samples']
    require(isinstance(samples, list) and len(samples) == len(SAMPLE_KINDS)
            and [s['kind'] for s in samples] == SAMPLE_KINDS, 'sample inventory/order')
    registration, undo, redo, rebind, share = samples[:5]
    counters = samples[10]
    rejections = samples[5:10]

    # The producer's own role->native-id inventory, recorded right after the five
    # creates. Host object ids are NOT the logical display names (real r9 handles
    # were ArtMeshMI3DN5A1/2); every expected target id must resolve through this
    # map, never by assuming a name prefix or by trusting the boundInput row under
    # review. The list must be complete, unique on both fields, and well-formed.
    meshes = e.get('fixtureMeshes')
    require(isinstance(meshes, list) and len(meshes) == len(SETUP_IDS) - 4,
            'fixtureMeshes inventory')
    require(all(isinstance(m, dict) and set(m) == {'name', 'id'}
                and common['text'](m['name']) and common['text'](m['id'])
                for m in meshes), 'fixtureMeshes rows')
    names = [m['name'] for m in meshes]
    ids = [m['id'] for m in meshes]
    require(set(names) == {'MI3D_N5A_1', 'MI3D_N5A_2', 'MI3D_N5A_3', 'MI3D_N5A_4',
                           'MI3D_N5A_5'}, 'fixtureMeshes role inventory')
    require(len(set(ids)) == len(ids), 'fixtureMeshes ids must be unique')
    MESH_ID = {m['name']: m['id'] for m in meshes}

    # (1) Registration: exactly one raw + one group entry carrying the handle identities.
    handle(registration['handle']); identities = registration['handle']
    require(identities['artMeshId'] == MESH_ID['MI3D_N5A_1'],
            'registration target must be the first fixture mesh')
    projection(registration['before']); projection(registration['after'])
    require(registration['before'] == EMPTY_PROJECTION, 'pre-registration projection not blank')
    after = registration['after']
    require(len(after['rawImages']) == 1 and after['rawImages'][0]['id'] == identities['rawImageId']
            and after['rawImages'][0]['width'] == 256 and after['rawImages'][0]['height'] == 128,
            'raw image projection')
    require(len(after['modelImageGroups']) == 1, 'model image group projection')
    group = after['modelImageGroups'][0]
    require(len(group['modelImages']) == 1
            and group['modelImages'][0]['id'] == identities['modelImageId']
            and group['modelImages'][0]['width'] == 256 and group['modelImages'][0]['height'] == 128,
            'model image entry projection')
    require(after['textureAtlases'] == [], 'atlas projection must stay untouched')
    histories(registration)
    require(registration['historyAfter']['position'] == registration['historyBefore']['position'] + 1,
            'registration is not one history entry')
    for key in ('meshSnapshotBefore', 'meshSnapshotAfter'):
        verify_snapshot(registration[key])
        require(registration[key]['artMeshId'] == identities['artMeshId'],
                'registration snapshot must belong to the bound target')
    require(registration['meshSnapshotBefore'] == registration['meshSnapshotAfter'],
            'binding must not touch stored geometry')
    texture_index(registration, 'textureIndexBefore', 'textureIndexAfter')
    unbound_input(registration, 'boundInputBefore', identities['artMeshId'])
    bound_model_image(registration, 'boundInputAfter', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])

    # (5) Undo/Redo legs sampled immediately while the transaction is the stack top.
    common['history'](undo['history'], initial)
    texture_index(undo, 'textureIndex')
    unbound_input(undo, 'boundInput', identities['artMeshId'])
    require(undo['meshSnapshot']['artMeshId'] == identities['artMeshId'],
            'undo snapshot must belong to the registration target')
    require(undo['projection'] == registration['before']
            and undo['meshSnapshot'] == registration['meshSnapshotBefore']
            and undo['textureIndex'] == registration['textureIndexBefore']
            and undo['history']['position'] == registration['historyBefore']['position'],
            'undo did not restore the pre-registration projection')
    common['history'](redo['history'], initial)
    texture_index(redo, 'textureIndex')
    bound_model_image(redo, 'boundInput', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    require(redo['meshSnapshot']['artMeshId'] == identities['artMeshId'],
            'redo snapshot must belong to the registration target')
    require(redo['projection'] == registration['after']
            and redo['meshSnapshot'] == registration['meshSnapshotBefore']
            and redo['textureIndex'] == registration['textureIndexAfter']
            and redo['history']['position'] == registration['historyAfter']['position'],
            'redo did not restore the registration projection')

    # (2) Same-image rebind to mesh1 is a no-change; the shared bind to mesh2 keeps identity.
    handle(rebind['handle'])
    require(rebind['handle']['artMeshId'] == identities['artMeshId']
            and rebind['handle']['modelImageId'] == identities['modelImageId']
            and rebind['handle']['rawImageId'] == identities['rawImageId'], 'rebind handle identity')
    projection(rebind['before']); projection(rebind['after']); histories(rebind)
    require(rebind['before'] == rebind['after'] and rebind['historyBefore'] == rebind['historyAfter'],
            'no-change rebind mutated the SDK projection/history')
    bound_model_image(rebind, 'boundInputBefore', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    bound_model_image(rebind, 'boundInputAfter', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    require(rebind['boundInputBefore'] == rebind['boundInputAfter'],
            'no-change rebind changed the bound input')
    handle(share['handle'])
    require(share['handle']['artMeshId'] == MESH_ID['MI3D_N5A_2']
            and share['handle']['artMeshId'] != identities['artMeshId']
            and share['handle']['modelImageId'] == identities['modelImageId']
            and share['handle']['rawImageId'] == identities['rawImageId'], 'shared bind handle')
    verify_snapshot(share['meshSnapshotBefore']); verify_snapshot(share['meshSnapshotAfter'])
    for key in ('meshSnapshotBefore', 'meshSnapshotAfter'):
        require(share[key]['artMeshId'] == share['handle']['artMeshId'],
                'shared bind snapshot must belong to the shared target')
    require(share['meshSnapshotBefore'] == share['meshSnapshotAfter'],
            'shared bind must not touch stored geometry')
    texture_index(share, 'textureIndexBefore', 'textureIndexAfter')
    unbound_input(share, 'boundInputBefore', share['handle']['artMeshId'])
    bound_model_image(share, 'boundInputAfter', share['handle']['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    histories(share)
    require(share['historyAfter']['position'] == share['historyBefore']['position'] + 1,
            'shared bind is not one history entry')

    # (4) Rejection matrix: exact exception classes, call-before baseline equality,
    # and the target's bound input must be untouched by every rejected call.
    for row, (name, exception) in zip(rejections, REJECTIONS):
        require(row['id'] == name and row['exception'] == exception,
                'rejection ' + name + ' did not raise ' + exception)
        projection(row['before']); projection(row['after']); histories(row)
        require(row['before'] == row['after'] and row['historyBefore'] == row['historyAfter'],
                'rejection ' + name + ' mutated the SDK projection/history')
    # Mesh-targeting rejections carry REQUIRED before/after boundInput observations on
    # their exact frozen targets: a failed read (ERROR) or a wrong target is never a
    # zero-change observation. differentImage targets the already-bound first mesh;
    # locked/deformerParent target the fourth/fifth fixture meshes, still unbound.
    bound_model_image(rejections[0], 'boundInputBefore', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    bound_model_image(rejections[0], 'boundInputAfter', identities['artMeshId'],
                      identities['modelImageId'], identities['modelGeneration'])
    for row, role in ((rejections[1], 'MI3D_N5A_4'), (rejections[2], 'MI3D_N5A_5')):
        unbound_input(row, 'boundInputBefore', MESH_ID[role])
        unbound_input(row, 'boundInputAfter', MESH_ID[role])
    for row in (rejections[3], rejections[4]):
        require('boundInputBefore' not in row and 'boundInputAfter' not in row,
                'content rejection ' + row['id'] + ' invented a bound input')
    # The second independent image is a real manager member bound to a different mesh
    # with a different GUID, observed under the same model/generation context.
    other = bound_model_image(rejections[0], 'otherMeshBoundInput', MESH_ID['MI3D_N5A_3'],
                              rejections[0]['after']['modelImageGroups'][1]['modelImages'][0]['id'],
                              identities['modelGeneration'])
    registered = {i['id'] for g in rejections[0]['after']['modelImageGroups']
                  for i in g['modelImages']}
    require(other['modelImageId'] in registered
            and other['modelImageId'] != identities['modelImageId']
            and other['modelId'] == r['document']['editorModelId']
            and other['artMeshId'] != identities['artMeshId']
            and other['artMeshId'] != share['handle']['artMeshId'],
            'second independent image observation')

    # (6) Decode counters: honest UNAVAILABLE, never a fabricated PASS payload.
    require(set(counters) == {'kind', 'resolution', 'reason', 'failure'}
            and counters['resolution'] == 'UNAVAILABLE' and common['text'](counters['reason'])
            and counters['failure'] is None, 'decode counters must stay UNAVAILABLE with a reason')
