"""Verify the shared bridge with genuine frozen Turboism sole-premain ownership."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import zipfile

SOURCE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('guard', SOURCE / 'verify-native-mesh-edge-loop.py')
guard = importlib.util.module_from_spec(spec)
spec.loader.exec_module(guard)
BASELINE_SHA = '17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--baseline', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--profile', choices=['5203', '5302', '5303'])
    parser.add_argument('--logger-only', action='store_true')
    parser.add_argument('--point-only', action='store_true', help='run point admission and cold logger callback controls only')
    parser.add_argument('--integration', action='store_true', help='compose actual SDK native mesh dependency plan and transformer')
    args = parser.parse_args()
    baseline = args.baseline.resolve()
    if sha(baseline) != BASELINE_SHA:
        raise ValueError('frozen T057 identity mismatch')
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {'scope': 'REAL_SOLE_PREMAIN_SHARED_BRIDGE_PROTOCOL', 'status': 'STARTED',
              'nativeMeshAdmissionIntegrated': args.integration, 'editorLaunched': False, 'cases': [], 'pins': {str(baseline): BASELINE_SHA}}
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    try:
        classes = out / 'bridge-classes'
        classes.mkdir()
        bridge = Path('runtime/src/main/java/dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge.java').resolve()
        lifecycle = bridge.with_name('TriangulationDefinitionLifecycle.java')
        source_names = ['LazyTriangulationEdgeBridge', 'TriangulationDefinitionLifecycle', 'PointTriangleReusePatcher', 'PointTriangleReusePreparation']
        if args.integration:
            source_names.extend(['TriangulationEdgeIndexTransformer', 'LazyTriangulationEdgePreparation',
                                 'NativeMeshEdgePreparation', 'NativeMeshEdgePatcher', 'NativeMeshEdgeLookup', 'NativeMeshEdgeTable'])
        originals = [bridge.with_name(name + '.java') for name in source_names]
        copies = out / 'compile-sources'
        copies.mkdir()
        inputs = []
        for source in originals:
            copy = copies / source.name
            copy.write_text(source.read_text().replace('org.objectweb.asm', 'dev.turboism.agent.shaded.asm'))
            inputs.append(copy)
        guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(baseline),
                       '-d', str(classes), *map(str, inputs)], out / 'bridge-compile.log', env)
        replacements = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
        family = tuple('dev/turboism/adapter/cubism/mesh/' + name for name in source_names)
        if not replacements or any(not name.startswith(family) for name in replacements):
            raise ValueError('unexpected compiled family')
        candidate = out / 'turboism-agent.jar'
        with zipfile.ZipFile(baseline) as src, zipfile.ZipFile(candidate, 'x') as dst:
            if len(src.namelist()) != len(set(src.namelist())):
                raise ValueError('duplicate baseline entries')
            for entry in src.infolist():
                dst.writestr(entry, replacements.get(entry.filename, src.read(entry.filename)))
            for name, raw in replacements.items():
                if name not in src.namelist():
                    dst.writestr(name, raw)
        with zipfile.ZipFile(baseline) as src, zipfile.ZipFile(candidate) as dst:
            changed = [name for name in src.namelist() if src.read(name) != dst.read(name)]
            added = sorted(set(dst.namelist()) - set(src.namelist()))
            if not changed or any(not name.startswith(family) for name in changed + added):
                raise ValueError('artifact isolation audit failed')
            report['artifactAudit'] = {'changed': changed, 'added': added, 'unrelatedEntriesIdentical':
                                      sum(not name.startswith(family) for name in src.namelist())}
        report['candidateSha256'] = sha(candidate)
        probes = [SOURCE / (name + '.java') for name in ('SharedMeshPremainSelfCheck',
                  'BorrowedEdgePremainSelfCheck', 'LocalBuilderPremainSelfCheck')]
        if args.integration:
            probes.extend([SOURCE / 'NativeMeshPremainSelfCheck.java', SOURCE / 'PointTrianglePremainSelfCheck.java', SOURCE / 'PointTriangleLoggerCallbackSelfCheck.java'])
        probe_classes = out / 'probe-classes'
        probe_classes.mkdir()
        guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(candidate),
                       '-d', str(probe_classes), *map(str, probes)], out / 'probe-compile.log', env)
        for path in [Path(__file__).resolve(), SOURCE / 'verify-native-mesh-edge-loop.py', *originals, *probes]:
            report['pins'][str(path)] = sha(path)
        for profile in ([args.profile] if args.profile else ('5203', '5302', '5303')):
            template = Path('build/t053-local-builder-r1/metadata-production') / ('on' + profile)
            old = json.loads((template / 'command.json').read_text())['argv']
            host_cp = old[old.index('-cp') + 1].split(os.pathsep)[1:]
            cases = [('shared-' + mode, 'SharedMeshPremainSelfCheck', mode) for mode in
                     ('shared', 'legacy', 'fallback', 'mesh-first', 'race', 'revocation', 'split', 'failure', 'missing-mesh')]
            cases += [('native-' + control, 'BorrowedEdgePremainSelfCheck', control) for control in
                      ('accepted', 'revocation', 'index-getter')]
            cases += [('builder-' + control, 'LocalBuilderPremainSelfCheck', control) for control in
                      ('accepted', 'revocation')]
            if args.integration:
                cases += [('mesh-' + mode, 'NativeMeshPremainSelfCheck', mode) for mode in
                          ('accepted', 'callback', 'mesh-mutation', 'revocation')]
            if args.integration:
                cases += [('point-' + mode, 'PointTrianglePremainSelfCheck', mode) for mode in ('accepted', 'edge-first', 'revocation')]
            if args.integration:
                cases += [('point-logger', 'PointTriangleLoggerCallbackSelfCheck', 'logger')]
            if args.point_only:
                if not args.integration: raise ValueError('--point-only requires --integration')
                cases = [case for case in cases if case[0].startswith('point-')]
            if args.logger_only:
                if not args.integration: raise ValueError('--logger-only requires --integration')
                cases = [case for case in cases if case[0] == 'point-logger']
            for key, main_class, control in cases:
                case = out / (profile + '-' + key)
                home = case / 'home'
                home.mkdir(parents=True)
                shutil.copyfile(candidate, home / 'turboism-agent.jar')
                shutil.copyfile(template / 'home/config.json', home / 'config.json')
                argv = ['java', '-Djava.awt.headless=true', '-Xverify:all', '-XX:+DisableAttachMechanism',
                        '-javaagent:' + str(home / 'turboism-agent.jar') + '=home=' + str(home)
                        + ';hostClass=owned.NoCubismApplication;timeoutSeconds=30', '-cp',
                        os.pathsep.join([str(probe_classes), *host_cp]), main_class, control]
                (case / 'command.json').write_text(json.dumps({'argv': argv}, indent=2) + '\n')
                guard.guarded(argv, case / 'console.log', env)
                console = (case / 'console.log').read_text()
                marker = {'SharedMeshPremainSelfCheck': 'SHARED_MESH_PREMAIN_PASS',
                          'BorrowedEdgePremainSelfCheck': 'BORROWED_EDGE_PREMAIN_CONTROL_PASS',
                          'LocalBuilderPremainSelfCheck': 'LOCAL_BUILDER_PREMAIN_CONTROL_PASS',
                          'NativeMeshPremainSelfCheck': 'NATIVE_MESH_PREMAIN_PASS',
                          'PointTrianglePremainSelfCheck': 'POINT_TRIANGLE_PREMAIN_PASS',
                          'PointTriangleLoggerCallbackSelfCheck': 'POINT_TRIANGLE_LOGGER_CALLBACK_PASS'}[main_class]
                if marker not in console:
                    raise ValueError('missing protocol marker: ' + profile + '-' + key)
                report['cases'].append({'profile': profile, 'control': key, 'status': 'PASS',
                                        'result': next(line for line in console.splitlines() if line.startswith(marker))})
                print(profile, key, 'PASS', flush=True)
        report['status'] = 'PASS'
    except Exception as failure:
        report['status'] = 'FAILED_OR_GUARDED_STOP'
        report['failure'] = str(failure)
        raise
    finally:
        for path in out.rglob('*'):
            if path.is_file() and path.name != 'review.json':
                report['pins'][str(path)] = sha(path)
        (out / 'review.json').write_text(json.dumps(report, indent=2) + '\n')
        print(report['status'], 'cases=' + str(len(report['cases'])), flush=True)


if __name__ == '__main__':
    main()
