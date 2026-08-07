package dev.turboism.adapter.cubism.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class MeshMirrorGeometryTest {

    @Test
    void reflectsProjectsAndHitsAgainstTheRotatedAxis() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(
            0.0f, 0.0f, 0.0f, true, 45.0f
        );

        assertPoint(-1.0f, -1.0f, MeshMirrorGeometry.reflect(axis, 1.0f, 1.0f));
        assertPoint(1.0f, -1.0f, MeshMirrorGeometry.project(axis, 2.0f, 0.0f));
        assertEquals(0.0f, MeshMirrorGeometry.distance(axis, 2.0f, -2.0f), 0.0001f);
        assertEquals(true, MeshMirrorGeometry.hit(axis, 2.0f, -1.95f, 0.1f));
        assertEquals(false, MeshMirrorGeometry.hit(axis, 2.0f, -1.8f, 0.1f));
    }

    @Test
    void keepsTheNativeAxisAtZeroRotation() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(
            3.0f, 4.0f, 7.0f, true, 0.0f
        );

        assertPoint(3.0f, 7.0f, axis.anchor());
        assertPoint(0.0f, 1.0f, axis.direction());
        assertPoint(5.0f, 4.0f, MeshMirrorGeometry.reflect(axis, 1.0f, 4.0f));
    }

    @Test
    void rotatesTheAnchorAroundThePivotAtNonZeroAngle() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(
            2.0f, 4.0f, 6.0f, true, 45.0f
        );

        assertPoint(2.5858f, 4.5858f, axis.anchor());
        assertPoint(-0.7071f, 0.7071f, axis.direction());
    }

    private static void assertPoint(
        final float expectedX,
        final float expectedY,
        final MeshMirrorGeometry.Point actual
    ) {
        assertEquals(expectedX, actual.x(), 0.0001f);
        assertEquals(expectedY, actual.y(), 0.0001f);
    }
}
