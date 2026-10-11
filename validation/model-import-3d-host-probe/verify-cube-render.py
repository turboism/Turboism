"""Independent frozen-cube visibility oracle; never infers expected colors from PNGs.

Samples a predeclared four-pixel grid at pixel centres, excluding points within
one pixel of any projected triangle edge. Depth is independently evaluated in
3D; no host winding, draw-order, culling flag or calculated vertex is its truth.
This module alone is not a host lifecycle or full-report admission gate.
"""
import functools
import hashlib
import math
from pathlib import Path
import runpy
import struct

HERE = Path(__file__).resolve().parent
GEOMETRY = runpy.run_path(str(HERE / 'verify-cube-geometry.py'))
PNG = runpy.run_path(str(HERE / 'verify-pixel-sampling.py'))['_pixels']
POSES = ((0, 0), (-30, -30), (-30, 30), (30, -30), (30, 30),
         (-1, 0), (1, 0), (0, -1), (0, 1))
PALETTE = ((220, 50, 50, 255), (50, 200, 80, 255), (50, 90, 220, 255),
           (220, 180, 50, 255), (190, 60, 200, 255), (50, 200, 200, 255))
PHASES = ('diagnostic-off', 'diagnostic-on', 'source-on')
PNG_MAX = 8 * 1024 * 1024


def require(ok, message):
    if not ok:
        raise ValueError('CUBE_RENDER: ' + message)


def fingerprint(snapshot):
    """Java DataOutputStream format, from independent report JSON fields."""
    digest = hashlib.sha256()
    def integer(value):
        digest.update(struct.pack('>i', value))
    def scalar(value):
        require(type(value) in (int, float) and math.isfinite(value), 'nonfinite snapshot')
        digest.update(struct.pack('>f', value))
    def text(value):
        raw = value.encode('utf-8'); integer(len(raw)); digest.update(raw)
    def points(values):
        integer(len(values))
        for x, y in values:
            scalar(x); scalar(y)
    text('mi3d-snapshot-fingerprint-v1')
    text(snapshot['artMeshId']); text(snapshot['sourceGuid'])
    points(snapshot['positions']); points(snapshot['uvs'])
    integer(len(snapshot['indices']))
    for value in snapshot['indices']:
        integer(value)
    integer(len(snapshot['axes']))
    for axis in snapshot['axes']:
        text(axis['guid']); text(axis['id'])
        for key in ('minimum', 'default', 'maximum'):
            scalar(axis[key])
        integer(len(axis['keys']))
        for value in axis['keys']:
            scalar(value)
    integer(len(snapshot['cells']))
    for cell in snapshot['cells']:
        text(cell['formGuid']); integer(len(cell['coordinates']))
        for coordinate in cell['coordinates']:
            text(coordinate['guid']); scalar(coordinate['value'])
    integer(len(snapshot['forms']))
    for form in snapshot['forms']:
        text(form['guid']); points(form['positions']); integer(form['drawOrder'])
    integer(len(snapshot['morphKeys']))
    for morph in snapshot['morphKeys']:
        text(morph['parameterGuid']); text(morph['formGuid'])
        value = morph['keyValue']; digest.update(b'\0' if value is None else b'\1')
        if value is not None:
            scalar(value)
    return digest.hexdigest()


def segment_distance(x, y, a, b):
    dx, dy = b[0]-a[0], b[1]-a[1]
    length = dx*dx+dy*dy
    t = max(0, min(1, ((x-a[0])*dx+(y-a[1])*dy)/length)) if length else 0
    return math.hypot(x-a[0]-t*dx, y-a[1]-t*dy)


