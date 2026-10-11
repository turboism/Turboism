package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.json.Json;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PlanarImportTest {
    private static SurfaceImport.Request request() {
        return new SurfaceImport.Request(
                "Plane",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                30,
                30,
                2_000_000);
    }

    private static byte[] input(String file) throws Exception {
        return Files.readAllBytes(Path.of(System.getProperty("mi3d.repo"), "artifacts/plane-textured", file));
    }

    @Test
    void equalPositionAndUvSeamSlotsAreNotWelded() throws Exception {
        var bytes = input("plane-seam.glb");
        var source = GlbSurfaceReader.read(bytes).get(0);
        assertEquals(source.positions().get(0), source.positions().get(3));
        assertEquals(source.textureUvs().get(0), source.textureUvs().get(3));
        var prepared = SurfaceImport.prepare(bytes, request());
        assertEquals(SurfaceImport.VisibilityKind.PLANAR_QUAD, prepared.visibility.kind());
        assertEquals(6, prepared.visibility.sourceSlots());
        assertEquals(2, prepared.geometry.patches().size());
        assertEquals(
                List.of(0, 1, 2), prepared.geometry.patches().get(0).source().sourceVertices());
        assertEquals(
                List.of(3, 4, 5), prepared.geometry.patches().get(1).source().sourceVertices());
        assertEquals(1, prepared.appearance.materials().size());
        for (var patch : prepared.geometry.patches()) {
            assertEquals(
                    patch.source().sourceVertices().stream()
                            .map(source.positions()::get)
                            .toList(),
                    patch.source().positions());
            assertEquals(
                    patch.source().sourceVertices().stream()
                            .map(source.textureUvs()::get)
                            .toList(),
                    patch.source().textureUvs());
        }
    }

    @Test
    void malformedActualPlaneGlbsAreRejectedDuringPrepare() throws Exception {
        byte[] bytes = input("plane.glb");
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int length = buffer.getInt(12);
        var document = (Map<?, ?>) Json.parseObject(Arrays.copyOfRange(bytes, 20, 20 + length));
        var accessors = (List<?>) document.get("accessors");
        var views = (List<?>) document.get("bufferViews");
        java.util.function.IntUnaryOperator offset = ai -> {
            var accessor = (Map<?, ?>) accessors.get(ai);
            var view = (Map<?, ?>) views.get(((Number) accessor.get("bufferView")).intValue());
            return 28 + length + ((Number) view.get("byteOffset")).intValue();
        };
        int position = offset.applyAsInt(0), indices = offset.applyAsInt(3);
        for (int mutation = 0; mutation < 4; mutation++) {
            var changed = bytes.clone();
            var b = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
            switch (mutation) {
                case 0 -> b.putFloat(position + 2 * 12 + 8, Math.ulp(1f)); // not coplanar
                case 1 -> {
                    b.putShort(indices + 8, (short) 2);
                    b.putShort(indices + 10, (short) 3);
                } // reverse second triangle
                case 2 -> {
                    for (int i = 0; i < 6; i++) changed[indices + 6 + i] = bytes[indices + i];
                } // overlapping duplicate
                case 3 -> {
                    b.putFloat(position + 2 * 12, 0);
                    b.putFloat(position + 2 * 12 + 4, -.25f);
                } // concave boundary
            }
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SurfaceImport.prepare(changed, request()),
                    "mutation " + mutation);
        }
    }

    @Test
    void actualTexturedPlanePreparesWithoutLosingSourceUvOrAspect() throws Exception {
        var root = Path.of(System.getProperty("mi3d.repo"));
        var bytes = Files.readAllBytes(root.resolve("artifacts/plane-textured/plane.glb"));
        var request = new SurfaceImport.Request(
                "Plane",
                new ParameterId("Yaw"),
                new ParameterId("Pitch"),
                new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0)),
                30,
                30,
                2_000_000);
        var source = GlbSurfaceReader.read(bytes).get(0);
        var prepared = SurfaceImport.prepare(bytes, request);
        assertEquals(SurfaceImport.VisibilityKind.PLANAR_QUAD, prepared.visibility.kind());
        assertEquals(1, prepared.geometry.patches().size());
        assertEquals(1, prepared.appearance.materials().size());
        assertTrue(prepared.appearance.materials().get(0).textured());
        var patch = prepared.geometry.patches().get(0);
        assertEquals(List.of(0, 2, 1, 3), patch.source().sourceVertices());
        assertEquals(
                patch.source().sourceVertices().stream()
                        .map(source.textureUvs()::get)
                        .toList(),
                patch.source().textureUvs());
        var neutral = patch.positionsByKey().get(prepared.geometry.neutralKey());
        assertEquals(
                400f,
                neutral.stream().map(p -> p.x()).max(Float::compare).orElseThrow()
                        - neutral.stream().map(p -> p.x()).min(Float::compare).orElseThrow());
        assertEquals(
                200f,
                neutral.stream().map(p -> p.y()).max(Float::compare).orElseThrow()
                        - neutral.stream().map(p -> p.y()).min(Float::compare).orElseThrow());
        assertTrue(prepared.geometry.grid().yawKeys().size() > 3);
        assertTrue(prepared.geometry.grid().pitchKeys().size() > 3);
        var cube = SurfaceImport.prepare(
                Files.readAllBytes(root.resolve("build/model-import-3d-inputs/cube-985496727e50/cube.glb")), request);
        assertEquals(SurfaceImport.VisibilityKind.CLOSED_CONVEX, cube.visibility.kind());
        assertEquals(6, cube.geometry.patches().size());
    }
}
