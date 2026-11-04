package dev.turboism.validation.modelupdate;

import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;

/**
 * Real-machine human-simulated Warp Deformer interaction workload on Cubism 5.3.04:
 * executes both (1) moving a Warp Deformer via its bounding-box move handle and
 * (2) deforming a Warp Deformer via dragging an individual control point on the canvas,
 * followed by native Undo/Redo verification and telemetry capture.
 */
final class NativeDeformerWorkload {

    static final String REPORT_FILE = "deformer-benchmark.txt";
    private static final String CLIPMASK_STATS = "turboism.clip-mask-probe.stats";
    private static final String CLIPMASK_DEDUPE_STATS = "turboism.clip-mask-dedupe.stats";
    private static final String CLIPMASK_SCISSOR_STATS = "turboism.clip-mask-scissor.stats";
    private static final String EXT_INTERP_SETUP_STATS = "turboism.extended-interpolation-setup.stats";
    private static final String COALESCE_STATS = "turboism.model-instance-coalesce.stats";

    private final String fixture;
    private final Path state;
    private final boolean calibration = Boolean.getBoolean("turboism.validation.modelUpdateCalibration");

    private Frame window;
    private JComponent canvas;
    private int width, height;
    private Object app, doc, view, model, modelSource, cameraManager, selector, undoManager, actionManager, boundingBox;
    private Class<?> documentType, vector2Type, selectionType, boundingBoxClass, actionManagerNClass;
    private Method getActionForDefault;
    private NarrowUniformTrial hook;

    private Object targetWarp;
    private Object targetWarpSource;
    private String targetWarpId;
    private Point movePoint;
    private Point deformPoint;
    private int deformPointIndex = -1;
    private boolean mouseDown;
    private int lastX, lastY;
    private String focusPreparation = "acquisition=not-started\n";

    NativeDeformerWorkload(final String fixture, final Path state) {
        if (fixture == null || fixture.isBlank()) {
            throw new IllegalArgumentException("deformer workload requires a fixture");
        }
        this.fixture = fixture;
        this.state = state;
    }

    void run() throws Exception {
        final StringBuilder report = new StringBuilder("schemaVersion=1\n")
                .append("workload=native-deformer-move-and-deform\n")
                .append("authoringDriver=real-awt-mouse-events-on-gljpanel\n")
                .append("calibration=").append(calibration).append('\n');
        Files.writeString(state.resolve(REPORT_FILE), report + "status=PREPARING\n");

        try {
            try (PreparationWatchdog preparation = new PreparationWatchdog(state, 45_000L)) {
                preparation.stage("discover");
                edt(() -> {
                    discover();
                    return null;
                }, 180L);
                preparation.stage("focus");
                acquireFocus();
                preparation.stage("attach-counter");
                edt(() -> {
                    requireFocus();
                    hook = NarrowUniformTrial.attach(canvas, false);
                    hook.setEnabled(true);
                    return null;
                });
                preparation.stage("select-warp-deformer");
                edt(() -> {
                    selectTargetWarpDeformer();
                    return null;
                }, 60L);
                report.append("width=").append(width).append("\nheight=").append(height).append('\n')
                        .append("targetWarpId=").append(targetWarpId).append('\n')
                        .append("moveHandlePoint=").append(movePoint.x).append(',').append(movePoint.y).append('\n')
                        .append("deformControlPoint=").append(deformPoint.x).append(',').append(deformPoint.y).append('\n')
                        .append("deformControlPointIndex=").append(deformPointIndex).append('\n');
                preparation.stage("ready");
            }
            report.append("preparationWatcherStopped=true\n");
            Files.writeString(state.resolve(REPORT_FILE), report + "status=RUNNING\n");

            final int warmupSteps = calibration ? 4 : 8;
            final int measureSteps = calibration ? 16 : 32;

            // 1. Warmup & Measure: Move Deformer (bounding-box DRAG_MOVE handle)
            Files.writeString(state.resolve("deformer-progress.txt"), "leg=moveDeformer\nstage=warmup\n");
            executeDeformerGesture("move", movePoint, warmupSteps, null, "warmup.move.");
            Files.writeString(state.resolve("deformer-progress.txt"), "leg=moveDeformer\nstage=measuring\n");
            executeDeformerGesture("move", movePoint, measureSteps, report, "leg.moveDeformer.");
            Files.writeString(state.resolve(REPORT_FILE), report);

            // Re-verify points after move undo/redo
            edt(() -> {
                refreshTargetPoints();
                return null;
            });

            // 2. Warmup & Measure: Deform Deformer (control point drag)
            Files.writeString(state.resolve("deformer-progress.txt"), "leg=deformDeformer\nstage=warmup\n");
            executeDeformerGesture("deform", deformPoint, warmupSteps, null, "warmup.deform.");
            Files.writeString(state.resolve("deformer-progress.txt"), "leg=deformDeformer\nstage=measuring\n");
            executeDeformerGesture("deform", deformPoint, measureSteps, report, "leg.deformDeformer.");

            report.append("status=PASS\n");
            Files.writeString(state.resolve(REPORT_FILE), report);
        } catch (Throwable failure) {
            report.append("status=FAIL\nerror=").append(failure).append('\n');
            Files.writeString(state.resolve(REPORT_FILE), report);
            throw failure;
        } finally {
            try {
                releaseMouseIfDown();
                if (hook != null) {
                    edt(() -> {
                        hook.close();
                        return null;
                    });
                }
            } finally {
                Files.writeString(state.resolve(REPORT_FILE), report);
            }
        }
    }

