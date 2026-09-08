#!/usr/bin/env python3
"""Read-only Linux/Proton main-Java occupancy sampler; no launches or signals."""
import importlib.util
import json
from pathlib import Path
import sys
import time

HELPER = Path(__file__).with_name('host_memory_identity.py')
SPEC = importlib.util.spec_from_file_location('memory_task_identity', HELPER)
IDENTITY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IDENTITY
SPEC.loader.exec_module(IDENTITY)
COUNTER_SPEC = importlib.util.spec_from_file_location('task_resource_counters', Path(__file__).with_name('host_resource_counters.py'))
COUNTERS = importlib.util.module_from_spec(COUNTER_SPEC)
COUNTER_SPEC.loader.exec_module(COUNTERS)


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


def sample(process, scope):
    started = time.monotonic_ns()
    scope.verify_process(process)
    if scope.find_java() != process:
        raise RuntimeError('task Editor identity is no longer unique')
    root = scope.proc / str(process.pid)
    cpu = COUNTERS.cpu_snapshot((root / 'stat').read_text())
    if cpu['started'] != process.started:
        raise RuntimeError('CPU process identity changed')
    gpu = COUNTERS.gpu_snapshot(root)
    status = kilobytes((root / 'status').read_text(), ('VmRSS', 'VmHWM', 'VmSwap'))
    rollup = kilobytes((root / 'smaps_rollup').read_text(),
                      ('Rss', 'Pss', 'Private_Clean', 'Private_Dirty', 'Swap', 'SwapPss'))
    same_process(process, scope.read_process(process.pid))
    scope.verify_process(process)
    identity = scope.metadata()
    system = kilobytes((scope.proc / 'meminfo').read_text(), ('MemAvailable', 'SwapFree'))
    return dict(epochMillis=time.time_ns() // 1_000_000, monotonicNs=time.monotonic_ns(),
                readDurationNs=time.monotonic_ns() - started, pid=process.pid, started=process.started,
                status=status, rollup=rollup, system=system, cpu=cpu, gpu=gpu, scope=identity)


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
    scope = None
    try:
        scope = IDENTITY.BoundScope()
        process = None
        deadline = time.monotonic() + 180
        while process is None and time.monotonic() < deadline:
            process = scope.find_java()
            if process is None:
                if (task / 'evidence/wrapper.exit').exists():
                    raise RuntimeError('launcher exited before sampler attachment')
                time.sleep(0.1)
        if process is None:
            raise RuntimeError('no bound-scope task Editor JVM appeared')
        first = sample(process, scope)
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
                    scope.verify_process(process)
                    publish(complete, f'status=PASS\nsamples={count}\npid={process.pid}\n')
                    return
                time.sleep(1)
                current = sample(process, scope)
        raise RuntimeError('memory observation did not finish within 900 seconds')
    except Exception as failure:
        if not complete.exists():
            publish(complete, 'status=FAIL\nfailure=' + type(failure).__name__ + ': ' + str(failure).replace('\n', ' ') + '\n')
        raise
    finally:
        if scope is not None:
            scope.close()


if __name__ == '__main__':
    measure(Path(sys.argv[1]))
