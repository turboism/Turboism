package dev.turboism.test.plugin;

import dev.turboism.sdk.plugin.PluginPaths;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Immutable {@link PluginPaths} test double.
 *
 * <p>The deprecated {@code logsDir()} stays unimplemented on purpose: production plugins must write
 * diagnostics through {@link dev.turboism.sdk.plugin.PluginContext#logger()}, so a fixture that
 * reaches for a log directory must fail instead of silently passing.
 */
public final class FakePluginPaths implements PluginPaths {

    private final Path dataDir;
    private final Path configDir;
    private final Path stateDir;
    private final Path cacheDir;

    private FakePluginPaths(final Path dataDir, final Path configDir, final Path stateDir, final Path cacheDir) {
        this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
        this.configDir = Objects.requireNonNull(configDir, "configDir");
        this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
        this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
    }

    /** Returns paths whose data, config, state and cache directories all resolve to {@code dataDir}. */
    public static FakePluginPaths of(final Path dataDir) {
        return new FakePluginPaths(dataDir, dataDir, dataDir, dataDir);
    }

    /** Returns paths that share {@code dataDir} as config directory and use the supplied siblings. */
    public static FakePluginPaths of(final Path dataDir, final Path stateDir, final Path cacheDir) {
        return new FakePluginPaths(dataDir, dataDir, stateDir, cacheDir);
    }

    /** Starts a builder that declares each directory explicitly. */
    public static Builder builder(final Path dataDir) {
        return new Builder(dataDir);
    }

    @Override
    public Path dataDir() {
        return dataDir;
    }

    @Override
    public Path configDir() {
        return configDir;
    }

    @Override
    public Path stateDir() {
        return stateDir;
    }

    @Override
    public Path cacheDir() {
        return cacheDir;
    }

    /** Builder for fixtures that need a config directory distinct from the data directory. */
    public static final class Builder {

        private final Path dataDir;
        private Path configDir;
        private Path stateDir;
        private Path cacheDir;

        private Builder(final Path dataDir) {
            this.dataDir = Objects.requireNonNull(dataDir, "dataDir");
            this.configDir = dataDir;
            this.stateDir = dataDir;
            this.cacheDir = dataDir;
        }

        public Builder configDir(final Path configDir) {
            this.configDir = Objects.requireNonNull(configDir, "configDir");
            return this;
        }

        public Builder stateDir(final Path stateDir) {
            this.stateDir = Objects.requireNonNull(stateDir, "stateDir");
            return this;
        }

        public Builder cacheDir(final Path cacheDir) {
            this.cacheDir = Objects.requireNonNull(cacheDir, "cacheDir");
            return this;
        }

        public FakePluginPaths build() {
            return new FakePluginPaths(dataDir, configDir, stateDir, cacheDir);
        }
    }
}
