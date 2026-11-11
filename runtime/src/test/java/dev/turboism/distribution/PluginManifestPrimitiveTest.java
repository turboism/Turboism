package dev.turboism.distribution;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class PluginManifestPrimitiveTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void retiredSelfHashFieldsRemainAcceptedAsIgnoredLegacyFields() throws Exception {
        ObjectNode withSelfHashes = manifest();
        withSelfHashes.put("packageHash", "a".repeat(64));
        withSelfHashes.put("pluginDescriptorSha256", "not-even-a-digest");
        assertDoesNotThrow(() -> read(withSelfHashes.toString()));

        assertDoesNotThrow(() -> read(manifest().toString()));
    }

    @Test
    void manifestPrimitivesUseExactRecordRules() throws Exception {
        assertValid(manifest().put("packageId", "a.b"));
        assertInvalid(manifest().put("packageId", "ab"), "packageId");
        for (String timestamp : List.of(
                "2026-07-12T18:00:00Z",
                "2026-07-12T18:00:00.0Z",
                "2026-07-12T18:00:00.000Z",
                "2026-07-12T18:00:00.123456789Z",
                "2024-02-29T00:00:00.000000001Z")) {
            assertValid(manifest().put("createdAt", timestamp));
        }
        for (String timestamp :
                List.of("2026-07-12T20:00:00+02:00", "2026-02-29T18:00:00Z", "2026-07-12T18:00:00.1234567890Z")) {
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

    @Test
    void schemaVersionMustBeArbitraryPrecisionIntegerOne() throws Exception {
        assertValid(withSchemaVersion("1"));
        for (String invalid : List.of("4294967297", "-4294967295", "2147483648", "1.0", "\"1\"")) {
            assertInvalid(withSchemaVersion(invalid), "schemaVersion");
        }
    }

    @Test
    void manifestFilesUseTheDocumentedTurboismV1PathIdentityKey() throws Exception {
        assertFileCollision("plugin/lib/e\u0301.jar", "plugin/lib/é.jar");
        assertFileCollision("plugin/lib/Å.jar", "plugin/lib/å.jar");
        assertFileCollision("plugin/lib/Σ.jar", "plugin/lib/ς.jar");
        assertFileCollision("plugin/lib/ς.jar", "plugin/lib/σ.jar");
        assertFileCollision("plugin/lib/ß.jar", "plugin/lib/ẞ.jar");
        assertNotEquals(
                dev.turboism.core.archive.ArchivePaths.pathIdentityKey("plugin/lib/ß.jar"),
                dev.turboism.core.archive.ArchivePaths.pathIdentityKey("plugin/lib/ss.jar"));
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
        DistributionValidationException error =
                assertThrows(DistributionValidationException.class, () -> read(root.toString()));
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
        assertDoesNotThrow(() -> read(root.toString()));
    }

    private static void assertInvalid(ObjectNode root, String path) throws Exception {
        DistributionValidationException error =
                assertThrows(DistributionValidationException.class, () -> read(root.toString()));
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

    private static void read(String json) throws Exception {
        PluginManifestReader.read(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
