#!/usr/bin/env python3
"""Exact save/reopen content and pixel pair; official lifecycle is a separate gate."""
import argparse
import hashlib
import json
import runpy
from pathlib import Path

COMPACT = runpy.run_path(str(Path(__file__).with_name('verify-apple-save-compact.py')))
SAVE = runpy.run_path(str(Path(__file__).with_name('verify-apple-save.py')))
POSES = SAVE['BASE']['POSES']


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_REOPEN: ' + why)


def verify(saved, reopened, save_run, reopen_run, prepared, save_artifacts, reopen_artifacts):
    require(save_run != reopen_run, 'distinct processes')
    checker=COMPACT if saved.get('schemaVersion')=='mi3d-apple-menu-save-compact-v1' else SAVE
    content = checker['verify'](saved, save_run, prepared, save_artifacts)
    require(reopened['schemaVersion'] == 'mi3d-apple-menu-reopen-v1'
            and reopened['scope'] == 'OBSERVE_REOPENED_SEED_ONLY'
            and reopened['runId'] == reopen_run and reopened['status'] == 'PASS'
            and 'failure' not in reopened, 'reopen report identity/scope/status')
    require(reopened['saveReopen'] == 'REOPENED_SEED_CAPTURED_REQUIRES_PAIR_CHECK'
            and reopened['reopened'] == reopened['afterReopenCapture'] == saved['imported'],
            'complete durable source/local/forms/orders/controls/resources/parameters')
    render = reopened['sourceTextureRender']
    require(render['schemaVersion'] == 'mi3d-apple-texture-render-v1'
            and render['status'] == 'CAPTURED' and render['restored'] is True
            and render['axes'] == saved['sourceTextureRender']['axes'], 'reopen capture scope')
    protected = dict(saved['imported'])
    protected['parameters'] = [p[:4] for p in protected['parameters']]
    digest = hashlib.sha256(render['protectedStateJson'].encode()).hexdigest()
    require(json.loads(render['protectedStateJson']) == protected
            and digest == render['protectedStateSha256'], 'protected durable state')
    frames = render['frames']
    root = reopen_artifacts / 'source-texture-render'
    require(len(frames) == 11 and {p.name for p in root.iterdir()} == {f'{i}.png' for i in range(11)},
            'complete reopen frame namespace')
    identities = {m['id']: m['sourceGuid'] for m in saved['imported']['meshes']}
    for i, (frame, pose) in enumerate(zip(frames, POSES)):
        name = f'source-texture-render/{i}.png'
        file = reopen_artifacts / name
        require(not file.is_symlink(), 'regular reopen frame')
        raw = file.read_bytes()
        require(frame['index'] == i and tuple(frame['pose']) == pose and frame['artifact'] == name
                and len(raw) == frame['artifactBytes']
                and hashlib.sha256(raw).hexdigest() == frame['artifactSha256']
                and raw == (save_artifacts / name).read_bytes(), 'exact saved/reopened pose pixels')
        require(frame['waitReason'] == 'CONSUMED' and frame['historyBefore'] == frame['historyAfter']
                and frame['stateBeforeSha256'] == frame['stateAfterSha256'] == digest
                and frame['evaluationBefore'] == frame['evaluationAfter'], 'capture protections')
        require(len(frame['requested']) == len(frame['lastObservation']) == len(frame['evaluationBefore']) == 98,
                'full evaluated chart inventory')
        require({o['artMeshId']: o['sourceGuid'] for o in frame['lastObservation']} == identities,
                'complete reopened evaluated identities')
        for request, actual in zip(frame['requested'], frame['lastObservation']):
            require(request['artMeshId'] == actual['artMeshId']
                    and request['sourceGuid'] == actual['sourceGuid']
                    and request['live']['values'] == actual['live']['values'] == actual['consumed']['values']
                    and request['consumed']['version'] != actual['consumed']['version'], 'fresh consumed pose')
            values = {v[1]: v[2] for v in actual['live']['values']}
            require(tuple(values[axis] for axis in render['axes']) == pose, 'exact reopened pose')
    before, after = render['entryHistory'], render['restoredHistory']
    require(all(before[k] == after[k] for k in ('availability', 'position', 'generation', 'document', 'manager')),
            'history target and position restored')
    return dict(status='APPLE_SAVE_REOPEN_CONTENT_AND_ELEVEN_PIXELS_CHECKED_NOT_PROCESS_ACCEPTANCE',
                saved=content, reopenRun=reopen_run, officialExportAccepted=False, goalComplete=False)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('save-report', 'reopen-report', 'prepared', 'save-artifacts', 'reopen-artifacts', 'out'):
        p.add_argument('--' + name, type=Path, required=True)
    for name in ('save-run', 'reopen-run'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    def read(file):
        require(not file.is_symlink() and file.stat().st_size <= 64 * 1024 * 1024, 'bounded report')
        def pairs(items):
            result = {}
            for key, value in items:
                require(key not in result, 'duplicate JSON key')
                result[key] = value
            return result
        return json.loads(file.read_bytes(), object_pairs_hook=pairs,
                          parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
    result = verify(read(a.save_report), read(a.reopen_report), a.save_run, a.reopen_run,
                    a.prepared, a.save_artifacts, a.reopen_artifacts)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['status'])
