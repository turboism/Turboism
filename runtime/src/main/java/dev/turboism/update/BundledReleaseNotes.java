package dev.turboism.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.core.FrameworkBuildInfo;
import dev.turboism.runtime.json.RuntimeJson;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves localized release notes and multi-version changelog ranges from bundled resources and update cache. */
final class BundledReleaseNotes {

    private static final String RELEASE_NOTES_PREFIX = "/META-INF/turboism/release-notes/";
    private static final String CHANGELOG_RESOURCE = RELEASE_NOTES_PREFIX + "CHANGELOG.md";
    private static final String STRUCTURED_CHANGELOG_RESOURCE = RELEASE_NOTES_PREFIX + "changelog.json";
    private static final String STRUCTURED_FORMAT = "turboism.changelog/v1";
    private static final String DEFAULT_LOCALE = "en";
    private static final String DEFAULT_REPOSITORY = "https://github.com/turboism/Turboism";
    private static final int SCHEMA_VERSION = 1;
    private static final int MAX_RESOURCE_BYTES = 256 * 1024;
    private static final int MAX_STRUCTURED_CHANGELOG_BYTES = 2 * 1024 * 1024;
    private static final int MAX_NOTE_LENGTH = 24_000;
    private static final int MAX_RANGE_NOTE_LENGTH = 96_000;
    private static final List<String> SUPPORTED_LOCALES = List.of("en", "zh", "ja", "ko");
    private static final List<String> CATEGORIES =
            List.of("added", "changed", "deprecated", "removed", "fixed", "security", "known_limitations");
    private static final Map<String, Map<String, String>> CATEGORY_HEADINGS = Map.of(
            "en",
            Map.of(
                    "added", "Added",
                    "changed", "Changed",
                    "deprecated", "Deprecated",
                    "removed", "Removed",
                    "fixed", "Fixed",
                    "security", "Security",
                    "known_limitations", "Known limitations"),
            "zh",
            Map.of(
                    "added", "新增",
                    "changed", "变更",
                    "deprecated", "已弃用",
                    "removed", "移除",
                    "fixed", "修复",
                    "security", "安全",
                    "known_limitations", "已知限制"),
            "ja",
            Map.of(
                    "added", "追加",
                    "changed", "変更",
                    "deprecated", "非推奨",
                    "removed", "削除",
                    "fixed", "修正",
                    "security", "セキュリティ",
                    "known_limitations", "既知の制限"),
            "ko",
            Map.of(
                    "added", "추가",
                    "changed", "변경",
                    "deprecated", "폐기 예정",
                    "removed", "제거",
                    "fixed", "수정",
                    "security", "보안",
                    "known_limitations", "알려진 제한 사항"));
    private static final Map<String, String> RANGE_SUMMARY_TEMPLATES = Map.of(
            "en", "Changes from **%s** to **%s** (%d releases).",
            "zh", "从 **%s** 到 **%s** 的全部变更（共 %d 个版本）。",
            "ja", "**%s** から **%s** までの変更（全 %d リリース）。",
            "ko", "**%s**에서 **%s**까지의 변경 사항(총 %d개 릴리스).");
    private static final Map<String, String> SINGLE_RANGE_SUMMARY_TEMPLATES = Map.of(
            "en", "Changes from **%s** to **%s**.",
            "zh", "从 **%s** 到 **%s** 的变更。",
            "ja", "**%s** から **%s** への変更。",
            "ko", "**%s**에서 **%s**으로의 변경 사항.");
    private static final Map<String, String> COMPARE_LINK_TEMPLATES = Map.of(
            "en", "Full changes since %s",
            "zh", "查看自 %s 以来的完整变化",
            "ja", "%s 以降のすべての変更",
            "ko", "%s 이후 전체 변경 사항");
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
        return load(home, info, Optional.empty());
    }

    /**
     * Loads localized notes for {@code info} directly from the bundled structured changelog first across
     * all channels (beta-to-beta, beta-to-stable, stable-to-beta, and stable-to-stable).
     */
    Map<String, String> load(final Path home, final FrameworkBuildInfo info, final Optional<String> previousVersion) {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(previousVersion, "previousVersion");
        final String exactVersion = UpdateVersionOrdering.isValid(info.version())
                ? info.version()
                : info.apiVersion().toString();
        if (previousVersion.isPresent()) {
            final Map<String, String> structuredRange = loadRange(previousVersion.orElseThrow(), exactVersion, false);
            if (!structuredRange.isEmpty()) {
                return structuredRange;
            }
        }
        final Map<String, String> structuredSingle = loadSingleStructuredRelease(exactVersion);
        if (!structuredSingle.isEmpty()) {
            return structuredSingle;
        }
        if (info.channel() != FrameworkBuildInfo.Channel.STABLE && home != null) {
            final Map<String, String> cached = loadCachedDiscoveryNotes(home, info);
            if (!cached.isEmpty()) {
                return cached;
            }
        }
        final String baseVersion = info.apiVersion().toString();
        final Map<String, String> notes = new LinkedHashMap<>(loadBundledJson(baseVersion));
        if (!notes.containsKey(DEFAULT_LOCALE)) {
            loadChangelogSection(baseVersion).ifPresent(english -> notes.put(DEFAULT_LOCALE, english));
        }
        if (!notes.isEmpty()) {
            return Map.copyOf(notes);
        }
        if (home != null) {
            return loadCachedDiscoveryNotes(home, info);
        }
        return Map.of();
    }

    /** Loads merged localized Markdown changes strictly after {@code fromVersion} up to {@code toVersion}. */
    Map<String, String> loadRange(final String fromVersion, final String toVersion) {
        return loadRange(fromVersion, toVersion, false);
    }

    private Map<String, String> loadSingleStructuredRelease(final String version) {
        final Optional<String> normalized = normalizeSemver(version);
        if (normalized.isEmpty()) {
            return Map.of();
        }
        final String target = normalized.orElseThrow();
        final byte[] bytes = readResourceBytes(STRUCTURED_CHANGELOG_RESOURCE, MAX_STRUCTURED_CHANGELOG_BYTES);
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            final JsonNode root = JSON.readTree(bytes);
            if (root == null
                    || !root.isObject()
                    || !STRUCTURED_FORMAT.equals(root.path("format").asText(""))
                    || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                return Map.of();
            }
            final JsonNode releases = root.get("releases");
            if (releases == null || !releases.isArray()) {
                return Map.of();
            }
            for (final JsonNode release : releases) {
                if (!target.equals(release.path("version").asText(""))) {
                    continue;
                }
                final String repository = root.path("repository").asText(DEFAULT_REPOSITORY);
                final Map<String, String> localized = new LinkedHashMap<>();
                for (final String locale : SUPPORTED_LOCALES) {
                    final String rendered = renderSingleReleaseMarkdown(release, locale, repository);
                    if (!rendered.isEmpty() && rendered.length() <= MAX_RANGE_NOTE_LENGTH) {
                        localized.put(locale, rendered);
                    }
                }
                return Map.copyOf(localized);
            }
            return Map.of();
        } catch (IOException | RuntimeException malformed) {
            return Map.of();
        }
    }

    private static String renderSingleReleaseMarkdown(
            final JsonNode release, final String locale, final String repository) {
        final Map<String, String> headings =
                CATEGORY_HEADINGS.getOrDefault(locale, CATEGORY_HEADINGS.get(DEFAULT_LOCALE));
        final List<String> blocks = new ArrayList<>();
        final String summary = resolveLocalizedText(release.get("summary"), locale);
        if (!summary.isEmpty()) {
            blocks.add(summary);
        }
        for (final String category : CATEGORIES) {
            final JsonNode items = release.path("sections").path(category);
            if (!items.isArray()) {
                continue;
            }
            final List<String> bullets = new ArrayList<>();
            for (final JsonNode item : items) {
                final String text = resolveLocalizedText(item, locale);
                if (!text.isEmpty()) {
                    bullets.add("- " + text);
                }
            }
            if (!bullets.isEmpty()) {
                blocks.add("### " + headings.get(category) + "\n\n" + String.join("\n", bullets));
            }
        }
        final String compareBase = release.path("compareBase").asText("");
        final String version = release.path("version").asText("");
        if (!compareBase.isEmpty() && !version.isEmpty()) {
            final String compareLabel = COMPARE_LINK_TEMPLATES
                    .getOrDefault(locale, COMPARE_LINK_TEMPLATES.get(DEFAULT_LOCALE))
                    .formatted(compareBase);
            blocks.add("[" + compareLabel + "](" + repository + "/compare/v" + compareBase + "...v" + version + ")");
        }
        return String.join("\n\n", blocks);
    }

    private Map<String, String> loadRange(
            final String fromVersion, final String toVersion, final boolean requireMultipleReleases) {
        final Optional<String> normalizedFrom = normalizeSemver(fromVersion);
        final Optional<String> normalizedTo = normalizeSemver(toVersion);
        if (normalizedFrom.isEmpty() || normalizedTo.isEmpty()) {
            return Map.of();
        }
        final String start = normalizedFrom.orElseThrow();
        final String end = normalizedTo.orElseThrow();
        if (UpdateVersionOrdering.compare(start, end) >= 0) {
            return Map.of();
        }
        final byte[] bytes = readResourceBytes(STRUCTURED_CHANGELOG_RESOURCE, MAX_STRUCTURED_CHANGELOG_BYTES);
        if (bytes.length == 0) {
            return Map.of();
        }
        try {
            final JsonNode root = JSON.readTree(bytes);
            if (root == null
                    || !root.isObject()
                    || !STRUCTURED_FORMAT.equals(root.path("format").asText(""))
                    || root.path("schemaVersion").asInt(-1) != SCHEMA_VERSION) {
                return Map.of();
            }
            final JsonNode releases = root.get("releases");
            if (releases == null || !releases.isArray() || releases.isEmpty()) {
                return Map.of();
            }
            boolean startKnown = false;
            boolean endKnown = false;
            final List<JsonNode> included = new ArrayList<>();
            for (final JsonNode release : releases) {
                final String version = release.path("version").asText("");
                if (!UpdateVersionOrdering.isValid(version)) {
                    continue;
                }
                if (start.equals(version)) {
                    startKnown = true;
                }
                if (end.equals(version)) {
                    endKnown = true;
                }
                if (UpdateVersionOrdering.compare(start, version) < 0
                        && UpdateVersionOrdering.compare(version, end) <= 0) {
                    included.add(release);
                }
            }
            final String earliest =
                    releases.get(releases.size() - 1).path("version").asText("");
            final boolean isPrereleaseStart = start.contains("-");
            if (!endKnown || included.isEmpty()) {
                return Map.of();
            }
            if (!startKnown
                    && (!isPrereleaseStart
                            || !UpdateVersionOrdering.isValid(earliest)
                            || UpdateVersionOrdering.compare(start, earliest) < 0)) {
                return Map.of();
            }
            if (requireMultipleReleases && included.size() <= 1) {
                return Map.of();
            }
            final String repository = root.path("repository").asText(DEFAULT_REPOSITORY);
            final Map<String, String> localized = new LinkedHashMap<>();
            for (final String locale : SUPPORTED_LOCALES) {
                final String rendered = renderRangeMarkdown(included, start, end, locale, repository);
                if (!rendered.isEmpty() && rendered.length() <= MAX_RANGE_NOTE_LENGTH) {
                    localized.put(locale, rendered);
                }
            }
            return Map.copyOf(localized);
        } catch (IOException | RuntimeException malformed) {
            return Map.of();
        }
    }

    private static String renderRangeMarkdown(
            final List<JsonNode> included,
            final String fromVersion,
            final String toVersion,
            final String locale,
            final String repository) {
        final Map<String, String> headings =
                CATEGORY_HEADINGS.getOrDefault(locale, CATEGORY_HEADINGS.get(DEFAULT_LOCALE));
        final List<String> blocks = new ArrayList<>();
        final String summaryLine = included.size() == 1
                ? SINGLE_RANGE_SUMMARY_TEMPLATES
                        .getOrDefault(locale, SINGLE_RANGE_SUMMARY_TEMPLATES.get(DEFAULT_LOCALE))
                        .formatted(fromVersion, toVersion)
                : RANGE_SUMMARY_TEMPLATES
                        .getOrDefault(locale, RANGE_SUMMARY_TEMPLATES.get(DEFAULT_LOCALE))
                        .formatted(fromVersion, toVersion, included.size());
        blocks.add(summaryLine);
        for (final String category : CATEGORIES) {
            final List<String> bullets = new ArrayList<>();
            for (final JsonNode release : included) {
                final JsonNode items = release.path("sections").path(category);
                if (!items.isArray()) {
                    continue;
                }
                for (final JsonNode item : items) {
                    final String text = resolveLocalizedText(item, locale);
                    if (!text.isEmpty()) {
                        bullets.add("- " + text);
                    }
                }
            }
            if (!bullets.isEmpty()) {
                blocks.add("### " + headings.get(category) + "\n\n" + String.join("\n", bullets));
            }
        }
        final String compareLabel = COMPARE_LINK_TEMPLATES
                .getOrDefault(locale, COMPARE_LINK_TEMPLATES.get(DEFAULT_LOCALE))
                .formatted(fromVersion);
        blocks.add("[" + compareLabel + "](" + repository + "/compare/v" + fromVersion + "...v" + toVersion + ")");
        return String.join("\n\n", blocks);
    }

    private static String resolveLocalizedText(final JsonNode item, final String locale) {
        if (item == null || !item.isObject()) {
            return "";
        }
        final JsonNode preferred = item.get(locale);
        if (preferred != null && preferred.isTextual() && !preferred.textValue().isBlank()) {
            return preferred.textValue().strip();
        }
        final JsonNode fallback = item.get(DEFAULT_LOCALE);
        if (fallback != null && fallback.isTextual() && !fallback.textValue().isBlank()) {
            return fallback.textValue().strip();
        }
        return "";
    }

    private static Optional<String> normalizeSemver(final String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String cleaned = raw.strip();
        if ((cleaned.startsWith("v") || cleaned.startsWith("V")) && cleaned.length() > 1) {
            cleaned = cleaned.substring(1);
        }
        return UpdateVersionOrdering.isValid(cleaned) ? Optional.of(cleaned) : Optional.empty();
    }

    private Map<String, String> loadBundledJson(final String baseVersion) {
        final String resourcePath = RELEASE_NOTES_PREFIX + baseVersion + ".json";
        final byte[] bytes = readResourceBytes(resourcePath, MAX_RESOURCE_BYTES);
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
        final byte[] bytes = readResourceBytes(CHANGELOG_RESOURCE, MAX_RESOURCE_BYTES);
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

    private byte[] readResourceBytes(final String resourcePath, final int maxBytes) {
        try (InputStream stream = resourceOpener.apply(resourcePath)) {
            if (stream == null) {
                return new byte[0];
            }
            final byte[] read = stream.readNBytes(maxBytes + 1);
            return read.length > maxBytes ? new byte[0] : read;
        } catch (IOException | RuntimeException unavailable) {
            return new byte[0];
        }
    }
}
