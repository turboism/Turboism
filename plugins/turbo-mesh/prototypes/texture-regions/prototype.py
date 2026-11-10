#!/usr/bin/env python3
"""OFFLINE PROTOTYPE: does a nested texture partition support a shared-boundary mesh?
No host integration. Image -> fine graph segmentation -> nested region hierarchy ->
shared PSLG -> boundary/interior sampling -> project's Java CDT -> inspectable evidence.
"""
import argparse
from collections import defaultdict
import hashlib
import heapq
import json
from pathlib import Path
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt
from scipy.spatial import cKDTree
from skimage.color import rgb2lab
from skimage.measure import approximate_polygon
from skimage.segmentation import felzenszwalb
from shapely.geometry import LineString, Point
from shapely.ops import polygonize, unary_union


def merge_regions(labels, lab, threshold, min_area=0):
    """Adjacent-region agglomeration: later snapshots are strictly nested parents."""
    size = int(labels.max())+1
    counts = np.bincount(labels.ravel(), minlength=size).astype(float)
    sums = np.array([np.bincount(labels.ravel(), weights=lab[:,:,i].ravel(), minlength=size) for i in range(3)]).T
    parent = np.arange(size)
    adj = [set() for _ in range(size)]
    for a,b in [(labels[:,:-1],labels[:,1:]),(labels[:-1],labels[1:])]:
        pairs=np.unique(np.sort(np.stack([a[a!=b],b[a!=b]],axis=1),axis=1),axis=0)
        for x,y in pairs:
            if x and y: adj[x].add(y); adj[y].add(x)
    def weight(a,b):
        contrast=float(np.linalg.norm(sums[a]/counts[a]-sums[b]/counts[b]))
        return contrast/1000 if min(counts[a],counts[b])<min_area else contrast
    queue=[]
    for a in range(1,size):
        for b in adj[a]:
            if a<b: heapq.heappush(queue,(weight(a,b),a,b))
    while queue:
        w,a,b=heapq.heappop(queue)
        if parent[a]!=a or parent[b]!=b or b not in adj[a]: continue
        now=weight(a,b)
        if abs(w-now)>1.e-8:
            heapq.heappush(queue,(now,a,b)); continue
        if now>threshold: break
        parent[b]=a; counts[a]+=counts[b]; sums[a]+=sums[b]
        neighbors=(adj[a]|adj[b])-{a,b}; adj[a]=neighbors
        for c in neighbors:
            adj[c].discard(b); adj[c].discard(a); adj[c].add(a)
            heapq.heappush(queue,(weight(a,c),min(a,c),max(a,c)))
    for a in range(size):
        root=a
        while parent[root]!=root: root=parent[root]
        parent[a]=root
    _,result=np.unique(parent[labels],return_inverse=True)
    return result.reshape(labels.shape).astype(np.int32)


def partition(rgba, min_ratio=.006, contrast=6):
    mask=rgba[:,:,3]>=128
    rgb=rgba[:,:,:3].copy()
    opaque=rgba[:,:,3]>=250
    _,nearest=distance_transform_edt(~opaque,return_indices=True)
    rgb[~opaque]=rgb[tuple(nearest[:,~opaque])]
    labels=felzenszwalb(rgb,scale=100,sigma=.6,min_size=100)+1
    labels[~mask]=0
    # Split disconnected labels to keep each region spatially autonomous.
    from skimage.measure import label
    labels=label(labels,connectivity=1,background=0)
    lab=rgb2lab(rgba[:,:,:3])
    fine=merge_regions(labels,lab,contrast*.65,min_area=max(100,int(mask.sum()*min_ratio)))
    medium=merge_regions(fine,lab,contrast)
    coarse=merge_regions(medium,lab,contrast*1.5)
    return mask,[coarse,medium,fine]


