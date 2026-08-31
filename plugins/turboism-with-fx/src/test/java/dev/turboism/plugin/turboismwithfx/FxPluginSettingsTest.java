package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Provider-profile persistence, credential reuse, and rollback across every written key. */
final class FxPluginSettingsTest {

    @TempDir
    Path credentials;

    @Test
    void savedApiKeysAreReusedAfterRestartAndProfileMetadataStaysNonSecret() throws Exception {
        final MemoryConfig config = new MemoryConfig();
        final FxProviderProfile profile = profile("custom-1", "vendor/default");

        try (FxPluginSettings settings = settings(config)) {
            settings.writeUserSettings(
                "",
                true,
                "",
                new FxProviderConfiguration(
                    profile.id(),
                    List.of(profile),
                    Map.of(profile.id(), "sk-persisted-value")
                )
            );
        }

        assertFalse(String.join("\n", config.values.values()).contains("sk-persisted-value"));
        assertTrue(Files.isRegularFile(credentials.resolve("auth.json")));

        try (FxPluginSettings reopened = settings(config)) {
            final FxProviderConfiguration restored = reopened.providerConfiguration();

            assertEquals(profile.id(), restored.activeProfileId());
            assertEquals(List.of(profile), restored.customProfiles());
            assertEquals("sk-persisted-value", restored.customEndpoint().resolveApiKey());
            assertTrue(reopened.secureApiKeyPersistenceAvailable());
        }
    }

    @Test
    void removingAProfileAlsoRemovesItsSavedCredential() throws Exception {
        final MemoryConfig config = new MemoryConfig();
        final FxProviderProfile first = profile("custom-1", "vendor/one");
        final FxProviderProfile second = profile("custom-2", "vendor/two");

        try (FxPluginSettings settings = settings(config)) {
            settings.writeUserSettings("", true, "", new FxProviderConfiguration(
                first.id(),
                List.of(first, second),
                Map.of(first.id(), "sk-first", second.id(), "sk-second")
            ));
            settings.writeUserSettings("", true, "", new FxProviderConfiguration(
                first.id(),
                List.of(first),
                Map.of(first.id(), "sk-first")
            ));
        }

        final String stored = Files.readString(credentials.resolve("auth.json"));
        assertTrue(stored.contains("sk-first"));
        assertFalse(stored.contains("sk-second"));

        try (FxPluginSettings reopened = settings(config)) {
            assertEquals(List.of(first), reopened.providerConfiguration().customProfiles());
        }
    }

    @Test
    void anyFailedSettingWriteRestoresEveryPreviouslyPersistedKey() throws Exception {
        final List<String> keys = List.of(
            "fxExecutable",
            "allowFxNativeTools",
            "initialPrompt",
            "activeProviderProfile",
            "customProviderProfiles"
        );
        for (String failedKey : keys) {
            final MemoryConfig config = new MemoryConfig();
            try (FxPluginSettings settings = settings(config)) {
                settings.writeUserSettings("/old/fx", false, "old", new FxProviderConfiguration());
            }
            final Map<String, String> before = new LinkedHashMap<>(config.values);
            config.failKey = failedKey;

            try (FxPluginSettings settings = settings(config)) {
                final FxProviderProfile profile = profile("custom-1", "vendor/default");
                try {
                    settings.writeUserSettings("/new/fx", true, "new", new FxProviderConfiguration(
                        profile.id(),
                        List.of(profile),
                        Map.of(profile.id(), "sk-never-committed")
                    ));
                    throw new AssertionError("expected a write failure for " + failedKey);
                } catch (PluginConfigException expected) {
                    assertEquals(before, config.values);
                }
            }
            assertFalse(
                Files.isRegularFile(credentials.resolve("auth.json"))
                    && Files.readString(credentials.resolve("auth.json")).contains("sk-never-committed"),
                "credential persisted despite failed " + failedKey
            );
        }
    }

    private FxPluginSettings settings(final MemoryConfig config) {
        return new FxPluginSettings(
            config,
            logger(),
            new FxSecretStore(credentials, null, logger())
        );
    }

    private static FxProviderProfile profile(final String id, final String model) {
        return new FxProviderProfile(
            id,
            "Self hosted " + id,
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            "http://127.0.0.1:8000/v1",
            "",
            model,
            List.of()
        );
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }

    private static final class MemoryConfig implements PluginConfigRegistry {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> written = new ArrayList<>();
        private String failKey;

        @Override public Registration readScope(final String relativePath) {
            return () -> { };
        }

        @Override public Registration writeScope(final String relativePath) {
            return () -> { };
        }

        @Override public Optional<String> readString(final String relativePath, final String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override public void writeString(
            final String relativePath,
            final String key,
            final String value
        ) throws PluginConfigException {
            if (key.equals(failKey) && !written.contains(key)) {
                written.add(key);
                throw new PluginConfigException("write rejected for " + key);
            }
            values.put(key, value);
        }

        @Override public CompletionStage<Void> registerSchema(
            final ConfigSchema schema,
            final List<ConfigMigration> migrations
        ) {
            return CompletableFuture.completedFuture(null);
        }

        @Override public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key,
            final T value,
            final long expectedRevision
        ) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
