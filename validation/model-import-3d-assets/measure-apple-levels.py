#!/usr/bin/env python3
"""Measure candidate subdivision levels against source-depth evidence, not acceptance.

Uses the frozen reference camera, all pixel centers, no boundary exclusions.
Reports diagnostic depth in source units and pixel-equivalent scale; depth error
is not projected-vertex error. Does not validate UV, native interpolation or materials.
"""
import argparse,hashlib,importlib.util,json
from pathlib import Path
import numpy as np
from PIL import Image


def run(cages,reference,out):
    spec=importlib.util.spec_from_file_location('raster',Path(__file__).with_name('apple-occlusion-reference.py'))
    raster=importlib.util.module_from_spec(spec);spec.loader.exec_module(raster)
    registered=json.loads((cages/'report.json').read_text());ref=json.loads((reference/'reference.json').read_text())
    assert registered['status']=='SUBDIVISION_POSITION_MATCH'
    assert all(c['comparison']['quadConnectivityMatchesIgnoringWinding'] for c in registered['components'])
    out.mkdir(parents=True,exist_ok=False);results=[];inputs={}
    def pin(path):inputs[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest()
    pin(cages/'report.json');pin(reference/'reference.json')
    for stem,body in [(1,2),(2,3),(3,4),(3,5)]:
        meshes=[]
        for i,level in enumerate((stem,body)):
            path=cages/f'component-{i}-level-{level}.npz';pin(path);data=np.load(path)
            vertices=data['vertices']+registered['components'][i]['comparison']['fittedTranslationToObj'];q=data['quads']
            triangles=np.concatenate([q[:,[0,1,2]],q[:,[0,2,3]]]);meshes.append((vertices,triangles))
        for pose in ref['poses']:
            yaw,pitch=pose['yaw'],pose['pitch'];y,p=np.radians([yaw,pitch]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
            rotation=np.array([[cy,sy*sp,sy*cp],[0,cp,-sp],[-sy,cy*sp,cy*cp]])
            depths=[];size=ref['resolution'][0];scale=ref['scale']
            for v,t in meshes:
                points=(v-ref['center'])@rotation.T;points[:,:2]*=[scale,-scale];points[:,:2]+=size/2
                depths.append(raster.raster_depth(points,t,size))
            path=reference/f'depth-{yaw}-{pitch}.npz';pin(path);original=np.load(path)
            expected=np.stack([original['component0'],original['component1']]);actual=np.stack(depths)
            ed=expected.min(axis=0);ad=actual.min(axis=0);em=np.isfinite(ed);am=np.isfinite(ad);both=em&am
            owner_expected=expected.argmin(axis=0);owner_actual=actual.argmin(axis=0)
            swapped=both&(owner_actual!=owner_expected);diff=np.abs(ed[both]-ad[both])
            # Per-component silhouette errors are also reported, including hidden parts.
            components=[dict(component=i,silhouetteDifferentPixels=int(np.count_nonzero(np.isfinite(expected[i])^np.isfinite(actual[i])))) for i in range(2)]
            row=dict(stemLevel=stem,bodyLevel=body,yaw=yaw,pitch=pitch,triangles=sum(len(t) for v,t in meshes),
                referenceVisiblePixels=int(em.sum()),silhouetteDifferentPixels=int(np.count_nonzero(em^am)),
                ownerDifferentPixels=int(swapped.sum()),depthDifferenceSourceUnits=dict(max=float(diff.max()),p99=float(np.quantile(diff,.99)),mean=float(diff.mean())),
                depthDifferencePixelEquivalentMax=float(diff.max()*scale),components=components)
            results.append(row);print(json.dumps(row),flush=True)
            diagnostic=np.zeros((size,size,3),dtype=np.uint8);diagnostic[both]=[70,70,70]
            diagnostic[em^am]=[255,80,0];diagnostic[swapped]=[0,220,255]
            Image.fromarray(diagnostic).save(out/f'differences-{stem}-{body}-{yaw}-{pitch}.png')
    report=dict(status='LEVEL_MEASUREMENTS_NOT_NATIVE_ACCEPTANCE',camera=ref,inputsSha256=inputs,measurements=results,
        reference='Original OBJ converted to float32 GLB; depth raster files',boundaryExclusions=False,hostExecuted=False,
        caveat='Three poses only. Source quad diagonals may differ from subdivision ordering. No geometry reduction accepted by these measurements alone.')
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--cages',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.cages,a.reference,a.out)