def shared_paths(labels, tolerance, spacing):
    """Extract each shared pixel edge ONCE, preserve junctions, then simplify/sample."""
    groups=defaultdict(list); h,w=labels.shape
    padded=np.pad(labels,1)
    for y in range(h+1):
        above=padded[y,1:w+1]; below=padded[y+1,1:w+1]
        for x in np.flatnonzero(above!=below):
            pair=tuple(sorted((int(above[x]),int(below[x]))))
            groups[pair].append(((int(x),y),(int(x)+1,y)))
    for x in range(w+1):
        left=padded[1:h+1,x]; right=padded[1:h+1,x+1]
        for y in np.flatnonzero(left!=right):
            pair=tuple(sorted((int(left[y]),int(right[y]))))
            groups[pair].append(((x,int(y)),(x,int(y)+1)))
    paths=[]
    for pair,edges in sorted(groups.items()):
        adj=defaultdict(list)
        for a,b in edges: adj[a].append(b); adj[b].append(a)
        unused={tuple(sorted(e)) for e in edges}
        starts=sorted(adj,key=lambda a:(len(adj[a])==2,a))
        for start in starts:
            for nxt in adj[start]:
                if tuple(sorted((start,nxt))) not in unused: continue
                chain=[start]; prev=start; cur=nxt
                unused.remove(tuple(sorted((prev,cur))))
                while True:
                    chain.append(cur)
                    if cur==start or len(adj[cur])!=2: break
                    options=[p for p in adj[cur] if tuple(sorted((cur,p))) in unused]
                    if not options: break
                    prev,cur=cur,options[0];unused.remove(tuple(sorted((prev,cur))))
                arr=np.array(chain,dtype=float)-.5
                # Low-pass subpixel staircase noise; retain endpoints and sharp multi-pixel turns.
                if len(arr)>9:
                    old=arr.copy()
                    for j in range(4,len(arr)-4):
                        a=old[j]-old[j-4];b=old[j+4]-old[j]
                        cosine=np.dot(a,b)/max(1.e-9,np.linalg.norm(a)*np.linalg.norm(b))
                        if cosine>.65: arr[j]=(old[j-2]+2*old[j-1]+3*old[j]+2*old[j+1]+old[j+2])/9
                if np.array_equal(arr[0],arr[-1]):
                    mid=len(arr)//2
                    simple=np.vstack([approximate_polygon(arr[:mid+1],tolerance),approximate_polygon(arr[mid:],tolerance)[1:]])
                else: simple=approximate_polygon(arr,tolerance)
                sampled=[simple[0]]
                for a,b in zip(simple[:-1],simple[1:]):
                    count=max(1,int(np.ceil(np.linalg.norm(a-b)/spacing)))
                    sampled.extend(a+(b-a)*j/count for j in range(1,count+1))
                paths.append((pair,np.array(sampled)))
    return paths


