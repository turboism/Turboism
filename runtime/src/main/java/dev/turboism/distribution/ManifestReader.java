package dev.turboism.distribution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.Set;

final class ManifestReader {
    static final String NAME = "META-INF/turboism/package.json";
    private static final long MAX_BYTES = 1_048_576;
    private static final Set<String> TOP = Set.of("format", "schemaVersion", "kind", "id",
        "version", "apiVersion", "javaVersion", "artifacts");
    private static final Set<String> ARTIFACT = Set.of("role", "path", "installPath", "sha256", "size");
    private static final ObjectMapper JSON = strictMapper();

    private ManifestReader() {}

    static JsonNode read(InputStream input) throws IOException, DistributionValidationException {
        byte[] bytes = input.readNBytes((int) MAX_BYTES + 1);
        require(bytes.length <= MAX_BYTES, "MANIFEST_TOO_LARGE", "Manifest exceeds limit", NAME);
        require(bytes.length < 3 || (bytes[0] & 255) != 0xef || (bytes[1] & 255) != 0xbb
            || (bytes[2] & 255) != 0xbf, "MANIFEST_BOM", "UTF-8 BOM is forbidden", NAME);
        JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException exception) {
            throw problem("MANIFEST_JSON_INVALID",
                "Malformed framework package manifest JSON", NAME);
        }
        require(root != null && root.isObject(), "MANIFEST_JSON_INVALID", "Manifest must be an object", NAME);
        unknown(root, TOP, "", "MANIFEST_UNKNOWN_FIELD");
        validateHeader(root);
        JsonNode artifacts = root.path("artifacts");
        require(artifacts.isArray(), "MANIFEST_FIELD_INVALID", "artifacts must be an array", "artifacts");
        for (int i = 0; i < artifacts.size(); i++) {
            JsonNode artifact = artifacts.get(i);
            require(artifact.isObject(), "MANIFEST_FIELD_INVALID", "artifact must be an object", "artifacts[" + i + "]");
            unknown(artifact, ARTIFACT, "artifacts[" + i + "].", "ARTIFACT_UNKNOWN_FIELD");
        }
        return root;
    }

    private static ObjectMapper strictMapper() {
        var factory = com.fasterxml.jackson.core.JsonFactory.builder()
            .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
        return new ObjectMapper(factory)
            .enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    }

    private static void validateHeader(JsonNode root) throws DistributionValidationException {
        exact(root, "format", "turboism.framework.package");
        schemaVersion(root, "schemaVersion");
        exact(root, "kind", "framework");
        exact(root, "id", "dev.turboism.framework");
        text(root, "version");
        text(root, "apiVersion");
        integer(root, "javaVersion", 17);
    }

    private static void unknown(JsonNode node, Set<String> allowed, String prefix, String code)
        throws DistributionValidationException {
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            String field = fields.next();
            require(allowed.contains(field), code, "Unknown manifest field: " + field, prefix + field);
        }
    }

    private static void exact(JsonNode root, String field, String value) throws DistributionValidationException {
        require(root.path(field).isTextual() && value.equals(root.path(field).textValue()),
            "MANIFEST_FIELD_INVALID", field + " must be " + value, field);
    }

    private static void text(JsonNode root, String field) throws DistributionValidationException {
        require(root.path(field).isTextual() && !root.path(field).textValue().isBlank(),
            "MANIFEST_FIELD_INVALID", field + " must be non-empty text", field);
    }

    private static void schemaVersion(JsonNode root, String field) throws DistributionValidationException {
        require(ManifestPrimitives.schemaVersion(root.path(field)),
            "MANIFEST_FIELD_INVALID", field + " must be 1", field);
    }

    private static void integer(JsonNode root, String field, int value) throws DistributionValidationException {
        JsonNode node = root.path(field);
        require(node.isIntegralNumber() && java.math.BigInteger.valueOf(value).equals(node.bigIntegerValue()),
            "MANIFEST_FIELD_INVALID", field + " must be " + value, field);
    }

    private static void require(boolean valid, String code, String message, String path)
        throws DistributionValidationException {
        if (!valid) throw problem(code, message, path);
    }

    private static DistributionValidationException problem(String code, String message, String path) {
        return new DistributionValidationException(code, message, path);
    }
}
