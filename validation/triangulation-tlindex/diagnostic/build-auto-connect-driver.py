"""Build a separate native-auto-connect diagnostic driver; never publish, prepare or launch a host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('output', type=Path)
args = parser.parse_args()
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
    private static Instrumentation meshInstrumentation;''')
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
            resourceMarker("mesh-enter-start", 0);
            final java.util.List<String> ids = FixedEdt.callWithin(
                () -> NativeAutoConnect.enter(meshController(), config.fixture.toFile()),
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
                    NativeAutoConnect.connect(meshController(), config.fixture.toFile(), true, true);
                    return null;
                }, FixedEdt.Operation.MESH_CONNECT, evidence, remainingMillis(runDeadlineNanos));
                resourceMarker("auto-connect-returned", cycle);
                final long readyDeadline = Math.min(runDeadlineNanos,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(30));
                java.util.List<NativeAutoConnect.MeshResult> results;
                while (true) {
                    try {
                        results = FixedEdt.callWithin(() -> NativeAutoConnect.capture(
                            meshController(), config.fixture.toFile(), ids),
                            FixedEdt.Operation.MESH_CAPTURE, evidence, remainingMillis(readyDeadline));
                        break;
                    } catch (MeshResultSnapshot.CacheNotReady pending) {
                        if (System.nanoTime() >= readyDeadline) throw pending;
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
            FixedEdt.callWithin(() -> {
                NativeAutoConnect.leave(meshController(), config.fixture.toFile());
                return null;
            }, FixedEdt.Operation.MESH_LEAVE, evidence, remainingMillis(runDeadlineNanos));
            resourceMarker("mesh-cancel-end", 0);
            verifyMeshFixture();
        }

        private void resourceMarker(final String phase, final int operation) throws Exception {''')
driver.write_text(text)
edt = src / 'FixedEdt.java'
text = edt.read_text()
assert text.count('        MAIN_LOOKUP,') == 1
edt.write_text(text.replace('        MAIN_LOOKUP,', '        MESH_ENTER,\n        MESH_CONNECT,\n        MESH_CAPTURE,\n        MESH_LEAVE,\n        MAIN_LOOKUP,'))
for name in ('MeshResultSnapshot.java', 'NativeAutoConnect.java'):
    raw = (diag / name).read_bytes()
    inputs[str((diag / name).relative_to(root))] = hashlib.sha256(raw).hexdigest()
    (src / name).write_text('package dev.turboism.validation.atlasimage.shadow;\n' + raw.decode())
env = dict(os.environ)
for name in ('JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'JDK_JAVAC_OPTIONS', 'CLASSPATH'):
    env.pop(name, None)
subprocess.run(['javac', '--release', '17', '-proc:none', '-implicit:none', '-Xlint:all', '-Werror',
                '-d', str(classes), *map(str, sorted(src.glob('*.java')))], env=env, check=True)
manifest = out / 'MANIFEST.MF'
manifest.write_text('Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.atlasimage.shadow.T040ShadowSceneDriverAgent\n\n')
jar = out / 'auto-connect-diagnostic-driver.jar'
subprocess.run(['jar', '--create', '--file', str(jar), '--manifest', str(manifest), '-C', str(classes), '.'], env=env, check=True)
(out / 'build.json').write_text(json.dumps({'status':'BUILT_NOT_HOST_VALIDATED', 'purpose':'Native auto-connect feasibility only; distinct phases incompatible with atlas performance protocol', 'inputs':inputs, 'generatedSources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(src.glob('*.java'))}, 'driverSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}, indent=2)+'\n')
print(jar)
