package dev.turboism.distribution;

import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.ByteArrayInputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class PluginJarInspector {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";

    Inspected inspect(byte[] bytes, String path) throws Exception {
        Scan scan = verifiedScan(bytes, path);
        require(scan.descriptors() == 1, "PLUGIN_DESCRIPTOR_COUNT_INVALID", DESCRIPTOR);
        PluginDescriptor descriptor = parse(scan.descriptor());
        return new Inspected(descriptor, scan.descriptor().clone());
    }

    void inspectLibrary(byte[] bytes, String path) throws Exception {
        Scan scan = verifiedScan(bytes, path);
        require(scan.descriptors() == 0, "PLUGIN_DESCRIPTOR_COUNT_INVALID", path);
    }

    private static Scan verifiedScan(byte[] bytes, String path) throws Exception {
        List<String> central = NestedZipDirectory.parse(bytes, path);
        Scan scan = scan(bytes, path);
        require(central.equals(scan.names()), DistributionErrors.JAR_INVALID, path);
        return scan;
    }

    private static Scan scan(byte[] bytes, String path) throws Exception {
        java.util.ArrayList<String> names = new java.util.ArrayList<>();
        byte[] descriptor = null;
        int descriptors = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null;) {
                String name = entry.getName();
                ArchivePolicy.safeRelative(name, "NESTED_PATH_UNSAFE", path);
                require(names.size() < ArchivePolicy.ENTRIES_MAX, "NESTED_ENTRY_LIMIT", path);
                require(!names.stream().anyMatch(existing -> existing.equalsIgnoreCase(name)),
                    "NESTED_PATH_COLLISION", path);
                names.add(name);
                byte[] content = zip.readNBytes((int) ArchivePolicy.ENTRY_MAX + 1);
                require(content.length <= ArchivePolicy.ENTRY_MAX, "NESTED_ENTRY_TOO_LARGE", path);
                if (name.equals(DESCRIPTOR)) { descriptor = content; descriptors++; }
                else if (name.endsWith("/" + DESCRIPTOR) || forbidden(name)) {
                    fail("PLUGIN_CONTENT_CONTAMINATION", path);
                }
            }
        }
        require(names.size() == new HashSet<>(names).size(), "NESTED_PATH_COLLISION", path);
        return new Scan(List.copyOf(names), descriptor, descriptors);
    }

    private static boolean forbidden(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        String file = lower.substring(lower.lastIndexOf('/') + 1);
        return lower.startsWith("meta-inf/versions/") || lower.startsWith("dev/turboism/core/")
            || lower.startsWith("dev/turboism/adapter/") || lower.startsWith("dev/turboism/hook/")
            || lower.startsWith("dev/turboism/mapping/") || lower.startsWith("dev/turboism/internal/")
            || lower.startsWith("dev/turboism/sdk/") || lower.startsWith("dev/turboism/test/")
            || lower.startsWith("test/") || lower.startsWith("tests/") || lower.startsWith("scripts/")
            || lower.contains("/test/") || lower.contains("/tests/") || lower.contains("/scripts/")
            || lower.startsWith("com/live2d/") || lower.contains("cubism") || lower.endsWith(".jar")
            || lower.endsWith(".dll") || lower.endsWith(".so") || lower.endsWith(".dylib")
            || lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd")
            || lower.endsWith(".ps1") || lower.endsWith(".sh")
            || file.matches("(?:install|setup)(?:\\..+)?") || file.endsWith("test.class");
    }

    private static PluginDescriptor parse(byte[] bytes) throws Exception {
        require(bytes != null, "PLUGIN_DESCRIPTOR_COUNT_INVALID", DESCRIPTOR);
        require(bytes.length <= 1_048_576, "PLUGIN_DESCRIPTOR_TOO_LARGE", DESCRIPTOR);
        require(bytes.length < 3 || (bytes[0] & 255) != 0xef || (bytes[1] & 255) != 0xbb
            || (bytes[2] & 255) != 0xbf, "PLUGIN_DESCRIPTOR_BOM", DESCRIPTOR);
        try {
            var factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            ObjectMapper mapper = new ObjectMapper(factory)
                .enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = mapper.readTree(bytes);
            return new PluginDescriptorParser().parse(root, DESCRIPTOR);
        } catch (DescriptorParseException exception) {
            throw ArchivePolicy.problem(exception.code(), exception.getMessage(), exception.path());
        } catch (Exception exception) {
            throw ArchivePolicy.problem("PLUGIN_META_INVALID_JSON", "Invalid plugin descriptor JSON", DESCRIPTOR);
        }
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) fail(code, path);
    }

    private static void fail(String code, String path) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid plugin JAR content", path);
    }

    record Inspected(PluginDescriptor descriptor, byte[] descriptorBytes) {}

    private record Scan(List<String> names, byte[] descriptor, int descriptors) {}
}
