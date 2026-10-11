"""Independent stored/evaluated order gate for the fixed parallel sheets, not pixels."""
from pathlib import Path
import itertools
import runpy

GEOMETRY = runpy.run_path(str(Path(__file__).with_name('verify-surface-geometry.py')))
require, f32 = GEOMETRY['require'], GEOMETRY['f32']

def order(surface, yaw):
    return 500 if yaw == 0 else 501 if ((surface == 0) == (yaw < 0)) else 499

def verify_order(report, glb):
    geometry = GEOMETRY['verify_sheets'](report, glb)
    keys, poses = GEOMETRY['inventory']()
    required_tuples = set(itertools.product(keys, repeat=2))
    for surface, mesh in enumerate(report['before']):
        axes = {a['id']: a['guid'] for a in mesh['axes']}
        forms = {f['guid']: f for f in mesh['forms']}
        require(len(forms) == len(mesh['forms']), 'unique stored form identity')
        tuples, identities = set(), set()
        for cell in mesh['cells']:
            coordinates = {c['guid']: f32(c['value']) for c in cell['coordinates']}
            require(len(cell['coordinates']) == len(coordinates) == 2 and set(coordinates) == set(axes.values()), 'complete order coordinates')
            pose = tuple(coordinates[axes[a]] for a in GEOMETRY['AXES'])
            require(pose not in tuples and cell['formGuid'] not in identities and cell['formGuid'] in forms, 'unique order tuple/form')
            tuples.add(pose); identities.add(cell['formGuid'])
            require(type(forms[cell['formGuid']]['drawOrder']) is int
                    and forms[cell['formGuid']]['drawOrder'] == order(surface, pose[0]), 'source-derived stored order')
        require(tuples == required_tuples and identities == set(forms), 'all stored order tuples')
    transition = min(k for k in keys if k > 0)
    separated = ordered = 0
    minimum_gap = None
    for row, pose in zip(report['samples'], poses):
        yaw, pitch = pose
        actual = [m['actual']['calculated'] for m in row['meshes']]
        orders = [m['drawOrder'] for m in actual]
        require(all(type(v) is int and 499 <= v <= 501 for v in orders), 'native order range')
        if abs(yaw) < transition:
            # The exact source proof establishes no overlap for all pitches in this
            # strip. Check the actually evaluated geometry as well, without assuming
            # an undocumented integer rounding rule for the native order interpolator.
            gap = min(f32(v[0]) for v in actual[1]['positions']) - max(f32(v[0]) for v in actual[0]['positions'])
            require(gap > .2, 'evaluated transition sheets overlap or exhaust error margin')
            minimum_gap = gap if minimum_gap is None else min(minimum_gap, gap)
            if yaw == 0: require(orders == [500,500], 'neutral native order')
            separated += 1
        else:
            # For Ry*Rx the two planes have normal (cos(yaw),0,-sin(yaw)).
            # At common screen coordinates delta-depth=delta-source-X/(-sin(yaw)).
            # Smaller depth is nearer; higher Cubism draw order must paint it last.
            require(orders == [order(i,yaw) for i in range(2)], 'native front/back order')
            ordered += 1
    return dict(status='SHEET_STORED_AND_EVALUATED_ORDER_CONTENT_ONLY_PASS',
                samples=geometry['samples'], orderedSamples=ordered, separatedSamples=separated,
                minimumTransitionGapPixels=minimum_gap, pixelsVerified=False, processAdmitted=False)
