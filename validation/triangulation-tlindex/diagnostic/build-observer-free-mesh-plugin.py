"""Build a distinct 5303 task plugin; never prepare, submit or admit performance."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--production-agent', type=Path,
                        help='Exact T057 or T075 JAR for offline real inspector/plugin-loader check')
    parser.add_argument('--source-only', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[3]
    diag = Path(__file__).resolve().parent
    scene = root / 'validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow'
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    src = out / 'src'
    src.mkdir()
    pins = {}
    helpers = ['NativeObserverFreeAutoConnect.java', 'NativeAutoConnect.java', 'NativeCancelPrompt.java',
               'MeshResultSnapshot.java', 'CommandCpuBoundary.java', 'ObserverFreeEvidenceWriter.java']
    names = ['FixedEdt.java', 'StageEvidence.java', 'ShadowPayloadStore.java', 'ShadowSceneContract.java',
             'T040ShadowSceneDriverAgent.java']
    for path in [scene / name for name in names] + [diag / name for name in helpers]:
        raw = path.read_bytes()
        pins[str(path.relative_to(root))] = hashlib.sha256(raw).hexdigest()
        text = raw.decode()
        if path.parent == diag:
            text = 'package dev.turboism.validation.atlasimage.shadow;\n' + text
        (src / path.name).write_text(text)
    plugin = diag / 'ObserverFreeMeshProbePlugin.java'
    pins[str(plugin.relative_to(root))] = hashlib.sha256(plugin.read_bytes()).hexdigest()
    (src / plugin.name).write_bytes(plugin.read_bytes())
    driver = src / 'T040ShadowSceneDriverAgent.java'
    assert pins[str((scene / driver.name).relative_to(root))] == 'e1be189f55c355081f7b52a100e709272e75f92d9d3edc2fd68a5337c12cf870'
    text = driver.read_text()

    def replace(old, new):
        nonlocal text
        if text.count(old) != 1:
            raise ValueError('unreviewed template occurrence: ' + old[:100])
        text = text.replace(old, new)

    replace('import java.lang.instrument.Instrumentation;\n', '')
    first = text.index('    public static void premain(')
    last = text.index('    static final class DriverConfig', first)
    text = text[:first] + '''    private static final AtomicBoolean STARTED = new AtomicBoolean();
    public static void startFromPlugin() {
        if (!STARTED.compareAndSet(false, true))
            throw new IllegalStateException("observer-free plugin already started");
        try {
            if (!"T076_OBSERVER_FREE_COMMAND_BOUNDARY_V1".equals(
                    System.getProperty("turboism.validation.observerFree.optIn")))
                throw new IllegalArgumentException("observer-free task opt-in required");
            final DriverConfig config = DriverConfig.fromSystemProperties();
            if (!"5303".equals(config.version) || config.shadow || config.exportProbe
                    || config.resourceObservationSeconds == 0 || config.menuDump || config.editorReopen)
                throw new IllegalArgumentException("exact5303 heavy observer-free command profile required");
            final Thread worker = new Thread(() -> new FixedDriver(config).run(),
                    "observer-free-native-command-driver");
            worker.setDaemon(true);
            worker.start();
            System.out.println("OBSERVER_FREE_NATIVE_COMMAND_DRIVER_STARTED_NOT_PERFORMANCE_ACCEPTANCE");
        } catch (Exception failure) {
            throw new IllegalStateException("observer-free task driver blocked", failure);
        }
    }

''' + text[last:]
    replace('this.shadow = ShadowSceneContract.VERSION_5303.equals(version);', 'this.shadow = false;')
    first = text.index('            final String profile;')
    last = text.index('            return new DriverConfig', first)
    text = text[:first] + '''            ShadowSceneContract.requireT039Absent();
            final String profile = version;
''' + text[last:]
    replace('        private final T039FreezeBridge.FreezeProvider freezeProvider;\n', '')
    replace('FixedDriver(final DriverConfig config, final T039FreezeBridge.FreezeProvider freezeProvider)',
            'FixedDriver(final DriverConfig config)')
    replace('            this.freezeProvider = freezeProvider;\n', '')
    first = text.index('                observeResources("baseline", 0);')
    last = text.index('                final Map<String, String> freeze;', first)
    text = text[:first] + '                observerFreeCommands();\n\n' + text[last:]
    first = text.index('                final Map<String, String> freeze;')
    last = text.index('                evidence.payloadWriteStarted();', first)
    text = text[:first] + '                final Map<String, String> freeze = Map.of();\n' + text[last:]
    replace('        private void resourceMarker(final String phase, final int operation) throws Exception {',
            '''        private Object meshController() throws Exception {
            ClassLoader hostLoader = ClassLoader.getSystemClassLoader();
            if (!hostLoader.getClass().getName().equals("jdk.internal.loader.ClassLoaders$AppClassLoader"))
                throw new IllegalStateException("unexpected host system loader");
            Class<?> type = Class.forName("com.live2d.cubism.CEAppCtrl", false, hostLoader);
            if (type.getClassLoader() != hostLoader) throw new IllegalStateException("controller loader mismatch");
            java.lang.reflect.Field instance = type.getDeclaredField("_instance");
            instance.setAccessible(true);
            Object value = instance.get(null);
            if (value == null) throw new IllegalStateException("native controller absent");
            return value;
        }

        private void observerFreeCommands() throws Exception {
            if (!config.fixtureSha256.equals(sha256(config.fixture)))
                throw new IllegalStateException("task fixture changed");
            final Object controller = FixedEdt.callWithin(() -> meshController(),
                    FixedEdt.Operation.MESH_BIND, evidence, remainingMillis(runDeadlineNanos));
            final NativeAutoConnect.Binding binding = FixedEdt.callWithin(
                    () -> NativeAutoConnect.binding(controller), FixedEdt.Operation.MESH_BIND,
                    evidence, remainingMillis(runDeadlineNanos));
            binding.verifyFixture(config.fixture.toFile());
            final List<String> ids = FixedEdt.callWithin(() -> NativeObserverFreeAutoConnect.enter(controller, binding),
                    FixedEdt.Operation.MESH_ENTER, evidence, remainingMillis(runDeadlineNanos));
            if (ids.size() != 711) throw new IllegalStateException("exact heavy source count differs");
            Files.writeString(run.resolve("auto-connect-selected.txt"), ids.stream().map(id ->
                    java.util.Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8)))
                    .collect(java.util.stream.Collectors.joining("\\n")) + "\\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            Files.writeString(run.resolve("observer-free-protocol.properties"),
                    "schema=OBSERVER_FREE_COMMAND_RETURN_V1\\nprofile=5303\\ncycles=3\\nrebuild=true\\npreserveBorder=true\\nproducerRecorder=false\\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(run.resolve("observer-free-command-cpu.tsv"), CommandCpuBoundary.header(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(run.resolve("observer-free-results.tsv"),
                    "cycle\\tsourceIdBase64\\tpointCount\\tpositionValues\\tindexValues\\tpositionsSha256\\tindicesSha256\\tbeforeEdgeVersion\\tcommandEdgeVersion\\tindexCacheVersion\\tpositionVersion\\tvertexCacheVersion\\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            final CommandCpuBoundary cpu = CommandCpuBoundary.system();
            observeResources("mesh-baseline", 0);
            for (int cycle = 1; cycle <= 3; cycle++) {
                if (!config.fixtureSha256.equals(sha256(config.fixture)))
                    throw new IllegalStateException("task fixture changed");
                resourceMarker("command-dispatch-start", cycle);
                final NativeObserverFreeAutoConnect.Observation observed = FixedEdt.callWithin(
                        () -> NativeObserverFreeAutoConnect.connect(controller, binding, ids, cpu),
                        FixedEdt.Operation.MESH_CONNECT, evidence, remainingMillis(runDeadlineNanos));
                resourceMarker("command-observation-end", cycle);
                Files.writeString(run.resolve("observer-free-command-cpu.tsv"),
                        CommandCpuBoundary.row("native-command-before", cycle, observed.before())
                        + CommandCpuBoundary.row("native-command-after", cycle, observed.after()),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                StringBuilder rows = new StringBuilder();
                for (var r : observed.results()) {
                    var a = r.arrays();
                    rows.append(cycle).append('\\t').append(java.util.Base64.getEncoder().encodeToString(
                            r.sourceId().getBytes(StandardCharsets.UTF_8))).append('\\t')
                            .append(a.pointCount()).append('\\t').append(a.positionValues()).append('\\t')
                            .append(a.indexValues()).append('\\t').append(a.positionsSha256()).append('\\t')
                            .append(a.indicesSha256()).append('\\t').append(r.beforeEdgeVersion()).append('\\t')
                            .append(a.edgeVersion()).append('\\t').append(r.indexCacheVersion()).append('\\t')
                            .append(r.positionVersion()).append('\\t').append(r.vertexCacheVersion()).append('\\n');
                }
                Files.writeString(run.resolve("observer-free-results.tsv"), rows.toString(),
                        StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                observeResources("mesh-retained", cycle);
            }
            final int answers = FixedEdt.callWithin(() -> NativeAutoConnect.leave(controller, binding, main),
                    FixedEdt.Operation.MESH_LEAVE, evidence,
                    Math.min(remainingMillis(runDeadlineNanos), TimeUnit.SECONDS.toMillis(30)));
            Files.writeString(run.resolve("observer-free-cancel.properties"),
                    "status=PASS\\nresource=CUB3-4362\\nanswers=" + answers + "\\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW);
            if (!config.fixtureSha256.equals(sha256(config.fixture)))
                throw new IllegalStateException("fixture changed after native cancel");
        }

        private void resourceMarker(final String phase, final int operation) throws Exception {''')
    driver.write_text(text)
    text = driver.read_text()
    first = text.index('            Files.writeString(run.resolve("observer-free-command-cpu.tsv"), CommandCpuBoundary.header(),')
    last = text.index('            final CommandCpuBoundary cpu =', first)
    text = text[:first] + '            ObserverFreeEvidenceWriter.begin(run);\n' + text[last:]
    first = text.index('                Files.writeString(run.resolve("observer-free-command-cpu.tsv"),')
    last = text.index('                observeResources("mesh-retained", cycle);', first)
    text = text[:first] + '                ObserverFreeEvidenceWriter.append(run, cycle, observed);\n' + text[last:]
    driver.write_text(text)
    edt = src / 'FixedEdt.java'
    edt.write_text(edt.read_text().replace('        MAIN_LOOKUP,',
                   '        MESH_BIND,\n        MESH_ENTER,\n        MESH_CONNECT,\n        MESH_LEAVE,\n        MAIN_LOOKUP,'))
    native = src / 'NativeAutoConnect.java'
    text = native.read_text()
    assert text.count('"CUB3-0009"') == 2
    native.write_text(text.replace('"CUB3-0009"', '"CUB3-4362"'))
    pins[str(Path(__file__).resolve().relative_to(root))] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
    report = {'status': 'SOURCE_ONLY_NOT_COMPILED', 'profile': '5303', 'inputs': pins,
              'generatedSources': {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(src.glob('*.java'))},
              'hostPreparedOrSubmitted': False, 'productionAcceptance': 'NOT_GRANTED'}
    if args.source_only:
        (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
        return
    classes = out / 'classes'
    classes.mkdir()
    env = dict(os.environ)
    for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
        env.pop(name, None)
    subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-cp', str(args.sdk.resolve()),
                    '-d', str(classes), *[str(p) for p in sorted(src.glob('*.java'))]], env=env, check=True)
    forbidden = [b'java/lang/instrument/', b'MeshProducerRecorder', b'MeshProducerWeave',
                 b'NativeProducerAutoConnect', b'org/objectweb/asm', b'T039FreezeBridge', b'premain', b'agentmain']
    for path in classes.rglob('*.class'):
        if any(token in path.read_bytes() for token in forbidden):
            raise ValueError('forbidden runtime dependency: ' + path.name)
    meta = classes / 'META-INF/turboism'
    meta.mkdir(parents=True)
    descriptor = json.loads((root / 'validation/settings-page-probe/src/META-INF/turboism/plugin.json').read_text())
    descriptor.update(id='dev.turboism.validation.observerfreemesh', name='Observer-Free Native Mesh Probe',
                      description='Task-local native mesh command/output boundary probe; no performance admission.',
                      entrypoints=['dev.turboism.validation.atlasimage.shadow.ObserverFreeMeshProbePlugin'])
    descriptor['permissions'] = [
        {'id': 'turboism.cubism.model.read', 'scope': 'application',
         'reason': 'Verifies the exact task document and complete mesh result scope.'},
        {'id': 'turboism.cubism.model.write', 'scope': 'application',
         'reason': 'Runs task-bound native mesh edits and cancels them before exiting without saving.'},
    ]
    (meta / 'plugin.json').write_text(json.dumps(descriptor, indent=2) + '\n')
    catalogs = meta / 'i18n'
    catalogs.mkdir()
    (catalogs / 'messages.properties').write_text('probe.name=Observer-Free Native Mesh Probe\n')
    jar = out / 'observer-free-mesh-probe.jar'
    # JDK jar emits META-INF/ with external type metadata rejected by this
    # production parser. Emit regular files only, with explicit Unix file type.
    # Keep the production archive policy unchanged.
    with zipfile.ZipFile(jar, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
        entries = {'META-INF/MANIFEST.MF': b'Manifest-Version: 1.0\r\n\r\n'}
        entries.update({str(p.relative_to(classes)): p.read_bytes()
                        for p in sorted(classes.rglob('*')) if p.is_file()})
        for name, raw in sorted(entries.items()):
            info = zipfile.ZipInfo(name)
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
    with zipfile.ZipFile(jar) as archive:
        if 'Premain-Class:' in archive.read('META-INF/MANIFEST.MF').decode():
            raise ValueError('agent manifest forbidden')
    report.update(status='BUILT_NOT_PLUGIN_LOADER_OR_HOST_VALIDATED',
                  sdkSha256=hashlib.sha256(args.sdk.read_bytes()).hexdigest(),
                  pluginSha256=hashlib.sha256(jar.read_bytes()).hexdigest(),
                  forbiddenDependencyScan='PASS_BOUNDED_BYTE_TOKEN_CHECK')
    if args.production_agent is not None:
        agent_pin = hashlib.sha256(args.production_agent.read_bytes()).hexdigest()
        if agent_pin not in {'17b2a71456917776faa5e91fea52acfa886c3d81cf3314c0b824f2dd7a25e295',
                             'aa960bc455ca78f3a8dfbab08a609f878f7d0773fdc1bc88136beed7e9d4b79a'}:
            raise ValueError('unreviewed production compile/runtime dependency')
        check_source = diag / 'ObserverFreePluginLoaderSelfCheck.java'
        report['inputs'][str(check_source.relative_to(root))] = hashlib.sha256(check_source.read_bytes()).hexdigest()
        check_classes = out / 'loader-check-classes'
        check_classes.mkdir()
        subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                        '-cp', str(args.production_agent.resolve()), '-d', str(check_classes), str(check_source)],
                       env=env, check=True)
        check = subprocess.run(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                                str(args.production_agent.resolve()) + os.pathsep + str(check_classes),
                                'dev.turboism.preview.ObserverFreePluginLoaderSelfCheck', str(jar)],
                               env=env, text=True, capture_output=True)
        (out / 'plugin-loader-selfcheck.log').write_text(check.stdout + check.stderr)
        check.check_returncode()
        print(check.stdout, end='')
        report.update(status='PASS_OFFLINE_PRODUCTION_INSPECTOR_AND_PLUGIN_LOADER_NOT_HOST_VALIDATED',
                      productionDependencySha256=agent_pin)
    check_src = out / 'check-src'
    check_classes = out / 'check-classes'
    check_src.mkdir()
    check_classes.mkdir()
    checks = ['NativeObserverFreeAutoConnectSelfCheck.java', 'CommandCpuBoundarySelfCheck.java',
              'MeshResultSnapshotSelfCheck.java', 'ObserverFreeEvidenceWriterSelfCheck.java']
    for name in checks:
        raw = (diag / name).read_bytes()
        report['inputs'][str((diag / name).relative_to(root))] = hashlib.sha256(raw).hexdigest()
        (check_src / name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + raw.decode())
    subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                    '-cp', str(jar), '-d', str(check_classes),
                    *[str(p) for p in sorted(check_src.glob('*.java'))]], env=env, check=True)
    for name in checks:
        command = ['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                   str(jar) + os.pathsep + str(check_classes),
                   'dev.turboism.validation.atlasimage.shadow.' + name.removesuffix('.java')]
        if name == 'ObserverFreeEvidenceWriterSelfCheck.java':
            command.append(str(out / 'writer-run'))
        checked = subprocess.run(command, env=env, text=True, capture_output=True)
        (out / (name.removesuffix('.java') + '.log')).write_text(checked.stdout + checked.stderr)
        checked.check_returncode()
        print(checked.stdout, end='')
    report['actualPackagedHelperChecks'] = checks
    report['limitations'] = ['No native cold startup or producer-free geometry admission yet.',
                             'The loader harness uses the actual production loader outside real premain.',
                             'Full all-version command/publication proof and independent kernel CPU brackets pending.',
                             'No new host task or performance acceptance; T075 original legs unchanged.']
    (out / 'build.json').write_text(json.dumps(report, indent=2) + '\n')
    print(report['status'], report['pluginSha256'])


if __name__ == '__main__':
    main()
