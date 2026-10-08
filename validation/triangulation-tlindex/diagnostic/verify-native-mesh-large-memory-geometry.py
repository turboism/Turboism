"""Compare actual system-loader native mesh operations with canonical sole premain versus pristine SDK."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess

SOURCE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('guard', SOURCE / 'verify-native-mesh-edge-loop.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate-review', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--diagnostic-overlay', action='store_true',
                        help='explicit diagnostic-only overlay; never production/performance acceptance')
    args = parser.parse_args()
    admission = json.loads(args.candidate_review.read_text())
    expected_status = 'BUILT_DIAGNOSTIC_ONLY' if args.diagnostic_overlay else 'PASS'
    if admission['status'] != expected_status or not admission['nativeMeshAdmissionIntegrated']:
        raise ValueError('successful actual native admission review required')
    if args.diagnostic_overlay and admission.get('performanceAcceptance') != 'NOT_APPLICABLE_DIAGNOSTIC':
        raise ValueError('diagnostic overlay must refuse performance acceptance')
    candidate = args.candidate_review.resolve().with_name('turboism-agent.jar')
    if sha(candidate) != admission['candidateSha256']:
        raise ValueError('candidate identity mismatch')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'status': 'STARTED', 'scope': 'ACTUAL_SOLE_PREMAIN_SYSTEM_LOADER_NATIVE_GEOMETRY',
              'candidateSha256': sha(candidate), 'editorLaunched': False, 'hostGain': 'UNPROVEN', 'groups': [],
              'pins': {str(candidate): sha(candidate), str(args.candidate_review.resolve()): sha(args.candidate_review)}}
    report['diagnosticOverlay'] = args.diagnostic_overlay
    report['performanceAcceptance'] = 'NOT_GRANTED'
    try:
        sources = [SOURCE / (name + '.java') for name in ('NativeMeshMemoryLargeProductionExecutionSelfCheck',
                   'NativeMeshMemoryLargeAutoConnectSelfCheck', 'NativeMeshEdgeLoopSelfCheck', 'NativeMeshEdgeLoopPrototype',
                   'NativeMeshEdgeTableOwnedAccess')]
        cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
        asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
        tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
        for path in [Path(__file__).resolve(), SOURCE / 'verify-native-mesh-edge-loop.py', *sources, asm, tree]:
            report['pins'][str(path)] = sha(path)
        env = dict(os.environ)
        for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
            env.pop(key, None)
        classes = out / 'classes'; classes.mkdir()
        guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp',
                       os.pathsep.join(map(str, [candidate, asm, tree])), '-d', str(classes), *map(str, sources)],
                      out / 'compile.log', env)
        for profile in ('5203', '5302', '5303'):
            template = Path('build/t053-local-builder-r1/metadata-production') / ('on' + profile)
            old = json.loads((template / 'command.json').read_text())['argv']
            host_cp = old[old.index('-cp') + 1].split(os.pathsep)[1:]
            official = next(Path(p) for p in host_cp if p.endswith('/Live2D_Cubism.jar'))
            if sha(official) != guard.PINS[profile]:
                raise ValueError('official archive identity mismatch')
            report['pins'][str(official)] = sha(official)
            for assertions in (False, True):
                records = []
                for leg in ('original', 'agent'):
                    case = out / (profile + ('-ea-' if assertions else '-da-') + leg)
                    home = case / 'home'; home.mkdir(parents=True)
                    shutil.copyfile(candidate, home / 'turboism-agent.jar')
                    shutil.copyfile(template / 'home/config.json', home / 'config.json')
                    record = case / 'records.txt'
                    argv = ['java', '-ea' if assertions else '-da', '-Djava.awt.headless=true',
                            '-Xverify:all', '-XX:+DisableAttachMechanism']
                    if leg == 'agent':
                        argv += ['-javaagent:' + str(home / 'turboism-agent.jar') + '=home=' + str(home)
                                 + ';hostClass=owned.NoCubismApplication;timeoutSeconds=30']
                        if args.diagnostic_overlay:
                            argv += ['-XX:StartFlightRecording=filename=' + str(case / 'native-scope.jfr')
                                     + ',dumponexit=true,maxsize=32m']
                    argv += ['-cp', os.pathsep.join([str(classes), str(candidate), str(asm), str(tree), *host_cp]),
                             'NativeMeshMemoryLargeProductionExecutionSelfCheck', str(official), str(record), leg]
                    (case / 'command.json').write_text(json.dumps({'argv': argv}, indent=2) + '\n')
                    guard.guarded(argv, case / 'console.log', env)
                    console = (case / 'console.log').read_text()
                    if 'NATIVE_MESH_PRODUCTION_EXECUTION_PASS ' not in console or len(record.read_text().splitlines()) != 48:
                        raise ValueError('missing actual full-operation record')
                    records.append(record)
                    if leg == 'agent' and args.diagnostic_overlay:
                        result = subprocess.run(['jfr', 'print', '--json', '--events',
                                                 'dev.turboism.validation.NativeMeshMemoryScope',
                                                 str(case / 'native-scope.jfr')],
                                                capture_output=True, text=True, check=True)
                        (case / 'scope-events.json').write_text(result.stdout)
                        events = json.loads(result.stdout)['recording']['events']
                        if not events or sum(e['values']['tableCalls'] for e in events) <= 0:
                            raise ValueError('diagnostic scope events missing actual table queries')
                        for event in events:
                            value = event['values']
                            capacity = 16
                            while capacity < 2 * value['entryLimit']: capacity *= 2
                            if (not 0 <= value['initialEntries'] <= value['entryLimit'] <= 16384
                                    or value['bufferCapacity'] != capacity
                                    or value['declaredBufferBytes'] != 12 * capacity + 1024
                                    or value['helperAndSuffixAllocatedBytes'] < 12 * capacity):
                                raise ValueError('memory diagnostic capacity/allocation invariant')
                            if (not value['released'] or value['unknown'] != 0
                                    or value['tableCalls'] != value['hits'] + value['absent']
                                    or any(value[k] < 0 for k in ('tableCalls', 'hits', 'absent', 'appended'))):
                                raise ValueError('diagnostic scope counters/release invariant')
                if records[0].read_bytes() != records[1].read_bytes():
                    raise ValueError('actual native geometry differs: ' + profile + ' assertions=' + str(assertions))
                report['groups'].append({'profile': profile, 'assertions': assertions, 'fixtures': 48,
                                         'recordsSha256': sha(records[0]), 'status': 'BYTE_IDENTICAL'})
                print(profile, 'assertions=' + str(assertions), '48 records BYTE_IDENTICAL', flush=True)
        report['status'] = 'PASS'
    except Exception as failure:
        report['status'] = 'FAILED_OR_GUARDED_STOP'; report['failure'] = str(failure)
        raise
    finally:
        for path in out.rglob('*'):
            if path.is_file() and path.name != 'review.json':
                report['pins'][str(path)] = sha(path)
        (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
        print(report['status'], 'groups=' + str(len(report['groups'])), flush=True)


if __name__ == '__main__':
    main()
