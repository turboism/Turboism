#!/usr/bin/env python3
"""Bind official exporter bytes to the proven apple seed; Core/process gates remain separate."""
import argparse
import hashlib
import json
import runpy
from pathlib import Path
import numpy as np
from PIL import Image

PAIR = runpy.run_path(str(Path(__file__).with_name('verify-apple-reopen.py')))


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_EXPORT: ' + why)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def verify(saved, r, save_run, run, prepared, save_artifacts, artifacts):
    require(r['schemaVersion'] == 'mi3d-apple-menu-export-v1'
            and r['scope'] == 'OFFICIAL_ATLAS_MOC_CAPTURE_FROM_REOPENED_SEED'
            and r['status'] == 'PASS' and 'failure' not in r, 'explicit official export scope')
    seed = PAIR['verify'](saved, dict(r, schemaVersion='mi3d-apple-menu-reopen-v1',
         scope='OBSERVE_REOPENED_SEED_ONLY'), save_run, run, prepared, save_artifacts, artifacts)
    section = verify_export_section(r, artifacts)
    return dict(status='APPLE_NATIVE_ATLAS_EXPORT_CONTENT_CHECKED_NOT_PROCESS_OR_CORE_ACCEPTANCE',
                seed=seed, **section, officialExportAccepted=False, goalComplete=False)