    private void executeDeformerGesture(
            final String mode,
            final Point startPt,
            final int steps,
            final StringBuilder report,
            final String prefix) throws Exception {
        drain();
        final float[] beforeKeyform = edt(this::readCurrentKeyformPositions);
        final float[] beforeCalculated = edt(this::readCalculatedPositions);
        final int undoPosBefore = edt(this::undoPosition);
        final String beforePixels = edt(() -> hook.capture(canvas).digest());

        final Map<String, Long> dedupeBefore = statsSlot(CLIPMASK_DEDUPE_STATS);
        final Map<String, Long> scissorBefore = statsSlot(CLIPMASK_SCISSOR_STATS);
        final Map<String, Long> extSetupBefore = statsSlot(EXT_INTERP_SETUP_STATS);
        final Map<String, Long> coalesceBefore = statsSlot(COALESCE_STATS);
        final Map<String, Long> probeBefore = statsSlot(CLIPMASK_STATS);

        final long[] samples = new long[steps];
        final long[] handlers = new long[steps];
        final long[] repaints = new long[steps];
        final long[] started = {0L};
        final long[] elapsed = {0L};
        final long[] completedBefore = {0L};
        final long[] completedAfter = {0L};

        NativeDragSequence.execute(steps, new NativeDragSequence.Driver() {
            @Override
            public void press() throws Exception {
                edt(() -> {
                    requireFocus();
                    mouse(MouseEvent.MOUSE_MOVED, startPt.x, startPt.y, 0, MouseEvent.NOBUTTON);
                    mouseDown = true;
                    mouse(MouseEvent.MOUSE_PRESSED, startPt.x, startPt.y, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.BUTTON1);
                    return null;
                });
                drain();
                completedBefore[0] = edt(() -> hook.snapshot().getOrDefault("completedDisplayFrames", 0L));
                started[0] = System.nanoTime();
            }

            @Override
            public void drag(final int step) throws Exception {
                final int offset = ((step & 1) == 0 ? 12 : 24) + (step % 4) * 2;
                final int x = Math.max(20, Math.min(width - 20, startPt.x + offset));
                final int y = Math.max(20, Math.min(height - 20, startPt.y + offset / 2));
                final long framesBeforeStep = edt(() -> hook.snapshot().getOrDefault("completedDisplayFrames", 0L));
                final long sent = System.nanoTime();
                final long[] eventTiming = edt(() -> {
                    final long entered = System.nanoTime();
                    requireFocus();
                    mouse(MouseEvent.MOUSE_DRAGGED, x, y, InputEvent.BUTTON1_DOWN_MASK, MouseEvent.NOBUTTON);
                    return new long[] {entered, System.nanoTime()};
                });
                final long[] barrier = edt(() -> new long[] {
                    hook.snapshot().getOrDefault("completedDisplayFrames", 0L),
                    System.nanoTime()
                });
                final long returned = System.nanoTime();
                if (barrier[0] <= framesBeforeStep) {
                    throw new IllegalStateException("deformer " + mode + " drag step produced no native paint: " + step);
                }
                samples[step] = returned - sent;
                handlers[step] = eventTiming[1] - eventTiming[0];
                repaints[step] = barrier[1] - eventTiming[1];
            }

            @Override
            public void release() throws Exception {
                if (started[0] != 0L) {
                    elapsed[0] = System.nanoTime() - started[0];
                    completedAfter[0] = edt(() -> hook.snapshot().getOrDefault("completedDisplayFrames", 0L));
                }
                releaseMouseIfDown();
            }
        });

        final float[] afterKeyform = edt(this::readCurrentKeyformPositions);
        final float[] afterCalculated = edt(this::readCalculatedPositions);
        final int undoPosAfter = edt(this::undoPosition);
        final String movedPixels = edt(() -> hook.capture(canvas).digest());

        final boolean keyformMutated = !NativeDragSequence.same(beforeKeyform, afterKeyform);
        final boolean calculatedMutated = !NativeDragSequence.same(beforeCalculated, afterCalculated);
        final boolean allMoved = NativeDragSequence.allVerticesMoved(beforeKeyform, afterKeyform);
        final int movedVertexCount = countMovedVertices(beforeKeyform, afterKeyform);

        if (!keyformMutated || !calculatedMutated) {
            throw new IllegalStateException("deformer " + mode + " drag did not mutate deformer coordinates! "
                    + "keyformMutated=" + keyformMutated + " calculatedMutated=" + calculatedMutated);
        }
        if (beforePixels.equals(movedPixels)) {
            throw new IllegalStateException("deformer " + mode + " drag did not change rendered canvas pixels!");
        }
        if (undoPosAfter <= undoPosBefore) {
            throw new IllegalStateException("deformer " + mode + " drag did not advance native Undo history!");
        }

        // Undo -> Redo -> Undo verification with timing
        final long undoStart = System.nanoTime();
        edt(() -> {
            call(app, "command_undo", new Class<?>[] {documentType}, doc);
            return null;
        });
        drain();
        final long undoNanos = System.nanoTime() - undoStart;
        final float[] afterUndo = edt(this::readCurrentKeyformPositions);
        if (!NativeDragSequence.same(beforeKeyform, afterUndo)) {
            throw new IllegalStateException("Undo after deformer " + mode + " did not restore exact keyform bits!");
        }

        final long redoStart = System.nanoTime();
        edt(() -> {
            call(app, "command_redo", new Class<?>[] {documentType}, doc);
            return null;
        });
        drain();
        final long redoNanos = System.nanoTime() - redoStart;
        final float[] afterRedo = edt(this::readCurrentKeyformPositions);
        if (!NativeDragSequence.same(afterKeyform, afterRedo)) {
            throw new IllegalStateException("Redo after deformer " + mode + " did not restore mutated keyform bits!");
        }

        // Final restoring Undo
        edt(() -> {
            call(app, "command_undo", new Class<?>[] {documentType}, doc);
            return null;
        });
        drain();
        final float[] finalRestored = edt(this::readCurrentKeyformPositions);
        if (!NativeDragSequence.same(beforeKeyform, finalRestored)) {
            throw new IllegalStateException("Final Undo after deformer " + mode + " did not restore baseline!");
        }

        if (report != null) {
            final long[] sorted = samples.clone();
            Arrays.sort(sorted);
            long total = 0L, handlerTotal = 0L, repaintTotal = 0L;
            for (int i = 0; i < steps; i++) {
                total += samples[i];
                handlerTotal += handlers[i];
                repaintTotal += repaints[i];
            }
            final long completedFrames = completedAfter[0] - completedBefore[0];
            final double fps = elapsed[0] > 0L ? (completedFrames * 1_000_000_000.0 / elapsed[0]) : 0.0;

            report.append(prefix).append("mode=").append(mode).append('\n')
                    .append(prefix).append("samples=").append(steps).append('\n')
                    .append(prefix).append("completedDisplayFrames=").append(completedFrames).append('\n')
                    .append(prefix).append("elapsedNanos=").append(elapsed[0]).append('\n')
                    .append(prefix).append("renderFramesPerSecond=").append(fps).append('\n')
                    .append(prefix).append("meanNanos=").append(total / steps).append('\n')
                    .append(prefix).append("meanHandlerNanos=").append(handlerTotal / steps).append('\n')
                    .append(prefix).append("meanRepaintBarrierNanos=").append(repaintTotal / steps).append('\n')
                    .append(prefix).append("p50Nanos=").append(CanvasWheelWorkload.percentile(sorted, 0.50)).append('\n')
                    .append(prefix).append("p95Nanos=").append(CanvasWheelWorkload.percentile(sorted, 0.95)).append('\n')
                    .append(prefix).append("maxNanos=").append(sorted[sorted.length - 1]).append('\n')
                    .append(prefix).append("undoNanos=").append(undoNanos).append('\n')
                    .append(prefix).append("redoNanos=").append(redoNanos).append('\n')
                    .append(prefix).append("totalVertices=").append(beforeKeyform.length / 2).append('\n')
                    .append(prefix).append("movedVertices=").append(movedVertexCount).append('\n')
                    .append(prefix).append("allVerticesMoved=").append(allMoved).append('\n')
                    .append(prefix).append("visiblePixelsChanged=true\n")
                    .append(prefix).append("undoRedoBitwiseRestored=true\n");

            appendStatsDelta(report, prefix + "clipMaskDedupe.", dedupeBefore, statsSlot(CLIPMASK_DEDUPE_STATS));
            appendStatsDelta(report, prefix + "clipMaskScissor.", scissorBefore, statsSlot(CLIPMASK_SCISSOR_STATS));
            appendStatsDelta(report, prefix + "extInterpSetup.", extSetupBefore, statsSlot(EXT_INTERP_SETUP_STATS));
            appendStatsDelta(report, prefix + "modelInstanceCoalesce.", coalesceBefore, statsSlot(COALESCE_STATS));
            appendStatsDelta(report, prefix + "clipMaskProbe.", probeBefore, statsSlot(CLIPMASK_STATS));
        }
    }

