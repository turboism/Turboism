#!/usr/bin/env python3
"""Audit native Cat SAVE_AS and independent fresh-process reopen; finite poses only."""
import json, math, runpy, sys
from pathlib import Path
from PIL import Image
PAIR=runpy.run_path(str(Path(__file__).with_name('analyze-derived-persistence.py')))
POSES=runpy.run_path(str(Path(__file__).with_name('analyze-cat-native.py')))['POSES']
SOURCE='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'

def bank(report):
 assert report['inputSha256']==SOURCE
 value=report['persistentBank']
 assert value['meshes']==512 and value['slots']==48497 and value['triangles']==70576
 assert len(value['sha256'])==64 and all(c in '0123456789abcdef' for c in value['sha256'])
 return value

def save(root):
 job,report=PAIR['terminal'](root)
 assert report['schemaVersion']=='mi3d-cat-vertex-save-v1'
 assert report['scope']=='REAL_CAT_MENU_IMPORT_NATIVE_SAVE_BANK_ONLY_NO_REPEATED_HISTORY_OR_FRAMES'
 assert 'frames' not in report and not report['importHistoryProbe']
 saved=report['persistenceSave'];assert saved['commandStatus']=='EXECUTED'
 assert saved['artifact']=='persistence/mi3d-cat-model.cmo3'
 path=PAIR['read_artifact'](root,saved['artifact'])
 assert 0<path.stat().st_size<=256*1024*1024 and path.stat().st_size==saved['artifactBytes']
 assert PAIR['digest'](path)==saved['artifactSha256']
 return job,report,bank(report),saved

def analyze(save_root,reopen_root=None,bank_root=None):
 root=Path(save_root);job,report,saved_bank,artifact=save(root)
 result=dict(status='PASS_CAT_NATIVE_SAVE_ONLY',saveJobId=job['job_id'],artifact=artifact,
             persistentBank=saved_bank,freshReopenAccepted=False,officialExportAccepted=False,
             continuousOcclusionAccepted=False)
 if reopen_root is not None:
  other=Path(reopen_root);reopen_job,reopened=PAIR['terminal'](other)
  assert reopened['schemaVersion']=='mi3d-cat-vertex-reopen-v1'
  assert reopened['scope']=='FRESH_EDITOR_CAT_PERSISTENCE_BANK_AND_NINE_NATIVE_FRAMES'
  assert reopen_job['job_id']!=job['job_id'] and reopen_job['run_id']!=job['run_id']
  reopened_bank=bank(reopened)
  if reopened_bank!=saved_bank:
   # Legacy capture hashed the SDK observation's process-local modelGeneration.
   # Accept a difference only with an independently completed same-seed job that
   # reproduces BOTH full legacy digests by changing that one field alone.
   assert bank_root is not None, 'saved complete bank changed across fresh reopen'
   diagnostic_root=Path(bank_root);diagnostic_job,diagnostic=PAIR['terminal'](diagnostic_root)
   assert diagnostic['schemaVersion']=='mi3d-cat-vertex-bank-diagnostic-v1'
   assert diagnostic['scope']=='READ_ONLY_EXACT_CAT_BANK_GENERATION_DIAGNOSIS_NO_FRAMES_OR_SAVE'
   assert diagnostic['inputSha256']==SOURCE and 'frames' not in diagnostic and 'persistenceSave' not in diagnostic
   assert diagnostic_job['job_id'] not in (job['job_id'],reopen_job['job_id'])
   assert artifact['artifactSha256'] in (diagnostic_root/'host-job/evidence/fixture-before.properties').read_text()
   d=diagnostic['bankDiagnostic']
   assert d['onlyOverriddenField']=='ArtMeshTextureInput.modelGeneration; all remaining original binary bank fields exact'
   assert d['rawBank']==reopened_bank
   generations=d['observedTextureGenerations'];assert len(generations)==1
   variants=d['generationOnlyVariants'];assert variants[str(generations[0])]==reopened_bank
   original=[int(k) for k,v in variants.items() if v==saved_bank]
   assert len(original)==1 and original[0]!=generations[0], 'difference is not solely session generation'
   result['bankComparison']=dict(status='EXACT_FULL_BANK_MATCH_EXCLUDING_NONPERSISTENT_TEXTURE_GENERATION',
       saveTextureGeneration=original[0],reopenTextureGeneration=generations[0],diagnosticJobId=diagnostic_job['job_id'],
       legacySaveBank=saved_bank,legacyReopenBank=reopened_bank)
  fixture=other/'host-job/evidence/fixture-before.properties'
  assert artifact['artifactSha256'] in fixture.read_text(), 'reopen fixture is not saved Cat'
  assert len(reopened['frames'])==9
  identities=None
  for i,(frame,pose) in enumerate(zip(reopened['frames'],POSES)):
   assert frame['XYZ']==pose and frame['artifact']==f'cat-frames/{i}.png'
   path=PAIR['read_artifact'](other,frame['artifact']);assert PAIR['digest'](path)==frame['sha256']
   with Image.open(path) as image:
    assert image.format=='PNG' and image.size==(1024,1024)
    assert image.convert('RGBA').getchannel('A').getbbox() is not None
   rows=frame['evaluated'];current={(r['artMeshId'],r['sourceGuid']) for r in rows}
   assert len(rows)==len(current)==512
   if identities is None:identities=current
   assert current==identities
   slots=0
   for row in rows:
    assert row['live']['values']==row['consumed']['values']
    parameters={r[1]:r[2] for r in row['consumed']['values']}
    for label,wanted in zip(('Pitch','Yaw','Roll'),pose):
     assert [v for n,v in parameters.items() if n.startswith('Param3D'+label)]==[wanted]
    calculated=row['calculated'];assert calculated['coordinateType']=='Canvas'
    points=calculated['positions'];slots+=len(points)
    assert all(len(p)==2 and all(math.isfinite(v) for v in p) for p in points)
   assert slots==48497
  result.update(status='PASS_CAT_NATIVE_SAVE_FRESH_REOPEN_BANK_AND_NINE_POSES',
                freshReopenAccepted=True,reopenJobId=reopen_job['job_id'])
 (root/'independent-cat-persistence-review.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps(result,indent=2))

if __name__=='__main__':analyze(*sys.argv[1:])
