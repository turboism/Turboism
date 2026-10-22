#!/usr/bin/env python3
"""Run independent offline oracle suites with bounded process concurrency; never launches Cubism."""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]


def run_suite(path):
    started = time.monotonic()
    result = subprocess.run(
        [sys.executable, '-B', '-m', 'unittest', 'discover', '-v',
         '-s', 'scripts/test', '-p', path.name],
        cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        check=False,
    )
    return path.name, result, time.monotonic() - started


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('pattern', nargs='?', default='test_model_import_3d_*.py',
                        choices=['test_model_import_3d_*.py'])
    args = parser.parse_args()
    started = time.monotonic()
    suites = list((ROOT / 'scripts/test').glob(args.pattern))
    if not suites:
        raise RuntimeError('no model-import-3d tooling suites found')
    # Start the expensive Java producer, dense joint and transport matrices first.
    priority = ('image_binding', 'joint_pose', 'host_validation', 'morph_order')
    suites.sort(key=lambda p: (next((i for i, name in enumerate(priority)
                                    if p.stem == 'test_model_import_3d_' + name), len(priority)), p.name))
    status = 0
    with ThreadPoolExecutor(max_workers=4) as pool:
        futures = [pool.submit(run_suite, path) for path in suites]
        for future in as_completed(futures):
            name, result, elapsed = future.result()
            print(result.stdout, end='')
            print(f'TIMING {name}: {elapsed:.3f}s', flush=True)
            status = max(status, int(result.returncode != 0))
    print(f'TIMING model-import-3d tooling: {time.monotonic() - started:.3f}s', flush=True)
    return status


if __name__ == '__main__':
    raise SystemExit(main())
