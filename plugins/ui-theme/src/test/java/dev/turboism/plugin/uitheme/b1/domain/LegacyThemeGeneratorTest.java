package dev.turboism.plugin.uitheme.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class LegacyThemeGeneratorTest {

    @Test
    void expandsLegacySlotsToTheFrozenHostKeyMapping() {
        final ThemePaletteGenerator.Result result = ThemePaletteGenerator.generate(
            fullSlots(),
            Map.of("Custom.keep", "#010101", "CubismCommon.blue", "#000000"),
            Set.of("CubismCommon.blue")
        );

        assertEquals("#123456", result.colors().get("accent"));
        assertEquals("#123456", result.colors().get("CubismCommon.blue"));
        assertEquals("#123456", result.colors().get("ToggleButton.selectedBackground"));
        assertEquals("#101010", result.colors().get("Panel.background"));
        assertEquals("#010101", result.colors().get("Custom.keep"));
        assertEquals("turboism-theme-gen", result.metadata().get("generator.id"));
        assertEquals("1.0.0", result.metadata().get("generator.version"));
        assertEquals("#123456", result.metadata().get("slot.accent"));
        assertEquals(String.join(",", result.managedKeys()), result.metadata().get("managedKeys"));
    }

    @Test
    void preservesLegacyLightAndDarkFallbackDesign() {
        assertEquals(Map.ofEntries(
            Map.entry("accent", "#2675BF"),
            Map.entry("background", "#F0F0F0"),
            Map.entry("surface", "#FFFFFF"),
            Map.entry("inputBackground", "#FFFFFF"),
            Map.entry("foreground", "#1E1E1E"),
            Map.entry("mutedForeground", "#808080"),
            Map.entry("selectionBackground", "#2675BF"),
            Map.entry("selectionForeground", "#FFFFFF"),
            Map.entry("border", "#CCCCCC"),
            Map.entry("glViewportBackground", "#E6E6E6")
        ), ThemePaletteGenerator.fallbackDefaults(ThemeBase.LIGHT));
        assertEquals("#539CDF", ThemePaletteGenerator.fallbackDefaults(ThemeBase.DARK).get("accent"));
        assertEquals("#1E1E1E", ThemePaletteGenerator.fallbackDefaults(ThemeBase.DARK).get("glViewportBackground"));
    }

    @Test
    void editsSubsetsAndRejectsUnknownOrMalformedSlots() {
        final ThemePaletteGenerator.Result generated = ThemePaletteGenerator.generate(fullSlots(), Map.of(), Set.of());
        final ThemePaletteGenerator.Result edited = ThemePaletteGenerator.edit(
            generated,
            Map.of("accent", "#abcdef", "border", "#010203")
        );
        assertEquals("#ABCDEF", edited.metadata().get("slot.accent"));
        assertEquals("#ABCDEF", edited.colors().get("CubismCommon.blue"));
        assertEquals("#010203", edited.colors().get("Separator.foreground"));
        assertEquals(generated.metadata().get("slot.background"), edited.metadata().get("slot.background"));
        assertThrows(IllegalArgumentException.class, () -> ThemePaletteGenerator.edit(generated, Map.of("unknown", "#FFFFFF")));
        assertThrows(IllegalArgumentException.class, () -> ThemePaletteGenerator.edit(generated, Map.of("accent", "red")));
    }

    @Test
    void allResultsAreImmutableAndOrderedByLegacySlotOrder() {
        final LinkedHashMap<String, String> mutable = fullSlots();
        final ThemePaletteGenerator.Result result = ThemePaletteGenerator.generate(mutable, Map.of(), new LinkedHashSet<>());
        mutable.put("accent", "#000000");
        assertEquals("#123456", result.metadata().get("slot.accent"));
        assertEquals(ThemePaletteGenerator.slotOrder(), result.slotValues().keySet().stream().toList());
        assertThrows(UnsupportedOperationException.class, () -> result.colors().put("x", "y"));
        assertFalse(result.managedKeys().isEmpty());
        assertTrue(result.managedKeys().contains("CubismCommon.gl.viewArea.background"));
    }

    private static LinkedHashMap<String, String> fullSlots() {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("accent", "#123456");
        values.put("background", "#101010");
        values.put("surface", "#202020");
        values.put("inputBackground", "#303030");
        values.put("foreground", "#F0F0F0");
        values.put("mutedForeground", "#808080");
        values.put("selectionBackground", "#405060");
        values.put("selectionForeground", "#FFFFFF");
        values.put("border", "#505050");
        values.put("glViewportBackground", "#111111");
        return values;
    }
}
