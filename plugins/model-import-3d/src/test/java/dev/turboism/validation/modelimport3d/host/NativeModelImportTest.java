package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.sdk.cubism.id.ParameterId;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** End-to-end pure production preparation; private assets must be explicitly configured. */
class NativeModelImportTest {
    private record Face(int mesh, int a, int b, int c) {}

    @Test
    void originalCharacterPreservesEverySourceTriangleAndPlansAtlases() throws Exception {
        verify("TURBOISM_VIEWER_FIXTURE");
    }

    @Test
    void repairedCharacterPreservesEverySourceTriangleAndPlansAtlases() throws Exception {
        verify("TURBOISM_REPAIRED_VIEWER_FIXTURE");
    }

    @Test
    void evaluatedDefaultPreservesOriginalGeometryAndAddsMaskedSurfaces() throws Exception {
        verify("TURBOISM_DEFAULT_APPEARANCE_FIXTURE");
    }

    @Test
    void smallStandardMorphModelUsesNativeProductionPathForGlbAndGltf() throws Exception {
        byte[] bin = new byte[72];
        var values = java.nio.ByteBuffer.wrap(bin).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float value : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, .1f, 0, 0, .1f, 0, 0, .1f, 0, 0})
            values.putFloat(value);
        String dataUri =
                "data:application/octet-stream;base64," + Base64.getEncoder().encodeToString(bin);
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],"nodes":[{"mesh":0}],
             "buffers":[{"byteLength":72%s}],"bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"}],
             "meshes":[{"extras":{"targetNames":["Source shape"]},"primitives":[{"attributes":{"POSITION":0},"targets":[{"POSITION":1}]}]}]}
            """;
        byte[] raw = json.formatted("").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int padded = (raw.length + 3) & ~3;
        var glb = java.nio.ByteBuffer.allocate(28 + padded + bin.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        glb.putInt(0x46546c67)
                .putInt(2)
                .putInt(glb.capacity())
                .putInt(padded)
                .putInt(0x4e4f534a)
                .put(raw);
        while (glb.position() < 20 + padded) glb.put((byte) ' ');
        glb.putInt(bin.length).putInt(0x004e4942).put(bin);
        var request = new SurfaceImport.Request(
                "Generic",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                Optional.of(new ParameterId("Roll")));
        for (byte[] input : List.of(
                glb.array(),
                json.formatted(",\"uri\":\"" + dataUri + "\"").getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            var prepared = ModelImportPlugin.prepare(input, request, SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
            assertNotNull(prepared.character());
            assertEquals(1, prepared.character().geometry().totalTriangles());
            assertEquals(1, prepared.character().controls().controls().size());
            assertEquals(
                    "Source shape",
                    prepared.character().controls().controls().get(0).name());
        }
    }

    private static void verify(String variable) throws Exception {
        String fixture = System.getenv(variable);
        Assumptions.assumeTrue(fixture != null && !fixture.isBlank(), variable + " not configured");
        Path path = Path.of(fixture);
        Assumptions.assumeTrue(Files.isRegularFile(path), "Fixture not available");
        byte[] bytes = Files.readAllBytes(path);
        byte[] before = MessageDigest.getInstance("SHA-256").digest(bytes);
        var source = GlbViewerLoader.load(bytes, () -> false);
        var request = new SurfaceImport.Request(
                "Character",
                new ParameterId("Param3DYawTest"),
                new ParameterId("Param3DPitchTest"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                180,
                180,
                SurfaceImportPlan.MAX_PROJECTED_VERTICES,
                Optional.of(new ParameterId("Param3DRollTest")));
        var result = ModelImportPlugin.prepare(bytes, request, SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
        assertNotNull(result.character(), "Large GLB must use the native character production path");
        var prepared = result.character();
        var plan = prepared.geometry();
        for (int i = 0; i < source.meshes().size(); i++)
            assertEquals(
                    source.meshes().get(i).visible(),
                    prepared.materials().scene().meshes().get(i).visible());
        if (variable.equals("TURBOISM_REPAIRED_VIEWER_FIXTURE")) {
            assertEquals(3, source.meshes().stream().filter(m -> !m.visible()).count());
            assertTrue(source.bounds().maxX() - source.bounds().minX() < 1.5f);
        }
        if (variable.equals("TURBOISM_DEFAULT_APPEARANCE_FIXTURE")) {
            assertEquals(203851, plan.totalTriangles());
            for (String part : List.of("Left leg", "Right leg")) {
                var groups = source.meshes().stream()
                        .filter(m -> m.visible() && part.equals(m.bodyPart()))
                        .map(m -> m.appearanceGroup())
                        .distinct()
                        .toList();
                assertTrue(groups.size() >= 2, "Each leg must include both skin and shoe source groups");
            }

            assertEquals(12, source.meshes().stream().filter(m -> !m.visible()).count());
            assertTrue(source.meshes().stream()
                    .filter(m -> m.name().startsWith("Body.004_"))
                    .noneMatch(m -> m.visible()));
            assertTrue(source.meshes().stream()
                    .filter(m -> m.name().startsWith("Body.004 [Evaluated default]"))
                    .allMatch(m -> m.visible()));
        } else assertEquals(121097, plan.totalTriangles());
        Map<Face, Integer> expected = new HashMap<>(), actual = new HashMap<>();
        for (int i = 0; i < source.meshes().size(); i++) {
            var indices = source.meshes().get(i).indices();
            for (int t = 0; t < indices.length; t += 3)
                expected.merge(new Face(i, indices[t], indices[t + 1], indices[t + 2]), 1, Integer::sum);
        }
        for (var partition : plan.partitions()) {
            var slots = partition.sourceSlotMapping();
            var indices = partition.indices();
            for (int t = 0; t < indices.size(); t += 3)
                actual.merge(
                        new Face(
                                partition.sourceMeshIndex(),
                                slots.get(indices.get(t)),
                                slots.get(indices.get(t + 1)),
                                slots.get(indices.get(t + 2))),
                        1,
                        Integer::sum);
        }
        assertEquals(expected, actual, "Every source face must survive once, with exact winding and vertex provenance");
        assertEquals(
                source.meshes().size(), prepared.materials().meshImageIndices().size());
        assertTrue(prepared.materials().meshImageIndices().stream().allMatch(i -> i >= 0));
        var atlas = NativeModelImport.planAtlas(prepared, i -> "Character " + (i + 1));
        assertEquals(prepared.materials().images().size(), atlas.placements().size());
        assertTrue(atlas.pageCount() <= 32);
        assertTrue((long) atlas.pageCount() * atlas.pageWidth() * atlas.pageHeight() <= 134_217_728L);
        assertTrue(plan.totalKeyformSlots() <= 6_000_000L);
        long cacheSlots = 0;
        for (int i = 0; i < plan.partitions().size(); i++)
            cacheSlots += plan.partitions().get(i).positions().size()
                    * plan.sampling().get(i).grid();
        assertTrue(cacheSlots <= 64_000_000L, "Native interpolation cache must remain within budget: " + cacheSlots);
        for (var partition : plan.partitions()) {
            if (!source.meshes().get(partition.sourceMeshIndex()).visible()) continue;
            var projected =
                    SurfaceProjection.project(partition.positions(), plan.camera(), new SurfaceProjection.Pose(0, 0));
            for (var point : projected.positions())
                assertTrue(
                        point.x() >= 0 && point.x() <= 1024 && point.y() >= 0 && point.y() <= 1024,
                        "Auto-framing must contain complete scene");
        }
        assertArrayEquals(before, MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        System.out.printf(
                Locale.ROOT,
                "%s: triangles=%d partitions=%d slots=%d forms=%d cacheSlots=%d images=%d atlas=%dx%dx%d errorBound=%.3fpx%n",
                variable,
                plan.totalTriangles(),
                plan.partitions().size(),
                plan.totalPartitionSlots(),
                plan.totalKeyformSlots(),
                cacheSlots,
                prepared.materials().images().size(),
                atlas.pageWidth(),
                atlas.pageHeight(),
                atlas.pageCount(),
                plan.maxInterpolationErrorPixels());
    }
}
