#!/usr/bin/env python3
"""Offline preparation of the authorized cat; never a native OBJ-import claim.

Keeps every original quad, its 0-2 diagonal, UV seam and source normal. OBJ's
bottom-origin V becomes glTF's top-origin V. JPEG texels are decoded once to PNG
at their original resolution. Bump lighting is explicitly not baked into color.
"""
import argparse
import hashlib
import io
import json
import math
from pathlib import Path
import struct
import zipfile
from PIL import Image, ImageCms

ARCHIVE_SHA = 'ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11'


def convert(archive, out):
    raw = archive.read_bytes()
    if hashlib.sha256(raw).hexdigest() != ARCHIVE_SHA or out.exists():
        raise ValueError('exact authorized archive and new output required')
    with zipfile.ZipFile(io.BytesIO(raw)) as z:
        files = {Path(n).name: z.read(n) for n in z.namelist() if not n.endswith('/')}
        if set(files) != {'12221_Cat_v1_l3.obj', '12221_Cat_v1_l3.mtl', 'Cat_diffuse.jpg', 'Cat_bump.jpg'}:
            raise ValueError('unexpected source members')
    positions, uvs, normals, faces = [], [], [], []
    for line in files['12221_Cat_v1_l3.obj'].decode().splitlines():
        fields = line.split()
        if not fields or fields[0].startswith('#'):
            continue
        kind = fields[0]
        if kind in ('v', 'vt', 'vn'):
            value = tuple(map(float, fields[1:]))
            if kind == 'vt' and len(value) == 3 and value[2] == 0:
                value = value[:2]
            if len(value) != (2 if kind == 'vt' else 3) or not all(map(math.isfinite, value)):
                raise ValueError('invalid source coordinate')
            {'v': positions, 'vt': uvs, 'vn': normals}[kind].append(value)
        elif kind == 'f':
            face = [tuple(int(v) - 1 for v in field.split('/')) for field in fields[1:]]
            if len(face) != 4 or any(len(c) != 3 for c in face):
                raise ValueError('source must retain position/UV/normal quads')
            for corner in face:
                if any(not 0 <= index < len(values) for index, values in zip(corner, (positions, uvs, normals))):
                    raise ValueError('source index outside domain')
            faces.append(face)
        elif kind in ('mtllib', 'g', 'usemtl', 's'):
            continue
        else:
            raise ValueError('unsupported source record: ' + kind)
    if (len(positions), len(uvs), len(faces)) != (35290, 36525, 35288):
        raise ValueError('authorized topology inventory differs')
    slots, indices = {}, []
    for face in faces:
        local = [slots.setdefault(c, len(slots)) for c in face]
        indices.extend((local[0], local[1], local[2], local[0], local[2], local[3]))
    pairs = list(slots)
    values = {
        'POSITION': [positions[c[0]] for c in pairs],
        'TEXCOORD_0': [(uvs[c[1]][0], 1 - uvs[c[1]][1]) for c in pairs],
        'NORMAL': [normals[c[2]] for c in pairs],
    }
    binary, views, accessors = bytearray(), [], []

    def view(data):
        binary.extend(b'\0' * (-len(binary) % 4))
        views.append(dict(buffer=0, byteOffset=len(binary), byteLength=len(data)))
        binary.extend(data)
        return len(views) - 1

    def accessor(rows, component, dimensions):
        fmt = '<' + ('f' if component == 5126 else 'I') * dimensions
        content = b''.join(struct.pack(fmt, *row) for row in rows)
        encoded = [struct.unpack_from(fmt, content, i * struct.calcsize(fmt)) for i in range(len(rows))]
        if component == 5126:
            # Every source slot is checked after its exact float32 encoding.
            if any(abs(a - b) > 2e-6 for original, decoded in zip(rows, encoded) for a, b in zip(original, decoded)):
                raise ValueError('source float32 error exceeds fixed budget')
        row = dict(bufferView=view(content), componentType=component, count=len(rows),
                   type={1: 'SCALAR', 2: 'VEC2', 3: 'VEC3'}[dimensions])
        if dimensions == 3:
            row.update(min=[min(v[i] for v in encoded) for i in range(3)], max=[max(v[i] for v in encoded) for i in range(3)])
        accessors.append(row)
        return len(accessors) - 1

    attrs = {name: accessor(rows, 5126, 2 if name == 'TEXCOORD_0' else 3) for name, rows in values.items()}
    ix = accessor([(i,) for i in indices], 5125, 1)
    source_image = Image.open(io.BytesIO(files['Cat_diffuse.jpg']))
    decoded = source_image.convert('RGB')
    if decoded.size != (1024, 1024):
        raise ValueError('source image dimensions')
    profile_bytes = source_image.info.get('icc_profile')
    profile_name = None
    if profile_bytes:
        profile = ImageCms.ImageCmsProfile(io.BytesIO(profile_bytes))
        profile_name = ImageCms.getProfileDescription(profile).strip()
        normalized = ImageCms.profileToProfile(decoded, profile, ImageCms.createProfile('sRGB'), outputMode='RGB')
        # This authorized source is already sRGB: normalizing must be pixel exact.
        if normalized.tobytes() != decoded.tobytes():
            raise ValueError('authorized cat ICC normalization changes source texels')
    plain = Image.frombytes('RGB', decoded.size, decoded.tobytes())
    png = io.BytesIO()
    plain.save(png, format='PNG')
    if Image.open(io.BytesIO(png.getvalue())).convert('RGB').tobytes() != decoded.tobytes():
        raise ValueError('source texels changed during PNG encoding')
    image_view = view(png.getvalue())
    doc = dict(asset=dict(version='2.0', generator='Turboism authorized cat source preparation'),
               scene=0, scenes=[dict(nodes=[0])], nodes=[dict(mesh=0, name='Cat')],
               meshes=[dict(name='Cat', primitives=[dict(attributes=attrs, indices=ix, material=0, mode=4)])],
               buffers=[dict(byteLength=len(binary))], bufferViews=views, accessors=accessors,
               materials=[dict(name='Cat', doubleSided=False, pbrMetallicRoughness=dict(
                   baseColorFactor=[1, 1, 1, 1], metallicFactor=0, roughnessFactor=1, baseColorTexture=dict(index=0)))],
               textures=[dict(source=0, sampler=0)], images=[dict(bufferView=image_view, mimeType='image/png')],
               samplers=[dict(wrapS=33071, wrapT=33071, magFilter=9729, minFilter=9729)])

    def encode(document):
        body = json.dumps(document, separators=(',', ':')).encode()
        body += b' ' * (-len(body) % 4)
        payload = bytes(binary) + b'\0' * (-len(binary) % 4)
        return (struct.pack('<III', 0x46546c67, 2, 28 + len(body) + len(payload)) +
                struct.pack('<II', len(body), 0x4e4f534a) + body +
                struct.pack('<II', len(payload), 0x004e4942) + payload)

    out.mkdir(parents=True)
    (out / 'cat-textured.glb').write_bytes(encode(doc))
    # Geometry-only diagnostic is never offered as a textured substitute.
    diagnostic = json.loads(json.dumps(doc))
    diagnostic['materials'][0]['pbrMetallicRoughness'].pop('baseColorTexture')
    (out / 'cat-geometry-diagnostic.glb').write_bytes(encode(diagnostic))
    (out / 'Cat_diffuse.png').write_bytes(png.getvalue())
    report = dict(status='SOURCE_PREPARED_NOT_NATIVE_IMPORTED', sourceArchive=str(archive), archiveSha256=ARCHIVE_SHA,
                  sourceFiles={name: hashlib.sha256(data).hexdigest() for name, data in files.items()},
                  sourceQuads=len(faces), triangles=len(indices) // 3, slots=len(pairs),
                  sourcePositions=len(positions), imageDimensions=list(decoded.size),
                  decodedRgbSha256=hashlib.sha256(decoded.tobytes()).hexdigest(),
                  sourceIccDescription=profile_name,
                  sourceIccSha256=hashlib.sha256(profile_bytes).hexdigest() if profile_bytes else None,
                  pngColorSpace='Plain sRGB; ICC normalized through LittleCMS and proved RGB byte identical',
                  uvConversion='OBJ bottom-origin V -> glTF top-origin 1-V; no UV welding',
                  bump='Original bump retained in source archive; no lighting/bump baked or claimed',
                  sampler='Explicit clamp/linear; source UVs lie inside [0,1], no texture resampling',
                  outputs={p.name: dict(bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                           for p in out.iterdir() if p.is_file()})
    (out / 'manifest.json').write_text(json.dumps(report, indent=2) + '\n')
    print('SOURCE_PREPARED_NOT_NATIVE_IMPORTED', len(pairs), 'slots', len(indices) // 3, 'triangles')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    convert(args.archive, args.out)
