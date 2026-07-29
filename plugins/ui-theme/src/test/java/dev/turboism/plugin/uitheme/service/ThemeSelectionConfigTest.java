package dev.turboism.plugin.uitheme.service;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ThemeSelectionConfigTest {

    @Test
    void selectedThemeRoundTripsThroughTypedPluginConfig() {
        MemoryConfigRegistry config = new MemoryConfigRegistry();
        ThemeSelectionConfig selections = new ThemeSelectionConfig(config);

        selections.initialize().toCompletableFuture().join();
        selections.saveSelectedThemeId("user.aurora");

        assertEquals("user.aurora", selections.selectedThemeId().orElseThrow());
        selections.clearSelectedThemeId();
        assertTrue(selections.selectedThemeId().isEmpty());
    }

    private static final class MemoryConfigRegistry implements PluginConfigRegistry {
        private final Map<String, Object> values = new HashMap<>();
        private long revision;

        @Override
        public CompletionStage<Void> registerSchema(ConfigSchema schema, List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            boolean present = values.containsKey(key.name());
            T value = present ? (T) values.get(key.name()) : key.defaultValue();
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(value, present ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING, revision),
                Optional.empty()
            ));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            if (expectedRevision != revision) {
                throw new IllegalStateException("unexpected revision");
            }
            values.put(key.name(), value);
            revision++;
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }

        @Override public Registration readScope(String relativePath) { throw new UnsupportedOperationException(); }
        @Override public Registration writeScope(String relativePath) { throw new UnsupportedOperationException(); }
        @Override public Optional<String> readString(String relativePath, String key) { throw new UnsupportedOperationException(); }
        @Override public void writeString(String relativePath, String key, String value) { throw new UnsupportedOperationException(); }
    }
}
