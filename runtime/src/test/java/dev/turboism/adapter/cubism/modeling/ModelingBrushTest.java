package dev.turboism.adapter.cubism.modeling;

import static dev.turboism.adapter.cubism.modeling.ModelingSelectionSelectorContract.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.modeling.ModelingBrush;
import dev.turboism.sdk.cubism.modeling.ModelingTool;
import dev.turboism.sdk.cubism.modeling.ModelingToolContext;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodType;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Exercises the verified native adapter with independently stored point selectors and current forms. */
class ModelingBrushTest {
    @Test
    void currentCanvasPointsIncludeParentDeformationAndCameraAndKeepOwners() {
        Fixture f = new Fixture();
        var snapshot = f.capture();
        assertEquals(
                List.of(new Point2(60, 90), new Point2(140, 90), new Point2(100, 150), new Point2(180, 210)),
                snapshot.positions());
        assertEquals(2, snapshot.owners().size());
        assertNotEquals(
                snapshot.candidates().get(0).key(), snapshot.candidates().get(2).key());
        f.warp.editable = false;
        assertEquals(2, f.capture().candidates().size());
        f.mesh.visible = false;
        assertTrue(f.capture().candidates().isEmpty());
    }

    @Test
    void replaceAddRemoveRetainObjectsForeignPointTypesAndOnlyOneNativeHistoryStep() {
        Fixture f = new Fixture();
        Object foreign = new Object();
        f.mesh.points.add(f.mesh.refs.get(1), .25f, true);
        f.mesh.points.values.put(foreign, .4f);
        var sources = List.copyOf(f.mode.selector.selected);
        f.commit(List.of(0, 2), SelectionMode.REPLACE);
        assertEquals(Set.of(f.mesh.refs.get(0), foreign), f.mesh.points.values.keySet());
        assertEquals(Set.of(f.warp.refs.get(0)), f.warp.points.values.keySet());
        assertEquals(sources, f.mode.selector.selected);
        assertEquals(1, f.mode.begins);
        assertEquals(1, f.mode.undo.captures);
        assertEquals(1, f.mode.commits);
        f.commit(List.of(1), SelectionMode.ADD);
        f.commit(List.of(0), SelectionMode.REMOVE);
        assertEquals(Set.of(f.mesh.refs.get(1), foreign), f.mesh.points.values.keySet());
        assertEquals(Set.of(f.warp.refs.get(0)), f.warp.points.values.keySet());
        assertEquals(.4f, f.mesh.points.values.get(foreign));
        assertEquals(3, f.mode.begins);
        assertEquals(3, f.pack.updates);
        assertFalse(f.mode.editing);
        assertFalse(f.dirty);
        assertEquals(new Vector(40, 60), f.mesh.refs.get(0).canvas());
    }

    @Test
    void emptyHitsAndEqualSelectionAreNoopsAndStaleSnapshotNeverWrites() {
        Fixture f = new Fixture();
        f.commit(List.of(), SelectionMode.REPLACE);
        assertEquals(0, f.mode.begins);
        f.commit(List.of(0), SelectionMode.ADD);
        f.commit(List.of(0), SelectionMode.ADD);
        assertEquals(1, f.mode.begins);
        var before = f.capture();
        f.camera.zoom = 3;
        assertThrows(
                IllegalStateException.class, () -> f.selection().commit(before, List.of(1), SelectionMode.REPLACE));
        assertEquals(1, f.mode.begins);
        f.camera.zoom = 2;
        var topology = f.capture();
        f.mesh.refs.get(0).uid++;
        assertThrows(
                IllegalStateException.class, () -> f.selection().commit(topology, List.of(1), SelectionMode.REPLACE));
        assertEquals(1, f.mode.begins);
        f.mesh.refs.get(0).uid--;
        var selection = f.selection();
        var currentSnapshot = f.capture();
        f.mode.editing = true;
        assertThrows(
                IllegalStateException.class,
                () -> selection.commit(currentSnapshot, List.of(1), SelectionMode.REPLACE));
        assertEquals(1, f.mode.begins, "must not borrow a foreign native edit");
    }

