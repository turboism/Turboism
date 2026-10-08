#!/usr/bin/env python3
"""Independent Cat menu lifecycle, inventory and consumed-pose observations."""
import json,math,runpy,sys
from pathlib import Path
from PIL import Image
PAIR=runpy.run_path(str(Path(__file__).with_name('analyze-derived-persistence.py')))
POSES=[[0,0,0],[-180,-180,-180],[180,180,180],[90,90,90],[35,-47,61],[-127,103,-155],
       [37.5,-112.5,23],[-150,75,-72],[-179.5,179.5,179.5]]

def analyze(root):
 root=Path(root);job,report=PAIR['terminal'](root)
 assert report['schemaVersion'] in ('mi3d-cat-vertex-menu-v1','mi3d-cat-vertex-frames-v1')
 history=report['schemaVersion']=='mi3d-cat-vertex-menu-v1'
 assert report['inputSha256']=='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
 if history:assert report['undoRestored'] and report['redoRestored']
 else:
  assert report['importHistoryProbe'] is False
  assert report['scope']=='REAL_MENU_SOURCE_VERTEX_IMPORT_NATIVE_FRAMES_ONLY_NO_IMPORT_UNDO_REDO_SAVE_EXPORT'
  assert report['importHistoryVerification']=='NOT_EXECUTED_SEPARATE_PERFORMANCE_WORK_REQUIRED'
  assert 'undoRestored' not in report and 'redoRestored' not in report
 bank=report['imported'];assert bank['meshes']==512 and bank['slots']==48497 and bank['triangles']==70576
 assert len(bank['sha256'])==64 and all(c in '0123456789abcdef' for c in bank['sha256'])
 assert len(report['frames'])==9
 inventory=None;source_guids=None;measurements=[]
 for i,(frame,pose) in enumerate(zip(report['frames'],POSES)):
  assert frame['XYZ']==pose and frame['artifact']==f'cat-frames/{i}.png'
  path=PAIR['read_artifact'](root,frame['artifact']);assert PAIR['digest'](path)==frame['sha256']
  with Image.open(path) as image:
   assert image.format=='PNG' and image.size==(1024,1024);image.load()
   bbox=image.convert('RGBA').getchannel('A').getbbox();assert bbox is not None,'blank Cat output'
  rows=frame['evaluated'];ids={r['artMeshId'] for r in rows};guids={r['sourceGuid'] for r in rows}
  assert len(ids)==len(rows)==len(guids)==512
  if inventory is None:inventory=ids;source_guids=guids
  assert ids==inventory and guids==source_guids
  slots=0
  for row in rows:
   live=row['live']['values'];consumed=row['consumed']['values'];assert live==consumed
   parameters={r[1]:r[2] for r in consumed};assert len(parameters)==len(consumed)
   for label,wanted in zip(('Pitch','Yaw','Roll'),pose):
    matched=[v for name,v in parameters.items() if name.startswith('Param3D'+label)]
    assert matched==[wanted], 'native evaluation did not consume requested XYZ pose'
   actual=row['calculated'];assert actual is not None and actual['coordinateType'] in ('Canvas','DeformerLocal')
   points=actual['positions'];assert len(points)>=3
   assert all(len(p)==2 and all(math.isfinite(v) for v in p) for p in points)
   slots+=len(points)
  assert slots==48497
  measurements.append(dict(poseXYZ=pose,slots=slots,alphaBoundingBox=bbox,pngSha256=frame['sha256']))
 result=dict(status='PASS_CAT_REAL_MENU_INVENTORY_HISTORY_AND_NINE_NATIVE_POSE_OBSERVATIONS' if history else 'PASS_CAT_REAL_MENU_INVENTORY_AND_NINE_NATIVE_POSE_OBSERVATIONS_ONLY',
  jobId=job['job_id'],runId=job['run_id'],normalExit=True,meshes=512,partitionSlots=48497,
  sourceTriangles=70576,frames=measurements,saveReopenAccepted=False,officialExportAccepted=False,
  importHistoryAccepted=history,numericSourceProjectionAccepted=False,occlusionAccepted=False,manualVisualReview='SEPARATE_REVIEW_REQUIRED')
 (root/'independent-cat-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))

if __name__=='__main__':analyze(sys.argv[1])
