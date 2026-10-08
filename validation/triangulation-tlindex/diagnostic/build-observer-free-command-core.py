"""Compile observer-free command helpers and own fixtures; no plugin or host admission."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    diag = Path(__file__).resolve().parent
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'
    checks = out / 'check-classes'
    classes.mkdir()
    checks.mkdir()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    names = ['NativeObserverFreeAutoConnect.java', 'NativeAutoConnect.java', 'NativeCancelPrompt.java',
             'MeshResultSnapshot.java', 'CommandCpuBoundary.java', 'ObserverFreeEvidenceWriter.java']
    sources = [diag / name for name in names]
    check_names = ['NativeObserverFreeAutoConnectSelfCheck.java', 'CommandCpuBoundarySelfCheck.java',
                   'MeshResultSnapshotSelfCheck.java', 'ObserverFreeEvidenceWriterSelfCheck.java']
    pins = {str(p.relative_to(root)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sources + [diag / name for name in check_names] + [Path(__file__).resolve()]}
    compile_options = ['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror']
    subprocess.run(compile_options + ['-d', str(classes)] + [str(p) for p in sources],
                   env=env, check=True)
    subprocess.run(compile_options + ['-cp', str(classes), '-d', str(checks)]
                   + [str(diag / name) for name in check_names], env=env, check=True)
    for name in check_names:
        command = ['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                   str(classes) + os.pathsep + str(checks), name.removesuffix('.java')]
        if name == 'ObserverFreeEvidenceWriterSelfCheck.java':
            command.append(str(out / 'writer-run'))
        run = subprocess.run(command,
                             env=env, text=True, capture_output=True)
        (out / (name.removesuffix('.java') + '.log')).write_text(run.stdout + run.stderr)
        run.check_returncode()
        print(run.stdout, end='')
    forbidden = [b'java/lang/instrument/', b'MeshProducerRecorder', b'MeshProducerWeave',
                 b'NativeProducerAutoConnect', b'org/objectweb/asm', b'premain', b'agentmain']
    compiled = {}
    for path in sorted(classes.glob('*.class')):
        raw = path.read_bytes()
        if any(token in raw for token in forbidden):
            raise ValueError('observer/transform dependency in ' + path.name)
        compiled[path.name] = hashlib.sha256(raw).hexdigest()
    jar = out / 'observer-free-command-core.jar'
    subprocess.run(['jar', '--create', '--file', str(jar), '-C', str(classes), '.'], env=env, check=True)
    report = {'status': 'PASS_OFFLINE_CORE_NOT_PLUGIN_OR_HOST_ADMISSION', 'inputs': pins,
              'compiledClasses': compiled, 'forbiddenDependencyScan': 'PASS_BOUNDED_BYTE_TOKEN_CHECK',
              'selfChecks': check_names, 'coreJarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'hostPreparedOrSubmitted': False,
              'limitations': ['Own fixtures do not execute host/native option/loop/context paths.',
                              'No plugin entrypoint, loader validation or all-version admission yet.',
                              'Dependency token scan is bounded; complete package audit remains required.',
                              'No observer-free production comparison or performance conclusion.']}
    (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'])


if __name__ == '__main__':
    main()
