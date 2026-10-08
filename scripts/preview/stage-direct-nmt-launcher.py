#!/usr/bin/env python3
"""Task-local pre-launch diagnostic: preserve official BAT and append NMT to its direct MAXMEMORY arguments."""
import hashlib
import json
import os
from pathlib import Path
import re
import sys

PROXY_SHA = 'c9b924644749a5e3fc8131443a8ff162278791a7e64e8dd8756625a5a9e2b9e4'
OFFICIAL_BAT_SHA = '4da7e34e346280cdce02527fc7746cfd563d449033e018b7f3af1205e7b2ca5b'
SUFFIX = b'\r\nrem T100 task-local NMT diagnostic; official BAT is unchanged\r\nset MAXMEMORY=-XX:MaxRAMPercentage=100 -XX:NativeMemoryTracking=summary\r\n'


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def stage(task, evidence, prefix, expected_task):
    task = Path(task).resolve(strict=True)
    evidence = Path(evidence).resolve(strict=True)
    prefix = Path(prefix).resolve(strict=True)
    if str(task) != expected_task or prefix != task / 'prefix' or evidence != task / 'evidence':
        raise ValueError('exact task-owned prefix/evidence required')
    app = prefix / 'pfx/drive_c/Program Files/Live2D Cubism 5.3.03'
    proxy = app / 'ProxyConfig.bat'; official = app / 'CubismEditor5.bat'
    if proxy.is_symlink() or official.is_symlink() or not proxy.is_file() or not official.is_file():
        raise ValueError('regular official cloned files required')
    if not proxy.resolve().is_relative_to(prefix) or not official.resolve().is_relative_to(prefix):
        raise ValueError('launcher escaped task prefix')
    raw = proxy.read_bytes(); bat = official.read_bytes()
    if sha(raw) != PROXY_SHA or sha(bat) != OFFICIAL_BAT_SHA:
        raise ValueError('unreviewed official launcher/config bytes')
    if len(re.findall(rb'(?m)^set MAXMEMORY=-XX:MaxRAMPercentage=100\r?$', bat)) != 1:
        raise ValueError('official memory argument mismatch')
    if b'if exist ProxyConfig.bat call ProxyConfig.bat' not in bat or b'  %MAXMEMORY% ^' not in bat:
        raise ValueError('official configuration/command seam mismatch')
    before = evidence / 'nmt-proxy-before.bin'
    after = evidence / 'nmt-proxy-after.bin'
    report_path = evidence / 'direct-nmt-launcher.json'
    if any(path.exists() for path in (before, after, report_path)):
        raise ValueError('existing task launcher evidence; never restage')
    with before.open('xb') as stream:
        stream.write(raw)
    updated = raw + SUFFIX
    # This is exclusively the disposable task clone. Do not touch the official BAT or golden prefix.
    with proxy.open('wb') as stream:
        stream.write(updated)
    if proxy.read_bytes() != updated or official.read_bytes() != bat:
        raise ValueError('staged launcher/config verification failed')
    with after.open('xb') as stream:
        stream.write(updated)
    report = dict(status='STAGED_TASK_ONLY_DIRECT_NMT_OPTION_NOT_HOST_VALIDATED',
                  officialBatSha256=sha(bat), originalProxySha256=sha(raw), stagedProxySha256=sha(updated),
                  directMemoryArguments=['-XX:MaxRAMPercentage=100', '-XX:NativeMemoryTracking=summary'],
                  preservedOriginalProxyBytes=True, officialBatChanged=False, goldenPrefixChanged=False)
    with report_path.open('x') as stream:
        json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'])


if __name__ == '__main__':
    if len(sys.argv) != 12 or sys.argv[7] != '5303':
        raise SystemExit('reviewed 5303 pre-launch hook context required')
    stage(sys.argv[1], sys.argv[3], sys.argv[4], os.environ.get('TURBOISM_HOST_VALIDATION_TASK_DIR'))