    private static int countMovedVertices(final float[] before, final float[] after) {
        int count = 0;
        for (int i = 0; i + 1 < before.length; i += 2) {
            if (Float.floatToRawIntBits(before[i]) != Float.floatToRawIntBits(after[i])
                    || Float.floatToRawIntBits(before[i + 1]) != Float.floatToRawIntBits(after[i + 1])) {
                count++;
            }
        }
        return count;
    }

    private static void appendStatsDelta(
            final StringBuilder report,
            final String prefix,
            final Map<String, Long> before,
            final Map<String, Long> after) {
        for (String key : after.keySet().stream().sorted().toList()) {
            final long val = key.equals("active") || key.equals("savedPermille") || key.endsWith("Peak")
                    ? after.get(key)
                    : after.get(key) - before.getOrDefault(key, 0L);
            report.append(prefix).append(key).append('=').append(val).append('\n');
        }
    }

    private void selectTargetWarpDeformer() throws Exception {
        call(modelSource, "setDefaultKeyformLocked", new Class<?>[] {boolean.class}, false);
        if (trySelectWarpDeformer()) return;
        call(app, "command_showAllObject_InViewArea");
        call(view, "repaintCanvas");
        if (trySelectWarpDeformer()) return;
        throw new IllegalStateException("No visible unlocked on-screen CWarpDeformer found for move + deform");
    }

