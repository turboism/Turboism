package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** Launch/session setting persistence, quoting, and rollback across every written key. */
final class AcpPluginSettingsTest {

    @Test
    void defaultsComeFromTheCatalog() {
        try (AcpPluginSettings settings = new AcpPluginSettings(new MemoryConfig(), logger())) {
            assertEquals(AcpPluginSettings.defaultAgentId(), settings.agentId());
            assertEquals(null, settings.customCommand());
            assertEquals(null, settings.sessionId());
            assertTrue(settings.initialPrompt().isEmpty());
        }
    }

    @Test
    void userSettingsPersistAcrossReopen() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings first = new AcpPluginSettings(config, logger())) {
            assertTrue(first.writeUserSettings("codex", null, "keep it tidy"));
            first.writeSessionId("session-1");
        }
        try (AcpPluginSettings second = new AcpPluginSettings(config, logger())) {
            assertEquals("codex", second.agentId());
            assertEquals("keep it tidy", second.initialPrompt().orElseThrow());
            assertEquals("session-1", second.sessionId());
            second.clearSessionId();
            assertEquals(null, second.sessionId());
        }
        try (AcpPluginSettings third = new AcpPluginSettings(config, logger())) {
            assertEquals(null, third.sessionId());
        }
    }

    @Test
    void changingAgentsDropsTheDurableSession() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings settings = new AcpPluginSettings(config, logger())) {
            assertTrue(settings.writeUserSettings("claude", null, ""));
            settings.writeSessionId("session-old");
            assertTrue(settings.writeUserSettings("codex", null, ""));
            assertEquals(null, settings.sessionId());
        }
    }

    @Test
    void unknownAgentAndMalformedCommandAreRejected() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings settings = new AcpPluginSettings(config, logger())) {
            assertThrows(IllegalArgumentException.class, () -> settings.writeUserSettings("no-such-agent", null, ""));
            assertThrows(
                    IllegalArgumentException.class, () -> settings.writeUserSettings("custom", "'unterminated", ""));
            assertEquals(AcpPluginSettings.defaultAgentId(), settings.agentId());
            assertTrue(config.written.isEmpty());
        }
    }

    @Test
    void midWriteFailureRollsBackEveryKey() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings first = new AcpPluginSettings(config, logger())) {
            assertTrue(first.writeUserSettings("codex", null, "before"));
        }
        config.failKey = "initialPrompt";
        try (AcpPluginSettings settings = new AcpPluginSettings(config, logger())) {
            assertFalse(settings.writeUserSettings("custom", "/opt/agent --acp", "after"));
            assertEquals("codex", settings.agentId());
            assertEquals("before", settings.initialPrompt().orElseThrow());
        }
        try (AcpPluginSettings reopened = new AcpPluginSettings(config, logger())) {
            assertEquals("codex", reopened.agentId());
            assertEquals("before", reopened.initialPrompt().orElseThrow());
            assertEquals(null, reopened.customCommand());
        }
    }

    @Test
    void storedUnknownAgentAndOversizedValuesAreDropped() {
        final MemoryConfig config = new MemoryConfig();
        config.values.put("agentId", "removed-agent");
        config.values.put("customCommand", "x".repeat(9000));
        config.values.put("acpSessionId", "x".repeat(9000));
        try (AcpPluginSettings settings = new AcpPluginSettings(config, logger())) {
            assertEquals(AcpPluginSettings.defaultAgentId(), settings.agentId());
            assertEquals(null, settings.customCommand());
            assertEquals(null, settings.sessionId());
        }
    }

    @Test
    void splitCommandHonorsQuotesAndRejectsBadInput() {
        assertEquals(
                List.of("/opt/agent", "--flag", "two words", "it's"),
                AcpPluginSettings.splitCommand("/opt/agent --flag \"two words\" \"it's\""));
        assertEquals(List.of("a", ""), AcpPluginSettings.splitCommand("a ''"));
        assertThrows(IllegalArgumentException.class, () -> AcpPluginSettings.splitCommand(""));
        assertThrows(IllegalArgumentException.class, () -> AcpPluginSettings.splitCommand("\"open"));
    }

    @Test
    void closePrioritizesFatalErrorWhenBothScopesFail() {
        final MemoryConfig config = new MemoryConfig();
        final OutOfMemoryError writeFatal = new OutOfMemoryError("write OOM");
        final IllegalStateException readException = new IllegalStateException("read failed");
        config.writeCloseFailure = writeFatal;
        config.readCloseFailure = readException;

        final AcpPluginSettings settings = new AcpPluginSettings(config, logger());
        final OutOfMemoryError thrown = assertThrows(OutOfMemoryError.class, settings::close);
        assertSame(writeFatal, thrown);
        assertEquals(1, thrown.getSuppressed().length);
        assertSame(readException, thrown.getSuppressed()[0]);
        assertEquals(1, config.writeCloseCount);
        assertEquals(1, config.readCloseCount);
    }

    @Test
    void constructorRollbackPrioritizesFatalError() {
        final OutOfMemoryError readFatal = new OutOfMemoryError("readString OOM");
        final MemoryConfig config = new MemoryConfig();
        config.readStringFailure = readFatal;
        final IllegalStateException writeException = new IllegalStateException("write close failed");
        final IllegalStateException readException = new IllegalStateException("read close failed");
        config.writeCloseFailure = writeException;
        config.readCloseFailure = readException;

        final OutOfMemoryError thrown =
                assertThrows(OutOfMemoryError.class, () -> new AcpPluginSettings(config, logger()));
        assertSame(readFatal, thrown);
        assertEquals(2, thrown.getSuppressed().length);
        assertSame(writeException, thrown.getSuppressed()[0]);
        assertSame(readException, thrown.getSuppressed()[1]);
        assertEquals(1, config.writeCloseCount);
        assertEquals(1, config.readCloseCount);
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {}

            @Override
            public void warn(final String message) {}

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
    }

    private static final class MemoryConfig implements PluginConfigRegistry {
        private final Map<String, String> values = new LinkedHashMap<>();
        private final List<String> written = new ArrayList<>();
        private String failKey;
        Throwable writeCloseFailure;
        Throwable readCloseFailure;
        Throwable readStringFailure;
        int writeCloseCount;
        int readCloseCount;

        @Override
        public Registration readScope(final String relativePath) {
            return () -> {
                readCloseCount++;
                if (readCloseFailure != null) {
                    if (readCloseFailure instanceof Error error) throw error;
                    if (readCloseFailure instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException(readCloseFailure);
                }
            };
        }

        @Override
        public Registration writeScope(final String relativePath) {
            return () -> {
                writeCloseCount++;
                if (writeCloseFailure != null) {
                    if (writeCloseFailure instanceof Error error) throw error;
                    if (writeCloseFailure instanceof RuntimeException runtime) throw runtime;
                    throw new RuntimeException(writeCloseFailure);
                }
            };
        }

        @Override
        public Optional<String> readString(final String relativePath, final String key) {
            if (readStringFailure != null) {
                if (readStringFailure instanceof Error error) throw error;
                if (readStringFailure instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(readStringFailure);
            }
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void writeString(final String relativePath, final String key, final String value)
                throws PluginConfigException {
            if (key.equals(failKey) && !written.contains(key)) {
                written.add(key);
                throw new PluginConfigException("write rejected for " + key);
            }
            values.put(key, value);
        }

        @Override
        public CompletionStage<Void> registerSchema(final ConfigSchema schema, final List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(
                final ConfigKey<T> key, final T value, final long expectedRevision) {
            throw new UnsupportedOperationException("not used");
        }
    }
}
