package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

public final class ThemePaletteGenerator {

    private static final String GENERATOR_ID = "turboism-theme-gen";
    private static final String GENERATOR_VERSION = "1.0.0";
    private static final Pattern COLOR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final List<String> SLOT_ORDER = List.of(
        "accent",
        "background",
        "surface",
        "inputBackground",
        "foreground",
        "mutedForeground",
        "selectionBackground",
        "selectionForeground",
        "border",
        "glViewportBackground"
    );
    private static final Map<String, List<String>> SLOT_TO_KEYS = slotMap();

    private ThemePaletteGenerator() {
    }

    public static Result generate(
        final Map<String, String> slots,
        final Map<String, String> existingColors,
        final Set<String> previousManagedKeys
    ) {
        Objects.requireNonNull(slots, "slots");
        Objects.requireNonNull(existingColors, "existingColors");
        Objects.requireNonNull(previousManagedKeys, "previousManagedKeys");
        final LinkedHashMap<String, String> normalizedSlots = normalizeSlots(slots, true);
        final LinkedHashMap<String, String> colors = new LinkedHashMap<>();
        existingColors.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            if (!previousManagedKeys.contains(entry.getKey())) {
                colors.put(entry.getKey(), entry.getValue());
            }
        });
        final LinkedHashSet<String> managedKeys = new LinkedHashSet<>();
        for (String slot : SLOT_ORDER) {
            final String value = normalizedSlots.get(slot);
            if (value == null) {
                continue;
            }
            for (String key : SLOT_TO_KEYS.get(slot)) {
                colors.put(key, value);
                managedKeys.add(key);
            }
        }
        return result(normalizedSlots, colors, managedKeys);
    }

    public static Result edit(final Result original, final Map<String, String> updates) {
        Objects.requireNonNull(original, "original");
        final LinkedHashMap<String, String> slots = new LinkedHashMap<>(original.slotValues());
        for (Map.Entry<String, String> update : normalizeSlots(updates, false).entrySet()) {
            slots.put(update.getKey(), update.getValue());
        }
        return generate(slots, original.colors(), original.managedKeys());
    }

    public static Map<String, String> fallbackDefaults(final ThemeBase base) {
        final boolean dark = base == ThemeBase.DARK;
        final LinkedHashMap<String, String> defaults = new LinkedHashMap<>();
        defaults.put("accent", dark ? "#539CDF" : "#2675BF");
        defaults.put("background", dark ? "#2B2B2B" : "#F0F0F0");
        defaults.put("surface", dark ? "#3C3C3C" : "#FFFFFF");
        defaults.put("inputBackground", dark ? "#3C3C3C" : "#FFFFFF");
        defaults.put("foreground", dark ? "#BBBBBB" : "#1E1E1E");
        defaults.put("mutedForeground", "#808080");
        defaults.put("selectionBackground", dark ? "#2D5F8A" : "#2675BF");
        defaults.put("selectionForeground", "#FFFFFF");
        defaults.put("border", dark ? "#555555" : "#CCCCCC");
        defaults.put("glViewportBackground", dark ? "#1E1E1E" : "#E6E6E6");
        return Collections.unmodifiableMap(defaults);
    }

    public static List<String> slotOrder() {
        return SLOT_ORDER;
    }

    private static Result result(
        final LinkedHashMap<String, String> slots,
        final LinkedHashMap<String, String> colors,
        final LinkedHashSet<String> managedKeys
    ) {
        final LinkedHashMap<String, String> metadata = new LinkedHashMap<>();
        metadata.put("generator.id", GENERATOR_ID);
        metadata.put("generator.version", GENERATOR_VERSION);
        for (String slot : SLOT_ORDER) {
            if (slots.containsKey(slot)) {
                metadata.put("slot." + slot, slots.get(slot));
            }
        }
        metadata.put("managedKeys", String.join(",", managedKeys));
        return new Result(slots, colors, managedKeys, metadata);
    }

    private static LinkedHashMap<String, String> normalizeSlots(
        final Map<String, String> values,
        final boolean requireAll
    ) {
        Objects.requireNonNull(values, "values");
        for (String key : values.keySet()) {
            if (!SLOT_ORDER.contains(key)) {
                throw new IllegalArgumentException("unknown theme slot: " + key);
            }
        }
        if (requireAll && !values.keySet().containsAll(SLOT_ORDER)) {
            throw new IllegalArgumentException("all legacy theme slots are required");
        }
        final LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (String slot : SLOT_ORDER) {
            if (!values.containsKey(slot)) {
                continue;
            }
            final String value = Objects.requireNonNull(values.get(slot), slot);
            if (!COLOR.matcher(value).matches()) {
                throw new IllegalArgumentException("invalid theme color for " + slot);
            }
            result.put(slot, value.toUpperCase(java.util.Locale.ROOT));
        }
        return result;
    }

    private static Map<String, List<String>> slotMap() {
        final LinkedHashMap<String, List<String>> values = new LinkedHashMap<>();
        values.put("accent", List.of(
            "accent", "CubismCommon.blue", "CubismCommon.selectedColor", "CubismCommon.activeColor",
            "CubismCommon.progressColor", "CubismCommon.linkColor", "Component.accentColor",
            "ToggleButton.selectedBackground"
        ));
        values.put("background", List.of("CubismCommon.background", "Panel.background"));
        values.put("surface", List.of(
            "CubismCommon.surface", "Button.background", "ComboBox.background", "Table.background"
        ));
        values.put("inputBackground", List.of(
            "CubismCommon.inputBackground", "TextField.background", "TextArea.background"
        ));
        values.put("foreground", List.of(
            "CubismCommon.foreground", "Button.foreground", "Label.foreground", "ComboBox.foreground",
            "TextField.foreground", "TextArea.foreground"
        ));
        values.put("mutedForeground", List.of("CubismCommon.mutedForeground"));
        values.put("selectionBackground", List.of(
            "CubismCommon.selectionBackground", "Table.selectionBackground"
        ));
        values.put("selectionForeground", List.of(
            "CubismCommon.selectionForeground", "Table.selectionForeground"
        ));
        values.put("border", List.of("CubismCommon.border", "Separator.foreground"));
        values.put("glViewportBackground", List.of("CubismCommon.gl.viewArea.background"));
        return Collections.unmodifiableMap(values);
    }

    public record Result(
        Map<String, String> slotValues,
        Map<String, String> colors,
        Set<String> managedKeys,
        Map<String, String> metadata
    ) {
        public Result {
            slotValues = immutableMap(slotValues);
            colors = immutableMap(colors);
            managedKeys = Collections.unmodifiableSet(new LinkedHashSet<>(managedKeys));
            metadata = immutableMap(metadata);
        }

        private static Map<String, String> immutableMap(final Map<String, String> source) {
            return Collections.unmodifiableMap(new LinkedHashMap<>(source));
        }
    }
}
