package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceWarpLayerPlanTest {
    @Test
    void partitionsEverySourceCornerAndProducesBoundedJointOrders() throws Exception {
        var source = SurfaceQuadChartDiscoveryTest.cube(2, 0);
        var request = new SurfaceImport.Request(
                "Layers",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(.5, .5, .5)),
                30,
                30,
                10000000);
        var prepared = SurfaceWarpImportCandidate.prepareDecoded(
                        List.of(source), request, SurfaceWarpImportCandidate.DEFAULT)
                .authoring();
        var original = prepared.geometry;
        var split = SurfaceWarpLayerPlan.splitCharts(original);
        assertEquals(6, split.meshes().size());
        assertEquals(original.sourceControls(), split.sourceControls());
        assertEquals(original.controlSamples(), split.controlSamples());
        assertEquals(
                new HashSet<>(original.meshes().stream()
                        .flatMap(m -> m.triangles().stream())
                        .toList()),
                new HashSet<>(split.meshes().stream()
                        .flatMap(m -> m.triangles().stream())
                        .toList()));
        int triangles = 0;
        for (var mesh : split.meshes()) {
            assertEquals(
                    1,
                    mesh.slotIdentities().stream()
                            .map(SurfaceWarpChartPlan.Slot::chart)
                            .distinct()
                            .count());
            for (int i = 0; i < mesh.triangles().size(); i++)
                for (int c = 0; c < 3; c++) {
                    int slot = mesh.indices().get(i * 3 + c);
                    int originalSlot =
                            source.indices().get(mesh.triangles().get(i).triangle() * 3 + c);
                    assertEquals(
                            source.positions().get(originalSlot),
                            mesh.sourcePositions().get(slot));
                    assertEquals(
                            source.textureUvs().get(originalSlot),
                            mesh.sourceUvs().get(slot));
                }
            triangles += mesh.triangles().size();
        }
        assertEquals(48, triangles);
        var orders = SurfaceWarpLayerPlan.orders(split, request.camera(), prepared.yawKeys, prepared.pitchKeys);
        assertEquals(289, orders.jointKeys().size());
        for (var row : orders.jointKeys()) assertEquals(Set.of(0, 8, 16, 24, 32, 40), new HashSet<>(row));
        var neutral = orders.jointKeys().get(8 * 17 + 8);
        for (int i = 0; i < 6; i++)
            for (int j = 0; j < 6; j++)
                if (orders.centroids().get(i).z() > orders.centroids().get(j).z())
                    assertTrue(neutral.get(i) < neutral.get(j));
    }
}
