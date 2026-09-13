#!/usr/bin/env python3
"""Verify complete task evidence, export every sample, and compute stratified statistics.
Usage: python3 summarize-host-ab.py TASK_DIR [TASK_DIR ...]
Writes host-ab-samples.csv and host-ab-summary.json beside this script.
"""
import csv
import hashlib
import json
import math
from pathlib import Path
import statistics
import sys

all_rows = []
cases = []
for argument in sys.argv[1:]:
    task = Path(argument).resolve()
    evidence = task / 'evidence'
    assert (evidence / 'status.txt').read_text().startswith('PASS ')
    assert (evidence / 'cleanup.txt').is_file()
    samples = json.loads((evidence / 'samples.json').read_text())
    assert len(samples) == 40
    expected_input = samples[0]['inputHash']
    seen = {'native': 0, 'new': 0}
    rows = []
    identity = None
    for index, sample in enumerate(samples):
        raw = evidence / f'timing-{index+1:03d}.json'
        result = json.loads(raw.read_text())
        impl = sample['implementation']
        assert result['branch'] == ('native' if impl == 'native' else 'handled')
        assert result['returned'] and result['inputHash'] == expected_input
        source, output = result['input'], result['output']
        assert output['finite'] and output['inside'] and output['layerMatch'] and output['overlaps'] == 0
        assert not output['overflow'] and output['dataScale'] == 1.0
        assert source['modelImage'] is False and source['rotate'] is True and source['requestedScale'] == 0.0
        identity = {k: v for k, v in source.items() if k not in ('items', 'overflow')}
        seen[impl] += 1
        row = {
            'count': source['count'], 'width': source['width'], 'height': source['height'],
            'implementation': impl, 'sequence': index+1, 'implementation_call': seen[impl],
            'warmup': seen[impl] <= 4,
            'transition': 'first' if index == 0 else 'repeat' if samples[index-1]['implementation'] == impl else 'switch',
            'method_ms': result['methodMs'], 'input_hash': result['inputHash'], 'output_hash': result['outputHash'],
            'placed': source['count'], 'overflow': 0, 'scale': output['dataScale'],
            'branch': result['branch'], 'geometry_ok': True,
            'task': task.name, 'raw_sha256': hashlib.sha256(raw.read_bytes()).hexdigest(),
        }
        rows.append(row)
    assert seen == {'native': 20, 'new': 20}
    for impl in seen:
        assert len({row['output_hash'] for row in rows if row['implementation'] == impl}) == 1
    groups = {}
    for group in ('balanced', 'repeat', 'switch'):
        values = {impl: [r['method_ms'] for r in rows if r['implementation'] == impl and not r['warmup']
            and (group == 'balanced' or r['transition'] == group)] for impl in seen}
        details = {impl: {'n': len(v), 'median_ms': statistics.median(v),
            'p95_nearest_rank_ms': sorted(v)[math.ceil(.95 * len(v))-1]} for impl, v in values.items()}
        details['native_over_new_median_ratio'] = statistics.median(values['native']) / statistics.median(values['new'])
        groups[group] = details
    cases.append({'task': str(task), 'input': identity, 'input_hash': expected_input, 'groups': groups})
    all_rows.extend(rows)
assert cases, 'Supply completed task directories'
destination = Path(__file__).resolve().parent
with (destination / 'host-ab-samples.csv').open('w', newline='') as stream:
    writer = csv.DictWriter(stream, fieldnames=list(all_rows[0]))
    writer.writeheader()
    writer.writerows(all_rows)
(destination / 'host-ab-summary.json').write_text(json.dumps(cases, ensure_ascii=False, indent=2) + '\n')
print('Verified and exported', len(all_rows), 'samples across', len(cases), 'cases')
