package dev.turboism.plugin.textureatlas.test;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public final class DefaultPluginConfigRegistry implements PluginConfigRegistry {
    private Object value;
    private long revision;
    @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
    @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
        @SuppressWarnings("unchecked") final T current = value == null ? key.defaultValue() : (T) value;
        final ConfigValueSource source = value == null ? ConfigValueSource.DEFAULT_MISSING : ConfigValueSource.STORED;
        return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(current, source, revision), Optional.empty()));
    }
    @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
        if (expectedRevision != revision) return CompletableFuture.completedFuture(new ConfigWriteResult(false, revision, Optional.empty()));
        this.value = value;
        revision++;
        return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
    }
    @Override public Registration readScope(String relativePath) { return () -> { }; }
    @Override public Registration writeScope(String relativePath) { return () -> { }; }
    @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
    @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
}
