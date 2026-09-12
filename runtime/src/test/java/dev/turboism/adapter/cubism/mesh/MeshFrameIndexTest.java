package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEdgeRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MeshFrameIndexTest {

    @Test
    void pointIndexKeepsTheFirstMatchPerIdLikeTheLinearScan() throws Exception {
        final Mesh mesh = new Mesh();
        mesh.points.add(new PointRef(7));
        mesh.points.add(new PointRef(7));
        mesh.points.add(new PointRef(3));

        final MeshFrameIndex index = new MeshFrameIndex(mesh);

        assertSame(mesh.points.get(0), index.pointsById().get(7));
        assertSame(mesh.points.get(2), index.pointsById().get(3));
        assertNull(index.pointsById().get(9));
    }

    @Test
    void edgeIndexNormalizesEndpointOrderAndKeepsFirstMatch() throws Exception {
        final Mesh mesh = new Mesh();
        final Edge first = new Edge(4, 2);
        final Edge reversed = new Edge(1, 5);
        mesh.edges.add(first);
        mesh.edges.add(reversed);

        final MeshFrameIndex index = new MeshFrameIndex(mesh);

        assertSame(first, index.edgesByKey().get(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(2, 4, null))));
        assertSame(reversed, index.edgesByKey().get(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(1, 5, null))));
        assertNull(index.edgesByKey().get(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(2, 5, null))));
    }

    @Test
    void edgeIndexReportsDuplicateKeysForAmbiguityChecks() throws Exception {
        final Mesh mesh = new Mesh();
        mesh.edges.add(new Edge(1, 2));
        mesh.edges.add(new Edge(2, 1));
        mesh.edges.add(new Edge(3, 4));

        final MeshFrameIndex index = new MeshFrameIndex(mesh);
        final long duplicate = MeshFrameIndex.refEdgeKey(new MeshEdgeRef(1, 2, null));

        assertTrue(index.duplicatedEdgeKeys().contains(duplicate));
        assertFalse(index.duplicatedEdgeKeys().contains(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(3, 4, null))));
        assertSame(mesh.edges.get(0), index.edgesByKey().get(duplicate));
    }

    @Test
    void excludedEdgeIsAbsentFromEdgeIndexButNotIdentity() throws Exception {
        final Mesh mesh = new Mesh();
        final Edge source = new Edge(1, 2);
        final Edge other = new Edge(3, 4);
        mesh.edges.add(source);
        mesh.edges.add(other);

        final MeshFrameIndex index = new MeshFrameIndex(mesh).excludingEdge(source);

        assertNull(index.edgesByKey().get(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(1, 2, null))));
        assertSame(other, index.edgesByKey().get(
            MeshFrameIndex.refEdgeKey(new MeshEdgeRef(3, 4, null))));
        assertTrue(index.edgeIdentity().contains(source));
    }

    @Test
    void identityViewsMatchByReferenceNotEquality() throws Exception {
        final Mesh mesh = new Mesh();
        final PointRef point = new PointRef(1);
        mesh.points.add(point);
        final Edge edge = new Edge(1, 2);
        mesh.edges.add(edge);

        final MeshFrameIndex index = new MeshFrameIndex(mesh);

        assertTrue(index.pointIdentity().contains(point));
        assertFalse(index.pointIdentity().contains(new PointRef(1)));
        assertTrue(index.edgeIdentity().contains(edge));
        assertFalse(index.edgeIdentity().contains(new Edge(1, 2)));
    }

    @Test
    void viewsAreLazySoUnusedViewsNeverTouchTheMesh() throws Exception {
        final Mesh mesh = new Mesh();
        mesh.failOnEdges = true;
        mesh.points.add(new PointRef(0));

        final MeshFrameIndex index = new MeshFrameIndex(mesh);

        assertSame(mesh.points.get(0), index.pointsById().get(0));
        assertEquals(1, index.points().size());
    }

    public static final class Mesh {
        final List<PointRef> points = new ArrayList<>();
        final List<Edge> edges = new ArrayList<>();
        boolean failOnEdges;

        public List<PointRef> getAllPointRef() {
            return points;
        }

        public List<Edge> getEdges() {
            if (failOnEdges) throw new IllegalStateException("edges must not be read");
            return edges;
        }
    }

    public static final class PointRef {
        private final int id;
        PointRef(final int id) { this.id = id; }
        public int b() { return id; }
    }

    public static final class Edge {
        private final int first;
        private final int second;
        Edge(final int first, final int second) {
            this.first = first;
            this.second = second;
        }
        public int getIndex1() { return first; }
        public int getIndex2() { return second; }
    }
}
