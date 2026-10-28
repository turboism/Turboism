package dev.turboism.validation.extendedinterpolation.host;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.deformer.rotation.CRotationDeformerForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformer;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerForm;
import com.live2d.cubism.doc.model.deformer.warp.CWarpDeformerSource;
import com.live2d.cubism.doc.model.drawable.artMesh.CArtMeshForm;
import com.live2d.cubism.doc.model.interpolator.KeyformBindingSource;
import com.live2d.cubism.doc.model.interpolator.KeyformGridSource;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.CExtendedInterpolationExtension;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.ExtendedInterpolationType;
import com.live2d.doc.selection.ISelectableObject;
import com.live2d.doc.selection.ISelection;
import com.live2d.doc.selection.ISelector;
import com.live2d.graphics3d.sceneGraph.GCameraManager;
import com.live2d.graphics3d.type.GVector2;
import com.live2d.type.CFormGuid;

import dev.turboism.validation.extendedinterpolation.host.ExtendedInterpolationHostProbe.ContextRef;
import dev.turboism.validation.extendedinterpolation.host.ExtendedInterpolationHostProbe.ScenarioMetrics;
import dev.turboism.validation.extendedinterpolation.host.ExtendedInterpolationHostProbe.TargetObjects;

import java.awt.event.MouseEvent;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;

/**
 * Verified 5.3.04 exact-host Warp drag validation and A/B verification helper.
 *
 * Exclusively implements Warp deformer interaction:
 *   1. Real source selection & visible center control point selection via live model & camera mapping.
 *   2. Official mouse event wrapper press / 8-drag / release on EDT with consumption verification.
 *   3. Geometry mutation assertions on current source keyform and all bank forms.
 *   4. Official Undo / Redo / Undo cycle with bitwise raw restoration verification across all banks.
 *   5. Paired A/B benchmarks across ELLIPSE and SNS_CURVE:
 *      - Native leg keeps optimization bridge disabled (bridge.enabled(false)) throughout drag,
 *        native frame barrier, and bank extraction.
 *      - Optimized leg enables bridge (bridge.enabled(true)), asserts accepted bridge invocations > 0,
 *        and compares post-mutation form raw bits against native oracle from identical pre-state.
 *      - Finally clause always restores bridge.enabled(true).
 */
public final class WarpDragValidation {

    public static final String BRIDGE_CLASS =
            "dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge";

    record WarpDragResult(ScenarioMetrics metrics, CFormGuid formGuid, int pointIndex,
                          float beforeX, float beforeY,
                          float afterX, float afterY,
                          float deltaX, float deltaY,
                          boolean mutated,
                          List<int[]> beforeBank, List<int[]> afterBank) {}

    private WarpDragValidation() {}

