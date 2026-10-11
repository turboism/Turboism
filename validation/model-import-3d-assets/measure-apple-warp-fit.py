#!/usr/bin/env python3
"""Fit original source chart vertices with linear native Warp basis, without decimation.

The 3D residual norm bounds projected residual under every orthogonal rotation.
Native kernel replay is offline math, never host/model/export acceptance.
"""
import argparse, hashlib, json, struct, subprocess
from pathlib import Path
import numpy as np

JAR_SHA = '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'

def basis(parameter, cells):
    uv = parameter*cells
    ij = np.minimum(np.floor(uv).astype(int), cells-1)
    delta = uv-ij
    x,y = delta.T
    ids = ij[:,1]*(cells+1)+ij[:,0]
    return np.stack([ids,ids+1,ids+cells+1,ids+cells+2],axis=1), np.stack([(1-x)*(1-y),x*(1-y),(1-x)*y,x*y],axis=1)

def evaluate(controls, parameter, cells):
    ids,weights=basis(parameter,cells)
    return np.sum(controls[ids]*weights[:,:,None],axis=1)

def fit(parameter, source, cells):
    ids,weights=basis(parameter,cells);count=(cells+1)**2
    lookup={tuple(p):s for p,s in zip(parameter,source)}
    grid=np.stack(np.meshgrid(np.arange(cells+1)/cells,np.arange(cells+1)/cells),axis=-1).reshape(-1,2)
    aligned=all(tuple(p) in lookup for p in grid)
    if aligned and len(parameter)==count:
        return np.array([lookup[tuple(p)] for p in grid],dtype=float)
    # Sparse four-entry rows accumulated without allocating a dense vertex design matrix.
    normal=np.zeros((count,count));rhs=np.zeros((count,3))
    for i in range(4):
        np.add.at(rhs,ids[:,i],weights[:,i,None]*source)
        for j in range(4):np.add.at(normal,(ids[:,i],ids[:,j]),weights[:,i]*weights[:,j])
    if aligned:
        # Shared chart edges use identical original source samples. This makes
        # the complete interpolated edge continuous, rather than only its ends.
        boundary=np.any((grid==0)|(grid==1),axis=1);interior=~boundary
        cage=np.zeros((count,3));cage[boundary]=[lookup[tuple(p)] for p in grid[boundary]]
        cage[interior]=np.linalg.solve(normal[np.ix_(interior,interior)],rhs[interior]-normal[np.ix_(interior,boundary)]@cage[boundary])
        return cage
    return np.linalg.solve(normal,rhs)