    private boolean trySelectWarpDeformer() throws Exception {
        final List<?> deformers = (List<?>) call(model, "getAllDeformers");
        final Class<?> warpClass = Class.forName(
                "com.live2d.cubism.doc.model.deformer.warp.CWarpDeformer",
                false,
                canvas.getClass().getClassLoader());

        for (int i = deformers.size() - 1; i >= 0; i--) {
            final Object def = deformers.get(i);
            if (!warpClass.isInstance(def)) continue;
            final Object src = call(def, "getSource");
            if (!Boolean.TRUE.equals(call(src, "isVisibleInHierarchy"))
                    || !Boolean.TRUE.equals(call(src, "isEditableInHierarchy"))
                    || Boolean.TRUE.equals(call(src, "isLockedInHierarchy"))
                    || call(def, "getCurrentKeyform") == null) {
                continue;
            }
            final Object calcForm = call(def, "getCalculatedForm");
            if (calcForm == null) continue;
            final float[] positions = (float[]) call(calcForm, "getPositions");
            if (positions == null || positions.length < 8) continue;

            // Select this WarpDeformer and check its bounding-box move handle and control points on screen
            call(selector, "clearSelection");
            final Object sel = call(src, "getSelection");
            call(selector, "select", new Class<?>[] {selectionType, boolean.class}, sel, false);
            call(view, "updateBoundingBox");
            call(boundingBox, "resetBoundingBoxIfNeed", new Class<?>[] {actionManagerNClass}, actionManager);

            final Point candidateMove = findMoveHandleOnCanvas();
            if (candidateMove == null) continue;

            final int[] candidateDeform = findDeformControlPointOnCanvas(positions, candidateMove);
            if (candidateDeform == null) continue;

            targetWarp = def;
            targetWarpSource = src;
            targetWarpId = String.valueOf(call(src, "getId"));
            movePoint = candidateMove;
            deformPoint = new Point(candidateDeform[0], candidateDeform[1]);
            deformPointIndex = candidateDeform[2];
            call(view, "repaintCanvas");
            return true;
        }
        return false;
    }