    /**
     * Executes the complete Warp drag validation scenario including standalone UI drag assertion,
     * undo/redo cycle bitwise recovery, and A/B benchmarks across ELLIPSE and SNS_CURVE.
     *
     * @param ctx      Live modeling context reference
     * @param targets  Audited target objects containing CWarpDeformerSource
     * @param result   Output properties recording metrics, counters, and proof keys
     * @throws Exception on any verification failure or invariant violation
     */
    public static void run(ContextRef ctx, TargetObjects targets, Properties result) throws Exception {
        if (ctx == null) {
            throw new IllegalArgumentException("ContextRef is null");
        }
        if (targets == null || targets.warp() == null) {
            throw new IllegalArgumentException("TargetObjects.warp is null");
        }
        if (result == null) {
            throw new IllegalArgumentException("Properties result is null");
        }

        Class<?> bridgeClass;
        try {
            bridgeClass = Class.forName(BRIDGE_CLASS);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Bridge class missing: " + BRIDGE_CLASS, e);
        }

        var threadBean = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        if (threadBean.isThreadAllocatedMemorySupported() && !threadBean.isThreadAllocatedMemoryEnabled()) {
            threadBean.setThreadAllocatedMemoryEnabled(true);
        }

        try {
            System.err.println("[EI-WARP-DRAG] Starting Warp Drag Validation...");

            // 1. Initial standalone Warp drag verification
            prepareAndSelectWarp(ctx, targets);
            WarpDragResult initialDrag = executeWarpViewDraggingWithAssertion(ctx, targets, threadBean, result);
            recordMetrics(result, "scenario.warpDrag", initialDrag.metrics());
            result.setProperty("scenario.warpDrag.mutatedDeltaX", Float.toString(initialDrag.deltaX()));
            result.setProperty("scenario.warpDrag.mutatedDeltaY", Float.toString(initialDrag.deltaY()));

            // 2. Undo / Redo lifecycle assertion on the standalone drag
            ScenarioMetrics undoRedoMetrics = executeUndoRedoCycleWithAssertion(ctx, targets, initialDrag, initialDrag.formGuid(), threadBean);
            recordMetrics(result, "scenario.warpUndoRedo", undoRedoMetrics);
            result.setProperty("scenario.warpUndoRedo.verifiedStatus", "ALL_WARP_BANKS_RAW_BITS_ATTRIBUTES_RESTORED");

            // Final Undo to return model to pristine state before benchmark
            edt(() -> {
                Method undo = ctx.appCtrl().getClass().getMethod("command_undo", Class.forName("com.live2d.cubism.doc.IDocument"));
                undo.invoke(ctx.appCtrl(), ctx.doc());
                CWarpDeformerSource current = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
                assertBankBits(current, initialDrag.beforeBank(), "Initial Warp Standalone Undo");
                return null;
            });
            awaitNativeFrame(ctx);

            // 3. A/B benchmark across ELLIPSE and SNS_CURVE
            executeWarpDragBenchmark(ctx, targets, bridgeClass, threadBean, result);

            result.setProperty("scenario.warpDrag.status", "PASS");
            System.err.println("[EI-WARP-DRAG] Warp Drag Validation completed successfully.");
        } finally {
            setBridgeEnabled(true);
        }
    }

    private static void prepareAndSelectWarp(ContextRef ctx, TargetObjects targets) throws Exception {
        long beforeFrame = edt(() -> ctx.model().getLastUpdatedTimeMSec());
        edt(() -> {
            ctx.doc().getModelSource().setDefaultKeyformLocked(false);
            targets.warp().setLocked(false);
            targets.warp().setVisible(true);
            for (var parent : targets.warp().getAllParents()) {
                parent.setLocked(false);
                parent.setVisible(true);
            }
            for (var parentDef : targets.warp().getAllParentDeformers()) {
                parentDef.setLocked(false);
                parentDef.setVisible(true);
            }
            ctx.doc().getModelSource().updateVisibleAndLockHierarchy();
            ctx.doc().getModelSource().updateModelInstances();
            selectObject(ctx, targets.warp());
            ctx.appCtrl().getClass().getMethod("command_showAllObject_InViewArea").invoke(ctx.appCtrl());
            ctx.viewContext().getClass().getMethod("repaintCanvas").invoke(ctx.viewContext());
            return null;
        });
        long frameDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (edt(() -> ctx.model().getLastUpdatedTimeMSec()) <= beforeFrame) {
            if (System.nanoTime() >= frameDeadline) {
                throw new IllegalStateException("No native model update after warp selection within 15s timeout");
            }
            Thread.sleep(50);
        }
    }

