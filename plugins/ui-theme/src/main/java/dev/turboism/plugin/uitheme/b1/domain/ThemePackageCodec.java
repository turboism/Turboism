package dev.turboism.plugin.uitheme.b1.domain;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;

public final class ThemePackageCodec {

    public static final String THEME_PROPERTIES = "theme.properties";
    public static final String COLORS_PROPERTIES = "colors.properties";
    public static final String GENERATOR_METADATA = "generator-metadata.properties";
    public static final String README = "README.md";
    public static final String LICENSE = "LICENSE";
    private static final List<String> ENTRY_ORDER = List.of(
        THEME_PROPERTIES, COLORS_PROPERTIES, GENERATOR_METADATA, README, LICENSE
    );
    private static final Set<String> THEME_KEYS = Set.of(
        "id", "name", "description", "icons", "author", "url", "version", "extends", "base", "built-in",
        "theme.id", "theme.name", "theme.description"
    );
    private static final int MAX_ENTRIES = 5;
    private static final int MAX_TOTAL_BYTES = 1_048_576;
    private static final int MAX_PROPERTIES_BYTES = 262_144;
    private static final int MAX_TEXT_BYTES = 524_288;
    private static final int MAX_KEYS = 512;

    private ThemePackageCodec() {
    }

    public static DecodeResult decode(final List<ThemePackageEntry> rawEntries) {
        Objects.requireNonNull(rawEntries, "rawEntries");
        final List<Issue> issues = new ArrayList<>();
        if (rawEntries.size() > MAX_ENTRIES) {
            issues.add(new Issue(IssueCode.ENTRY_COUNT_LIMIT, ""));
        }
        final String root = detectRoot(rawEntries, issues);
        final LinkedHashMap<String, ThemePackageEntry> entries = new LinkedHashMap<>();
        final Set<String> duplicates = new HashSet<>();
        long total = 0;
        for (ThemePackageEntry raw : rawEntries) {
            Objects.requireNonNull(raw, "entry");
            total += raw.size();
            final String relative = relativeName(raw.name(), root);
            if (relative == null || !ENTRY_ORDER.contains(relative)) {
                issues.add(new Issue(IssueCode.ENTRY_UNKNOWN, raw.name()));
                continue;
            }
            if (entries.putIfAbsent(relative, raw) != null && duplicates.add(relative)) {
                issues.add(new Issue(IssueCode.ENTRY_DUPLICATE, relative));
            }
            final int limit = relative.endsWith(".properties") ? MAX_PROPERTIES_BYTES : MAX_TEXT_BYTES;
            if (raw.size() > limit) {
                issues.add(new Issue(IssueCode.ENTRY_SIZE_LIMIT, relative));
            }
        }
        if (total > MAX_TOTAL_BYTES) {
            issues.add(new Issue(IssueCode.TOTAL_SIZE_LIMIT, ""));
        }
        for (String required : List.of(THEME_PROPERTIES, COLORS_PROPERTIES)) {
            if (!entries.containsKey(required)) {
                issues.add(new Issue(IssueCode.ENTRY_MISSING, required));
            }
        }
        if (!issues.isEmpty()) {
            return invalid(issues);
        }

        final Map<String, String> theme = loadProperties(entries.get(THEME_PROPERTIES), true, issues);
        final Map<String, String> colors = loadProperties(entries.get(COLORS_PROPERTIES), false, issues);
        final Map<String, String> generator = entries.containsKey(GENERATOR_METADATA)
            ? loadProperties(entries.get(GENERATOR_METADATA), false, issues)
            : Map.of();
        if (!issues.isEmpty()) {
            return invalid(issues);
        }
        for (String key : theme.keySet()) {
            if (!THEME_KEYS.contains(key)) {
                issues.add(new Issue(IssueCode.KEY_UNKNOWN, key));
            }
        }
        if (!issues.isEmpty()) {
            return invalid(issues);
        }
        final String id = firstNonEmpty(theme.get("id"), theme.get("theme.id"));
        final String name = firstNonEmpty(theme.get("name"), theme.get("theme.name"));
        final String description = firstNonEmpty(theme.get("description"), theme.get("theme.description"));
        if (id.isEmpty() || name.isEmpty()) {
            issues.add(new Issue(IssueCode.METADATA_INVALID, THEME_PROPERTIES));
        }
        if (!BuiltinThemeCatalog.isReviewedBuiltin(id) && !ThemePackageCatalog.isValidId(id)) {
            issues.add(new Issue(IssueCode.METADATA_INVALID, "id"));
        }
        if (!issues.isEmpty()) {
            return invalid(issues);
        }
        final ThemePackageMetadata metadata = new ThemePackageMetadata(
            id,
            name,
            description,
            firstNonEmpty(theme.get("author")),
            firstNonEmpty(theme.get("url")),
            firstNonEmpty(theme.get("version")),
            emptyToNull(firstNonEmpty(theme.get("extends"))),
            parseBase(theme.get("base")),
            parseIcons(theme.get("icons")),
            BuiltinThemeCatalog.isReviewedBuiltin(id)
        );
        final String readme = entries.containsKey(README) ? decodeText(entries.get(README), issues) : null;
        final String license = entries.containsKey(LICENSE) ? decodeText(entries.get(LICENSE), issues) : null;
        return issues.isEmpty()
            ? new DecodeResult(Optional.of(new ThemePackageData(metadata, colors, generator, readme, license)), List.of())
            : invalid(issues);
    }

