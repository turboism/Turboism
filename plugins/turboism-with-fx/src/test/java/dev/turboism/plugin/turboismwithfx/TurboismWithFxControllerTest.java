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
    void compatibilityChoiceDoesNotBlockConnectionSetup() throws Exception {
        final Fixture fixture = new Fixture();
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.connect("missing-fx-fixture", false, "");
            fixture.view.awaitFailure("status.mcp-unavailable");
        }

        assertEquals("missing-fx-fixture", fixture.config.value("fxExecutable"));
        assertEquals("false", fixture.config.value("allowFxNativeTools"));
        assertTrue(fixture.logger.hasErrors());
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
        fixture.config.values.put("activeProviderProfile", FxProviderProfile.VERCEL_ID);
        try (TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", inactiveClient());
            set(controller, "session", configuredSession("sess-1"));

            controller.sendPrompt("x".repeat(TurboismWithFxController.MAX_PROMPT_CHARS));
            fixture.view.awaitFailure("status.prompt-failed");

            assertTrue(fixture.logger.warnings.stream()
                .anyMatch(message -> message.contains("ACP text limit")));
            assertTrue(fixture.view.userMessages.isEmpty());
            assertFalse(booleanField(controller, "prompting"));
        }
    }

    @Test
    void missingProviderAndModelWarnOnlyWhenPrompting() throws Exception {
        final Fixture fixture = new Fixture();
        final CapturingTransport transport = new CapturingTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));

            controller.sendPrompt("rename the object");
            fixture.view.awaitFailure("status.provider-model-required");

            assertTrue(fixture.view.userMessages.isEmpty());
            assertFalse(booleanField(controller, "prompting"));
            assertFalse(transport.hasRequest());
        }
    }

    @Test
    void changingProviderClearsThePreviousDurableSession() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxSessionId", "provider-a-session");
        try (TurboismWithFxController controller = fixture.controller()) {
            controller.saveSettings("", false, "", new FxProviderConfiguration(
                FxProviderProfile.CODEX_ID, List.of(), Map.of()
            ));
            awaitSerial(controller);

            assertEquals("", fixture.config.value("fxSessionId"));
            assertEquals(
                FxProviderProfile.CODEX_ID,
                fixture.config.value("activeProviderProfile")
            );
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
        fixture.config.values.put("activeProviderProfile", FxProviderProfile.VERCEL_ID);
        final CapturingTransport transport = new CapturingTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", configuredSession("sess-1"));

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
        fixture.config.values.put("activeProviderProfile", FxProviderProfile.VERCEL_ID);
        final CapturingTransport transport = new CapturingTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", client);
            set(controller, "session", configuredSession("sess-1"));
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
            fixture.view.awaitFailure("status.mcp-unavailable");
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
    void pendingLoadReplaysTypedEventsAfterSelectionResetInOriginalOrder() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("old-session", List.of()));
            final Object load = beginPendingLoad(controller, source, "restored-session");

            controller.agentText(source, "restored-session", "restored text");
            controller.agentThought(source, "restored-session", "restored thought");
            controller.toolCall(
                source,
                "restored-session",
                "call-1",
                "Rename",
                "edit",
                "pending"
            );
            controller.toolCallUpdate(
                source,
                "restored-session",
                "call-1",
                "complete",
                "done"
            );

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("restored-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            flushUi();

            assertEquals(
                List.of(
                    "reset",
                    "agent:restored text",
                    "thought:restored thought",
                    "tool:call-1:Rename:edit:pending",
                    "update:call-1:complete:done"
                ),
                fixture.view.timeline
            );
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
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("restored-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            controller.agentText(source, "restored-session", "post-response");
            releaseEdt.countDown();
            flushUi();

            assertEquals(
                List.of("reset", "agent:captured", "agent:post-response"),
                fixture.view.timeline
            );
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void savedSessionConnectBuffersReplayAndShowsConnectedFirst() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxSessionId", "saved-session");
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final ReplayLoadTransport transport = new ReplayLoadTransport("saved-session");
        final java.util.concurrent.atomic.AtomicReference<FxLaunchConfiguration> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        try (TurboismWithFxController controller = fixture.controller(
            (configuration, listener) -> {
                captured.set(configuration);
                final FxAcpClient connected = new FxAcpClient(transport, listener);
                try {
                    setCapabilities(
                        connected,
                        new FxAcpClient.FxAcpCapabilities(true, false, false)
                    );
                } catch (ReflectiveOperationException failure) {
                    throw new IllegalStateException(failure);
                }
                return connected;
            }
        )) {
            controller.connect(temporaryExecutable().toString(), true, "");
            fixture.view.awaitTimeline("agent:restored");

            assertEquals(List.of("connected", "agent:restored"), fixture.view.timeline);
            assertEquals("saved-session", fixture.config.value("fxSessionId"));
            assertEquals("gateway", object(dev.turboism.protocol.json.StrictJson.parse(
                java.nio.file.Files.readAllBytes(
                    Path.of(captured.get().environment().get("HOME"))
                        .resolve(".fx/settings.json")
                )
            )).get("provider"));
            assertTrue(captured.get().environment().containsKey("AI_GATEWAY_API_KEY"));
            assertEquals(List.of(temporaryExecutable().toString(), "acp"), captured.get().command());
        }
    }

    @Test
    void customProviderUsesIsolatedGatewayHomeAndStartupModel() throws Exception {
        final Fixture fixture = new Fixture();
        final FxProviderProfile profile = new FxProviderProfile(
            "custom-provider",
            "Custom provider",
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            "http://127.0.0.1:1/v1",
            "",
            "vendor/model",
            List.of("vendor/manual")
        );
        fixture.config.values.put("activeProviderProfile", profile.id());
        fixture.config.values.put(
            "customProviderProfiles",
            FxProviderProfileCodec.encode(List.of(profile))
        );
        fixture.config.values.put("fxSessionId", "saved-session");
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final ReplayLoadTransport transport = new ReplayLoadTransport("saved-session");
        final java.util.concurrent.atomic.AtomicReference<FxLaunchConfiguration> captured =
            new java.util.concurrent.atomic.AtomicReference<>();
        try (TurboismWithFxController controller = fixture.controller(
            (configuration, listener) -> {
                captured.set(configuration);
                final FxAcpClient connected = new FxAcpClient(transport, listener);
                setCapabilitiesUnchecked(
                    connected,
                    new FxAcpClient.FxAcpCapabilities(true, false, false)
                );
                return connected;
            }
        )) {
            final Path executable = temporaryExecutable();
            controller.connect(executable.toString(), false, "");
            fixture.view.awaitTimeline("agent:restored");

            assertEquals(
                List.of(executable.toString(), "acp", "--model", "vendor/model"),
                captured.get().command()
            );
            assertEquals(
                captured.get().environment().get("HOME"),
                captured.get().environment().get("USERPROFILE")
            );
            assertTrue(captured.get().environment().containsKey("FX_GATEWAY_CHAT_URL"));
            assertEquals("gateway", object(dev.turboism.protocol.json.StrictJson.parse(
                java.nio.file.Files.readAllBytes(
                    Path.of(captured.get().environment().get("HOME"))
                        .resolve(".fx/settings.json")
                )
            )).get("provider"));
        }
    }

    @Test
    void selectedSessionPersistsBeforeBlockedEdtReplay() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxSessionId", "old-session");
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
        try (TurboismWithFxController controller = fixture.controller();
             FxAcpClient source = new FxAcpClient(transport, controller)) {
            setCapabilities(
                source,
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            );
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            invokeActivateSession(controller, new FxAcpSession(
                "old-session",
                List.of(),
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            ));

            selectSessionNow(controller, "selected-session");

            assertEquals("selected-session", fixture.config.value("fxSessionId"));
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
        try (TurboismWithFxController controller = fixture.controller();
             FxAcpClient source = new FxAcpClient(transport, controller)) {
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            set(controller, "session", new FxAcpSession(
                "old-session",
                List.of(),
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            ));

            selectSessionNow(controller, "selected-session");
            fixture.view.awaitTimeline("agent:restored");
            flushUi();

            assertEquals(
                List.of("clear", "config", "agent:restored"),
                fixture.view.timeline
            );
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
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            for (int index = 0; index < TurboismWithFxController.MAX_PENDING_LOAD_EVENTS; index++) {
                controller.agentText(source, "restored-session", "event-" + index);
            }

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("restored-session", List.of()),
                () -> fixture.view.record("reset")
            ));
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
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "saved-session");

            controller.agentText(source, "saved-session", "restored text");
            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("saved-session", List.of()),
                () -> fixture.view.record("connected")
            ));
            flushUi();

            assertEquals(List.of("connected", "agent:restored text"), fixture.view.timeline);
        }
    }

    @Test
    void fullNonDroppableUiQueueRejectsLoadCommitWithoutActivationOrPersistence() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("fxSessionId", "old-session");
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
            invokeActivateSession(controller, new FxAcpSession(
                "old-session",
                List.of(),
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            ));
            for (int index = 0; index < 256; index++) {
                invokeUi(controller, () -> fixture.view.record("queued"));
            }
            final Object load = beginPendingLoad(controller, source, "selected-session");
            controller.agentText(source, "selected-session", "captured");

            assertFalse(completePendingLoad(
                controller,
                load,
                new FxAcpSession(
                    "selected-session",
                    List.of(),
                    new FxAcpClient.FxAcpCapabilities(true, false, false)
                ),
                () -> fixture.view.record("reset")
            ));

            assertEquals("old-session", session(controller).sessionId());
            assertEquals("old-session", fixture.config.value("fxSessionId"));
            assertTrue(pendingLoad(controller) == null);
        } finally {
            releaseEdt.countDown();
        }
    }

    @Test
    void replayUiFailureCannotLeaveTransactionStuck() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("restored-session", List.of()),
                () -> { throw new IllegalStateException("view failed"); }
            ));
            flushUi();

            assertTrue(pendingLoad(controller) == null);
            controller.agentText(source, "restored-session", "live");
            flushUi();
            assertEquals(List.of("agent:live"), fixture.view.timeline);
            assertTrue(fixture.logger.warnings.stream()
                .anyMatch(message -> message.contains("UI update failed safely")));
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
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "restored-session");
            controller.agentText(source, "restored-session", "captured");
            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("restored-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            set(controller, "session", new FxAcpSession("restored-session", List.of()));
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
        try (FxAcpClient source = inactiveClient();
             FxAcpClient wrongSource = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("old-session", List.of()));
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
                new FxAcpSession("first-session", List.of()),
                () -> fixture.view.record("old-reset")
            ));
            assertTrue(completePendingLoad(
                controller,
                currentLoad,
                new FxAcpSession("second-session", List.of()),
                () -> fixture.view.record("new-reset")
            ));
            flushUi();

            assertEquals(
                List.of("new-reset", "agent:current"),
                fixture.view.timeline
            );
        }
    }

    @Test
    void failedAndRuntimeFailedLoadsDiscardReplayWithoutChangingSelection() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("old-session", List.of()));

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
        fixture.config.values.put("fxSessionId", "old-session");
        final BlockingLoadTransport transport = new BlockingLoadTransport();
        final FxAcpClient source = new FxAcpClient(transport, new FxAcpListener() { });
        final TurboismWithFxController controller = fixture.controller();
        try {
            setCapabilities(
                source,
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            );
            set(controller, "client", source);
            set(controller, "mcpConnection", testMcpConnection());
            invokeActivateSession(controller, new FxAcpSession(
                "old-session",
                List.of(),
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            ));
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

            assertEquals("old-session", fixture.config.value("fxSessionId"));
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
        fixture.config.values.put("fxSessionId", "saved-session");
        fixture.mcpConnection = Optional.of(testMcpConnection());
        final BlockingLoadTransport transport = new BlockingLoadTransport();
        final java.util.concurrent.atomic.AtomicReference<FxAcpClient> source =
            new java.util.concurrent.atomic.AtomicReference<>();
        final TurboismWithFxController controller = fixture.controller(
            (configuration, listener) -> {
                final FxAcpClient connected = new FxAcpClient(transport, listener);
                source.set(connected);
                setCapabilitiesUnchecked(
                    connected,
                    new FxAcpClient.FxAcpCapabilities(true, false, false)
                );
                return connected;
            }
        );
        try {
            controller.connect(temporaryExecutable().toString(), true, "");
            assertTrue(transport.loadStarted.await(2, java.util.concurrent.TimeUnit.SECONDS));
            final java.util.concurrent.CompletableFuture<Void> close =
                java.util.concurrent.CompletableFuture.runAsync(controller::close);
            source.get().close();
            transport.releaseLoad.countDown();
            close.get(6, java.util.concurrent.TimeUnit.SECONDS);

            assertEquals("saved-session", fixture.config.value("fxSessionId"));
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
        final FxAcpClient closeSource = inactiveClient();
        final TurboismWithFxController closedController = closeFixture.controller();
        set(closedController, "client", closeSource);
        beginPendingLoad(closedController, closeSource, "close-session");
        closedController.agentText(closeSource, "close-session", "closed");
        closedController.close();
        assertTrue(pendingLoad(closedController) == null);
        closeSource.close();

        final Fixture terminalFixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = terminalFixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("old-session", List.of()));
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
        try (FxAcpClient source = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            transport.source = source;
            transport.listener = controller;
            set(controller, "client", source);
            set(controller, "mcpConnection", new dev.turboism.sdk.mcp.McpHttpConnection(
                java.net.URI.create("http://127.0.0.1:41234/mcp"),
                "2025-06-18",
                "Bearer test"
            ));
            set(controller, "session", new FxAcpSession(
                "old-session",
                List.of(),
                new FxAcpClient.FxAcpCapabilities(true, false, false)
            ));

            selectSessionNow(controller, "loading-session");
            flushUi();

            assertEquals(FxAcpListener.PermissionDecision.CANCELLED, transport.decision.get());
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
        fixture.view.permissionDecision = FxAcpListener.PermissionDecision.ALLOW_ONCE;
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            invokeActivateSession(
                controller,
                new FxAcpSession("current-session", List.of())
            );
            final java.util.concurrent.CompletableFuture<FxAcpListener.PermissionDecision> decision =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> controller.permission(
                    source,
                    "current-session",
                    new FxAcpListener.PermissionRequest("Rename", "edit", "call-1", "{}")
                ));
            assertTrue(fixture.view.permissionEntered.await(
                2, java.util.concurrent.TimeUnit.SECONDS
            ));

            controller.terminated(source, "terminated");
            awaitSerial(controller);
            fixture.view.releasePermission.countDown();

            assertEquals(
                FxAcpListener.PermissionDecision.CANCELLED,
                decision.get(2, java.util.concurrent.TimeUnit.SECONDS)
            );
        } finally {
            if (fixture.view.releasePermission != null) {
                fixture.view.releasePermission.countDown();
            }
        }
    }

    @Test
    void permissionDuringPendingLoadIsCancelledWithoutCallingTheView() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("old-session", List.of()));
            beginPendingLoad(controller, source, "loading-session");

            final FxAcpListener.PermissionDecision decision = controller.permission(
                source,
                "loading-session",
                new FxAcpListener.PermissionRequest("Rename", "edit", "call-1", "{}")
            );

            assertEquals(FxAcpListener.PermissionDecision.CANCELLED, decision);
            assertEquals(0, fixture.view.permissionRequests.get());
        }
    }

    @Test
    void pendingLoadAcceptsExactly64EventsWithoutOverflow() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");
            for (int index = 0; index < 64; index++) {
                controller.agentText(source, "bounded-session", "event-" + index);
            }

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("bounded-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            flushUi();

            assertEquals(65, fixture.view.timeline.size());
            assertEquals("agent:event-63", fixture.view.timeline.get(64));
            assertFalse(fixture.logger.warnings.stream()
                .anyMatch(message -> message.contains("session-load replay events")));
        }
    }

    @Test
    void pendingLoadLatchesAt65thEventAndDropsAllLaterEvents() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");
            for (int index = 0; index < 70; index++) {
                controller.agentText(source, "bounded-session", "event-" + index);
            }

            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("bounded-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            flushUi();

            assertEquals(65, fixture.view.timeline.size());
            assertEquals("agent:event-63", fixture.view.timeline.get(64));
            assertEquals(1L, fixture.logger.warnings.stream()
                .filter(message -> message.contains("session-load replay events"))
                .count());
        }
    }

    @Test
    void pendingLoadAcceptsExactlyOneMiBUtf8AcrossAllStringFields() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");
            final String content = "é".repeat(
                ((int) TurboismWithFxController.MAX_PENDING_LOAD_TEXT_BYTES - 2) / 2
            );

            controller.toolCallUpdate(
                source,
                "bounded-session",
                "i",
                "s",
                content
            );
            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("bounded-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            flushUi();

            assertEquals(List.of("reset", "update:i:s:" + content), fixture.view.timeline);
            assertFalse(fixture.logger.warnings.stream()
                .anyMatch(message -> message.contains("session-load replay events")));
        }
    }

    @Test
    void pendingLoadLatchesOneByteOverTextLimitAndDropsLaterEvents() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            set(controller, "client", source);
            final Object load = beginPendingLoad(controller, source, "bounded-session");
            final String exact = "x".repeat(
                (int) TurboismWithFxController.MAX_PENDING_LOAD_TEXT_BYTES
            );

            controller.agentText(source, "bounded-session", exact);
            controller.agentText(source, "bounded-session", "x");
            controller.agentText(source, "bounded-session", "later");
            assertTrue(completePendingLoad(
                controller,
                load,
                new FxAcpSession("bounded-session", List.of()),
                () -> fixture.view.record("reset")
            ));
            flushUi();

            assertEquals(List.of("reset", "agent:" + exact), fixture.view.timeline);
            assertEquals(1L, fixture.logger.warnings.stream()
                .filter(message -> message.contains("session-load replay events"))
                .count());
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
    void savedBuiltInProviderIsAppliedBeforeTheSessionIsShown() throws Exception {
        final Fixture fixture = new Fixture();
        fixture.config.values.put("activeProviderProfile", FxProviderProfile.CODEX_ID);
        final ProviderConfigTransport transport = new ProviderConfigTransport();
        try (FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { });
             TurboismWithFxController controller = fixture.controller()) {
            final FxAcpSession updated = invokeApplySavedProvider(
                controller,
                client,
                new FxAcpSession("sess-1", List.of(
                    new FxAcpConfigOption(
                        "provider",
                        "Provider",
                        "gateway",
                        List.of(
                            new FxAcpConfigOption.Choice("gateway", "Gateway"),
                            new FxAcpConfigOption.Choice("codex", "Codex")
                        )
                    )
                ))
            );

            assertEquals("codex", updated.option("provider").currentValue());
            assertEquals("codex", transport.selectedProvider);
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
    void closeFlushesTheLatestVisibleProviderSettings() {
        final Fixture fixture = new Fixture();
        final FxProviderProfile profile = new FxProviderProfile(
            "saved-on-close",
            "Saved on close",
            FxProviderProfile.Kind.OPENAI_COMPATIBLE,
            "",
            "http://127.0.0.1:9000/v1",
            "",
            "",
            List.of()
        );
        final TurboismWithFxController controller = fixture.controller();

        controller.saveSettings("", false, "", new FxProviderConfiguration(
            profile.id(), List.of(profile), Map.of()
        ));
        controller.close();

        assertEquals(profile.id(), fixture.config.value("activeProviderProfile"));
        assertTrue(fixture.config.value("customProviderProfiles").contains(profile.id()));
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
    void matchingTerminationClosesTheDeferredProviderAdapter() throws Exception {
        final Fixture fixture = new Fixture();
        try (FxAcpClient source = inactiveClient();
             TurboismWithFxController controller = fixture.controller()) {
            final FxDeferredGatewayAdapter adapter = FxDeferredGatewayAdapter.start();
            set(controller, "client", source);
            set(controller, "session", new FxAcpSession("sess-1", List.of()));
            set(controller, "deferredGatewayAdapter", adapter);

            controller.terminated(source, "terminated");
            awaitSerial(controller);

            assertTrue(adapter.isClosed());
            assertTrue(atomicClient(controller) == null);
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

    private static FxAcpSession configuredSession(final String sessionId) {
        return new FxAcpSession(sessionId, List.of(
            new FxAcpConfigOption(
                "provider",
                "Provider",
                "gateway",
                List.of(new FxAcpConfigOption.Choice("gateway", "Gateway"))
            ),
            new FxAcpConfigOption(
                "model",
                "Model",
                "vendor/model",
                List.of(new FxAcpConfigOption.Choice("vendor/model", "vendor/model"))
            )
        ));
    }

    private FxAcpClient inactiveClient() throws java.io.IOException {
        return new FxAcpClient(new CapturingTransport(), new FxAcpListener() { });
    }

    private static FxAcpSession invokeApplySavedProvider(
        final TurboismWithFxController controller,
        final FxAcpClient client,
        final FxAcpSession session
    ) throws Exception {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "applySavedProvider", FxAcpClient.class, FxAcpSession.class
        );
        method.setAccessible(true);
        try {
            return (FxAcpSession) method.invoke(controller, client, session);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof Exception exception) throw exception;
            if (cause instanceof Error error) throw error;
            throw failure;
        }
    }

    private static void invokeActivateSession(
        final TurboismWithFxController controller,
        final FxAcpSession session
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "activateSession", FxAcpSession.class
        );
        method.setAccessible(true);
        method.invoke(controller, session);
    }

    private static Object beginPendingLoad(
        final TurboismWithFxController controller,
        final FxAcpClient source,
        final String sessionId
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "beginLoadTransaction", FxAcpClient.class, String.class
        );
        method.setAccessible(true);
        return method.invoke(controller, source, sessionId);
    }

    private static boolean completePendingLoad(
        final TurboismWithFxController controller,
        final Object load,
        final FxAcpSession session,
        final Runnable reset
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "completeLoadTransaction", load.getClass(), FxAcpSession.class, Runnable.class
        );
        method.setAccessible(true);
        return (Boolean) method.invoke(controller, load, session, reset);
    }

    private static void selectSessionNow(
        final TurboismWithFxController controller,
        final String sessionId
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "selectSessionNow", String.class
        );
        method.setAccessible(true);
        try {
            method.invoke(controller, sessionId);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw failure;
        }
    }

    private static void discardPendingLoad(
        final TurboismWithFxController controller,
        final Object load
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Method method = controller.getClass().getDeclaredMethod(
            "discardLoadTransaction", load.getClass()
        );
        method.setAccessible(true);
        method.invoke(controller, load);
    }

    private static Object pendingLoad(final TurboismWithFxController controller)
        throws ReflectiveOperationException {
        final java.lang.reflect.Field field = controller.getClass().getDeclaredField("loadTransaction");
        field.setAccessible(true);
        return field.get(controller);
    }

    private static void flushUi() throws Exception {
        javax.swing.SwingUtilities.invokeAndWait(() -> { });
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

    private Path temporaryExecutable() throws java.io.IOException {
        final Path executable = temporaryDirectory.resolve("fx-test");
        java.nio.file.Files.writeString(executable, "test");
        return executable;
    }

    private static dev.turboism.sdk.mcp.McpHttpConnection testMcpConnection() {
        return new dev.turboism.sdk.mcp.McpHttpConnection(
            java.net.URI.create("http://127.0.0.1:41234/mcp"),
            "2025-06-18",
            "Bearer test"
        );
    }

    private static void setCapabilities(
        final FxAcpClient client,
        final FxAcpClient.FxAcpCapabilities capabilities
    ) throws ReflectiveOperationException {
        final java.lang.reflect.Field field = FxAcpClient.class.getDeclaredField("capabilities");
        field.setAccessible(true);
        field.set(client, capabilities);
    }

    private static void setCapabilitiesUnchecked(
        final FxAcpClient client,
        final FxAcpClient.FxAcpCapabilities capabilities
    ) {
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
        private Optional<dev.turboism.sdk.mcp.McpHttpConnection> mcpConnection = Optional.empty();

        private TurboismWithFxController controller() {
            return controller(FxAcpClient::start);
        }

        private TurboismWithFxController controller(
            final TurboismWithFxController.ClientStarter clientStarter
        ) {
            return new TurboismWithFxController(
                context(), new FxPluginSettings(config, logger), view, clientStarter
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
                    case "mcpConnections" -> new McpConnectionService() {
                        @Override public Optional<dev.turboism.sdk.mcp.McpHttpConnection> current() {
                            return mcpConnection;
                        }
                        @Override public Registration publish(
                            final dev.turboism.sdk.mcp.McpHttpConnection connection
                        ) { throw new UnsupportedOperationException("not used"); }
                    };
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
        private final List<String> timeline = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.concurrent.atomic.AtomicInteger permissionRequests =
            new java.util.concurrent.atomic.AtomicInteger();
        private volatile java.util.concurrent.CountDownLatch permissionEntered;
        private volatile java.util.concurrent.CountDownLatch releasePermission;
        private volatile FxAcpListener.PermissionDecision permissionDecision =
            FxAcpListener.PermissionDecision.CANCELLED;

        private void record(final String event) { timeline.add(event); }

        @Override public void showConnecting(final boolean compatibilityMode) { }
        @Override public void showConnected(
            final List<FxAcpConfigOption> options,
            final boolean durableSessionsAvailable
        ) { record("connected"); }
        @Override public void showConfigOptions(final List<FxAcpConfigOption> options) {
            record("config");
        }
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
        @Override public void clearTranscript() { record("clear"); }
        @Override public void showPrompting() { prompting.set(true); }
        @Override public void showPromptComplete(final String stopReason) { }
        @Override public void showFailure(final String localizationKey) { failures.add(localizationKey); }
        @Override public void showSessionFailure(final String localizationKey) { failures.add(localizationKey); }
        @Override public void showSettingsSaved() { }
        @Override public void appendUser(final String text) { userMessages.add(text); }
        @Override public void appendAgent(final String text) {
            agentMessages.add(text);
            record("agent:" + text);
        }
        @Override public void appendThinking(final String text) {
            thinkingMessages.add(text);
            record("thought:" + text);
        }
        @Override public void appendTool(
            final String toolCallId,
            final String title,
            final String kind,
            final String status
        ) {
            toolCalls.add(List.of(toolCallId, title, kind, status));
            record("tool:" + toolCallId + ":" + title + ":" + kind + ":" + status);
        }
        @Override public void updateTool(
            final String toolCallId,
            final String status,
            final String content
        ) { record("update:" + toolCallId + ":" + status + ":" + content); }
        @Override public FxAcpListener.PermissionDecision requestPermission(
            final FxAcpListener.PermissionRequest request
        ) {
            permissionRequests.incrementAndGet();
            final java.util.concurrent.CountDownLatch entered = permissionEntered;
            if (entered != null) entered.countDown();
            final java.util.concurrent.CountDownLatch release = releasePermission;
            if (release != null) {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return FxAcpListener.PermissionDecision.CANCELLED;
                }
            }
            return permissionDecision;
        }

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

        private void awaitTimeline(final String event) throws InterruptedException {
            for (int attempt = 0; !timeline.contains(event) && attempt < 2000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(timeline.contains(event), "controller did not deliver " + event);
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

    private static final class BlockingLoadTransport implements FxAcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;
        private final java.util.concurrent.CountDownLatch loadStarted =
            new java.util.concurrent.CountDownLatch(1);
        private final java.util.concurrent.CountDownLatch releaseLoad =
            new java.util.concurrent.CountDownLatch(1);
        private volatile boolean alive = true;

        private BlockingLoadTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override public java.io.InputStream stdout() { return clientStdout; }
        @Override public java.io.InputStream stderr() { return stderr; }
        @Override public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                @Override public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void terminate(final Duration grace) { close(); }
        @Override public void close() {
            alive = false;
            releaseLoad.countDown();
            try { serverStdout.close(); } catch (java.io.IOException ignored) { }
            try { serverStderr.close(); } catch (java.io.IOException ignored) { }
        }

        private void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(dev.turboism.protocol.json.StrictJson.parse(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!"session/load".equals(request.get("method"))) return;
            loadStarted.countDown();
            try {
                releaseLoad.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class ReplayLoadTransport implements FxAcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;
        private final String sessionId;
        private volatile boolean alive = true;

        private ReplayLoadTransport(final String sessionId) throws java.io.IOException {
            this.sessionId = sessionId;
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override public java.io.InputStream stdout() { return clientStdout; }
        @Override public java.io.InputStream stderr() { return stderr; }
        @Override public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                @Override public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void terminate(final Duration grace) { close(); }
        @Override public void close() {
            alive = false;
            try { serverStdout.close(); } catch (java.io.IOException ignored) { }
            try { serverStderr.close(); } catch (java.io.IOException ignored) { }
        }

        private void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(dev.turboism.protocol.json.StrictJson.parse(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!"session/load".equals(request.get("method"))) return;
            final Map<String, Object> update = new LinkedHashMap<>();
            update.put("jsonrpc", "2.0");
            update.put("method", "session/update");
            update.put("params", Map.of(
                "sessionId", sessionId,
                "update", Map.of(
                    "sessionUpdate", "agent_message_chunk",
                    "content", Map.of("type", "text", "text", "restored")
                )
            ));
            serverStdout.write((dev.turboism.protocol.json.StrictJson.stringify(update) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", request.get("id"));
            response.put("result", Map.of("configOptions", List.of()));
            serverStdout.write((dev.turboism.protocol.json.StrictJson.stringify(response) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverStdout.flush();
        }
    }

    private static final class PermissionFailingLoadTransport implements FxAcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;
        private final java.util.concurrent.atomic.AtomicReference<FxAcpListener.PermissionDecision>
            decision = new java.util.concurrent.atomic.AtomicReference<>();
        private volatile FxAcpClient source;
        private volatile FxAcpListener listener;
        private volatile boolean alive = true;

        private PermissionFailingLoadTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override public java.io.InputStream stdout() { return clientStdout; }
        @Override public java.io.InputStream stderr() { return stderr; }
        @Override public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
                @Override public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        handle(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }
        @Override public boolean isAlive() { return alive; }
        @Override public void terminate(final Duration grace) { close(); }
        @Override public void close() {
            alive = false;
            try { serverStdout.close(); } catch (java.io.IOException ignored) { }
            try { serverStderr.close(); } catch (java.io.IOException ignored) { }
        }

        private void handle(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(dev.turboism.protocol.json.StrictJson.parse(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            ));
            if (!"session/load".equals(request.get("method"))) return;
            final Map<String, Object> params = object(request.get("params"));
            final FxAcpListener.PermissionDecision permission = listener.permission(
                source,
                (String) params.get("sessionId"),
                new FxAcpListener.PermissionRequest("Resume", "edit", "call-1", "{}")
            );
            decision.set(permission);
            final Map<String, Object> response = new LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", request.get("id"));
            response.put("error", Map.of("code", -32000L, "message", "permission cancelled"));
            serverStdout.write((dev.turboism.protocol.json.StrictJson.stringify(response) + "\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverStdout.flush();
        }
    }

    private static final class ProviderConfigTransport implements FxAcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.PipedInputStream stderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;
        private volatile String selectedProvider;

        private ProviderConfigTransport() throws java.io.IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            serverStderr = new java.io.PipedOutputStream(stderr);
        }

        @Override public java.io.InputStream stdout() { return clientStdout; }
        @Override public java.io.InputStream stderr() { return stderr; }
        @Override public java.io.OutputStream stdin() {
            return new java.io.OutputStream() {
                private final java.io.ByteArrayOutputStream line =
                    new java.io.ByteArrayOutputStream();
                @Override public void write(final int value) throws java.io.IOException {
                    if (value == '\n') {
                        respond(line.toString(java.nio.charset.StandardCharsets.UTF_8));
                        line.reset();
                    } else {
                        line.write(value);
                    }
                }
            };
        }
        @Override public boolean isAlive() { return true; }
        @Override public void terminate(final Duration grace) { close(); }
        @Override public void close() {
            try { serverStdout.close(); } catch (java.io.IOException ignored) { }
            try { serverStderr.close(); } catch (java.io.IOException ignored) { }
        }

        private void respond(final String json) throws java.io.IOException {
            final Map<String, Object> request = object(
                dev.turboism.protocol.json.StrictJson.parse(
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                )
            );
            final Map<String, Object> params = object(request.get("params"));
            selectedProvider = (String) params.get("value");
            final Map<String, Object> option = Map.of(
                "type", "select",
                "id", "provider",
                "name", "Provider",
                "currentValue", selectedProvider,
                "options", List.of(
                    Map.of("value", "gateway", "name", "Gateway"),
                    Map.of("value", "codex", "name", "Codex")
                )
            );
            final Map<String, Object> response = Map.of(
                "jsonrpc", "2.0",
                "id", request.get("id"),
                "result", Map.of("configOptions", List.of(option))
            );
            serverStdout.write((
                dev.turboism.protocol.json.StrictJson.stringify(response) + "\n"
            ).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            serverStdout.flush();
        }
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

        private boolean hasRequest() {
            return stdin.size() > 0;
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
