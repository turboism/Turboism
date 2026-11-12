package dev.turboism.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.runtime.json.RuntimeJson;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

final class PluginManifestReader {
    static final String NAME = "META-INF/turboism/package.json";
    // packageHash and pluginDescriptorSha256 stay admitted but unused: archives built
    // before the self-hash retirement must keep installing, and the outer archive digest
    // plus catalog descriptorSha256 already bind the bytes an operator downloads.
    private static final Set<String> TOP = Set.of(
            "format",
            "schemaVersion",
            "packageKind",
            "packageId",
            "version",
            "packageHash",
            "pluginDescriptorPath",
            "pluginDescriptorSha256",
            "files",
            "createdAt");
    private static final Set<String> FILE = Set.of("path", "size", "sha256", "role");
    private static final ObjectMapper JSON = mapper();

    private PluginManifestReader() {}

    static JsonNode read(InputStream input) throws Exception {
        byte[] bytes = dev.turboism.sdk.io.BoundedInput.readNBytes(
                input, PluginArchiveLimits.JSON_MAX, () -> problem("MANIFEST_TOO_LARGE", NAME));
        require(!bom(bytes), "MANIFEST_BOM", NAME);
        JsonNode root = parse(bytes);
        unknown(root, TOP, "");
        exact(root, "format", "turboism.distribution.plugin-package");
        schemaVersion(root, "schemaVersion");
        exact(root, "packageKind", "PLUGIN");
        require(ManifestPrimitives.packageId(root.path("packageId")), "MANIFEST_FIELD_INVALID", "packageId");
        text(root, "version", strictVersion());
        exact(root, "pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        timestamp(root);
        files(root.path("files"));
        return root;
    }

    private static JsonNode parse(byte[] bytes) throws Exception {
        try {
            JsonNode root = JSON.readTree(bytes);
            require(root != null && root.isObject(), "MANIFEST_JSON_INVALID", NAME);
            return root;
        } catch (DistributionValidationException exception) {
            throw exception;
        } catch (Exception exception) {
            throw problem("MANIFEST_JSON_INVALID", NAME);
        }
    }

    private static void files(JsonNode files) throws Exception {
        require(files.isArray() && !files.isEmpty(), "MANIFEST_FIELD_INVALID", "files");
        String previous = null;
        Set<String> pathIdentityKeys = new HashSet<>();
        String unsafePath = null;
        int main = 0;
        for (int index = 0; index < files.size(); index++) {
            JsonNode file = files.get(index);
            require(file.isObject(), "MANIFEST_FIELD_INVALID", "files[" + index + "]");
            unknown(file, FILE, "files[" + index + "].");
            String path = fileText(file, "path", index, ".+");
            String identityKey = dev.turboism.core.archive.ArchivePaths.pathIdentityKey(path);
            require(pathIdentityKeys.add(identityKey), "MANIFEST_FILE_PATH_COLLISION", "files[" + index + "].path");
            if (!dev.turboism.core.archive.ArchivePaths.relativePath(path) && unsafePath == null) {
                unsafePath = "files[" + index + "].path";
            }
            String orderKey = "plugin/plugin.jar".equals(path) ? "0" : "1" + path;
            require(previous == null || previous.compareTo(orderKey) < 0, "MANIFEST_FILE_ORDER_INVALID", "files");
            previous = orderKey;
            fileText(file, "sha256", index, "[0-9a-f]{64}");
            require(
                    ManifestPrimitives.byteCount(file.path("size")),
                    "MANIFEST_FIELD_INVALID",
                    "files[" + index + "].size");
            String role = fileText(file, "role", index, "PLUGIN_JAR|PLUGIN_LIBRARY");
            boolean isMain = "plugin/plugin.jar".equals(path) && "PLUGIN_JAR".equals(role);
            boolean isLibrary = path.matches("plugin/lib/[^/]+\\.jar") && "PLUGIN_LIBRARY".equals(role);
            require(isMain || isLibrary, "ARTIFACT_PATH_INVALID", "files[" + index + "].path");
            if (isMain) main++;
        }
        require(unsafePath == null, "ARCHIVE_PATH_UNSAFE", unsafePath);
        require(
                main == 1
                        && "plugin/plugin.jar".equals(files.get(0).path("path").textValue()),
                "ARTIFACT_ROLES_INVALID",
                "files");
    }

    private static String fileText(JsonNode node, String field, int index, String regex) throws Exception {
        require(
                node.path(field).isTextual() && node.path(field).textValue().matches(regex),
                "MANIFEST_FIELD_INVALID",
                "files[" + index + "]." + field);
        return node.path(field).textValue();
    }

    private static void timestamp(JsonNode root) throws Exception {
        require(ManifestPrimitives.timestamp(root.path("createdAt")), "MANIFEST_FIELD_INVALID", "createdAt");
    }

    private static ObjectMapper mapper() {
        return RuntimeJson.strict();
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
        require(
                root.path(field).isTextual() && value.equals(root.path(field).textValue()),
                "MANIFEST_FIELD_INVALID",
                field);
    }

    private static void schemaVersion(JsonNode root, String field) throws Exception {
        require(ManifestPrimitives.schemaVersion(root.path(field)), "MANIFEST_FIELD_INVALID", field);
    }

    private static void text(JsonNode root, String field, String regex) throws Exception {
        require(
                root.path(field).isTextual() && root.path(field).textValue().matches(regex),
                "MANIFEST_FIELD_INVALID",
                field);
    }

    private static boolean bom(byte[] bytes) {
        return bytes.length >= 3 && (bytes[0] & 255) == 0xef && (bytes[1] & 255) == 0xbb && (bytes[2] & 255) == 0xbf;
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) throw problem(code, path);
    }

    private static DistributionValidationException problem(String code, String path) {
        return ArchivePolicy.problem(code, "Invalid plugin package manifest", path);
    }
}