def direction_paths(mask, outline_paths, spacing, track_count):
    """PCA shape family: spindle connecting arcs, round/ellipse nested closed loops."""
    outer_lines=[LineString(np.round(p,7)) for pair,p in outline_paths if 0 in pair]
    polygons=list(polygonize(unary_union(outer_lines)))
    domain=max(polygons,key=lambda p:p.area)
    yy,xx=np.where(mask); samples=np.stack([xx,yy],axis=1)
    center=samples.mean(axis=0);values,vectors=np.linalg.eigh(np.cov(samples.T))
    axis=vectors[:,1];normal=vectors[:,0]
    coords=np.array(domain.exterior.coords)[:-1];major=(coords-center)@axis
    lo,hi=major.min(),major.max();out=[]
    aspect=float(np.sqrt(values[1]/values[0]))
    projected=(samples-center)@axis;across=(samples-center)@normal
    widths=[]
    for fraction in [.1,.9]:
        at=np.abs(projected-(projected.min()+np.ptp(projected)*fraction))<np.ptp(projected)*.02
        widths.append(np.ptp(across[at])/np.ptp(across))
    tapered=max(widths)<.72
    if aspect>2.2 and not tapered:
        for fraction in np.linspace(0,1,track_count+2)[1:-1]:
            offset=np.min(across)+np.ptp(across)*fraction
            out.append(((-1,-1),np.array([center+axis*lo+normal*offset,center+axis*hi+normal*offset])))
        family='straight-guides'
    elif aspect>2.2:
        start=coords[major.argmin()];end=coords[major.argmax()]
        sections=[]
        for t in np.linspace(lo+.1,hi-.1,max(5,int((hi-lo)/spacing)+1)):
            base=center+axis*t
            cut=domain.intersection(LineString([base-normal*2048,base+normal*2048]))
            if cut.is_empty:continue
            if cut.geom_type=='MultiLineString':cut=max(cut.geoms,key=lambda q:q.length)
            if cut.geom_type!='LineString':continue
            pts=np.array(cut.coords);lateral=(pts-center)@normal
            sections.append((pts[lateral.argmin()],pts[lateral.argmax()]))
        for fraction in np.linspace(0,1,track_count+2)[1:-1]:
            path=np.vstack([start,*[(1-fraction)*a+fraction*b for a,b in sections],end])
            out.append(((-1,-1),path))
        family='spindle-arcs'
    else:
        from shapely.affinity import affine_transform
        # Homothetic rings in the silhouette: exact concentric ellipses for an ellipse input.
        cx,cy=domain.centroid.coords[0]
        for factor in np.linspace(0,1,track_count+2)[1:-1]:
            ring=affine_transform(domain,[factor,0,0,factor,cx*(1-factor),cy*(1-factor)])
            ring=ring.intersection(domain)
            path=np.array(ring.exterior.coords)
            out.append(((-1,-1),approximate_polygon(path,1.5)))
        family='concentric-rings'
    clipped=[]
    for pair,path in out:
        curve=LineString(np.round(path,7)).intersection(domain)
        parts=curve.geoms if hasattr(curve,'geoms') else [curve]
        for part in parts:
            if part.geom_type=='LineString' and part.length>1.e-5:clipped.append((pair,np.array(part.coords)))
    return clipped,family,aspect


def node_paths(paths):
    """Split texture and flow curves at shared intersections before sampling the PSLG."""
    noded=unary_union([LineString(np.round(p,7)) for _,p in paths])
    pieces=list(noded.geoms) if hasattr(noded,'geoms') else [noded]
    return [((-2,-2),np.array(line.coords)) for line in pieces]


def mesh_pslg(labels, mask, paths, interior_spacing):
    lookup={}; points=[]; edges=[]; edge_pairs=[]
    def site(p):
        key=tuple(np.round(p,8))
        if key not in lookup: lookup[key]=len(points); points.append(np.array(p))
        return lookup[key]
    for pair,path in paths:
        ids=[site(p) for p in path]
        for a,b in zip(ids[:-1],ids[1:]):
            if a!=b: edges.append((a,b));edge_pairs.append(pair)
    boundary_count=len(points)
    lines=[LineString([points[a],points[b]]) for a,b in edges]
    polygons=list(polygonize(lines))
    def label_at(p):
        x,y=int(round(p.x)),int(round(p.y))
        return int(labels[np.clip(y,0,labels.shape[0]-1),np.clip(x,0,labels.shape[1]-1)])
    solid=[p for p in polygons if label_at(p.representative_point())]
    domain=unary_union(solid)
    rings=[]
    for poly in ([domain] if domain.geom_type=='Polygon' else domain.geoms):
        for ring in [poly.exterior,*poly.interiors]: rings.append([site(p) for p in list(ring.coords)[:-1]])
    # Independent fills per leaf, using distance from ALL boundaries, not just Alpha.
    boundary_pixels=np.zeros(mask.shape,bool)
    boundary_pixels[:,:-1]|=labels[:,:-1]!=labels[:,1:]
    boundary_pixels[:,1:]|=labels[:,:-1]!=labels[:,1:]
    boundary_pixels[:-1]|=labels[:-1]!=labels[1:]
    boundary_pixels[1:]|=labels[:-1]!=labels[1:]
    clearance=distance_transform_edt(~boundary_pixels)
    for region in np.unique(labels[mask]):
        ys,xs=np.where(labels==region)
        candidates=[]
        for row,y in enumerate(np.arange(ys.min(),ys.max()+1,interior_spacing*.866)):
            for x in np.arange(xs.min()+interior_spacing*(row%2)/2,xs.max()+1,interior_spacing):
                ix,iy=int(round(x)),int(round(y))
                if labels[iy,ix]==region and clearance[iy,ix]>interior_spacing*.32:
                    candidates.append((x,y))
        for p in candidates:
            if domain.contains(Point(p)): site(p)
    return np.array(points),np.array(edges),edge_pairs,rings,boundary_count,domain,len(solid)


