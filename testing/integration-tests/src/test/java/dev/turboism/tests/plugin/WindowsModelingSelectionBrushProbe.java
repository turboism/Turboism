package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsMeshEditValidationProbe.*;

import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.plugin.PluginContext;
import java.awt.Component;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.Point;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JSeparator;
import javax.swing.SwingUtilities;

/**
 * Test-only ordinary-mode acceptance. Native readback, display-position oracle and hit expectation
 * are independent of the production projector, route and selection adapter. Native writes here
 * only prepare the object selection; every accepted point-selection gesture uses Robot.
 */
final class WindowsModelingSelectionBrushProbe {
    private static final int RADIUS = 32;
    private final WindowsMeshEditValidationProbe harness;
    private final PluginContext context;
    private final List<String> report;
    private Object app;
    private Object document;
    private Object view;
    private Object camera;
    private Object model;
    private Object selector;
    private Object updateManager;
    private JComponent canvas;
    private AbstractButton brushButton;
    private List<Object> originals;
    private int nativeHistoryDelta;
    private boolean nativeHistorySignificant;
    private KeyboardFocusManager keyboard;
    private Window taskWindow;
    private final int[] escapeReceipts = new int[2];
    private final int[] navigationReceipts = new int[2];
    private final KeyEventDispatcher keyboardObserver = event -> {
        if (keyboard.getFocusedWindow() == taskWindow) {
            final int[] receipts =
                    switch (event.getKeyCode()) {
                        case KeyEvent.VK_ESCAPE -> escapeReceipts;
                        case KeyEvent.VK_SPACE -> navigationReceipts;
                        default -> null;
                    };
            if (receipts != null) {
                if (event.getID() == KeyEvent.KEY_PRESSED) receipts[0]++;
                if (event.getID() == KeyEvent.KEY_RELEASED) receipts[1]++;
            }
        }
        return false;
    };

    WindowsModelingSelectionBrushProbe(
            WindowsMeshEditValidationProbe harness, PluginContext context, List<String> report) {
        this.harness = harness;
        this.context = context;
        this.report = report;
    }

