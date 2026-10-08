"""Real-SDK source-cache copy controls, with shared performance-queue guard."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil

SOURCE = Path(__file__).resolve().parent.parent
spec = importlib.util.spec_from_file_location('guard', SOURCE / 'verify-native-mesh-edge-loop.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    agents = {
        'candidate': (Path('build/t085-native-shared-integration-r1/sole-premain-r2/turboism-agent.jar').resolve(),
                      'eb6875699d3fdb92e3340a0a0af106fcf30f2985d490389f81fe3a0823c209f8'),
        'growth': (Path('build/t093-native-table-growth-r1/sole-premain-r1/turboism-agent.jar').resolve(),
                   'fa9e417c52cfc8463bfdf7c505da93bfbb70930e9cbc9edf397d0a3aba234e1d')}
    report = {'status': 'STARTED', 'groups': [], 'pins': {}, 'editorLaunched': False,
              'actionContextReproduced': False, 'performanceAcceptance': 'NOT_GRANTED'}
    try:
        for path, pin in agents.values():
            if sha(path) != pin: raise ValueError('agent pin mismatch: ' + str(path))
        source = SOURCE / 'NativeMeshCacheCopySelfCheck.java'
        inputs = [Path(__file__).resolve(), SOURCE / 'verify-native-mesh-edge-loop.py', source,
                  *[p for p, _ in agents.values()]]
        report['pins'].update({str(p): sha(p) for p in inputs})
        env = dict(os.environ)
        for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
            env.pop(key, None)
        classes = out / 'classes'; classes.mkdir()
        guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-d', str(classes), str(source)],
                      out / 'compile.log', env)
        for profile in ('5203', '5302', '5303'):
            template = Path('build/t053-local-builder-r1/metadata-production') / ('on' + profile)
            argv = json.loads((template / 'command.json').read_text())['argv']
            cp = argv[argv.index('-cp') + 1].split(os.pathsep)[1:]
            official = next(Path(p) for p in cp if p.endswith('/Live2D_Cubism.jar'))
            if sha(official) != guard.PINS[profile]: raise ValueError('SDK pin mismatch')
            report['pins'][str(official)] = sha(official)
            for assertions in (False, True):
                records = []
                for leg in ('original', 'candidate', 'growth'):
                    case = out / (profile + ('-ea-' if assertions else '-da-') + leg); case.mkdir()
                    record = case / 'records.txt'
                    command = ['java', '-ea' if assertions else '-da', '-Djava.awt.headless=true',
                               '-Xverify:all', '-XX:+DisableAttachMechanism']
                    if leg != 'original':
                        home = case / 'home'; home.mkdir()
                        shutil.copyfile(agents[leg][0], home / 'turboism-agent.jar')
                        shutil.copyfile(template / 'home/config.json', home / 'config.json')
                        command += ['-javaagent:' + str(home / 'turboism-agent.jar') + '=home=' + str(home)
                                    + ';hostClass=owned.NoCubismApplication;timeoutSeconds=30']
                    command += ['-cp', os.pathsep.join([str(classes), *cp]), 'NativeMeshCacheCopySelfCheck', str(record),
                                'original' if leg == 'original' else 'agent']
                    (case / 'command.json').write_text(json.dumps({'argv': command}, indent=2) + '\n')
                    guard.guarded(command, case / 'console.log', env)
                    if 'NATIVE_CACHE_COPY_PASS fixtures=12 ' not in (case / 'console.log').read_text():
                        raise ValueError('missing copy controls')
                    if len(record.read_text().splitlines()) != 12: raise ValueError('missing records')
                    records.append(record)
                if any(p.read_bytes() != records[0].read_bytes() for p in records[1:]):
                    raise ValueError('native copy/source state differs: ' + profile)
                report['groups'].append({'profile': profile, 'assertions': assertions, 'fixtures': 12,
                                         'legs': 3, 'status': 'BYTE_IDENTICAL', 'recordsSha256': sha(records[0])})
                print(profile, assertions, '12 native source/copy records BYTE_IDENTICAL', flush=True)
        if any(sha(Path(p)) != pin for p, pin in report['pins'].items()): raise ValueError('input changed')
        report['status'] = 'PASS_NATIVE_COPY_SEAM_ONLY'
    except Exception as failure:
        report.update(status='FAILED_OR_GUARDED_STOP', failure=str(failure)); raise
    finally:
        report['pins'].update({str(p): sha(p) for p in out.rglob('*') if p.is_file() and p.name != 'review.json'})
        (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
        print(report['status'], flush=True)


if __name__ == '__main__':
    main()
