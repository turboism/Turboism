package dev.turboism.validation;

import java.awt.Frame;
import java.awt.event.WindowEvent;
import java.lang.instrument.Instrumentation;
import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/** Test-only phase-marked native camera and document-close workload. No product hooks or GC requests. */
public final class NativeResourceHostAgent {
    private static final String HOST_SHA = "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21";
    private static final Properties RESULT = new Properties();
    private static Path directory;
    private static volatile boolean aborted;
    private static volatile WeakReference<?> closedDocument;
    private static volatile NativeImageRetainObservation.Snapshot closedImageCohort;
    private static volatile NativeCloseOwnershipObservation.WeakPair closedWeak;

    public static void premain(String args, Instrumentation instrumentation) {
        Path home = Path.of(System.getProperty("turboism.validation.textureUpload.home"));
        NativeMemoryObservation.startLoading(home);
        Thread worker = new Thread(() -> run(home, instrumentation), "turboism-resource-validation");
        worker.setDaemon(true);
        worker.start();
    }

    private static void run(Path home, Instrumentation instrumentation) {
        directory = home.resolve("state/resource-workload");
        Recording recording = null;
        try {
            Files.createDirectories(directory);
            RESULT.setProperty("schemaVersion", "1");
            RESULT.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
            RESULT.setProperty("profile", System.getProperty("turboism.validation.resource.profile", "false"));
            require(Runtime.version().feature() == 17, "requires exact host JVM17");
            require(Integer.getInteger("turboism.validation.textureUpload.memoryIdleSeconds", 0) == 300,
                    "resource protocol requires 300s sampler window");
            for (String name : List.of("imageArchiveReuse", "floatArrayParseCache", "textureUploadPreparation", "warpPositionProjection")) {
                require(!Boolean.getBoolean("turboism.optimization." + name), "diagnostic baseline requires optimizations off");
            }
            if (Boolean.getBoolean("turboism.validation.resource.profile")) {
                recording = new Recording(Configuration.getConfiguration("profile"));
                recording.setMaxSize(128L * 1024 * 1024);
                recording.setToDisk(true);
                recording.start();
            }
            long deadline = System.nanoTime() + 600_000_000_000L;
            while (!Files.isRegularFile(directory.resolve("start.flag")) && System.nanoTime() < deadline) Thread.sleep(100);
            require(Files.isRegularFile(directory.resolve("start.flag")), "runtime-ready trigger absent");
            Class<?> appType = null;
            while (appType == null && System.nanoTime() < deadline) {
                for (Class<?> type : instrumentation.getAllLoadedClasses()) {
                    if (type.getName().equals("com.live2d.cubism.CEAppCtrl")) appType = type;
                }
                if (appType == null) Thread.sleep(100);
            }
            require(appType != null, "host controller absent");
            Path jar = Path.of(appType.getProtectionDomain().getCodeSource().getLocation().toURI());
            try (var in = Files.newInputStream(jar)) {
                var digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[65536];
                for (int n; (n = in.read(buffer)) >= 0;) digest.update(buffer, 0, n);
                require(HOST_SHA.equals(HexFormat.of().formatHex(digest.digest())), "host artifact differs");
            }
            ClassLoader loader = appType.getClassLoader();
            NativeAtlasWorkflow.awaitTaskDocument(loader);
            Class<?> verifiedApp = appType;
            Frame frame = (Frame) NativeAtlasWorkflow.onEdt(() -> {
                Frame selected = null;
                String name = System.getProperty("turboism.validation.imageArchive.fixtureName", "");
                require(!name.isBlank(), "fixture name absent");
                for (Frame candidate : Frame.getFrames()) if (candidate.isVisible() && candidate.getTitle().contains(name)) {
                    require(selected == null, "ambiguous task window"); selected = candidate;
                }
                require(selected != null, "task window absent");
                return selected;
            });
            normalizeNativeZoom(verifiedApp, frame);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread driver = new Thread(() -> {
                try {
                    long waitUntil = System.nanoTime() + 15_000_000_000L;
                    Path ready = home.resolve("state/texture-upload/memory/ready.properties");
                    while (!Files.isRegularFile(ready) && System.nanoTime() < waitUntil) Thread.sleep(50);
                    require(Files.isRegularFile(ready), "memory-ready marker absent");
                    phase("idle.begin");
                    Thread.sleep(30_000);
                    ClosedHandles handles = cameraAndClose(verifiedApp, frame);
                    WeakReference<?> document = handles.document();
                    closedDocument = document;
                    closedWeak = handles.pair();
                    // Strong document/view/manager locals die with cameraAndClose; no heap walk or forced collection.
                    phase("closed.begin");
                    Thread.sleep(120_000);
                    observeOwnershipClosed(verifiedApp, frame, "closed120", closedWeak);
                    RESULT.setProperty("documentWeakCleared", Boolean.toString(document.refersTo(null)));
                    if (closedImageCohort != null) closedImageCohort.writeWeak(RESULT, "retain.closed");
                    NativeAtlasWorkflow.onEdt(() -> {
                        Object app = verifiedApp.getMethod("access$get_instance$cp").invoke(null);
                        require(((List<?>) call(app, "getAllDocs")).isEmpty(), "document reopened during closed window");
                        return null;
                    });
                    phase("closed.end");
                } catch (Throwable problem) { failure.set(problem); }
            }, "turboism-native-camera-workload");
            driver.setDaemon(true);
            driver.start();
            NativeMemoryObservation.observe(home);
            driver.join(1000);
            if (closedDocument != null) {
                RESULT.setProperty("documentWeakClearedAtSamplerEnd", Boolean.toString(closedDocument.refersTo(null)));
                RESULT.setProperty("weakFinal.epochMillis", Long.toString(System.currentTimeMillis()));
            }
            if (closedImageCohort != null) closedImageCohort.writeWeak(RESULT, "retain.final");
            RESULT.setProperty("retain.attributionStatus",
                    "COMPLETE".equals(RESULT.getProperty("retain.beforeZoom.status"))
                    && "COMPLETE".equals(RESULT.getProperty("retain.beforeClose.status")) ? "COMPLETE" : "INCOMPLETE");
            if (closedWeak != null) {
                observeOwnershipClosed(verifiedApp, frame, "closedFinal", closedWeak);
            }
            boolean ownershipComplete = true;
            for (String name : List.of("idle", "beforeClose", "closed120", "closedFinal")) {
                ownershipComplete &= "COMPLETE".equals(RESULT.getProperty("ownership." + name + ".status"));
            }
            RESULT.setProperty("ownership.attributionStatus", ownershipComplete ? "COMPLETE" : "INCOMPLETE");
            require(!driver.isAlive(), "workload exceeded bounded observation window");
            if (failure.get() != null) throw new IllegalStateException("native workload failed", failure.get());
            if (recording != null) { recording.stop(); recording.dump(directory.resolve("profile.jfr")); }
            RESULT.setProperty("status", "PASS");
            store();
            SwingUtilities.invokeLater(() -> {
                if (frame.isVisible()) frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING));
            });
        } catch (Throwable problem) {
            Throwable root = problem;
            while (root.getCause() != null) root = root.getCause();
            RESULT.setProperty("status", "FAIL"); RESULT.setProperty("failure", root.toString());
            root.printStackTrace(System.err);
            try { store(); } catch (Exception ignored) { }
        } finally {
            aborted = true;
            if (recording != null) recording.close();
        }
    }

    private static void normalizeNativeZoom(Class<?> appType, Frame frame) throws Exception {
        Object[] owned = (Object[]) NativeAtlasWorkflow.onEdt(() -> {
            Object app = appType.getMethod("access$get_instance$cp").invoke(null);
            Object doc = call(app, "getCurrentDoc"), view = call(app, "getCurrentViewContext");
            require(((List<?>) call(app, "getAllDocs")).size() == 1 && doc != null && view != null, "normalization requires one task model");
            require(call(doc, "getFile") instanceof java.io.File file && file.getName().equals(
                    System.getProperty("turboism.validation.imageArchive.fixtureName")), "normalization file identity differs");
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "refusing to normalize dirty document");
            checkIdentity(app, doc, view, frame);
            RESULT.setProperty("cameraScale.beforeNormalization", Float.toString(cameraScale(view)));
            return new Object[]{app, doc, view, undoState(doc)};
        });
        for (String command : List.of("command_zoomIn", "command_zoomOut")) {
            NativeAtlasWorkflow.onEdt(() -> {
                checkIdentity(owned[0], owned[1], owned[2], frame);
                call(owned[0], command);
                return null;
            });
            Thread.sleep(1000);
        }
        NativeAtlasWorkflow.onEdt(() -> {
            checkIdentity(owned[0], owned[1], owned[2], frame);
            require(owned[3].equals(undoState(owned[1])) && !Boolean.TRUE.equals(call(owned[1], "isModifiedAfterSaving")),
                    "normalization changed authoring state");
            RESULT.setProperty("cameraScale.normalized", Float.toString(cameraScale(owned[2])));
            return null;
        });
    }

    private static ClosedHandles cameraAndClose(Class<?> appType, Frame frame) throws Exception {
        Object[] identity = (Object[]) NativeAtlasWorkflow.onEdt(() -> {
            Object app = appType.getMethod("access$get_instance$cp").invoke(null);
            Object doc = call(app, "getCurrentDoc"), view = call(app, "getCurrentViewContext");
            require(((List<?>) call(app, "getAllDocs")).size() == 1, "requires exactly one task document");
            require(doc != null && doc.getClass().getName().equals("com.live2d.cubism.doc.modeling.CModelingDocument"), "not modeling document");
            require(call(doc, "getFile") instanceof java.io.File file && file.getName().equals(
                    System.getProperty("turboism.validation.imageArchive.fixtureName")), "active file identity differs");
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "task document is dirty before workload");
            require(view != null && call(view, "getDoc") == doc, "view/document mismatch");
            Object camera = call(call(view, "getCameraManager"), "getCameraWrapper");
            return new Object[]{app, doc, view, ((Number) call(camera, "getCameraScale")).floatValue(), undoState(doc), cameraScale(view)};
        });
        Object app = identity[0], doc = identity[1], view = identity[2];
        float originalCamera = (Float) identity[3], originalScale = (Float) identity[5];
        RESULT.setProperty("cameraScale.before", Float.toString(originalScale));
        NativeCloseOwnershipObservation.WeakPair handles = new NativeCloseOwnershipObservation.WeakPair(
                new WeakReference<>(doc), new WeakReference<>(call(doc, "getModelSource")),
                ((java.io.File) call(doc, "getFile")).getPath());
        phase("idle.end");
        observeImages(app, doc, view, frame, "beforeZoom");
        observeOwnershipOpen(app, appType, doc, view, frame, "idle", handles);
        phase("zoom.begin");
        try {
            for (int index = 0; index < 60; index++) {
                require(!aborted, "observer aborted; no further native actions allowed");
                String command = index % 2 == 0 ? "command_zoomIn" : "command_zoomOut";
                float before = (Float) NativeAtlasWorkflow.onEdt(() -> {
                    checkIdentity(app, doc, view, frame);
                    float scale = cameraScale(view);
                    call(app, command);
                    return scale;
                });
                Thread.sleep(500);
                float after = (Float) NativeAtlasWorkflow.onEdt(() -> {
                    checkIdentity(app, doc, view, frame);
                    return cameraScale(view);
                });
                require(Float.isFinite(after) && after > 0 && Float.floatToRawIntBits(before) != Float.floatToRawIntBits(after),
                        "native zoom did not change camera scale at action " + index);
                RESULT.setProperty("zoom.completed", Integer.toString(index + 1));
            }
            phase("zoom.end");
        } finally {
            NativeAtlasWorkflow.onEdt(() -> {
                checkIdentity(app, doc, view, frame);
                Object manager = call(view, "getCameraManager"), camera = call(manager, "getCameraWrapper");
                camera.getClass().getMethod("setCameraScale", float.class).invoke(camera, originalCamera);
                manager.getClass().getMethod("updateCamera", boolean.class).invoke(manager, true);
                require(Float.floatToRawIntBits(((Number) call(camera, "getCameraScale")).floatValue())
                        == Float.floatToRawIntBits(originalCamera), "raw camera scale not restored exactly");
                call(view, "repaintCanvas");
                return null;
            });
        }
        Thread.sleep(1000);
        NativeAtlasWorkflow.onEdt(() -> {
            checkIdentity(app, doc, view, frame);
            float restored = cameraScale(view);
            RESULT.setProperty("cameraScale.restored", Float.toString(restored));
            require(Float.floatToRawIntBits(restored) == Float.floatToRawIntBits(originalScale), "camera scale not restored exactly");
            require(identity[4].equals(undoState(doc)), "zoom changed Undo history");
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "zoom dirtied document");
            RESULT.setProperty("dirtyAndUndoUnchanged", "true");
            return null;
        });
        phase("restored.begin");
        Thread.sleep(30_000);
        phase("restored.end");
        closedImageCohort = observeImages(app, doc, view, frame, "beforeClose");
        observeOwnershipOpen(app, appType, doc, view, frame, "beforeClose", handles);
        WeakReference<?> weak = new WeakReference<>(doc);
        require(!aborted, "observer aborted; refusing document close");
        NativeAtlasWorkflow.onEdt(() -> {
            checkIdentity(app, doc, view, frame);
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "refusing to close dirty document");
            appType.getMethod("command_close", Class.forName("com.live2d.cubism.doc.IDocument", false, appType.getClassLoader()))
                    .invoke(app, doc);
            return null;
        });
        long until = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < until) {
            if (Boolean.TRUE.equals(NativeAtlasWorkflow.onEdt(() -> ((List<?>) call(app, "getAllDocs")).isEmpty()))) return new ClosedHandles(weak, handles);
            Thread.sleep(100);
        }
        throw new IllegalStateException("native document close did not complete");
    }

    private static NativeImageRetainObservation.Snapshot observeImages(
            Object app, Object doc, Object view, Frame frame, String name) throws Exception {
        require(!aborted, "observer aborted; refusing image observation");
        phase("retain." + name + ".begin");
        NativeImageRetainObservation.Snapshot snapshot = (NativeImageRetainObservation.Snapshot) NativeAtlasWorkflow.onEdt(() -> {
            checkIdentity(app, doc, view, frame);
            String undo = undoState(doc);
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "refusing observation of dirty document");
            NativeImageRetainObservation.Snapshot captured = NativeImageRetainObservation.captureHost(doc, app.getClass());
            checkIdentity(app, doc, view, frame);
            require(undo.equals(undoState(doc)) && !Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")),
                    "image observation changed authoring state");
            return captured;
        });
        snapshot.write(RESULT, "retain." + name);
        RESULT.setProperty("retain.hostJarSha256", HOST_SHA);
        phase("retain." + name + ".end");
        return snapshot;
    }

    private record ClosedHandles(WeakReference<?> document, NativeCloseOwnershipObservation.WeakPair pair) { }

    private static void observeOwnershipOpen(Object app, Class<?> verifiedApp, Object doc, Object view, Frame frame,
                                             String name, NativeCloseOwnershipObservation.WeakPair weak) throws Exception {
        require(!aborted, "observer aborted; refusing ownership observation");
        phase("ownership." + name + ".begin");
        NativeCloseOwnershipObservation.Snapshot snapshot = (NativeCloseOwnershipObservation.Snapshot) NativeAtlasWorkflow.onEdt(() -> {
            checkIdentity(app, doc, view, frame);
            String undo = undoState(doc);
            require(!Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")), "refusing observation of dirty document");
            NativeCloseOwnershipObservation.Snapshot captured = NativeCloseOwnershipObservation.captureHost(app, verifiedApp, weak);
            checkIdentity(app, doc, view, frame);
            require(undo.equals(undoState(doc)) && !Boolean.TRUE.equals(call(doc, "isModifiedAfterSaving")),
                    "ownership observation changed authoring state");
            return captured;
        });
        snapshot.write(RESULT, "ownership." + name);
        phase("ownership." + name + ".end");
    }

    private static void observeOwnershipClosed(Class<?> verifiedApp, Frame frame, String name,
                                               NativeCloseOwnershipObservation.WeakPair weak) throws Exception {
        require(!aborted, "observer aborted; refusing ownership observation");
        phase("ownership." + name + ".begin");
        NativeCloseOwnershipObservation.Snapshot snapshot = (NativeCloseOwnershipObservation.Snapshot) NativeAtlasWorkflow.onEdt(() -> {
            require(frame.isVisible(), "task window absent during closed ownership observation");
            Object app = verifiedApp.getMethod("access$get_instance$cp").invoke(null);
            require(((List<?>) call(app, "getAllDocs")).isEmpty(), "document reopened before closed ownership observation");
            return NativeCloseOwnershipObservation.captureHost(app, verifiedApp, weak);
        });
        snapshot.write(RESULT, "ownership." + name);
        phase("ownership." + name + ".end");
    }

    private static void checkIdentity(Object app, Object doc, Object view, Frame frame) throws Exception {
        require(frame.isVisible() && call(app, "getCurrentDoc") == doc && call(app, "getCurrentViewContext") == view,
                "task document/view identity changed");
    }
    private static float cameraScale(Object view) throws Exception {
        return ((Number) call(call(view, "getCameraManager"), "getCameraScale")).floatValue();
    }
    private static String undoState(Object doc) throws Exception {
        Object undo = call(doc, "getUndoManager");
        return call(undo, "getCurrentPos") + ":" + ((List<?>) call(undo, "getUndoList")).size() + ":" + call(undo, "getEditCount");
    }
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private static void phase(String name) throws Exception { RESULT.setProperty(name + ".epochMillis", Long.toString(System.currentTimeMillis())); store(); }
    private static void store() throws Exception {
        Path temporary = directory.resolve("result.tmp");
        try (var out = Files.newOutputStream(temporary)) { RESULT.store(out, "native resource workload; measurements are not optimization claims"); }
        Files.move(temporary, directory.resolve("result.properties"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
}
