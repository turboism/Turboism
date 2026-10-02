#!/usr/bin/env python3
"""Real frozen sole-premain mutation controls and composed owned-fixture equality; no Editor."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--candidate', type=Path, required=True)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    root = Path.cwd()
    candidate, baseline = args.candidate.resolve(), args.baseline.resolve()
    expected = {candidate: 'e91db380b40db8dbc3608c6cc387c1669955aad382563e688dc643e8ce55dc7b',
                baseline: '17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295'}
    for artifact, pin in expected.items():
        if sha(artifact) != pin:
            raise ValueError('frozen input mismatch ' + str(artifact))
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'
    classes.mkdir()
    source = Path(__file__).resolve().parent
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
    tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
    sources = [source / (name + '.java') for name in (
        'BorrowedEdgePremainSelfCheck', 'BorrowedEdgePremainExecutionSelfCheck',
        'BorrowedEdgeNativeSelfCheck', 'BorrowedEdgeBytecodePrototype')]
    compile_argv = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp',
                    os.pathsep.join(map(str, [candidate, asm, tree])), '-d', str(classes), *map(str, sources)]
    compiled = subprocess.run(compile_argv, capture_output=True, text=True, timeout=60)
    (out / 'compile.txt').write_text(compiled.stdout + compiled.stderr)
    if compiled.returncode:
        raise ValueError(compiled.stdout + compiled.stderr)
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    cases = []
    pins = {str(path): sha(path) for path in [candidate, baseline, asm, tree, Path(__file__).resolve(), *sources]}
    def execute(profile, control, artifact, main_class, extra, assertions=False):
        case = out / (profile + '-' + control)
        case.mkdir()
        home = case / 'home'
        home.mkdir()
        agent = home / 'turboism-agent.jar'
        shutil.copy2(artifact, agent)
        template = root / 'build/t053-local-builder-r1/metadata-production' / ('on' + profile)
        shutil.copy2(template / 'home/config.json', home / 'config.json')
        old = json.loads((template / 'command.json').read_text())['argv']
        host_cp = old[old.index('-cp') + 1].split(os.pathsep)[1:]
        host = Path(next(path for path in host_cp if Path(path).name == 'Live2D_Cubism.jar'))
        argv = ['java', '-Djava.awt.headless=true', '-Xverify:all']
        if assertions:
            argv.append('-ea')
        if control != 'unsupported':
            argv.append('-XX:+DisableAttachMechanism')
        argv += ['-javaagent:' + str(agent) + '=home=' + str(home)
                 + ';hostClass=owned.NoCubismApplication;timeoutSeconds=30',
                 '-cp', os.pathsep.join(map(str, [classes, asm, tree, *host_cp])), main_class]
        if main_class == 'BorrowedEdgePremainExecutionSelfCheck':
            argv += [str(host), str(case / 'records.txt')]
        else:
            argv += extra
        (case / 'command.json').write_text(json.dumps({'argv': argv}, indent=2) + '\n')
        run = subprocess.run(argv, env=env, capture_output=True, text=True, timeout=90)
        console = run.stdout + run.stderr
        (case / 'console.txt').write_text(console)
        marker = 'BORROWED_EDGE_PREMAIN_' + ('EXECUTION_PASS' if main_class.endswith('ExecutionSelfCheck') else 'CONTROL_PASS')
        passed = run.returncode == 0 and marker in console
        if control == 'accepted':
            borrowed = 'TRIANGULATION_LAZY_EDGE_BORROWED_PATCHED' in console
            passed = passed and borrowed == (profile != '5203')
        result = {'profile': profile, 'control': control, 'exit': run.returncode, 'passed': passed,
                  'artifactSha256': sha(artifact), 'consoleSha256': sha(case / 'console.txt')}
        cases.append(result)
        (out / 'review.json').write_text(json.dumps({'cases': cases, 'pins': pins}, indent=2) + '\n')
        print(profile, control, 'PASS' if passed else 'FAIL', flush=True)
        if not passed:
            raise ValueError('premain control failed: ' + console)
        return case
    controls = ['accepted', 'revocation', 'unsupported', 'list-getter', 'list-field', 'membership',
                'endpoint-getter', 'endpoint-field', 'coordinate', 'index-getter', 'index-field']
    for profile in ('5203', '5302', '5303'):
        for control in [c for c in controls if profile != '5203' or c != 'coordinate'] + ([] if profile == '5203' else ['operation']):
            execute(profile, control, candidate, 'BorrowedEdgePremainSelfCheck', [control])
    for profile in ('5302', '5303'):
        for assertions in (False, True):
            pair = [execute(profile, side + '-execution-' + str(assertions), artifact,
                            'BorrowedEdgePremainExecutionSelfCheck', [], assertions)
                    for side, artifact in [('baseline', baseline), ('candidate', candidate)]]
            if (pair[0] / 'records.txt').read_bytes() != (pair[1] / 'records.txt').read_bytes():
                raise ValueError('composed fixture equality failed ' + profile)
    for path in out.rglob('*'):
        if path.is_file() and path.name not in ('review.json', 'turboism-agent.jar'):
            pins[str(path)] = sha(path)
    (out / 'review.json').write_text(json.dumps({'scope': 'ACTUAL_SOLE_PREMAIN_NO_EDITOR',
        'passed': True, 'cases': cases, 'pins': pins}, indent=2) + '\n')
    print('BORROWED_PREMAIN_FINISHED cases=' + str(len(cases)), flush=True)


if __name__ == '__main__':
    main()
