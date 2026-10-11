"""Software sample of official-Core output against independent source depth.

NOT an official renderer or process admission gate. Requires prior geometry,
artifact and process admission. Uses frozen 4px sample grid / 1px source-edge
exclusion from the native cube oracle. No fitted winding, coordinate transform,
UV transform or expected color. Actual Core export must establish the declared
Core Y-up / bottom-origin UV / front-face winding convention before acceptance.
"""
import math
from pathlib import Path
import runpy

HERE = Path(__file__).resolve().parent
SOURCE = runpy.run_path(str(HERE.parents[0]/'model-import-3d-host-probe/verify-cube-render.py'))


def require(ok, message):
    if not ok:
        raise ValueError('OFFICIAL_CORE_RASTER: '+message)


def constant(value, expected):
    # Four-weight float32 interpolation roundoff, far below one 8-bit channel step.
    return type(value) in (int,float) and math.isfinite(value) and abs(value-expected)<=4*2**-23


def triangles(frame, mesh_ids):
    rows = frame['drawables']; require(len(rows) == len(mesh_ids) == 6, 'six admitted drawables')
    require(set(r['id'] for r in rows) == set(mesh_ids), 'drawable source identities')
    result = []
    for row in sorted(rows, key=lambda r:r['renderOrder']):
        require(row['textureIndex'] == 0 and row['masks'] == [] and row['constantFlags'] == 0,
                'single texture, no mask, normal blend, culling required')
        require(type(row['dynamicFlags']) is int and row['dynamicFlags'] & 1, 'visible drawable flag')
        require(constant(row['opacity'],1), 'opaque source face')
        # Only RGB affects the opaque base-color path; do not infer any shader settings.
        require(len(row['multiplyColor'])==4 and len(row['screenColor'])==4
                and all(constant(v,1) for v in row['multiplyColor'][:3])
                and all(constant(v,0) for v in row['screenColor'][:3]), 'source color modulation')
        pos, uv, indices = row['positions'], row['uvs'], row['indices']
        require(len(pos) == len(uv) == row['vertexCount']*2 and len(indices) == 6,
                'bounded face arrays')
        require(all(type(v) in (int,float) and math.isfinite(v) for v in pos+uv), 'finite coordinates')
        for offset in (0,3):
            ix=indices[offset:offset+3]
            require(all(type(i) is int and 0 <= i < row['vertexCount'] for i in ix), 'triangle indices')
            vertices=[(512+100*pos[2*i],512-100*pos[2*i+1],uv[2*i],uv[2*i+1]) for i in ix]
            a,b,c=vertices
            area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
            # Official NativeFramework 45de21229a9faab9675525bc59deddbe089b0e95
            # uses glFrontFace(GL_CCW). Y-up Core CCW is negative Y-down canvas area.
            if area >= 0:continue
            result.append((mesh_ids.index(row['id']),a,b,c,area))
    return result


def sample(tris, texture, x, y, *, linear=False):
    height=len(texture);width=len(texture[0])//4
    require(height in (512,1024) and width==height, "atlas dimensions")
    face=None;rgba=(0,0,0,0)
    for identity,a,b,c,area in tris:
        if not (min(a[0],b[0],c[0]) <= x <= max(a[0],b[0],c[0])
                and min(a[1],b[1],c[1]) <= y <= max(a[1],b[1],c[1])):continue
        v=((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
        w=((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area;u=1-v-w
        if min(u,v,w)<0:continue
        tx=u*a[2]+v*b[2]+w*c[2];ty=u*a[3]+v*b[3]+w*c[3]
        require(0<=tx<=1 and 0<=ty<=1, 'UV outside declared atlas')
        face=identity
        if linear:
            fx=tx*width-.5;fy=(1-ty)*height-.5;ix=math.floor(fx);iy=math.floor(fy);fx-=ix;fy-=iy
            def pixel(dx,dy):
                px=max(0,min(width-1,ix+dx));py=max(0,min(height-1,iy+dy))
                return texture[py][4*px:4*px+4]
            a0,b0,c0,d0=pixel(0,0),pixel(1,0),pixel(0,1),pixel(1,1)
            rgba=tuple(round((1-fy)*((1-fx)*a0[k]+fx*b0[k])+fy*((1-fx)*c0[k]+fx*d0[k])) for k in range(4))
        else:
            px=min(width-1,int(tx*width));py=min(height-1,int((1-ty)*height))
            rgba=tuple(texture[py][4*px:4*px+4])
    return face,rgba


def verify(capture, cube_path, mesh_ids, texture_png, *, source_texture=None):
    require(capture.get('schemaVersion')=='mi3d-official-core-capture-v2'
            and capture.get('poseProtocol')=='mi3d-cube-geometry-377-v1', 'capture protocol')
    require(len(capture['frames'])==377, 'full geometry inventory required')
    frames={tuple(f['pose']):f for f in capture['frames']}
    require(len(frames)==377,'duplicate poses')
    require(len(mesh_ids)==len(set(mesh_ids))==6,'admitted source-face mapping')
    texture=SOURCE['PNG'](texture_png,1024 if source_texture is not None else 512)
    oracle=runpy.run_path(str(HERE.parent/'model-import-3d-user-entry/verify-texture-pixels.py')) if source_texture is not None else None
    cube=Path(cube_path).read_bytes();results=[]
    for pose in SOURCE['POSES']:
        require(pose in frames,'missing raster pose')
        tris=triangles(frames[pose],mesh_ids)
        points,coverage=SOURCE['samples'](cube,pose)
        expected={} if oracle is None else {(x,y):c for x,y,c in oracle['reference'](cube_path,source_texture,pose,persistence=True)[0]}
        mismatches=0
        for x,y,expected_face in points:
            face,color=sample(tris,texture,x+.5,y+.5,linear=oracle is not None)
            require(face==expected_face,f'{pose} at {(x,y)}: nearest source face {face}, expected {expected_face}')
            require(color[3]==(0 if face is None else 255),'exported opacity')
            if oracle is None:
                require(face is None or all(abs(v-231)<=2 for v in color[:3]),f'{pose} at {(x,y)}: exported base color {color}')
            elif face is not None and any(abs(a-b)>2 for a,b in zip(color[:3],expected[(x,y)][:3])):
                mismatches+=1
        # User accepts sparse sampling differences; never relax face/opacity checks.
        require(mismatches<=len(points)*.001,'source texture mismatch exceeds 0.1% of samples')
        results.append(dict(pose=list(pose),samples=len(points),coverage=coverage,mismatches=mismatches))
    return dict(status='SOFTWARE_RASTER_CHECKED_NOT_FULL_ACCEPTANCE',frames=results,
                notCovered=['native process admission','artifact provenance','official renderer execution'])
