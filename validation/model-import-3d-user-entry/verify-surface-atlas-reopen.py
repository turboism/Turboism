#!/usr/bin/env python3
"""Exact packed surface CMO3 new-process authoring/layout/render pair."""
import json,runpy
from pathlib import Path
BASE=runpy.run_path(str(Path(__file__).with_name('verify-surface-menu.py')))
require=BASE['require']
def verify(r,saved,glb,run,reader):
 require(r['schemaVersion']=='mi3d-surface-menu-atlas-reopen-v1' and r['scope']=='OBSERVE_REOPENED_PACKED_SURFACE_SEED_ONLY'
         and r['status']=='PASS' and r['runId']==run and 'failure' not in r,'explicit successful packed reopen scope')
 require(r['textureLayout']=='EDGE_PADDED_POT' and r['inputSha256']==saved['inputSha256']
         and r['saveReopen']=='REOPENED_PACKED_SURFACE_SEED_CAPTURED_REQUIRES_PAIR_CHECK','exact packed source identity')
 require(not any(k in r for k in ('persistenceSave','officialExport','imported','menuInvoked')),'observe-only packed reopen')
 # Native document IDs are process-local and may repeat in fresh Editor processes.
 # The enclosing job gate independently requires distinct job/run/attempt identities.
 require(saved['runId']!=run and isinstance(r['targetDocumentId'],str) and bool(r['targetDocumentId']),
         'distinct process run and nonempty document report')
 e=saved['officialExport'];packed=json.loads(e['packedStateJson'])
 require(r['reopened']==r['afterReopenCapture']==e['packedPersistenceSave']['before']==e['packedPersistenceSave']['after']==packed,
         'all saved mesh/forms/UV/indices/images/atlas/parameters/canvas identities exact')
 require(r['reopenedAtlasLayout']==e['packedAtlasLayout']==e['repack']['layoutAfter'],'complete persisted logical atlas placement')
 section=r['sourceTextureRender'];root=section['entryHistory']
 pixels=BASE['verify_render'](r,glb,reader,packed,root['position'])
 old=e['atlasCapture']['sourceTextureRender']['frames'];new=section['frames']
 require(len(old)==len(new)==9 and all(a['pose']==b['pose'] and a['artifactBytes']==b['artifactBytes'] and a['artifactSha256']==b['artifactSha256'] for a,b in zip(old,new)),
         'all nine packed save/new-process reopen PNGs byte-identical')
 return dict(status='EXACT_PACKED_SURFACE_SAVE_NEW_PROCESS_REOPEN_STATE_LAYOUT_NINE_RASTERS',pixels=pixels,packedPersistenceAccepted=True,officialExportAccepted=False,goalComplete=False)
