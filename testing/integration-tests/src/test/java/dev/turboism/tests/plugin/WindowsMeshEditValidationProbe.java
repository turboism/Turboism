package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshEditResult;
import dev.turboism.sdk.cubism.mesh.MeshEditService;
import dev.turboism.sdk.cubism.mesh.MeshPointPosition;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.plugin.PluginContext;
import java.awt.Component;
import java.awt.Container;
import java.awt.Frame;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.AbstractButton;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;

/** Manual-test-only probe for direct mesh authoring and native selection-brush acceptance. */
public final class WindowsMeshEditValidationProbe implements CubismPlugin {

    static final String MODE_PROPERTY = "turboism.meshEditValidation.mode";
    static final String EXIT_PROPERTY = "turboism.validation.exitOnComplete";
    static final long SNAPSHOT_TIMEOUT_MILLIS = 120_000L;
    static final long SAVE_TIMEOUT_MILLIS = 30_000L;
    static final long SAVE_POLL_MILLIS = 100L;
    static final int SAVE_STABLE_SAMPLES = 3;
    private static final Set<String> SELECTION_BRUSH_VERSIONS = Set.of("5203", "5302", "5303");
    private static final float EPSILON = 0.0001F;

    private final AtomicReference<MeshSnapshot> failureBaseline = new AtomicReference<>();
    private final AtomicReference<byte[]> persistenceFileBaseline = new AtomicReference<>();
    private volatile boolean persistenceFileWritten;
    private PluginContext context;
    private Thread worker;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
        context.logger().info("Windows mesh edit validation probe initialized");
    }

    @Override
    public void enable() {
        worker = new Thread(this::run, "turboism-mesh-edit-validation");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        if (worker != null) worker.interrupt();
    }

    private void run() {
        final String mode = System.getProperty(MODE_PROPERTY, "matrix");
        final Path result = context.paths().stateDir().resolve("mesh-edit-host-validation.properties");
        final List<String> report = new ArrayList<>();
        report.add("schemaVersion=1");
        report.add("runId=" + safe(System.getProperty("turboism.validation.runId", "unknown")));
        report.add("mode=" + safe(mode));
        boolean passed = false;
        try {
            switch (mode) {
                case "matrix" -> runMatrix(report);
                case "persistence" -> runPersistence(report);
                case "selection-brush" -> {
                    new WindowsModelingSelectionBrushProbe(this, context, report).run();
                    runSelectionBrush(report);
                }
                default -> throw new IllegalArgumentException("unsupported mesh validation mode: " + mode);
            }
            passed = true;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            report.add("error=" + safe(failureDescription(failure)));
            cleanupAfterFailure(report);
        }
        report.add("status=" + (passed ? "PASS" : "FAIL"));
        boolean terminalPublished = false;
        try {
            Files.createDirectories(result.getParent());
            Files.writeString(
                    result,
                    String.join(System.lineSeparator(), report) + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            terminalPublished = true;
            context.logger()
                    .info("MESH_EDIT_HOST_VALIDATION_RESULT status=" + (passed ? "PASS" : "FAIL") + " mode=" + mode
                            + " result=" + result);
        } catch (Exception publicationFailure) {
            context.logger().error("Mesh edit validation result could not be written", publicationFailure);
        } finally {
            if (Boolean.getBoolean(EXIT_PROPERTY)) requestHostClose(terminalPublished);
        }
    }

    private void runMatrix(final List<String> report) throws Exception {
        final MeshEditService edit =
                context.services().find(MeshEditService.class).orElse(MeshEditService.unavailable());
        final MeshSnapshot original = awaitEditableMesh(report);
        failureBaseline.set(original);
        require(report, "fixture.points", original.points().size() >= 2, original.toString());
        require(report, "fixture.edges", !original.edges().isEmpty(), original.toString());
        final MeshPointPosition addedPosition = distinctPosition(original);

        final MeshSnapshot afterAddPoint = mutate(
                report,
                "addPoint",
                original,
                () -> edit.addPoints(List.of(addedPosition)),
                snapshot -> isExactPointAddition(original, snapshot, addedPosition));
        final MeshPointRef added = discoverAddedPoint(original, afterAddPoint, addedPosition);
        undoRedo(report, "addPoint", original, afterAddPoint);

        final MeshPointRef moveSource = pointWithConnectedEdge(original);
        final MeshPointRef moved = movedPoint(moveSource, afterAddPoint);
        final MeshSnapshot afterMove = mutate(
                report,
                "movePoint",
                afterAddPoint,
                () -> edit.movePoints(List.of(moved)),
                snapshot -> isExactPointMove(afterAddPoint, snapshot, moved));
        undoRedo(report, "movePoint", afterAddPoint, afterMove);
        require(
                report,
                "movePoint.connectedEdges",
                connectedEdges(original, moveSource.id()).equals(connectedEdges(afterMove, moveSource.id())),
                "before=" + connectedEdges(original, moveSource.id()) + " after="
                        + connectedEdges(afterMove, moveSource.id()));

        final MeshPointRef addEdgePartner = chooseEdgePartner(afterMove, added.id());
        final MeshEdgeRef addedEdge = new MeshEdgeRef(added.id(), addEdgePartner.id(), MeshEdgeKind.INNER);
        final MeshSnapshot afterAddEdge = mutate(
                report,
                "addEdge",
                afterMove,
                () -> edit.addEdges(List.of(addedEdge)),
                snapshot -> isExactEdgeAddition(afterMove, snapshot, addedEdge));
        undoRedo(report, "addEdge", afterMove, afterAddEdge);

        final MeshSnapshot afterDeleteEdge = mutate(
                report,
                "deleteEdge",
                afterAddEdge,
                () -> edit.deleteEdges(List.of(addedEdge)),
                snapshot -> isExactEdgeDeletion(afterAddEdge, snapshot, addedEdge));
        undoRedo(report, "deleteEdge", afterAddEdge, afterDeleteEdge);

        final MeshSnapshot afterDeletePoint = mutate(
                report,
                "deletePoint",
                afterDeleteEdge,
                () -> edit.deletePoints(List.of(added)),
                snapshot -> isExactPointDeletion(afterDeleteEdge, snapshot, added.id()));
        undoRedo(report, "deletePoint", afterDeleteEdge, afterDeletePoint);

        executeCommand(report, "cleanup.deletePoint", EditorCommand.UNDO);
        require(
                report,
                "cleanup.afterDeletePointUndo",
                awaitSnapshot(afterDeleteEdge::equals, "cleanup delete point undo")
                        .equals(afterDeleteEdge),
                afterDeleteEdge.toString());
        executeCommand(report, "cleanup.deleteEdge", EditorCommand.UNDO);
        require(
                report,
                "cleanup.afterDeleteEdgeUndo",
                awaitSnapshot(afterAddEdge::equals, "cleanup delete edge undo").equals(afterAddEdge),
                afterAddEdge.toString());
        executeCommand(report, "cleanup.addEdge", EditorCommand.UNDO);
        require(
                report,
                "cleanup.afterAddEdgeUndo",
                awaitSnapshot(afterMove::equals, "cleanup add edge undo").equals(afterMove),
                afterMove.toString());
        executeCommand(report, "cleanup.movePoint", EditorCommand.UNDO);
        require(
                report,
                "cleanup.afterMoveUndo",
                awaitSnapshot(afterAddPoint::equals, "cleanup move point undo").equals(afterAddPoint),
                afterAddPoint.toString());
        executeCommand(report, "cleanup.addPoint", EditorCommand.UNDO);
        final MeshSnapshot restored = awaitSnapshot(original::equals, "cleanup add point undo");
        require(report, "cleanup.restored", restored.equals(original), restored.toString());
        finishMeshEditIfActive(report);
        require(
                report,
                "cleanup.meshEditorExited",
                context.services()
                        .find(MeshEditService.class)
                        .orElse(MeshEditService.unavailable())
                        .snapshot()
                        .points()
                        .isEmpty(),
                context.services()
                        .find(MeshEditService.class)
                        .orElse(MeshEditService.unavailable())
                        .snapshot()
                        .toString());
        report.add("original.points=" + original.points().size());
        report.add("original.edges=" + original.edges().size());
        report.add("assignedPointId=" + added.id());
        failureBaseline.set(null);
    }

    private void runSelectionBrush(final List<String> report) throws Exception {
        final String version = System.getProperty("turboism.validation.cubismVersion", "");
        require(report, "selectionBrush.version", SELECTION_BRUSH_VERSIONS.contains(version), version);
        report.add("cubismVersion=" + safe(version));

        final MeshSnapshot geometryBefore = awaitEditableMesh(report);
        failureBaseline.set(geometryBefore);
        final Object session = awaitNativeMeshSession();
        reportNativeProjection(report, session);
        final JComponent view = onEdt(() -> (JComponent) invokeNoArgs(invokeNoArgs(session, "identity"), "component"));
        report.add("input.windowOrigin=" + awaitRobotWindowOrigin(view));
        final VertexSelection selectionBefore = awaitNativeSelection(session);
        final var historyBefore = onEdt(() -> context.cubism().history().snapshot());
        require(
                report,
                "selectionBrush.historyAvailable",
                historyBefore.availability() == dev.turboism.sdk.cubism.history.HistorySnapshot.Availability.AVAILABLE,
                historyBefore.toString());
        final var dirtyBefore = onEdt(() ->
                context
                        .services()
                        .require(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class)
                        .statuses()
                        .stream()
                        .map(status -> status.documentName() + ":" + status.modifiedAfterSaving())
                        .sorted()
                        .toList());
        require(report, "selectionBrush.dirtyAvailable", !dirtyBefore.isEmpty(), dirtyBefore.toString());
        final boolean redoBefore = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .available()
                .contains(EditorCommand.REDO);
        final boolean undoBefore = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .available()
                .contains(EditorCommand.UNDO);
        report.add("selection.before=" + safe(selectionBefore.indices().toString()));
        report.add("undo.available.before=" + undoBefore);

        final BrushControls controls = awaitBrushControls();
        if (!onEdt(() -> controls.button().isShowing())) {
            executeCommand(report, "selectionBrush.showToolPalette", EditorCommand.SHOW_TOOL_PALETTE);
        }
        requireBrushControlsVisible(controls, report, "beforeActivation");
        report.add("tool.count=" + controls.toolCount());
        report.add("slider.count=" + controls.sliderCount());
        report.add("slider.minimum=" + controls.minimum());
        report.add("slider.maximum=" + controls.maximum());
        report.add("slider.default=" + controls.value());
        require(report, "selectionBrush.toolCount", controls.toolCount() == 1, controls.toString());
        require(report, "selectionBrush.sliderCount", controls.sliderCount() == 1, controls.toString());
        require(
                report,
                "selectionBrush.sliderRange",
                controls.minimum() == 8 && controls.maximum() == 128,
                controls.toString());
        require(report, "selectionBrush.sliderDefault", controls.value() == 32, controls.toString());
        setSliderValue(controls.slider(), 8);
        require(report, "selectionBrush.sliderMinimum", sliderValue(controls.slider()) == 8, controls.toString());
        setSliderValue(controls.slider(), 128);
        require(report, "selectionBrush.sliderMaximum", sliderValue(controls.slider()) == 128, controls.toString());
        setSliderValue(controls.slider(), 8);

        clickBrushTool(controls.button());
        final JComponent overlay = awaitOverlay(1);
        requireBrushControlsVisible(awaitBrushControls(), report, "afterActivation");
        require(
                report,
                "selectionBrush.radiusAfterActivation",
                awaitBrushControls().value() == 8,
                "activation reset the displayed radius");
        final StrokeTarget target = projectedTarget(session, selectionBefore, overlay);
        report.add("selection.targetIndex=" + target.index());
        dispatchStroke(overlay, target.point(), InputEvent.BUTTON1_DOWN_MASK);
        final VertexSelection selectionAfter = awaitSelectionContains(target.index());
        report.add("selection.after=" + safe(selectionAfter.indices().toString()));
        require(
                report,
                "selectionBrush.selected",
                selectionAfter.indices().contains(target.index()),
                selectionAfter.toString());
        require(
                report,
                "selectionBrush.selectionChanged",
                !selectionAfter.equals(selectionBefore),
                "before=" + selectionBefore + " after=" + selectionAfter);

        setSliderValue(awaitBrushControls().slider(), 8);
        final GesturePair pair = separatedPair(session, overlay, 8, target.index());
        report.add("gesture.separatedPair=" + (pair != null));
        require(
                report,
                "selectionBrush.gesturePair",
                pair != null,
                "the fixture exposes no pair of projectable vertices further apart than 2r+2");

        dispatchStroke(overlay, pair.firstPoint(), InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        final VertexSelection shiftAdded = awaitSelectionContains(pair.first());
        report.add("gesture.shiftAdded=" + pair.first());
        require(report, "selectionBrush.shiftAdds", shiftAdded.indices().contains(pair.first()), shiftAdded.toString());
        report.add("gesture.shiftPreservedPrior=" + shiftAdded.indices().contains(target.index()));
        require(
                report,
                "selectionBrush.shiftPreservesPrior",
                shiftAdded.indices().contains(target.index()),
                "a Shift stroke must add to the existing native selection: " + shiftAdded);

        dispatchStroke(overlay, pair.secondPoint(), InputEvent.BUTTON1_DOWN_MASK);
        final VertexSelection replacedByPlainStroke = awaitSelectionContains(pair.second());
        report.add("gesture.replaceSelected=" + pair.second());
        require(
                report,
                "selectionBrush.replaceSelectsHit",
                replacedByPlainStroke.indices().contains(pair.second()),
                replacedByPlainStroke.toString());
        report.add("gesture.replaceDroppedPrior="
                + !replacedByPlainStroke.indices().contains(pair.first()));
        require(
                report,
                "selectionBrush.replaceDropsPrior",
                !replacedByPlainStroke.indices().contains(pair.first()),
                "an unmodified stroke must replace the native selection: " + replacedByPlainStroke);

        dispatchStroke(overlay, pair.firstPoint(), InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        final VertexSelection unionAgain = awaitSelectionContains(pair.first());
        report.add("gesture.unionKeptSecond=" + unionAgain.indices().contains(pair.second()));
        require(
                report,
                "selectionBrush.shiftPreservesSecond",
                unionAgain.indices().contains(pair.second()),
                "a Shift stroke must keep the vertices selected by the previous stroke: " + unionAgain);

        dispatchStroke(overlay, pair.secondPoint(), InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK);
        final VertexSelection removed = awaitSelectionExcludes(pair.second());
        report.add("gesture.ctrlRemoved=" + pair.second());
        require(
                report,
                "selectionBrush.ctrlRemoves",
                !removed.indices().contains(pair.second()),
                "a Ctrl stroke must deselect its hits: " + removed);
        report.add("gesture.ctrlKeptFirst=" + removed.indices().contains(pair.first()));
        require(
                report,
                "selectionBrush.ctrlKeepsOthers",
                removed.indices().contains(pair.first()),
                "a Ctrl stroke must not disturb the rest of the native selection: " + removed);
        dispatchStroke(
                overlay,
                pair.firstPoint(),
                InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        require(
                report,
                "selectionBrush.ctrlShiftRemoves",
                !awaitSelectionExcludes(pair.first()).indices().contains(pair.first()),
                "Ctrl must win over Shift");
        runBrushInputAssertions(session, awaitBrushControls(), overlay, report);
        proveBrushCancellation(session, overlay, report);

        final MeshSnapshot geometryAfter = context.services()
                .find(MeshEditService.class)
                .orElse(MeshEditService.unavailable())
                .snapshot();
        final boolean geometryUnchanged = geometryBefore.equals(geometryAfter);
        final boolean undoAfter = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .available()
                .contains(EditorCommand.UNDO);
        report.add("geometry.unchanged=" + geometryUnchanged);
        report.add("undo.available.after=" + undoAfter);
        require(report, "selectionBrush.geometryUnchanged", geometryUnchanged, geometryAfter.toString());
        require(
                report,
                "selectionBrush.undoUnchanged",
                undoBefore == undoAfter,
                "before=" + undoBefore + " after=" + undoAfter);

        final var dirtyAfter = onEdt(() ->
                context
                        .services()
                        .require(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class)
                        .statuses()
                        .stream()
                        .map(status -> status.documentName() + ":" + status.modifiedAfterSaving())
                        .sorted()
                        .toList());
        require(report, "selectionBrush.dirtyUnchanged", dirtyBefore.equals(dirtyAfter), dirtyAfter.toString());
        final var historyAfter = onEdt(() -> context.cubism().history().snapshot());
        require(report, "selectionBrush.historyUnchanged", historyBefore.equals(historyAfter), historyAfter.toString());
        require(
                report,
                "selectionBrush.redoUnchanged",
                redoBefore
                        == context.services()
                                .find(EditorCommandService.class)
                                .orElse(EditorCommandService.unavailable())
                                .available()
                                .contains(EditorCommand.REDO),
                "Redo availability changed");
        proveNativeConfirmationClick(session, awaitBrushControls().button(), report);
        final MeshSnapshot rebuiltGeometry = awaitEditableMesh(report);
        require(
                report,
                "selectionBrush.rebuildGeometry",
                rebuiltGeometry.equals(geometryBefore),
                rebuiltGeometry.toString());
        final BrushControls rebuilt = awaitBrushControls();
        requireBrushControlsVisible(rebuilt, report, "afterSessionRebuild");
        require(
                report,
                "selectionBrush.radiusAfterSessionRebuild",
                rebuilt.value() == 8,
                "session rebuild reset the displayed radius");
        final boolean replaced = controls.button() != rebuilt.button() && controls.slider() != rebuilt.slider();
        report.add("rebuild.replaced=" + replaced);
        require(report, "selectionBrush.rebuildReplaced", replaced, "before=" + controls + " after=" + rebuilt);
        clickBrushTool(rebuilt.button());
        final JComponent rebuiltOverlay = awaitOverlay(1);
        require(
                report,
                "selectionBrush.radiusAfterRebuildActivation",
                awaitBrushControls().value() == 8,
                "rebuilt activation reset the displayed radius");
        invokeEscape(rebuiltOverlay);
        awaitOverlay(0);
        finishMeshEditIfActive(report);
        final int overlays = overlayCount();
        report.add("cleanup.overlayCount=" + overlays);
        require(report, "selectionBrush.cleanup", overlays == 0, "overlays=" + overlays);
        failureBaseline.set(null);
    }

    private void proveNativeConfirmationClick(
            final Object session, final AbstractButton brushButton, final List<String> report) throws Exception {
        clickBrushTool(brushButton);
        final JComponent overlay = awaitOverlay(1);
        final JComponent view = onEdt(() -> (JComponent) invokeNoArgs(invokeNoArgs(session, "identity"), "component"));
        final Point origin = awaitRobotWindowOrigin(view);
        final Point target = onEdt(() -> nativeConfirmationCenter(session));
        require(
                report,
                "selectionBrush.confirmationPassThrough",
                onEdt(() -> SwingUtilities.getDeepestComponentAt(view, target.x, target.y) == view),
                "the brush intercepts the native confirmation at " + target);
        final int[] nativeReceipts = new int[2];
        final int[] brushReceipts = new int[2];
        final var nativeListener = confirmationReceipts(nativeReceipts);
        final var brushListener = confirmationReceipts(brushReceipts);
        onEdt(() -> {
            view.addMouseListener(nativeListener);
            overlay.addMouseListener(brushListener);
            return null;
        });
        final var robot = new java.awt.Robot();
        boolean pressed = false;
        try {
            robot.mouseMove(origin.x + target.x, origin.y + target.y);
            robot.delay(100);
            robot.waitForIdle();
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            pressed = true;
            robot.delay(100);
            robot.waitForIdle();
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            pressed = false;
            robot.waitForIdle();
            report.add("input.nativeConfirmation=" + target + " delivery=java.awt.Robot");
            require(
                    report,
                    "selectionBrush.confirmationReceivesGesture",
                    onEdt(() -> nativeReceipts[0] == 1
                            && nativeReceipts[1] == 1
                            && brushReceipts[0] == 0
                            && brushReceipts[1] == 0),
                    "native=" + java.util.Arrays.toString(nativeReceipts) + " brush="
                            + java.util.Arrays.toString(brushReceipts));
            final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!context.services()
                            .find(MeshEditService.class)
                            .orElse(MeshEditService.unavailable())
                            .snapshot()
                            .points()
                            .isEmpty()
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            require(
                    report,
                    "selectionBrush.confirmationEndsMeshEdit",
                    context.services()
                            .find(MeshEditService.class)
                            .orElse(MeshEditService.unavailable())
                            .snapshot()
                            .points()
                            .isEmpty(),
                    "native check did not end mesh edit");
            require(
                    report,
                    "selectionBrush.confirmationRemovesOverlay",
                    awaitOverlay(0) == null,
                    "native check retained the brush overlay");
        } finally {
            if (pressed) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            onEdt(() -> {
                view.removeMouseListener(nativeListener);
                overlay.removeMouseListener(brushListener);
                return null;
            });
        }
    }

    private static java.awt.event.MouseAdapter confirmationReceipts(final int[] receipts) {
        return new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent event) {
                receipts[0]++;
            }

            @Override
            public void mouseReleased(final MouseEvent event) {
                receipts[1]++;
            }
        };
    }

    /** Independent oracle: locate the exact native commit action, without calling it. */
    private static Point nativeConfirmationCenter(final Object session) throws Exception {
        final Object view = invokeNoArgs(invokeNoArgs(session, "identity"), "modelingView");
        final Object scene = invokeNoArgs(view, "getSceneGraph");
        final Iterable<?> entities =
                (Iterable<?>) invokeNoArgs(invokeNoArgs(scene, "getObjectsOnComponent"), "traverseAll");
        final List<Point> candidates = new ArrayList<>();
        for (Object entity : entities) {
            if (!entity.getClass().getName().equals("com.live2d.cubism.view.context.guiEntity.GIconButtonEntity")
                    || !Boolean.TRUE.equals(invokeNoArgs(entity, "getEnabledInHierarchy"))
                    || !Boolean.TRUE.equals(invokeNoArgs(entity, "isButtonEnabled"))) continue;
            final Object action = invokeNoArgs(entity, "getFunc");
            // In all three retained exact JARs, drawImpl.f commits endMeshEditMode(true,true);
            // drawImpl.e is the cancellation action. Neither callback is invoked by the probe.
            if (action == null || !action.getClass().getName().equals("com.live2d.cubism.view.context.drawImpl.f"))
                continue;
            final Object rect = invokeNoArgs(entity, "getRectOnComponent");
            final float x = ((Number) invokeNoArgs(rect, "getX")).floatValue();
            final float y = ((Number) invokeNoArgs(rect, "getY")).floatValue();
            final float width = ((Number) invokeNoArgs(rect, "getWidth")).floatValue();
            final float height = ((Number) invokeNoArgs(rect, "getHeight")).floatValue();
            if (!Float.isFinite(x)
                    || !Float.isFinite(y)
                    || !Float.isFinite(width)
                    || !Float.isFinite(height)
                    || width <= 0
                    || height <= 0) throw new IllegalStateException("native confirmation bounds are unavailable");
            candidates.add(new Point(Math.round(x + width / 2), Math.round(y + height / 2)));
        }
        if (candidates.size() != 1)
            throw new IllegalStateException("expected one native confirmation, found " + candidates.size());
        return candidates.get(0);
    }

    private void runBrushInputAssertions(
            final Object session, final BrushControls controls, final JComponent overlay, final List<String> report)
            throws Exception {
        final List<ProjectedBrushVertex> vertices = projectedBrushVertices(session, overlay);
        final VertexSelection before = awaitNativeSelection(session);
        final CapsuleGesture gesture = capsuleGesture(vertices, before, overlay);
        report.add("input.vertexCount=" + vertices.size());
        report.add("input.capsuleInterior=" + gesture.interiorIndex());
        report.add("input.radiusWitness=" + gesture.radiusWitness());
        setSliderValue(controls.slider(), 8);
        final List<Long> eventNanos = new ArrayList<>();
        eventNanos.add(dispatchBrushEvent(
                overlay,
                MouseEvent.MOUSE_PRESSED,
                gesture.path().get(0),
                InputEvent.BUTTON1_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        require(
                report,
                "selectionBrush.noWriteBeforeRelease",
                before.equals(awaitNativeSelection(session)),
                before.toString());
        setSliderValue(controls.slider(), 128);
        for (int step = 1; step < gesture.path().size(); step++) {
            eventNanos.add(dispatchBrushEvent(
                    overlay,
                    MouseEvent.MOUSE_DRAGGED,
                    gesture.path().get(step),
                    InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK));
        }
        require(
                report,
                "selectionBrush.noWriteDuringDrag",
                before.equals(awaitNativeSelection(session)),
                before.toString());
        require(
                report,
                "selectionBrush.previewVisible",
                onEdt(() -> Boolean.TRUE.equals(overlay.getClientProperty("turboism:selection-brush-preview-visible"))),
                "stroke preview is absent");
        final var robot = new java.awt.Robot();
        captureBrushPreview(report);
        eventNanos.add(dispatchBrushEvent(
                overlay,
                MouseEvent.MOUSE_RELEASED,
                gesture.path().get(gesture.path().size() - 1),
                InputEvent.CTRL_DOWN_MASK));
        final java.util.TreeSet<Integer> expected = new java.util.TreeSet<>(before.indices());
        expected.addAll(expectedBrushHits(vertices, gesture.path(), 8));
        final VertexSelection after = awaitNativeSelection(session);
        require(
                report,
                "selectionBrush.exactLockedDrag",
                after.equals(new VertexSelection(List.copyOf(expected))),
                "expected=" + expected + " actual=" + after);
        require(
                report,
                "selectionBrush.capsuleInterior",
                after.indices().contains(gesture.interiorIndex()),
                after.toString());
        require(
                report,
                "selectionBrush.radiusLocked",
                !after.indices().contains(gesture.radiusWitness()),
                after.toString());
        require(
                report,
                "selectionBrush.previewCleared",
                onEdt(() ->
                        Boolean.FALSE.equals(overlay.getClientProperty("turboism:selection-brush-preview-visible"))),
                "released stroke preview remains");
        report.add("input.syntheticDragSteps=" + (gesture.path().size() - 1));
        report.add("input.eventNanos=" + eventNanos);
        report.add("input.maxEventNanos="
                + eventNanos.stream().mapToLong(Long::longValue).max().orElseThrow());

        setSliderValue(controls.slider(), 8);
        final Point empty = emptyBrushPoint(vertices, overlay);
        dispatchStroke(overlay, empty, InputEvent.BUTTON1_DOWN_MASK);
        require(
                report,
                "selectionBrush.emptyStroke",
                after.equals(awaitNativeSelection(session)),
                "empty stroke changed selection");
        proveRobotStroke(robot, session, overlay, vertices, gesture.path(), report);
        proveViewInput(session, overlay, report);
    }

    private void proveRobotStroke(
            final java.awt.Robot robot,
            final Object session,
            final JComponent overlay,
            final List<ProjectedBrushVertex> vertices,
            final List<Point> path,
            final List<String> report)
            throws Exception {
        final VertexSelection expected = new VertexSelection(expectedBrushHits(vertices, path, 8));
        final List<Point> robotPath = sampledRobotPath(path);
        final var size = onEdt(overlay::getSize);
        final ProjectedBrushVertex seed = vertices.stream()
                .filter(vertex -> !expected.indices().contains(vertex.index()))
                .filter(vertex -> vertex.x() >= 1
                        && vertex.y() >= 1
                        && vertex.x() < size.width - 1
                        && vertex.y() < size.height - 1)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("fixture lacks a distinct real-stroke selection baseline"));
        dispatchStroke(overlay, new Point(Math.round(seed.x()), Math.round(seed.y())), InputEvent.BUTTON1_DOWN_MASK);
        final VertexSelection seeded = awaitNativeSelection(session);
        require(
                report,
                "selectionBrush.realInputBaselineDistinct",
                !seeded.equals(expected),
                "before=" + seeded + " expected=" + expected);
        final int[] delivered = new int[3];
        final List<Point> deliveredPath = new ArrayList<>();
        final List<String> deliveredEvents = new ArrayList<>();
        final var listener = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                delivered[0]++;
                deliveredPath.add(event.getPoint());
                deliveredEvents.add("press=" + event.getPoint() + ":modifiers=" + event.getModifiersEx());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                delivered[1]++;
                deliveredPath.add(event.getPoint());
                deliveredEvents.add("drag=" + event.getPoint() + ":modifiers=" + event.getModifiersEx());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                delivered[2]++;
                deliveredEvents.add("release=" + event.getPoint() + ":modifiers=" + event.getModifiersEx());
            }
        };
        onEdt(() -> {
            overlay.addMouseListener(listener);
            overlay.addMouseMotionListener(listener);
            return null;
        });
        boolean pressed = false;
        try {
            final Point origin = awaitRobotWindowOrigin(overlay);
            final VertexSelection before = awaitNativeSelection(session);
            require(
                    report,
                    "selectionBrush.realInputCalibrationPreservesSelection",
                    before.equals(seeded),
                    before.toString());
            robot.mouseMove(origin.x + robotPath.get(0).x, origin.y + robotPath.get(0).y);
            robot.delay(100);
            robot.waitForIdle();
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            pressed = true;
            robot.waitForIdle();
            require(
                    report,
                    "selectionBrush.realInputNoWriteBeforeRelease",
                    before.equals(awaitNativeSelection(session)),
                    before.toString());
            for (int step = 1; step < robotPath.size(); step++) {
                robot.mouseMove(origin.x + robotPath.get(step).x, origin.y + robotPath.get(step).y);
                robot.delay(50);
            }
            robot.waitForIdle();
            require(
                    report,
                    "selectionBrush.realInputNoWriteDuringDrag",
                    before.equals(awaitNativeSelection(session)),
                    before.toString());
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            pressed = false;
            robot.waitForIdle();
            report.add("input.requestedPath=" + safe(path.toString()));
            report.add("input.robotSampledPath=" + safe(robotPath.toString()));
            report.add("input.deliveredEvents=" + safe(onEdt(deliveredEvents::toString)));
            report.add("input.deliveredPathHits=" + onEdt(() -> expectedBrushHits(vertices, deliveredPath, 8)));
            require(
                    report,
                    "selectionBrush.realInputDelivered",
                    onEdt(() -> delivered[0] == 1 && delivered[1] > 0 && delivered[2] == 1),
                    "press/drag/release=" + java.util.Arrays.toString(delivered));
            final VertexSelection actual = awaitNativeSelection(session);
            final VertexSelection deliveredExpected =
                    new VertexSelection(onEdt(() -> expectedBrushHits(vertices, deliveredPath, 8)));
            require(
                    report,
                    "selectionBrush.realInputMatchesDeliveredPath",
                    deliveredExpected.equals(actual),
                    "expected=" + deliveredExpected + " actual=" + actual);
            require(
                    report,
                    "selectionBrush.realInputExactSelection",
                    expected.equals(actual),
                    "expected=" + expected + " actual=" + actual);
            require(
                    report,
                    "selectionBrush.realInputChangesSelection",
                    !before.equals(actual),
                    "before=" + before + " after=" + actual);
            report.add("input.delivery=java.awt.Robot");
        } finally {
            if (pressed) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            onEdt(() -> {
                overlay.removeMouseListener(listener);
                overlay.removeMouseMotionListener(listener);
                return null;
            });
        }
    }

    private static List<Point> sampledRobotPath(final List<Point> path) {
        // Short moves preserve the intended footprint when the window system delivers an
        // intermediate pointer warp as well as the requested position. Keep the sparse capsule
        // test above unchanged, and still require exact hits for both requested and delivered paths.
        final List<Point> sampled = new ArrayList<>();
        sampled.add(new Point(path.get(0)));
        for (int segment = 1; segment < path.size(); segment++) {
            final Point start = path.get(segment - 1);
            final Point end = path.get(segment);
            final int steps = Math.max(1, (int) Math.ceil(start.distance(end) / 4.0));
            for (int step = 1; step <= steps; step++) {
                sampled.add(new Point((int) Math.round(start.x + (end.x - start.x) * (double) step / steps), (int)
                        Math.round(start.y + (end.y - start.y) * (double) step / steps)));
            }
        }
        return List.copyOf(sampled);
    }

    Point awaitRobotWindowOrigin(final JComponent component) throws Exception {
        final Path ready = context.paths().stateDir().resolve("editor-window-ready.properties");
        final String runId = System.getProperty("turboism.validation.runId", "");
        final long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.isRegularFile(ready)
                    && System.currentTimeMillis()
                                    - Files.getLastModifiedTime(ready).toMillis()
                            < 2_000L) {
                final var properties = new java.util.Properties();
                try (var input = Files.newInputStream(ready)) {
                    properties.load(input);
                }
                if (runId.equals(properties.getProperty("runId")) && "1".equals(properties.getProperty("scale"))) {
                    final Rectangle actual = new Rectangle(
                            Integer.parseInt(properties.getProperty("originX")),
                            Integer.parseInt(properties.getProperty("originY")),
                            Integer.parseInt(properties.getProperty("width")),
                            Integer.parseInt(properties.getProperty("height")));
                    final RobotCoordinateCandidates candidates = onEdt(() -> {
                        final Window window = SwingUtilities.getWindowAncestor(component);
                        if (window == null || !window.isShowing())
                            throw new IllegalStateException("Robot target window is absent");
                        final Rectangle awt = new Rectangle(window.getLocationOnScreen(), window.getSize());
                        final java.awt.Insets insets = window.getInsets();
                        final int caption = Math.max(insets.top, awt.height - actual.height - insets.bottom);
                        final Point nativeOrigin = component.getLocationOnScreen();
                        final Point origin = new Point(nativeOrigin);
                        origin.translate(actual.x - awt.x - insets.left, actual.y - awt.y - caption);
                        context.logger()
                                .info("Robot coordinate estimate awt=" + awt + " insets=" + insets + " compositor="
                                        + actual + " componentOrigin=" + origin);
                        return new RobotCoordinateCandidates(nativeOrigin, awt, origin);
                    });
                    try {
                        return calibrateRobotWithMoves(component, candidates.nativeOrigin(), candidates.nativeBounds());
                    } catch (IllegalStateException nativeFailure) {
                        context.logger()
                                .info("Robot native coordinate candidate rejected: " + nativeFailure.getMessage());
                        return calibrateRobotWithMoves(component, candidates.compositorOrigin(), actual);
                    }
                }
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("task editor did not report stable focused compositor bounds");
    }

    private record RobotCoordinateCandidates(Point nativeOrigin, Rectangle nativeBounds, Point compositorOrigin) {}

    private Point calibrateRobotWithMoves(
            final JComponent component, final Point estimate, final Rectangle actualWindow) throws Exception {
        final var size = onEdt(component::getSize);
        if (size.width < 12 || size.height < 12) throw new IllegalStateException("Robot target component is too small");
        final var robot = new java.awt.Robot();
        final Point firstScreen = new Point(estimate.x + size.width / 2, estimate.y + size.height / 2);
        if (!actualWindow.contains(firstScreen))
            throw new IllegalStateException("Robot calibration point is outside the task window");
        final Point firstLocal = robotMovementReceipt(robot, component, firstScreen);
        final Point origin = robotOriginFromMovement(firstScreen, firstLocal);
        final Point requestedLocal = new Point(size.width / 3, size.height / 3);
        final Point secondScreen = new Point(origin.x + requestedLocal.x, origin.y + requestedLocal.y);
        if (!actualWindow.contains(secondScreen))
            throw new IllegalStateException("Robot verification point is outside the task window");
        final Point secondLocal = robotMovementReceipt(robot, component, secondScreen);
        if (!requestedLocal.equals(secondLocal)) {
            throw new IllegalStateException(
                    "Robot coordinate verification differs: requested=" + requestedLocal + " delivered=" + secondLocal);
        }
        context.logger()
                .info("Robot coordinate calibration firstScreen=" + firstScreen + " firstLocal=" + firstLocal
                        + " secondScreen=" + secondScreen + " secondLocal=" + secondLocal + " componentOrigin="
                        + origin);
        return origin;
    }

    static Point robotOriginFromMovement(final Point requestedScreen, final Point deliveredLocal) {
        return new Point(requestedScreen.x - deliveredLocal.x, requestedScreen.y - deliveredLocal.y);
    }

    private static Point robotMovementReceipt(
            final java.awt.Robot robot, final JComponent component, final Point screen) throws Exception {
        final AtomicReference<Point> delivered = new AtomicReference<>();
        final Window targetWindow = onEdt(() -> SwingUtilities.getWindowAncestor(component));
        final java.awt.event.AWTEventListener listener = event -> {
            if (event instanceof MouseEvent mouse
                    && mouse.getID() == MouseEvent.MOUSE_MOVED
                    && mouse.getSource() instanceof Component source
                    && SwingUtilities.getWindowAncestor(source) == targetWindow) {
                final Point local = SwingUtilities.convertPoint(source, mouse.getPoint(), component);
                if (local.x >= 0 && local.y >= 0 && local.x < component.getWidth() && local.y < component.getHeight()) {
                    delivered.set(local);
                }
            }
        };
        onEdt(() -> {
            java.awt.Toolkit.getDefaultToolkit()
                    .addAWTEventListener(listener, java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK);
            return null;
        });
        try {
            robot.mouseMove(screen.x, screen.y);
            robot.delay(100);
            robot.waitForIdle();
            final long deadline = System.nanoTime() + 2_000_000_000L;
            while (delivered.get() == null && System.nanoTime() < deadline) Thread.sleep(50L);
            final Point observed = delivered.get();
            if (observed == null)
                throw new IllegalStateException("Robot calibration move was not delivered to the task view");
            return observed;
        } finally {
            onEdt(() -> {
                java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(listener);
                return null;
            });
        }
    }

    private void captureBrushPreview(final List<String> report) throws Exception {
        final Path state = context.paths().stateDir();
        final String runId = System.getProperty("turboism.validation.runId", "");
        final Path request = state.resolve("selection-brush-preview.request");
        Files.writeString(request, "schemaVersion=1\nrunId=" + runId + "\n", StandardOpenOption.CREATE_NEW);
        final Path receipt = state.resolve("selection-brush-preview.receipt");
        final long deadline = System.nanoTime() + 15_000_000_000L;
        while (!Files.isRegularFile(receipt) && System.nanoTime() < deadline) Thread.sleep(100L);
        if (!Files.isRegularFile(receipt)) throw new IllegalStateException("task window preview capture timed out");
        final var properties = new java.util.Properties();
        try (var input = Files.newInputStream(receipt)) {
            properties.load(input);
        }
        require(
                report,
                "selectionBrush.previewBoundToRun",
                runId.equals(properties.getProperty("runId"))
                        && "PASS".equals(properties.getProperty("status"))
                        && "niri".equals(properties.getProperty("source")),
                properties.toString());
        final Path preview = state.resolve("selection-brush-preview.png");
        final byte[] data = Files.readAllBytes(preview);
        final String digest = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        final var image = javax.imageio.ImageIO.read(preview.toFile());
        require(
                report,
                "selectionBrush.previewCaptured",
                digest.equals(properties.getProperty("sha256"))
                        && image != null
                        && image.getWidth() == Integer.parseInt(properties.getProperty("width"))
                        && image.getHeight() == Integer.parseInt(properties.getProperty("height"))
                        && imageHasVariation(image),
                preview.getFileName().toString());
        report.add("input.previewSource=niri");
    }

    static boolean imageHasVariation(final java.awt.image.BufferedImage image) {
        final int first = image.getRGB(0, 0);
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) if (image.getRGB(x, y) != first) return true;
        }
        return false;
    }

    private void proveViewInput(final Object session, final JComponent overlay, final List<String> report)
            throws Exception {
        final List<ProjectedBrushVertex> beforeZoom = projectedBrushVertices(session, overlay);
        final Point center = onEdt(() -> new Point(overlay.getWidth() / 2, overlay.getHeight() / 2));
        onEdt(() -> {
            overlay.dispatchEvent(new java.awt.event.MouseWheelEvent(
                    overlay,
                    MouseEvent.MOUSE_WHEEL,
                    System.currentTimeMillis(),
                    0,
                    center.x,
                    center.y,
                    0,
                    false,
                    java.awt.event.MouseWheelEvent.WHEEL_UNIT_SCROLL,
                    3,
                    -1));
            return null;
        });
        final List<ProjectedBrushVertex> zoomed = awaitProjectionChange(session, overlay, beforeZoom);
        require(
                report,
                "selectionBrush.zoomForwarded",
                !beforeZoom.equals(zoomed),
                "wheel did not change native projection");
        verifyProjectedCircle(session, overlay, zoomed, report, "afterZoom");
        onEdt(() -> {
            final long when = System.currentTimeMillis();
            overlay.dispatchEvent(new MouseEvent(
                    overlay,
                    MouseEvent.MOUSE_PRESSED,
                    when,
                    InputEvent.BUTTON2_DOWN_MASK,
                    center.x,
                    center.y,
                    1,
                    false,
                    MouseEvent.BUTTON2));
            overlay.dispatchEvent(new MouseEvent(
                    overlay,
                    MouseEvent.MOUSE_DRAGGED,
                    when + 1,
                    InputEvent.BUTTON2_DOWN_MASK,
                    center.x + 24,
                    center.y + 12,
                    0,
                    false,
                    MouseEvent.NOBUTTON));
            overlay.dispatchEvent(new MouseEvent(
                    overlay,
                    MouseEvent.MOUSE_RELEASED,
                    when + 2,
                    0,
                    center.x + 24,
                    center.y + 12,
                    1,
                    false,
                    MouseEvent.BUTTON2));
            return null;
        });
        final List<ProjectedBrushVertex> panned = awaitProjectionChange(session, overlay, zoomed);
        require(
                report,
                "selectionBrush.panForwarded",
                !zoomed.equals(panned),
                "middle drag did not change native projection");
        verifyProjectedCircle(session, overlay, panned, report, "afterPan");
    }

    private static List<ProjectedBrushVertex> awaitProjectionChange(
            final Object session, final JComponent overlay, final List<ProjectedBrushVertex> before) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        List<ProjectedBrushVertex> current = before;
        while (System.nanoTime() < deadline) {
            current = projectedBrushVertices(session, overlay);
            if (!before.equals(current)) return current;
            Thread.sleep(50);
        }
        return current;
    }

    private void verifyProjectedCircle(
            final Object session,
            final JComponent overlay,
            final List<ProjectedBrushVertex> vertices,
            final List<String> report,
            final String label)
            throws Exception {
        final java.awt.Dimension size = onEdt(overlay::getSize);
        final ProjectedBrushVertex target = vertices.stream()
                .filter(vertex -> vertex.x() >= 9
                        && vertex.y() >= 9
                        && vertex.x() < size.width - 9
                        && vertex.y() < size.height - 9)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no visible vertex after native view input"));
        final Point point = new Point(Math.round(target.x()), Math.round(target.y()));
        dispatchStroke(overlay, point, InputEvent.BUTTON1_DOWN_MASK);
        final VertexSelection expected = new VertexSelection(expectedBrushHits(vertices, List.of(point), 8));
        final VertexSelection actual = awaitNativeSelection(session);
        require(
                report,
                "selectionBrush." + label,
                expected.equals(actual),
                "point=" + point + " expected=" + expected + " actual=" + actual + " overlayBounds="
                        + onEdt(overlay::getBounds));
    }

    private void proveBrushCancellation(final Object session, final JComponent overlay, final List<String> report)
            throws Exception {
        final VertexSelection before = awaitNativeSelection(session);
        final int selected = before.indices().stream().findFirst().orElseThrow();
        final Point point = projectedPoint(session, selected, overlay);
        if (point == null) throw new IllegalStateException("cancellation target is outside the viewport");
        dispatchBrushEvent(
                overlay, MouseEvent.MOUSE_PRESSED, point, InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK);
        dispatchBrushEvent(
                overlay,
                MouseEvent.MOUSE_DRAGGED,
                new Point(point.x + 2, point.y),
                InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK);
        invokeEscape(overlay);
        require(report, "selectionBrush.escape", awaitOverlay(0) == null, "overlay remained installed");
        dispatchBrushEvent(overlay, MouseEvent.MOUSE_RELEASED, point, 0);
        require(
                report,
                "selectionBrush.interruptedStroke",
                before.equals(awaitNativeSelection(session)),
                "Escape committed a partial stroke");
        final BrushControls resumed = awaitBrushControls();
        require(report, "selectionBrush.radiusAfterEscape", resumed.value() == 8, "Escape reset the displayed radius");
        clickBrushTool(resumed.button());
        final JComponent replacement = awaitOverlay(1);
        dispatchBrushEvent(
                replacement, MouseEvent.MOUSE_PRESSED, point, InputEvent.BUTTON1_DOWN_MASK | InputEvent.CTRL_DOWN_MASK);
        final BrushControls activeControls = awaitBrushControls();
        require(
                report,
                "selectionBrush.radiusAfterReactivation",
                activeControls.value() == 8,
                "reactivation reset the displayed radius");
        final AbstractButton nativePeer = onEdt(() -> {
            final Container row = activeControls.button().getParent();
            if (row == null) throw new IllegalStateException("current brush button is detached from its native row");
            for (Component sibling : row.getComponents()) {
                if (sibling instanceof AbstractButton peer
                        && peer != activeControls.button()
                        && peer.isEnabled()
                        && (peer.getName() == null || !peer.getName().startsWith("turboism:"))) return peer;
            }
            throw new IllegalStateException("native mesh-tool peer is unavailable in the contributed toolbar row");
        });
        report.add("input.nativeToolPeer=" + safe(nativePeer.getClass().getName() + ":" + nativePeer.getName()));
        if (!awaitControlVisible(nativePeer)) throw new IllegalStateException("native peer tool is not visible");
        onEdt(() -> {
            nativePeer.doClick();
            return null;
        });
        require(
                report,
                "selectionBrush.nativeToolSwitch",
                awaitOverlay(0) == null,
                "native tool retained brush overlay");
        dispatchBrushEvent(replacement, MouseEvent.MOUSE_RELEASED, point, 0);
        require(
                report,
                "selectionBrush.nativeSwitchCancelledStroke",
                before.equals(awaitNativeSelection(session)),
                "native tool switch committed a partial stroke");
        require(
                report,
                "selectionBrush.radiusAfterNativeSwitch",
                awaitBrushControls().value() == 8,
                "native tool switch reset the displayed radius");
    }

    private static long dispatchBrushEvent(
            final JComponent overlay, final int type, final Point point, final int modifiers) throws Exception {
        return onEdt(() -> {
            final long start = System.nanoTime();
            overlay.dispatchEvent(new MouseEvent(
                    overlay,
                    type,
                    System.currentTimeMillis(),
                    modifiers,
                    point.x,
                    point.y,
                    type == MouseEvent.MOUSE_DRAGGED ? 0 : 1,
                    false,
                    type == MouseEvent.MOUSE_DRAGGED ? MouseEvent.NOBUTTON : MouseEvent.BUTTON1));
            return System.nanoTime() - start;
        });
    }

    private static List<ProjectedBrushVertex> projectedBrushVertices(final Object session, final JComponent overlay)
            throws Exception {
        return onEdt(() -> {
            final Object identity = invokeNoArgs(session, "identity");
            final Object resolver = invokeNoArgs(session, "resolver");
            final Object camera = invokeNoArgs(identity, "camera");
            final JComponent component = (JComponent) invokeNoArgs(identity, "component");
            final Point offset = SwingUtilities.convertPoint(component, 0, 0, overlay);
            final float[] positions = nativeCanvasPositions(identity, resolver);
            final int count = ((Number) resolverInvoke(
                            resolver,
                            "cubism.editor-model.editable-mesh.point-count",
                            invokeNoArgs(identity, "editableMesh")))
                    .intValue();
            if (count < 0 || count > positions.length / 2)
                throw new IllegalStateException("invalid editable mesh point count");
            final List<ProjectedBrushVertex> vertices = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                final Object documentPoint = resolverConstruct(
                        resolver, "cubism.editor-model.vector.create", positions[index * 2], positions[index * 2 + 1]);
                final Object projected = resolverInvoke(
                        resolver, "cubism.editor-model.camera.document-to-component", camera, documentPoint);
                final float x =
                        ((Number) resolverInvoke(resolver, "cubism.editor-model.vector.x", projected)).floatValue()
                                + offset.x;
                final float y =
                        ((Number) resolverInvoke(resolver, "cubism.editor-model.vector.y", projected)).floatValue()
                                + offset.y;
                if (!Float.isFinite(x) || !Float.isFinite(y))
                    throw new IllegalStateException("non-finite vertex projection");
                vertices.add(new ProjectedBrushVertex(index, x, y));
            }
            return List.copyOf(vertices);
        });
    }

    private static List<Integer> expectedBrushHits(
            final List<ProjectedBrushVertex> vertices, final List<Point> path, final int radius) {
        return vertices.stream()
                .filter(vertex -> {
                    if (path.size() == 1) return path.get(0).distanceSq(vertex.x(), vertex.y()) <= radius * radius;
                    for (int step = 1; step < path.size(); step++) {
                        final Point a = path.get(step - 1);
                        final Point b = path.get(step);
                        if (java.awt.geom.Line2D.ptSegDistSq(a.x, a.y, b.x, b.y, vertex.x(), vertex.y())
                                <= radius * radius) return true;
                    }
                    return false;
                })
                .map(ProjectedBrushVertex::index)
                .toList();
    }

    private static CapsuleGesture capsuleGesture(
            final List<ProjectedBrushVertex> vertices, final VertexSelection before, final JComponent overlay)
            throws Exception {
        final java.awt.Dimension size = onEdt(overlay::getSize);
        for (final ProjectedBrushVertex vertex : vertices) {
            if (before.indices().contains(vertex.index())) continue;
            for (int axis = 0; axis < 2; axis++) {
                final List<Point> path = new ArrayList<>();
                for (int step = 0; step <= 4; step++) {
                    path.add(new Point(
                            Math.round(vertex.x()) + (axis == 0 ? step * 24 - 36 : 0),
                            Math.round(vertex.y()) + (axis == 1 ? step * 24 - 36 : 0)));
                }
                if (path.stream()
                        .anyMatch(point ->
                                point.x < 1 || point.y < 1 || point.x >= size.width - 1 || point.y >= size.height - 1))
                    continue;
                if (path.stream().anyMatch(point -> point.distanceSq(vertex.x(), vertex.y()) <= 8 * 8)) continue;
                final List<Integer> narrow = expectedBrushHits(vertices, path, 8);
                if (!narrow.contains(vertex.index())) continue;
                final var witness = expectedBrushHits(vertices, path, 128).stream()
                        .filter(index ->
                                !narrow.contains(index) && !before.indices().contains(index))
                        .findFirst();
                if (witness.isPresent()) return new CapsuleGesture(List.copyOf(path), vertex.index(), witness.get());
            }
        }
        throw new IllegalStateException("fixture lacks an interior capsule target with a radius-lock witness");
    }

    private static Point emptyBrushPoint(final List<ProjectedBrushVertex> vertices, final JComponent overlay)
            throws Exception {
        final java.awt.Dimension size = onEdt(overlay::getSize);
        for (int y = 9; y < size.height - 9; y += 24) {
            for (int x = 9; x < size.width - 9; x += 24) {
                final Point point = new Point(x, y);
                if (expectedBrushHits(vertices, List.of(point), 8).isEmpty()) return point;
            }
        }
        throw new IllegalStateException("fixture has no empty 8-pixel brush footprint");
    }

    private record ProjectedBrushVertex(int index, float x, float y) {}

    private record CapsuleGesture(List<Point> path, int interiorIndex, int radiusWitness) {}

    private Object awaitNativeMeshSession() throws Exception {
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        Throwable last = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                final Class<?> bridge = Class.forName(
                        "dev.turboism.adapter.cubism.mesh.NativeMeshToolSessionBridge",
                        false,
                        null); // The distributed agent owns this diagnostic bridge on the bootstrap loader.
                final Method current = bridge.getDeclaredMethod("currentSessionForTests");
                current.setAccessible(true);
                final Object session = current.invoke(null);
                if (session != null) return session;
            } catch (Throwable failure) {
                last = failure;
            }
            Thread.sleep(100L);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("mesh-session wait interrupted");
        throw new IllegalStateException("exact native mesh-tool session was not published", last);
    }

    private static VertexSelection nativeSelection(final Object session) throws Exception {
        return (VertexSelection) invokeNoArgs(session, "selection");
    }

    /**
     * Reads the native vertex selection, tolerating a Core evaluated read that is transiently
     * unavailable while the freshly-opened mesh session settles.
     */
    private VertexSelection awaitNativeSelection(final Object session) throws Exception {
        final long deadline = System.nanoTime() + 15_000_000_000L;
        Exception last = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                return onEdt(() -> nativeSelection(session));
            } catch (Exception failure) {
                last = failure;
                Thread.sleep(400L);
            }
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("selection read interrupted");
        throw last == null ? new IllegalStateException("native selection read produced no result") : last;
    }

    private VertexSelection awaitSelectionContains(final int index) throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        VertexSelection last = VertexSelection.empty();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            last = nativeSelection(awaitNativeMeshSession());
            if (last.indices().contains(index)) return last;
            Thread.sleep(50L);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("selection wait interrupted");
        throw new IllegalStateException("native selection did not contain target " + index + ": " + last);
    }

    private VertexSelection awaitSelectionExcludes(final int index) throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        VertexSelection last = nativeSelection(awaitNativeMeshSession());
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            last = nativeSelection(awaitNativeMeshSession());
            if (!last.indices().contains(index)) return last;
            Thread.sleep(50L);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("selection wait interrupted");
        throw new IllegalStateException("native selection still contained target " + index + ": " + last);
    }

    private BrushControls awaitBrushControls() throws Exception {
        final long deadline = System.nanoTime() + 30_000_000_000L;
        BrushControls last = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            last = onEdt(WindowsMeshEditValidationProbe::brushControls);
            if (last.toolCount() == 1 && last.sliderCount() == 1) return last;
            Thread.sleep(100L);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("mesh-toolbar wait interrupted");
        throw new IllegalStateException("Selection Brush controls were not uniquely materialized: " + last);
    }

    private static void requireBrushControlsVisible(
            final BrushControls controls, final List<String> report, final String phase) throws Exception {
        final boolean buttonVisible = awaitControlVisible(controls.button());
        final boolean sliderVisible = awaitControlVisible(controls.slider());
        require(
                report,
                "selectionBrush.controlsVisible." + phase,
                buttonVisible && sliderVisible,
                onEdt(() -> "button=" + controlAncestors(controls.button()) + " slider="
                        + controlAncestors(controls.slider())));
        onEdt(() -> {
            scrollControlIntoView(controls.button());
            return null;
        });
    }

    private static void clickBrushTool(final AbstractButton button) throws Exception {
        if (!awaitControlVisible(button)) throw new IllegalStateException("brush button is not visible");
        onEdt(() -> {
            button.doClick();
            return null;
        });
    }

    private static boolean awaitControlVisible(final Component control) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            if (onEdt(() -> {
                scrollControlIntoView(control);
                return controlVisible(control);
            })) return true;
            Thread.sleep(50L);
        }
        return false;
    }

    private static void scrollControlIntoView(final Component control) {
        if (control instanceof JComponent component) {
            component.scrollRectToVisible(new java.awt.Rectangle(0, 0, component.getWidth(), component.getHeight()));
        }
    }

    private static boolean controlVisible(final Component control) {
        return control != null
                && control.isShowing()
                && control.getWidth() > 0
                && control.getHeight() > 0
                && (!(control instanceof JComponent component)
                        || !component.getVisibleRect().isEmpty());
    }

    private static List<String> controlAncestors(final Component control) {
        final List<String> ancestors = new ArrayList<>();
        for (Component current = control; current != null && ancestors.size() < 12; current = current.getParent()) {
            ancestors.add(current.getClass().getSimpleName() + ":" + current.getName() + ":visible="
                    + current.isVisible() + ":showing=" + current.isShowing() + ":bounds=" + current.getBounds());
        }
        return List.copyOf(ancestors);
    }

    private static BrushControls brushControls() {
        final List<Component> components = showingComponents();
        final List<AbstractButton> tools = components.stream()
                .filter(AbstractButton.class::isInstance)
                .map(AbstractButton.class::cast)
                .filter(button -> button.getName() != null && button.getName().startsWith("turboism:mesh:"))
                .filter(button -> matches(button, "selection-brush"))
                .toList();
        final List<Component> sliders = components.stream()
                .filter(component -> matches(component, "selection-brush.radius"))
                .filter(component -> hasIntMethod(component, "getValue") && hasIntSetter(component, "setValue"))
                .toList();
        final AbstractButton tool = tools.size() == 1 ? tools.get(0) : null;
        final Component slider = sliders.size() == 1 ? sliders.get(0) : null;
        return new BrushControls(
                tool,
                slider,
                tools.size(),
                sliders.size(),
                slider == null ? Integer.MIN_VALUE : intValue(slider, "getMinimum"),
                slider == null ? Integer.MIN_VALUE : intValue(slider, "getMaximum"),
                slider == null ? Integer.MIN_VALUE : sliderValue(slider));
    }

    private static boolean matches(final Component component, final String token) {
        if (component.getName() != null && component.getName().contains(token)) return true;
        return component instanceof JComponent widget
                && widget.getToolTipText() != null
                && widget.getToolTipText().contains(token);
    }

    static List<Component> showingComponents() {
        final List<Component> values = new ArrayList<>();
        for (Frame frame : Frame.getFrames()) {
            if (frame.isShowing()) collectComponents(frame, values);
        }
        return values;
    }

    private static void collectComponents(final Component component, final List<Component> values) {
        values.add(component);
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectComponents(child, values);
        }
    }

    private static boolean hasIntMethod(final Object target, final String name) {
        try {
            target.getClass().getMethod(name);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static boolean hasIntSetter(final Object target, final String name) {
        try {
            target.getClass().getMethod(name, int.class);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static int intValue(final Object target, final String method) {
        try {
            return ((Number) target.getClass().getMethod(method).invoke(target)).intValue();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(method + " is unavailable", failure);
        }
    }

    private static int sliderValue(final Object slider) {
        return intValue(slider, "getValue");
    }

    private static void setSliderValue(final Object slider, final int value) throws Exception {
        if (!(slider instanceof Component component) || !awaitControlVisible(component)) {
            throw new IllegalStateException("brush radius slider is not visible");
        }
        onEdt(() -> {
            slider.getClass().getMethod("setValue", int.class).invoke(slider, value);
            return null;
        });
    }

    private StrokeTarget projectedTarget(
            final Object session, final VertexSelection selection, final JComponent overlay) throws Exception {
        final Drawable drawable = (Drawable) invokeNoArgs(session, "drawable");
        if (drawable == null) throw new IllegalStateException("active native session has no SDK drawable");
        final Object identity = invokeNoArgs(session, "identity");
        final Object camera = invokeNoArgs(identity, "camera");
        final JComponent component = (JComponent) invokeNoArgs(identity, "component");
        final Method resolverMethod = session.getClass().getDeclaredMethod("resolver");
        resolverMethod.setAccessible(true);
        final Object resolver = resolverMethod.invoke(session);
        if (resolver == null) throw new IllegalStateException("active native session has no exact resolver");
        final float[] positions = nativeCanvasPositions(identity, resolver);
        for (int index = 0; index < positions.length / 2; index++) {
            if (selection.indices().contains(index)) continue;
            final Object documentPoint = resolverConstruct(
                    resolver, "cubism.editor-model.vector.create", positions[index * 2], positions[index * 2 + 1]);
            final Object componentPoint =
                    resolverInvoke(resolver, "cubism.editor-model.camera.document-to-component", camera, documentPoint);
            final Number x = (Number) resolverInvoke(resolver, "cubism.editor-model.vector.x", componentPoint);
            final Number y = (Number) resolverInvoke(resolver, "cubism.editor-model.vector.y", componentPoint);
            if (x == null || y == null || !Float.isFinite(x.floatValue()) || !Float.isFinite(y.floatValue())) continue;
            final int px = Math.round(x.floatValue());
            final int py = Math.round(y.floatValue());
            if (px < 0 || py < 0 || px >= component.getWidth() || py >= component.getHeight()) continue;
            final Point overlayPoint = onEdt(() -> SwingUtilities.convertPoint(component, px, py, overlay));
            if (overlay.contains(overlayPoint)) return new StrokeTarget(index, overlayPoint);
        }
        throw new IllegalStateException("no unselected evaluated vertex projects inside the active component");
    }

    /** Projects one evaluated vertex into overlay coordinates, or null when it is not projectable. */
    private static Point projectedPoint(final Object session, final int index, final JComponent overlay)
            throws Exception {
        final Drawable drawable = (Drawable) invokeNoArgs(session, "drawable");
        if (drawable == null) throw new IllegalStateException("active native session has no SDK drawable");
        final Object identity = invokeNoArgs(session, "identity");
        final Object camera = invokeNoArgs(identity, "camera");
        final JComponent component = (JComponent) invokeNoArgs(identity, "component");
        final Method resolverMethod = session.getClass().getDeclaredMethod("resolver");
        resolverMethod.setAccessible(true);
        final Object resolver = resolverMethod.invoke(session);
        if (resolver == null) throw new IllegalStateException("active native session has no exact resolver");
        final float[] positions = nativeCanvasPositions(identity, resolver);
        final Object documentPoint = resolverConstruct(
                resolver, "cubism.editor-model.vector.create", positions[index * 2], positions[index * 2 + 1]);
        final Object componentPoint =
                resolverInvoke(resolver, "cubism.editor-model.camera.document-to-component", camera, documentPoint);
        final Number x = (Number) resolverInvoke(resolver, "cubism.editor-model.vector.x", componentPoint);
        final Number y = (Number) resolverInvoke(resolver, "cubism.editor-model.vector.y", componentPoint);
        if (x == null || y == null || !Float.isFinite(x.floatValue()) || !Float.isFinite(y.floatValue())) return null;
        final int px = Math.round(x.floatValue());
        final int py = Math.round(y.floatValue());
        if (px < 0 || py < 0 || px >= component.getWidth() || py >= component.getHeight()) return null;
        final Point overlayPoint = onEdt(() -> SwingUtilities.convertPoint(component, px, py, overlay));
        return overlay.contains(overlayPoint) ? overlayPoint : null;
    }

    /** Returns raw staging-mesh positions; these are not necessarily displayed Canvas coordinates. */
    private static float[] editableMeshPositions(final Object identity, final Object resolver) throws Exception {
        final Object editableMesh = invokeNoArgs(identity, "editableMesh");
        final Object value = resolverInvoke(resolver, "cubism.editor-model.editable-mesh.gl-positions", editableMesh);
        if (!(value instanceof float[] positions) || (positions.length & 1) != 0) {
            throw new IllegalStateException("editable mesh vertex positions are unavailable");
        }
        return positions;
    }

    /**
     * Independent expected coordinates: read the displayed native form directly when the staging
     * mesh is unchanged, otherwise ask the native lasso's converter factory. Do not call the
     * production brush projector or rebuild its triangle mapping here.
     */
    private static float[] nativeCanvasPositions(final Object identity, final Object resolver) throws Exception {
        return onEdt(() -> {
            final float[] raw = editableMeshPositions(identity, resolver);
            final Object view = invokeNoArgs(identity, "modelingView");
            final Object mode = resolverInvoke(resolver, "cubism.editor-model.modeling-view.current-view-mode", view);
            final String modeClass = mode.getClass().getName();
            final String modeOwner = "com.live2d.cubism.view.context.CEViewContext_ModelingView$c$";
            if (modeClass.equals(modeOwner + "a") || modeClass.equals(modeOwner + "c")) return raw;
            if (!modeClass.equals(modeOwner + "b"))
                throw new IllegalStateException("unsupported native mesh view mode");
            final Object model = resolverInvoke(resolver, "cubism.editor-model.modeling-view.model", view);
            final Object source = invokeNoArgs(identity, "artMesh");
            final Object id = resolverInvoke(resolver, "cubism.editor-model.parameter-controllable-source.id", source);
            final Object artMesh = resolverInvoke(resolver, "cubism.editor-model.model.get-object", model, id);
            if (invokeNoArgs(artMesh, "getSource") != source)
                throw new IllegalStateException("native displayed ArtMesh source identity differs");
            final float[] authored = (float[]) invokeNoArgs(source, "getPositions");
            final float[] displayed =
                    (float[]) invokeNoArgs(invokeNoArgs(artMesh, "getCalculatedForm"), "getPositions");
            if (java.util.Arrays.equals(raw, authored)) {
                if (displayed.length != raw.length) throw new IllegalStateException("displayed form count differs");
                return displayed.clone();
            }
            final ClassLoader loader = view.getClass().getClassLoader();
            final String helperName =
                    switch ((String) invokeNoArgs(resolver, "cubismVersion")) {
                        case "5.2.03" -> "j";
                        case "5.3.02", "5.3.03" -> "p";
                        default -> throw new IllegalStateException("unreviewed native lasso converter factory");
                    };
            final Class<?> helper = Class.forName(
                    "com.live2d.cubism.view.context.action.action_meshEditor." + helperName, true, loader);
            final Object converter = helper.getMethod(
                            "b",
                            Class.forName("com.live2d.cubism.doc.model.drawable.artMesh.CArtMesh", false, loader),
                            Class.forName("com.live2d.graphics3d.editableMesh.GEditableMesh2", false, loader))
                    .invoke(helper.getField("a").get(null), artMesh, invokeNoArgs(identity, "editableMesh"));
            final float[] canvas = (float[]) converter
                    .getClass()
                    .getMethod("transform", float[].class, float[].class)
                    .invoke(converter, raw, new float[raw.length]);
            if (canvas.length != raw.length) throw new IllegalStateException("native lasso vertex count differs");
            return canvas;
        });
    }

    private static void reportNativeProjection(final List<String> report, final Object session) throws Exception {
        onEdt(() -> {
            final Object identity = invokeNoArgs(session, "identity");
            final Object resolver = invokeNoArgs(session, "resolver");
            final float[] raw = editableMeshPositions(identity, resolver);
            final float[] canvas = nativeCanvasPositions(identity, resolver);
            if (raw.length != canvas.length) throw new IllegalStateException("native projection count differs");
            double maximum = 0;
            for (int index = 0; index < raw.length; index += 2) {
                if (!Float.isFinite(canvas[index]) || !Float.isFinite(canvas[index + 1]))
                    throw new IllegalStateException("native projection is not finite");
                maximum = Math.max(maximum, Math.hypot(canvas[index] - raw[index], canvas[index + 1] - raw[index + 1]));
            }
            report.add("projection.oracle=native-displayed-form-or-lasso-helper");
            report.add("projection.sourceToCanvasMaxDistance=" + maximum);
            report.add("projection.vertexCount=" + canvas.length / 2);
            report.add("projection.viewMode="
                    + invokeNoArgs(invokeNoArgs(identity, "modelingView"), "getCurrentViewMode")
                            .getClass()
                            .getName());
            return null;
        });
    }

    /**
     * Returns two projectable vertices further apart than ``2 * radius + 2`` so that neither can fall
     * inside the other's brush footprint. That separation is what makes the additive and replacing
     * assertions exact instead of probabilistic.
     */
    private static GesturePair separatedPair(
            final Object session, final JComponent overlay, final int radius, final int excludedIndex)
            throws Exception {
        final Object identity = invokeNoArgs(session, "identity");
        final Method resolverMethod = session.getClass().getDeclaredMethod("resolver");
        resolverMethod.setAccessible(true);
        final Object resolver = resolverMethod.invoke(session);
        if (resolver == null) throw new IllegalStateException("active native session has no exact resolver");
        final int count = editableMeshPositions(identity, resolver).length / 2;
        final List<Integer> projectable = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (index == excludedIndex) continue;
            if (projectedPoint(session, index, overlay) != null) projectable.add(index);
        }
        final double minimum = 2.0 * radius + 2.0;
        for (int first = 0; first < projectable.size(); first++) {
            final Point firstPoint = projectedPoint(session, projectable.get(first), overlay);
            for (int second = first + 1; second < projectable.size(); second++) {
                final Point secondPoint = projectedPoint(session, projectable.get(second), overlay);
                if (firstPoint.distance(secondPoint) > minimum) {
                    return new GesturePair(projectable.get(first), projectable.get(second), firstPoint, secondPoint);
                }
            }
        }
        return null;
    }

    private static Object resolverConstruct(final Object resolver, final String alias, final Object... arguments)
            throws Exception {
        final Method method = resolver.getClass().getMethod("construct", String.class, Object[].class);
        return method.invoke(resolver, new Object[] {alias, arguments});
    }

    private static Object resolverInvoke(
            final Object resolver, final String alias, final Object target, final Object... arguments)
            throws Exception {
        final Method method = resolver.getClass().getMethod("invoke", String.class, Object.class, Object[].class);
        return method.invoke(resolver, new Object[] {alias, target, arguments});
    }

    private static Object invokeNoArgs(final Object target, final String name) throws Exception {
        Method method;
        try {
            method = target.getClass().getMethod(name);
        } catch (NoSuchMethodException missing) {
            method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
        }
        return method.invoke(target);
    }

    private static void dispatchStroke(final JComponent overlay, final Point point, final int pressModifiers)
            throws Exception {
        onEdt(() -> {
            final long when = System.currentTimeMillis();
            overlay.dispatchEvent(new MouseEvent(
                    overlay,
                    MouseEvent.MOUSE_PRESSED,
                    when,
                    pressModifiers,
                    point.x,
                    point.y,
                    1,
                    false,
                    MouseEvent.BUTTON1));
            overlay.dispatchEvent(new MouseEvent(
                    overlay, MouseEvent.MOUSE_RELEASED, when + 1L, 0, point.x, point.y, 1, false, MouseEvent.BUTTON1));
            return null;
        });
    }

    static JComponent awaitOverlay(final int expected) throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        List<JComponent> overlays = List.of();
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            overlays = onEdt(() -> showingComponents().stream()
                    .filter(JComponent.class::isInstance)
                    .map(JComponent.class::cast)
                    .filter(component -> "turboism:selection-brush-overlay".equals(component.getName()))
                    .toList());
            if (overlays.size() == expected) return expected == 0 ? null : overlays.get(0);
            Thread.sleep(50L);
        }
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("overlay wait interrupted");
        throw new IllegalStateException(
                "expected " + expected + " selection-brush overlays but found " + overlays.size());
    }

    static int overlayCount() throws Exception {
        return onEdt(() -> (int) showingComponents().stream()
                .filter(component -> "turboism:selection-brush-overlay".equals(component.getName()))
                .count());
    }

    static void invokeEscape(final JComponent overlay) throws Exception {
        onEdt(() -> {
            final Object binding = overlay.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0));
            final Action action =
                    binding == null ? null : overlay.getActionMap().get(binding);
            if (action == null) throw new IllegalStateException("Selection Brush Escape action is unavailable");
            action.actionPerformed(new ActionEvent(overlay, ActionEvent.ACTION_PERFORMED, "escape"));
            return null;
        });
    }

    private record BrushControls(
            AbstractButton button,
            Component slider,
            int toolCount,
            int sliderCount,
            int minimum,
            int maximum,
            int value) {}

    private record StrokeTarget(int index, Point point) {}

    private record GesturePair(int first, int second, Point firstPoint, Point secondPoint) {}

    private void cleanupAfterFailure(final List<String> report) {
        try {
            MeshSnapshot current = context.services()
                    .find(MeshEditService.class)
                    .orElse(MeshEditService.unavailable())
                    .snapshot();
            if (current.points().isEmpty()) return;
            final MeshSnapshot baseline = failureBaseline.get();
            if (baseline != null) {
                for (int attempts = 0; attempts < 12 && !current.equals(baseline); attempts++) {
                    final EditorCommandResult undo = context.services()
                            .find(EditorCommandService.class)
                            .orElse(EditorCommandService.unavailable())
                            .execute(EditorCommand.UNDO);
                    report.add("failureCleanup.undo." + attempts + "=" + undo.status());
                    if (!undo.executed()) break;
                    current = awaitCleanupSnapshotChange(current, 5_000L);
                }
                report.add("failureCleanup.restored=" + current.equals(baseline));
                if (!current.equals(baseline)) {
                    report.add("failureCleanup.exitSkipped=baseline-not-restored");
                    return;
                }
            }
            final EditorCommandResult exit = context.services()
                    .find(EditorCommandService.class)
                    .orElse(EditorCommandService.unavailable())
                    .execute(EditorCommand.START_OR_END_MESH_EDITOR);
            report.add("failureCleanup.meshExit=" + exit.status());
            final long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline
                    && !context.services()
                            .find(MeshEditService.class)
                            .orElse(MeshEditService.unavailable())
                            .snapshot()
                            .points()
                            .isEmpty()) {
                Thread.sleep(100L);
            }
            report.add("failureCleanup.meshInactive="
                    + context.services()
                            .find(MeshEditService.class)
                            .orElse(MeshEditService.unavailable())
                            .snapshot()
                            .points()
                            .isEmpty());
        } catch (Exception failure) {
            report.add("failureCleanup.error=" + safe(failureDescription(failure)));
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            restorePersistenceFileAfterFailure(report);
            failureBaseline.set(null);
        }
    }

    private void restorePersistenceFileAfterFailure(final List<String> report) {
        final byte[] original = persistenceFileBaseline.getAndSet(null);
        try {
            if (persistenceFileWritten && original != null) {
                Files.write(fixturePath(), original, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                report.add("failureCleanup.fixtureBytesRestored=true");
            }
        } catch (Exception failure) {
            report.add("failureCleanup.fixtureRestoreError=" + safe(failureDescription(failure)));
        } finally {
            persistenceFileWritten = false;
        }
    }

    private MeshSnapshot awaitCleanupSnapshotChange(final MeshSnapshot before, final long timeoutMillis)
            throws InterruptedException {
        final long deadline = System.nanoTime() + timeoutMillis * 1_000_000L;
        MeshSnapshot current = context.services()
                .find(MeshEditService.class)
                .orElse(MeshEditService.unavailable())
                .snapshot();
        while (System.nanoTime() < deadline && current.equals(before)) {
            Thread.sleep(100L);
            current = context.services()
                    .find(MeshEditService.class)
                    .orElse(MeshEditService.unavailable())
                    .snapshot();
        }
        return current;
    }

    private void runPersistence(final List<String> report) throws Exception {
        final Path fixture = fixturePath();
        persistenceFileBaseline.set(Files.readAllBytes(fixture));
        persistenceFileWritten = false;
        final MeshEditService edit =
                context.services().find(MeshEditService.class).orElse(MeshEditService.unavailable());
        final MeshSnapshot before = awaitEditableMesh(report);
        failureBaseline.set(before);
        final MeshPointPosition position = distinctPosition(before);
        final MeshEditResult addedResult = edit.addPoints(List.of(position));
        requireApplied(report, "persist.addPoint", addedResult);
        final MeshSnapshot after = awaitSnapshot(
                snapshot -> snapshot.points().size() == before.points().size() + 1, "persist point addition");
        final MeshPointRef added = discoverAddedPoint(before, after, position);
        finishMeshEditIfActive(report);
        final SaveConfirmation firstSave = saveFixture(report, "saveWritten");
        persistenceFileWritten = firstSave.confirmed();
        require(report, "persist.saveWritten", firstSave.confirmed(), firstSave.toString());
        report.add(firstSave.report("saveWritten."));

        final EditorCommandResult reloadWritten = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(EditorCommand.RELOAD_MODEL);
        require(report, "persist.reloadWritten", reloadWritten.executed(), reloadWritten.toString());
        final MeshSnapshot persisted = awaitEditableMesh(report);
        require(report, "persist.reopened", persisted.equals(after), "expected=" + after + " actual=" + persisted);

        final MeshPointRef persistedAdded = point(persisted, added.id()).orElseThrow();
        final MeshEditResult removed = edit.deletePoints(List.of(persistedAdded));
        requireApplied(report, "persist.cleanupDelete", removed);
        final MeshSnapshot restored = awaitSnapshot(
                snapshot -> snapshot.points().size() == before.points().size()
                        && snapshot.edges().size() == before.edges().size()
                        && point(snapshot, added.id()).isEmpty(),
                "persist cleanup");
        finishMeshEditIfActive(report);
        final SaveConfirmation cleanupSave = saveFixture(report, "saveRestored");
        require(report, "persist.saveRestored", cleanupSave.confirmed(), cleanupSave.toString());
        report.add(cleanupSave.report("saveRestored."));

        final EditorCommandResult reloadRestored = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(EditorCommand.RELOAD_MODEL);
        require(report, "persist.reloadRestored", reloadRestored.executed(), reloadRestored.toString());
        final MeshSnapshot finalState = awaitEditableMesh(report);
        require(
                report,
                "persist.finalRestored",
                finalState.equals(before),
                "expected=" + before + " actual=" + finalState);
        finishMeshEditIfActive(report);
        require(
                report,
                "persist.finalMeshEditorExited",
                context.services()
                        .find(MeshEditService.class)
                        .orElse(MeshEditService.unavailable())
                        .snapshot()
                        .points()
                        .isEmpty(),
                context.services()
                        .find(MeshEditService.class)
                        .orElse(MeshEditService.unavailable())
                        .snapshot()
                        .toString());
        report.add("persist.addedPointId=" + added.id());
        report.add("persist.restoredPoints=" + restored.points().size());
        report.add("persist.restoredEdges=" + restored.edges().size());
        failureBaseline.set(null);
        persistenceFileBaseline.set(null);
        persistenceFileWritten = false;
    }

    private MeshSnapshot awaitEditableMesh(final List<String> report) throws Exception {
        awaitVisibleModelWindow();
        awaitModelingDocument();
        MeshSnapshot snapshot = context.services()
                .find(MeshEditService.class)
                .orElse(MeshEditService.unavailable())
                .snapshot();
        if (!snapshot.points().isEmpty()) {
            report.add("meshEntry=already-active");
            return snapshot;
        }
        final SelectionTarget target = selectFirstArtMesh(report);
        report.add("detail.selection.snapshot="
                + safe(context.cubism().runtime().selection().toString()));
        final EditorCommandResult entered = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(EditorCommand.START_OR_END_MESH_EDITOR);
        report.add("meshEntry.attempts=1"); // A toggle is never blindly retried.
        require(report, "meshEntry.command", entered.executed(), entered.toString());
        report.add("meshEntry=command");
        return awaitSnapshot(value -> !value.points().isEmpty(), "mesh editor entry");
    }

    void awaitVisibleModelWindow() throws Exception {
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        Exception unavailable = null;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                final boolean visible = onEdt(() -> {
                    for (Frame frame : Frame.getFrames()) {
                        if (frame.isShowing()
                                && frame.isDisplayable()
                                && frame.getTitle() != null
                                && frame.getTitle().contains(".cmo3")) {
                            return true;
                        }
                    }
                    return false;
                });
                if (visible) return;
            } catch (IllegalStateException failure) {
                unavailable = failure;
            }
            Thread.sleep(250L);
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("model-window wait interrupted");
        }
        throw unavailable == null
                ? new IllegalStateException("visible Cubism model window did not become ready")
                : unavailable;
    }

    void awaitModelingDocument() throws Exception {
        Exception unavailable = null;
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline && !Thread.currentThread().isInterrupted()) {
            try {
                onEdt(() -> context.cubism().model().active().drawables().all().size());
                return;
            } catch (Exception failure) {
                unavailable = failure;
                Thread.sleep(250L);
            }
        }
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("modeling-document wait interrupted");
        }
        throw unavailable == null ? new IllegalStateException("modeling document did not become active") : unavailable;
    }

    SelectionTarget selectFirstArtMesh(final List<String> report) throws Exception {
        if (!onEdt(WindowsMeshEditValidationProbe::partsPaletteShowing)) {
            executeCommand(report, "selection.showPartsPalette", EditorCommand.SHOW_PARTS_PALETTE);
        } else {
            report.add("selection.partsPalette=already-visible");
        }
        final SelectionTarget target = onEdt(() -> uniqueSelectionTarget(
                context.cubism().model().active().drawables().all()));
        report.add("selection.requestedArtMeshId=" + safe(target.id()));
        report.add("selection.requestedDisplayName=" + safe(target.displayName()));
        report.add("selection.before="
                + safe(onEdt(() -> context.cubism().runtime().selection().toString())));
        SelectionAttempt attempt = new SelectionAttempt(false, "none", target.displayName(), -1, -1, -1, -1, -1, -1);
        final long selectionDeadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < selectionDeadline && !attempt.selected()) {
            attempt = onEdt(() -> selectTreePath(target.displayName()));
            if (!attempt.selected()) Thread.sleep(250L);
        }
        require(report, "selection.treePath", attempt.selected(), attempt.toString());
        report.add("selection.afterDispatch="
                + safe(onEdt(() -> context.cubism().runtime().selection().toString())));
        if (!onEdt(
                () -> context.cubism().runtime().selection().selectedObjectIds().contains(target.id()))) {
            final java.awt.Robot robot = new java.awt.Robot();
            robot.mouseMove(attempt.screenX(), attempt.screenY());
            robot.delay(200);
            final var pointer = java.awt.MouseInfo.getPointerInfo();
            report.add("selection.robotPointer="
                    + safe(
                            pointer == null
                                    ? "unavailable"
                                    : pointer.getLocation().toString()));
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            robot.delay(80);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            robot.waitForIdle();
            report.add("selection.input=java.awt.Robot");
            report.add("selection.afterRobot="
                    + safe(onEdt(() -> context.cubism().runtime().selection().toString())));
        }
        final long nativeDeadline = System.nanoTime() + 10_000_000_000L;
        boolean selected = false;
        while (System.nanoTime() < nativeDeadline && !selected) {
            selected = onEdt(() ->
                    context.cubism().runtime().selection().selectedObjectIds().contains(target.id()));
            if (!selected) Thread.sleep(100L);
        }
        require(
                report,
                "selection.nativeObject",
                selected,
                onEdt(() -> context.cubism().runtime().selection().toString()));
        report.add("selection.tree=" + safe(attempt.treeDescription()));
        return target;
    }

    private static boolean partsPaletteShowing() {
        for (Window window : Window.getWindows()) {
            if (window.isVisible() && hasShowingPartsTable(window)) return true;
        }
        return false;
    }

    private static boolean hasShowingPartsTable(final Component component) {
        if (component instanceof javax.swing.JTable table
                && table.isShowing()
                && table.getClass().getName().contains(".palette.parts.")) return true;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (hasShowingPartsTable(child)) return true;
            }
        }
        return false;
    }

    static SelectionTarget uniqueSelectionTarget(final List<? extends Drawable> drawables) {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        for (Drawable drawable : drawables) {
            final String name = drawable.name();
            if (name != null && !name.isBlank()) counts.merge(name, 1, Integer::sum);
        }
        return drawables.stream()
                .map(drawable -> new SelectionTarget(drawable.id().value(), drawable.name()))
                .filter(target ->
                        target.displayName() != null && !target.displayName().isBlank())
                .filter(target -> counts.getOrDefault(target.displayName(), 0) == 1)
                .min(Comparator.comparing(SelectionTarget::id))
                .orElseThrow(
                        () -> new IllegalStateException("fixture has no ArtMesh with a unique nonblank display name"));
    }

    static SelectionAttempt selectTreePath(final String displayName) {
        final List<String> observed = new ArrayList<>();
        final List<JTree> candidates = new ArrayList<>();
        for (Window window : Window.getWindows()) {
            if (!window.isVisible()) continue;
            final List<JTree> trees = new ArrayList<>();
            collectTrees(window, trees, observed);
            for (JTree tree : trees) {
                final String listeners = listenerClasses(tree);
                final javax.swing.JTable owner = findTreeTableOwner(tree);
                final String ownerName = owner == null ? "" : owner.getClass().getName();
                final boolean project = listeners.contains(".palette.project.");
                final boolean deformer = ownerName.contains("palette.deformer");
                final boolean parts = listeners.contains(".palette.parts.") || ownerName.contains("palette.parts");
                if (parts) {
                    candidates.add(tree);
                    observed.add("candidate-parts:" + describeTree(tree) + ":listeners=" + listeners);
                    continue;
                }
                if (project) {
                    // The Project palette contains image layers with the same rendered names as
                    // ArtMeshes. Selecting that row does not select an authored model object.
                    observed.add("skip-project:" + describeTree(tree) + ":listeners=" + listeners);
                    continue;
                }
                if (deformer) {
                    observed.add("skip-deformer:" + describeTree(tree) + ":owner=" + ownerName);
                    continue;
                }
                observed.add("skip-unknown:" + describeTree(tree) + ":listeners=" + listeners + ":owner="
                        + (ownerName.isEmpty() ? "none" : ownerName));
            }
        }
        for (JTree tree : candidates) {
            final List<TreePath> paths = findTreePaths(tree, displayName);
            if (paths.size() != 1) {
                if (paths.size() > 1) {
                    observed.add("ambiguous:" + describeTree(tree) + ":matches=" + paths.size());
                } else {
                    // A zero-match failure needs to show what the tree renders: the model's part
                    // names and the rendered row labels are not guaranteed to be the same text.
                    observed.add("no-match:" + describeTree(tree) + ":rendered=" + renderedLabels(tree));
                }
                continue;
            }
            final TreePath path = paths.get(0);
            tree.expandPath(path.getParentPath());
            tree.scrollPathToVisible(path);
            tree.clearSelection();
            tree.setSelectionPath(path);
            tree.setLeadSelectionPath(path);
            tree.setAnchorSelectionPath(path);
            final Rectangle bounds = tree.getPathBounds(path);
            if (bounds == null) {
                observed.add("no-bounds:" + describeTree(tree));
                continue;
            }
            final javax.swing.JTable owner = findTreeTableOwner(tree);
            final Component clickComponent;
            final Rectangle clickBounds;
            int nameColumn = -1;
            if (owner != null) {
                final int row = tree.getRowForPath(path);
                if (row < 0 || row >= owner.getRowCount()) {
                    observed.add("invalid-table-row:" + describeTree(tree) + ":" + row);
                    continue;
                }
                clickComponent = owner;
                nameColumn = renderedTreeColumn(owner, tree, row);
                owner.setRowSelectionInterval(row, row);
                owner.scrollRectToVisible(owner.getCellRect(row, nameColumn, true));
                clickBounds = owner.getCellRect(row, nameColumn, true);
            } else {
                clickComponent = tree;
                clickBounds = bounds;
            }
            final Window window = SwingUtilities.getWindowAncestor(clickComponent);
            if (window == null) {
                observed.add("no-window:" + describeTree(tree));
                continue;
            }
            final int localX = clickBounds.x + Math.max(1, clickBounds.width / 2);
            final int localY = clickBounds.y + Math.max(1, clickBounds.height / 2);
            final long when = System.currentTimeMillis();
            final int[] receipts = new int[3];
            final var receiptListener = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(final MouseEvent event) {
                    receipts[0]++;
                }

                @Override
                public void mouseReleased(final MouseEvent event) {
                    receipts[1]++;
                }

                @Override
                public void mouseClicked(final MouseEvent event) {
                    receipts[2]++;
                }
            };
            clickComponent.addMouseListener(receiptListener);
            try {
                clickComponent.dispatchEvent(new MouseEvent(
                        clickComponent,
                        MouseEvent.MOUSE_PRESSED,
                        when,
                        InputEvent.BUTTON1_DOWN_MASK,
                        localX,
                        localY,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                clickComponent.dispatchEvent(new MouseEvent(
                        clickComponent,
                        MouseEvent.MOUSE_RELEASED,
                        when + 1,
                        0,
                        localX,
                        localY,
                        1,
                        false,
                        MouseEvent.BUTTON1));
                clickComponent.dispatchEvent(new MouseEvent(
                        clickComponent,
                        MouseEvent.MOUSE_CLICKED,
                        when + 2,
                        0,
                        localX,
                        localY,
                        1,
                        false,
                        MouseEvent.BUTTON1));
            } finally {
                clickComponent.removeMouseListener(receiptListener);
            }
            final Point screen = new Point(localX, localY);
            SwingUtilities.convertPointToScreen(screen, clickComponent);
            final Point focus = new Point(
                    Math.max(1, window.getWidth() / 2),
                    Math.max(1, Math.min(window.getHeight() - 1, window.getHeight() / 3)));
            SwingUtilities.convertPointToScreen(focus, window);
            return new SelectionAttempt(
                    true,
                    describeTree(tree)
                            + " owner="
                            + (owner == null ? "none" : owner.getClass().getName() + " rows=" + owner.getRowCount())
                            + " click=" + clickComponent.getClass().getName()
                            + " nameColumn=" + nameColumn
                            + " nodeClass="
                            + path.getLastPathComponent().getClass().getName()
                            + " dispatchReceipts=" + java.util.Arrays.toString(receipts)
                            + " window=" + window.getClass().getName()
                            + " active=" + window.isActive() + " focused=" + window.isFocused()
                            + " listeners=" + listenerClasses(tree),
                    String.valueOf(path.getLastPathComponent()),
                    screen.x,
                    screen.y,
                    focus.x,
                    focus.y,
                    tree.getRowForPath(path),
                    clickBounds.height);
        }
        return new SelectionAttempt(false, String.join("|", observed), displayName, -1, -1, -1, -1, -1, -1);
    }

    /** Finds the table's tree-rendered name column; visibility/lock icon columns are never clicked. */
    static int renderedTreeColumn(final javax.swing.JTable table, final JTree tree, final int row) {
        for (int column = 0; column < table.getColumnCount(); column++) {
            if (table.getCellRenderer(row, column) == tree) return column;
        }
        throw new IllegalStateException("Parts table has no column rendered by its embedded tree");
    }

    private static void collectTrees(final Component component, final List<JTree> trees, final List<String> observed) {
        final String componentClass = component.getClass().getName();
        if (componentClass.toLowerCase(java.util.Locale.ROOT).contains("part")) {
            observed.add("part-component:" + componentClass + ":showing=" + component.isShowing());
        }
        if (component instanceof JTree tree && tree.isShowing()) {
            trees.add(tree);
            observed.add("tree:" + describeTree(tree));
        }
        if (component instanceof javax.swing.JTable table) {
            final String className = table.getClass().getName();
            if (className.contains("Parts") || className.contains("parts") || className.contains("TreeTable")) {
                final JTree embedded = extractTree(table);
                observed.add("table:" + className + ":rows=" + table.getRowCount() + ":embedded="
                        + (embedded == null ? "none" : embedded.getClass().getName()));
                if (embedded != null && !trees.contains(embedded)) trees.add(embedded);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) collectTrees(child, trees, observed);
        }
    }

    static JTree extractTree(final javax.swing.JTable table) {
        for (Component child : table.getComponents()) {
            if (child instanceof JTree tree) return tree;
        }
        Class<?> type = table.getClass();
        for (int depth = 0; type != null && depth < 4; depth++, type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;
                try {
                    field.setAccessible(true);
                    final Object value = field.get(table);
                    if (value instanceof JTree tree) return tree;
                    if (value instanceof Component nested) {
                        final JTree tree = findNestedTree(nested);
                        if (tree != null) return tree;
                    }
                } catch (ReflectiveOperationException | LinkageError ignored) {
                    // Continue through the bounded exact tree-table field scan.
                }
            }
        }
        return null;
    }

    private static JTree findNestedTree(final Component component) {
        if (component instanceof JTree tree) return tree;
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final JTree found = findNestedTree(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JTable findTreeTableOwner(final JTree tree) {
        for (Frame frame : Frame.getFrames()) {
            final javax.swing.JTable owner = findTreeTableOwner(frame, tree);
            if (owner != null) return owner;
        }
        return null;
    }

    private static javax.swing.JTable findTreeTableOwner(final Component component, final JTree tree) {
        if (component instanceof javax.swing.JTable table && extractTree(table) == tree) {
            return table;
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                final javax.swing.JTable owner = findTreeTableOwner(child, tree);
                if (owner != null) return owner;
            }
        }
        return null;
    }

    private static String listenerClasses(final JTree tree) {
        final java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        for (java.awt.event.MouseListener listener : tree.getMouseListeners()) {
            classes.add(listener.getClass().getName());
        }
        for (javax.swing.event.TreeSelectionListener listener : tree.getTreeSelectionListeners()) {
            classes.add(listener.getClass().getName());
        }
        return String.join(",", classes);
    }

    static List<TreePath> findTreePaths(final JTree tree, final String displayName) {
        final ArrayList<TreePath> matches = new ArrayList<>();
        final Object root = tree.getModel().getRoot();
        findTreePaths(tree, new TreePath(root), displayName, matches);
        return List.copyOf(matches);
    }

    private static void findTreePaths(
            final JTree tree, final TreePath path, final String displayName, final List<TreePath> matches) {
        final Object node = path.getLastPathComponent();
        final String rendered = tree.convertValueToText(node, false, false, false, 0, false);
        if (displayName.equals(rendered) || displayName.equals(String.valueOf(node))) {
            matches.add(path);
        }
        final int children = tree.getModel().getChildCount(node);
        for (int index = 0; index < children; index++) {
            final Object child = tree.getModel().getChild(node, index);
            findTreePaths(tree, path.pathByAddingChild(child), displayName, matches);
        }
    }

    private static String describeTree(final JTree tree) {
        return tree.getClass().getName() + " rows=" + tree.getRowCount();
    }

    /** A bounded sample of the labels the tree actually renders, for diagnosing name mismatches. */
    private static String renderedLabels(final JTree tree) {
        final ArrayList<String> labels = new ArrayList<>();
        final ArrayDeque<Object> pending = new ArrayDeque<>();
        pending.add(tree.getModel().getRoot());
        while (!pending.isEmpty() && labels.size() < 24) {
            final Object node = pending.removeFirst();
            labels.add(tree.convertValueToText(node, false, false, false, 0, false));
            final int children = tree.getModel().getChildCount(node);
            for (int index = 0; index < children && labels.size() + index < 48; index++) {
                pending.addLast(tree.getModel().getChild(node, index));
            }
        }
        return "[" + String.join(",", labels) + (pending.isEmpty() ? "" : ",…") + "]";
    }

    static <T> T onEdt(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final FutureTask<T> task = new FutureTask<>(call);
        SwingUtilities.invokeLater(task);
        try {
            return task.get(30L, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            task.cancel(false);
            throw new IllegalStateException("EDT operation timed out", failure);
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("EDT operation failed", cause);
        }
    }

    record SelectionTarget(String id, String displayName) {}

    record SelectionAttempt(
            boolean selected,
            String treeDescription,
            String node,
            int screenX,
            int screenY,
            int focusX,
            int focusY,
            int treeRow,
            int rowHeight) {}

    private void finishMeshEditIfActive(final List<String> report) throws Exception {
        if (context.services()
                .find(MeshEditService.class)
                .orElse(MeshEditService.unavailable())
                .snapshot()
                .points()
                .isEmpty()) {
            report.add("meshExit=already-inactive");
            return;
        }
        final EditorCommandResult result = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(EditorCommand.START_OR_END_MESH_EDITOR);
        require(report, "meshExit.command", result.executed(), result.toString());
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (context.services()
                    .find(MeshEditService.class)
                    .orElse(MeshEditService.unavailable())
                    .snapshot()
                    .points()
                    .isEmpty()) {
                report.add("meshExit=command");
                return;
            }
            Thread.sleep(100L);
        }
        throw new IllegalStateException("mesh editor did not exit within the bounded wait");
    }

    private MeshSnapshot mutate(
            final List<String> report,
            final String name,
            final MeshSnapshot before,
            final Callable<MeshEditResult> mutation,
            final java.util.function.Predicate<MeshSnapshot> expected)
            throws Exception {
        final MeshEditResult result = mutation.call();
        requireApplied(report, name + ".result", result);
        final MeshSnapshot after = awaitSnapshot(expected, name);
        require(report, name + ".changed", !after.equals(before), after.toString());
        return after;
    }

    private void undoRedo(
            final List<String> report, final String name, final MeshSnapshot before, final MeshSnapshot after)
            throws Exception {
        executeCommand(report, name + ".undo", EditorCommand.UNDO);
        require(
                report,
                name + ".undoState",
                awaitSnapshot(before::equals, name + " undo").equals(before),
                before.toString());
        executeCommand(report, name + ".redo", EditorCommand.REDO);
        require(
                report,
                name + ".redoState",
                awaitSnapshot(after::equals, name + " redo").equals(after),
                after.toString());
        report.add("assertion." + name + ".oneUndoStep=PASS");
    }

    private void executeCommand(final List<String> report, final String phase, final EditorCommand command) {
        final EditorCommandResult result = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(command);
        require(report, phase + ".command", result.executed(), result.toString());
    }

    private MeshSnapshot awaitSnapshot(final java.util.function.Predicate<MeshSnapshot> expected, final String phase)
            throws Exception {
        final long deadline = System.nanoTime() + SNAPSHOT_TIMEOUT_MILLIS * 1_000_000L;
        MeshSnapshot actual = context.services()
                .find(MeshEditService.class)
                .orElse(MeshEditService.unavailable())
                .snapshot();
        while (System.nanoTime() < deadline) {
            if (expected.test(actual)) return actual;
            Thread.sleep(100L);
            actual = context.services()
                    .find(MeshEditService.class)
                    .orElse(MeshEditService.unavailable())
                    .snapshot();
        }
        throw new IllegalStateException("mesh snapshot timed out during " + phase + "; actual=" + actual);
    }

    private SaveConfirmation saveFixture(final List<String> report, final String phase) throws Exception {
        final Path fixture = fixturePath();
        final FileTime beforeMtime = Files.getLastModifiedTime(fixture);
        final long beforeSize = Files.size(fixture);
        final EditorCommandResult command = context.services()
                .find(EditorCommandService.class)
                .orElse(EditorCommandService.unavailable())
                .execute(EditorCommand.SAVE);
        require(report, phase + ".command", command.executed(), command.toString());
        return awaitSaveConfirmation(fixture, beforeMtime, beforeSize, SAVE_TIMEOUT_MILLIS, SAVE_POLL_MILLIS);
    }

    static boolean isExactPointAddition(
            final MeshSnapshot before, final MeshSnapshot after, final MeshPointPosition requested) {
        if (!after.edges().equals(before.edges())
                || after.points().size() != before.points().size() + 1) return false;
        final Map<Integer, MeshPointRef> old = pointsById(before);
        for (MeshPointRef point : before.points()) {
            if (!after.points().contains(point)) return false;
        }
        return after.points().stream()
                        .filter(point -> !old.containsKey(point.id()))
                        .filter(point -> same(point.x(), requested.x()) && same(point.y(), requested.y()))
                        .count()
                == 1L;
    }

    static boolean isExactPointMove(final MeshSnapshot before, final MeshSnapshot after, final MeshPointRef moved) {
        if (!after.edges().equals(before.edges())
                || after.points().size() != before.points().size()) {
            return false;
        }
        for (MeshPointRef point : before.points()) {
            final MeshPointRef expected = point.id() == moved.id() ? moved : point;
            if (!after.points().contains(expected)) return false;
        }
        return true;
    }

    static boolean isExactEdgeAddition(final MeshSnapshot before, final MeshSnapshot after, final MeshEdgeRef added) {
        if (!after.points().equals(before.points())
                || after.edges().size() != before.edges().size() + 1
                || !after.edges().contains(added)) return false;
        return before.edges().stream().allMatch(after.edges()::contains);
    }

    static boolean isExactEdgeDeletion(final MeshSnapshot before, final MeshSnapshot after, final MeshEdgeRef deleted) {
        if (!after.points().equals(before.points())
                || after.edges().size() != before.edges().size() - 1
                || after.edges().contains(deleted)) return false;
        return after.edges().stream().allMatch(before.edges()::contains);
    }

    static boolean isExactPointDeletion(final MeshSnapshot before, final MeshSnapshot after, final int deletedId) {
        if (after.points().size() != before.points().size() - 1
                || point(after, deletedId).isPresent()) return false;
        if (!after.points().stream().allMatch(before.points()::contains)) return false;
        final List<MeshEdgeRef> expectedEdges = before.edges().stream()
                .filter(edge -> !endpoint(edge, deletedId))
                .toList();
        return after.edges().equals(expectedEdges);
    }

    static MeshPointRef discoverAddedPoint(
            final MeshSnapshot before, final MeshSnapshot after, final MeshPointPosition requested) {
        final Map<Integer, MeshPointRef> old = pointsById(before);
        final List<MeshPointRef> added = after.points().stream()
                .filter(point -> !old.containsKey(point.id()))
                .toList();
        if (added.size() != 1) {
            throw new IllegalStateException("expected exactly one assigned point id; added=" + added);
        }
        final MeshPointRef point = added.get(0);
        if (!same(point.x(), requested.x()) || !same(point.y(), requested.y())) {
            throw new IllegalStateException("assigned point position differs from request: " + point);
        }
        return point;
    }

    static MeshPointPosition distinctPosition(final MeshSnapshot snapshot) {
        if (snapshot.points().isEmpty()) throw new IllegalArgumentException("mesh has no points");
        final float minX = snapshot.points().stream()
                .map(MeshPointRef::x)
                .min(Float::compare)
                .orElseThrow();
        final float maxX = snapshot.points().stream()
                .map(MeshPointRef::x)
                .max(Float::compare)
                .orElseThrow();
        final float minY = snapshot.points().stream()
                .map(MeshPointRef::y)
                .min(Float::compare)
                .orElseThrow();
        final float maxY = snapshot.points().stream()
                .map(MeshPointRef::y)
                .max(Float::compare)
                .orElseThrow();
        final float span = Math.max(Math.max(maxX - minX, maxY - minY), 1.0F);
        return new MeshPointPosition(maxX + span * 0.173F, maxY + span * 0.197F);
    }

    static MeshPointRef movedPoint(final MeshPointRef source, final MeshSnapshot snapshot) {
        final MeshPointPosition anchor = distinctPosition(snapshot);
        return new MeshPointRef(source.id(), anchor.x(), anchor.y());
    }

    static MeshPointRef chooseEdgePartner(final MeshSnapshot snapshot, final int addedId) {
        final Map<Integer, MeshPointRef> points = pointsById(snapshot);
        return snapshot.points().stream()
                .filter(point -> point.id() != addedId)
                .filter(point -> snapshot.edges().stream()
                        .noneMatch(edge -> edge.equals(new MeshEdgeRef(addedId, point.id(), edge.kind()))))
                .min(Comparator.comparingInt(MeshPointRef::id))
                .orElseGet(() -> points.values().stream()
                        .filter(point -> point.id() != addedId)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("no edge partner is available")));
    }

    static MeshPointRef pointWithConnectedEdge(final MeshSnapshot snapshot) {
        final MeshEdgeRef edge = snapshot.edges().get(0);
        return point(snapshot, edge.startPointId()).orElseThrow();
    }

    static List<MeshEdgeRef> connectedEdges(final MeshSnapshot snapshot, final int pointId) {
        return snapshot.edges().stream().filter(edge -> endpoint(edge, pointId)).toList();
    }

    static Map<Integer, MeshPointRef> pointsById(final MeshSnapshot snapshot) {
        final Map<Integer, MeshPointRef> points = new LinkedHashMap<>();
        for (MeshPointRef point : snapshot.points()) {
            if (points.put(point.id(), point) != null) {
                throw new IllegalArgumentException("duplicate mesh point id: " + point.id());
            }
        }
        return Map.copyOf(points);
    }

    private static java.util.Optional<MeshPointRef> point(final MeshSnapshot snapshot, final int id) {
        return snapshot.points().stream().filter(point -> point.id() == id).findFirst();
    }

    private static boolean endpoint(final MeshEdgeRef edge, final int pointId) {
        return edge.startPointId() == pointId || edge.endPointId() == pointId;
    }

    private static boolean samePosition(final MeshPointRef first, final MeshPointRef second) {
        return same(first.x(), second.x()) && same(first.y(), second.y());
    }

    private static boolean same(final float first, final float second) {
        return Math.abs(first - second) <= EPSILON;
    }

    private static void requireApplied(final List<String> report, final String name, final MeshEditResult result) {
        require(report, name, result.accepted() && result.rejected().isEmpty(), result.toString());
    }

    static void require(final List<String> report, final String name, final boolean condition, final String detail) {
        report.add("assertion." + name + "=" + (condition ? "PASS" : "FAIL"));
        report.add("detail." + name + "=" + safe(detail));
        if (!condition) throw new IllegalStateException(name + ": " + safe(detail));
    }

    static Path fixturePath() {
        final String value = System.getProperty("turboism.validation.fixture");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("turboism.validation.fixture is required");
        }
        return Path.of(value);
    }

    static SaveConfirmation awaitSaveConfirmation(
            final Path fixture,
            final FileTime beforeMtime,
            final long beforeSize,
            final long deadlineMillis,
            final long pollMillis)
            throws Exception {
        if (!Files.isRegularFile(fixture)) {
            throw new IllegalArgumentException("validation fixture is missing: " + fixture);
        }
        final long deadline = System.nanoTime() + deadlineMillis * 1_000_000L;
        FileTime changedMtime = null;
        long changedSize = -1L;
        int stable = 0;
        while (System.nanoTime() < deadline) {
            final FileTime mtime = Files.getLastModifiedTime(fixture);
            final long size = Files.size(fixture);
            if (!mtime.equals(beforeMtime) || size != beforeSize) {
                if (!Objects.equals(changedMtime, mtime) || changedSize != size) {
                    changedMtime = mtime;
                    changedSize = size;
                    stable = 0;
                }
                if (++stable >= SAVE_STABLE_SAMPLES) {
                    return new SaveConfirmation(true, beforeMtime.toMillis(), beforeSize, mtime.toMillis(), size);
                }
            }
            Thread.sleep(pollMillis);
        }
        return new SaveConfirmation(
                false,
                beforeMtime.toMillis(),
                beforeSize,
                Files.getLastModifiedTime(fixture).toMillis(),
                Files.size(fixture));
    }

    record SaveConfirmation(
            boolean confirmed, long beforeMtimeMillis, long beforeSize, long afterMtimeMillis, long afterSize) {
        String report(final String prefix) {
            return prefix + "confirmed=" + confirmed + ";"
                    + prefix + "beforeMtimeMillis=" + beforeMtimeMillis + ";"
                    + prefix + "beforeSize=" + beforeSize + ";"
                    + prefix + "afterMtimeMillis=" + afterMtimeMillis + ";"
                    + prefix + "afterSize=" + afterSize;
        }
    }

    private static String failureDescription(final Throwable failure) {
        final StringBuilder result = new StringBuilder();
        Throwable current = failure;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) result.append(" <- ");
            result.append(current.getClass().getSimpleName())
                    .append(": ")
                    .append(current.getMessage() == null ? "" : current.getMessage());
        }
        return result.toString();
    }

    private static String safe(final String value) {
        return String.valueOf(value).replace('\r', ' ').replace('\n', ' ').replace('=', ':');
    }

    private void requestHostClose(final boolean terminalPublished) {
        try {
            final var close = WindowsHistoryNativeUiHostClose.closeIfEligible(
                    Boolean.getBoolean(EXIT_PROPERTY),
                    worker == Thread.currentThread() && !Thread.currentThread().isInterrupted(),
                    terminalPublished,
                    System.getProperty(WindowsHistoryNativeUiHostClose.RUN_ID_PROPERTY),
                    System.getProperty(WindowsHistoryNativeUiHostClose.HOST_VERSION_PROPERTY));
            context.logger().info("Mesh validation host close status=" + close.status() + " reason=" + close.reason());
        } catch (Exception failure) {
            context.logger().error("Automated mesh validation host close failed", failure);
        }
    }
}
