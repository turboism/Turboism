#!/usr/bin/env python3
"""Audit the actual production-menu import against frozen original-face planes."""
import hashlib,json,runpy,struct,sys
from pathlib import Path
PAIR=runpy.run_path(str(Path(__file__).with_name('analyze-derived-persistence.py')))

def analyze(root,reopen_root=None):
 root=Path(root);job,report=PAIR['terminal'](root)
 assert report['schemaVersion']=='mi3d-apple-xyz360-v1'
 assert report['inputSha256']=='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
 assert report['undoRestored'] and report['redoRestored'] and report['framesCaptured']
 bank=report['imported'];meshes={m['id']:m for m in bank['meshes']}
 assert len(meshes)==len(bank['meshes'])==98 and sum(m['vertexCount'] for m in meshes.values())==80514
 assert sum(m['triangleCount'] for m in meshes.values())==150784
 assert len(bank['warps'])==1 and bank['warps'][0]['formCount']==2401
 charts=PAIR['EDIT']['fixture'](PAIR['FIXTURE'].with_name('derived-bound-all98-aligned-v4.bin'))
 reciprocal=struct.unpack('>f',struct.pack('>f',1/576))[0]
 for i,c in enumerate(charts):
  mesh=meshes[f'ArtMesh3D{i}']
  assert mesh['vertexCount']==c['n'] and mesh['triangleCount']==len(c['indices'])//3
  assert mesh['axisCount']==2 and mesh['formCount']==mesh['cellCount']==len(mesh['forms'])==49
  h=hashlib.sha256()
  for p in c['positions']:h.update(struct.pack('>ff',*p))
  for x,y in c['positions']:h.update(struct.pack('>ff',x*reciprocal,y*reciprocal))
  for index in c['indices']:h.update(struct.pack('>i',index))
  assert mesh['geometrySha256']==h.hexdigest(), 'menu source differs from original-face fixture'
 PAIR['frames'](root,report,meshes)
 saved=report['persistenceSave'];assert saved['historyUnchanged'] and saved['before']==saved['after']==bank
 file=PAIR['read_artifact'](root,saved['artifact'])
 assert file.stat().st_size==saved['artifactBytes']<=256*1024*1024 and PAIR['digest'](file)==saved['artifactSha256']
 result=dict(status='PASS_REAL_PRODUCTION_MENU_ORIGINAL_FACES_XYZ_UNDO_REDO_SAVE_ONLY',jobId=job['job_id'],
  runId=job['run_id'],charts=98,slots=80514,originalFaces=150784,frames=12,normalExit=True,
  artifact=dict(artifact=saved['artifact'],bytes=saved['artifactBytes'],sha256=saved['artifactSha256']),
  productionMenuAccepted=True,menuReopenAccepted=False,fullAppleAccepted=False)
 if reopen_root is not None:
  reopen_root=Path(reopen_root);second,reopened=PAIR['terminal'](reopen_root)
  assert second['run_id']!=job['run_id'] and reopened['schemaVersion']=='mi3d-apple-derived-reopen-v1'
  assert reopened['sourceReadHistoryUnchanged'] and reopened['sourceUnchanged'] and reopened['historyPositionRestored']
  after=reopened['reopenedState'];assert bank.keys()==after.keys()
  for key in bank:
   if key in ('meshes','meshMetadata'):
    before_rows={r['id']:r for r in bank[key]};after_rows={r['id']:r for r in after[key]}
    assert len(before_rows)==len(bank[key]) and len(after_rows)==len(after[key]) and before_rows==after_rows
   else:assert bank[key]==after[key],key
  PAIR['frames'](reopen_root,reopened,meshes)
  assert reopened['axes']==report['axes']
  for a,b in zip(report['frames'],reopened['frames']):
   assert a['poseXYZ']==b['poseXYZ'] and a['sha256']==b['sha256']
   assert {r['id']:r for r in a['evaluated']}=={r['id']:r for r in b['evaluated']}
  result.update(status='PASS_REAL_PRODUCTION_MENU_ORIGINAL_FACES_XYZ_SAVE_REOPEN',menuReopenAccepted=True,
   reopenJobId=second['job_id'],reopenRunId=second['run_id'],all12FramePngsIdentical=True)
 (reopen_root or root).joinpath('independent-menu-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

if __name__=='__main__':analyze(*sys.argv[1:])
