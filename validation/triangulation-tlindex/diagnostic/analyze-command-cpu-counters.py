#!/usr/bin/env python3
"""Validate direct command-boundary process CPU evidence, without performance acceptance."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


FIELDS = ['phase', 'operation', 'processCpuNanos', 'readStartNanos', 'readEndNanos',
          'readStartEpochMillis', 'readEndEpochMillis', 'osName']


def analyze(rows, require_windows=False):
    rows = list(rows)
    if not rows:
        raise ValueError('missing CPU boundary evidence')
    previous = None
    systems = set()
    for row in rows:
        if set(row) != set(FIELDS):
            raise ValueError('unknown CPU boundary schema')
        for key in FIELDS[1:-1]:
            row[key] = int(row[key])
        systems.add(row['osName'])
        if row['processCpuNanos'] < 0 or row['readStartEpochMillis'] > row['readEndEpochMillis'] \
                or row['readStartNanos'] > row['readEndNanos']:
            raise ValueError('invalid CPU counter/read interval')
        if previous and (row['processCpuNanos'] < previous['processCpuNanos']
                         or row['readStartNanos'] < previous['readEndNanos']
                         or row['readStartEpochMillis'] < previous['readEndEpochMillis']):
            raise ValueError('decreasing counter/clocks or overlapping reads')
        previous = row
    if len(systems) != 1 or require_windows and not next(iter(systems)).startswith('Windows'):
        raise ValueError('inconsistent/unproven Windows JVM')
    ordered = [(row['phase'], row['operation']) for row in rows
               if row['phase'] in ('auto-connect-start', 'auto-connect-returned')]
    if ordered != [(phase, cycle) for cycle in (1, 2, 3)
                   for phase in ('auto-connect-start', 'auto-connect-returned')]:
        raise ValueError('unexpected command CPU sequence/count')
    cycles = []
    for cycle in (1, 2, 3):
        bounds = []
        for phase in ('auto-connect-start', 'auto-connect-returned'):
            values = [row for row in rows if row['operation'] == cycle and row['phase'] == phase]
            if len(values) != 1:
                raise ValueError('missing/duplicate command CPU boundary')
            bounds.append(values[0])
        start, end = bounds
        delta = end['processCpuNanos'] - start['processCpuNanos']
        if delta <= 0 or end['readStartNanos'] <= start['readEndNanos']:
            raise ValueError('nonprogressing CPU/command read boundary')
        cycles.append({'cycle': cycle, 'processCpuSeconds': delta / 1e9,
                       'startReadSpanNanos': start['readEndNanos'] - start['readStartNanos'],
                       'endReadSpanNanos': end['readEndNanos'] - end['readStartNanos'],
                       'cpuBoundaryWallSpanSeconds': (end['readStartNanos'] - start['readEndNanos']) / 1e9})
    if any(b['readStartNanos'] <= a['readEndNanos'] for a, b in
           zip([r for r in rows if r['phase'] == 'auto-connect-returned'],
               [r for r in rows if r['phase'] == 'auto-connect-start'][1:])):
        raise ValueError('overlapping command CPU spans')
    return {'status': 'PASS_DIRECT_PROCESS_CPU_EVIDENCE', 'osName': next(iter(systems)),
            'cycles': cycles, 'totalCommandProcessCpuSeconds': sum(c['processCpuSeconds'] for c in cycles),
            'maximumReadSpanNanos': max(row['readEndNanos'] - row['readStartNanos'] for row in rows),
            'limitations': ['Diagnostic evidence only; does not decide or replace original acceptance gates.',
                            'Provider counter granularity and read intervals are not exact continuous CPU duration.',
                            'Boundary CPU span includes common driver/dispatch work between reads.',
                            'Native Windows/Wine evidence and an independently declared comparison are required.']}


def check_kernel_counter_units(boundaries, batches):
    observations = []
    identities = set()
    frequencies = set()
    groups = set()
    for batch in batches:
        hosts = [r for r in batch['records'] if r['role'] == 'host-java']
        if not hosts:
            continue # Native lifecycle review separately proves terminal exit.
        if len(hosts) != 1:
            raise ValueError('ambiguous kernel process counter')
        row = hosts[0]
        identities.add((row['pid'], row['startTicks']))
        frequencies.add(batch['ticksPerSecond'])
        groups.add((batch['cgroup'], batch['cgroupInode']))
        if row['cpuReadStartEpochMillis'] > row['cpuReadEndEpochMillis']:
            raise ValueError('invalid kernel counter read range')
        if observations and (row['cpuReadStartEpochMillis'] < observations[-1]['cpuReadEndEpochMillis']
                             or row['userTicks'] < observations[-1]['userTicks']
                             or row['systemTicks'] < observations[-1]['systemTicks']):
            raise ValueError('kernel counter clocks/accounting decreased')
        observations.append(row)
    if len(identities) != 1 or len(frequencies) != 1 or len(groups) != 1:
        raise ValueError('kernel counter identity/frequency changed')
    hz = next(iter(frequencies))
    if hz <= 0:
        raise ValueError('invalid kernel counter frequency')
    comparisons = []
    for boundary in boundaries:
        if boundary['phase'] not in ('auto-connect-start', 'auto-connect-returned'):
            continue
        before = [r for r in observations if r['cpuReadEndEpochMillis'] <= int(boundary['readStartEpochMillis'])]
        after = [r for r in observations if r['cpuReadStartEpochMillis'] >= int(boundary['readEndEpochMillis'])]
        if not before or not after:
            raise ValueError('missing kernel brackets for provider unit verification')
        a, b = before[-1], after[0]
        # Two quantized fields (user/system). Account for one tick each; this
        # is unit cross-validation, not an exception to a performance threshold.
        lower = (a['userTicks'] + a['systemTicks'] - 2) / hz
        upper = (b['userTicks'] + b['systemTicks'] + 2) / hz
        value = int(boundary['processCpuNanos']) / 1e9
        if not lower <= value <= upper:
            raise ValueError('provider nanosecond units/process scope disagree with kernel counter')
        comparisons.append(dict(phase=boundary['phase'], cycle=int(boundary['operation']),
                                providerCpuSeconds=value, kernelLowerSeconds=lower, kernelUpperSeconds=upper))
    if len(comparisons) != 6:
        raise ValueError('incomplete provider unit verification')
    return {'status': 'PASS_SAME_PROCESS_NANOSECOND_UNITS', 'javaIdentity': list(next(iter(identities))),
            'ticksPerSecond': hz, 'comparisons': comparisons,
            'quantizationAllowanceSeconds': 2 / hz}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--require-windows', action='store_true')
    parser.add_argument('--samples', type=Path, help='Per-read kernel clocks for same-process unit verification')
    args = parser.parse_args()
    with args.evidence.open() as stream:
        reader = csv.DictReader(stream, delimiter='\t')
        if reader.fieldnames != FIELDS:
            raise ValueError('unknown CPU header')
        rows = list(reader)
        result = analyze(rows, args.require_windows)
    if args.require_windows and args.samples is None:
        raise ValueError('Windows feasibility requires independent kernel counter unit verification')
    if args.samples is not None:
        batches = [json.loads(line) for line in args.samples.read_text().splitlines()]
        result['kernelUnitVerification'] = check_kernel_counter_units(rows, batches)
    result['pins'] = {str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in (args.evidence, Path(__file__))}
    if args.samples is not None:
        result['pins'][str(args.samples)] = hashlib.sha256(args.samples.read_bytes()).hexdigest()
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
