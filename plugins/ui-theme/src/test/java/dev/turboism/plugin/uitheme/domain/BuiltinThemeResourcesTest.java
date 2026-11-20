package dev.turboism.plugin.uitheme.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class BuiltinThemeResourcesTest {

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$");
    private static final List<String> REQUIRED_COLOR_KEYS = List.of(
            "CubismCommon.blue",
            "CubismCommon.background",
            "CubismCommon.foreground",
            "CubismCommon.gl.viewArea.background",
            "turboism.icon.tint");

    @Test
    void builtinCatalogEntriesDecodeCleanlyAndDeclareRequiredKeys() throws Exception {
        for (BuiltinThemeCatalog.Entry entry : BuiltinThemeCatalog.entries()) {
            final String basePath = "/themes/" + entry.resourceDirectory() + "/";
            final byte[] themeBytes = resource(basePath + ThemePackageCodec.THEME_PROPERTIES);
            final byte[] colorsBytes = resource(basePath + ThemePackageCodec.COLORS_PROPERTIES);

            final ThemePackageCodec.DecodeResult decoded = ThemePackageCodec.decode(List.of(
                    new ThemePackageEntry(ThemePackageCodec.THEME_PROPERTIES, themeBytes),
                    new ThemePackageEntry(ThemePackageCodec.COLORS_PROPERTIES, colorsBytes)));
            assertTrue(decoded.valid(), () -> entry.id() + " decode issues: " + decoded.issues());

            final ThemePackageData data = decoded.theme().orElseThrow();
            final ThemePackageMetadata metadata = data.metadata();
            assertEquals(entry.id(), metadata.id(), entry.id());
            assertTrue(metadata.builtIn(), entry.id());
            assertFalse(metadata.name().isBlank(), entry.id());
            assertNotEquals(ThemeBase.ANY, metadata.base(), entry.id());
            if (entry.visible()) {
                assertFalse(metadata.description().isBlank(), entry.id());
                assertFalse(metadata.author().isBlank(), entry.id());
                assertFalse(metadata.url().isBlank(), entry.id());
                assertFalse(metadata.version().isBlank(), entry.id());
            }

            for (String colorKey : REQUIRED_COLOR_KEYS) {
                final String colorValue = data.colors().get(colorKey);
                assertNotNull(colorValue, entry.id() + " missing " + colorKey);
                assertTrue(
                        HEX_COLOR.matcher(colorValue).matches(),
                        () -> entry.id() + " invalid hex for " + colorKey + ": " + colorValue);
            }
        }
    }

    @Test
    void exposesTheLegacyBuiltinThemeInventory() {
        assertEquals(
                List.of(
                        "turboism.mint",
                        "turboism.paper-yellow",
                        "turboism.slate",
                        "turboism.nord",
                        "turboism.cherry-blossom",
                        "turboism.sky-blue",
                        "__cubism_light__",
                        "__cubism_dark__"),
                BuiltinThemeCatalog.entries().stream()
                        .map(BuiltinThemeCatalog.Entry::id)
                        .toList());
        assertEquals(6, BuiltinThemeCatalog.visibleEntries().size());
    }

    private static byte[] resource(final String path) throws IOException {
        try (InputStream input = BuiltinThemeResourcesTest.class.getResourceAsStream(path)) {
            assertNotNull(input, path);
            return input.readAllBytes();
        }
    }
}
