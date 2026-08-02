package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test EDT wait interrupted", exception);
        }
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
        return new VerifiedEmbeddedPanelHostOperations(resolver);
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
}
