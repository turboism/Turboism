package dev.turboism.plugin.atlasmaxrectsbssf.test;

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
    private final java.util.Map<ConfigKey<?>, Object> values = new java.util.HashMap<>();
    private long revision;
    @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) {
        final java.util.regex.Pattern identifier = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,127}");
        for (ConfigKey<?> key : schema.keys()) {
            if (!schema.configId().equals(key.configId()) || !identifier.matcher(key.name()).matches()) {
                throw new dev.turboism.sdk.config.ConfigSchemaValidationException(
                    dev.turboism.sdk.config.ConfigSchemaValidationError.INVALID_KEY
                );
            }
        }
        return CompletableFuture.completedFuture(null);
    }
    @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
        final boolean stored = values.containsKey(key);
        @SuppressWarnings("unchecked") final T current = stored ? (T) values.get(key) : key.defaultValue();
        final ConfigValueSource source = stored ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING;
        return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(current, source, revision), Optional.empty()));
    }
    @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
        if (expectedRevision != revision) return CompletableFuture.completedFuture(new ConfigWriteResult(false, revision, Optional.empty()));
        values.put(key, value);
        revision++;
        return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
    }
    @Override public Registration readScope(String relativePath) { return () -> { }; }
    @Override public Registration writeScope(String relativePath) { return () -> { }; }
    @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
    @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
}
