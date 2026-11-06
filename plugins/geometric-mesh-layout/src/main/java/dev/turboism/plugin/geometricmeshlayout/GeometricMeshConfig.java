package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.Objects;

/**
 * Plugin-owned geometric layout parameter state.
 *
 * <p>The values are normalized on construction, so every consumer — preview, commit and the
 * configuration dialog's controls — sees the same clamped configuration.</p>
 */
record GeometricMeshConfig(
        GeometricMeshShape.Preset preset,
        float radius,
        int sides,
        int concentricRings,
        boolean createCenter,
        boolean connectToCenter) {

    GeometricMeshConfig {
        Objects.requireNonNull(preset, "preset");
        radius = GeometricMeshShape.clampRadius(radius);
        sides = GeometricMeshShape.clampSides(sides);
        concentricRings = GeometricMeshShape.clampConcentricRings(concentricRings);
        if (!createCenter) {
            connectToCenter = false;
        }
    }

    /** Returns the pristine configuration the tool starts from. */
    static GeometricMeshConfig defaults() {
        return new GeometricMeshConfig(
                GeometricMeshShape.Preset.CIRCLE,
                GeometricMeshShape.DEFAULT_RADIUS,
                GeometricMeshShape.DEFAULT_SIDES,
                GeometricMeshShape.DEFAULT_CONCENTRIC_RINGS,
                false,
                false);
    }

    /** Generates the mesh-space layout for the given anchor. */
    MeshPointLayout toLayoutPlan(final Point2 anchor) {
        Objects.requireNonNull(anchor, "anchor");
        return GeometricMeshShape.generate(
                anchor.x(), anchor.y(), radius, sides, concentricRings, createCenter, connectToCenter);
    }
}
