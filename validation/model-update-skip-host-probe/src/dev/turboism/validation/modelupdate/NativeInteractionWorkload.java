package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/** Real continuous mouse drags, not SDK geometry replacement or direct camera mutation. */
final class NativeInteractionWorkload {
    private final String fixture, kind;
    private final Path state;
    private final boolean calibration = Boolean.getBoolean("turboism.validation.modelUpdateCalibration");
    private Frame window;
    private JComponent canvas;
    private NativeInteractionHost host;
    private NarrowUniformTrial hook;
    private int width, height;
    private Point startPoint;
    private boolean spaceDown, mouseDown;
    private final Map<Boolean, Boolean> nativeUndoDirtyPolicy = new java.util.HashMap<>();
    private int lastX, lastY;

    NativeInteractionWorkload(String fixture, Path state, String kind) {
        if (fixture == null || fixture.isBlank() || !List.of("pan", "artmesh").contains(kind)) {
            throw new IllegalArgumentException("native interaction requires a fixture and pan/artmesh mode");
        }
        if (Boolean.getBoolean("turboism.uniform-location.shadow")
            || Boolean.getBoolean("turboism.validation.modelUpdateGlCalls")
            || Boolean.getBoolean("turboism.validation.modelUpdateJfr")
            || Boolean.getBoolean("turboism.validation.allocationProfile")) {
            throw new IllegalArgumentException("native interaction throughput requires intrusive profiling off");
        }
        this.fixture = fixture; this.state = state; this.kind = kind;
    }

    void run() throws Exception {
        StringBuilder report = new StringBuilder("schemaVersion=1\nworkload=native-continuous-mouse-drag\n")
            .append("interaction=").append(kind).append('\n')
            .append("calibration=").append(calibration).append('\n')
            .append("performanceAccepted=false\nlatencyDefinition=drag-event-to-native-paint-barrier-not-presentation\n")
            .append("geometryChecks=source-all-keyforms-interpolated-calculated-raw-bits\n")
            .append("authoringDriver=native-mouse-not-SDK-replaceGeometry\n");
        Files.writeString(state.resolve("interaction-benchmark.txt"), report + "status=PREPARING\n");
        try {
            try (PreparationWatchdog preparation = new PreparationWatchdog(state, 30_000L)) {
                preparation.stage("discover");
                edt(() -> { discover(); return null; }, 180);
                preparation.stage("focus");
                edt(() -> { window.toFront(); window.requestFocus(); canvas.requestFocusInWindow(); return null; });
                Thread.sleep(150L);
                preparation.stage("attach-counter");
                edt(() -> { requireFocus(); hook = NarrowUniformTrial.attach(canvas); return null; });
                preparation.stage("scene-inventory");
                report.append("width=").append(width).append("\nheight=").append(height).append('\n')
                    .append(edt(host::sceneInventory));
                preparation.stage("ready");
            }
            report.append("preparationWatcherStopped=true\n");
            Files.writeString(state.resolve("interaction-benchmark.txt"), report + "status=RUNNING\n");
            boolean[] variants = {false, true, true, false};
            for (int leg = 0; leg < variants.length; leg++) {
                final boolean enabled = variants[leg];
                edt(() -> { hook.setEnabled(enabled); return null; });
                Files.writeString(state.resolve("interaction-progress.txt"), "leg=" + leg + "\nstage=warmup\n");
                gesture(enabled, calibration ? 4 : 24, null, "warmup." + leg + ".");
                Files.writeString(state.resolve("interaction-progress.txt"), "leg=" + leg + "\nstage=measuring\n");
                gesture(enabled, calibration ? 16 : 200, report, "leg." + leg + ".");
                Files.writeString(state.resolve("interaction-benchmark.txt"), report);
            }
            report.append("status=PASS\n");
        } catch (Throwable failure) {
            report.append("status=FAIL\nerror=").append(failure).append('\n');
            Files.writeString(state.resolve("interaction-benchmark.txt"), report);
            throw failure;
        } finally {
            try {
                release();
                if (host != null) edt(() -> { host.restoreSelection(); return null; });
                if (hook != null) edt(() -> { hook.close(); return null; });
            } catch (Throwable cleanup) {
                report.append("cleanupError=").append(cleanup).append("\nstatus=FAIL\n");
                throw cleanup;
            } finally { Files.writeString(state.resolve("interaction-benchmark.txt"), report); }
        }
    }