    public static Map<String, byte[]> encodeDirectory(final ThemePackageData data) {
        Objects.requireNonNull(data, "data");
        final LinkedHashMap<String, byte[]> result = new LinkedHashMap<>();
        final LinkedHashMap<String, String> theme = new LinkedHashMap<>();
        theme.put("id", data.metadata().id());
        theme.put("name", data.metadata().name());
        putIfNotEmpty(theme, "description", data.metadata().description());
        putIfNotEmpty(theme, "icons", data.metadata().icons().name().toLowerCase(java.util.Locale.ROOT));
        putIfNotEmpty(theme, "author", data.metadata().author());
        putIfNotEmpty(theme, "url", data.metadata().url());
        putIfNotEmpty(theme, "version", data.metadata().version());
        putIfNotEmpty(theme, "extends", data.metadata().parentId());
        theme.put("base", data.metadata().base().name().toLowerCase(java.util.Locale.ROOT));
        if (data.metadata().builtIn()) {
            theme.put("built-in", "true");
        }
        result.put(THEME_PROPERTIES, storeProperties(theme, "Turboism theme metadata"));
        result.put(COLORS_PROPERTIES, storeProperties(data.colors(), "Turboism theme colors"));
        if (!data.generatorMetadata().isEmpty()) {
            result.put(GENERATOR_METADATA, storeProperties(data.generatorMetadata(), "Turboism theme generator metadata"));
        }
        if (data.readme() != null) {
            result.put(README, data.readme().getBytes(StandardCharsets.UTF_8));
        }
        if (data.license() != null) {
            result.put(LICENSE, data.license().getBytes(StandardCharsets.UTF_8));
        }
        return immutableBytes(result);
    }

