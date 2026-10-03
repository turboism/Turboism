"""Run owned native append-loop differential controls with a shared-queue guard."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import sqlite3
import subprocess
import time

PINS = {'5203': 'bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd',
        '5302': '988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21',
        '5303': 'bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'}


def quiet_jobs():
    shared = Path.home() / '.local/state/turboism/host-validation'
    with sqlite3.connect('file:' + str(shared / 'queue.sqlite3') + '?mode=ro', uri=True) as db:
        rows = db.execute("select sequence,request_key,state,prepared_id from jobs where state in ('queued','running')").fetchall()
    found = []
    for seq, key, state, prepared in rows:
        args = json.loads((shared / 'prepared' / prepared / 'prepared.json').read_text())['argv']
        if any(any(token in arg.lower() for token in ('resourceobservation=true', 'startflightrecording', 'benchmark', 'quiet', 'profiling')) for arg in args):
            found.append({'sequence': seq, 'key': key, 'state': state})
    return found


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def guarded(argv, output, env):
    blocked = quiet_jobs()
    if blocked:
        raise RuntimeError('shared performance job present: ' + json.dumps(blocked))
    with output.open('x') as log:
        child = subprocess.Popen(argv, env=env, stdout=log, stderr=subprocess.STDOUT, start_new_session=True)
        output.with_suffix('.process.json').write_text(json.dumps({'pid': child.pid, 'argv': argv}, indent=2) + '\n')
        started = time.monotonic()
        while child.poll() is None:
            try:
                blocked = quiet_jobs()
            except Exception as failure:
                blocked = [{'reason': 'authority_read_failed', 'type': type(failure).__name__}]
            timed_out = time.monotonic() - started > 180
            if blocked or timed_out:
                os.killpg(child.pid, signal.SIGTERM)
                try:
                    child.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    os.killpg(child.pid, signal.SIGKILL)
                    child.wait()
                raise RuntimeError('own process stopped: ' + json.dumps(blocked) + ' timeout=' + str(timed_out))
            time.sleep(0.5)
    if child.returncode:
        raise RuntimeError('owned command failed: ' + str(child.returncode) + '\n' + output.read_text()[-6000:])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'scope': 'OWNED_NATIVE_SUFFIX_ONLY', 'productionChanged': False, 'editorLaunched': False,
              'hostGain': 'UNPROVEN', 'status': 'STARTED', 'pins': {}}
    try:
        if quiet_jobs():
            raise RuntimeError('shared performance work pending')
        source = Path(__file__).resolve().parent
        sources = [source / (name + '.java') for name in ('NativeMeshEdgeLoopPrototype', 'NativeMeshEdgeLoopSelfCheck')]
        jars = []
        for version, expected in PINS.items():
            argv = json.loads(Path(f'build/t053-local-builder-r1/metadata-production/on{version}/command.json').read_text())['argv']
            jar = next(Path(p) for p in argv[argv.index('-cp') + 1].split(os.pathsep) if p.endswith('/Live2D_Cubism.jar'))
            actual = sha(jar)
            if actual != expected:
                raise ValueError('official archive identity mismatch: ' + version)
            report['pins'][str(jar)] = actual
            jars.append(jar)
        cache = Path.home() / '.gradle/caches/modules-2/files-2.1/org.ow2.asm'
        asm = next((cache / 'asm/9.7.1').glob('*/*.jar'))
        tree = next((cache / 'asm-tree/9.7.1').glob('*/*.jar'))
        for path in [Path(__file__).resolve(), *sources, asm, tree]:
            report['pins'][str(path)] = sha(path)
        classes = out / 'classes'
        classes.mkdir()
        env = dict(os.environ)
        for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
            env.pop(key, None)
        guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', os.pathsep.join(map(str, [asm, tree])),
                 '-d', str(classes), *map(str, sources)], out / 'compile.log', env)
        guarded(['java', '-Djava.awt.headless=true', '-Xverify:all', '-XX:+DisableAttachMechanism', '-cp',
                 os.pathsep.join(map(str, [classes, asm, tree])), 'NativeMeshEdgeLoopSelfCheck', *map(str, jars)],
                out / 'execution.log', env)
        console = (out / 'execution.log').read_text()
        if console.count('NATIVE_MESH_EDGE_LOOP_PASS ') != 6 or 'NATIVE_MESH_EDGE_LOOP_FINISHED ' not in console:
            raise ValueError('missing complete three-version assertion controls')
        report['status'] = 'PASS'
        report['results'] = [line for line in console.splitlines() if line.startswith('NATIVE_MESH_EDGE_LOOP_')]
    except Exception as failure:
        report['status'] = 'FAILED_OR_GUARDED_STOP'
        report['failure'] = str(failure)
        raise
    finally:
        for path in out.rglob('*'):
            if path.is_file() and path.name != 'review.json':
                report['pins'][str(path)] = sha(path)
        (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
        print(json.dumps(report), flush=True)


if __name__ == '__main__':
    main()
