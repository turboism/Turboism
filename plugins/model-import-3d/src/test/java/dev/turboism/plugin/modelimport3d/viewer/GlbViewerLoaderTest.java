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
