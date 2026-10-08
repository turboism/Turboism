#!/usr/bin/env python3
"""Synthetic refusal cases; never a native acceptance result."""
import copy
import hashlib
import json
import runpy
import sys
import tempfile
from pathlib import Path

CHECK = runpy.run_path(str(Path(__file__).with_name('verify-apple-atlas-save.py')))['verify']
report = json.loads(Path(sys.argv[1]).read_text())
with tempfile.TemporaryDirectory(prefix='apple-atlas-save-') as name:
    root = Path(name); (root/'persistence').mkdir(); raw = b'SYNTHETIC_NOT_A_CMO3'
    (root/'persistence/mi3d-apple-model.cmo3').write_bytes(raw)
    e=report['officialExport']; packed=json.loads(e['packedStateJson']); before=copy.deepcopy(packed)
    before['meshes'][0]['geometrySha256']='0'*64
    h=copy.deepcopy(e['atlasCapture']['sourceTextureRender']['restoredHistory']); h0=copy.deepcopy(h);h0['position']-=1
    layout=dict(atlasId=e['atlases'][0][1],pageWidth=2048,pageHeight=2048,pageCount=1,placements=[[packed['imageGroups'][0]['images'][0][0],0,2,2,1024,256,False]])
    reflected=copy.deepcopy(layout);reflected['placements'][0][2:4]=[1022,1790]
    e['repack']=dict(before=before,after=packed,layoutBefore=layout,layoutAfter=reflected,historyBefore=h0,historyAfter=h,undoExact=True,redoExact=True)
    e['packedAtlasLayout']=reflected
    e['packedPersistenceSave']=dict(commandStatus='EXECUTED',artifact='persistence/mi3d-apple-model.cmo3',artifactBytes=len(raw),artifactSha256=hashlib.sha256(raw).hexdigest(),maximumBytes=256*1024*1024,before=packed,after=packed,historyBefore=h,historyAfter=h)
    CHECK(report,root)
    cases=[('undo',lambda e:e['repack'].update(undoExact=False)),('redo',lambda e:e['repack'].update(redoExact=False)),
        ('no-repack',lambda e:e['repack'].update(before=e['repack']['after'])),
        ('protected-control',lambda e:e['repack']['before']['deformers'][0].update(controlsSha256='0'*64)),
        ('layer-keys',lambda e:e['repack']['before']['meshes'][0]['axes'][0]['keys'].pop()),
        ('wrong-atlas-binding',lambda e:e['repack']['before']['meshMetadata'][0]['input'].update(atlas='wrong')),
        ('lost-mesh',lambda e:e['repack']['before']['meshes'].pop()),
        ('extra-history',lambda e:e['repack']['historyBefore'].update(position=-20)),
        ('wrong-layout',lambda e:e['packedAtlasLayout'].update(pageWidth=4096)),
        ('image-rotation',lambda e:e['repack']['layoutBefore']['placements'][0].__setitem__(6,True)),
        ('wrong-reflection',lambda e:e['repack']['layoutBefore']['placements'][0].__setitem__(2,4)),
        ('save-refused',lambda e:e['packedPersistenceSave'].update(commandStatus='REJECTED')),
        ('save-state',lambda e:e['packedPersistenceSave']['after'].update(parts=[])),
        ('save-history',lambda e:e['packedPersistenceSave']['historyBefore'].update(position=-1)),
        ('outside-path',lambda e:e['packedPersistenceSave'].update(artifact='../elsewhere.cmo3')),
        ('oversize',lambda e:e['packedPersistenceSave'].update(artifactBytes=256*1024*1024+1)),
        ('wrong-bytes',lambda e:e['packedPersistenceSave'].update(artifactSha256='0'*64))]
    # Decouple shared synthetic maps before each mutation.
    base=json.loads(json.dumps(report))
    for name,mutate in cases:
        changed=copy.deepcopy(base);mutate(changed['officialExport'])
        try:CHECK(changed,root)
        except (ValueError,KeyError,IndexError):continue
        raise AssertionError('accepted corruption: '+name)
    print('PASS',len(cases),'synthetic corruption refusals; NOT_NATIVE_ACCEPTANCE')
