package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression and boundary tests for ACP agent auto-detection, platform awareness,
 * command probing, process cleanup, and error reporting.
 */
final class AcpAgentDetectionRegressionTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void ordinaryCliToolsAreNotCatalogAcpCandidates() {
        // Raw native CLIs (claude, codex, pi) do not implement ACP v1 over stdio.
        // Profiles must only search for true ACP adapters or ACP-native commands.
        for (AgentProfile profile : AgentCatalog.profiles()) {
            if ("claude".equals(profile.id())) {
                assertEquals(List.of("claude-agent-acp"), profile.executableCandidates());
            } else if ("codex".equals(profile.id())) {
                assertEquals(List.of("codex-acp"), profile.executableCandidates());
            } else if ("pi".equals(profile.id())) {
                assertEquals(List.of("pi-acp"), profile.executableCandidates());
            } else if ("opencode".equals(profile.id())) {
                assertEquals(List.of("acp"), profile.arguments());
            } else if ("devin".equals(profile.id())) {
                assertEquals(List.of("acp"), profile.arguments());
            }
        }
    }

    @Test
    void windowsCommandShimWrapsBatchFilesOnlyOnWindows() {
        final List<String> cmd = List.of("C:\\Tools\\agent.cmd", "--version");
        final List<String> bat = List.of("C:\\Tools\\agent.bat", "--acp");
        final List<String> exe = List.of("C:\\Tools\\agent.exe", "--version");

        final List<String> shimmedCmd = AcpProcessTransport.windowsCommandShim(cmd, "Windows 11");
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "\"\"C:\\Tools\\agent.cmd\" \"--version\"\""), shimmedCmd);

        final List<String> shimmedBat = AcpProcessTransport.windowsCommandShim(bat, "Windows 10");
        assertEquals(List.of("cmd.exe", "/d", "/s", "/c", "\"\"C:\\Tools\\agent.bat\" \"--acp\"\""), shimmedBat);

        // .exe files are not wrapped
        final List<String> shimmedExe = AcpProcessTransport.windowsCommandShim(exe, "Windows 10");
        assertEquals(exe, shimmedExe);

        // On non-Windows platforms, batch files are not wrapped with cmd.exe
        final List<String> linuxCmd = AcpProcessTransport.windowsCommandShim(cmd, "Linux");
        assertEquals(cmd, linuxCmd);
    }

    @Test
    void windowsCommandShimRejectsDangerousCharacters() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AcpProcessTransport.windowsCommandShim(List.of("bad.cmd", "foo&bar"), "Windows 10"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AcpProcessTransport.windowsCommandShim(List.of("bad.cmd", "foo|bar"), "Windows 10"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AcpProcessTransport.windowsCommandShim(List.of("bad.bat", "foo%PATH%"), "Windows 10"));
        assertThrows(
                IllegalArgumentException.class,
                () -> AcpProcessTransport.windowsCommandShim(List.of("bad.bat", "foo!VAR!"), "Windows 10"));
    }

    @Test
    void probeReturnsFalseForNonexistentOrUnusableFiles() {
        final Path nonexistent = temporaryDirectory.resolve("does-not-exist");
        assertFalse(AgentLocator.probe(nonexistent, Duration.ofSeconds(1)));

        // Non-regular file (directory)
        assertFalse(AgentLocator.probe(temporaryDirectory, Duration.ofSeconds(1)));
    }

    @Test
    void probeReturnsFalseWhenProcessTimesOutAndCleansUpProcess() throws Exception {
        final Path sleepingScript = temporaryDirectory.resolve("slow-agent.sh");
        Files.writeString(sleepingScript, "#!/bin/sh\nsleep 30\n");
        makeExecutable(sleepingScript);

        // Probe with 100ms timeout should cleanly fail without hanging
        final long start = System.nanoTime();
        final boolean result = AgentLocator.probe(sleepingScript, Duration.ofMillis(100));
        final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result, "timed-out probe must not be considered available");
        assertTrue(elapsed < 5000, "probe should abort within bounded timeout");
    }

    @Test
    void probeSucceedsForFastExecutableEvenWithNonZeroExitCode() throws Exception {
        final Path exitOneScript = temporaryDirectory.resolve("exit-one.sh");
        Files.writeString(exitOneScript, "#!/bin/sh\nexit 1\n");
        makeExecutable(exitOneScript);

        assertTrue(
                AgentLocator.probe(exitOneScript, Duration.ofSeconds(2)),
                "probe verifies spawnability on host even if --version returns non-zero");
    }

    @Test
    void unconfiguredSettingsAutoSelectsFirstAvailableAgent() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings settings = new AcpPluginSettings(config, new CapturingLogger())) {
            assertFalse(settings.hasPersistedAgentId());
            assertEquals(AcpPluginSettings.defaultAgentId(), settings.agentId());
            final Optional<AgentProfile> available = AgentCatalog.findFirstAvailable();
            if (available.isPresent()) {
                assertEquals(available.get().id(), settings.agentId());
            } else {
                assertEquals(AgentCatalog.fallbackAgentId(), settings.agentId());
            }
        }
    }

    @Test
    void persistedCustomSettingsArePreservedAndNotOverwrittenByAutoDetection() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings first = new AcpPluginSettings(config, new CapturingLogger())) {
            assertTrue(first.writeUserSettings("codex", "/custom/bin", "prompt"));
        }

        try (AcpPluginSettings second = new AcpPluginSettings(config, new CapturingLogger())) {
            assertTrue(second.hasPersistedAgentId());
            assertEquals("codex", second.agentId());
            assertEquals("/custom/bin", second.customCommand());
            assertEquals("prompt", second.initialPrompt().orElseThrow());
        }
    }

    @Test
    void detectAgentsEmitsProbeResultsToView() throws Exception {
        final TestFixture fixture = new TestFixture();
        try (AcpChatController controller = fixture.controller()) {
            controller.detectAgents();
            final Map<String, String> detected = fixture.view.awaitDetectedAgents(5, TimeUnit.SECONDS);
            assertNotNull(detected);
            for (AgentProfile profile : AgentCatalog.profiles()) {
                assertTrue(detected.containsKey(profile.id()));
            }
        }
    }

    @Test
    void missingExecutableInConnectLogsClearWarningAndFailsWithKey() throws Exception {
        final TestFixture fixture = new TestFixture();
        // Configure an agent whose executable is absent
        fixture.config.values.put("agentId", "antigravity");
        try (AcpChatController controller = fixture.controller()) {
            controller.connect();
            final String failure = fixture.view.awaitFailure(5, TimeUnit.SECONDS);
            assertEquals("status.agent-not-found-generic", failure);

            assertTrue(
                    fixture.logger.warnings.stream()
                            .anyMatch(msg -> msg.contains("ACP connection") && msg.contains("antigravity")),
                    "Controller must log warning with agent ID on launch resolution failure");
        }
    }

    private void makeExecutable(final Path file) {
        try {
            final Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(file);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            permissions.add(PosixFilePermission.GROUP_EXECUTE);
            permissions.add(PosixFilePermission.OTHERS_EXECUTE);
            Files.setPosixFilePermissions(file, permissions);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX platforms ignore posix file permissions
        } catch (IOException failure) {
            throw new AssertionError("failed to set executable permission", failure);
        }
    }

    private final class TestFixture {
        private final MemoryConfig config = new MemoryConfig();
        private final CapturingLogger logger = new CapturingLogger();
        private final TestView view = new TestView();

        private AcpChatController controller() {
            return new AcpChatController(
                    context(),
                    new AcpPluginSettings(config, logger),
                    view,
                    (spec, listener) -> {
                        throw new IOException("mock clientStarter rejection");
                    },
                    Duration.ofSeconds(5));
        }

        private PluginContext context() {
            final PluginPaths paths = new PluginPaths() {
                @Override
                public Path dataDir() {
                    return temporaryDirectory.resolve("data");
                }

                @Override
                public Path stateDir() {
                    return temporaryDirectory.resolve("state");
                }

                @Override
                public Path cacheDir() {
                    return temporaryDirectory.resolve("cache");
                }
            };
            final UiScheduler ui = new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    work.run();
                    return () -> {};
                }
            };
            return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                    PluginContext.class.getClassLoader(),
                    new Class<?>[] {PluginContext.class},
                    (proxy, method, arguments) -> switch (method.getName()) {
                        case "logger" -> logger;
                        case "paths" -> paths;
                        case "services" ->
                            dev.turboism.sdk.plugin.PluginServices.builder()
                                    .install(McpConnectionService.class, new McpConnectionService() {
                                        @Override
                                        public Optional<dev.turboism.sdk.mcp.McpHttpConnection> current() {
                                            return Optional.empty();
                                        }

                                        @Override
                                        public Registration publish(dev.turboism.sdk.mcp.McpHttpConnection connection) {
                                            throw new UnsupportedOperationException();
                                        }

                                        @Override
                                        public Registration subscribe(
                                                java.util.function.Consumer<
                                                                Optional<dev.turboism.sdk.mcp.McpHttpConnection>>
                                                        listener) {
                                            return () -> {};
                                        }
                                    })
                                    .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                    .build();
                        case "uiScheduler" -> ui;
                        case "toString" -> "AcpAgentDetectionTestContext";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        default ->
                            throw new UnsupportedOperationException("unused PluginContext method: " + method.getName());
                    });
        }
    }

    private static final class TestView implements AcpChatController.View {
        private final CountDownLatch detectedLatch = new CountDownLatch(1);
        private final CountDownLatch failureLatch = new CountDownLatch(1);
        private final AtomicReference<Map<String, String>> detectedRef = new AtomicReference<>();
        private final AtomicReference<String> failureRef = new AtomicReference<>();

        @Override
        public void showConnecting(String agentLabel) {}

        @Override
        public void showConnected(
                AcpClient.AcpAgentInfo agentInfo,
                List<AcpConfigOption> options,
                boolean durableSessionsAvailable,
                AcpClient.McpAttachment mcpAttachment) {}

        @Override
        public void showAuthRequired(List<AcpAuthMethod> methods) {}

        @Override
        public void showConfigOptions(List<AcpConfigOption> options) {}

        @Override
        public void showSessions(
                List<AcpSessionSummary> sessions, String activeSessionId, boolean durableSessionsAvailable) {}

        @Override
        public void showDetectedAgents(Map<String, String> detectedPaths) {
            detectedRef.set(detectedPaths);
            detectedLatch.countDown();
        }

        @Override
        public void clearTranscript() {}

        @Override
        public void showPrompting() {}

        @Override
        public void showPromptComplete(String stopReason) {}

        @Override
        public void showFailure(String localizationKey) {
            failureRef.set(localizationKey);
            failureLatch.countDown();
        }

        @Override
        public void showSessionFailure(String localizationKey) {}

        @Override
        public void showSettingsSaved() {}

        @Override
        public void appendUser(String text) {}

        @Override
        public void appendAgent(String text) {}

        @Override
        public void appendTool(String toolCallId, String title, String kind, String status) {}

        @Override
        public void updateTool(String toolCallId, String status, String content) {}

        @Override
        public AcpListener.PermissionDecision requestPermission(AcpListener.PermissionRequest request) {
            return AcpListener.PermissionDecision.CANCELLED;
        }

        Map<String, String> awaitDetectedAgents(long timeout, TimeUnit unit) throws InterruptedException {
            if (detectedLatch.await(timeout, unit)) {
                return detectedRef.get();
            }
            throw new AssertionError("timeout awaiting detected agents");
        }

        String awaitFailure(long timeout, TimeUnit unit) throws InterruptedException {
            if (failureLatch.await(timeout, unit)) {
                return failureRef.get();
            }
            throw new AssertionError("timeout awaiting failure");
        }
    }

    private static final class CapturingLogger implements PluginLogger {
        final List<String> warnings = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void debug(String message) {}

        @Override
        public void info(String message) {}

        @Override
        public void warn(String message) {
            warnings.add(message);
        }

        @Override
        public void error(String message) {
            errors.add(message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            errors.add(message + ": " + throwable.getMessage());
        }
    }

    private static final class MemoryConfig implements PluginConfigRegistry {
        final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Registration readScope(String relativePath) {
            return () -> {};
        }

        @Override
        public Registration writeScope(String relativePath) {
            return () -> {};
        }

        @Override
        public Optional<String> readString(String relativePath, String key) {
            return Optional.ofNullable(values.get(key));
        }

        @Override
        public void writeString(String relativePath, String key, String value) {
            if (value == null) {
                values.remove(key);
            } else {
                values.put(key, value);
            }
        }

        @Override
        public CompletionStage<Void> registerSchema(ConfigSchema schema, List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) {
            throw new UnsupportedOperationException();
        }
    }
}