def average_merge_sites(points, edges, rings, boundary_count, radius):
    """Step 5b: average nearby complete sites, retaining the embedded region graph."""
    points=points.copy();mass=np.ones(len(points));active=set(range(len(points)))
    edges={tuple(sorted(map(int,e))) for e in edges};rings=[list(r) for r in rings]
    boundary=set(range(boundary_count));merged=0
    def lines(es,ps):return [LineString([ps[a],ps[b]]) for a,b in es]
    faces=len(list(polygonize(lines(edges,points))))
    while True:
        ids=sorted(active);pairs=cKDTree(points[ids]).query_pairs(radius)
        candidates=sorted(((ids[a],ids[b]) for a,b in pairs),key=lambda e:np.linalg.norm(points[e[0]]-points[e[1]]))
        accepted=False
        for a,b in candidates:
            if a not in active or b not in active or np.linalg.norm(points[a]-points[b])>=radius:continue
            proposed=points.copy();proposed[a]=(points[a]*mass[a]+points[b]*mass[b])/(mass[a]+mass[b])
            newedges={tuple(sorted((a if x==b else x,a if y==b else y))) for x,y in edges if {x,y}!={a,b}}
            newedges={e for e in newedges if e[0]!=e[1]}
            newrings=[]
            for ring in rings:
                r=[a if k==b else k for k in ring];r=[k for i,k in enumerate(r) if k!=r[i-1]]
                newrings.append(r)
            if any(len(set(r))<3 for r in newrings):continue
            newlines=lines(newedges,proposed)
            cells=list(polygonize(newlines))
            if len(cells)!=faces:continue
            noded=unary_union(newlines)
            geoms=noded.geoms if hasattr(noded,'geoms') else [noded]
            if sum(len(l.coords)-1 for l in geoms)!=len(newedges):continue
            points=proposed;mass[a]+=mass[b];edges=newedges;rings=newrings;active.remove(b)
            if b in boundary:boundary.add(a)
            boundary.discard(b);merged+=1;accepted=True
        if not accepted:break
    order=sorted(active&boundary)+sorted(active-boundary);remap={old:i for i,old in enumerate(order)}
    return (points[order],np.array([[remap[a],remap[b]] for a,b in sorted(edges)]),
            [[remap[k] for k in r] for r in rings],len(active&boundary),merged)


def triangulate(root, output, points, edges, rings):
    jars=[next((root/'build/worktree').glob('*/turbo-mesh/libs/turbo-mesh-*.jar')),next((root/'build/worktree').glob('*/sdk/libs/sdk-*.jar'))]
    cp=':'.join(map(str,jars)); source=Path(__file__).with_name('RegionTriangulator.java')
    subprocess.run(['javac','-cp',cp,'-d',str(output),str(source)],check=True)
    with (output/'constraints.txt').open('w') as f:
        f.write(f'{len(points)} {len(edges)} {len(rings)}\n')
        np.savetxt(f,points,fmt='%.10f');np.savetxt(f,edges,fmt='%d')
        for r in rings: f.write(str(len(r))+' '+' '.join(map(str,r))+'\n')
    subprocess.run(['java','-cp',str(output)+':'+cp,'RegionTriangulator',str(output/'constraints.txt'),str(output/'triangles.txt')],check=True)
    with (output/'triangles.txt').open() as f:
        n,t=map(int,f.readline().split());p=np.loadtxt([next(f) for _ in range(n)])
        tris=np.loadtxt(f,dtype=int).reshape(-1,3)
    return p,tris


