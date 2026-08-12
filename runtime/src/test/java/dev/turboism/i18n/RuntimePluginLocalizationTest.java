package dev.turboism.i18n;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginLocalizationTest {

    @TempDir
    Path tempDir;

    @Test
    void normalizesChineseRegionsAndFallsBackAfterInvalidExplicitLocale() throws Exception {
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        try (URLClassLoader loader = pluginLoader("locale", Map.of(
            path("zh_Hans"), utf8("value=简体\n"),
            path("ja"), utf8("value=日本語\n"),
            path(null), utf8("value=base\n")
        ))) {
            final RuntimePluginLocalization simplified = RuntimePluginLocalization.create(
                "plugin.locale", loader, metadata(), "zh-CN", Locale.JAPAN, Locale.ENGLISH, diagnostics
            );
            assertEquals("zh-Hans", simplified.locale().toLanguageTag());
            assertEquals("简体", simplified.text("value"));

            final RuntimePluginLocalization explicitScript = RuntimePluginLocalization.create(
                "plugin.locale", loader, metadata(), "zh-Hant-HK", Locale.JAPAN, Locale.ENGLISH, diagnostics
            );
            assertEquals("zh-Hant-HK", explicitScript.locale().toLanguageTag());

            final RuntimePluginLocalization invalid = RuntimePluginLocalization.create(
                "plugin.locale", loader, metadata(), "bad_tag", Locale.JAPAN, Locale.ENGLISH, diagnostics
            );
            assertEquals("ja-JP", invalid.locale().toLanguageTag());
            assertEquals("日本語", invalid.text("value"));
            assertTrue(diagnostics.hasCode("I18N_INVALID_EXPLICIT_LOCALE"));
        }
    }

    @Test
    void usesTheFrozenFallbackOrderWithChineseDefaultingToSimplified() throws Exception {
        try (URLClassLoader loader = pluginLoader("fallback", Map.of(
            path(null), utf8("baseOnly=base\nshared=base\n"),
            path("en"), utf8("shared=english\nenglishOnly=english\n"),
            path("zh_Hans"), utf8("shared=简体\n")
        ))) {
            final RuntimePluginLocalization script = localization(loader, "zh-Hans-CN");
            assertEquals("简体", script.text("shared"));
            assertEquals("base", script.text("baseOnly"));

            // zh 无 country：默认简体（跟随 Cubism -Duser.language=zh 的语言版本）。
            final RuntimePluginLocalization bareChinese = localization(loader, "zh");
            assertEquals("zh-Hans", bareChinese.locale().toLanguageTag());
            assertEquals("简体", bareChinese.text("shared"));

            // Wine 产物 zh-US 同样归一为简体。
            final RuntimePluginLocalization wineChinese = localization(loader, "zh-US");
            assertEquals("zh-Hans", wineChinese.locale().toLanguageTag());
            assertEquals("简体", wineChinese.text("shared"));

            final RuntimePluginLocalization french = localization(loader, "fr-FR");
            assertEquals("base", french.text("shared"));
            assertEquals("⟦englishOnly⟧", french.text("englishOnly"));
        }
    }

    @Test
    void isolatesCatalogsByPluginClassloader() throws Exception {
        try (
            URLClassLoader firstLoader = pluginLoader("first", Map.of(path(null), utf8("name=first\n")));
            URLClassLoader secondLoader = pluginLoader("second", Map.of(path(null), utf8("name=second\n")))
        ) {
            assertEquals("first", localization(firstLoader, "en").text("name"));
            assertEquals("second", localization(secondLoader, "en").text("name"));
        }
    }

    @Test
    void warnsOnceForMissingTextAndContainsRemainsSilent() throws Exception {
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        try (URLClassLoader loader = pluginLoader("missing", Map.of(path(null), utf8("known=value\n")))) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                "plugin.missing", loader, metadata(), "en", null, Locale.ENGLISH, diagnostics
            );

            assertFalse(localization.contains("missing"));
            assertEquals(0, diagnostics.count("I18N_MISSING_KEY"));
            assertEquals("⟦missing⟧", localization.text("missing"));
            assertEquals("⟦missing⟧", localization.text("missing"));
            assertEquals(1, diagnostics.count("I18N_MISSING_KEY"));
        }
    }

    @Test
    void formatsWithTheActiveLocaleAndSanitizesMalformedPatterns() throws Exception {
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        try (URLClassLoader loader = pluginLoader("format", Map.of(
            path(null), utf8("number={0,number}\nbroken={0,number\n")
        ))) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                "plugin.format", loader, metadata(), "de-DE", null, Locale.ENGLISH, diagnostics
            );

            assertEquals("1.234,5", localization.format("number", 1234.5));
            assertEquals("⟦broken⟧", localization.format("broken", "private-value"));
            final LocalizationDiagnostic diagnostic = diagnostics.first("I18N_FORMAT_INVALID");
            assertEquals("broken", diagnostic.key());
            assertFalse(diagnostic.message().contains("private-value"));
        }
    }

    @Test
    void rejectsDuplicatePluginLocalCatalogResourcesInsteadOfUsingUrlOrder() throws Exception {
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final Path first = pluginJar("duplicate-first", Map.of(path(null), utf8("value=first\n")));
        final Path second = pluginJar("duplicate-second", Map.of(path(null), utf8("value=second\n")));
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] {first.toUri().toURL(), second.toUri().toURL()},
            RuntimePluginLocalizationTest.class.getClassLoader()
        )) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                "plugin.duplicate", loader, metadata(), "fr", null, Locale.ENGLISH, diagnostics
            );

            assertEquals("⟦value⟧", localization.text("value"));
            assertTrue(diagnostics.hasCode("I18N_CATALOG_DUPLICATE_RESOURCE"));
        }
    }

    @Test
    void rejectsOnlyTheAffectedCatalogForBomBadUtf8AndDuplicateKeys() throws Exception {
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final byte[] bom = concat(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, utf8("base=value\n"));
        final byte[] malformed = new byte[] {'x', '=', (byte) 0xC3, 0x28, '\n'};
        try (URLClassLoader loader = pluginLoader("invalid", Map.of(
            path(null), bom,
            path("en"), utf8("ok=english\ndup=one\ndup=two\n"),
            path("ja"), malformed,
            path("ko"), utf8("ok=한국어\n")
        ))) {
            final RuntimePluginLocalization english = RuntimePluginLocalization.create(
                "plugin.invalid", loader, metadata(), "en", null, Locale.ENGLISH, diagnostics
            );
            assertEquals("⟦ok⟧", english.text("ok"));
            assertTrue(diagnostics.hasCode("I18N_CATALOG_BOM"));
            assertTrue(diagnostics.hasCode("I18N_CATALOG_DUPLICATE_KEY"));

            final RuntimePluginLocalization korean = RuntimePluginLocalization.create(
                "plugin.invalid", loader, metadata(), "ko", null, Locale.ENGLISH, diagnostics
            );
            assertEquals("한국어", korean.text("ok"));

            final RuntimePluginLocalization japanese = RuntimePluginLocalization.create(
                "plugin.invalid", loader, metadata(), "ja", null, Locale.ENGLISH, diagnostics
            );
            assertEquals("⟦x⟧", japanese.text("x"));
            assertTrue(diagnostics.hasCode("I18N_CATALOG_INVALID_UTF8"));
        }
    }

    @Test
    void implicitBaseIsLoadedOnceAsFinalFallbackWhenLocalesOmitsBase() throws Exception {
        try (URLClassLoader loader = pluginLoader("implicit-base", Map.of(
            path(null), utf8("shared=base\nbaseOnly=base\n"),
            path("en"), utf8("shared=english\n")
        ))) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                "plugin.implicit", loader, metadataWithoutBase(), "en", null, Locale.ENGLISH,
                diagnostic -> { }
            );
            // A locale-specific key overrides base; a missing key falls back to base
            assertEquals("english", localization.text("shared"));
            assertEquals("base", localization.text("baseOnly"));
            // The implicit base is loaded exactly once
            final List<RuntimePluginLocalization.CatalogSnapshot> catalogs =
                localization.reportSnapshot().catalogs();
            assertEquals(
                1,
                catalogs.stream().filter(catalog -> catalog.locale().equals("base")).count()
            );
            assertEquals(
                "AVAILABLE",
                catalogs.stream().filter(catalog -> catalog.locale().equals("base"))
                    .findFirst().orElseThrow().state()
            );
        }
    }

    @Test
    void legacyExplicitBaseIsDeduplicatedAndLoadedOnce() throws Exception {
        try (URLClassLoader loader = pluginLoader("legacy-base", Map.of(
            path(null), utf8("shared=base\nbaseOnly=base\n"),
            path("en"), utf8("shared=english\n")
        ))) {
            final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
                "plugin.legacy", loader, metadata(), "en", null, Locale.ENGLISH,
                diagnostic -> { }
            );
            assertEquals("english", localization.text("shared"));
            assertEquals("base", localization.text("baseOnly"));
            // Explicit legacy base and implicit base resolve to one loaded catalog
            final List<RuntimePluginLocalization.CatalogSnapshot> catalogs =
                localization.reportSnapshot().catalogs();
            assertEquals(
                1,
                catalogs.stream().filter(catalog -> catalog.locale().equals("base")).count()
            );
        }
    }
    private RuntimePluginLocalization localization(
        final ClassLoader loader,
        final String explicitLocale
    ) {
        return RuntimePluginLocalization.create(
            "plugin.test", loader, metadata(), explicitLocale, null, Locale.ENGLISH,
            diagnostic -> { }
        );
    }

    private static PluginDescriptor.I18n metadata() {
        return new PluginDescriptor.I18n() {
            @Override public String baseName() {
                return "META-INF/turboism/i18n/messages";
            }

            @Override public List<String> locales() {
                return List.of("base", "en", "zh_Hans", "zh_Hant", "ja", "ko");
            }
        };
    }

    private static PluginDescriptor.I18n metadataWithoutBase() {
        // Current official descriptor form: baseName() implicitly declares the
        // base catalog and locales() lists only localized catalogs.
        return new PluginDescriptor.I18n() {
            @Override public String baseName() {
                return "META-INF/turboism/i18n/messages";
            }

            @Override public List<String> locales() {
                return List.of("en");
            }
        };
    }

    private URLClassLoader pluginLoader(
        final String name,
        final Map<String, byte[]> resources
    ) throws IOException {
        final Path jar = pluginJar(name, resources);
        return new URLClassLoader(
            new URL[] {jar.toUri().toURL()},
            RuntimePluginLocalizationTest.class.getClassLoader()
        );
    }

    private Path pluginJar(
        final String name,
        final Map<String, byte[]> resources
    ) throws IOException {
        final Path jar = tempDir.resolve(name + ".jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            for (Map.Entry<String, byte[]> entry : new LinkedHashMap<>(resources).entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
        return jar;
    }

    private static String path(final String suffix) {
        final String base = "META-INF/turboism/i18n/messages";
        return suffix == null ? base + ".properties" : base + "_" + suffix + ".properties";
    }

    private static byte[] utf8(final String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(final byte[] left, final byte[] right) {
        final byte[] result = new byte[left.length + right.length];
        System.arraycopy(left, 0, result, 0, left.length);
        System.arraycopy(right, 0, result, left.length, right.length);
        return result;
    }

    private static final class RecordingDiagnostics implements LocalizationDiagnosticSink {
        private final List<LocalizationDiagnostic> diagnostics = new ArrayList<>();

        @Override
        public void record(final LocalizationDiagnostic diagnostic) {
            diagnostics.add(diagnostic);
        }

        private boolean hasCode(final String code) {
            return diagnostics.stream().anyMatch(value -> value.code().equals(code));
        }

        private long count(final String code) {
            return diagnostics.stream().filter(value -> value.code().equals(code)).count();
        }

        private LocalizationDiagnostic first(final String code) {
            return diagnostics.stream()
                .filter(value -> value.code().equals(code))
                .findFirst()
                .orElseThrow();
        }
    }
}
