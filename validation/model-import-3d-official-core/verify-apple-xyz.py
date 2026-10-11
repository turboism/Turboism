#!/usr/bin/env python3
"""Full original Apple XYZ source oracle. Finite geometry gate, not pixel acceptance."""
import argparse
import gzip
import hashlib
import json
import math
from pathlib import Path
import runpy
import struct
import numpy as np

HERE = Path(__file__).resolve().parent
JOB = runpy.run_path(str(HERE/'verify-apple-job.py'))
SOURCE = runpy.run_path(str(HERE.parent/'model-import-3d-canonical-warp/verify-canonical-warp-layered.py'))
require, read, sha = (JOB[k] for k in ('require', 'read', 'sha'))


def poses():
    result = [[0,0,0],[0,-180,0],[0,180,0],[-180,0,0],[180,0,0],[0,0,-180],
              [0,0,180],[0,90,0],[90,0,0],[0,0,90],[-112.5,37.5,45],[75,-150,-135]]
    seed = (0x33445859 ^ 0x5DEECE66D) & ((1 << 48)-1)
    def bits(n):
        nonlocal seed
        seed = (seed*0x5DEECE66D+11) & ((1 << 48)-1)
        return seed >> (48-n)
    for _ in range(128):
        result.append([float(np.float32(-180+360*((bits(26)*(1<<27)+bits(27))/(1<<53))))
                       for _ in range(3)])
    return result


def projection(xyz, pose):
    # Capture values are yaw, pitch, roll. Independent closed Rz*Ry*Rx matrix.
    y,p,r = map(math.radians, pose)
    cy,sy,cp,sp,cr,sr = math.cos(y),math.sin(y),math.cos(p),math.sin(p),math.cos(r),math.sin(r)
    matrix = np.array([[cr*cy, cr*sy*sp-sr*cp, cr*sy*cp+sr*sp],
                       [sr*cy, sr*sy*sp+cr*cp, sr*sy*cp-cr*sp],
                       [-sy, cy*sp, cy*cp]])
    world = (xyz-[0,130.6125549674034,0]) @ matrix.T
    return world[:,:2]*[2.954462184876577,-2.954462184876577]+[512,512]


def frames(capture, file, vertex_count=100258):
    baseline = capture['baselineDrawables']; invariants = {}
    with gzip.open(file, 'rb') as stream:
        require(stream.read(16) == b'APXYZ001'+struct.pack('>ii',140,vertex_count), 'exact XYZ coordinate header')
        for frame in capture['frames']:
            require([r['id'] for r in frame['drawables']] == [r['id'] for r in baseline], 'stable full native mesh order')
            rows = []
            for base, scalar in zip(baseline, frame['drawables']):
                identity = base['id']; n = base['vertexCount']; fingerprint = scalar['invariantSha256']
                require(scalar['vertexCount'] == n and isinstance(fingerprint,str) and len(fingerprint)==64
                        and all(c in '0123456789abcdef' for c in fingerprint), 'full scalar invariant identity')
                require(invariants.setdefault(identity,fingerprint)==fingerprint, 'source topology/UV invariants across updates')
                raw = stream.read(n*8)
                require(len(raw)==n*8, 'complete native coordinates')
                rows.append(dict(base, **scalar, positions=np.frombuffer(raw,dtype='>f4').reshape(n,2)))
            yield dict(frame, drawables=rows)
        require(stream.read(1)==b'', 'no trailing coordinate payload')


def order_bounds(mesh, pose, axes):
    by_guid = {a['guid']:a['id'] for a in mesh['axes']}
    forms = {tuple(dict((by_guid[g],v) for g,v in f['coordinates'])[a] for a in axes[:2]):f['drawOrder']
             for f in mesh['forms']}
    require(len(forms)==49 and len(mesh['forms'])==49, 'complete source 7 by 7 draw order bank')
    brackets=[]
    for axis,value in zip(axes[:2],pose[:2]):
        keys=next(a['keys'] for a in mesh['axes'] if a['id']==axis)
        require(keys==list(range(-180,181,60)), 'full source layer keys')
        i=min(5,max(0,int(np.searchsorted(keys,value,side='right')-1)))
        brackets.append((keys[i],keys[i+1],(value-keys[i])/60))
    a,b,fy=brackets[0];c,d,fp=brackets[1]
    value=float(np.float32((1-fy)*((1-fp)*forms[a,c]+fp*forms[a,d])+fy*((1-fp)*forms[b,c]+fp*forms[b,d])))
    return math.floor(value),math.ceil(value)


