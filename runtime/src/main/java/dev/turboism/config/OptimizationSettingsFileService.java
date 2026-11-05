package dev.turboism.config;

import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import dev.turboism.internal.core.OptimizationSettingsService;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Persists every native-optimization preference in the canonical runtime configuration.
 *
 * <p>One service covers the whole Performance page because the catalogue already declares, per
 * optimization, both its {@code config.json} key and the {@link PerformanceSettingsConfig.Field}
 * that decides the JSON scope. A key the catalogue does not declare is refused by
 * {@link OptimizationCatalog#requireByKey(String)} instead of silently persisting nothing.</p>
 */
public final class OptimizationSettingsFileService implements OptimizationSettingsService, AutoCloseable {

    private final RuntimeConfigRepository config;

    public OptimizationSettingsFileService(final Path turboismHome) {
        this(new RuntimeConfigRepository(turboismHome, ignored -> {}));
    }

    OptimizationSettingsFileService(final RuntimeConfigRepository config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    @Override
    public boolean enabled(final String key) {
        return PerformanceSettingsConfig.readSavedBoolean(config, field(key));
    }

    /**
     * {@inheritDoc}
     *
     * <p>The row, and therefore the field, comes from the catalogue; the value is written as the
     * {@code "true"}/{@code "false"} string every existing launcher already parses.</p>
     */
    @Override
    public void setEnabled(final String key, final boolean enabled) {
        PerformanceSettingsConfig.save(config, field(key), Boolean.toString(enabled));
    }

    private static PerformanceSettingsConfig.Field field(final String key) {
        final Optimization optimization = OptimizationCatalog.requireByKey(key);
        return PerformanceSettingsConfig.Field.forKey(optimization.key());
    }

    @Override
    public void close() {
        // The repository holds no OS resources; the config file is the durable state.
    }
}