    private static WarpDragResult executeWarpViewDraggingWithAssertion(ContextRef ctx, TargetObjects targets,
                                                                      com.sun.management.ThreadMXBean threadBean,
                                                                      Properties result) throws Exception {
        return edt(() -> {
            CWarpDeformer liveWarp = ctx.model().getWarpDeformer(targets.warp().getGuid());
            if (liveWarp == null) {
                throw new IllegalStateException("CWarpDeformer not found in live model: " + targets.warp().getGuid());
            }
            CWarpDeformerForm form = liveWarp.getCurrentKeyform();
            if (form == null) {
                throw new IllegalStateException("Task parameter values do not select an editable source keyform for warp: " + targets.warp().getGuid());
            }
            CFormGuid formGuid = form.getGuid();
            float[] origPositions = form.getPositions();
            if (origPositions == null || origPositions.length < 2) {
                throw new IllegalStateException("Target WarpDeformer has no vertex positions!");
            }
            float[] beforePositions = origPositions.clone();

            CWarpDeformerSource preWarp = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
            if (preWarp == null) {
                throw new IllegalStateException("Target WarpDeformerSource missing from document: " + targets.warp().getGuid());
            }
            List<int[]> beforeBank = new ArrayList<>();
            for (ACForm bankForm : allForms(preWarp)) {
                beforeBank.add(formBits(bankForm));
            }

            // Hit a visible control point of the live warp deformer, using calculated form mapped to Canvas
            CWarpDeformerForm calculatedForm = liveWarp.getCalculatedForm();
            if (calculatedForm == null) {
                throw new IllegalStateException("Calculated form is null for warp: " + targets.warp().getGuid());
            }
            float[] canvasPositions = calculatedForm.getPositions();
            if (canvasPositions == null || canvasPositions.length < 2) {
                throw new IllegalStateException("Warp deformer has no calculated positions: " + targets.warp().getGuid());
            }
            int numPoints = canvasPositions.length / 2;

            int row = targets.warp().getRow();
            int col = targets.warp().getCol();
            float meanX = 0, meanY = 0;
            for (int i = 0; i < numPoints; i++) {
                meanX += canvasPositions[i * 2];
                meanY += canvasPositions[i * 2 + 1];
            }
            meanX /= numPoints;
            meanY /= numPoints;

            GCameraManager camera = (GCameraManager) ctx.camera();
            int canvasWidth = ctx.canvas().getWidth();
            int canvasHeight = ctx.canvas().getHeight();

            List<Integer> candidateIndices = new ArrayList<>(numPoints);
            for (int i = 0; i < numPoints; i++) {
                candidateIndices.add(i);
            }
            final int idealIndex = (row >= 0 && col >= 0 && (row + 1) * (col + 1) == numPoints)
                    ? (row / 2) * (col + 1) + (col / 2)
                    : numPoints / 2;

            final float cx = meanX;
            final float cy = meanY;
            candidateIndices.sort((a, b) -> {
                if (a.intValue() == idealIndex && b.intValue() != idealIndex) return -1;
                if (b.intValue() == idealIndex && a.intValue() != idealIndex) return 1;
                float distA = (canvasPositions[a * 2] - cx) * (canvasPositions[a * 2] - cx)
                        + (canvasPositions[a * 2 + 1] - cy) * (canvasPositions[a * 2 + 1] - cy);
                float distB = (canvasPositions[b * 2] - cx) * (canvasPositions[b * 2] - cx)
                        + (canvasPositions[b * 2 + 1] - cy) * (canvasPositions[b * 2 + 1] - cy);
                return Float.compare(distA, distB);
            });

            int chosenPoint = -1;
            int dragStartX = -1, dragStartY = -1;
            for (int idx : candidateIndices) {
                float x = canvasPositions[idx * 2];
                float y = canvasPositions[idx * 2 + 1];
                GVector2 mapped = camera.documentToComponent(new GVector2(x, y));
                if (Float.isFinite(mapped.getX()) && Float.isFinite(mapped.getY())
                        && mapped.getX() >= 24 && mapped.getY() >= 24
                        && mapped.getX() < canvasWidth - 64 && mapped.getY() < canvasHeight - 48) {
                    chosenPoint = idx;
                    dragStartX = Math.round(mapped.getX());
                    dragStartY = Math.round(mapped.getY());
                    break;
                }
            }

            if (dragStartX < 0 || chosenPoint < 0) {
                throw new IllegalStateException("No visible bounded control point in task edit warp. canvas="
                        + canvasWidth + "x" + canvasHeight + ", points=" + numPoints);
            }

            result.setProperty("scenario.warpDrag.formGuid", formGuid.getUuidString());
            result.setProperty("scenario.warpDrag.canvasSize", canvasWidth + "x" + canvasHeight);
            result.setProperty("scenario.warpDrag.calculatedPoints", Integer.toString(numPoints));
            result.setProperty("scenario.warpDrag.selectedPointIndex", Integer.toString(chosenPoint));
            result.setProperty("scenario.warpDrag.componentX", Integer.toString(dragStartX));
            result.setProperty("scenario.warpDrag.componentY", Integer.toString(dragStartY));
            result.setProperty("scenario.warpDrag.mapping", "CALCULATED_CANVAS_WARP_CONTROL_POINT");

            // MOUSE_MOVED hover
            Object hover = ctx.mouseCtor().newInstance(new MouseEvent(
                    ctx.canvas(), MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                    dragStartX, dragStartY, 0, false, MouseEvent.NOBUTTON));
            ctx.onInput().invoke(ctx.viewContext(), hover);

            // Real Mouse Drag Sequence on View Canvas
            long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long tStart = System.nanoTime();

            // PRESS
            MouseEvent pressEvent = new MouseEvent(
                    ctx.canvas(), MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(),
                    MouseEvent.BUTTON1_DOWN_MASK, dragStartX, dragStartY, 1, false, MouseEvent.BUTTON1);
            Object dragGesture = ctx.dragCtor().newInstance(pressEvent);
            ctx.onInput().invoke(ctx.viewContext(), dragGesture);
            verifyLastMouseEvent(ctx, dragGesture);

            // MULTI-STEP DRAG (8 steps, total 40px displacement X, 24px displacement Y)
            for (int step = 1; step <= 8; step++) {
                int curX = dragStartX + (step * 5);
                int curY = dragStartY + (step * 3);
                MouseEvent dragMove = new MouseEvent(
                        ctx.canvas(), MouseEvent.MOUSE_DRAGGED, System.currentTimeMillis(),
                        MouseEvent.BUTTON1_DOWN_MASK, curX, curY, 0, false, MouseEvent.NOBUTTON);
                ctx.dragUpdate().invoke(dragGesture, dragMove);
                ctx.onInput().invoke(ctx.viewContext(), dragGesture);
                verifyLastMouseEvent(ctx, dragGesture);
            }

            // RELEASE
            MouseEvent releaseEvent = new MouseEvent(
                    ctx.canvas(), MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(),
                    0, dragStartX + 40, dragStartY + 24, 1, false, MouseEvent.BUTTON1);
            ctx.dragRelease().invoke(dragGesture, releaseEvent);
            ctx.onInput().invoke(ctx.viewContext(), dragGesture);
            verifyLastMouseEvent(ctx, dragGesture);

            long tEnd = System.nanoTime();
            long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

            long latency = tEnd - tStart;
            long allocated = Math.max(0, tAllocEnd - tAllocStart);

            // Re-acquire fresh warp from document to read updated keyform
            CWarpDeformerSource currentWarp = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
            if (currentWarp == null) {
                throw new IllegalStateException("Warp deformer disappeared after drag: " + targets.warp().getGuid());
            }
            CWarpDeformerForm afterKeyform = (CWarpDeformerForm) currentWarp.getKeyForm(formGuid);
            if (afterKeyform == null) {
                throw new IllegalStateException("Keyform disappeared after drag: " + formGuid);
            }
            float[] afterPositions = afterKeyform.getPositions();

            int[] beforeBits = rawPositionBits(beforePositions);
            int[] afterBits = rawPositionBits(afterPositions);
            boolean mutated = !Arrays.equals(beforeBits, afterBits);

            float beforeX = beforePositions[chosenPoint * 2];
            float beforeY = beforePositions[chosenPoint * 2 + 1];
            float afterX = afterPositions[chosenPoint * 2];
            float afterY = afterPositions[chosenPoint * 2 + 1];

            if (!Float.isFinite(afterX) || !Float.isFinite(afterY)) {
                throw new AssertionError("Drag resulted in non-finite coordinates: " + afterX + ", " + afterY);
            }

            List<int[]> afterBank = new ArrayList<>();
            for (ACForm bankForm : allForms(currentWarp)) {
                afterBank.add(formBits(bankForm));
            }

            String verifiedStatus = mutated ? "FULL_UI_MUTATION_PROVEN" : "UNVERIFIED_GEOMETRY_UNCHANGED";
            result.setProperty("scenario.warpDrag.verifiedStatus", verifiedStatus);
            result.setProperty("scenario.warpDrag.mutated", Boolean.toString(mutated));

            ScenarioMetrics metrics = new ScenarioMetrics(mutated ? "PASS" : "UNVERIFIED", 1, allocated, latency, latency, latency, latency, latency);
            return new WarpDragResult(metrics, formGuid, chosenPoint, beforeX, beforeY, afterX, afterY, afterX - beforeX, afterY - beforeY, mutated, beforeBank, afterBank);
        });
    }

