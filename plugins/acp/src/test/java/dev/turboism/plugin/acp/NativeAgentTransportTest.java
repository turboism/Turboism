package dev.turboism.plugin.acp;

import static dev.turboism.plugin.acp.NativeAgentTransport.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpHttpConnection;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class NativeAgentTransportTest {
    @TempDir
    Path directory;

    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void acceptanceDoesNotCompletePromptAndStreamingIsNotDuplicated(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            var response = fixture.client.prompt(fixture.session.sessionId(), "hello");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            assertFalse(response.isDone(), "Native command ACK must not complete a conversation turn");
            fixture.raw.delta("answer");
            fixture.raw.full("answer");
            fixture.raw.finish(false);
            assertEquals("end_turn", response.get(2, TimeUnit.SECONDS));
            assertEquals("answer", fixture.text.toString());
            assertTrue(fixture.session.durableSessionsAvailable());
            assertFalse(fixture.session.capabilities().listSessions());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void runtimeErrorsReachPromptCaller(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            var response = fixture.client.prompt(fixture.session.sessionId(), "hello");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            fixture.raw.finish(true);
            var error = assertThrows(ExecutionException.class, () -> response.get(2, TimeUnit.SECONDS));
            assertTrue(AcpClient.failureDetails(error).contains("provider failed"));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void cancellationWaitsForAuthoritativeEnd(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            var response = fixture.client.prompt(fixture.session.sessionId(), "hello");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            fixture.client.cancel(fixture.session.sessionId());
            assertTrue(fixture.raw.aborted.await(2, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            fixture.raw.finish(false);
            assertEquals("cancelled", response.get(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void permissionsUseExistingDecisionDialogAndRejectByDefault(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            fixture.client.prompt(fixture.session.sessionId(), "request permission");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            fixture.raw.askPermission();
            var decision = fixture.raw.permission.get(2, TimeUnit.SECONDS);
            var summary = fixture.permissionRequest.get(2, TimeUnit.SECONDS).details();
            assertFalse(summary.contains("<unavailable>"));
            if (protocol == AgentProtocol.PI) assertEquals("Run tool?", summary);
            switch (protocol) {
                case CODEX ->
                    assertEquals("decline", object(decision.get("result")).get("decision"));
                case CLAUDE ->
                    assertEquals(
                            "deny",
                            object(object(decision.get("response")).get("response"))
                                    .get("behavior"));
                case PI -> assertEquals(false, decision.get("confirmed"));
                default -> fail();
            }
        }
    }

    @Test
    void codexLateCompletionAndOutputCannotEndOrPolluteNextTurn() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            var first = fixture.client.prompt(fixture.session.sessionId(), "first");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.finish(false);
            assertEquals("end_turn", first.get(2, TimeUnit.SECONDS));
            var second = fixture.client.prompt(fixture.session.sessionId(), "second");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.codexEvent("turn/started", "thread-1", "turn-1", Map.of());
            fixture.raw.codexEvent(
                    "item/agentMessage/delta", "thread-1", "turn-1", Map.of("itemId", "old", "delta", "old"));
            fixture.raw.codexEvent("turn/completed", "thread-1", "turn-1", Map.of("status", "completed"));
            fixture.raw.delta("current");
            assertEquals("current", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertFalse(second.isDone(), "A late completion must not terminate another turn");
            fixture.client.cancel(fixture.session.sessionId());
            assertEquals(
                    "turn-2", fixture.raw.interrupts.poll(2, TimeUnit.SECONDS).get("turnId"));
            fixture.raw.finish(false);
            assertEquals("cancelled", second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void codexCancellationBeforeStartAckWaitsForItsAuthoritativeTurnId() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            fixture.raw.deferCodexStart = true;
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "start slowly");
            var request = fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            fixture.client.cancel(fixture.session.sessionId());
            fixture.raw.codexEvent("turn/started", "thread-1", "stale-turn", Map.of());
            fixture.raw.emit(Map.of(
                    "id",
                    "stale-approval",
                    "method",
                    "item/commandExecution/requestApproval",
                    "params",
                    Map.of("threadId", "thread-1", "turnId", "stale-turn", "itemId", "old", "command", "old")));
            fixture.raw.emit(Map.of("id", request.get("id"), "result", Map.of("turn", Map.of("id", "turn-1"))));
            assertEquals(
                    "turn-1", fixture.raw.interrupts.poll(2, TimeUnit.SECONDS).get("turnId"));
            assertFalse(prompt.isDone(), "Interrupt ACK is not a terminal turn event");
            assertEquals(
                    "decline",
                    object(fixture.raw.permission.get(2, TimeUnit.SECONDS).get("result"))
                            .get("decision"));
            assertFalse(fixture.permissionRequest.isDone(), "Old approvals must not reach the permission UI");
            fixture.raw.finish(false);
            assertEquals("cancelled", prompt.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void codexRejectsForeignThreadApprovalWithoutDisplayingIt() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "active");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.emit(Map.of(
                    "id",
                    "foreign",
                    "method",
                    "item/commandExecution/requestApproval",
                    "params",
                    Map.of(
                            "threadId",
                            "foreign-thread",
                            "turnId",
                            "turn-1",
                            "itemId",
                            "foreign",
                            "command",
                            "foreign")));
            assertEquals(
                    "decline",
                    object(fixture.raw.permission.get(2, TimeUnit.SECONDS).get("result"))
                            .get("decision"));
            assertFalse(fixture.permissionRequest.isDone());
            fixture.raw.finish(false);
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void codexReplaysEventsBeforeStartAckInOrderWithoutDuplicateText() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            fixture.raw.deferCodexStart = true;
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "slow ack");
            var request = fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            fixture.raw.delta("buffered");
            fixture.raw.full("buffered");
            fixture.raw.finish(false);
            fixture.raw.emit(Map.of("id", request.get("id"), "result", Map.of("turn", Map.of("id", "turn-1"))));
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
            assertEquals("buffered", fixture.text.toString());
        }
    }

    @Test
    void codexLateAllowAfterTurnCompletionOrCancellationIsDenied() throws Exception {
        for (boolean cancel : List.of(false, true)) {
            try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
                fixture.connect();
                fixture.permissionAnswer = new CompletableFuture<>();
                var first = fixture.client.prompt(fixture.session.sessionId(), "permission");
                assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
                fixture.raw.askPermission();
                fixture.permissionRequest.get(2, TimeUnit.SECONDS);
                if (cancel) {
                    fixture.client.cancel(fixture.session.sessionId());
                    assertNotNull(fixture.raw.interrupts.poll(2, TimeUnit.SECONDS));
                } else {
                    fixture.raw.finish(false);
                    assertEquals("end_turn", first.get(2, TimeUnit.SECONDS));
                    fixture.client.prompt(fixture.session.sessionId(), "next turn");
                    assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
                }
                fixture.permissionAnswer.complete(AcpListener.PermissionDecision.ALLOW_ONCE);
                assertEquals(
                        "decline",
                        object(fixture.raw.permission.get(2, TimeUnit.SECONDS).get("result"))
                                .get("decision"));
                fixture.raw.finish(false);
                if (cancel) assertEquals("cancelled", first.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void codexMissingStartIdentityRequiresReconnect() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            fixture.raw.deferCodexStart = true;
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "unknown identity");
            var request = fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            fixture.raw.emit(Map.of("id", request.get("id"), "result", Map.of("turn", Map.of())));
            var error = assertThrows(ExecutionException.class, () -> prompt.get(2, TimeUnit.SECONDS));
            assertTrue(AcpClient.failureDetails(error).contains("reconnect"));
            var next = fixture.client.prompt(fixture.session.sessionId(), "do not replay");
            assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
            assertTrue(fixture.raw.turnStarts.isEmpty());
        }
    }

    @Test
    void codexPendingIdentityBudgetsFailClosed() throws Exception {
        for (boolean bytes : List.of(false, true)) {
            try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
                fixture.connect();
                fixture.raw.deferCodexStart = true;
                var prompt = fixture.client.prompt(fixture.session.sessionId(), "bounded start");
                assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
                if (bytes) fixture.raw.delta("x".repeat(NativeCodexProvider.MAX_PENDING_START_BYTES));
                else for (int i = 0; i <= NativeCodexProvider.MAX_PENDING_START_EVENTS; i++) fixture.raw.delta("x");
                var error = assertThrows(ExecutionException.class, () -> prompt.get(2, TimeUnit.SECONDS));
                assertTrue(AcpClient.failureDetails(error).contains("budget"));
                assertTrue(fixture.text.isEmpty());
            }
        }
    }

    @Test
    void codexLateInterruptFailureCannotFailNextTurnAndCancelIsSentOnce() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            fixture.raw.deferCodexInterrupt = true;
            var first = fixture.client.prompt(fixture.session.sessionId(), "first");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.client.cancel(fixture.session.sessionId());
            var interrupt = fixture.raw.interruptRequests.poll(2, TimeUnit.SECONDS);
            assertNotNull(interrupt);
            fixture.client.cancel(fixture.session.sessionId());
            fixture.raw.finish(false);
            assertEquals("cancelled", first.get(2, TimeUnit.SECONDS));
            var second = fixture.client.prompt(fixture.session.sessionId(), "next");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.emit(Map.of(
                    "id", interrupt.get("id"), "error", Map.of("code", -32000, "message", "old interrupt failed")));
            fixture.raw.delta("still active");
            assertEquals("still active", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            assertTrue(fixture.raw.interruptRequests.isEmpty(), "Repeated cancel must not send another interrupt");
            fixture.raw.finish(false);
            assertEquals("end_turn", second.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void codexUnownedErrorsAndItemsCannotPolluteActiveTurn() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "current");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.codexEvent(
                    "error", "thread-1", "old", Map.of("willRetry", false, "error", Map.of("message", "old failure")));
            fixture.raw.codexEvent(
                    "item/completed",
                    "thread-1",
                    "old",
                    Map.of("item", Map.of("id", "stale", "type", "mcpToolCall", "result", "old tool result")));
            fixture.raw.emit(Map.of(
                    "method", "item/agentMessage/delta", "params", Map.of("threadId", "thread-1", "delta", "unowned")));
            fixture.raw.codexEvent(
                    "error", "thread-1", "turn-1", Map.of("willRetry", true, "error", Map.of("message", "retrying")));
            fixture.raw.delta("current");
            assertEquals("current", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertFalse(prompt.isDone());
            assertTrue(fixture.toolOutput.isEmpty());
            fixture.raw.finish(false);
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void codexFailedStartOrInterruptRequiresReconciliationWithoutReplay() throws Exception {
        for (boolean interrupt : List.of(false, true)) {
            try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
                fixture.connect();
                fixture.raw.deferCodexStart = !interrupt;
                fixture.raw.deferCodexInterrupt = interrupt;
                var prompt = fixture.client.prompt(fixture.session.sessionId(), "uncertain request");
                var start = fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS);
                assertNotNull(start);
                Map<String, Object> request = start;
                if (interrupt) {
                    fixture.client.cancel(fixture.session.sessionId());
                    request = fixture.raw.interruptRequests.poll(2, TimeUnit.SECONDS);
                    assertNotNull(request);
                }
                fixture.raw.emit(
                        Map.of("id", request.get("id"), "error", Map.of("code", -32000, "message", "request failed")));
                var error = assertThrows(ExecutionException.class, () -> prompt.get(2, TimeUnit.SECONDS));
                assertTrue(AcpClient.failureDetails(error).contains("reconcile host operations"));
                var next = fixture.client.prompt(fixture.session.sessionId(), "do not replay");
                assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
                assertTrue(fixture.raw.turnStarts.isEmpty());
            }
        }
    }

    @Test
    void codexUnknownTerminalStatusDoesNotClaimSuccess() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.CODEX)) {
            fixture.connect();
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "current");
            assertNotNull(fixture.raw.turnStarts.poll(2, TimeUnit.SECONDS));
            fixture.raw.codexEvent("turn/completed", "thread-1", "turn-1", Map.of("status", "future-status"));
            var error = assertThrows(ExecutionException.class, () -> prompt.get(2, TimeUnit.SECONDS));
            assertTrue(AcpClient.failureDetails(error).contains("unknown terminal status"));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void nativeLaunchAndMcpAreBundledAndTemporaryFilesAreRemoved(AgentProtocol protocol) throws Exception {
        Fixture fixture = new Fixture(protocol);
        fixture.connect();
        var command = fixture.spec.get().command();
        assertFalse(command.stream().anyMatch(arg -> arg.endsWith("-acp")));
        if (protocol == AgentProtocol.CODEX) {
            assertEquals(List.of("original", "app-server"), command);
            assertTrue(fixture.raw.threadParams.containsKey("config"));
            assertFalse(fixture.raw.threadParams.containsKey("ephemeral"));
        } else {
            int config = command.indexOf("--mcp-config");
            Path configFile = protocol == AgentProtocol.PI
                    ? Path.of(fixture.spec.get().environment().get("TURBOISM_PI_MCP_CONFIG"))
                    : Path.of(command.get(config + 1));
            assertTrue(Files.readString(configFile).contains("127.0.0.1:43123"));
            if (protocol == AgentProtocol.PI)
                assertTrue(Files.exists(Path.of(command.get(command.indexOf("--extension") + 1))));
        }
        fixture.close();
        assertFalse(fixture.raw.isAlive());
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith("native-agent-")));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void resumeUsesTheSameNativeContextAndReplacesPreviousProcess(AgentProtocol protocol) throws Exception {
        final var processes = new java.util.concurrent.CopyOnWriteArrayList<FakeCli>();
        final var specs = new java.util.concurrent.CopyOnWriteArrayList<AgentLaunchSpec>();
        final var transport = new NativeAgentTransport(
                new AgentLaunchSpec(List.of("original"), directory, Map.of(), protocol), launch -> {
                    final var process = new FakeCli(protocol);
                    processes.add(process);
                    specs.add(launch);
                    return process;
                });
        try (AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(TIMEOUT);
            final var mcp = new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25");
            final var first = client.newSession(directory, mcp, TIMEOUT);
            final var restored = client.resumeSession(first.sessionId(), directory, mcp, TIMEOUT);
            assertEquals(first.sessionId(), restored.sessionId());
            assertEquals(2, processes.size());
            assertFalse(processes.get(0).isAlive());
            switch (protocol) {
                case CODEX ->
                    assertEquals(
                            first.sessionId(), processes.get(1).threadParams.get("threadId"));
                case CLAUDE -> assertTrue(specs.get(1).command().contains("--resume"));
                case PI ->
                    assertEquals(
                            specs.get(0).command().get(specs.get(0).command().indexOf("--session") + 1),
                            specs.get(1).command().get(specs.get(1).command().indexOf("--session") + 1));
                default -> fail();
            }
        }
        assertFalse(processes.get(1).isAlive());
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void toolImagesReachImageListenerWithoutBase64ChatText(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            var response = fixture.client.prompt(fixture.session.sessionId(), "render");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            var pixels = new java.awt.image.BufferedImage(2, 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            pixels.setRGB(0, 0, 0xffff0000);
            var bytes = new ByteArrayOutputStream();
            javax.imageio.ImageIO.write(pixels, "png", bytes);
            String encoded = Base64.getEncoder().encodeToString(bytes.toByteArray());
            fixture.raw.toolImage(encoded);
            fixture.raw.finish(false);
            assertEquals("end_turn", response.get(2, TimeUnit.SECONDS));
            var image = fixture.images.get(2, TimeUnit.SECONDS);
            assertEquals(encoded, image.data());
            assertTrue(image.name().contains("capture=native-capture-7"), image.name());
            assertTrue(image.name().contains("doc=native-doc"), image.name());
            assertTrue(image.name().contains("revision=7"), image.name());
            assertNotNull(image.preview(), "The UI must be able to decode the returned pixels");
            assertTrue(fixture.toolOutput.toString().contains("revision"));
            assertFalse(fixture.toolOutput.toString().contains(encoded));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CLAUDE", "PI"})
    void idlessNativeLatePermissionCannotAuthorizeAfterCompletionOrCancel(AgentProtocol protocol) throws Exception {
        for (boolean cancel : List.of(false, true)) {
            try (Fixture fixture = new Fixture(protocol)) {
                fixture.connect();
                fixture.permissionAnswer = new CompletableFuture<>();
                var first = fixture.client.prompt(fixture.session.sessionId(), "permission");
                assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
                fixture.raw.askPermission();
                fixture.permissionRequest.get(2, TimeUnit.SECONDS);
                if (cancel) {
                    fixture.client.cancel(fixture.session.sessionId());
                    assertTrue(fixture.raw.aborted.await(2, TimeUnit.SECONDS));
                } else {
                    fixture.raw.finish(false);
                    assertEquals("end_turn", first.get(2, TimeUnit.SECONDS));
                    fixture.client.prompt(fixture.session.sessionId(), "next");
                    assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
                    assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
                }
                fixture.permissionAnswer.complete(AcpListener.PermissionDecision.ALLOW_ONCE);
                var decision = fixture.raw.permission.get(2, TimeUnit.SECONDS);
                if (protocol == AgentProtocol.PI) assertEquals(false, decision.get("confirmed"));
                else
                    assertEquals(
                            "deny",
                            object(object(decision.get("response")).get("response"))
                                    .get("behavior"));
                fixture.raw.finish(false);
                if (cancel) assertEquals("cancelled", first.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void piLateHandledAckCannotFinishAnotherPrompt() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.PI)) {
            fixture.connect();
            fixture.raw.deferPiPrompt = true;
            var first = fixture.client.prompt(fixture.session.sessionId(), "first");
            var request = fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS);
            assertNotNull(request);
            fixture.raw.finish(false);
            assertEquals("end_turn", first.get(2, TimeUnit.SECONDS));
            var second = fixture.client.prompt(fixture.session.sessionId(), "second");
            var secondRequest = fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS);
            assertNotNull(secondRequest);
            fixture.raw.emit(Map.of(
                    "id",
                    request.get("id"),
                    "type",
                    "response",
                    "success",
                    true,
                    "data",
                    Map.of("disposition", "handled")));
            fixture.raw.delta("active");
            assertEquals("active", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            fixture.raw.emit(Map.of(
                    "id",
                    secondRequest.get("id"),
                    "type",
                    "response",
                    "success",
                    true,
                    "data",
                    Map.of("disposition", "started")));
            fixture.raw.finish(false);
            assertEquals("end_turn", second.get(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CLAUDE", "PI"})
    void idlessNativeLateCancelFailureCannotAffectNextPrompt(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            fixture.raw.deferNativeCancel = true;
            var first = fixture.client.prompt(fixture.session.sessionId(), "first");
            assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
            fixture.client.cancel(fixture.session.sessionId());
            var cancel = fixture.raw.cancelRequests.poll(2, TimeUnit.SECONDS);
            assertNotNull(cancel);
            fixture.client.cancel(fixture.session.sessionId());
            fixture.raw.finish(false);
            assertEquals("cancelled", first.get(2, TimeUnit.SECONDS));
            var second = fixture.client.prompt(fixture.session.sessionId(), "next");
            assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
            fixture.raw.failCancel(cancel);
            fixture.raw.delta("current");
            assertEquals("current", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertFalse(second.isDone());
            assertTrue(fixture.raw.cancelRequests.isEmpty());
            fixture.raw.finish(false);
            assertEquals("end_turn", second.get(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CLAUDE", "PI"})
    void idlessNativeCancelFailureRequiresReconnectWithoutReplay(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            fixture.raw.deferNativeCancel = true;
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "current");
            assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
            fixture.client.cancel(fixture.session.sessionId());
            var cancel = fixture.raw.cancelRequests.poll(2, TimeUnit.SECONDS);
            assertNotNull(cancel);
            fixture.raw.failCancel(cancel);
            var failure = assertThrows(ExecutionException.class, () -> prompt.get(2, TimeUnit.SECONDS));
            assertTrue(AcpClient.failureDetails(failure).contains("reconcile host operations"));
            var next = fixture.client.prompt(fixture.session.sessionId(), "no replay");
            assertThrows(ExecutionException.class, () -> next.get(2, TimeUnit.SECONDS));
            assertTrue(fixture.raw.promptRequests.isEmpty());
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CLAUDE", "PI"})
    void idlessNativeIdleOutputAndPermissionAreNotForwarded(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            fixture.client.cancel(fixture.session.sessionId());
            fixture.raw.delta("idle");
            fixture.raw.full("idle");
            fixture.raw.askPermission();
            var decision = fixture.raw.permission.get(2, TimeUnit.SECONDS);
            if (protocol == AgentProtocol.PI) assertEquals(false, decision.get("confirmed"));
            else
                assertEquals(
                        "deny",
                        object(object(decision.get("response")).get("response")).get("behavior"));
            assertFalse(fixture.permissionRequest.isDone());
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "current");
            assertNotNull(fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS));
            fixture.raw.delta("current");
            assertEquals("current", fixture.chunks.poll(2, TimeUnit.SECONDS));
            assertTrue(fixture.raw.cancelRequests.isEmpty(), "Idle cancel must not target the next prompt");
            fixture.raw.finish(false);
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProtocol.class,
            names = {"CODEX", "CLAUDE", "PI"})
    void currentNativePermissionCanStillBeAllowed(AgentProtocol protocol) throws Exception {
        try (Fixture fixture = new Fixture(protocol)) {
            fixture.connect();
            fixture.permissionAnswer = CompletableFuture.completedFuture(AcpListener.PermissionDecision.ALLOW_ONCE);
            var prompt = fixture.client.prompt(fixture.session.sessionId(), "allowed");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            fixture.raw.askPermission();
            var decision = fixture.raw.permission.get(2, TimeUnit.SECONDS);
            switch (protocol) {
                case CODEX ->
                    assertEquals("accept", object(decision.get("result")).get("decision"));
                case CLAUDE ->
                    assertEquals(
                            "allow",
                            object(object(decision.get("response")).get("response"))
                                    .get("behavior"));
                case PI -> assertEquals(true, decision.get("confirmed"));
                default -> fail();
            }
            fixture.raw.finish(false);
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void piCurrentHandledPromptDoesNotWaitForAgentTerminal() throws Exception {
        for (var data : List.of(Map.of("disposition", "handled"), Map.of("agentInvoked", false))) {
            try (Fixture fixture = new Fixture(AgentProtocol.PI)) {
                fixture.connect();
                fixture.raw.deferPiPrompt = true;
                var prompt = fixture.client.prompt(fixture.session.sessionId(), "handled by extension");
                var request = fixture.raw.promptRequests.poll(2, TimeUnit.SECONDS);
                assertNotNull(request);
                fixture.raw.emit(Map.of("id", request.get("id"), "type", "response", "success", true, "data", data));
                assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void piRequiresSettledBoundaryAndRejectsImagesForUnverifiedModel() throws Exception {
        try (Fixture fixture = new Fixture(AgentProtocol.PI)) {
            fixture.connect();
            var image = AcpAttachment.receivedImage("image/png", "cGl4ZWxz");
            var rejected = fixture.client.prompt(fixture.session.sessionId(), "inspect", List.of(image));
            var failure = assertThrows(ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
            assertTrue(AcpClient.failureDetails(failure).contains("vision model"));
            assertEquals(1L, fixture.raw.accepted.getCount(), "Rejected image must not be dispatched");
            var response = fixture.client.prompt(fixture.session.sessionId(), "text only");
            assertTrue(fixture.raw.accepted.await(2, TimeUnit.SECONDS));
            fixture.raw.emit(Map.of("type", "agent_end", "willRetry", false));
            // A subsequent RPC ACK is a deterministic reader barrier, not a turn terminal.
            fixture.client.cancel(fixture.session.sessionId());
            assertTrue(fixture.raw.aborted.await(2, TimeUnit.SECONDS));
            assertFalse(response.isDone());
            fixture.raw.emit(Map.of("type", "agent_settled"));
            assertEquals("cancelled", response.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void nativePermissionSlotsRetireWithoutAnyClientReply() throws Exception {
        try (var transport = new NativeAgentTransport(
                        new AgentLaunchSpec(List.of("unused"), directory, Map.of(), AgentProtocol.PI));
                var output = new BufferedReader(new InputStreamReader(transport.stdout(), StandardCharsets.UTF_8))) {
            transport.sessionId("local-test-session");
            for (int i = 0; i < 12; i++) {
                var scope = new CompletableFuture<String>();
                var replies = new java.util.concurrent.CopyOnWriteArrayList<Boolean>();
                transport.permission(scope, "tool", "edit", "call-" + i, Map.of(), replies::add);
                assertTrue(replies.isEmpty(), "A retired prompt must not consume one of the eight slots");
                var request = object(Json.parseObject(output.readLine().getBytes(StandardCharsets.UTF_8)));
                assertEquals("session/request_permission", request.get("method"));
                if (i % 2 == 0) transport.cancelPermissions(scope);
                scope.complete("end_turn");
                transport.cancelPermissions(scope);
                assertEquals(List.of(false), replies, "Cancellation/terminal must answer exactly once");
            }
        }
    }

    @Test
    void protocolSelectionSupportsWindowsShimsAndAcpFallback() {
        for (String name : List.of("codex", "codex.exe", "codex.cmd", "codex.bat"))
            assertEquals(
                    AgentProtocol.CODEX,
                    AgentProtocol.forExecutable(AgentCatalog.profile("codex").orElseThrow(), Path.of(name)));
        assertEquals(
                AgentProtocol.ACP,
                AgentProtocol.forExecutable(AgentCatalog.profile("codex").orElseThrow(), Path.of("codex-acp.cmd")));
    }

    private final class Fixture implements AutoCloseable {
        final AtomicReference<AgentLaunchSpec> spec = new AtomicReference<>();
        final StringBuffer text = new StringBuffer();
        final BlockingQueue<String> chunks = new LinkedBlockingQueue<>();
        final StringBuffer toolOutput = new StringBuffer();
        final CompletableFuture<AcpAttachment> images = new CompletableFuture<>();
        final CompletableFuture<AcpListener.PermissionRequest> permissionRequest = new CompletableFuture<>();
        volatile CompletableFuture<AcpListener.PermissionDecision> permissionAnswer =
                CompletableFuture.completedFuture(AcpListener.PermissionDecision.CANCELLED);
        final FakeCli raw;
        final AcpClient client;
        AcpSession session;

        Fixture(AgentProtocol protocol) throws Exception {
            raw = new FakeCli(protocol);
            var transport = new NativeAgentTransport(
                    new AgentLaunchSpec(List.of("original"), directory, Map.of(), protocol), launch -> {
                        spec.set(launch);
                        return raw;
                    });
            client = new AcpClient(transport, new AcpListener() {
                @Override
                public void agentText(AcpClient source, String id, String value) {
                    text.append(value);
                    chunks.add(value);
                }

                @Override
                public void agentImage(AcpClient source, String id, AcpAttachment image) {
                    images.complete(image);
                }

                @Override
                public void toolCallUpdate(AcpClient source, String id, String callId, String status, String content) {
                    toolOutput.append(content);
                }

                @Override
                public PermissionDecision permission(AcpClient source, String id, PermissionRequest request) {
                    permissionRequest.complete(request);
                    try {
                        return permissionAnswer.get(2, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        return PermissionDecision.CANCELLED;
                    }
                }
            });
        }

        void connect() throws Exception {
            client.initialize(TIMEOUT);
            session = client.newSession(
                    directory, new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25"), TIMEOUT);
        }

        @Override
        public void close() {
            client.close();
            raw.close();
        }
    }

    private static final class FakeCli implements AcpTransport {
        final AgentProtocol protocol;
        final PipedInputStream output = new PipedInputStream(65536);
        final PipedOutputStream events = new PipedOutputStream(output);
        final PipedInputStream commands = new PipedInputStream(65536);
        final PipedOutputStream input = new PipedOutputStream(commands);
        final CountDownLatch accepted = new CountDownLatch(1), aborted = new CountDownLatch(1);
        final CompletableFuture<Map<String, Object>> permission = new CompletableFuture<>();
        final BlockingQueue<Map<String, Object>> turnStarts = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> interrupts = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> interruptRequests = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> promptRequests = new LinkedBlockingQueue<>();
        final BlockingQueue<Map<String, Object>> cancelRequests = new LinkedBlockingQueue<>();
        volatile boolean deferNativeCancel;
        volatile boolean deferPiPrompt;
        volatile boolean deferCodexInterrupt;
        volatile boolean deferCodexStart;
        volatile String codexTurnId = "turn-0";
        private int codexTurnSequence;
        Map<String, Object> threadParams = Map.of();
        volatile boolean alive = true;

        FakeCli(AgentProtocol protocol) throws IOException {
            this.protocol = protocol;
            Thread worker = new Thread(() -> {
                try (var reader = new BufferedReader(new InputStreamReader(commands, StandardCharsets.UTF_8))) {
                    for (String line; (line = reader.readLine()) != null; )
                        handle(object(Json.parseObject(line.getBytes(StandardCharsets.UTF_8))));
                } catch (Exception error) {
                    if (alive) permission.completeExceptionally(error);
                }
            });
            worker.setDaemon(true);
            worker.start();
        }

        synchronized void emit(Map<String, Object> message) throws IOException {
            events.write((Json.stringify(message) + "\n").getBytes(StandardCharsets.UTF_8));
            events.flush();
        }

        void handle(Map<String, Object> request) throws IOException {
            if (request.containsKey("result")
                    || "control_response".equals(request.get("type"))
                    || "extension_ui_response".equals(request.get("type"))) {
                permission.complete(request);
                return;
            }
            Object id = request.get("id");
            String method = text(request.get("method"));
            if (protocol == AgentProtocol.CODEX) {
                Map<String, Object> result = Map.of();
                switch (method) {
                    case "initialized" -> {
                        return;
                    }
                    case "thread/start", "thread/resume" -> {
                        threadParams = object(request.get("params"));
                        result = Map.of("thread", Map.of("id", "thread-1"), "model", "model-a");
                    }
                    case "model/list" ->
                        result = Map.of("data", List.of(Map.of("model", "model-a", "displayName", "Model A")));
                    case "turn/start" -> {
                        codexTurnId = "turn-" + ++codexTurnSequence;
                        turnStarts.add(request);
                        result = Map.of("turn", Map.of("id", codexTurnId));
                        accepted.countDown();
                        if (deferCodexStart) return;
                    }
                    case "turn/interrupt" -> {
                        interruptRequests.add(request);
                        interrupts.add(object(request.get("params")));
                        aborted.countDown();
                        if (deferCodexInterrupt) return;
                    }
                    default -> {}
                }
                emit(Map.of("id", id, "result", result));
            } else if (protocol == AgentProtocol.CLAUDE) {
                if ("control_request".equals(request.get("type"))) {
                    if ("interrupt".equals(object(request.get("request")).get("subtype"))) {
                        cancelRequests.add(request);
                        aborted.countDown();
                        if (deferNativeCancel) return;
                    }
                    emit(Map.of(
                            "type",
                            "control_response",
                            "response",
                            Map.of(
                                    "subtype",
                                    "success",
                                    "request_id",
                                    request.get("request_id"),
                                    "response",
                                    Map.of("models", List.of(Map.of("value", "model-a", "displayName", "Model A"))))));
                } else if ("user".equals(request.get("type"))) {
                    promptRequests.add(request);
                    accepted.countDown();
                }
            } else {
                String type = text(request.get("type"));
                Map<String, Object> data = Map.of();
                switch (type) {
                    case "get_state" -> {
                        emit(Map.of("type", "turboism_mcp_status", "ok", true));
                        data = Map.of("model", Map.of("provider", "local", "id", "model-a"));
                    }
                    case "get_commands" -> data = Map.of("commands", List.of(Map.of("name", "turboism-mcp-ready")));
                    case "get_available_models" ->
                        data = Map.of(
                                "models", List.of(Map.of("provider", "local", "id", "model-a", "name", "Model A")));
                    case "prompt" -> {
                        promptRequests.add(request);
                        data = Map.of("disposition", "started");
                        accepted.countDown();
                        if (deferPiPrompt) return;
                    }
                    case "abort" -> {
                        cancelRequests.add(request);
                        aborted.countDown();
                        if (deferNativeCancel) return;
                    }
                    default -> {}
                }
                emit(Map.of("id", id, "type", "response", "command", type, "success", true, "data", data));
            }
        }

        void failCancel(Map<String, Object> request) throws IOException {
            if (protocol == AgentProtocol.PI)
                emit(Map.of("id", request.get("id"), "type", "response", "success", false, "error", "abort failed"));
            else
                emit(Map.of(
                        "type",
                        "control_response",
                        "response",
                        Map.of(
                                "subtype",
                                "error",
                                "request_id",
                                request.get("request_id"),
                                "error",
                                "interrupt failed")));
        }

        void delta(String value) throws IOException {
            switch (protocol) {
                case CODEX ->
                    codexEvent(
                            "item/agentMessage/delta", "thread-1", codexTurnId, Map.of("itemId", "a1", "delta", value));
                case CLAUDE ->
                    emit(Map.of(
                            "type",
                            "stream_event",
                            "event",
                            Map.of(
                                    "type",
                                    "content_block_delta",
                                    "delta",
                                    Map.of("type", "text_delta", "text", value))));
                case PI ->
                    emit(Map.of(
                            "type",
                            "message_update",
                            "assistantMessageEvent",
                            Map.of("type", "text_delta", "delta", value)));
                default -> {}
            }
        }

        void full(String value) throws IOException {
            switch (protocol) {
                case CODEX ->
                    emit(Map.of(
                            "method",
                            "item/completed",
                            "params",
                            Map.of(
                                    "threadId",
                                    "thread-1",
                                    "turnId",
                                    codexTurnId,
                                    "item",
                                    Map.of("id", "a1", "type", "agentMessage", "text", value))));
                case CLAUDE ->
                    emit(Map.of(
                            "type",
                            "assistant",
                            "message",
                            Map.of("content", List.of(Map.of("type", "text", "text", value)))));
                case PI ->
                    emit(Map.of(
                            "type",
                            "message_end",
                            "message",
                            Map.of(
                                    "role",
                                    "assistant",
                                    "content",
                                    List.of(Map.of("type", "text", "text", value)),
                                    "stopReason",
                                    "stop")));
                default -> {}
            }
        }

        void finish(boolean error) throws IOException {
            switch (protocol) {
                case CODEX ->
                    emit(Map.of(
                            "method",
                            "turn/completed",
                            "params",
                            Map.of(
                                    "threadId",
                                    "thread-1",
                                    "turn",
                                    error
                                            ? Map.of(
                                                    "id",
                                                    codexTurnId,
                                                    "status",
                                                    "failed",
                                                    "error",
                                                    Map.of("message", "provider failed"))
                                            : Map.of("id", codexTurnId, "status", "completed"))));
                case CLAUDE ->
                    emit(Map.of("type", "result", "is_error", error, "result", error ? "provider failed" : "answer"));
                case PI -> {
                    if (error)
                        emit(Map.of(
                                "type",
                                "message_end",
                                "message",
                                Map.of("role", "assistant", "stopReason", "error", "errorMessage", "provider failed")));
                    emit(Map.of("type", "agent_end", "willRetry", false));
                    emit(Map.of("type", "agent_settled"));
                }
                default -> {}
            }
        }

        void codexEvent(String method, String thread, String turn, Map<String, Object> fields) throws IOException {
            var params = new LinkedHashMap<String, Object>(fields);
            params.put("threadId", thread);
            if (method.startsWith("turn/")) {
                var value = new LinkedHashMap<String, Object>(fields);
                value.put("id", turn);
                params.put("turn", value);
            } else params.put("turnId", turn);
            emit(Map.of("method", method, "params", params));
        }

        void toolImage(String encoded) throws IOException {
            var blocks = List.of(
                    Map.of(
                            "type",
                            "text",
                            "text",
                            Json.stringify(Map.of(
                                    "ok",
                                    true,
                                    "revision",
                                    7,
                                    "captureId",
                                    "native-capture-7",
                                    "width",
                                    2,
                                    "height",
                                    2,
                                    "state",
                                    Map.of(
                                            "documentId",
                                            "native-doc",
                                            "modelId",
                                            "native-model",
                                            "historyRevision",
                                            7)))),
                    protocol == AgentProtocol.CLAUDE
                            ? Map.of(
                                    "type",
                                    "image",
                                    "source",
                                    Map.of("type", "base64", "media_type", "image/png", "data", encoded))
                            : Map.of("type", "image", "mimeType", "image/png", "data", encoded));
            switch (protocol) {
                case CODEX ->
                    emit(Map.of(
                            "method",
                            "item/completed",
                            "params",
                            Map.of(
                                    "threadId",
                                    "thread-1",
                                    "turnId",
                                    codexTurnId,
                                    "item",
                                    Map.of(
                                            "id",
                                            "render-1",
                                            "type",
                                            "mcpToolCall",
                                            "tool",
                                            "render.capture",
                                            "status",
                                            "completed",
                                            "result",
                                            Map.of("content", blocks)))));
                case CLAUDE ->
                    emit(Map.of(
                            "type",
                            "user",
                            "message",
                            Map.of(
                                    "content",
                                    List.of(Map.of(
                                            "type", "tool_result", "tool_use_id", "render-1", "content", blocks)))));
                case PI ->
                    emit(Map.of(
                            "type",
                            "tool_execution_end",
                            "toolCallId",
                            "render-1",
                            "toolName",
                            "render.capture",
                            "result",
                            Map.of("content", blocks, "details", Map.of("revision", 7))));
                default -> fail();
            }
        }

        void askPermission() throws IOException {
            switch (protocol) {
                case CODEX ->
                    emit(Map.of(
                            "id",
                            "approval-1",
                            "method",
                            "item/commandExecution/requestApproval",
                            "params",
                            Map.of(
                                    "threadId",
                                    "thread-1",
                                    "turnId",
                                    codexTurnId,
                                    "itemId",
                                    "tool-1",
                                    "command",
                                    "echo hi")));
                case CLAUDE ->
                    emit(Map.of(
                            "type",
                            "control_request",
                            "request_id",
                            "approval-1",
                            "request",
                            Map.of(
                                    "subtype",
                                    "can_use_tool",
                                    "tool_name",
                                    "Bash",
                                    "tool_use_id",
                                    "tool-1",
                                    "input",
                                    Map.of("command", "echo hi"))));
                case PI ->
                    emit(Map.of(
                            "type",
                            "extension_ui_request",
                            "id",
                            "approval-1",
                            "method",
                            "confirm",
                            "title",
                            "Tool",
                            "message",
                            "Run tool?"));
                default -> {}
            }
        }

        @Override
        public InputStream stdout() {
            return output;
        }

        @Override
        public InputStream stderr() {
            return InputStream.nullInputStream();
        }

        @Override
        public OutputStream stdin() {
            return input;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate(Duration grace) {
            close();
        }

        @Override
        public void close() {
            alive = false;
            try {
                input.close();
                events.close();
                output.close();
                commands.close();
            } catch (IOException ignored) {
            }
        }
    }
}
