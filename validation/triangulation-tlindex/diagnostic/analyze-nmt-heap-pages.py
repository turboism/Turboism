"""Audit independent NMT/heap-page observations without treating committed memory as RSS."""
import argparse
import base64
import csv
import gzip
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import statistics

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('pages', HERE / 'observe-heap-pages.py')
pages = importlib.util.module_from_spec(spec); spec.loader.exec_module(pages)
spec = importlib.util.spec_from_file_location('context', HERE / 'analyze-jvm-context.py')
context = importlib.util.module_from_spec(spec); spec.loader.exec_module(context)


def require(value, message):
    if not value:
        raise ValueError(message)


def parse_summary(text):
    total = re.findall(r'^Total: reserved=(\d+)KB, committed=(\d+)KB', text, re.MULTILINE)
    require(len(total) == 1, 'one NMT total in KB required')
    categories = re.findall(r'^-\s+(.+?)\s+\(reserved=(\d+)KB, committed=(\d+)KB\)', text, re.MULTILINE)
    require(len(categories) == len({row[0] for row in categories}) and categories, 'unique NMT categories required')
    result = {name: dict(reservedBytes=int(reserved) * 1024, committedBytes=int(committed) * 1024)
              for name, reserved, committed in categories}
    require('Java Heap' in result and 'GC' in result, 'NMT heap/GC coverage')
    result['Total'] = dict(reservedBytes=int(total[0][0]) * 1024, committedBytes=int(total[0][1]) * 1024)
    require(all(0 <= row['committedBytes'] <= row['reservedBytes'] for row in result.values()), 'NMT reserved/committed invariant')
    return result