def run(a):
    a.out.mkdir(parents=True,exist_ok=False)
    charts=[];inputs={}
    report_path=a.charts/'report.json';prior=json.loads(report_path.read_text())
    assert prior['sourceTrianglesUnchanged'] and prior['textureUVUnchanged']
    for component in range(2):
        path=a.charts/f'component-{component}.npz';inputs[str(path)]=hashlib.sha256(path.read_bytes()).hexdigest()
        data=np.load(path);chart_ids=np.repeat(data['chart'],3);params=data['parameter'].reshape(-1,2)
        source=data['source3D'].reshape(-1,3);vertex=data['sourceVertex'].reshape(-1)
        for chart in np.unique(chart_ids):
            mask=chart_ids==chart;v=vertex[mask];_,unique=np.unique(v,return_index=True)
            charts.append((int(chart),params[mask][unique],source[mask][unique],8 if component==0 else 32,v[unique]))
    charts.sort(key=lambda x:x[0]);assert [c[0] for c in charts]==list(range(98))
    all_source=np.concatenate([c[2] for c in charts]).astype(float)
    pivot=all_source.min(axis=0)/2+all_source.max(axis=0)/2
    # Production SurfaceAutoCamera uses the AABB midpoint and maximum actual
    # vertex distance, rather than the larger sphere around the AABB corners.
    radius=float(np.max(np.linalg.norm(all_source-pivot,axis=1)));scale=400/radius
    points=np.concatenate([c[1] for c in charts]);xyz=np.concatenate([c[2] for c in charts])
    results=[]
    for cells in [3,4,8,16,32]:
        controls=[];residuals=[];seams={}
        for chart,p,source,resolution,source_ids in charts:
            fit_cells=min(cells,resolution)
            cage=fit(p,source,fit_cells)
            if fit_cells!=cells:
                grid=np.stack(np.meshgrid(np.arange(cells+1)/cells,np.arange(cells+1)/cells),axis=-1).reshape(-1,2)
                cage=evaluate(cage,grid,fit_cells)
            predicted=evaluate(cage,p,cells)
            residuals.append(np.linalg.norm(predicted-source,axis=1));controls.append(cage)
            # A shared source position can have independent chart fits; measure separation.
            for source_id,fitted in zip(source_ids,predicted):seams.setdefault(int(source_id),[]).append(fitted)
        residual=np.concatenate(residuals)
        seam_max=0
        for positions in seams.values():
            if len(positions)>1:
                pos=np.array(positions);seam_max=max(seam_max,float(np.max(np.linalg.norm(pos[:,None]-pos[None,:],axis=2))))
        columns=14*(cells+1);rows=7*(cells+1);samples=(columns+1)*(rows+1)*2401
        result=dict(cellsPerChart=cells,sourceSampledBoundaryConstraints=cells!=3,warpColumns=columns,warpRows=rows,controls=(columns+1)*(rows+1),
            controlSamplesAt49x49=samples,minimumPackedXYBytes=samples*8,
            maxSourceResidual=float(residual.max()),p99SourceResidual=float(np.quantile(residual,.99)),
            allRotationProjectedMaxBoundPx=float(residual.max()*scale),allRotationProjectedP99BoundPx=float(np.quantile(residual,.99)*scale),
            allRotationChartSeamMaxBoundPx=seam_max*scale)
        controls=np.array(controls);np.savez_compressed(a.out/f'fit-{cells}.npz',controls3D=controls,pivot=pivot,scale=scale)
        results.append(result);print(json.dumps(result),flush=True)
    inputs[str(report_path)]=hashlib.sha256(report_path.read_bytes()).hexdigest()
    report=dict(status='SOURCE_WARP_FIT_MEASURED_NOT_HOST_IMPORTED',inputsSha256=inputs,results=results,
        scale=scale,pivot=pivot.tolist(),sourceVerticesAndUVNotReplaced=True,sourceTriangleOrderNotChanged=True,
        boundScope='all orthogonal rotations, original chart vertices and same-barycentric triangle interiors; excludes parameter interpolation and float roundoff',
        hostExecuted=False)
    # Spatial fit and parameter interpolation are independent approximation terms.
    # Include the fitted controls' radius (not merely the original source radius).
    selected=np.load(a.out/f'fit-{a.replay_cells}.npz')['controls3D']
    curvature=float(np.max(np.linalg.norm(selected-pivot,axis=2))*scale)
    spatial=next(r['allRotationProjectedMaxBoundPx'] for r in results if r['cellsPerChart']==a.replay_cells)
    count=next(r['controls'] for r in results if r['cellsPerChart']==a.replay_cells)
    report['combinedAllocations']=[]
    for key_count in [13,17,25,49]:
        step=np.deg2rad(60/(key_count-1));error=curvature*step*step/4
        report['combinedAllocations'].append(dict(keysPerAxis=key_count,controlSamples=count*key_count**2,
            minimumPackedXYBytes=8*count*key_count**2,spatialFitBoundPx=spatial,idealParameterInterpolationBoundPx=error,
            combinedAllRotationIdealBoundPx=spatial+error,fitsCurrent4MSampleBudget=count*key_count**2<=4_000_000))
    if a.kernel_classes:
        lib=a.lib;jar=lib/'Live2D_Cubism.jar';assert hashlib.sha256(jar.read_bytes()).hexdigest()==JAR_SHA
        # Replay full atlas (including gaps), float32 normalized inputs, exact official Java kernel.
        cells=a.replay_cells;controls=np.load(a.out/f'fit-{cells}.npz')['controls3D'];columns=14*(cells+1);rows=7*(cells+1)
        atlas=np.zeros((rows+1,columns+1,3))
        unfolded=[]
        for chart,p,_,_,_ in charts:
            x=(chart%14)*(cells+1);y=(chart//14)*(cells+1)
            atlas[y:y+cells+1,x:x+cells+1]=controls[chart].reshape(cells+1,cells+1,3)
            unfolded.append((p*cells+[x,y])/[columns,rows])
        unfolded=np.concatenate(unfolded).astype('>f4');predicted=np.concatenate([evaluate(controls[c[0]],c[1],cells) for c in charts])
        views=[(yaw,pitch) for yaw in [-30,-15,0,15,30] for pitch in [-30,-15,0,15,30]]
        keys=np.linspace(-30,30,a.replay_key_count)
        # Additional off-key poses exercise joint interpolation instead of only endpoints.
        middle=[float((keys[i]+keys[i+1])/2) for i in sorted(set([0,(len(keys)-1)//4,(len(keys)-1)//2,3*(len(keys)-1)//4,len(keys)-2]))]
        views += [(yaw,pitch) for yaw in middle for pitch in middle]
        expected=[];target=[]
        def project(source,yaw,pitch):
            y,p=np.deg2rad([yaw,pitch]);rx=np.array([[1,0,0],[0,np.cos(p),-np.sin(p)],[0,np.sin(p),np.cos(p)]])
            ry=np.array([[np.cos(y),0,np.sin(y)],[0,1,0],[-np.sin(y),0,np.cos(y)]])
            q=(source-pivot)@(ry@rx).T;return q[:,:2]*[scale,-scale]
        def interpolated(source,yaw,pitch):
            yi=min(int(np.searchsorted(keys,yaw,side='right')-1),len(keys)-2)
            pi=min(int(np.searchsorted(keys,pitch,side='right')-1),len(keys)-2)
            assert yi>=0 and pi>=0
            u=(yaw-keys[yi])/(keys[yi+1]-keys[yi]);v=(pitch-keys[pi])/(keys[pi+1]-keys[pi])
            return ((1-u)*(1-v)*project(source,keys[yi],keys[pi])+u*(1-v)*project(source,keys[yi+1],keys[pi])
                +(1-u)*v*project(source,keys[yi],keys[pi+1])+u*v*project(source,keys[yi+1],keys[pi+1]))
        with (a.out/'kernel-input.bin').open('wb') as f:
            f.write(struct.pack('>i',len(views)))
            for yaw,pitch in views:
                f.write(struct.pack('>iii?',columns,rows,len(points),True))
                f.write(interpolated(atlas.reshape(-1,3),yaw,pitch).astype('>f4').tobytes());f.write(unfolded.tobytes())
                expected.append(interpolated(predicted,yaw,pitch));target.append(project(xyz,yaw,pitch))
        command=['java','-Djava.awt.headless=true','-cp',str(a.kernel_classes)+':'+str(lib/'*'),'OfficialWarpKernelProbe',str(a.out/'kernel-input.bin'),str(a.out/'kernel-output.bin')]
        executed=subprocess.run(command,capture_output=True,text=True);(a.out/'kernel.log').write_text(executed.stdout+executed.stderr);assert executed.returncode==0
        actual=np.fromfile(a.out/'kernel-output.bin',dtype='>f4').reshape(len(views),len(points),2)
        basis_error=np.linalg.norm(actual-np.array(expected),axis=2);source_error=np.linalg.norm(actual-np.array(target),axis=2)
        assert np.all(np.isfinite(actual)) and basis_error.max()<.002
        bound=next(r['combinedAllRotationIdealBoundPx'] for r in report['combinedAllocations'] if r['keysPerAxis']==a.replay_key_count)
        assert source_error.max()<=bound+.002
        report['officialJavaKernelOffline']=dict(jarSha256=JAR_SHA,cellsPerChart=cells,parameterKeysPerAxis=a.replay_key_count,
            parameterInterpolation='IDEAL_BILINEAR_OFFLINE_NOT_EDITOR_EVALUATOR',views=views,
            originalChartVerticesPerView=len(points),maxBasisReplayErrorPx=float(basis_error.max()),
            maxOriginalSourceProjectionErrorPx=float(source_error.max()),p99OriginalSourceProjectionErrorPx=float(np.quantile(source_error,.99)),
            quadTransform=True,hostExecuted=False)
    report['scriptsSha256']={str(Path(__file__)):hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (a.out/'report.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--charts',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    p.add_argument('--kernel-classes',type=Path);p.add_argument('--lib',type=Path);p.add_argument('--replay-cells',type=int,default=8)
    p.add_argument('--replay-key-count',type=int,choices=[13,17,25,49],default=17)
    run(p.parse_args())
