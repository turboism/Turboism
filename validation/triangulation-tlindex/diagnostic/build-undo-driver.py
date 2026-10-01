"""Build a separate undo-trigger diagnostic driver; never publish, prepare or launch a host."""
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
    private static Instrumentation undoInstrumentation;''')
replace('            final DriverConfig config = DriverConfig.fromSystemProperties();', '''            final DriverConfig config = DriverConfig.fromSystemProperties();
            if (config.resourceObservationSeconds == 0 || config.exportProbe || config.shadow
                    || !"5203".equals(config.version)) {
                throw new IllegalStateException("undo diagnostic requires 5203 resource production scene");
            }
            undoInstrumentation = ignoredInstrumentation;
            System.out.println("ATLAS_UNDO_DIAGNOSTIC_ONLY_NOT_PERFORMANCE_ACCEPTANCE");''')
replace('                resourceMarker("operation-start", 1);', '''                AtlasUndoGuard.Snapshot undoBefore = undoSnapshot();
                resourceMarker("operation-start", 1);''')
replace('                observeResources("retained", 1);', '''                diagnosticUndo(undoBefore, 1);
                undoBefore = null;
                observeResources("retained", 1);''')
replace('                    resourceMarker("operation-start", operation);', '''                    undoBefore = undoSnapshot();
                    resourceMarker("operation-start", operation);''')
replace('                    observeResources("retained", operation);', '''                    diagnosticUndo(undoBefore, operation);
                    undoBefore = null;
                    observeResources("retained", operation);''')
replace('        private void resourceMarker(final String phase, final int operation) throws Exception {', '''        private Object undoController() throws Exception {
            for (Class<?> type : undoInstrumentation.getAllLoadedClasses()) {
                if (!type.getName().equals("com.live2d.cubism.CEAppCtrl")) continue;
                java.lang.reflect.Field instance = type.getDeclaredField("_instance");
                instance.setAccessible(true);
                Object value = instance.get(null);
                if (value == null) throw new IllegalStateException("controller not initialized");
                return value;
            }
            throw new IllegalStateException("controller not loaded");
        }

        private AtlasUndoGuard.Snapshot undoSnapshot() throws Exception {
            if (!config.fixtureSha256.equals(sha256(config.fixture))) {
                throw new IllegalStateException("task fixture changed");
            }
            return FixedEdt.call(() -> AtlasNativeUndo.snapshot(undoController(), config.fixture.toFile()),
                FixedEdt.Operation.UNDO_SNAPSHOT, evidence);
        }

        private void diagnosticUndo(final AtlasUndoGuard.Snapshot before, final int operation)
                throws Exception {
            resourceMarker("diagnostic-undo-start", operation);
            final int restoredPosition = FixedEdt.callWithin(() -> {
                Object controller = undoController();
                AtlasUndoGuard.Snapshot applied = AtlasNativeUndo.snapshot(controller, config.fixture.toFile());
                AtlasNativeUndo.undo(controller, config.fixture.toFile(), before, applied);
                return before.position();
            }, FixedEdt.Operation.NATIVE_UNDO, evidence, remainingMillis(runDeadlineNanos));
            if (!config.fixtureSha256.equals(sha256(config.fixture))) {
                throw new IllegalStateException("task fixture changed during undo");
            }
            resourceMarker("diagnostic-undo-end", operation);
            Files.writeString(run.resolve("undo-diagnostic.tsv"), operation + "\\t" + restoredPosition + "\\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }

        private void resourceMarker(final String phase, final int operation) throws Exception {''')
driver.write_text(text)
edt = src / 'FixedEdt.java'
text = edt.read_text()
assert text.count('        MAIN_LOOKUP,') == 1
edt.write_text(text.replace('        MAIN_LOOKUP,', '        UNDO_SNAPSHOT,\n        NATIVE_UNDO,\n        MAIN_LOOKUP,'))
for name in ('AtlasUndoGuard.java', 'AtlasNativeUndo.java'):
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
jar = out / 'undo-diagnostic-driver.jar'
subprocess.run(['jar', '--create', '--file', str(jar), '--manifest', str(manifest), '-C', str(classes), '.'], env=env, check=True)
(out / 'build.json').write_text(json.dumps({'status':'BUILT_NOT_HOST_VALIDATED', 'purpose':'Undo trigger feasibility only; extra undo markers intentionally incompatible with performance window protocol', 'inputs':inputs, 'generatedSources':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(src.glob('*.java'))}, 'driverSha256':hashlib.sha256(jar.read_bytes()).hexdigest()}, indent=2)+'\n')
print(jar)
