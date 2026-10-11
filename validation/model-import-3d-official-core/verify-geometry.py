"""Independent frozen-cube Core geometry checks, NOT process/raster acceptance.

Caller must bind mesh_ids to the already-admitted export's authored snapshot order
and separately admit the MOC/Core/recorder hashes and contained process evidence.
The fixed conversion is Core units (X right, Y up) to the 1024px canvas, origin
(512,512), 100px/unit. Never fit scale, orientation or vertex correspondence to
observed results. A mismatch fails; native execution is still required to prove
that the official export follows this declared contract.
"""
import math
from pathlib import Path
import runpy

GEOMETRY = runpy.run_path(str(Path(__file__).resolve().parents[1] /
                            'model-import-3d-host-probe/verify-cube-geometry.py'))


def require(ok, message):
    if not ok:
        raise ValueError('OFFICIAL_CORE_GEOMETRY: ' + message)


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def verify(capture, cube_path, mesh_ids, authored_parameters=None):
    require(capture.get('schemaVersion') == 'mi3d-official-core-capture-v2'
            and capture.get('status') == 'CAPTURED_NOT_VERIFIED'
            and capture.get('poseProtocol') == 'mi3d-cube-geometry-377-v1', 'capture protocol')
    require(capture['canvas'] == dict(size=[1024, 1024], origin=[512, 512], pixelsPerUnit=100),
            'fixed export canvas/units')
    require(len(mesh_ids) == 6 and len(set(mesh_ids)) == 6
            and all(isinstance(i, str) and i for i in mesh_ids), 'admitted mesh identities')
    parameters = capture['parameters']; axes = GEOMETRY['PARAMETERS']
    # Cubism NEW_MODEL retains standard unused parameters. Admit them only by
    # matching the already-accepted export's authored inventory, never by learning
    # an allowlist from this Core capture. Unused parameters must remain at default.
    if authored_parameters is None:
        authored_parameters = [[axis, axis, 'NORMAL', 0, -30, 0, 30, False] for axis in axes]
    require(all(len(p) == 8 and p[2] == 'NORMAL' and p[3] == p[5]
                and all(finite(p[i]) for i in (3, 4, 5, 6)) and type(p[7]) is bool
                for p in authored_parameters), 'admitted parameter definitions')
    admitted = {p[0]: p for p in authored_parameters}
    ids = parameters['ids']
    require(capture['axes'] == axes and len(ids) == len(set(ids)) == len(admitted)
            == len(authored_parameters) and set(ids) == set(admitted)
            and set(axes) <= set(ids), 'parameter inventory')
    require(all(len(parameters[k]) == len(ids) for k in
                ('minimum', 'maximum', 'default', 'keyCounts', 'repeats', 'keys')), 'parameter array lengths')
    for i, identity in enumerate(ids):
        p = admitted[identity]
        require([parameters[k][i] for k in ('minimum', 'default', 'maximum', 'repeats')]
                == [p[4], p[5], p[6], p[7]], 'parameter definitions')
        expected_keys = GEOMETRY['KEYS'] if identity in axes else []
        require(parameters['keyCounts'][i] == len(expected_keys)
                and [GEOMETRY['f32'](k) for k in parameters['keys'][i]] == expected_keys, 'dense keys')
        if identity in axes:
            require([p[4], p[5], p[6], p[7]] == [-30, 0, 30, False], 'axis definitions')
    sources, patches = GEOMETRY['cube_source'](cube_path)
    poses = GEOMETRY['poses'](); frames = capture['frames']
    require(len(frames) == len(poses) == 377, 'complete sample inventory')
    frozen = {}; maximum = 0.0
    for frame, pose in zip(frames, poses):
        require(frame['pose'] == list(pose), 'ordered sample pose')
        require(len(frame['values']) == len(ids) and all(finite(v) for v in frame['values'])
                and all(GEOMETRY['f32'](frame['values'][parameters['ids'].index(axis)]) == GEOMETRY['f32'](value)
                        for axis, value in zip(axes, pose)), 'evaluated parameter values')
        require(all(frame['values'][i] == admitted[identity][5]
                    for i, identity in enumerate(ids) if identity not in axes), 'unused parameter changed')
        rows = frame['drawables']; require(len(rows) == 6, 'drawable inventory')
        by_id = {row['id']: row for row in rows}
        require(set(by_id) == set(mesh_ids), 'drawable identities')
        require(sorted(row['renderOrder'] for row in rows) == list(range(6)), 'render order permutation')
        for mesh, vertices, patch in zip(mesh_ids, sources, patches):
            row = by_id[mesh]; count = len(vertices)
            # Official OpenGL renderer declares GL_CCW front faces (NativeFramework
            # 45de21229a9faab9675525bc59deddbe089b0e95, DrawMeshOpenGL).
            # Source patch triangles face positive Y-down canvas area, so export
            # must reverse each triple in the Y-up Core coordinate domain.
            expected_indices = [i for offset in range(0, len(patch['indices']), 3)
                                for i in reversed(patch['indices'][offset:offset+3])]
            require(row['vertexCount'] == count and row['indices'] == expected_indices, 'source topology')
            # Four float32 bilinear weights may sum a few machine epsilons away
            # from one. This bound is unrelated to the unchanged 0.1px geometry gate.
            require(row['textureIndex'] == 0 and row['masks'] == [] and finite(row['opacity'])
                    and abs(row['opacity']-1) <= 4*2**-23,
                    'texture/masks/opacity')
            require(type(row['constantFlags']) is int and row['constantFlags'] == 0,
                    'normal blend and single-sided faces')
            require(type(row['drawOrder']) is int and 0 <= row['drawOrder'] <= 1000, 'draw order')
            uvs = row['uvs']; require(len(uvs) == 2*count and all(finite(v) and 0 <= v <= 1 for v in uvs), 'atlas UVs')
            invariant = {k: row[k] for k in ('vertexCount', 'indices', 'textureIndex', 'masks', 'uvs',
                                            'constantFlags', 'drawOrder')}
            if mesh in frozen:
                require(invariant == frozen[mesh], 'topology/UV/order changed across poses')
            else:
                frozen[mesh] = invariant
            positions = row['positions']
            require(len(positions) == count*2 and all(finite(v) for v in positions), 'finite vertex array')
            canvas = [[512+100*positions[2*i], 512-100*positions[2*i+1]] for i in range(count)]
            error = GEOMETRY['error'](canvas, GEOMETRY['truth'](vertices, *pose))
            require(error <= .1, f'{mesh} at {pose}: {error} canvas px exceeds 0.1')
            maximum = max(maximum, error)
    return dict(status='GEOMETRY_CHECKED_NOT_FULL_ACCEPTANCE', samples=377, meshes=6,
                maxErrorCanvasPixels=maximum, toleranceCanvasPixels=.1,
                notCovered=['native process admission', 'artifact provenance', 'texture pixels',
                            'raster visibility and occlusion'])
