package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshMirrorAxisService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