def read_tsv(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def analyze(evidence):
    nmt_path = evidence / 'native-nmt-summary.tsv'
    context_path = evidence / 'native-jvm-context.tsv'
    pages_path = evidence / 'page-residency.jsonl'
    rows = read_tsv(nmt_path)
    groups = context.snapshots(read_tsv(context_path))
    require(len(rows) == len(groups) == 20, 'twenty NMT and context snapshots required')
    require(len({(row['phase'], int(row['operation'])) for row in rows}) == 20, 'unique NMT snapshots required')
    ranges = set(); summaries = []
    for row in rows:
        key = row['phase'], int(row['operation'])
        require(key in groups, 'NMT snapshot outside context coverage')
        capture = groups[key][0]
        require(int(capture['nanoStart']) <= int(row['nanoStart']) <= int(row['nanoEnd']) <= int(capture['nanoEnd']),
                'NMT query outside parent context capture')
        text = base64.b64decode(row['summaryBase64'], validate=True).decode()
        heap = base64.b64decode(row['heapInfoBase64'], validate=True).decode()
        addresses = re.findall(r'\[0x([0-9a-fA-F]+), 0x([0-9a-fA-F]+)\)', heap)
        require(len(addresses) == 1 and 'garbage-first heap' in heap, 'one exact G1 range required')
        low, high = (int(value, 16) for value in addresses[0]); ranges.add((low, high))
        values = parse_summary(text)
        require(values['Java Heap']['reservedBytes'] == high - low, 'G1 range/NMT reservation mismatch')
        summaries.append(dict(phase=row['phase'], operation=int(row['operation']),
                              epochStartMillis=int(row['epochStartMillis']), epochEndMillis=int(row['epochEndMillis']), categories=values))
    require(len(ranges) == 1, 'stable JVM heap reservation range required')
    samples = [json.loads(line) for line in pages_path.read_text().splitlines()]
    require(samples, 'independent page observations required')
    require(len({(s['pid'], s['startTicks'], s['cgroup'], s['cgroupInode']) for s in samples}) == 1, 'page observer identity changed')
    ready_path = evidence / 'java-process.ready.properties'
    ready_rows = [line.split('=', 1) for line in ready_path.read_text().splitlines() if '=' in line]
    require(len(ready_rows) == len({row[0] for row in ready_rows}), 'duplicate process identity properties')
    ready = dict(ready_rows)
    require(ready['javaProcessCount'] == '1' and samples[0]['pid'] == int(ready['java.0.pid'])
            and samples[0]['startTicks'] == ready['java.0.start'], 'pages differ from independently bound task Java identity')
    original_rows = {json.dumps(row, sort_keys=True) for row in rows}
    for sample in samples:
        require((int(sample['heapLow'], 16), int(sample['heapHigh'], 16)) in ranges, 'observer/JVM range mismatch')
        require(json.dumps(sample['nmtSourceSnapshot'], sort_keys=True) in original_rows, 'page range source not bound to saved NMT')
        require(sample['epochStartMillis'] <= sample['epochEndMillis'] and sample['nanoStart'] <= sample['nanoEnd'], 'invalid page capture duration')
        raw = gzip.decompress(base64.b64decode(sample['rawSmapsGzipBase64'], validate=True))
        require(hashlib.sha256(raw).hexdigest() == sample['rawSmapsSha256'], 'raw smaps pin mismatch')
        recomputed = pages.parse_smaps(raw.decode(), *next(iter(ranges)))
        require(all(sample[key] == recomputed[key] for key in ('status', 'mappingCount', 'groups')), 'page sums differ from raw smaps')
    markers = read_tsv(evidence / 'resource-windows.tsv')
    windows = []
    for operation in (0, 1, 2, 3):
        phase = 'mesh-baseline' if operation == 0 else 'mesh-retained'
        start = next(int(row['epochMillis']) for row in markers if row['phase'] == phase + '-start' and int(row['operation']) == operation)
        end = next(int(row['epochMillis']) for row in markers if row['phase'] == phase + '-end' and int(row['operation']) == operation)
        selected = [row for row in samples if start <= row['epochStartMillis'] <= row['epochEndMillis'] <= end]
        require(len(selected) >= 4, 'at least four whole captures per idle window required')
        windows.append(dict(phase=phase, operation=operation, sampleCount=len(selected),
                            firstSampleAgeMillis=selected[0]['epochStartMillis'] - start,
                            lastSampleAgeMillis=end - selected[-1]['epochEndMillis'],
                            maxSampleGapMillis=max(b['epochStartMillis'] - a['epochStartMillis'] for a, b in zip(selected, selected[1:])),
                            crossingResidentObserved=any(row['groups']['crossing']['Rss'] > 0 for row in selected),
                            groups={kind: {metric: statistics.median(row['groups'][kind][metric] for row in selected)
                                           for metric in pages.METRICS} for kind in ('heap', 'outside', 'crossing')}))
    input_paths = [nmt_path, context_path, pages_path, ready_path, evidence / 'resource-windows.tsv']
    return dict(status='PASS_BOUND_NMT_AND_RAW_PAGE_EVIDENCE_NOT_PERFORMANCE_ACCEPTANCE', nmtSnapshots=summaries,
                inputPins={str(path.resolve().relative_to(Path.cwd())): hashlib.sha256(path.read_bytes()).hexdigest() for path in input_paths},
                pageSampleCount=len(samples), javaIdentity={key: samples[0][key] for key in ('pid', 'startTicks', 'cgroupInode')},
                heapRange={'low': hex(next(iter(ranges))[0]), 'high': hex(next(iter(ranges))[1])}, windows=windows,
                heapRetained3Minus1RssBytes=windows[3]['groups']['heap']['Rss'] - windows[1]['groups']['heap']['Rss'],
                outsideRetained3Minus1RssBytes=windows[3]['groups']['outside']['Rss'] - windows[1]['groups']['outside']['Rss'],
                limitations=['Independent NMT startup and query/smaps overhead; no formal gain, gate exemption or causal algorithm proof.',
                             'Sparse non-atomic page scans; medians of separate groups need not add to median total RSS.',
                             'Mappings crossing a heap boundary remain unattributed, never proportionally assigned.',
                             'NMT committed/reserved are accounting, not resident bytes; some Wine/library allocations are untracked.'])


def selfcheck():
    text = 'Native Memory Tracking:\nTotal: reserved=100KB, committed=80KB\n- Java Heap (reserved=80KB, committed=70KB)\n- GC (reserved=20KB, committed=10KB)\n'
    assert parse_summary(text)['Java Heap']['committedBytes'] == 70 * 1024
    for invalid in (text.replace('committed=80KB', 'committed=101KB'), text + '- GC (reserved=1KB, committed=1KB)\n', text.replace('KB', 'MB')):
        try:
            parse_summary(invalid)
            raise AssertionError('invalid NMT summary accepted')
        except ValueError:
            pass
    print('PASS_NMT_UNITS_DUPLICATE_AND_RESERVED_COMMITTED_CONTROLS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selfcheck', action='store_true')
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.selfcheck:
        selfcheck()
    else:
        if args.evidence is None or args.output is None:
            parser.error('--evidence and --output required')
        result = analyze(args.evidence)
        with args.output.open('x') as stream:
            json.dump(result, stream, indent=2); stream.write('\n')
        print(result['status'])
