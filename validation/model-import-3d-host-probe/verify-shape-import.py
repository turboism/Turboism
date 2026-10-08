"""Independent B0 shape-import oracle. Frozen literals only; never imports Java constants."""
import json


def require(condition, message):
    if not condition:
        raise ValueError('B0: ' + message)


CHECKS = {'rotationParameterCreated', 'geometryCommitted', 'geometryReadback',
          'keyformsCommitted', 'keyformReadback', 'defaultFormIsZeroPose',
          'keyformUndo', 'keyformRedo', 'undoStable', 'historyPositionRestored'}

# Frozen B0 source surfaces and binding contract, independently stated.
TRI3D = ((0.9, 0.6, 0.4), (-0.9, 0.6, -0.4), (0.0, -0.9, 0.2))
QUAD3D = ((0.8, 0.8, 0.3), (-0.8, 0.8, 0.1), (-0.8, -0.8, -0.3), (0.8, -0.8, -0.1))
TRI_IDX = [0, 1, 2]
QUAD_IDX = [0, 1, 2, 0, 2, 3]
MESH_NAMES = ['MI3D_B0_TRI', 'MI3D_B0_QUAD']
ROT_PARAMETER = 'ParamMI3DB0RotX'
BOUND_KEYS = [-30.0, 0.0, 30.0]


def zero_pose(vertices):
    """Independent truth: theta=0 yaw gives x'=x, y'=y on the fixed canvas."""
    return [[512.0 + 200.0 * v[0], 512.0 - 200.0 * v[1]] for v in vertices]


def uv_map(vertices, shift):
    return [[shift + 0.1 * i, 0.5 - 0.4 * (i % 2)] for i in range(len(vertices))]


def points_close(actual, expected, limit):
    return (isinstance(actual, list) and len(actual) == len(expected)
            and all(isinstance(p, list) and len(p) == 2
                    and (p[0] - e[0]) ** 2 + (p[1] - e[1]) ** 2 <= limit * limit
                    for p, e in zip(actual, expected)))


def compact(value):
    return len(json.dumps(value, separators=(',', ':'), ensure_ascii=False))


def verify_shape_import(r, common):
    e = r.get('shapeImport')
    require(isinstance(e, dict), 'shapeImport section missing')
    require(compact(e) <= 100_000, 'shapeImport section exceeds the transport budget')
    require(e.get('schemaVersion') == 'mi3d-native-b0-v1', 'shapeImport schema')
    require(e.get('status') == 'PASS' and e.get('failure') is None, 'shapeImport not PASS')
    require(e.get('finallyRestored') is True, 'shapeImport restoration flag')
    require(e.get('coordinateSpace') == 'EDITOR_STORED_FORM', 'shapeImport coordinate space')
    checks = e.get('checks')
    require(isinstance(checks, list) and len(checks) == len(CHECKS)
            and {c['id'] for c in checks} == CHECKS, 'check inventory')
    require(all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST'
                and common['text'](c['expected']) for c in checks), 'all B0 checks must be EXACT_HOST PASS')
    for tx in ('geometryTransaction', 'keyformTransaction'):
        t = e.get(tx)
        require(isinstance(t, dict) and t.get('outcome') == 'COMMITTED'
                and (t.get('diagnosticId') is None or common['text'](t['diagnosticId'])),
                tx + ' must be COMMITTED')
    meshes = e.get('meshes')
    require(isinstance(meshes, list) and len(meshes) == 2,
            'mesh identity observations missing')
    mesh_ids = {}
    for m in meshes:
        require(isinstance(m, dict) and isinstance(m.get('name'), str)
                and isinstance(m.get('id'), str) and m['id'].strip(),
                'each mesh observation must carry name and minted id')
        mesh_ids[m['name']] = m['id']
    require(set(mesh_ids) == set(MESH_NAMES), 'both surfaces must report their identities')
    bindings = e.get('bindings')
    require(isinstance(bindings, list) and len(bindings) == 2,
            'object-level binding observations missing')
    require([[b.get('mesh'), b.get('parameter'), b.get('keys')] for b in bindings]
            == [[mesh_ids[n], ROT_PARAMETER, BOUND_KEYS] for n in MESH_NAMES],
            'each mesh must be bound to the rotation parameter with the frozen keys')
    protected = e.get('protectedDomain')
    require(isinstance(protected, dict), 'protected domain observations missing')
    for mesh, (vertices, indices, shift) in {'tri': (TRI3D, TRI_IDX, 0.2),
                                             'quad': (QUAD3D, QUAD_IDX, 0.6)}.items():
        d = protected.get(mesh)
        require(isinstance(d, dict), 'protectedDomain.' + mesh + ' missing')
        require(d.get('indices') == indices, mesh + ' triangle index summary changed')
        require(points_close(d.get('uvs'), uv_map(vertices, shift), 1e-4),
                mesh + ' source UVs changed')
        require(points_close(d.get('sourcePositions'), zero_pose(vertices), 0.1),
                mesh + ' source positions exceed the 0.1px budget')
        require(points_close(d.get('defaultFormPositions'), zero_pose(vertices), 0.1),
                mesh + ' default form is not the untouched 0-degree pose')
        require(d.get('formCount') == 3, mesh + ' stored forms differ from the bound key tuples')
    initial = e.get('initialHistory')
    restored = e.get('restoredHistory')
    require(isinstance(initial, dict) and isinstance(restored, dict), 'history observations missing')
    require(common['text'](restored.get('documentBindingId'))
            and common['text'](restored.get('managerBindingId')), 'restored history identity')
    require(restored.get('documentBindingId') == initial.get('documentBindingId')
            and restored.get('managerBindingId') == initial.get('managerBindingId')
            and restored.get('position') == initial.get('position'),
            'restored history must equal the import root')
    # No texture/figure writes: the geometry transaction must not leak texture state.
    require('textureIndex' not in json.dumps(e), 'B0 must not observe texture fields')
