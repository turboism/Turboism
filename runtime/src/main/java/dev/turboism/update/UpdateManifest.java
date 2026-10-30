package dev.turboism.update;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic immutable release manifest describing framework-owned files.
 *
 * <p>Enforces strict JSON schema, bounds, field types, and provides project-specific deterministic serialization (not RFC 8785/JCS).</p>
 */
public record UpdateManifest(
        int schemaVersion,
        String keyId,
        String version,
        String channel,
        long buildNumber,
        String sourceRevision,
        String minimumUpdaterVersion,
        String targetPlatform,
        List<UpdateManifestEntry> entries) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_MANIFEST_BYTES = 1024 * 1024; // 1 MiB
    public static final int MAX_ENTRIES = 512;
    public static final long MAX_FILE_BYTES = 512L * 1024 * 1024; // 512 MiB per entry

    private static final Pattern KEY_ID_PATTERN = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final Pattern CHANNEL_PATTERN = Pattern.compile("stable|beta|nightly");
    private static final Pattern SOURCE_REVISION_PATTERN = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern TARGET_PLATFORM_PATTERN = Pattern.compile("windows-x64|all");

    private static final ObjectMapper STRICT_MAPPER = new ObjectMapper(JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public UpdateManifest {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported schemaVersion: " + schemaVersion);
        }
        Objects.requireNonNull(keyId, "keyId");
        if (keyId.length() > 64 || !KEY_ID_PATTERN.matcher(keyId).matches()) {
            throw new IllegalArgumentException("invalid keyId: " + keyId);
        }
        Objects.requireNonNull(version, "version");
        if (version.length() > 128 || !validReleaseVersion(version, channel)) {
            throw new IllegalArgumentException("invalid version: " + version);
        }
        Objects.requireNonNull(channel, "channel");
        if (!CHANNEL_PATTERN.matcher(channel).matches()) {
            throw new IllegalArgumentException("invalid channel: " + channel);
        }
        if (buildNumber <= 0 || buildNumber > UpdateDiscovery.MAX_BUILD_NUMBER) {
            throw new IllegalArgumentException("buildNumber must be positive");
        }
        Objects.requireNonNull(sourceRevision, "sourceRevision");
        if (!SOURCE_REVISION_PATTERN.matcher(sourceRevision).matches()) {
            throw new IllegalArgumentException("sourceRevision must be a 40-character lowercase hex commit hash");
        }
        Objects.requireNonNull(minimumUpdaterVersion, "minimumUpdaterVersion");
        // Verify minimumUpdaterVersion is semantically parseable by UpdateVersion
        try {
            if (minimumUpdaterVersion.length() > 128
                    || !minimumUpdaterVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?"))
                throw new IllegalArgumentException("version too long");
            UpdateVersion.parseInstalled(minimumUpdaterVersion);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "minimumUpdaterVersion must be parseable version: " + minimumUpdaterVersion, e);
        }

        Objects.requireNonNull(targetPlatform, "targetPlatform");
        if (!TARGET_PLATFORM_PATTERN.matcher(targetPlatform).matches()) {
            throw new IllegalArgumentException("unsupported targetPlatform: " + targetPlatform);
        }
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty() || entries.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException("entries count must be between 1 and " + MAX_ENTRIES);
        }
        final Set<String> seen = new HashSet<>();
        for (final UpdateManifestEntry entry : entries) {
            Objects.requireNonNull(entry, "manifest entry");
            if (!seen.add(entry.relativePath().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("duplicate case-folded manifest path: " + entry.relativePath());
            }
        }
        entries = entries.stream()
                .sorted(Comparator.comparing(UpdateManifestEntry::relativePath))
                .toList();
    }

    /**
     * Project deterministic UTF-8 JSON: alphabetic keys, path-sorted entries, no whitespace,
     * decimal Java integers, Jackson string escaping. This is not RFC 8785/JCS.
     * Signatures always cover the exact published bytes, including noncanonical input.
     */
    public byte[] toCanonicalBytes() {
        final ObjectNode root = STRICT_MAPPER.createObjectNode();
        root.put("buildNumber", buildNumber);
        root.put("channel", channel);
        final ArrayNode array = root.putArray("entries");
        for (final UpdateManifestEntry entry : entries) {
            final ObjectNode item = array.addObject();
            item.put("bytes", entry.bytes());
            item.put("contentId", entry.contentId());
            item.put("relativePath", entry.relativePath());
            item.put("sha256", entry.sha256());
        }
        root.put("keyId", keyId);
        root.put("minimumUpdaterVersion", minimumUpdaterVersion);
        root.put("schemaVersion", schemaVersion);
        root.put("sourceRevision", sourceRevision);
        root.put("targetPlatform", targetPlatform);
        root.put("version", version);
        try {
            final byte[] result = STRICT_MAPPER.writeValueAsBytes(root);
            if (result.length > MAX_MANIFEST_BYTES) throw new IllegalStateException("serialized manifest too large");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot serialize manifest", impossible);
        }
    }

    // Same release forms admitted by UpdateDiscoveryParser; numeric components use UpdateVersion.
    static boolean validReleaseVersion(final String version, final String channel) {
        if (version == null || channel == null) return false;
        try {
            final UpdateVersion core = UpdateVersion.parseInstalled(version);
            final String suffix = version.substring(core.toString().length());
            return switch (channel) {
                case "stable" -> suffix.isEmpty();
                case "beta" -> suffix.isEmpty() || suffix.matches("-(alpha|beta|rc)\\.(0|[1-9][0-9]*)");
                case "nightly" -> suffix.isEmpty() || suffix.matches("-0\\.nightly\\.[1-9][0-9]*");
                default -> false;
            };
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    static void requireFields(final ObjectNode node, final Set<String> fields) throws IOException {
        if (node.size() != fields.size()) throw new IOException("unknown or missing JSON fields");
        for (final String field : fields) {
            if (!node.has(field)) throw new IOException("missing JSON field: " + field);
        }
    }

    static String strictUtf8(final byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    /** Parses a manifest from UTF-8 JSON bytes with strict duplicate and field checking. */
    public static UpdateManifest parse(final byte[] jsonBytes) throws IOException {
        Objects.requireNonNull(jsonBytes, "jsonBytes");
        if (jsonBytes.length == 0 || jsonBytes.length > MAX_MANIFEST_BYTES) {
            throw new IOException("manifest size outside bounds: " + jsonBytes.length + " bytes");
        }
        final JsonNode tree = STRICT_MAPPER.readTree(strictUtf8(jsonBytes));
        if (!(tree instanceof ObjectNode root)) {
            throw new IOException("manifest JSON root is not an object");
        }

        requireFields(
                root,
                Set.of(
                        "schemaVersion",
                        "keyId",
                        "version",
                        "channel",
                        "buildNumber",
                        "sourceRevision",
                        "minimumUpdaterVersion",
                        "targetPlatform",
                        "entries"));
        try {
            final int schemaVersion = requireInt(root, "schemaVersion");
            final String keyId = requireString(root, "keyId");
            final String version = requireString(root, "version");
            final String channel = requireString(root, "channel");
            final long buildNumber = requireLong(root, "buildNumber");
            final String sourceRevision = requireString(root, "sourceRevision");
            final String minimumUpdaterVersion = requireString(root, "minimumUpdaterVersion");
            final String targetPlatform = requireString(root, "targetPlatform");

            final JsonNode entriesNode = root.get("entries");
            if (!(entriesNode instanceof ArrayNode entriesArray)) {
                throw new IOException("manifest entries field is not an array");
            }
            if (entriesArray.size() > MAX_ENTRIES) {
                throw new IOException("too many manifest entries: " + entriesArray.size());
            }

            final List<UpdateManifestEntry> entries = new ArrayList<>(entriesArray.size());
            for (int i = 0; i < entriesArray.size(); i++) {
                final JsonNode item = entriesArray.get(i);
                if (!(item instanceof ObjectNode entryObject)) {
                    throw new IOException("manifest entry at index " + i + " is not an object");
                }
                requireFields(entryObject, Set.of("relativePath", "bytes", "sha256", "contentId"));
                final String relativePath = requireString(entryObject, "relativePath");
                final long bytes = requireLong(entryObject, "bytes");
                final String sha256 = requireString(entryObject, "sha256");
                final String contentId = requireString(entryObject, "contentId");
                entries.add(new UpdateManifestEntry(relativePath, bytes, sha256, contentId));
            }

            return new UpdateManifest(
                    schemaVersion,
                    keyId,
                    version,
                    channel,
                    buildNumber,
                    sourceRevision,
                    minimumUpdaterVersion,
                    targetPlatform,
                    entries);
        } catch (IllegalArgumentException e) {
            throw new IOException("malformed manifest structure: " + e.getMessage(), e);
        }
    }

    private static String requireString(final ObjectNode node, final String field) throws IOException {
        final JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) {
            throw new IOException("manifest missing required text field: " + field);
        }
        return value.textValue();
    }

    private static int requireInt(final ObjectNode node, final String field) throws IOException {
        final JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IOException("manifest missing required integer field: " + field);
        }
        return value.intValue();
    }

    private static long requireLong(final ObjectNode node, final String field) throws IOException {
        final JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IOException("manifest missing required long field: " + field);
        }
        return value.longValue();
    }
}
