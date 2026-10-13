package dev.turboism.update;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.core.FrameworkBuildInfo;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * Strict parser for the {@code /v1/releases/<channel>.json} discovery document
 * served by {@code api.turboism.dev}.
 *
 * <p>Every failure is reported as {@link IllegalArgumentException}; callers treat
 * an unparseable document as an unavailable check, never as up to date.</p>
 */
public final class UpdateDiscoveryParser {
    public static final int MAX_BYTES = 256 * 1024;
    private static final String STATUS_READY = "ready";
    private static final String STATUS_NOT_PUBLISHED = "not_published";
    private static final String STATUS_UNAVAILABLE = "unavailable";
    private static final Pattern BETA_VERSION = Pattern.compile(
            "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\\.(?:0|[1-9][0-9]*))?");
    private static final Pattern NIGHTLY_VERSION =
            Pattern.compile("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-0\\.nightly\\.(?:[1-9][0-9]*))?");
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
                    .enable(com.fasterxml.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final String expectedChannel;

    /** Creates a parser expecting the default {@code stable} release channel. */
    public UpdateDiscoveryParser() {
        this(UpdateDiscovery.CHANNEL_STABLE);
    }

    /** Creates a parser bound to a specific release channel wire name. */
    public UpdateDiscoveryParser(final String expectedChannel) {
        this.expectedChannel = Objects.requireNonNull(expectedChannel, "expectedChannel");
        if (!UpdateDiscovery.isSupportedChannel(expectedChannel)) {
            throw new IllegalArgumentException("unsupported discovery channel: " + expectedChannel);
        }
    }

    /** Creates a parser bound to the release channel of the installed framework build. */
    public UpdateDiscoveryParser(final FrameworkBuildInfo.Channel channel) {
        this(InstalledBuild.channelFor(channel));
    }

    /** Returns the release channel wire name this parser accepts. */
    public String expectedChannel() {
        return expectedChannel;
    }

    /** Parses bounded UTF-8 discovery bytes into a validated discovery record. */
    public UpdateDiscovery parse(final byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw invalid("discovery document size is outside the allowed bound");
        }
        if (bytes.length >= 3 && (bytes[0] & 0xff) == 0xef && (bytes[1] & 0xff) == 0xbb && (bytes[2] & 0xff) == 0xbf) {
            throw invalid("UTF-8 BOM is not accepted");
        }
        final String source;
        try {
            source = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw invalid("discovery document is not valid UTF-8", invalidUtf8);
        }
        try {
            return parseNode(JSON.readTree(source));
        } catch (JsonProcessingException malformed) {
            throw invalid("discovery document is not valid JSON", malformed);
        }
    }

    /** Parses an already detached JSON node, used for the validated restart cache. */
    public UpdateDiscovery parseNode(final JsonNode root) {
        if (!(root instanceof ObjectNode object)) {
            throw invalid("discovery document must be a JSON object");
        }
        final int schemaVersion = integer(object, "schemaVersion");
        if (schemaVersion != UpdateDiscovery.SCHEMA_VERSION) {
            throw invalid("unsupported discovery schema version");
        }
        final String channel = text(object, "channel");
        if (!expectedChannel.equals(channel)) {
            throw invalid("unexpected discovery channel");
        }
        final String statusName = text(object, "status");
        final UpdateDiscovery.Status status = statusFor(statusName);
        final Optional<UpdateDiscovery.Candidate> candidate =
                status == UpdateDiscovery.Status.READY ? Optional.of(candidate(object)) : Optional.empty();
        if (status != UpdateDiscovery.Status.READY) requireAbsentRelease(object);
        try {
            return new UpdateDiscovery(schemaVersion, channel, status, candidate);
        } catch (IllegalArgumentException invalidContract) {
            throw invalid("discovery document failed contract validation", invalidContract);
        }
    }

    private static UpdateDiscovery.Status statusFor(final String value) {
        return switch (value) {
            case STATUS_READY -> UpdateDiscovery.Status.READY;
            case STATUS_NOT_PUBLISHED -> UpdateDiscovery.Status.NOT_PUBLISHED;
            case STATUS_UNAVAILABLE -> UpdateDiscovery.Status.UNAVAILABLE;
            default -> throw invalid("unsupported discovery status");
        };
    }

    private UpdateDiscovery.Candidate candidate(final ObjectNode object) {
        final JsonNode release = required(object, "release");
        if (!(release instanceof ObjectNode node)) {
            throw invalid("release must be a JSON object");
        }
        final String rawVersion = text(node, "version");
        final UpdateVersion version = parseReleaseVersion(rawVersion);
        final List<UpdateDiscovery.Asset> assets = parseAssets(node);
        return new UpdateDiscovery.Candidate(
                version,
                rawVersion,
                optionalPositiveLong(node, "buildNumber"),
                optionalUtcInstant(node, "publishedAt"),
                assets);
    }

