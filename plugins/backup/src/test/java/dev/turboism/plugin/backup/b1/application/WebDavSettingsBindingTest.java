package dev.turboism.plugin.backup.b1.application;

import dev.turboism.plugin.backup.webdav.WebDavConfig;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class WebDavSettingsBindingTest {

    @Test
    void updatePersistsEveryKeyAndConfirmsByReadback() {
        final FakeRegistry registry = new FakeRegistry();
        registry.values.put("url", "http://old.example");
        registry.revision = 3;
        final WebDavSettingsBinding binding = new WebDavSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        binding.enable();
        binding.read().toCompletableFuture().join();

        final WebDavConfig target = new WebDavConfig(
            true, URI.create("https://dav.example/remote.php/webdav"), "alice", "s3cret!",
            "/turboism-backup", true, 2, 800, 45);
        assertEquals(ConfigBindingResult.APPLIED, binding.update(target).toCompletableFuture().join());

        final WebDavConfig readback = binding.read().toCompletableFuture().join();
        assertEquals(target, readback, "the write path must be confirmed by readback");
        assertEquals(target, binding.confirmed());
        assertEquals(9, registry.writes.size(), "every key must be written exactly once");
        assertEquals(3, registry.writes.get(0).expected, "the chain must start from the observed revision");
    }

    @Test
    void updateShortCircuitsWhenTheTargetEqualsTheConfirmedValues() {
        final FakeRegistry registry = new FakeRegistry();
        final WebDavSettingsBinding binding = new WebDavSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        binding.enable();
        final WebDavConfig current = binding.read().toCompletableFuture().join();
        assertEquals(ConfigBindingResult.UNCHANGED, binding.update(current).toCompletableFuture().join());
        assertEquals(0, registry.writes.size(), "no write for an identical target");
    }

    @Test
    void updateReportsPartialPersistenceWhenTheReadbackDoesNotConfirm() {
        final FakeRegistry registry = new FakeRegistry();
        final WebDavSettingsBinding binding = new WebDavSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        binding.enable();
        binding.read().toCompletableFuture().join();
        registry.readOverride = Optional.of(new ConfigReadResult<>(
            new ConfigValue<>("http://other.example", ConfigValueSource.STORED, 99),
            Optional.empty()));
        final WebDavConfig target = new WebDavConfig(
            true, URI.create("https://dav.example"), "", "", "/x", true, 1, 100, 30);
        assertEquals(
            ConfigBindingResult.PARTIAL_PERSISTENCE,
            binding.update(target).toCompletableFuture().join(),
            "an unconfirmed readback must not be reported as applied"
        );
    }

    @Test
    void updateFailsClosedWhenDisabled() {
        final FakeRegistry registry = new FakeRegistry();
        final WebDavSettingsBinding binding = new WebDavSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        final WebDavConfig target = new WebDavConfig(
            true, URI.create("https://dav.example"), "", "", "/x", true, 1, 100, 30);
        assertEquals(ConfigBindingResult.DISABLED, binding.update(target).toCompletableFuture().join());
        assertEquals(0, registry.writes.size());
    }

    /** In-memory registry mirroring the typed read/write contract (revision-chained). */
    private static final class FakeRegistry implements PluginConfigRegistry {
        static final ConfigWriteResult SUCCESS = new ConfigWriteResult(true, 1, Optional.empty());
        final Map<String, Object> values = new HashMap<>();
        final List<Write> writes = new java.util.ArrayList<>();
        final Queue<ConfigWriteResult> writeResults = new ArrayDeque<>();
        long revision;
        Optional<ConfigReadResult<?>> readOverride = Optional.empty();

        @Override
        public CompletionStage<Void> registerSchema(
            final ConfigSchema schema, final List<dev.turboism.sdk.config.ConfigMigration> migrations
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            if (readOverride.isPresent() && "url".equals(key.name())) {
                return CompletableFuture.completedFuture((ConfigReadResult<T>) readOverride.get());
            }
            final T value = (T) values.getOrDefault(key.name(), key.defaultValue());
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(value, ConfigValueSource.STORED, revision), Optional.empty()));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key, final T value, final long expected
        ) {
            writes.add(new Write(key.name(), expected));
            if (!writeResults.isEmpty()) {
                final ConfigWriteResult queued = writeResults.remove();
                if (queued != SUCCESS) {
                    return CompletableFuture.completedFuture(queued);
                }
            }
            values.put(key.name(), value);
            revision = expected + 1;
            return CompletableFuture.completedFuture(new ConfigWriteResult(true, revision, Optional.empty()));
        }

        @Override
        public Registration readScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> { };
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            return Optional.empty();
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
            throws PluginConfigException {
            throw new PluginConfigException("not supported");
        }

        record Write(String key, long expected) { }
    }
}
