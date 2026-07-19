package dev.turboism.plugin.perfopt.b1.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.config.ConfigError;
import dev.turboism.sdk.config.ConfigErrorCode;
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
import org.junit.jupiter.api.Test;

final class FpsPreferenceBindingTest {

    @Test
    void hydratesWritesStaleChecksAndKeepsPreferenceAcrossDisable() {
        final FakeRegistry registry = new FakeRegistry();
        registry.stored = true;
        registry.revision = 3;
        final FpsPreferenceBinding binding = new FpsPreferenceBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        assertEquals(true, binding.confirmed().enabled());
        assertEquals(ConfigBindingResult.APPLIED, binding.setEnabled(false).toCompletableFuture().join());
        assertEquals("enabled@3", registry.lastWrite);
        binding.disable();
        assertEquals(false, binding.confirmed().enabled());

        registry.writeFailure = new ConfigWriteResult(false, 4,
            Optional.of(new ConfigError(ConfigErrorCode.REVISION_CONFLICT, "conflict", "enabled")));
        final FpsPreferenceBinding fresh = new FpsPreferenceBinding();
        fresh.init(registry).toCompletableFuture().join();
        fresh.enable().toCompletableFuture().join();
        assertEquals(ConfigBindingResult.REVISION_CONFLICT, fresh.setEnabled(true).toCompletableFuture().join());
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        boolean stored;
        long revision;
        String lastWrite;
        ConfigWriteResult writeFailure;

        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            @SuppressWarnings("unchecked") final T value = (T) Boolean.valueOf(stored);
            return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(value, ConfigValueSource.STORED, revision), Optional.empty()));
        }
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            lastWrite = key.name() + "@" + expectedRevision;
            if (writeFailure != null) return CompletableFuture.completedFuture(writeFailure);
            stored = (Boolean) value;
            revision = expectedRevision + 1;
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
    }
}
