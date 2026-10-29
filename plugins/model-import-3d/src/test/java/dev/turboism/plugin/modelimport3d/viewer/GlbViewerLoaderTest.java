package dev.turboism.plugin.modelimport3d.viewer;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;

public class GlbViewerLoaderTest {

    @Test
    void rejectsNullInput() {
        assertThrows(NullPointerException.class, () -> GlbViewerLoader.load(null, () -> false));
    }

    @Test
    void rejectsTooShortInput() {
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(new byte[19], () -> false));
    }

    @Test
    void rejectsInvalidMagic() {
        byte[] invalidMagic = createGlbHeader(0x12345678, 2, 20);
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(invalidMagic, () -> false));
    }

    @Test
    void rejectsUnsupportedVersion() {
        byte[] invalidVersion = createGlbHeader(0x46546C67, 1, 20);
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(invalidVersion, () -> false));
    }

    @Test
    void rejectsOversizedDeclaredLengthHeader() {
        // Actual byte length is 20, declared length is 1000
        byte[] glb = createGlbHeader(0x46546C67, 2, 1000);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("declared length mismatch"));
    }

    @Test
    void rejectsUndersizedDeclaredLengthHeaderWithTrailingBytes() {
        // Actual byte length is 24, declared length is 20
        ByteBuffer buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x46546C67);
        buf.putInt(2);
        buf.putInt(20); // Declared 20, actual 24
        buf.putInt(0);
        buf.putInt(0x4E4F534A);
        buf.putInt(0x12345678); // Trailing junk
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(buf.array(), () -> false));
        assertTrue(ex.getMessage().contains("declared length mismatch"));
    }

    @Test
    void rejectsDeclaredLengthExceedingBudget() {
        byte[] glb = createGlbHeader(0x46546C67, 2, (int) (257 * 1024 * 1024L));
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
    }

    @Test
    void respectsCancellationImmediately() {
        byte[] dummy = createGlbHeader(0x46546C67, 2, 20);
        assertThrows(CancellationException.class, () -> GlbViewerLoader.load(dummy, () -> true));
    }

    @Test
    void rejectsChunkNotJson() {
        ByteBuffer buf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(0x46546C67); // "glTF"
        buf.putInt(2); // version 2
        buf.putInt(28); // length
        buf.putInt(8); // chunk0 len
        buf.putInt(0x004E4942); // "BIN\0" (illegal chunk 0 type)
        buf.putLong(0); // data
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(buf.array(), () -> false));
    }

    @Test
    void rejectsUnsupportedRequiredExtensions() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"extensionsRequired\":[\"KHR_draco_mesh_compression\"]}";
        byte[] glb = buildGlb(json, new byte[0]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("Unsupported required extension"));
    }

    @Test
    void rejectsCycleInNodeHierarchy() {
        // Node 0 -> Node 1 -> Node 0
        String json = "{\"asset\":{\"version\":\"2.0\"}," + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"name\":\"n0\",\"children\":[1]},{\"name\":\"n1\",\"children\":[0]}],"
                + "\"meshes\":[]}";
        byte[] glb = buildGlb(json, new byte[0]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("cycle"));
    }

    @Test
    void rejectsNodeDepthExceedingLimit() {
        // Chain 70 nodes deep (limit is 64)
        StringBuilder nodes = new StringBuilder("[");
        for (int i = 0; i < 70; i++) {
            if (i > 0) nodes.append(",");
            nodes.append("{\"name\":\"n")
                    .append(i)
                    .append("\",\"children\":[")
                    .append(i < 69 ? (i + 1) : "")
                    .append("]}");
        }
        nodes.append("]");

        String json = "{\"asset\":{\"version\":\"2.0\"}," + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":"
                + nodes + "," + "\"meshes\":[]}";
        byte[] glb = buildGlb(json, new byte[0]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("depth exceeds limit"));
    }

    @Test
    void rejectsExternalUriInBuffer() {
        String json =
                "{\"asset\":{\"version\":\"2.0\"},\"buffers\":[{\"uri\":\"http://example.com/model.bin\",\"byteLength\":100}]}";
        byte[] glb = buildGlb(json, new byte[0]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("External URI"));
    }

    @Test
    void rejectsExternalUriInImage() {
        String json = "{\"asset\":{\"version\":\"2.0\"},\"images\":[{\"uri\":\"file:///secret.png\"}]}";
        byte[] glb = buildGlb(json, new byte[0]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("External URI"));
    }

    @Test
    void rejectsAccessorOutOfBounds() {
        // BufferView is 36 bytes, but accessor count requests 10 VEC3 floats = 120 bytes
        String json = "{\"asset\":{\"version\":\"2.0\"}," + "\"buffers\":[{\"byteLength\":36}],"
                + "\"bufferViews\":[{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36}],"
                + "\"accessors\":[{\"bufferView\":0,\"byteOffset\":0,\"componentType\":5126,\"count\":10,\"type\":\"VEC3\"}]}";
        byte[] glb = buildGlb(json, new byte[36]);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("exceeds bufferView"));
    }

    @Test
    void loadsMinimalSyntheticGlb() {
        // Build minimal GLB with 1 triangle, 3 vertices: positions [0,0,0], [1,0,0], [0,1,0]
        ByteBuffer bin = ByteBuffer.allocate(36 + 36 + 12).order(ByteOrder.LITTLE_ENDIAN);
        // Positions (3 * VEC3 = 9 floats = 36 bytes)
        bin.putFloat(0).putFloat(0).putFloat(0);
        bin.putFloat(1).putFloat(0).putFloat(0);
        bin.putFloat(0).putFloat(1).putFloat(0);
        // Normals (3 * VEC3 = 9 floats = 36 bytes)
        bin.putFloat(0).putFloat(0).putFloat(1);
        bin.putFloat(0).putFloat(0).putFloat(1);
        bin.putFloat(0).putFloat(0).putFloat(1);
        // Indices (3 * UNSIGNED_SHORT = 6 bytes + 2 pad = 8 bytes)
        bin.putShort((short) 0).putShort((short) 1).putShort((short) 2).putShort((short) 0);

        String json = "{\"asset\":{\"version\":\"2.0\"}," + "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],"
                + "\"nodes\":[{\"name\":\"root\",\"mesh\":0}],"
                + "\"buffers\":[{\"byteLength\":"
                + bin.position() + "}]," + "\"bufferViews\":["
                + "{\"buffer\":0,\"byteOffset\":0,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":36,\"byteLength\":36},"
                + "{\"buffer\":0,\"byteOffset\":72,\"byteLength\":6}"
                + "],"
                + "\"accessors\":["
                + "{\"bufferView\":0,\"byteOffset\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":1,\"byteOffset\":0,\"componentType\":5126,\"count\":3,\"type\":\"VEC3\"},"
                + "{\"bufferView\":2,\"byteOffset\":0,\"componentType\":5123,\"count\":3,\"type\":\"SCALAR\"}"
                + "],"
                + "\"materials\":[{\"name\":\"mat0\",\"pbrMetallicRoughness\":{\"baseColorFactor\":[1,0,0,1]}}],"
                + "\"meshes\":[{\"name\":\"triangle\",\"primitives\":[{\"attributes\":{\"POSITION\":0,\"NORMAL\":1},\"indices\":2,\"material\":0}]}]"
                + "}";

        byte[] glb = buildGlb(json, Arrays.copyOf(bin.array(), bin.position()));
        ViewerScene scene = GlbViewerLoader.load(glb, () -> false);

        assertNotNull(scene);
        assertEquals(1, scene.meshes().size());
        assertEquals(1, scene.materials().size());
        assertEquals(0, scene.textures().size());
        assertEquals(1, scene.stats().triangleCount());
        assertEquals(1, scene.stats().meshCount());

        ViewerScene.Mesh mesh = scene.meshes().get(0);
        assertEquals("triangle", mesh.name());
        var tagged = GlbViewerLoader.load(
                buildGlb(
                        json.replace("\"material\":0}", "\"material\":0,\"extras\":{\"bodyPart\":\"Left leg\"}}"),
                        Arrays.copyOf(bin.array(), bin.position())),
                () -> false);
        assertEquals("Left leg", tagged.meshes().get(0).bodyPart());

        assertEquals(9, mesh.positions().length);
        assertEquals(9, mesh.normals().length);
        assertEquals(12, mesh.colors().length);
        for (float c : mesh.colors()) {
            assertEquals(1.0f, c, 1e-6f);
        }
        assertEquals(3, mesh.indices().length);
        assertEquals(0, mesh.materialIndex());

        // Bounds
        assertEquals(0.0f, scene.bounds().minX(), 1e-6f);
        assertEquals(1.0f, scene.bounds().maxX(), 1e-6f);
        assertEquals(0.0f, scene.bounds().minY(), 1e-6f);
        assertEquals(1.0f, scene.bounds().maxY(), 1e-6f);

        // Explicit metadata hides auxiliary geometry without deleting it or changing statistics.
        String withHelper = json.replace("\"nodes\":[0]", "\"nodes\":[0,1]")
                .replace(
                        "\"name\":\"root\",\"mesh\":0}",
                        "\"name\":\"root\",\"mesh\":0},{\"mesh\":0,\"translation\":[-100,0,0],\"extras\":{\"hide_render\":true}} ");
        ViewerScene hidden =
                GlbViewerLoader.load(buildGlb(withHelper, Arrays.copyOf(bin.array(), bin.position())), () -> false);
        assertEquals(2, hidden.meshes().size());
        assertEquals(2, hidden.stats().triangleCount());
        assertTrue(hidden.meshes().get(0).visible());
        assertFalse(hidden.meshes().get(1).visible());
        assertEquals(scene.bounds(), hidden.bounds());
        ViewerScene viewportDefault = GlbViewerLoader.load(
                buildGlb(
                        withHelper.replace("\"hide_render\":true", "\"defaultVisible\":false"),
                        Arrays.copyOf(bin.array(), bin.position())),
                () -> false);
        assertFalse(viewportDefault.meshes().get(1).visible());
        assertEquals(2, viewportDefault.stats().triangleCount());
        assertEquals(scene.bounds(), viewportDefault.bounds());
        assertNotEquals(
                viewportDefault.meshes().get(0).appearanceGroup(),
                viewportDefault.meshes().get(1).appearanceGroup());

        String hiddenMesh =
                json.replace("\"name\":\"triangle\"", "\"name\":\"triangle\",\"extras\":{\"hide_render\":true}");
        ViewerScene allHidden =
                GlbViewerLoader.load(buildGlb(hiddenMesh, Arrays.copyOf(bin.array(), bin.position())), () -> false);
        assertFalse(allHidden.meshes().get(0).visible());
        assertEquals(1, allHidden.stats().triangleCount());
        assertEquals(0f, allHidden.bounds().maxX());
        ViewerScene helperOnly = GlbViewerLoader.load(
                buildGlb(
                        hiddenMesh.replace("\"hide_render\":true", "\"isHelper\":true,\"hide_render\":false"),
                        Arrays.copyOf(bin.array(), bin.position())),
                () -> false);
        assertFalse(helperOnly.meshes().get(0).visible());
    }

    private static byte[] createGlbHeader(int magic, int version, int length) {
        ByteBuffer buf = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(magic);
        buf.putInt(version);
        buf.putInt(length);
        buf.putInt(0);
        buf.putInt(0x4E4F534A);
        return buf.array();
    }

    @Test
    void explicitSparseControlsRetainWorldDeltasAndOpacityFactors() {
        var scene = GlbViewerLoader.load(controlGlb(2), () -> false);
        assertEquals(1, scene.controls().size());
        var mesh = scene.meshes().get(0);
        assertEquals(.5f, mesh.defaultOpacity());
        assertArrayEquals(
                new float[] {0, 0, 0, 0, 0, 0, .25f, .5f, .75f},
                mesh.controlSamples().get(0).deltas());
        assertEquals(1, mesh.controlSamples().get(0).key());
    }

    @Test
    void rejectsSparseControlSlotOutsideSourceGeometry() {
        assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(controlGlb(3), () -> false));
    }

    private static byte[] controlGlb(int slot) {
        var bin = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, .25f, .5f, .75f}) bin.putFloat(v);
        bin.putInt(slot);
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "extras":{"turboismControls":[{"id":"Control","name":"Control","minimum":0,"maximum":1}]},
             "nodes":[{"mesh":0,"scale":[2,2,2],"extras":{"turboismOpacityControls":[{"parameter":"Control","base":0.5,"target":0}]}}],
             "buffers":[{"byteLength":52}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":12},{"buffer":0,"byteOffset":48,"byteLength":4}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":1,"type":"VEC3"},{"bufferView":2,"componentType":5125,"count":1,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"extras":{"turboismControlSamples":[{"parameter":"Control","key":1,"slots":2,"deltas":1}]}}]}]}
            """;
        return buildGlb(json, bin.array());
    }

    @Test
    void standardMorphTargetsBecomeControlsWithoutCustomMetadata() {
        byte[] glb = standardMorphGlb();
        assertTrue(GlbViewerLoader.requiresNativeRigImport(glb));
        assertTrue(GlbViewerLoader.load(glb, () -> false).controls().isEmpty());
        var scene = GlbViewerLoader.loadWithSourceControls(glb, () -> false);
        var control = scene.controls().get(0);
        assertEquals("Source shape", control.name());
        assertEquals(-.25f, control.minimum());
        assertEquals(.75f, control.maximum());
        var mesh = scene.meshes().get(0);
        assertEquals(5.5f, mesh.positions()[0]);
        assertEquals(2, mesh.controlSamples().size());
        assertArrayEquals(
                new float[] {-.5f, 0, 0, -.5f, 0, 0, -.5f, 0, 0},
                mesh.controlSamples().get(0).deltas());
        assertArrayEquals(
                new float[] {1.5f, 0, 0, 1.5f, 0, 0, 1.5f, 0, 0},
                mesh.controlSamples().get(1).deltas());
    }

    private static byte[] standardMorphGlb() {
        var bin = ByteBuffer.allocate(72).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0}) bin.putFloat(v);
        return buildGlb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0,"translation":[5,0,0],"scale":[2,2,2],"weights":[0.25]}],
             "buffers":[{"byteLength":72}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},{"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"}],
             "meshes":[{"extras":{"targetNames":["Source shape"]},"primitives":[{"attributes":{"POSITION":0},"targets":[{"POSITION":1}]}]}]}
            """, bin.array());
    }

    @Test
    void skinInfluencesGroupPartsWithoutAnatomicalNameRules() {
        var bin = ByteBuffer.allocate(192).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 2, 0, 0, 3, 0, 0, 2, 1, 0}) bin.putFloat(v);
        for (int i = 0; i < 6; i++)
            bin.put((byte) (i < 3 ? 0 : 1)).put((byte) 0).put((byte) 0).put((byte) 0);
        for (int i = 0; i < 6; i++) bin.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        var glb = buildGlb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
             "nodes":[{"mesh":0,"skin":0},{"name":"Skeleton","children":[2,3]},{"name":"Segment A"},{"name":"Segment B"}],
             "skins":[{"joints":[2,3],"skeleton":1}],"buffers":[{"byteLength":192}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":72},{"buffer":0,"byteOffset":72,"byteLength":24},{"buffer":0,"byteOffset":96,"byteLength":96}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":6,"type":"VEC3"},{"bufferView":1,"componentType":5121,"count":6,"type":"VEC4"},{"bufferView":2,"componentType":5126,"count":6,"type":"VEC4"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0,"JOINTS_0":1,"WEIGHTS_0":2}}]}]}
            """, bin.array());
        var source = GlbViewerLoader.load(glb, () -> false);
        assertEquals(1, source.meshes().size());
        var scene = GlbViewerLoader.loadWithSourceControls(glb, () -> false);
        assertEquals(2, scene.meshes().size());
        assertEquals(2, scene.stats().triangleCount());
        assertEquals("Segment A [node 2]", scene.meshes().get(0).bodyPart());
        assertEquals("Segment B [node 3]", scene.meshes().get(1).bodyPart());
        assertArrayEquals(new int[] {0, 1, 2}, scene.meshes().get(1).indices());
        assertArrayEquals(
                new float[] {2, 0, 0, 3, 0, 0, 2, 1, 0}, scene.meshes().get(1).positions());
    }

    @Test
    void skinnedMorphDeltasUseJointRotationAndScaleWithoutTranslationLeak() {
        var bin = ByteBuffer.allocate(132).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : new float[] {0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 0, 0, 1, 0, 0, 1, 0, 0}) bin.putFloat(v);
        for (int i = 0; i < 12; i++) bin.put((byte) 0);
        for (int i = 0; i < 3; i++) bin.putFloat(1).putFloat(0).putFloat(0).putFloat(0);
        var glb = buildGlb("""
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0,1]}],
             "nodes":[{"mesh":0,"skin":0,"weights":[0.25]},
              {"name":"Arbitrary joint","matrix":[0,2,0,0,-3,0,0,0,0,0,4,0,7,8,9,1]}],
             "skins":[{"joints":[1]}],"buffers":[{"byteLength":132}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":36},
              {"buffer":0,"byteOffset":72,"byteLength":12},{"buffer":0,"byteOffset":84,"byteLength":48}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
              {"bufferView":1,"componentType":5126,"count":3,"type":"VEC3"},
              {"bufferView":2,"componentType":5121,"count":3,"type":"VEC4"},
              {"bufferView":3,"componentType":5126,"count":3,"type":"VEC4"}],
             "meshes":[{"extras":{"targetNames":["Source shape"]},"primitives":[{
              "attributes":{"POSITION":0,"JOINTS_0":2,"WEIGHTS_0":3},"targets":[{"POSITION":1}]}]}]}
            """, bin.array());
        var scene = GlbViewerLoader.loadWithSourceControls(glb, () -> false);
        assertEquals(1, scene.controls().size());
        var mesh = scene.meshes().get(0);
        assertEquals("Arbitrary joint [node 1]", mesh.bodyPart());
        assertArrayEquals(new float[] {7, 8.5f, 9, 7, 10.5f, 9, 4, 8.5f, 9}, mesh.positions(), 1e-6f);
        assertArrayEquals(
                new float[] {0, -.5f, 0, 0, -.5f, 0, 0, -.5f, 0},
                mesh.controlSamples().get(0).deltas(),
                1e-6f);
        assertArrayEquals(
                new float[] {0, 1.5f, 0, 0, 1.5f, 0, 0, 1.5f, 0},
                mesh.controlSamples().get(1).deltas(),
                1e-6f);
    }

    @Test
    void rejectsNonScalarIndexAccessor() {
        var bin = ByteBuffer.allocate(36 + 12).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 9; i++) bin.putFloat(0.0f);
        for (short s : new short[] {0, 1, 2, 0, 1, 2}) bin.putShort(s);
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0}],"buffers":[{"byteLength":48}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":12}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5123,"count":3,"type":"VEC2"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
            """;
        byte[] glb = buildGlb(json, bin.array());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("Index accessor must be SCALAR"), ex.getMessage());
    }

    @Test
    void rejectsSignedOrInvalidIndexComponentType() {
        var bin = ByteBuffer.allocate(36 + 6).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 9; i++) bin.putFloat(0.0f);
        for (short s : new short[] {0, 1, 2}) bin.putShort(s);
        // componentType 5122 is signed SHORT (invalid for glTF indices, must be 5121, 5123, or 5125)
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0}],"buffers":[{"byteLength":42}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":6}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5122,"count":3,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
            """;
        byte[] glb = buildGlb(json, bin.array());
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("Index accessor componentType must be unsigned integer"), ex.getMessage());
    }

    @Test
    void rejectsIndexAccessorExceedingTriangleBudgetBeforeAllocation() {
        int indexCount = 6_000_003; // 2_000_001 triangles > MAX_TRIANGLES (2_000_000)
        int totalBin = 36 + indexCount;
        byte[] binData = new byte[totalBin];
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0}],"buffers":[{"byteLength":%d}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":%d}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5121,"count":%d,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
            """.formatted(totalBin, indexCount, indexCount);
        byte[] glb = buildGlb(json, binData);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("Triangle count exceeds budget"), ex.getMessage());
    }

    @Test
    void rejectsCumulativeTriangleBudgetExceededAcrossMultiplePrimitives() {
        // Primitive 1: 1_500_000 triangles (4_500_000 indices)
        // Primitive 2: 600_000 triangles (1_800_000 indices)
        // Total: 2_100_000 > 2_000_000 (MAX_TRIANGLES)
        int idxCount1 = 4_500_000;
        int idxCount2 = 1_800_000;
        int totalBin = 36 + idxCount1 + idxCount2;
        byte[] binData = new byte[totalBin];
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0}],"buffers":[{"byteLength":%d}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},
                            {"buffer":0,"byteOffset":36,"byteLength":%d},
                            {"buffer":0,"byteOffset":%d,"byteLength":%d}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5121,"count":%d,"type":"SCALAR"},
                          {"bufferView":2,"componentType":5121,"count":%d,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1},
                                     {"attributes":{"POSITION":0},"indices":2}]}]}
            """.formatted(totalBin, idxCount1, 36 + idxCount1, idxCount2, idxCount1, idxCount2);
        byte[] glb = buildGlb(json, binData);
        IllegalArgumentException ex =
                assertThrows(IllegalArgumentException.class, () -> GlbViewerLoader.load(glb, () -> false));
        assertTrue(ex.getMessage().contains("Triangle count exceeds budget"), ex.getMessage());
    }

    @Test
    void acceptsTriangleBudgetAtExactBoundary() {
        // Exactly 2_000_000 triangles (6_000_000 indices)
        int indexCount = 6_000_000;
        int totalBin = 36 + indexCount;
        byte[] binData = new byte[totalBin]; // index 0 in bounds for vertexCount = 3
        String json = """
            {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
             "nodes":[{"mesh":0}],"buffers":[{"byteLength":%d}],
             "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":%d}],
             "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                          {"bufferView":1,"componentType":5121,"count":%d,"type":"SCALAR"}],
             "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
            """.formatted(totalBin, indexCount, indexCount);
        byte[] glb = buildGlb(json, binData);
        var scene = GlbViewerLoader.load(glb, () -> false);
        assertEquals(2_000_000, scene.stats().triangleCount());
    }

    @Test
    void lowHeapForkRejectsOversizedIndexAccessorWithoutOom() throws Exception {
        final String javaHome = System.getProperty("java.home");
        final String javaBin = java.nio.file.Path.of(javaHome, "bin", "java").toString();
        final String testCp = java.nio.file.Path.of(GlbViewerLoaderTest.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        final String loaderCp = java.nio.file.Path.of(GlbViewerLoader.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        final String classpath = testCp
                + java.io.File.pathSeparator
                + loaderCp
                + java.io.File.pathSeparator
                + System.getProperty("java.class.path");
        final Process process = new ProcessBuilder(
                        javaBin,
                        "-Xmx96m",
                        "-cp",
                        classpath,
                        GlbViewerLoaderTest.LowHeapIndexBudgetRunner.class.getName())
                .redirectErrorStream(true)
                .start();
        final boolean finished = process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            fail("Forked low-heap JVM timed out after 15 seconds");
        }
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), "Forked low-heap JVM failed:\n" + output);
    }

    public static final class LowHeapIndexBudgetRunner {
        public static void main(String[] args) {
            int indexCount = 18_000_000;
            int totalBin = 36 + indexCount;
            byte[] binData = new byte[totalBin];
            // 18 million UINT8 indices with 3 vertices: in earlier code, readIntArray allocates
            // new int[18_000_000] (72 MB), throwing OOM on -Xmx96m before triangle budget check.
            String json = """
                {"asset":{"version":"2.0"},"scene":0,"scenes":[{"nodes":[0]}],
                 "nodes":[{"mesh":0}],"buffers":[{"byteLength":%d}],
                 "bufferViews":[{"buffer":0,"byteOffset":0,"byteLength":36},{"buffer":0,"byteOffset":36,"byteLength":%d}],
                 "accessors":[{"bufferView":0,"componentType":5126,"count":3,"type":"VEC3"},
                              {"bufferView":1,"componentType":5121,"count":%d,"type":"SCALAR"}],
                 "meshes":[{"primitives":[{"attributes":{"POSITION":0},"indices":1}]}]}
                """.formatted(totalBin, indexCount, indexCount);
            byte[] glb = buildGlb(json, binData);
            try {
                GlbViewerLoader.load(glb, () -> false);
                System.err.println("FAIL: Expected IllegalArgumentException");
                System.exit(1);
            } catch (IllegalArgumentException ex) {
                if (ex.getMessage().contains("Triangle count exceeds budget")) {
                    System.out.println("PASS");
                    System.exit(0);
                }
                System.err.println("FAIL: Unexpected message: " + ex.getMessage());
                System.exit(2);
            } catch (Throwable t) {
                System.err.println("FAIL: Unexpected throwable: " + t);
                t.printStackTrace();
                System.exit(3);
            }
        }
    }

    private static byte[] buildGlb(String json, byte[] bin) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int jsonPad = (4 - (jsonBytes.length % 4)) % 4;
        int binPad = (4 - (bin.length % 4)) % 4;

        int totalLen = 12 + (8 + jsonBytes.length + jsonPad) + (bin.length > 0 ? (8 + bin.length + binPad) : 0);
        ByteBuffer buf = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN);

        // Header
        buf.putInt(0x46546C67); // "glTF"
        buf.putInt(2); // version
        buf.putInt(totalLen); // length

        // Chunk 0: JSON
        buf.putInt(jsonBytes.length + jsonPad);
        buf.putInt(0x4E4F534A);
        buf.put(jsonBytes);
        for (int i = 0; i < jsonPad; i++) buf.put((byte) ' ');

        // Chunk 1: BIN
        if (bin.length > 0) {
            buf.putInt(bin.length + binPad);
            buf.putInt(0x004E4942);
            buf.put(bin);
            for (int i = 0; i < binPad; i++) buf.put((byte) 0);
        }

        return buf.array();
    }
}