def verify_export_section(r, artifacts):
    e = r['officialExport']
    require(e['schemaVersion'] == 'mi3d-apple-official-export-v1'
            and e['status'] == 'CAPTURED_REQUIRES_OFFICIAL_CORE_VERIFICATION'
            and e['sourceRestored'] is True and 'failure' not in e, 'native exporter and source restoration')
    require(sha(e['beforeAtlasStateJson'].encode()) == e['beforeAtlasSha256']
            and json.loads(e['beforeAtlasStateJson']) == r['reopened'], 'exact accepted source before atlas')
    packed = json.loads(e['packedStateJson'])
    digest = sha(e['packedStateJson'].encode())
    require(e['beforeExportSha256'] == e['afterExportSha256'] == digest
            and e['historyBeforeExport'] == e['historyAfterExport'], 'export read-only protections')
    require(e['settings'] == [512, 512, 100, False, False, False, False, False], 'fixed coordinate/export settings')
    for field in ('deformers', 'parameters', 'parts', 'rawImages', 'imageGroups'):
        require(packed[field] == r['reopened'][field], 'atlas protected ' + field)
    require(len(e['atlases']) == 1 and e['atlases'][0][2:] == [2048, 2048, 1], 'one complete native atlas')
    atlas_id = e['atlases'][0][0]
    require(len(packed['meshes']) == len(e['packedSource']) == 98, 'complete packed chart inventory')
    require({p.name for p in (artifacts/'packed-source').iterdir()} == {f'{i}.bin' for i in range(98)}, 'exact packed source namespace')
    source = {m['id']: m for m in r['reopened']['meshes']}
    for i, (mesh, row) in enumerate(zip(packed['meshes'], e['packedSource'])):
        old = source[mesh['id']]
        for field in ('sourceGuid', 'vertexCount', 'triangleCount', 'axisCount', 'formCount', 'cellCount', 'axes', 'forms', 'localSha256'):
            require(mesh[field] == old[field], 'protected packed mesh ' + field)
        name = f'packed-source/{i}.bin'
        file = artifacts/name
        require(not file.is_symlink() and row['id'] == mesh['id'] and row['artifact'] == name, 'packed geometry identity')
        raw = file.read_bytes()
        require(len(raw) == row['bytes'] == mesh['vertexCount']*16+mesh['triangleCount']*12
                and sha(raw) == row['sha256'] == mesh['geometrySha256'], 'complete source geometry/UV/index bytes')
        floats = np.frombuffer(raw[:mesh['vertexCount']*16], dtype='>f4')
        require(np.all(np.isfinite(floats)), 'finite packed source')
    require(len(packed['meshMetadata']) == 98 and all(m['culling'] is True and m['visible'] is True
            and m['input']['kind'] == 'TEXTURE_ATLAS_REGION' and m['input']['atlas'] == atlas_id
            for m in packed['meshMetadata']), 'native atlas input binding')
    capture = e['atlasCapture']['sourceTextureRender']
    require(capture['status'] == 'CAPTURED' and capture['restored'] is True
            and capture['axes'] == r['sourceTextureRender']['axes'], 'atlas capture and axes')
    expected = dict(packed, parameters=[p[:4] for p in packed['parameters']])
    require(json.loads(capture['protectedStateJson']) == expected
            and sha(capture['protectedStateJson'].encode()) == capture['protectedStateSha256'], 'full packed capture protections')
    require(len(capture['frames']) == 11, 'all eleven atlas poses')
    root = artifacts/'atlas-capture/source-texture-render'
    require({p.name for p in root.iterdir()} == {f'{i}.png' for i in range(11)}, 'exact atlas PNG namespace')
    for i, (frame, pose) in enumerate(zip(capture['frames'], PAIR['POSES'])):
        raw = (root/f'{i}.png').read_bytes()
        require(frame['index'] == i and tuple(frame['pose']) == pose
                and frame['artifact'] == f'source-texture-render/{i}.png'
                and len(raw) == frame['artifactBytes'] and sha(raw) == frame['artifactSha256']
                and frame['waitReason'] == 'CONSUMED', 'fresh atlas pose/PNG identity')
        require(frame['historyBefore'] == frame['historyAfter']
                and frame['stateBeforeSha256'] == frame['stateAfterSha256'] == capture['protectedStateSha256']
                and frame['evaluationBefore'] == frame['evaluationAfter'], 'atlas render protections')
        require(len(frame['requested']) == len(frame['lastObservation']) == len(frame['evaluationBefore']) == 98, 'full atlas consumption')
        for request, actual in zip(frame['requested'], frame['lastObservation']):
            require(request['artMeshId'] == actual['artMeshId'] and request['sourceGuid'] == actual['sourceGuid']
                    and request['live']['values'] == actual['live']['values'] == actual['consumed']['values']
                    and request['consumed']['version'] != actual['consumed']['version'], 'fresh native atlas consumption')
    moc = e['moc'];file = artifacts/'official-export/model.moc3'
    require(moc['artifact'] == 'official-export/model.moc3' and not file.is_symlink()
            and 64 <= file.stat().st_size == moc['bytes'] <= 128*1024*1024, 'bounded official MOC')
    raw = file.read_bytes();require(raw[:4] == b'MOC3' and sha(raw) == moc['sha256'], 'actual official MOC bytes')
    textures = e['textures'];require(len(textures) == 1, 'single official texture')
    t = textures[0];file = artifacts/'official-export/texture-0.png';raw = file.read_bytes()
    require(not file.is_symlink() and t['index'] == 0 and t['artifact'] == 'official-export/texture-0.png'
            and t['width'] == t['height'] == 2048 and len(raw) == t['bytes'] and sha(raw) == t['sha256'], 'official texture identity')
    with Image.open(file) as image:
        require(image.size == (2048, 2048), 'actual official texture dimensions');image.load()
    return dict(moc=moc, textures=textures)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('save-report', 'report', 'prepared', 'save-artifacts', 'artifacts', 'out'):
        p.add_argument('--' + name, type=Path, required=True)
    for name in ('save-run', 'run'):
        p.add_argument('--' + name, required=True)
    a = p.parse_args()
    def read(file):
        require(not file.is_symlink() and file.stat().st_size <= 64*1024*1024, 'bounded report')
        def pairs(items):
            result = {}
            for key, value in items:
                require(key not in result, 'duplicate JSON key');result[key] = value
            return result
        return json.loads(file.read_bytes(), object_pairs_hook=pairs,
                          parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
    result = verify(read(a.save_report), read(a.report), a.save_run, a.run,
                    a.prepared, a.save_artifacts, a.artifacts)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2);stream.write('\n')
    print(result['status'])