FONT='/usr/share/fonts/noto-cjk/NotoSansCJK-Regular.ttc'
def render(rgba, labels, paths, points, triangles, output, levels, stats):
    # Tight crop, enlarged enough to inspect the source's small highlight tips.
    h,w=labels.shape;scale=4 if h<360 else 2;pad=28
    yy,xx=np.indices((h,w)); bg=np.where(((xx//16+yy//16)%2)[:,:,None],42,48)*np.ones((1,1,3))
    a=rgba[:,:,3:4]/255.;rgb=np.uint8(rgba[:,:,:3]*a+bg*(1-a))
    base=Image.fromarray(rgb).resize((w*scale,h*scale))
    font=ImageFont.truetype(FONT,22);small=ImageFont.truetype(FONT,17)
    def make(title,subtitle):
        im=Image.new('RGB',(w*scale+2*pad,h*scale+104),'#151c27');im.paste(base,(pad,80))
        d=ImageDraw.Draw(im);d.text((pad,10),title,font=font,fill='#f0f3fc');d.text((pad,44),subtitle,font=small,fill='#aebdd1')
        return im,ImageDraw.Draw(im)
    def xy(p):return (pad+float(p[0])*scale,80+float(p[1])*scale)
    panels=[]
    im,d=make('01 原图与外轮廓','Alpha ≥ 128；黄色为外轮廓')
    for pair,path in paths:
        if 0 in pair:d.line([xy(p) for p in path],fill='#ffd166',width=2)
    im.save(output/'01-outline.png');panels.append(im)
    rng=np.random.default_rng(15);palette=rng.integers(65,230,(max(int(l.max()) for l in levels)+1,3),dtype=np.uint8)
    for idx,lab in enumerate(levels):
        im,d=make(['02 一级区域','03 二级子区域','04 三级子区域'][idx],f'{len(np.unique(lab[lab>0]))} 个连通区域；粗 → 细保持父子归属')
        col=rgb.copy();inside=lab>0;col[inside]=.30*col[inside]+.70*palette[lab[inside]]
        im.paste(Image.fromarray(col).resize((w*scale,h*scale)),(pad,80));d=ImageDraw.Draw(im)
        for pair,path in shared_paths(lab,.8,22): d.line([xy(p) for p in path],fill='#e4eefc' if 0 in pair else '#253147',width=2)
        for r in np.unique(lab[inside]):
            dist=distance_transform_edt(lab==r);y,x=np.unravel_index(dist.argmax(),dist.shape)
            if dist[y,x]>4:d.text(xy((x,y)),str(r),font=small,fill='white',stroke_width=1,stroke_fill='#182132')
        im.save(output/f'0{idx+2}-regions.png');panels.append(im)
    im,d=make('05 分界线与布点',f'均值合并 {stats["vertices_before_merge"]} → {stats["output_vertices"]} 点；紫色为方向弧')
    for pair,path in paths:d.line([xy(p) for p in path],fill='#b9a1ff' if pair[0]<0 else ('#ffd166' if 0 in pair else '#44e3df'),width=2)
    for i,p in enumerate(points[:stats['input_vertices']]):
        x,y=xy(p);r=2.6 if i<stats['boundary_vertices'] else 3.2
        d.ellipse((x-r,y-r,x+r,y+r),fill='#ffbd69' if i<stats['boundary_vertices'] else '#ff70bb')
    im.save(output/'05-sites.png');panels.append(im)
    im,d=make('06 方向约束三角网格',f'{len(points)} 顶点 / {len(triangles)} 三角形；紫色为方向线')
    for tri in triangles:
        d.line([xy(points[k]) for k in [*tri,tri[0]]],fill='#8996a8',width=1)
    for pair,path in paths:d.line([xy(p) for p in path],fill='#b9a1ff' if pair[0]<0 else ('#ffd166' if 0 in pair else '#44e3df'),width=2)
    im.save(output/'06-mesh.png');panels.append(im)
    sheet=Image.new('RGB',(panels[0].width*3,panels[0].height*2),'#151c27')
    for i,p in enumerate(panels):sheet.paste(p,((i%3)*p.width,(i//3)*p.height))
    sheet.save(output/'overview.png')
    # Detail crop at the upper highlight cluster, in the same source coordinates for every stage.
    detail=Image.new('RGB',(panels[0].width*3,650),'#151c27')
    for i,k in enumerate([3,4,5]):
        p=panels[k];detail.paste(p.crop((0,0,p.width,min(650,p.height))),(i*p.width,0))
    detail.save(output/'detail.png')


def main():
    ap=argparse.ArgumentParser();ap.add_argument('image',type=Path);ap.add_argument('--output',type=Path,required=True)
    ap.add_argument('--spacing',type=float,default=None);ap.add_argument('--tolerance',type=float,default=None)
    ap.add_argument('--min-region-ratio',type=float,default=.006);ap.add_argument('--contrast',type=float,default=6)
    ap.add_argument('--level',type=int,choices=[1,2,3],default=1);ap.add_argument('--tracks',type=int,default=3)
    ap.add_argument('--min-spacing',type=float,default=None)
    args=ap.parse_args();output=args.output.resolve();output.mkdir(parents=True,exist_ok=True)
    original=Image.open(args.image).convert('RGBA');rgba=np.array(original);ys,xs=np.where(rgba[:,:,3]>=128)
    x0=max(0,xs.min()-4);y0=max(0,ys.min()-4);rgba=rgba[y0:ys.max()+5,x0:xs.max()+5]
    span=max(np.ptp(xs),np.ptp(ys))
    if args.spacing is None:args.spacing=round(span*.087,2)
    if args.tolerance is None:args.tolerance=round(max(.6,span*.00375),2)
    if args.min_spacing is None:args.min_spacing=round(max(1.5,span*.009),2)
    mask,levels=partition(rgba,args.min_region_ratio,args.contrast);labels=levels[args.level-1]
    paths=shared_paths(labels,args.tolerance,args.spacing)
    flows,family,aspect=direction_paths(mask,paths,args.spacing,args.tracks)
    noded=node_paths(paths+flows)
    points,edges,pairs,rings,boundary,domain,polygon_count=mesh_pslg(labels,mask,noded,args.spacing*1.4)
    (output/'sites-before-merge.json').write_text(json.dumps({'vertices':points.tolist(),'constraints':edges.tolist()}))
    before_merge=len(points)
    points,edges,rings,boundary,merged=average_merge_sites(points,edges,rings,boundary,args.min_spacing)
    # Reconstruct domain and region graph after averaging; all subsequent checks use these positions.
    from shapely.geometry import Polygon
    domain=Polygon(points[rings[0]], [points[r] for r in rings[1:]])
    root=Path(__file__).resolve().parents[4]
    verts,tris=triangulate(root,output,points,edges,rings)
    points=verts[:len(points)]
    from shapely.geometry import Polygon
    domain=Polygon(points[rings[0]],[points[r] for r in rings[1:]])
    actual={tuple(sorted((int(a),int(b)))) for t in tris for a,b in zip(t,np.roll(t,-1))}
    missing=[];subdivided=0
    # Existing vertices can split a constraint into a collinear chain. Check coverage,
    # not just direct-edge identity; tolerance is 1e-4 source pixels for float32 UV.
    for a,b in edges:
        if tuple(sorted((int(a),int(b)))) in actual:continue
        vector=points[b]-points[a];length=np.linalg.norm(vector);unit=vector/length
        spans=[]
        for c,d in actual:
            rel=verts[[c,d]]-points[a]
            if np.max(np.abs(rel[:,0]*unit[1]-rel[:,1]*unit[0]))>1.e-4:continue
            projection=rel@unit;spans.append(sorted(projection))
        reached=0.
        for low,high in sorted(spans):
            if low>reached+1.e-4:break
            if high>reached:reached=high
        if reached>=length-1.e-4:subdivided+=1
        else:missing.append((int(a),int(b)))
    # Check triangle geometry against the vector cells, not ambiguous antialiased raster labels.
    from shapely.geometry import Polygon
    triangle_polys=[Polygon(verts[t]) for t in tris]
    union=unary_union(triangle_polys)
    overlap=sum(p.area for p in triangle_polys)-union.area
    cells=list(polygonize([LineString([points[a],points[b]]) for a,b in edges]))
    from shapely.strtree import STRtree
    tree=STRtree(cells);crossing=0
    for t in triangle_polys:
        areas=[t.intersection(cells[i]).area for i in tree.query(t)]
        if sum(a>1.e-5 for a in areas)>1: crossing+=1
    hierarchy=[]
    for depth in range(1,len(levels)):
        for child in np.unique(levels[depth][mask]):
            parents=np.unique(levels[depth-1][levels[depth]==child]);assert len(parents)==1
            hierarchy.append({'depth':depth+1,'region':int(child),'parent':int(parents[0]),'pixels':int(np.sum(levels[depth]==child))})
    from shapely import contains_xy
    yy,xx=np.indices(mask.shape);vector_mask=contains_xy(domain,xx,yy)
    alpha_iou=float(np.sum(mask&vector_mask)/np.sum(mask|vector_mask))
    stats={'alpha_silhouette_iou':alpha_iou, 'source_sha256':hashlib.sha256(args.image.read_bytes()).hexdigest(),'crop_origin':[int(x0),int(y0)],'alpha_threshold':128,
           'region_counts':[len(np.unique(l[mask])) for l in levels], 'selected_regions':len(np.unique(labels[mask])), 'flow_family':family, 'shape_aspect':aspect, 'flow_tracks':args.tracks, 'flow_curve_pieces':len(flows), 'vertices_before_merge':before_merge, 'merge_rule':'weighted mean of original sites', 'merged_nearby_vertices':merged, 'parameters':vars(args)|{'image':str(args.image),'output':str(args.output)},'boundary_vertices':boundary,'interior_vertices':len(points)-boundary,
           'input_vertices':len(points),'output_vertices':len(verts),'triangles':len(tris),'constraints':len(edges),'missing_constraints':len(missing), 'subdivided_constraints':subdivided, 'domain_holes':len(rings)-1, 'numeric_area_tolerance_px2':.001,
           'triangles_crossing_vector_regions':crossing,'uncovered_vector_area':domain.difference(union).area,'outside_vector_area':union.difference(domain).area,
           'overlapping_triangle_area':max(0.,overlap),'alpha_area_pixels':int(mask.sum()),'vector_area':domain.area,'polygon_regions':polygon_count}
    (output/'metrics.json').write_text(json.dumps(stats,indent=2)+'\n');(output/'hierarchy.json').write_text(json.dumps(hierarchy,indent=2)+'\n')
    np.savez_compressed(output/'regions.npz',coarse=levels[0],medium=levels[1],fine=levels[2])
    (output/'mesh.json').write_text(json.dumps({'origin':[int(x0),int(y0)],'vertices':verts.tolist(),'triangles':tris.tolist(),'constraints':edges.tolist(),'region_pairs_before_noding':[]}))
    Image.fromarray(rgba).save(output/'input-crop.png')
    # Render actual post-merge graph, not the pre-merge guide lines.
    source_lines=[LineString(p) for _,p in paths+flows]
    source_pairs=[pair for pair,_ in paths+flows]
    actual_paths=[];pairs=[]
    for a,b in edges:
        midpoint=Point((verts[a]+verts[b])/2)
        pair=source_pairs[min(range(len(source_lines)),key=lambda i:source_lines[i].distance(midpoint))]
        actual_paths.append((pair,np.array([verts[a],verts[b]])));pairs.append(pair)
    (output/'constraint-roles.json').write_text(json.dumps(pairs))
    render(rgba,labels,actual_paths,verts,tris,output,levels,stats)
    print(json.dumps(stats,indent=2))
    assert not missing and not crossing and stats['uncovered_vector_area']<.001 and stats['outside_vector_area']<.001 and overlap<.001

if __name__=='__main__':main()
