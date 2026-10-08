"""Source-textured cube pixel oracle; NOT host/process/state admission.

Frozen 1024 canvas, orthographic 200 px/source unit, top-row-first source PNG,
CLAMP_TO_EDGE/LINEAR at texel centres. Nine exact authored key poses. Sample
4px grid/pixel centres, excluding only the existing 1px triangle-edge band.
No image-derived fitting, UV flipping, color substitution or texture-edge mask.
"""
import hashlib
import io
import math
from pathlib import Path
import runpy
import struct
import json
from PIL import Image

HERE=Path(__file__).resolve().parent
ENTRY=runpy.run_path(str(HERE/'verify-report.py'))
RENDER=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-cube-render.py'))
POSES=tuple((yaw,pitch) for yaw in (-30,0,30) for pitch in (-30,0,30))
TOLERANCE=2


def require(ok,message):
    if not ok:raise ValueError('SOURCE_TEXTURE_PIXELS: '+message)


def source_image(cube,glb):
    ENTRY['textured_source'](cube,glb)
    raw=Path(glb).read_bytes();n=struct.unpack_from('<I',raw,12)[0]
    doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
    view=doc['bufferViews'][doc['images'][0]['bufferView']]
    png=binary[view['byteOffset']:view['byteOffset']+view['byteLength']]
    with Image.open(io.BytesIO(png)) as image:
        require(image.size==(256,128) and image.mode=='RGB','source image format')
        return image.convert('RGBA').copy()


def linear(image,u,v):
    require(all(math.isfinite(x) and 0<=x<=1 for x in (u,v)),'source UV domain')
    x=u*image.width-.5;y=v*image.height-.5
    ix,iy=math.floor(x),math.floor(y);fx,fy=x-ix,y-iy
    def pixel(dx,dy):
        return image.getpixel((max(0,min(image.width-1,ix+dx)),max(0,min(image.height-1,iy+dy))))
    a,b,c,d=pixel(0,0),pixel(1,0),pixel(0,1),pixel(1,1)
    return tuple(round((1-fy)*((1-fx)*a[k]+fx*b[k])+fy*((1-fx)*c[k]+fx*d[k])) for k in range(4))


