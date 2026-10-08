#!/usr/bin/env python3
"""Synthetic save/reopen content mutations using accepted surface import evidence."""
import argparse,copy,json,runpy
from pathlib import Path
V=runpy.run_path(str(Path(__file__).with_name('verify-surface-reopen.py')))
p=argparse.ArgumentParser();p.add_argument('--save-artifacts',type=Path,required=True);a=p.parse_args()
saved=json.loads((a.save_artifacts/'report.json').read_text());run='queue-'+'a'*32
r={'schemaVersion':'mi3d-surface-menu-reopen-v1','scope':'OBSERVE_REOPENED_SURFACE_SEED_ONLY','status':'PASS','runId':run,
   'textureLayout':saved['textureLayout'],'inputSha256':saved['inputSha256'],'targetDocumentId':'synthetic-new-document',
   'saveReopen':'REOPENED_SURFACE_SEED_CAPTURED_REQUIRES_PAIR_CHECK','reopened':saved['imported'],
   'afterReopenCapture':saved['imported'],'sourceTextureRender':saved['sourceTextureRender'],'renderVerification':saved['renderVerification']}
reader=lambda name:(a.save_artifacts/name).read_bytes()
assert V['verify'](r,saved,a.save_artifacts/'authorized-surface.glb',run,reader)['persistenceAccepted'] is True
checks=0
def reject(changed):
 global checks
 try:V['verify'](changed,saved,a.save_artifacts/'authorized-surface.glb',run,reader)
 except (ValueError,KeyError):checks+=1;return
 raise AssertionError('corruption accepted')
for key,value in [('schemaVersion','mi3d-surface-menu-v1'),('scope','REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER'),('status','FAIL'),
                  ('inputSha256','0'*64),('targetDocumentId',saved['targetDocumentId']),('runId',saved['runId']),('saveReopen','NOT_RUN'),
                  ('persistenceSave',{}),('imported',{}),('menuInvoked',True),('afterReopenCapture',{})]:
 changed=copy.deepcopy(r);changed[key]=value;reject(changed)
changed=copy.deepcopy(r);changed['reopened']['meshes'][0]['forms'][0]['positions'][0][0]+=1;changed['afterReopenCapture']=changed['reopened'];reject(changed)
changed=copy.deepcopy(r);changed['sourceTextureRender']['frames'][0]['artifactSha256']='0'*64;reject(changed)
changed=copy.deepcopy(r);changed['sourceTextureRender']['frames'][0]['historyAfter']['revision']+=1;reject(changed)
changed=copy.deepcopy(r);changed['sourceTextureRender']['restored']=False;reject(changed)
print(json.dumps({'status':'SYNTHETIC_SURFACE_REOPEN_REFUSALS_PASS','refusals':checks,'nativeExecuted':False}))
