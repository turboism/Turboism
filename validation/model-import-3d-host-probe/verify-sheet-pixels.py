"""Fixed-source sheet ray reference. Native capture and process admission remain separate."""
import functools
import hashlib
import json
import math
from pathlib import Path
import runpy
import struct

HERE=Path(__file__).resolve().parent
EDGE=runpy.run_path(str(HERE/'verify-cube-render.py'))['segment_distance']
SHA='0c0f716da082c9987ba580565a97ec81237c4e0333ae4023042b9c17b41a9984'
POSES=tuple((y,p) for y in (-30,0,30) for p in (-30,0,30))
def require(ok,why):
    if not ok:raise ValueError('SHEET_PIXELS: '+why)
@functools.lru_cache(maxsize=9)
def samples(raw,pose):
    require(hashlib.sha256(raw).hexdigest()==SHA,'exact source GLB')
    require(pose in POSES,'declared pose')
    n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
    def values(index,fmt):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        offset=v.get('byteOffset',0)+a.get('byteOffset',0);size=struct.calcsize('<'+fmt)
        require(a['count']*size==v['byteLength'],'packed source accessor')
        return [struct.unpack_from('<'+fmt,binary,offset+i*size) for i in range(a['count'])]
    yaw,pitch=map(math.radians,pose);cy,sy,cp,sp=math.cos(yaw),math.sin(yaw),math.cos(pitch),math.sin(pitch)
    triangles=[];colors=[]
    for surface,p in enumerate(doc['meshes'][0]['primitives']):
        source=values(p['attributes']['POSITION'],'3f');indices=[x[0] for x in values(p['indices'],'H')]
        vertices=[(512+200*(cy*x+sy*sp*y+sy*cp*z),512-200*(cp*y-sp*z),-sy*x+cy*sp*y+cy*cp*z) for x,y,z in source]
        material=doc['materials'][p['material']]
        require(material['doubleSided'] is True,'source double-sided material')
        colors.append(tuple(round(c*255) for c in material['pbrMetallicRoughness']['baseColorFactor']))
        for k in (0,3):
            a,b,c=[vertices[i] for i in indices[k:k+3]]
            area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
            triangles.append((surface,a,b,c,area))
    points=[];coverage=[0,0];overlap=0
    for iy in range(2,1024,4):
        for ix in range(2,1024,4):
            x,y=ix+.5,iy+.5;hits={};edge=False
            for surface,a,b,c,area in triangles:
                if not (min(a[0],b[0],c[0])-1<=x<=max(a[0],b[0],c[0])+1 and min(a[1],b[1],c[1])-1<=y<=max(a[1],b[1],c[1])+1):continue
                edge|=min(EDGE(x,y,a,b),EDGE(x,y,b,c),EDGE(x,y,c,a))<=1
                if abs(area)<1e-10:continue
                v=((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
                w=((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area;u=1-v-w
                if min(u,v,w)>=0:hits[surface]=u*a[2]+v*b[2]+w*c[2]
            if edge:continue
            nearest=min(hits,key=hits.get) if hits else None
            if nearest is not None:coverage[nearest]+=1
            overlapping=len(hits)==2;overlap+=overlapping
            points.append((ix,iy,nearest,overlapping))
    require((sum(coverage)==0 and overlap==0) if pose[0]==0 else (min(coverage)>100 and overlap>100),'visible/overlap coverage')
    return points,tuple(colors),coverage,overlap

def inspect_pixels(rows,raw,pose):
    require(len(rows)==1024 and all(len(row)==4096 for row in rows),'RGBA dimensions')
    points,colors,coverage,overlap=samples(raw,tuple(pose));errors=0;first=None;overlap_errors=0
    for x,y,near,overlapping in points:
        actual=tuple(rows[y][4*x:4*x+4]);expected=(0,0,0,0) if near is None else colors[near]
        bad=actual[3]!=0 if near is None else actual[3]!=255 or any(abs(a-b)>2 for a,b in zip(actual[:3],expected[:3]))
        if bad:
            errors+=1;overlap_errors+=overlapping
            if first is None:first=dict(x=x,y=y,near=near,actual=actual,expected=expected)
    return dict(samples=len(points),coverage=coverage,overlapSamples=overlap,mismatches=errors,overlapMismatches=overlap_errors,firstMismatch=first)

def verify_frames(section, geometry, raw, artifact_reader):
    """Bind all nine PNGs to independently admitted geometry/order and native observations."""
    import copy
    order_api=runpy.run_path(str(HERE/'verify-sheet-order.py'))
    order_api['verify_order'](geometry,raw)
    geom=order_api['GEOMETRY'];f32=geom['f32']
    reference=runpy.run_path(str(HERE/'verify-cube-render.py'))
    require(section['status']=='CAPTURED' and section['pixelAcceptance']=='REQUIRES_INDEPENDENT_ORACLE'
            and section['inputSha256']==SHA,'capture scope/status')
    require(section['poses']==[list(p) for p in POSES] and len(section['frames'])==9,'complete fixed frame inventory')
    entry=section['entryState'];history=section['entryHistory']
    protocol=section.get('historyProtocol')
    require(protocol==geometry.get('historyProtocol') and protocol in (None,'POSE_DRIVER_UNDO_V1','POSE_DRIVER_UNDO_RETURN_V2'),'pixel history protocol binding')
    require(section['finallyRestored'] is True and section['restoredState']==entry,'capture restoration')
    if protocol:
        runpy.run_path(str(HERE/'verify-pose-drive.py'))['verify_pixels'](section,geometry,geom['AXES'])
    else:
        require(section['restoredHistory']==history and history==geometry['historyAfter'],'capture restoration/history binding')
    require(len(entry['meshes'])==2 and entry['canvas'][:2]==[1024,1024],'isolated render canvas')
    for state,stored in zip(entry['meshes'],geometry['after']):
        require(state['id']==stored['artMeshId'] and state['snapshotSha256']==reference['fingerprint'](stored)
                and state['culling'] is False and state['visible'] is True,'render mesh/state binding')
    def evaluation(e,surface,pose):
        stored=geometry['after'][surface]
        require((e['artMeshId'],e['sourceGuid'])==(stored['artMeshId'],stored['sourceGuid']),'frame evaluated identity')
        require(e['passEntryTimeMillis']>=0 and geom['parameters'](e,'live')==geom['parameters'](e,'consumed'),'frame consumed state')
        for axis,value in zip(geom['AXES'],pose):
            require([v for name,v in geom['parameters'](e,'live').values() if name==axis]==[value],'frame evaluated pose')
        form=e['calculated']
        require(form['interpolated'] is True and form['coordinateType'] in ('Canvas','DeformerLocal')
                and type(form['drawOrder']) is int and form['drawOrder']==order_api['order'](surface,pose[0]),'frame calculated state/order')
        require(len(form['positions'])==4 and all(len(p)==2 for p in form['positions']),'frame vertex count')
        error=max(math.dist([f32(v) for v in p],q) for p,q in zip(form['positions'],geom['truth'](surface,*pose)))
        require(error<=.1,'frame geometric error')
    results=[]
    for index,(frame,pose) in enumerate(zip(section['frames'],POSES)):
        name=f'sheet-render/{index}.png'
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
        require(len(frame['consumption'])==len(frame['evaluationAfter'])==2,'frame evaluation inventory')
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
        result=inspect_pixels(reference['PNG'](png),raw,pose)
        require(result['mismatches']==0,'source visibility pixel mismatch: '+str(result['firstMismatch']))
        results.append(result)
    return dict(status='SHEET_FRAME_STATE_AND_PIXELS_CONTENT_ONLY_PASS',frames=results,processAdmitted=False)