    private List<UpdateDiscovery.Asset> parseAssets(final ObjectNode node) {
        final JsonNode assetsNode = node.get("assets");
        if (assetsNode == null || assetsNode.isNull()) {
            return List.of();
        }
        if (!assetsNode.isArray()) {
            throw invalid("assets must be an array");
        }
        final List<UpdateDiscovery.Asset> list = new ArrayList<>();
        for (final JsonNode item : assetsNode) {
            if (!(item instanceof ObjectNode assetObj)) {
                throw invalid("asset must be a JSON object");
            }
            final String name = text(assetObj, "name");
            final String kind = text(assetObj, "kind");
            final Optional<String> variant = optionalText(assetObj, "variant");
            final Optional<String> platform = optionalText(assetObj, "platform");
            final String url = text(assetObj, "url");
            final long size = longValue(assetObj, "size");
            final String sha256 = text(assetObj, "sha256");
            list.add(new UpdateDiscovery.Asset(name, kind, variant, platform, url, size, sha256));
        }
        return Collections.unmodifiableList(list);
    }

    private UpdateVersion parseReleaseVersion(final String rawVersion) {
        try {
            return switch (expectedChannel) {
                case UpdateDiscovery.CHANNEL_STABLE -> UpdateVersion.parse(rawVersion);
                case UpdateDiscovery.CHANNEL_BETA -> {
                    if (!BETA_VERSION.matcher(rawVersion).matches()) {
                        throw invalid("beta release version is not canonical");
                    }
                    yield UpdateVersion.parseInstalled(rawVersion);
                }
                case UpdateDiscovery.CHANNEL_NIGHTLY -> {
                    if (!NIGHTLY_VERSION.matcher(rawVersion).matches()) {
                        throw invalid("nightly release version is not canonical");
                    }
                    yield UpdateVersion.parseInstalled(rawVersion);
                }
                default -> throw invalid("unsupported discovery channel");
            };
        } catch (IllegalArgumentException invalidVersion) {
            throw invalid("release version is not canonical", invalidVersion);
        }
    }

    private static void requireAbsentRelease(final ObjectNode object) {
        final JsonNode release = object.get("release");
        if (release != null && !release.isNull()) {
            throw invalid("a non-ready document must not carry a release");
        }
    }

    private static int integer(final ObjectNode object, final String name) {
        final JsonNode value = required(object, name);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw invalid(name + " must be an integer");
        }
        return value.intValue();
    }

    private static String text(final ObjectNode object, final String name) {
        final JsonNode value = required(object, name);
        if (!value.isTextual() || value.textValue().isBlank()) {
            throw invalid(name + " must be a non-blank string");
        }
        return value.textValue();
    }

    private static Optional<String> optionalText(final ObjectNode object, final String name) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || value.textValue().isBlank()) return Optional.empty();
        return Optional.of(value.textValue());
    }

    private static long longValue(final ObjectNode object, final String name) {
        final JsonNode value = required(object, name);
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(name + " must be an integer");
        }
        final long converted = value.longValue();
        if (converted <= 0) throw invalid(name + " must be positive");
        return converted;
    }

    private static OptionalLong optionalPositiveLong(final ObjectNode object, final String name) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) return OptionalLong.empty();
        if (!value.isIntegralNumber() || !value.canConvertToLong()) {
            throw invalid(name + " must be an integer");
        }
        final long converted = value.longValue();
        if (converted <= 0 || converted > UpdateDiscovery.MAX_BUILD_NUMBER) {
            throw invalid(name + " is outside the allowed range");
        }
        return OptionalLong.of(converted);
    }

    private static Optional<Instant> optionalUtcInstant(final ObjectNode object, final String name) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) return Optional.empty();
        if (!value.isTextual() || !value.textValue().endsWith("Z")) {
            throw invalid(name + " must be a UTC timestamp");
        }
        try {
            return Optional.of(Instant.parse(value.textValue()));
        } catch (DateTimeParseException invalid) {
            throw invalid(name + " must be an ISO-8601 timestamp", invalid);
        }
    }

    private static JsonNode required(final ObjectNode object, final String name) {
        final JsonNode value = object.get(name);
        if (value == null || value.isNull()) throw invalid("missing " + name);
        return value;
    }

    private static IllegalArgumentException invalid(final String message) {
        return new IllegalArgumentException(message);
    }

    private static IllegalArgumentException invalid(final String message, final Throwable cause) {
        return new IllegalArgumentException(message, cause);
    }
}