def geometry(capture, report, prepared, artifacts, file, derived=False):
    require(capture['schemaVersion']==('mi3d-apple-derived-xyz-official-core-capture-v1' if derived else 'mi3d-apple-xyz-official-core-capture-v1')
            and capture['poseProtocol']==('mi3d-apple-derived-xyz-140-v1' if derived else 'mi3d-apple-xyz-140-v1') and capture['status']=='CAPTURED_NOT_VERIFIED', 'explicit full XYZ capture')
    protocol=poses()
    require([f['pose'] for f in capture['frames']]==protocol, 'all 140 ordered independent XYZ poses')
    require(capture['canvas']==dict(size=[1024,1024],origin=[512,512],pixelsPerUnit=100), 'fixed source canvas')
    require(report['status']=='PASS' and report['inputSha256']==SOURCE['SOURCE_SHA'], 'original Apple source')
    export=report['officialExport'];packed=json.loads(export['packedStateJson']);axes=capture['axes']
    require(len(axes)==len(set(axes))==3 and all(a.startswith('Param3D'+name) for a,name in zip(axes,('Yaw','Pitch','Roll'))), 'three distinct rotation axes')
    moc=artifacts/export['moc']['artifact']
    require(moc.stat().st_size==capture['mocBytes']==export['moc']['bytes'] and sha(moc)==capture['mocSha256']==export['moc']['sha256'], 'exact complete MOC')
    authored={p[0]:p for p in packed['parameters']};params=capture['parameters'];ids=params['ids']
    require(len(ids)==len(set(ids))==len(authored) and set(ids)==set(authored), 'complete parameter identity')
    for field in ('minimum','maximum','default','keyCounts','keys','repeats'):
        require(len(params[field])==len(ids), 'parameter array lengths')
    for i,identity in enumerate(ids):
        p=authored[identity]
        require([params[f][i] for f in ('minimum','default','maximum','repeats')]==[p[1],p[2],p[3],False], 'original parameter definitions')
        keys=[-180+7.5*k for k in range(49)] if identity in axes[:2] else [-180,-90,0,90,180] if identity==axes[2] else []
        require(params['keys'][i]==keys and params['keyCounts'][i]==len(keys), 'original full XYZ keys')
    sources,_=SOURCE['prepared'](prepared);meshes=packed['meshes'];baseline=capture['baselineDrawables']
    texture_pages={}
    if derived:
        edit=runpy.run_path(str(HERE.parent/'model-import-3d-user-entry/analyze-derived-bound-edit.py'))
        charts=edit['fixture'](HERE.parents[1]/'build/model-import-3d-offline/apple-all-chart-engine-r1-20261003/derived-bound-all98-aligned-v4.bin')
        derived_sources=[]
        for mesh in meshes:
            i=int(mesh['id'].removeprefix('ArtMeshAppleEditableChart'));c=charts[i];source=sources[i]
            require(np.array_equal(np.asarray(c['remap'])[source['indices']],np.asarray(c['indices']).reshape(-1,3)), 'every original face remapped exactly')
            derived_sources.append(dict(source,xyz=source['xyz'][c['representatives']],indices=np.asarray(c['indices']).reshape(-1,3),count=c['n']))
        sources=derived_sources
        placements={p[0]:p for p in export['initialAtlasLayout']['placements']}
        for row in report['imported']['meshMetadata']:
            texture_pages[row['id']]=placements[row['input']['image']][1]
    inventory=[m['id'] for m in meshes]
    require(len(meshes)==len(set(inventory))==len(sources)==len(baseline)==98
            and [r['id'] for r in baseline]==inventory, 'full original mesh order')
    expected=[]
    require(len(export['packedSource'])==98, 'complete packed source evidence')
    for i,(mesh,source,receipt) in enumerate(zip(meshes,sources,export['packedSource'])):
        n,t=source['count'],source['triangles'];raw=(artifacts/f'packed-source/{i}.bin').read_bytes()
        require(mesh['vertexCount']==n and mesh['triangleCount']==t and len(raw)==n*16+t*12
                and receipt['artifact']==f'packed-source/{i}.bin' and receipt['id']==mesh['id']
                and receipt['bytes']==len(raw) and hashlib.sha256(raw).hexdigest()==mesh['geometrySha256']==receipt['sha256'], 'packed geometry identity')
        floats=np.frombuffer(raw[:n*16],dtype='>f4').reshape(2,n,2)
        ix=np.frombuffer(raw[n*16:],dtype='>i4').reshape(t,3)
        require(np.array_equal(ix,source['indices']), 'every original triangle retained')
        uv=floats[1].astype(np.float32);uv[:,1]=np.float32(1)-uv[:,1]
        expected.append((uv,ix[:,::-1].ravel().tolist()))
    maximum=0.;samples=0;results=[];poses_over_one_pixel=0
    for frame,pose in zip(frames(capture,file,80514 if derived else 100258),protocol):
        values=np.asarray(frame['values'],dtype=float)
        require(values.shape==(len(ids),) and np.all(np.isfinite(values)), 'finite complete parameters')
        for i,identity in enumerate(ids):
            wanted=pose[axes.index(identity)] if identity in axes else authored[identity][2]
            require(np.float32(values[i])==np.float32(wanted), 'exact requested and default values')
        rows=frame['drawables']
        require(sorted(r['renderOrder'] for r in rows)==list(range(98)), 'full render order permutation')
        pose_error=0.
        for mesh,source,row,(uv,ix) in zip(meshes,sources,rows,expected):
            n=source['count']
            require(row['id']==mesh['id'] and row['vertexCount']==n and row['indices']==ix, 'full original topology and reversed Core winding')
            actual_uv=np.asarray(row['uvs'],dtype=np.float32)
            require(actual_uv.shape==(n*2,) and np.all(np.isfinite(actual_uv))
                    and np.max(np.abs(actual_uv.reshape(n,2)-uv))<=2**-23, 'packed atlas UV Y flip')
            require(row['textureIndex']==texture_pages.get(mesh['id'],0) and row['masks']==[] and row['constantFlags']==0
                    and math.isfinite(row['opacity']) and abs(row['opacity']-1)<=4*2**-23, 'source culling, texture and opacity')
            multiply=np.asarray(row['multiplyColor'],dtype=np.float32);screen=np.asarray(row['screenColor'],dtype=np.float32)
            require(row['blendMode']==0 and multiply.shape==screen.shape==(4,) and np.all(np.isfinite(multiply))
                    and np.all(np.isfinite(screen)) and np.max(np.abs(multiply-[1,1,1,1]))<=4*2**-23
                    and np.max(np.abs(screen-[0,0,0,1]))<=4*2**-23, 'original texture colors')
            low,high=order_bounds(mesh,pose,axes)
            require(type(row['drawOrder']) is int and low<=row['drawOrder']<=high, 'source layer integer enclosure')
            actual=row['positions'].astype(float)*[100,-100]+[512,512]
            require(actual.shape==(n,2) and np.all(np.isfinite(actual)), 'all finite native vertex slots')
            error=float(np.linalg.norm(actual-projection(source['xyz'],pose),axis=1).max())
            # Use the established full +/-180 XYZ source oracle's 5px budget
            # (model-import-3d-user-entry/verify-apple-xyz.py). The earlier
            # +/-30 two-axis 1px gate remains unchanged and is not this scope.
            require(error<=5, f'{mesh["id"]} {pose} exceeds existing full XYZ 5px budget: {error}')
            pose_error=max(pose_error,error);samples+=n
        maximum=max(maximum,pose_error);poses_over_one_pixel+=int(pose_error>1)
        results.append(dict(pose=pose,maximumCanvasErrorPixels=pose_error))
    return dict(meshes=98,triangles=150784,poses=140,checkedVertexSamples=samples,
                maximumCanvasErrorPixels=maximum,toleranceCanvasPixels=5,posesExceedingOnePixel=poses_over_one_pixel,
                toleranceSource='model-import-3d-user-entry/verify-apple-xyz.py full +/-180 XYZ gate',topologyExact=True,
                packedUVVerified=True,layerIntegerEnclosureVerified=True,poseResults=results)


