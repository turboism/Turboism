#!/usr/bin/env python3
"""Independent full98 derived save/reopen audit; visual acceptance is separate."""
import hashlib
import json
import math
import runpy
import struct
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
EDIT = runpy.run_path(str(HERE / 'analyze-derived-bound-edit.py'))
FIXTURE = Path('build/model-import-3d-offline/apple-all-chart-engine-r1-20261003/derived-bound-all98-v2.bin')
POSES = [[0,0,0],[-180,0,0],[180,0,0],[0,-180,0],[0,180,0],
         [0,0,-180],[0,0,180],[90,0,0],[0,90,0],[0,0,90],
         [37.5,-112.5,45],[-150,75,-135]]

def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            h.update(block)
    return h.hexdigest()

def read_artifact(root, name):
    path = root / 'host-job/state' / name
    assert not path.is_symlink()
    path = path.resolve(strict=True)
    assert path.is_relative_to((root / 'host-job/state').resolve(strict=True))
    return path

def terminal(root):
    state = json.loads((root / 'terminal-status.json').read_text())
    job = state.get('job') or state['jobs'][0]
    assert job['state'] == 'succeeded'
    evidence = json.loads(job['evidence_json'])
    proof = evidence['containment']['kernelProof']
    assert evidence['normalExit'] and evidence['fixtureUnchanged'] and evidence['identityVerified']
    assert not proof['cgroupKillWritten'] and not proof['processTimedOut'] and proof['processReturncode'] == 0
    assert evidence['details']['wrapperExit'] == evidence['details']['exitCode'] == 0
    report = json.loads((root / 'native-report.json').read_text())
    assert report['status'] == 'PASS' and report['runId'] == job['run_id']
    return job, report

def inventory(bank, charts, rows, uv_method='FLOAT_DIVISION'):
    assert uv_method in ('FLOAT_DIVISION','OFFICIAL_5302_ALIGNED_RECIPROCAL_FLOAT')
    if uv_method != 'FLOAT_DIVISION':
        assert all(c['imageSize']==576 for c in charts)
    meshes = {m['id']: m for m in bank['meshes']}
    assert len(meshes) == len(bank['meshes']) == len(rows) == 98
    assert sum(m['vertexCount'] for m in meshes.values()) == 80514
    assert sum(m['triangleCount'] for m in meshes.values()) == 150784
    assert len(bank['warps']) == 1 and bank['warps'][0]['formCount'] == 2401
    for chart, row in zip(charts, rows):
        assert row['chart'] == chart['chart'] and row['oldToNew'] == chart['remap']
        assert row['representatives'] == chart['representatives'] and 0 <= row['localDrift'] <= 1e-6
        mesh = meshes[row['id']]
        assert mesh['vertexCount'] == chart['n'] and mesh['triangleCount'] == len(chart['indices']) // 3
        assert mesh['axisCount'] == 2 and mesh['formCount'] == mesh['cellCount'] == len(mesh['forms']) == 49
        geometry = hashlib.sha256()
        for x, y in chart['positions']:
            geometry.update(struct.pack('>ff', x, y))
        for x, y in chart['positions']:
            if uv_method == 'FLOAT_DIVISION':
                geometry.update(struct.pack('>ff', x / chart['imageSize'], y / chart['imageSize']))
            else:
                reciprocal=struct.unpack('>f',struct.pack('>f',1/576))[0]
                geometry.update(struct.pack('>ff', x * reciprocal, y * reciprocal))
        for index in chart['indices']:
            geometry.update(struct.pack('>i', index))
        assert mesh['geometrySha256'] == geometry.hexdigest(), 'unedited fixture geometry differs'
    assert set(meshes) == {r['id'] for r in rows}
    return meshes

def frames(root, report, meshes):
    assert report['framesCaptured'] is True and len(report['frames']) == 12
    for row, pose in zip(report['frames'], POSES):
        assert row['poseXYZ'] == pose
        path = read_artifact(root, row['artifact'])
        assert digest(path) == row['sha256']
        from PIL import Image
        with Image.open(path) as image:
            assert image.size == (1024, 1024) and image.format == 'PNG'
            image.load()
        evaluated = {m['id']: m for m in row['evaluated']}
        assert len(evaluated) == len(row['evaluated']) == 98 and evaluated.keys() == meshes.keys()
        for name, mesh in evaluated.items():
            assert len(mesh['positions']) == meshes[name]['vertexCount']
            assert all(len(p) == 2 and all(math.isfinite(v) for v in p) for p in mesh['positions'])

