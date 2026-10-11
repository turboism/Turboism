#!/usr/bin/env python3
"""Refusal regression over a bound real capture; never edits frozen evidence."""
import argparse
import copy
import json
from pathlib import Path
import runpy
import numpy as np

p = argparse.ArgumentParser()
for name in ('capture', 'report', 'prepared', 'artifacts', 'out'):
    p.add_argument('--'+name, type=Path, required=True)
a = p.parse_args()
verify = runpy.run_path(str(Path(__file__).with_name('verify-apple-geometry.py')))['verify']
c = json.loads(a.capture.read_text()); r = json.loads(a.report.read_text())
verify(c, r, a.prepared, a.artifacts)
checks = []


def reject(name, altered):
    try:
        verify(altered, r, a.prepared, a.artifacts)
    except ValueError:
        checks.append(name)
    else:
        raise AssertionError('accepted '+name)


for name, bad in [('schemaVersion', 'mi3d-official-core-capture-v2'),
                  ('poseProtocol', 'mi3d-cube-geometry-377-v1'),
                  ('mocSha256', '0'*64), ('canvas', {})]:
    reject(name, dict(c, **{name: bad}))
reject('missing pose', dict(c, frames=c['frames'][:-1]))
frame = dict(c['frames'][0]); values = list(frame['values'])
axis = c['parameters']['ids'].index(c['axes'][0])
values[axis] = float(np.nextafter(np.float32(values[axis]), np.float32(0)))
frame['values'] = values
reject('one ULP parameter change', dict(c, frames=[frame]+c['frames'][1:]))
params = copy.deepcopy(c['parameters']); params['keys'][-1] = [-30, 0, 30]
reject('three keys', dict(c, parameters=params))
for name, value in [('textureIndex', 1), ('constantFlags', 1), ('masks', [0]),
                    ('multiplyColor', [0.5, 1, 1, 1]),
                    ('opacity', 0), ('drawOrder', 0), ('vertexCount', 80),
                    ('indices', []), ('uvs', []), ('positions', [])]:
    frame = dict(c['frames'][0]); rows = list(frame['drawables'])
    rows[0] = dict(rows[0], **{name: value}); frame['drawables'] = rows
    reject(name, dict(c, frames=[frame]+c['frames'][1:]))
frame = dict(c['frames'][0]); rows = list(frame['drawables'])
xy = list(rows[0]['positions']); xy[0] += .02
rows[0] = dict(rows[0], positions=xy); frame['drawables'] = rows
reject('two pixel drift', dict(c, frames=[frame]+c['frames'][1:]))
with a.out.open('x') as f:
    json.dump(dict(status='PASS_REFUSAL_REGRESSION', rejected=checks, nativeExecuted=False), f, indent=2)
    f.write('\n')
print('PASS', len(checks), 'refusals')
