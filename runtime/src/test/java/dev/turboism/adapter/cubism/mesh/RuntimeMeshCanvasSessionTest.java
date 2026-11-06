package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class RuntimeMeshCanvasSessionTest {
    private static final List<Point2> TRIANGLE =
            List.of(new Point2(1.0f, 2.0f), new Point2(3.0f, 4.0f), new Point2(5.0f, 6.0f));
    private static final int[] TRIANGLE_EDGES = {0, 1, 1, 2, 2, 0};

    @Test
    void previewProjectsTheAnchorAndVerticesThroughTheHostProjection() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            try {
                session.install();
                final MeshCanvasOverlayPanel overlay = overlay(session);

                session.showPreview(TRIANGLE, TRIANGLE_EDGES);

                assertTrue(overlay.previewVisible());
                assertEquals(new Point2(5000.0f, 6000.0f), overlay.centerForTests());
                assertEquals(
                        List.of(new Point2(100.0f, 200.0f), new Point2(300.0f, 400.0f), new Point2(500.0f, 600.0f)),
                        overlay.verticesForTests());
                assertEquals(6, overlay.edgePairsForTests().length);
                assertEquals(1, host.projectionCalls);
            } finally {
                close(session);
            }
        });
    }

    @Test
    void draggingTheAnchorUnprojectsItNotifiesListenersAndRefreshesThePreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            try {
                session.install();
                assertEquals(new Point2(50.0f, 60.0f), session.anchor());
                final List<Point2> observed = new ArrayList<>();
                session.observeAnchorMoved(observed::add);
                session.showPreview(List.of(), new int[0]);
                final MeshCanvasOverlayPanel overlay = overlay(session);

                overlay.dispatchEvent(new MouseEvent(
                        overlay,
                        MouseEvent.MOUSE_PRESSED,
                        1L,
                        InputEvent.BUTTON1_DOWN_MASK,
                        12,
                        34,
                        1,
                        false,
                        MouseEvent.BUTTON1));

                assertEquals(List.of(new Point2(120.0f, 340.0f)), observed);
                assertEquals(new Point2(120.0f, 340.0f), session.anchor());
                assertEquals(new Point2(12000.0f, 34000.0f), overlay.centerForTests());
            } finally {
                close(session);
            }
        });
    }

    @Test
    void commitFlattensTheLayoutSelectsTheAddedPointsAndClearsThePreview() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            try {
                session.install();
                session.showPreview(TRIANGLE, TRIANGLE_EDGES);

                final List<Integer> added = session.commit(TRIANGLE, TRIANGLE_EDGES);

                assertEquals(List.of(10, 11, 12), added);
                assertEquals(List.of(1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f), boxed(host.committedPoints));
                assertEquals(6, host.committedEdges.length);
                assertEquals(List.of(10, 11, 12), host.selected);
                assertFalse(overlay(session).previewVisible());
            } finally {
                close(session);
            }
        });
    }

    @Test
    void everyOperationalEntryPointFailsClosedAfterTheLeaseGoesStale() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            try {
                session.install();
                host.stale = true;

                assertThrows(IllegalStateException.class, session::anchor);
                assertThrows(IllegalStateException.class, () -> session.showPreview(List.of(), new int[0]));
                assertThrows(IllegalStateException.class, () -> session.clearPreview());
                assertThrows(IllegalStateException.class, () -> session.commit(TRIANGLE, TRIANGLE_EDGES));
                assertThrows(IllegalStateException.class, () -> session.observeAnchorMoved(anchor -> {}));
            } finally {
                host.stale = false;
                close(session);
            }
        });
    }

    @Test
    void malformedEdgePairsAreRefusedBeforeAnyHostWrite() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            try {
                session.install();
                final List<Point2> vertices = List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f));

                assertThrows(IllegalArgumentException.class, () -> session.showPreview(vertices, new int[] {0, 1, 1}));
                assertThrows(IllegalArgumentException.class, () -> session.commit(vertices, new int[] {0, 2}));
                assertNull(host.committedPoints);
            } finally {
                close(session);
            }
        });
    }

    @Test
    void closingIsIdempotentAndDetachesTheOverlay() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final RecordingHost host = new RecordingHost();
            final RuntimeMeshCanvasSession session = new RuntimeMeshCanvasSession(host);
            session.install();
            final JComponent overlay = session.overlayForTests();
            assertSame(host.canvas, overlay.getParent());

            session.close();
            session.close();

            assertNull(overlay.getParent());
            assertEquals(0, host.canvas.getComponentCount());
        });
    }

    @Test
    void requireOwnerFailsClosedWithoutTheDeclaredCapability() {
        final List<String> checked = new ArrayList<>();
        final PermissionChecker permissions = (permissionId, operation) -> checked.add(permissionId);

        final IllegalStateException refusal = assertThrows(
                IllegalStateException.class, () -> RuntimeMeshCanvasSession.requireOwner(false, permissions));
        assertTrue(
                refusal.getMessage().contains(RuntimeMeshToolRegistry.POINT_AND_EDGE_LAYOUT_CAPABILITY),
                refusal.getMessage());
        assertEquals(List.of(), checked, "a missing capability must be refused before any permission check");
    }

    @Test
    void requireOwnerChecksReadAndWriteForTheCapabilityDeclaringPlugin() {
        final List<String> checked = new ArrayList<>();
        final PermissionChecker permissions = (permissionId, operation) -> checked.add(permissionId);

        RuntimeMeshCanvasSession.requireOwner(true, permissions);

        assertEquals(
                List.of(PermissionIds.TURBOISM_CUBISM_MODEL_READ, PermissionIds.TURBOISM_CUBISM_MODEL_WRITE), checked);
    }

    @Test
    void requireOwnerFailsClosedWhenTheOwningPluginLacksWriteAccess() {
        final PermissionChecker readOnly = (permissionId, operation) -> {
            if (!PermissionIds.TURBOISM_CUBISM_MODEL_READ.equals(permissionId)) {
                throw new CubismPermissionException("denied " + permissionId);
            }
        };

        assertThrows(CubismPermissionException.class, () -> RuntimeMeshCanvasSession.requireOwner(true, readOnly));
    }

    private static MeshCanvasOverlayPanel overlay(final RuntimeMeshCanvasSession session) {
        return (MeshCanvasOverlayPanel) session.overlayForTests();
    }

    private static void close(final RuntimeMeshCanvasSession session) {
        session.close();
    }

    private static List<Float> boxed(final float[] values) {
        final List<Float> result = new ArrayList<>(values.length);
        for (float value : values) {
            result.add(value);
        }
        return result;
    }

    private static final class RecordingHost implements RuntimeMeshCanvasSession.Host {
        final JPanel canvas = new JPanel(null);
        float[] committedPoints;
        int[] committedEdges;
        List<Integer> selected = List.of();
        int projectionCalls;
        boolean stale;

        RecordingHost() {
            canvas.setSize(600, 500);
        }

        @Override
        public JComponent component() {
            return canvas;
        }

        @Override
        public Point2 defaultAnchor() {
            return new Point2(50.0f, 60.0f);
        }

        @Override
        public Point2 unprojectToMesh(final float componentX, final float componentY) {
            return new Point2(componentX * 10.0f, componentY * 10.0f);
        }

        @Override
        public List<Point2> projectPositions(final float[] meshPositions) {
            projectionCalls++;
            final List<Point2> result = new ArrayList<>(meshPositions.length / 2);
            for (int index = 0; index < meshPositions.length; index += 2) {
                result.add(new Point2(meshPositions[index] * 100.0f, meshPositions[index + 1] * 100.0f));
            }
            return result;
        }

        @Override
        public List<Integer> commitPlan(final float[] meshPositions, final int[] edgePairs) {
            committedPoints = meshPositions.clone();
            committedEdges = edgePairs.clone();
            final List<Integer> ids = new ArrayList<>(meshPositions.length / 2);
            for (int index = 0; index < meshPositions.length / 2; index++) {
                ids.add(10 + index);
            }
            return ids;
        }

        @Override
        public void selectVertices(final List<Integer> indices) {
            selected = List.copyOf(indices);
        }

        @Override
        public boolean revalidate() {
            return !stale;
        }

        @Override
        public void deactivate() {}
    }
}
