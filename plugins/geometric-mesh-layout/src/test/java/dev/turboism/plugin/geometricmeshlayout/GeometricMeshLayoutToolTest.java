package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshCanvasSession;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.AbstractButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class GeometricMeshLayoutToolTest {
    @Test
    void activationShowsTheDefaultPreviewAndTheConfigurationDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));

                assertNotNull(tool.dialogForTests());
                assertEquals(1, session.previews.size());
                // The default configuration is one 16-sided perimeter loop without a center vertex.
                assertEquals(16, session.previews.get(0).size());
                assertEquals(32, session.previewEdges.get(0).length);
                assertEquals(new Point2(100.0f, 140.0f), session.previews.get(0).get(0));
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void configurationChangesRepaintThePreviewWithoutCommitting() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));
                final GeometricMeshLayoutDialog dialog = tool.dialogForTests();

                final JComboBox<?> preset = (JComboBox<?>) findByName(dialog.panel(), "geometric-mesh-layout.preset");
                preset.setSelectedIndex(3); // Hexagon (6)

                assertEquals(
                        GeometricMeshShape.Preset.HEXAGON,
                        dialog.currentConfig().preset());
                assertEquals(6, dialog.currentConfig().sides());
                assertEquals(
                        6, session.previews.get(session.previews.size() - 1).size());
                assertEquals(0, session.commits.size(), "changing the configuration never writes to the mesh");
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void confirmCommitsTheLayoutClearsThePreviewAndHidesTheDialog() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));
                final GeometricMeshLayoutDialog dialog = tool.dialogForTests();

                ((AbstractButton) findByName(dialog.panel(), "geometric-mesh-layout.confirm")).doClick();

                assertEquals(1, session.commits.size());
                assertEquals(16, session.commits.get(0).size());
                assertEquals(32, session.committedEdges.get(0).length);
                assertTrue(session.previewClears.get() >= 1);
                assertFalse(dialog.isShowing());
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void aCommitDisarmsThePreviewUntilTheAnchorMovesAgain() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));
                final GeometricMeshLayoutDialog dialog = tool.dialogForTests();
                ((AbstractButton) findByName(dialog.panel(), "geometric-mesh-layout.confirm")).doClick();
                assertEquals(1, session.previews.size());

                // A configuration edit while the preview is disarmed must not repaint it.
                final JComboBox<?> preset = (JComboBox<?>) findByName(dialog.panel(), "geometric-mesh-layout.preset");
                preset.setSelectedIndex(3);
                assertEquals(1, session.previews.size(), "a commit disarms the preview");

                // Placing a new anchor re-arms it, exactly like the previous runtime brush.
                session.moveAnchor(new Point2(400.0f, 500.0f));
                assertEquals(2, session.previews.size());
                assertEquals(6, session.previews.get(1).size(), "the chosen preset survives the commit");
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void cancelClearsThePreviewAndEndsTheActivation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));
                final GeometricMeshLayoutDialog dialog = tool.dialogForTests();

                ((AbstractButton) findByName(dialog.panel(), "geometric-mesh-layout.cancel")).doClick();

                assertTrue(session.previewClears.get() >= 1);
                assertEquals(1, session.deactivations.get());
                assertFalse(dialog.isShowing());
                assertEquals(0, session.commits.size());
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void anchorMovesFollowTheDialogLabelAndRefreshThePreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                tool.activate(toolContext(session));
                final GeometricMeshLayoutDialog dialog = tool.dialogForTests();

                session.moveAnchor(new Point2(400.0f, 500.0f));

                assertEquals(new Point2(400.0f, 500.0f), session.anchor());
                assertTrue(((javax.swing.JLabel) findByName(dialog.panel(), "geometric-mesh-layout.anchor"))
                        .getText()
                        .contains("(400.0, 500.0)"));
                assertEquals(2, session.previews.size(), "the preview follows the anchor");
            } finally {
                tool.close();
            }
        });
    }

    @Test
    void deactivateAndCloseReleaseTheCanvasSession() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingSession session = new RecordingSession();
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            tool.activate(toolContext(session));
            final GeometricMeshLayoutDialog dialog = tool.dialogForTests();
            assertNotNull(dialog);

            tool.deactivate();
            assertEquals(1, session.closes.get());

            final RecordingSession second = new RecordingSession();
            tool.activate(toolContext(second));
            tool.close();
            assertEquals(1, second.closes.get());
            assertNull(tool.dialogForTests());
            assertThrows(IllegalStateException.class, () -> tool.activate(toolContext(new RecordingSession())));
        });
    }

    @Test
    void aRefusedCanvasSessionLeavesNothingOpen() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final GeometricMeshLayoutTool tool = new GeometricMeshLayoutTool(context());
            try {
                assertThrows(
                        IllegalStateException.class,
                        () -> tool.activate(new MeshToolContext() {
                            @Override
                            public MeshEditor editor() {
                                throw new AssertionError("the tool must not touch the selection editor");
                            }

                            @Override
                            public MeshBrush selectionBrush() {
                                throw new AssertionError("the tool must not request the selection brush");
                            }

                            @Override
                            public MeshCanvasSession canvasSession() {
                                throw new IllegalStateException(
                                        "mesh canvas session requires the cubism.mesh.point-and-edge-layout capability");
                            }
                        }));
                assertNull(tool.dialogForTests());
            } finally {
                tool.close();
            }
        });
    }

    private static java.awt.Component findByName(final java.awt.Container root, final String name) {
        final java.awt.Component found = search(root, name);
        if (found == null) throw new AssertionError("no dialog component named " + name);
        return found;
    }

    private static java.awt.Component search(final java.awt.Container root, final String name) {
        if (name.equals(root.getName())) return root;
        for (java.awt.Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof java.awt.Container nested) {
                final java.awt.Component found = search(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static MeshToolContext toolContext(final RecordingSession session) {
        return new MeshToolContext() {
            @Override
            public MeshEditor editor() {
                throw new AssertionError("the tool must not touch the selection editor");
            }

            @Override
            public MeshBrush selectionBrush() {
                throw new AssertionError("the tool must not request the selection brush");
            }

            @Override
            public MeshCanvasSession canvasSession() {
                return session;
            }
        };
    }

    private static PluginContext context() {
        final PluginLocalization localization = PluginCatalogs.english();
        final UiScheduler uiScheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                work.run();
                return () -> {};
            }

            @Override
            public boolean runUiTeardown(final Runnable cleanup, final Duration timeout) {
                cleanup.run();
                return true;
            }
        };
        return (PluginContext) Proxy.newProxyInstance(
                GeometricMeshLayoutToolTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "localization" -> localization;
                    case "logger" -> NoopLogger.INSTANCE;
                    case "uiScheduler" -> uiScheduler;
                    case "toString" -> "GeometricMeshLayoutToolTestContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }

    private static final class RecordingSession implements MeshCanvasSession {
        final List<List<Point2>> previews = new ArrayList<>();
        final List<int[]> previewEdges = new ArrayList<>();
        final List<List<Point2>> commits = new ArrayList<>();
        final List<int[]> committedEdges = new ArrayList<>();
        final AtomicInteger previewClears = new AtomicInteger();
        final AtomicInteger deactivations = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        private Point2 anchor = new Point2(100.0f, 200.0f);
        private Consumer<Point2> anchorListener;

        @Override
        public Point2 anchor() {
            return anchor;
        }

        @Override
        public Registration observeAnchorMoved(final Consumer<Point2> listener) {
            anchorListener = listener;
            return () -> anchorListener = null;
        }

        @Override
        public void showPreview(final List<Point2> vertices, final int[] edgePairs) {
            previews.add(List.copyOf(vertices));
            previewEdges.add(edgePairs.clone());
        }

        @Override
        public void clearPreview() {
            previewClears.incrementAndGet();
        }

        @Override
        public List<Integer> commit(final List<Point2> vertices, final int[] edgePairs) {
            commits.add(List.copyOf(vertices));
            committedEdges.add(edgePairs.clone());
            final List<Integer> ids = new ArrayList<>(vertices.size());
            for (int index = 0; index < vertices.size(); index++) {
                ids.add(index);
            }
            return ids;
        }

        @Override
        public void deactivate() {
            deactivations.incrementAndGet();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }

        void moveAnchor(final Point2 next) {
            anchor = next;
            final Consumer<Point2> listener = anchorListener;
            if (listener != null) listener.accept(next);
        }
    }
}
