package dev.turboism.config.optimization;

import dev.turboism.internal.core.OptimizationSettingsService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Named performance optimization profiles derived from the persisted native optimization flags.
 *
 * <p>Turboism derives profiles dynamically from the boolean switches without adding a separate
 * field to {@code config.json}, preserving full backward compatibility.</p>
 */
public enum OptimizationPreset {
    /** Disables every persisted native optimization. */
    OFF("off", "settings.performance.preset.off"),
    /** Enables universal optimizations and omits workload trade-offs. */
    BALANCED("balanced", "settings.performance.preset.balanced"),
    /** Enables all persisted native optimizations for maximum heavy-model editing throughput. */
    HEAVY_EDITING("heavy-editing", "settings.performance.preset.heavy-editing"),
    /** Represents any user-edited flag combination that does not match a built-in preset. */
    CUSTOM("custom", "settings.performance.preset.custom");

    private static final Set<Optimization> WORKLOAD_TRADE_OFF_OPTIMIZATIONS = Set.of(
            OptimizationCatalog.INCREMENTAL_UPDATE,
            OptimizationCatalog.INCREMENTAL_UPDATE_AFFECTERS,
            OptimizationCatalog.ASYNC_READBACK,
            OptimizationCatalog.MESA_GL_THREAD);

    private final String configValue;
    private final String labelKey;

    OptimizationPreset(final String configValue, final String labelKey) {
        this.configValue = configValue;
        this.labelKey = labelKey;
    }

    /** Returns the stable identifier used by settings bindings and choice options. */
    public String configValue() {
        return configValue;
    }

    /** Returns the i18n key for the localized preset display label. */
    public String labelKey() {
        return labelKey;
    }

    /** Returns {@code true} when selecting this preset prescribes fixed optimization flags. */
    public boolean appliesFlags() {
        return this != CUSTOM;
    }

    /**
     * Resolves the prescribed state of {@code optimization} under this preset.
     *
     * @param optimization the persisted optimization entry to inspect
     * @return the target flag state, or empty for {@link #CUSTOM}
     */
    public Optional<Boolean> targetFlag(final Optimization optimization) {
        Objects.requireNonNull(optimization, "optimization");
        if (!optimization.persisted()) {
            throw new IllegalArgumentException("Diagnostic-only optimization has no preset flag");
        }
        return switch (this) {
            case OFF -> Optional.of(Boolean.FALSE);
            case BALANCED -> Optional.of(!WORKLOAD_TRADE_OFF_OPTIMIZATIONS.contains(optimization));
            case HEAVY_EDITING -> Optional.of(Boolean.TRUE);
            case CUSTOM -> Optional.empty();
        };
    }

    /**
     * Returns an immutable map of all persisted optimization keys to their prescribed states.
     *
     * @return key-to-boolean map for this preset
     * @throws IllegalStateException when called on {@link #CUSTOM}
     */
    public Map<String, Boolean> flags() {
        if (!appliesFlags()) {
            throw new IllegalStateException("Custom preset does not define fixed flags");
        }
        final Map<String, Boolean> result = new LinkedHashMap<>();
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            result.put(optimization.key(), targetFlag(optimization).orElseThrow());
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Applies this preset's flags to {@code settings} when this preset prescribes fixed flags.
     *
     * @param settings the target optimization settings service
     * @throws IllegalStateException when called on {@link #CUSTOM} or when storage fails
     */
    public void apply(final OptimizationSettingsService settings) {
        Objects.requireNonNull(settings, "settings");
        if (!appliesFlags()) {
            throw new IllegalStateException("Custom preset cannot be applied to settings");
        }
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            settings.setEnabled(optimization.key(), targetFlag(optimization).orElseThrow());
        }
    }

    /**
     * Looks up a preset by its {@link #configValue()}.
     *
     * @param value the serialized option value
     * @return the matching preset
     * @throws IllegalArgumentException when {@code value} is null or unknown
     */
    public static OptimizationPreset fromConfigValue(final String value) {
        if (value != null) {
            for (final OptimizationPreset preset : values()) {
                if (preset.configValue.equals(value)) {
                    return preset;
                }
            }
        }
        throw new IllegalArgumentException("Unknown optimization preset: " + value);
    }

    /**
     * Derives the active preset from the current state of {@code settings}.
     *
     * @param settings the settings service to read the persisted flags from
     * @return {@link #OFF}, {@link #BALANCED}, {@link #HEAVY_EDITING}, or {@link #CUSTOM}
     */
    public static OptimizationPreset detect(final OptimizationSettingsService settings) {
        Objects.requireNonNull(settings, "settings");
        return detect(settings::enabled);
    }

    /**
     * Derives the active preset from a key-to-boolean map of optimization flags.
     *
     * @param flags map containing boolean states keyed by optimization key
     * @return {@link #OFF}, {@link #BALANCED}, {@link #HEAVY_EDITING}, or {@link #CUSTOM}
     */
    public static OptimizationPreset detect(final Map<String, Boolean> flags) {
        Objects.requireNonNull(flags, "flags");
        return detect(key -> Boolean.TRUE.equals(flags.get(key)));
    }

    /**
     * Derives the active preset by querying {@code enabledReader} for each persisted optimization key.
     *
     * @param enabledReader predicate returning the current boolean state for a persisted key
     * @return {@link #OFF}, {@link #BALANCED}, {@link #HEAVY_EDITING}, or {@link #CUSTOM}
     */
    public static OptimizationPreset detect(final Predicate<String> enabledReader) {
        Objects.requireNonNull(enabledReader, "enabledReader");
        boolean matchesOff = true;
        boolean matchesBalanced = true;
        boolean matchesHeavyEditing = true;
        for (final Optimization optimization : OptimizationCatalog.persisted()) {
            final boolean enabled = enabledReader.test(optimization.key());
            if (enabled) {
                matchesOff = false;
            } else {
                matchesHeavyEditing = false;
            }
            final boolean expectedBalanced = !WORKLOAD_TRADE_OFF_OPTIMIZATIONS.contains(optimization);
            if (enabled != expectedBalanced) {
                matchesBalanced = false;
            }
        }
        if (matchesOff) return OFF;
        if (matchesBalanced) return BALANCED;
        if (matchesHeavyEditing) return HEAVY_EDITING;
        return CUSTOM;
    }
}
