package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceWarpImportCandidateTest {
    private static SurfaceImport.Request request(long budget) {
        return new SurfaceImport.Request(
                "Candidate",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(.5, .5, .5)),
                30,
                30,
                budget);
    }

    @Test
    void derivesCompletePipelineWithoutExternalChartOrderOrControlFiles() throws Exception {
        var source = SurfaceQuadChartDiscoveryTest.cube(2, 0);
        var p = SurfaceWarpImportCandidate.prepareDecoded(
                List.of(source), request(10000000), SurfaceWarpImportCandidate.DEFAULT);
        assertEquals(6, p.discovery().charts().size());
        assertEquals(48, p.candidateOrder().size());
        assertEquals(48, new HashSet<>(p.candidateOrder()).size());
        assertEquals(
                p.candidateOrder(),
                p.authoring().geometry.meshes().stream()
                        .flatMap(m -> m.triangles().stream())
                        .toList());
        assertEquals(17, p.authoring().yawKeys.size());
        assertEquals(17, p.authoring().pitchKeys.size());
        assertEquals(0f, p.authoring().yawKeys.get(8));
        assertTrue(p.authoring().idealErrorBoundPixels < 1);
        double previous = Double.POSITIVE_INFINITY;
        for (var t : p.candidateOrder()) {
            double z = 0;
            for (int c = 0; c < 3; c++)
                z += source.positions()
                                .get(source.indices().get(t.triangle() * 3 + c))
                                .z()
                        / 3;
            assertTrue(z <= previous);
            previous = z;
        }
    }

    @Test
    void enforcesActualControlCapacityAndIdealPrecisionBeforeAuthoring() {
        var sources = List.of(SurfaceQuadChartDiscoveryTest.cube(2, 0));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImportCandidate.prepareDecoded(
                        sources, request(100), SurfaceWarpImportCandidate.DEFAULT));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImportCandidate.prepareDecoded(
                        sources, request(10000000), new SurfaceWarpImportCandidate.Config(32, 16, 3, .001)));
        assertThrows(IllegalArgumentException.class, () -> new SurfaceWarpImportCandidate.Config(32, 16, 65, 1));
    }

    @Test
    void measuresFullCircleAppleSourceWithoutDroppingVerticesOrTriangles() throws Exception {
        byte[] bytes = ModelImportTestFixtures.readBytes("artifacts/apple-textured/source-r2/apple-textured.glb");
        var request = new SurfaceImport.Request(
                "Apple360",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                20000000);
        var p = SurfaceWarpImportCandidate.prepareFramed(
                bytes, request, new SurfaceWarpImportCandidate.Config(32, 8, 49, 5));
        var a = p.authoring();
        System.out.println("APPLE360_MEASURE charts=" + p.discovery().charts().size() + " controls="
                + a.geometry.sourceControls().size() + " samples=" + a.geometry.controlSamples() + " error="
                + a.idealErrorBoundPixels);
        assertEquals(49, a.yawKeys.size());
        assertEquals(49, a.pitchKeys.size());
        var source = GlbSurfaceReader.read(bytes);
        assertEquals(
                source.stream().mapToInt(v -> v.indices().size() / 3).sum(),
                p.candidateOrder().size());
        assertEquals(
                source.stream().mapToInt(v -> v.indices().size() / 3).sum(),
                a.geometry.meshes().stream().mapToInt(v -> v.triangles().size()).sum());
        for (int i = 0; i < 49; i++) {
            for (int slot = 0; slot < a.geometry.sourceControls().size(); slot++) {
                var left = a.controls(0, i).get(slot);
                var right = a.controls(48, i).get(slot);
                assertEquals(left.x(), right.x(), 0.0001);
                assertEquals(left.y(), right.y(), 0.0001);
                var top = a.controls(i, 0).get(slot);
                var bottom = a.controls(i, 48).get(slot);
                assertEquals(top.x(), bottom.x(), 0.0001);
                assertEquals(top.y(), bottom.y(), 0.0001);
            }
        }
    }
}