    void run() throws Exception {
        harness.awaitVisibleModelWindow();
        harness.awaitModelingDocument();
        harness.selectFirstArtMesh(report);
        onEdt(() -> {
            final ClassLoader loader = showingComponents().stream()
                    .map(Component::getClass)
                    .filter(type -> type.getName().startsWith("com.live2d."))
                    .findFirst()
                    .orElseThrow()
                    .getClassLoader();
            app = Class.forName("com.live2d.cubism.CEAppCtrl", false, loader)
                    .getMethod("access$get_instance$cp")
                    .invoke(null);
            document = call(app, "getCurrentDoc");
            view = call(app, "getCurrentViewContext");
            check(
                    "ordinaryMode",
                    call(document, "getCurrentEditMode")
                            .getClass()
                            .getSimpleName()
                            .equals("CModelingEditMode_Main"),
                    document.toString());
            selector = call(call(document, "getCurrentEditMode"), "getSelector");
            model = call(view, "getModel");
            camera = call(view, "getCameraManager");
            final Object pack = call(view, "getCompletePack");
            updateManager = call(pack, "getUpdateManager");
            canvas = (JComponent) call(call(pack, "getMainViewPanel"), "getJComponent");
            originals = List.copyOf(list(call(selector, "getSelectedObjects")));
            report.add("modeling.identity=document:" + System.identityHashCode(document) + ",view:"
                    + System.identityHashCode(view) + ",model:" + System.identityHashCode(model));
            return null;
        });
        brushButton = awaitBrushButton();
        onEdt(() -> {
            assertToolbarPosition();
            check("toolbarNoBorder", !brushButton.isBorderPainted(), brushButton.toString());
            check(
                    "toolbarNoFocusOutline",
                    !brushButton.isFocusPainted() && !brushButton.isFocusable(),
                    brushButton.toString());
            return null;
        });
        final List<Object> targets = onEdt(this::fixtureTargets);
        final Object mesh = targets.get(0), warp = targets.get(1);
        final String geometry = onEdt(this::authoredGeometry);
        final List<String> parameters = onEdt(this::parameters);
        final List<String> dirty = onEdt(this::dirty);
        final CameraState initialCamera = onEdt(() -> new CameraState(
                number(call(call(camera, "getCameraWrapper"), "getCameraScale")),
                copyVector(call(call(camera, "getCameraWrapper"), "getCameraLookAt")),
                copyVector(call(call(call(camera, "getCamera"), "getTransform"), "getPosition"))));
        // Observe actual input before the production activation installs its consuming dispatcher.
        onEdt(() -> {
            keyboard = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            taskWindow = SwingUtilities.getWindowAncestor(canvas);
            keyboard.addKeyEventDispatcher(keyboardObserver);
            return null;
        });
        Exception primaryFailure = null;
        try {
            setupObjects(List.of(mesh));
            nativeSelectionBaseline();
            runFamily("artMesh", List.of(mesh), initialCamera);
            runFamily("warp", List.of(warp), initialCamera);
            runFamily("mixed", List.of(mesh, warp), initialCamera);
            for (float scale : new float[] {0.5f, 1f, 2f}) {
                restoreCamera(initialCamera);
                setupObjects(List.of(mesh, warp));
                onEdt(() -> {
                    final Point target = targetPath(nativeCandidates()).get(0);
                    // The setter takes a relative factor; the getter returns document units per pixel.
                    final float factor = (1f / scale) / number(call(camera, "getCameraScale"));
                    call(camera, "setCameraScale", factor, nativePoint(target));
                    final float effective = number(call(camera, "getCameraScale"));
                    check(
                            "scale" + scale + ".actualMagnification",
                            Math.abs(effective * scale - 1f) < .001f,
                            "documentUnitsPerPixel=" + effective);
                    return null;
                });
                report.add(
                        "modeling.scale" + scale + ".nativeCameraScale=" + onEdt(() -> call(camera, "getCameraScale")));
                activate();
                final JComponent overlay = awaitOverlay(1);
                final List<NativePoint> candidates = onEdt(this::nativeCandidates);
                stroke(
                        "scale" + scale,
                        overlay,
                        candidates,
                        List.of(targetPath(candidates).get(0)),
                        0);
                robotEscape("scale" + scale + ".escape");
                awaitOverlay(0);
            }
            restoreCamera(initialCamera);
            setupObjects(List.of(mesh, warp));
            proveNavigationAndCleanup();
            check(
                    "dirtyUnchanged",
                    dirty.equals(onEdt(this::dirty)),
                    onEdt(this::dirty).toString());
            proveMeshEntryCleanup(mesh);
            check("geometryUnchanged", geometry.equals(onEdt(this::authoredGeometry)), "authored point arrays");
            check("parametersUnchanged", parameters.equals(onEdt(this::parameters)), parameters.toString());
            report.add("modeling.status=PASS");
        } catch (Exception failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (overlayCount() != 0) invokeEscape(awaitOverlay(1));
                restoreCamera(initialCamera);
                setupObjects(originals);
            } catch (Exception cleanupFailure) {
                if (primaryFailure == null) throw cleanupFailure;
                primaryFailure.addSuppressed(cleanupFailure);
                report.add("modeling.cleanupFailure=" + cleanupFailure);
            } finally {
                onEdt(() -> {
                    keyboard.removeKeyEventDispatcher(keyboardObserver);
                    return null;
                });
            }
        }
    }

    private Object nativePoint(Point point) throws Exception {
        return Class.forName("com.live2d.type.CPoint", false, camera.getClass().getClassLoader())
                .getConstructor(int.class, int.class)
                .newInstance(point.x, point.y);
    }

    private Object copyVector(Object vector) throws Exception {
        return vector.getClass().getConstructor(vector.getClass()).newInstance(vector);
    }

    private void restoreCamera(CameraState state) throws Exception {
        onEdt(() -> {
            call(call(camera, "getCameraWrapper"), "setCameraScale", state.scale());
            call(camera, "setCameraLookAt", state.lookAt());
            call(call(call(call(camera, "getCamera"), "getTransform"), "getPosition"), "set", state.position());
            call(camera, "updateCamera", false);
            call(camera, "repaintCanvas");
            return null;
        });
    }

    private record CameraState(float scale, Object lookAt, Object position) {}

    private List<Object> fixtureTargets() throws Exception {
        final List<Object> sources = list(call(call(document, "getModelSource"), "getAllObjects"));
        Object mesh = null, warp = null;
        int meshCount = -1, warpCount = -1;
        for (Object source : sources) {
            final String type = source.getClass().getSimpleName();
            if (!type.equals("CArtMeshSource") && !type.equals("CWarpDeformerSource")) continue;
            if (!Boolean.TRUE.equals(call(source, "isEditableInHierarchy"))) continue;
            final Object object = call(model, "getObject", call(source, "getId"));
            if (object == null || !Boolean.TRUE.equals(call(view, "isEditableInView", object))) continue;
            final int visible =
                    (int) candidatesFor(source).stream().filter(this::inside).count();
            if (type.equals("CArtMeshSource") && visible > meshCount) {
                mesh = source;
                meshCount = visible;
            }
            if (type.equals("CWarpDeformerSource") && visible > warpCount) {
                warp = source;
                warpCount = visible;
            }
        }
        check("fixtureArtMesh", mesh != null && meshCount >= 2, "visible=" + meshCount);
        check("fixtureWarp", warp != null && warpCount >= 2, "visible=" + warpCount);
        report.add("modeling.fixture.mesh=" + id(mesh));
        report.add("modeling.fixture.warp=" + id(warp));
        final int columns = ((Number) call(warp, "getCol")).intValue();
        final int rows = ((Number) call(warp, "getRow")).intValue();
        final int count = candidatesFor(warp).size();
        check(
                "warpControlPointCount",
                count == (columns + 1) * (rows + 1),
                "count=" + count + " col=" + columns + " row=" + rows);
        return List.of(mesh, warp);
    }

    private void setupObjects(List<Object> sources) throws Exception {
        final List<String> expected = onEdt(() -> {
            final List<String> ids = new ArrayList<>();
            for (Object source : sources) ids.add(id(source));
            return ids.stream().sorted().toList();
        });
        onEdt(() -> {
            selector = call(call(document, "getCurrentEditMode"), "getSelector");
            // Follow the Parts selection path: update the live selector first, then
            // publish its selection to native UI observers. This is fixture setup only.
            call(selector, "clearSelection");
            for (Object source : sources) call(selector, "addSelected", source, -1);
            call(updateManager, "updateSelectionBySelectorSelected", updateManager, true, false, false);
            return null;
        });
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (!expected.equals(onEdt(this::objectIds)) && System.nanoTime() < deadline) Thread.sleep(50);
        check(
                "setupObjects",
                expected.equals(onEdt(this::objectIds)),
                "expected=" + expected + " actual=" + onEdt(this::objectIds));
        report.add("modeling.setup.delivery=native-test-only-selector-then-ui-sync");
    }

    private void nativeSelectionBaseline() throws Exception {
        switchNativeTool("toolGroup_lasso", "ToolMode_Lasso");
        normalizeFixtureScale("nativeBaseline", onEdt(() -> list(call(selector, "getSelectedObjects"))));
        onEdt(() -> {
            call(selector, "clearSubSelection");
            return null;
        });
        final List<NativePoint> points = onEdt(this::nativeCandidates);
        final NativePoint target =
                points.stream().filter(this::inside).findFirst().orElseThrow();
        final HistorySnapshot before = history();
        final List<String> dirtyBefore = onEdt(this::dirty);
        final String geometryBefore = onEdt(this::authoredGeometry);
        final Point center = target.rounded();
        final int extent = 2 * RADIUS;
        final List<Point> outline = List.of(
                new Point(center.x - extent, center.y - extent),
                new Point(center.x + extent, center.y - extent),
                new Point(center.x + extent, center.y + extent),
                new Point(center.x - extent, center.y + extent),
                new Point(center.x - extent, center.y - extent));
        report.add("modeling.nativeBaseline.target=" + target);
        report.add("modeling.nativeBaseline.candidatesBefore=" + points);
        report.add("modeling.nativeBaseline.objectsBefore=" + onEdt(this::objectIds));
        final List<Point> delivered = robotGesture(canvas, outline, 0, null, "nativeBaseline");
        report.add("modeling.nativeBaseline.requestedPath=" + outline);
        report.add("modeling.nativeBaseline.deliveredPath=" + delivered);
        report.add("modeling.nativeBaseline.candidatesAfter=" + onEdt(this::nativeCandidates));
        report.add("modeling.nativeBaseline.objectsAfter=" + onEdt(this::objectIds));
        report.add("modeling.nativeBaseline.nativeReleasePoint=" + nativeMousePoint());
        final String actionTool = onEdt(
                () -> call(call(view, "getLastActionPack"), "ak").getClass().getSimpleName());
        check("nativeBaselineActionTool", actionTool.equals("ToolMode_Lasso"), actionTool);
        final Set<String> expected = lassoHits(points, outline);
        check("nativeBaselineSinglePointWitness", expected.size() == 1, expected.toString());
        final Set<String> deliveredExpected = lassoHits(points, delivered);
        check(
                "nativeBaselineMatchesDeliveredPath",
                expected.equals(deliveredExpected),
                "requested=" + expected + " delivered=" + deliveredExpected);
        final Set<String> selected = onEdt(this::selectedPoints);
        check("nativeBaselineReadsTarget", selected.contains(target.key()), selected.toString());
        check(
                "nativeBaselineExactSelection",
                selected.equals(expected),
                "expected=" + expected + " actual=" + selected);
        assertBoundingBox("nativeBaseline", selected);
        final HistorySnapshot after = history();
        nativeHistoryDelta = after.position() - before.position();
        check(
                "nativeBaselineHistory",
                nativeHistoryDelta >= 0 && nativeHistoryDelta <= 1,
                "before=" + before + " after=" + after);
        nativeHistorySignificant = after.position() > 0
                && after.entries().get(after.position() - 1).significant();
        report.add("modeling.nativeBaseline.tool="
                + onEdt(() -> call(app, "getToolMode").getClass().getName()));
        check("nativeBaselineToolRetained", onEdt(() -> toolMode().equals("ToolMode_Lasso")), onEdt(this::toolMode));
        report.add("modeling.nativeBaseline.historyDelta=" + nativeHistoryDelta);
        report.add("modeling.nativeBaseline.significant=" + nativeHistorySignificant);
        report.add("modeling.nativeBaseline.selection=" + selected);
        check("nativeBaselineGeometry", geometryBefore.equals(onEdt(this::authoredGeometry)), "authored point arrays");
        check(
                "nativeBaselineDirty",
                dirtyBefore.equals(onEdt(this::dirty)),
                onEdt(this::dirty).toString());
    }

    private void runFamily(String name, List<Object> owners, CameraState initialCamera) throws Exception {
        restoreCamera(initialCamera);
        setupObjects(owners);
        normalizeFixtureScale(name, owners);
        activate();
        final JComponent overlay = awaitOverlay(1);
        final List<NativePoint> points = onEdt(this::nativeCandidates);
        report.add("modeling." + name + ".candidateCount=" + points.size());
        report.add("modeling." + name + ".displayedPoints=" + points);
        final List<Point> path;
        if (owners.size() == 2) {
            final NativePoint mesh = points.stream()
                    .filter(this::inside)
                    .filter(point -> point.key().contains(":MeshPointRef:"))
                    .findFirst()
                    .orElseThrow();
            final NativePoint warp = points.stream()
                    .filter(this::inside)
                    .filter(point -> point.key().contains(":WarpPointRef:"))
                    .findFirst()
                    .orElseThrow();
            path = List.of(mesh.rounded(), warp.rounded());
            check(
                    name + ".bothFamiliesHit",
                    hits(points, path, RADIUS).stream().anyMatch(key -> key.contains(":MeshPointRef:"))
                            && hits(points, path, RADIUS).stream().anyMatch(key -> key.contains(":WarpPointRef:")),
                    path.toString());
            final Set<String> meshIndices = new LinkedHashSet<>();
            for (NativePoint point : points)
                if (point.key().contains(":MeshPointRef:"))
                    meshIndices.add(point.key().substring(point.key().lastIndexOf(':') + 1));
            check(
                    name + ".sameLocalIndexWitness",
                    points.stream()
                            .filter(point -> point.key().contains(":WarpPointRef:"))
                            .anyMatch(point -> meshIndices.contains(
                                    point.key().substring(point.key().lastIndexOf(':') + 1))),
                    "owner-qualified keys="
                            + points.stream().map(NativePoint::key).toList());
        } else path = targetPath(points);
        stroke(name + ".replace", overlay, points, path, 0);
        final List<Point> second = separatedPath(points, path.get(0));
        stroke(name + ".add", overlay, points, second, InputEvent.SHIFT_DOWN_MASK);
        stroke(name + ".remove", overlay, points, path, InputEvent.CTRL_DOWN_MASK);
        stroke(name + ".ctrlShift", overlay, points, second, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
        final Point empty = emptyPoint(points);
        stroke(name + ".zeroHit", overlay, points, List.of(empty, new Point(empty.x + 2, empty.y)), 0);
        stroke(name + ".seedForCancel", overlay, points, path, 0);
        final Set<String> beforeCancel = onEdt(this::selectedPoints);
        check(name + ".cancelSeedNonempty", !beforeCancel.isEmpty(), beforeCancel.toString());
        final HistorySnapshot historyBeforeCancel = history();
        robotGesture(overlay, path, 0, () -> robotEscape(name + ".escapeDuringStroke"), name + ".cancel");
        awaitOverlay(0);
        final Set<String> afterCancel = onEdt(this::selectedPoints);
        report.add("modeling." + name + ".cancelObjects=" + onEdt(this::objectIds));
        report.add("modeling." + name + ".cancelHistory=" + history());
        check(
                name + ".cancelUnchanged",
                beforeCancel.equals(afterCancel),
                "before=" + beforeCancel + " after=" + afterCancel);
        check(
                name + ".cancelHistory",
                sameHistory(historyBeforeCancel, history()),
                history().toString());
        check(name + ".buttonCleared", !onEdt(brushButton::isSelected), brushButton.toString());
    }

    private void normalizeFixtureScale(String name, List<Object> owners) throws Exception {
        onEdt(() -> {
            final List<NativePoint> visible =
                    candidatesFor(owners.get(0)).stream().filter(this::inside).toList();
            if (visible.isEmpty()) throw new IllegalStateException("fixture owner has no visible camera pivot");
            final Point pivot = new Point(
                    (int) Math.round(visible.stream()
                            .mapToDouble(NativePoint::x)
                            .average()
                            .orElseThrow()),
                    (int) Math.round(visible.stream()
                            .mapToDouble(NativePoint::y)
                            .average()
                            .orElseThrow()));
            // The setter is relative, and exact versions open the fixture at different fit scales.
            // Normalize to one document unit per pixel so radius-32 witnesses remain separated.
            final float factor = 1f / number(call(camera, "getCameraScale"));
            call(camera, "setCameraScale", factor, nativePoint(pivot));
            check(
                    name + ".fixtureScale",
                    Math.abs(number(call(camera, "getCameraScale")) - 1f) < .001f,
                    "documentUnitsPerPixel=" + call(camera, "getCameraScale"));
            report.add("modeling." + name + ".nativeCameraScale=" + call(camera, "getCameraScale"));
            return null;
        });
    }

    private void stroke(String name, JComponent overlay, List<NativePoint> points, List<Point> path, int modifiers)
            throws Exception {
        report.add("modeling." + name + ".dirtyBefore=" + onEdt(this::dirty));
        final Set<String> before = onEdt(this::selectedPoints);
        final List<String> objects = onEdt(this::objectIds);
        final HistorySnapshot historyBefore = history();
        final Set<String> hits = hits(points, path, RADIUS);
        final Set<String> expected = apply(before, hits, modifiers);
        final long start = System.nanoTime();
        final List<Point> delivered = robotGesture(overlay, path, modifiers, null, name);
        report.add("modeling." + name + ".dirtyAfterRelease=" + onEdt(this::dirty));
        final Set<String> actual = onEdt(this::selectedPoints);
        check(
                name + ".deliveredFootprint",
                hits.equals(hits(points, delivered, RADIUS)),
                "requested=" + path + " delivered=" + delivered);
        check(name + ".nativeReadback", expected.equals(actual), "expected=" + expected + " actual=" + actual);
        check(name + ".objectsRetained", objects.equals(onEdt(this::objectIds)), objects.toString());
        assertBoundingBox(name + ".release", expected);
        assertExclusiveBrush();
        final HistorySnapshot after = history();
        final int delta = after.position() - historyBefore.position();
        if (before.equals(expected)) {
            check(name + ".noOpHistory", sameHistory(historyBefore, after), after.toString());
        } else {
            check(
                    name + ".nativeHistoryDelta",
                    delta == nativeHistoryDelta,
                    "native=" + nativeHistoryDelta + " brush=" + delta);
            if (delta == 1) {
                check(
                        name + ".selectionHistoryOnly",
                        after.entries().get(after.position() - 1).significant() == nativeHistorySignificant,
                        after.toString());
                execute(EditorCommand.UNDO);
                check(
                        name + ".undo",
                        before.equals(onEdt(this::selectedPoints)),
                        onEdt(this::selectedPoints).toString());
                assertBoundingBox(name + ".undo", before);
                assertExclusiveBrush();
                execute(EditorCommand.REDO);
                check(
                        name + ".redo",
                        expected.equals(onEdt(this::selectedPoints)),
                        onEdt(this::selectedPoints).toString());
                assertBoundingBox(name + ".redo", expected);
                assertExclusiveBrush();
            }
        }
        report.add("modeling." + name + ".elapsedMillis=" + (System.nanoTime() - start) / 1_000_000L);
    }

    private List<Point> robotGesture(
            JComponent target, List<Point> path, int modifiers, CheckedAction cancel, String name) throws Exception {
        final Robot robot = new Robot();
        final Point swingOrigin = harness.awaitRobotWindowOrigin(target);
        final Point origin = target == canvas ? nativeRobotOrigin(robot, swingOrigin) : swingOrigin;
        if (name.equals("nativeBaseline")) {
            report.add("modeling.nativeBaseline.candidatesAfterCalibration=" + onEdt(this::nativeCandidates));
            report.add("modeling.nativeBaseline.canvasBounds=" + onEdt(canvas::getBounds));
        }
        final Set<String> before = onEdt(this::selectedPoints);
        final HistorySnapshot historyBefore = history();
        final List<Point> delivered = new ArrayList<>();
        final int[] counts = new int[3];
        final int[] pressModifiers = {-1};
        final MouseAdapter receipt = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                counts[0]++;
                pressModifiers[0] = event.getModifiersEx();
                delivered.add(event.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                counts[1]++;
                delivered.add(event.getPoint());
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                counts[2]++;
            }
        };
        onEdt(() -> {
            target.addMouseListener(receipt);
            target.addMouseMotionListener(receipt);
            return null;
        });
        boolean pressed = false;
        try {
            robot.mouseMove(origin.x + path.get(0).x, origin.y + path.get(0).y);
            robot.delay(100);
            robot.waitForIdle();
            // Establish the final pointer/window focus before pressing modifiers. Native focus
            // transitions can otherwise drop a key-down issued before moving to the stroke.
            if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_SHIFT);
            if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) robot.keyPress(KeyEvent.VK_CONTROL);
            robot.delay(100);
            robot.waitForIdle();
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            pressed = true;
            robot.waitForIdle();
            final int modifierMask = InputEvent.SHIFT_DOWN_MASK | InputEvent.CTRL_DOWN_MASK;
            check(
                    name + ".pressModifiersDelivered",
                    onEdt(() -> (pressModifiers[0] & modifierMask) == (modifiers & modifierMask)),
                    "expected=" + modifiers + " actual=" + onEdt(() -> pressModifiers[0]));
            final boolean custom = target != canvas;
            if (custom) check(name + ".noWriteOnPress", before.equals(onEdt(this::selectedPoints)), before.toString());
            for (int i = 1; i < path.size(); i++) {
                final Point from = path.get(i - 1), to = path.get(i);
                final int steps = Math.max(1, (int) Math.ceil(from.distance(to) / 4));
                for (int step = 1; step <= steps; step++) {
                    robot.mouseMove(
                            origin.x + (int) Math.round(from.x + (to.x - from.x) * (double) step / steps),
                            origin.y + (int) Math.round(from.y + (to.y - from.y) * (double) step / steps));
                    robot.delay(35);
                }
            }
            robot.waitForIdle();
            if (custom) {
                check(name + ".noWriteDuringDrag", before.equals(onEdt(this::selectedPoints)), before.toString());
                check(
                        name + ".noHistoryDuringDrag",
                        sameHistory(historyBefore, history()),
                        history().toString());
            }
            if (cancel != null) cancel.run();
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            pressed = false;
            robot.waitForIdle();
            report.add("modeling." + name + ".delivery=java.awt.Robot");
            report.add("modeling." + name + ".receipts=" + Arrays.toString(counts));
            check(
                    name + ".pressAndDragDelivered",
                    onEdt(() -> counts[0] == 1 && (path.size() == 1 || counts[1] > 0)),
                    Arrays.toString(counts));
            if (cancel == null) check(name + ".releaseDelivered", onEdt(() -> counts[2] == 1), Arrays.toString(counts));
            return onEdt(() -> List.copyOf(delivered));
        } finally {
            if (pressed) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_CONTROL);
            if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) robot.keyRelease(KeyEvent.VK_SHIFT);
            onEdt(() -> {
                target.removeMouseListener(receipt);
                target.removeMouseMotionListener(receipt);
                return null;
            });
        }
    }

    private Point nativeMousePoint() throws Exception {
        return onEdt(() -> {
            final Object point = call(call(view, "getLastMouseEvent"), "K");
            return new Point(Math.round(number(call(point, "getX"))), Math.round(number(call(point, "getY"))));
        });
    }

    private Point nativeRobotOrigin(Robot robot, Point swingOrigin) throws Exception {
        final Set<String> selectionBefore = onEdt(this::selectedPoints);
        final List<String> objectsBefore = onEdt(this::objectIds);
        final HistorySnapshot historyBefore = history();
        final java.awt.Dimension size = onEdt(canvas::getSize);
        final Point sample = new Point(size.width / 3, size.height / 3);
        robot.mouseMove(swingOrigin.x + sample.x, swingOrigin.y + sample.y);
        robot.delay(100);
        robot.waitForIdle();
        final Point received = nativeMousePoint();
        final Point origin = new Point(swingOrigin.x + sample.x - received.x, swingOrigin.y + sample.y - received.y);
        report.add("modeling.nativeBaseline.nativeInputOffset="
                + new Point(origin.x - swingOrigin.x, origin.y - swingOrigin.y));
        // Calibrate against the host's actual camera-input point, independently of Swing receipts.
        // Some native forwarding paths publish both translated and unmodified Swing drag events.
        for (Point expected : List.of(sample, new Point(2 * size.width / 3, 2 * size.height / 3))) {
            robot.mouseMove(origin.x + expected.x, origin.y + expected.y);
            robot.delay(100);
            robot.waitForIdle();
            final Point actual = nativeMousePoint();
            check(
                    "nativeBaseline.nativeInputCalibrated",
                    expected.equals(actual),
                    "expected=" + expected + " actual=" + actual);
        }
        check(
                "nativeBaseline.calibrationPreservesSelection",
                selectionBefore.equals(onEdt(this::selectedPoints)) && objectsBefore.equals(onEdt(this::objectIds)),
                onEdt(this::selectedPoints).toString());
        check(
                "nativeBaseline.calibrationPreservesHistory",
                sameHistory(historyBefore, history()),
                history().toString());
        return origin;
    }

    private void proveNavigationAndCleanup() throws Exception {
        activate();
        JComponent overlay = awaitOverlay(1);
        final List<NativePoint> before = onEdt(this::nativeCandidates);
        final Set<String> selectedBefore = onEdt(this::selectedPoints);
        final HistorySnapshot historyBefore = history();
        final Robot robot = new Robot();
        final Point origin = harness.awaitRobotWindowOrigin(overlay);
        final int[] keyBefore = onEdt(() -> navigationReceipts.clone());
        final int[] nativeReceipts = new int[3];
        final MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                nativeReceipts[0]++;
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                nativeReceipts[1]++;
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                nativeReceipts[2]++;
            }
        };
        onEdt(() -> {
            canvas.addMouseListener(listener);
            canvas.addMouseMotionListener(listener);
            return null;
        });
        robot.mouseMove(origin.x + canvas.getWidth() / 2, origin.y + canvas.getHeight() / 2);
        robot.keyPress(KeyEvent.VK_SPACE);
        try {
            robot.waitForIdle();
            check("navigationSpacePressed", onEdt(() -> navigationReceipts[0] > keyBefore[0]), "native key receipt");
            check(
                    "navigationInputNative",
                    !onEdt(() -> overlay.contains(canvas.getWidth() / 2, canvas.getHeight() / 2)),
                    "Space press bypasses brush overlay");
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            try {
                robot.mouseMove(origin.x + canvas.getWidth() / 2 + 60, origin.y + canvas.getHeight() / 2 + 30);
                robot.delay(200);
            } finally {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            }
        } finally {
            robot.keyRelease(KeyEvent.VK_SPACE);
            robot.waitForIdle();
            onEdt(() -> {
                canvas.removeMouseListener(listener);
                canvas.removeMouseMotionListener(listener);
                return null;
            });
        }
        robot.waitForIdle();
        check("navigationSpaceReleased", onEdt(() -> navigationReceipts[1] > keyBefore[1]), "native key receipt");
        check(
                "navigationMouseNative",
                onEdt(() -> nativeReceipts[0] == 1 && nativeReceipts[1] > 0 && nativeReceipts[2] == 1),
                Arrays.toString(nativeReceipts));
        check(
                "navigationSelectionUnchanged",
                selectedBefore.equals(onEdt(this::selectedPoints)),
                selectedBefore.toString());
        check("navigationHistoryUnchanged", sameHistory(historyBefore, history()), "navigation is selection-free");
        final List<NativePoint> panned = onEdt(this::nativeCandidates);
        check("navigationPan", !before.equals(panned), "native projected points changed");
        check(
                "navigationBrushRetained",
                onEdt(() -> brushButton.isSelected() && overlay.getParent() == canvas),
                "Space release must not toggle the focused toolbar button");
        assertExclusiveBrush();
        stroke("nonzeroPan", overlay, panned, targetPath(panned), 0);
        robotClick(brushButton, "sameButtonOff");
        awaitOverlay(0);
        for (String nativeTool : List.of("toolGroup_arrow", "toolGroup_lasso", "toolGroup_brushSelection")) {
            activate();
            switchNativeTool(
                    nativeTool,
                    switch (nativeTool) {
                        case "toolGroup_arrow" -> "ToolMode_Arrow";
                        case "toolGroup_lasso" -> "ToolMode_Lasso";
                        default -> "ToolMode_BrushSelection";
                    });
            awaitOverlay(0);
            check("switch." + nativeTool, !onEdt(brushButton::isSelected), "overlay=0");
        }
    }

    private void proveMeshEntryCleanup(Object mesh) throws Exception {
        setupObjects(List.of(mesh));
        awaitOverlay(0);
        final List<String> dirtyBefore = onEdt(this::dirty);
        final String geometryBefore = onEdt(this::authoredGeometry);
        final HistorySnapshot historyBefore = history();
        // Establish native enter/exit behavior without a custom activation. No dirty flag is reset.
        execute(EditorCommand.START_OR_END_MESH_EDITOR);
        execute(EditorCommand.START_OR_END_MESH_EDITOR);
        final List<String> nativeDirtyAfter = onEdt(this::dirty);
        final HistorySnapshot nativeHistoryAfter = history();
        report.add("modeling.nativeMeshBaseline.dirtyBefore=" + dirtyBefore);
        report.add("modeling.nativeMeshBaseline.dirtyAfter=" + nativeDirtyAfter);
        check(
                "nativeMeshBaselineGeometry",
                geometryBefore.equals(onEdt(this::authoredGeometry)),
                "native mesh round trip");
        final int delta = nativeHistoryAfter.position() - historyBefore.position();
        report.add("modeling.nativeMeshBaseline.historyDelta=" + delta);
        report.add("modeling.nativeMeshBaseline.historyAfter=" + nativeHistoryAfter);
        // Native mesh exit can clear the ordinary history and mark the document modified.
        // Brush dirty preservation was checked before this control. Do not invent an Undo
        // restoration contract for mesh exit or reset the native dirty flag here.
        activate();
        check("meshTransitionActivationHistoryUnchanged", nativeHistoryAfter.equals(history()), "native history");
        execute(EditorCommand.START_OR_END_MESH_EDITOR);
        awaitOverlay(0);
        check(
                "meshEntry",
                !context.meshEdit().snapshot().points().isEmpty(),
                context.meshEdit().snapshot().toString());
        check("ordinaryDisabledInMesh", !onEdt(brushButton::isEnabled), brushButton.toString());
        execute(EditorCommand.START_OR_END_MESH_EDITOR);
        check(
                "meshExit",
                context.meshEdit().snapshot().points().isEmpty(),
                context.meshEdit().snapshot().toString());
        check(
                "meshExitDirtyMatchesNative",
                nativeDirtyAfter.equals(onEdt(this::dirty)),
                onEdt(this::dirty).toString());
        final HistorySnapshot historyAfter = history();
        final var nativeMeshEntry = nativeHistoryAfter.entries().get(nativeHistoryAfter.position() - 1);
        final var customMeshEntry = historyAfter.entries().get(historyAfter.position() - 1);
        check(
                "meshExitHistoryMatchesNative",
                historyAfter.position() == nativeHistoryAfter.position() + 1
                        && historyAfter.entries().size()
                                == nativeHistoryAfter.entries().size() + 1
                        && historyAfter
                                .entries()
                                .subList(0, nativeHistoryAfter.entries().size())
                                .equals(nativeHistoryAfter.entries())
                        && nativeMeshEntry.label().equals(customMeshEntry.label())
                        && nativeMeshEntry.significant() == customMeshEntry.significant()
                        && nativeMeshEntry.action().equals(customMeshEntry.action())
                        && nativeMeshEntry.detail().equals(customMeshEntry.detail())
                        && nativeHistoryAfter.canUndo() == historyAfter.canUndo()
                        && nativeHistoryAfter.canRedo() == historyAfter.canRedo(),
                historyAfter.toString());
        check(
                "meshExitGeometryMatchesNative",
                geometryBefore.equals(onEdt(this::authoredGeometry)),
                "authored point arrays after custom-active native mesh round trip");
    }

    private void activate() throws Exception {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (!onEdt(brushButton::isEnabled) && System.nanoTime() < deadline) Thread.sleep(50);
        check("buttonEnabled", onEdt(brushButton::isEnabled), brushButton.toString());
        robotClick(brushButton, "customActivate");
        awaitOverlay(1);
        assertExclusiveBrush();
    }

    private void assertExclusiveBrush() throws Exception {
        onEdt(() -> {
            check("buttonSelected", brushButton.isSelected(), brushButton.toString());
            final AbstractButton modelingArrow = nativeButton("toolGroup_arrow");
            check("nativeModelingArrowCleared", !modelingArrow.isSelected(), modelingArrow.toString());
            final Map<?, ?> buttons = (Map<?, ?>) call(call(app, "getMainFrameCtrl"), "getToolGroupToButtonMap");
            for (Object button : buttons.values()) {
                check(
                        "nativeHighlightCleared",
                        !((AbstractButton) call(button, "getJAbstractButton")).isSelected(),
                        button.toString());
            }
            return null;
        });
    }

    private AbstractButton awaitBrushButton() throws Exception {
        final long deadline = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < deadline) {
            final List<AbstractButton> found = onEdt(() -> showingComponents().stream()
                    .filter(AbstractButton.class::isInstance)
                    .map(AbstractButton.class::cast)
                    .filter(button -> button.getName() != null
                            && button.getName().startsWith("turboism:modeling:")
                            && button.getName().contains("selection-brush"))
                    .toList());
            if (found.size() == 1) return found.get(0);
            if (found.size() > 1) throw new IllegalStateException("duplicate ordinary brush buttons");
            Thread.sleep(100);
        }
        throw new IllegalStateException("ordinary brush toolbar contribution is absent");
    }

    private void assertToolbarPosition() throws Exception {
        final AbstractButton anchor = nativeButton("toolGroup_brushSelection");
        check("toolbarSameParent", anchor.getParent() == brushButton.getParent(), brushButton.toString());
        final List<Component> peers = List.of(anchor.getParent().getComponents());
        final int at = peers.indexOf(anchor);
        check("toolbarOrder", peers.indexOf(brushButton) == at + 1, peers.toString());
        check(
                "toolbarBeforeDivider",
                at + 2 < peers.size() && peers.get(at + 2) instanceof JSeparator,
                peers.toString());
    }

    private AbstractButton nativeButton(String field) throws Exception {
        final Object frameView = call(call(app, "getMainFrameCtrl"), "getViewCtrl");
        return (AbstractButton) call(frameView.getClass().getField(field).get(frameView), "getJAbstractButton");
    }

    private String toolMode() throws Exception {
        return call(app, "getToolMode").getClass().getSimpleName();
    }

    private void switchNativeTool(String field, String expectedMode) throws Exception {
        final AbstractButton button = onEdt(() -> nativeButton(field));
        report.add("modeling." + field + ".modeBefore=" + onEdt(this::toolMode));
        robotClick(button, field);
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (!expectedMode.equals(onEdt(this::toolMode)) && System.nanoTime() < deadline) Thread.sleep(50);
        check(
                field + ".actualToolMode",
                expectedMode.equals(onEdt(this::toolMode)),
                "expected=" + expectedMode + " actual=" + onEdt(this::toolMode));
        check(field + ".selected", onEdt(button::isSelected), button.toString());
    }

    private void robotClick(AbstractButton button, String name) throws Exception {
        check(name + ".enabled", onEdt(button::isEnabled), button.toString());
        // Calibrate on the large task canvas. A tiny toolbar target may receive only
        // enter/exit events during calibration, so derive its offset in the same window.
        final Point origin = harness.awaitRobotWindowOrigin(canvas);
        final Point center = onEdt(() ->
                SwingUtilities.convertPoint(button, new Point(button.getWidth() / 2, button.getHeight() / 2), canvas));
        final int[] receipts = new int[3];
        final MouseAdapter listener = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                receipts[0]++;
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                receipts[1]++;
            }

            @Override
            public void mouseClicked(MouseEvent event) {
                receipts[2]++;
            }
        };
        onEdt(() -> {
            button.addMouseListener(listener);
            return null;
        });
        final Robot robot = new Robot();
        boolean pressed = false;
        try {
            robot.mouseMove(origin.x + center.x, origin.y + center.y);
            robot.delay(100);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            pressed = true;
            robot.delay(100);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            pressed = false;
            robot.waitForIdle();
            check(
                    name + ".robotClickDelivered",
                    onEdt(() -> Arrays.equals(receipts, new int[] {1, 1, 1})),
                    Arrays.toString(receipts));
            report.add("modeling." + name + ".delivery=java.awt.Robot");
        } finally {
            if (pressed) robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            onEdt(() -> {
                button.removeMouseListener(listener);
                return null;
            });
        }
    }

    private void robotEscape(String name) throws Exception {
        report.add("modeling." + name + ".beforeKey=" + onEdt(this::selectedPoints));
        final int[] before = onEdt(() -> escapeReceipts.clone());
        final Robot robot = new Robot();
        boolean pressed = false;
        try {
            robot.keyPress(KeyEvent.VK_ESCAPE);
            pressed = true;
            robot.delay(100);
            robot.waitForIdle();
            report.add("modeling." + name + ".afterKeyPress=" + onEdt(this::selectedPoints));
            report.add("modeling." + name + ".afterKeyPressObjects=" + onEdt(this::objectIds));
            robot.keyRelease(KeyEvent.VK_ESCAPE);
            pressed = false;
            robot.waitForIdle();
            report.add("modeling." + name + ".beforeMouseRelease=" + onEdt(this::selectedPoints));
            final int[] receipts =
                    onEdt(() -> new int[] {escapeReceipts[0] - before[0], escapeReceipts[1] - before[1]});
            check(name + ".keysDelivered", receipts[0] > 0 && receipts[1] > 0, Arrays.toString(receipts));
            report.add("modeling." + name + ".delivery=java.awt.Robot");
        } finally {
            if (pressed) robot.keyRelease(KeyEvent.VK_ESCAPE);
        }
    }

    private List<NativePoint> nativeCandidates() throws Exception {
        final List<NativePoint> result = new ArrayList<>();
        for (Object source : list(call(selector, "getSelectedObjects"))) result.addAll(candidatesFor(source));
        return List.copyOf(result);
    }

    private List<NativePoint> candidatesFor(Object source) throws Exception {
        if (!Boolean.TRUE.equals(call(source, "isEditableInHierarchy"))) return List.of();
        final Object object = call(model, "getObject", call(source, "getId"));
        if (object == null || !Boolean.TRUE.equals(call(view, "isEditableInView", object))) return List.of();
        final List<NativePoint> result = new ArrayList<>();
        for (Object ref : list(call(object, "getAllPointRefEx"))) {
            if (!supported(ref)) continue;
            final Object position = call(camera, "documentToComponent", call(ref, "e"));
            result.add(new NativePoint(key(ref), number(call(position, "getX")), number(call(position, "getY"))));
        }
        return List.copyOf(result);
    }

    private Set<String> selectedPoints() throws Exception {
        final Set<String> result = new LinkedHashSet<>();
        for (Object source : list(call(selector, "getSelectedObjects"))) {
            final Object points = call(call(source, "getSelection"), "getPointSelector");
            for (Object ref : list(call(points, "getSelectedPoints"))) if (supported(ref)) result.add(key(ref));
        }
        return Set.copyOf(result);
    }

    private void assertBoundingBox(String name, Set<String> selected) throws Exception {
        final List<NativePoint> candidates = onEdt(this::nativeCandidates);
        final List<NativePoint> targets = candidates.stream()
                .filter(point -> selected.size() <= 1 || selected.contains(point.key()))
                .toList();
        final BoundingBoxState actual = onEdt(() -> {
            final Object box = call(selector, "getBoundingBox");
            // Use the native lazy reader; never invalidate or rebuild the cache in this oracle.
            final Object bounds = call(box, "getBoundingRect", call(view, "getLastActionPack"));
            final Set<String> points = new LinkedHashSet<>();
            for (Object weighted : list(call(box, "getSelectedPoints"))) {
                final Object ref = call(weighted, "a");
                if (supported(ref)) points.add(key(ref));
            }
            final List<NativePoint> corners = new ArrayList<>();
            for (Object corner : list(call(box, "getTransformedCornerPoints$cubism"))) {
                final Object position = call(camera, "documentToComponent", corner);
                corners.add(new NativePoint("corner", number(call(position, "getX")), number(call(position, "getY"))));
            }
            return new BoundingBoxState(
                    Boolean.TRUE.equals(call(box, "isAvailable")),
                    bounds != null,
                    Set.copyOf(points),
                    List.copyOf(corners));
        });
        report.add("modeling." + name + ".boundingBox=" + actual);
        report.add("modeling." + name + ".boundingBoxTargets=" + targets);
        // CBoundingBox_forModel uses point bounds only for more than one selected point.
        // Zero/one selected point falls back to whole-object bounds; a rectangle still
        // requires at least two target points after that native fallback.
        if (targets.size() < 2) {
            check(
                    name + ".boundingBoxNoHandlesBelowTwoTargets",
                    !actual.hasBounds() && actual.points().isEmpty(),
                    actual.toString());
            return;
        }
        final Set<String> expected = new LinkedHashSet<>();
        targets.forEach(point -> expected.add(point.key()));
        check(
                name + ".boundingBoxTargets",
                actual.points().equals(expected),
                "expected=" + expected + " actual=" + actual.points());
        check(
                name + ".boundingBoxAvailable",
                actual.available() && actual.hasBounds() && actual.corners().size() == 4,
                actual.toString());
        final double[] bounds = displayBounds(actual.corners());
        final double[] expectedBounds = displayBounds(targets);
        for (int index = 0; index < bounds.length; index++) {
            check(
                    name + ".boundingBoxExtent" + index,
                    Math.abs(bounds[index] - expectedBounds[index]) < 1,
                    "expected=" + Arrays.toString(expectedBounds) + " actual=" + Arrays.toString(bounds));
        }
    }

    private static double[] displayBounds(List<NativePoint> points) {
        return new double[] {
            points.stream().mapToDouble(NativePoint::x).min().orElseThrow(),
            points.stream().mapToDouble(NativePoint::y).min().orElseThrow(),
            points.stream().mapToDouble(NativePoint::x).max().orElseThrow(),
            points.stream().mapToDouble(NativePoint::y).max().orElseThrow()
        };
    }

    private record BoundingBoxState(
            boolean available, boolean hasBounds, Set<String> points, List<NativePoint> corners) {}

    private List<String> objectIds() throws Exception {
        final List<String> result = new ArrayList<>();
        for (Object source : list(call(selector, "getSelectedObjects"))) result.add(id(source));
        return result.stream().sorted().toList();
    }

    private String authoredGeometry() throws Exception {
        final Map<String, List<String>> forms = new LinkedHashMap<>();
        for (Object source : list(call(call(document, "getModelSource"), "getAllObjects"))) {
            final String type = source.getClass().getSimpleName();
            if (!type.equals("CArtMeshSource") && !type.equals("CWarpDeformerSource")) continue;
            final List<String> values = new ArrayList<>();
            for (Object form : list(call(source, "getKeyforms"))) {
                values.add(Arrays.toString((float[]) call(form, "getPositions")));
            }
            if (type.equals("CWarpDeformerSource")) values.add(call(source, "getCol") + ":" + call(source, "getRow"));
            forms.put(id(source), List.copyOf(values));
        }
        if (forms.isEmpty()) throw new IllegalStateException("no authored point arrays available");
        return forms.toString();
    }

    private List<String> parameters() {
        return context.cubism().model().active().parameters().all().stream()
                .map(parameter -> parameter.id().value() + ":" + parameter.getValue())
                .sorted()
                .toList();
    }

    private List<String> dirty() {
        final List<String> result =
                context
                        .services()
                        .require(dev.turboism.sdk.cubism.backup.EditorAutoBackupService.class)
                        .statuses()
                        .stream()
                        .map(status -> status.documentName() + ":" + status.modifiedAfterSaving())
                        .sorted()
                        .toList();
        if (result.isEmpty()) throw new IllegalStateException("native document dirty-state reader unavailable");
        return result;
    }

    private HistorySnapshot history() throws Exception {
        final HistorySnapshot snapshot = onEdt(() -> context.cubism().history().snapshot());
        check(
                "historyAvailable",
                snapshot.availability() == HistorySnapshot.Availability.AVAILABLE,
                snapshot.toString());
        return snapshot;
    }

    private void execute(EditorCommand command) throws Exception {
        report.add("modeling.command." + command.name() + ".dirtyBefore=" + onEdt(this::dirty));
        final var result = onEdt(() -> context.editorCommands().execute(command));
        check("command." + command.name(), result.executed(), result.toString());
        report.add("modeling.command." + command.name() + ".dirtyAfter=" + onEdt(this::dirty));
    }

    private List<Point> targetPath(List<NativePoint> points) {
        final NativePoint target =
                points.stream().filter(this::inside).findFirst().orElseThrow();
        final Point start = target.rounded();
        return List.of(start, new Point(start.x + 10, start.y + 3));
    }

    private List<Point> separatedPath(List<NativePoint> points, Point first) {
        final NativePoint target = points.stream()
                .filter(this::inside)
                .filter(point -> first.distance(point.x, point.y) > 2 * RADIUS + 12)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("fixture lacks separated visible points at radius 32"));
        final Point start = target.rounded();
        return List.of(start, new Point(start.x + 10, start.y + 3));
    }

    private Point emptyPoint(List<NativePoint> points) {
        for (int y = 60; y < canvas.getHeight() - 60; y += 40) {
            for (int x = 60; x < canvas.getWidth() - 60; x += 40) {
                final Point trial = new Point(x, y);
                if (hits(points, List.of(trial, new Point(x + 2, y)), RADIUS).isEmpty()) return trial;
            }
        }
        throw new IllegalStateException("fixture lacks an empty brush footprint");
    }

    private boolean inside(NativePoint point) {
        return point.x > 60 && point.y > 60 && point.x < canvas.getWidth() - 60 && point.y < canvas.getHeight() - 60;
    }

    static Set<String> hits(List<NativePoint> points, List<Point> path, int radius) {
        final Set<String> hits = new LinkedHashSet<>();
        for (NativePoint point : points) {
            for (int i = 0; i < path.size(); i++) {
                final Point from = path.get(Math.max(0, i - 1)), to = path.get(i);
                final double dx = to.x - from.x, dy = to.y - from.y;
                final double length = dx * dx + dy * dy;
                final double t = length == 0
                        ? 0
                        : Math.max(0, Math.min(1, ((point.x - from.x) * dx + (point.y - from.y) * dy) / length));
                if (Math.hypot(point.x - from.x - t * dx, point.y - from.y - t * dy) <= radius) {
                    hits.add(point.key);
                    break;
                }
            }
        }
        return Set.copyOf(hits);
    }

    static Set<String> lassoHits(List<NativePoint> points, List<Point> path) {
        final java.awt.Polygon polygon = new java.awt.Polygon();
        path.forEach(point -> polygon.addPoint(point.x, point.y));
        final Set<String> result = new LinkedHashSet<>();
        for (NativePoint point : points) {
            if (polygon.contains(point.x, point.y)) result.add(point.key);
        }
        return Set.copyOf(result);
    }

    static Set<String> apply(Set<String> before, Set<String> hits, int modifiers) {
        if (hits.isEmpty()) return before;
        final Set<String> result = new LinkedHashSet<>(before);
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) result.removeAll(hits);
        else if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) result.addAll(hits);
        else {
            result.clear();
            result.addAll(hits);
        }
        return Set.copyOf(result);
    }

    private static boolean sameHistory(HistorySnapshot before, HistorySnapshot after) {
        return before.position() == after.position()
                && before.entries().equals(after.entries())
                && before.canUndo() == after.canUndo()
                && before.canRedo() == after.canRedo();
    }

    private static boolean supported(Object ref) {
        return pointFamily(ref) != null;
    }

    private static String pointFamily(Object ref) {
        // Native canvas refs subclass the local point types and have version-specific names.
        for (Class<?> type = ref.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().equals("com.live2d.cubism.doc.model.drawable.artMesh.MeshPointRef"))
                return "MeshPointRef";
            if (type.getName().equals("com.live2d.cubism.doc.model.deformer.warp.WarpPointRef")) return "WarpPointRef";
        }
        return null;
    }

    private static String key(Object ref) throws Exception {
        return id(call(ref, "getSource")) + ":" + pointFamily(ref) + ":" + call(ref, "a");
    }

    private static String id(Object source) throws Exception {
        return call(call(source, "getId"), "getIdString").toString();
    }

    private static float number(Object value) {
        return ((Number) value).floatValue();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalStateException("expected native list: " + value);
        return (List<Object>) list;
    }

    private static Object call(Object target, String name, Object... arguments) throws Exception {
        final List<Method> methods = Arrays.stream(target.getClass().getMethods())
                .filter(method -> method.getName().equals(name) && method.getParameterCount() == arguments.length)
                .filter(method -> compatible(method.getParameterTypes(), arguments))
                .filter(method -> !method.isBridge())
                .toList();
        if (methods.size() != 1)
            throw new IllegalStateException("ambiguous/unavailable native method " + name + " on " + target.getClass());
        return methods.get(0).invoke(target, arguments);
    }

    private static boolean compatible(Class<?>[] parameters, Object[] arguments) {
        for (int i = 0; i < parameters.length; i++) {
            final Class<?> type = parameters[i];
            final Object value = arguments[i];
            if (value == null) {
                if (type.isPrimitive()) return false;
            } else if (type.isPrimitive()) {
                if (!((type == boolean.class && value instanceof Boolean)
                        || (type == int.class && value instanceof Integer)
                        || (type == float.class && value instanceof Float))) return false;
            } else if (!type.isInstance(value)) return false;
        }
        return true;
    }

    private void check(String name, boolean success, String detail) {
        require(report, "modeling." + name, success, detail);
    }

    record NativePoint(String key, float x, float y) {
        Point rounded() {
            return new Point(Math.round(x), Math.round(y));
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        void run() throws Exception;
    }
}
