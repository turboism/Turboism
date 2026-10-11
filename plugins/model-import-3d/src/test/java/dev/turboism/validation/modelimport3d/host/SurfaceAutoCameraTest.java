package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurfaceAutoCameraTest {
    private static byte[] cube() throws Exception {
        return Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cube-textured/cube-textured.glb"));
    }

    @Test
    void scaledTranslatedSourcesHaveEquivalentFramedJointProjections() throws Exception {
        var original = GlbSurfaceReader.read(cube());
        var shifted = original.stream()
                .map(s -> new GlbSurfaceReader.Surface(
                        s.node(),
                        s.mesh(),
                        s.primitive(),
                        s.material(),
                        s.positions().stream()
                                .map(p -> new SurfaceProjection.Vertex(
                                        p.x() * 1000 + 1e8, p.y() * 1000 - 2e8, p.z() * 1000 + 3e8))
                                .toList(),
                        s.sourceNormals(),
                        s.textureUvs(),
                        s.indices(),
                        s.baseColor(),
                        s.doubleSided(),
                        s.reflected(),
                        s.texture()))
                .toList();
        var a = SurfaceAutoCamera.fit(original, 512, 512, 400);
        var b = SurfaceAutoCamera.fit(shifted, 512, 512, 400);
        for (int yaw = -30; yaw <= 30; yaw += 15)
            for (int pitch = -30; pitch <= 30; pitch += 15)
                for (int i = 0; i < original.size(); i++) {
                    var pose = new SurfaceProjection.Pose(yaw, pitch);
                    var x = SurfaceProjection.project(original.get(i).positions(), a, pose)
                            .positions();
                    var y = SurfaceProjection.project(shifted.get(i).positions(), b, pose)
                            .positions();
                    for (int j = 0; j < x.size(); j++) {
                        assertEquals(x.get(j).x(), y.get(j).x(), 1e-4);
                        assertEquals(x.get(j).y(), y.get(j).y(), 1e-4);
                        assertTrue(Math.hypot(x.get(j).x() - 512, x.get(j).y() - 512) <= 400.001);
                    }
                }
        assertEquals(original.get(0).textureUvs(), shifted.get(0).textureUvs());
        assertEquals(original.get(0).indices(), shifted.get(0).indices());
    }

    @Test
    void userPreparationUsesOneFixedCameraAndPreservesTheGlbDigest() throws Exception {
        var bytes = cube();
        var request = new SurfaceImport.Request(
                "Cube",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                30,
                30,
                4000000);
        var direct = SurfaceImport.prepare(bytes, request);
        var framed = SurfaceImport.prepareFramed(bytes, request, SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
        assertEquals(direct.inputSha256, framed.inputSha256);
        assertEquals(request, direct.request);
        assertEquals(framed.request.camera(), framed.geometry.camera());
        assertNotEquals(direct.request.camera().scale(), framed.request.camera().scale());
        for (int i = 0; i < direct.geometry.patches().size(); i++) {
            assertEquals(
                    direct.geometry.patches().get(i).source(),
                    framed.geometry.patches().get(i).source());
        }
    }

    @Test
    void degenerateAndNonfiniteFramingFailBeforeAnyHostAccess() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SurfaceAutoCamera.fit(List.of(), 512, 512, 400));
        var source = GlbSurfaceReader.read(cube());
        assertThrows(IllegalArgumentException.class, () -> SurfaceAutoCamera.fit(source, 512, 512, Double.NaN));
        var p = source.get(0);
        var collapsed = new GlbSurfaceReader.Surface(
                p.node(),
                p.mesh(),
                p.primitive(),
                p.material(),
                p.positions().stream()
                        .map(v -> new SurfaceProjection.Vertex(0, 0, 0))
                        .toList(),
                p.sourceNormals(),
                p.textureUvs(),
                p.indices(),
                p.baseColor(),
                p.doubleSided(),
                p.reflected(),
                p.texture());
        assertThrows(IllegalArgumentException.class, () -> SurfaceAutoCamera.fit(List.of(collapsed), 512, 512, 400));
    }
}
