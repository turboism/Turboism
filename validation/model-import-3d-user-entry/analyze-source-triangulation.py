#!/usr/bin/env python3
"""Verify a read-only real SDK query witness, never claim import/edit acceptance."""
import collections,csv,hashlib,json,math,struct,sys
from pathlib import Path

def faces(indices):
 assert len(indices)%3==0
 return collections.Counter(min(tuple(indices[i:i+3]),tuple(indices[i+1:i+3]+indices[i:i+1]),tuple(indices[i+2:i+3]+indices[i:i+2])) for i in range(0,len(indices),3))

def positions(values):
 assert all(len(p)==2 and all(math.isfinite(v) for v in p) for p in values)
 return [tuple(struct.unpack('>f',struct.pack('>f',v))[0] for v in p) for p in values]

def unpack(data,offset=0):
 n,k=struct.unpack_from('>ii',data,offset);offset+=8
 p=list(zip(*[iter(struct.unpack_from(f'>{2*n}f',data,offset))]*2));offset+=8*n
 ix=list(struct.unpack_from(f'>{k}i',data,offset));offset+=4*k
 return p,ix,offset

def analyze(root):
 root=Path(root);terminal=json.loads((root/'terminal-status.json').read_text());job=terminal['job'];e=json.loads(job['evidence_json'])
 assert job['state']=='succeeded' and job['prepared_id']==e['preparedDigest']
 assert e['normalExit'] and e['fixtureUnchanged'] and e['identityVerified']
 kernel=e['containment']['kernelProof'];assert not kernel['cgroupKillWritten'] and not kernel['processTimedOut'] and kernel['processReturncode']==0
 assert e['details']['wrapperExit']==0 and e['details']['exitCode']==0
 report=json.loads((root/'native-report.json').read_text())
 assert report['status']=='PASS' and report['runId']==job['run_id']
 assert report['schemaVersion']=='mi3d-native-source-triangulation-query-v1'
 assert report['sourceUnchanged'] is True and report['historyUnchanged'] is True
 assert report['nativeMeshEditingAccepted'] is False and report['fullAppleEditingAccepted'] is False
 engine=Path('build/model-import-3d-offline/apple-all-chart-engine-r1-20261003')
 expected={};hashes={}
 for chart in (0,26):
  path=engine/'input'/f'original-{chart:03}.bin';data=path.read_bytes();p,ix,end=unpack(data);assert end==len(data)
  expected[f'original-chart{chart}']=(p,ix);hashes[str(path)]=hashlib.sha256(data).hexdigest()
 fixture=Path('build/model-import-3d-offline/apple-equivalent-chart26-native-r1-20261003/official-analysis/partitioned-chart26.bin');data=fixture.read_bytes()
 assert hashlib.sha256(data).hexdigest()=='906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1'
 assert struct.unpack_from('>ii',data)==(0x4d493344,5);offset=8;inventory=[]
 for group in range(5):
  p,ix,offset=unpack(data,offset);count=len(ix)//3;inventory+=struct.unpack_from(f'>{count}i',data,offset);offset+=4*count
  expected[f'partition26-{group}']=(p,ix)
 assert offset==len(data) and sorted(inventory)==list(range(2048))
 hashes[str(fixture)]=hashlib.sha256(data).hexdigest()
 rows=report['queries'];assert [r['id'] for r in rows]==list(expected)
 checks=[]
 for row in rows:
  p,ix=expected[row['id']];actualp=positions(row['inputPositions']);outp=positions(row['outputPositions']);outix=row['outputIndices']
  assert actualp==p and row['inputIndices']==ix
  assert all(type(i) is int and 0<=i<len(outp) for i in outix)
  samep=actualp==outp;samefaces=faces(ix)==faces(outix)
  assert row['pointsExact']==samep and row['orientedFacesExact']==samefaces
  if row['id']=='original-chart26':assert samep and not samefaces and len(outix)//3==1935
  else:assert samep and samefaces
  checks.append({'id':row['id'],'points':len(p),'inputFaces':len(ix)//3,'outputFaces':len(outix)//3,'orientedFacesExact':samefaces})
 for path in [root/'native-report.json',root/'terminal-status.json',Path(__file__)]:hashes[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest()
 result={'status':'PASS_REAL_READONLY_SDK_SOURCE_TRIANGULATION_QUERY_ONLY','jobId':job['job_id'],'runId':job['run_id'],
 'prepared':job['prepared_id'],'queries':checks,'sourceAndHistoryUnchanged':True,'normalExit':True,
 'nativeEditorCommitAccepted':False,'fullAppleAccepted':False,'hashes':hashes}
 (root/'independent-source-query-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='hashes'},indent=2))
if __name__=='__main__':analyze(sys.argv[1])
