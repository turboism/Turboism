package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;

class GeometricMeshConfigTest {
    @Test
    void defaultsMatchTheDocumentedStartingShape() {
        final GeometricMeshConfig defaults = GeometricMeshConfig.defaults();

        assertEquals(GeometricMeshShape.Preset.CIRCLE, defaults.preset());
        assertEquals(GeometricMeshShape.DEFAULT_RADIUS, defaults.radius());
        assertEquals(GeometricMeshShape.DEFAULT_SIDES, defaults.sides());
        assertEquals(GeometricMeshShape.DEFAULT_CONCENTRIC_RINGS, defaults.concentricRings());
        assertFalse(defaults.createCenter());
        assertFalse(defaults.connectToCenter());
    }

    @Test
    void normalizesEveryParameterOnConstruction() {
        final GeometricMeshConfig config =
                new GeometricMeshConfig(GeometricMeshShape.Preset.HEXAGON, 100_000.0f, 1_000, 99, true, true);

        assertEquals(GeometricMeshShape.MAX_RADIUS, config.radius());
        assertEquals(GeometricMeshShape.MAX_SIDES, config.sides());
        assertEquals(GeometricMeshShape.MAX_CONCENTRIC_RINGS, config.concentricRings());
    }

    @Test
    void spokesRequireTheCenterVertex() {
        final GeometricMeshConfig withoutCenter =
                new GeometricMeshConfig(GeometricMeshShape.Preset.CIRCLE, 60.0f, 16, 0, false, true);

        assertFalse(withoutCenter.connectToCenter());
    }

    @Test
    void generatesTheLayoutAroundTheGivenAnchor() {
        final GeometricMeshConfig config =
                new GeometricMeshConfig(GeometricMeshShape.Preset.TRIANGLE, 50.0f, 3, 0, true, true);

        final MeshPointLayout layout = config.toLayoutPlan(new Point2(100.0f, 200.0f));

        // 1 center + 3 perimeter vertices; 3 perimeter edges + 3 spokes.
        assertEquals(4, layout.vertexCount());
        assertEquals(6, layout.edgeCount());
        assertEquals(new Point2(100.0f, 200.0f), layout.points().get(0));
    }

    @Test
    void refusesANullAnchor() {
        assertThrows(
                NullPointerException.class, () -> GeometricMeshConfig.defaults().toLayoutPlan(null));
    }
}
