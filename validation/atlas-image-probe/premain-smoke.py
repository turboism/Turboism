#!/usr/bin/env python3
"""Ordinary JDK smoke: official JAR is hashed/read, never put on a classpath."""
import argparse
import hashlib
import os
from pathlib import Path
import subprocess


def require(condition, message):
    if not condition:
        raise RuntimeError(message)

def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def escape(value):
    # Properties.load(InputStream) uses ISO-8859-1 and Java Unicode escapes.
    result = ''
    for char in str(value):
        if char in '\\:=#! ':
            result += '\\' + char
        elif char == '\n':
            result += '\\n'
        elif char == '\r':
            result += '\\r'
        elif ord(char) > 126 or ord(char) < 32:
            for offset in range(0, len(char.encode('utf-16-be')), 2):
                unit = char.encode('utf-16-be')[offset:offset + 2]
                result += '\\u' + unit.hex()
        else:
            result += char
    return result


def require_line(path, key, value):
    expected = key + '=' + escape(value)
    require(expected in path.read_text(encoding='latin-1').splitlines(), f'{path}: missing {expected}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('java', 'agent', 'editor-jar', 'version', 'output'):
        parser.add_argument('--' + name, required=True)
    args = parser.parse_args()
    artifact = Path(args.editor_jar).resolve(strict=True)
    original_hash = digest(artifact)
    output = Path(args.output).resolve()
    output.mkdir(parents=False, exist_ok=False)
    config = output / 'probe.properties'
    values = dict(version=args.version, editorJar=artifact, outputRoot=output, runId='premain-smoke')
    config.write_text(''.join(k + '=' + escape(v) + '\n' for k, v in values.items()), encoding='ascii')
    env = os.environ.copy()
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    command = [args.java, '-javaagent:' + str(Path(args.agent).resolve(strict=True)) + '=' + str(config), '-version']

    def run(label):
        process = subprocess.run(command, env=env, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=30)
        (output / (label + '.log')).write_bytes(process.stdout)
        require(process.returncode == 0, f'{label}: JVM exited {process.returncode}')
        return process.stdout.decode('utf-8', errors='replace')

    first = run('valid')
    require('ATLAS_IMAGE_LOAD_PROBE_ARMED' in first and 'ATLAS_IMAGE_LOAD_PROBE_BLOCKED' not in first,
            'Valid configuration did not arm cleanly')
    identity = output / 'premain-smoke' / 'identity.properties'
    result = output / 'premain-smoke' / 'result.properties'
    require_line(identity, 'version', args.version)
    require_line(identity, 'editorSha256', original_hash)
    for key, value in {'status': 'BLOCKED', 'eventCount': '0', 'runId': 'premain-smoke',
                       'actual.requiredTargetObserved': 'false', 'optimizationReadiness': 'NOT_EVALUATED',
                       'completionReason': 'JVM_SHUTDOWN', 'reportAttempts': '1'}.items():
        require_line(result, key, value)
    before = {path: path.read_bytes() for path in (identity, result)}
    duplicate = run('duplicate')
    require('ATLAS_IMAGE_LOAD_PROBE_BLOCKED' in duplicate and 'ATLAS_IMAGE_LOAD_PROBE_ARMED' not in duplicate,
            'Duplicate runId was not refused')
    require(all(path.read_bytes() == data for path, data in before.items()), 'Duplicate runId changed evidence')
    require(digest(artifact) == original_hash, 'Read-only JAR changed during smoke')
    print('PREMAIN_READ_ONLY_SMOKE PASS version=' + args.version + ' hostClassesLoaded=false')


if __name__ == '__main__':
    main()
