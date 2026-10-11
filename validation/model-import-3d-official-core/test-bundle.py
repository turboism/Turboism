#!/usr/bin/env python3
"""Validate frozen closure and reject tampering in an owned temporary COPY."""
import argparse
import json
from pathlib import Path
import runpy
import shutil
import tempfile

M=runpy.run_path(str(Path(__file__).with_name('prepare-bundle.py')))


def test(bundle):
    manifest=M['verify'](bundle);checks=1
    with tempfile.TemporaryDirectory(prefix='mi3d-core-bundle-test-') as tmp:
        target=Path(tmp)/'copy';shutil.copytree(bundle,target)
        def reject():
            nonlocal checks
            try:M['verify'](target)
            except ValueError:checks+=1;return
            raise AssertionError('changed closure accepted')
        path=target/'model.moc3';original=path.read_bytes();path.write_bytes(original+b'changed');reject();path.write_bytes(original)
        path=target/'native/Live2DCubismCoreJNI.dll';original=path.read_bytes();path.unlink();reject();path.write_bytes(original)
        path=target/'unexpected.jar';path.write_bytes(b'undeclared');reject();path.unlink()
        path=target/'native/linked.dll';path.symlink_to(target/'native/vcruntime140.dll');reject();path.unlink()
        M['verify'](target);checks+=1
    M['verify'](bundle);checks+=1
    print(json.dumps(dict(status='OFFLINE_PASS',checks=checks,hostExecuted=False,files=len(manifest['files']),mocSha256=manifest['mocSha256'])))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--bundle',type=Path,required=True)
    test(p.parse_args().bundle)
