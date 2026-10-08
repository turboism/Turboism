#!/usr/bin/env python3
"""Audit native placement evidence against a separately accepted source inventory."""
import argparse
import json
import math
from pathlib import Path


def verify(report, reference):
    def require(ok, message):
        if not ok:
            raise ValueError(message)

    require(report['status'] == 'PASS', 'native probe did not pass')
    require(report['scope'] == 'REAL_MENU_XYZ_OUTER_PLACEMENT_TRANSLATION_AND_SCALE', 'wrong native scope')
    require(report['inputSha256'] == reference['inputSha256'], 'foreign source')
    meshes = report['imported']['meshes']
    expected = reference['imported']['meshes']
    signature = lambda m: (m['vertexCount'], m['triangleCount'], m['geometrySha256'], m['localSha256'])
    require(len(meshes) > 0 and [signature(m) for m in meshes] == [signature(m) for m in expected],
            'source geometry/UV/local inventory differs from accepted model')
    require(len({m['id'] for m in meshes}) == len(meshes), 'duplicate mesh identities')
    require(report['undoRestored'] is True and report['redoRestored'] is True, 'import history not restored')
    rows = report['placement']
    poses = [(0, 0, 0), (35, -47, 61), (-127, 103, -155)]
    require(len(rows) == 6 and [(r['X'], r['Y'], r['Z'], r['scale']) for r in rows] ==
            [(*pose, scale) for pose in poses for scale in (1, 1.25)], 'incomplete affine cases')
    maximum = 0
    for row in rows:
        require((row['dx'], row['dy']) == (23, -11), 'wrong translation')
        before, after = row['passBefore'], row['passAfter']
        require(len(before) == len(after) == len(meshes), 'partial native pass witness')
        require(all(type(a) is int and type(b) is int and 0 < a < b for a, b in zip(before, after)),
                'stale native calculation')
        error = row['maximumErrorPixels']
        require(type(error) in (int, float) and math.isfinite(error) and 0 <= error <= .002,
                'rigid placement error exceeds fixed .002px gate')
        maximum = max(maximum, error)
    return dict(status='NATIVE_OUTER_PLACEMENT_PASS', sourceSha256=report['inputSha256'],
                meshes=len(meshes), vertices=sum(m['vertexCount'] for m in meshes),
                cases=6, maximumErrorPixels=maximum,
                scope='Three joint XYZ poses, translation and uniform scaling; native probe protects child banks and restores state. No mesh editing, occlusion or export claim.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--reference', required=True, type=Path)
    parser.add_argument('--out', required=True, type=Path)
    args = parser.parse_args()
    result = verify(json.loads(args.report.read_text()), json.loads(args.reference.read_text()))
    args.out.write_text(json.dumps(result, indent=2) + '\n')
    print(result['status'], result['maximumErrorPixels'])
