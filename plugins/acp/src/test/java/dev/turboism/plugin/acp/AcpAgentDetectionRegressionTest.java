package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

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
import java.util.concurrent.atomic.AtomicBoolean;
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

        final long start = System.nanoTime();
        final boolean result = AgentLocator.probe(sleepingScript, Duration.ofMillis(100));
        final long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        assertFalse(result, "timed-out probe must return false");
        assertTrue(elapsed < 5000, "probe should abort within bounded timeout");
    }

    @Test
    void probeRejectsCrashingOrNonZeroExitPrograms() throws Exception {
        final Path exitOneScript = temporaryDirectory.resolve("exit-one.sh");
        Files.writeString(exitOneScript, "#!/bin/sh\nexit 1\n");
        makeExecutable(exitOneScript);

        assertFalse(
                AgentLocator.probe(exitOneScript, Duration.ofSeconds(2)),
                "probe must reject programs that exit non-zero on --version");
    }

    @Test
    void probeSucceedsForCleanExitZeroVersion() throws Exception {
        final Path validScript = temporaryDirectory.resolve("valid-agent.sh");
        Files.writeString(validScript, "#!/bin/sh\necho \"agent 1.0.0\"\nexit 0\n");
        makeExecutable(validScript);

        assertTrue(
                AgentLocator.probe(validScript, Duration.ofSeconds(2)),
                "probe must accept programs that cleanly exit 0 on --version");
    }

    @Test
    void settingsAgentIdGetterIsPureInMemoryAndDoesNotBlockOrProbe() {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings settings = new AcpPluginSettings(config, new CapturingLogger())) {
            assertFalse(settings.hasPersistedAgentId());
            // Before detection, getter returns catalog fallback in memory
            assertEquals("claude", settings.agentId());

            // Applying inferred agent updates memory
            assertTrue(settings.applyInferredAgentId("opencode"));
            assertEquals("opencode", settings.agentId());

            // Persisting an explicit choice overrides inferred
            assertTrue(settings.writeUserSettings("devin", null, ""));
            assertTrue(settings.hasPersistedAgentId());
            assertEquals("devin", settings.agentId());

            // Inferred cannot override persisted
            assertFalse(settings.applyInferredAgentId("opencode"));
            assertEquals("devin", settings.agentId());
        }
    }

    @Test
    void firstConnectRunsDetectionAndInfersAgentBeforeConnecting() throws Exception {
        final TestFixture fixture = new TestFixture();
        final AtomicReference<AgentLaunchSpec> launched = new AtomicReference<>();
        try (AcpChatController controller = fixture.controller((spec, listener) -> {
            launched.set(spec);
            throw new IOException("test launch stop");
        })) {
            controller.connect();
            fixture.view.awaitConnecting(5, TimeUnit.SECONDS);

            // Wait for failure indicating connect reached startClient
            fixture.view.awaitFailure(5, TimeUnit.SECONDS);

            // Detection must have run and broadcasted results to view
            final Map<String, String> detected = fixture.view.awaitDetectedAgents(5, TimeUnit.SECONDS);
            assertNotNull(detected);

            // If an agent was detected on this host (opencode or devin), it was inferred and launched
            final Optional<AgentProfile> available = AgentCatalog.findFirstAvailable();
            if (available.isPresent()) {
                assertEquals(available.get().id(), fixture.settings.agentId());
                assertNotNull(launched.get());
                assertTrue(launched.get().executable().contains(available.get().id()));
            }
        }
    }

    @Test
    void unpersistedUserSelectionInUiIsNotOverwrittenByLateDetection() throws Exception {
        assumeFalse(
                java.awt.GraphicsEnvironment.isHeadless(),
                "AcpChatWindow frame construction requires non-headless environment");
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final AcpChatWindow window = new AcpChatWindow(
                    new dev.turboism.sdk.i18n.PluginLocalization() {
                        @Override
                        public String text(String key) {
                            return key;
                        }

                        @Override
                        public String format(String key, Object... args) {
                            return key;
                        }

                        @Override
                        public boolean contains(String key) {
                            return true;
                        }

                        @Override
                        public java.util.Locale locale() {
                            return java.util.Locale.ROOT;
                        }
                    },
                    "claude",
                    null,
                    "");
            try {
                assertFalse(window.isUserSelectionDirty());

                // Programmatic showAgentSelected before user edit works
                window.showAgentSelected("gemini");
                assertEquals("gemini", window.agentId());

                // Simulate user dropdown interaction
                window.agentSelector().setSelectedIndex(1);
                final String userSelected = window.agentId();
                assertTrue(window.isUserSelectionDirty());

                // Late background detection returning "opencode" MUST NOT overwrite user's edit
                window.showAgentSelected("opencode");
                assertEquals(userSelected, window.agentId());
            } finally {
                window.dispose();
            }
        });
    }

    @Test
    void dirtyUiViewPreventsControllerFromOverwritingInferredSelection() throws Exception {
        final TestFixture fixture = new TestFixture();
        fixture.view.userSelectionDirty = true;
        try (AcpChatController controller = fixture.controller()) {
            controller.detectAgents();
            fixture.view.awaitDetectedAgents(5, TimeUnit.SECONDS);

            // Because view is dirty, controller must not apply inferred agent ID
            assertEquals(AgentCatalog.fallbackAgentId(), fixture.settings.agentId());
            assertFalse(fixture.view.agentSelectedCalled.get());
        }
    }

    @Test
    void persistedCustomSettingsArePreservedAndNotOverwrittenByAutoDetection() throws Exception {
        final MemoryConfig config = new MemoryConfig();
        try (AcpPluginSettings first = new AcpPluginSettings(config, new CapturingLogger())) {
            assertTrue(first.writeUserSettings("codex", "/custom/bin", "prompt"));
        }

        final TestFixture fixture = new TestFixture(config);
        try (AcpChatController controller = fixture.controller()) {
            controller.detectAgents();
            fixture.view.awaitDetectedAgents(5, TimeUnit.SECONDS);

            assertTrue(fixture.settings.hasPersistedAgentId());
            assertEquals("codex", fixture.settings.agentId());
            assertEquals("/custom/bin", fixture.settings.customCommand());
            assertEquals("prompt", fixture.settings.initialPrompt().orElseThrow());
        }
    }

    @Test
    void missingExecutableInConnectLogsClearWarningAndFailsWithKey() throws Exception {
        final MemoryConfig config = new MemoryConfig();
        config.values.put("agentId", "antigravity");
        final TestFixture fixture = new TestFixture(config);
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
        private final MemoryConfig config;
        private final CapturingLogger logger = new CapturingLogger();
        private final TestView view = new TestView();
        private final AcpPluginSettings settings;

        TestFixture() {
            this(new MemoryConfig());
        }

        TestFixture(final MemoryConfig config) {
            this.config = config;
            this.settings = new AcpPluginSettings(config, logger);
        }

        private AcpChatController controller() {
            return controller((spec, listener) -> {
                throw new IOException("mock clientStarter rejection");
            });
        }

        private AcpChatController controller(final AcpChatController.ClientStarter clientStarter) {
            return new AcpChatController(context(), settings, view, clientStarter, Duration.ofSeconds(5));
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
        private final CountDownLatch connectingLatch = new CountDownLatch(1);
        private final AtomicReference<Map<String, String>> detectedRef = new AtomicReference<>();
        private final AtomicReference<String> failureRef = new AtomicReference<>();
        private final AtomicReference<String> connectingRef = new AtomicReference<>();
        private final AtomicReference<String> selectedAgentRef = new AtomicReference<>();
        volatile boolean userSelectionDirty;
        final AtomicBoolean agentSelectedCalled = new AtomicBoolean();

        @Override
        public boolean isUserSelectionDirty() {
            return userSelectionDirty;
        }

        @Override
        public void showConnecting(String agentLabel) {
            connectingRef.set(agentLabel);
            connectingLatch.countDown();
        }

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
        public void showAgentSelected(String agentId) {
            agentSelectedCalled.set(true);
            selectedAgentRef.set(agentId);
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

        String awaitConnecting(long timeout, TimeUnit unit) throws InterruptedException {
            if (connectingLatch.await(timeout, unit)) {
                return connectingRef.get();
            }
            throw new AssertionError("timeout awaiting connecting");
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
