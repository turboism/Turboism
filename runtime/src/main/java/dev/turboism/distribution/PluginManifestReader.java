package dev.turboism.distribution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.Set;

final class PluginManifestReader {
    static final String NAME = "META-INF/turboism/package.json";
    private static final Set<String> TOP = Set.of(
        "format", "schemaVersion", "kind", "id", "version", "artifacts");
    private static final Set<String> ARTIFACT = Set.of(
        "role", "path", "installPath", "sha256", "size");
    private static final ObjectMapper JSON = mapper();

    private PluginManifestReader() {}

    static JsonNode read(InputStream input) throws Exception {
        byte[] bytes = input.readNBytes(1_048_577);
        require(bytes.length <= 1_048_576, "MANIFEST_TOO_LARGE", NAME);
        require(!bom(bytes), "MANIFEST_BOM", NAME);
        JsonNode root;
        try { root = JSON.readTree(bytes); }
        catch (Exception exception) { throw problem("MANIFEST_JSON_INVALID", NAME); }
        require(root != null && root.isObject(), "MANIFEST_JSON_INVALID", NAME);
        unknown(root, TOP, "");
        exact(root, "format", "turboism.plugin.package");
        integer(root, "schemaVersion", 1);
        exact(root, "kind", "plugin");
        text(root, "id", "^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$");
        text(root, "version", "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");
        JsonNode artifacts = root.path("artifacts");
        require(artifacts.isArray() && artifacts.size() == 1, "ARTIFACT_ROLES_INVALID", "artifacts");
        JsonNode artifact = artifacts.get(0);
        require(artifact.isObject(), "MANIFEST_FIELD_INVALID", "artifacts[0]");
        unknown(artifact, ARTIFACT, "artifacts[0].");
        return root;
    }

    private static ObjectMapper mapper() {
        var factory = com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory).enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
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
