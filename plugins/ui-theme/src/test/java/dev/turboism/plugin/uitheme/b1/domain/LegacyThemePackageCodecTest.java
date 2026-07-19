package dev.turboism.plugin.uitheme.b1.domain;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

final class LegacyThemePackageCodecTest {

    @Test
    void acceptsLegacyRootAndSingleWrapperLayouts() {
        final ThemePackageCodec.DecodeResult root = ThemePackageCodec.decode(List.of(
            entry("theme.properties", metadata("turboism.demo")),
            entry("colors.properties", "CubismCommon.blue=#2675BF\n"),
            entry("README.md", "read me")
        ));
        final ThemePackageCodec.DecodeResult wrapped = ThemePackageCodec.decode(List.of(
            entry("turboism.demo/theme.properties", metadata("turboism.demo")),
            entry("turboism.demo/colors.properties", "CubismCommon.blue=#2675BF\n"),
            entry("turboism.demo/LICENSE", "license")
        ));

        assertTrue(root.valid(), root.issues().toString());
        assertTrue(wrapped.valid(), wrapped.issues().toString());
        assertEquals("https://turboism.dev", root.theme().orElseThrow().metadata().url());
        assertEquals(root.theme().orElseThrow().metadata(), wrapped.theme().orElseThrow().metadata());
        assertEquals(List.of("theme.properties", "colors.properties", "README.md"),
            List.copyOf(ThemePackageCodec.encodeDirectory(root.theme().orElseThrow()).keySet()));
        assertEquals(List.of(
            "turboism.demo/theme.properties",
            "turboism.demo/colors.properties",
            "turboism.demo/README.md"
        ), List.copyOf(ThemePackageCodec.encodeZip(root.theme().orElseThrow()).keySet()));
    }

    @Test
    void preservesLegacyPropertiesSemanticsAndHistoricalAliases() {
        final ThemePackageCodec.DecodeResult decoded = ThemePackageCodec.decode(List.of(
            entry("theme.properties", "theme.id=turboism.alias\ntheme.name=Alias\ntheme.description=Old key\nbase=bogus\nicons=bogus\nbuilt-in=true\n"),
            entry("colors.properties", "Button.background=#aabbcc\n")
        ));

        assertTrue(decoded.valid(), decoded.issues().toString());
        final ThemePackageMetadata metadata = decoded.theme().orElseThrow().metadata();
        assertEquals("turboism.alias", metadata.id());
        assertEquals("Alias", metadata.name());
        assertEquals("Old key", metadata.description());
        assertEquals(ThemeBase.ANY, metadata.base());
        assertEquals(ThemeIcons.LIGHT, metadata.icons());
        assertFalse(metadata.builtIn(), "untrusted package metadata must not grant built-in protection");
        assertEquals("#aabbcc", decoded.theme().orElseThrow().colors().get("Button.background"));
    }

