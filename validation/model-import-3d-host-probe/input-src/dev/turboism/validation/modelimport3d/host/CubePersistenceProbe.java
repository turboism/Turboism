package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.command.*;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.ui.UserFileAccessService;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import javax.swing.SwingUtilities;

/** Real importer and native file command. Independent pair verification decides persistence acceptance. */
final class CubePersistenceProbe {
    private CubePersistenceProbe() { }

    static void save(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, AuthoringTransactionService transactions, EditorCommandService commands,
            UserFileAccessService files, Path state, Map<String,Object> data) throws Exception {
        requireWorker();
        var prepared = CubePersistenceInput.prepare(glb);
        start(data, "cube-persistence-save", prepared);
        var root = edt(history::get);
        var initial = edt(() -> WholeImportProbe.state(current.get()));
        data.put("initialState", initial);
        data.put("initialHistory", WholeImportProbe.historyData(root));
        Throwable primary = null;
        try {
            edt(() -> {
                NativeAssertions.require(current.get().drawables().all().isEmpty(), "save requires blank document");
                var resized = commands.execute(new EditorCanvasSettingsRequest(1024, 1024));
                NativeAssertions.require(resized.status() == EditorCommandResult.Status.EXECUTED, "canvas setup failed");
                return null;
            });
            var before = edt(history::get);
            var result = edt(() -> SurfaceImport.apply(prepared, current, transactions));
            NativeAssertions.require(result.outcome() == AuthoringTransactionOutcome.COMMITTED, "cube import failed: " + result.outcome());
            var imported = result.value().orElseThrow();
            var committed = edt(history::get);
            NativeAssertions.require(committed.position() == before.position() + 1, "cube import requires one native history entry");
            data.put("importHistoryBefore", WholeImportProbe.historyData(before));
            data.put("importHistoryAfter", WholeImportProbe.historyData(committed));
            data.put("meshIds", imported.geometry().patches().stream().map(p -> p.meshId().value()).toList());
            data.put("partId", imported.partId().value());
            var captured = edt(() -> capture(current.get()));
            data.put("beforeSave", captured);
            var rawDisplay=edt(()->commands.execute(EditorCommand.SHOW_MODEL_BY_RAW_IMAGE));
            data.put("rawDisplayStatus",rawDisplay.status().name());
            NativeAssertions.require(rawDisplay.status()==EditorCommandResult.Status.EXECUTED,"raw-image display failed");
            var afterRaw=edt(()->capture(current.get()));data.put("afterRawDisplay",afterRaw);
            NativeAssertions.require(afterRaw.equals(captured),"native raw-image UV reconstruction changed source mapping");
            data.put("beforeSaveFrames", CubePersistenceRender.sample("before-save", prepared, current, history, restore, state, data));
            String documentBinding = committed.documentBindingId();
            var artifact = CubePersistenceFile.save(files, commands, state, () -> {
                current.get();
                NativeAssertions.require(history.get().documentBindingId().equals(documentBinding), "save document binding changed");
                NativeAssertions.require(capture(current.get()).equals(captured), "authored model changed before SAVE_AS");
            });
            data.put("artifact", artifact.relativePath());
            data.put("artifactBytes", artifact.bytes());
            data.put("artifactSha256", artifact.sha256());
            var after = edt(() -> capture(current.get()));
            data.put("afterSave", after);
            NativeAssertions.require(after.equals(captured), "SAVE_AS changed authored cube state");
            data.put("afterSaveFrames", CubePersistenceRender.sample("after-save", prepared, current, history, restore, state, data));
            data.put("renderStatus", "CAPTURED");
            data.put("status", "PASS");
        } catch (Exception | Error failure) {
            primary = failure; data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        } finally {
            try {
                edt(() -> { restore.accept(root.position()); return null; });
                var restored = edt(() -> WholeImportProbe.state(current.get()));
                data.put("restoredState", restored);
                data.put("restoredHistory", WholeImportProbe.historyData(edt(history::get)));
                NativeAssertions.require(restored.equals(initial), "cube save cleanup changed initial document content");
                data.put("finallyRestored", true);
            } catch (Exception | Error failure) {
                data.put("status", "FAIL"); data.put("finallyRestored", false);
                if (primary != null) primary.addSuppressed(failure); else throw failure;
            }
        }
    }

