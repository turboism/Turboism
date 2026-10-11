"""Read-only pairing of native XYZ SAVE_AS with a distinct-process reopen capture."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy

HERE=Path(__file__).resolve().parent
O=runpy.run_path(str(HERE/'verify-xyz-content.py'))
require=O['require']


def paired(save,reopen,artifact):
    require(save['schemaVersion']=='mi3d-cube-xyz360-v1' and save['status']=='PASS','successful XYZ save capture')
    require(reopen['schemaVersion']=='mi3d-cube-xyz360-reopen-v1' and reopen['status']=='PASS'
            and reopen['scope']=='OBSERVE_REOPENED_XYZ_SEED_ONLY','successful XYZ reopen capture')
    require(save['runId']!=reopen['runId'] and save['runId'].startswith('queue-')
            and reopen['runId'].startswith('queue-'),'distinct isolated process runs')
    require(save['inputSha256']==reopen['inputSha256'] and save['axes']==reopen['axes'],'same source and parameter identity')
    s=save['persistenceSave']
    require(s['commandStatus']=='EXECUTED' and s['historyUnchanged'] is True
            and s['artifact']=='persistence/mi3d-xyz-model.cmo3','native fixed-target SAVE_AS')
    require(0<len(artifact)<=256*1024*1024 and s['artifactBytes']==len(artifact)
            and s['artifactSha256']==hashlib.sha256(artifact).hexdigest(),'exact bounded native CMO3')
    require(s['before']==s['after']==save['imported']==reopen['imported'],'complete stored source/forms/parameters/images survived reopen')
    a,b=save['frames'],reopen['frames']
    require(len(a)==len(b)==12,'complete paired pose bank')
    errors=[]
    for x,y,pose in zip(a,b,O['POSES']):
        require(x['poseXYZ']==y['poseXYZ']==list(pose),'paired pose identity')
        require(len(x['evaluated'])==len(y['evaluated'])==6,'paired mesh inventory')
        maximum=0
        for m,n in zip(x['evaluated'],y['evaluated']):
            require(m['id']==n['id'] and m['order']==n['order'],'paired source and native layer order')
            maximum=max(maximum,O['G']['error'](m['positions'],n['positions']))
        require(maximum<=.001,'native geometry changed after save/reopen')
        errors.append(dict(poseXYZ=list(pose),maxErrorPixels=maximum,pngIdentical=x['sha256']==y['sha256']))
    return dict(status='XYZ_STORED_AUTHORING_AND_NATIVE_GEOMETRY_PERSISTENCE_CHECKED',
                saveRun=save['runId'],reopenRun=reopen['runId'],artifactSha256=s['artifactSha256'],frames=errors)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--save-state',required=True,type=Path);p.add_argument('--reopen-state',required=True,type=Path)
    p.add_argument('--cube',required=True,type=Path);p.add_argument('--textured',required=True,type=Path)
    args=p.parse_args();roots=[args.save_state.resolve(strict=True),args.reopen_state.resolve(strict=True)]
    def read(root,name):
        require(not (root/name).is_symlink(),'unlinked artifact')
        path=(root/name).resolve(strict=True);require(path.is_relative_to(root),'artifact containment')
        return path.read_bytes()
    reports=[json.loads(read(root,'report.json')) for root in roots]
    content=[O['verify'](r,args.cube,args.textured,lambda name,root=root:read(root,name)) for r,root in zip(reports,roots)]
    result=paired(*reports,read(roots[0],reports[0]['persistenceSave']['artifact']))
    result['independentContent']=content;print(json.dumps(result,indent=2))