def verify(controller,bundle,export_acceptance,recorder,prepared):
    identity,capture,_=JOB['process'](controller,bundle,export_acceptance,recorder,'apple-xyz-140-v1')
    artifacts=export_acceptance.parent/'native-result/state';file=controller/'host-job/evidence/standalone-core/capture/positions-f32.bin.gz'
    coords=capture['coordinates']
    require(coords['artifact']==file.name and coords['verticesPerFrame']==100258
            and coords['uncompressedBytes']==16+140*100258*8 and 0<file.stat().st_size==coords['bytes']<=512*1024*1024
            and sha(file)==coords['sha256'], 'bounded complete compressed XYZ capture')
    result=geometry(capture,read(artifacts/'report.json'),prepared,artifacts,file)
    return dict(status='PASS_NATIVE_APPLE_FINITE_XYZ_GEOMETRY',**identity,geometry=result,normalExit=True,
                sourceMatrix='Rz(roll)*Ry(yaw)*Rx(pitch)',officialCoreAccepted=True,
                nativePixelCorrectnessAccepted=False,officialViewerAccepted=False,continuousDomainAccepted=False,
                captureSha256=sha(file.with_name('core-capture.json')),coordinatesSha256=sha(file),goalComplete=False)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('controller','bundle','export-acceptance','recorder','prepared','out'):
        parser.add_argument('--'+name,type=Path,required=True)
    a=parser.parse_args();result=verify(a.controller,a.bundle,a.export_acceptance,a.recorder,a.prepared)
    with a.out.open('x') as stream:json.dump(result,stream,indent=2);stream.write('\n')
    print(json.dumps(result,indent=2))
