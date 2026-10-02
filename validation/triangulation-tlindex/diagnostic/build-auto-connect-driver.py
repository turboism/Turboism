"""Build a separate native-auto-connect diagnostic driver; never publish, prepare or launch a host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
parser.add_argument('--host-profile', choices=('5203', '5302', '5303'), default='5203',
                    help='Freeze one reviewed host version; 53x requires producer recorder')
parser.add_argument('--producer-recorder', action='store_true', help='Bind raw results at the reviewed native producer return boundary')
parser.add_argument('--base-agent', type=Path, help='Pinned production b47f6f47 Agent, shaded ASM/ownership compile dependency only')
parser.add_argument('--cycles', type=int, choices=range(3, 13), default=3,
                    help='Bounded producer memory-stability commands; default protocol remains three')
args = parser.parse_args()
if args.cycles != 3 and not args.producer_recorder:
    raise ValueError('extended cycles require producer recorder')
if args.host_profile in ('5302', '5303') and not args.producer_recorder:
    raise ValueError('53x requires producer recorder; delayed-cache capture is not admitted')
if args.producer_recorder:
    if args.base_agent is None or hashlib.sha256(args.base_agent.read_bytes()).hexdigest() != 'b47f6f47928f46d7fc2acd94223d66e89c80c903a4bd8d2878d5f6cc92e425cb':
        raise ValueError('producer recorder requires the reviewed frozen base')
elif args.base_agent is not None:
    raise ValueError('--base-agent requires --producer-recorder')
root = Path(__file__).resolve().parents[3]
source = root / 'validation/atlas-image-shadow-scene/src/dev/turboism/validation/atlasimage/shadow'
diag = Path(__file__).resolve().parent
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=False)
src = out / 'src'
classes = out / 'classes'
src.mkdir(); classes.mkdir()
inputs = {}
for path in sorted(source.glob('*.java')):
    raw = path.read_bytes()
    inputs[str(path.relative_to(root))] = hashlib.sha256(raw).hexdigest()
    (src / path.name).write_bytes(raw)
driver = src / 'T040ShadowSceneDriverAgent.java'
assert hashlib.sha256(driver.read_bytes()).hexdigest() == 'e1be189f55c355081f7b52a100e709272e75f92d9d3edc2fd68a5337c12cf870', 'unreviewed driver source'
text = driver.read_text()
def replace(old, new):
    global text
    assert text.count(old) == 1, old
    text = text.replace(old, new)
replace('    private T040ShadowSceneDriverAgent() {}', '''    private T040ShadowSceneDriverAgent() {}
    private static Instrumentation meshInstrumentation;
    static boolean mayRetryCaptureWait(FixedEdt.Timeout timeout, long nowNanos, long deadlineNanos) {
        return timeout.operation == FixedEdt.Operation.MESH_CAPTURE
            && timeout.state == FixedEdt.State.TIMED_OUT
            && nowNanos < deadlineNanos && !Thread.currentThread().isInterrupted();
    }''')
replace('            final DriverConfig config = DriverConfig.fromSystemProperties();', '''            final DriverConfig config = DriverConfig.fromSystemProperties();
            if (config.resourceObservationSeconds == 0 || config.exportProbe || config.shadow
                    || !"5203".equals(config.version)) {
                throw new IllegalStateException("auto-connect diagnostic requires 5203 resource production scene");
            }
            meshInstrumentation = ignoredInstrumentation;
            System.out.println("AUTO_CONNECT_DIAGNOSTIC_ONLY_NOT_ATLAS_PERFORMANCE_ACCEPTANCE");''')
first = text.index('                observeResources("baseline", 0);')
last = text.index('                final Map<String, String> freeze;', first)
text = text[:first] + '''                diagnosticAutoConnect();

''' + text[last:]
replace('        private void resourceMarker(final String phase, final int operation) throws Exception {', '''        private Object meshController() throws Exception {
            for (Class<?> type : meshInstrumentation.getAllLoadedClasses()) {
                if (!type.getName().equals("com.live2d.cubism.CEAppCtrl")) continue;
                java.lang.reflect.Field instance = type.getDeclaredField("_instance");
                instance.setAccessible(true);
                Object value = instance.get(null);
                if (value == null) throw new IllegalStateException("controller not initialized");
                return value;
            }
            throw new IllegalStateException("controller not loaded");
        }

        private void verifyMeshFixture() throws Exception {
            if (!config.fixtureSha256.equals(sha256(config.fixture))) {
                throw new IllegalStateException("task fixture changed during auto-connect diagnostic");
            }
        }

        private void diagnosticAutoConnect() throws Exception {
            verifyMeshFixture();
            Files.writeString(run.resolve("auto-connect-protocol.properties"),
                "scope=DIAGNOSTIC_ONLY\\nrebuild=true\\npreserveBorder=true\\ncycles=3\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(run.resolve("auto-connect-capture-wait.tsv"),
                "cycle\\tepochMillis\\treason\\tdetail\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            final NativeAutoConnect.Binding binding = FixedEdt.callWithin(
                () -> NativeAutoConnect.binding(meshController()),
                FixedEdt.Operation.MESH_BIND, evidence, remainingMillis(runDeadlineNanos));
            binding.verifyFixture(config.fixture.toFile());
            resourceMarker("mesh-enter-start", 0);
            final java.util.List<String> ids = FixedEdt.callWithin(
                () -> NativeAutoConnect.enter(meshController(), binding),
                FixedEdt.Operation.MESH_ENTER, evidence, remainingMillis(runDeadlineNanos));
            resourceMarker("mesh-enter-end", 0);
            Files.writeString(run.resolve("auto-connect-selected.txt"),
                ids.stream().map(id -> java.util.Base64.getEncoder().encodeToString(
                    id.getBytes(StandardCharsets.UTF_8))).collect(java.util.stream.Collectors.joining("\\n")) + "\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            observeResources("mesh-baseline", 0);
            for (int cycle = 1; cycle <= 3; cycle++) {
                verifyMeshFixture();
                resourceMarker("auto-connect-start", cycle);
                FixedEdt.callWithin(() -> {
                    NativeAutoConnect.connect(meshController(), binding, true, true);
                    return null;
                }, FixedEdt.Operation.MESH_CONNECT, evidence, remainingMillis(runDeadlineNanos));
                resourceMarker("auto-connect-returned", cycle);
                final long readyDeadline = Math.min(runDeadlineNanos,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
                java.util.List<NativeAutoConnect.MeshResult> results;
                String lastCacheFailure = null;
                while (true) {
                    if (System.nanoTime() >= readyDeadline) {
                        recordCaptureWait(cycle, "READY_DEADLINE_EXPIRED", "No further capture dispatched");
                        throw new IllegalStateException("mesh capture ready deadline expired");
                    }
                    try {
                        results = FixedEdt.callWithin(() -> NativeAutoConnect.capture(
                            meshController(), binding, ids),
                            FixedEdt.Operation.MESH_CAPTURE, evidence, remainingMillis(readyDeadline));
                        if (System.nanoTime() >= readyDeadline) {
                            recordCaptureWait(cycle, "READY_DEADLINE_EXPIRED", "Query returned after ready deadline");
                            throw new IllegalStateException("mesh capture returned after ready deadline");
                        }
                        break;
                    } catch (MeshResultSnapshot.CacheNotReady pending) {
                        if (!pending.getMessage().equals(lastCacheFailure)) {
                            recordCaptureWait(cycle, "CACHE_NOT_READY", pending.getMessage());
                            lastCacheFailure = pending.getMessage();
                        }
                        if (System.nanoTime() >= readyDeadline) throw pending;
                        sleep(100L);
                    } catch (FixedEdt.Timeout timeout) {
                        final boolean retry = mayRetryCaptureWait(timeout, System.nanoTime(), readyDeadline);
                        recordCaptureWait(cycle, retry ? "CANCELLED_BEFORE_START_RETRY" : "TIMEOUT_NOT_RETRIED",
                            timeout.getMessage());
                        if (!retry) throw timeout;
                        sleep(100L);
                    }
                }
                resourceMarker("auto-connect-end", cycle);
                final StringBuilder output = new StringBuilder();
                for (NativeAutoConnect.MeshResult result : results) {
                    MeshResultSnapshot.Result snapshot = result.result();
                    output.append(cycle).append('\\t').append(java.util.Base64.getEncoder().encodeToString(
                        result.sourceId().getBytes(StandardCharsets.UTF_8))).append('\\t')
                        .append(snapshot.pointCount()).append('\\t').append(snapshot.edgeVersion()).append('\\t')
                        .append(snapshot.positionValues()).append('\\t').append(snapshot.indexValues()).append('\\t')
                        .append(snapshot.positionsSha256()).append('\\t').append(snapshot.indicesSha256()).append('\\n');
                }
                Files.writeString(run.resolve("auto-connect-results.tsv"), output.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                verifyMeshFixture();
                observeResources("mesh-retained", cycle);
            }
            resourceMarker("mesh-cancel-start", 0);
            final int cancelAnswers = FixedEdt.callWithin(
                () -> NativeAutoConnect.leave(meshController(), binding, main),
                FixedEdt.Operation.MESH_LEAVE, evidence,
                Math.min(remainingMillis(runDeadlineNanos), TimeUnit.SECONDS.toMillis(30)));
            resourceMarker("mesh-cancel-end", 0);
            Files.writeString(run.resolve("auto-connect-cancel.properties"),
                "status=PASS\\nresource=CUB3-0009\\nanswer=YES_CANCEL_MESH_EDIT\\nanswers=" + cancelAnswers + "\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            verifyMeshFixture();
        }

        private void recordCaptureWait(int cycle, String reason, String detail) throws Exception {
            Files.writeString(run.resolve("auto-connect-capture-wait.tsv"),
                cycle + "\\t" + System.currentTimeMillis() + "\\t" + reason + "\\t"
                    + detail.replace('\\t', ' ').replace('\\r', ' ').replace('\\n', ' ') + "\\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        }

        private void resourceMarker(final String phase, final int operation) throws Exception {''')
if args.producer_recorder:
    replace('() -> NativeAutoConnect.enter(meshController(), binding),',
            '() -> NativeProducerAutoConnect.enter(meshController(), binding),')
    replace('    private static Instrumentation meshInstrumentation;', '''    private static Instrumentation meshInstrumentation;
    private static MeshProducerWeave producerWeave;''')
    replace('            meshInstrumentation = ignoredInstrumentation;', '''            meshInstrumentation = ignoredInstrumentation;
            producerWeave = MeshProducerWeave.install(meshInstrumentation, config.version);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> producerWeave.close(),
                "native-producer-recorder-shutdown"));''')
    replace('''            System.err.println("ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED "
                + failure.getClass().getSimpleName());''', '''            System.err.println("ATLAS_IMAGE_SHADOW_DRIVER_BLOCKED "
                + failure.getClass().getSimpleName() + ":" + failure.getMessage());
            failure.printStackTrace(System.err);''')
    replace('"scope=DIAGNOSTIC_ONLY\\nrebuild=true\\npreserveBorder=true\\ncycles=3\\n",',
            '"scope=DIAGNOSTIC_ONLY\\nrecorder=PRODUCER_ENTRY_RETURN_V1\\nrebuild=true\\npreserveBorder=true\\ncycles=3\\n",')
    replace('            observeResources("mesh-baseline", 0);', '''            Files.writeString(run.resolve("auto-connect-producer-results.tsv"),
                "cycle\\tinvocation\\tsourceIdBase64\\tthreadId\\tstartedNanos\\treturnedNanos\\tstatus\\tpointCount\\tedgeVersion\\tpositionValues\\tindexValues\\tpositionsSha256\\tindicesSha256\\tfailureBase64\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(run.resolve("auto-connect-producer-status.properties"),
                "recorder=PRODUCER_ENTRY_RETURN_V1\\ninitialHookStatus=" + producerWeave.status() + "\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            observeResources("mesh-baseline", 0);''')
    first = text.index('                FixedEdt.callWithin(() -> {\n                    NativeAutoConnect.connect(')
    last = text.index('                verifyMeshFixture();\n                observeResources("mesh-retained", cycle);', first)
    text = text[:first] + '''                final int boundCycle = cycle;
                final NativeProducerAutoConnect.Observation observation = FixedEdt.callWithin(
                    () -> NativeProducerAutoConnect.connect(meshController(), binding, ids, boundCycle),
                    FixedEdt.Operation.MESH_CONNECT, evidence, remainingMillis(runDeadlineNanos));
                resourceMarker("auto-connect-returned", cycle);
                final StringBuilder output = new StringBuilder();
                for (MeshProducerRecorder.Event event : observation.events()) {
                    output.append(event.cycle()).append('\\t').append(event.invocation()).append('\\t')
                        .append(java.util.Base64.getEncoder().encodeToString(event.sourceId().getBytes(StandardCharsets.UTF_8)))
                        .append('\\t').append(event.threadId()).append('\\t').append(event.startedNanos())
                        .append('\\t').append(event.returnedNanos()).append('\\t');
                    MeshResultSnapshot.Result snapshot = event.result();
                    if (snapshot != null) {
                        output.append("PASS").append('\\t').append(snapshot.pointCount()).append('\\t')
                            .append(snapshot.edgeVersion()).append('\\t').append(snapshot.positionValues()).append('\\t')
                            .append(snapshot.indexValues()).append('\\t').append(snapshot.positionsSha256()).append('\\t')
                            .append(snapshot.indicesSha256()).append('\\t');
                    } else {
                        output.append("FAIL\\t-1\\t-1\\t-1\\t-1\\t\\t\\t")
                            .append(java.util.Base64.getEncoder().encodeToString(event.failure().getBytes(StandardCharsets.UTF_8)));
                    }
                    output.append('\\n');
                }
                Files.writeString(run.resolve("auto-connect-producer-results.tsv"), output.toString(),
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                Files.writeString(run.resolve("auto-connect-producer-status.properties"),
                    "cycle." + cycle + ".status=" + (observation.complete() ? "PASS" : "FAIL") + "\\n"
                    + "cycle." + cycle + ".nativeCommandReturned=" + observation.nativeCommandReturned() + "\\n"
                    + "cycle." + cycle + ".hookStatus=" + producerWeave.status() + "\\n"
                    + "cycle." + cycle + ".failureBase64=" + java.util.Base64.getEncoder().encodeToString(
                        observation.failure().getBytes(StandardCharsets.UTF_8)) + "\\n",
                    StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                if (!observation.complete()) throw new IllegalStateException("native producer observation failed: " + observation.failure());
                producerWeave.requireInstalled();
                resourceMarker("auto-connect-end", cycle);
''' + text[last:]
    first = text.index('                final StringBuilder output = new StringBuilder();\n                for (MeshProducerRecorder.Event')
    last = text.index('                if (!observation.complete())', first)
    writer = text[first:last].replace('producerWeave.status()', 'hookStatus')
    text = text[:first] + '                persistProducerObservation(run, observation, cycle, producerWeave.status());\n' + text[last:]
    writer = '\n'.join(line[8:] if line.startswith('        ') else line for line in writer.splitlines())
    replace('    static boolean mayRetryCaptureWait(', '''    static void persistProducerObservation(Path run, NativeProducerAutoConnect.Observation observation,
            int cycle, String hookStatus) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("producer evidence I/O requires driver thread");
''' + writer + '''
    }

    static boolean mayRetryCaptureWait(''')
if args.host_profile == '5302':
    # Preserve reviewed5203 generation. This separate build changes admission
    # and its post-measurement cancel resource; commands/recorder/windows stay exact.
    replace('!"5203".equals(config.version)', '!"5302".equals(config.version)')
    replace('auto-connect diagnostic requires 5203 resource production scene',
            'auto-connect diagnostic requires 5302 resource production scene')
    replace('resource=CUB3-0009', 'resource=CUB3-4362')
elif args.host_profile == '5303':
    # Real owned premain ordering is independently proven. Keep5303 shadow and
    # its immutable freeze; only the explicit producer diagnostic admits this branch.
    replace('config.exportProbe || config.shadow\n                    || !"5203".equals(config.version)',
            'config.exportProbe || !config.shadow\n                    || !"5303".equals(config.version)')
    replace('auto-connect diagnostic requires 5203 resource production scene',
            'auto-connect diagnostic requires 5303 resource production scene with shadow')
    replace('resource=CUB3-0009', 'resource=CUB3-4362')
if args.cycles != 3:
    # A native backup can hold the EDT beyond its five-second queue barrier.
    # Retrying only cancelled-before-start callbacks cannot duplicate a command.
    replace('    static boolean mayRetryCaptureWait(', '''    static <T> T callExtendedMeshCommand(java.util.concurrent.Callable<T> action,
            StageEvidence evidence, long completionMillis) throws Exception {
        final long queueDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (true) {
            try {
                return FixedEdt.callWithin(action, FixedEdt.Operation.MESH_CONNECT, evidence, completionMillis);
            } catch (FixedEdt.Timeout timeout) {
                if (!mayRetryExtendedMeshWait(timeout, System.nanoTime(), queueDeadline)) throw timeout;
                Thread.sleep(100L);
            }
        }
    }
    static boolean mayRetryExtendedMeshWait(FixedEdt.Timeout timeout, long now, long deadline) {
        return timeout.operation == FixedEdt.Operation.MESH_CONNECT
            && timeout.state == FixedEdt.State.TIMED_OUT && now < deadline
            && !Thread.currentThread().isInterrupted();
    }
    static boolean mayRetryCaptureWait(''')
    replace('final NativeProducerAutoConnect.Observation observation = FixedEdt.callWithin(',
            'final NativeProducerAutoConnect.Observation observation = callExtendedMeshCommand(')
    replace('FixedEdt.Operation.MESH_CONNECT, evidence, remainingMillis(runDeadlineNanos));',
            'evidence, remainingMillis(runDeadlineNanos));')
    assert text.count('for (int cycle = 1; cycle <= 3; cycle++)') == 1
    assert text.count('cycles=3\\n') == 1
    text = text.replace('for (int cycle = 1; cycle <= 3; cycle++)',
                        f'for (int cycle = 1; cycle <= {args.cycles}; cycle++)')
    text = text.replace('cycles=3\\n', f'cycles={args.cycles}\\n')
driver.write_text(text)
edt = src / 'FixedEdt.java'
text = edt.read_text()
assert text.count('        MAIN_LOOKUP,') == 1
edt.write_text(text.replace('        MAIN_LOOKUP,', '        MESH_BIND,\n        MESH_ENTER,\n        MESH_CONNECT,\n        MESH_CAPTURE,\n        MESH_LEAVE,\n        MAIN_LOOKUP,'))
helpers = ['MeshResultSnapshot.java', 'NativeAutoConnect.java', 'NativeCancelPrompt.java']
if args.producer_recorder:
    helpers += ['MeshProducerRecorder.java', 'MeshProducerWeave.java', 'NativeProducerAutoConnect.java']
    inputs[str(args.base_agent.resolve())] = hashlib.sha256(args.base_agent.read_bytes()).hexdigest()
for name in helpers:
    raw = (diag / name).read_bytes()
    inputs[str((diag / name).relative_to(root))] = hashlib.sha256(raw).hexdigest()
    helper_text = raw.decode()
    if name == 'NativeAutoConnect.java' and args.host_profile in ('5302', '5303'):
        # Pinned5302 editCancel uses this resource; its reviewed UUOption
        # Yes/Cancel shape and all context/lifecycle guards remain unchanged.
        assert helper_text.count('"CUB3-0009"') == 2
        helper_text = helper_text.replace('"CUB3-0009"', '"CUB3-4362"')
    (src / name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + helper_text)
env = dict(os.environ)
for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
    env.pop(name, None)
dependency = ['-cp', str(args.base_agent.resolve())] if args.producer_recorder else []
subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror', *dependency,
                '-d', str(classes), *map(str, sorted(src.glob('*.java')))], env=env, check=True)
check_source = diag / 'CaptureWaitSelfCheck.java'
inputs[str(check_source.relative_to(root))] = hashlib.sha256(check_source.read_bytes()).hexdigest()
check_src = out / 'selfcheck-src'
check_classes = out / 'selfcheck-classes'
check_src.mkdir(); check_classes.mkdir()
check_java = check_src / check_source.name
check_java.write_text('package dev.turboism.validation.atlasimage.shadow;\n' + check_source.read_text())
subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                '-cp', str(classes), '-d', str(check_classes), str(check_java)], env=env, check=True)
check = subprocess.run(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                        str(classes) + os.pathsep + str(check_classes),
                        'dev.turboism.validation.atlasimage.shadow.CaptureWaitSelfCheck'],
                       env=env, capture_output=True, text=True)
(out / 'capture-wait-selfcheck.log').write_text(check.stdout + check.stderr)
check.check_returncode()
print(check.stdout, end='')
if args.producer_recorder:
    writer_check = diag / 'ProducerDriverSelfCheck.java'
    inputs[str(writer_check.relative_to(root))] = hashlib.sha256(writer_check.read_bytes()).hexdigest()
    check_java = check_src / writer_check.name
    check_java.write_text('package dev.turboism.validation.atlasimage.shadow;\n' + writer_check.read_text())
    cp = str(classes) + os.pathsep + str(args.base_agent.resolve())
    subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                    '-cp', cp, '-d', str(check_classes), str(check_java)], env=env, check=True)
    check = subprocess.run(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                            cp + os.pathsep + str(check_classes),
                            'dev.turboism.validation.atlasimage.shadow.ProducerDriverSelfCheck', str(out / 'writer-selfcheck-run')],
                           env=env, capture_output=True, text=True)
    (out / 'writer-selfcheck.log').write_text(check.stdout + check.stderr)
    check.check_returncode()
    print(check.stdout, end='')
if args.cycles != 3:
    queue_check = diag / 'ExtendedMeshWaitSelfCheck.java'
    inputs[str(queue_check.relative_to(root))] = hashlib.sha256(queue_check.read_bytes()).hexdigest()
    check_java = check_src / queue_check.name
    check_java.write_text('package dev.turboism.validation.atlasimage.shadow;\n' + queue_check.read_text())
    subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                    '-cp', str(classes), '-d', str(check_classes), str(check_java)], env=env, check=True)
    check = subprocess.run(['java', '-Xverify:all', '-Djava.awt.headless=true', '-cp',
                            str(classes) + os.pathsep + str(check_classes),
                            'dev.turboism.validation.atlasimage.shadow.ExtendedMeshWaitSelfCheck'],
                           env=env, capture_output=True, text=True)
    (out / 'extended-queue-selfcheck.log').write_text(check.stdout + check.stderr)
    check.check_returncode()
    print(check.stdout, end='')
manifest = out / 'MANIFEST.MF'
manifest.write_text('Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent\n\n')
jar = out / 'auto-connect-diagnostic-driver.jar'
subprocess.run(['jar', '--create', '--file', str(jar), '--manifest', str(manifest), '-C', str(classes), '.'], env=env, check=True)
(out / 'build.json').write_text(json.dumps({'status':'BUILT_NOT_HOST_VALIDATED', 'hostProfile':args.host_profile, 'cycles':args.cycles, 'recorder':'PRODUCER_ENTRY_RETURN_V1' if args.producer_recorder else 'DELAYED_CACHE_R4', 'purpose':'Native auto-connect feasibility only; distinct phases incompatible with atlas performance protocol', 'inputs':inputs, 'generatedSources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(src.glob('*.java'))}, 'driverSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}, indent=2)+'\n')
print(jar)
