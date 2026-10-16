#!/usr/bin/env python3
"""Compare actual native pixels against a source-depth oracle on evaluated native XY.
Only stable 3x3 interiors are compared; filtered texture/AA boundaries are counted as excluded.
Uses fixture plan exports once, never rebuilds or reruns import. Requires numpy and Pillow.
"""
import argparse,csv,json,re,math
from pathlib import Path
import numpy as np
from PIL import Image

def rows(path):
    with path.open() as f:return list(csv.DictReader(f))

def compare(source,poses,atlas,out,expected_poses=18,gate="all"):
    xyzuv={}
    for r in rows(source/'source-plan.vertices.csv'):
        part,slot=int(r['partition']),int(r['slot'])
        xyzuv.setdefault(part,[]).append((slot,*[float(r[k]) for k in ('x','y','z','u','v')]))
    xyzuv={p:np.array(sorted(v))[:,1:] for p,v in xyzuv.items()}
    tris={}
    for r in rows(source/'source-plan.triangles.csv'):
        tris.setdefault(int(r['partition']),[]).append(([int(r[k]) for k in ('a','b','c')],r['doubleSided']=='true'))
    texture=np.array(Image.open(atlas).convert('RGBA'))
    height,width=texture.shape[:2]
    report=[]
    for file in sorted(poses.glob('*.vertices.csv')):
        label=file.name.removesuffix('.vertices.csv')
        match=re.fullmatch(r'yaw([-\d.]+)-pitch([-\d.]+)',label)
        yaw,pitch=map(lambda s:math.radians(float(s)),match.groups())
        native={}; orders={}
        for r in rows(file):
            if r['coordinateType']!='Canvas':raise ValueError('Unverified coordinate type '+r['coordinateType'])
            part=int(re.search(r'(\d+)$',r['mesh']).group(1))
            orders[part]=int(r['order'])
            native.setdefault(part,[]).append((int(r['slot']),float(r['x']),float(r['y'])))
        if set(native)!=set(xyzuv):raise ValueError('Partition mapping differs')
        # Verify target winding and all triangle identities before simulating native painter order.
        actual_tris={}
        for r in rows(poses/(label+'.triangles.csv')):
            p=int(re.search(r'(\d+)$',r['mesh']).group(1))
            actual_tris.setdefault(p,[]).append([int(r[k]) for k in ('a','c','b')])
        if actual_tris != {p:[ids for ids,_ in ts] for p,ts in tris.items()}:
            raise ValueError('Native source triangle identity/winding differs')
        pixels=np.array(Image.open(poses/(label+'.png')).convert('RGBA'))
        h,w=pixels.shape[:2]; depth=np.full((h,w),-np.inf); expected=np.zeros((h,w,4),dtype=np.uint8); owner=np.full((h,w),-1)
        paint=np.full((h,w),-np.inf); paint_order=np.full((h,w),-1); paint_part=np.full((h,w),-1); tie=np.zeros((h,w),dtype=bool)
        for p in sorted(xyzuv,key=lambda p:(orders[p],p)):
            data=xyzuv[p]
            xy=np.array(sorted(native[p]))[:,1:]
            if len(xy)!=len(data):raise ValueError('Source/native slot count differs')
            z=-data[:,0]*math.sin(yaw)+(data[:,1]*math.sin(pitch)+data[:,2]*math.cos(pitch))*math.cos(yaw)
            for ids,double in tris[p]:
                a,b,c=ids;x0,y0=xy[a];x1,y1=xy[b];x2,y2=xy[c]
                d=(y1-y2)*(x0-x2)+(x2-x1)*(y0-y2)
                if abs(d)<1e-8 or (not double and d>=0):continue
                xa=max(0,int(np.floor(min(x0,x1,x2))));xb=min(w-1,int(np.ceil(max(x0,x1,x2))))
                ya=max(0,int(np.floor(min(y0,y1,y2))));yb=min(h-1,int(np.ceil(max(y0,y1,y2))))
                if xa>xb or ya>yb:continue
                yy,xx=np.mgrid[ya:yb+1,xa:xb+1];xx=xx+.5;yy=yy+.5
                w0=((y1-y2)*(xx-x2)+(x2-x1)*(yy-y2))/d
                w1=((y2-y0)*(xx-x2)+(x0-x2)*(yy-y2))/d;w2=1-w0-w1
                zz=w0*z[a]+w1*z[b]+w2*z[c]
                target=depth[ya:yb+1,xa:xb+1]
                inside=(w0>=0)&(w1>=0)&(w2>=0)
                previous=paint[ya:yb+1,xa:xb+1]; previous_order=paint_order[ya:yb+1,xa:xb+1]
                # Equal native integer ranks cannot assume partition-index tie precedence.
                ties=tie[ya:yb+1,xa:xb+1]
                previous_part=paint_part[ya:yb+1,xa:xb+1]
                ties[inside & (previous_order<orders[p])]=False
                ties[inside & (previous_part!=p) & (previous_order==orders[p]) & np.isfinite(previous) & (np.abs(previous-zz)>1e-5)]=True
                previous[inside]=zz[inside]; previous_order[inside]=orders[p]; previous_part[inside]=p
                mask=inside&(zz>target+1e-7)
                u=w0*data[a,3]+w1*data[b,3]+w2*data[c,3]
                v=w0*data[a,4]+w1*data[b,4]+w2*data[c,4]
                tx=(u%1)*width-.5;ty=(v%1)*height-.5
                xlo=np.floor(tx).astype(int);ylo=np.floor(ty).astype(int);fx=(tx-xlo)[:,:,None];fy=(ty-ylo)[:,:,None]
                rgb=np.rint(texture[ylo%height,xlo%width]*(1-fx)*(1-fy)
                    +texture[ylo%height,(xlo+1)%width]*fx*(1-fy)
                    +texture[(ylo+1)%height,xlo%width]*(1-fx)*fy
                    +texture[(ylo+1)%height,(xlo+1)%width]*fx*fy).astype(np.uint8)
                owner[ya:yb+1,xa:xb+1][mask]=p
                target[mask]=zz[mask];expected[ya:yb+1,xa:xb+1][mask]=rgb[mask]
        covered=np.isfinite(depth)
        depth_wrong=int(np.count_nonzero(covered & (np.subtract(depth,paint,where=covered,out=np.zeros_like(depth))>1e-5)))
        # Mesh boundaries are a separate seam metric, not a stable occlusion interior.
        owner_windows=[owner[dy:h-2+dy,dx:w-2+dx] for dy in range(3) for dx in range(3)]
        interior=np.minimum.reduce(owner_windows)==np.maximum.reduce(owner_windows)
        wrong_mask=covered & (np.subtract(depth,paint,where=covered,out=np.zeros_like(depth))>1e-5)
        interior_wrong=int(np.count_nonzero(interior & wrong_mask[1:-1,1:-1]))
        tied_pixels=int(np.count_nonzero(tie))
        interior_ties=int(np.count_nonzero(interior & tie[1:-1,1:-1]))
        # Stable interiors ensure a sharp, disclosed signal, separate from edge rasterization.
        windows=[expected[dy:h-2+dy,dx:w-2+dx] for dy in range(3) for dx in range(3)]
        low=np.minimum.reduce(windows);high=np.maximum.reduce(windows)
        stable=np.max(high.astype(int)-low.astype(int),axis=2)<=12
        ep=expected[1:-1,1:-1];actual=pixels[1:-1,1:-1]
        fore=stable&(ep[:,:,3]>128);empty=stable&(ep[:,:,3]==0)
        color=np.max(np.abs(ep[:,:,:3].astype(int)-actual[:,:,:3].astype(int)),axis=2)
        bad=fore&((actual[:,:,3]<128)|(color>20))
        extra=empty&(actual[:,:,3]>128)
        wrong=int(np.count_nonzero(bad));added=int(np.count_nonzero(extra));tested=int(np.count_nonzero(fore))
        report.append(dict(pose=label,nativeOrderWrongDepthPixels=depth_wrong,ambiguousIntegerTiePixels=tied_pixels,nativeOrderInteriorWrongPixels=interior_wrong,nativeOrderInteriorTiePixels=interior_ties,nativeOrderPassed=interior_wrong==0 and interior_ties==0,testedInteriorPixels=tested,wrongInteriorPixels=wrong,extraInteriorPixels=added,
                           excludedPixels=int(stable.size-np.count_nonzero(stable)),passed=tested>0 and wrong==0 and added==0))
        if wrong or added:
            diagnostic=pixels.copy();diag=diagnostic[1:-1,1:-1];diag[bad]=[255,0,0,255];diag[extra]=[0,255,255,255]
            Image.fromarray(diagnostic).save(out/(label+'.errors.png'))
    if len(report)!=expected_poses:raise ValueError(f'Expected {expected_poses} native poses, got {len(report)}')
    (out/'native-pixel-results.json').write_text(json.dumps({'acceptanceGate':gate,'nativeOrderScope':'Exported triangle identity and integer draw order on evaluated Canvas XY; stable 3x3 same-partition interiors. Boundary errors and rank ties reported separately.','scope':'Native evaluated XY and source projected depth; stable 3x3 texture interiors, RGB tolerance 20; edges excluded. Does not assert exact 3D silhouette interpolation.','poses':report},indent=2))
    print(json.dumps({'gate':gate,'poses':len(report),'strictRGBPassed':sum(r['passed'] for r in report),'nativeOrderPassed':sum(r['nativeOrderPassed'] for r in report),'nativeOrderInteriorWrong':sum(r['nativeOrderInteriorWrongPixels'] for r in report),'nativeOrderInteriorTies':sum(r['nativeOrderInteriorTiePixels'] for r in report),'nativeOrderWrong':sum(r['nativeOrderWrongDepthPixels'] for r in report),'ambiguousTies':sum(r['ambiguousIntegerTiePixels'] for r in report),'wrong':sum(r['wrongInteriorPixels'] for r in report),'extra':sum(r['extraInteriorPixels'] for r in report)}))
    return all(r['nativeOrderPassed'] and (gate=='order-interiors' or r['passed']) for r in report)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('source',type=Path);p.add_argument('poses',type=Path);p.add_argument('atlas',type=Path);p.add_argument('out',type=Path);p.add_argument('--expected-poses',type=int,default=18);p.add_argument('--gate',choices=('all','order-interiors'),default='all');a=p.parse_args();a.out.mkdir(parents=True,exist_ok=True)
    raise SystemExit(0 if compare(a.source,a.poses,a.atlas,a.out,a.expected_poses,a.gate) else 1)
