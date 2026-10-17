package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.AcpChatControllerTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class AcpChatControllerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void customCommandPersistsBeforeLaunchFails() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpChatController controller = fixture.controller()) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitFailure("status.executable-start-failed");
        }

        assertEquals("custom", fixture.config.value("agentId"));
        assertEquals(temporaryExecutable().toString(), fixture.config.value("customCommand"));
        assertTrue(fixture.logger.hasErrors());
    }

    @Test
    void failedSettingsPersistencePreventsConnectAndReportsFailure() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.failWrites = true;
        try (AcpChatController controller = fixture.controller()) {
            controller.connect("custom", "agent-exe", "instructions");
            fixture.view.awaitFailure("status.settings-save-failed");
        }

        assertTrue(fixture.config.values.isEmpty());
        assertTrue(fixture.logger.warnings.stream().anyMatch(message -> message.contains("could not be persisted")));
    }

    @ParameterizedTest(name = "settings write failure on {0} ({1}) restores previous values")
    @CsvSource({
        "initialPrompt, fail_only",
        "agentId, mutate_then_fail",
        "customCommand, mutate_then_fail",
        "initialPrompt, mutate_then_fail",
        "acpSessionId, mutate_then_fail"
    })
    void settingsWriteFailureRestoresPreviousValues(String failedKey, String mode) throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("agentId", "codex");
        fixture.config.values.put("customCommand", "/old/agent");
        fixture.config.values.put("initialPrompt", "old instructions");
        if ("fail_only".equals(mode)) {
            fixture.config.failKey = failedKey;
        } else {
            fixture.config.mutateThenFailKey = failedKey;
        }
        try (AcpChatController controller = fixture.controller()) {
            controller.connect("custom", "/new/agent", "new instructions");
            fixture.view.awaitFailure("status.settings-save-failed");
            awaitConfigValue(fixture, "agentId", "codex");
            awaitConfigValue(fixture, "customCommand", "/old/agent");
            awaitConfigValue(fixture, "initialPrompt", "old instructions");
        }

        assertEquals("codex", fixture.config.value("agentId"));
        assertEquals("/old/agent", fixture.config.value("customCommand"));
        assertEquals("old instructions", fixture.config.value("initialPrompt"));
    }

    @Test
    void invalidInitialPromptDoesNotPartiallyPersistOtherSettings() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpChatController controller = fixture.controller()) {
            controller.connect("custom", "/tmp/agent", "invalid\0instructions");
            fixture.view.awaitFailure("status.settings-invalid");
        }

        assertTrue(fixture.config.values.isEmpty());
        assertTrue(fixture.logger.warnings.stream().anyMatch(message -> message.contains("settings were invalid")));
    }

    @Test
    void oversizedPromptIsRejectedBeforeAcpDispatch() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpChatController controller = fixture.controller()) {
            set(controller, "client", inactiveClient());
            set(controller, "session", configuredSession("sess-1"));

            controller.sendPrompt("x".repeat(AcpChatController.MAX_PROMPT_CHARS));
            fixture.view.awaitFailure("status.prompt-failed");

            assertTrue(fixture.logger.warnings.stream().anyMatch(message -> message.contains("ACP text limit")));
            assertTrue(fixture.view.userMessages.isEmpty());
            assertFalse(booleanField(controller, "prompting"));
        }
    }

    @Test
    void changingAgentClearsThePreviousDurableSession() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("acpSessionId", "agent-a-session");
        fixture.config.values.put("agentId", "claude");
        try (AcpChatController controller = fixture.controller()) {
            controller.saveSettings("codex", null, "");
            awaitSerial(controller);

            assertEquals("", fixture.config.value("acpSessionId"));
            assertEquals("codex", fixture.config.value("agentId"));
        }
    }

    @Test
    void activatingAnEphemeralSessionDoesNotPersistItsOpaqueId() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("acpSessionId", "saved-session");
        try (AcpChatController controller = fixture.controller()) {
            final java.lang.reflect.Method method =
                    controller.getClass().getDeclaredMethod("activateSession", AcpSession.class);
            method.setAccessible(true);
            method.invoke(controller, new AcpSession("ephemeral-session", List.of(), AcpClient.AcpCapabilities.NONE));

            assertEquals("", fixture.config.value("acpSessionId"));
            assertEquals("ephemeral-session", session(controller).sessionId());
        }
    }

    @Test
    void userInitialPromptIsPersistedAndAppendedAfterTheFixedBoundary() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("initialPrompt", "Prefer concise Cubism edits.");
        final CapturingTransport transport = new CapturingTransport();
        try (AcpClient client = new AcpClient(transport, new AcpListener() {});
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", configuredSession("sess-1"));

            controller.sendPrompt("rename the object");
            fixture.view.awaitPrompting();

            final Map<String, Object> request = transport.request();
            final Map<String, Object> params = object(request.get("params"));
            final Map<String, Object> prompt = object(list(params.get("prompt")).get(0));
            final String text = (String) prompt.get("text");
            assertTrue(text.startsWith(AcpChatController.SYSTEM_BOUNDARY));
            assertTrue(text.indexOf("Prefer concise Cubism edits.") > text.indexOf(AcpChatController.SYSTEM_BOUNDARY));
            assertTrue(text.endsWith("rename the object"));
        }
    }

    @Test
    void exactBoundaryPromptIsAcceptedAndCarriesTheSystemBoundary() throws Exception {
        final Fixture fixture = new Fixture();
        final CapturingTransport transport = new CapturingTransport();
        try (AcpClient client = new AcpClient(transport, new AcpListener() {});
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", configuredSession("sess-1"));
            final int prefixLength = (AcpChatController.SYSTEM_BOUNDARY + "\n\nUser request:\n").length();
            final String userPrompt = "x".repeat(AcpChatController.MAX_PROMPT_CHARS - prefixLength);

            controller.sendPrompt(userPrompt);
            fixture.view.awaitPrompting();

            final Map<String, Object> request = transport.request();
            assertEquals("session/prompt", request.get("method"));
            final Map<String, Object> params = object(request.get("params"));
            final Map<String, Object> prompt = object(list(params.get("prompt")).get(0));
            final String text = (String) prompt.get("text");
            assertEquals(AcpChatController.MAX_PROMPT_CHARS, text.length());
            assertTrue(text.startsWith(AcpChatController.SYSTEM_BOUNDARY));
            assertTrue(text.endsWith(userPrompt));
        }
    }

    @Test
    void uiUpdatesDoNotDependOnTheRuntimeUiBudgetLane() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.uiRejects = true;
        try (AcpChatController controller = fixture.controller()) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitFailure("status.executable-start-failed");
        }

        assertEquals(0, fixture.uiCalls.get());
        assertTrue(fixture.logger.hasErrors());
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

        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("sess-1", List.of()));
            for (int index = 0; index < 300; index++) {
                controller.agentText(source, "sess-1", "chunk-" + index);
            }
            awaitSerial(controller);
            releaseEdt.countDown();
            fixture.view.awaitAgentMessages();

            assertTrue(fixture.view.agentMessages.size() <= 256);
            assertEquals(
                    1L,
                    fixture.logger.warnings.stream()
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

        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("sess-1", List.of()));
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
    void pendingLoadReplaysTypedEventsAfterSelectionResetInOriginalOrder() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("old-session", List.of()));
            final Object load = beginPendingLoad(controller, source, "restored-session");

            controller.agentText(source, "restored-session", "restored text");
            controller.agentThought(source, "restored-session", "restored thought");
            controller.toolCall(source, "restored-session", "call-1", "Rename", "edit", "pending");
            controller.toolCallUpdate(source, "restored-session", "call-1", "complete", "done");

            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("restored-session", List.of()),
                    () -> fixture.view.record("reset")));
            flushUi();

            assertEquals(
                    List.of(
                            "reset",
                            "agent:restored text",
                            "thought:restored thought",
                            "tool:call-1:Rename:edit:pending",
                            "update:call-1:complete:done"),
                    fixture.view.timeline);
        }
    }

    @Test
    void postResponseEventAppendsAfterResetAndCapturedReplay() throws Exception {
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
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");

            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("restored-session", List.of()),
                    () -> fixture.view.record("reset")));
            controller.agentText(source, "restored-session", "post-response");
            releaseEdt.countDown();
            flushUi();

            assertEquals(List.of("reset", "agent:captured", "agent:post-response"), fixture.view.timeline);
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void savedSessionConnectBuffersReplayAndShowsConnectedFirst() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("agentId", "custom");
        fixture.config.values.put("acpSessionId", "saved-session");
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final ReplayLoadTransport transport = new ReplayLoadTransport("saved-session");
        final java.util.concurrent.atomic.AtomicReference<AgentLaunchSpec> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (AcpChatController controller = fixture.controller((configuration, listener) -> {
            captured.set(configuration);
            final AcpClient connected = new AcpClient(transport, listener);
            try {
                setCapabilities(
                        connected, new AcpClient.AcpCapabilities(true, false, false, false, false, true, false));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
            return connected;
        })) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitTimeline("agent:restored");

            assertEquals(List.of("connected", "agent:restored"), fixture.view.timeline);
            assertEquals(AcpClient.McpAttachment.HTTP_READ_ONLY, fixture.view.lastMcpAttachment);
            assertEquals("saved-session", fixture.config.value("acpSessionId"));
            assertEquals(
                    List.of(temporaryExecutable().toString()), captured.get().command());
            // The final status line is logged after the view callback; wait for it.
            fixture.logger.awaitInfo("ACP connection: session ready");
            assertEquals(
                    List.of(
                            "ACP connection: starting",
                            "ACP connection: starting agent process",
                            "ACP connection: ACP initialized",
                            "ACP connection: loading saved session",
                            "ACP connection: session ready"),
                    fixture.logger.infos);
        }
    }

    @Test
    void stalledClientStartupFailsWithinConfiguredDeadline() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean release = new java.util.concurrent.atomic.AtomicBoolean();
        try (AcpChatController controller = fixture.controller(
                (configuration, listener) -> {
                    entered.countDown();
                    while (!release.get()) {
                        try {
                            Thread.sleep(5L);
                        } catch (InterruptedException ignored) {
                            // The fixture deliberately models a native start call that ignores interruption.
                        }
                    }
                    return inactiveClient();
                },
                Duration.ofMillis(50))) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            fixture.view.awaitFailure("status.acp-failed");
            assertTrue(fixture.logger.hasErrors());
        } finally {
            release.set(true);
        }
    }

    @Test
    void selectedSessionPersistsBeforeBlockedEdtReplay() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("acpSessionId", "old-session");
        final ReplayLoadTransport transport = new ReplayLoadTransport("selected-session");
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
        try (AcpChatController controller = fixture.controller();
                AcpClient source = new AcpClient(transport, controller)) {
            setCapabilities(source, new AcpClient.AcpCapabilities(true, false, false, false, false, true, false));
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            invokeActivateSession(
                    controller,
                    new AcpSession(
                            "old-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));

            selectSessionNow(controller, "selected-session");

            assertEquals("selected-session", fixture.config.value("acpSessionId"));
            assertTrue(fixture.view.timeline.isEmpty());
            releaseEdt.countDown();
            flushUi();
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void selectedSessionLoadBuffersReplayAndResetsFirst() throws Exception {
        final Fixture fixture = new Fixture();
        final ReplayLoadTransport transport = new ReplayLoadTransport("selected-session");
        try (AcpChatController controller = fixture.controller();
                AcpClient source = new AcpClient(transport, controller)) {
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            set(
                    controller,
                    "session",
                    new AcpSession(
                            "old-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));

            selectSessionNow(controller, "selected-session");
            fixture.view.awaitTimeline("agent:restored");
            flushUi();

            assertEquals(List.of("clear", "config", "agent:restored"), fixture.view.timeline);
            assertEquals("selected-session", session(controller).sessionId());
        }
    }

    @Test
    void exactlyFullPrefixStillAllowsPostCommitEventBehindReplay() throws Exception {
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
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            for (int index = 0; index < AcpChatController.MAX_PENDING_LOAD_EVENTS; index++) {
                controller.agentText(source, "restored-session", "event-" + index);
            }

            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("restored-session", List.of()),
                    () -> fixture.view.record("reset")));
            controller.agentText(source, "restored-session", "post-commit");
            releaseEdt.countDown();
            flushUi();

            assertEquals(66, fixture.view.timeline.size());
            assertEquals("agent:event-63", fixture.view.timeline.get(64));
            assertEquals("agent:post-commit", fixture.view.timeline.get(65));
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void initialRestoreReplayRunsAfterShowConnected() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "saved-session");

            controller.agentText(source, "saved-session", "restored text");
            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("saved-session", List.of()),
                    () -> fixture.view.record("connected")));
            flushUi();

            assertEquals(List.of("connected", "agent:restored text"), fixture.view.timeline);
        }
    }

    @Test
    void fullNonDroppableUiQueueRejectsLoadCommitWithoutActivationOrPersistence() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("acpSessionId", "old-session");
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
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            invokeActivateSession(
                    controller,
                    new AcpSession(
                            "old-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));
            for (int index = 0; index < 256; index++) {
                invokeUi(controller, () -> fixture.view.record("queued"));
            }
            final Object load = beginPendingLoad(controller, source, "selected-session");
            controller.agentText(source, "selected-session", "captured");

            assertFalse(completePendingLoad(
                    controller,
                    load,
                    new AcpSession(
                            "selected-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)),
                    () -> fixture.view.record("reset")));

            assertEquals("old-session", session(controller).sessionId());
            assertEquals("old-session", fixture.config.value("acpSessionId"));
            assertTrue(pendingLoad(controller) == null);
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void replayUiFailureCannotLeaveTransactionStuck() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");

            assertTrue(completePendingLoad(controller, load, new AcpSession("restored-session", List.of()), () -> {
                throw new IllegalStateException("view failed");
            }));
            flushUi();

            assertTrue(pendingLoad(controller) == null);
            controller.agentText(source, "restored-session", "live");
            flushUi();
            assertEquals(List.of("agent:live"), fixture.view.timeline);
            assertTrue(
                    fixture.logger.warnings.stream().anyMatch(message -> message.contains("UI update failed safely")));
        }
    }

    @Test
    void replacingSessionObjectWithinGenerationDoesNotDropDelayedReplay() throws Exception {
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
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");
            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("restored-session", List.of()),
                    () -> fixture.view.record("reset")));
            set(controller, "session", new AcpSession("restored-session", List.of()));
            releaseEdt.countDown();
            flushUi();

            assertEquals(List.of("reset", "agent:captured"), fixture.view.timeline);
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void pendingLoadRejectsWrongSessionAndSourceAndSupersedesOlderLoad() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpClient wrongSource = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("old-session", List.of()));
            final Object oldLoad = beginPendingLoad(controller, source, "first-session");

            controller.agentText(source, "old-session", "old active");
            controller.agentText(source, "wrong-session", "wrong id");
            controller.agentText(wrongSource, "first-session", "wrong source");
            controller.agentText(source, "first-session", "superseded");
            final Object currentLoad = beginPendingLoad(controller, source, "second-session");
            controller.agentText(source, "first-session", "old pending");
            controller.agentText(source, "second-session", "current");

            assertFalse(completePendingLoad(
                    controller,
                    oldLoad,
                    new AcpSession("first-session", List.of()),
                    () -> fixture.view.record("old-reset")));
            assertTrue(completePendingLoad(
                    controller,
                    currentLoad,
                    new AcpSession("second-session", List.of()),
                    () -> fixture.view.record("new-reset")));
            flushUi();

            assertEquals(List.of("new-reset", "agent:current"), fixture.view.timeline);
        }
    }

    @Test
    void failedAndRuntimeFailedLoadsDiscardReplayWithoutChangingSelection() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("old-session", List.of()));

            final Object acpFailure = beginPendingLoad(controller, source, "failed-session");
            controller.agentText(source, "failed-session", "discarded acp");
            discardPendingLoad(controller, acpFailure);
            final Object runtimeFailure = beginPendingLoad(controller, source, "runtime-session");
            controller.agentText(source, "runtime-session", "discarded runtime");
            discardPendingLoad(controller, runtimeFailure);

            assertEquals("old-session", session(controller).sessionId());
            assertTrue(fixture.view.timeline.isEmpty());
            assertTrue(pendingLoad(controller) == null);
        }
    }

    @Test
    void closeDuringBlockedSelectionDoesNotActivateOrPersistReplay() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("acpSessionId", "old-session");
        final BlockingLoadTransport transport = new BlockingLoadTransport();
        final AcpClient source = new AcpClient(transport, new AcpListener() {});
        final AcpChatController controller = fixture.controller();
        try {
            setCapabilities(source, new AcpClient.AcpCapabilities(true, false, false, false, false, true, false));
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            invokeActivateSession(
                    controller,
                    new AcpSession(
                            "old-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));
            final java.util.concurrent.CompletableFuture<Void> select =
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        try {
                            selectSessionNow(controller, "selected-session");
                        } catch (ReflectiveOperationException failure) {
                            throw new java.util.concurrent.CompletionException(failure);
                        }
                    });
            assertTrue(transport.loadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.CompletableFuture<Void> close =
                    java.util.concurrent.CompletableFuture.runAsync(controller::close);
            source.close();
            transport.releaseLoad.countDown();
            select.get(2, java.util.concurrent.TimeUnit.SECONDS);
            close.get(6, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals("old-session", fixture.config.value("acpSessionId"));
            assertTrue(session(controller) == null);
            assertTrue(fixture.view.timeline.isEmpty());
            assertTrue(pendingLoad(controller) == null);
        } finally {
            transport.releaseLoad.countDown();
            controller.close();
            source.close();
        }
    }

    @Test
    void closeDuringBlockedInitialRestoreDoesNotFallbackOrPersist() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("agentId", "custom");
        fixture.config.values.put("acpSessionId", "saved-session");
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final BlockingLoadTransport transport = new BlockingLoadTransport();
        final java.util.concurrent.atomic.AtomicReference<AcpClient> source =
                new java.util.concurrent.atomic.AtomicReference<>();
        final AcpChatController controller = fixture.controller((configuration, listener) -> {
            final AcpClient connected = new AcpClient(transport, listener);
            source.set(connected);
            setCapabilitiesUnchecked(
                    connected, new AcpClient.AcpCapabilities(true, false, false, false, false, true, false));
            return connected;
        });
        try {
            controller.connect("custom", temporaryExecutable().toString(), "");
            assertTrue(transport.loadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.CompletableFuture<Void> close =
                    java.util.concurrent.CompletableFuture.runAsync(controller::close);
            source.get().close();
            transport.releaseLoad.countDown();
            close.get(6, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals("saved-session", fixture.config.value("acpSessionId"));
            assertTrue(session(controller) == null);
            assertTrue(fixture.view.timeline.isEmpty());
            assertTrue(pendingLoad(controller) == null);
        } finally {
            transport.releaseLoad.countDown();
            controller.close();
        }
    }

    @Test
    void closeAndMatchingTerminationDiscardPendingReplay() throws Exception {
        final Fixture closeFixture = new Fixture();
        final AcpClient closeSource = inactiveClient();
        final AcpChatController closedController = closeFixture.controller();
        set(closedController, "client", closeSource);
        beginPendingLoad(closedController, closeSource, "close-session");
        closedController.agentText(closeSource, "close-session", "closed");
        closedController.close();
        assertTrue(pendingLoad(closedController) == null);
        closeSource.close();

        final Fixture terminalFixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = terminalFixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("old-session", List.of()));
            beginPendingLoad(controller, source, "terminal-session");
            controller.agentText(source, "terminal-session", "terminated");

            controller.terminated(source, "terminal");
            awaitSerial(controller);

            assertTrue(pendingLoad(controller) == null);
            assertTrue(session(controller) == null);
            assertTrue(terminalFixture.view.timeline.isEmpty());
        }
    }

    @Test
    void permissionCancellationDuringSelectionLoadFailureLeavesPriorSelectionClean() throws Exception {
        final Fixture fixture = new Fixture();
        final PermissionFailingLoadTransport transport = new PermissionFailingLoadTransport();
        try (AcpClient source = new AcpClient(transport, new AcpListener() {});
                AcpChatController controller = fixture.controller()) {
            transport.source = source;
            transport.listener = controller;
            set(controller, "client", source);
            set(
                    controller,
                    "mcpConnection",
                    new dev.turboism.sdk.mcp.McpHttpConnection(
                            java.net.URI.create("http://127.0.0.1:41234/mcp"), "2025-06-18"));
            set(
                    controller,
                    "session",
                    new AcpSession(
                            "old-session",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));

            selectSessionNow(controller, "loading-session");
            flushUi();

            assertEquals(AcpListener.PermissionDecision.CANCELLED, transport.decision.get());
            assertEquals("old-session", session(controller).sessionId());
            assertTrue(fixture.view.failures.contains("status.session-load-failed"));
            assertTrue(fixture.view.timeline.isEmpty());
            assertTrue(pendingLoad(controller) == null);
        }
    }

    @Test
    void slowPermissionIsCancelledIfClientTerminatesBeforeDecisionReturns() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.view.permissionEntered = new java.util.concurrent.CountDownLatch(1);
        fixture.view.releasePermission = new java.util.concurrent.CountDownLatch(1);
        fixture.view.permissionDecision = AcpListener.PermissionDecision.ALLOW_ONCE;
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            invokeActivateSession(controller, new AcpSession("current-session", List.of()));
            final java.util.concurrent.CompletableFuture<AcpListener.PermissionDecision> decision =
                    java.util.concurrent.CompletableFuture.supplyAsync(() -> controller.permission(
                            source,
                            "current-session",
                            new AcpListener.PermissionRequest(
                                    "Rename",
                                    "edit",
                                    "call-1",
                                    "{}",
                                    new AcpClient.PermissionOptionSet("allow_once", "allow_always", "reject_once"))));
            assertTrue(fixture.view.permissionEntered.await(2, java.util.concurrent.TimeUnit.SECONDS));

            controller.terminated(source, "terminated");
            awaitSerial(controller);
            fixture.view.releasePermission.countDown();

            assertEquals(
                    AcpListener.PermissionDecision.CANCELLED, decision.get(2, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            if (fixture.view.releasePermission != null) {
                fixture.view.releasePermission.countDown();
            }
        }
    }

    @Test
    void permissionDuringPendingLoadIsCancelledWithoutCallingTheView() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("old-session", List.of()));
            beginPendingLoad(controller, source, "loading-session");

            final AcpListener.PermissionDecision decision = controller.permission(
                    source,
                    "loading-session",
                    new AcpListener.PermissionRequest(
                            "Rename",
                            "edit",
                            "call-1",
                            "{}",
                            new AcpClient.PermissionOptionSet("allow_once", "allow_always", "reject_once")));

            assertEquals(AcpListener.PermissionDecision.CANCELLED, decision);
            assertEquals(0, fixture.view.permissionRequests.get());
        }
    }

    @ParameterizedTest(name = "pending load handles {0} events (overflow: {1})")
    @CsvSource({"64, false", "70, true"})
    void pendingLoadEventCountBoundary(int eventCount, boolean expectOverflow) throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");
            for (int index = 0; index < eventCount; index++) {
                controller.agentText(source, "bounded-session", "event-" + index);
            }

            assertTrue(completePendingLoad(
                    controller,
                    load,
                    new AcpSession("bounded-session", List.of()),
                    () -> fixture.view.record("reset")));
            flushUi();

            assertEquals(65, fixture.view.timeline.size());
            assertEquals("agent:event-63", fixture.view.timeline.get(64));
            if (!expectOverflow) {
                assertFalse(fixture.logger.warnings.stream()
                        .anyMatch(message -> message.contains("session-load replay events")));
            } else {
                assertEquals(
                        1L,
                        fixture.logger.warnings.stream()
                                .filter(message -> message.contains("session-load replay events"))
                                .count());
            }
        }
    }

    @ParameterizedTest(name = "pending load text boundary (exceedsLimit: {0})")
    @ValueSource(booleans = {false, true})
    void pendingLoadTextPayloadBoundary(boolean exceedsLimit) throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");

            if (!exceedsLimit) {
                final String content = "é".repeat(((int) AcpChatController.MAX_PENDING_LOAD_TEXT_BYTES - 2) / 2);
                controller.toolCallUpdate(source, "bounded-session", "i", "s", content);
                assertTrue(completePendingLoad(
                        controller,
                        load,
                        new AcpSession("bounded-session", List.of()),
                        () -> fixture.view.record("reset")));
                flushUi();

                assertEquals(List.of("reset", "update:i:s:" + content), fixture.view.timeline);
                assertFalse(fixture.logger.warnings.stream()
                        .anyMatch(message -> message.contains("session-load replay events")));
            } else {
                final String exact = "x".repeat((int) AcpChatController.MAX_PENDING_LOAD_TEXT_BYTES);
                controller.agentText(source, "bounded-session", exact);
                controller.agentText(source, "bounded-session", "x");
                controller.agentText(source, "bounded-session", "later");
                assertTrue(completePendingLoad(
                        controller,
                        load,
                        new AcpSession("bounded-session", List.of()),
                        () -> fixture.view.record("reset")));
                flushUi();

                assertEquals(List.of("reset", "agent:" + exact), fixture.view.timeline);
                assertEquals(
                        1L,
                        fixture.logger.warnings.stream()
                                .filter(message -> message.contains("session-load replay events"))
                                .count());
            }
        }
    }

    @Test
    void thinkingAndToolIdentityAreForwardedToTheView() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("sess-1", List.of()));

            controller.agentThought(source, "sess-1", "hidden plan");
            controller.toolCall(source, "sess-1", "call-1", "Rename object", "edit", "pending");
            awaitSerial(controller);
            fixture.view.awaitThinking();
            fixture.view.awaitToolCall();

            assertEquals(List.of("hidden plan"), fixture.view.thinkingMessages);
            assertEquals(List.of("call-1", "Rename object", "edit", "pending"), fixture.view.toolCalls.get(0));
        }
    }

    @Test
    void configDispatchFailureRestoresTheLastConfirmedOptions() throws Exception {
        final Fixture fixture = new Fixture();
        final AcpConfigOption confirmed = new AcpConfigOption(
                "provider",
                "Provider",
                "gateway",
                List.of(
                        new AcpConfigOption.Choice("gateway", "Gateway"),
                        new AcpConfigOption.Choice("codex", "Codex")));
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            source.close();
            set(controller, "client", source);
            set(controller, "session", new AcpSession("sess-1", List.of(confirmed)));

            controller.setConfigOption("provider", "codex");
            fixture.view.awaitConfigFailure();

            assertEquals(List.of("provider"), fixture.view.configUpdatingIds);
            assertEquals("provider", fixture.view.configFailureId.get());
            assertEquals(List.of(confirmed), fixture.view.configFailureOptions.get());
        }
    }

    @Test
    void closeFlushesTheLatestSettings() {
        final Fixture fixture = new Fixture();
        final AcpChatController controller = fixture.controller();

        controller.saveSettings("custom", "/opt/agent --acp", "note");
        controller.close();

        assertEquals("custom", fixture.config.value("agentId"));
        assertEquals("/opt/agent --acp", fixture.config.value("customCommand"));
        assertEquals("note", fixture.config.value("initialPrompt"));
    }

    @Test
    void closeReturnsPromptlyOnTheSwingEventThread() throws Exception {
        final Fixture fixture = new Fixture();
        final AcpChatController controller = fixture.controller();
        final java.util.concurrent.atomic.AtomicLong elapsed =
                new java.util.concurrent.atomic.AtomicLong(Long.MAX_VALUE);

        javax.swing.SwingUtilities.invokeAndWait(() -> {
            final long started = System.nanoTime();
            controller.close();
            elapsed.set(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
        });

        assertTrue(elapsed.get() < 1_000L, "controller close blocked the Swing EDT");
    }

    @Test
    void submissionsAfterCloseAreIgnoredWithoutExecutorRejection() {
        final Fixture fixture = new Fixture();
        final AcpChatController controller = fixture.controller();
        controller.close();

        controller.connect("custom", "fx", "");
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
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new AcpSession("current-session", List.of()));

            controller.agentText(source, "old-session", "delayed text");
            controller.agentThought(source, "old-session", "delayed thought");
            controller.toolCall(source, "old-session", "call-1", "Rename object", "edit", "pending");
            final AcpListener.PermissionDecision decision = controller.permission(
                    source,
                    "old-session",
                    new AcpListener.PermissionRequest(
                            "Rename",
                            "edit",
                            "call-1",
                            "{}",
                            new AcpClient.PermissionOptionSet("allow_once", "allow_always", "reject_once")));
            awaitSerial(controller);

            assertTrue(fixture.view.agentMessages.isEmpty());
            assertTrue(fixture.view.thinkingMessages.isEmpty());
            assertTrue(fixture.view.toolCalls.isEmpty());
            assertEquals(AcpListener.PermissionDecision.CANCELLED, decision);
        }
    }

    @Test
    void staleTerminationCannotDetachTheCurrentClient() throws Exception {
        final Fixture fixture = new Fixture();
        try (AcpClient stale = inactiveClient();
                AcpClient current = inactiveClient();
                AcpChatController controller = fixture.controller()) {
            set(controller, "client", current);
            set(controller, "session", new AcpSession("sess-1", List.of()));

            controller.terminated(stale, "stale process ended");
            awaitSerial(controller);

            assertEquals(current, atomicClient(controller));
            assertTrue(fixture.view.failures.isEmpty());
            assertTrue(session(controller) != null);
        }
    }

    @Test
    void mcpEndpointDriftReconnectsToBindTheLatestEndpoint() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller((configuration, listener) -> {
                    launches.incrementAndGet();
                    throw new java.io.IOException("no agent in test");
                })) {
            // connectNow subscribes before the launch fails, so the listener stays live.
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitFailure("status.executable-start-failed");
            assertEquals(1, fixture.mcpListeners.size());
            assertEquals(1, launches.get());

            set(controller, "client", source);
            set(
                    controller,
                    "session",
                    new AcpSession(
                            "sess-1",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));
            set(controller, "mcpConnection", testMcpConnection());

            // A same-endpoint republish must not reconnect.
            fixture.pushMcpConnection(Optional.of(testMcpConnection()));
            fixture.pushMcpConnection(Optional.of(new dev.turboism.sdk.mcp.McpHttpConnection(
                    java.net.URI.create("http://127.0.0.1:49999/mcp"), "2025-06-18")));

            fixture.view.awaitFailure("status.mcp-endpoint-changed");
            awaitLaunches(controller, launches, 2);
            assertEquals(2, launches.get(), "failures=" + fixture.view.failures + " infos=" + fixture.logger.infos);
            assertTrue(atomicClient(controller) == null);
            assertTrue(session(controller) == null);
            assertEquals(
                    1,
                    fixture.view.failures.stream()
                            .filter("status.mcp-endpoint-changed"::equals)
                            .count());
        }
    }

    @Test
    void mcpEndpointRevocationReconnectsAndNotificationsStopAfterClose() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        final AcpChatController controller = fixture.controller((configuration, listener) -> {
            launches.incrementAndGet();
            throw new java.io.IOException("no agent in test");
        });
        try (AcpClient source = inactiveClient()) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitFailure("status.executable-start-failed");
            set(controller, "client", source);
            set(
                    controller,
                    "session",
                    new AcpSession(
                            "sess-1",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, true, false)));
            set(controller, "mcpConnection", testMcpConnection());

            fixture.pushMcpConnection(Optional.empty());
            fixture.view.awaitFailure("status.mcp-endpoint-changed");
            awaitLaunches(controller, launches, 2);
            assertEquals(2, launches.get());
        }
        controller.close();
        fixture.pushMcpConnection(Optional.of(testMcpConnection()));

        assertEquals(0, fixture.mcpListeners.size());
        assertEquals(2, launches.get());
    }

    @ParameterizedTest(name = "endpoint drift with stdioAttached={0}")
    @ValueSource(booleans = {true, false})
    void mcpEndpointDriftAttachmentBehavior(boolean stdioAttached) throws Exception {
        final Fixture fixture = new Fixture();
        fixture.mcpConnection = Optional.of(stdioAttached ? stdioMcpConnection() : testMcpConnection());
        final java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
        try (AcpClient source = inactiveClient();
                AcpChatController controller = fixture.controller((configuration, listener) -> {
                    launches.incrementAndGet();
                    throw new java.io.IOException("no agent in test");
                })) {
            controller.connect("custom", temporaryExecutable().toString(), "");
            fixture.view.awaitFailure("status.executable-start-failed");
            assertEquals(1, launches.get());

            set(controller, "client", source);
            set(
                    controller,
                    "session",
                    new AcpSession(
                            "sess-1",
                            List.of(),
                            new AcpClient.AcpCapabilities(true, false, false, false, false, false, false)));
            set(controller, "mcpConnection", stdioAttached ? stdioMcpConnection() : testMcpConnection());

            fixture.pushMcpConnection(Optional.of(new dev.turboism.sdk.mcp.McpHttpConnection(
                    java.net.URI.create("http://127.0.0.1:49998/mcp"), "2025-06-18", stdioLaunch())));

            if (stdioAttached) {
                fixture.view.awaitFailure("status.mcp-endpoint-changed");
                awaitLaunches(controller, launches, 2);
                assertEquals(2, launches.get());
            } else {
                awaitSerial(controller);
                assertEquals(1, launches.get());
                assertFalse(fixture.view.failures.contains("status.mcp-endpoint-changed"));
            }
        }
    }

    private static AcpSession configuredSession(final String sessionId) {
        return new AcpSession(
                sessionId,
                List.of(
                        new AcpConfigOption(
                                "provider",
                                "Provider",
                                "gateway",
                                List.of(new AcpConfigOption.Choice("gateway", "Gateway"))),
                        new AcpConfigOption(
                                "model",
                                "Model",
                                "vendor/model",
                                List.of(new AcpConfigOption.Choice("vendor/model", "vendor/model")))));
    }

    private AcpClient inactiveClient() throws java.io.IOException {
        return new AcpClient(new CapturingTransport(), new AcpListener() {});
    }

    private static void invokeActivateSession(final AcpChatController controller, final AcpSession session)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method =
                controller.getClass().getDeclaredMethod("activateSession", AcpSession.class);
        method.setAccessible(true);
        method.invoke(controller, session);
    }

    private static Object beginPendingLoad(
            final AcpChatController controller, final AcpClient source, final String sessionId)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method =
                controller.getClass().getDeclaredMethod("beginLoadTransaction", AcpClient.class, String.class);
        method.setAccessible(true);
        return method.invoke(controller, source, sessionId);
    }

    private static boolean completePendingLoad(
            final AcpChatController controller, final Object load, final AcpSession session, final Runnable reset)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller
                .getClass()
                .getDeclaredMethod("completeLoadTransaction", load.getClass(), AcpSession.class, Runnable.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(controller, load, session, reset);
    }

    private static void selectSessionNow(final AcpChatController controller, final String sessionId)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method =
                controller.getClass().getDeclaredMethod("selectSessionNow", String.class);
        method.setAccessible(true);
        try {
            method.invoke(controller, sessionId);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    private static void discardPendingLoad(final AcpChatController controller, final Object load)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method =
                controller.getClass().getDeclaredMethod("discardLoadTransaction", load.getClass());
        method.setAccessible(true);
        method.invoke(controller, load);
    }

    private static Object pendingLoad(final AcpChatController controller) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("loadTransaction");
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void flushUi() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> {});
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

    private static void awaitConfigValue(final Fixture fixture, final String key, final String expected)
            throws InterruptedException {
        // Settings rollback runs on the serial lane after the failure status publishes, so
        // awaiting the status alone can race ahead of the restored values.
        final long deadline =
                System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
        while (!expected.equals(fixture.config.value(key)) && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
    }

    private static void awaitLaunches(
            final AcpChatController controller,
            final java.util.concurrent.atomic.AtomicInteger launches,
            final int expected)
            throws Exception {
        // The reconnect launch is enqueued on the serial lane after the drift failure is
        // published, so a single drain can complete before the second launch runs.
        for (int attempt = 0; launches.get() < expected && attempt < 2000; attempt++) {
            awaitSerial(controller);
            Thread.sleep(5);
        }
    }

    private static void awaitSerial(final AcpChatController controller) throws Exception {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("serial");
        field.setAccessible(true);
        final java.util.concurrent.ExecutorService serial =
                (java.util.concurrent.ExecutorService) field.get(controller);
        serial.submit(() -> {}).get(2, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static void invokeUi(final AcpChatController controller, final Runnable work)
            throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod("ui", Runnable.class);
        method.setAccessible(true);
        method.invoke(controller, work);
    }

    private Path temporaryExecutable() throws java.io.IOException {
        final Path executable = temporaryDirectory.resolve("fx-test");
        java.nio.file.Files.writeString(executable, "test");
        return executable;
    }

    private static dev.turboism.sdk.mcp.McpHttpConnection testMcpConnection() {
        return new dev.turboism.sdk.mcp.McpHttpConnection(
                java.net.URI.create("http://127.0.0.1:41234/mcp"), "2025-06-18");
    }

    private static dev.turboism.sdk.mcp.McpStdioLaunch stdioLaunch() {
        return new dev.turboism.sdk.mcp.McpStdioLaunch(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath()
                        .toString(),
                List.of("-cp", "/plugins dir/mcp.jar", "dev.turboism.plugin.mcp.TurboismMcpBridge", "/state dir"));
    }

    private static dev.turboism.sdk.mcp.McpHttpConnection stdioMcpConnection() {
        return new dev.turboism.sdk.mcp.McpHttpConnection(
                java.net.URI.create("http://127.0.0.1:41234/mcp"), "2025-06-18", stdioLaunch());
    }

    private static void setCapabilities(final AcpClient client, final AcpClient.AcpCapabilities capabilities)
            throws ReflectiveOperationException {
        final java.lang.reflect.Field field = AcpClient.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(client, capabilities);
    }

    private static void setCapabilitiesUnchecked(final AcpClient client, final AcpClient.AcpCapabilities capabilities) {
        try {
            setCapabilities(client, capabilities);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static boolean booleanField(final Object target, final String fieldName)
            throws ReflectiveOperationException {
        final java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static AcpClient atomicClient(final AcpChatController controller) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("client");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        final java.util.concurrent.atomic.AtomicReference<AcpClient> reference =
                (java.util.concurrent.atomic.AtomicReference<AcpClient>) field.get(controller);
        return reference.get();
    }

    private static AcpSession session(final AcpChatController controller) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("session");
        field.setAccessible(true);
        return (AcpSession) field.get(controller);
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
        private Optional<dev.turboism.sdk.mcp.McpHttpConnection> mcpConnection = Optional.empty();
        private final java.util.List<java.util.function.Consumer<Optional<dev.turboism.sdk.mcp.McpHttpConnection>>>
                mcpListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

        private void pushMcpConnection(final Optional<dev.turboism.sdk.mcp.McpHttpConnection> connection) {
            mcpConnection = connection;
            mcpListeners.forEach(listener -> listener.accept(connection));
        }

        private AcpChatController controller() {
            return controller(AcpClient::start);
        }

        private AcpChatController controller(final AcpChatController.ClientStarter clientStarter) {
            return controller(clientStarter, Duration.ofSeconds(25));
        }

        private AcpChatController controller(
                final AcpChatController.ClientStarter clientStarter, final Duration startTimeout) {
            return new AcpChatController(
                    context(), new AcpPluginSettings(config, logger), view, clientStarter, startTimeout);
        }

        private PluginContext context() {
            final PluginPaths paths = new PluginPaths() {
                @Override
                public Path dataDir() {
                    return temporaryDirectory.resolve("data/dev.turboism.plugin.acp");
                }

                @Override
                public Path logsDir() {
                    return temporaryDirectory.resolve("logs/dev.turboism.plugin.acp");
                }

                @Override
                public Path stateDir() {
                    return temporaryDirectory.resolve("state/dev.turboism.plugin.acp");
                }

                @Override
                public Path cacheDir() {
                    return temporaryDirectory.resolve("cache/dev.turboism.plugin.acp");
                }
            };
            final UiScheduler ui = new UiScheduler() {
                @Override
                public Registration runOnUiThread(final Runnable work) {
                    uiCalls.incrementAndGet();
                    if (uiRejects) throw new IllegalStateException("test UI budget rejection");
                    work.run();
                    return () -> {};
                }

                @Override
                public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    uiCalls.incrementAndGet();
                    if (uiRejects) throw new IllegalStateException("test UI budget rejection");
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
                                    .install(
                                            dev.turboism.sdk.mcp.McpConnectionService.class,
                                            new McpConnectionService() {
                                                @Override
                                                public Optional<dev.turboism.sdk.mcp.McpHttpConnection> current() {
                                                    return mcpConnection;
                                                }

                                                @Override
                                                public Registration publish(
                                                        final dev.turboism.sdk.mcp.McpHttpConnection connection) {
                                                    throw new UnsupportedOperationException("not used");
                                                }

                                                @Override
                                                public Registration subscribe(
                                                        final java.util.function.Consumer<
                                                                        Optional<
                                                                                dev.turboism.sdk.mcp.McpHttpConnection>>
                                                                listener) {
                                                    mcpListeners.add(listener);
                                                    listener.accept(mcpConnection);
                                                    return () -> mcpListeners.remove(listener);
                                                }
                                            })
                                    .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                    .build();
                        case "uiScheduler" -> ui;
                        case "toString" -> "AcpChatControllerTestContext";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        default ->
                            throw new UnsupportedOperationException("unused PluginContext method: " + method.getName());
                    });
        }

        private void await(final CheckedCondition condition) throws Exception {
            for (int attempt = 0; attempt < 2000; attempt++) {
                if (condition.test()) return;
                Thread.sleep(1L);
            }
            assertTrue(condition.test(), "controller did not reach the expected state");
        }
    }
}
