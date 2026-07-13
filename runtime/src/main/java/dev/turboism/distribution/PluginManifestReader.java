package dev.turboism.distribution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Set;

final class PluginManifestReader {
    static final String NAME = "META-INF/turboism/package.json";
    private static final Set<String> TOP = Set.of("format", "schemaVersion", "packageKind",
        "packageId", "version", "packageHash", "pluginDescriptorPath",
        "pluginDescriptorSha256", "files", "createdAt");
    private static final Set<String> FILE = Set.of("path", "size", "sha256", "role");
    private static final ObjectMapper JSON = mapper();

    private PluginManifestReader() {}

    static JsonNode read(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(PluginArchiveLimits.JSON_MAX + 1);
        require(bytes.length <= PluginArchiveLimits.JSON_MAX, "MANIFEST_TOO_LARGE", NAME);
        require(!bom(bytes), "MANIFEST_BOM", NAME);
        JsonNode root = parse(bytes);
        unknown(root, TOP, "");
        exact(root, "format", "turboism.distribution.plugin-package");
        integer(root, "schemaVersion", 1);
        exact(root, "packageKind", "PLUGIN");
        require(ManifestPrimitives.packageId(root.path("packageId")),
            "MANIFEST_FIELD_INVALID", "packageId");
        text(root, "version", strictVersion());
        text(root, "packageHash", "[0-9a-f]{64}");
        exact(root, "pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        text(root, "pluginDescriptorSha256", "[0-9a-f]{64}");
        timestamp(root);
        files(root.path("files"));
        require(root.path("packageHash").textValue().equals(canonicalHash(root)),
            "PACKAGE_HASH_MISMATCH", "packageHash");
        return root;
    }

    private static JsonNode parse(byte[] bytes) throws Exception {
        try {
            JsonNode root = JSON.readTree(bytes);
            require(root != null && root.isObject(), "MANIFEST_JSON_INVALID", NAME);
            return root;
        } catch (DistributionValidationException exception) { throw exception; }
        catch (Exception exception) { throw problem("MANIFEST_JSON_INVALID", NAME); }
    }

    private static void files(JsonNode files) throws Exception {
        require(files.isArray() && !files.isEmpty(), "MANIFEST_FIELD_INVALID", "files");
        String previous = null;
        int main = 0;
        for (int index = 0; index < files.size(); index++) {
            JsonNode file = files.get(index);
            require(file.isObject(), "MANIFEST_FIELD_INVALID", "files[" + index + "]");
            unknown(file, FILE, "files[" + index + "].");
            String path = fileText(file, "path", index, ".+");
            require(ManifestPrimitives.relativePath(path),
                "ARCHIVE_PATH_UNSAFE", "files[" + index + "].path");
            String orderKey = "plugin/plugin.jar".equals(path) ? "0" : "1" + path;
            require(previous == null || previous.compareTo(orderKey) < 0,
                "MANIFEST_FILE_ORDER_INVALID", "files");
            previous = orderKey;
            fileText(file, "sha256", index, "[0-9a-f]{64}");
            require(ManifestPrimitives.byteCount(file.path("size")),
                "MANIFEST_FIELD_INVALID", "files[" + index + "].size");
            String role = fileText(file, "role", index, "PLUGIN_JAR|PLUGIN_LIBRARY");
            boolean isMain = "plugin/plugin.jar".equals(path) && "PLUGIN_JAR".equals(role);
            boolean isLibrary = path.matches("plugin/lib/[^/]+\\.jar") && "PLUGIN_LIBRARY".equals(role);
            require(isMain || isLibrary, "ARTIFACT_PATH_INVALID", "files[" + index + "].path");
            if (isMain) main++;
        }
        require(main == 1 && "plugin/plugin.jar".equals(files.get(0).path("path").textValue()),
            "ARTIFACT_ROLES_INVALID", "files");
    }

    private static String fileText(JsonNode node, String field, int index, String regex) throws Exception {
        require(node.path(field).isTextual() && node.path(field).textValue().matches(regex),
            "MANIFEST_FIELD_INVALID", "files[" + index + "]." + field);
        return node.path(field).textValue();
    }

    private static void timestamp(JsonNode root) throws Exception {
        require(ManifestPrimitives.timestamp(root.path("createdAt")),
            "MANIFEST_FIELD_INVALID", "createdAt");
    }

    private static String canonicalHash(JsonNode root) throws Exception {
        return CanonicalJson.sha256Without(root, "packageHash");
    }

    private static ObjectMapper mapper() {
        var factory = com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory).enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static String strictVersion() {
        return "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)";
    }

    private static void unknown(JsonNode node, Set<String> fields, String prefix) throws Exception {
        var names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            require(fields.contains(name), "MANIFEST_UNKNOWN_FIELD", prefix + name);
        }
    }

    private static void exact(JsonNode root, String field, String value) throws Exception {
        require(root.path(field).isTextual() && value.equals(root.path(field).textValue()),
            "MANIFEST_FIELD_INVALID", field);
    }

    private static void integer(JsonNode root, String field, int value) throws Exception {
        require(root.path(field).isIntegralNumber() && root.path(field).intValue() == value,
            "MANIFEST_FIELD_INVALID", field);
    }

    private static void text(JsonNode root, String field, String regex) throws Exception {
        require(root.path(field).isTextual() && root.path(field).textValue().matches(regex),
            "MANIFEST_FIELD_INVALID", field);
    }

    private static boolean bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 255) == 0xef
            && (bytes[1] & 255) == 0xbb && (bytes[2] & 255) == 0xbf;
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw problem(code, path);
    }

    private static DistributionValidationException problem(String code, String path) {
        return ArchivePolicy.problem(code, "Invalid plugin package manifest", path);
    }
}
