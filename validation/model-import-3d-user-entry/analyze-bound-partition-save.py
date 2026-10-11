#!/usr/bin/env python3
"""Finite chart26 bound edit/save evidence; full Apple editing and reopen remain separate gates."""
import collections,hashlib,json,math,struct,sys
from pathlib import Path

def oriented(ix):
    result=collections.Counter()
    for i in range(0,len(ix),3):
        a,b,c=ix[i:i+3];result[min((a,b,c),(b,c,a),(c,a,b))]+=1
    return result

def f32(v):return struct.unpack('>f',struct.pack('>f',v))[0]
def points(values):return [tuple(map(f32,p)) for p in values]
def cells(bank):
    forms={f['guid']:f for f in bank['forms']}
    return {tuple(sorted((p['guid'],p['value']) for p in c['coordinates'])):forms[c['formGuid']] for c in bank['cells']}
def digest(path):
    h=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest()

def analyze(controller):
    root=Path(controller);s=json.loads((root/'terminal-status.json').read_text());assert len(s['jobs'])==1
    job=s['jobs'][0];assert job['state']=='succeeded';e=json.loads(job['evidence_json']);kernel=e['containment']['kernelProof']
    assert e['normalExit'] and e['fixtureUnchanged'] and e['identityVerified']
    assert not kernel['cgroupKillWritten'] and not kernel['processTimedOut'] and kernel['processReturncode']==0
    report=json.loads((root/'native-report.json').read_text());assert report['runId']==job['run_id'] and report['status']=='PASS'
    assert report['schemaVersion']=='mi3d-apple-bound-partition-edit-save-v1'
    assert report['candidateSha256']=='906624bccb601f507a1531a9518c4efe9ab58027ac5a7eb9755ac8a0ba6fb1e1'
    assert report['originalTaskSeedRestored'] and report['originalXYZBanksPreserved'] and report['fullAppleEditingAccepted'] is False
    restored=report['restoredState'];initial=report['initial'];assert initial.keys()==restored.keys()
    for key in initial:
        if key in ('meshes','meshMetadata'):
            old={r['id']:r for r in initial[key]};new={r['id']:r for r in restored[key]}
            assert len(old)==len(initial[key]) and len(new)==len(restored[key]) and old==new
        else:assert initial[key]==restored[key]
    assert report['neutralSeedPixelsRestored'] and report['seedPixelsBeforeSha256']==report['seedPixelsAfterSha256']
    assert report['restoredMeshEnumerationChanged']==(initial['meshes']!=restored['meshes'])
    original=report['originalChart'];assert len(original['positions'])==1373 and len(original['indices'])==6144
    sourcecells=cells(original);groups=report['replacementGroups'];assert len(groups)==5;seen=[];groupIds=[]
    for row in groups:
        b=row['bank'];groupIds.append(b['artMeshId']);slots=row['originalSlots'];faces=row['originalFaces'];seen+=faces
        assert b['axes']==original['axes'] and len(b['forms'])==49 and len(b['cells'])==49
        assert 0<=row['localDrift']<=1e-6 and len(b['positions'])==len(slots)
        for i,old in enumerate(slots):
            assert points([b['positions'][i]])==points([original['positions'][old]])
            assert points([b['uvs'][i]])==points([original['uvs'][old]])
        for t,face in enumerate(faces):
            for c in range(3):
                old=original['indices'][face*3+c];new=b['indices'][t*3+c]
                assert points([original['positions'][old]])==points([b['positions'][new]])
                assert points([original['uvs'][old]])==points([b['uvs'][new]])
        targetcells=cells(b);assert targetcells.keys()==sourcecells.keys()
        for key,value in targetcells.items():assert value['drawOrder']==sourcecells[key]['drawOrder']
    assert sorted(seen)==list(range(2048)) and len(set(groupIds))==5
    deleted=report['afterOriginalDelete'];assert deleted['count']==102 and len(set(deleted['ids']))==102 and 'ArtMesh3D26' not in deleted['ids'] and set(groupIds)<=set(deleted['ids'])
    poses=report['boundPartitionPoses'];expected=[[0,0,0],[90,0,0],[0,90,0],[0,0,90],[37,-61,113],[-123,67,-151],[180,0,0],[0,180,0],[0,0,180],[-180,-180,-180],[165,75,45],[-45,125,-75]]
    assert [p['pose'] for p in poses]==expected;maximum=0;count=0
    for frame in poses:
        reference=points(frame['referencePositions']);assert len(reference)==1373;error=0
        assert len(frame['groups'])==5
        for row,captured in zip(groups,frame['groups']):
            actual=points(captured['positions']);assert captured['group']==row['group'] and len(actual)==len(row['originalSlots'])
            for p,slot in zip(actual,row['originalSlots']):
                assert all(map(math.isfinite,p));error=max(error,math.hypot(p[0]-reference[slot][0],p[1]-reference[slot][1]));count+=1
        assert error<=0.125 and math.isclose(error,frame['maxPairPixels'],abs_tol=1e-8);maximum=max(maximum,error)
    neutral=points(poses[0]['referencePositions']);assert any(max(math.dist(a,b) for a,b in zip(neutral,points(f['referencePositions'])))>1 for f in poses[1:])
    editors=report['nativeBoundEditors'];assert [r['id'] for r in editors]==groupIds
    for row in editors:
        b,a=row['before'],row['after'];assert row['exactNativeBoundCommit']
        assert b['positions']==a['positions'] and b['uvs']==a['uvs'] and oriented(b['indices'])==oriented(a['indices'])
        assert all(b[k]==a[k] for k in ('artMeshId','sourceGuid','axes','cells','forms','morphKeys'))
    edit=report['pointEdit'];b=edit['before'];a=report['pointEditCommitted'];assert edit['id']==groupIds[0]
    assert len(b['positions'])==len(a['positions']) and b['positions']!=a['positions'] and oriented(b['indices'])==oriented(a['indices'])
    assert b['axes']==a['axes'] and b['cells']==a['cells'] and len(a['forms'])==49
    initial,saved=report['initial'],report['savedState'];assert len(initial['meshes'])==98 and len(saved['meshes'])==102
    assert sum(m['triangleCount'] for m in saved['meshes'])==150784
    old={m['id']:m for m in initial['meshes']};new={m['id']:m for m in saved['meshes']}
    for name,value in old.items():
        if name!='ArtMesh3D26':assert new[name]==value
    assert 'ArtMesh3D26' not in new
    for key in ('warps','rotations','parameters','parts','rawImages','imageGroups'):assert initial[key]==saved[key]
    artifact=report['boundPartitionSave'];path=root/'host-job/state'/artifact['artifact'];assert path.is_file() and path.stat().st_size==artifact['bytes'] and 0<artifact['bytes']<=256*1024*1024
    assert digest(path)==artifact['sha256']
    result=dict(status='PASS_FINITE_REAL_BOUND_CHART26_EDIT_SAVE_NOT_REOPEN',jobId=job['job_id'],runId=job['run_id'],prepared=job['prepared_id'],groups=5,
        sourceTriangles=150784,meshes=102,clonePairSamples=count,maxClonePairPixels=maximum,originalXYZBanksPreserved=True,
        actualPointEditCommitted=True,artifact=artifact,originalTaskSeedRestored=True,fullAppleEditingAccepted=False,saveReopenAccepted=False,
        reportSha256=digest(root/'native-report.json'))
    (root/'independent-bound-save-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':analyze(sys.argv[1])