    @Test
    void registryPermissionsAndOldGenerationCleanupCannotRevokeReplacement() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            ModelingToolCoordinator coordinator = new ModelingToolCoordinator();
            var lifecycle = new dev.turboism.ui.host.RuntimeEditorUiHostLifecycle();
            long generation = lifecycle.connecting().generation();
            lifecycle.ready(generation, Set.of(dev.turboism.ui.host.EditorUiFamily.MAIN_TOOLBAR));
            var authority = new dev.turboism.ui.contribution.EditorUiContributionAuthority(lifecycle);
            var placement = dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Placement.after(
                    dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL);
            var missing = new RuntimeModelingToolRegistry(
                    "plugin", 0, PermissionChecker.allowAll(), false, coordinator, authority);
            var denied = new RuntimeModelingToolRegistry(
                    "plugin",
                    0,
                    (permission, operation) -> {
                        throw new SecurityException(permission);
                    },
                    true,
                    coordinator,
                    authority);
            var old = new RuntimeModelingToolRegistry(
                    "plugin", 1, PermissionChecker.allowAll(), true, coordinator, authority);
            var replacement = new RuntimeModelingToolRegistry(
                    "plugin", 2, PermissionChecker.allowAll(), true, coordinator, authority);
            try {
                assertThrows(UnsupportedOperationException.class, () -> missing.register(new Tool(), placement));
                assertThrows(SecurityException.class, () -> denied.register(new Tool(), placement));
                assertTrue(authority
                        .contributions(dev.turboism.ui.host.EditorUiFamily.MAIN_TOOLBAR)
                        .isEmpty());
                assertFalse(old.isAvailable());
                coordinator.connect(f.resolver, 7);
                coordinator.lifecycleReady(7, true);
                var stale = old.register(new Tool(), placement);
                replacement.register(new Tool(), placement);
                assertThrows(IllegalStateException.class, () -> replacement.register(new Tool(), placement));
                coordinator.toggle("plugin", 2, "brush");
                stale.close();
                old.close();
                stale.close();
                assertTrue(coordinator.isActive("plugin", 2, "brush"));
                assertThrows(NullPointerException.class, () -> coordinator.toggle("plugin", 1, "brush"));
                assertTrue(coordinator.isActive("plugin", 2, "brush"));
                assertEquals(
                        1,
                        authority
                                .contributions(dev.turboism.ui.host.EditorUiFamily.MAIN_TOOLBAR)
                                .size());
                replacement.close();
                replacement.close();
                assertEquals(0, f.pack.component.getComponentCount());
                assertTrue(authority
                        .contributions(dev.turboism.ui.host.EditorUiFamily.MAIN_TOOLBAR)
                        .isEmpty());
                assertThrows(IllegalStateException.class, () -> replacement.register(new Tool(), placement));
            } finally {
                old.close();
                replacement.close();
                missing.close();
                denied.close();
                coordinator.close();
            }
        });
    }

    @Test
    void failedAndReentrantActivationReleaseOnlyTheirOwnOverlay() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            ModelingToolCoordinator coordinator = new ModelingToolCoordinator();
            Tool failing = new Tool() {
                @Override
                public void activate(ModelingToolContext context) {
                    super.activate(context);
                    throw new IllegalStateException("activation rejected");
                }
            };
            var registration = coordinator.register("plugin", 1, failing, PermissionChecker.allowAll());
            try {
                coordinator.connect(f.resolver, 7);
                coordinator.lifecycleReady(7, true);
                assertThrows(IllegalStateException.class, () -> coordinator.toggle("plugin", 1, "brush"));
                assertEquals(0, f.pack.component.getComponentCount());
                assertFalse(coordinator.isActive("plugin", 1, "brush"));
                registration.close();
                Tool reentrant = new Tool() {
                    @Override
                    public void activate(ModelingToolContext context) {
                        super.activate(context);
                        coordinator.deactivate("plugin", 2, "brush");
                    }
                };
                var owned = coordinator.register("plugin", 2, reentrant, PermissionChecker.allowAll());
                try {
                    coordinator.toggle("plugin", 2, "brush");
                    assertEquals(0, f.pack.component.getComponentCount());
                    assertThrows(IllegalStateException.class, () -> reentrant.brush.setRadiusPixels(8));
                } finally {
                    owned.close();
                }
            } finally {
                registration.close();
                coordinator.close();
            }
        });
    }

    @Test
    void failedAdditionRestoresExactWeightsAndNeverWritesReplacementDocument() {
        Fixture f = new Fixture();
        f.mesh.points.add(f.mesh.refs.get(1), .25f, true);
        f.warp.points.rejectNext = true;
        assertThrows(RuntimeException.class, () -> f.commit(List.of(0, 2), SelectionMode.REPLACE));
        assertEquals(Map.of(f.mesh.refs.get(1), .25f), f.mesh.points.values);
        assertTrue(f.warp.points.values.isEmpty());
        assertEquals(1, f.mode.cancels);
        assertFalse(f.mode.editing);
        f.mode.onBegin = () -> f.app.document = new Document();
        assertThrows(RuntimeException.class, () -> f.commit(List.of(0), SelectionMode.REPLACE));
        assertEquals(Map.of(f.mesh.refs.get(1), .25f), f.mesh.points.values);
        assertEquals(1, f.mode.cancels, "replacement must never receive old selection or endEdit");
    }

    @Test
    void brushReleaseCommitsDisplayedOwnerAndLocksModifiersAndRadius() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            RuntimeModelingBrush brush = new RuntimeModelingBrush(
                    f.sessions,
                    f.identity(),
                    () -> true,
                    () -> {},
                    new Tool() {
                        @Override
                        public SelectionMode strokeSelectionMode(boolean shift, boolean control, boolean alt) {
                            return control ? SelectionMode.REMOVE : shift ? SelectionMode.ADD : SelectionMode.REPLACE;
                        }
                    },
                    PermissionChecker.allowAll());
            try {
                brush.install();
                brush.setRadiusPixels(8);
                JComponent overlay = (JComponent) f.pack.component.getComponent(0);
                event(overlay, MouseEvent.MOUSE_PRESSED, 60, 90, InputEvent.BUTTON1_DOWN_MASK);
                assertEquals(0, f.mode.begins);
                brush.setRadiusPixels(128);
                event(overlay, MouseEvent.MOUSE_RELEASED, 60, 90, InputEvent.CTRL_DOWN_MASK);
                assertEquals(Set.of(f.mesh.refs.get(0)), f.mesh.points.values.keySet());
                assertTrue(f.warp.points.values.isEmpty());
            } finally {
                brush.close();
            }
            assertEquals(0, f.pack.component.getComponentCount());
        });
    }

    @Test
    void releaseRefreshesCachedNativeBoundingBoxAroundSelectedPoints() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            final Bounds objectBounds = new Bounds(40, 60, 100, 120);
            assertEquals(objectBounds, f.mode.selector.box.bounds());
            final RuntimeModelingBrush brush = new RuntimeModelingBrush(
                    f.sessions, f.identity(), () -> true, () -> {}, new Tool(), PermissionChecker.allowAll());
            try {
                brush.install();
                brush.setRadiusPixels(8);
                final JComponent overlay = (JComponent) f.pack.component.getComponent(0);
                event(overlay, MouseEvent.MOUSE_PRESSED, 60, 90, InputEvent.BUTTON1_DOWN_MASK);
                event(overlay, MouseEvent.MOUSE_DRAGGED, 100, 150, InputEvent.BUTTON1_DOWN_MASK);
                assertEquals(
                        objectBounds, f.mode.selector.box.bounds(), "a stroke must not change the native box early");
                event(overlay, MouseEvent.MOUSE_RELEASED, 100, 150, 0);
                assertEquals(Set.of(f.mesh.refs.get(0)), f.mesh.points.values.keySet());
                assertEquals(Set.of(f.warp.refs.get(0)), f.warp.points.values.keySet());
                assertEquals(
                        new Bounds(40, 60, 60, 90),
                        f.mode.selector.box.bounds(),
                        "the next native render must bound the selected points, not the whole objects");
                assertEquals(1, f.mode.commits);
                assertFalse(f.dirty);
            } finally {
                brush.close();
            }
        });
    }

    @Test
    void changedFormDeletedOwnerAndReplacementDocumentCancelTheLockedStroke() throws Exception {
        final List<java.util.function.Consumer<Fixture>> changes = List.of(
                f -> f.mesh.refs.get(0).position = new Vector(180, 190),
                f -> f.view.model.objects.remove("mesh"),
                f -> f.mode.selector.selected.clear(),
                f -> f.warp.editable = false,
                f -> f.camera.zoom = .5f,
                f -> f.document.mode = new Mode(),
                f -> f.app.document = new Document());
        SwingUtilities.invokeAndWait(() -> {
            for (var change : changes) {
                final Fixture f = new Fixture();
                f.mesh.points.add(f.mesh.refs.get(1), 1f, true);
                final var before = Map.copyOf(f.mesh.points.values);
                final RuntimeModelingBrush brush = new RuntimeModelingBrush(
                        f.sessions, f.identity(), () -> true, () -> {}, new Tool(), PermissionChecker.allowAll());
                brush.install();
                final JComponent overlay = (JComponent) f.pack.component.getComponent(0);
                event(overlay, MouseEvent.MOUSE_PRESSED, 60, 90, InputEvent.BUTTON1_DOWN_MASK);
                change.accept(f);
                event(overlay, MouseEvent.MOUSE_DRAGGED, 62, 91, InputEvent.BUTTON1_DOWN_MASK);
                event(overlay, MouseEvent.MOUSE_RELEASED, 62, 91, 0);
                assertEquals(0, f.mode.begins, "stale stroke must never begin a selection transaction");
                assertEquals(before, f.mesh.points.values);
                assertTrue(f.warp.points.values.isEmpty());
                brush.close();
                assertEquals(0, f.pack.component.getComponentCount());
                assertEquals(0, overlay.getMouseListeners().length);
                assertEquals(0, overlay.getMouseMotionListeners().length);
                assertEquals(0, overlay.getMouseWheelListeners().length);
            }
        });
    }

    @Test
    void nativeToolModeAndViewNotificationsRevokeExactActivationAndCleanRepeatedSwitches() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Fixture f = new Fixture();
            ModelingToolCoordinator coordinator = new ModelingToolCoordinator();
            Tool tool = new Tool();
            var registration = coordinator.register("plugin", 1, tool, PermissionChecker.allowAll());
            try {
                coordinator.connect(f.resolver, 7);
                coordinator.lifecycleReady(7, true);
                assertTrue(coordinator.isEligible());
                for (int i = 0; i < 100; i++) {
                    coordinator.toggle("plugin", 1, "brush");
                    assertEquals(1, f.pack.component.getComponentCount());
                    assertTrue(coordinator.isActive("plugin", 1, "brush"));
                    coordinator.nativeToolActivated(f.app);
                    assertEquals(0, f.pack.component.getComponentCount());
                }
                coordinator.toggle("plugin", 1, "brush");
                ModelingBrush stale = tool.brush;
                coordinator.modeChanged(f.document, new Mode());
                assertThrows(IllegalStateException.class, () -> stale.setRadiusPixels(8));
                assertEquals(0, f.pack.component.getComponentCount());
                coordinator.toggle("plugin", 1, "brush");
                f.app.changeView(new View());
                assertEquals(0, f.pack.component.getComponentCount());
                assertFalse(coordinator.isEligible());
                coordinator.disconnect();
                assertTrue(f.app.listeners.isEmpty());
                coordinator.lifecycleReady(6, true);
                assertFalse(coordinator.isAvailable());
            } finally {
                registration.close();
                registration.close();
                coordinator.close();
            }
        });
    }

    @Test
    void lifecycleRevocationInterruptedWhileEdtWedgedStillRunsExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        ModelingToolCoordinator coordinator = new ModelingToolCoordinator();
        var registration = coordinator.register("plugin", 1, new Tool(), PermissionChecker.allowAll());
        final CountDownLatch release = new CountDownLatch(1);
        try {
            SwingUtilities.invokeAndWait(() -> {
                coordinator.connect(f.resolver, 7);
                coordinator.lifecycleReady(7, true);
                coordinator.toggle("plugin", 1, "brush");
            });
            assertTrue(coordinator.isActive("plugin", 1, "brush"));
            final CountDownLatch wedged = new CountDownLatch(1);
            SwingUtilities.invokeLater(() -> {
                wedged.countDown();
                await(release);
            });
            assertTrue(wedged.await(5, TimeUnit.SECONDS));
            final CountDownLatch callerDone = new CountDownLatch(1);
            final AtomicReference<Throwable> outcome = new AtomicReference<>();
            Thread caller = new Thread(
                    () -> {
                        try {
                            coordinator.lifecycleReady(7, false);
                        } catch (Throwable failure) {
                            outcome.set(failure);
                        } finally {
                            callerDone.countDown();
                        }
                    },
                    "modeling-lifecycle-revocation");
            caller.setDaemon(true);
            caller.start();
            caller.interrupt();
            assertTrue(callerDone.await(5, TimeUnit.SECONDS), "an interrupted revocation must not keep waiting");
            assertNull(outcome.get(), "an interrupted revocation defers the transition instead of failing");
            assertEquals(
                    1,
                    f.pack.component.getComponentCount(),
                    "the activation survives while the revocation is still queued");
            release.countDown();
            SwingUtilities.invokeAndWait(() -> {});
            assertEquals(
                    0,
                    f.pack.component.getComponentCount(),
                    "the deferred revocation must deactivate the tool once the EDT drains");
            assertFalse(coordinator.isAvailable(), "the deferred revocation must clear lifecycle readiness");
        } finally {
            release.countDown();
            registration.close();
            coordinator.close();
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static void event(JComponent overlay, int type, int x, int y, int mask) {
        overlay.dispatchEvent(new MouseEvent(overlay, type, 1L, mask, x, y, 1, false, MouseEvent.BUTTON1));
    }

    static class Fixture {
        final Source mesh = new Source("mesh"), warp = new Source("warp");
        final Mode mode = new Mode();
        final Pack pack = new Pack();
        final Camera camera = new Camera();
        final View view = new View();
        final Document document = new Document();
        final App app = new App();
        final VerifiedMemberResolver resolver;
        final NativeModelingToolSessionResolver sessions;
        boolean dirty;

        Fixture() {
            App.current = app;
            app.document = document;
            app.view = view;
            document.view = view;
            document.mode = mode;
            view.pack = pack;
            view.camera = camera;
            mesh.refs.add(new MeshPoint(mesh, 0, 40, 60));
            mesh.refs.add(new MeshPoint(mesh, 1, 80, 60));
            warp.refs.add(new WarpPoint(warp, 0, 60, 90));
            warp.refs.add(new WarpPoint(warp, 1, 100, 120));
            view.model.objects.put("mesh", mesh);
            view.model.objects.put("warp", warp);
            mode.selector.selected.addAll(List.of(mesh, warp));
            resolver = resolver();
            sessions = new NativeModelingToolSessionResolver(resolver);
        }

        NativeModelingToolSessionResolver.Identity identity() {
            return sessions.resolve().orElseThrow();
        }

        NativeModelingPointProjector.Snapshot capture() {
            return NativeModelingPointProjector.capture(resolver, identity());
        }

        NativeModelingPointSelectionAdapter selection() {
            return new NativeModelingPointSelectionAdapter(resolver, sessions, identity(), () -> true);
        }

        void commit(List<Integer> hits, SelectionMode mode) {
            selection().commit(capture(), hits, mode);
        }
    }

    private static VerifiedMemberResolver resolver() {
        Map<String, StaticSelector> s = new HashMap<>();
        for (String alias : REQUIRED_ALIASES) s.put(alias, StaticSelector.classSelector(alias, name(Source.class)));
        klass(s, DOCUMENT_CLASS, Document.class);
        klass(s, MAIN_MODE_CLASS, Mode.class);
        klass(s, VIEW_CLASS, View.class);
        klass(s, MESH_POINT_CLASS, MeshPoint.class);
        klass(s, WARP_POINT_CLASS, WarpPoint.class);
        klass(s, POINT_EX_CLASS, Point.class);
        klass(s, MESH_CLASS, Source.class);
        klass(s, WARP_CLASS, Source.class);
        s.put(
                APP,
                new StaticSelector(
                        APP,
                        APP,
                        StaticSelector.Kind.METHOD,
                        name(App.class),
                        "instance",
                        "()L" + name(App.class) + ";",
                        9,
                        0));
        method(s, DOCUMENT, App.class, "document", Document.class);
        method(s, CURRENT_VIEW, App.class, "view", View.class);
        method(s, VIEW_LISTENERS, App.class, "listeners", ArrayList.class);
        method(s, CURRENT_MODE, Document.class, "mode", Mode.class);
        method(s, VIEW, Document.class, "view", View.class);
        method(s, MODE_EDITING, Mode.class, "editing", boolean.class);
        method(s, MAIN_SELECTOR, Mode.class, "selector", Selector.class);
        method(s, PACK, View.class, "pack", Pack.class);
        method(s, MAIN_VIEW, Pack.class, "main", Pack.class);
        method(s, COMPONENT, Pack.class, "component", JComponent.class);
        method(s, CAMERA, View.class, "camera", Camera.class);
        method(s, MODEL, View.class, "model", Model.class);
        method(s, MODEL_OBJECT, Model.class, "object", Source.class, String.class);
        method(s, SELECTED_OBJECTS, Selector.class, "objects", List.class);
        method(s, SELECTOR_BOUNDING_BOX, Selector.class, "boundingBox", Box.class);
        method(s, BOUNDING_BOX_DIRTY, Box.class, "setDirty", void.class);
        method(s, SOURCE_EDITABLE, Source.class, "editable", boolean.class);
        method(s, SOURCE_ID, Source.class, "id", String.class);
        method(s, OBJECT_SOURCE, Source.class, "source", Source.class);
        method(s, VIEW_EDITABLE, View.class, "editable", boolean.class, Source.class);
        method(s, OBJECT_POINTS, Source.class, "refs", List.class);
        method(s, SOURCE_SELECTION, Source.class, "points", Points.class);
        method(s, SELECTION_POINTS, Points.class, "self", Points.class);
        method(s, POINT_SOURCE, Point.class, "source", Source.class);
        method(s, MESH_POINT_INDEX, MeshPoint.class, "index", int.class);
        method(s, MESH_POINT_UID, MeshPoint.class, "uid", long.class);
        method(s, WARP_POINT_INDEX, WarpPoint.class, "index", int.class);
        method(s, POINT_CANVAS, Point.class, "canvas", Vector.class);
        method(s, VECTOR_X, Vector.class, "x", float.class);
        method(s, VECTOR_Y, Vector.class, "y", float.class);
        method(s, PROJECT, Camera.class, "project", Vector.class, Vector.class);
        method(s, POINTS, Points.class, "selected", List.class);
        method(s, POINT_WEIGHT, Points.class, "weight", float.class, Object.class, float.class);
        method(s, POINT_REMOVE, Points.class, "remove", boolean.class, Object.class);
        method(s, POINT_ADD_WEIGHTED, Points.class, "add", boolean.class, Object.class, float.class, boolean.class);
        method(s, BEGIN_EDIT, Mode.class, "begin", Undo.class, String.class);
        method(s, END_EDIT, Mode.class, "end", boolean.class, boolean.class, Object.class);
        method(s, SELECTION_UNDO, Undo.class, "capture", void.class);
        method(s, UPDATE_MANAGER, Pack.class, "main", Pack.class);
        method(
                s,
                SELECTION_REFRESH,
                Pack.class,
                "refresh",
                void.class,
                Object.class,
                boolean.class,
                boolean.class,
                boolean.class);
        method(s, SETUP_TOOL, App.class, "tool", void.class, Object.class, Object.class, boolean.class);
        s.put(ARROW_TOOL, StaticSelector.field(ARROW_TOOL, name(App.class), "ARROW", "Ljava/lang/Object;", 9));
        method(s, MAIN_FRAME, App.class, "self", App.class);
        method(s, TOOL_GROUP, App.class, "group", Object.class);
        method(s, UPDATE_TOOL_BUTTONS, App.class, "highlight", void.class, Object.class);
        method(s, VIEW_SCENE_GRAPH, View.class, "pack", Pack.class);
        method(s, SCENE_COMPONENT_OBJECTS, Pack.class, "main", Pack.class);
        method(s, ENTITY_TRAVERSE_ALL, Pack.class, "entities", List.class);
        s.put(VECTOR_CREATE, StaticSelector.constructor(VECTOR_CREATE, name(Vector.class), "(FF)V", 1));
        return TestVerifiedResolvers.create(
                ADAPTER_SLICE_ID,
                Set.of(CAPABILITY_ID),
                List.copyOf(s.values()),
                ModelingBrushTest.class.getClassLoader());
    }

    static void klass(Map<String, StaticSelector> s, String alias, Class<?> type) {
        s.put(alias, StaticSelector.classSelector(alias, name(type)));
    }

    static void method(
            Map<String, StaticSelector> s,
            String alias,
            Class<?> owner,
            String name,
            Class<?> result,
            Class<?>... args) {
        s.put(
                alias,
                StaticSelector.method(
                        alias,
                        name(owner),
                        name,
                        MethodType.methodType(result, args).toMethodDescriptorString(),
                        1));
    }

    static String name(Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public interface Callback<A, B, C> {
        C invoke(A previous, B next);
    }

    public static class App {
        static App current;
        public static final Object ARROW = new Object();
        Document document;
        View view;
        ArrayList<Callback<View, View, Object>> listeners = new ArrayList<>();

        public static App instance() {
            return current;
        }

        public Document document() {
            return document;
        }

        public View view() {
            return view;
        }

        public ArrayList<Callback<View, View, Object>> listeners() {
            return listeners;
        }

        void changeView(View next) {
            View prev = view;
            view = next;
            for (var callback : List.copyOf(listeners)) callback.invoke(prev, next);
        }

        public void tool(Object group, Object mode, boolean persist) {}

        public App self() {
            return this;
        }

        public Object group() {
            return ARROW;
        }

        public void highlight(Object group) {}
    }

    public static class Document {
        View view;
        Mode mode;

        public View view() {
            return view;
        }

        public Mode mode() {
            return mode;
        }
    }

    public static class View {
        Pack pack;
        Camera camera;
        Model model = new Model();

        public Pack pack() {
            return pack;
        }

        public Camera camera() {
            return camera;
        }

        public Model model() {
            return model;
        }

        public boolean editable(Source source) {
            return source.visible;
        }
    }

    public static class Pack {
        JPanel component = new JPanel(null);
        int updates;

        Pack() {
            component.setSize(400, 400);
        }

        public Pack main() {
            return this;
        }

        public JComponent component() {
            return component;
        }

        public List<Object> entities() {
            return List.of();
        }

        public void refresh(Object source, boolean a, boolean b, boolean c) {
            updates++;
        }
    }

    public static class Model {
        Map<String, Source> objects = new HashMap<>();

        public Source object(String id) {
            return objects.get(id);
        }
    }

    public static class Selector {
        List<Source> selected = new ArrayList<>();
        final Box box = new Box(this);

        public List<Source> objects() {
            return selected;
        }

        public Box boundingBox() {
            return box;
        }
    }

    /** Native ACBoundingBox caches its corners until setDirty() invalidates them. */
    public static class Box {
        private final Selector selector;
        private Bounds cached;
        private boolean dirty = true;

        Box(Selector selector) {
            this.selector = selector;
        }

        public void setDirty() {
            dirty = true;
        }

        Bounds bounds() {
            if (dirty) {
                List<Point> points = selector.selected.stream()
                        .flatMap(source -> source.points.values.keySet().stream())
                        .filter(Point.class::isInstance)
                        .map(Point.class::cast)
                        .toList();
                if (points.size() <= 1)
                    points = selector.selected.stream()
                            .flatMap(source -> source.refs.stream())
                            .toList();
                cached = new Bounds(
                        (float) points.stream()
                                .mapToDouble(point -> point.canvas().x())
                                .min()
                                .orElseThrow(),
                        (float) points.stream()
                                .mapToDouble(point -> point.canvas().y())
                                .min()
                                .orElseThrow(),
                        (float) points.stream()
                                .mapToDouble(point -> point.canvas().x())
                                .max()
                                .orElseThrow(),
                        (float) points.stream()
                                .mapToDouble(point -> point.canvas().y())
                                .max()
                                .orElseThrow());
                dirty = false;
            }
            return cached;
        }
    }

    private record Bounds(float minX, float minY, float maxX, float maxY) {}

    public static class Mode {
        Selector selector = new Selector();
        Undo undo = new Undo();
        boolean editing;
        int begins, commits, cancels;
        Runnable onBegin = () -> {};

        public Selector selector() {
            return selector;
        }

        public boolean editing() {
            return editing;
        }

        public Undo begin(String name) {
            assertFalse(editing);
            editing = true;
            begins++;
            onBegin.run();
            return undo;
        }

        public boolean end(boolean cancel, Object callback) {
            editing = false;
            if (cancel) cancels++;
            else commits++;
            return !cancel;
        }
    }

    public static class Undo {
        int captures;

        public void capture() {
            captures++;
        }
    }

    public static class Source {
        String id;
        boolean editable = true, visible = true;
        List<Point> refs = new ArrayList<>();
        Points points = new Points();

        Source(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public boolean editable() {
            return editable;
        }

        public Source source() {
            return this;
        }

        public List<Point> refs() {
            return refs;
        }

        public Points points() {
            return points;
        }
    }

    public static class Points {
        Map<Object, Float> values = new LinkedHashMap<>();
        boolean rejectNext;

        public Points self() {
            return this;
        }

        public List<Object> selected() {
            return List.copyOf(values.keySet());
        }

        public float weight(Object point, float fallback) {
            return values.getOrDefault(point, fallback);
        }

        public boolean remove(Object point) {
            return values.remove(point) != null;
        }

        public boolean add(Object point, float weight, boolean update) {
            if (rejectNext) {
                rejectNext = false;
                return false;
            }
            return values.put(point, weight) == null;
        }
    }

    public static class Point {
        Source source;
        int index;
        Vector position;
        long uid;

        Point(Source source, int index, float x, float y) {
            this.source = source;
            this.index = index;
            position = new Vector(x, y);
            uid = index;
        }

        public Source source() {
            return source;
        }

        public Vector canvas() {
            return position;
        }

        public int index() {
            return index;
        }

        public long uid() {
            return uid;
        }
    }

    public static class MeshPoint extends Point {
        MeshPoint(Source s, int i, float x, float y) {
            super(s, i, x, y);
        }

        @Override
        public int index() {
            return super.index();
        }

        @Override
        public long uid() {
            return super.uid();
        }
    }

    public static class WarpPoint extends Point {
        WarpPoint(Source s, int i, float x, float y) {
            super(s, i, x, y);
        }

        @Override
        public int index() {
            return super.index();
        }
    }

    public record Vector(float x, float y) {}

    public static class Camera {
        float zoom = 2;

        public Vector project(Vector p) {
            return new Vector(p.x * zoom - 20, p.y * zoom - 30);
        }
    }

    static class Tool implements ModelingTool {
        ModelingBrush brush;

        public String id() {
            return "brush";
        }

        public String label() {
            return "Brush";
        }

        public String iconResourcePath() {
            return "icons/brush.png";
        }

        public void activate(ModelingToolContext context) {
            brush = context.selectionBrush();
        }
    }
}
