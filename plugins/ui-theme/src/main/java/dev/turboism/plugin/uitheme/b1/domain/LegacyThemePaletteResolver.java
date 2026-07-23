package dev.turboism.plugin.uitheme.b1.domain;

import dev.turboism.sdk.appearance.AppearanceBase;
import dev.turboism.sdk.appearance.AppearancePalette;
import dev.turboism.sdk.appearance.AppearanceRequest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Converts legacy package colors into the bounded semantic SDK appearance palette. */
public final class LegacyThemePaletteResolver {

    private static final Map<String, List<String>> SLOT_KEYS = Map.ofEntries(
        Map.entry("accent", List.of("accent", "CubismCommon.blue", "Component.accentColor")),
        Map.entry("background", List.of("CubismCommon.background", "Panel.background")),
        Map.entry("surface", List.of("CubismCommon.surface", "Table.background", "Button.background")),
        Map.entry("inputBackground", List.of("CubismCommon.inputBackground", "TextField.background")),
        Map.entry("foreground", List.of("CubismCommon.foreground", "Label.foreground")),
        Map.entry("mutedForeground", List.of("CubismCommon.mutedForeground")),
        Map.entry("selectionBackground", List.of("CubismCommon.selectionBackground", "Table.selectionBackground")),
        Map.entry("selectionForeground", List.of("CubismCommon.selectionForeground", "Table.selectionForeground")),
        Map.entry("border", List.of("CubismCommon.border", "Separator.foreground")),
        Map.entry("glViewportBackground", List.of("CubismCommon.gl.viewArea.background"))
    );

    private LegacyThemePaletteResolver() {
    }

    public static AppearanceRequest resolve(
        final ThemePackageData data,
        final long expectedRevision
    ) {
        Objects.requireNonNull(data, "data");
        final LinkedHashMap<String, String> slots = new LinkedHashMap<>(
            ThemePaletteGenerator.fallbackDefaults(data.metadata().base())
        );
        for (String slot : ThemePaletteGenerator.slotOrder()) {
            first(data.colors(), SLOT_KEYS.get(slot)).ifPresent(value -> slots.put(slot, value));
        }
        final ThemePaletteGenerator.Result normalized = ThemePaletteGenerator.generate(
            slots,
            Map.of(),
            java.util.Set.of()
        );
        final Map<String, String> values = normalized.slotValues();
        return new AppearanceRequest(
            data.metadata().id(),
            base(data.metadata().base()),
            new AppearancePalette(
                values.get("accent"),
                values.get("background"),
                values.get("surface"),
                values.get("inputBackground"),
                values.get("foreground"),
                values.get("mutedForeground"),
                values.get("selectionBackground"),
                values.get("selectionForeground"),
                values.get("border"),
                values.get("glViewportBackground")
            ),
            expectedRevision
        );
    }

    private static java.util.Optional<String> first(
        final Map<String, String> colors,
        final List<String> keys
    ) {
        for (String key : keys) {
            final String value = colors.get(key);
            if (value != null && !value.isBlank()) {
                return java.util.Optional.of(value);
            }
        }
        return java.util.Optional.empty();
    }

    private static AppearanceBase base(final ThemeBase base) {
        return switch (base) {
            case LIGHT -> AppearanceBase.LIGHT;
            case DARK -> AppearanceBase.DARK;
            case ANY -> AppearanceBase.NATIVE;
        };
    }
}
