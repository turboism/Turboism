#!/usr/bin/env python3
"""Offline UV-domain projection feasibility, not topology/visibility admission.

Fits all source position/UV samples, including ambiguous/folded UVs; no sample
is masked or removed. A tiny ridge only selects otherwise unused controls.
Every fitted vertex is measured against its original 3D position. Native Warp
packing, interpolation, image mapping and occlusion remain separate gates.
"""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import zipfile
import numpy as np

SHA = 'ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11'


def source(archive):
    if hashlib.sha256(archive.read_bytes()).hexdigest() != SHA:
        raise ValueError('foreign source')
    with zipfile.ZipFile(archive) as z:
        lines = z.read(next(n for n in z.namelist() if n.endswith('.obj'))).decode().splitlines()
    positions = np.array([list(map(float, r.split()[1:4])) for r in lines if r.startswith('v ')])
    uvs = np.array([list(map(float, r.split()[1:3])) for r in lines if r.startswith('vt ')])
    faces = [[tuple(int(v)-1 for v in c.split('/')) for c in r.split()[1:]] for r in lines if r.startswith('f ')]
    edges = collections.defaultdict(list)
    for f, face in enumerate(faces):
        for i, c in enumerate(face):
            n = face[(i+1) % 4]
            edges[tuple(sorted((c[0], n[0])))].append((f, c[1], n[1]))
    adjacent = [set() for _ in faces]
    for entries in edges.values():
        if len(entries) == 2:
            a, b = entries
            if set(a[1:]) == set(b[1:]):
                adjacent[a[0]].add(b[0]); adjacent[b[0]].add(a[0])
    groups, seen = [], set()
    for start in range(len(faces)):
        if start in seen:
            continue
        seen.add(start); todo, group = [start], []
        while todo:
            f = todo.pop(); group.append(f)
            for n in sorted(adjacent[f]):
                if n not in seen:
                    seen.add(n); todo.append(n)
        # Position/UV/normal identities stay distinct, even at identical UV values.
        slots = sorted({c for f in group for c in faces[f]})
        groups.append((positions[[c[0] for c in slots]], uvs[[c[1] for c in slots]], len(group)))
    center = (positions.min(axis=0) + positions.max(axis=0)) / 2
    radius = np.linalg.norm(positions-center, axis=1).max()
    return groups, float(400 / radius)


def fit(points, uv, cells):
    low, high = uv.min(axis=0), uv.max(axis=0)
    if np.any(high <= low):
        raise ValueError('collapsed UV domain')
    parameter = (uv-low)/(high-low)*cells
    xy = np.minimum(np.floor(parameter).astype(int), cells-1)
    ab = parameter-xy
    stride, count = cells+1, (cells+1)**2
    base = xy[:, 1]*stride + xy[:, 0]
    ix = np.stack((base, base+1, base+stride, base+stride+1), axis=1)
    a, b = ab[:, 0], ab[:, 1]
    weights = np.stack(((1-a)*(1-b), a*(1-b), (1-a)*b, a*b), axis=1)
    origin = points.mean(axis=0)
    target = points-origin

    def transpose(value):
        return np.column_stack([np.bincount(ix.ravel(), (weights*value[:, axis, None]).ravel(), minlength=count)
                                for axis in range(3)])

    diagonal = np.bincount(ix.ravel(), (weights*weights).ravel(), minlength=count)
    ridge = diagonal.max()*1e-10

    def multiply(value):
        projected = np.sum(weights[:, :, None]*value[ix], axis=1)
        return transpose(projected)+ridge*value

    rhs = transpose(target)
    controls = np.zeros((count, 3))
    residual = rhs.copy()
    z = residual/(diagonal[:, None]+ridge)
    direction = z.copy()
    rz = np.sum(residual*z, axis=0)
    norm = np.maximum(np.linalg.norm(rhs, axis=0), 1)
    converged = False
    for iteration in range(1200):
        product = multiply(direction)
        denominator = np.sum(direction*product, axis=0)
        alpha = np.divide(rz, denominator, out=np.zeros(3), where=denominator != 0)
        controls += direction*alpha
        residual -= product*alpha
        if np.max(np.linalg.norm(residual, axis=0)/norm) < 1e-9:
            converged = True
            break
        z = residual/(diagonal[:, None]+ridge)
        next_rz = np.sum(residual*z, axis=0)
        beta = np.divide(next_rz, rz, out=np.zeros(3), where=rz != 0)
        direction = z+direction*beta
        rz = next_rz
    controls += origin
    actual = np.sum(weights[:, :, None]*controls[ix], axis=1)
    errors = np.linalg.norm(actual-points, axis=1)
    return dict(cells=cells, controls=count, samples=len(points), converged=converged,
                iterations=iteration+1, maximumWorldError=float(errors.max()),
                maximumErrorSample=int(errors.argmax()), rmsWorldError=float(np.sqrt(np.mean(errors**2))),
                ambiguousUvValues=sum(len(v)>1 for v in _uv_positions(points, uv).values()))


def _uv_positions(points, uv):
    result = collections.defaultdict(set)
    for p, t in zip(points, uv):
        result[tuple(t)].add(tuple(p))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    if args.out.exists():
        raise ValueError('new measurement output required')
    groups, scale = source(args.archive)
    report = dict(status='OFFLINE_FEASIBILITY_NOT_NATIVE_ADMITTED', sourceArchiveSha256=SHA,
                  cameraScale=scale, domains=len(groups), sourceQuads=sum(g[2] for g in groups), cases=[])
    args.out.mkdir(parents=True)
    largest = max(len(g[0]) for g in groups)
    for major in (32, 48, 64):
        rows = []
        for points, uv, quads in groups:
            cells = max(4, round(major*np.sqrt(len(points)/largest)))
            row = fit(points, uv, cells); row['quads'] = quads; rows.append(row)
        count = sum(r['controls'] for r in rows)
        maximum = max(r['maximumWorldError'] for r in rows)*scale
        case = dict(majorCells=major, domains=rows, controls=count, sourceFitBoundPixels=maximum,
                    xy49KeyControlSamples=count*49*49,
                    idealFivePixelSourceFit=maximum <= 5, allSolvesConverged=all(r['converged'] for r in rows))
        report['cases'].append(case)
        (args.out/'report.json').write_text(json.dumps(report, indent=2)+'\n')
        print('MAJOR_CELLS', major, 'CONTROLS', count, 'SOURCE_BOUND_PIXELS', maximum,
              'CONVERGED', case['allSolvesConverged'], flush=True)
