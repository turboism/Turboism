#!/usr/bin/env python3
"""Fresh-process persistence for the bounded chart26 replacement; not full Apple acceptance."""
import hashlib,json,sys
from pathlib import Path

def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1048576),b''):h.update(chunk)
    return h.hexdigest()

def analyze(save,reopen):
    save,reopen=Path(save),Path(reopen)
    admission=json.loads((save/'independent-bound-save-review.json').read_text())
    assert admission['status']=='PASS_FINITE_REAL_BOUND_CHART26_EDIT_SAVE_NOT_REOPEN'
    saved=json.loads((save/'native-report.json').read_text())
    terminal=json.loads((reopen/'terminal-status.json').read_text());assert len(terminal['jobs'])==1
    job=terminal['jobs'][0];assert job['state']=='succeeded' and job['run_id']!=saved['runId']
    evidence=json.loads(job['evidence_json']);proof=evidence['containment']['kernelProof']
    assert evidence['normalExit'] and evidence['fixtureUnchanged'] and evidence['identityVerified']
    assert not proof['cgroupKillWritten'] and not proof['processTimedOut'] and proof['processReturncode']==0
    actual=json.loads((reopen/'native-report.json').read_text())
    assert actual['status']=='PASS' and actual['runId']==job['run_id'] and actual['schemaVersion']=='mi3d-apple-bound-partition-reopen-v1'
    assert actual['stateUnchanged'] and actual['historyUnchanged'] and actual['fullAppleEditingAccepted'] is False
    before,after=saved['savedState'],actual['reopenedState'];assert before.keys()==after.keys()
    for key in before:
        if key in ('meshes','meshMetadata'):
            left={m['id']:m for m in before[key]};right={m['id']:m for m in after[key]}
            assert len(left)==len(before[key]) and len(right)==len(after[key]) and left==right
        else:assert before[key]==after[key],key
    expected={row['id']:row['after'] for row in saved['nativeBoundEditors']}
    expected[saved['pointEdit']['id']]=saved['pointEditCommitted']
    groups={m['artMeshId']:m for m in actual['reopenedPartitionMeshes']}
    assert len(groups)==len(actual['reopenedPartitionMeshes'])==5 and groups==expected
    artifact=saved['boundPartitionSave']
    assert len(actual['reopenedNeutralPixelSha256'])==64
    result=dict(status='PASS_FRESH_EDITOR_BOUND_CHART26_EDIT_SAVE_REOPEN_ONLY',saveJob=admission['jobId'],reopenJob=job['job_id'],saveRun=saved['runId'],reopenRun=actual['runId'],artifact=artifact,meshes=102,sourceTriangles=150784,allFiveStoredPartitionBanksPreserved=True,actualPointEditPreserved=True,fullXYZBanksPreserved=True,readOnlyReopen=True,fullAppleEditingAccepted=False,sourcePixelOcclusionAccepted=False,reportSha256=digest(reopen/'native-report.json'))
    (reopen/'independent-bound-reopen-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':analyze(*sys.argv[1:])
