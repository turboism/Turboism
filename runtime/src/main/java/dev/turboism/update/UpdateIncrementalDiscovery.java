package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Set;

/**
 * Unauthenticated discovery metadata for the offline v1 incremental contract.
 * No URL is derived here: transport and exact-byte signature verification remain separate requirements.
 */
public record UpdateIncrementalDiscovery(
        String version,
        String channel,
        long buildNumber,
        String sourceRevision,
        Reference manifest,
        Reference envelope,
        String objectsSha256) {
    public static final String FORMAT = "turboism.incremental-candidate";
    public static final String ORIGIN = "https://api.turboism.dev";
    public static final String REPOSITORY = "turboism/Turboism";

    public UpdateIncrementalDiscovery {
        if (version == null
                || version.length() > 96
                || !UpdateManifest.validReleaseVersion(version, channel)
                || (!UpdateDiscovery.CHANNEL_STABLE.equals(channel) && !version.contains("-"))) {
            throw new IllegalArgumentException("incremental version/channel mismatch");
        }
        if (buildNumber <= 0 || buildNumber > UpdateDiscovery.MAX_BUILD_NUMBER) {
            throw new IllegalArgumentException("incremental build number out of bounds");
        }
        requireHash(sourceRevision, 40);
        manifest = Objects.requireNonNull(manifest, "manifest");
        envelope = Objects.requireNonNull(envelope, "envelope");
        if (envelope.bytes() > UpdateManifestVerifier.MAX_ENVELOPE_BYTES) {
            throw new IllegalArgumentException("incremental envelope too large");
        }
        requireHash(objectsSha256, 64);
    }

    /** A digest/length identity only; never a feed-supplied download URL or signing key. */
    public record Reference(long bytes, String sha256) {
        public Reference {
            if (bytes <= 0 || bytes > UpdateManifest.MAX_MANIFEST_BYTES) {
                throw new IllegalArgumentException("incremental document size out of bounds");
            }
            requireHash(sha256, 64);
        }
    }

    static UpdateIncrementalDiscovery parse(final JsonNode value) {
        final ObjectNode wrapper = fields(value, "source", "descriptor");
        final ObjectNode source = fields(wrapper.get("source"), "origin", "redirects");
        if (!ORIGIN.equals(text(source, "origin")) || !"reject".equals(text(source, "redirects"))) {
            throw new IllegalArgumentException("incremental source must be fixed origin with no redirects");
        }
        final ObjectNode descriptor = fields(
                wrapper.get("descriptor"),
                "format",
                "schemaVersion",
                "repository",
                "identity",
                "manifest",
                "envelope",
                "objectsSha256");
        final JsonNode schema = descriptor.get("schemaVersion");
        if (!schema.isIntegralNumber()
                || !schema.canConvertToInt()
                || schema.intValue() != 1
                || !FORMAT.equals(text(descriptor, "format"))
                || !REPOSITORY.equals(text(descriptor, "repository"))) {
            throw new IllegalArgumentException("unsupported incremental descriptor");
        }
        final ObjectNode identity =
                fields(descriptor.get("identity"), "version", "channel", "buildNumber", "sourceRevision");
        return new UpdateIncrementalDiscovery(
                text(identity, "version"),
                text(identity, "channel"),
                number(identity, "buildNumber"),
                text(identity, "sourceRevision"),
                reference(descriptor.get("manifest")),
                reference(descriptor.get("envelope")),
                text(descriptor, "objectsSha256"));
    }

    ObjectNode toJson(final ObjectMapper mapper) {
        final ObjectNode wrapper = mapper.createObjectNode();
        wrapper.putObject("source").put("origin", ORIGIN).put("redirects", "reject");
        final ObjectNode descriptor = wrapper.putObject("descriptor");
        descriptor.put("format", FORMAT).put("schemaVersion", 1).put("repository", REPOSITORY);
        descriptor
                .putObject("identity")
                .put("version", version)
                .put("channel", channel)
                .put("buildNumber", buildNumber)
                .put("sourceRevision", sourceRevision);
        descriptor.putObject("manifest").put("bytes", manifest.bytes()).put("sha256", manifest.sha256());
        descriptor.putObject("envelope").put("bytes", envelope.bytes()).put("sha256", envelope.sha256());
        descriptor.put("objectsSha256", objectsSha256);
        return wrapper;
    }

    private static Reference reference(final JsonNode node) {
        final ObjectNode object = fields(node, "bytes", "sha256");
        return new Reference(number(object, "bytes"), text(object, "sha256"));
    }

    private static ObjectNode fields(final JsonNode node, final String... names) {
        final Set<String> allowed = Set.of(names);
        if (!(node instanceof ObjectNode object)
                || object.size() != allowed.size()
                || allowed.stream().anyMatch(name -> !object.hasNonNull(name))) {
            throw new IllegalArgumentException("unknown or missing incremental fields");
        }
        return object;
    }

    private static String text(final ObjectNode object, final String name) {
        final JsonNode node = object.get(name);
        if (!node.isTextual()) throw new IllegalArgumentException("invalid incremental text");
        return node.textValue();
    }

    private static long number(final ObjectNode object, final String name) {
        final JsonNode node = object.get(name);
        if (!node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new IllegalArgumentException("invalid incremental integer");
        }
        return node.longValue();
    }

    private static void requireHash(final String value, final int length) {
        if (value == null || value.length() != length || !value.matches("[0-9a-f]+")) {
            throw new IllegalArgumentException("invalid incremental hash identity");
        }
    }
}
