package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.runtime.json.RuntimeJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves localized release notes for the installed framework build from bundled resources and update cache. */
final class BundledReleaseNotes {

    private static final String RELEASE_NOTES_PREFIX = "/META-INF/turboism/release-notes/";
    private static final String CHANGELOG_RESOURCE = RELEASE_NOTES_PREFIX + "CHANGELOG.md";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RESOURCE_BYTES = 256 * 1024;
    private static final int MAX_NOTE_LENGTH = 24_000;
    private static final List<String> SUPPORTED_LOCALES = List.of("en", "zh", "ja", "ko");
    private static final Pattern CHANGELOG_HEADING = Pattern.compile("^## \\[([^\\]]+)\\](?: - .+)?$");
    private static final ObjectMapper JSON = RuntimeJson.strict();

    private final Function<String, InputStream> resourceOpener;

    BundledReleaseNotes() {
        this(BundledReleaseNotes.class::getResourceAsStream);
    }

    BundledReleaseNotes(final Function<String, InputStream> resourceOpener) {
        this.resourceOpener = Objects.requireNonNull(resourceOpener, "resourceOpener");
    }

    /** Loads localized notes for {@code info}, falling back to cached discovery notes when bundled notes are absent. */
    Map<String, String> load(final Path home, final FrameworkBuildInfo info) {
        Objects.requireNonNull(info, "info");
        if (info.channel() != FrameworkBuildInfo.Channel.STABLE && home != null) {
            final Map<String, String> cached = loadCachedDiscoveryNotes(home, info);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        final String baseVersion = info.apiVersion().toString();
        final Map<String, String> notes = new LinkedHashMap<>(loadBundledJson(baseVersion));
        if (!notes.containsKey("en")) {
            loadChangelogSection(baseVersion).ifPresent(english -> notes.put("en", english));
        }
        if (!notes.isEmpty()) {
            return Map.copyOf(notes);
        }
        if (home != null) {
            return loadCachedDiscoveryNotes(home, info);
        }
        return Map.of();
    }

    private Map<String, String> loadBundledJson(final String baseVersion) {
        final String resourcePath = RELEASE_NOTES_PREFIX + baseVersion + ".json";
        final byte[] bytes = readResourceBytes(resourcePath);
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            final JsonNode root = JSON.readTree(bytes);
            if (root == null
                    || !root.isObject()
                    || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION
                    || !baseVersion.equals(root.path("version").asText(""))) {
                return Map.of();
            }
            final JsonNode locales = root.get("locales");
            if (locales == null || !locales.isObject()) {
                return Map.of();
            }
            final Map<String, String> result = new LinkedHashMap<>();
            for (final String language : SUPPORTED_LOCALES) {
                final JsonNode entry = locales.get(language);
                if (entry != null && entry.isTextual()) {
                    final String text = entry.textValue().strip();
                    if (!text.isEmpty() && text.length() <= MAX_NOTE_LENGTH) {
                        result.put(language, text);
                    }
                }
            }
            return Map.copyOf(result);
        } catch (IOException | RuntimeException malformed) {
            return Map.of();
        }
    }

    private Optional<String> loadChangelogSection(final String baseVersion) {
        final byte[] bytes = readResourceBytes(CHANGELOG_RESOURCE);
        if (bytes.length == 0) {
            return Optional.empty();
        }
        return extractChangelogSection(new String(bytes, StandardCharsets.UTF_8), baseVersion);
    }

    static Optional<String> extractChangelogSection(final String changelog, final String version) {
        if (changelog == null || changelog.isBlank() || version == null || version.isBlank()) {
            return Optional.empty();
        }
        final String[] lines =
                changelog.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        int start = -1;
        int end = lines.length;
        for (int index = 0; index < lines.length; index++) {
            final Matcher heading = CHANGELOG_HEADING.matcher(lines[index]);
            if (!heading.matches()) {
                continue;
            }
            if (start < 0 && version.equals(heading.group(1))) {
                start = index + 1;
            } else if (start >= 0) {
                end = index;
                break;
            }
        }
        if (start < 0) {
            return Optional.empty();
        }
        final StringBuilder body = new StringBuilder();
        for (int index = start; index < end; index++) {
            if (!body.isEmpty()) {
                body.append('\n');
            }
            body.append(lines[index]);
        }
        final String trimmed = body.toString().strip();
        if (trimmed.isEmpty() || trimmed.length() > MAX_NOTE_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    private static Map<String, String> loadCachedDiscoveryNotes(final Path home, final FrameworkBuildInfo info) {
        try {
            final String expectedChannel = new UpdatePreferencesStore(home).effectiveChannel(info.channel());
            final Optional<UpdateDiscovery.Candidate> candidate = new UpdateStateStore(
                            home, expectedChannel, ignored -> {})
                    .read()
                    .cachedResult()
                    .flatMap(UpdateDiscovery::candidate);
            if (candidate.isEmpty()) {
                return Map.of();
            }
            final UpdateDiscovery.Candidate cached = candidate.orElseThrow();
            if (!info.version().equals(cached.versionText())) {
                return Map.of();
            }
            if (info.buildNumber().isPresent()
                    && cached.buildNumber().isPresent()
                    && !info.buildNumber().equals(cached.buildNumber())) {
                return Map.of();
            }
            return cached.releaseNotes();
        } catch (RuntimeException unavailable) {
            return Map.of();
        }
    }

    private byte[] readResourceBytes(final String resourcePath) {
        try (InputStream stream = resourceOpener.apply(resourcePath)) {
            if (stream == null) {
                return new byte[0];
            }
            final byte[] read = stream.readNBytes(MAX_RESOURCE_BYTES + 1);
            return read.length > MAX_RESOURCE_BYTES ? new byte[0] : read;
        } catch (IOException | RuntimeException unavailable) {
            return new byte[0];
        }
    }
}
