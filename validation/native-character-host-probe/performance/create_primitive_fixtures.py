#!/usr/bin/env python3
"""Generate public, textured GLB acceptance fixtures using only Python's standard library."""
import argparse
import colorsys
import hashlib
import json
import math
import struct
import zlib
from collections import Counter
from pathlib import Path


def atlas_png():
    size, tile = 512, 64
    digits = ('111101101101111', '010110010010111', '111001111100111', '111001111001111',
              '101101111001001', '111100111001111', '111100111101111', '111001010010010',
              '111101111101111', '111101111001111')
    pixels = bytearray(size * size * 3)
    for y in range(size):
        for x in range(size):
            tx, ty, px, py = x // tile, y // tile, x % tile, y % tile
            number = ty * 8 + tx
            rgb = colorsys.hsv_to_rgb((number * 0.61803398875) % 1, .72, .95)
            factor = .78 if (px // 8 + py // 8) % 2 else 1
            color = tuple(round(c * 255 * factor) for c in rgb)
            if px < 2 or py < 2 or px >= 62 or py >= 62:
                color = (15, 15, 15)
            # Upward arrow and a two-digit tile number, independent of installed fonts.
            if (29 <= px <= 34 and 34 <= py <= 54) or (22 <= py <= 35 and abs(px - 31) <= py - 22):
                color = (255, 255, 255)
            for offset, char in [(12, str(number // 10)), (36, str(number % 10))]:
                dx, dy = (px - offset) // 4, (py - 4) // 3
                if 0 <= dx < 3 and 0 <= dy < 5 and digits[int(char)][dy * 3 + dx] == '1':
                    color = (255, 255, 255)
            start = (y * size + x) * 3
            pixels[start:start + 3] = bytes(color)
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data))
    scan = b''.join(b'\0' + pixels[y * size * 3:(y + 1) * size * 3] for y in range(size))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', size, size, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(scan, 9)) + chunk(b'IEND', b''))


def oriented(points, normal):
    a, b, c = points[:3]
    u, v = [b[i] - a[i] for i in range(3)], [c[i] - a[i] for i in range(3)]
    cross = (u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0])
    return list(reversed(points)) if sum(cross[i]*normal[i] for i in range(3)) < 0 else points


def boxes(cells, scale):
    faces = []
    for cell in sorted(cells):
        for axis in range(3):
            for sign in (-1, 1):
                neighbor = list(cell); neighbor[axis] += sign
                if tuple(neighbor) in cells:
                    continue
                other = [i for i in range(3) if i != axis]
                points = []
                for u, v in [(-.5,-.5),(.5,-.5),(.5,.5),(-.5,.5)]:
                    p = list(cell); p[axis] += sign * .5; p[other[0]] += u; p[other[1]] += v
                    points.append(tuple(t * scale for t in p))
                normal = [0, 0, 0]; normal[axis] = sign
                faces.append(oriented(points, normal))
    return faces


def octahedron():
    faces = []
    for x in (-1, 1):
        for y in (-1, 1):
            for z in (-1, 1):
                faces.append(oriented([(x,0,0),(0,y,0),(0,0,z)], (x,y,z)))
    return faces


def torus():
    def point(u, v):
        a, b = 2*math.pi*u/32, 2*math.pi*v/12
        return ((1+.35*math.cos(b))*math.cos(a), .35*math.sin(b), (1+.35*math.cos(b))*math.sin(a))
    return [[point(u,v),point(u,v+1),point(u+1,v+1),point(u+1,v)] for u in range(32) for v in range(12)]


def cylinder():
    def point(i, y):
        return (.55*math.cos(2*math.pi*i/32), y, .55*math.sin(2*math.pi*i/32))
    faces = [[point(i,-1.4),point(i,1.4),point(i+1,1.4),point(i+1,-1.4)] for i in range(32)]
    faces += [[point(i,-1.4) for i in range(32)], [point(i,1.4) for i in reversed(range(32))]]
    return faces


def write_glb(path, objects, png):
    binary, views, accessors, meshes = bytearray(), [], [], []
    def blob(data):
        binary.extend(b'\0' * (-len(binary) % 4))
        start = len(binary); binary.extend(data)
        views.append({'buffer':0, 'byteOffset':start, 'byteLength':len(data)})
        return len(views)-1
    def accessor(values, kind, dimensions, integer=False):
        flat = [x for p in values for x in p] if dimensions > 1 else values
        view = blob(struct.pack('<' + ('I' if integer else 'f') * len(flat), *flat))
        item = {'bufferView':view, 'componentType':5125 if integer else 5126, 'count':len(values), 'type':kind}
        if dimensions == 3:
            item.update(min=[min(p[i] for p in values) for i in range(3)], max=[max(p[i] for p in values) for i in range(3)])
        accessors.append(item); return len(accessors)-1
    total_triangles = 0
    for name, faces in objects:
        positions, uvs, normals, indices = [], [], [], []
        edges = Counter()
        for face_number, points in enumerate(faces):
            first = len(positions)
            a,b,c = points[:3]
            u,v = [b[i]-a[i] for i in range(3)],[c[i]-a[i] for i in range(3)]
            n = (u[1]*v[2]-u[2]*v[1],u[2]*v[0]-u[0]*v[2],u[0]*v[1]-u[1]*v[0])
            length = math.sqrt(sum(t*t for t in n))
            if length <= 1e-10: raise ValueError('Degenerate fixture face')
            n = tuple(t/length for t in n)
            tile = face_number % 64; tx,ty = tile%8,tile//8
            for i,p in enumerate(points):
                positions.append(p); normals.append(n)
                if len(points) == 4: q = [(0,0),(1,0),(1,1),(0,1)][i]
                elif len(points) == 3: q = [(0,0),(1,0),(.5,1)][i]
                else: q = (.5+.48*math.cos(i*2*math.pi/len(points)), .5+.48*math.sin(i*2*math.pi/len(points)))
                uvs.append(((tx*64+3+q[0]*58)/512,(ty*64+3+q[1]*58)/512))
            for i in range(1,len(points)-1):
                indices.extend((first,first+i,first+i+1))
                triangle = [points[k] for k in (0,i,i+1)]
                for j in range(3):
                    edge = tuple(sorted(tuple(round(t,9) for t in triangle[k]) for k in (j,(j+1)%3)))
                    edges[edge] += 1
        if any(count != 2 for count in edges.values()): raise ValueError('Fixture is not a closed two-manifold')
        primitive = {'attributes': {'POSITION':accessor(positions,'VEC3',3), 'NORMAL':accessor(normals,'VEC3',3),
                                     'TEXCOORD_0':accessor(uvs,'VEC2',2)}, 'indices':accessor(indices,'SCALAR',1,True),
                     'material':0, 'targets':[{'POSITION':accessor([tuple(.1*x for x in p) for p in positions],'VEC3',3)}]}
        meshes.append({'name':name, 'primitives':[primitive], 'weights':[0], 'extras':{'targetNames':['Expand']}})
        total_triangles += len(indices)//3
    image_view = blob(png)
    binary.extend(b'\0' * (-len(binary)%4))
    doc = {'asset':{'version':'2.0','generator':'Turboism deterministic public primitive fixtures'},
           'scene':0,'scenes':[{'nodes':list(range(len(meshes)))}],
           'nodes':[{'mesh':i,'name':name} for i,(name,_) in enumerate(objects)], 'meshes':meshes,
           'extensionsUsed':['KHR_materials_unlit'],
           'materials':[{'name':'Numbered directional atlas','doubleSided':False,'alphaMode':'OPAQUE',
                         'extensions':{'KHR_materials_unlit':{}},
                         'pbrMetallicRoughness':{'baseColorTexture':{'index':0},'metallicFactor':0,'roughnessFactor':1}}],
           'textures':[{'source':0,'sampler':0}], 'samplers':[{'magFilter':9728,'minFilter':9728,'wrapS':33071,'wrapT':33071}],
           'images':[{'mimeType':'image/png','bufferView':image_view}],
           'buffers':[{'byteLength':len(binary)}],'bufferViews':views,'accessors':accessors}
    encoded = json.dumps(doc,sort_keys=True,separators=(',',':')).encode(); encoded += b' '*(-len(encoded)%4)
    data = struct.pack('<III',0x46546c67,2,28+len(encoded)+len(binary))+struct.pack('<II',len(encoded),0x4e4f534a)+encoded+struct.pack('<II',len(binary),0x004e4942)+binary
    path.write_bytes(data)
    return {'file':path.name,'sha256':hashlib.sha256(data).hexdigest(),'triangles':total_triangles,
            'objects':len(objects),'standardMorphTargets':len(objects),'texturePixels':512*512,
            'closedManifold':True,'sourceDoubleSided':False}


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('output',type=Path); args=parser.parse_args()
    args.output.mkdir(parents=True,exist_ok=True)
    png=atlas_png(); (args.output/'directional-atlas.png').write_bytes(png)
    shapes={'cube':boxes({(0,0,0)},2), 'octahedron':octahedron(),
            'cross-star':boxes({(x,y,z) for x in (-1,0,1) for y in (-1,0,1) for z in (-1,0,1) if (x!=0)+(y!=0)+(z!=0)<=1},.8),
            'torus':torus(), 'cylinder':cylinder()}
    records=[write_glb(args.output/(name+'.glb'),[(name,faces)],png) for name,faces in shapes.items()]
    # Disjoint solids with projected overlap; no physical intersection or duplicated faces.
    ring=[[(x,y-.9,z) for x,y,z in face] for face in shapes['torus']]
    post=[[(x,y+1.2,z) for x,y,z in face] for face in shapes['cylinder']]
    records.append(write_glb(args.output/'ring-and-column.glb',[('ring',ring),('column',post)],png))
    (args.output/'manifest.json').write_text(json.dumps({'schemaVersion':1,'fixtures':records},indent=2)+'\n')
    print(json.dumps(records,indent=2))


if __name__=='__main__': main()
