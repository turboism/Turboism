#!/usr/bin/env python3
"""Exact packed authoring, atlas layout and eleven native pixel pairs in a fresh process."""
import hashlib
import json
import runpy
from pathlib import Path

POSES = runpy.run_path(str(Path(__file__).with_name('verify-apple-reopen.py')))['POSES']


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_ATLAS_REOPEN: ' + why)


def verify(saved, reopened, save_run, reopen_run, save_artifacts, artifacts):
    content = runpy.run_path(str(Path(__file__).with_name('verify-apple-atlas-save.py')))['verify'](saved,save_artifacts)
    require(save_run != reopen_run and saved['runId'] == save_run, 'distinct bound processes')
    require(reopened['schemaVersion'] == 'mi3d-apple-menu-atlas-reopen-v1' and reopened['scope'] == 'OBSERVE_REOPENED_PACKED_SEED_ONLY'
            and reopened['runId'] == reopen_run and reopened['status'] == 'PASS' and 'failure' not in reopened, 'packed reopen scope and identity')
    e = saved['officialExport']; packed = json.loads(e['packedStateJson'])
    require(reopened['reopened'] == reopened['afterReopenCapture'] == packed, 'complete durable geometry, UV, forms, controls and resources')
    require(reopened['reopenedAtlasLayout'] == e['packedAtlasLayout'] and reopened['reopenedAtlases'] == e['atlases'], 'durable atlas inventory and placement')
    a, b = e['atlasCapture']['sourceTextureRender'], reopened['sourceTextureRender']
    require(b['schemaVersion'] == 'mi3d-apple-texture-render-v1' and b['status'] == 'CAPTURED' and b['restored'] is True and b['axes'] == a['axes'], 'native atlas capture scope')
    protected = dict(packed,parameters=[p[:4] for p in packed['parameters']])
    digest = hashlib.sha256(b['protectedStateJson'].encode()).hexdigest()
    require(json.loads(b['protectedStateJson']) == protected and digest == b['protectedStateSha256'], 'full packed capture protection')
    root = artifacts/'source-texture-render'
    require(len(a['frames']) == len(b['frames']) == 11 and {p.name for p in root.iterdir()} == {f'{i}.png' for i in range(11)}, 'complete eleven pose namespace')
    identities = {m['id']:m['sourceGuid'] for m in packed['meshes']}
    for i, (before, frame, pose) in enumerate(zip(a['frames'],b['frames'],POSES)):
        file = root/f'{i}.png';raw = file.read_bytes();name=f'source-texture-render/{i}.png'
        require(not file.is_symlink() and frame['index'] == i and tuple(frame['pose']) == pose and frame['artifact'] == name
                and len(raw) == frame['artifactBytes'] and hashlib.sha256(raw).hexdigest() == frame['artifactSha256'] == before['artifactSha256']
                and raw == (save_artifacts/'atlas-capture'/name).read_bytes(), 'exact saved and reopened native pixels')
        require(frame['waitReason'] == 'CONSUMED' and frame['historyBefore'] == frame['historyAfter'] and frame['stateBeforeSha256'] == frame['stateAfterSha256'] == digest
                and frame['evaluationBefore'] == frame['evaluationAfter'], 'read-only fresh capture')
        require(len(frame['requested']) == len(frame['lastObservation']) == len(frame['evaluationBefore']) == 98
                and {v['artMeshId']:v['sourceGuid'] for v in frame['lastObservation']} == identities, 'complete chart consumption')
        for requested, observed in zip(frame['requested'],frame['lastObservation']):
            require(requested['artMeshId'] == observed['artMeshId'] and requested['sourceGuid'] == observed['sourceGuid']
                    and requested['live']['values'] == observed['live']['values'] == observed['consumed']['values']
                    and requested['consumed']['version'] != observed['consumed']['version'], 'fresh parameter consumption')
            values={v[1]:v[2] for v in observed['live']['values']}
            require(tuple(values[axis] for axis in b['axes']) == pose, 'exact joint pose')
    require(all(b['entryHistory'][k] == b['restoredHistory'][k] for k in ('availability','position','generation','document','manager')), 'history restoration')
    return dict(status='APPLE_PACKED_SAVE_REOPEN_LAYOUT_AND_ELEVEN_PIXELS_CHECKED',saved=content['saved'],persistenceAccepted=True,savedReopenedPixelsExact=True,goalComplete=False)
