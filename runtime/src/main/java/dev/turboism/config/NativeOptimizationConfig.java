package dev.turboism.config;

import dev.turboism.config.optimization.Optimization;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable launch snapshot of native optimization preferences from the global configuration. */
public final class NativeOptimizationConfig {
    /** Resolved startup preference, including the source of an explicit override. */
    public record Choice(boolean enabled, String source) {}

    private final PerformanceSettingsConfig settings;

    private NativeOptimizationConfig(PerformanceSettingsConfig settings) {
        this.settings = settings;
    }
    /** Captures every performance preference once for this launch attempt. */
    public static NativeOptimizationConfig load(Path home, Consumer<String> diagnostic) {
        return new NativeOptimizationConfig(PerformanceSettingsConfig.load(home, diagnostic));
    }
    /** The complete Performance page snapshot used by this launch. */
    public PerformanceSettingsConfig settings() {
        return settings;
    }
    /**
     * Immutable native optimization request.
     *
     * @throws IllegalArgumentException when {@code optimization} is diagnostic-only and therefore
     *     has no persisted preference to resolve
     */
    public Choice choice(Optimization optimization) {
        final Optimization checked = Objects.requireNonNull(optimization, "optimization");
        if (!checked.persisted()) {
            throw new IllegalArgumentException("No persisted preference: " + checked.protocols());
        }
        var value = settings.value(PerformanceSettingsConfig.Field.forKey(checked.key()));
        return new Choice(Boolean.parseBoolean(value.value()), value.source());
    }
}
