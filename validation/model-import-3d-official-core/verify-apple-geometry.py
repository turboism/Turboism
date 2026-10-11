#!/usr/bin/env python3
"""Fixed apple source projection and packed UV/topology oracle; no fitted correspondence."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import numpy as np

SOURCE = runpy.run_path(str(Path(__file__).resolve().parents[1] /
                           'model-import-3d-canonical-warp/verify-canonical-warp-layered.py'))


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_CORE_GEOMETRY: ' + why)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def verify(capture, report, prepared, artifacts, *, poses=None, native_frames=None):
    poses = SOURCE['POSES'] if poses is None else poses
    require(capture['schemaVersion'] == 'mi3d-apple-official-core-capture-v1'
            and capture['status'] == 'CAPTURED_NOT_VERIFIED'
            and capture['poseProtocol'] == 'mi3d-apple-geometry-11-v1', 'explicit protocol')
    require(capture['canvas'] == dict(size=[1024, 1024], origin=[512, 512], pixelsPerUnit=100),
            'fixed canvas conversion')
    export = report['officialExport']; packed = json.loads(export['packedStateJson'])
    moc = (artifacts/'official-export/model.moc3').read_bytes()
    require(capture['mocBytes'] == len(moc) == export['moc']['bytes']
            and capture['mocSha256'] == sha(moc) == export['moc']['sha256'], 'exact exported MOC')
    axes = report['sourceTextureRender']['axes']; params = capture['parameters']
    authored = {p[0]: p for p in packed['parameters']}; ids = params['ids']
    require(capture['axes'] == axes and len(ids) == len(set(ids)) == len(authored)
            and set(ids) == set(authored), 'complete parameter identity')
    for field in ('minimum', 'maximum', 'default', 'keyCounts', 'keys', 'repeats'):
        require(len(params[field]) == len(ids), 'parameter array lengths')
    for i, identity in enumerate(ids):
        p = authored[identity]
        require([params[f][i] for f in ('minimum', 'default', 'maximum', 'repeats')]
                == [p[1], p[2], p[3], False], 'source parameter definitions')
        keys = SOURCE['KEYS'] if identity in axes else []
        require(params['keyCounts'][i] == len(keys) and params['keys'][i] == keys,
                'full seventeen geometry keys')
    sources, _ = SOURCE['prepared'](prepared)
    meshes = packed['meshes']; inventory = [m['id'] for m in meshes]
    require(len(meshes) == len(set(inventory)) == 98 and len(capture['frames']) == len(poses),
            'complete mesh/frame inventory')
    packed_geometry = []
    for i, (mesh, source, receipt) in enumerate(zip(meshes, sources, export['packedSource'])):
        raw = (artifacts/f'packed-source/{i}.bin').read_bytes(); n = source['count']; t = source['triangles']
        require(mesh['vertexCount'] == n and mesh['triangleCount'] == t
                and len(raw) == n*16+t*12 and sha(raw) == mesh['geometrySha256'] == receipt['sha256'],
                'admitted packed geometry bytes')
        floats = np.frombuffer(raw[:n*16], dtype='>f4').reshape(2, n, 2)
        ix = np.frombuffer(raw[n*16:], dtype='>i4').reshape(t, 3)
        require(np.array_equal(ix, source['indices']), 'all original triangle indices')
        # SDK atlas UV is Y down; official Core/OpenGL texture coordinates are Y up.
        uv = floats[1].astype(np.float32); uv[:, 1] = np.float32(1)-uv[:, 1]
        packed_geometry.append((uv, ix[:, ::-1].ravel().tolist()))
    maximum = 0.; count = 0
    captures = export['atlasCapture']['sourceTextureRender']['frames'] if native_frames is None else native_frames
    for frame, pose, native in zip(capture['frames'], poses, captures):
        require(frame['pose'] == list(pose), 'ordered eleven source poses')
        values = np.asarray(frame['values'], dtype=float)
        require(values.shape == (len(ids),) and np.all(np.isfinite(values)), 'finite complete parameter values')
        for i, identity in enumerate(ids):
            expected = pose[axes.index(identity)] if identity in axes else authored[identity][2]
            # Core values are float32; Java serializes their shortest decimal form.
            # Compare exact float32 values, retaining rejection of a one-ULP change.
            require(np.float32(values[i]) == np.float32(expected), 'exact requested and unused parameter values')
        rows = frame['drawables']; by_id = {row['id']: row for row in rows}
        require(len(rows) == len(by_id) == 98 and set(by_id) == set(inventory), 'exact drawable identities')
        require(sorted(r['renderOrder'] for r in rows) == list(range(98)), 'render order permutation')
        native_by_id = {r['artMeshId']: r for r in native['evaluationBefore']}
        for mesh, source, (uv, ix) in zip(meshes, sources, packed_geometry):
            row = by_id[mesh['id']]; n = source['count']
            require(row['vertexCount'] == n and row['indices'] == ix,
                    'complete original topology with fixed reversed winding')
            actual_uv = np.asarray(row['uvs'], dtype=np.float32)
            require(actual_uv.shape == (n*2,) and np.all(np.isfinite(actual_uv))
                    and np.max(np.abs(actual_uv.reshape(n, 2)-uv)) <= 2**-23, 'fixed packed atlas UV convention')
            require(row['textureIndex'] == 0 and row['masks'] == [] and row['constantFlags'] == 0
                    and np.isfinite(row['opacity']) and abs(row['opacity']-1) <= 4*2**-23,
                    'single sided normal textured drawables')
            multiply = np.asarray(row['multiplyColor'], dtype=np.float32)
            screen = np.asarray(row['screenColor'], dtype=np.float32)
            # Four float32 bilinear weights incur the same sum roundoff as opacity.
            require(row['blendMode'] == 0 and multiply.shape == screen.shape == (4,)
                    and np.all(np.isfinite(multiply)) and np.all(np.isfinite(screen))
                    and np.max(np.abs(multiply-[1, 1, 1, 1])) <= 4*2**-23
                    and np.max(np.abs(screen-[0, 0, 0, 1])) <= 4*2**-23, 'unchanged source texture colors')
            order = native_by_id[mesh['id']]['calculated']
            if 'drawOrderMinimum' in order:
                require(type(row['drawOrder']) is int
                        and order['drawOrderMinimum'] <= row['drawOrder'] <= order['drawOrderMaximum'],
                        'native layer order within integer quantization enclosure')
            else:
                require(row['drawOrder'] == order['drawOrder'], 'native parameter-dependent layer order')
            positions = np.asarray(row['positions'], dtype=float)
            require(positions.shape == (n*2,) and np.all(np.isfinite(positions)), 'finite complete vertices')
            xy = positions.reshape(n, 2)*[100, -100]+[512, 512]
            error = float(np.linalg.norm(xy-SOURCE['source_projection'](source['xyz'], pose), axis=1).max())
            require(error <= 1, f'{mesh["id"]} {pose} exceeds fixed 1px source projection budget: {error}')
            maximum = max(maximum, error); count += n
    return dict(status='APPLE_CORE_GEOMETRY_CHECKED_NOT_PROCESS_OR_RENDERER_ACCEPTANCE',
                meshes=98, triangles=sum(m['triangles'] for m in sources), poses=len(poses),
                checkedVertexSamples=count, maximumCanvasErrorPixels=maximum, toleranceCanvasPixels=1,
                topologyExact=True, packedUVConventionVerified=True, nativeLayerOrderExact=native_frames is None,
                nativeLayerOrderQuantizationBounded=native_frames is not None,
                notCovered=['official renderer execution', 'continuous rotation domain'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('capture', 'report', 'prepared', 'artifacts', 'out'):
        parser.add_argument('--'+name, type=Path, required=True)
    a = parser.parse_args()
    result = verify(json.loads(a.capture.read_text()), json.loads(a.report.read_text()), a.prepared, a.artifacts)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2); stream.write('\n')
    print(json.dumps(result))
