"""Match nominal-window JFR GC summaries to the same process's console GC clock."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('work', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    source = Path(__file__).with_name('analyze-gc-heap-log.py')
    spec = importlib.util.spec_from_file_location('gc_log', source)
    gc_log = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(gc_log)
    result = {'status': 'JFR_NATIVE_WINDOW_CLOCK_ALIGNMENT_UNPROVEN', 'legs': {},
              'inputPins': {str(source.relative_to(root)): sha(source), str(Path(__file__)): sha(Path(__file__))},
              'performanceAcceptance': 'NOT_GRANTED',
              'limitations': ['Same gcId and before/after phase match event observations, not exact emission instants.',
                              'Unmatched JFR or console summaries are not invented or corrected.',
                              'No constant offset correction or retrospective command-window reclassification.',
                              'Original nominal ownership reports and failed production gates remain unchanged.']}
    for name in ('baseline', 'candidate'):
        out = args.work / name
        native = json.loads((out / 'native-review.json').read_text())
        for saved in native['savedEvidence']:
            if sha(root / saved['path']) != saved['sha256']:
                raise ValueError('saved native evidence changed')
        ownership = json.loads((out / 'ownership-review.json').read_text())
        for path, pin in ownership['inputPins'].items():
            if sha(Path(path)) != pin:
                raise ValueError('ownership analysis input changed')
        console_path = out / 'native-evidence/console.txt'
        logs = gc_log.analyze(console_path)
        lookup = {}
        for entry in logs['heapSummaries']:
            key = (entry['gcId'], entry['phase'])
            if key in lookup:
                raise ValueError('ambiguous console GC identity/phase')
            lookup[key] = entry
        cycles = []
        for cycle in ownership['cycles']:
            matched, unmatched = [], []
            for heap in cycle['heapSummaries']:
                phase = {'Before GC': 'before', 'After GC': 'after'}[heap['when']]
                log = lookup.get((heap['gcId'], phase))
                if log is None:
                    unmatched.append({'gcId': heap['gcId'], 'phase': phase})
                    continue
                matched.append({'gcId': heap['gcId'], 'phase': phase,
                                'jfrEpochMillis': heap['epochMillis'], 'gcLogEpochMillis': log['epochMillis'],
                                'jfrMinusGcLogMillis': heap['epochMillis'] - log['epochMillis']})
            cycles.append({'cycle': cycle['cycle'], 'matched': matched, 'unmatched': unmatched})
        if not any(c['matched'] for c in cycles):
            raise ValueError('no independent same-process GC clock comparisons')
        result['legs'][name] = cycles
        for path in (out / 'native-review.json', out / 'ownership-review.json', console_path):
            result['inputPins'][str(path)] = sha(path)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['status'])


if __name__ == '__main__':
    main()
