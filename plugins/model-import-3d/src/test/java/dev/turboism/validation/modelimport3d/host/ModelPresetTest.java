package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ModelPresetTest {
    @Test
    void tetrahedronHasFourOutwardEquilateralFacesAndSeparateUvTiles() throws Exception {
        var scene = GlbViewerLoader.load(ModelPreset.TETRAHEDRON.read(), () -> false);
        assertFalse(scene.materials().get(0).doubleSided());
        var mesh = scene.meshes().get(0);
        assertEquals(12, mesh.indices().length);
        var positions = mesh.positions();
        var edges = new java.util.HashMap<java.util.Set<java.util.List<Float>>, Integer>();
        var tiles = new java.util.HashSet<Integer>();
        for (int i = 0; i < mesh.indices().length; i += 3) {
            double centerU = 0, centerV = 0;
            for (int j = 0; j < 3; j++) {
                int a = mesh.indices()[i + j], b = mesh.indices()[i + (j + 1) % 3];
                double distanceSquared = 0;
                for (int axis = 0; axis < 3; axis++) {
                    double delta = positions[a * 3 + axis] - positions[b * 3 + axis];
                    distanceSquared += delta * delta;
                }
                assertEquals(8d / 3, distanceSquared, 1e-6);
                var first = java.util.List.of(positions[a * 3], positions[a * 3 + 1], positions[a * 3 + 2]);
                var second = java.util.List.of(positions[b * 3], positions[b * 3 + 1], positions[b * 3 + 2]);
                edges.merge(java.util.Set.of(first, second), 1, Integer::sum);
                assertTrue(positions[a * 3] * mesh.normals()[a * 3]
                                + positions[a * 3 + 1] * mesh.normals()[a * 3 + 1]
                                + positions[a * 3 + 2] * mesh.normals()[a * 3 + 2]
                        > 0);
                centerU += mesh.uvs()[a * 2] / 3d;
                centerV += mesh.uvs()[a * 2 + 1] / 3d;
            }
            tiles.add((int) (centerU * 2) + 2 * (int) (centerV * 2));
        }
        assertEquals(6, edges.size());
        assertTrue(edges.values().stream().allMatch(count -> count == 2), "Every edge must close two faces");
        assertEquals(4, tiles.size());
    }

    @Test
    void triangleIsEquilateralPlanarAndDoubleSided() throws Exception {
        var scene = GlbViewerLoader.load(ModelPreset.TRIANGLE.read(), () -> false);
        assertTrue(scene.materials().get(0).doubleSided());
        var mesh = scene.meshes().get(0);
        assertEquals(3, mesh.indices().length);
        var positions = mesh.positions();
        for (int i = 0; i < 3; i++) {
            int a = mesh.indices()[i] * 3, b = mesh.indices()[(i + 1) % 3] * 3;
            double x = positions[a] - positions[b], y = positions[a + 1] - positions[b + 1];
            assertEquals(3, x * x + y * y, 1e-6);
            assertEquals(0, positions[a + 2]);
        }
    }

    @Test
    void flatRingHasDoubleSidedShortWallsWithInwardWinding() throws Exception {
        var scene = GlbViewerLoader.load(ModelPreset.INWARD_RING.read(), () -> false);
        assertTrue(scene.materials().get(0).doubleSided());
        var mesh = scene.meshes().get(0);
        var positions = mesh.positions();
        var normals = mesh.normals();
        for (int i = 0; i < positions.length; i += 3) {
            float x = positions[i], y = positions[i + 1], z = positions[i + 2];
            assertEquals(1, x * x + z * z, 1e-6);
            assertEquals(0.15, Math.abs(y), 1e-6);
            assertTrue(x * normals[i] + z * normals[i + 2] < -0.99);
        }
        var indices = mesh.indices();
        for (int i = 0; i < indices.length; i += 3) {
            int a = indices[i] * 3, b = indices[i + 1] * 3, c = indices[i + 2] * 3;
            double ux = positions[b] - positions[a], uy = positions[b + 1] - positions[a + 1];
            double uz = positions[b + 2] - positions[a + 2];
            double vx = positions[c] - positions[a], vy = positions[c + 1] - positions[a + 1];
            double vz = positions[c + 2] - positions[a + 2];
            double centerX = (positions[a] + positions[b] + positions[c]) / 3;
            double centerZ = (positions[a + 2] + positions[b + 2] + positions[c + 2]) / 3;
            assertTrue(
                    (uy * vz - uz * vy) * centerX + (ux * vy - uy * vx) * centerZ < 0,
                    "Triangle winding must face the center, independently of stored normals");
        }
    }

    @Test
    void circleIsFilledPlanarAndDoubleSided() throws Exception {
        var scene = GlbViewerLoader.load(ModelPreset.CIRCLE.read(), () -> false);
        assertTrue(scene.materials().get(0).doubleSided());
        var mesh = scene.meshes().get(0);
        assertEquals(32, mesh.indices().length / 3);
        assertEquals(0, mesh.positions()[0]);
        assertEquals(0, mesh.positions()[1]);
        for (int i = 0; i < mesh.positions().length; i += 3) {
            assertEquals(0, mesh.positions()[i + 2]);
        }
        for (int i = 0; i < mesh.indices().length; i += 3) {
            assertEquals(0, mesh.indices()[i], "Every sector must include the center");
        }
    }

    @ParameterizedTest
    @EnumSource(ModelPreset.class)
    void bundledGlbPreservesGeometryAndOneReplaceableImage(ModelPreset preset) throws Exception {
        byte[] bytes = preset.read();
        assertTrue(bytes.length < 16 * 1024, "Presets should stay small");
        var scene = GlbViewerLoader.load(bytes, () -> false);
        assertEquals(1, scene.meshes().size());
        assertEquals(1, scene.textures().size());
        var request = new SurfaceImport.Request(
                preset.fallbackLabel(),
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                Optional.of(new ParameterId("Roll")));
        var prepared = NativeModelImport.prepare(bytes, request, () -> false);
        assertEquals(
                scene.meshes().get(0).indices().length / 3, prepared.geometry().totalTriangles());
        assertEquals(1, prepared.materials().images().size());
        assertEquals(256, prepared.materials().images().get(0).width());
        assertEquals(256, prepared.materials().images().get(0).height());
        var source = scene.meshes().get(0);
        for (var partition : prepared.geometry().partitions()) {
            assertEquals(
                    preset == ModelPreset.SQUARE
                            || preset == ModelPreset.ANNULUS
                            || preset == ModelPreset.CIRCLE
                            || preset == ModelPreset.INWARD_RING
                            || preset == ModelPreset.TRIANGLE,
                    partition.doubleSided());
            for (int i = 0; i < partition.sourceSlotMapping().size(); i++) {
                int slot = partition.sourceSlotMapping().get(i);
                assertEquals(source.uvs()[slot * 2], partition.uvs().get(i).x());
                assertEquals(source.uvs()[slot * 2 + 1], partition.uvs().get(i).y());
            }
        }
        assertEquals(180, prepared.geometry().request().yawLimit());
        assertEquals(180, prepared.geometry().request().pitchLimit());
        assertTrue(prepared.geometry().request().roll().isPresent());
    }
}
