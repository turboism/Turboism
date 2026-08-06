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
import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
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
}