    private void gesture(boolean enabled, int steps, StringBuilder report, String prefix) throws Exception {
        if (kind.equals("artmesh")) acquireNativeTarget(enabled);
        else startPoint = new Point(width / 2, height / 2);
        drain();
        final var baseline = edt(host::geometry);
        final var cameraBefore = edt(host::camera);
        final String undoBefore = edt(host::undoState);
        final int undoPosition = edt(host::undoPosition);
        final List<Object> authoringBefore = edt(host::appliedAuthoringEdits);
        final boolean dirtyBefore = edt(host::modified);
        final String beforePixels = kind.equals("artmesh") ? edt(() -> hook.capture(canvas).digest()) : "not-required";
        final long[] samples = new long[steps], queue = new long[steps], handler = new long[steps], repaint = new long[steps];
        final long[] started = {0L}, elapsed = {0L}, pressNanos = {0L}, releaseNanos = {0L};
        final List<Map<String, Long>> snapshots = new ArrayList<>();
        final List<Map<String, Long>> modelSnapshots = new ArrayList<>();
        final boolean resourcesEnabled = report != null && Boolean.getBoolean("turboism.validation.resources");
        final BenchmarkResources resources = resourcesEnabled ? new BenchmarkResources(canvas.getClass().getClassLoader(),
            edt(() -> Thread.currentThread().getId())) : null;
        try {
            NativeDragSequence.execute(steps, new NativeDragSequence.Driver() {
                @Override public void press() throws Exception {
                    final long began = System.nanoTime();
                    edt(() -> {
                        requireFocus(); mouse(MouseEvent.MOUSE_MOVED, startPoint.x, startPoint.y, 0, MouseEvent.NOBUTTON);
                        return null;
                    });
                    drain();
                    if (kind.equals("pan")) {
                        edt(() -> {
                            requireFocus(); spaceDown = true;
                            canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_PRESSED,
                                System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
                            mouse(MouseEvent.MOUSE_MOVED, startPoint.x, startPoint.y, 0, MouseEvent.NOBUTTON);
                            return null;
                        });
                        drain();
                    }
                    edt(() -> {
                        requireFocus(); mouseDown = true;
                        mouse(MouseEvent.MOUSE_PRESSED, startPoint.x, startPoint.y,
                            InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                        return null;
                    });
                    drain();
                    if (kind.equals("artmesh")) edt(() -> { host.requireTargetSelected(); return null; });
                    if (report == null) Files.writeString(state.resolve("interaction-action-press.txt"), edt(host::actionState));
                    pressNanos[0] = System.nanoTime() - began;
                    snapshots.add(edt(hook::snapshot));
                    modelSnapshots.add(edt(NativeInteractionWorkload::modelStats));
                    if (resources != null) resources.start();
                    started[0] = System.nanoTime();
                }
                @Override public void drag(int step) throws Exception {
                    int offset = (step & 1) == 0 ? 8 : 16;
                    final int x = startPoint.x + offset, y = startPoint.y + offset / 2;
                    long beforeFrames = edt(() -> hook.snapshot().getOrDefault("completedDisplayFrames", 0L));
                    final long sent = System.nanoTime();
                    long[] event = edt(() -> {
                        long entered = System.nanoTime();
                        requireFocus();
                        mouse(MouseEvent.MOUSE_DRAGGED, x, y, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                        return new long[] {entered, System.nanoTime()};
                    });
                    long[] barrier = edt(() -> new long[] {hook.snapshot().getOrDefault("completedDisplayFrames", 0L), System.nanoTime()});
                    long returned = System.nanoTime();
                    if (barrier[0] <= beforeFrames) throw new IllegalStateException("drag event produced no native paint: " + step);
                    samples[step] = returned - sent;
                    queue[step] = event[0] - sent; handler[step] = event[1] - event[0];
                    repaint[step] = barrier[1] - event[1];
                }
                @Override public void release() throws Exception {
                    if (report == null) Files.writeString(state.resolve("interaction-action-before-release.txt"), edt(host::actionState));
                    if (started[0] != 0L) {
                        elapsed[0] = System.nanoTime() - started[0];
                        if (resources != null) resources.stop();
                        snapshots.add(edt(hook::snapshot));
                        modelSnapshots.add(edt(NativeInteractionWorkload::modelStats));
                    }
                    final long beforeRelease = System.nanoTime();
                    NativeInteractionWorkload.this.release();
                    releaseNanos[0] = System.nanoTime() - beforeRelease;
                }
            });
            if (snapshots.size() != 2) throw new IllegalStateException("gesture accounting incomplete");
            hook.requireLeg(snapshots.get(0), snapshots.get(1), enabled, steps);
            final var moved = edt(host::geometry);
            List<Integer> changed = NativeInteractionHost.changed(baseline, moved);
            final var movedCamera = edt(host::camera);
            final String movedPixels = kind.equals("artmesh") ? edt(() -> hook.capture(canvas).digest()) : "not-required";
            Files.writeString(state.resolve("interaction-state-check.txt"), "interaction=" + kind
                + "\nchangedMeshes=" + changed + "\ntargetIndex=" + host.targetIndex()
                + "\nundoBefore=" + undoPosition + "\nundoAfter=" + edt(host::undoPosition)
                + "\nvisiblePixelsChanged=" + !beforePixels.equals(movedPixels)
                + "\nwholeMeshChanged=" + (kind.equals("artmesh") && NativeInteractionHost.wholeMeshChanged(baseline, moved, host.targetIndex()))
                + "\nauthoringBefore=" + authoringBefore.size() + "\nauthoringAfter=" + edt(host::appliedAuthoringEdits).size()
                + "\n" + edt(host::actionState));
            if (report != null) verifyPixelsAtCurrentState();
            if (kind.equals("pan")) {
                if (cameraBefore.equals(movedCamera) || !changed.isEmpty()
                    || !undoBefore.equals(edt(host::undoState)) || dirtyBefore != edt(host::modified)) {
                    throw new IllegalStateException("pan did not exclusively change camera: changedMeshes=" + changed);
                }
                edt(() -> { host.restoreCamera(cameraBefore); return null; }); drain();
                if (!cameraBefore.equals(edt(host::camera)) || !NativeInteractionHost.changed(baseline, edt(host::geometry)).isEmpty()) {
                    throw new IllegalStateException("pan restoration mismatch");
                }
            } else {
                final List<Object> authoringAfter = edt(host::appliedAuthoringEdits);
                if (!changed.equals(List.of(host.targetIndex())) || !cameraBefore.equals(movedCamera)
                    || !NativeInteractionHost.wholeMeshChanged(baseline, moved, host.targetIndex())
                    || beforePixels.equals(movedPixels)
                    || authoringAfter.size() != authoringBefore.size() + 1
                    || !authoringAfter.subList(0, authoringBefore.size()).equals(authoringBefore)) {
                    throw new IllegalStateException("not a single-ArtMesh authoring drag: changedMeshes=" + changed
                        + " target=" + host.targetIndex() + " undoBefore=" + undoPosition + " undoAfter=" + edt(host::undoPosition));
                }
                edt(() -> { host.undo(); return null; }); drain();
                if (!NativeInteractionHost.changed(baseline, edt(host::geometry)).isEmpty()) throw new IllegalStateException("Undo geometry mismatch");
                edt(() -> { host.redo(); return null; }); drain();
                if (!NativeInteractionHost.changed(moved, edt(host::geometry)).isEmpty()) throw new IllegalStateException("Redo geometry mismatch");
                edt(() -> { host.undo(); return null; }); drain();
                final List<Integer> remainingChanges = NativeInteractionHost.changed(baseline, edt(host::geometry));
                final List<Object> remainingEdits = edt(host::appliedAuthoringEdits);
                final boolean dirtyAfterUndo = edt(host::modified);
                Files.writeString(state.resolve("interaction-undo-check.txt"), "remainingGeometryChanges=" + remainingChanges
                    + "\nappliedAuthoringEditsRestored=" + remainingEdits.equals(authoringBefore)
                    + "\nmodifiedBefore=" + dirtyBefore + "\nmodifiedAfterNativeUndo=" + dirtyAfterUndo + "\n");
                if (!remainingChanges.isEmpty() || !remainingEdits.equals(authoringBefore)) {
                    throw new IllegalStateException("final native Undo did not restore geometry/history");
                }
                // Native undo can keep the document's sticky modified-after-saving flag.
                // Compare ON to the observed OFF behavior, never clear that flag ourselves.
                if (!enabled) {
                    Boolean previous = nativeUndoDirtyPolicy.putIfAbsent(dirtyBefore, dirtyAfterUndo);
                    if (previous != null && previous != dirtyAfterUndo) throw new IllegalStateException("native Undo dirty behavior unstable");
                } else if (!java.util.Objects.equals(nativeUndoDirtyPolicy.get(dirtyBefore), dirtyAfterUndo)) {
                    throw new IllegalStateException("cached Undo dirty behavior differs from native control");
                }
                if (report != null) report.append(prefix).append("modifiedBefore=").append(dirtyBefore).append('\n')
                    .append(prefix).append("modifiedAfterNativeUndo=").append(dirtyAfterUndo).append('\n')
                    .append(prefix).append("modifiedFlagMatchesNative=true\n");
            }
            if (report != null) {
                verifyPixelsAtCurrentState();
                edt(() -> { hook.setEnabled(enabled); return null; });
                long[] sorted = samples.clone(); Arrays.sort(sorted);
                long total = Arrays.stream(samples).sum();
                long frames = NarrowUniformTrial.delta(snapshots.get(0), snapshots.get(1), "completedDisplayFrames");
                report.append(prefix).append("enabled=").append(enabled).append('\n')
                    .append(prefix).append("variant=").append(enabled ? "narrow-locations" : "native").append('\n')
                    .append(prefix).append("samples=").append(steps).append('\n')
                    .append(prefix).append("elapsedNanos=").append(elapsed[0]).append('\n')
                    .append(prefix).append("meanNanos=").append(total / steps).append('\n')
                    .append(prefix).append("meanQueueNanos=").append(Arrays.stream(queue).sum() / steps).append('\n')
                    .append(prefix).append("meanHandlerNanos=").append(Arrays.stream(handler).sum() / steps).append('\n')
                    .append(prefix).append("meanRepaintBarrierNanos=").append(Arrays.stream(repaint).sum() / steps).append('\n')
                    .append(prefix).append("p95Nanos=").append(CanvasWheelWorkload.percentile(sorted, .95)).append('\n')
                    .append(prefix).append("p99Nanos=").append(CanvasWheelWorkload.percentile(sorted, .99)).append('\n')
                    .append(prefix).append("pressNanos=").append(pressNanos[0]).append('\n')
                    .append(prefix).append("releaseNanos=").append(releaseNanos[0]).append('\n')
                    .append(prefix).append("completedDisplayFrames=").append(frames).append('\n')
                    .append(prefix).append("renderFramesPerSecond=").append(frames * 1e9 / elapsed[0]).append('\n')
                    .append(prefix).append("rawNanos=").append(Arrays.toString(samples)).append('\n')
                    .append(prefix).append("changedMeshes=").append(changed).append('\n')
                    .append(prefix).append("geometryRestored=true\n")
                    .append(prefix).append("movedStatePixelParity=true\n")
                    .append(prefix).append("restoredStatePixelParity=true\n")
                    .append(prefix).append("canvasPixelParity=true\n");
                if (kind.equals("artmesh")) report.append(prefix).append("targetId=").append(edt(host::targetId)).append('\n')
                    .append(prefix).append("nativeUndoRedoRestored=true\n")
                    .append(prefix).append("allTargetVerticesMoved=true\n");
                for (var value : snapshots.get(1).entrySet()) report.append(prefix).append("uniformHook.")
                    .append(value.getKey()).append('=').append(value.getValue() - snapshots.get(0).getOrDefault(value.getKey(), 0L)).append('\n');
                for (var value : modelSnapshots.get(1).entrySet()) report.append(prefix).append("modelUpdate.")
                    .append(value.getKey()).append('=').append(value.getValue() - modelSnapshots.get(0).getOrDefault(value.getKey(), 0L)).append('\n');
                if (resources != null) resources.snapshot().forEach((key, value) -> report.append(prefix)
                    .append("resources.").append(key).append('=').append(value).append('\n'));
            }
        } finally { if (resources != null) resources.close(); }
    }

    /** Triangle coverage alone is not an opaque-texture hit; let the native picker decide. */
    private void acquireNativeTarget(boolean enabled) throws Exception {
        edt(() -> { hook.setEnabled(true); return null; });
        List<Point> candidates = edt(host::candidatePoints);
        int attempts = 0;
        boolean selected = false;
        try {
            for (Point point : candidates) {
                startPoint = point; attempts++;
                edt(() -> { host.clearSelection(); return null; }); drain();
                edt(() -> {
                    requireFocus();
                    mouse(MouseEvent.MOUSE_MOVED, point.x, point.y, 0, MouseEvent.NOBUTTON);
                    mouseDown = true;
                    mouse(MouseEvent.MOUSE_PRESSED, point.x, point.y, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                    return null;
                });
                release();
                edt(() -> { mouse(MouseEvent.MOUSE_CLICKED, point.x, point.y, 0, MouseEvent.BUTTON1); return null; });
                drain();
                if (edt(host::adoptSelectedTarget)) { selected = true; break; }
            }
            Files.writeString(state.resolve("interaction-target.txt"), "nativePickAttempts=" + attempts
                + "\nselected=" + selected + "\npoint=" + startPoint + "\n");
            if (!selected) throw new IllegalStateException("native picker found no editable single-ArtMesh target");
            Thread.sleep(600L);
        } finally { edt(() -> { hook.setEnabled(enabled); return null; }); }
    }

    private void verifyPixelsAtCurrentState() throws Exception {
        final FrameReadback original = edt(() -> { hook.setEnabled(false); return hook.capture(canvas); });
        final FrameReadback repeat = edt(() -> hook.capture(canvas));
        final FrameReadback cached = edt(() -> { hook.setEnabled(true); return hook.capture(canvas); });
        final FrameReadback restored = edt(() -> { hook.setEnabled(false); return hook.capture(canvas); });
        if (!original.samePixels(repeat)) throw new IllegalStateException("native same-state pixels are unstable");
        if (!repeat.samePixels(cached) || !cached.samePixels(restored)) throw new IllegalStateException("interaction pixel parity failed");
    }

    private void release() throws Exception {
        try {
            if (mouseDown) {
                mouseDown = false;
                edt(() -> { host.check(); mouse(MouseEvent.MOUSE_RELEASED, lastX, lastY, 0, MouseEvent.BUTTON1); return null; });
                drain();
            }
        } finally {
            if (spaceDown) {
                spaceDown = false;
                edt(() -> {
                    host.check();
                    canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(), 0, KeyEvent.VK_SPACE, ' '));
                    return null;
                });
                drain();
            }
        }
    }
    private void mouse(int id, int x, int y, int modifiers, int button) throws Exception {
        host.check();
        if (x < 0 || y < 0 || x >= width || y >= height) throw new IllegalArgumentException("gesture left canvas");
        Point origin = canvas.getLocationOnScreen(); lastX = x; lastY = y;
        canvas.dispatchEvent(new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers, x, y,
            origin.x + x, origin.y + y, id == MouseEvent.MOUSE_PRESSED || id == MouseEvent.MOUSE_RELEASED || id == MouseEvent.MOUSE_CLICKED ? 1 : 0, false, button));
    }
    private void requireFocus() throws Exception {
        host.check();
        var focus = KeyboardFocusManager.getCurrentKeyboardFocusManager();
        Component owner = focus.getFocusOwner();
        if (focus.getActiveWindow() != window || owner == null
            || (owner != canvas && !SwingUtilities.isDescendingFrom(owner, canvas))) {
            throw new IllegalStateException("task canvas does not own keyboard focus; refusing key input");
        }
    }
    private void discover() throws Exception {
        List<Frame> matching = new ArrayList<>();
        for (Window w : Window.getWindows()) if (w instanceof Frame frame && frame.isShowing()
            && frame.getTitle().contains(fixture)) matching.add(frame);
        if (matching.size() != 1) throw new IllegalStateException("task window count=" + matching.size());
        window = matching.get(0);
        List<JComponent> canvases = new ArrayList<>(); collect(window, canvases, 0);
        if (canvases.size() != 1) throw new IllegalStateException("task GLJPanel count=" + canvases.size());
        canvas = canvases.get(0); width = canvas.getWidth(); height = canvas.getHeight();
        if (width < 100 || height < 100 || SwingUtilities.getWindowAncestor(canvas) != window) throw new IllegalStateException("invalid canvas");
        host = new NativeInteractionHost(fixture, window, canvas);
    }
    private static void collect(Component component, List<JComponent> found, int depth) {
        if (depth > 32) throw new IllegalStateException("component tree too deep");
        if (!component.isShowing()) return;
        if (component.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel") && component instanceof JComponent c) found.add(c);
        if (component instanceof Container container) for (Component child : container.getComponents()) collect(child, found, depth + 1);
    }
    private static Map<String, Long> modelStats() {
        Object slot = System.getProperties().get("turboism.model-update-skip.stats");
        if (!(slot instanceof java.util.function.Supplier<?> supplier) || !(supplier.get() instanceof Map<?, ?> values)) {
            throw new IllegalStateException("model-update counters missing");
        }
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        values.forEach((key, value) -> { if (key instanceof String name && value instanceof Number n) result.put(name, n.longValue()); });
        return result;
    }
    private static void drain() throws Exception { edt(() -> null); }
    private static <T> T edt(Callable<T> action) throws Exception { return edt(action, 10L); }
    private static <T> T edt(Callable<T> action, long seconds) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); EventQueue.invokeLater(task);
        try { return task.get(seconds, TimeUnit.SECONDS); }
        catch (Exception failure) { task.cancel(false); throw failure; }
    }
}
