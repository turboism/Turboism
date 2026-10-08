#!/usr/bin/env python3
"""Audit full original-face bound native editing, not full Apple visual/export acceptance."""
import collections,hashlib,json,math,struct,sys
from pathlib import Path

def oriented(indices):
 assert len(indices)%3==0
 return collections.Counter(min(tuple(indices[i:i+3]),tuple(indices[i+1:i+3]+indices[i:i+1]),tuple(indices[i+2:i+3]+indices[i:i+2])) for i in range(0,len(indices),3))

def f32(value):return struct.unpack('>f',struct.pack('>f',value))[0]
def points(values):
 assert all(len(v)==2 and all(math.isfinite(n) for n in v) for v in values)
 return [tuple(f32(n) for n in v) for v in values]

def fixture(path, include_png=False):
 data=path.read_bytes();assert hashlib.sha256(data).hexdigest() in ('f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520','00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc','cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2')
 assert struct.unpack_from('>iii',data)==(0x4d493345,2,98);offset=12;charts=[]
 for index in range(98):
  chart,old,n,k=struct.unpack_from('>iiii',data,offset);offset+=16;assert chart==index
  offset+=old*8;source_hash=data[offset:offset+32].hex();offset+=32
  remap=list(struct.unpack_from(f'>{old}i',data,offset));offset+=old*4
  p=list(zip(*[iter(struct.unpack_from(f'>{n*2}f',data,offset))]*2));offset+=n*8
  ix=list(struct.unpack_from(f'>{k}i',data,offset));offset+=k*4
  size=struct.unpack_from('>i',data,offset)[0];offset+=4;png=data[offset:offset+size];offset+=size
  representative=[remap.index(i) for i in range(n)]
  width,height=struct.unpack_from('>ii',png,16);assert width==height and width in (1024,520,576)
  charts.append(dict(chart=chart,old=old,n=n,indices=ix,positions=p,remap=remap,representatives=representative,sourceHash=source_hash,pngHash=hashlib.sha256(png).hexdigest(),imageSize=width))
  if include_png:charts[-1]['png']=png
 assert offset==len(data) and sum(c['n'] for c in charts)==80514 and sum(len(c['indices'])//3 for c in charts)==150784
 return charts

def analyze(root):
 root=Path(root);terminal=json.loads((root/'terminal-status.json').read_text());job=terminal['job'];e=json.loads(job['evidence_json']);kernel=e['containment']['kernelProof']
 assert job['state']=='succeeded' and job['prepared_id']==e['preparedDigest']
 assert e['normalExit'] and e['fixtureUnchanged'] and e['identityVerified']
 assert not kernel['cgroupKillWritten'] and not kernel['processTimedOut'] and kernel['processReturncode']==0
 assert e['details']['wrapperExit']==0 and e['details']['exitCode']==0
 report=json.loads((root/'native-report.json').read_text());assert report['runId']==job['run_id'] and report['status']=='PASS'
 assert report['schemaVersion']=='mi3d-apple-derived-bound-edit-v1'
 assert report['all98NativeEntryCommitAccepted'] is True and report['fullAppleEditingAccepted'] is False
 path=Path('build/model-import-3d-offline/apple-all-chart-engine-r1-20261003/derived-bound-all98-v2.bin');charts=fixture(path)
 assert report['candidateSha256']==hashlib.sha256(path.read_bytes()).hexdigest()
 created=report['derivedCharts'];edited=report['derivedNativeEditors'];assert len(created)==len(edited)==98
 ids=set();corners=0
 for c,row,edit in zip(charts,created,edited):
  i=c['chart'];assert row['chart']==edit['chart']==i and row['originalId']==f'ArtMesh3D{i}'
  assert row['id'] not in ids;ids.add(row['id'])
  assert row['oldToNew']==c['remap'] and row['representatives']==c['representatives'] and 0<=row['localDrift']<=1e-6
  before=edit['beforeGeometry'];after=edit['afterGeometry'];assert edit['exactNativeCommit'] is True
  assert points(before['positions'])==points(after['positions'])==c['positions']
  expected=[(f32(x/1024),f32(y/1024)) for x,y in c['positions']]
  assert points(before['uvs'])==points(after['uvs'])==expected
  assert oriented(before['indices'])==oriented(after['indices'])==oriented(c['indices'])
  a,b=edit['before'],edit['after'];assert a['id']==b['id']==row['id']
  for key in ('sourceGuid','vertexCount','triangleCount','axisCount','formCount','cellCount','localSha256','axes','forms'):assert a[key]==b[key]
  assert a['vertexCount']==c['n'] and a['triangleCount']==len(c['indices'])//3
  assert a['axisCount']==2 and a['formCount']==a['cellCount']==49;corners+=len(c['indices'])
  source=path.parent/'input'/f'original-{i:03}.bin';assert hashlib.sha256(source.read_bytes()).hexdigest()==c['sourceHash']
 assert corners==452352
 pairs=report['derivedXYZPairs'];assert [r['pose'] for r in pairs]==[[0, 0, 0], [90, 0, 0], [0, 90, 0], [0, 0, 90], [37, -61, 113], [-123, 67, -151], [180, 0, 0], [0, 180, 0], [0, 0, 180], [-180, -180, -180], [165, 75, 45], [-45, 125, -75]];maximum=0;samples=0
 for row in pairs:
  assert len(row['pose'])==3 and all(-180<=v<=180 for v in row['pose'])
  assert len(row['charts'])==98;pose_max=0
  for c,observed in zip(charts,row['charts']):
   assert observed['chart']==c['chart'];old=points(observed['reference']);new=points(observed['derived']);assert len(old)==c['old'] and len(new)==c['n']
   for p,slot in zip(new,c['representatives']):pose_max=max(pose_max,math.hypot(p[0]-old[slot][0],p[1]-old[slot][1]))
   samples+=len(new)
  assert abs(pose_max-row['maximumPairPixels'])<=1e-9 and pose_max<=.125;maximum=max(maximum,pose_max)
 point=report['derivedPointEdit'];assert point['chart']==26
 before=point['before'];after=point['committed'];assert points(before['positions'])!=points(after['positions'])
 assert oriented(before['indices'])==oriented(after['indices']) and before['axes']==after['axes'] and before['cells']==after['cells']
 assert point['pointBefore'][0]==point['pointMoved'][0] and f32(f32(point['pointBefore'][1])-f32(.001))==f32(point['pointMoved'][1]) and point['pointBefore'][2]==point['pointMoved'][2]
 assert report['originalTaskSeedRestored'] is True and report['seedPixelsBeforeSha256']==report['seedPixelsAfterSha256']
 result=dict(status='PASS_FULL98_BOUND_DERIVED_NATIVE_ENTRY_COMMIT_POINT_EDIT_RESTORE_ONLY',jobId=job['job_id'],runId=job['run_id'],prepared=job['prepared_id'],charts=98,slots=80514,originalFaces=150784,checkedOriginalCorners=corners,xyzSamples=samples,maximumPairPixels=maximum,actualPointMove=True,normalExit=True,saveReopenAccepted=False,sourcePixelOcclusionAccepted=False,fullAppleAccepted=False,
  reportSha256=hashlib.sha256((root/'native-report.json').read_bytes()).hexdigest(),fixtureSha256=hashlib.sha256(path.read_bytes()).hexdigest())
 (root/'independent-derived-edit-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if __name__=='__main__':analyze(sys.argv[1])
