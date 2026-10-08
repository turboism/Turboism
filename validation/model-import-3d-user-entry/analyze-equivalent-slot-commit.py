#!/usr/bin/env python3
"""Independent finite diagnostic; never grants full Apple editability acceptance."""
import collections,hashlib,json,struct,sys
from pathlib import Path

def analyze(controller):
    root=Path(controller);terminal=json.loads((root/'terminal-status.json').read_text());jobs=terminal['jobs']
    assert len(jobs)==1 and jobs[0]['state']=='succeeded'
    job=jobs[0];e=json.loads(job['evidence_json']);kernel=e['containment']['kernelProof']
    assert e['normalExit'] and e['fixtureUnchanged'] and e['identityVerified']
    assert not kernel['cgroupKillWritten'] and not kernel['processTimedOut'] and kernel['processReturncode']==0
    report=json.loads((root/'native-report.json').read_text())
    assert report['runId']==job['run_id'] and report['schemaVersion']=='mi3d-apple-equivalent-slot-edit-v1'
    assert report['status']=='PASS' and report['fullAppleEditingAccepted'] is False
    assert report['nativeEditorEntry']['vertexWrites'] is False and report['nativeEditorEnded'] is True
    raw=Path('build/model-import-3d-offline/apple-equivalent-slots-r1-20261003/chart26.bin').read_bytes()
    assert hashlib.sha256(raw).hexdigest()=='adebbb4e934804d9642da62f2383d813aae1b4378e912426e5055ab35f412daa'
    n,k=struct.unpack_from('>ii',raw);assert (n,k)==(1108,6144)
    flat=struct.unpack_from('>'+str(n*2)+'f',raw,8);ix=struct.unpack_from('>'+str(k)+'i',raw,8+n*8)
    f32=lambda v:struct.unpack('>f',struct.pack('>f',v))[0]
    source=report['candidateGeometry'];before=report['candidateBefore'];after=report['candidateCommitted']
    assert [f32(v) for p in source['positions'] for v in p]==list(flat)
    assert source['indices']==list(ix) and before['indices']==list(ix)
    assert before['positions']==source['positions'] and before['uvs']==source['uvs']
    assert all([f32(u),f32(v)]==[f32(x/1024),f32(y/256)] for (x,y),(u,v) in zip(source['positions'],source['uvs']))
    assert before['positions']==after['positions'] and before['uvs']==after['uvs']
    assert len(after['positions'])==n and len(after['indices'])//3==report['committedTriangles']
    def faces(indices):
        return collections.Counter(tuple(sorted(indices[i:i+3])) for i in range(0,len(indices),3))
    original=faces(before['indices']);committed=faces(after['indices'])
    missing=sum((original-committed).values());extra=sum((committed-original).values())
    assert missing==100 and extra==97 and report['committedTriangles']==2045
    result=dict(status='PASS_EQUIVALENT_SLOT_NONEMPTY_COMMIT_DIAGNOSTIC_ONLY',jobId=job['job_id'],runId=job['run_id'],
        prepared=job['prepared_id'],sourceVertices=n,sourceTriangles=k//3,committedVertices=n,committedTriangles=2045,
        missingOriginalFaces=missing,newFaces=extra,positionsPreserved=True,uvsPreserved=True,vertexWrites=False,
        fullAppleEditingAccepted=False,originalTopologyPreserved=False,saveReopenAccepted=False,
        reportSha256=hashlib.sha256((root/'native-report.json').read_bytes()).hexdigest())
    (root/'independent-equivalent-commit-review.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':analyze(sys.argv[1])
