package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.mesh.MeshEdgeKind;
import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import dev.turboism.sdk.cubism.mesh.MeshPointPosition;
import dev.turboism.sdk.cubism.mesh.MeshPointRef;
import dev.turboism.sdk.cubism.mesh.MeshSnapshot;
import dev.turboism.sdk.cubism.model.Drawable;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Set;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowsMeshEditValidationProbeTest {

    @TempDir
    Path temporary;

    @AfterEach
    void clearFixture() {
        System.clearProperty("turboism.validation.fixture");
    }

    @Test
    void addedPointDiscoveryRequiresOneNewHostAssignedIdAtTheRequestedPosition() {
        final MeshSnapshot before = mesh(List.of(point(2, 0.0F, 0.0F), point(7, 1.0F, 1.0F)), List.of(edge(2, 7)));
        final MeshPointPosition requested = new MeshPointPosition(4.0F, 5.0F);
        final MeshSnapshot after =
                mesh(List.of(point(2, 0.0F, 0.0F), point(7, 1.0F, 1.0F), point(19, 4.0F, 5.0F)), List.of(edge(2, 7)));

        assertEquals(
                point(19, 4.0F, 5.0F), WindowsMeshEditValidationProbe.discoverAddedPoint(before, after, requested));
        assertThrows(
                IllegalStateException.class,
                () -> WindowsMeshEditValidationProbe.discoverAddedPoint(
                        before,
                        mesh(
                                List.of(
                                        point(2, 0.0F, 0.0F), point(7, 1.0F, 1.0F),
                                        point(19, 4.0F, 5.0F), point(20, 6.0F, 7.0F)),
                                List.of(edge(2, 7))),
                        requested));
    }

    @Test
    void deterministicMutationHelpersDoNotAssumeContiguousIds() {
        final MeshSnapshot snapshot = mesh(
                List.of(point(41, -2.0F, -1.0F), point(3, 2.0F, 4.0F), point(88, 0.0F, 1.0F)),
                List.of(edge(41, 88), edge(3, 88)));

        final MeshPointPosition added = WindowsMeshEditValidationProbe.distinctPosition(snapshot);
        assertTrue(Float.isFinite(added.x()));
        assertTrue(Float.isFinite(added.y()));
        assertTrue(snapshot.points().stream().noneMatch(point -> point.x() == added.x() && point.y() == added.y()));
        assertEquals(
                41,
                WindowsMeshEditValidationProbe.pointWithConnectedEdge(snapshot).id());
        assertEquals(List.of(edge(41, 88)), WindowsMeshEditValidationProbe.connectedEdges(snapshot, 41));
        assertEquals(
                3,
                WindowsMeshEditValidationProbe.chooseEdgePartner(snapshot, 100).id());
        assertEquals(3, WindowsMeshEditValidationProbe.pointsById(snapshot).size());
    }

    @Test
    void duplicatePointIdsFailClosed() {
        final MeshSnapshot duplicate = mesh(List.of(point(4, 0.0F, 0.0F), point(4, 1.0F, 1.0F)), List.of());

        assertThrows(IllegalArgumentException.class, () -> WindowsMeshEditValidationProbe.pointsById(duplicate));
    }

    @Test
    void ordinaryOracleRetainsBothOwnersAtTheSameLocalIndexAndCapsuleBoundary() {
        final var points = List.of(
                new WindowsModelingSelectionBrushProbe.NativePoint("mesh:MeshPointRef:0", 50, 0),
                new WindowsModelingSelectionBrushProbe.NativePoint("warp:WarpPointRef:0", 50, 32),
                new WindowsModelingSelectionBrushProbe.NativePoint("warp:WarpPointRef:1", 50, 32.25f),
                new WindowsModelingSelectionBrushProbe.NativePoint("mesh:MeshPointRef:1", -32, 0),
                new WindowsModelingSelectionBrushProbe.NativePoint("mesh:MeshPointRef:2", 132, 0));
        assertEquals(
                Set.of("mesh:MeshPointRef:0", "warp:WarpPointRef:0", "mesh:MeshPointRef:1", "mesh:MeshPointRef:2"),
                WindowsModelingSelectionBrushProbe.hits(points, List.of(new Point(0, 0), new Point(100, 0)), 32));
    }

    @Test
    void nativeLassoOracleDetectsTheObservedTranslatedDragFootprint() {
        final var points = List.of(
                new WindowsModelingSelectionBrushProbe.NativePoint("mesh:MeshPointRef:0", 200.08083f, 408.2952f));
        for (int extent : new int[] {16, 64}) {
            final var requested = List.of(
                    new Point(200 - extent, 408 - extent),
                    new Point(200 + extent, 408 - extent),
                    new Point(200 + extent, 408 + extent),
                    new Point(200 - extent, 408 + extent),
                    new Point(200 - extent, 408 - extent));
            final var delivered = new java.util.ArrayList<Point>();
            delivered.add(requested.get(0));
            for (int i = 1; i < requested.size(); i++) {
                final Point from = requested.get(i - 1), to = requested.get(i);
                for (int step = 1; step <= 2 * extent / 4; step++) {
                    final Point position = new Point(
                            from.x + Integer.signum(to.x - from.x) * 4 * step,
                            from.y + Integer.signum(to.y - from.y) * 4 * step);
                    delivered.add(new Point(position.x, position.y - 20));
                    delivered.add(position);
                }
            }
            assertEquals(
                    Set.of("mesh:MeshPointRef:0"), WindowsModelingSelectionBrushProbe.lassoHits(points, requested));
            assertEquals(
                    extent == 16 ? Set.of() : Set.of("mesh:MeshPointRef:0"),
                    WindowsModelingSelectionBrushProbe.lassoHits(points, delivered));
        }
    }

    @Test
    void ordinaryOracleUsesControlPrecedenceAndLeavesZeroHitSelectionIntact() {
        final Set<String> before = Set.of("mesh:MeshPointRef:0", "warp:WarpPointRef:0");
        final Set<String> hit = Set.of("warp:WarpPointRef:0", "warp:WarpPointRef:1");
        assertEquals(hit, WindowsModelingSelectionBrushProbe.apply(before, hit, 0));
        assertEquals(
                Set.of("mesh:MeshPointRef:0", "warp:WarpPointRef:0", "warp:WarpPointRef:1"),
                WindowsModelingSelectionBrushProbe.apply(before, hit, InputEvent.SHIFT_DOWN_MASK));
        assertEquals(
                Set.of("mesh:MeshPointRef:0"),
                WindowsModelingSelectionBrushProbe.apply(
                        before, hit, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK));
        assertEquals(before, WindowsModelingSelectionBrushProbe.apply(before, Set.of(), 0));
    }

    @Test
    void uniqueSelectionTargetUsesDisplayNameButRetainsExactId() {
        final var target = WindowsMeshEditValidationProbe.uniqueSelectionTarget(
                List.of(drawable("ArtMesh9", "Face"), drawable("ArtMesh4", "Body")));

        assertEquals("ArtMesh4", target.id());
        assertEquals("Body", target.displayName());
    }

    @Test
    void uniqueSelectionTargetFailsClosedForBlankOrAmbiguousNames() {
        assertThrows(
                IllegalStateException.class,
                () -> WindowsMeshEditValidationProbe.uniqueSelectionTarget(
                        List.of(drawable("ArtMesh1", ""), drawable("ArtMesh2", "Body"), drawable("ArtMesh3", "Body"))));
    }

    @Test
    void treePathLookupRequiresExactDisplayTextAndReportsAllMatches() {
        final DefaultMutableTreeNode root = new DefaultMutableTreeNode("Root");
        root.add(new DefaultMutableTreeNode("Body"));
        root.add(new DefaultMutableTreeNode("Body Copy"));
        final JTree tree = new JTree(new DefaultTreeModel(root));

        assertEquals(
                1, WindowsMeshEditValidationProbe.findTreePaths(tree, "Body").size());
        root.add(new DefaultMutableTreeNode("Body"));
        ((DefaultTreeModel) tree.getModel()).reload();
        assertEquals(
                2, WindowsMeshEditValidationProbe.findTreePaths(tree, "Body").size());
        assertTrue(
                WindowsMeshEditValidationProbe.findTreePaths(tree, "ArtMesh4").isEmpty());
    }

    @Test
    void partsClickUsesTheTreeNameColumnAndRejectsIconOnlyTables() {
        final javax.swing.JTable table = new javax.swing.JTable(
                new Object[][] {{"visible", "Body", "locked"}}, new Object[] {"Visibility", "Name", "Lock"});
        final RendererTree tree = new RendererTree();
        table.getColumnModel().getColumn(1).setCellRenderer(tree);

        assertEquals(1, WindowsMeshEditValidationProbe.renderedTreeColumn(table, tree, 0));
        table.getColumnModel().getColumn(1).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer());
        assertThrows(
                IllegalStateException.class, () -> WindowsMeshEditValidationProbe.renderedTreeColumn(table, tree, 0));
    }

    private static final class RendererTree extends JTree implements javax.swing.table.TableCellRenderer {
        @Override
        public java.awt.Component getTableCellRendererComponent(
                final javax.swing.JTable table,
                final Object value,
                final boolean selected,
                final boolean focus,
                final int row,
                final int column) {
            return this;
        }
    }

    @Test
    void exactMutationPredicatesRejectCollateralChanges() {
        final MeshSnapshot original =
                mesh(List.of(point(2, 0.0F, 0.0F), point(7, 1.0F, 1.0F), point(9, 2.0F, 2.0F)), List.of(edge(2, 7)));
        final MeshPointPosition requested = new MeshPointPosition(4.0F, 5.0F);
        final MeshSnapshot added = mesh(
                List.of(point(2, 0.0F, 0.0F), point(7, 1.0F, 1.0F), point(9, 2.0F, 2.0F), point(19, 4.0F, 5.0F)),
                List.of(edge(2, 7)));
        assertTrue(WindowsMeshEditValidationProbe.isExactPointAddition(original, added, requested));
        assertFalse(WindowsMeshEditValidationProbe.isExactPointAddition(
                original,
                mesh(
                        List.of(
                                point(2, 8.0F, 8.0F),
                                point(7, 1.0F, 1.0F),
                                point(9, 2.0F, 2.0F),
                                point(19, 4.0F, 5.0F)),
                        List.of(edge(2, 7))),
                requested));

        final MeshPointRef moved = point(2, 6.0F, 7.0F);
        final MeshSnapshot afterMove =
                mesh(List.of(moved, point(7, 1.0F, 1.0F), point(9, 2.0F, 2.0F)), List.of(edge(2, 7)));
        assertTrue(WindowsMeshEditValidationProbe.isExactPointMove(original, afterMove, moved));
        assertFalse(WindowsMeshEditValidationProbe.isExactPointMove(
                original,
                mesh(List.of(moved, point(7, 9.0F, 9.0F), point(9, 2.0F, 2.0F)), List.of(edge(2, 7))),
                moved));

        final MeshEdgeRef addedEdge = edge(7, 9);
        final MeshSnapshot afterEdgeAdd = mesh(original.points(), List.of(edge(2, 7), addedEdge));
        assertTrue(WindowsMeshEditValidationProbe.isExactEdgeAddition(original, afterEdgeAdd, addedEdge));
        assertFalse(WindowsMeshEditValidationProbe.isExactEdgeAddition(
                original,
                mesh(
                        List.of(point(2, 3.0F, 3.0F), point(7, 1.0F, 1.0F), point(9, 2.0F, 2.0F)),
                        List.of(edge(2, 7), addedEdge)),
                addedEdge));

        assertTrue(WindowsMeshEditValidationProbe.isExactEdgeDeletion(afterEdgeAdd, original, addedEdge));
        assertFalse(WindowsMeshEditValidationProbe.isExactEdgeDeletion(
                afterEdgeAdd,
                mesh(List.of(point(2, 3.0F, 3.0F), point(7, 1.0F, 1.0F), point(9, 2.0F, 2.0F)), List.of(edge(2, 7))),
                addedEdge));

        final MeshSnapshot beforeDelete = mesh(original.points(), List.of(edge(2, 7), edge(7, 9)));
        final MeshSnapshot afterDelete = mesh(List.of(point(2, 0.0F, 0.0F), point(9, 2.0F, 2.0F)), List.of());
        assertTrue(WindowsMeshEditValidationProbe.isExactPointDeletion(beforeDelete, afterDelete, 7));
        assertFalse(WindowsMeshEditValidationProbe.isExactPointDeletion(
                beforeDelete, mesh(List.of(point(2, 8.0F, 8.0F), point(9, 2.0F, 2.0F)), List.of()), 7));
    }

    @Test
    void saveConfirmationDetectsStableCommittedWriteAndFailsClosedWithoutOne() throws Exception {
        final Path fixture = temporary.resolve("fixture.cmo3");
        Files.writeString(fixture, "before");
        final FileTime beforeMtime = Files.getLastModifiedTime(fixture);
        final long beforeSize = Files.size(fixture);
        final Thread writer = new Thread(() -> {
            try {
                Thread.sleep(1_200L);
                Files.writeString(fixture, "after-save-content");
            } catch (Exception failure) {
                throw new RuntimeException(failure);
            }
        });
        writer.start();

        final WindowsMeshEditValidationProbe.SaveConfirmation confirmed =
                WindowsMeshEditValidationProbe.awaitSaveConfirmation(fixture, beforeMtime, beforeSize, 10_000L, 50L);
        writer.join(15_000L);
        assertTrue(confirmed.confirmed());

        final WindowsMeshEditValidationProbe.SaveConfirmation unchanged =
                WindowsMeshEditValidationProbe.awaitSaveConfirmation(
                        fixture, Files.getLastModifiedTime(fixture), Files.size(fixture), 300L, 25L);
        assertFalse(unchanged.confirmed());
    }

    @Test
    void fixturePropertyIsMandatory() {
        assertThrows(IllegalArgumentException.class, WindowsMeshEditValidationProbe::fixturePath);
        System.setProperty("turboism.validation.fixture", temporary.toString());
        assertEquals(temporary, WindowsMeshEditValidationProbe.fixturePath());
    }

    @Test
    void robotOriginUsesTheReceivedViewCoordinatesInsteadOfFrameDecorations() {
        assertEquals(
                new java.awt.Point(1388, 160),
                WindowsMeshEditValidationProbe.robotOriginFromMovement(
                        new java.awt.Point(1653, 609), new java.awt.Point(265, 449)));
    }

    @Test
    void uniformRobotScreenshotsCannotSatisfyPreviewEvidence() {
        final var image = new java.awt.image.BufferedImage(4, 4, java.awt.image.BufferedImage.TYPE_INT_RGB);
        assertFalse(WindowsMeshEditValidationProbe.imageHasVariation(image));
        image.setRGB(2, 2, 0x191996);
        assertTrue(WindowsMeshEditValidationProbe.imageHasVariation(image));
    }

    private static MeshSnapshot mesh(final List<MeshPointRef> points, final List<MeshEdgeRef> edges) {
        return new MeshSnapshot(points, edges);
    }

    private static MeshPointRef point(final int id, final float x, final float y) {
        return new MeshPointRef(id, x, y);
    }

    private static Drawable drawable(final String id, final String name) {
        return (Drawable) Proxy.newProxyInstance(
                Drawable.class.getClassLoader(),
                new Class<?>[] {Drawable.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "id" -> new ArtMeshId(id);
                    case "name" -> name;
                    case "toString" -> id + ":" + name;
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static MeshEdgeRef edge(final int first, final int second) {
        return new MeshEdgeRef(first, second, MeshEdgeKind.INNER);
    }
}