    @Test
    void decodesUtf8AfterBomAndPreservesNonAsciiMetadata() {
        final byte[] metadata = ("id=turboism.localized\nname=樱花主题\ndescription=柔和配色\n")
            .getBytes(StandardCharsets.UTF_8);
        final byte[] withBom = new byte[metadata.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(metadata, 0, withBom, 3, metadata.length);
        final ThemePackageCodec.DecodeResult decoded = ThemePackageCodec.decode(List.of(
            new ThemePackageEntry("theme.properties", withBom),
            entry("colors.properties", "Button.background=#AABBCC\n")
        ));
        assertTrue(decoded.valid(), decoded.issues().toString());
        assertEquals("樱花主题", decoded.theme().orElseThrow().metadata().name());
        assertEquals("柔和配色", decoded.theme().orElseThrow().metadata().description());
    }

    @Test
    void rejectsMultipleRootsDuplicatesUnknownAndMissingFiles() {
        assertIssue(ThemePackageCodec.IssueCode.MULTIPLE_ROOTS, List.of(
            entry("one/theme.properties", metadata("turboism.one")),
            entry("two/colors.properties", "x=#FFFFFF\n")
        ));
        assertIssue(ThemePackageCodec.IssueCode.ENTRY_DUPLICATE, List.of(
            entry("theme.properties", metadata("turboism.one")),
            entry("theme.properties", metadata("turboism.two")),
            entry("colors.properties", "x=#FFFFFF\n")
        ));
        assertIssue(ThemePackageCodec.IssueCode.ENTRY_UNKNOWN, List.of(
            entry("theme.properties", metadata("turboism.one")),
            entry("colors.properties", "x=#FFFFFF\n"),
            entry("nested/file.txt", "unsafe")
        ));
        assertIssue(ThemePackageCodec.IssueCode.ENTRY_MISSING, List.of(
            entry("theme.properties", metadata("turboism.one"))
        ));
    }

    @Test
    void conflictAndSaveAsMatchLegacyUserVisiblePolicy() {
        final ThemePackageData data = valid("turboism.demo");
        assertEquals(ThemePackageCodec.IssueCode.CONFLICT_BUILTIN,
            ThemePackageCodec.resolveConflict(data, Set.of("turboism.demo"), Set.of("turboism.demo"),
                ThemePackageCodec.ConflictOutcome.OVERWRITE, null).issues().get(0).code());
        assertEquals(ThemePackageCodec.IssueCode.CONFLICT_CANCELLED,
            ThemePackageCodec.resolveConflict(data, Set.of("turboism.demo"), Set.of(),
                ThemePackageCodec.ConflictOutcome.CANCEL, null).issues().get(0).code());
        final ThemePackageCodec.ConflictResult saved = ThemePackageCodec.resolveConflict(
            data, Set.of("turboism.demo"), Set.of(), ThemePackageCodec.ConflictOutcome.SAVE_AS_NEW,
            "author.copy"
        );
        assertTrue(saved.accepted());
        assertEquals("author.copy", saved.theme().orElseThrow().metadata().id());
        assertFalse(saved.theme().orElseThrow().metadata().builtIn());
        assertEquals(data.colors(), saved.theme().orElseThrow().colors());
    }

    @Test
    void randomizedCanonicalRoundTripsKeepLegacyVisibleData() {
        final SplittableRandom random = new SplittableRandom(0xB1C0DEC0DEL);
        for (int iteration = 0; iteration < 100; iteration++) {
            final java.util.LinkedHashMap<String, String> colors = new java.util.LinkedHashMap<>();
            for (int index = 0; index < 1 + random.nextInt(24); index++) {
                colors.put("Custom.color" + index, String.format("#%06X", random.nextInt(0x1000000)));
            }
            final ThemePackageData data = new ThemePackageData(
                new ThemePackageMetadata("author.theme" + iteration, "Theme " + iteration, "Description", "Author",
                    "https://example.invalid", "1.0", null, ThemeBase.ANY, ThemeIcons.LIGHT, false),
                colors,
                Map.of(),
                null,
                null
            );
            final Map<String, byte[]> encoded = ThemePackageCodec.encodeZip(data);
            final ThemePackageCodec.DecodeResult decoded = ThemePackageCodec.decode(encoded.entrySet().stream()
                .map(entry -> new ThemePackageEntry(entry.getKey(), entry.getValue())).toList());
            assertTrue(decoded.valid(), decoded.issues().toString());
            assertEquals(data.metadata(), decoded.theme().orElseThrow().metadata());
            assertEquals(data.colors(), decoded.theme().orElseThrow().colors());
            final Map<String, byte[]> encodedAgain = ThemePackageCodec.encodeZip(decoded.theme().orElseThrow());
            assertEquals(encoded.keySet(), encodedAgain.keySet());
            for (String name : encoded.keySet()) {
                assertArrayEquals(encoded.get(name), encodedAgain.get(name), name);
            }
        }
    }

    private static ThemePackageData valid(String id) {
        return ThemePackageCodec.decode(List.of(
            entry("theme.properties", metadata(id)),
            entry("colors.properties", "CubismCommon.blue=#2675BF\n")
        )).theme().orElseThrow();
    }

    private static String metadata(String id) {
        return "id=" + id + "\nname=Demo\ndescription=Legacy compatible\nauthor=Turboism\nurl=https://turboism.dev\nversion=1.0\nbase=light\nicons=dark\nbuilt-in=false\n";
    }

    private static ThemePackageEntry entry(String name, String value) {
        return new ThemePackageEntry(name, value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertIssue(ThemePackageCodec.IssueCode issue, List<ThemePackageEntry> entries) {
        assertTrue(ThemePackageCodec.decode(entries).issues().stream().anyMatch(value -> value.code() == issue));
    }
}
