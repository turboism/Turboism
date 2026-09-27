#!/usr/bin/env python3
"""Read-only, bounded Linux pointer evidence; no host launch or input commands."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

PHASE = 'parts-tree-drag'
MAX_FILE_BYTES = 16 * 1024 * 1024


def phase_state(path):
    """Read only complete lines; reject oversized/truncated/replaced input in caller."""
    try:
        with open(path, 'rb') as stream:
            stat = os.fstat(stream.fileno())
            data = stream.read(MAX_FILE_BYTES + 1)
    except FileNotFoundError:
        return 'waiting', None, 0
    if len(data) > MAX_FILE_BYTES:
        raise ValueError('probe exceeds byte bound')
    state = 'waiting'
    for line in data.split(b'\n')[:-1]:
        if not line:
            continue
        obj = json.loads(line)
        if not isinstance(obj, dict):
            raise ValueError('non-object probe record')
        if obj.get('type') == 'prompt':
            if obj.get('phase') == PHASE and state == 'waiting':
                state = 'active'
            elif state == 'active':
                state = 'closed'
        if obj.get('type') == 'actor' and obj.get('phase') == PHASE:
            state = 'closed'
        if obj.get('type') == 'summary':
            state = 'closed'
    return state, (stat.st_dev, stat.st_ino), len(data)


def query_pointer(timeout):
    result = subprocess.run(['xdotool', 'getmouselocation', '--shell'],
                            capture_output=True, text=True, timeout=timeout, check=True)
    if len(result.stdout) > 4096:
        raise ValueError('oversized xdotool output')
    values = {}
    for line in result.stdout.splitlines():
        key, sep, value = line.partition('=')
        if sep != '=' or key not in {'X', 'Y', 'SCREEN', 'WINDOW'} or key in values:
            raise ValueError('unexpected xdotool output')
        if not re.fullmatch(r'-?\d{1,20}', value):
            raise ValueError('invalid xdotool integer')
        values[key] = int(value)
    if set(values) != {'X', 'Y', 'SCREEN', 'WINDOW'}:
        raise ValueError('incomplete xdotool output')
    return values


def collect(probe, output, task_id, duration=120, interval=.05, limit=1200,
            query=query_pointer, clock=time, state_reader=phase_state):
    if not 0 < duration <= 300 or not .02 <= interval <= 1 or not 1 <= limit <= 5000:
        raise ValueError('invalid bounds')
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', task_id):
        raise ValueError('invalid task id')
    probe, output = Path(probe), Path(output)
    if not probe.is_absolute() or not output.is_absolute():
        raise ValueError('absolute paths required')
    # CLI additionally confines these paths to Runner-provided task directory.
    with output.open('x', encoding='utf-8') as stream:
        def emit(record):
            stream.write(json.dumps(dict(task_id=task_id, **record), sort_keys=True) + '\n')
            stream.flush()
        start = clock.monotonic()
        count = 0
        identity = None
        size = 0
        def read_state():
            nonlocal identity, size
            state, current, length = state_reader(probe)
            if identity is not None and (current != identity or length < size):
                raise ValueError('probe replaced/truncated/disappeared')
            if current is not None:
                identity, size = current, length
            return state
        reason = 'duration-bound'
        emit({'type': 'collector-start', 'wall_time_ns': clock.time_ns(),
              'clock': 'Linux CLOCK_REALTIME; query interval, not Windows nanoTime'})
        try:
            while clock.monotonic() - start < duration and count < limit:
                state = read_state()
                if state == 'closed':
                    reason = 'actor-or-phase-closed'
                    break
                if state == 'active':
                    remaining = duration - (clock.monotonic() - start)
                    if remaining <= 0:
                        break
                    before = clock.time_ns()
                    values = query(min(.5, remaining))
                    after = clock.time_ns()
                    count += 1
                    # Do not publish coordinates from a query crossing observed closure.
                    if read_state() != 'active':
                        reason = 'actor-during-query-discarded'
                        break
                    emit({'type': 'pointer', 'query_start_time_ns': before,
                          'query_end_time_ns': after, 'pointer': values})
                clock.sleep(min(interval, max(0, duration - (clock.monotonic() - start))))
            if count >= limit:
                reason = 'record-bound'
        except Exception as exc:
            emit({'type': 'collector-end', 'reason': 'error', 'error_type': type(exc).__name__,
                  'queries': count, 'wall_time_ns': clock.time_ns()})
            raise
        emit({'type': 'collector-end', 'reason': reason, 'queries': count,
              'wall_time_ns': clock.time_ns()})
        return reason


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--task-id', required=True)
    args = parser.parse_args()
    root = Path(os.environ['TURBOISM_HOST_VALIDATION_TASK_DIR']).resolve(strict=True)
    probe = root / 'turboism-home/data/dev.turboism.validation.history-native-ui/history-native-ui-ingress.jsonl'
    output = root / 'evidence' / ('host-pointer-' + args.task_id + '.jsonl')
    for path in (probe, output):
        if not path.resolve().is_relative_to(root):
            raise ValueError('path escapes task root')
    collect(probe, output, args.task_id)


if __name__ == '__main__':
    main()
