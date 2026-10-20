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
            fixture.raw.askPermission();
            var decision = fixture.raw.permission.get(2, TimeUnit.SECONDS);
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
                        result = Map.of("turn", Map.of("id", "turn-1"));
                        accepted.countDown();
                    }
                    case "turn/interrupt" -> aborted.countDown();
                    default -> {}
                }
                emit(Map.of("id", id, "result", result));
            } else if (protocol == AgentProtocol.CLAUDE) {
                if ("control_request".equals(request.get("type"))) {
                    if ("interrupt".equals(object(request.get("request")).get("subtype"))) aborted.countDown();
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
                } else if ("user".equals(request.get("type"))) accepted.countDown();
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
                        data = Map.of("disposition", "started");
                        accepted.countDown();
                    }
                    case "abort" -> aborted.countDown();
                    default -> {}
                }
                emit(Map.of("id", id, "type", "response", "command", type, "success", true, "data", data));
            }
        }

        void delta(String value) throws IOException {
            switch (protocol) {
                case CODEX ->
                    emit(Map.of("method", "item/agentMessage/delta", "params", Map.of("itemId", "a1", "delta", value)));
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
                            Map.of("item", Map.of("id", "a1", "type", "agentMessage", "text", value))));
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
                                    "turn",
                                    error
                                            ? Map.of("status", "failed", "error", Map.of("message", "provider failed"))
                                            : Map.of("status", "completed"))));
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

        void askPermission() throws IOException {
            switch (protocol) {
                case CODEX ->
                    emit(Map.of(
                            "id",
                            "approval-1",
                            "method",
                            "item/commandExecution/requestApproval",
                            "params",
                            Map.of("itemId", "tool-1", "command", "echo hi")));
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