@functools.lru_cache(maxsize=9)
def samples(cube_bytes, pose):
    # Decode authorized input once per predeclared pose. Byte argument binds cache
    # identity to the actual immutable input, not merely a replaceable pathname.
    import tempfile
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'cube.glb'; path.write_bytes(cube_bytes)
        sources, patches = GEOMETRY['cube_source'](path)
    yaw, pitch = map(math.radians, pose)
    cy, sy, cp, sp = math.cos(yaw), math.sin(yaw), math.cos(pitch), math.sin(pitch)
    triangles = []
    for face, (source, patch) in enumerate(zip(sources, patches)):
        vertices = [(512+200*(cy*x+sy*sp*y+sy*cp*z),
                     512-200*(cp*y-sp*z), -sy*x+cy*sp*y+cy*cp*z) for x,y,z in source]
        for offset in (0, 3):
            a,b,c = [vertices[i] for i in patch['indices'][offset:offset+3]]
            area = (b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
            triangles.append((face,a,b,c,area))
    result = []; coverage = [0]*6
    for iy in range(2, 1024, 4):
        for ix in range(2, 1024, 4):
            x,y = ix+.5,iy+.5
            hits = []; near_edge = False
            for face,a,b,c,area in triangles:
                if not (min(a[0],b[0],c[0])-1 <= x <= max(a[0],b[0],c[0])+1
                        and min(a[1],b[1],c[1])-1 <= y <= max(a[1],b[1],c[1])+1):
                    continue
                if min(segment_distance(x,y,a,b), segment_distance(x,y,b,c), segment_distance(x,y,c,a)) <= 1:
                    near_edge = True
                if abs(area) < 1e-10:
                    continue
                v = ((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
                w = ((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area
                u = 1-v-w
                if min(u,v,w) >= 0:
                    hits.append((u*a[2]+v*b[2]+w*c[2],face))
            if near_edge:
                continue
            face = min(hits)[1] if hits else None
            if face is not None:
                coverage[face] += 1
            result.append((ix,iy,face))
    require(sum(coverage) > 1000, 'insufficient interior coverage')
    # A nonzero axis turn must expose at least one additional narrow face.
    require(sum(n > 0 for n in coverage) == (1 if pose == (0,0) else 3 if all(pose) else 2),
            'visible-face coverage missing, including narrow one-degree faces')
    return result, coverage


def inspect_pixels(rows, cube_bytes, pose, source=False):
    require(len(rows) == 1024 and all(len(row) == 4096 for row in rows), 'RGBA raster dimensions')
    points, coverage = samples(cube_bytes, tuple(pose))
    mismatches = 0; first = None
    for x,y,face in points:
        actual = tuple(rows[y][4*x:4*x+4])
        expected = (0,0,0,0) if face is None else (231,231,231,255) if source else PALETTE[face]
        # RGB is immaterial when fully transparent. All interior channels allow
        # at most two quantization levels, fixed before native capture.
        bad = actual[3] != 0 if face is None else (actual[3] != 255 or any(abs(a-b)>2 for a,b in zip(actual[:3],expected[:3])))
        if bad:
            mismatches += 1
            if first is None:
                first = dict(x=x,y=y,face=face,actual=actual,expected=expected)
    return dict(samples=len(points),coverage=coverage,mismatches=mismatches,firstMismatch=first)


def verify_pixels(section, cube_path, artifact_reader):
    """Validate exact capture inventory/hashes and independently check visibility.

    artifact_reader accepts only the caller's already-admitted artifact namespace.
    State/history/host identity admission must be performed by the report gate.
    """
    cube_bytes = Path(cube_path).read_bytes()
    require(section['poses'] == [list(p) for p in POSES], 'pose inventory')
    require(section['palette'] == [int.from_bytes(bytes((255,*c[:3])), 'big', signed=True) for c in PALETTE], 'palette inventory')
    frames = section['frames']; require(len(frames) == 27, 'frame count')
    results = []; total = 0; counterexamples = 0
    for frame, (phase, index) in zip(frames, ((p,i) for p in PHASES for i in range(9))):
        name = f'cube-render/{phase}-{index}.png'
        require(frame['phase'] == phase and frame['index'] == index and frame['pose'] == list(POSES[index])
                and frame['artifact'] == name, 'frame identity/order')
        require(type(frame['artifactBytes']) is int and 0 < frame['artifactBytes'] <= PNG_MAX, 'PNG size budget')
        raw = artifact_reader(name)
        require(len(raw) == frame['artifactBytes'] and hashlib.sha256(raw).hexdigest() == frame['artifactSha256'], 'PNG bytes/hash')
        total += len(raw); require(total <= 27*PNG_MAX, 'aggregate PNG budget')
        result = inspect_pixels(PNG(raw),cube_bytes,POSES[index],phase == 'source-on')
        result.update(phase=phase,index=index); results.append(result)
        if phase == 'diagnostic-off':
            counterexamples += result['mismatches']
        else:
            require(result['mismatches'] == 0, f'{phase}/{index}: incorrect nearest surface or silhouette: {result["firstMismatch"]}')
    require(counterexamples > 0, 'culling-off counterexample missing')
    return dict(frames=results,cullingOffMismatches=counterexamples,pngBytes=total)


def verify_state(report):
    """Cross-bind captured state to cubeGeometry already admitted by its oracle."""
    section = report['cubeRender']; geometry = report['cubeGeometry']
    require(section.get('schemaVersion') == 'mi3d-cube-render-v1'
            and section.get('status') == 'CAPTURED' and section.get('failure') is None
            and section.get('pixelAcceptance') == 'REQUIRES_INDEPENDENT_ORACLE'
            and section.get('finallyRestored') is True, 'capture did not complete/recover')
    require(section['inputSha256'] == GEOMETRY['INPUT_SHA256']
            and section['fingerprintVersion'] == 'mi3d-snapshot-fingerprint-v1', 'input/fingerprint protocol')
    snapshots = geometry['written']; ids = geometry['meshIds']
    hashes = [fingerprint(s) for s in snapshots]
    require(len(ids) == len(snapshots) == 6 and section['geometryFingerprints'] == hashes, 'authored snapshot fingerprints')
    initial = section['initialState']; root = section['initialHistory']
    require(initial == section['restoredState'], 'render resources/state restoration')
    require(list(map(GEOMETRY['f32'], initial['pose'])) ==
            list(map(GEOMETRY['f32'], geometry['samples'][-1]['pose'])), 'callback initial pose')
    require(initial['model'] == report['document']['editorModelId'], 'task model identity')
    require(initial['canvas'][:2] == [1024,1024] and len(initial['canvas']) == 5
            and all(GEOMETRY['number'](v) for v in initial['canvas']), 'canvas dimensions/values')
    require(initial['rawImages'] == initial['imageGroups'] == [], 'blank initial image resources')

    def history(value):
        require(type(value['revision']) is int and value['revision'] >= root['revision'], 'history revision')
        tuple_value = {k:v for k,v in value.items() if k != 'revision'}
        runpy_history(tuple_value, 'cube-render')
        require(all(value[k] == root[k] for k in ('generation','documentBindingId','managerBindingId')), 'history binding')
        return tuple_value

    runpy_history = runpy.run_path(str(HERE/'verify-pixel-sampling.py'))['_history_tuple']
    history(root); history(section['restoredHistory'])
    require(section['restoredHistory']['position'] == root['position']
            and root['position'] == geometry['samples'][-1]['historyAfter']['position'], 'history restoration/callback position')

    def state(value, pose, culling=None, bindings=None):
        require(set(value) == {'model','meshes','pose','canvas','instances','currentInstance','rawImages','imageGroups'}, 'state fields')
        require(value['pose'] == list(pose), 'stale captured parameter pose')
        require(all(value[k] == initial[k] for k in ('model','canvas','instances','currentInstance')), 'render context changed')
        rows = value['meshes']; require(len(rows) == 6, 'six captured meshes')
        for i,row in enumerate(rows):
            require(set(row) == {'id','snapshotSha256','culling','visible','opacity','positions','drawOrder','input'}, 'mesh fields')
            require(row['id'] == ids[i] and row['snapshotSha256'] == hashes[i], 'mesh identity/fingerprint changed')
            require(row['visible'] is True and type(row['culling']) is bool, 'mesh visibility')
            opacity=row['opacity']
            require(opacity == dict(status='AVAILABLE',value=1) or opacity ==
                    dict(status='UNAVAILABLE',reason='CURRENT_AUTHORED_KEYFORM_ABSENT'), 'opacity observation')
            require(row['drawOrder'] == snapshots[i]['forms'][0]['drawOrder'], 'draw order changed')
            require(GEOMETRY['error'](row['positions'],GEOMETRY['truth'](geometry['sources'][i],*pose)) <= .1, 'calculated vertex error')
            if bindings is None:
                require(row['input'] is None, 'unexpected initial texture binding')
            else:
                b = bindings[i]
                require(row['culling'] is culling and row['input'] == dict(kind='MODEL_IMAGE',modelId=b['modelId'],
                    generation=b['generation'],meshId=ids[i],imageId=b['imageId']), 'texture/culling identity')

    state(initial,initial['pose'])
    require(set(section['phases']) == set(PHASES) and len(section['frames']) == 27, 'phase/frame inventory')
    phase_bindings = {}
    for name,key,colors in (('diagnostic','diagnosticBindings',PALETTE),('source','sourceBindings',[(231,231,231,255)]*6)):
        bindings=section[key]; require(len(bindings) == 6, 'binding count')
        for i,b in enumerate(bindings):
            expected_argb=int.from_bytes(bytes((255,*colors[i][:3])),'big',signed=True)
            require(b['meshId'] == ids[i] and b['modelId'] == initial['model'] and b['surface'] == b['material'] == 0
                    and b['argb'] == expected_argb and type(b['generation']) is int and b['generation'] >= 0
                    and all(isinstance(b[k],str) and b[k] for k in ('imageId','rawId')), 'material binding metadata')
        require(len({b['generation'] for b in bindings}) == 1, 'mixed model generations')
        require(len({b['imageId'] for b in bindings}) == len({b['rawId'] for b in bindings}) == (6 if name == 'diagnostic' else 1),
                'unique diagnostic/shared source material identity')
        phase_bindings[name]=bindings
    require(section['diagnosticBindings'][0]['generation'] == section['sourceBindings'][0]['generation'], 'material model generation changed')
    for phase in PHASES:
        bindings=phase_bindings['source' if phase == 'source-on' else 'diagnostic']
        start=section['phases'][phase]; history(start['history'])
        state(start['state'],start['state']['pose'],phase != 'diagnostic-off',bindings)
        resources=start['state']
        require({r[0] for r in resources['rawImages']} == {b['rawId'] for b in bindings}
                and len(resources['rawImages']) == len({b['rawId'] for b in bindings}), 'raw image resource inventory')
        images=[r for group in resources['imageGroups'] for r in group['images']]
        require({r[0] for r in images} == {b['imageId'] for b in bindings}
                and len(images) == len({b['imageId'] for b in bindings}), 'model image resource inventory')
        require(all(len(r) == 4 and r[2:] == [256,128] for r in [*resources['rawImages'],*images]), 'bounded material dimensions')
        for index,pose in enumerate(POSES):
            row=section['frames'][PHASES.index(phase)*9+index]
            require(row['phase'] == phase and row['index'] == index and row['pose'] == list(pose), 'frame phase/pose')
            require(row['stateBefore'] == row['stateAfter'], 'render mutated protected state')
            state(row['stateBefore'],pose,phase != 'diagnostic-off',bindings)
            require(all(row['stateBefore'][k] == resources[k] for k in ('rawImages','imageGroups')), 'render resource drift')
            require(history(row['historyBefore']) == history(row['historyAfter'])
                    and row['historyAfter']['revision'] >= row['historyBefore']['revision'], 'render mutated history')
            require(len(row['consumption']) == 6, 'evaluation coverage')
            for i,observation in enumerate(row['consumption']):
                requested,actual=observation['requested'],observation['actual']
                require(all(o['artMeshId'] == ids[i] and o['sourceGuid'] == snapshots[i]['sourceGuid']
                            for o in (requested,actual)), 'consumption identity')
                live=GEOMETRY['values'](actual['live'])
                require(live == GEOMETRY['values'](actual['consumed']) == GEOMETRY['values'](requested['live'])
                        and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion']
                        and actual['passEntryTimeMillis'] >= max(0,requested['passEntryTimeMillis']), 'fresh consumption')
                for parameter,value in zip(GEOMETRY['PARAMETERS'],pose):
                    require([v for p,v in live.values() if p == parameter] == [value], 'consumed pose')
                calculated=actual['calculated']
                require(calculated['interpolated'] is True and calculated['coordinateType'] in ('Canvas','DeformerLocal')
                        and calculated['drawOrder'] == row['stateBefore']['meshes'][i]['drawOrder']
                        and calculated['positions'] == row['stateBefore']['meshes'][i]['positions'], 'calculated frame state')
                require(observation['waitReason'] == 'CONSUMED' and observation['lastObservation'] == actual
                        and type(observation['attempts']) is int and observation['attempts'] > 0
                        and type(observation['elapsedMillis']) is int and 0 <= observation['elapsedMillis'] < 15000, 'bounded wait evidence')
    return dict(fingerprints=hashes,frames=27)


def verify_persistence_frames(frames, capture, phase, cube_path, artifact_reader, *, texture=None, padded=False):
    """Source-bound persistence sweep. Capture geometry must be admitted separately."""
    require(type(padded) is bool and padded == (texture is not None), 'explicit padded persistence texture')
    textured = None
    if padded:
        textured = runpy.run_path(str(HERE.parent / 'model-import-3d-user-entry/verify-texture-pixels.py'))
        textured['ENTRY']['textured_source'](cube_path,texture)
    require(phase in ('before-save','after-save','reopen','atlas-baseline','atlas-packed','atlas-repacked','atlas-redone',
                     'atlas-before-save','atlas-after-save','atlas-reopen') and len(frames) == len(POSES), 'persistence frame inventory')
    baseline = capture['state']; snapshots = capture['snapshots']
    source, _ = GEOMETRY['cube_source'](cube_path)
    cube = Path(cube_path).read_bytes(); results = []
    def history(h):
        require(h['availability'] == 'AVAILABLE' and type(h['revision']) is int
                and type(h['position']) is int and 0 <= h['position'] <= len(h['entryIds']), 'persistence history unavailable')
        return {k:v for k,v in h.items() if k != 'revision'}
    for index,(frame,pose) in enumerate(zip(frames,POSES)):
        name = f'cube-persistence-render/{phase}-{index}.png'
        require(frame['phase'] == phase and frame['index'] == index and frame['pose'] == list(pose)
                and frame['artifact'] == name, 'persistence frame identity')
        state = frame['stateBefore']
        expected_parameters = [list(p) for p in baseline['parameters']]
        for parameter,value in zip(GEOMETRY['PARAMETERS'],pose):
            matches = [p for p in expected_parameters if p[0] == parameter]
            require(len(matches) == 1, 'persistence parameter identity'); matches[0][3] = value
        require(state == dict(baseline,parameters=expected_parameters) and frame['stateAfter'] == state,
                'persistence render changed authored state/pose')
        require(history(frame['historyBefore']) == history(frame['historyAfter'])
                and frame['historyAfter']['revision'] >= frame['historyBefore']['revision'], 'persistence render changed history')
        require(len(frame['consumption']) == len(frame['evaluationBefore']) == len(frame['evaluationAfter']) == 6, 'persistence consumption inventory')
        for i,observation in enumerate(frame['consumption']):
            requested,actual = observation['requested'],observation['actual']
            require(all(o['artMeshId'] == snapshots[i]['artMeshId'] and o['sourceGuid'] == snapshots[i]['sourceGuid']
                        for o in (requested,actual)), 'persistence consumption identity')
            live = GEOMETRY['values'](actual['live'])
            require(live == GEOMETRY['values'](actual['consumed']) == GEOMETRY['values'](requested['live'])
                    and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion']
                    and actual['passEntryTimeMillis'] >= max(0,requested['passEntryTimeMillis']), 'persistence stale evaluation')
            for parameter,value in zip(GEOMETRY['PARAMETERS'],pose):
                require([v for p,v in live.values() if p == parameter] == [value], 'persistence consumed pose')
            calculated = actual['calculated']
            require(calculated['interpolated'] is True and calculated['coordinateType'] in ('Canvas','DeformerLocal')
                    and calculated['drawOrder'] == snapshots[i]['forms'][0]['drawOrder']
                    and GEOMETRY['error'](calculated['positions'],GEOMETRY['truth'](source[i],*pose)) <= .1,
                    'persistence calculated projection error')
            for evaluated in (frame['evaluationBefore'][i],frame['evaluationAfter'][i]):
                require(evaluated['artMeshId'] == actual['artMeshId'] and evaluated['sourceGuid'] == actual['sourceGuid']
                        and GEOMETRY['values'](evaluated['live']) == GEOMETRY['values'](evaluated['consumed']) == live
                        and evaluated['calculated'] == calculated, 'persistence evaluation changed at render')
            require(type(observation['attempts']) is int and observation['attempts'] > 0
                    and type(observation['elapsedMillis']) is int and 0 <= observation['elapsedMillis'] < 15000,
                    'persistence bounded consumption')
        require(type(frame['artifactBytes']) is int and 0 < frame['artifactBytes'] <= PNG_MAX, 'persistence PNG budget')
        raw = artifact_reader(name)
        require(len(raw) == frame['artifactBytes'] and hashlib.sha256(raw).hexdigest() == frame['artifactSha256'], 'persistence PNG hash')
        if textured is None:
            pixels = inspect_pixels(PNG(raw),cube,pose,source=True)
        else:
            expected,_,_ = textured['reference'](cube_path,texture,pose,persistence=True)
            pixels = textured['inspect'](PNG(raw),expected)
        require(pixels['mismatches'] == 0, f'persistence pixels differ: {pixels["firstMismatch"]}')
        results.append(pixels)
    return dict(frames=len(results),samples=sum(r['samples'] for r in results))
