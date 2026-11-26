package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class NativeMeshMirrorGeometryOpsTest {

    @Test
    void geometricLayoutPreservesExistingEdgesAndSkipsHostRejectedEdges() throws Exception {
        final Mesh mesh = new Mesh(false);
        final List<Edge> original = List.copyOf(mesh.edges);

        final List<Integer> added = addLayout(mesh);

        assertEquals(List.of(3, 4, 5), added);
        assertEquals(List.of(original.get(0), original.get(1), new Edge(4, 5, EdgeType.NORMAL)), mesh.edges);
    }

    @Test
    void geometricLayoutRetainsAcceptedEdgesWhenHostTriangulationExpands() throws Exception {
        final Mesh mesh = new Mesh(true);
        final List<Edge> original = List.copyOf(mesh.edges);

        assertEquals(List.of(3, 4, 5), addLayout(mesh));

        assertEquals(List.of(original.get(0), original.get(1), new Edge(4, 5, EdgeType.NORMAL)), mesh.edges);
        assertArrayEquals(new int[] {0, 1, 2, 3, 4, 5}, Mesh.getGlIndices$default(mesh, null, false, 3, null));
    }

    private static List<Integer> addLayout(final Mesh mesh) throws ReflectiveOperationException {
        return NativeMeshMirrorGeometryOps.addGeometricPointsAndEdges(
                null, mesh, null, "Geometric Mesh Layout", new float[] {1, -1, 1, 1, 2, 1}, new int[] {0, 1, 1, 2});
    }

    enum PointType {
        NORMAL
    }

    enum EdgeType {
        NORMAL,
        AUTO_TRIANGULATION
    }

    record Edge(int index1, int index2, EdgeType type) {
        public int getIndex1() {
            return index1;
        }

        public int getIndex2() {
            return index2;
        }

        public EdgeType getType() {
            return type;
        }
    }

    static final class Mesh {
        private static final int FIRST_ADDED_POINT = 3;
        final List<Edge> edges =
                new ArrayList<>(List.of(new Edge(0, 1, EdgeType.NORMAL), new Edge(1, 2, EdgeType.AUTO_TRIANGULATION)));
        final boolean expandsCoverage;
        int pointCount = FIRST_ADDED_POINT;

        Mesh(final boolean expandsCoverage) {
            this.expandsCoverage = expandsCoverage;
        }

        public float[] getGlPositions() {
            return new float[] {0, 0, 3, 0, 3, 3, 1, -1, 1, 1, 2, 1};
        }

        public static int[] getGlIndices$default(
                final Mesh mesh, final Object progress, final boolean force, final int defaults, final Object unused) {
            return mesh.expandsCoverage && mesh.edges.size() > 2 ? new int[] {0, 1, 2, 3, 4, 5} : new int[] {0, 1, 2};
        }

        public List<Edge> getEdges() {
            return edges;
        }

        public void removeEdge(final int first, final int second) {
            edges.removeIf(edge -> edge.index1() == first && edge.index2() == second);
        }

        public void clearAutoTriangulation() {
            edges.removeIf(edge -> edge.type() == EdgeType.AUTO_TRIANGULATION);
        }

        public int addPoint(final float x, final float y, final PointType type, final long uid) {
            return pointCount++;
        }

        public Integer addEdge(
                final int first,
                final int second,
                final EdgeType type,
                final boolean allowDuplicate,
                final boolean checkCross) {
            if (checkCross && first == FIRST_ADDED_POINT) return null;
            edges.add(new Edge(first, second, type));
            return edges.size() - 1;
        }
    }
}
