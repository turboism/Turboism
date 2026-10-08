"""Verify the scoped producer recorder and exact official byte-data weave; never launch Cubism."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--base-agent', type=Path, required=True)
    for version in ('5203', '5302', '5303'):
        parser.add_argument('--jar-' + version, type=Path, required=True)
    args = parser.parse_args()
    base = args.base_agent.resolve()
    if sha(base) != 'b47f6f47928f46d7fc2acd94223d66e89c80c903a4bd8d2878d5f6cc92e425cb':
        raise ValueError('unreviewed base dependency')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    classes = out / 'classes'
    classes.mkdir()
    diag = Path(__file__).resolve().parent
    sources = [diag / name for name in (
        'MeshResultSnapshot.java', 'MeshResultSnapshotSelfCheck.java',
        'MeshProducerRecorder.java', 'MeshProducerRecorderSelfCheck.java',
        'MeshProducerWeave.java', 'MeshProducerWeaveSelfCheck.java')]
    inputs = {str(p): sha(p) for p in [*sources, Path(__file__).resolve(), base]}
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    commands = []

    def run(name, argv):
        result = subprocess.run(argv, env=env, text=True, capture_output=True)
        (out / (name + '.log')).write_text(result.stdout + result.stderr)
        commands.append({'name': name, 'argv': argv, 'exitCode': result.returncode})
        (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        result.check_returncode()
        if result.stdout:
            print(result.stdout, end='')

    run('compile', ['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                    '-cp', str(base), '-d', str(classes), *map(str, sources)])
    java = ['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(classes) + os.pathsep + str(base)]
    run('snapshot-selfcheck', [*java, 'MeshResultSnapshotSelfCheck'])
    run('recorder-selfcheck', [*java, 'MeshProducerRecorderSelfCheck'])
    weave = [*java, 'MeshProducerWeaveSelfCheck']
    for version in ('5203', '5302', '5303'):
        jar = getattr(args, 'jar_' + version).resolve()
        inputs[str(jar)] = sha(jar)
        weave.extend([version, str(jar), str(out / (version + '-producer-woven.class'))])
    run('weave-selfcheck', weave)
    artifacts = {str(p): sha(p) for p in sorted(out.rglob('*')) if p.is_file()}
    report = {'status': 'PRODUCER_RECORDER_OFFLINE_PASS', 'hostValidated': False,
              'productionAcceptance': 'NOT_PASSED',
              'limitations': ['No generated scene driver or native command execution in this offline slice.',
                              'Official classes remain byte data; executable cases use owned synthetic producers.',
                              'Complete final three-version resource/UI/managed-startup acceptance remains open.'],
              'inputs': inputs, 'artifacts': artifacts}
    for file, digest in {**inputs, **artifacts}.items():
        if sha(Path(file)) != digest:
            raise ValueError('input changed before report: ' + file)
    (out / 'offline-review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(out / 'offline-review.json')


if __name__ == '__main__':
    main()
