package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import dev.turboism.sdk.ui.context.PanelTabSelection;
import dev.turboism.ui.action.EditorUiActionRouter;

import java.util.function.BiConsumer;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedEmbeddedPanelHostOperationsTest {

    @Test
    void deferredEdtDispatchReturnsWhileTheEdtIsBusy() throws Exception {
        CountDownLatch edtEntered = new CountDownLatch(1);
        CountDownLatch releaseEdt = new CountDownLatch(1);
        CountDownLatch operationRan = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtEntered.countDown();
            await(releaseEdt);
        });
        assertTrue(edtEntered.await(2, TimeUnit.SECONDS));

        try {
            assertTimeoutPreemptively(
                Duration.ofMillis(500),
                () -> VerifiedEmbeddedPanelHostOperations.runOnEdtLater(operationRan::countDown)
            );
            assertEquals(1L, operationRan.getCount());
        } finally {
            releaseEdt.countDown();
        }

        assertTrue(operationRan.await(2, TimeUnit.SECONDS));
    }

    @Test
    void panelCleanupHidesClosesRemovesWindowEntryAndAlwaysRefreshes() {
        List<String> operations = new ArrayList<>();

        VerifiedEmbeddedPanelHostOperations.closePanel(
            () -> operations.add("hide"),
            () -> operations.add("close"),
            () -> operations.add("remove-window-item"),
            () -> operations.add("refresh")
        );

        assertEquals(List.of("hide", "close", "remove-window-item", "refresh"), operations);

        operations.clear();
        IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> VerifiedEmbeddedPanelHostOperations.closePanel(
                () -> {
                    operations.add("hide");
                    throw new IllegalStateException("hide failed");
                },
                () -> operations.add("close"),
                () -> operations.add("remove-window-item"),
                () -> operations.add("refresh")
            )
        );
        assertEquals("hide failed", failure.getMessage());
        assertEquals(List.of("hide", "close", "remove-window-item", "refresh"), operations);
    }

    @Test
    void originalDockBoxMustStillBelongToTheWorkspaceTree() {
        final PaletteBox live = new PaletteBox(1);
        final PaletteBox detached = new PaletteBox(1);
        final Workspace workspace = new Workspace(
            new RootContainer(new SplitContainer(List.of(new SplitContainer(List.of(live)))))
        );
        final VerifiedEmbeddedPanelHostOperations operations = treeOperations();

        assertTrue(operations.isDockBoxInWorkspaceTree(workspace, live));
        assertFalse(operations.isDockBoxInWorkspaceTree(workspace, detached));
        assertFalse(operations.isDockBoxInWorkspaceTree(
            new Workspace(new RootContainer(() -> 0)),
            detached
        ));
    }

    @Test
    void cleanupRemovesEmptyBoxesAndBranchesButKeepsLiveSingletonBranches() {
        final PaletteBox live = new PaletteBox(1);
        final PaletteBox nestedLive = new PaletteBox(1);
        final SplitContainer emptyBranch = new SplitContainer(List.of(new PaletteBox(0)));
        final SplitContainer liveBranch = new SplitContainer(List.of(nestedLive));
        final SplitContainer root = new SplitContainer(
            List.of(live, new PaletteBox(0), emptyBranch, liveBranch)
        );

        treeOperations().pruneEmptyBoxes(root);

        assertEquals(List.of(live, liveBranch), root.contents());
        assertEquals(List.of(nestedLive), liveBranch.contents());
    }

    @Test
    void cleanEmptyDocksRejectsAnInvalidatedHostBinding() throws Exception {
        final VerifiedEmbeddedPanelHostOperations operations = treeOperations();
        operations.bindHostGeneration(7);
        final boolean[] rejected = {false};
        operations.invalidateHost();

        final CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                operations.cleanEmptyDocks();
            } catch (IllegalStateException expected) {
                rejected[0] = true;
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertTrue(rejected[0]);
    }

    @Test
    void queuedCleanupFromPreviousGenerationIsRejectedAfterRebind() throws Exception {
        final VerifiedEmbeddedPanelHostOperations operations = treeOperations();
        operations.bindHostGeneration(7);
        final CountDownLatch edtEntered = new CountDownLatch(1);
        final CountDownLatch releaseEdt = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            edtEntered.countDown();
            await(releaseEdt);
        });
        assertTrue(edtEntered.await(2, TimeUnit.SECONDS));

        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread cleanup = new Thread(() -> {
            try {
                operations.cleanEmptyDocks();
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        cleanup.start();
        awaitWaiting(cleanup);

        operations.invalidateHost();
        operations.bindHostGeneration(8);
        releaseEdt.countDown();
        cleanup.join(2_000L);

        assertFalse(cleanup.isAlive());
        assertTrue(failure.get() instanceof IllegalStateException);
        assertEquals("embedded-panel host binding is no longer active", failure.get().getMessage());
    }

    @Test
    void panelToggleRejectsAnInvalidatedHostBeforeNativeLookup() {
        final VerifiedEmbeddedPanelHostOperations operations = treeOperations();
        operations.bindHostGeneration(7);
        operations.invalidateHost();

        final IllegalStateException failure = assertThrows(
            IllegalStateException.class,
            () -> operations.togglePanelFloating(new PanelTabSelection(7, "palette-a", false))
        );

        assertEquals("embedded-panel host binding is no longer active", failure.getMessage());
    }

    @Test
    void panelTabRouteCachesNativePaletteAndPreservesContributionGeneration() {
        RouteApp.instanceCalls = 0;
        final AtomicReference<String> routed = new AtomicReference<>();
        final AtomicReference<ActionRegistry.ActionContext> context = new AtomicReference<>();
        final VerifiedEmbeddedPanelHostOperations operations = routeOperations(new EditorUiActionRouter() {
            @Override
            public void invoke(final String pluginId, final String actionId) {
                throw new AssertionError("typed panel-tab context was not routed");
            }

            @Override
            public void invoke(
                final String pluginId,
                final String actionId,
                final ActionRegistry.ActionContext value
            ) {
                routed.set(pluginId + ":" + actionId);
                context.set(value);
            }
        });
        operations.bindHostGeneration(7);

        operations.routePanelTabAction(
            new PanelTabMenuContribution(7, "turboism.core", panelTabContribution()),
            new RoutePalette(new RoutePaletteId("palette-a"))
        );

        assertEquals("turboism.core:panel.toggle", routed.get());
        assertEquals(
            new PanelTabSelection(7, "palette-a", false),
            context.get().panelTabSelection().orElseThrow()
        );
        assertEquals(1, RouteApp.instanceCalls);
    }

    @Test
    void stalePanelTabContributionDoesNotRouteAgainstANewerHostGeneration() {
        RouteApp.instanceCalls = 0;
        final AtomicReference<ActionRegistry.ActionContext> routed = new AtomicReference<>();
        final VerifiedEmbeddedPanelHostOperations operations = routeOperations(new EditorUiActionRouter() {
            @Override
            public void invoke(final String pluginId, final String actionId) {
            }

            @Override
            public void invoke(
                final String pluginId,
                final String actionId,
                final ActionRegistry.ActionContext context
            ) {
                routed.set(context);
            }
        });
        operations.bindHostGeneration(8);

        operations.routePanelTabAction(
            new PanelTabMenuContribution(7, "turboism.core", panelTabContribution()),
            new RoutePalette(new RoutePaletteId("palette-a"))
        );

        assertNull(routed.get());
        assertEquals(0, RouteApp.instanceCalls);
    }

    @Test
    void injectedButtonActionRoutesToContributorPluginId() {
        final List<String> routed = new ArrayList<>();
        final BiConsumer<String, Optional<UiActionEvent>> action = VerifiedEmbeddedPanelHostOperations.routedAction(
            (pluginId, actionId) -> routed.add(pluginId + ":" + actionId),
            Map.of("clipmask-viewer.open.viewer", "clipmask-viewer"),
            "turboism.panel.main");

        action.accept("clipmask-viewer.open.viewer", Optional.empty());
        action.accept("panel.own.action", Optional.empty());

        assertEquals(
            List.of("clipmask-viewer:clipmask-viewer.open.viewer", "turboism.panel.main:panel.own.action"),
            routed);
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test EDT wait interrupted", exception);
        }
    }


    private static void awaitWaiting(final Thread thread) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (thread.getState() != Thread.State.WAITING && System.nanoTime() < deadline) {
            Thread.sleep(5L);
        }
        assertEquals(Thread.State.WAITING, thread.getState());
    }

    private static VerifiedEmbeddedPanelHostOperations treeOperations() {
        final List<StaticSelector> selectors = List.of(
            StaticSelector.classSelector(
                "cubism.ui-panel.palette-box.class", internal(PaletteBox.class)
            ),
            method(
                "cubism.ui-panel.workspace.root-container",
                Workspace.class,
                "rootContainer",
                descriptor(RootContainer.class)
            ),
            method(
                "cubism.ui-panel.root.component",
                RootContainer.class,
                "component",
                descriptor(Component.class)
            ),
            method(
                "cubism.ui-panel.split.contents",
                SplitContainer.class,
                "contents",
                "()Ljava/util/List;"
            ),
            method(
                "cubism.ui-panel.split.remove",
                SplitContainer.class,
                "remove",
                "(L" + internal(Component.class) + ";)V"
            ),
            method(
                "cubism.ui-panel.component.palette-count",
                Component.class,
                "paletteCount",
                "()I"
            )
        );
        final VerifiedMemberResolver resolver = TestVerifiedResolvers.create(
            "adapter.editor-ui.embedded-panel",
            Set.of("cubism.editor-ui.embedded-panel"),
            selectors,
            VerifiedEmbeddedPanelHostOperationsTest.class.getClassLoader()
        );
        return new VerifiedEmbeddedPanelHostOperations(
            resolver,
            (pluginId, actionId) -> { }
        );
    }

    private static VerifiedEmbeddedPanelHostOperations routeOperations(
        final EditorUiActionRouter actionRouter
    ) {
        final List<StaticSelector> selectors = List.of(
            StaticSelector.staticMethod(
                "cubism.ui-panel.app-controller.instance",
                internal(RouteApp.class),
                "instance",
                descriptor(RouteApp.class),
                StaticSelector.ACCESS_PUBLIC
            ),
            method(
                "cubism.ui-panel.app-controller.main-frame",
                RouteApp.class,
                "mainFrame",
                descriptor(RouteMainFrame.class)
            ),
            method(
                "cubism.ui-panel.main-frame.dock-manager",
                RouteMainFrame.class,
                "dockManager",
                descriptor(RouteDockManager.class)
            ),
            method(
                "cubism.ui-panel.dock.palette-manager",
                RouteDockManager.class,
                "paletteManager",
                descriptor(RoutePaletteManager.class)
            ),
            method(
                "cubism.ui-panel.palette.id",
                RoutePalette.class,
                "id",
                descriptor(RoutePaletteId.class)
            )
        );
        return new VerifiedEmbeddedPanelHostOperations(
            TestVerifiedResolvers.create(
                "adapter.editor-ui.embedded-panel",
                Set.of("cubism.editor-ui.embedded-panel"),
                selectors,
                VerifiedEmbeddedPanelHostOperationsTest.class.getClassLoader()
            ),
            actionRouter
        );
    }

    private static ContextMenuRegistry.ContextMenuContribution panelTabContribution() {
        return new ContextMenuRegistry.ContextMenuContribution(
            "panel.float",
            "panel.toggle",
            "Float",
            null,
            "panel.docked",
            ContextMenuRegistry.Location.WORKSPACE_OBJECT,
            Set.of(),
            100,
            ContextMenuRegistry.Target.PANEL_TAB,
            ContextMenuRegistry.Operation.TOGGLE_PANEL_FLOATING,
            ContextMenuRegistry.ContextMenuEntry.item("panel.float", "Float", "panel.toggle"),
            ContextMenuRegistry.Placement.last()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String descriptor(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    public interface Component {
        int paletteCount();
    }

    public static final class PaletteBox implements Component {
        private final int paletteCount;

        public PaletteBox(final int paletteCount) {
            this.paletteCount = paletteCount;
        }

        @Override
        public int paletteCount() {
            return paletteCount;
        }
    }

    public static final class SplitContainer implements Component {
        private final List<Component> contents;

        public SplitContainer(final List<? extends Component> contents) {
            this.contents = new ArrayList<>(contents);
        }

        public List<Component> contents() {
            return contents;
        }

        public void remove(final Component component) {
            contents.remove(component);
        }

        @Override
        public int paletteCount() {
            return contents.stream().mapToInt(Component::paletteCount).sum();
        }
    }

    public record RootContainer(Component component) {
    }

    public record Workspace(RootContainer rootContainer) {
    }


    public static final class RouteApp {
        private static int instanceCalls;

        public static RouteApp instance() {
            instanceCalls++;
            return new RouteApp();
        }

        public RouteMainFrame mainFrame() {
            return new RouteMainFrame();
        }
    }

    public static final class RouteMainFrame {
        public RouteDockManager dockManager() {
            return new RouteDockManager();
        }
    }

    public static final class RouteDockManager {
        public RoutePaletteManager paletteManager() {
            return new RoutePaletteManager();
        }
    }

    public static final class RoutePaletteManager {
    }

    public record RoutePalette(RoutePaletteId id) {
    }

    public record RoutePaletteId(String value) {
        @Override
        public String toString() {
            return value;
        }
    }
    @Test
    void installRegistersCheckMenuItemInPaletteMenuMapAndCleansBothOnClose() throws Exception {
        final InstallHost host = installHost();
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            ),
            (actionId, event) -> { }
        )));
        final EmbeddedPanelHostOperations.PanelHandle handle = handleRef.get();
        final FakePaletteId paletteId = host.paletteId("test-pane");

        // The check menu item and its paletteMenuMap entry exist before addPalette /
        // setPaletteVisible; every native updateWindowMenuItem run (including the one the
        // host triggers at the end of setPaletteVisible) derived the check state instead of
        // reporting a missing map entry.
        assertTrue(host.log.toString().contains("check:" + paletteId + ":true"));
        assertFalse(host.log.toString().contains("missing"));
        assertEquals(List.of(
            "add:" + paletteId,
            "set-visible:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);

        final FakeCheckMenuItem item = host.menuItem(paletteId);
        assertNotNull(item);
        assertEquals("turboism:turboism.core:test-pane:window-menu", item.getName());
        assertEquals(item, host.paletteMenuMap.get(paletteId));
        // CCheckMenuItem's Swing peer is a JCheckBoxMenuItem (com.live2d.ui.swingImpl.k),
        // which is what renders the check mark next to the label.
        assertTrue(item.peer instanceof JCheckBoxMenuItem);
        assertEquals(1, host.windowMenu.getItems().size());

        // The PanelHandle activation path routes to the workspace activate (show) path;
        // menu-item clicks themselves toggle (covered by windowMenuClickTogglesPaletteVisibility).
        host.log.clear();
        runOnEdt(handle::activate);
        assertTrue(host.log.contains("activate:" + paletteId));

        // close removes the map entry and detaches the menu item (Swing peer included).
        host.log.clear();
        runOnEdt(handle::close);
        assertTrue(host.paletteMenuMap.isEmpty());
        assertTrue(host.windowMenu.getItems().isEmpty());
        assertTrue(host.log.contains("close:" + paletteId));
        assertEquals("update-window-menu", host.log.get(host.log.size() - 2));
        assertEquals("repaint", host.log.get(host.log.size() - 1));
    }

    @Test
    void windowMenuClickTogglesPaletteVisibility() throws Exception {
        final InstallHost host = installHost();
        runOnEdt(() -> host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            ),
            (actionId, event) -> { }
        ));
        final FakePaletteId paletteId = host.paletteId("test-pane");
        final FakeCheckMenuItem item = host.menuItem(paletteId);
        assertNotNull(item);

        // Visible palette: the native updateWindowMenuItem left the check item selected.
        // The user click flips the Swing peer and fires the native aa handler, which reads
        // isSelected() (now false) and hides the palette through the native hide route
        // (removeTab/removePaletteUpdate plus the trailing updateWindowMenuItem).
        item.peer.setSelected(true);
        host.log.clear();
        runOnEdt(() -> item.peer.doClick());
        assertEquals(List.of(
            "set-visible:" + paletteId + ":false",
            "update-window-menu",
            "check:" + paletteId + ":false"
        ), host.log);
        assertFalse(host.log.contains("activate:"));

        // Hidden palette: the item is unchecked. Clicking again flips it back to selected,
        // so the handler takes the verified show path (workspace activate + setPaletteVisible).
        item.peer.setSelected(false);
        host.log.clear();
        runOnEdt(() -> item.peer.doClick());
        assertEquals(List.of(
            "activate:" + paletteId,
            "set-visible:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
    }

    @Test
    void installFailureAfterMenuRegistrationCleansMapEntryAndMenuItem() throws Exception {
        final InstallHost host = installHost();
        host.failOnAddPalette = true;

        // The resolver fails closed by wrapping host invocation failures; the cleanup
        // must still drop the paletteMenuMap entry and the menu item.
        assertThrows(dev.turboism.mapping.verification.VerifiedAccessException.class, () ->
            runOnEdt(() -> host.operations.addPanel(
                new EmbeddedPanelContributionDescriptor(
                    "turboism.core",
                    "test-pane",
                    "Test Pane",
                    "window",
                    100,
                    new dev.turboism.sdk.ui.PanelView.Text("content")
                ),
                (actionId, event) -> { }
            ))
        );

        // The failure cleanup removed both the paletteMenuMap entry and the menu item.
        assertTrue(host.paletteMenuMap.isEmpty());
        assertTrue(host.windowMenu.getItems().isEmpty());
        assertTrue(host.windowMenu.getJMenu().getPopupMenu().getComponentCount() == 0);
        assertTrue(host.log.contains("close:" + host.paletteId("test-pane")));
        assertEquals("update-window-menu", host.log.get(host.log.size() - 2));
        assertEquals("repaint", host.log.get(host.log.size() - 1));
    }

    /** Runs a body on the EDT, rethrowing failures on the caller thread. */
    private static void runOnEdt(final Runnable body) throws Exception {
        final Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                body.run();
            } catch (Throwable thrown) {
                failure[0] = thrown;
            }
        });
        if (failure[0] instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure[0] instanceof Error error) {
            throw error;
        }
        if (failure[0] != null) {
            throw new IllegalStateException("EDT body failed", failure[0]);
        }
    }

    /**
     * A fake Cubism embedded-panel host graph mirroring the native relationships:
     * CEDockWrapper.getMainFrameCtrl -> CEMainFrameCtrl.getPaletteMenuMap (HashMap),
     * and updateWindowMenuItem iterating palettes against the map with visibility.
     */
    private static InstallHost installHost() {
        return new InstallHost();
    }

    static final class InstallHost {
        private final List<String> log = new ArrayList<>();
        private final HashMap<FakePaletteId, FakeCheckMenuItem> paletteMenuMap = new HashMap<>();
        private final FakePaletteManager paletteManager = new FakePaletteManager(this);
        private final FakeDockWrapper dockWrapper = new FakeDockWrapper(this);
        private final FakeMainFrameCtrl mainFrameCtrl = new FakeMainFrameCtrl(this);
        private final FakeFrame frame = new FakeFrame(this);
        private final FakeMenuBar menuBar = new FakeMenuBar(this);
        private final FakeMenu windowMenu = new FakeMenu(this, "Window");
        private final FakeWorkspace workspace = new FakeWorkspace(this);
        private final FakeApp app = new FakeApp(this);
        private final VerifiedEmbeddedPanelHostOperations operations;
        private boolean failOnAddPalette;

        InstallHost() {
            FakeApp.HOST = this;
            final List<StaticSelector> selectors = List.of(
                StaticSelector.staticMethod(
                    "cubism.ui-panel.app-controller.instance",
                    internal(FakeApp.class),
                    "instance",
                    descriptor(FakeApp.class),
                    StaticSelector.ACCESS_PUBLIC
                ),
                method(
                    "cubism.ui-panel.app-controller.main-frame",
                    FakeApp.class,
                    "getMainFrameCtrl",
                    descriptor(FakeMainFrameCtrl.class)
                ),
                method(
                    "cubism.ui-panel.app-controller.repaint",
                    FakeApp.class,
                    "forceRepaintCanvas$cubism",
                    "()V"
                ),
                method(
                    "cubism.ui-panel.main-frame.dock-manager",
                    FakeMainFrameCtrl.class,
                    "getDockManager",
                    descriptor(FakeDockWrapper.class)
                ),
                method(
                    "cubism.ui-panel.main-frame.palette-menu-map",
                    FakeMainFrameCtrl.class,
                    "getPaletteMenuMap",
                    "()Ljava/util/HashMap;"
                ),
                method(
                    "cubism.ui-panel.dock.palette-manager",
                    FakeDockWrapper.class,
                    "getPaletteManager",
                    descriptor(FakePaletteManager.class)
                ),
                method(
                    "cubism.ui-panel.dock.main-frame-ctrl",
                    FakeDockWrapper.class,
                    "getMainFrameCtrl",
                    descriptor(FakeMainFrameCtrl.class)
                ),
                method(
                    "cubism.ui-panel.dock.set-palette-visible",
                    FakeDockWrapper.class,
                    "setPaletteVisible",
                    "(L" + internal(FakePalette.class) + ";Z)V"
                ),
                method(
                    "cubism.ui-panel.dock.update-window-menu",
                    FakeDockWrapper.class,
                    "updateWindowMenuItem",
                    "()V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.get",
                    FakePaletteManager.class,
                    "getPalette",
                    "(L" + internal(FakePaletteId.class) + ";)L" + internal(FakePalette.class) + ";"
                ),
                method(
                    "cubism.ui-panel.palette-manager.add",
                    FakePaletteManager.class,
                    "addPalette",
                    "(L" + internal(FakePalette.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.close",
                    FakePaletteManager.class,
                    "closePalette",
                    "(L" + internal(FakePaletteId.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.current-workspace",
                    FakePaletteManager.class,
                    "getCurrentWorkspace",
                    descriptor(FakeWorkspace.class)
                ),
                method(
                    "cubism.ui-panel.workspace.activate",
                    FakeWorkspace.class,
                    "activate",
                    "(L" + internal(FakePalette.class) + ";)Z"
                ),
                method(
                    "cubism.ui-panel.workspace.palette-box-for",
                    FakeWorkspace.class,
                    "getPaletteBoxFor",
                    "(L" + internal(FakePalette.class) + ";)L" + internal(FakePaletteBox.class) + ";"
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.palette-id.create",
                    internal(FakePaletteId.class),
                    "(Ljava/lang/String;)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.palette.create",
                    internal(FakePalette.class),
                    "(L" + internal(FakePaletteId.class) + ";Ljava/lang/String;)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                method(
                    "cubism.ui-panel.palette.set-panel",
                    FakePalette.class,
                    "setPanel",
                    "(L" + internal(FakeWidget.class) + ";II)V"
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.swing-container.create",
                    internal(FakeSwingContainer.class),
                    "(Ljavax/swing/JComponent;)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                method(
                    "cubism.ui-panel.main-frame.window",
                    FakeMainFrameCtrl.class,
                    "getMainFrame",
                    descriptor(FakeFrame.class)
                ),
                method(
                    "cubism.ui-panel.window.menu-bar",
                    FakeFrame.class,
                    "getMenuBar",
                    descriptor(FakeMenuBar.class)
                ),
                method(
                    "cubism.ui-panel.menu-bar.menus",
                    FakeMenuBar.class,
                    "getMenus",
                    "()Ljava/util/List;"
                ),
                method(
                    "cubism.ui-panel.widget.name",
                    FakeWidget.class,
                    "getName",
                    "()Ljava/lang/String;"
                ),
                method(
                    "cubism.ui-panel.widget.set-name",
                    FakeWidget.class,
                    "setName",
                    "(Ljava/lang/String;)V"
                ),
                method(
                    "cubism.ui-panel.widget.revalidate",
                    FakeWidget.class,
                    "revalidate",
                    "()V"
                ),
                method(
                    "cubism.ui-panel.widget.repaint",
                    FakeWidget.class,
                    "repaint",
                    "()V"
                ),
                method(
                    "cubism.ui-panel.menu.items",
                    FakeMenu.class,
                    "getItems",
                    "()Ljava/util/List;"
                ),
                method(
                    "cubism.ui-panel.menu.add",
                    FakeMenu.class,
                    "add",
                    "(L" + internal(FakeMenuItem.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.menu.swing",
                    FakeMenu.class,
                    "getJMenu",
                    "()Ljavax/swing/JMenu;"
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.menu-item.check.create",
                    internal(FakeCheckMenuItem.class),
                    "(Ljava/lang/String;L" + internal(FakeCallback.class) + ";)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                method(
                    "cubism.ui-panel.menu-item.swing",
                    FakeMenuItem.class,
                    "getJMenuItem",
                    "()Ljavax/swing/JMenuItem;"
                ),
                method(
                    "cubism.ui-panel.menu-item.is-selected",
                    FakeCheckMenuItem.class,
                    "isSelected",
                    "()Z"
                )
            );
            operations = new VerifiedEmbeddedPanelHostOperations(
                TestVerifiedResolvers.create(
                    "adapter.editor-ui.embedded-panel",
                    Set.of("cubism.editor-ui.embedded-panel"),
                    selectors,
                    VerifiedEmbeddedPanelHostOperationsTest.class.getClassLoader()
                ),
                (pluginId, actionId) -> { }
            );
        }

        private FakePaletteId paletteId(final String contributionId) {
            return new FakePaletteId("turboism:turboism.core:" + contributionId);
        }

        private FakeCheckMenuItem menuItem(final FakePaletteId paletteId) {
            return paletteMenuMap.get(paletteId);
        }
    }

    /** Single-abstract-method stand-in for kotlin.jvm.functions.Function1. */
    public interface FakeCallback {
        Object invoke(Object argument);
    }

    public static class FakeWidget {
        private String name = "";

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public void revalidate() {
        }

        public void repaint() {
        }
    }

    public static class FakeMenuItem extends FakeWidget {
        private final String label;
        protected JMenuItem peer;

        public FakeMenuItem(final String label) {
            this.label = label;
            this.peer = new JMenuItem(label);
        }

        public String label() {
            return label;
        }

        public JMenuItem getJMenuItem() {
            return peer;
        }
    }

    public static final class FakeCheckMenuItem extends FakeMenuItem {
        private final FakeCallback callback;

        public FakeCheckMenuItem(final String label, final FakeCallback callback) {
            super(label);
            this.callback = callback;
            this.peer = new JCheckBoxMenuItem(label);
            // Mirrors the native CCheckMenuItem wiring: the Swing peer flips the check
            // state on click and then fires the kotlin callback, which reads isSelected().
            this.peer.addActionListener(event -> callback.invoke(event));
        }

        public FakeCallback callback() {
            return callback;
        }

        public boolean isSelected() {
            return peer.isSelected();
        }
    }

    public static final class FakeMenu extends FakeWidget {
        private final InstallHost host;
        private final List<FakeMenuItem> items = new ArrayList<>();
        private final JMenu jMenu;

        public FakeMenu(final InstallHost host, final String label) {
            this.host = host;
            this.jMenu = new JMenu(label);
        }

        public List<FakeMenuItem> getItems() {
            return items;
        }

        public void add(final FakeMenuItem item) {
            items.add(item);
            jMenu.add(item.getJMenuItem());
        }

        public JMenu getJMenu() {
            return jMenu;
        }
    }

    public static final class FakeMenuBar extends FakeWidget {
        private final InstallHost host;

        public FakeMenuBar(final InstallHost host) {
            this.host = host;
        }

        public List<FakeMenu> getMenus() {
            return List.of(host.windowMenu);
        }
    }

    public static final class FakeFrame {
        private final InstallHost host;

        public FakeFrame(final InstallHost host) {
            this.host = host;
        }

        public FakeMenuBar getMenuBar() {
            return host.menuBar;
        }
    }

    public static final class FakeApp {
        private static InstallHost HOST;
        private final InstallHost host;

        public FakeApp(final InstallHost host) {
            this.host = host;
        }

        public static FakeApp instance() {
            return HOST.app;
        }

        public FakeMainFrameCtrl getMainFrameCtrl() {
            return host.mainFrameCtrl;
        }

        public void forceRepaintCanvas$cubism() {
            host.log.add("repaint");
        }
    }

    public static final class FakeMainFrameCtrl {
        private final InstallHost host;

        public FakeMainFrameCtrl(final InstallHost host) {
            this.host = host;
        }

        public FakeDockWrapper getDockManager() {
            return host.dockWrapper;
        }

        public FakeFrame getMainFrame() {
            return host.frame;
        }

        public HashMap<FakePaletteId, FakeCheckMenuItem> getPaletteMenuMap() {
            return host.paletteMenuMap;
        }
    }

    public static final class FakeDockWrapper {
        private final InstallHost host;
        private final Set<FakePalette> visible = new HashSet<>();

        public FakeDockWrapper(final InstallHost host) {
            this.host = host;
        }

        public FakePaletteManager getPaletteManager() {
            return host.paletteManager;
        }

        public FakeMainFrameCtrl getMainFrameCtrl() {
            return host.mainFrameCtrl;
        }

        public void setPaletteVisible(final FakePalette palette, final boolean value) {
            host.log.add("set-visible:" + palette.getPaletteId() + ":" + value);
            if (value) {
                visible.add(palette);
            } else {
                visible.remove(palette);
            }
            // Native behavior: setPaletteVisible ends with updateWindowMenuItem.
            updateWindowMenuItem();
        }

        public void updateWindowMenuItem() {
            host.log.add("update-window-menu");
            for (FakePalette palette : host.paletteManager.palettes()) {
                final FakePaletteId paletteId = palette.getPaletteId();
                final FakeCheckMenuItem item = host.paletteMenuMap.get(paletteId);
                if (item == null) {
                    // Mirrors the native DEVELOPER_MODE failure (RuntimeException "Illegal state :_")
                    // and production silent skip for unregistered palettes.
                    host.log.add("missing:" + paletteId);
                } else {
                    host.log.add("check:" + paletteId + ":" + visible.contains(palette));
                }
            }
        }
    }

    public static final class FakePaletteManager {
        private final InstallHost host;
        private final Map<FakePaletteId, FakePalette> palettes = new HashMap<>();

        public FakePaletteManager(final InstallHost host) {
            this.host = host;
        }

        public FakePalette getPalette(final FakePaletteId paletteId) {
            return palettes.get(paletteId);
        }

        public void addPalette(final FakePalette palette) {
            if (host.failOnAddPalette) {
                throw new IllegalStateException("palette add rejected");
            }
            host.log.add("add:" + palette.getPaletteId());
            palettes.put(palette.getPaletteId(), palette);
        }

        public void closePalette(final FakePaletteId paletteId) {
            host.log.add("close:" + paletteId);
            palettes.remove(paletteId);
        }

        public FakeWorkspace getCurrentWorkspace() {
            return host.workspace;
        }

        public List<FakePalette> palettes() {
            return List.copyOf(palettes.values());
        }
    }

    public static final class FakeWorkspace {
        private final InstallHost host;

        public FakeWorkspace(final InstallHost host) {
            this.host = host;
        }

        public boolean activate(final FakePalette palette) {
            host.log.add("activate:" + palette.getPaletteId());
            return true;
        }

        public FakePaletteBox getPaletteBoxFor(final FakePalette palette) {
            return null;
        }
    }

    public static final class FakePaletteBox {
    }

    public static final class FakePaletteId {
        private final String value;

        public FakePaletteId(final String value) {
            this.value = value;
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof FakePaletteId that && value.equals(that.value);
        }

        @Override
        public int hashCode() {
            return value.hashCode();
        }

        @Override
        public String toString() {
            return value;
        }
    }

    public static final class FakePalette {
        private final FakePaletteId paletteId;

        public FakePalette(final FakePaletteId paletteId, final String title) {
            this.paletteId = paletteId;
        }

        public FakePaletteId getPaletteId() {
            return paletteId;
        }

        public void setPanel(final FakeWidget widget, final int width, final int height) {
        }
    }

    public static final class FakeSwingContainer extends FakeWidget {
        public FakeSwingContainer(final JComponent component) {
        }
    }

}
