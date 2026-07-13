package dev.turboism.distribution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

final class PluginJarInspector {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";
    private static final StrictZipArchive.Limits LIMITS = new StrictZipArchive.Limits(
        PluginArchiveLimits.RAW_MAX, PluginArchiveLimits.ENTRY_MAX, PluginArchiveLimits.TOTAL_MAX,
        PluginArchiveLimits.ENTRY_COUNT_MAX, PluginArchiveLimits.RATIO_MAX);

    Inspected inspect(Path path, String logicalPath) throws Exception {
        return scan(path, logicalPath, true);
    }

    void inspectLibrary(Path path, String logicalPath) throws Exception {
        scan(path, logicalPath, false);
    }

    private static Inspected scan(Path path, String logicalPath, boolean main) throws Exception {
        try {
            return strictScan(path, logicalPath, main);
        } catch (DistributionValidationException exception) {
            if (exception.code().startsWith("ARCHIVE_")) {
                throw ArchivePolicy.problem("ARTIFACT_JAR_INVALID", "Invalid plugin JAR", logicalPath);
            }
            throw exception;
        }
    }

    private static Inspected strictScan(Path path, String logicalPath, boolean main) throws Exception {
        byte[] descriptor = null;
        int descriptors = 0;
        try (StrictZipArchive archive = StrictZipArchive.open(path, LIMITS)) {
            for (StrictZipArchive.Entry entry : archive.entries()) {
                if (entry.directory()) continue;
                if (entry.name().equals(DESCRIPTOR)) {
                    descriptors++;
                    if (main) descriptor = descriptor(archive, entry);
                    else fail("PLUGIN_DESCRIPTOR_COUNT_INVALID", logicalPath);
                } else inspectContent(archive, entry, logicalPath);
            }
        }
        require(descriptors == (main ? 1 : 0), "PLUGIN_DESCRIPTOR_COUNT_INVALID", logicalPath);
        if (!main) return null;
        PluginDescriptor parsed = parse(descriptor);
        return new Inspected(PluginDescriptorSnapshot.copyOf(parsed), sha256(descriptor));
    }

    private static void inspectContent(StrictZipArchive archive, StrictZipArchive.Entry entry,
                                       String logicalPath) throws Exception {
        require(!PluginPathPolicy.contamination(entry.name(), false),
            "PLUGIN_CONTENT_CONTAMINATION", logicalPath + "!/" + entry.name());
        archive.consume(entry, null);
    }

    private static byte[] descriptor(StrictZipArchive archive, StrictZipArchive.Entry entry) throws Exception {
        require(entry.expanded() <= PluginArchiveLimits.JSON_MAX, "PLUGIN_DESCRIPTOR_TOO_LARGE", DESCRIPTOR);
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) entry.expanded());
        archive.consume(entry, output);
        byte[] bytes = output.toByteArray();
        require(bytes.length < 3 || (bytes[0] & 255) != 0xef || (bytes[1] & 255) != 0xbb
            || (bytes[2] & 255) != 0xbf, "PLUGIN_DESCRIPTOR_BOM", DESCRIPTOR);
        return bytes;
    }

    private static PluginDescriptor parse(byte[] bytes) throws Exception {
        try {
            var factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
            ObjectMapper mapper = new ObjectMapper(factory).enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            JsonNode root = mapper.readTree(bytes);
            return new PluginDescriptorParser().parse(root, DESCRIPTOR);
        } catch (DescriptorParseException exception) {
            throw ArchivePolicy.problem(exception.code(), exception.getMessage(), exception.path());
        } catch (Exception exception) {
            throw ArchivePolicy.problem("PLUGIN_META_INVALID_JSON", "Invalid plugin descriptor JSON", DESCRIPTOR);
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void require(boolean valid, String code, String path) throws Exception {
        if (!valid) fail(code, path);
    }

    private static void fail(String code, String path) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid plugin JAR content", path);
    }

    record Inspected(PluginDescriptorSnapshot descriptor, String descriptorSha256) {}
}
