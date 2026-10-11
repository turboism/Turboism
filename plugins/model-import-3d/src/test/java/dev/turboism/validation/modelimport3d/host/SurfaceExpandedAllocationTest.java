package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SurfaceExpandedAllocationTest {
    @Test
    void expandedRequestCapHasAFiniteBoundary() {
        var camera = new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0));
        assertEquals(
                20000000,
                new SurfaceImport.Request(
                                "3D", new ParameterId("Yaw"), new ParameterId("Pitch"), camera, 30, 30, 20000000)
                        .maxProjectedVertices());
        assertThrows(
                IllegalArgumentException.class,
                () -> new SurfaceImport.Request(
                        "3D", new ParameterId("Yaw"), new ParameterId("Pitch"), camera, 30, 30, 20000001));
    }

    @Test
    void actualCubePreparationRejectsAllocationBeforeHostWrites() throws Exception {
        var input = Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cube-textured/cube-textured.glb"));
        var camera = new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0));
        var error = assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceImport.prepare(
                        input,
                        new SurfaceImport.Request(
                                "3D", new ParameterId("Yaw"), new ParameterId("Pitch"), camera, 30, 30, 1)));
        assertTrue(error.getMessage().contains("minimum packed coordinate bytes="));
        var prepared = SurfaceImport.prepare(
                input,
                new SurfaceImport.Request(
                        "3D", new ParameterId("Yaw"), new ParameterId("Pitch"), camera, 30, 30, 4000000));
        assertTrue(prepared.geometry.allocation().packedCoordinateBytes() > 0);
        assertTrue(prepared.geometry.allocation().keyforms() > 0);
    }
}
