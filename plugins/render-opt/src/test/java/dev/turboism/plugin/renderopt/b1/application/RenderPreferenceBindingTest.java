package dev.turboism.plugin.renderopt.b1.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.turboism.plugin.renderopt.b1.domain.RenderOptInReportStatus;
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

final class RenderPreferenceBindingTest {

    @Test
    void hydratesAndPersistsRequestedWithoutEverApplyingOptimization() {
        final FakeRegistry registry = new FakeRegistry();
        final RenderPreferenceBinding binding = new RenderPreferenceBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        assertEquals(RenderOptInReportStatus.NOT_REQUESTED, binding.confirmed().reportStatus());
        assertEquals(ConfigBindingResult.APPLIED, binding.setRequested(true).toCompletableFuture().join());
        assertEquals("requested@0", registry.lastWrite);
        assertEquals(RenderOptInReportStatus.REQUESTED_PENDING_CAPABILITY, binding.confirmed().reportStatus());
        assertFalse(binding.confirmed().effectiveOptimization());
        binding.disable();
        assertEquals(ConfigBindingResult.DISABLED, binding.setRequested(false).toCompletableFuture().join());
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        boolean requested;
        long revision;
        String lastWrite;
        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            @SuppressWarnings("unchecked") final T value = (T) Boolean.valueOf(requested);
            return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(value, ConfigValueSource.DEFAULT_MISSING, revision), Optional.empty()));
        }
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            lastWrite = key.name() + "@" + expectedRevision;
            requested = (Boolean) value;
            revision = expectedRevision + 1;
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
    }
}
