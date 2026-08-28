package dev.turboism.tests.i18n;

import java.io.IOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

final class OfficialPluginCatalogCompleteness {
    static final List<String> CATALOG_FILES = List.of(
        "messages.properties",
        "messages_en.properties",
        "messages_zh_Hans.properties",
        "messages_zh_Hant.properties",
        "messages_ja.properties",
        "messages_ko.properties"
    );
    private static final Map<String, Locale> CATALOG_LOCALES = Map.of(
        "messages.properties", Locale.ROOT,
        "messages_en.properties", Locale.ENGLISH,
        "messages_zh_Hans.properties", Locale.forLanguageTag("zh-Hans"),
        "messages_zh_Hant.properties", Locale.forLanguageTag("zh-Hant"),
        "messages_ja.properties", Locale.JAPANESE,
        "messages_ko.properties", Locale.KOREAN
    );
    private static final String BASELINE_FILE = "baseline-keys.txt";
    private static final Pattern VALID_KEY = Pattern.compile("[a-z][A-Za-z0-9]*(?:[._-][a-z][A-Za-z0-9]*)*");
    private static final Pattern KEY_REFERENCE = Pattern.compile(
        "[\\\"](?:labelKey|titleKey|messageKey)[\\\"]\\s*[:=]\\s*[\\\"]([^\\\"]+)[\\\"]"
            + "|\\b(?:labelKey|titleKey|messageKey)\\s*\\(\\s*[\\\"]([^\\\"]+)[\\\"]"
    );
    private static final Set<String> REQUIRED_DECLARED_LOCALES = Set.of("en", "ja", "ko", "zh-Hans", "zh-Hant");
    private static final Pattern I18N_BLOCK = Pattern.compile("\\\"i18n\\\"\\s*:\\s*\\{(.*?)\\}", Pattern.DOTALL);
    private static final Pattern JSON_STRING = Pattern.compile("\\\"([^\\\"]+)\\\"");
    private static final Set<String> REVIEWED_TECHNICAL_EQUAL_KEYS = Set.of(
        "common.turboism",
        "plugins.column.id",
        "plugins.details.api",
        "plugins.details.id",
        "plugins.details.readme",
        "table.id",
        "tooltip.guid",
        "theme.detail.id",
        "theme.detail.url",
        "texture-atlas.algorithm.maxrects",
        "chart.cpu.title",
        "history.entry.cursor-marker",
        "chart.cpu.series",
        "series.cpu",
        "status.cpu.label",
        "value.none",
        "button.new-session-short",
        "button.refresh-short",
        "button.settings-short",
        "transcript.agent",
        "transcript.system",
        "transcript.tool"
    );

    private OfficialPluginCatalogCompleteness() {
    }

    static void verify(String pluginId, Path i18nDirectory) throws IOException {
        verify(pluginId, i18nDirectory, null);
    }