    private void refreshTargetPoints() throws Exception {
        call(boundingBox, "resetBoundingBoxIfNeed", new Class<?>[] {actionManagerNClass}, actionManager);
        final Point m = findMoveHandleOnCanvas();
        if (m != null) movePoint = m;
        final float[] positions = readCalculatedPositions();
        if (deformPointIndex >= 0 && deformPointIndex * 2 + 1 < positions.length) {
            final Object vec = vector(positions[deformPointIndex * 2], positions[deformPointIndex * 2 + 1]);
            final Object mapped = call(cameraManager, "documentToComponent", new Class<?>[] {vector2Type}, vec);
            final int x = Math.round(number(call(mapped, "getX")));
            final int y = Math.round(number(call(mapped, "getY")));
            if (inCanvasBounds(x, y)) {
                deformPoint = new Point(x, y);
            }
        }
    }

    private Point findMoveHandleOnCanvas() throws Exception {
        final List<?> pts = (List<?>) call(boundingBox, "getPt", new Class<?>[] {boolean.class}, true);
        if (pts == null || pts.size() < 4) return null;
        final Object moveDocPos = call(
                boundingBox,
                "getDragMovePos",
                new Class<?>[] {actionManagerNClass, List.class},
                actionManager,
                pts);
        final Object mapped = call(cameraManager, "documentToComponent", new Class<?>[] {vector2Type}, moveDocPos);
        final float mx = number(call(mapped, "getX"));
        final float my = number(call(mapped, "getY"));
        if (!Float.isFinite(mx) || !Float.isFinite(my)) return null;
        final int x = Math.round(mx);
        final int y = Math.round(my);
        if (!inCanvasBounds(x, y)) return null;

        // Hover at (x, y) and verify boundingBox.getActionFor returns 100 (DRAG_MOVE)
        mouse(MouseEvent.MOUSE_MOVED, x, y, 0, MouseEvent.NOBUTTON);
        final Object action = getActionForDefault.invoke(
                null, boundingBox, actionManager, null, false, false, false, 30, null);
        final int code = ((Number) call(action, "a")).intValue();
        return code == 100 ? new Point(x, y) : null;
    }