    /** Launcher seed is the immutable saved artifact; this method never imports or authors. */
    static void reopen(byte[] glb, Supplier<CubismModel> current, Supplier<HistorySnapshot> history,
            IntConsumer restore, Path state, Map<String,Object> data) throws Exception {
        requireWorker();
        var prepared = CubePersistenceInput.prepare(glb);
        start(data, "cube-persistence-reopen", prepared);
        try {
            var before = edt(history::get);
            var observed = edt(() -> capture(current.get()));
            data.put("reopened", observed);
            data.put("historyBefore", WholeImportProbe.historyData(before));
            data.put("reopenFrames", CubePersistenceRender.sample("reopen", prepared, current, history, restore, state, data));
            NativeAssertions.require(observed.equals(edt(() -> capture(current.get()))), "reopen render changed document");
            data.put("renderStatus", "CAPTURED");
            var after = edt(history::get);
            data.put("historyAfter", WholeImportProbe.historyData(after));
            NativeAssertions.require(CubePersistenceRender.restoredHistory(before,after), "reopen observation changed native history");
            data.put("status", "PASS");
        } catch (Exception | Error failure) {
            data.put("status", "FAIL"); data.put("failure", failure.toString()); throw failure;
        }
    }

    static Map<String,Object> capture(CubismModel model) {
        NativeAssertions.require(SwingUtilities.isEventDispatchThread(), "capture must run on EDT");
        NativeAssertions.require(model.drawables().all().size() == 6 && model.canvas().widthPixels() == 1024
            && model.canvas().heightPixels() == 1024, "cube document shape/canvas differs");
        for (var id : List.of(CubeGeometryProbe.YAW, CubeGeometryProbe.PITCH)) {
            var parameter = model.parameters().all().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
            NativeAssertions.require(parameter.getMinimumValue() == -30 && parameter.getMaximumValue() == 30
                && parameter.getDefaultValue() == 0 && parameter.getValue() == 0, "cube parameter definition/pose differs");
        }
        var snapshots = new ArrayList<Map<String,Object>>();
        for (var mesh : model.drawables().all()) {
            var snapshot = mesh.authoringSnapshot();
            NativeAssertions.require(snapshot.axes().size() == 2 && snapshot.axes().stream().allMatch(a -> a.keys().size() == 45)
                && snapshot.cells().size() == 2025 && snapshot.forms().size() == 2025 && mesh.culling() && mesh.visible(),
                "cube dense bindings or visibility missing");
            NativeAssertions.require(model.textures().textureInput(mesh.id()).isPresent(), "cube material binding missing");
            snapshots.add(NativeAssertions.snapshot(snapshot));
        }
        return Map.of("state", WholeImportProbe.persistenceState(model), "snapshots", List.copyOf(snapshots));
    }

    private static void start(Map<String,Object> data, String mode, SurfaceImport.Prepared prepared) {
        var profile=CubePersistenceInput.identify(prepared.inputSha256);
        data.put("schemaVersion", "mi3d-cube-persistence-v1"); data.put("mode", mode);
        data.put("status", "RUNNING"); data.put("failure", null);
        data.put("inputSha256", prepared.inputSha256);
        data.put("textureLayout", profile.layout);
        data.put("registeredImageSize", List.of(profile.imageWidth,profile.imageHeight));
        data.put("renderUvTransform", profile.uvTransform);
        var process = ProcessHandle.current();
        data.put("process", Map.of("pid", process.pid(), "startEpochMillis",
            process.info().startInstant().orElseThrow().toEpochMilli()));
        data.put("renderStatus", "NOT_EVALUATED");
        data.put("observedImageBytes", "UNAVAILABLE");
    }
    private static void requireWorker() {
        NativeAssertions.require(!SwingUtilities.isEventDispatchThread(), "persistence probe must not block EDT");
    }
    private static <T>T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