def analyze(save_root, reopen_root=None):
    save_root = Path(save_root).resolve(strict=True)
    job, save = terminal(save_root)
    assert save['schemaVersion'] == 'mi3d-apple-derived-save-v1'
    fixture = FIXTURE
    if save['candidateSha256'] == '00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc':
        fixture = FIXTURE.with_name('derived-bound-all98-cropped-v3.bin')
    if save['candidateSha256'] == 'cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2':
        fixture = FIXTURE.with_name('derived-bound-all98-aligned-v4.bin')
    assert save['candidateSha256'] == digest(fixture) and save['testPointDeltaIncluded'] is False
    charts = EDIT['fixture'](fixture)
    uv_method=save.get('derivedShaderUvMethod','FLOAT_DIVISION')
    meshes = inventory(save['savedState'], charts, save['derivedCharts'],uv_method)
    if uv_method == 'OFFICIAL_5302_ALIGNED_RECIPROCAL_FLOAT':
        assert save['derivedAtlasUndoVerified'] is True
        atlas=save['officialExport']
        assert atlas['status']=='LAYOUT_APPLIED_UNDO_DIAGNOSTIC_ONLY' and atlas['sourceRestored']
        assert atlas['atlasPageCount']==2 and atlas['restorationAtlasIds']==[]
        assert atlas['restoredState']==save['savedState']
    frames(save_root, save, meshes)
    assert save['originalTaskSeedRestored'] and save['seedPixelsBeforeSha256'] == save['seedPixelsAfterSha256']
    initial, restored = save['initial'], save['restoredState']
    assert initial.keys() == restored.keys()
    for key in initial:
        if key in ('meshes', 'meshMetadata'):
            assert {r['id']: r for r in initial[key]} == {r['id']: r for r in restored[key]}
        else:
            assert initial[key] == restored[key]
    for key in ('parameters', 'parts', 'warps', 'rotations'):
        assert initial[key] == save['savedState'][key]
    artifact = save['derivedSave']
    path = read_artifact(save_root, artifact['artifact'])
    assert 0 < path.stat().st_size == artifact['bytes'] <= 256 * 1024 * 1024
    assert digest(path) == artifact['sha256']
    result = dict(status='PASS_FULL98_UNEDITED_DERIVED_SAVE_ONLY', jobId=job['job_id'],
                  runId=job['run_id'], artifact=artifact, charts=98, slots=80514, originalFaces=150784,
                  frames=12, saveReopenAccepted=False, sourcePixelOcclusionAccepted=False,
                  fullAppleAccepted=False, reportSha256=digest(save_root / 'native-report.json'))
    if reopen_root is not None:
        reopen_root = Path(reopen_root).resolve(strict=True)
        second_job, reopen = terminal(reopen_root)
        assert second_job['run_id'] != job['run_id']
        assert reopen['schemaVersion'] == 'mi3d-apple-derived-reopen-v1'
        assert reopen['sourceReadHistoryUnchanged'] and reopen['sourceUnchanged'] and reopen['historyPositionRestored']
        before_bank, after_bank = save['savedState'], reopen['reopenedState']
        assert before_bank.keys() == after_bank.keys() and reopen['axes'] == save['axes']
        for key in before_bank:
            if key in ('meshes', 'meshMetadata'):
                before_rows, after_rows = before_bank[key], after_bank[key]
                before_ids = {r['id']: r for r in before_rows}
                after_ids = {r['id']: r for r in after_rows}
                assert len(before_ids) == len(before_rows) == len(after_ids) == len(after_rows)
                assert before_ids == after_ids
            else:
                assert before_bank[key] == after_bank[key], key
        inventory(after_bank, charts, save['derivedCharts'],uv_method)
        frames(reopen_root, reopen, meshes)
        comparisons = []
        for a, b in zip(save['frames'], reopen['frames']):
            before = {m['id']: m for m in a['evaluated']}
            after = {m['id']: m for m in b['evaluated']}
            maximum = 0
            for name in before:
                assert before[name]['order'] == after[name]['order']
                for p, q in zip(before[name]['positions'], after[name]['positions']):
                    maximum = max(maximum, math.dist(p, q))
            assert maximum <= .001
            comparisons.append(dict(poseXYZ=a['poseXYZ'], maximumPairPixels=maximum,
                                    pngIdentical=a['sha256'] == b['sha256']))
        result.update(status='PASS_FULL98_UNEDITED_DERIVED_SAVE_REOPEN_BANK_AND_GEOMETRY',
                      saveReopenAccepted=True, reopenJobId=second_job['job_id'], reopenRunId=second_job['run_id'],
                      comparisons=comparisons, reopenReportSha256=digest(reopen_root / 'native-report.json'))
    target = (reopen_root or save_root) / 'independent-derived-persistence-review.json'
    target.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))

if __name__ == '__main__':
    analyze(*sys.argv[1:])