    private int[] findDeformControlPointOnCanvas(final float[] positions, final Point moveHandle) throws Exception {
        final int numPoints = positions.length / 2;
        for (int idx = 0; idx < numPoints; idx++) {
            final Object vec = vector(positions[idx * 2], positions[idx * 2 + 1]);
            final Object mapped = call(cameraManager, "documentToComponent", new Class<?>[] {vector2Type}, vec);
            final float px = number(call(mapped, "getX"));
            final float py = number(call(mapped, "getY"));
            if (!Float.isFinite(px) || !Float.isFinite(py)) continue;
            final int x = Math.round(px);
            final int y = Math.round(py);
            if (!inCanvasBounds(x, y)) continue;
            if (moveHandle.distance(x, y) < 24.0) continue;

            // Hover at (x, y) and verify boundingBox does NOT intercept this point (code == 0)
            mouse(MouseEvent.MOUSE_MOVED, x, y, 0, MouseEvent.NOBUTTON);
            final Object action = getActionForDefault.invoke(
                    null, boundingBox, actionManager, null, false, false, false, 30, null);
            final int code = ((Number) call(action, "a")).intValue();
            if (code == 0) {
                return new int[] {x, y, idx};
            }
        }
        return null;
    }

    private boolean inCanvasBounds(final int x, final int y) {
        return x >= 60 && y >= 60 && x < width - 60 && y < height - 60;
    }

    private float[] readCurrentKeyformPositions() throws Exception {
        final Object form = call(targetWarp, "getCurrentKeyform");
        if (form == null) throw new IllegalStateException("target WarpDeformer has no active keyform");
        return ((float[]) call(form, "getPositions")).clone();
    }

    private float[] readCalculatedPositions() throws Exception {
        final Object form = call(targetWarp, "getCalculatedForm");
        if (form == null) throw new IllegalStateException("target WarpDeformer has no calculated form");
        return ((float[]) call(form, "getPositions")).clone();
    }

    private int undoPosition() throws Exception {
        return ((Number) call(undoManager, "getCurrentPos")).intValue();
    }

    private void discover() throws Exception {
        final List<Frame> matching = new ArrayList<>();
        for (Window w : Window.getWindows()) {
            if (w instanceof Frame frame && frame.isShowing() && frame.getTitle().contains(fixture)) {
                matching.add(frame);
            }
        }
        if (matching.size() != 1) throw new IllegalStateException("task window count=" + matching.size());
        window = matching.get(0);
        final List<JComponent> canvases = new ArrayList<>();
        collectCanvases(window, canvases, 0);
        if (canvases.size() != 1) throw new IllegalStateException("task GLJPanel count=" + canvases.size());
        canvas = canvases.get(0);
        width = canvas.getWidth();
        height = canvas.getHeight();
        if (width < 100 || height < 100) throw new IllegalStateException("canvas too small: " + width + "x" + height);

        final ClassLoader loader = canvas.getClass().getClassLoader();
        final Class<?> appType = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader);
        documentType = Class.forName("com.live2d.cubism.doc.IDocument", false, loader);
        vector2Type = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
        selectionType = Class.forName("com.live2d.doc.selection.ISelection", false, loader);
        boundingBoxClass = Class.forName("com.live2d.cubism.view.context.boundingBox.ACBoundingBox", false, loader);
        actionManagerNClass = Class.forName("com.live2d.cubism.view.context.actionManager.N", false, loader);
        getActionForDefault = boundingBoxClass.getMethod(
                "getActionFor$default",
                boundingBoxClass,
                actionManagerNClass,
                vector2Type,
                boolean.class,
                boolean.class,
                boolean.class,
                int.class,
                Object.class);

