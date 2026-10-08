#!/usr/bin/env python3
"""Audit CPU boundary omission; does not replace or relax original acceptance gates."""
import argparse
import bisect
import csv
import hashlib
import json
from pathlib import Path


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def observations(rows):
    result = []
    identities = set()
    groups = set()
    frequencies = set()
    for row in rows:
        hosts = [record for record in row['records'] if record['role'] == 'host-java']
        if len(hosts) != 1:
            raise ValueError('missing/ambiguous host Java observation')
        host = hosts[0]
        identities.add((host['pid'], host['startTicks']))
        groups.add((row['cgroup'], row['cgroupInode']))
        frequencies.add(row['ticksPerSecond'])
        item = dict(epochMs=row['epochMs'], userTicks=host['userTicks'], systemTicks=host['systemTicks'])
        if item['userTicks'] < 0 or item['systemTicks'] < 0:
            raise ValueError('negative CPU counter')
        if result and (item['epochMs'] <= result[-1]['epochMs']
                       or item['userTicks'] < result[-1]['userTicks']
                       or item['systemTicks'] < result[-1]['systemTicks']):
            raise ValueError('unordered timestamp or decreasing CPU counter')
        result.append(item)
    if len(result) < 4 or len(identities) != 1 or len(groups) != 1 or len(frequencies) != 1:
        raise ValueError('insufficient or changing observation identity')
    hz = next(iter(frequencies))
    if hz <= 0:
        raise ValueError('invalid CPU tick frequency')
    return result, hz, next(iter(identities)), next(iter(groups))


def total(row):
    return row['userTicks'] + row['systemTicks']


def boundary(rows, stamps, epoch):
    before = bisect.bisect_right(stamps, epoch) - 1
    # A batch's CPU read precedes its epoch stamp. To prove that the CPU read
    # follows the boundary, the *previous batch's* stamp must follow it.
    after = bisect.bisect_left(stamps, epoch) + 1
    if before < 0 or after >= len(rows):
        raise ValueError('boundary lacks outer serialized batches')
    return rows[before], rows[after]


def summarize(rows, commands):
    # The final collection can race a normal process exit. Only trim terminal
    # batches with no host; internal gaps or ambiguous identities still refuse.
    rows = list(rows)
    terminal_batches = 0
    while rows and not any(record['role'] == 'host-java' for record in rows[-1]['records']):
        terminal_batches += 1
        rows.pop()
    rows, hz, identity, group = observations(rows)
    stamps = [row['epochMs'] for row in rows]
    result = []
    previous_end = None
    for command in commands:
        start, end = command['startEpochMillis'], command['endEpochMillis']
        if start >= end or previous_end is not None and start <= previous_end:
            raise ValueError('invalid/overlapping command windows')
        previous_end = end
        s0, s1 = boundary(rows, stamps, start)
        e0, e1 = boundary(rows, stamps, end)
        inside = [row for row in rows if start <= row['epochMs'] <= end]
        if len(inside) < 2:
            raise ValueError('insufficient in-window samples')
        original = total(inside[-1]) - total(inside[0])
        lower = max(0, total(e0) - total(s1))
        upper = total(e1) - total(s0)
        if not 0 <= lower <= original <= upper:
            raise ValueError('invalid CPU bracket accounting')
        result.append({**command, 'originalInsideSampleCpuSeconds': original / hz,
                       'startOmittedMillis': inside[0]['epochMs'] - start,
                       'endOmittedMillis': end - inside[-1]['epochMs'],
                       'conditionalSerializedBatchLowerCpuSeconds': lower / hz,
                       'conditionalSerializedBatchUpperCpuSeconds': upper / hz,
                       'startCounterRangeTicks': [total(s0), total(s1)],
                       'endCounterRangeTicks': [total(e0), total(e1)],
                       'outerBatchEpochs': [s0['epochMs'], s1['epochMs'], e0['epochMs'], e1['epochMs']]})
    return {'javaIdentity': list(identity), 'cgroupIdentity': list(group), 'ticksPerSecond': hz,
            'terminalNoHostBatchesExcluded': terminal_batches,
            'commands': result,
            'originalInsideCpuSeconds': sum(row['originalInsideSampleCpuSeconds'] for row in result),
            'conditionalLowerCpuSeconds': sum(row['conditionalSerializedBatchLowerCpuSeconds'] for row in result),
            'conditionalUpperCpuSeconds': sum(row['conditionalSerializedBatchUpperCpuSeconds'] for row in result),
            'omittedWallMillis': sum(row['startOmittedMillis'] + row['endOmittedMillis'] for row in result)}


