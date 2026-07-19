package dev.turboism.plugin.logfilter.b1.application;

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
    @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
    @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) { return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(key.defaultValue(), ConfigValueSource.DEFAULT_MISSING, 0), Optional.empty())); }
    @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) { return CompletableFuture.completedFuture(new ConfigWriteResult(true, expectedRevision + 1, Optional.empty())); }
    @Override public Registration readScope(String relativePath) { return () -> { }; }
    @Override public Registration writeScope(String relativePath) { return () -> { }; }
    @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
    @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
}