    private static ScenarioMetrics executeUndoRedoCycleWithAssertion(ContextRef ctx, TargetObjects targets,
                                                                    WarpDragResult drag,
                                                                    CFormGuid formGuid,
                                                                    com.sun.management.ThreadMXBean threadBean) throws Exception {
        if (!drag.mutated()) {
            throw new AssertionError("Cannot perform Undo/Redo assertion: drag did not mutate geometry!");
        }

        List<Long> latencies = new ArrayList<>();
        AtomicLong allocTotal = new AtomicLong();

        edt(() -> {
            Method undo = ctx.appCtrl().getClass().getMethod("command_undo", Class.forName("com.live2d.cubism.doc.IDocument"));
            Method redo = ctx.appCtrl().getClass().getMethod("command_redo", Class.forName("com.live2d.cubism.doc.IDocument"));

            long tAllocStart = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());
            long tStart = System.nanoTime();

            // 1. Perform Undo
            undo.invoke(ctx.appCtrl(), ctx.doc());
            ctx.viewContext().getClass().getMethod("repaintCanvas").invoke(ctx.viewContext());

            CWarpDeformerSource currentWarpUndo = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
            if (currentWarpUndo == null) {
                throw new IllegalStateException("Warp disappeared after undo: " + targets.warp().getGuid());
            }
            CWarpDeformerForm undoForm = (CWarpDeformerForm) currentWarpUndo.getKeyForm(formGuid);
            float[] undoPositions = undoForm.getPositions();
            float undoX = undoPositions[drag.pointIndex() * 2];
            float undoY = undoPositions[drag.pointIndex() * 2 + 1];

            if (Float.floatToRawIntBits(undoX) != Float.floatToRawIntBits(drag.beforeX())
                    || Float.floatToRawIntBits(undoY) != Float.floatToRawIntBits(drag.beforeY())) {
                throw new AssertionError("Undo failed to restore initial geometry bits! Expected: ("
                        + drag.beforeX() + "," + drag.beforeY() + ") but got: (" + undoX + "," + undoY + ")");
            }

            assertBankBits(currentWarpUndo, drag.beforeBank(), "Undo");

            // 2. Perform Redo
            redo.invoke(ctx.appCtrl(), ctx.doc());
            ctx.viewContext().getClass().getMethod("repaintCanvas").invoke(ctx.viewContext());

            CWarpDeformerSource currentWarpRedo = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
            if (currentWarpRedo == null) {
                throw new IllegalStateException("Warp disappeared after redo: " + targets.warp().getGuid());
            }
            CWarpDeformerForm redoForm = (CWarpDeformerForm) currentWarpRedo.getKeyForm(formGuid);
            float[] redoPositions = redoForm.getPositions();
            float redoX = redoPositions[drag.pointIndex() * 2];
            float redoY = redoPositions[drag.pointIndex() * 2 + 1];

            if (Float.floatToRawIntBits(redoX) != Float.floatToRawIntBits(drag.afterX())
                    || Float.floatToRawIntBits(redoY) != Float.floatToRawIntBits(drag.afterY())) {
                throw new AssertionError("Redo failed to reapply mutated geometry bits! Expected: ("
                        + drag.afterX() + "," + drag.afterY() + ") but got: (" + redoX + "," + redoY + ")");
            }

            assertBankBits(currentWarpRedo, drag.afterBank(), "Redo");

            long tEnd = System.nanoTime();
            long tAllocEnd = threadBean.getThreadAllocatedBytes(Thread.currentThread().getId());

            latencies.add(tEnd - tStart);
            allocTotal.addAndGet(Math.max(0, tAllocEnd - tAllocStart));
            return null;
        });

