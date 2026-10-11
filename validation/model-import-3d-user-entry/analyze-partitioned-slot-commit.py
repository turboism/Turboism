#!/usr/bin/env python3
"""Validate exact source corners and real commits for one Apple chart, not full model acceptance."""
import collections,hashlib,json,struct,sys
from pathlib import Path

def oriented(indices):
    result=collections.Counter()
    for i in range(0,len(indices),3):
        a,b,c=indices[i:i+3];result[min((a,b,c),(b,c,a),(c,a,b))]+=1
    return result

def analyze(controller):
    root=Path(controller);terminal=json.loads((root/'terminal-status.json').read_text());assert len(terminal['jobs'])==1
    job=terminal['jobs'][0];assert job['state']=='succeeded';e=json.loads(job['evidence_json']);kernel=e['containment']['kernelProof']
    assert e['normalExit'] and e['fixtureUnchanged'] and e['identityVerified']
    assert not kernel['cgroupKillWritten'] and not kernel['processTimedOut'] and kernel['processReturncode']==0
    report=json.loads((root/'native-report.json').read_text());assert report['runId']==job['run_id']
    assert report['schemaVersion']=='mi3d-apple-partitioned-slot-edit-v1' and report['status']=='PASS'
    assert report['exactNativeCommit'] and report['fullAppleEditingAccepted'] is False
    source=Path('build/model-import-3d-offline/apple-equivalent-slots-r1-20261003/chart26.bin').read_bytes()
    assert hashlib.sha256(source).hexdigest()=='adebbb4e934804d9642da62f2383d813aae1b4378e912426e5055ab35f412daa'
    n,k=struct.unpack_from('>ii',source);coords=struct.unpack_from('>'+str(n*2)+'f',source,8);original=struct.unpack_from('>'+str(k)+'i',source,8+n*8)
    groups=report['groups'];assert len(groups)==5;seen=[];slots=0
    f32=lambda v:struct.unpack('>f',struct.pack('>f',v))[0]
    for row in groups:
        before=row['before'];after=row['committed'];ids=row['originalFaces'];indices=before['indices'];p=before['positions'];uv=before['uvs']
        assert row['nativeEntry']['vertexWrites'] is False and row['exactOrientedFaces']
        assert len(indices)==len(ids)*3 and row['nativeEntry']['points']==len(p)
        assert p==after['positions'] and uv==after['uvs'] and oriented(indices)==oriented(after['indices'])
        assert all([f32(u),f32(v)]==[f32(x/1024),f32(y/256)] for (x,y),(u,v) in zip(p,uv))
        for t,face in enumerate(ids):
            for corner in range(3):
                a=original[face*3+corner];b=indices[t*3+corner]
                assert [f32(v) for v in p[b]]==list(coords[a*2:a*2+2])
        seen+=ids;slots+=len(p)
    assert sorted(seen)==list(range(2048)) and slots==2189
    result=dict(status='PASS_REAL_CHART26_PARTITIONED_EXACT_NATIVE_COMMIT_ONLY',jobId=job['job_id'],runId=job['run_id'],prepared=job['prepared_id'],
        groups=5,sourceTriangles=2048,committedTriangles=2048,partitionSlots=slots,positionsPreserved=True,uvsPreserved=True,
        originalOrientedFacesPreserved=True,allSourceCornersChecked=6144,vertexWrites=False,fullAppleEditingAccepted=False,
        saveReopenAccepted=False,reportSha256=hashlib.sha256((root/'native-report.json').read_bytes()).hexdigest())
    (root/'independent-partitioned-commit-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':analyze(sys.argv[1])
