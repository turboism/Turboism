package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

class GeometricMeshShapeTest {
    @Test
    void generatesConcentricRingsCenterVertexAndRadialSpokesWithEqualSpacing() {
        // 1. Single triangle without center (concentricRings = 0)
        final MeshPointLayout triangle = GeometricMeshShape.generate(100.0f, 200.0f, 50.0f, 3, 0, false, false);
        assertEquals(3, triangle.vertexCount());
        assertEquals(3, triangle.edgeCount());
        assertEquals(new Point2(100.0f, 150.0f), triangle.points().get(0));

        // 2. Single perimeter loop (concentricRings = 0) with center point and spokes to center
        final MeshPointLayout spokedWheel = GeometricMeshShape.generate(50.0f, 60.0f, 40.0f, 8, 0, true, true);
        // 1 center vertex + 8 outer perimeter vertices = 9 vertices
        assertEquals(9, spokedWheel.vertexCount());
        // 8 outer perimeter edges + 8 spoke edges to center = 16 edges
        assertEquals(16, spokedWheel.edgeCount());
        assertEquals(new Point2(50.0f, 60.0f), spokedWheel.points().get(0));

        // 3. Hexagon with 2 inner concentric rings (total 3 rings), center point and spokes to center
        final MeshPointLayout concentricHex = GeometricMeshShape.generate(0.0f, 0.0f, 90.0f, 6, 2, true, true);
        // 1 center vertex + 3 total rings * 6 vertices = 19 vertices
        assertEquals(19, concentricHex.vertexCount());
        // 3 rings * 6 perimeter edges + 6 rays * 3 radial segments = 36 edges
        assertEquals(36, concentricHex.edgeCount());
        assertEquals(new Point2(0.0f, 0.0f), concentricHex.points().get(0));
        // Ring 1 radius = 30, Ring 2 radius = 60, Ring 3 radius = 90 (equally spaced)
        assertEquals(30.0, radiusOf(concentricHex.points().get(1)), 0.001);
        assertEquals(60.0, radiusOf(concentricHex.points().get(7)), 0.001);
        assertEquals(90.0, radiusOf(concentricHex.points().get(13)), 0.001);
    }

    @Test
    void perimeterLoopWithoutCenterStartsAtTheTopOfTheCircle() {
        final MeshPointLayout circle = GeometricMeshShape.generate(10.0f, 20.0f, 40.0f, 16, 0, false, false);

        assertEquals(16, circle.vertexCount());
        assertEquals(16, circle.edgeCount());
        assertEquals(new Point2(10.0f, -20.0f), circle.points().get(0));
        // The perimeter closes: the last edge returns to vertex 0.
        final int[] edges = circle.edgePairs();
        assertEquals(0, edges[edges.length - 1]);
        assertEquals(15, edges[edges.length - 2]);
    }

    @Test
    void presetsCarryTheirDefaultSideCounts() {
        assertEquals(16, GeometricMeshShape.Preset.CIRCLE.defaultSides());
        assertEquals(3, GeometricMeshShape.Preset.TRIANGLE.defaultSides());
        assertEquals(4, GeometricMeshShape.Preset.QUADRILATERAL.defaultSides());
        assertEquals(6, GeometricMeshShape.Preset.HEXAGON.defaultSides());
        assertEquals(8, GeometricMeshShape.Preset.OCTAGON.defaultSides());
    }

    @Test
    void clampsSidesRadiusAndConcentricRingsWithinTheirSupportedRanges() {
        assertEquals(GeometricMeshShape.MIN_SIDES, GeometricMeshShape.clampSides(0));
        assertEquals(GeometricMeshShape.MAX_SIDES, GeometricMeshShape.clampSides(1_000));
        assertEquals(GeometricMeshShape.MIN_RADIUS, GeometricMeshShape.clampRadius(-5.0f));
        assertEquals(GeometricMeshShape.MAX_RADIUS, GeometricMeshShape.clampRadius(100_000.0f));
        assertEquals(GeometricMeshShape.DEFAULT_RADIUS, GeometricMeshShape.clampRadius(Float.NaN));
        assertEquals(GeometricMeshShape.MIN_CONCENTRIC_RINGS, GeometricMeshShape.clampConcentricRings(-3));
        assertEquals(GeometricMeshShape.MAX_CONCENTRIC_RINGS, GeometricMeshShape.clampConcentricRings(99));
    }

    @Test
    void generationClampsOutOfRangeParametersInsteadOfProducingAnImpossibleLayout() {
        final MeshPointLayout clamped = GeometricMeshShape.generate(0.0f, 0.0f, 0.0f, 1, 0, false, false);

        assertEquals(GeometricMeshShape.MIN_SIDES, clamped.vertexCount());
        assertEquals(GeometricMeshShape.MIN_SIDES, clamped.edgeCount());
        assertEquals(GeometricMeshShape.MIN_RADIUS, radiusOf(clamped.points().get(0)), 0.001);
    }

    @Test
    void refusesNonFiniteAnchors() {
        assertThrows(
                IllegalArgumentException.class,
                () -> GeometricMeshShape.generate(Float.NaN, 0.0f, 10.0f, 4, 0, false, false));
        assertThrows(
                IllegalArgumentException.class,
                () -> GeometricMeshShape.generate(0.0f, Float.POSITIVE_INFINITY, 10.0f, 4, 0, false, false));
    }

    private static double radiusOf(final Point2 point) {
        return Math.hypot(point.x(), point.y());
    }
}
