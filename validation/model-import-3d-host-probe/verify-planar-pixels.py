"""Fixed plane/seam source-texture pixel reference; no native/process admission.

1024 canvas, Ry*Rx, orthographic 200px/unit, nine fixed authored poses.
4px sample grid at pixel centres, existing 1px triangle-edge exclusion,
exact alpha and RGB tolerance 2. No texture-derived masks or fitting.
"""
import functools
import hashlib
import io
import json
import math
from pathlib import Path
import runpy
import struct
from PIL import Image

HERE=Path(__file__).resolve().parent
EDGE=runpy.run_path(str(HERE/'verify-cube-render.py'))['segment_distance']
POSES=tuple((yaw,pitch) for yaw in (-30,0,30) for pitch in (-30,0,30))
HASHES=('8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997',
        '89d1617c9555250d504136c081abec11b2f3e00fde45237d633bdb2096585f34',
        'c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd')

def require(ok,why):
    if not ok:raise ValueError('PLANAR_PIXELS: '+why)

@functools.lru_cache(maxsize=3)
def source(raw):
    require(hashlib.sha256(raw).hexdigest() in HASHES,'fixed source GLB')
    n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[n+28:]
    primitive=doc['meshes'][0]['primitives'][0]
    def accessor(index,fmt):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        offset=v.get('byteOffset',0)+a.get('byteOffset',0);size=struct.calcsize('<'+fmt)
        require(a['count']*size==v['byteLength'],'packed accessor')
        return tuple(struct.unpack_from('<'+fmt,binary,offset+i*size) for i in range(a['count']))
    vertices=accessor(primitive['attributes']['POSITION'],'3f')
    uvs=accessor(primitive['attributes']['TEXCOORD_0'],'2f')
    indices=tuple(x[0] for x in accessor(primitive['indices'],'H'))
    material=doc['materials'][primitive['material']]['pbrMetallicRoughness']
    require(material['baseColorFactor']==[1,1,1,1],'unmodified source tint')
    texture=doc['textures'][material['baseColorTexture']['index']]
    require(doc['samplers'][texture['sampler']]==dict(wrapS=33071,wrapT=33071,minFilter=9729,magFilter=9729),'linear clamp sampler')
    v=doc['bufferViews'][doc['images'][texture['source']]['bufferView']]
    with Image.open(io.BytesIO(binary[v['byteOffset']:v['byteOffset']+v['byteLength']])) as image:
        require(image.size==(256,128) and image.mode=='RGB','source image format')
        image=image.convert('RGBA').copy()
    return vertices,uvs,indices,image

def linear(image,u,v):
    require(0<=u<=1 and 0<=v<=1,'source UV domain')
    x=u*image.width-.5;y=v*image.height-.5;ix=math.floor(x);iy=math.floor(y)
    fx=x-ix;fy=y-iy
    def pixel(dx,dy):
        return image.getpixel((min(image.width-1,max(0,ix+dx)),min(image.height-1,max(0,iy+dy))))
    a,b,c,d=pixel(0,0),pixel(1,0),pixel(0,1),pixel(1,1)
    return tuple(round((1-fy)*((1-fx)*a[k]+fx*b[k])+fy*((1-fx)*c[k]+fx*d[k])) for k in range(4))

