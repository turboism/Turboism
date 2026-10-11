#!/usr/bin/env python3
"""Exact saved surface authoring and fresh render pair; process admitted separately."""
import runpy
from pathlib import Path
BASE=runpy.run_path(str(Path(__file__).with_name('verify-surface-menu.py')))
require=BASE['require']

def verify(r,saved,glb,run,reader):
 require(r['schemaVersion']=='mi3d-surface-menu-reopen-v1' and r['scope']=='OBSERVE_REOPENED_SURFACE_SEED_ONLY'
         and r['runId']==run and r['status']=='PASS' and 'failure' not in r,'explicit successful reopen scope')
 require(r['textureLayout']=='EDGE_PADDED_POT' and r['inputSha256']==saved['inputSha256'],'reopened source identity')
 require(r['saveReopen']=='REOPENED_SURFACE_SEED_CAPTURED_REQUIRES_PAIR_CHECK','explicit reopened seed')
 require('persistenceSave' not in r and 'imported' not in r and 'menuInvoked' not in r,'observe-only reopen')
 require(saved['runId']!=run and saved['targetDocumentId']!=r['targetDocumentId'],'fresh process/document capture')
 BASE['verify_ui'](saved,glb,saved['runId'])
 require(r['reopened']==r['afterReopenCapture']==saved['persistenceSave']['before']==saved['persistenceSave']['after']==saved['imported'],
         'all persisted mesh/form/source/UV/resource/parameter/canvas identities exact')
 root=r['sourceTextureRender']['entryHistory']
 pixels=BASE['verify_render'](r,glb,reader,r['reopened'],root['position'])
 previous=saved['sourceTextureRender']['frames'];current=r['sourceTextureRender']['frames']
 require(len(previous)==len(current)==9 and all(a['pose']==b['pose'] and a['artifactBytes']==b['artifactBytes']
         and a['artifactSha256']==b['artifactSha256'] for a,b in zip(previous,current)), 'nine PNGs exact across save/new-process reopen')
 return dict(status='EXACT_SURFACE_SAVE_REOPEN_STATE_AND_NINE_NATIVE_RASTERS',pixels=pixels,persistenceAccepted=True,officialExportAccepted=False,goalComplete=False)
