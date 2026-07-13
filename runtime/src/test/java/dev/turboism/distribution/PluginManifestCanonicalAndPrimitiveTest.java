package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginManifestCanonicalAndPrimitiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void canonicalHashIgnoresTopLevelAndNestedObjectOrderButPreservesArrayOrder() throws Exception {
        ObjectNode base = manifest();
        String hash = canonicalHash(base);
        base.put("packageHash", hash);
        assertDoesNotThrow(() -> read(base.toString()));

        String reordered = "{\"version\":\"0.1.0\",\"schemaVersion\":1,\"pluginDescriptorSha256\":\""
            + "b".repeat(64) + "\",\"pluginDescriptorPath\":\"plugin/plugin.jar!/META-INF/turboism/plugin.json\","
            + "\"packageKind\":\"PLUGIN\",\"packageId\":\"dev.turboism.plugin.sample\",\"packageHash\":\"" + hash
            + "\",\"format\":\"turboism.distribution.plugin-package\",\"files\":[{\"size\":4096,\"sha256\":\""
            + "c".repeat(64) + "\",\"role\":\"PLUGIN_JAR\",\"path\":\"plugin/plugin.jar\"}],"
            + "\"createdAt\":\"2026-07-12T18:00:00Z\"}";
        assertDoesNotThrow(() -> read(reordered));

        ObjectNode inputOrder = JSON.createObjectNode();
        inputOrder.set("version", manifest().get("version"));
        manifest().fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("version")) inputOrder.set(entry.getKey(), entry.getValue());
        });
        inputOrder.put("packageHash", rawHash(inputOrder.toString().getBytes(StandardCharsets.UTF_8)));
        DistributionValidationException error = assertThrows(DistributionValidationException.class,
            () -> read(inputOrder.toString()));
        assertEquals("PACKAGE_HASH_MISMATCH", error.code());
    }

    @Test void canonicalJsonOwnsRequiredStringEscapesForKeysValuesAndArrays() throws Exception {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("array").add("\u0002").add("plain/é😀");
        root.put("key\u0001", "value\u0000\b\t\n\f\r\u001f\"\\/");

        String expected = "{\"array\":[\"\\u0002\",\"plain/é😀\"],\"key\\u0001\":\"value\\u0000\\u0008"
            + "\\u0009\\u000a\\u000c\\u000d\\u001f\\\"\\\\/\"}";

        assertEquals(expected, new String(CanonicalJson.bytes(root), StandardCharsets.UTF_8));
    }

    @Test void manifestPrimitivesUseExactRecordRules() throws Exception {
        assertValid(manifest().put("packageId", "a.b"));
        assertInvalid(manifest().put("packageId", "ab"), "packageId");
        for (String timestamp : List.of("2026-07-12T18:00:00Z", "2026-07-12T18:00:00.0Z",
            "2026-07-12T18:00:00.000Z", "2026-07-12T18:00:00.123456789Z",
            "2024-02-29T00:00:00.000000001Z")) {
            assertValid(manifest().put("createdAt", timestamp));
        }
        for (String timestamp : List.of("2026-07-12T20:00:00+02:00", "2026-02-29T18:00:00Z",
            "2026-07-12T18:00:00.1234567890Z")) {
            assertInvalid(manifest().put("createdAt", timestamp), "createdAt");
        }
        ObjectNode unicodePath = manifest();
        unicodePath.withArray("files").removeAll();
        unicodePath.withArray("files").add(file("plugin/plugin.jar", BigInteger.valueOf(4096)));
        unicodePath.withArray("files").add(file("plugin/lib/资料.jar", BigInteger.ZERO));
        assertValid(unicodePath);
        assertInvalid(withLibraryPath("plugin/lib/e\u0301.jar"), "files[1].path");
        assertInvalid(withLibraryPath("plugin/lib/../escape.jar"), "files[1].path");
        assertInvalid(withSize(new BigInteger("9223372036854775808")), "files[0].size");
        assertInvalid(withSize(new BigInteger("-1")), "files[0].size");
    }

    @Test void schemaVersionMustBeArbitraryPrecisionIntegerOne() throws Exception {
        assertValid(withSchemaVersion("1"));
        for (String invalid : List.of("4294967297", "-4294967295", "2147483648", "1.0", "\"1\"")) {
            assertHeaderInvalid(withSchemaVersion(invalid), "schemaVersion");
        }
    }

    @Test void manifestFilesUseTheDocumentedTurboismV1PathIdentityKey() throws Exception {
        assertFileCollision("plugin/lib/e\u0301.jar", "plugin/lib/é.jar");
        assertFileCollision("plugin/lib/Å.jar", "plugin/lib/å.jar");
        assertFileCollision("plugin/lib/Σ.jar", "plugin/lib/ς.jar");
        assertFileCollision("plugin/lib/ς.jar", "plugin/lib/σ.jar");
        assertFileCollision("plugin/lib/ß.jar", "plugin/lib/ẞ.jar");
        assertNotEquals(ManifestPrimitives.pathIdentityKey("plugin/lib/ß.jar"),
            ManifestPrimitives.pathIdentityKey("plugin/lib/ss.jar"));
    }

    private static ObjectNode withSchemaVersion(String literal) throws Exception {
        ObjectNode root = manifest();
        root.set("schemaVersion", JSON.readTree(literal));
        return root;
    }

    private static void assertFileCollision(String first, String second) throws Exception {
        ObjectNode root = manifest();
        root.withArray("files").add(file(first, BigInteger.ZERO));
        root.withArray("files").add(file(second, BigInteger.ZERO));
        root.put("packageHash", canonicalHash(root));
        DistributionValidationException error = assertThrows(DistributionValidationException.class,
            () -> read(root.toString()));
        assertEquals("MANIFEST_FILE_PATH_COLLISION", error.code());
        assertEquals("files[2].path", error.problemPath());
    }

    private static ObjectNode withLibraryPath(String path) {
        ObjectNode root = manifest();
        root.withArray("files").add(file(path, BigInteger.ZERO));
        return root;
    }

    private static ObjectNode withSize(BigInteger size) {
        ObjectNode root = manifest();
        ((ObjectNode) root.withArray("files").get(0)).put("size", size);
        return root;
    }

    private static void assertValid(ObjectNode root) throws Exception {
        root.put("packageHash", canonicalHash(root));
        assertDoesNotThrow(() -> read(root.toString()));
    }

    private static void assertInvalid(ObjectNode root, String path) throws Exception {
        root.put("packageHash", canonicalHash(root));
        assertInvalidManifest(root, path);
    }

    private static void assertHeaderInvalid(ObjectNode root, String path) throws Exception {
        root.put("packageHash", "0".repeat(64));
        assertInvalidManifest(root, path);
    }

    private static void assertInvalidManifest(ObjectNode root, String path) throws Exception {
        DistributionValidationException error = assertThrows(DistributionValidationException.class,
            () -> read(root.toString()));
        assertEquals(path, error.problemPath());
    }

    private static ObjectNode manifest() {
        ObjectNode root = JSON.createObjectNode();
        root.put("createdAt", "2026-07-12T18:00:00Z");
        root.putArray("files").add(file("plugin/plugin.jar", BigInteger.valueOf(4096)));
        root.put("format", "turboism.distribution.plugin-package");
        root.put("packageId", "dev.turboism.plugin.sample");
        root.put("packageKind", "PLUGIN");
        root.put("pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        root.put("pluginDescriptorSha256", "b".repeat(64));
        root.put("schemaVersion", 1);
        root.put("version", "0.1.0");
        return root;
    }

    private static ObjectNode file(String path, BigInteger size) {
        ObjectNode file = JSON.createObjectNode();
        file.put("path", path);
        file.put("role", path.equals("plugin/plugin.jar") ? "PLUGIN_JAR" : "PLUGIN_LIBRARY");
        file.put("sha256", "c".repeat(64));
        file.put("size", size);
        return file;
    }

    private static String canonicalHash(JsonNode node) throws Exception {
        return CanonicalJson.sha256Without(node, "packageHash");
    }

    private static String rawHash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void read(String json) throws Exception {
        PluginManifestReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
