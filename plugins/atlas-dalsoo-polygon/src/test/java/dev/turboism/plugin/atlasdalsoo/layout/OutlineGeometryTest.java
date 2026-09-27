package dev.turboism.plugin.atlasdalsoo.layout;

import java.awt.geom.Path2D;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutlineGeometryTest {

    @Test
    void concaveRingIsPreservedNotConvexified() {
        // L-shape: six vertices, genuinely concave
        final double[][] lShape = {
            {0, 0}, {40, 0}, {40, 20}, {20, 20}, {20, 40}, {0, 40}
        };
        final OutlineGeometry.Rings rings =
            OutlineGeometry.decompose(java.util.List.of(path(lShape)), 0.25);
        assertEquals(1, rings.outerRings().size());
        assertFalse(rings.hasHoles());
        final double[][] ring = rings.outerRings().get(0);
        // a convex hull would have 4 vertices; the concave notch must survive
        assertEquals(6, ring.length);
        assertTrue(containsVertex(ring, 20, 20), "concave notch vertex must be preserved");
    }

    @Test
    void disjointSubpathsStaySeparateRings() {
        final Path2D.Double path = new Path2D.Double();
        append(path, rect(0, 0, 10, 10));
        append(path, rect(50, 50, 10, 10));
        final OutlineGeometry.Rings rings =
            OutlineGeometry.decompose(java.util.List.of(path), 0.25);
        assertEquals(2, rings.outerRings().size());
        assertFalse(rings.hasHoles());
    }

    @Test
    void holeIsDetectedAndFlagged() {
        final Path2D.Double path = new Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD);
        append(path, rect(0, 0, 40, 40));
        append(path, rect(10, 10, 20, 20));
        final OutlineGeometry.Rings rings =
            OutlineGeometry.decompose(java.util.List.of(path), 0.25);
        assertTrue(rings.hasHoles(), "interior ring must classify as a hole");
        assertEquals(1, rings.outerRings().size());
        assertEquals(1, rings.holeRings().size());
        // conservative fill keeps outer ring only
        final OutlineGeometry.Rings filled = OutlineGeometry.fillHoles(rings);
        assertTrue(filled.hasHoles());
        assertEquals(1, filled.outerRings().size());
        assertTrue(filled.holeRings().isEmpty());
    }

    @Test
    void overlappingSubpathsMergeIntoSingleRing() {
        final Path2D.Double path = new Path2D.Double();
        append(path, rect(0, 0, 20, 20));
        append(path, rect(10, 0, 20, 20));
        final OutlineGeometry.Rings rings =
            OutlineGeometry.decompose(java.util.List.of(path), 0.25);
        assertEquals(1, rings.outerRings().size());
    }

    private static boolean containsVertex(final double[][] ring,
        final double x, final double y) {
        for (final double[] v : ring) {
            if (Math.abs(v[0] - x) < 1e-6 && Math.abs(v[1] - y) < 1e-6) {
                return true;
            }
        }
        return false;
    }

    private static double[][] rect(final double x, final double y,
        final double w, final double h) {
        return new double[][] {{x, y}, {x + w, y}, {x + w, y + h}, {x, y + h}};
    }

    private static void append(final Path2D.Double path, final double[][] ring) {
        path.moveTo(ring[0][0], ring[0][1]);
        for (int i = 1; i < ring.length; i++) {
            path.lineTo(ring[i][0], ring[i][1]);
        }
        path.closePath();
    }

    private static Path2D.Double path(final double[][] ring) {
        final Path2D.Double path = new Path2D.Double();
        append(path, ring);
        return path;
    }

    static List<double[][]> unused() { return List.of(); }
}
