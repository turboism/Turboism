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
import java.awt.BorderLayout;
import java.awt.event.ContainerEvent;
import java.awt.event.ContainerListener;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

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
            StaticSelector.classSelector(
                "cubism.ui-panel.split.class", internal(SplitContainer.class)
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

    /** Non-box, non-split workspace component (mirrors CPMContentsBox). */
    public static final class ContentsBox implements Component {
        @Override
        public int paletteCount() {
            return 0;
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
    void refreshSwapsTheStableWrappersSingleChildWithoutReinstalling() throws Exception {
        final InstallHost host = installHost();
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("first"),
                false
            ),
            (actionId, event) -> { }
        )));
        final EmbeddedPanelHostOperations.PanelHandle handle = handleRef.get();
        final FakePaletteId paletteId = host.paletteId("test-pane");

        final FakeSwingContainer nativeContainer = host.nativeContainer(paletteId);
        // The host is given a layout-neutral panel wrapper, never the layout-specific
        // renderer root itself (whose header/scroll children must stay direct children).
        assertTrue(nativeContainer.component() instanceof JPanel);
        final JPanel wrapper = (JPanel) nativeContainer.component();
        assertTrue(wrapper.getLayout() instanceof BorderLayout);
        assertEquals(1, wrapper.getComponentCount());
        final java.awt.Component first = wrapper.getComponent(0);
        assertEquals("turboism:turboism.core:test-pane", first.getName());

        // Observe the wrapper's child counts during the refresh so the host can
        // never be seen empty (no blank flash).
        final List<Integer> observedCounts = new ArrayList<>();
        wrapper.addContainerListener(new ContainerListener() {
            @Override
            public void componentAdded(final ContainerEvent event) {
                observedCounts.add(wrapper.getComponentCount());
            }

            @Override
            public void componentRemoved(final ContainerEvent event) {
                observedCounts.add(wrapper.getComponentCount());
            }
        });

        // Refresh rebuilds the renderer root inside the same wrapper: no second
        // setPanel, no palette close/reinstall, no host interaction at all.
        host.log.clear();
        runOnEdt(() -> handle.updateContent(new EmbeddedPanelContributionDescriptor(
            "turboism.core",
            "test-pane",
            "Test Pane",
            "window",
            100,
            new dev.turboism.sdk.ui.PanelView.Text("second"),
            false
        )));
        assertTrue(host.log.isEmpty());
        assertSame(nativeContainer, host.nativeContainer(paletteId));
        assertSame(wrapper, nativeContainer.component());
        assertEquals(1, wrapper.getComponentCount());
        final java.awt.Component second = wrapper.getComponent(0);
        assertNotSame(first, second);
        assertTrue(second instanceof JLabel);
        assertTrue(((JLabel) second).getText().contains("second"));
        assertEquals("turboism:turboism.core:test-pane", second.getName());

        // No observed event may see the wrapper with zero children: the fresh root
        // is attached before the previous one is detached.
        assertFalse(observedCounts.isEmpty(), "refresh must re-parent the renderer root");
        assertTrue(
            observedCounts.stream().noneMatch(count -> count == 0),
            "wrapper observed with zero children: " + observedCounts
        );

        // After sizing/layout the BorderLayout CENTER child fills the wrapper.
        wrapper.setSize(320, 240);
        wrapper.doLayout();
        assertEquals(320, second.getWidth());
        assertEquals(240, second.getHeight());

        // Close the retained handle on the EDT so the content coordinator and the
        // fake native palette state do not leak into later tests.
        runOnEdt(handle::close);
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
            , false),
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
            "activate:" + paletteId,
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
    void floatingByDefaultInstallFloatsOnlyAfterWorkspaceAttachment() throws Exception {
        final InstallHost host = installHost();
        host.firstPaletteBox = new FakePaletteBox(host, "box-a");
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content"),
                true
            ),
            (actionId, event) -> { }
        )));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        // The float conversion resolves the palette's source box only after the
        // palette was attached to a workspace box: the workspace attachment
        // (add-tab) appears before the floating lookup (box-for), and the install
        // completes instead of failing with "Cubism panel is not docked".
        assertFalse(host.log.toString().contains("Cubism panel is not docked"));
        assertTrue(host.log.indexOf("add-tab:box-a:" + paletteId)
            < host.log.indexOf("box-for:" + paletteId + ":box-a"), "log=" + host.log);
        assertEquals(List.of(
            "add:" + paletteId,
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "box-for:" + paletteId + ":box-a",
            "main-frame-window",
            "palette-box-create",
            "palette-frame-create",
            "add-palette-frame",
            "remove-tab:box-a:" + paletteId,
            "root-set-component",
            "remove-update",
            "verify-cleanup",
            "fire-state",
            "window-visible:true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
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
            , false),
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
    void showReusesExistingPaletteBoxInsteadOfOpeningNewColumn() throws Exception {
        final InstallHost host = installHost();
        host.firstPaletteBox = new FakePaletteBox(host, "box-a");
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        )));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        // Reuse path: the palette is added as a tab of the first existing workspace box
        // and selected there; the new-column path (setPaletteVisible true) never runs,
        // and the derived check state is still selected.
        assertEquals(List.of(
            "add:" + paletteId,
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));

        // The PanelHandle activation path reuses the same box as well.
        host.log.clear();
        runOnEdt(handleRef.get()::activate);
        assertEquals(List.of(
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));
    }

    @Test
    void showDocksIntoLeastLoadedPaletteBoxAndBreaksTiesByTraversalOrder() throws Exception {
        final InstallHost host = installHost();
        // box-a holds 2 docked tabs, box-b holds 1: the new tab must land in box-b.
        host.workspaceTree = new SplitContainer(List.of(
            new FakePaletteBox(
                host,
                "box-a",
                List.of(
                    new FakePalette(new FakePaletteId("turboism:turboism.core:other-a1"), "A1"),
                    new FakePalette(new FakePaletteId("turboism:turboism.core:other-a2"), "A2")
                )
            ),
            new FakePaletteBox(
                host,
                "box-b",
                List.of(new FakePalette(new FakePaletteId("turboism:turboism.core:other-b"), "B"))
            )
        ));
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        )));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        assertEquals(List.of(
            "add:" + paletteId,
            "add-tab:box-b:" + paletteId,
            "set-selected:box-b:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));

        // Both boxes now hold 2 tabs: re-docking breaks the tie toward box-a, the
        // first box in traversal order.
        host.log.clear();
        runOnEdt(handleRef.get()::activate);
        assertEquals(List.of(
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));
    }

    @Test
    void showPrefersEmptyPaletteBoxOverLoadedOnesInNestedTrees() throws Exception {
        final InstallHost host = installHost();
        // The empty box sits inside a nested split: the traversal must descend into
        // split branches to find it and prefer it over the loaded boxes.
        host.workspaceTree = new SplitContainer(List.of(
            new FakePaletteBox(
                host,
                "box-a",
                List.of(new FakePalette(new FakePaletteId("turboism:turboism.core:other-a"), "A"))
            ),
            new SplitContainer(List.of(
                new FakePaletteBox(host, "box-b"),
                new FakePaletteBox(
                    host,
                    "box-c",
                    List.of(new FakePalette(new FakePaletteId("turboism:turboism.core:other-c"), "C"))
                )
            ))
        ));
        runOnEdt(() -> host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        ));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        assertEquals(List.of(
            "add:" + paletteId,
            "add-tab:box-b:" + paletteId,
            "set-selected:box-b:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));
    }

    @Test
    void showWithoutAnyPaletteBoxRunsNativeNewColumnPath() throws Exception {
        final InstallHost host = installHost();
        // The workspace root exists but the split tree holds no palette box at all,
        // so the native new-column path (workspace activate + setPaletteVisible)
        // runs unchanged.
        host.workspaceTree = new SplitContainer(List.of(new SplitContainer(List.of())));
        runOnEdt(() -> host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        ));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        assertEquals(List.of(
            "add:" + paletteId,
            "activate:" + paletteId,
            "set-visible:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("add-tab:"));
    }

    @Test
    void showSkipsNonSplitContentComponentsWhileFindingSparsestBox() throws Exception {
        final InstallHost host = installHost();
        // A CPMContentsBox-style component (neither palette box nor split container)
        // must be skipped, not expanded; the box with 2 tabs is still selected.
        host.workspaceTree = new SplitContainer(List.of(
            new ContentsBox(),
            new FakePaletteBox(
                host,
                "box-a",
                List.of(
                    new FakePalette(new FakePaletteId("turboism:turboism.core:other-a1"), "A1"),
                    new FakePalette(new FakePaletteId("turboism:turboism.core:other-a2"), "A2")
                )
            )
        ));
        final AtomicReference<EmbeddedPanelHostOperations.PanelHandle> handleRef = new AtomicReference<>();
        runOnEdt(() -> handleRef.set(host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        )));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        assertEquals(List.of(
            "add:" + paletteId,
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("set-visible:" + paletteId + ":true"));

        // The activation path traverses the same tree with the ContentsBox present.
        host.log.clear();
        runOnEdt(handleRef.get()::activate);
        assertEquals(List.of(
            "add-tab:box-a:" + paletteId,
            "set-selected:box-a:" + paletteId,
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
    }

    @Test
    void showWithOnlyContentComponentsRunsNativeNewColumnPath() throws Exception {
        final InstallHost host = installHost();
        // A workspace whose tree holds only a non-split content component has no
        // palette box: the native new-column path runs unchanged.
        host.workspaceTree = new SplitContainer(List.of(new ContentsBox()));
        runOnEdt(() -> host.operations.addPanel(
            new EmbeddedPanelContributionDescriptor(
                "turboism.core",
                "test-pane",
                "Test Pane",
                "window",
                100,
                new dev.turboism.sdk.ui.PanelView.Text("content")
            , false),
            (actionId, event) -> { }
        ));
        final FakePaletteId paletteId = host.paletteId("test-pane");

        assertEquals(List.of(
            "add:" + paletteId,
            "activate:" + paletteId,
            "set-visible:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "update-window-menu",
            "check:" + paletteId + ":true",
            "repaint"
        ), host.log);
        assertFalse(host.log.contains("add-tab:"));
    }

    @Test
    void treeTraversalSkipsNonSplitComponentsDuringCleanupAndTreeChecks() {
        final PaletteBox live = new PaletteBox(1);
        final PaletteBox empty = new PaletteBox(0);
        final ContentsBox contents = new ContentsBox();
        final SplitContainer root = new SplitContainer(List.of(contents, live, empty));

        final VerifiedEmbeddedPanelHostOperations operations = treeOperations();
        operations.pruneEmptyBoxes(root);

        // The ContentsBox is neither removed nor expanded; the empty box is pruned.
        assertEquals(List.of(contents, live), root.contents());

        final Workspace workspace = new Workspace(new RootContainer(root));
        assertTrue(operations.isDockBoxInWorkspaceTree(workspace, live));
        assertFalse(operations.isDockBoxInWorkspaceTree(workspace, new PaletteBox(1)));
        assertTrue(operations.isDockBoxInWorkspaceTree(workspace, contents));
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
                , false),
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
        private FakePaletteBox firstPaletteBox;
        private Component workspaceTree;
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
                method(
                    "cubism.ui-panel.workspace.first-palette-box",
                    FakeWorkspace.class,
                    "getFirstPaletteBox",
                    descriptor(FakePaletteBox.class)
                ),
                StaticSelector.classSelector(
                    "cubism.ui-panel.palette-box.class",
                    internal(FakePaletteBox.class)
                ),
                method(
                    "cubism.ui-panel.workspace.root-container",
                    FakeWorkspace.class,
                    "getRootContainer",
                    descriptor(RootContainer.class)
                ),
                method(
                    "cubism.ui-panel.root.component",
                    RootContainer.class,
                    "component",
                    descriptor(Component.class)
                ),
                StaticSelector.classSelector(
                    "cubism.ui-panel.split.class", internal(SplitContainer.class)
                ),
                method(
                    "cubism.ui-panel.split.contents",
                    SplitContainer.class,
                    "contents",
                    "()Ljava/util/List;"
                ),
                method(
                    "cubism.ui-panel.palette-box.palettes",
                    FakePaletteBox.class,
                    "getPalettes",
                    "()Ljava/util/List;"
                ),
                method(
                    "cubism.ui-panel.palette-box.add-tab",
                    FakePaletteBox.class,
                    "addTab",
                    "(L" + internal(FakePalette.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-box.set-selected",
                    FakePaletteBox.class,
                    "setSelected",
                    "(L" + internal(FakePaletteId.class) + ";)V"
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
                ),
                method(
                    "cubism.ui-panel.palette-manager.main-frame-window",
                    FakePaletteManager.class,
                    "getMainFrameWindow",
                    descriptor(FakeFrame.class)
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.palette-box.create",
                    internal(FakePaletteBox.class),
                    "(L" + internal(FakePaletteManager.class) + ";[L" + internal(FakePalette.class) + ";)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                StaticSelector.constructor(
                    "cubism.ui-panel.palette-frame.create",
                    internal(FakePaletteFrame.class),
                    "(L" + internal(FakePaletteManager.class) + ";L" + internal(FakeFrame.class) + ";)V",
                    StaticSelector.ACCESS_PUBLIC
                ),
                method(
                    "cubism.ui-panel.workspace.add-palette-frame",
                    FakeWorkspace.class,
                    "addPaletteFrame",
                    "(L" + internal(FakePaletteFrame.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-box.remove-tab",
                    FakePaletteBox.class,
                    "removeTab",
                    "(L" + internal(FakePalette.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-frame.root",
                    FakePaletteFrame.class,
                    "getRoot",
                    descriptor(FakeRootContainer.class)
                ),
                method(
                    "cubism.ui-panel.root.set-component",
                    FakeRootContainer.class,
                    "setComponent",
                    "(L" + internal(FakePaletteBox.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.remove-update",
                    FakePaletteManager.class,
                    "removeUpdate",
                    "(L" + internal(FakeWorkspace.class) + ";L" + internal(FakePaletteBox.class)
                        + ";[L" + internal(FakePalette.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.verify-cleanup",
                    FakePaletteManager.class,
                    "verifyCleanup",
                    "()V"
                ),
                method(
                    "cubism.ui-panel.palette-manager.fire-state",
                    FakePaletteManager.class,
                    "fireState",
                    "(L" + internal(FakePalette.class) + ";)V"
                ),
                method(
                    "cubism.ui-panel.palette-frame.window",
                    FakePaletteFrame.class,
                    "getWindow",
                    descriptor(FakeFrame.class)
                ),
                method(
                    "cubism.ui-panel.window.set-visible",
                    FakeFrame.class,
                    "setVisible",
                    "(Z)V"
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

        /** The native Swing container the palette was given exactly once. */
        private FakeSwingContainer nativeContainer(final FakePaletteId paletteId) {
            return (FakeSwingContainer) paletteManager.getPalette(paletteId).getPanelWidget();
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

        public void setVisible(final boolean value) {
            host.log.add("window-visible:" + value);
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

        public FakeFrame getMainFrameWindow() {
            host.log.add("main-frame-window");
            return host.frame;
        }

        public void removeUpdate(
            final FakeWorkspace workspace,
            final FakePaletteBox sourceBox,
            final FakePalette[] palettes
        ) {
            host.log.add("remove-update");
        }

        public void verifyCleanup() {
            host.log.add("verify-cleanup");
        }

        public void fireState(final FakePalette palette) {
            host.log.add("fire-state");
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
            final FakePaletteBox box = findPaletteBox(
                host.workspaceTree != null ? host.workspaceTree : host.firstPaletteBox,
                palette
            );
            host.log.add("box-for:" + palette.getPaletteId() + ":"
                + (box == null ? "null" : box.label));
            return box;
        }

        private static FakePaletteBox findPaletteBox(final Component node, final FakePalette palette) {
            if (node instanceof FakePaletteBox box) {
                return box.getPalettes().contains(palette) ? box : null;
            }
            if (node instanceof SplitContainer split) {
                for (Component child : split.contents()) {
                    final FakePaletteBox found = findPaletteBox(child, palette);
                    if (found != null) {
                        return found;
                    }
                }
            }
            return null;
        }

        public void addPaletteFrame(final FakePaletteFrame frame) {
            host.log.add("add-palette-frame");
        }

        public FakePaletteBox getFirstPaletteBox() {
            return host.firstPaletteBox;
        }

        public RootContainer getRootContainer() {
            // A single-box tree mirrors the r3 first-palette-box fixture; workspaceTree
            // overrides it with an arbitrary split tree for multi-box tests.
            return new RootContainer(
                host.workspaceTree != null ? host.workspaceTree : host.firstPaletteBox
            );
        }
    }

    public static final class FakePaletteFrame {
        private final InstallHost host;
        private final FakeFrame window;
        private final FakeRootContainer root;

        public FakePaletteFrame(final FakePaletteManager manager, final FakeFrame ownerWindow) {
            this.host = manager.host;
            this.window = ownerWindow;
            this.root = new FakeRootContainer(host);
            host.log.add("palette-frame-create");
        }

        public FakeRootContainer getRoot() {
            return root;
        }

        public FakeFrame getWindow() {
            return window;
        }
    }

    public static final class FakeRootContainer {
        private final InstallHost host;

        public FakeRootContainer(final InstallHost host) {
            this.host = host;
        }

        public void setComponent(final FakePaletteBox component) {
            host.log.add("root-set-component");
        }
    }

    public static final class FakePaletteBox implements Component {
        private final InstallHost host;
        private final String label;
        private final List<FakePalette> palettes = new ArrayList<>();

        public FakePaletteBox(final InstallHost host, final String label) {
            this(host, label, List.of());
        }

        public FakePaletteBox(
            final InstallHost host,
            final String label,
            final List<FakePalette> palettes
        ) {
            this.host = host;
            this.label = label;
            this.palettes.addAll(palettes);
        }

        /** Floating palette box created by the verified palette-box.create operation. */
        public FakePaletteBox(final FakePaletteManager manager, final FakePalette[] palettes) {
            this.host = manager.host;
            this.label = "float";
            this.palettes.addAll(java.util.Arrays.asList(palettes));
            host.log.add("palette-box-create");
        }

        public void removeTab(final FakePalette palette) {
            palettes.remove(palette);
            host.log.add("remove-tab:" + label + ":" + palette.getPaletteId());
        }

        public void addTab(final FakePalette palette) {
            palettes.add(palette);
            host.log.add("add-tab:" + label + ":" + palette.getPaletteId());
            // Attaching the tab to a workspace box shows the palette in the dock, so the
            // derived updateWindowMenuItem check state is selected (matching the native
            // visibility derivation the reuse path relies on).
            host.dockWrapper.visible.add(palette);
        }

        public void setSelected(final FakePaletteId paletteId) {
            host.log.add("set-selected:" + label + ":" + paletteId);
        }

        public List<FakePalette> getPalettes() {
            return palettes;
        }

        @Override
        public int paletteCount() {
            return palettes.size();
        }
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
        private FakeWidget panelWidget;

        public FakePalette(final FakePaletteId paletteId, final String title) {
            this.paletteId = paletteId;
        }

        public FakePaletteId getPaletteId() {
            return paletteId;
        }

        public void setPanel(final FakeWidget widget, final int width, final int height) {
            this.panelWidget = widget;
        }

        public FakeWidget getPanelWidget() {
            return panelWidget;
        }
    }

    public static final class FakeSwingContainer extends FakeWidget {
        private final JComponent component;

        public FakeSwingContainer(final JComponent component) {
            this.component = component;
        }

        /** The JComponent handed to the native palette: the stable content wrapper. */
        public JComponent component() {
            return component;
        }
    }

}