def commands(path):
    with path.open() as stream:
        markers = list(csv.DictReader(stream, delimiter='\t'))
    result = []
    for cycle in (1, 2, 3):
        pair = []
        for phase in ('auto-connect-start', 'auto-connect-returned'):
            selected = [m for m in markers if m['phase'] == phase and int(m['operation']) == cycle]
            if len(selected) != 1:
                raise ValueError('missing/duplicate command markers')
            pair.append(selected[0])
        result.append({'cycle': cycle, 'startEpochMillis': int(pair[0]['epochMillis']),
                       'endEpochMillis': int(pair[1]['epochMillis']),
                       'epochMinusMonotonicDurationMillis': (int(pair[1]['epochMillis']) - int(pair[0]['epochMillis']))
                       - (int(pair[1]['monotonicNanos']) - int(pair[0]['monotonicNanos'])) / 1_000_000})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--pair', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    report = {'status': 'READ_ONLY_CPU_BOUNDARY_AUDIT', 'legs': {}, 'pins': {},
              'limitations': ['Conditional brackets assume monotonic recorded epoch time and serialized observer batches.',
                              'CPU stat reads occur between previous/current batch stamps; per-read clocks were not recorded.',
                              'Ticks are kernel accounting units, not exact continuous CPU duration or a confidence interval.',
                              'No proportional interpolation or assumption of uniform boundary CPU usage.',
                              'These intervals do not replace the original point-estimate acceptance gate.',
                              'A single diagnostic pair cannot establish stable or causal performance changes.']}
    for leg in ('baseline', 'candidate'):
        evidence = args.pair / leg / 'native-evidence'
        markers, samples = evidence / 'resource-windows.tsv', evidence / 'resources.jsonl'
        observer = args.pair / leg / 'observe-resources.py'
        # Require the exact reviewed serialization convention; unknown collectors refuse.
        if sha(observer) != '355abd49fb6b21d2559e6bb259dd139cf53f0ab30c2921720acb03f06d62ea21':
            raise ValueError('unreviewed collector serialization')
        rows = [json.loads(line) for line in samples.read_text().splitlines()]
        report['legs'][leg] = summarize(rows, commands(markers))
        report['pins'].update({str(p): sha(p) for p in (markers, samples, observer)})
    a, b = report['legs']['baseline'], report['legs']['candidate']
    report['candidateMinusBaselineConditionalCpuSeconds'] = [
        b['conditionalLowerCpuSeconds'] - a['conditionalUpperCpuSeconds'],
        b['conditionalUpperCpuSeconds'] - a['conditionalLowerCpuSeconds']]
    review_path = args.pair / 'pair-review.json'
    original = json.loads(review_path.read_text())
    for leg in ('baseline', 'candidate'):
        if abs(report['legs'][leg]['originalInsideCpuSeconds']
               - original['summary'][leg]['sampledCpuSeconds']) > 1e-8:
            raise ValueError('original accepted analysis CPU estimate mismatch')
    report['originalResourceGatesUnchanged'] = original['resourceGates']
    report['pins'].update({str(review_path): sha(review_path), str(Path(__file__)): sha(Path(__file__))})
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(json.dumps({leg: {key: value for key, value in data.items() if key != 'commands'}
                      for leg, data in report['legs'].items()}, indent=2))
    print('Conditional difference seconds:', report['candidateMinusBaselineConditionalCpuSeconds'])


if __name__ == '__main__':
    main()
