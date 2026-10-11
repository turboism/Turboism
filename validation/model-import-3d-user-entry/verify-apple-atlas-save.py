#!/usr/bin/env python3
"""Verify actual native repack and typed packed SAVE_AS, separate from fresh reopen."""
import hashlib
import json
from pathlib import Path


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_ATLAS_SAVE: ' + why)


def verify(r, artifacts):
    e = r['officialExport']; repack = e['repack']; packed = json.loads(e['packedStateJson'])
    require(repack['undoExact'] is True and repack['redoExact'] is True, 'exact native Undo/Redo')
    require(repack['after'] == packed and repack['before'] != packed, 'actual repack authored change')
    for field in ('deformers', 'parameters', 'parts', 'rawImages', 'imageGroups'):
        require(repack['before'][field] == packed[field], 'protected repack ' + field)
    old_metadata=repack['before']['meshMetadata'];new_metadata=packed['meshMetadata']
    require(len(old_metadata)==len(new_metadata)==98 and len({m['input']['atlas'] for m in old_metadata})==1, 'complete original atlas binding')
    for old,new in zip(old_metadata,new_metadata):
        require({k:v for k,v in old.items() if k!='input'}=={k:v for k,v in new.items() if k!='input'}
                and old['input']['kind']==new['input']['kind']=='TEXTURE_ATLAS_REGION'
                and old['input']['image']==new['input']['image']==''
                and new['input']['atlas']==e['atlases'][0][0], 'recreated native atlas binding and protected metadata')
    require(len(repack['before']['meshes']) == len(packed['meshes']) == 98, 'complete repack inventory')
    for before, after in zip(repack['before']['meshes'], packed['meshes']):
        for field in ('id','sourceGuid','vertexCount','triangleCount','axisCount','formCount','cellCount','axes','forms','localSha256'):
            require(before[field] == after[field], 'protected repack mesh ' + field)
    h0, h1 = repack['historyBefore'], repack['historyAfter']
    require(h1['position'] == h0['position'] + 1 and all(h0[k] == h1[k] for k in ('availability','generation','document','manager')), 'one native repack entry')
    a, b = repack['layoutBefore'], repack['layoutAfter']
    require(b == e['packedAtlasLayout'] and a != b and a['atlasId'] == b['atlasId'] == e['atlases'][0][1], 'same logical atlas, different placements')
    require(a['pageWidth'] == a['pageHeight'] == b['pageWidth'] == b['pageHeight'] == 2048 and a['pageCount'] == b['pageCount'] == 1, 'bounded one page')
    require(len(a['placements']) == len(b['placements']) == 1, 'complete one source image')
    p, q = a['placements'][0], b['placements'][0]
    require(p[0] == q[0] == packed['imageGroups'][0]['images'][0][0] and p[1] == q[1] == 0 and p[4:] == q[4:] and p[6] is False, 'no rotation or scaling')
    require(q[2] == 2048 - p[2] - p[4] and q[3] == 2048 - p[3] - p[5], 'actual reflected placement')
    s = e['packedPersistenceSave']
    require(s['commandStatus'] == 'EXECUTED' and s['maximumBytes'] == 256*1024*1024, 'typed bounded save')
    require(s['before'] == s['after'] == packed and s['historyBefore'] == s['historyAfter'] == e['atlasCapture']['sourceTextureRender']['restoredHistory'], 'packed save state and history exact')
    require(s['artifact'] == 'persistence/mi3d-apple-model.cmo3', 'fixed task-owned path')
    file = artifacts / s['artifact']
    require(not any(p.is_symlink() for p in (file,*file.parents)) and file.is_file() and 0 < file.stat().st_size == s['artifactBytes'] <= s['maximumBytes'], 'bounded regular saved file')
    digest = hashlib.sha256()
    with file.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):
            digest.update(chunk)
    require(digest.hexdigest() == s['artifactSha256'], 'actual saved CMO3 identity')
    return dict(status='APPLE_REPACK_UNDO_REDO_PACKED_SAVE_CONTENT_CHECKED_REOPEN_PENDING', saved=dict(artifact=s['artifact'],bytes=s['artifactBytes'],sha256=s['artifactSha256']), persistenceAccepted=False,goalComplete=False)