    public static Map<String, byte[]> encodeZip(final ThemePackageData data) {
        final LinkedHashMap<String, byte[]> wrapped = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : encodeDirectory(data).entrySet()) {
            wrapped.put(data.metadata().id() + "/" + entry.getKey(), entry.getValue());
        }
        return immutableBytes(wrapped);
    }

    public static ConflictResult resolveConflict(
        final ThemePackageData data,
        final Set<String> existingIds,
        final Set<String> builtInIds,
        final ConflictOutcome outcome,
        final String saveAsId
    ) {
        Objects.requireNonNull(data, "data");
        Objects.requireNonNull(existingIds, "existingIds");
        Objects.requireNonNull(builtInIds, "builtInIds");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome == ConflictOutcome.CANCEL) {
            return conflict(IssueCode.CONFLICT_CANCELLED, data.metadata().id());
        }
        if (outcome == ConflictOutcome.OVERWRITE) {
            if (builtInIds.contains(data.metadata().id())) {
                return conflict(IssueCode.CONFLICT_BUILTIN, data.metadata().id());
            }
            return new ConflictResult(Optional.of(data), List.of());
        }
        if (!ThemePackageCatalog.isValidId(saveAsId)) {
            return conflict(IssueCode.SAVE_AS_ID_INVALID, String.valueOf(saveAsId));
        }
        if (existingIds.contains(saveAsId) || builtInIds.contains(saveAsId)) {
            return conflict(IssueCode.SAVE_AS_ID_CONFLICT, saveAsId);
        }
        final ThemePackageMetadata original = data.metadata();
        return new ConflictResult(Optional.of(new ThemePackageData(
            new ThemePackageMetadata(
                saveAsId,
                original.name(),
                original.description(),
                original.author(),
                original.url(),
                original.version(),
                original.parentId(),
                original.base(),
                original.icons(),
                false
            ),
            data.colors(), data.generatorMetadata(), data.readme(), data.license()
        )), List.of());
    }

    private static String detectRoot(final List<ThemePackageEntry> entries, final List<Issue> issues) {
        final Set<String> roots = new java.util.LinkedHashSet<>();
        for (ThemePackageEntry entry : entries) {
            final String name = entry.name();
            if (name.startsWith("/") || name.startsWith("\\") || name.contains("..") || name.indexOf('\\') >= 0) {
                issues.add(new Issue(IssueCode.PATH_INVALID, name));
                continue;
            }
            final int slash = name.indexOf('/');
            if (slash < 0) {
                roots.add("");
            } else if (slash > 0 && name.indexOf('/', slash + 1) < 0) {
                roots.add(name.substring(0, slash));
            } else {
                issues.add(new Issue(IssueCode.PATH_INVALID, name));
            }
        }
        if (roots.size() > 1) {
            issues.add(new Issue(IssueCode.MULTIPLE_ROOTS, String.join(",", roots)));
        }
        return roots.isEmpty() ? "" : roots.iterator().next();
    }

    private static String relativeName(final String name, final String root) {
        if (root == null || root.isEmpty()) {
            return name.indexOf('/') < 0 ? name : null;
        }
        final String prefix = root + "/";
        return name.startsWith(prefix) && name.indexOf('/', prefix.length()) < 0
            ? name.substring(prefix.length())
            : null;
    }

    private static Map<String, String> loadProperties(
        final ThemePackageEntry entry,
        final boolean rejectDuplicateKeys,
        final List<Issue> issues
    ) {
        final String text = decodeText(entry, issues);
        final Map<String, String> duplicateProbe = new LinkedHashMap<>();
        for (String rawLine : text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1)) {
            final String line = rawLine.stripLeading();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                continue;
            }
            final int separator = propertySeparator(rawLine);
            if (separator > 0) {
                final String key = rawLine.substring(0, separator).trim();
                if (rejectDuplicateKeys && duplicateProbe.putIfAbsent(key, "") != null) {
                    issues.add(new Issue(IssueCode.KEY_DUPLICATE, key));
                }
            }
        }
        final Properties properties = new Properties();
        try (InputStreamReader reader = new InputStreamReader(
            new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8
        )) {
            properties.load(reader);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        if (properties.size() > MAX_KEYS) {
            issues.add(new Issue(IssueCode.KEY_LIMIT, entry.name()));
        }
        final List<String> keys = properties.stringPropertyNames().stream().sorted().toList();
        final LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String key : keys) {
            final String value = properties.getProperty(key);
            if (key.length() > 128) {
                issues.add(new Issue(IssueCode.KEY_LIMIT, key));
            } else if (value.length() > 4096) {
                issues.add(new Issue(IssueCode.VALUE_LIMIT, key));
            }
            result.put(key, value);
        }
        return Collections.unmodifiableMap(result);
    }

    private static int propertySeparator(final String line) {
        boolean escaped = false;
        for (int index = 0; index < line.length(); index++) {
            final char value = line.charAt(index);
            if (!escaped && (value == '=' || value == ':' || Character.isWhitespace(value))) {
                return index;
            }
            escaped = !escaped && value == '\\';
            if (value != '\\') {
                escaped = false;
            }
        }
        return -1;
    }

    private static String decodeText(final ThemePackageEntry entry, final List<Issue> issues) {
        byte[] bytes = entry.bytes();
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF) {
            bytes = java.util.Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        try {
            final String text = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
            for (int index = 0; index < text.length(); index++) {
                final char value = text.charAt(index);
                if (value == 0 || (Character.isISOControl(value) && value != '\n' && value != '\r' && value != '\t')) {
                    issues.add(new Issue(IssueCode.CONTROL_FORBIDDEN, entry.name()));
                    return "";
                }
            }
            return text;
        } catch (CharacterCodingException failure) {
            issues.add(new Issue(IssueCode.UTF8_INVALID, entry.name()));
            return "";
        }
    }

    private static byte[] storeProperties(final Map<String, String> values, final String comment) {
        final Properties properties = new Properties();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            properties.setProperty(entry.getKey(), entry.getValue());
        }
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            properties.store(writer, comment);
        } catch (IOException impossible) {
            throw new IllegalStateException(impossible);
        }
        final String withoutTimestamp = new String(output.toByteArray(), StandardCharsets.UTF_8)
            .lines().filter(line -> !line.matches("^#[A-Z][a-z]{2} .*$"))
            .reduce("", (left, right) -> left + right + "\n");
        return withoutTimestamp.getBytes(StandardCharsets.UTF_8);
    }

    private static Map<String, byte[]> immutableBytes(final LinkedHashMap<String, byte[]> source) {
        final LinkedHashMap<String, byte[]> copy = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> entry : source.entrySet()) {
            copy.put(entry.getKey(), java.util.Arrays.copyOf(entry.getValue(), entry.getValue().length));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static ThemeBase parseBase(final String value) {
        return switch (value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "light" -> ThemeBase.LIGHT;
            case "dark" -> ThemeBase.DARK;
            default -> ThemeBase.ANY;
        };
    }

    private static ThemeIcons parseIcons(final String value) {
        return "dark".equals(value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT))
            ? ThemeIcons.DARK : ThemeIcons.LIGHT;
    }

    private static String firstNonEmpty(final String... values) {
        if (values != null) {
            for (String value : values) {
                if (value != null && !value.trim().isEmpty()) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private static String emptyToNull(final String value) {
        return value.isEmpty() ? null : value;
    }

    private static void putIfNotEmpty(final Map<String, String> target, final String key, final String value) {
        if (value != null && !value.isEmpty()) {
            target.put(key, value);
        }
    }

    private static DecodeResult invalid(final List<Issue> issues) {
        return new DecodeResult(Optional.empty(), List.copyOf(issues));
    }

    private static ConflictResult conflict(final IssueCode code, final String subject) {
        return new ConflictResult(Optional.empty(), List.of(new Issue(code, subject)));
    }

    public record DecodeResult(Optional<ThemePackageData> theme, List<Issue> issues) {
        public DecodeResult {
            theme = Objects.requireNonNull(theme, "theme");
            issues = List.copyOf(issues);
        }

        public boolean valid() {
            return theme.isPresent() && issues.isEmpty();
        }
    }

    public record ConflictResult(Optional<ThemePackageData> theme, List<Issue> issues) {
        public ConflictResult {
            theme = Objects.requireNonNull(theme, "theme");
            issues = List.copyOf(issues);
        }

        public boolean accepted() {
            return theme.isPresent() && issues.isEmpty();
        }
    }

    public record Issue(IssueCode code, String subject) {
        public Issue {
            code = Objects.requireNonNull(code, "code");
            subject = subject == null ? "" : subject;
        }
    }

    public enum ConflictOutcome {
        OVERWRITE,
        SAVE_AS_NEW,
        CANCEL
    }

    public enum IssueCode {
        ENTRY_UNKNOWN,
        ENTRY_DUPLICATE,
        ENTRY_MISSING,
        ENTRY_COUNT_LIMIT,
        TOTAL_SIZE_LIMIT,
        ENTRY_SIZE_LIMIT,
        PATH_INVALID,
        MULTIPLE_ROOTS,
        UTF8_INVALID,
        CONTROL_FORBIDDEN,
        KEY_DUPLICATE,
        KEY_UNKNOWN,
        KEY_LIMIT,
        VALUE_LIMIT,
        METADATA_INVALID,
        CONFLICT_CANCELLED,
        CONFLICT_BUILTIN,
        SAVE_AS_ID_INVALID,
        SAVE_AS_ID_CONFLICT
    }
}
