"""Independent sheet geometry capture oracle. Process/visibility admission is separate."""
import json
import hashlib
import math
import struct
import runpy
from pathlib import Path

SHEETS_SHA = '0c0f716da082c9987ba580565a97ec81237c4e0333ae4023042b9c17b41a9984'
AXES = ('ParamMI3DCubeYaw', 'ParamMI3DCubePitch')

def require(ok, reason):
    if not ok:
        raise ValueError('SURFACE_GEOMETRY: ' + reason)

def f32(x):
    require(type(x) in (int, float) and math.isfinite(x), 'finite number')
    return struct.unpack('!f', struct.pack('!f', x))[0]

def inventory(segments=38):
    keys = [f32(30 * (2 * i / segments - 1)) if i != segments // 2 else 0.0 for i in range(segments + 1)]
    split = []
    for i, v in enumerate(keys):
        split.append(v)
        if i + 1 < len(keys):
            split.append(f32(f32(v + keys[i + 1]) / 2))
    poses = [(y, p) for y in split for p in split]
    state = (0x3344494d ^ 0x5deece66d) & ((1 << 48) - 1)
    def random_float():
        nonlocal state
        state = (state * 0x5deece66d + 11) & ((1 << 48) - 1)
        return (state >> 24) / (1 << 24)
    for _ in range(512):
        poses.append(tuple(f32(-30 + f32(60 * random_float())) for _ in range(2)))
    require(len(set(poses)) == (2*segments+1)**2+512, 'fixed inventory collision')
    return keys, poses

def truth_vertices(vertices, yaw, pitch):
    y, p = math.radians(yaw), math.radians(pitch)
    return [[512 + 200 * (math.cos(y)*x + math.sin(y)*math.sin(p)*v + math.sin(y)*math.cos(p)*z),
             512 - 200 * (math.cos(p)*v - math.sin(p)*z)] for x,v,z in vertices]

def truth(surface, yaw, pitch):
    x = -.25 if surface == 0 else .25
    return truth_vertices([(x,v,z) for v,z in [(-.75,-1),(.75,-1),(.75,1),(-.75,1)]],yaw,pitch)


def parameters(e, section):
    rows = e[section]['parameters']
    require(len({p['guid'] for p in rows}) == len(rows), 'parameter GUID uniqueness')
    return {p['guid']: (p['id'], f32(p['value'])) for p in rows}

def _verify_geometry(report, glb, source_sha, sources, segments, label):
    require(hashlib.sha256(glb).hexdigest() == source_sha, 'frozen source input')
    require(report['status'] == 'PASS' and report['scope'] == 'NATIVE_SURFACE_GEOMETRY_ONLY'
            and report['inputSha256'] == source_sha and report['maxErrorPixels'] == .1, 'scope/status/input/tolerance')
    require(report['restored'] is True and report['before'] == report['after']
            and report['liveBefore'] == report['liveAfter'], 'authored/live restoration')
    history = report['historyBefore']
    protocol=report.get('historyProtocol')
    require(protocol in (None,'POSE_DRIVER_UNDO_V1','POSE_DRIVER_UNDO_RETURN_V2'),'known history protocol')
    if protocol:
        runpy.run_path(str(Path(__file__).with_name('verify-pose-drive.py')))['verify'](report,AXES)
    else:
        require(history['availability'] == 'AVAILABLE' and report['historyAfter'] == history, 'history restoration')
    keys, poses = inventory(segments)
    before = report['before']
    require(len(before) == len(sources) and len({m['artMeshId'] for m in before}) == len(sources)
            and len({m['sourceGuid'] for m in before}) == len(sources), 'mesh identities')
    for mesh in before:
        require(len(mesh['axes']) == 2 and len(mesh['forms']) == len(mesh['cells']) == len(keys)**2, 'stored joint grid')
        for name in AXES:
            axes = [a for a in mesh['axes'] if a['id'] == name]
            require(len(axes) == 1 and list(map(f32, axes[0]['keys'])) == keys, 'dense axes')
    require(report['expectedSamples'] == len(poses) == len(report['samples']), 'complete sample count')
    maximum = 0.0
    for row, pose in zip(report['samples'], poses):
        require(tuple(map(f32, row['pose'])) == pose and row['status'] == 'PASS', 'ordered pose inventory')
        require((protocol is not None or row['historyAfter'] == history) and len(row['meshes']) == len(sources), 'sample history/meshes')
        priming=row.get('priming')
        if priming is not None:
            require(protocol=='POSE_DRIVER_UNDO_RETURN_V2' and len(priming['meshes'])==len(sources),'priming mesh count')
            prime_pose=tuple(map(f32,priming['pose']))
            require(prime_pose[0] in keys and prime_pose[0]!=pose[0] and prime_pose[1]==pose[1],'priming grid pose')
            for i, detail in enumerate(priming['meshes']):
                requested,actual=detail['requested'],detail['actual']
                require(detail['waitReason']=='CONSUMED' and detail['lastObservation']==actual
                        and type(detail['attempts']) is int and detail['attempts']>=1
                        and 0<=detail['elapsedMillis']<15000,'priming consumption witness')
                for e in (requested,actual):
                    require((e['artMeshId'],e['sourceGuid'])==(before[i]['artMeshId'],before[i]['sourceGuid']),'priming identity')
                require(parameters(requested,'live')==parameters(actual,'live')==parameters(actual,'consumed'),'priming consumed values')
                for axis,value in zip(AXES,prime_pose):
                    require([v for name,v in parameters(actual,'live').values() if name==axis]==[value],'priming applied pose')
                require(actual['passEntryTimeMillis']>=0
                        and actual['consumed']['updateVersion']!=requested['consumed']['updateVersion'],'priming fresh pass')
                returned=row['meshes'][i]['requested']
                require(parameters(returned,'consumed')==parameters(actual,'consumed'), 'return starts from consumed priming pose')
        for i, detail in enumerate(row['meshes']):
            requested, actual = detail['requested'], detail['actual']
            require(detail['waitReason'] == 'CONSUMED' and detail['lastObservation'] == actual
                    and type(detail['attempts']) is int and detail['attempts'] >= 1
                    and 0 <= detail['elapsedMillis'] < 15000, 'consumption witness')
            for e in (requested, actual):
                require((e['artMeshId'], e['sourceGuid']) == (before[i]['artMeshId'], before[i]['sourceGuid']), 'mesh/source identity')
            require(parameters(requested, 'live') == parameters(actual, 'live') == parameters(actual, 'consumed'), 'consumed parameter values')
            for axis, value in zip(AXES, pose):
                matches = [v for name, v in parameters(actual, 'live').values() if name == axis]
                require(matches == [value], 'requested pose applied')
            require(actual['passEntryTimeMillis'] >= 0 and actual['consumed']['updateVersion'] != requested['consumed']['updateVersion'], 'fresh native pass')
            form = actual['calculated']
            require(form['interpolated'] is True and form['coordinateType'] in ('Canvas', 'DeformerLocal'), 'calculated coordinate state')
            positions = form['positions']
            require(len(positions) == len(sources[i]) and all(len(p) == 2 for p in positions), 'vertex correspondence')
            observed = [[f32(v) for v in p] for p in positions]
            error = max(math.dist(a, b) for a, b in zip(observed, truth_vertices(sources[i], *pose)))
            require(error <= .1, 'independent source projection exceeds 0.1px')
            maximum = max(maximum, error)
    return dict(status=label, samples=len(poses), maxErrorPixels=maximum,
                processAdmitted=False, visibilityVerified=False)

def verify_sheets(report,glb):
    sources=[[(x,y,z) for y,z in [(-.75,-1),(.75,-1),(.75,1),(-.75,1)]] for x in (-.25,.25)]
    return _verify_geometry(report,glb,SHEETS_SHA,sources,38,'SHEET_GEOMETRY_CONTENT_ONLY_PASS')

PLANE_SHA='8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997'
SEAM_SHA='89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34'
def verify_planar(report,glb):
    sha=hashlib.sha256(glb).hexdigest()
    require(sha in (PLANE_SHA,SEAM_SHA),'fixed planar input')
    length=struct.unpack_from('<I',glb,12)[0];doc=json.loads(glb[20:20+length]);binary=glb[28+length:]
    accessor=doc['accessors'][0];view=doc['bufferViews'][accessor['bufferView']]
    offset=view.get('byteOffset',0)+accessor.get('byteOffset',0)
    vertices=[struct.unpack_from('<3f',binary,offset+i*12) for i in range(accessor['count'])]
    # Fixed fixture correspondence, independently specified from its source index sequence.
    # Coincident positions on the seam remain distinct source slots and separate patches.
    mapping=((0,2,1,3),) if sha==PLANE_SHA else ((0,1,2),(3,4,5))
    require(len(vertices)==(4 if sha==PLANE_SHA else 6),'source slot inventory')
    return _verify_geometry(report,glb,sha,[[vertices[i] for i in patch] for patch in mapping],36,
                            'PLANAR_GEOMETRY_CONTENT_ONLY_PASS')


def verify_sheet_report(report, run_id, glb, *, expected_mode="sheet-geometry"):
    require(expected_mode in ("sheet-geometry", "sheet-render"), "explicit sheet mode")
    return _verify_surface_report(report,run_id,glb,expected_mode,'sheetGeometry',SHEETS_SHA,verify_sheets)


def verify_planar_report(report,run_id,glb,*,expected_mode):
    profiles={'plane-geometry':('PLANE',PLANE_SHA),'plane-seam-geometry':('SEAM',SEAM_SHA),
              'plane-render':('PLANE',PLANE_SHA),'plane-seam-render':('SEAM',SEAM_SHA)}
    require(expected_mode in profiles,'explicit planar mode')
    profile,sha=profiles[expected_mode]
    require(report['planarGeometry']['profile']==profile,'planar mode/profile correspondence')
    return _verify_surface_report(report,run_id,glb,expected_mode,'planarGeometry',sha,verify_planar)


def _verify_surface_report(report,run_id,glb,expected_mode,section_key,input_sha,verify_capture):
    require(report['schemaVersion'] == 'mi3d-native-n2-v1' and report['scope'] == 'N2_BOOTSTRAP_CREATE_GRID_READ'
            and report['mi3dMode'] == expected_mode, 'outer report mode')
    require(report['runId'] == run_id and bool(run_id) and report['status'] == 'PASS'
            and report['failure'] is None and report['stageAStatus'] == 'NOT_EVALUATED', 'outer run/status')
    require(report['requestedVersion'] in {
        '5000', '5001', '5002', '5003', '5004', '5005', '5006',
        '5100', '5101', '5102', '5103',
        '5200', '5201', '5202', '5203',
        '5300', '5301', '5302', '5303', '5304',
    } and report['hostIdentity'] is None, 'host identity scope')
    checks = report['checks']
    require(len(checks) == 2 and {c['id'] for c in checks} == {'newCommand', 'newIdentity'}
            and all(c['status'] == 'PASS' and c['actual'] is True and c['evidenceKind'] == 'EXACT_HOST' for c in checks), 'native entry checks')
    for phase, key in (('SEED', 'seed'), ('NEW', 'document')):
        identity, wait = report[key], report['documentWait' + phase]
        require(identity['kind'] == 'MODEL' and wait['phase'] == phase and wait['reason'] == 'READY'
                and wait['documentPresent'] is True and wait['modelPresent'] is True and wait['kind'] == 'MODEL'
                and type(wait['attempts']) is int and wait['attempts'] > 0, 'document readiness')
        require((wait['documentId'], wait['snapshotModelId'], wait['editorModelId']) ==
                (identity['documentId'], identity['modelId'], identity['editorModelId']), 'document identity observation')
    for key in ('documentId', 'modelId', 'editorModelId'):
        require(all(isinstance(report[p][key], str) and report[p][key] for p in ('seed', 'document'))
                and report['seed'][key] != report['document'][key], 'seed reused')
    counts = report['initialCounts']
    require(set(counts) == {'meshes', 'deformers', 'parameters', 'parts'}
            and all(type(v) is int and v >= 0 for v in counts.values())
            and counts['meshes'] == counts['deformers'] == 0, 'blank model')
    require(report['finallyRestored'] is True and report['finallyCounts'] == counts, 'outer restoration')
    section = report[section_key]
    require(section['status'] == 'PASS' and section['inputSha256'] == input_sha
            and section['importOutcome'] == 'COMMITTED' and section['finallyRestored'] is True
            and section['initialState'] == section['restoredState'], 'import/capture restoration')
    return verify_capture(section['capture'], glb)