def face_uv(vertices,patch,pose,x,y):
    positions=ENTRY['G']['truth'](vertices,*pose)
    for offset in (0,3):
        indices=patch['indices'][offset:offset+3];a,b,c=[positions[i] for i in indices]
        area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
        if abs(area)<1e-10:continue
        v=((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
        w=((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area;u=1-v-w
        if min(u,v,w)>=0:
            uv=[patch['uvs'][i] for i in indices]
            return tuple(u*uv[0][k]+v*uv[1][k]+w*uv[2][k] for k in (0,1))
    raise ValueError('SOURCE_TEXTURE_PIXELS: source depth hit has no UV triangle')


def reference(cube,glb,pose,*,persistence=False):
    require(type(persistence) is bool,'explicit persistence sampling mode')
    pose=tuple(pose)
    require(pose in (RENDER['POSES'] if persistence else POSES),'predeclared pose')
    image=source_image(cube,glb);sources,patches=ENTRY['G']['cube_source'](cube)
    points,coverage=RENDER['samples'](Path(cube).read_bytes(),pose)
    expected=[];marked=0
    for x,y,face in points:
        if face is None:color=(0,0,0,0)
        else:
            uv=face_uv(sources[face],patches[face],pose,x+.5,y+.5)
            color=linear(image,*uv)
            if max(color[:3])-min(color[:3])<15:marked+=1
        expected.append((x,y,color))
    require(marked>0,'no asymmetric label/arrow samples')
    return expected,coverage,marked


def inspect(rows,expected):
    require(len(rows)==1024 and all(len(row)==4096 for row in rows),'RGBA dimensions')
    mismatches=0;first=None
    for x,y,color in expected:
        actual=tuple(rows[y][4*x:4*x+4])
        bad=actual[3]!=color[3] or (color[3]!=0 and any(abs(a-b)>TOLERANCE for a,b in zip(actual[:3],color[:3])))
        if bad:
            mismatches+=1
            if first is None:first=dict(x=x,y=y,expected=color,actual=actual)
    return dict(samples=len(expected),mismatches=mismatches,firstMismatch=first)


def verify_frames(frames,cube,glb,artifact_reader):
    require(len(frames)==len(POSES),'nine source-texture frames')
    results=[]
    for i,(frame,pose) in enumerate(zip(frames,POSES)):
        name=f'source-texture-render/{i}.png'
        require(frame['pose']==list(pose) and frame['index']==i and frame['artifact']==name,'frame identity/order')
        require(type(frame['artifactBytes']) is int and 0<frame['artifactBytes']<=8*1024*1024,'PNG budget')
        raw=artifact_reader(name)
        require(len(raw)==frame['artifactBytes'] and hashlib.sha256(raw).hexdigest()==frame['artifactSha256'],'PNG identity')
        expected,coverage,marked=reference(cube,glb,pose)
        result=inspect(RENDER['PNG'](raw),expected)
        require(result['mismatches']==0,f'pose {pose}: {result["firstMismatch"]}')
        results.append(dict(result,pose=pose,coverage=coverage,markSamples=marked))
    return dict(status='SOURCE_TEXTURE_PIXELS_ONLY_NOT_HOST_ADMITTED',frames=results)


def verify_capture(report,cube,glb,run,artifact_reader,*,padded=False):
    """Bind raster to admitted UI import, unchanged authored state and fresh poses.

    Caller still admits bundle/job/process identities and artifact namespace.
    """
    ui=ENTRY['verify'](report,cube,run,glb,padded=padded)
    section=report['sourceTextureRender']
    require(report['renderVerification']=='CAPTURED_REQUIRES_INDEPENDENT_ORACLE'
            and section['schemaVersion']=='mi3d-source-texture-render-v1'
            and section['status']=='CAPTURED' and 'failure' not in section
            and section['restored'] is True,'complete/restored render capture')
    raw=section['protectedStateJson'];digest=hashlib.sha256(raw.encode('utf-8')).hexdigest()
    protected=dict(report['imported'],parameters=[p[:4] for p in report['imported']['parameters']])
    require(json.loads(raw)==protected and section['protectedStateSha256']==digest,'protected authored state identity')
    canvas=section['canvas']
    require(len(canvas)==5 and canvas[:2]==[1024,1024] and all(type(v) in (int,float) and math.isfinite(v) for v in canvas),'canvas definition')
    snapshots=report['imported']['meshes'];parameters=report['imported']['parameters']
    yaw,pitch=section['axes']
    require(yaw.startswith('Param3DYaw') and pitch.startswith('Param3DPitch')
            and {yaw,pitch}=={a['id'] for a in snapshots[0]['axes']},'render parameter axes')
    root=section['entryHistory'];restored=section['restoredHistory']
    def history(h):
        require(all(type(e) is str and bool(e) for e in h['entries'])
                and len(set(h['entries']))==len(h['entries']),'stable history entry identities')
        require(h['availability']=='AVAILABLE' and type(h['position']) is int
                and 0<=h['position']<=len(h['entries']) and type(h['revision']) is int and h['revision']>=root['revision'], 'history values')
        require(all(h[k]==root[k] for k in ('generation','document','manager'))
                and bool(h['document']) and bool(h['manager']),'history binding')
        require(h['entries'][:root['position']]==root['entries'][:root['position']],'import history prefix changed')
    history(root);history(restored)
    require(root['position']==restored['position']==report['historyAfterRedo'],'render restore history position')
    sources,_=ENTRY['G']['cube_source'](cube)
    frames=section['frames'];require(len(frames)==9,'capture frame inventory')
    identity=None
    for frame,pose in zip(frames,POSES):
        require(frame['pose']==list(pose) and frame['waitReason']=='CONSUMED'
                and type(frame['elapsedMillis']) is int and 0<=frame['elapsedMillis']<15000
                and type(frame['attempts']) is int and frame['attempts']>0,'pose/wait protocol')
        require(frame['canvasBefore']==frame['canvasAfter']==canvas,'canvas drift')
        require(frame['stateBeforeSha256']==frame['stateAfterSha256']==digest,'render authored state drift')
        history(frame['historyBefore']);history(frame['historyAfter'])
        require(frame['historyBefore']==frame['historyAfter'],'render history drift')
        require(all(len(frame[k])==6 for k in ('requested','lastObservation','evaluationBefore','evaluationAfter')),'six mesh observations')
        expected={p[0]:p[4] for p in parameters};expected.update({yaw:pose[0],pitch:pose[1]})
        for i,snapshot in enumerate(snapshots):
            requested=frame['requested'][i];actual=frame['lastObservation'][i]
            def values(state):
                rows=state['values'];require(len(rows)==len(expected),'parameter observation inventory')
                require(len({x[0] for x in rows})==len({x[1] for x in rows})==len(rows),'duplicate parameter identities')
                require({x[1]:x[2] for x in rows}==expected,'unexpected live/consumed pose')
                return sorted(rows)
            wanted=values(requested['live'])
            mapping=sorted((v[0],v[1]) for v in wanted)
            if identity is None:identity=mapping
            require(identity==mapping,'parameter GUID/ID drift')
            for axis in snapshot['axes']:
                require((axis['guid'],axis['id']) in identity,'authored/observed parameter identity')
            for obs in (requested,actual,frame['evaluationBefore'][i],frame['evaluationAfter'][i]):
                require(obs['artMeshId']==snapshot['artMeshId'] and obs['sourceGuid']==snapshot['sourceGuid'],'render mesh identity')
                require(values(obs['live'])==wanted,'live values drift')
            require(values(actual['consumed'])==wanted
                    and actual['consumed']['version']!=requested['consumed']['version']
                    and actual['passEntryTimeMillis']>=max(0,requested['passEntryTimeMillis']),'stale consumption')
            calculated=actual['calculated']
            require(calculated['interpolated'] is True and calculated['coordinateType'] in ('Canvas','DeformerLocal')
                    and calculated['drawOrder']==500
                    and ENTRY['G']['error'](calculated['positions'],ENTRY['G']['truth'](sources[i],*pose))<=.1,'native geometry error')
            for obs in (frame['evaluationBefore'][i],frame['evaluationAfter'][i]):
                require(values(obs['consumed'])==wanted and obs['calculated']==calculated,'render geometry/consumption changed')
            require(frame['evaluationBefore'][i]==frame['evaluationAfter'][i],'evaluation changed during render')
    return dict(status='UI_SOURCE_TEXTURE_CONTENT_CHECKED_NOT_PROCESS_ADMITTED',ui=ui,
                pixels=verify_frames(frames,cube,glb,artifact_reader))


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ('report','cube','texture','artifacts','out'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--padded',action='store_true');parser.add_argument('--run',required=True);args=parser.parse_args()
    require(args.report.stat().st_size<=128*1024*1024,'report budget')
    def pairs(items):
        result={}
        for key,value in items:
            require(key not in result,'duplicate report key');result[key]=value
        return result
    report_raw=args.report.read_bytes()
    report=json.loads(report_raw,object_pairs_hook=pairs,parse_constant=lambda x:(_ for _ in ()).throw(ValueError(x)))
    root=args.artifacts.absolute()
    def read_artifact(name):
        require(name in {f'source-texture-render/{i}.png' for i in range(9)},'artifact namespace')
        path=root/name
        require(not any(p.is_symlink() for p in (path,*path.parents)),'linked artifact path')
        require(path.is_file() and path.stat().st_size<=8*1024*1024,'artifact file/budget')
        return path.read_bytes()
    result=verify_capture(report,args.cube,args.texture,args.run,read_artifact,padded=args.padded)
    result['reportSha256']=hashlib.sha256(report_raw).hexdigest()
    with args.out.open('x') as out:json.dump(result,out,indent=2);out.write('\n')
    print(result['status'])
