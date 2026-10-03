"""Controlled 5303 canvas input task plugin without JFR dependency; no production rewrite."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

HERE = Path(__file__).resolve().parent
SOURCE = HERE.parent
ROOT = SOURCE.parents[2]
spec = importlib.util.spec_from_file_location('guard', SOURCE / 'verify-native-mesh-edge-loop.py')
guard = importlib.util.module_from_spec(spec); spec.loader.exec_module(guard)


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def replace(path, old, new):
    text = path.read_text()
    if text.count(old) != 1: raise ValueError('unreviewed template occurrence: ' + old)
    path.write_text(text.replace(old, new))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    env = dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(key, None)
    out.mkdir(parents=True, exist_ok=False)
    template = out / 'template'
    guard.guarded([sys.executable, str(SOURCE / 'build-observer-free-mesh-plugin.py'), str(template),
                   '--sdk', str(args.sdk.resolve()), '--source-only'], out / 'template.log', env)
    source = template / 'src'
    native = source / 'NativeObserverFreeAutoConnect.java'
    replace(native, 'Interval interval = invokeMeasured(command, controller, doc, cpu);',
            'NativeInputStateObservation.Snapshot beforeInput = NativeInputStateObservation.capture(doc);\n'
            '        Interval interval = invokeMeasured(command, controller, doc, cpu);\n'
            '        NativeInputStateObservation.publish(beforeInput, NativeInputStateObservation.capture(doc));')
    replace(native, '        List<Input> inputs = bind(mode, expectedIds);',
            '        NativeCanvasInputDispatch.dispatch(doc);\n        List<Input> inputs = bind(mode, expectedIds);')
    driver = source / 'T040ShadowSceneDriverAgent.java'
    replace(driver, '                ObserverFreeEvidenceWriter.append(run, cycle, observed);',
            '                ObserverFreeEvidenceWriter.append(run, cycle, observed);\n'
            '                NativeInputStateObservation.persist(run, cycle);')
    replace(driver, 'T076_OBSERVER_FREE_COMMAND_BOUNDARY_V1', 'T096_CONTROLLED_CANVAS_PERFORMANCE_V1')
    helper = SOURCE / 'NativeInputStateObservation.java'
    (source / helper.name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + helper.read_text())
    canvas_helper = SOURCE / 'NativeCanvasInputDispatch.java'
    (source / canvas_helper.name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + canvas_helper.read_text())
    classes = out / 'classes'; classes.mkdir()
    guard.guarded(['javac', '--release', '17', '-proc:none', '-implicit:none', '-cp', str(args.sdk.resolve()),
                   '-d', str(classes), *map(str, sorted(source.glob('*.java')))], out / 'compile.log', env)
    forbidden = [b'java/lang/instrument/', b'MeshProducerRecorder', b'MeshProducerWeave',
                 b'NativeProducerAutoConnect', b'org/objectweb/asm', b'T039FreezeBridge', b'premain', b'agentmain', b'jdk/jfr/', b'NativeCommandJfrClock']
    for p in classes.rglob('*.class'):
        if any(token in p.read_bytes() for token in forbidden): raise ValueError('forbidden runtime dependency')
    meta = classes / 'META-INF/turboism'; meta.mkdir(parents=True)
    descriptor = json.loads((ROOT / 'validation/settings-page-probe/src/META-INF/turboism/plugin.json').read_text())
    descriptor.update(id='dev.turboism.validation.observerfreemesh', name='Native Controlled Canvas Task',
                      description='Task-local controlled canvas input and native command CPU boundary collection without JFR.',
                      entrypoints=['dev.turboism.validation.atlasimage.shadow.ObserverFreeMeshProbePlugin'])
    descriptor['permissions'] = [
        {'id': 'turboism.cubism.model.read', 'scope': 'application', 'reason': 'Reads task-bound native input and mesh state.'},
        {'id': 'turboism.cubism.model.write', 'scope': 'application', 'reason': 'Runs native mesh commands and cancels without saving.'}]
    (meta / 'plugin.json').write_text(json.dumps(descriptor, indent=2) + '\n')
    catalogs = meta / 'i18n'; catalogs.mkdir(); (catalogs / 'messages.properties').write_text('probe.name=Native Controlled Canvas Task\n')
    jar = out / 'native-controlled-canvas-probe.jar'
    with zipfile.ZipFile(jar, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        entries = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n'}
        entries.update({str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*') if p.is_file()})
        for name, raw in sorted(entries.items()):
            info = zipfile.ZipInfo(name); info.create_system = 3; info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED; archive.writestr(info, raw)
    checks = out / 'checks'; checks.mkdir()
    check_names = ['NativeObserverFreeAutoConnectSelfCheck', 'CommandCpuBoundarySelfCheck', 'MeshResultSnapshotSelfCheck',
                   'ObserverFreeEvidenceWriterSelfCheck', 'NativeInputStateObservationSelfCheck', 'NativeCanvasInputDispatchSelfCheck']
    for name in check_names:
        (checks / (name + '.java')).write_text('package dev.turboism.validation.atlasimage.shadow;\n'
                                              + (SOURCE / (name + '.java')).read_text())
    check_classes = out / 'check-classes'; check_classes.mkdir()
    guard.guarded(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror', '-cp', str(jar),
                   '-d', str(check_classes), *map(str, checks.glob('*.java'))], out / 'check-compile.log', env)
    for name in check_names:
        command = ['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(jar) + os.pathsep + str(check_classes),
                   'dev.turboism.validation.atlasimage.shadow.' + name]
        if name in ('ObserverFreeEvidenceWriterSelfCheck', 'NativeInputStateObservationSelfCheck'):
            command.append(str(out / (name + '-run')))
        guard.guarded(command, out / (name + '.log'), env)
    accepted = ROOT / 'build/t057-angle-integration-r1/production-scoped-r3/turboism-agent.jar'
    if sha(accepted) != '17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295':
        raise ValueError('accepted production loader pin mismatch')
    loader_source = SOURCE / 'ObserverFreePluginLoaderSelfCheck.java'
    guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(accepted), '-d', str(check_classes),
                   str(loader_source)], out / 'loader-compile.log', env)
    guard.guarded(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(accepted) + os.pathsep + str(check_classes),
                   'dev.turboism.preview.ObserverFreePluginLoaderSelfCheck', str(jar)], out / 'loader-check.log', env)
    guard.guarded(['javap', '-classpath', str(jar), '-c', '-p',
                   'dev.turboism.validation.atlasimage.shadow.NativeObserverFreeAutoConnect'],
                  out / 'native-bytecode.txt', env)
    bytecode = (out / 'native-bytecode.txt').read_text()
    if 'invokestatic' not in bytecode or 'Method invokeMeasured:' not in bytecode or 'JfrClock' in bytecode:
        raise ValueError('actual direct native CPU boundary call required')
    final_agent = ROOT / 'build/t093-native-table-growth-r1/sole-premain-r1/turboism-agent.jar'
    if sha(final_agent) != 'fa9e417c52cfc8463bfdf7c505da93bfbb70930e9cbc9edf397d0a3aba234e1d':
        raise ValueError('final production agent identity mismatch')
    guard.guarded(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(final_agent) + os.pathsep + str(check_classes),
                   'dev.turboism.preview.ObserverFreePluginLoaderSelfCheck', str(jar)], out / 'final-loader-check.log', env)
    report = {'status': 'PASS_OFFLINE_NON_JFR_CONTROLLED_CANVAS_PLUGIN_NOT_HOST_VALIDATED', 'pluginSha256': sha(jar),
              'performanceAcceptance': 'NOT_GRANTED', 'hostPreparedOrSubmitted': False,
              'inputs': json.loads((template / 'build.json').read_text())['inputs'],
              'actualPackagedChecks': check_names, 'productionLoaderCheck': 'PASS', 'jfrDependency': False, 'cpuInterval': 'UNCHANGED_DIRECT_PRE_RESOLVED_NATIVE_COMMAND',
              'pins': {str(p): sha(p) for p in [Path(__file__).resolve(), helper, canvas_helper, loader_source, args.sdk.resolve(), accepted, final_agent,
                                               *[SOURCE / (n + '.java') for n in check_names]]}}
    (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], report['pluginSha256'])


if __name__ == '__main__': main()
