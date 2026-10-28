#!/usr/bin/env python3
"""Inspect every class referencing updateInterpolatedForms in each official JAR.

This covers direct bytecode calls in Live2D_Cubism.jar, not reflection, plugins,
other JARs or observed Editor interaction frequency. Fail on missing evidence.
"""
import argparse
import hashlib
from pathlib import Path
import subprocess
import zipfile

VERSIONS = [f'5.{minor}.{patch:02d}' for minor, count in [(0, 7), (1, 4), (2, 4), (3, 5)] for patch in range(count)]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ref', type=Path, default=Path('/opt/dev/projects/turboism/cubism-ref'))
    args = parser.parse_args()
    # Confirm target first, then expand to historical artifacts.
    for version in dict.fromkeys(['5.3.04', *VERSIONS]):
        jar = args.ref / version / 'Live2D_Cubism.jar'
        print(f'=== Version {version} sha256={hashlib.sha256(jar.read_bytes()).hexdigest()} ===', flush=True)
        with zipfile.ZipFile(jar) as archive:
            candidates = [name[:-6] for name in archive.namelist()
                          if name.endswith('.class') and b'updateInterpolatedForms' in archive.read(name)]
        calls = 0
        for cls in candidates:
            print(f'  candidate={cls}', flush=True)
            proc = subprocess.run(['javap', '-c', '-p', '-cp', str(jar), cls],
                                  capture_output=True, text=True, check=True)
            lines = proc.stdout.splitlines()
            for index, line in enumerate(lines):
                if 'invoke' in line and 'updateInterpolatedForms:(Z)Z' in line:
                    calls += 1
                    context = lines[max(0, index - 3):index + 1]
                    for instruction in context:
                        print('    ' + instruction.strip(), flush=True)
                    if 'iconst_1' not in lines[index - 1]:
                        raise AssertionError(f'nonconstant true argument: {version} {cls}')
        if calls != 2:
            raise AssertionError(f'unexpected direct call count: {version} {calls}')
        print(f'  PASS candidates={len(candidates)} directCalls={calls} allLiteralTrue=true', flush=True)


if __name__ == '__main__':
    main()
