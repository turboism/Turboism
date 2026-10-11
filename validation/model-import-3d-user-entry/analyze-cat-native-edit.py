#!/usr/bin/env python3
"""Compare field-level native Cat no-write commits; diagnosis is not edit acceptance."""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path

SOURCE_SHA = '4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
SEED_SHA = '0bfdbbb6bd0521a07ed566241b19a2a5c69b1d7f36a9efed1d5b744a9bb7f8b4'


def require(condition, message):
    if not condition:
        raise ValueError(message)


def faces(indices, oriented):
    require(len(indices) % 3 == 0, 'complete triangles')
    result = []
    for start in range(0, len(indices), 3):
        a, b, c = indices[start:start + 3]
        result.append(min((a, b, c), (b, c, a), (c, a, b)) if oriented else tuple(sorted((a, b, c))))
    return Counter(result)


def compare_points(before, after):
    require(len(before) == len(after) and before, 'complete corner count')
    require(all(len(point) == 2 and all(math.isfinite(x) for x in point) for point in before + after), 'finite 2D coordinates')
    errors = [math.dist(a, b) for a, b in zip(before, after)]
    return dict(changedSlots=sum(error != 0 for error in errors), maximumDistance=max(errors),
                allAfterInteger=all(x == round(x) for point in after for x in point),
                allAfterNearestInteger=all(b == round(a) for p, q in zip(before, after) for a, b in zip(p, q)))


def analyze(root):
    root = Path(root)
    raw = (root / 'native-report.json').read_bytes()
    report = json.loads(raw)
    require(report['schemaVersion'] == 'mi3d-cat-native-edit-diagnostic-v2' and report['status'] == 'PASS', 'completed field diagnosis')
    require(report['inputSha256'] == SOURCE_SHA and not report['all512NativeEditorAccepted'], 'exact Cat diagnostic scope')
    require(report['sourceUnchanged'] and report['beforeBank'] == report['afterBank'], 'entire bank restored')
    lifecycle = json.loads((root / 'host-job/evidence/lifecycle-result.json').read_text())
    require(lifecycle['normalExit'] and lifecycle['validationStatus'] == 'PASS' and lifecycle['fixtureUnchanged'] and lifecycle['identityVerified'], 'normal independent host lifecycle')
    require(lifecycle['details']['sourceFixtureBeforeSha256'] == SEED_SHA and lifecycle['details']['sourceFixtureAfterSha256'] == SEED_SHA, 'exact accepted seed unchanged')
    rows = report['meshEditors']
    require([row['index'] for row in rows] == [0, 256, 511], 'three explicit partitions')
    metrics = []
    for row in rows:
        require(row['buttonEnabled'] and row['nativeEditorEntered'] and row['nativeEditorEnded'] and row['sourceRestored'] and row['vertexWrites'] is False, 'real native entry/commit/restore')
        require(not row['dialogs'] and row['formsEqualAfterCommit'] and row['axesEqualAfterCommit'] and row['cellsEqualAfterCommit'], 'parameter bank unchanged without refusal')
        before, after = row['geometryBefore'], row['geometryAfter']
        require(row['positionsEqualAfterCommit'] == (before['positions'] == after['positions']), 'position flag matches original arrays')
        require(row['uvsEqualAfterCommit'] == (before['uvs'] == after['uvs']), 'UV flag matches original arrays')
        require(row['indicesEqualAfterCommit'] == (before['indices'] == after['indices']), 'index flag matches original arrays')
        require(row['geometryEqualAfterCommit'] == (before == after), 'geometry flag matches original arrays')
        require(row['sourceVertices'] == row['nativePoints'] == len(before['positions']), 'observed native corner inventory')
        require(len(before['positions']) == len(before['uvs']) == len(after['uvs']), 'UV corner inventory')
        require(all(isinstance(i, int) and 0 <= i < len(points['positions']) for points in (before, after) for i in points['indices']), 'source indices in bounds')
        metrics.append(dict(index=row['index'], id=row['id'], positions=compare_points(before['positions'], after['positions']),
                            uvs=compare_points(before['uvs'], after['uvs']), exactIndices=before['indices'] == after['indices'],
                            sameOrientedFaces=faces(before['indices'], True) == faces(after['indices'], True),
                            sameUnorientedFaces=faces(before['indices'], False) == faces(after['indices'], False),
                            sourceEqualAfterCommit=row['sourceEqualAfterCommit']))
    result = dict(status='PASS_FIELD_DIAGNOSIS_NOT_EDIT_ACCEPTANCE', nativeReportSha256=hashlib.sha256(raw).hexdigest(),
                  completeBankRestored=True, diagnosticPartitions=metrics, all512NativeEditorAccepted=False,
                  stableNoWriteCommitForSample=all(row['sourceEqualAfterCommit'] for row in rows))
    target = root / 'independent-native-edit-diff.json'
    require(not target.exists(), 'new result required')
    target.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('controller', type=Path)
    analyze(parser.parse_args().controller)
