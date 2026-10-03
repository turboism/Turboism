"""Read-only identity-bound smaps snapshots. Preserve raw data; never apportion crossing mappings."""
import argparse
import base64
import csv
import gzip
import hashlib
import io
import json
from pathlib import Path
import re
import time


HEADER = re.compile(r'^([0-9a-f]+)-([0-9a-f]+) (\S+) \S+ \S+ \d+ *(.*)$')
METRICS = ('Size', 'Rss', 'Pss', 'Private_Clean', 'Private_Dirty', 'Anonymous', 'Swap', 'AnonHugePages')


def parse_smaps(text, low, high):
    if not 0 < low < high:
        raise ValueError('invalid JVM heap address range')
    maps = []; current = None
    for line in text.splitlines():
        header = HEADER.fullmatch(line)
        if header:
            start, end = int(header[1], 16), int(header[2], 16)
            if start >= end:
                raise ValueError('invalid mapping range')
            category = 'heap' if low <= start < end <= high else 'outside' if end <= low or start >= high else 'crossing'
            current = dict(start=hex(start), end=hex(end), permissions=header[3], path=header[4], category=category, bytes={})
            maps.append(current)
        elif current is not None and ':' in line:
            name, value = line.split(':', 1)
            if name in METRICS:
                parts = value.split()
                if len(parts) != 2 or parts[1] != 'kB':
                    raise ValueError('unsupported smaps units')
                current['bytes'][name] = int(parts[0]) * 1024
    if not maps or any(set(row['bytes']) != set(METRICS) for row in maps):
        raise ValueError('incomplete smaps metric coverage')
    if not any(row['category'] in ('heap', 'crossing') for row in maps):
        raise ValueError('no mapping overlaps JVM-reported heap range')
    sums = {kind: {metric: sum(row['bytes'][metric] for row in maps if row['category'] == kind) for metric in METRICS}
            for kind in ('heap', 'outside', 'crossing')}
    return dict(status='EXACT_CONTAINED_MAPPING_SUMS' if sums['crossing']['Rss'] == 0 else 'CROSSING_RESIDENT_MAPPING_UNATTRIBUTED',
                mappingCount=len(maps), groups=sums, mappings=maps)


def heap_range(path):
    raw = path.read_bytes()
    # Ignore an incomplete append. The snapshot writer persists one line at a time.
    last_newline = raw.rfind(b'\n')
    rows = list(csv.DictReader(io.StringIO(raw[:last_newline + 1].decode()), delimiter='\t'))
    if not rows:
        return None
    row = rows[-1]
    text = base64.b64decode(row['heapInfoBase64'], validate=True).decode()
    ranges = re.findall(r'\[0x([0-9a-fA-F]+), 0x([0-9a-fA-F]+)\)', text)
    if len(ranges) != 1 or 'garbage-first heap' not in text:
        raise ValueError('exact G1 range required')
    return int(ranges[0][0], 16), int(ranges[0][1], 16), row


def identity(pid):
    proc = Path('/proc') / str(pid)
    text = (proc / 'stat').read_text()
    fields = text[text.rindex(')') + 2:].split()
    group = next(line.split(':', 2)[2] for line in (proc / 'cgroup').read_text().splitlines() if line.startswith('0::'))
    return fields[19], group


def observe(pid, start, nmt, output):
    initial = identity(pid)
    if initial[0] != start or 'turboism-queue-' not in initial[1]:
        raise ValueError('owned task process identity required')
    inode = (Path('/sys/fs/cgroup') / initial[1].lstrip('/')).stat().st_ino
    last_range = None
    with output.open('x') as stream:
        while True:
            began = time.monotonic()
            try:
                if identity(pid) != initial:
                    break
                if (Path('/sys/fs/cgroup') / initial[1].lstrip('/')).stat().st_ino != inode:
                    break
                value = heap_range(nmt) if nmt.exists() else None
                if value is not None:
                    low, high, source = value
                    if last_range is not None and last_range != (low, high):
                        raise ValueError('JVM heap reservation range changed')
                    last_range = low, high
                    epoch = time.time_ns() // 1_000_000; nano = time.monotonic_ns()
                    raw = (Path('/proc') / str(pid) / 'smaps').read_bytes()
                    end_nano = time.monotonic_ns(); end_epoch = time.time_ns() // 1_000_000
                    if identity(pid) != initial:
                        break
                    parsed = parse_smaps(raw.decode(), low, high)
                    parsed.pop('mappings')  # All original headers/metrics remain in the compressed raw input.
                    result = dict(pid=pid, startTicks=start, cgroup=initial[1], cgroupInode=inode,
                                  epochStartMillis=epoch, epochEndMillis=end_epoch, nanoStart=nano, nanoEnd=end_nano,
                                  heapLow=hex(low), heapHigh=hex(high), nmtSourceSnapshot=source,
                                  rawSmapsSha256=hashlib.sha256(raw).hexdigest(),
                                  rawSmapsGzipBase64=base64.b64encode(gzip.compress(raw, mtime=0)).decode(), **parsed)
                    stream.write(json.dumps(result) + '\n'); stream.flush()
            except (FileNotFoundError, ProcessLookupError):
                break
            time.sleep(max(0, 5 - (time.monotonic() - began)))


def selfcheck():
    def mapping(start, end, rss):
        return f'{start:x}-{end:x} rw-p 00000000 00:00 0\n' + ''.join(f'{metric}: {rss if metric != "Size" else (end-start)//1024} kB\n' for metric in METRICS)
    exact = parse_smaps(mapping(1024, 2048, 1) + mapping(4096, 8192, 2), 1024, 2048)
    assert exact['groups']['heap']['Rss'] == 1024 and exact['groups']['outside']['Rss'] == 2048
    crossing = parse_smaps(mapping(1024, 4096, 2), 2048, 3072)
    assert crossing['groups']['heap']['Rss'] == 0 and crossing['groups']['crossing']['Rss'] == 2048
    assert crossing['status'] == 'CROSSING_RESIDENT_MAPPING_UNATTRIBUTED'
    try:
        parse_smaps(mapping(4096, 8192, 2), 1024, 2048)
        raise AssertionError('missing heap overlap accepted')
    except ValueError:
        pass
    try:
        parse_smaps(mapping(1024, 2048, 1).replace('Pss:', 'Missing:'), 1024, 2048)
        raise AssertionError('missing metric accepted')
    except ValueError:
        pass
    print('PASS_HEAP_PAGE_EXACT_CROSSING_MISSING_RANGE_AND_METRIC_CONTROLS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selfcheck', action='store_true')
    parser.add_argument('--pid', type=int)
    parser.add_argument('--start')
    parser.add_argument('--nmt', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.selfcheck:
        selfcheck()
    else:
        if any(value is None for value in (args.pid, args.start, args.nmt, args.output)):
            parser.error('--pid, --start, --nmt and --output required')
        observe(args.pid, args.start, args.nmt, args.output)
