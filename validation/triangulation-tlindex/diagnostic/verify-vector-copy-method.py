#!/usr/bin/env python3
"""Three-profile complete native d and alias controls for leased vector-copy elision."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
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
    jars = {profile: getattr(args, 'jar_' + profile).resolve() for profile in ('5203', '5302', '5303')}
    kotlin_pin = re.search(r'KOTLIN_SHA = "([a-f0-9]{64})"', preparation.read_text())[1]
    for profile, jar in jars.items():
        name = {'5203': 'CUBISM_5_2_03', '5302': 'CUBISM_5_3_02', '5303': 'CUBISM_5_3_03'}[profile]
        match = re.search(r'\b' + name + r'\s*=\s*new HostArtifactDigest\(([\d_]+)L,\s*"([a-f0-9]{64})"\)', authority.read_text())
        if not match or jar.stat().st_size != int(match[1].replace('_', '')) or sha(jar) != match[2]:
            raise ValueError('official artifact mismatch ' + profile)
        if sha(jar.parent / 'kotlin-stdlib-1.7.21.jar') != kotlin_pin:
            raise ValueError('Kotlin mismatch ' + profile)
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    sources = output / 'sources'
    sources.mkdir()
    classes = output / 'classes'
    classes.mkdir()
    base = Path(__file__).resolve().parent
    for name in ('AngleGuardBytecodePrototype.java', 'AngleGuardNativeSelfCheck.java', 'AngleGuardMath.java',
                 'VectorCopyBytecodePrototype.java', 'VectorCopyNativeSelfCheck.java', 'VectorCopyIdentitySelfCheck.java'):
        shutil.copy2(base / name, sources / name)
    cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
    asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
    tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
    cp = os.pathsep.join(map(str, (asm, tree)))
    compiled = ['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', cp,
                '-d', str(classes), *map(str, sorted(sources.glob('*.java')))]
    run = subprocess.run(compiled, text=True, capture_output=True, timeout=60)
    (output / 'compile.txt').write_text(run.stdout + run.stderr)
    if run.returncode:
        raise ValueError(run.stdout + run.stderr)
    argv = ['java', '-Djava.awt.headless=true', '-Xverify:all', '-cp', str(classes) + os.pathsep + cp,
            'VectorCopyNativeSelfCheck', *map(str, jars.values())]
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS'):
        env.pop(key, None)
    run = subprocess.run(argv, env=env, text=True, capture_output=True, timeout=180)
    (output / 'native.txt').write_text(run.stdout + run.stderr)
    passed = run.returncode == 0 and 'FULL_VECTOR_COPY_D_FINISHED' in run.stdout
    identity_argv = argv.copy()
    identity_argv[identity_argv.index('VectorCopyNativeSelfCheck')] = 'VectorCopyIdentitySelfCheck'
    identity = subprocess.run(identity_argv, env=env, text=True, capture_output=True, timeout=180)
    (output / 'identity.txt').write_text(identity.stdout + identity.stderr)
    passed = passed and identity.returncode == 0 and 'VECTOR_COPY_IDENTITY_FINISHED' in identity.stdout
    pins = {}
    for path in [authority, preparation, Path(__file__).resolve(), asm, tree,
                 *[base / name for name in ('AngleGuardBytecodePrototype.java', 'AngleGuardNativeSelfCheck.java', 'AngleGuardMath.java',
                      'VectorCopyBytecodePrototype.java', 'VectorCopyNativeSelfCheck.java', 'VectorCopyIdentitySelfCheck.java')],
                 *sources.glob('*.java'), *classes.rglob('*.class'), *jars.values(),
                 *[p.parent / 'kotlin-stdlib-1.7.21.jar' for p in jars.values()],
                 output / 'compile.txt', output / 'native.txt',
                 output / 'identity.txt']:
        pins[str(path)] = sha(path)
    report = {'scope': 'OWNED_COMPLETE_D_EXISTING_NULLABLE_ANGLE_LEASE_NO_PRODUCTION_OR_HOST_CLAIM',
              'passed': passed, 'exit': run.returncode, 'compile': compiled, 'argv': argv, 'identityArgv': identity_argv, 'identityExit': identity.returncode, 'pins': pins}
    (output / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
    print(run.stdout + run.stderr + identity.stdout + identity.stderr, end='')
    if not passed:
        raise ValueError('owned complete method controls failed')


if __name__ == '__main__':
    main()
