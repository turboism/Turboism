"""Independent in-process NMT/heap-range plugin; no production rewrite or attach."""
import argparse
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
    guard.guarded([sys.executable, str(HERE / 'build-jvm-context-plugin.py'), '--output', str(original),
                   '--sdk', str(args.sdk.resolve())], out / 'base.log', env)
    original_jar = original / 'native-jvm-context-probe.jar'
    if base.sha(original_jar) != '9b5a2397cd585717a6fc3a7ad70dc6611af08ff5dd5342637d1c9eb4200c9eee':
        raise ValueError('frozen T098 identity changed')
    source = original / 'base/template/src'
    context = source / 'NativeJvmContextObservation.java'
    base.replace(context, 'long nanoStart, long nanoEnd, List<Row> rows) {}',
                 'long nanoStart, long nanoEnd, List<Row> rows, NativeNmtObservation.Snapshot nmt) {}')
    base.replace(context, '    static Snapshot capture() {', '    static Snapshot capture() throws Exception {')
    base.replace(context, '        return new Snapshot(startEpoch, System.currentTimeMillis(), startNano, System.nanoTime(), List.copyOf(rows));',
                 '        NativeNmtObservation.Snapshot nmt = NativeNmtObservation.capture();\n'
                 '        return new Snapshot(startEpoch, System.currentTimeMillis(), startNano, System.nanoTime(), List.copyOf(rows), nmt);')
    base.replace(context, '        Path target = run.resolve("native-jvm-context.tsv");',
                 '        NativeNmtObservation.persist(run, phase, operation, snapshot.nmt());\n'
                 '        Path target = run.resolve("native-jvm-context.tsv");')
    base.replace(source / 'T040ShadowSceneDriverAgent.java', 'T098_JVM_CONTEXT_DIAGNOSTIC_V1', 'T099_NMT_HEAP_PAGES_DIAGNOSTIC_V1')
    helper = HERE / 'NativeNmtObservation.java'
    (source / helper.name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + helper.read_text())
    classes = out / 'classes'; classes.mkdir()
    guard.guarded(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                   '-cp', str(args.sdk.resolve()), '-d', str(classes), *map(str, sorted(source.glob('*.java')))], out / 'compile.log', env)
    jar = out / 'native-nmt-context-probe.jar'
    with zipfile.ZipFile(original_jar) as old, zipfile.ZipFile(jar, 'x', compression=zipfile.ZIP_DEFLATED) as new:
        entries = {name: old.read(name) for name in old.namelist() if not name.endswith('.class')}
        entries.update({str(p.relative_to(classes)): p.read_bytes() for p in classes.rglob('*.class')})
        for name, raw in sorted(entries.items()):
            if name.endswith('.class') and (b'jdk/jfr/' in raw or b'java/lang/instrument/' in raw):
                raise ValueError('forbidden diagnostic dependency')
            info = zipfile.ZipInfo(name); info.create_system = 3; info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED; new.writestr(info, raw)
    check = out / 'NativeNmtObservationSelfCheck.java'
    check.write_text('package dev.turboism.validation.atlasimage.shadow;\n' + (HERE / check.name).read_text())
    checks = out / 'check-classes'; checks.mkdir()
    guard.guarded(['javac', '--release', '17', '-Xlint:all', '-Werror', '-cp', str(jar), '-d', str(checks), str(check)], out / 'check-compile.log', env)
    common = ['java', '-ea', '-Xverify:all', '-XX:+DisableAttachMechanism']
    for label, options, target in [('enabled', ['-XX:NativeMemoryTracking=summary'], str(out / 'nmt-selfcheck')), ('disabled', [], 'disabled')]:
        guard.guarded([*common, *options, '-cp', str(jar) + os.pathsep + str(checks),
                       'dev.turboism.validation.atlasimage.shadow.NativeNmtObservationSelfCheck', target], out / (label + '.log'), env)
    guard.guarded([*common, '-XX:NativeMemoryTracking=summary', '-cp', str(jar) + os.pathsep + str(original / 'check-classes'),
                   'dev.turboism.validation.atlasimage.shadow.NativeJvmContextObservationSelfCheck', str(out / 'context-selfcheck')], out / 'context-selfcheck.log', env)
    for name in json.loads((original / 'base/build.json').read_text())['actualPackagedChecks']:
        command = [*common, '-XX:NativeMemoryTracking=summary', '-Djava.awt.headless=true', '-cp',
                   str(jar) + os.pathsep + str(original / 'base/check-classes'),
                   'dev.turboism.validation.atlasimage.shadow.' + name]
        if name in ('ObserverFreeEvidenceWriterSelfCheck', 'NativeInputStateObservationSelfCheck'):
            command.append(str(out / (name + '-run')))
        guard.guarded(command, out / (name + '.log'), env)
    agents = [ROOT / 'build/t057-angle-integration-r1/production-scoped-r3/turboism-agent.jar',
              ROOT / 'build/t093-native-table-growth-r1/sole-premain-r1/turboism-agent.jar']
    for number, agent in enumerate(agents):
        guard.guarded([*common, '-XX:NativeMemoryTracking=summary', '-Djava.awt.headless=true', '-cp',
                       str(agent) + os.pathsep + str(original / 'base/check-classes'),
                       'dev.turboism.preview.ObserverFreePluginLoaderSelfCheck', str(jar)], out / f'loader-{number}.log', env)
    report = dict(status='PASS_OFFLINE_IN_PROCESS_NMT_AND_HEAP_RANGE_NOT_HOST_VALIDATED', pluginSha256=base.sha(jar),
                  productionPerformanceAcceptance='NOT_GRANTED', productionAgentsChanged=False, hostSubmitted=False,
                  inputPins={str(p): base.sha(p) for p in [Path(__file__), helper, HERE / check.name, args.sdk.resolve(), *agents]},
                  limitations=['NMT summary is reserved/committed accounting, not RSS; external smaps is needed for page residency.',
                               'GC.heap_info is read-only; no GC request, attach, stack sampling or monitoring enablement.',
                               'NMT startup bookkeeping and in-process queries are independent diagnostic overhead, not formal performance.'])
    (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], report['pluginSha256'])


if __name__ == '__main__':
    main()
