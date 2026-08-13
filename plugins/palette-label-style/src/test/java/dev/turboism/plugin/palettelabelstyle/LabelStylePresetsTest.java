package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.ui.appearance.PresetColor;
import dev.turboism.sdk.ui.appearance.UiColor;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LabelStylePresetsTest {

    @Test
    void menuKeysCoverNoneSevenPresetsAndCustom() {
        assertEquals(
            java.util.List.of("none", "red", "orange", "yellow", "green", "blue", "purple", "gray"),
            LabelStylePresets.MENU_KEYS
        );
    }

    @Test
    void noneHasNoColor() {
        assertEquals(Optional.empty(), LabelStylePresets.hexFor(LabelStylePresets.NONE_KEY));
        assertEquals(Optional.empty(), LabelStylePresets.colorFor(LabelStylePresets.NONE_KEY));
    }

    @Test
    void presetHexValuesMatchLegacyPalette() {
        assertEquals("#E53935", LabelStylePresets.hexFor("red").orElseThrow());
        assertEquals("#FF9800", LabelStylePresets.hexFor("orange").orElseThrow());
        assertEquals("#FDD835", LabelStylePresets.hexFor("yellow").orElseThrow());
        assertEquals("#4CAF50", LabelStylePresets.hexFor("green").orElseThrow());
        assertEquals("#2196F3", LabelStylePresets.hexFor("blue").orElseThrow());
        assertEquals("#9C27B0", LabelStylePresets.hexFor("purple").orElseThrow());
        assertEquals("#9E9E9E", LabelStylePresets.hexFor("gray").orElseThrow());
        assertEquals(Optional.empty(), LabelStylePresets.hexFor("unknown"));
    }

    @Test
    void presetColorsAreOpaqueUiColors() {
        final UiColor red = LabelStylePresets.colorFor("red").orElseThrow();
        assertEquals(0xE5 / 255.0F, red.red(), 0.0001F);
        assertEquals(0x39 / 255.0F, red.green(), 0.0001F);
        assertEquals(0x35 / 255.0F, red.blue(), 0.0001F);
        assertEquals(1.0F, red.alpha());
    }

    @Test
    void nativePresetMappingCoversAllSevenPresets() {
        assertEquals(Optional.of(PresetColor.RED), LabelStylePresets.nativePresetFor("red"));
        assertEquals(Optional.of(PresetColor.ORANGE), LabelStylePresets.nativePresetFor("orange"));
        assertEquals(Optional.of(PresetColor.YELLOW), LabelStylePresets.nativePresetFor("yellow"));
        assertEquals(Optional.of(PresetColor.GREEN), LabelStylePresets.nativePresetFor("green"));
        assertEquals(Optional.of(PresetColor.BLUE), LabelStylePresets.nativePresetFor("blue"));
        assertEquals(Optional.of(PresetColor.PURPLE), LabelStylePresets.nativePresetFor("purple"));
        assertEquals(Optional.of(PresetColor.GRAY), LabelStylePresets.nativePresetFor("gray"));
        assertEquals(Optional.empty(), LabelStylePresets.nativePresetFor("none"));
        assertEquals(Optional.empty(), LabelStylePresets.nativePresetFor("custom"));
    }

    @Test
    void parseHexAcceptsCanonicalAndLowercase() {
        assertEquals(Optional.of("#E53935"), LabelStylePresets.parseHex("#E53935").map(LabelStylePresets::toHex));
        assertEquals(Optional.of("#E53935"), LabelStylePresets.parseHex("#e53935").map(LabelStylePresets::toHex));
    }

    @Test
    void parseHexRejectsMalformedValues() {
        assertTrue(LabelStylePresets.parseHex(null).isEmpty());
        assertTrue(LabelStylePresets.parseHex("E53935").isEmpty());
        assertTrue(LabelStylePresets.parseHex("#E5393").isEmpty());
        assertTrue(LabelStylePresets.parseHex("#E5393G").isEmpty());
        assertTrue(LabelStylePresets.parseHex("#E53935FF").isEmpty());
        assertTrue(LabelStylePresets.parseHex("").isEmpty());
    }

    @Test
    void toHexRoundTripsUiColor() {
        final UiColor color = LabelStylePresets.parseHex("#123456").orElseThrow();
        assertEquals("#123456", LabelStylePresets.toHex(color));
    }

    @Test
    void toHexNormalizesThroughRgbChannels() {
        final UiColor color = new UiColor(0.0F, 0.5F, 1.0F, 1.0F);
        assertEquals("#0080FF", LabelStylePresets.toHex(color));
        assertFalse(LabelStylePresets.parseHex("#0080FF").isEmpty());
    }
}
