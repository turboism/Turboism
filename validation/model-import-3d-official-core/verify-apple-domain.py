#!/usr/bin/env python3
"""All native vertices at layer keys, centers, edge midpoints and frozen random poses."""
import argparse
import gzip
import json
from pathlib import Path
import runpy
import struct
import numpy as np

HERE = Path(__file__).resolve().parent
JOB = runpy.run_path(str(HERE/'verify-apple-job.py'))
GEOMETRY = runpy.run_path(str(HERE/'verify-apple-geometry.py'))['verify']
require, read, sha = (JOB[k] for k in ('require', 'read', 'sha'))


class Frames:
    def __init__(self, capture, file):
        self.capture, self.file = capture, file

    def __len__(self):
        return 353

    def __iter__(self):
        baseline = self.capture['baselineDrawables']; invariants = {}
        with gzip.open(self.file, 'rb') as stream:
            require(stream.read(16) == b'APXY0001'+struct.pack('>ii', 353, 100258), 'fixed coordinate header')
            for frame in self.capture['frames']:
                require([r['id'] for r in frame['drawables']] == [r['id'] for r in baseline], 'stable complete native mesh order')
                rows = []
                for source, scalar in zip(baseline, frame['drawables']):
                    identity = scalar['id']; count = source['vertexCount']
                    require(scalar['vertexCount'] == count and isinstance(scalar['invariantSha256'], str)
                            and len(scalar['invariantSha256']) == 64, 'complete invariant evidence')
                    require(invariants.setdefault(identity, scalar['invariantSha256']) == scalar['invariantSha256'],
                            'actual topology/UV/texture/mask/flags unchanged across all updates')
                    raw = stream.read(count*8)
                    require(len(raw) == count*8, 'complete frame vertex bytes')
                    positions = np.frombuffer(raw, dtype='>f4')
                    rows.append(dict(source, **scalar, positions=positions))
                yield dict(frame, drawables=rows)
            require(stream.read(1) == b'', 'no trailing/inflated coordinate data')


def verify(controller, bundle, export_acceptance, recorder, prepared, domain_receipt):
    identity, capture, accepted = JOB['process'](controller, bundle, export_acceptance, recorder, 'apple-domain-353-v1')
    require(capture['schemaVersion'] == 'mi3d-apple-official-core-domain-capture-v1'
            and capture['poseProtocol'] == 'mi3d-apple-layer-domain-353-v1'
            and capture['status'] == 'CAPTURED_NOT_VERIFIED', 'explicit domain capture')
    diagnostic = read(domain_receipt/'report.json')
    poses = read(domain_receipt/'poses.json')
    require(diagnostic['status'] == 'SOURCE_DEPTH_DOMAIN_DIAGNOSTIC_NOT_NATIVE_ACCEPTANCE'
            and diagnostic['poses'] == len(poses) == len(capture['frames']) == 353, 'same frozen source domain')
    for name in ('poses.json', 'report.json'):
        require(sha(domain_receipt/name) == read(domain_receipt/'artifact-manifest.json')[name], 'frozen domain protocol')
    require([r['pose'] for r in capture['frames']] == poses, 'all ordered exact domain values')
    require(poses[:289] == [[-30+3.75*y, -30+3.75*p] for y in range(17) for p in range(17)], 'keys/centers/edges and boundary')
    coords = capture['coordinates']; file = controller/'host-job/evidence/standalone-core/capture/positions-f32.bin.gz'
    require({p.name for p in file.parent.iterdir()} == {'core-capture.json', 'positions-f32.bin.gz'},
            'exact captured coordinate namespace')
    require(coords['artifact'] == 'positions-f32.bin.gz' and coords['verticesPerFrame'] == 100258
            and coords['uncompressedBytes'] == 16+353*100258*8
            and 0 < file.stat().st_size == coords['bytes'] <= 512*1024*1024
            and sha(file) == coords['sha256'], 'bounded complete compressed native coordinates')
    artifacts = export_acceptance.parent/'artifacts'; report = read(artifacts/'report.json')
    require(diagnostic['exportReportSha256'] == sha(artifacts/'report.json'), 'source depth uses same authored order bank')
    state = json.loads(report['officialExport']['packedStateJson']); axes = capture['axes']
    expected_orders = []
    for pose in poses:
        evaluated = []
        for mesh in state['meshes']:
            keys = mesh['axes'][0]['keys']; by_guid = {a['guid']: a['id'] for a in mesh['axes']}
            forms = {tuple(dict((by_guid[g], v) for g, v in f['coordinates'])[a] for a in axes): f['drawOrder'] for f in mesh['forms']}
            brackets = []
            for value in pose:
                i = min(len(keys)-2, max(0, int(np.searchsorted(keys, value, side='right')-1)))
                brackets.append((keys[i], keys[i+1], (value-keys[i])/(keys[i+1]-keys[i])))
            a, b, fy = brackets[0]; c, d, fp = brackets[1]
            value = (1-fy)*((1-fp)*forms[a, c]+fp*forms[a, d])+fy*((1-fp)*forms[b, c]+fp*forms[b, d])
            # Stored values are exact integers. At fractional poses Core exposes an
            # integer, not the unquantized interpolant. Admit only its floor/ceil
            # enclosure; integral keys/midpoints still require the exact value.
            value = float(np.float32(value))
            evaluated.append(dict(artMeshId=mesh['id'], calculated=dict(
                drawOrderMinimum=int(np.floor(value)), drawOrderMaximum=int(np.ceil(value)))))
        expected_orders.append(dict(evaluationBefore=evaluated))
    adapted = dict(capture, schemaVersion='mi3d-apple-official-core-capture-v1',
                   poseProtocol='mi3d-apple-geometry-11-v1', frames=Frames(capture, file))
    geometry = GEOMETRY(adapted, report, prepared, artifacts, poses=poses, native_frames=expected_orders)
    return dict(status='PASS_NATIVE_APPLE_FINITE_LAYER_DOMAIN_GEOMETRY', **identity, normalExit=True,
                officialCoreAccepted=True, geometry=geometry, keyPoses=81, cellCenters=64,
                edgeMidpoints=144, randomPoses=64, allVerticesPerFrame=100258,
                captureSha256=sha(controller/'host-job/evidence/standalone-core/capture/core-capture.json'),
                coordinatesSha256=sha(file), sourceDepthDiagnosticSha256=sha(domain_receipt/'report.json'),
                sourceDepthResidualPoses=diagnostic['posesWithWrongDepth'],
                maximumSourceDepthResidualPixels=diagnostic['maximumWrongDepthPixels'],
                layerQuantizationScope='EXACT_INTEGRAL_VALUES_AND_FLOOR_CEIL_ENCLOSURE_AT_FRACTIONAL_POSES',
                nativePixelCorrectnessAccepted=False, continuousDomainAccepted=False, goalComplete=False)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('controller', 'bundle', 'export-acceptance', 'recorder', 'prepared', 'domain-receipt', 'out'):
        p.add_argument('--'+name, type=Path, required=True)
    a = p.parse_args(); result = verify(a.controller, a.bundle, a.export_acceptance, a.recorder, a.prepared, a.domain_receipt)
    with a.out.open('x') as f:
        json.dump(result, f, indent=2); f.write('\n')
    print(json.dumps(result, indent=2))
