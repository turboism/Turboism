#!/usr/bin/env python3
"""Native binary parser corruption regressions, without editing frozen evidence."""
import argparse
import copy
import gzip
import json
from pathlib import Path
import runpy
import struct

p = argparse.ArgumentParser()
for name in ('capture', 'coordinates', 'out'):
    p.add_argument('--'+name, type=Path, required=True)
a = p.parse_args(); a.out.mkdir(parents=True, exist_ok=False)
Frames = runpy.run_path(str(Path(__file__).with_name('verify-apple-domain.py')))['Frames']
c = json.loads(a.capture.read_text())
assert sum(1 for _ in Frames(c, a.coordinates)) == 353
with gzip.open(a.coordinates, 'rb') as stream:
    two = stream.read(16+2*100258*8)
small = dict(c, frames=c['frames'][:2]); checks = []


def reject(name, raw, meta=small):
    file = a.out/(name+'.gz')
    with gzip.open(file, 'wb') as stream:
        stream.write(raw)
    try:
        list(Frames(meta, file))
    except ValueError:
        checks.append(name)
    else:
        raise AssertionError('accepted '+name)


reject('wrong-magic', b'FOREIGN!'+two[8:])
reject('wrong-frame-count', two[:8]+struct.pack('>i', 352)+two[12:])
reject('wrong-vertex-count', two[:12]+struct.pack('>i', 100257)+two[16:])
reject('truncated-vertices', two[:-1])
reject('trailing-data', two+b'X')
bad = copy.deepcopy(small); bad['frames'][0]['drawables'][0]['id'] = 'foreign'
reject('foreign-mesh', two, bad)
bad = copy.deepcopy(small); bad['frames'][1]['drawables'][0]['invariantSha256'] = '0'*64
reject('changed-topology-uv-invariant', two, bad)
with (a.out/'tests.json').open('x') as stream:
    json.dump(dict(status='PASS_BINARY_PARSER_REGRESSION', rejected=checks,
                   positiveFramesDecoded=353, nativeExecuted=False), stream, indent=2)
    stream.write('\n')
print('PASS', len(checks), 'binary corruption refusals')