        app = appType.getMethod("access$get_instance$cp").invoke(null);
        doc = call(app, "getCurrentDoc");
        view = call(app, "getCurrentViewContext");
        model = call(view, "getModel");
        modelSource = call(model, "getSource");
        cameraManager = call(view, "getCameraManager");
        selector = call(doc, "getSelector");
        undoManager = call(doc, "getUndoManager");
        actionManager = call(view, "getActionManager");
        boundingBox = call(selector, "getBoundingBox");
    }

    private static void collectCanvases(final Component c, final List<JComponent> found, final int depth) {
        if (depth > 32 || !c.isShowing()) return;
        if (c.getClass().getName().equals("com.jogamp.opengl.awt.GLJPanel") && c instanceof JComponent jc) {
            found.add(jc);
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                collectCanvases(child, found, depth + 1);
            }
        }
    }

    private void acquireFocus() throws Exception {
        final var acquisition = NativeInteractionFocus.acquire(new NativeInteractionFocus.Driver() {
            @Override
            public NativeInteractionFocus.State observe() throws Exception {
                return edt(() -> NativeInteractionFocus.observe(canvas, window));
            }

            @Override
            public void requestWindow() throws Exception {
                edt(() -> {
                    window.toFront();
                    window.requestFocus();
                    return null;
                });
            }

            @Override
            public boolean requestCanvas() throws Exception {
                return edt(() -> canvas.requestFocusInWindow());
            }

            @Override
            public void pause() throws InterruptedException {
                Thread.sleep(50L);
            }

            @Override
            public long nanoTime() {
                return System.nanoTime();
            }
        }, TimeUnit.SECONDS.toNanos(3L));
        focusPreparation = acquisition.diagnostics();
        if (!acquisition.acquired()) {
            throw NativeInteractionFocus.refused(state, focusPreparation, acquisition.last());
        }
    }

    private void requireFocus() {
        if (!NativeInteractionFocus.owns(canvas, window)) {
            throw NativeInteractionFocus.refused(
                    state, focusPreparation, NativeInteractionFocus.observe(canvas, window));
        }
    }

    private void mouse(final int id, final int x, final int y, final int modifiers, final int button) {
        final Point origin = canvas.getLocationOnScreen();
        lastX = x;
        lastY = y;
        final int clicks = (id == MouseEvent.MOUSE_PRESSED
                || id == MouseEvent.MOUSE_RELEASED
                || id == MouseEvent.MOUSE_CLICKED) ? 1 : 0;
        canvas.dispatchEvent(new MouseEvent(
                canvas,
                id,
                System.currentTimeMillis(),
                modifiers,
                x,
                y,
                origin.x + x,
                origin.y + y,
                clicks,
                false,
                button));
    }

    private void releaseMouseIfDown() throws Exception {
        if (mouseDown) {
            mouseDown = false;
            edt(() -> {
                mouse(MouseEvent.MOUSE_RELEASED, lastX, lastY, 0, MouseEvent.BUTTON1);
                return null;
            });
            drain();
        }
    }

    private static void drain() throws Exception {
        edt(() -> null);
    }

    private static Map<String, Long> statsSlot(final String property) {
        final Object callback = System.getProperties().get(property);
        if (!(callback instanceof java.util.function.Supplier<?> supplier)
                || !(supplier.get() instanceof Map<?, ?> raw)) {
            return Map.of();
        }
        final Map<String, Long> result = new LinkedHashMap<>();
        raw.forEach((k, v) -> {
            if (k instanceof String key && v instanceof Number value) {
                result.put(key, value.longValue());
            }
        });
        return result;
    }

    private Object vector(final float x, final float y) throws Exception {
        return vector2Type.getConstructor(float.class, float.class).newInstance(x, y);
    }

    private static float number(final Object value) {
        return ((Number) value).floatValue();
    }

    private static Object call(final Object receiver, final String name) throws Exception {
        return call(receiver, name, new Class<?>[0]);
    }

    private static Object call(
            final Object receiver, final String name, final Class<?>[] types, final Object... args)
            throws Exception {
        try {
            return receiver.getClass().getMethod(name, types).invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof Exception ex) throw ex;
            if (failure.getCause() instanceof Error err) throw err;
            throw failure;
        }
    }

    private static <T> T edt(final Callable<T> action) throws Exception {
        return edt(action, 30L);
    }

    private static <T> T edt(final Callable<T> action, final long timeoutSeconds) throws Exception {
        if (EventQueue.isDispatchThread()) return action.call();
        final FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        return task.get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
