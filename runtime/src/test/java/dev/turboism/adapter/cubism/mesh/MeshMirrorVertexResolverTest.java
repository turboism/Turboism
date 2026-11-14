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

    @Test
    void packedCoordinateOverloadMatchesPointListOnRotatedAndAmbiguousMeshes() {
        var axis = MeshMirrorGeometry.rotatedAxis(1.5f, 2.0f, -3.0f, false, 37.0f);
        var points = List.of(
                p(-1.0f, 0.0f),
                MeshMirrorGeometry.reflect(axis, -1.0f, 0.0f),
                p(4.0f, 5.0f),
                p(4.0f, 5.0f),
                MeshMirrorGeometry.reflect(axis, 4.0f, 5.0f),
                p(100.0f, 200.0f));
        float[] xy = new float[points.size() * 2];
        for (int i = 0; i < points.size(); i++) {
            xy[i * 2] = points.get(i).x();
            xy[i * 2 + 1] = points.get(i).y();
        }
        assertArrayEquals(
                MeshMirrorVertexResolver.pairs(points, axis, 0.01f),
                MeshMirrorVertexResolver.pairs(
                        xy,
                        points.size(),
                        axis.anchor().x(),
                        axis.anchor().y(),
                        axis.direction().x(),
                        axis.direction().y(),
                        0.01f));
    }

    private static MeshMirrorGeometry.Point p(float x, float y) {
        return new MeshMirrorGeometry.Point(x, y);
    }
}
