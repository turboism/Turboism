#!/usr/bin/env python3
"""Synthetic reopen corruption refusals from captured native save data; not a process gate."""
import copy
import json
from pathlib import Path
import runpy
import shutil
import sys
import tempfile

artifacts=Path(sys.argv[1]);saved=json.loads((artifacts/'report.json').read_text());e=saved['officialExport'];packed=json.loads(e['packedStateJson'])
check=runpy.run_path(str(Path(__file__).with_name('verify-apple-atlas-reopen.py')))['verify'];run='queue-'+'1'*32
reopened=dict(schemaVersion='mi3d-apple-menu-atlas-reopen-v1',scope='OBSERVE_REOPENED_PACKED_SEED_ONLY',runId=run,status='PASS',reopened=packed,afterReopenCapture=packed,reopenedAtlasLayout=e['packedAtlasLayout'],reopenedAtlases=e['atlases'],sourceTextureRender=e['atlasCapture']['sourceTextureRender'])
base=json.loads(json.dumps(reopened))
with tempfile.TemporaryDirectory(prefix='apple-atlas-reopen-') as name:
    root=Path(name);shutil.copytree(artifacts/'atlas-capture/source-texture-render',root/'source-texture-render')
    check(saved,base,saved['runId'],run,artifacts,root)
    cases=[('wrong-scope',lambda r:r.update(scope='OBSERVE_REOPENED_SEED_ONLY')),
        ('lost-binding',lambda r:r['reopened']['meshes'][0]['axes'][0]['keys'].pop()),
        ('wrong-control',lambda r:r['reopened']['deformers'][0].update(controlsSha256='0'*64)),
        ('wrong-UV',lambda r:r['reopened']['meshes'][0].update(geometrySha256='0'*64)),
        ('wrong-atlas-placement',lambda r:r['reopenedAtlasLayout']['placements'][0].__setitem__(2,2)),
        ('wrong-atlas-GUID',lambda r:r['reopenedAtlases'][0].__setitem__(0,'wrong')),
        ('wrong-pose',lambda r:r['sourceTextureRender']['frames'][0]['pose'].__setitem__(0,5)),
        ('stale-consumption',lambda r:r['sourceTextureRender']['frames'][0].update(waitReason='TIMEOUT'))]
    for name,mutate in cases:
        changed=copy.deepcopy(base);mutate(changed)
        try:check(saved,changed,saved['runId'],run,artifacts,root)
        except (ValueError,KeyError):continue
        raise AssertionError('accepted corruption: '+name)
    file=root/'source-texture-render/0.png';raw=file.read_bytes();file.write_bytes(raw[:-1])
    try:check(saved,base,saved['runId'],run,artifacts,root)
    except ValueError:pass
    else:raise AssertionError('accepted altered native pixels')
    print('PASS9 synthetic packed reopen corruptions; NOT_NATIVE_ACCEPTANCE')
