package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class MeshMirrorVertexResolverTest {
    @Test
    void nativeNearestKeepsFirstTieAndStrictTolerance() throws Exception {
        assertEquals("first", MeshMirrorVertexResolver.nearest(List.of("first", "second"), 2, ignored -> 1));
        assertNull(MeshMirrorVertexResolver.nearest(List.of("first"), 1, ignored -> 1));
        assertNull(MeshMirrorVertexResolver.nearest(List.of("invalid"), 2, ignored -> Double.NaN));
    }

    @Test
    void sharedPairsRequireMutualMatchesAndRejectMissingPoints() {
        var axis = MeshMirrorGeometry.rotatedAxis(0, 0, 0, true, 0);
        assertArrayEquals(
                new int[] {1, 0, -1, -1},
                MeshMirrorVertexResolver.pairs(List.of(p(-1, 0), p(1, 0), p(1.02f, 0), p(3, 4)), axis, .03f));
    }

    @Test
    void spatialIndexWorksAcrossNegativeCellsAndLargeMeshes() {
        var points = new java.util.ArrayList<MeshMirrorGeometry.Point>();
        for (int i = 0; i < 5000; i++) {
            points.add(p(-10, i));
            points.add(p(10, i));
        }
        int[] pairs = MeshMirrorVertexResolver.pairs(points, MeshMirrorGeometry.rotatedAxis(0, 0, 0, true, 0), .01f);
        for (int i = 0; i < pairs.length; i++) assertEquals(i ^ 1, pairs[i]);
    }

    private static MeshMirrorGeometry.Point p(float x, float y) {
        return new MeshMirrorGeometry.Point(x, y);
    }
}
