package dev.turboism.distribution;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.descriptor.DescriptorParseException;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.plugin.PluginJarContract;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class PluginJarInspector {
    private static final String DESCRIPTOR = "META-INF/turboism/plugin.json";
    private static final StrictZipArchive.Limits LIMITS = new StrictZipArchive.Limits(
        PluginArchiveLimits.RAW_MAX,
        PluginArchiveLimits.ENTRY_MAX,
        PluginArchiveLimits.TOTAL_MAX,
        PluginArchiveLimits.ENTRY_COUNT_MAX,
        PluginArchiveLimits.RATIO_MAX
    );

    Inspected inspect(final Path path, final String logicalPath) throws Exception {
        return scan(path, logicalPath, true);
    }

    void inspectLibrary(final Path path, final String logicalPath) throws Exception {
        scan(path, logicalPath, false);
    }

    private static Inspected scan(
        final Path path,
        final String logicalPath,
        final boolean main
    ) throws Exception {
        try {
            return strictScan(path, logicalPath, main);
        } catch (DistributionValidationException exception) {
            if (exception.code().startsWith("ARCHIVE_")) {
                throw ArchivePolicy.problem(
                    "ARTIFACT_JAR_INVALID",
                    "Invalid plugin JAR",
                    logicalPath
                );
            }
            throw exception;
        }
    }

    private static Inspected strictScan(
        final Path path,
        final String logicalPath,
        final boolean main
    ) throws Exception {
        byte[] descriptor = null;
        int descriptors = 0;
        final List<String> content = new ArrayList<>();
        try (StrictZipArchive archive = StrictZipArchive.open(path, LIMITS)) {
            for (StrictZipArchive.Entry entry : archive.entries()) {
                if (entry.directory()) {
                    continue;
                }
                content.add(entry.name());
                if (entry.name().equals(DESCRIPTOR)) {
                    descriptors++;
                    if (main) {
                        descriptor = descriptor(archive, entry);
                    } else {
                        fail("PLUGIN_DESCRIPTOR_COUNT_INVALID", logicalPath);
                    }
                } else {
                    inspectContent(archive, entry, logicalPath);
                }
            }
        }
        require(
            descriptors == (main ? 1 : 0),
            "PLUGIN_DESCRIPTOR_COUNT_INVALID",
            logicalPath
        );
        if (!main) {
            return null;
        }
        final PluginDescriptor parsed = parse(descriptor);
        try {
            PluginJarContract.validate(parsed, content, logicalPath);
        } catch (PluginJarContract.PluginJarContractException exception) {
            throw ArchivePolicy.problem(
                exception.code(),
                exception.getMessage(),
                exception.path()
            );
        }
        return new Inspected(
            PluginDescriptorSnapshot.copyOf(parsed),
            sha256(descriptor)
        );
    }

    private static void inspectContent(
        final StrictZipArchive archive,
        final StrictZipArchive.Entry entry,
        final String logicalPath
    ) throws Exception {
        require(
            !PluginPathPolicy.contamination(entry.name(), false),
            "PLUGIN_CONTENT_CONTAMINATION",
            logicalPath + "!/" + entry.name()
        );
        archive.consume(entry, null);
    }

    private static byte[] descriptor(
        final StrictZipArchive archive,
        final StrictZipArchive.Entry entry
    ) throws Exception {
        require(
            entry.expanded() <= PluginArchiveLimits.JSON_MAX,
            "PLUGIN_DESCRIPTOR_TOO_LARGE",
            DESCRIPTOR
        );
        final ByteArrayOutputStream output = new ByteArrayOutputStream((int) entry.expanded());
        archive.consume(entry, output);
        final byte[] bytes = output.toByteArray();
        require(
            bytes.length < 3
                || (bytes[0] & 255) != 0xef
                || (bytes[1] & 255) != 0xbb
                || (bytes[2] & 255) != 0xbf,
            "PLUGIN_DESCRIPTOR_BOM",
            DESCRIPTOR
        );
        return bytes;
    }

    private static PluginDescriptor parse(final byte[] bytes) throws Exception {
        try {
            final var factory = com.fasterxml.jackson.core.JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build();
            final ObjectMapper mapper = new ObjectMapper(factory)
                .enable(JsonParser.Feature.AUTO_CLOSE_SOURCE)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
            final JsonNode root = mapper.readTree(bytes);
            return new PluginDescriptorParser().parse(root, DESCRIPTOR);
        } catch (DescriptorParseException exception) {
            throw ArchivePolicy.problem(
                exception.code(),
                exception.getMessage(),
                exception.path()
            );
        } catch (Exception exception) {
            throw ArchivePolicy.problem(
                "PLUGIN_META_INVALID_JSON",
                "Invalid plugin descriptor JSON",
                DESCRIPTOR
            );
        }
    }

    private static String sha256(final byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes)
        );
    }

    private static void require(
        final boolean valid,
        final String code,
        final String path
    ) throws Exception {
        if (!valid) {
            fail(code, path);
        }
    }

    private static void fail(
        final String code,
        final String path
    ) throws DistributionValidationException {
        throw ArchivePolicy.problem(code, "Invalid plugin JAR content", path);
    }

    record Inspected(PluginDescriptorSnapshot descriptor, String descriptorSha256) {
    }
}