        return computeMetrics(latencies, allocTotal.get(), "PASS");
    }

    private static void executeWarpDragBenchmark(ContextRef ctx, TargetObjects targets, Class<?> bridge,
                                                 com.sun.management.ThreadMXBean threadBean,
                                                 Properties result) throws Exception {
        int warmup = Integer.getInteger("turboism.validation.warp.warmup", 3);
        int samples = Integer.getInteger("turboism.validation.warp.samples", 12);
        if (samples < 1) samples = 1;
        if (warmup < 0) warmup = 0;

        for (ExtendedInterpolationType curve : new ExtendedInterpolationType[]{
                ExtendedInterpolationType.ELLIPSE,
                ExtendedInterpolationType.SNS_CURVE}) {

            // Apply curve configuration to all controllable sources
            edt(() -> {
                for (ACParameterControllableSource owner : List.of(targets.rotation(), targets.warp(), targets.artMesh())) {
                    KeyformGridSource grid = owner.getKeyformGridSource();
                    if (grid != null && grid.getKeyformBindings() != null) {
                        for (KeyformBindingSource binding : grid.getKeyformBindings()) {
                            binding.setExtendedInterpolationType(curve);
                        }
                    }
                    for (Object extension : owner.getExtensions()) {
                        if (extension instanceof CExtendedInterpolationExtension ext) {
                            ext.setupFormStructure(true, true);
                        }
                    }
                }
                ctx.doc().getModelSource().updateModelInstances();
                ctx.model().updateParamInstance();
                return null;
            });
            awaitNativeFrame(ctx);

            List<Long> nativeTimes = new ArrayList<>();
            List<Long> optimizedTimes = new ArrayList<>();
            long nativeAlloc = 0, optimizedAlloc = 0;
            long nativeBridgeCalls = 0, optimizedBridgeCalls = 0;

            for (int iteration = -warmup; iteration < samples; iteration++) {
                List<int[]> oracle = null;

                for (boolean optimized : new boolean[]{false, true}) {
                    setBridgeEnabled(optimized);
                    long accepted = readBridgeCounter(bridge, "acceptedCalls");
                    long start = System.nanoTime();

                    prepareAndSelectWarp(ctx, targets);
                    WarpDragResult drag = executeWarpViewDraggingWithAssertion(ctx, targets, threadBean, result);
                    if (!drag.mutated()) {
                        throw new AssertionError("Warp A/B drag did not mutate geometry for curve " + curve + " (iteration " + iteration + ", optimized=" + optimized + ")");
                    }

                    // Native frame barrier: keep native leg with bridge.enabled(false) throughout!
                    awaitNativeFrame(ctx);
                    long elapsed = System.nanoTime() - start;

                    List<int[]> actual = edt(() -> {
                        CWarpDeformerSource current = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
                        List<int[]> bits = new ArrayList<>();
                        for (ACForm form : allForms(current)) {
                            bits.add(formBits(form));
                        }
                        return bits;
                    });

                    if (!optimized) {
                        oracle = actual;
                    } else {
                        if (oracle.size() != actual.size()) {
                            throw new AssertionError("Warp A/B drag bank size mismatch: oracle=" + oracle.size() + ", actual=" + actual.size());
                        }
                        for (int i = 0; i < actual.size(); i++) {
                            if (!Arrays.equals(oracle.get(i), actual.get(i))) {
                                throw new AssertionError("Warp A/B drag raw bits differ at form " + i + " for curve " + curve);
                            }
                        }
                    }

                    long calls = readBridgeCounter(bridge, "acceptedCalls") - accepted;
                    if (!optimized && calls != 0) {
                        throw new AssertionError("Native Warp A/B leg used optimization bridge! calls=" + calls);
                    }
                    if (optimized && calls <= 0) {
                        throw new AssertionError("Optimized Warp A/B leg did not use bridge! calls=" + calls);
                    }

                    // Undo/Redo cycle with exact coordinate and bank restoration assertion
                    executeUndoRedoCycleWithAssertion(ctx, targets, drag, drag.formGuid(), threadBean);

                    // Final Undo to reset document to exact identical pre-drag state
                    edt(() -> {
                        Method undo = ctx.appCtrl().getClass().getMethod("command_undo", Class.forName("com.live2d.cubism.doc.IDocument"));
                        undo.invoke(ctx.appCtrl(), ctx.doc());
                        CWarpDeformerSource current = (CWarpDeformerSource) ctx.doc().getModelSource().getObject(targets.warp().getGuid(), false);
                        assertBankBits(current, drag.beforeBank(), "Warp A/B final Undo");
                        return null;
                    });
                    awaitNativeFrame(ctx);

                    if (iteration >= 0) {
                        if (optimized) {
                            optimizedTimes.add(elapsed);
                            optimizedAlloc += drag.metrics().allocatedBytes();
                            optimizedBridgeCalls += calls;
                        } else {
                            nativeTimes.add(elapsed);
                            nativeAlloc += drag.metrics().allocatedBytes();
                            nativeBridgeCalls += calls;
                        }
                    }
                }
            }

            String prefix = "scenario.warpDragAB." + curve.name();
            recordMetrics(result, prefix + ".native", computeMetrics(nativeTimes, nativeAlloc, "PASS"));
            recordMetrics(result, prefix + ".optimized", computeMetrics(optimizedTimes, optimizedAlloc, "PASS"));
            result.setProperty(prefix + ".warmupIterations", Integer.toString(warmup));
            result.setProperty(prefix + ".sampleIterations", Integer.toString(samples));
            result.setProperty(prefix + ".totalPairs", Integer.toString(warmup + samples));
            result.setProperty(prefix + ".native.acceptedDelta", Long.toString(nativeBridgeCalls));
            result.setProperty(prefix + ".optimized.acceptedDelta", Long.toString(optimizedBridgeCalls));
            result.setProperty(prefix + ".scope", samples + "_PAIRED_REAL_WARP_DRAGS_PREPARATION_AND_NATIVE_FRAME_BARRIER_INCLUDED_NOT_FPS");
            result.setProperty(prefix + ".allocationScope", "MEASURED_EVENT_SEQUENCE_EDT_ONLY_NOT_FULL_OPERATION");
            result.setProperty(prefix + ".bankProof", "ALL_WARP_BANKS_NATIVE_OPTIMIZED_RAW_BITS_AND_HISTORY_MATCH");
            result.setProperty(prefix + ".verifiedStatus", "PASS");
        }
    }

    private static void awaitNativeFrame(ContextRef ctx) throws Exception {
        long before = edt(() -> {
            long timestamp = ctx.model().getLastUpdatedTimeMSec();
            ctx.doc().getModelSource().updateModelInstances();
            ctx.model().updateParamInstance();
            ctx.viewContext().getClass().getMethod("repaintCanvas").invoke(ctx.viewContext());
            return timestamp;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (edt(() -> ctx.model().getLastUpdatedTimeMSec()) <= before) {
            if (System.nanoTime() >= deadline) {
                throw new IllegalStateException("No native model evaluation after repaint within 15s timeout");
            }
            Thread.sleep(50);
        }
    }

    private static void selectObject(ContextRef ctx, Object targetSource) throws Exception {
        ISelector selector = ctx.selector();
        ISelection selection;
        if (targetSource instanceof ISelectableObject iso) {
            selection = iso.getSelection();
        } else {
            Method getSel = targetSource.getClass().getMethod("getSelection");
            selection = (ISelection) getSel.invoke(targetSource);
        }
        selector.clearSelection();
        selector.select(selection, false);
    }

    private static void verifyLastMouseEvent(ContextRef ctx, Object expectedEvent) throws Exception {
        Object last = ctx.viewContext().getClass().getMethod("getLastMouseEvent").invoke(ctx.viewContext());
        if (last != expectedEvent) {
            throw new AssertionError("View did not consume the task mouse event! Expected: "
                    + expectedEvent + " but was: " + last);
        }
    }

    private static void setBridgeEnabled(boolean enabled) throws Exception {
        Class.forName(BRIDGE_CLASS).getMethod("enabled", boolean.class).invoke(null, enabled);
    }

    private static long readBridgeCounter(Class<?> bridgeClass, String methodName) throws Exception {
        return ((Number) bridgeClass.getMethod(methodName).invoke(null)).longValue();
    }

    private static List<ACForm> allForms(ACParameterControllableSource owner) {
        List<ACForm> forms = new ArrayList<>();
        if (owner == null) return forms;
        for (Object form : owner.getKeyforms()) {
            forms.add((ACForm) form);
        }
        for (Object extension : owner.getExtensions()) {
            if (extension instanceof CExtendedInterpolationExtension ext) {
                if (ext.getNormalExtendedKeyforms() != null) {
                    forms.addAll(ext.getNormalExtendedKeyforms());
                }
                if (ext.getMorphTargetExtendedKeyforms() != null) {
                    forms.addAll(ext.getMorphTargetExtendedKeyforms());
                }
            }
        }
        return forms;
    }

    private static float[] formCoordinates(ACForm form) {
        if (form instanceof CArtMeshForm mesh) return mesh.getPositions();
        if (form instanceof CWarpDeformerForm warp) return warp.getPositions();
        if (form instanceof CRotationDeformerForm rotation) return new float[]{rotation.getOriginX(), rotation.getOriginY()};
        return new float[0];
    }

    private static int[] formBits(ACForm form) {
        float[] coordinates = formCoordinates(form);
        int[] bits = new int[coordinates.length + 4];
        for (int i = 0; i < coordinates.length; i++) {
            bits[i] = Float.floatToRawIntBits(coordinates[i]);
        }
        float opacity = form instanceof CArtMeshForm mesh ? mesh.getOpacity() :
                form instanceof CWarpDeformerForm warp ? warp.getOpacity() :
                form instanceof CRotationDeformerForm rot ? rot.getOpacity() : 1.0f;
        bits[coordinates.length] = Float.floatToRawIntBits(opacity);
        if (form instanceof CRotationDeformerForm rotation) {
            bits[coordinates.length + 1] = Float.floatToRawIntBits(rotation.getAngle());
            bits[coordinates.length + 2] = Float.floatToRawIntBits(rotation.getScale());
        }
        return bits;
    }

    private static int[] rawPositionBits(float[] points) {
        if (points == null) return new int[0];
        int[] bits = new int[points.length];
        for (int i = 0; i < points.length; i++) {
            bits[i] = Float.floatToRawIntBits(points[i]);
        }
        return bits;
    }

    private static void assertBankBits(ACParameterControllableSource owner, List<int[]> expected, String phase) {
        List<ACForm> forms = allForms(owner);
        if (forms.size() != expected.size()) {
            throw new AssertionError(phase + " changed bank size: expected " + expected.size() + " but was " + forms.size());
        }
        for (int i = 0; i < forms.size(); i++) {
            int[] actual = formBits(forms.get(i));
            if (!Arrays.equals(expected.get(i), actual)) {
                throw new AssertionError(phase + " bank mismatch at form " + i);
            }
        }
    }

    private static void recordMetrics(Properties result, String prefix, ScenarioMetrics m) {
        result.setProperty(prefix + ".status", m.status());
        result.setProperty(prefix + ".iterations", Integer.toString(m.iterations()));
        result.setProperty(prefix + ".allocatedBytes", Long.toString(m.allocatedBytes()));
        result.setProperty(prefix + ".minNs", Long.toString(m.minNs()));
        result.setProperty(prefix + ".p50Ns", Long.toString(m.p50Ns()));
        result.setProperty(prefix + ".p95Ns", Long.toString(m.p95Ns()));
        result.setProperty(prefix + ".p99Ns", Long.toString(m.p99Ns()));
        result.setProperty(prefix + ".maxNs", Long.toString(m.maxNs()));
    }

    private static ScenarioMetrics computeMetrics(List<Long> latencies, long allocBytes, String status) {
        if (latencies.isEmpty()) {
            return new ScenarioMetrics(status, 0, allocBytes, 0, 0, 0, 0, 0);
        }
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();
        long min = sorted.get(0);
        long max = sorted.get(n - 1);
        long p50 = sorted.get((int) Math.round((n - 1) * 0.50));
        long p95 = sorted.get((int) Math.round((n - 1) * 0.95));
        long p99 = sorted.get((int) Math.round((n - 1) * 0.99));
        return new ScenarioMetrics(status, n, allocBytes, min, p50, p95, p99, max);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
