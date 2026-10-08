#!/usr/bin/env python3
"""Synthetic surface SAVE_AS receipt corruptions; no native acceptance claim."""
import copy,hashlib,json,runpy,tempfile
from pathlib import Path
V=runpy.run_path(str(Path(__file__).with_name('verify-surface-save.py')))
with tempfile.TemporaryDirectory(prefix='mi3d-surface-save-') as tmp:
 root=Path(tmp);parent=root/'persistence';parent.mkdir();file=parent/'mi3d-surface-model.cmo3'
 raw=b'synthetic SAVE_AS output; not a native model';file.write_bytes(raw)
 state={'parameters':[['Yaw',-30,0,30,0]],'meshes':[{'id':'source'}]};history={'position':2,'revision':18,'entries':['baseline','import']}
 r={'schemaVersion':'mi3d-surface-menu-v1','scope':'REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER','saveReopen':'SAVED_REOPEN_NOT_RUN',
    'imported':state,'sourceTextureRender':{'restoredHistory':history},'persistenceSave':{
    'commandStatus':'EXECUTED','maximumBytes':64*1024*1024,'before':state,'after':state,'historyUnchanged':True,
    'historyBefore':history,'historyAfter':history,'artifact':'persistence/mi3d-surface-model.cmo3',
    'artifactBytes':len(raw),'artifactSha256':hashlib.sha256(raw).hexdigest()}}
 assert V['verify_save'](r,root)['bytes']==len(raw)
 checks=0
 def reject(value):
  global checks
  try:V['verify_save'](value,root)
  except (ValueError,KeyError,FileNotFoundError):checks+=1;return
  raise AssertionError('corruption accepted')
 for key,value in [('commandStatus','FAILED'),('maximumBytes',256*1024*1024),('before',{}),('after',{}),('historyUnchanged',False),
                   ('historyBefore',{}),('historyAfter',{}),('artifact','persistence/mi3d-apple-model.cmo3'),('artifact','../mi3d-surface-model.cmo3'),
                   ('artifactBytes',len(raw)+1),('artifactSha256','0'*64)]:
  changed=copy.deepcopy(r);changed['persistenceSave'][key]=value;reject(changed)
 for key,value in [('schemaVersion','mi3d-apple-menu-save-v1'),('scope','REAL_MENU_IMPORT_SAVE_ONLY'),('saveReopen','NOT_RUN')]:
  changed=copy.deepcopy(r);changed[key]=value;reject(changed)
 file.write_bytes(raw+b'changed');reject(r);file.unlink()
 target=root/'elsewhere';target.write_bytes(raw);file.symlink_to(target);reject(r);file.unlink()
 with file.open('wb') as stream:stream.truncate(64*1024*1024+1)
 reject(r)
 print(json.dumps({'status':'SYNTHETIC_SURFACE_SAVE_REFUSALS_PASS','refusals':checks,'nativeExecuted':False}))
