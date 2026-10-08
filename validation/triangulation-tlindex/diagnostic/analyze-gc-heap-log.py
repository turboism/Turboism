"""Extract timestamped HotSpot G1 heap summaries; diagnostic evidence only."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re


PREFIX = re.compile(r'^\[([^]]+)\]\[(\d+)ms\]\[\s*(info|debug)\s*\]\[([^]]+)\]\s*(.*)$')
PHASE = re.compile(r'GC\((\d+)\) Heap (before|after) GC invocations=')
HEAP = re.compile(r'GC\((\d+)\)\s+garbage-first heap\s+total (\d+)K, used (\d+)K')


def analyze(path):
    phases = {}
    summaries = []
    events = []
    g1 = False
    last_uptime = -1
    for line_number, line in enumerate(path.read_text(errors='strict').splitlines(), 1):
        matched = PREFIX.match(line)
        if not matched:
            continue
        time, uptime, level, tags, message = matched.groups()
        tags = ','.join(tag.strip() for tag in tags.split(','))
        if 'gc' not in tags.split(','):
            continue
        epoch = int(datetime.datetime.fromisoformat(time).timestamp() * 1000)
        uptime = int(uptime)
        # Concurrent GC logging may interleave timestamps across threads. Preserve
        # raw ordering and timestamps; do not sort away an inversion.
        if message == 'Using G1':
            g1 = True
        item = {'line': line_number, 'epochMillis': epoch, 'uptimeMillis': uptime,
                'level': level, 'tags': tags, 'message': message,
                'uptimeOrderInversion': uptime < last_uptime}
        last_uptime = uptime
        phase = PHASE.search(message)
        if phase:
            phases[int(phase[1])] = phase[2]
        heap = HEAP.search(message)
        if heap:
            gc_id, total, used = map(int, heap.groups())
            if gc_id not in phases or used > total:
                raise ValueError('unbound/invalid G1 heap summary')
            summaries.append(dict(item, gcId=gc_id, phase=phases[gc_id],
                                  committedHeapBytes=total * 1024, usedHeapBytes=used * 1024))
        events.append(item)
    completed = {s['gcId'] for s in summaries if s['phase'] == 'before'} & {
        s['gcId'] for s in summaries if s['phase'] == 'after'}
    if not g1 or not completed:
        raise ValueError('G1 admission or completed GC heap evidence missing')
    return {'status': 'PASS_TIMESTAMPED_G1_HEAP_LOG_DIAGNOSTIC_ONLY',
            'inputSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'heapSummaries': summaries, 'gcEvents': events,
            'productionAcceptance': 'NOT_GRANTED',
            'limitations': ['Logged heap commitment is distinct from resident pages, PSS and live post-full-GC objects.',
                            'GC summaries identify no allocation owner and do not establish a leak cause.',
                            'Logging changes the diagnostic process; timings must not replace frozen performance legs.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('console', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    report = analyze(args.console)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'], len(report['heapSummaries']), 'heap summaries')


if __name__ == '__main__':
    main()
