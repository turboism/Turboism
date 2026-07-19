package dev.turboism.plugin.logfilter.b1.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.plugin.logfilter.b1.domain.KeywordMode;
import dev.turboism.plugin.logfilter.b1.domain.LogFilterSettings;
import dev.turboism.plugin.logfilter.b1.domain.LogLevel;
import dev.turboism.sdk.config.ConfigError;
import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigRegistrationError;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class LogFilterSettingsBindingTest {

    @Test
    void registersSchemaHydratesStoredValuesAndWritesInFixedRevisionOrder() {
        final FakeRegistry registry = new FakeRegistry();
        registry.values.put("minimumLevel", LogLevel.WARNING);
        registry.values.put("keywordMode", KeywordMode.ALL);
        registry.values.put("caseSensitive", true);
        registry.values.put("keywords", List.of("alpha", "beta"));
        registry.revision = 7;
        final LogFilterSettingsBinding binding = new LogFilterSettingsBinding();
        binding.init(registry).toCompletableFuture().join();

        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        assertEquals(new LogFilterSettings(LogLevel.WARNING, KeywordMode.ALL, true, List.of("alpha", "beta")), binding.confirmed());

        final LogFilterSettings update = new LogFilterSettings(LogLevel.ERROR, KeywordMode.ANY, false, List.of("fatal"));
        assertEquals(ConfigBindingResult.APPLIED, binding.update(update).toCompletableFuture().join());
        assertEquals(List.of("minimumLevel@7", "keywordMode@8", "caseSensitive@9", "keywords@10"), registry.writes);
        assertEquals(update, binding.confirmed());
    }

    @Test
    void returnsPermissionDeniedAndRuntimeUnavailableWithoutMutatingDefaults() {
        final FakeRegistry denied = new FakeRegistry();
        denied.registrationFailure = new ConfigRegistrationException(ConfigRegistrationError.PERMISSION_DENIED);
        final LogFilterSettingsBinding deniedBinding = new LogFilterSettingsBinding();
        assertEquals(ConfigBindingResult.PERMISSION_DENIED,
            deniedBinding.init(denied).toCompletableFuture().join());
        assertEquals(LogFilterSettings.defaults(), deniedBinding.confirmed());

        final FakeRegistry unavailable = new FakeRegistry();
        unavailable.registrationFailure = new ConfigRegistrationException(ConfigRegistrationError.RUNTIME_UNAVAILABLE);
        final LogFilterSettingsBinding unavailableBinding = new LogFilterSettingsBinding();
        assertEquals(ConfigBindingResult.RUNTIME_UNAVAILABLE,
            unavailableBinding.init(unavailable).toCompletableFuture().join());
    }

    @Test
    void detectsMismatchedReadRevisionsAfterOneRetry() {
        final FakeRegistry registry = new FakeRegistry();
        registry.readRevisionOverrides.add(Map.of("minimumLevel", 1L, "keywordMode", 2L, "caseSensitive", 1L, "keywords", 1L));
        registry.readRevisionOverrides.add(Map.of("minimumLevel", 3L, "keywordMode", 4L, "caseSensitive", 3L, "keywords", 3L));
        final LogFilterSettingsBinding binding = new LogFilterSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.REVISION_CONFLICT, binding.enable().toCompletableFuture().join());
        assertEquals(LogFilterSettings.defaults(), binding.confirmed());
        assertEquals(8, registry.reads);
    }

    @Test
    void keepsConfirmedStateOnStaleOrPartialWritesAndReconciles() {
        final FakeRegistry stale = new FakeRegistry();
        final LogFilterSettingsBinding staleBinding = enabled(stale);
        stale.writeFailures.add(new ConfigWriteResult(false, 1,
            Optional.of(error(ConfigErrorCode.REVISION_CONFLICT, "minimumLevel"))));
        assertEquals(ConfigBindingResult.REVISION_CONFLICT,
            staleBinding.update(new LogFilterSettings(LogLevel.ERROR, KeywordMode.ANY, false, List.of())).toCompletableFuture().join());
        assertEquals(LogFilterSettings.defaults(), staleBinding.confirmed());

        final FakeRegistry partial = new FakeRegistry();
        final LogFilterSettingsBinding partialBinding = enabled(partial);
        partial.writeFailures.add(FakeRegistry.SUCCESS_SENTINEL);
        partial.writeFailures.add(new ConfigWriteResult(false, 1,
            Optional.of(error(ConfigErrorCode.PERSISTENCE_FAILED, "keywordMode"))));
        assertEquals(ConfigBindingResult.PARTIAL_PERSISTENCE,
            partialBinding.update(new LogFilterSettings(LogLevel.ERROR, KeywordMode.ALL, true, List.of("x"))).toCompletableFuture().join());
        assertEquals(LogFilterSettings.defaults(), partialBinding.confirmed());
        assertEquals(8, partial.reads, "partial persistence triggers a complete reconcile read");
    }

    @Test
    void ignoresLateHydrationAfterDisableAndFreshRegistryCanReenable() {
        final FakeRegistry registry = new FakeRegistry();
        registry.deferReads = true;
        final LogFilterSettingsBinding binding = new LogFilterSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        final CompletionStage<ConfigBindingResult> enabling = binding.enable();
        binding.disable();
        registry.completeDeferredReads();
        assertEquals(ConfigBindingResult.DISABLED, enabling.toCompletableFuture().join());
        assertEquals(LogFilterSettings.defaults(), binding.confirmed());

        final LogFilterSettingsBinding fresh = enabled(new FakeRegistry());
        assertEquals(LogFilterSettings.defaults(), fresh.confirmed());
    }

    private static LogFilterSettingsBinding enabled(FakeRegistry registry) {
        final LogFilterSettingsBinding binding = new LogFilterSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        return binding;
    }

    private static ConfigError error(ConfigErrorCode code, String key) {
        return new ConfigError(code, code.name(), key);
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        private final Map<String, Object> values = new HashMap<>();
        private final List<String> writes = new java.util.ArrayList<>();
        private static final ConfigWriteResult SUCCESS_SENTINEL = new ConfigWriteResult(
            true, 1, Optional.empty()
        );
        private final Queue<ConfigWriteResult> writeFailures = new ArrayDeque<>();
        private final Queue<Map<String, Long>> readRevisionOverrides = new ArrayDeque<>();
        private final List<CompletableFuture<ConfigReadResult<?>>> deferred = new java.util.ArrayList<>();
        private RuntimeException registrationFailure;
        private long revision;
        private int reads;
        private boolean deferReads;

        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) {
            if (registrationFailure != null) return CompletableFuture.failedFuture(registrationFailure);
            return CompletableFuture.completedFuture(null);
        }

        @SuppressWarnings("unchecked")
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            reads++;
            final long readRevision = readRevisionOverrides.isEmpty()
                ? revision
                : readRevisionOverrides.peek().getOrDefault(key.name(), revision);
            if (!readRevisionOverrides.isEmpty() && reads % 4 == 0) readRevisionOverrides.remove();
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            final ConfigReadResult<T> result = new ConfigReadResult<>(
                new ConfigValue<>(value, values.containsKey(key.name()) ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING, readRevision),
                Optional.empty()
            );
            if (!deferReads) return CompletableFuture.completedFuture(result);
            final CompletableFuture<ConfigReadResult<T>> future = new CompletableFuture<>();
            deferred.add((CompletableFuture<ConfigReadResult<?>>) (CompletableFuture<?>) future);
            return future;
        }

        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            writes.add(key.name() + "@" + expectedRevision);
            if (!writeFailures.isEmpty()) {
                final ConfigWriteResult failure = writeFailures.remove();
                if (failure != SUCCESS_SENTINEL) return CompletableFuture.completedFuture(failure);
            }
            revision = expectedRevision + 1;
            values.put(key.name(), value);
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }

        void completeDeferredReads() {
            deferReads = false;
            for (CompletableFuture<ConfigReadResult<?>> future : deferred) {
                future.complete(new ConfigReadResult<>(new ConfigValue<>("ignored", ConfigValueSource.DEFAULT_MISSING, 0), Optional.empty()));
            }
        }

        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
    }
}
