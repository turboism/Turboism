#!/usr/bin/env python3
"""Read the final task-clone launcher before cleanup; never modify its inputs."""
import hashlib
import json
import os
from pathlib import Path
import sys

OFFICIAL_SHA = '4da7e34e346280cdce02527fc7746cfd563d449033e018b7f3af1205e7b2ca5b'
ORIGINAL_SHA = 'c9b924644749a5e3fc8131443a8ff162278791a7e64e8dd8756625a5a9e2b9e4'
SUFFIX = b'\r\nrem T100 task-local NMT diagnostic; official BAT is unchanged\r\nset MAXMEMORY=-XX:MaxRAMPercentage=100 -XX:NativeMemoryTracking=summary\r\n'


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def regular(path, owner):
    if path.is_symlink() or not path.is_file() or not path.resolve(strict=True).is_relative_to(owner):
        raise ValueError('regular task-owned evidence/launcher required')
    return path.read_bytes()


def capture(task, evidence, prefix, expected_task):
    task = Path(task).resolve(strict=True)
    evidence = Path(evidence).resolve(strict=True)
    prefix = Path(prefix).resolve(strict=True)
    if str(task) != expected_task or prefix != task / 'prefix' or evidence != task / 'evidence':
        raise ValueError('exact task-owned prefix/evidence required')
    app = prefix / 'pfx/drive_c/Program Files/Live2D Cubism 5.3.03'
    before = regular(evidence / 'nmt-proxy-before.bin', evidence)
    staged = regular(evidence / 'nmt-proxy-after.bin', evidence)
    report = json.loads(regular(evidence / 'direct-nmt-launcher.json', evidence))
    if digest(before) != ORIGINAL_SHA or staged != before + SUFFIX or report.get('stagedProxySha256') != digest(staged):
        raise ValueError('unreviewed staged NMT config evidence')
    final = regular(app / 'ProxyConfig.bat', prefix)
    official = regular(app / 'CubismEditor5.bat', prefix)
    result = dict(status='PASS_FINAL_TASK_CONFIG_UNCHANGED' if final == staged and digest(official) == OFFICIAL_SHA
                  else 'FAIL_FINAL_TASK_CONFIG_CHANGED', finalProxySha256=digest(final),
                  stagedProxySha256=digest(staged), officialBatSha256=digest(official), task=str(task))
    destinations = [evidence / 'nmt-proxy-final.bin', evidence / 'direct-nmt-final-launcher.json']
    if any(path.exists() or path.is_symlink() for path in destinations):
        raise ValueError('final evidence already exists; never overwrite')
    with destinations[0].open('xb') as stream:
        stream.write(final)
    with destinations[1].open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    if result['status'] != 'PASS_FINAL_TASK_CONFIG_UNCHANGED':
        raise ValueError(result['status'])
    print(result['status'])
    return result


if __name__ == '__main__':
    if len(sys.argv) != 12 or sys.argv[7] != '5303':
        raise SystemExit('reviewed 5303 pre-cleanup hook context required')
    capture(sys.argv[1], sys.argv[3], sys.argv[4], os.environ.get('TURBOISM_HOST_VALIDATION_TASK_DIR'))
