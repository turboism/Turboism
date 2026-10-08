"""Build an independent non-JFR JVM context diagnostic; frozen production Agents unchanged."""
import argparse
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sys
import zipfile

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
spec = importlib.util.spec_from_file_location('base', HERE / 'controlled-performance-r1/build-controlled-canvas-performance-plugin.py')
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(name, None)
    guard = base.guard
    original = out / 'base'
    guard.guarded([sys.executable, str(HERE / 'controlled-performance-r1/build-controlled-canvas-performance-plugin.py'),
                   '--output', str(original), '--sdk', str(args.sdk.resolve())], out / 'base.log', env)
    if base.sha(original / 'native-controlled-canvas-probe.jar') != 'e03edb6d6d54fd3aa5869a7204cf19694ed4bc2a6d95b69553a9f45e82ae4b8a':
        raise ValueError('frozen T096 base plugin identity changed')
    sources = original / 'template/src'
    native = sources / 'NativeObserverFreeAutoConnect.java'
    base.replace(native, '        Interval interval = invokeMeasured(command, controller, doc, cpu);',
                 '        NativeJvmContextObservation.Snapshot beforeJvm = NativeJvmContextObservation.capture();\n'
                 '        Interval interval = invokeMeasured(command, controller, doc, cpu);\n'
                 '        NativeJvmContextObservation.publish(beforeJvm, NativeJvmContextObservation.capture());')
    driver = sources / 'T040ShadowSceneDriverAgent.java'
    base.replace(driver, '                NativeInputStateObservation.persist(run, cycle);',
                 '                NativeInputStateObservation.persist(run, cycle);\n'
                 '                NativeJvmContextObservation.persistPending(run, cycle);')
    base.replace(driver, '            final Path target = run.resolve("resource-windows.tsv");',
                 '            NativeJvmContextObservation.persist(run, phase, operation, NativeJvmContextObservation.capture());\n'
                 '            final Path target = run.resolve("resource-windows.tsv");')
    base.replace(driver, 'T096_CONTROLLED_CANVAS_PERFORMANCE_V1', 'T098_JVM_CONTEXT_DIAGNOSTIC_V1')
    helper = HERE / 'NativeJvmContextObservation.java'
    (sources / helper.name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + helper.read_text())
    classes = out / 'classes'; classes.mkdir()
    guard.guarded(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                   '-cp', str(args.sdk.resolve()), '-d', str(classes), *map(str, sorted(sources.glob('*.java')))], out / 'compile.log', env)
    jar = out / 'native-jvm-context-probe.jar'
    with zipfile.ZipFile(original / 'native-controlled-canvas-probe.jar') as old, zipfile.ZipFile(jar, 'x', compression=zipfile.ZIP_DEFLATED) as new:
        entries = {name: old.read(name) for name in old.namelist() if not name.endswith('.class')}
        entries.update({str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*.class')})
        for name, raw in sorted(entries.items()):
            if name.endswith('.class') and (b'jdk/jfr/' in raw or b'java/lang/instrument/' in raw):
                raise ValueError('forbidden diagnostic dependency')
            info = zipfile.ZipInfo(name); info.create_system = 3; info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED; new.writestr(info, raw)
    check = out / 'NativeJvmContextObservationSelfCheck.java'
    check.write_text('package dev.turboism.validation.atlasimage.shadow;\n' + (HERE / check.name).read_text())
    checks = out / 'check-classes'; checks.mkdir()
    guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(jar), '-d', str(checks), str(check)], out / 'check-compile.log', env)
    guard.guarded(['java', '-ea', '-Xverify:all', '-cp', str(jar) + os.pathsep + str(checks),
                   'dev.turboism.validation.atlasimage.shadow.NativeJvmContextObservationSelfCheck', str(out / 'context-selfcheck')], out / 'context-selfcheck.log', env)
    for name in json.loads((original / 'build.json').read_text())['actualPackagedChecks']:
        command = ['java', '-ea', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(jar) + os.pathsep + str(original / 'check-classes'),
                   'dev.turboism.validation.atlasimage.shadow.' + name]
        if name in ('ObserverFreeEvidenceWriterSelfCheck', 'NativeInputStateObservationSelfCheck'):
            command.append(str(out / (name + '-run')))
        guard.guarded(command, out / (name + '.log'), env)
    agents = [ROOT / 'build/t057-angle-integration-r1/production-scoped-r3/turboism-agent.jar',
              ROOT / 'build/t093-native-table-growth-r1/sole-premain-r1/turboism-agent.jar']
    for number, agent in enumerate(agents):
        guard.guarded(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp', str(agent) + os.pathsep + str(original / 'check-classes'),
                       'dev.turboism.preview.ObserverFreePluginLoaderSelfCheck', str(jar)], out / f'loader-{number}.log', env)
    report = dict(status='PASS_OFFLINE_JVM_CONTEXT_DIAGNOSTIC_NOT_HOST_VALIDATED', pluginSha256=base.sha(jar),
                  formalAcceptance='NOT_GRANTED', productionAgentsChanged=False, hostSubmitted=False,
                  inputPins={str(p): base.sha(p) for p in [Path(__file__), helper, HERE / check.name, args.sdk.resolve(), *agents]},
                  limitations=['MXBean enumeration is diagnostic overhead outside unchanged native command counter interval.',
                               'Thread counters cover surviving Java threads only; no monitoring is enabled, no stack sampling or forced GC.',
                               'GC collection time and compilation time are elapsed counters, not CPU attribution.'])
    (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], report['pluginSha256'])


if __name__ == '__main__':
    main()
