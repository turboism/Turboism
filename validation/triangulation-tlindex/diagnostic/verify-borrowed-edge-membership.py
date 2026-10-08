#!/usr/bin/env python3
"""T058 owned native predicate experiment; no Editor or production transformation."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for profile in ('5203', '5302', '5303'):
        parser.add_argument('--jar-' + profile, required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    authority = root / 'runtime/src/main/java/dev/turboism/mapping/verification/ReviewedHostArtifacts.java'
    preparation = root / 'runtime/src/main/java/dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgePreparation.java'
    source = Path(__file__).resolve().with_name('BorrowedEdgeMembershipSelfCheck.java')
    jars = {profile: getattr(args, 'jar_' + profile).resolve() for profile in ('5203', '5302', '5303')}
    kotlin_pin = re.search(r'KOTLIN_SHA = "([a-f0-9]{64})"', preparation.read_text())[1]
    for profile, jar in jars.items():
        name = {'5203': 'CUBISM_5_2_03', '5302': 'CUBISM_5_3_02', '5303': 'CUBISM_5_3_03'}[profile]
        match = re.search(r'\b' + name + r'\s*=\s*new HostArtifactDigest\(([\d_]+)L,\s*"([a-f0-9]{64})"\)', authority.read_text())
        if not match or jar.stat().st_size != int(match[1].replace('_', '')) or sha(jar) != match[2]:
            raise ValueError('official artifact mismatch ' + profile)
        if sha(jar.parent / 'kotlin-stdlib-1.7.21.jar') != kotlin_pin:
            raise ValueError('Kotlin artifact mismatch ' + profile)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    classes = output / 'classes'
    classes.mkdir()
    saved_source = output / source.name
    saved_source.write_bytes(source.read_bytes())

    def classpath(jar):
        return os.pathsep.join(map(str, [jar] + [p for p in sorted(jar.parent.glob('*.jar')) if p != jar]))

    command = ['javac', '--release', '17', '-Xlint:all', '-cp', classpath(jars['5203']),
               '-d', str(classes), str(saved_source)]
    compiled = subprocess.run(command, text=True, capture_output=True, timeout=60)
    (output / 'compile.txt').write_text(compiled.stdout + compiled.stderr)
    # Reviewed legacy external class annotations contain this unresolved enum constant.
    # Record rather than claim a Werror pass; reject every other warning/error.
    expected_warning = 'warning: unknown enum constant h.AUTO\n1 warning\n'
    if compiled.returncode or compiled.stdout or compiled.stderr not in ('', expected_warning):
        raise ValueError('unexpected compilation result: ' + compiled.stdout + compiled.stderr)
    report = {'scope': 'OWNED_PREDICATE_ONLY_NO_HOST_METHOD_PATCH', 'compile': command,
              'externalAnnotationWarning': compiled.stderr, 'records': [], 'pins': {}}
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    for profile, jar in jars.items():
        argv = ['java', '-Djava.awt.headless=true', '-Xverify:all', '-cp', str(classes) + os.pathsep
                + classpath(jar), 'BorrowedEdgeMembershipSelfCheck', profile]
        run = subprocess.run(argv, env=env, text=True, capture_output=True, timeout=120)
        log = output / (profile + '.txt')
        log.write_text(run.stdout + run.stderr)
        passed = run.returncode == 0 and 'BORROWED_EDGE_MEMBERSHIP_PASS profile=' + profile in run.stdout
        report['records'].append({'profile': profile, 'argv': argv, 'passed': passed, 'exit': run.returncode,
                                  'fullHostMethodCandidateApplicable': profile != '5203'})
        report['pins'][str(jar)] = sha(jar)
        report['pins'][str(jar.parent / 'kotlin-stdlib-1.7.21.jar')] = kotlin_pin
        (output / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
        print(profile, 'PASS' if passed else 'FAIL', flush=True)
        if not passed:
            raise ValueError(run.stdout + run.stderr)
    for path in [authority, preparation, source, saved_source, Path(__file__).resolve(),
                 output / 'compile.txt', *classes.rglob('*.class'), *output.glob('5*.txt')]:
        report['pins'][str(path)] = sha(path)
    (output / 'review.json').write_text(json.dumps(report, indent=2) + '\n')


if __name__ == '__main__':
    main()
