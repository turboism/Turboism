#!/usr/bin/env python3
"""Read-only Linux/Proton main-Java occupancy sampler; no launches or signals."""
import importlib.util
import json
import os
from pathlib import Path
import sys
import time

HELPER = Path(__file__).with_name('host-task-processes.py')
if not HELPER.is_file():
    HELPER = Path(__file__).resolve().parents[1] / 'preview/host-task-processes.py'
SPEC = importlib.util.spec_from_file_location('memory_task_identity', HELPER)
IDENTITY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IDENTITY
SPEC.loader.exec_module(IDENTITY)


def kilobytes(text, required):
    result = {}
    for line in text.splitlines():
        key, separator, rest = line.partition(':')
        if separator and key in required:
            fields = rest.split()
            if len(fields) != 2 or fields[1] != 'kB' or int(fields[0]) < 0:
                raise ValueError('invalid memory field: ' + key)
            result[key] = int(fields[0]) * 1024
    if set(result) != set(required):
        raise ValueError('missing memory fields: ' + ','.join(sorted(set(required) - set(result))))
    return result


def same_process(before, after):
    if after is None or (before.pid, before.started, before.uid) != (after.pid, after.started, after.uid):
        raise RuntimeError('measured process exited or changed identity')


def find_java(task):
    found = []
    for directory in Path('/proc').iterdir():
        if not directory.name.isdigit():
            continue
        try:
            name = (directory / 'comm').read_text().strip().lower()
        except FileNotFoundError:
            continue
        if name not in ('java.exe', 'javaw.exe'):
            continue
        process = IDENTITY.read_process(int(directory.name))
        if process is not None and process.uid == os.getuid() and IDENTITY.tagged(process, task):
            if 'com.live2d.cubism.CECubismEditorApp' in process.command:
                found.append(process)
    if len(found) > 1:
        raise RuntimeError('multiple task Editor JVMs; memory attribution ambiguous')
    return found[0] if found else None


def sample(process):
    before = IDENTITY.read_process(process.pid)
    same_process(process, before)
    root = Path('/proc') / str(process.pid)
    started = time.monotonic_ns()
    status = kilobytes((root / 'status').read_text(), ('VmRSS', 'VmHWM', 'VmSwap'))
    rollup = kilobytes((root / 'smaps_rollup').read_text(),
                      ('Rss', 'Pss', 'Private_Clean', 'Private_Dirty', 'Swap', 'SwapPss'))
    same_process(process, IDENTITY.read_process(process.pid))
    system = kilobytes(Path('/proc/meminfo').read_text(), ('MemAvailable', 'SwapFree'))
    return dict(epochMillis=time.time_ns() // 1_000_000, monotonicNs=time.monotonic_ns(),
                readDurationNs=time.monotonic_ns() - started, pid=process.pid, started=process.started,
                status=status, rollup=rollup, system=system)


def publish(path, text):
    temporary = path.with_suffix('.tmp')
    with temporary.open('x') as output:
        output.write(text)
    if path.exists():
        raise RuntimeError('memory observation evidence already exists')
    temporary.rename(path)


def validate_window(rows, ready, end):
    start_ms, end_ms, seconds = int(ready['epochMillis']), int(end['epochMillis']), int(ready['seconds'])
    if not 30 <= seconds <= 300 or end_ms - start_ms < seconds * 1000 - 100:
        raise RuntimeError('incomplete idle observation duration')
    if not rows or rows[0]['epochMillis'] >= start_ms or rows[-1]['epochMillis'] < end_ms:
        raise RuntimeError('sampler did not cover loading and the complete idle window')
    idle = [row for row in rows if start_ms <= row['epochMillis'] <= end_ms]
    if len(idle) < seconds * 0.8:
        raise RuntimeError('too few idle samples')
    gaps = [(b['monotonicNs'] - a['monotonicNs']) / 1e9 for a, b in zip(rows, rows[1:])]
    if any(gap <= 0 or gap > 5 for gap in gaps):
        raise RuntimeError('memory sample timing gap outside 0..5 seconds')


def measure(task):
    task = task.absolute()
    IDENTITY.verify_task(task)
    directory = task / 'turboism-home/state/texture-upload/memory'
    directory.mkdir(parents=True, exist_ok=True)
    output = task / 'evidence/memory-samples.jsonl'
    complete = directory / 'complete.properties'
    try:
        process = None
        deadline = time.monotonic() + 180
        while process is None and time.monotonic() < deadline:
            process = find_java(task)
            if process is None:
                if (task / 'evidence/wrapper.exit').exists():
                    raise RuntimeError('launcher exited before sampler attachment')
                time.sleep(0.1)
        if process is None:
            raise RuntimeError('no prefix-verified task Editor JVM appeared')
        first = sample(process)
        publish(directory / 'attached.properties', f'pid={process.pid}\nstarted={process.started}\nuid={process.uid}\n')
        count = 0
        rows = []
        deadline = time.monotonic() + 900
        with output.open('x') as stream:
            current = first
            while time.monotonic() < deadline:
                current['phase'] = 'idle' if (directory / 'ready.properties').exists() else 'loading'
                stream.write(json.dumps(current, separators=(',', ':')) + '\n')
                stream.flush()
                rows.append(current)
                count += 1
                if (directory / 'end.properties').exists():
                    if not (directory / 'ready.properties').is_file():
                        raise RuntimeError('memory observation ended without readiness')
                    validate_window(rows, IDENTITY.properties(directory / 'ready.properties'),
                                    IDENTITY.properties(directory / 'end.properties'))
                    IDENTITY.verify_task(task)
                    publish(complete, f'status=PASS\nsamples={count}\npid={process.pid}\n')
                    return
                time.sleep(1)
                current = sample(process)
        raise RuntimeError('memory observation did not finish within 900 seconds')
    except Exception as failure:
        if not complete.exists():
            publish(complete, 'status=FAIL\nfailure=' + type(failure).__name__ + ': ' + str(failure).replace('\n', ' ') + '\n')
        raise


if __name__ == '__main__':
    measure(Path(sys.argv[1]))
