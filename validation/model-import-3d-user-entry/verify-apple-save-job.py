#!/usr/bin/env python3
"""Admit the exact frozen apple menu job, containment, artifacts and strict SDK report."""
import argparse,hashlib,json,runpy,tarfile
from pathlib import Path
PREFIX='state/dev.turboism.validation.model-import-3d-user-entry/'
def require(ok,why):
 if not ok:raise ValueError('APPLE_UI_PROCESS: '+why)
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text())
def verify(controller,bundle,prepared_source,output):
 admission=read(controller/'admission-review.json');require(admission['status']=='INPUT_REVIEW_PASS_NOT_EXECUTED' and len(admission['checks'])==35 and all(c['passed'] is True for c in admission['checks']),'prior exact input review')
 submission=read(controller/'04-submit.json')['job'];job=submission['job_id'];prepared=submission['prepared_id'];require(admission['prepared']==prepared,'prepared admission identity')
 terminal=read(controller/'job-terminal.json');rows=[j for j in terminal['jobs'] if j['job_id']==job];require(len(rows)==1,'unique terminal job');state=rows[0];run=state['run_id'];attempt=state['attempt_id']
 require(state['state']=='succeeded' and state['prepared_id']==prepared,'queue success identity')
 root=controller/'native-result';o=read(root/'queue/outcome.json')
 require((o['jobId'],o['runId'],o['attemptId'],o['preparedDigest'])==(job,run,attempt,prepared),'outcome binding')
 require(o['terminalState']=='succeeded' and o['normalExit'] is True and o['cleanup']=='safe' and o['runnerExitCode']==0 and o['identityVerified'] is True and o['fixtureUnchanged'] is True and o['validationStatus']=='PASS','normal host/seed/lifecycle')
 c=o['containment'];k=c['kernelProof'];require((c['jobId'],c['runId'],c['attemptId'],c['preparedDigest'])==(job,run,attempt,prepared),'containment binding')
 require(k['cgroupKillWritten'] is False and k['processTimedOut'] is False and k['processReturncode']==0 and k['originalCgroupBound'] is True and k['errors']==[],'normal proven scope')
 d=o['details'];post=d['postContainmentChecks'];require(d['goldenUnchanged'] is True and d['validationComplete'] is True and d['wrapperExit']==0,'complete official launcher')
 require(d['identityActualJarSha256']==d['identityExpectedJarSha256']=='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21','official Cubism jar')
 require(post['fixtureSourceSha256']==post['fixtureCopySha256']=='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4','seed preserved')
 manifest=runpy.run_path(str(Path(__file__).with_name('verify-bundle.py')))['verify'](bundle);require(manifest['profile']=='apple-menu-save','explicit apple bundle')
 expected={Path(name).name:r['sha256'] for name,r in manifest['files'].items()};require(len(expected)==3,'three distinct frozen artifacts')
 staged=post['stagedArtifacts'];require(len(staged)==3 and {Path(r['staged']).name for r in staged}==set(expected),'executed jar inventory')
 for r in staged:require(r['sourceSha256']==r['stagedSha256']==expected[Path(r['staged']).name],'executed jar identity')
 for name,digest in read(root/'manifest.json').items():require(sha(root/name)==digest,'collected native evidence identity')
 for name,digest in read(controller/'source-manifest.json').items():require(sha(controller/'sources'/name)==digest,'frozen source identity')
 archive=root/'queue/evidence/final-task/turboism-home-logs-state.tar';artifacts=output/'artifacts'
 expected_local={PREFIX+f'apple-locals/{i}.xy' for i in range(98)};expected_png={PREFIX+f'source-texture-render/{i}.png' for i in range(11)};report_name=PREFIX+'report.json';save_name=PREFIX+'persistence/mi3d-apple-model.cmo3'
 wanted=expected_local|expected_png|{report_name,save_name};payloads={}
 with tarfile.open(archive) as t:
  members=t.getmembers();names=[m.name for m in members];require(len(names)==len(set(names)),'duplicate archive members')
  require(len(members)<=10000 and sum(max(0,m.size) for m in members)<=512*1024*1024,'complete bounded archive')
  for m in members:
   require(not m.name.startswith('/') and '..' not in Path(m.name).parts and not m.issym() and not m.islnk(),'no links/traversal')
  scoped={m.name for m in members if not m.isdir() and (m.name.startswith(PREFIX+'apple-locals/') or m.name.startswith(PREFIX+'source-texture-render/') or m.name.startswith(PREFIX+'persistence/'))}
  require(scoped==expected_local|expected_png|{save_name},'exact PNG/local archive namespace')
  selected=[m for m in members if m.name in wanted];require({m.name for m in selected}==wanted,'complete report/local/PNG artifacts')
  for m in selected:
   limit=256*1024*1024 if m.name==save_name else 64*1024*1024 if m.name==report_name else 8*1024*1024 if m.name in expected_png else 1609*8
   require(m.isreg() and 0<m.size<=limit,'regular bounded selected artifact')
   with t.extractfile(m) as stream:raw=stream.read(m.size+1)
   require(len(raw)==m.size,'exact archive member length');payloads[m.name[len(PREFIX):]]=raw
 def pairs(items):
  result={}
  for key,value in items:require(key not in result,'duplicate report JSON key');result[key]=value
  return result
 r=json.loads(payloads['report.json'],object_pairs_hook=pairs,parse_constant=lambda v:(_ for _ in ()).throw(ValueError(v)))
 output.mkdir(exist_ok=False,parents=True)
 for name,raw in payloads.items():
  file=artifacts/name;file.parent.mkdir(exist_ok=True,parents=True)
  with file.open('xb') as stream:stream.write(raw)
 content=runpy.run_path(str(Path(__file__).with_name('verify-apple-save.py')))['verify'](r,run,prepared_source,artifacts)
 result={'status':'PASS_EXACT_FROZEN_APPLE_MENU_SAVE_SOURCE_HISTORY_CAPTURE','jobId':job,'runId':run,'attemptId':attempt,'prepared':prepared,'normalExit':True,'content':content,'executedJars':expected,'archiveSha256':sha(archive),'reportSha256':hashlib.sha256(payloads['report.json']).hexdigest(),'pixelCorrectnessAccepted':False,'persistenceAccepted':False,'officialExportAccepted':False,'goalComplete':False}
 with (output/'acceptance.json').open('x') as f:json.dump(result,f,indent=2);f.write('\n')
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--controller',type=Path,required=True);p.add_argument('--bundle',type=Path,required=True);p.add_argument('--prepared-source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();print(json.dumps(verify(a.controller,a.bundle,a.prepared_source,a.out),indent=2))
