package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    void requiresAnObservedAxisCenterAndRecomputesFromMirrorState() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(45.0f);

        assertNull(service.resolveLine(new State("VERTICAL", 0.0f)));

        service.observeAxis(0.0f, true, 4.0f, 6.0f);
        final MeshMirrorGeometry.Line vertical = service.resolveLine(new State("VERTICAL", 2.0f));
        final MeshMirrorGeometry.Line horizontal = service.resolveLine(new State("HORIZONTAL", 3.0f));

        assertEquals(2.0f, vertical.anchor().x(), 0.0001f);
        assertEquals(6.0f, vertical.anchor().y(), 0.0001f);
        assertEquals(4.0f, horizontal.anchor().x(), 0.0001f);
        assertEquals(3.0f, horizontal.anchor().y(), 0.0001f);
    }

    @Test
    void explicitPivotCanBeObservedBeforeDrawAndInvalidated() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(90.0f);
        service.observePivot(8.0f, 10.0f);

        final MeshMirrorGeometry.Line line = service.resolveLine(new State("HORIZONTAL", 3.0f));
        assertEquals(8.0f, line.anchor().x(), 0.0001f);
        assertEquals(3.0f, line.anchor().y(), 0.0001f);

        service.clearPivot();
        assertNull(service.resolveLine(new State("HORIZONTAL", 3.0f)));
    }

    @Test
    void unreadableMirrorStateFailsClosedInsteadOfReusingStaleState() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(45.0f);
        service.observePivot(8.0f, 10.0f);

        assertNull(service.resolveLine(new Object()));
    }

    @Test
    void unknownMirrorOrientationFailsClosed() {
        final RuntimeMeshMirrorAxisService service = new RuntimeMeshMirrorAxisService();
        service.setCurrentAngleDegrees(45.0f);
        service.observePivot(8.0f, 10.0f);

        assertNull(service.resolveLine(new State("DIAGONAL", 3.0f)));
    }

    private record State(String orientation, float axisValue) {
        public Object b() { return orientation; }
        public float c() { return axisValue; }
    }
}
