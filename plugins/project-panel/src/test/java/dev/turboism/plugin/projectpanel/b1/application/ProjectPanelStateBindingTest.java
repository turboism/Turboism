package dev.turboism.plugin.projectpanel.b1.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.plugin.projectpanel.b1.domain.ProjectPanelStateModel;
import dev.turboism.plugin.projectpanel.b1.domain.ProjectPhase;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class ProjectPanelStateBindingTest {

    @Test
    void hydratesWithoutReducerRevisionAndPersistsInFixedOrder() {
        final FakeRegistry registry = new FakeRegistry();
        registry.values.put("lastPhase", StoredProjectPhase.CLOSED);
        registry.values.put("openingCount", 1);
        registry.values.put("openedCount", 2);
        registry.values.put("closingCount", 3);
        registry.values.put("closedCount", 4);
        registry.revision = 9;
        final ProjectPanelStateBinding binding = new ProjectPanelStateBinding();
        binding.init(registry).toCompletableFuture().join();
        assertEquals(ConfigBindingResult.APPLIED, binding.enable().toCompletableFuture().join());
        assertEquals(0, binding.confirmed().revision());
        assertEquals(ProjectPhase.CLOSED, binding.confirmed().lastPhase());

        final ProjectPanelStateModel updated = binding.confirmed().activate().state().apply(ProjectPhase.OPENING).state();
        assertEquals(ConfigBindingResult.APPLIED, binding.update(updated).toCompletableFuture().join());
        assertEquals(List.of("lastPhase@9", "openingCount@10", "openedCount@11", "closingCount@12", "closedCount@13"), registry.writes);
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        final Map<String, Object> values = new HashMap<>();
        final List<String> writes = new java.util.ArrayList<>();
        long revision;
        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
        @SuppressWarnings("unchecked") @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(value, ConfigValueSource.STORED, revision), Optional.empty()));
        }
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            writes.add(key.name() + "@" + expectedRevision);
            values.put(key.name(), value);
            revision = expectedRevision + 1;
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
    }
}
