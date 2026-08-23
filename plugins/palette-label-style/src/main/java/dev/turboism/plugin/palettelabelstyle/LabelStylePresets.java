package dev.turboism.plugin.palettelabelstyle;

import dev.turboism.sdk.ui.appearance.PresetColor;
import dev.turboism.sdk.ui.appearance.UiColor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Preset label colors shared by the text and background submenus.
 *
 * <p>Keys mirror the legacy COLOR_PRESETS: {@code none} clears the property,
 * the seven named keys map to fixed RGB values, and {@code custom} routes to
 * the runtime-rendered color form dialog.</p>
 */
public final class LabelStylePresets {

    public static final String NONE_KEY = "none";
    public static final String CUSTOM_KEY = "custom";

    /** Ordered menu keys: none, red, orange, yellow, green, blue, purple, gray. */
    public static final List<String> MENU_KEYS = List.of(
        NONE_KEY, "red", "orange", "yellow", "green", "blue", "purple", "gray"
    );

    private static final Map<String, String> PRESET_HEX = Map.of(
        "red", "E53935",
        "orange", "FF9800",
        "yellow", "FDD835",
        "green", "4CAF50",
        "blue", "2196F3",
        "purple", "9C27B0",
        "gray", "9E9E9E"
    );

    private LabelStylePresets() {
    }

    /** Preset RGB as canonical {@code #RRGGBB}, or empty for {@code none}. */
    public static Optional<String> hexFor(final String key) {
        if (NONE_KEY.equals(key)) {
            return Optional.empty();
        }
        final String rgb = PRESET_HEX.get(key);
        return rgb == null ? Optional.empty() : Optional.of("#" + rgb);
    }

    /** Preset color as an opaque {@link UiColor}, or empty for {@code none}. */
    public static Optional<UiColor> colorFor(final String key) {
        return hexFor(key).flatMap(LabelStylePresets::parseHex);
    }

    /** Native label-color preset for the deformer background mechanism. */
    public static Optional<PresetColor> nativePresetFor(final String key) {
        return switch (key) {
            case "red" -> Optional.of(PresetColor.RED);
            case "orange" -> Optional.of(PresetColor.ORANGE);
            case "yellow" -> Optional.of(PresetColor.YELLOW);
            case "green" -> Optional.of(PresetColor.GREEN);
            case "blue" -> Optional.of(PresetColor.BLUE);
            case "purple" -> Optional.of(PresetColor.PURPLE);
            case "gray" -> Optional.of(PresetColor.GRAY);
            default -> Optional.empty();
        };
    }

    /** Parses {@code #RRGGBB} (case-insensitive) into an opaque {@link UiColor}. */
    public static Optional<UiColor> parseHex(final String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
            return Optional.empty();
        }
        final int value = Integer.parseInt(hex.substring(1), 16);
        return Optional.of(new UiColor(
            ((value >> 16) & 0xFF) / 255.0F,
            ((value >> 8) & 0xFF) / 255.0F,
            (value & 0xFF) / 255.0F,
            1.0F
        ));
    }

    /** Formats an opaque {@link UiColor} as canonical {@code #RRGGBB}. */
    public static String toHex(final UiColor color) {
        Objects.requireNonNull(color, "color");
        return String.format("#%02X%02X%02X",
            Math.round(color.red() * 255.0F),
            Math.round(color.green() * 255.0F),
            Math.round(color.blue() * 255.0F));
    }
}
