package dev.turboism.plugin.uitheme.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Expands the ten semantic palette slots into the many legacy color keys a Cubism theme file
 * needs, and records which keys it owns.
 *
 * <p>The managed-key set is what makes regeneration safe: keys the generator wrote last time
 * are dropped before the new expansion, so hand-written colors in the same file survive while
 * generated ones are replaced rather than accumulated. Output ordering is deterministic -
 * slots in a fixed order, retained keys sorted by name. Not instantiable.
 */
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

    /**
     * Expands a complete set of slot values into legacy color keys, preserving unmanaged colors.
     *
     * <p>Requires every slot to be present: this is the full-generation entry point, so a partial
     * map is a caller error rather than a partial update - use {@link #edit} for that. Colors
     * from {@code existingColors} are kept unless {@code previousManagedKeys} says the generator
     * wrote them, which is how a regeneration replaces its own output without disturbing anything
     * else.
     *
     * @param slots every slot in {@link #slotOrder()} mapped to a {@code #RRGGBB} value; must not
     *              be null
     * @param existingColors the color keys already in the theme file; must not be null
     * @param previousManagedKeys the keys a previous generation owned and may now overwrite; must
     *                            not be null
     * @return the normalized slots, the resulting color map, the newly managed keys, and
     *         generator metadata recording all three
     * @throws IllegalArgumentException if a slot name is unknown, a slot is missing, or a value is
     *                                  not a six-digit hex color
     * @throws NullPointerException if any argument is null, or any supplied slot value is null
     */
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

    /**
     * Re-generates a palette with some slots changed, carrying over the previous result's colors
     * and managed keys so unmanaged colors survive the edit.
     *
     * @param original the palette to edit; must not be null
     * @param updates only the slots to change, which may be empty; must not be null
     * @return the regenerated palette
     * @throws IllegalArgumentException if an update names an unknown slot or supplies a value that
     *                                  is not a six-digit hex color
     * @throws NullPointerException if either argument is null, or any update value is null
     */
    public static Result edit(final Result original, final Map<String, String> updates) {
        Objects.requireNonNull(original, "original");
        final LinkedHashMap<String, String> slots = new LinkedHashMap<>(original.slotValues());
        for (Map.Entry<String, String> update : normalizeSlots(updates, false).entrySet()) {
            slots.put(update.getKey(), update.getValue());
        }
        return generate(slots, original.colors(), original.managedKeys());
    }

    /**
     * The palette to start from when a theme supplies no value for a slot - a Cubism-like light
     * or dark scheme, with a couple of slots deliberately shared between the two.
     *
     * @param base the appearance to match; anything other than {@link ThemeBase#DARK}, including
     *             {@link ThemeBase#ANY}, yields the light defaults
     * @return an unmodifiable, complete slot map in {@link #slotOrder()} order
     */
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

    /**
     * @return the ten semantic slot names, in the fixed order generation and metadata use;
     *         immutable and identical on every call
     */
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

    /**
     * One generated palette: what was asked for, what it produced, and what it now owns.
     *
     * <p>All four collections are defensively copied into unmodifiable, insertion-ordered forms,
     * so the result is immutable and its iteration order is the deterministic generation order.
     *
     * @param slotValues the normalized slot values this palette was generated from
     * @param colors the resulting legacy color map - generated keys plus the unmanaged keys
     *               carried over
     * @param managedKeys the color keys this generation owns, to be passed back as
     *                    {@code previousManagedKeys} next time so they are replaced rather than
     *                    duplicated
     * @param metadata the generator id and version, each slot value, and the managed keys as a
     *                 comma-separated list, for embedding in the package
     */
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