    static void verifyAll(Path pluginsRoot) throws IOException {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(pluginsRoot)) {
            throw new IllegalStateException("missing plugins directory " + pluginsRoot);
        }
        try (Stream<Path> plugins = Files.list(pluginsRoot)) {
            for (Path pluginRoot : plugins.filter(Files::isDirectory).sorted().toList()) {
                Path productionRoot = pluginRoot.resolve("src/main");
                Path descriptor = productionRoot.resolve("resources/META-INF/turboism/plugin.json");
                Path i18nDirectory = productionRoot.resolve("resources/META-INF/turboism/i18n");
                boolean hasDescriptor = Files.isRegularFile(descriptor);
                boolean descriptorParticipates = hasDescriptor
                    && descriptorDeclaresI18n(pluginRoot.getFileName().toString(), descriptor, problems);
                // An official plugin descriptor without an i18n block is a completeness problem.
                if (hasDescriptor && !descriptorParticipates) {
                    problems.add(pluginRoot.getFileName() + ": plugin descriptor has no i18n block");
                }
                boolean baselineParticipates = Files.isRegularFile(i18nDirectory.resolve(BASELINE_FILE));
                if (!descriptorParticipates && !baselineParticipates) {
                    continue;
                }
                String pluginId = pluginRoot.getFileName().toString();
                try {
                    verify(pluginId, i18nDirectory, productionRoot);
                } catch (IllegalStateException failure) {
                    problems.add(failure.getMessage());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), problems));
        }
    }

    static void verify(String pluginId, Path i18nDirectory, Path productionRoot) throws IOException {
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(i18nDirectory)) {
            throw new IllegalStateException(pluginId + ": missing i18n directory " + i18nDirectory);
        }

        List<String> baseline = readBaseline(pluginId, i18nDirectory.resolve(BASELINE_FILE), problems);
        Set<String> baselineKeys = new LinkedHashSet<>(baseline);
        verifyCatalogFileSet(pluginId, i18nDirectory, problems);

        Map<String, Map<String, String>> catalogs = new LinkedHashMap<>();
        for (String catalogFile : CATALOG_FILES) {
            Path catalogPath = i18nDirectory.resolve(catalogFile);
            if (!Files.isRegularFile(catalogPath)) {
                problems.add(pluginId + ": missing required catalog " + catalogFile);
                continue;
            }
            Map<String, String> catalog = readCatalog(pluginId, catalogPath, problems);
            catalogs.put(catalogFile, catalog);
            verifyKeyParity(pluginId, catalogFile, baselineKeys, catalog.keySet(), problems);
            catalog.forEach((key, value) -> {
                if (value.isBlank()) {
                    problems.add(pluginId + ": blank value for " + key + " in " + catalogFile);
                }
            });
        }
        verifyMessagePatterns(pluginId, catalogs, problems);
        verifyTranslationQuality(pluginId, catalogs, problems);
        if (productionRoot != null) {
            verifyProductionKeyReferences(pluginId, productionRoot, baselineKeys, problems);
        }

        if (!problems.isEmpty()) {
            throw new IllegalStateException(String.join(System.lineSeparator(), problems));
        }
    }

    private static boolean descriptorDeclaresI18n(
        String pluginId,
        Path descriptor,
        List<String> problems
    ) throws IOException {
        String json = Files.readString(descriptor, StandardCharsets.UTF_8);
        Matcher block = I18N_BLOCK.matcher(json);
        if (!block.find()) {
            return false;
        }
        Matcher strings = JSON_STRING.matcher(block.group(1));
        List<String> values = new ArrayList<>();
        while (strings.find()) {
            values.add(strings.group(1));
        }
        if (values.isEmpty() || !values.get(0).equals("baseName")) {
            problems.add(pluginId + ": i18n descriptor is missing baseName");
            return true;
        }
        if (!values.get(1).equals("META-INF/turboism/i18n/messages")) {
            problems.add(pluginId + ": unexpected i18n baseName " + values.get(1));
        }
        int localesIndex = values.indexOf("locales");
        List<String> locales = localesIndex < 0 ? List.of() : values.subList(localesIndex + 1, values.size());
        if (!new LinkedHashSet<>(locales).equals(REQUIRED_DECLARED_LOCALES)) {
            problems.add(pluginId + ": descriptor locales must be " + REQUIRED_DECLARED_LOCALES + ", got " + locales);
        }
        return true;
    }

    private static List<String> readBaseline(String pluginId, Path path, List<String> problems)
        throws IOException {
        if (!Files.isRegularFile(path)) {
            problems.add(pluginId + ": missing " + BASELINE_FILE);
            return List.of();
        }
        String text = decodeUtf8(pluginId, path, problems);
        List<String> keys = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String line : text.split("\\R", -1)) {
            String key = line.trim();
            if (key.isEmpty() || key.startsWith("#")) {
                continue;
            }
            if (!line.equals(key)) {
                problems.add(pluginId + ": baseline key has surrounding whitespace: " + key);
            }
            if (!VALID_KEY.matcher(key).matches() || line.chars().anyMatch(Character::isISOControl)) {
                problems.add(pluginId + ": invalid baseline key " + key);
            }
            if (!seen.add(key)) {
                problems.add(pluginId + ": duplicate baseline key " + key);
            }
            keys.add(key);
        }
        if (keys.isEmpty()) {
            problems.add(pluginId + ": " + BASELINE_FILE + " must not be empty");
        }
        List<String> sorted = keys.stream().sorted().toList();
        if (!keys.equals(sorted)) {
            problems.add(pluginId + ": " + BASELINE_FILE + " must be sorted");
        }
        return keys;
    }

    private static void verifyCatalogFileSet(String pluginId, Path directory, List<String> problems)
        throws IOException {
        Set<String> expected = Set.copyOf(CATALOG_FILES);
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(Files::isRegularFile)
                .map(path -> path.getFileName().toString())
                .filter(name -> name.startsWith("messages") && name.endsWith(".properties"))
                .filter(name -> !expected.contains(name))
                .sorted()
                .forEach(name -> problems.add(pluginId + ": unexpected catalog " + name));
        }
    }

    private static Map<String, String> readCatalog(String pluginId, Path path, List<String> problems)
        throws IOException {
        String catalogFile = path.getFileName().toString();
        String text = decodeUtf8(pluginId, path, problems);
        Map<String, String> entries = new LinkedHashMap<>();
        for (String logicalLine : logicalLines(pluginId, catalogFile, text, problems)) {
            String stripped = logicalLine.stripLeading();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("!")) {
                continue;
            }
            Properties property = new Properties();
            try {
                property.load(new StringReader(logicalLine + "\n"));
            } catch (IOException | IllegalArgumentException exception) {
                problems.add(pluginId + ": invalid properties entry in " + catalogFile);
                continue;
            }
            if (property.size() != 1) {
                problems.add(pluginId + ": invalid logical property count in " + catalogFile);
                continue;
            }
            String key = property.stringPropertyNames().iterator().next();
            String value = property.getProperty(key);
            if (key.isBlank()) {
                problems.add(pluginId + ": blank key in " + catalogFile);
                continue;
            }
            if (entries.putIfAbsent(key, value) != null) {
                problems.add(pluginId + ": duplicate key " + key + " in " + catalogFile);
            }
        }
        return entries;
    }

    private static List<String> logicalLines(
        String pluginId,
        String catalogFile,
        String text,
        List<String> problems
    ) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean continuing = false;
        for (String physical : text.split("\\R", -1)) {
            current.append(continuing ? physical.stripLeading() : physical);
            int trailingSlashes = trailingBackslashes(current);
            if ((trailingSlashes & 1) == 1) {
                current.setLength(current.length() - 1);
                continuing = true;
            } else {
                lines.add(current.toString());
                current.setLength(0);
                continuing = false;
            }
        }
        if (continuing) {
            problems.add(pluginId + ": incomplete continuation in " + catalogFile);
        }
        return lines;
    }

    private static int trailingBackslashes(CharSequence value) {
        int count = 0;
        for (int index = value.length() - 1; index >= 0 && value.charAt(index) == '\\'; index--) {
            count++;
        }
        return count;
    }

    private static String decodeUtf8(String pluginId, Path path, List<String> problems) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length >= 3
            && bytes[0] == (byte) 0xEF
            && bytes[1] == (byte) 0xBB
            && bytes[2] == (byte) 0xBF) {
            problems.add(pluginId + ": UTF-8 BOM is forbidden in " + path.getFileName());
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException exception) {
            problems.add(pluginId + ": invalid UTF-8 in " + path.getFileName());
            return "";
        }
    }

    private static void verifyKeyParity(
        String pluginId,
        String catalogFile,
        Set<String> baseline,
        Set<String> actual,
        List<String> problems
    ) {
        baseline.stream()
            .filter(key -> !actual.contains(key))
            .forEach(key -> problems.add(pluginId + ": missing key " + key + " in " + catalogFile));
        actual.stream()
            .filter(key -> !baseline.contains(key))
            .forEach(key -> problems.add(pluginId + ": extra key " + key + " in " + catalogFile));
    }

    private static void verifyMessagePatterns(
        String pluginId,
        Map<String, Map<String, String>> catalogs,
        List<String> problems
    ) {
        Map<String, String> base = catalogs.get("messages.properties");
        if (base == null) {
            return;
        }
        Map<String, Set<Integer>> expectedIndexes = new LinkedHashMap<>();
        base.forEach((key, pattern) -> expectedIndexes.put(
            key,
            validateMessagePattern(pluginId, "messages.properties", key, pattern, Locale.ROOT, problems)
        ));
        catalogs.forEach((catalogFile, catalog) -> {
            if (catalogFile.equals("messages.properties")) {
                return;
            }
            Locale locale = CATALOG_LOCALES.get(catalogFile);
            catalog.forEach((key, pattern) -> {
                Set<Integer> indexes = validateMessagePattern(
                    pluginId, catalogFile, key, pattern, locale, problems);
                Set<Integer> expected = expectedIndexes.get(key);
                if (expected != null && !indexes.equals(expected)) {
                    problems.add(pluginId + ": argument index mismatch for " + key + " in " + catalogFile);
                }
            });
        });
    }

    private static void verifyTranslationQuality(
        String pluginId,
        Map<String, Map<String, String>> catalogs,
        List<String> problems
    ) {
        Map<String, String> english = catalogs.get("messages_en.properties");
        if (english == null) {
            return;
        }
        catalogs.forEach((catalogFile, catalog) -> {
            if (catalogFile.equals("messages.properties")
                || catalogFile.equals("messages_en.properties")) {
                return;
            }
            catalog.forEach((key, value) -> {
                if (value.equals(english.get(key)) && !REVIEWED_TECHNICAL_EQUAL_KEYS.contains(key)) {
                    problems.add(pluginId + ": copied English value for " + key + " in " + catalogFile);
                }
            });
        });
    }

    private static Set<Integer> validateMessagePattern(
        String pluginId,
        String catalogFile,
        String key,
        String pattern,
        Locale locale,
        List<String> problems
    ) {
        try {
            new MessageFormat(pattern, locale);
        } catch (IllegalArgumentException exception) {
            problems.add(pluginId + ": invalid MessageFormat pattern for " + key + " in " + catalogFile);
            return Set.of();
        }
        return argumentIndexes(pattern);
    }

    private static Set<Integer> argumentIndexes(String pattern) {
        Set<Integer> indexes = new LinkedHashSet<>();
        boolean quoted = false;
        int depth = 0;
        for (int index = 0; index < pattern.length(); index++) {
            char character = pattern.charAt(index);
            if (character == '\'') {
                if (index + 1 < pattern.length() && pattern.charAt(index + 1) == '\'') {
                    index++;
                } else {
                    quoted = !quoted;
                }
                continue;
            }
            if (quoted) {
                continue;
            }
            if (character == '{') {
                depth++;
                int digit = index + 1;
                while (digit < pattern.length() && Character.isWhitespace(pattern.charAt(digit))) {
                    digit++;
                }
                int end = digit;
                while (end < pattern.length() && Character.isDigit(pattern.charAt(end))) {
                    end++;
                }
                if (end > digit) {
                    indexes.add(Integer.parseInt(pattern.substring(digit, end)));
                }
            } else if (character == '}' && depth > 0) {
                depth--;
            }
        }
        return indexes;
    }

    private static void verifyProductionKeyReferences(
        String pluginId,
        Path productionRoot,
        Set<String> baseline,
        List<String> problems
    ) throws IOException {
        if (!Files.isDirectory(productionRoot)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(productionRoot)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(file -> file.toString().endsWith(".java") || file.toString().endsWith(".json"))
                .sorted().toList()) {
                String source = decodeUtf8(pluginId, path, problems);
                Matcher matcher = KEY_REFERENCE.matcher(source);
                while (matcher.find()) {
                    String key = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                    if (!baseline.contains(key)) {
                        problems.add(pluginId + ": unknown production localization key " + key
                            + " in " + productionRoot.relativize(path));
                    }
                }
            }
        }
    }
}