@functools.lru_cache(maxsize=54)
def reference(raw,pose,*,framed=False):
    require(type(framed) is bool,'explicit framing profile')
    require(pose in POSES,'declared pose')
    vertices,uvs,indices,image=source(raw)
    yaw,pitch=map(math.radians,pose);cy,sy,cp,sp=math.cos(yaw),math.sin(yaw),math.cos(pitch),math.sin(pitch)
    scale=400/math.sqrt(1.25) if framed else 200
    projected=[(512+scale*(cy*x+sy*sp*y+sy*cp*z),512-scale*(cp*y-sp*z)) for x,y,z in vertices]
    triangles=[tuple(indices[k:k+3]) for k in range(0,len(indices),3)]
    points=[];coverage=[0]*len(triangles);marked=0
    for iy in range(2,1024,4):
        for ix in range(2,1024,4):
            x,y=ix+.5,iy+.5;edge=False;color=(0,0,0,0);surface=None
            for index,tri in enumerate(triangles):
                a,b,c=[projected[i] for i in tri]
                if not (min(a[0],b[0],c[0])-1<=x<=max(a[0],b[0],c[0])+1 and min(a[1],b[1],c[1])-1<=y<=max(a[1],b[1],c[1])+1):continue
                edge |= min(EDGE(x,y,a,b),EDGE(x,y,b,c),EDGE(x,y,c,a))<=1
                area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
                # Source winding faces camera throughout the declared +/-30 degree domain.
                require(area>0,'front-facing source triangle')
                v=((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
                w=((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area;u=1-v-w
                if min(u,v,w)>=0:
                    uv=tuple(sum(weight*uvs[i][k] for weight,i in zip((u,v,w),tri)) for k in (0,1))
                    color=linear(image,*uv);surface=index
            if edge:continue
            if surface is not None:
                coverage[surface]+=1
                if max(color[:3])-min(color[:3])<15:marked+=1
            points.append((ix,iy,color))
    require(min(coverage)>100 and marked>0,'both triangles and asymmetric mark coverage')
    return tuple(points),tuple(coverage),marked

def inspect(rows,raw,pose,*,framed=False):
    require(len(rows)==1024 and all(len(row)==4096 for row in rows),'RGBA dimensions')
    points,coverage,marked=reference(raw,tuple(pose),framed=framed);errors=0;first=None
    for x,y,expected in points:
        actual=tuple(rows[y][4*x:4*x+4])
        if actual[3]!=expected[3] or (expected[3]!=0 and any(abs(a-b)>2 for a,b in zip(actual[:3],expected[:3]))):
            errors+=1
            if first is None:first=dict(x=x,y=y,actual=actual,expected=expected)
    return dict(samples=len(points),triangleCoverage=coverage,markSamples=marked,mismatches=errors,firstMismatch=first,
                processAdmitted=False)

def verify_frames(section, geometry, raw, artifact_reader):
    """Bind all nine PNGs to independently admitted planar geometry and native observations."""
    import copy
    geom=runpy.run_path(str(HERE/'verify-surface-geometry.py'))
    geom['verify_planar'](geometry,raw);f32=geom['f32']
    sha=hashlib.sha256(raw).hexdigest()
    vertices=source(raw)[0]
    slots=((0,2,1,3),) if sha==HASHES[0] else ((0,1,2),(3,4,5))
    sources=[[vertices[i] for i in patch] for patch in slots]
    count=len(sources)
    reference=runpy.run_path(str(HERE/'verify-cube-render.py'))
    require(section['status']=='CAPTURED' and section['pixelAcceptance']=='REQUIRES_INDEPENDENT_ORACLE'
            and section['inputSha256']==sha,'capture scope/status')
    require(section['poses']==[list(p) for p in POSES] and len(section['frames'])==9,'complete fixed frame inventory')
    entry=section['entryState'];history=section['entryHistory']
    protocol=section.get('historyProtocol')
    require(protocol==geometry.get('historyProtocol') and protocol in (None,'POSE_DRIVER_UNDO_V1','POSE_DRIVER_UNDO_RETURN_V2'),'pixel history protocol binding')
    require(section['finallyRestored'] is True and section['restoredState']==entry,'capture restoration')
    if protocol:
        runpy.run_path(str(HERE/'verify-pose-drive.py'))['verify_pixels'](section,geometry,geom['AXES'])
    else:
        require(section['restoredHistory']==history and history==geometry['historyAfter'],'capture restoration/history binding')
    require(len(entry['meshes'])==count and entry['canvas'][:2]==[1024,1024],'isolated render canvas')
    for state,stored in zip(entry['meshes'],geometry['after']):
        require(state['id']==stored['artMeshId'] and state['snapshotSha256']==reference['fingerprint'](stored)
                and state['culling'] is True and state['visible'] is True,'render mesh/state binding')
    def evaluation(e,surface,pose):
        stored=geometry['after'][surface]
        require((e['artMeshId'],e['sourceGuid'])==(stored['artMeshId'],stored['sourceGuid']),'frame evaluated identity')
        require(e['passEntryTimeMillis']>=0 and geom['parameters'](e,'live')==geom['parameters'](e,'consumed'),'frame consumed state')
        for axis,value in zip(geom['AXES'],pose):
            require([v for name,v in geom['parameters'](e,'live').values() if name==axis]==[value],'frame evaluated pose')
        form=e['calculated']
        require(form['interpolated'] is True and form['coordinateType'] in ('Canvas','DeformerLocal')
                and type(form['drawOrder']) is int and form['drawOrder']==500,'frame calculated state/order')
        require(len(form['positions'])==len(sources[surface]) and all(len(p)==2 for p in form['positions']),'frame vertex count')
        error=max(math.dist([f32(v) for v in p],q) for p,q in zip(form['positions'],geom['truth_vertices'](sources[surface],*pose)))
        require(error<=.1,'frame geometric error')
    results=[]
    for index,(frame,pose) in enumerate(zip(section['frames'],POSES)):
        name=f'planar-render/{index}.png'
        require(frame['index']==index and frame['pose']==list(pose) and frame['artifact']==name
                and frame['status']=='CAPTURED','frame identity')
        expected=copy.deepcopy(entry)
        for axis,value in zip(geom['AXES'],pose):
            parameters=[p for p in expected['parameters'] if p[0]==axis]
            require(len(parameters)==1 and parameters[0][3]==0,'neutral entry parameter')
            parameters[0][3]=value
        require(frame['stateBefore']==frame['stateAfter']==expected and frame['historyBefore']==frame['historyAfter']
                and (protocol or frame['historyBefore']==history),
                'frame state/history mutation')
        require(len(frame['consumption'])==len(frame['evaluationAfter'])==count,'frame evaluation inventory')
        for surface,(consumed,after) in enumerate(zip(frame['consumption'],frame['evaluationAfter'])):
            requested,actual=consumed['requested'],consumed['actual']
            require(consumed['waitReason']=='CONSUMED' and consumed['lastObservation']==actual
                    and type(consumed['attempts']) is int and consumed['attempts']>0 and 0<=consumed['elapsedMillis']<15000,'frame wait witness')
            require((requested['artMeshId'],requested['sourceGuid'])==(actual['artMeshId'],actual['sourceGuid'])
                    and geom['parameters'](requested,'live')==geom['parameters'](actual,'live')
                    and requested['consumed']['updateVersion']!=actual['consumed']['updateVersion'],'frame fresh identity/consumption')
            evaluation(actual,surface,pose);evaluation(after,surface,pose)
        require(type(frame['artifactBytes']) is int and 0<frame['artifactBytes']<=8*1024*1024,'frame PNG budget')
        png=artifact_reader(name)
        require(len(png)==frame['artifactBytes'] and hashlib.sha256(png).hexdigest()==frame['artifactSha256'],'frame PNG identity')
        result=inspect(reference['PNG'](png),raw,pose)
        require(result['mismatches']==0,'source visibility pixel mismatch: '+str(result['firstMismatch']))
        results.append(result)
    return dict(status='PLANAR_FRAME_STATE_AND_PIXELS_CONTENT_ONLY_PASS',frames=results,processAdmitted=False)
