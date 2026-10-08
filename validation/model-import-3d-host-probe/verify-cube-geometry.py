"""Independent exact-input cube geometry oracle; lifecycle remains a separate host gate."""
import itertools
import json
import math
import struct
import hashlib
from pathlib import Path

INPUT_SHA256 = '985496727e505bcc1b5c748415373090c11b1058bb20ac57a4434914e3717630'
PARAMETERS = ['ParamMI3DCubeYaw', 'ParamMI3DCubePitch']
# Complete six-face stored snapshots plus a predeclared intermediate-pose inventory.
# This is a new protocol budget, not an admission change to existing joint reports.
MAX_SECTION_BYTES = 96 * 1024 * 1024


def require(value, message):
    if not value:
        raise ValueError('CUBE: ' + message)


def f32(value):
    require(type(value) in (float, int) and math.isfinite(value) and abs(value) <= 3.4028234663852886e38,
            'finite float value required')
    return struct.unpack('!f', struct.pack('!f', value))[0]


KEYS = [f32(30 * (2 * i / 44 - 1)) if i != 22 else 0.0 for i in range(45)]


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def truth(vertices, yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    # Independent closed matrix R_y * R_x, not sequential calls to the Java sampler.
    return [[512 + 200 * (math.cos(y)*x + math.sin(y)*math.sin(p)*v + math.sin(y)*math.cos(p)*z),
             512 - 200 * (math.cos(p)*v - math.sin(p)*z)] for x, v, z in vertices]


def error(actual, expected):
    require(isinstance(actual, list) and len(actual) == len(expected)
            and all(isinstance(p, list) and len(p) == 2 and all(number(v) for v in p) for p in actual),
            'finite vertex positions')
    return max(math.dist(a, e) for a, e in zip(actual, expected))


def cube_source(path):
    require(Path(path).stat().st_size == 1936, 'authorized input size')
    raw = Path(path).read_bytes()
    require(len(raw) == 1936 and hashlib.sha256(raw).hexdigest() == INPUT_SHA256, 'authorized input bytes')
    length = struct.unpack_from('<I', raw, 12)[0]
    document = json.loads(raw[20:20+length])
    binary = raw[28+length:]
    def accessor(index, fmt, width):
        a = document['accessors'][index]; view = document['bufferViews'][a['bufferView']]
        offset = view.get('byteOffset', 0) + a.get('byteOffset', 0)
        stride = view.get('byteStride', struct.calcsize(fmt)*width)
        return [list(struct.unpack_from('<'+fmt*width, binary, offset+i*stride)) for i in range(a['count'])]
    vertices, uvs = accessor(0, 'f', 3), accessor(2, 'f', 2)
    indices = [r[0] for r in accessor(3, 'H', 1)]
    # Independent grouping by each triangle's exact outward normal, not the Java
    # connected-component algorithm. This oracle accepts only the frozen cube.
    groups = {}
    for triangle in range(12):
        a, b, c = (vertices[i] for i in indices[triangle*3:triangle*3+3])
        u, v = [b[i]-a[i] for i in range(3)], [c[i]-a[i] for i in range(3)]
        normal = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
        groups.setdefault(normal, []).append(triangle)
    require(len(groups) == 6 and all(len(t) == 2 for t in groups.values()), 'six cube faces')
    patches, sources = [], []
    for triangles in groups.values():
        source_slots = list(dict.fromkeys(indices[3*t+k] for t in triangles for k in range(3)))
        local_indices = [source_slots.index(indices[3*t+k]) for t in triangles for k in range(3)]
        positions = [vertices[i] for i in source_slots]; projected = truth(positions, 0, 0)
        collapsed = []
        for t in range(2):
            a,b,c = (projected[i] for i in local_indices[3*t:3*t+3])
            if (b[0]-a[0])*(c[1]-a[1]) == (b[1]-a[1])*(c[0]-a[0]): collapsed.append(t)
        patches.append(dict(surfaceIndex=0,node=0,mesh=0,primitive=0,sourceVertices=source_slots,
            sourceTriangles=triangles,indices=local_indices,uvs=[uvs[i] for i in source_slots],
            neutralCollapsedTriangles=collapsed))
        sources.append(positions)
    return sources, patches


def poses():
    critical = [-30., -15., -1., -.25, 0., .25, 1., 15., 30.]
    result = list(itertools.product(critical, repeat=2))
    result.extend((k,0.) for k in KEYS); result.extend((0.,k) for k in KEYS)
    for i in range(1,len(KEYS)):
        midpoint = f32(f32(KEYS[i-1]+KEYS[i])/2)
        result.extend(((midpoint,midpoint),(midpoint,-midpoint)))
    state = (4012027 ^ 0x5DEECE66D) & ((1 << 48)-1)
    def bits(n):
        nonlocal state
        state = (state * 0x5DEECE66D + 11) & ((1 << 48)-1)
        return state >> (48-n)
    def coordinate():
        return f32(-30 + 60 * (((bits(26) << 27) + bits(27)) / (1 << 53)))
    result.extend((coordinate(),coordinate()) for _ in range(128))
    return list(dict.fromkeys(result))


def history(h, root):
    require(isinstance(h, dict) and set(h) == {'availability', 'generation', 'revision', 'position',
                                             'documentBindingId', 'managerBindingId'}, 'history fields')
    require(h['availability'] == 'AVAILABLE' and all(type(h[k]) is int and h[k] >= 0
            for k in ('generation', 'revision', 'position')), 'history unavailable/counters')
    for k in ('documentBindingId', 'managerBindingId'):
        require(isinstance(h[k], str) and h[k].strip(), 'history identity missing')
    require(all(h[k] == root[k] for k in ('generation', 'documentBindingId', 'managerBindingId')),
            'history binding changed')
    require(h['revision'] >= root['revision'], 'history revision regressed')


def stored(snapshot, vertices, baked, patch, *, texture_source=False, texture_size=(256,128), keys=None, parameter_ids=None, projection=None):
    keys=KEYS if keys is None else keys;parameter_ids=PARAMETERS if parameter_ids is None else parameter_ids
    project=truth if projection is None else projection;count=len(keys)**2
    require(isinstance(snapshot, dict) and set(snapshot) == {'artMeshId', 'sourceGuid', 'positions', 'uvs',
            'indices', 'axes', 'cells', 'forms', 'morphKeys'}, 'snapshot fields')
    require(all(isinstance(snapshot[k], str) and snapshot[k].strip() for k in ('artMeshId', 'sourceGuid')),
            'mesh identity')
    # Native Point2 values are float32; Java JSON emits shortest round-trip decimals.
    # Compare their exact float32 values, never introduce a numerical tolerance.
    require([[f32(u),f32(v)] for u,v in snapshot['uvs']] == patch['uvs'] and snapshot['indices'] == patch['indices']
            and snapshot['morphKeys'] == [], 'source topology/UV/morph changed')
    expected_source = ([[f32(u*texture_size[0]),f32(v*texture_size[1])] for u,v in patch['uvs']]
                       if texture_source else project(vertices, 0, 0))
    require(error(snapshot['positions'], expected_source) <= .0001,
            'image-space texture source geometry' if texture_source else 'neutral source geometry')
    axes = snapshot['axes']
    require(isinstance(axes, list) and len(axes) == 2, 'axis count')
    by_id = {a['id']: a['guid'] for a in axes}
    require(set(by_id) == set(parameter_ids) and len(set(by_id.values())) == 2, 'axis identity')
    for a in axes:
        require(isinstance(a['guid'], str) and a['guid'].strip() and a['minimum'] == -30
                and a['maximum'] == 30 and a['default'] == 0 and sorted(map(f32, a['keys'])) == keys, 'axis domain/keys')
    cells, forms = snapshot['cells'], snapshot['forms']
    require(len(cells) == len(forms) == count, 'dense grid count')
    by_guid = {f['guid']: f for f in forms}
    require(len(by_guid) == count and all(isinstance(g, str) and g.strip() for g in by_guid), 'form identities')
    seen, used = set(), set()
    for cell in cells:
        c = cell['coordinates']; values = {v['guid']: v['value'] for v in c}
        require(len(c) == len(values) == 2 and set(values) == set(by_id.values()), 'complete tuple')
        pose = tuple(f32(values[by_id[p]]) for p in parameter_ids)
        require(pose not in seen and all(v in keys for v in pose), 'tuple missing/duplicated/undeclared')
        seen.add(pose)
        guid = cell['formGuid']
        require(guid in by_guid and guid not in used, 'form aliases/dangling reference')
        used.add(guid)
        form = by_guid[guid]
        require(type(form['drawOrder']) is int and 0 <= form['drawOrder'] <= 1000, 'draw order')
        require(error(form['positions'], project(vertices, *(pose if baked else (0, 0)))) <= .0001,
                'stored joint projection differs from source truth')
    require(seen == set(itertools.product(keys, repeat=2)), 'joint coverage')
    return by_id


def values(state):
    require(isinstance(state, dict) and set(state) == {'updateVersion', 'parameters'}
            and type(state['updateVersion']) is int and -(2**31) <= state['updateVersion'] < 2**31, 'parameter counter')
    result = {}
    for p in state['parameters']:
        require(set(p) == {'guid', 'id', 'value'} and isinstance(p['guid'], str) and p['guid']
                and isinstance(p['id'], str) and p['id'] and number(p['value']), 'parameter value')
        require(p['guid'] not in result, 'duplicate parameter identity')
        result[p['guid']] = (p['id'], f32(p['value']))
    return result


def verify_cube_geometry(report, input_path):
    SOURCES, patches = cube_source(input_path)
    e = report.get('cubeGeometry')
    require(isinstance(e, dict) and e.get('schemaVersion') == 'mi3d-cube-geometry-v1'
            and e.get('status') == 'PASS' and e.get('failure') is None and e.get('finallyRestored') is True,
            'cube geometry evidence not PASS')
    require(len(json.dumps(e, separators=(',', ':')).encode('utf-8')) <= MAX_SECTION_BYTES, 'section budget')
    require(e['inputSha256'] == INPUT_SHA256 and e['patches'] == patches, 'source identity/patch mapping')
    require(e['convexProof'] == dict(sourceSlots=24, vertices=8, triangles=12, edges=18, sixVolume='48'), 'convex proof')
    require(e['sources'] == SOURCES and e['rotationOrder'] == 'Ry(yaw)*Rx(pitch)'
            and e['camera'] == [512, 512, 200, 0, 0, 0] and e['maxNativeErrorPixels'] == .1, 'frozen projection contract')
    require(list(map(f32, e['yawKeys'])) == list(map(f32, e['pitchKeys'])) == KEYS, 'frozen dense keys')
    bound = 200 * math.sqrt(3) * 2 * math.radians(60/44)**2 / 8
    require(number(e['idealErrorBoundPixels']) and abs(e['idealErrorBoundPixels']-bound) < 1e-12, 'analytic bound')
    root = e['initialHistory']; history(root, root)
    for name in ('writeBefore', 'writeAfter', 'restoredHistory'):
        history(e[name], root)
    require(e['writeOutcome'] == 'COMMITTED' and e['writeAfter']['position'] == e['writeBefore']['position'] + 1,
            'single bake history entry')
    require(e['restoredHistory']['position'] == root['position'] and e['initialCounts'] == e['restoredCounts'], 'restoration')
    require(set(e['initialCounts']) == {'meshes', 'parameters', 'deformers', 'parts'}
            and all(type(v) is int and v >= 0 for v in e['initialCounts'].values()), 'initial counts')
    require(e['initialCounts']['meshes'] == e['initialCounts']['deformers'] == 0, 'blank undeformed model required')
    require(e['initialCanvas'] == e['restoredCanvas']
            and set(e['initialCanvas']) == {'width', 'height', 'originX', 'originY', 'pixelsPerUnit'}
            and all(number(v) for v in e['initialCanvas'].values()), 'canvas restoration')
    for phase in ('before', 'written', 'undone', 'redone', 'afterEvaluation'):
        require(isinstance(e[phase], list) and len(e[phase]) == 6, 'six source faces required')
    require(e['before'] == e['undone'] and e['written'] == e['redone'] == e['afterEvaluation'], 'Undo/Redo/evaluation isolation')
    require(e['meshIds'] == [s['artMeshId'] for s in e['written']] and len(set(e['meshIds'])) == 6, 'mesh inventory')
    transform = e.get('renderUvTransform', [1,0,1,0])
    require(transform == [1,0,1,0] or (report.get('mi3dMode') == 'cube-render' and transform == [.5,.25,.5,.25]),
            'undeclared/unsupported render UV mapping')
    render_patches = [{**patch, 'uvs': [[f32(transform[0]*u+transform[1]),f32(transform[2]*v+transform[3])]
                       for u,v in patch['uvs']]} for patch in patches]
    bindings = []
    for i, source in enumerate(SOURCES):
        before, after = e['before'][i], e['written'][i]
        texture_source = transform == [.5,.25,.5,.25]
        stored(before, source, False, render_patches[i], texture_source=texture_source)
        bindings.append(stored(after, source, True, render_patches[i], texture_source=texture_source))
        require({k: v for k, v in before.items() if k != 'forms'} == {k: v for k, v in after.items() if k != 'forms'},
                'protected source/layout changed')
        require([(f['guid'], f['drawOrder']) for f in before['forms']]
                == [(f['guid'], f['drawOrder']) for f in after['forms']], 'form identity/order changed')
    expected_poses = poses(); samples = e['samples']
    require(isinstance(samples, list) and len(samples) == len(expected_poses), 'predeclared critical/axis/diagonal/random coverage')
    prior_revision = e['writeAfter']['revision']
    prior_pose = (0.0, 0.0)
    expected_position = e['writeAfter']['position']
    for index, (sample, pose) in enumerate(zip(samples, expected_poses)):
        require(sample['index'] == index and sample['pose'] == list(pose) and sample['status'] == 'PASS', 'sample identity/pose')
        h = sample['historyBefore']; history(h, root); history(sample['historyAfter'], root)
        # Each changed parameter setter is an authoring operation before the
        # read-only evaluation interval; unchanged setters are no-ops. Do not
        # confuse those admitted writes with mutation by evaluation reads.
        expected_position += sum(a != b for a, b in zip(prior_pose, pose))
        require(h == sample['historyAfter'] and h['position'] == expected_position
                    and h['revision'] >= prior_revision, 'evaluation history changed/regressed')
        prior_pose = pose
        prior_revision = h['revision']
        require(len(sample['meshes']) == 6, 'sample mesh coverage')
        for i, row in enumerate(sample['meshes']):
            requested, actual = row['requested'], row['actual']; s = e['written'][i]
            for o in (requested, actual):
                require(o['artMeshId'] == s['artMeshId'] and o['sourceGuid'] == s['sourceGuid'], 'observation identity')
                require(type(o['passEntryTimeMillis']) is int and o['passEntryTimeMillis'] >= -1, 'pass entry stamp')
            live = values(actual['live']); consumed = values(actual['consumed'])
            values(requested['consumed'])
            require(live == consumed == values(requested['live']) and actual['passEntryTimeMillis'] >= 0
                    and actual['passEntryTimeMillis'] >= requested['passEntryTimeMillis']
                    and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'], 'fresh native consumption')
            for p, value in zip(PARAMETERS, pose):
                require(live.get(bindings[i][p]) == (p, value)
                        and sum(v[0] == p for v in live.values()) == 1, 'requested joint parameter not applied')
            f = actual['calculated']
            require(isinstance(f, dict) and f['interpolated'] is True and f['coordinateType'] in ('Canvas', 'DeformerLocal')
                    and f['drawOrder'] == s['forms'][0]['drawOrder'], 'calculated form metadata')
            maximum = error(f['positions'], truth(SOURCES[i], *pose))
            require(maximum <= .1, 'native interpolation error exceeds 0.1px')
            require(number(row['maxErrorPixels']) and abs(row['maxErrorPixels']-maximum) <= .0001, 'reported error mismatch')
            require(row['waitReason'] == 'CONSUMED' and type(row['attempts']) is int and row['attempts'] > 0
                    and type(row['elapsedMillis']) is int and 0 <= row['elapsedMillis'] < 15000
                    and row['lastObservation'] == actual, 'wait evidence')
    require(e['restoredHistory']['revision'] >= prior_revision, 'restoration history regressed')
