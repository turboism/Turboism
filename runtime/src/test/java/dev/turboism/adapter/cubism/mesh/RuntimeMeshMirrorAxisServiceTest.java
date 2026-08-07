package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeMeshMirrorAxisServiceTest {

    @Test
    void storesTheCurrentAngleNormalizedToOneTurn() {
        final MeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();

        service.setCurrentAngleDegrees(225.0f);

        assertEquals(-135.0f, service.currentAngleDegrees());
        assertThrows(IllegalArgumentException.class,
            () -> service.setCurrentAngleDegrees(Float.NaN));
    }

    @Test
    void requiresAnObservedAxisAndRotatesItAroundTheObservedPivot() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(45.0f);

        assertNull(service.resolveLine());

        service.observeAxis(0.0f, true, 4.0f, 6.0f);
        final MeshMirrorGeometry.Line line = service.resolveLine();

        // anchor (0, 6) rotated 45° about pivot (4, 6): (1.1716, 3.1716), dir (-sin45, cos45).
        assertEquals(1.1716f, line.anchor().x(), 0.0001f);
        assertEquals(3.1716f, line.anchor().y(), 0.0001f);
        assertEquals(-0.7071f, line.direction().x(), 0.0001f);
        assertEquals(0.7071f, line.direction().y(), 0.0001f);
    }

    @Test
    void explicitPivotCanBeObservedBeforeDrawAndInvalidated() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(90.0f);
        service.observePivot(8.0f, 10.0f);

        final MeshMirrorGeometry.Line line = service.resolveLine();

        // anchor (0, 10) rotated 90° about pivot (8, 10): (8, 2), dir (-1, 0).
        assertEquals(8.0f, line.anchor().x(), 0.0001f);
        assertEquals(2.0f, line.anchor().y(), 0.0001f);
        assertEquals(-1.0f, line.direction().x(), 0.0001f);
        assertEquals(0.0f, line.direction().y(), 0.0001f);

        service.clearPivot();
        assertNull(service.resolveLine());
    }

    @Test
    void missingAxisStateOrClearedPivotFailsClosed() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(45.0f);

        assertNull(service.resolveLine());

        service.observePivot(8.0f, 10.0f);
        assertNotNull(service.resolveLine());

        service.clearPivot();
        assertNull(service.resolveLine());
    }

    @Test
    void zeroAngleFailsClosedEvenWithAnObservedAxis() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.observeAxis(0.0f, true, 4.0f, 6.0f);

        assertNull(service.resolveLine());
    }
}
