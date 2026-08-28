package dev.turboism.plugin.turboismwithfx;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TurboismWithFxControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void strictModeFailsClosedBeforeLaunchingFx() throws Exception {
        final Fixture fixture = new Fixture();
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("missing-fx-fixture", false, "");
            fixture.view.awaitFailure("status.mcp-only-unavailable");
        }

        assertEquals("missing-fx-fixture", fixture.config.value("fxExecutable"));
        assertEquals("false", fixture.config.value("allowFxNativeTools"));
        assertFalse(fixture.logger.hasErrors());
    }

    @Test
    void failedSettingsPersistencePreventsConnectAndReportsFailure() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.failWrites = true;
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("fx", true, "instructions");
            fixture.view.awaitFailure("status.settings-save-failed");
        }

        assertTrue(fixture.config.values.isEmpty());
        assertTrue(fixture.logger.warnings.stream()
            .anyMatch(message -> message.contains("could not be persisted")));
    }

    @Test
    void laterSettingsWriteFailureRollsBackEarlierValues() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxExecutable", "/old/fx");
        fixture.config.values.put("allowFxNativeTools", "false");
        fixture.config.values.put("initialPrompt", "old instructions");
        fixture.config.failKey = "initialPrompt";
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("/new/fx", true, "new instructions");
            fixture.view.awaitFailure("status.settings-save-failed");
        }

        assertEquals("/old/fx", fixture.config.value("fxExecutable"));
        assertEquals("false", fixture.config.value("allowFxNativeTools"));
        assertEquals("old instructions", fixture.config.value("initialPrompt"));
    }

    @Test
    void mutatingSettingsWriteFailureRestoresEveryAttemptedValue() throws Exception {
        for (String failedKey : List.of(
            "fxExecutable",
            "allowFxNativeTools",
            "initialPrompt"
        )) {
            final Fixture fixture = new Fixture();
            fixture.config.values.put("fxExecutable", "/old/fx");
            fixture.config.values.put("allowFxNativeTools", "false");
            fixture.config.values.put("initialPrompt", "old instructions");
            fixture.config.mutateThenFailKey = failedKey;
            try (TurboismWithFxController controller = fixture.controller()) {
                controller.connect("/new/fx", true, "new instructions");
                fixture.view.awaitFailure("status.settings-save-failed");
            }

            assertEquals("/old/fx", fixture.config.value("fxExecutable"));
            assertEquals("false", fixture.config.value("allowFxNativeTools"));
            assertEquals("old instructions", fixture.config.value("initialPrompt"));
        }
    }

    @Test
    void invalidInitialPromptDoesNotPartiallyPersistOtherSettings() throws Exception {
        final Fixture fixture = new Fixture();
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("/tmp/fx-custom", true, "invalid\0instructions");
            fixture.view.awaitFailure("status.settings-invalid");
        }

        assertTrue(fixture.config.values.isEmpty());
        assertTrue(fixture.logger.warnings.stream()
            .anyMatch(message -> message.contains("settings were invalid")));
    }

    @Test
    void oversizedPromptIsRejectedBeforeAcpDispatch() throws Exception {
        final Fixture fixture = new Fixture();
        try (TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", inactiveClient());
            set(controller, "session", new FxAcpSession("sess-1", List.of()));

            controller.sendPrompt("x".repeat(TurboismWithFxController.MAX_PROMPT_CHARS));
            fixture.view.awaitFailure("status.prompt-failed");

            assertTrue(fixture.logger.warnings.stream()
                .anyMatch(message -> message.contains("ACP text limit")));
            assertTrue(fixture.view.userMessages.isEmpty());
            assertFalse(booleanField(controller, "prompting"));
        }
    }

    @Test
    void activatingAnEphemeralSessionDoesNotPersistItsOpaqueId() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxSessionId", "saved-session");
        try (TurboismWithFxController controller = fixture.controller()) {
            final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
                "activateSession", FxAcpSession.class
            );
            method.setAccessible(true);
            method.invoke(controller, new FxAcpSession(
                "ephemeral-session",
                List.of(),
                FxAcpClient.FxAcpCapabilities.NONE
            ));

            assertEquals("saved-session", fixture.config.value("fxSessionId"));
            assertEquals("ephemeral-session", session(controller).sessionId());
        }
    }

    @Test
    void userInitialPromptIsPersistedAndAppendedAfterTheFixedBoundary() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("initialPrompt", "Prefer concise Cubism edits.");
        final CapturingTransport transport = new CapturingTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));

            controller.sendPrompt("rename the object");
            fixture.view.awaitPrompting();

            final Map<String, Object> request = transport.request();
            final Map<String, Object> params = object(request.get("params"));
            final Map<String, Object> prompt = object(list(params.get("prompt")).get(0));
            final String text = (String) prompt.get("text");
            assertTrue(text.startsWith(TurboismWithFxController.SYSTEM_BOUNDARY));
            assertTrue(text.indexOf("Prefer concise Cubism edits.")
                > text.indexOf(TurboismWithFxController.SYSTEM_BOUNDARY));
            assertTrue(text.endsWith("rename the object"));
        }
    }

    @Test
    void exactBoundaryPromptIsAcceptedAndCarriesTheSystemBoundary() throws Exception {
        final Fixture fixture = new Fixture();
        final CapturingTransport transport = new CapturingTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));
            final int prefixLength = (TurboismWithFxController.SYSTEM_BOUNDARY
                + "\n\nUser request:\n").length();
            final String userPrompt = "x".repeat(
                TurboismWithFxController.MAX_PROMPT_CHARS - prefixLength
            );

            controller.sendPrompt(userPrompt);
            fixture.view.awaitPrompting();

            final Map<String, Object> request = transport.request();
            assertEquals("session/prompt", request.get("method"));
            final Map<String, Object> params = object(request.get("params"));
            final Map<String, Object> prompt = object(list(params.get("prompt")).get(0));
            final String text = (String) prompt.get("text");
            assertEquals(TurboismWithFxController.MAX_PROMPT_CHARS, text.length());
            assertTrue(text.startsWith(TurboismWithFxController.SYSTEM_BOUNDARY));
            assertTrue(text.endsWith(userPrompt));
        }
    }

    @Test
    void uiUpdatesDoNotDependOnTheRuntimeUiBudgetLane() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.uiRejects = true;
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("fx", false, "");
            fixture.view.awaitFailure("status.mcp-only-unavailable");
        }

        assertEquals(0, fixture.uiCalls.get());
        assertFalse(fixture.logger.hasErrors());
    }

    @Test
    void excessUiUpdatesAreBoundedAndReportedOncePerDrain() throws Exception {
        final Fixture fixture = new Fixture();
        final java.util.concurrent.CountDownLatch edtBlocked = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseEdt = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS));

        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));
            for (int index = 0; index < 300; index++) {
                controller.agentText(source, "sess-1", "chunk-" + index);
            }
            awaitSerial(controller);
            releaseEdt.countDown();
            fixture.view.awaitAgentMessages();

            assertTrue(fixture.view.agentMessages.size() <= 256);
            assertEquals(1L, fixture.logger.warnings.stream()
                .filter(message -> message.contains("dropped excess UI updates"))
                .count());
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void excessStreamUpdatesCannotDropPromptStateTransitions() throws Exception {
        final Fixture fixture = new Fixture();
        final java.util.concurrent.CountDownLatch edtBlocked = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releaseEdt = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeLater(() -> {
            edtBlocked.countDown();
            try {
                releaseEdt.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(edtBlocked.await(2, java.util.concurrent.TimeUnit.SECONDS));

        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));
            for (int index = 0; index < 256; index++) {
                controller.agentText(source, "sess-1", "chunk-" + index);
            }
            awaitSerial(controller);
            invokeUi(controller, fixture.view::showPrompting);
            releaseEdt.countDown();
            fixture.view.awaitPrompting();

            assertTrue(fixture.view.prompting.get());
            assertTrue(fixture.view.agentMessages.size() < 256);
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void thinkingAndToolIdentityAreForwardedToTheView() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));

            controller.agentThought(source, "sess-1", "hidden plan");
            controller.toolCall(source, "sess-1", "call-1", "Rename object", "edit", "pending");
            awaitSerial(controller);
            fixture.view.awaitThinking();
            fixture.view.awaitToolCall();

            assertEquals(List.of("hidden plan"), fixture.view.thinkingMessages);
            assertEquals(
                List.of("call-1", "Rename object", "edit", "pending"),
                fixture.view.toolCalls.get(0)
            );
        }
    }

    @Test
    void configDispatchFailureRestoresTheLastConfirmedOptions() throws Exception {
        final Fixture fixture = new Fixture();
        final FxAcpConfigOption confirmed = new FxAcpConfigOption(
            "provider",
            "Provider",
            "gateway",
            List.of(
                new FxAcpConfigOption.Choice("gateway", "Gateway"),
                new FxAcpConfigOption.Choice("codex", "Codex")
            )
        );
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            source.close();
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("sess-1", List.of(confirmed)));

            controller.setConfigOption("provider", "codex");
            fixture.view.awaitConfigFailure();

            assertEquals(List.of("provider"), fixture.view.configUpdatingIds);
            assertEquals("provider", fixture.view.configFailureId.get());
            assertEquals(List.of(confirmed), fixture.view.configFailureOptions.get());
        }
    }

    @Test
    void closeReturnsPromptlyOnTheSwingEventThread() throws Exception {
        final Fixture fixture = new Fixture();
        final TurboismWithFxController controller = fixture.controller();
        final java.util.concurrent.atomic.AtomicLong elapsed =
            new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final long started = System.nanoTime();
            controller.close();
            elapsed.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - started
            ));
        });

        assertTrue(elapsed.get() < 1_000L, "controller close blocked the Swing EDT");
    }

    @Test
    void submissionsAfterCloseAreIgnoredWithoutExecutorRejection() {
        final Fixture fixture = new Fixture();
        final TurboismWithFxController controller = fixture.controller();
        controller.close();

        controller.connect("fx", true, "");
        controller.sendPrompt("work");
        controller.setConfigOption("provider", "gateway");
        controller.terminated(null, "closed");

        assertTrue(fixture.config.values.isEmpty());
        assertTrue(fixture.view.failures.isEmpty());
        assertFalse(fixture.logger.hasErrors());
    }

    @Test
    void staleSessionEventsAndPermissionsAreRejected() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("current-session", List.of()));

            controller.agentText(source, "old-session", "delayed text");
            controller.agentThought(source, "old-session", "delayed thought");
            controller.toolCall(
                source,
                "old-session",
                "call-1",
                "Rename object",
                "edit",
                "pending"
            );
            final FxAcpListener.PermissionDecision decision = controller.permission(
                source,
                "old-session",
                new FxAcpListener.PermissionRequest("Rename", "edit", "call-1", "{}")
            );
            awaitSerial(controller);

            assertTrue(fixture.view.agentMessages.isEmpty());
            assertTrue(fixture.view.thinkingMessages.isEmpty());
            assertTrue(fixture.view.toolCalls.isEmpty());
            assertEquals(FxAcpListener.PermissionDecision.CANCELLED, decision);
        }
    }

    @Test
    void staleTerminationCannotDetachTheCurrentClient() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient stale = inactiveClient();
             FxAcpClient current = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", current);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));

            controller.terminated(stale, "stale process ended");
            awaitSerial(controller);

            assertEquals(current, atomicClient(controller));
            assertTrue(fixture.view.failures.isEmpty());
            assertTrue(session(controller) != null);
        }
    }

    private FxAcpClient inactiveClient() throws java.io.IOException {
        return new FxAcpClient(new CapturingTransport(), new FxAcpListener() { });
    }

    private static void set(final Object target, final String fieldName, final Object value)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        if (field.getType() == java.util.concurrent.atomic.AtomicReference.class) {
            @SuppressWarnings("unchecked")
            final java.util.concurrent.atomic.AtomicReference<Object> reference =
                (java.util.concurrent.atomic.AtomicReference<Object>) field.get(target);
            reference.set(value);
        } else {
            field.set(target, value);
        }
    }

    private static void awaitSerial(final TurboismWithFxController controller) throws Exception {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("serial");
        field.setAccessible(true);
        final java.util.concurrent.ExecutorService serial =
            (java.util.concurrent.ExecutorService) field.get(controller);
        serial.submit(() -> { }).get(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void invokeUi(
        final TurboismWithFxController controller,
        final Runnable work
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "ui", Runnable.class
        );
        method.setAccessible(true);
        method.invoke(controller, work);
    }

    private static boolean booleanField(final Object target, final String fieldName)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static FxAcpClient atomicClient(final TurboismWithFxController controller)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("client");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.concurrent.atomic.AtomicReference<FxAcpClient> reference =
            (java.util.concurrent.atomic.AtomicReference<FxAcpClient>) field.get(controller);
        return reference.get();
    }

    private static FxAcpSession session(final TurboismWithFxController controller)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("session");
        field.setAccessible(true);
        return (FxAcpSession) field.get(controller);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return (List<Object>) value;
    }

    private final class Fixture {
        private final MemoryConfig config = new MemoryConfig();
        private final CapturingLogger logger = new CapturingLogger();
        private final RecordingView view = new RecordingView();
        private final java.util.concurrent.atomic.AtomicInteger uiCalls =
            new java.util.concurrent.atomic.AtomicInteger();
        private boolean uiRejects;

        private TurboismWithFxController controller() {
            return new TurboismWithFxController(
                context(), new FxPluginSettings(config, logger), view
            );
        }

        private PluginContext context() {
            final PluginPaths paths = new PluginPaths() {
                @Override public Path dataDir() {
                    return temporaryDirectory.resolve("data/dev.turboism.plugin.turboism-with-fx");
                }
                @Override public Path logsDir() {
                    return temporaryDirectory.resolve("logs/dev.turboism.plugin.turboism-with-fx");
                }
                @Override public Path stateDir() {
                    return temporaryDirectory.resolve("state/dev.turboism.plugin.turboism-with-fx");
                }
                @Override public Path cacheDir() {
                    return temporaryDirectory.resolve("cache/dev.turboism.plugin.turboism-with-fx");
                }
            };
            final UiScheduler ui = new UiScheduler() {
                @Override public Registration runOnUiThread(final Runnable work) {
                    uiCalls.incrementAndGet();
                    if (uiRejects) throw new IllegalStateException("test UI budget rejection");
                    work.run();
                    return () -> { };
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    uiCalls.incrementAndGet();
                    if (uiRejects) throw new IllegalStateException("test UI budget rejection");
                    work.run();
                    return () -> { };
                }
            };
            return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> logger;
                    case "paths" -> paths;
                    case "mcpConnections" -> McpConnectionService.unavailable();
                    case "uiScheduler" -> ui;
                    case "toString" -> "TurboismWithFxControllerTestContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    default -> throw new UnsupportedOperationException(
                        "unused PluginContext method: " + method.getName()
                    );
                }
            );
        }

        private void await(final CheckedCondition condition) throws Exception {
            for (int attempt = 0; attempt < 2000; attempt++) {
                if (condition.test()) return;
                Thread.sleep(1L);
            }
            assertTrue(condition.test(), "controller did not reach the expected state");
        }
    }

    @FunctionalInterface
    private interface CheckedCondition {
        boolean test() throws Exception;
    }

    private static final class RecordingView implements TurboismWithFxController.View {
        private final List<String> failures = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> userMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> agentMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> thinkingMessages = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<List<String>> toolCalls = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<String> configUpdatingIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicReference<String> configFailureId =
            new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicReference<List<FxAcpConfigOption>>
            configFailureOptions = new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean prompting =
            new java.util.concurrent.atomic.AtomicBoolean();

        @Override public void showConnecting(final boolean compatibilityMode) { }
        @Override public void showConnected(
            final List<FxAcpConfigOption> options,
            final boolean durableSessionsAvailable
        ) { }
        @Override public void showConfigOptions(final List<FxAcpConfigOption> options) { }
        @Override public void showConfigUpdating(final String optionId) {
            configUpdatingIds.add(optionId);
        }
        @Override public void showConfigFailure(
            final String optionId,
            final List<FxAcpConfigOption> confirmedOptions
        ) {
            configFailureId.set(optionId);
            configFailureOptions.set(List.copyOf(confirmedOptions));
        }
        @Override public void showSessions(
            final List<FxAcpSessionSummary> sessions,
            final String activeSessionId,
            final boolean durableSessionsAvailable
        ) { }
        @Override public void clearTranscript() { }
        @Override public void showPrompting() { prompting.set(true); }
        @Override public void showPromptComplete(final String stopReason) { }
        @Override public void showFailure(final String localizationKey) { failures.add(localizationKey); }
        @Override public void showSessionFailure(final String localizationKey) { failures.add(localizationKey); }
        @Override public void showSettingsSaved() { }
        @Override public void appendUser(final String text) { userMessages.add(text); }
        @Override public void appendAgent(final String text) { agentMessages.add(text); }
        @Override public void appendThinking(final String text) { thinkingMessages.add(text); }
        @Override public void appendTool(
            final String toolCallId,
            final String title,
            final String kind,
            final String status
        ) { toolCalls.add(List.of(toolCallId, title, kind, status)); }
        @Override public void updateTool(final String toolCallId, final String status, final String content) { }
        @Override public FxAcpListener.PermissionDecision requestPermission(
            final FxAcpListener.PermissionRequest request
        ) { return FxAcpListener.PermissionDecision.CANCELLED; }

        private void awaitFailure(final String expected) throws InterruptedException {
            for (int attempt = 0; !failures.contains(expected) && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(failures.contains(expected), "missing controller failure " + expected);
        }

        private void awaitPrompting() throws InterruptedException {
            for (int attempt = 0; !prompting.get() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(prompting.get(), "controller did not enter prompting state");
        }

        private void awaitAgentMessages() throws InterruptedException {
            for (int attempt = 0; agentMessages.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(agentMessages.isEmpty(), "controller did not drain ACP UI updates");
        }

        private void awaitThinking() throws InterruptedException {
            for (int attempt = 0; thinkingMessages.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(thinkingMessages.isEmpty(), "controller did not forward ACP thinking");
        }

        private void awaitToolCall() throws InterruptedException {
            for (int attempt = 0; toolCalls.isEmpty() && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertFalse(toolCalls.isEmpty(), "controller did not forward ACP tool identity");
        }

        private void awaitConfigFailure() throws InterruptedException {
            for (int attempt = 0; configFailureId.get() == null && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(configFailureId.get() != null, "controller did not restore config state");
        }
    }

    private static final class CapturingLogger implements PluginLogger {
        private final List<String> warnings = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { }
        @Override public void warn(final String message) { warnings.add(message); }
        @Override public void error(final String message) { errors.add(new AssertionError(message)); }
        @Override public void error(final String message, final Throwable throwable) { errors.add(throwable); }

        private boolean hasErrors() { return !errors.isEmpty(); }
    }

    private static final class MemoryConfig implements PluginConfigRegistry {
        private final Map<String, String> values = new LinkedHashMap<>();
        private boolean failWrites;
        private String failKey;
        private String mutateThenFailKey;

        private String value(final String key) { return values.get(key); }

        @Override public Registration readScope(final String relativePath) { return () -> { }; }
        @Override public Registration writeScope(final String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(final String relativePath, final String key) {
            return Optional.ofNullable(values.get(key));
        }
        @Override public void writeString(
            final String relativePath,
            final String key,
            final String value
        ) throws dev.turboism.sdk.config.PluginConfigException {
            if (failWrites || key.equals(failKey)) {
                failKey = null;
                throw new dev.turboism.sdk.config.PluginConfigException("test write failure");
            }
            values.put(key, value);
            if (key.equals(mutateThenFailKey)) {
                mutateThenFailKey = null;
                throw new dev.turboism.sdk.config.PluginConfigException(
                    "test post-mutation write failure"
                );
            }
        }
        @Override public CompletionStage<Void> registerSchema(
            final ConfigSchema schema,
            final List<ConfigMigration> migrations
        ) { return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            throw new UnsupportedOperationException("not used");
        }
        @Override public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key,
            final T value,
            final long expectedRevision
        ) { throw new UnsupportedOperationException("not used"); }
    }

    private static final class CapturingTransport implements FxAcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.ByteArrayOutputStream stdin = new java.io.ByteArrayOutputStream();
        private final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;

        private CapturingTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        private Map<String, Object> request() throws Exception {
            for (int attempt = 0; stdin.size() == 0 && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            final String line = stdin.toString(java.nio.charset.StandardCharsets.UTF_8).strip();
            assertFalse(line.isEmpty(), "ACP request was not written");
            return object(dev.turboism.protocol.json.StrictJson.parse(
                line.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
        }

        @Override public java.io.InputStream stdout() { return clientStdout; }
        @Override public java.io.InputStream stderr() { return stderr; }
        @Override public java.io.OutputStream stdin() { return stdin; }
        @Override public boolean isAlive() { return true; }

        @Override
        public void terminate(final Duration grace) {
            try { serverStdout.close(); } catch (java.io.IOException ignored) { }
            try { serverStderr.close(); } catch (java.io.IOException ignored) { }
        }

        @Override public void close() { terminate(Duration.ZERO); }
    }
}
