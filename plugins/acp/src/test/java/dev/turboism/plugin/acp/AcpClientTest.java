package dev.turboism.plugin.acp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpHttpConnection;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class AcpClientTest {

    @Test
    void sendsImageAndFileContentAndReceivesAgentAndToolImages() throws Exception {
        final AcpAttachment image = AcpAttachment.fromClipboard(
                new java.awt.image.BufferedImage(20, 16, java.awt.image.BufferedImage.TYPE_INT_RGB));
        final AcpAttachment file = new AcpAttachment("notes.txt", "text/plain", "file:///tmp/notes.txt", "aGk=", 2);
        final AtomicReference<List<Object>> blocks = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch received = new java.util.concurrent.CountDownLatch(2);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final long id = (Long) request.get("id");
                    if ("initialize".equals(request.get("method"))) {
                        output.response(
                                id,
                                Map.of(
                                        "protocolVersion",
                                        1L,
                                        "agentCapabilities",
                                        Map.of("promptCapabilities", Map.of("image", true, "embeddedContext", true))));
                    } else if ("session/prompt".equals(request.get("method"))) {
                        blocks.set(list(object(request.get("params")).get("prompt")));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "s1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_message_chunk",
                                                "content",
                                                image.promptBlock(true, true))));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "s1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "tool_call_update",
                                                "toolCallId",
                                                "t1",
                                                "content",
                                                List.of(Map.of(
                                                        "type",
                                                        "content",
                                                        "content",
                                                        image.promptBlock(true, true))))));
                        output.response(id, Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentImage(AcpClient source, String sessionId, AcpAttachment actual) {
                        if (sessionId.equals("s1") && actual.data().equals(image.data())) received.countDown();
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals(
                    "end_turn",
                    client.prompt("s1", "inspect", List.of(image, file)).get(2, TimeUnit.SECONDS));
            assertEquals(
                    List.of("text", "image", "resource"),
                    blocks.get().stream()
                            .map(block -> object(block).get("type"))
                            .toList());
            assertTrue(received.await(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void failureDetailsAreBoundedAndRedactCredentials() {
        final String details = AcpClient.failureDetails(
                new IllegalStateException("API failed: Authorization: Bearer super-secret-token"));
        assertTrue(details.contains("API failed"));
        assertFalse(details.contains("super-secret-token"));
        assertTrue(AcpClient.failureDetails(new IllegalStateException("x".repeat(4096)))
                        .length()
                <= 1025);
        assertEquals("", AcpClient.failureDetails(new IllegalStateException()));
    }

    @Test
    void initializesCreatesSessionAndUsesConfigOptions() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    final long id = (Long) request.get("id");
                    if ("initialize".equals(method)) {
                        output.response(id, initializeResult());
                    } else if ("session/new".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        final List<Object> servers = list(params.get("mcpServers"));
                        assertEquals(1, servers.size());
                        final Map<String, Object> server = object(servers.get(0));
                        assertEquals("http://127.0.0.1:43123/mcp", server.get("url"));
                        assertEquals(Set.of("type", "name", "url"), server.keySet());
                        assertFalse(server.containsKey("headers"));
                        output.response(
                                id, Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    } else if ("session/set_config_option".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        assertEquals("sess-1", params.get("sessionId"));
                        assertEquals("provider", params.get("configId"));
                        assertEquals("codex", params.get("value"));
                        output.response(id, Map.of("configOptions", options("codex", "model-b")));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            final AcpSession session = client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            assertEquals("sess-1", session.sessionId());
            assertEquals("gateway", session.option("provider").currentValue());

            final List<AcpConfigOption> changed = client.setConfigOption("sess-1", "provider", "codex")
                    .result()
                    .get(2, TimeUnit.SECONDS);
            assertEquals(
                    "codex",
                    changed.stream()
                            .filter(option -> option.id().equals("provider"))
                            .findFirst()
                            .orElseThrow()
                            .currentValue());
        }
    }

    @Test
    void attachesTheStdioBridgeServerRegardlessOfHttpCapability() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    final long id = (Long) request.get("id");
                    if ("initialize".equals(method)) {
                        output.response(id, initializeResult(false));
                    } else if ("session/new".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        final List<Object> servers = list(params.get("mcpServers"));
                        assertEquals(1, servers.size());
                        final Map<String, Object> server = object(servers.get(0));
                        assertEquals(java.util.Set.of("name", "command", "args", "env"), server.keySet());
                        assertEquals("turboism", server.get("name"));
                        assertEquals(stdioLaunch().command(), server.get("command"));
                        assertEquals(stdioLaunch().args(), server.get("args"));
                        assertEquals(List.of(), server.get("env"));
                        output.response(id, Map.of("sessionId", "sess-stdio"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            assertFalse(client.capabilities().mcpHttp());
            assertEquals(
                    "sess-stdio",
                    client.newSession(Path.of("."), stdioConnection(), Duration.ofSeconds(2))
                            .sessionId());
        }
    }

    @Test
    void stdioServerPayloadCarriesNoTokenMaterial() throws Exception {
        final String token = "ab12cd34".repeat(8);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    final long id = (Long) request.get("id");
                    if ("initialize".equals(method)) {
                        output.response(id, initializeResult());
                    } else if ("session/new".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        final List<Object> servers = list(params.get("mcpServers"));
                        final Map<String, Object> server = object(servers.get(0));
                        assertFalse(server.toString().contains(token));
                        assertFalse(server.toString().contains("Bearer"));
                        @SuppressWarnings("unchecked")
                        final List<String> args = (List<String>) server.get("args");
                        args.forEach(arg -> assertFalse(arg.contains(token)));
                        output.response(id, Map.of("sessionId", "sess-1"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), stdioConnection(), Duration.ofSeconds(2));
        }
    }

    @Test
    void omitsMcpServersWhenNeitherStdioNorHttpIsUsable() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    final long id = (Long) request.get("id");
                    if ("initialize".equals(method)) {
                        output.response(id, initializeResult(false));
                    } else if ("session/new".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        assertEquals(List.of(), params.get("mcpServers"));
                        output.response(id, Map.of("sessionId", "sess-none"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            assertFalse(client.capabilities().mcpHttp());
            assertEquals(
                    "sess-none",
                    client.newSession(Path.of("."), connection(), Duration.ofSeconds(2))
                            .sessionId());
        }
    }

    @Test
    void parsesTheExactAdvertisedSessionLifecycleSurface() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport(
                        (request, output) -> output.response((Long) request.get("id"), initializeResult()));
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            assertTrue(client.capabilities().loadSession());
            assertTrue(client.capabilities().listSessions());
            assertTrue(client.capabilities().closeSession());
        }

        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> output.response(
                        (Long) request.get("id"),
                        Map.of(
                                "protocolVersion", 1L,
                                "agentInfo", Map.of("name", "fixture-agent", "version", "0.0.5"),
                                "agentCapabilities",
                                        Map.of(
                                                "loadSession",
                                                false,
                                                "mcpCapabilities",
                                                Map.of("http", true, "sse", false)))));
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            assertFalse(client.capabilities().loadSession());
            assertFalse(client.capabilities().listSessions());
            assertFalse(client.capabilities().closeSession());
        }
    }

    @Test
    void listsFxOwnedSessionsWithoutInventingLocalMetadata() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/list".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of(
                                        "sessions",
                                        List.of(
                                                Map.of(
                                                        "sessionId", "sess-new",
                                                        "title", "修复模型参数",
                                                        "cwd", "/workspace",
                                                        "updatedAt", "2026-08-25T10:00:00Z"),
                                                Map.of(
                                                        "sessionId", "sess-old",
                                                        "cwd", "/workspace",
                                                        "updatedAt", "2026-08-24T09:00:00Z"))));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            final List<AcpSessionSummary> sessions = client.listSessions(Duration.ofSeconds(2));
            assertEquals(
                    List.of("sess-new", "sess-old"),
                    sessions.stream().map(AcpSessionSummary::sessionId).toList());
            assertEquals("2026-08-25T10:00:00Z", sessions.get(0).updatedAt());
            assertEquals("修复模型参数", sessions.get(0).title());
            assertEquals("", sessions.get(1).title());
        }
    }

    @Test
    void loadSessionAcceptsNullResultAfterReplayingSessionUpdates() throws Exception {
        final AtomicReference<String> update = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/load".equals(method)) {
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-durable",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_message_chunk",
                                                "content",
                                                Map.of("type", "text", "text", "replayed"))));
                        output.response((Long) request.get("id"), null);
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentText(final AcpClient source, final String sessionId, final String text) {
                        update.set(sessionId + ":" + text);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            final AcpSession session =
                    client.loadSession("sess-durable", Path.of("."), connection(), Duration.ofSeconds(2));
            assertEquals("sess-durable", session.sessionId());
            assertTrue(session.configOptions().isEmpty());
            assertEquals("sess-durable:replayed", update.get());
        }
    }

    @Test
    void preservesOpaqueConfigValuesWhileRedactingDisplayLabels() throws Exception {
        final String secret = "Bearer config-secret";
        final String opaqueValue = "provider-" + secret;
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "configOptions",
                                        List.of(Map.of(
                                                "id",
                                                "provider",
                                                "name",
                                                "Provider " + secret,
                                                "type",
                                                "select",
                                                "currentValue",
                                                opaqueValue,
                                                "options",
                                                List.of(Map.of("value", opaqueValue, "name", "Gateway " + secret))))));
                    } else if ("session/set_config_option".equals(method)) {
                        final Map<String, Object> params = object(request.get("params"));
                        assertEquals("sess-1", params.get("sessionId"));
                        assertEquals("provider", params.get("configId"));
                        assertEquals(opaqueValue, params.get("value"));
                        output.response((Long) request.get("id"), Map.of("configOptions", List.of()));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            final AcpSession session = client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            final AcpConfigOption option = session.option("provider");
            assertEquals(opaqueValue, option.currentValue());
            assertEquals(opaqueValue, option.choices().get(0).value());
            assertFalse(option.name().contains("config-secret"));
            assertTrue(option.name().contains("<redacted>"));
            assertFalse(option.choices().get(0).name().contains("config-secret"));
            assertTrue(option.choices().get(0).name().contains("<redacted>"));
            client.setConfigOption("sess-1", "provider", option.choices().get(0).value())
                    .result()
                    .get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void rejectsCredentialMaterialReturnedAsSessionId() throws Exception {
        final String secret = "Bearer persisted-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", secret, "configOptions", options("gateway", "model-a")));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            final IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> client.newSession(Path.of("."), connection(), Duration.ofSeconds(2)));
            assertFalse(failure.getMessage().contains("persisted-secret"));
        }
    }

    @Test
    void acceptsAnyAcpV1AgentIdentity() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> output.response(
                        (Long) request.get("id"),
                        Map.of(
                                "protocolVersion",
                                1L,
                                "agentInfo",
                                Map.of("name", "some-agent", "title", "Some Agent", "version", "0.0.6"))));
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("some-agent", client.agentInfo().name());
            assertEquals("0.0.6", client.agentInfo().version());
        }
    }

    @Test
    void streamsUpdatesAndAnswersPermissionRequests() throws Exception {
        final AtomicReference<Long> promptId = new AtomicReference<>();
        final AtomicReference<String> agentText = new AtomicReference<>();
        final AtomicReference<String> agentThought = new AtomicReference<>();
        final AtomicReference<AcpListener.PermissionRequest> permission = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/prompt".equals(method)) {
                        promptId.set((Long) request.get("id"));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_message_chunk",
                                                "content",
                                                Map.of("type", "text", "text", "hello"))));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_thought_chunk",
                                                "content",
                                                Map.of("type", "text", "text", "private plan"))));
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "sess-1",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "call-1",
                                                        "title", "Rename object",
                                                        "kind", "edit",
                                                        "rawInput", Map.of("name", "Renamed")),
                                        "options", permissionOptions()));
                    } else if (!request.containsKey("method")
                            && Long.valueOf(99L).equals(request.get("id"))) {
                        final Map<String, Object> result = object(request.get("result"));
                        final Map<String, Object> outcome = object(result.get("outcome"));
                        assertEquals("allow_once", outcome.get("optionId"));
                        output.response(promptId.get(), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentText(final AcpClient source, final String sessionId, final String text) {
                        agentText.set(text);
                    }

                    @Override
                    public void agentThought(final AcpClient source, final String sessionId, final String text) {
                        agentThought.set(text);
                    }

                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        permission.set(request);
                        return PermissionDecision.ALLOW_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            assertEquals("hello", agentText.get());
            assertEquals("private plan", agentThought.get());
            for (int attempt = 0; permission.get() == null && attempt < 200; attempt++) {
                Thread.sleep(5L);
            }
            assertEquals("call-1", permission.get().toolCallId());
            assertEquals("{\"name\":\"Renamed\"}", permission.get().details());
            transport.awaitHandled(3);
        }
    }

    @Test
    void forwardsOpaqueSessionIdsForUpdatesAndPermissions() throws Exception {
        final AtomicReference<List<String>> update = new AtomicReference<>();
        final AtomicReference<String> permissionSession = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/prompt".equals(method)) {
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "opaque-old-session",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_message_chunk",
                                                "content",
                                                Map.of("type", "text", "text", "delayed"))));
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "opaque-old-session",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "call-1",
                                                        "title", "Rename object",
                                                        "kind", "edit",
                                                        "rawInput", Map.of("name", "Renamed")),
                                        "options", permissionOptions()));
                        output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentText(final AcpClient source, final String sessionId, final String text) {
                        update.set(List.of(sessionId, text));
                    }

                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        permissionSession.set(sessionId);
                        return PermissionDecision.REJECT_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            for (int attempt = 0; permissionSession.get() == null && attempt < 200; attempt++) {
                Thread.sleep(5L);
            }
            assertEquals(List.of("opaque-old-session", "delayed"), update.get());
            assertEquals("opaque-old-session", permissionSession.get());
        }
    }

    @Test
    void permissionDialogDoesNotBlockStdoutResponseProcessing() throws Exception {
        final java.util.concurrent.CountDownLatch permissionOpened = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releasePermission = new java.util.concurrent.CountDownLatch(1);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/prompt".equals(method)) {
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "sess-1",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "call-1",
                                                        "title", "Rename object",
                                                        "kind", "edit",
                                                        "rawInput", Map.of("name", "Renamed")),
                                        "options", permissionOptions()));
                        assertTrue(permissionOpened.await(2, TimeUnit.SECONDS));
                        output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        permissionOpened.countDown();
                        try {
                            releasePermission.await();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return PermissionDecision.CANCELLED;
                        }
                        return PermissionDecision.ALLOW_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            final java.util.concurrent.CompletableFuture<String> prompt = client.prompt("sess-1", "work");
            assertTrue(permissionOpened.await(2, TimeUnit.SECONDS));
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        } finally {
            releasePermission.countDown();
        }
    }

    @Test
    void cancelRetiresPermissionBeforeUiReturnsAndLateAllowCannotAnswerTwice() throws Exception {
        var promptId = new AtomicReference<Long>();
        var permissionId = new java.util.concurrent.atomic.AtomicLong(900L);
        var opened = new java.util.concurrent.CompletableFuture<AcpListener.PermissionRequest>();
        var release = new java.util.concurrent.CountDownLatch(1);
        var replies = new java.util.concurrent.LinkedBlockingQueue<Map<String, Object>>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    if ("initialize".equals(request.get("method")))
                        output.response((Long) request.get("id"), Map.of("protocolVersion", 1L));
                    else if ("session/prompt".equals(request.get("method"))) {
                        promptId.set((Long) request.get("id"));
                        output.request(
                                permissionId.getAndIncrement(),
                                "session/request_permission",
                                Map.of(
                                        "sessionId",
                                        "s1",
                                        "toolCall",
                                        Map.of("title", "write", "kind", "edit", "toolCallId", "t1"),
                                        "options",
                                        permissionOptions()));
                    } else if (request.containsKey("result")) {
                        replies.add(request);
                        output.response(promptId.get(), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            AcpClient source, String sessionId, PermissionRequest request) {
                        opened.complete(request);
                        try {
                            release.await();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                        return PermissionDecision.ALLOW_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            var first = client.prompt("s1", "work");
            var permission = opened.get(2, TimeUnit.SECONDS);
            client.cancel("s1");
            assertTrue(permission.cancellation().isCancelled());
            var reply = replies.poll(2, TimeUnit.SECONDS);
            assertNotNull(reply, "Cancellation must answer without waiting for the UI worker");
            assertEquals(
                    "cancelled",
                    object(object(reply.get("result")).get("outcome")).get("outcome"));
            assertEquals("end_turn", first.get(2, TimeUnit.SECONDS));
            release.countDown();
            var next = client.prompt("s1", "next");
            var nextReply = replies.poll(2, TimeUnit.SECONDS);
            assertNotNull(nextReply);
            assertEquals(901L, nextReply.get("id"), "Late reply from the retired UI must not be sent");
            assertEquals(
                    "selected",
                    object(object(nextReply.get("result")).get("outcome")).get("outcome"));
            assertEquals("end_turn", next.get(2, TimeUnit.SECONDS));
            assertTrue(replies.isEmpty());
        } finally {
            release.countDown();
        }
    }

    @Test
    void permissionSessionBudgetRejectsNewIdentitiesAndAllowsKnownSessionReuse() throws Exception {
        var prompts = new java.util.concurrent.atomic.AtomicInteger();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    if ("initialize".equals(request.get("method")))
                        output.response((Long) request.get("id"), initializeResult());
                    else if ("session/prompt".equals(request.get("method"))) {
                        prompts.incrementAndGet();
                        output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            for (int i = 0; i < 64; i++)
                assertEquals("end_turn", client.prompt("s" + i, "work").get(2, TimeUnit.SECONDS));
            var rejected = client.prompt("new-session", "work");
            assertThrows(java.util.concurrent.ExecutionException.class, () -> rejected.get(2, TimeUnit.SECONDS));
            assertEquals("end_turn", client.prompt("s0", "work").get(2, TimeUnit.SECONDS));
            assertEquals(65, prompts.get(), "Over-budget request must not reach the peer");
        }
    }

    @Test
    void sessionResumeAndClientCloseRetireOldApprovalLifetime() throws Exception {
        for (boolean resume : List.of(false, true)) {
            var opened = new java.util.concurrent.CompletableFuture<AcpListener.PermissionRequest>();
            var release = new java.util.concurrent.CountDownLatch(1);
            try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                        if ("initialize".equals(request.get("method")))
                            output.response((Long) request.get("id"), initializeResult());
                        else if ("session/resume".equals(request.get("method")))
                            output.response((Long) request.get("id"), Map.of("sessionId", "s1"));
                        else if ("session/prompt".equals(request.get("method")))
                            output.request(
                                    900L,
                                    "session/request_permission",
                                    Map.of(
                                            "sessionId",
                                            "s1",
                                            "toolCall",
                                            Map.of("title", "write", "kind", "edit", "toolCallId", "t1"),
                                            "options",
                                            permissionOptions()));
                    });
                    AcpClient client = new AcpClient(transport, new AcpListener() {
                        @Override
                        public PermissionDecision permission(
                                AcpClient source, String sessionId, PermissionRequest request) {
                            opened.complete(request);
                            try {
                                release.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                            return PermissionDecision.ALLOW_ONCE;
                        }
                    })) {
                client.initialize(Duration.ofSeconds(2));
                client.prompt("s1", "work");
                var permission = opened.get(2, TimeUnit.SECONDS);
                assertThrows(
                        IllegalArgumentException.class,
                        () -> client.resumeSession("", Path.of("."), connection(), Duration.ofSeconds(2)));
                assertFalse(
                        permission.cancellation().isCancelled(),
                        "Invalid resume must not retire an unrelated approval");
                if (resume)
                    assertEquals(
                            "s1",
                            client.resumeSession("s1", Path.of("."), connection(), Duration.ofSeconds(2))
                                    .sessionId());
                else client.close();
                assertTrue(permission.cancellation().isCancelled());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void cancelledQueuedPermissionsNeverOpenAndNewPromptHasCapacity() throws Exception {
        var opened = new java.util.concurrent.LinkedBlockingQueue<String>();
        var releases = new java.util.concurrent.CountDownLatch(1);
        var replies = new java.util.concurrent.LinkedBlockingQueue<Long>();
        var firstPromptId = new AtomicReference<Long>();
        var round = new java.util.concurrent.atomic.AtomicInteger();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    if ("initialize".equals(request.get("method")))
                        output.response((Long) request.get("id"), initializeResult());
                    else if ("session/prompt".equals(request.get("method"))) {
                        if (round.getAndIncrement() == 0) {
                            firstPromptId.set((Long) request.get("id"));
                            for (int i = 0; i < 9; i++)
                                output.request(
                                        900L + i,
                                        "session/request_permission",
                                        Map.of(
                                                "sessionId",
                                                "s1",
                                                "toolCall",
                                                Map.of("title", "write", "kind", "edit", "toolCallId", "old-" + i),
                                                "options",
                                                permissionOptions()));
                        } else {
                            output.request(
                                    1000L,
                                    "session/request_permission",
                                    Map.of(
                                            "sessionId",
                                            "s1",
                                            "toolCall",
                                            Map.of("title", "write", "kind", "edit", "toolCallId", "fresh"),
                                            "options",
                                            permissionOptions()));
                        }
                    } else if ("session/cancel".equals(request.get("method")))
                        output.response(firstPromptId.get(), Map.of("stopReason", "cancelled"));
                    else if (request.containsKey("result")) replies.add((Long) request.get("id"));
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            AcpClient source, String sessionId, PermissionRequest request) {
                        opened.add(request.toolCallId());
                        if (request.toolCallId().startsWith("old-")) {
                            try {
                                releases.await();
                            } catch (InterruptedException failure) {
                                Thread.currentThread().interrupt();
                            }
                        }
                        return PermissionDecision.REJECT_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            var first = client.prompt("s1", "queued");
            assertEquals("old-0", opened.poll(2, TimeUnit.SECONDS));
            client.cancel("s1");
            for (int i = 0; i < 9; i++) assertNotNull(replies.poll(2, TimeUnit.SECONDS));
            assertEquals("cancelled", first.get(2, TimeUnit.SECONDS));
            releases.countDown();
            client.prompt("s1", "fresh");
            assertEquals("fresh", opened.poll(2, TimeUnit.SECONDS));
            assertEquals(1000L, replies.poll(2, TimeUnit.SECONDS));
            assertTrue(opened.isEmpty(), "Old queued UI tasks must be removed rather than shown");
        } finally {
            releases.countDown();
        }
    }

    @Test
    void promptTerminalRetiresPermissionUiLifetime() throws Exception {
        var opened = new java.util.concurrent.CompletableFuture<AcpListener.PermissionRequest>();
        var terminate = new java.util.concurrent.CompletableFuture<Runnable>();
        var release = new java.util.concurrent.CountDownLatch(1);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    if ("initialize".equals(request.get("method")))
                        output.response((Long) request.get("id"), Map.of("protocolVersion", 1L));
                    else if ("session/prompt".equals(request.get("method"))) {
                        output.request(
                                900L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId",
                                        "s1",
                                        "toolCall",
                                        Map.of("title", "write", "kind", "edit", "toolCallId", "t1"),
                                        "options",
                                        permissionOptions()));
                        long id = (Long) request.get("id");
                        terminate.complete(() -> {
                            try {
                                output.response(id, Map.of("stopReason", "end_turn"));
                            } catch (Exception failure) {
                                throw new RuntimeException(failure);
                            }
                        });
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            AcpClient source, String sessionId, PermissionRequest request) {
                        opened.complete(request);
                        try {
                            release.await();
                        } catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                        }
                        return PermissionDecision.ALLOW_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            var prompt = client.prompt("s1", "work");
            var permission = opened.get(2, TimeUnit.SECONDS);
            terminate.get(2, TimeUnit.SECONDS).run();
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
            assertTrue(permission.cancellation().isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void permissionUiFailureCancelsWithoutTerminatingAcp() throws Exception {
        final AtomicReference<Long> promptId = new AtomicReference<>();
        final AtomicReference<Map<String, Object>> permissionResponse = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/prompt".equals(method)) {
                        promptId.set((Long) request.get("id"));
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "sess-1",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "call-1",
                                                        "title", "Rename object",
                                                        "kind", "edit",
                                                        "rawInput", Map.of("name", "Renamed")),
                                        "options", permissionOptions()));
                    } else if ("session/list".equals(method)) {
                        output.response((Long) request.get("id"), Map.of("sessions", List.of()));
                    } else if (!request.containsKey("method")
                            && Long.valueOf(99L).equals(request.get("id"))) {
                        permissionResponse.set(request);
                        output.response(promptId.get(), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        throw new AssertionError("host Swing UI delegate failed");
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            transport.awaitHandled(3);
            final Map<String, Object> result = object(permissionResponse.get().get("result"));
            final Map<String, Object> outcome = object(result.get("outcome"));
            assertEquals("cancelled", outcome.get("outcome"));
            assertTrue(client.listSessions(Duration.ofSeconds(2)).isEmpty());
            transport.awaitHandled(4);
        }
    }

    @Test
    void abandoningDerivedConfigUpdateReleasesItsPendingSlot() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {});
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            for (int attempt = 0; attempt < 70; attempt++) {
                final AcpClient.PendingConfigUpdate update = client.setConfigOption("sess-1", "provider", "codex");
                client.abandon(update.request());
                assertTrue(update.result().isCompletedExceptionally()
                        || update.result().isCancelled());
            }
        }
    }

    @Test
    void timedOutRequestsReleaseTheirPendingSlots() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {});
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            for (int attempt = 0; attempt < 70; attempt++) {
                final AcpException failure = assertThrows(AcpException.class, () -> client.initialize(Duration.ZERO));
                assertTrue(failure.getMessage().contains("timed out"));
            }
        }
    }

    @Test
    void reportsRedactedStderrWhenAcpProcessExitsBeforeInitialization() throws Exception {
        final String marker = "validation bridge failed at configuration";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    output.stderr(marker);
                    output.closeStdout();
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.initialize(Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains(marker));
        }
    }

    @Test
    void redactsSecretShapedStderrFromEarlyProcessExitDiagnostic() throws Exception {
        final String secret = "early-exit-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    output.stderr("credential=" + secret);
                    output.closeStdout();
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.initialize(Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains("<redacted>"));
            assertFalse(failure.getMessage().contains(secret));
        }
    }

    @Test
    void retainsNonSecretCredentialSafetyDiagnostics() throws Exception {
        final String diagnostic = "error.WindowsAcpCredentialFileUnsafe";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    output.stderr(diagnostic);
                    output.closeStdout();
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.initialize(Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains(diagnostic));
            assertFalse(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void reportsBoundedPrintablePreviewForInvalidAcpStdout() throws Exception {
        final String prefix = "launcher-output-";
        final String line = prefix + "x".repeat(180) + "TAIL";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> output.stdout(line));
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.initialize(Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains(prefix));
            assertFalse(failure.getMessage().contains("TAIL"));
            assertTrue(failure.getMessage().contains("first line:"));
        }
    }

    @Test
    void redactsBearerFromInvalidAcpStdoutPreview() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    } else if ("session/list".equals(method)) {
                        output.stdout("launch failed: Bearer stdout-secret");
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.listSessions(Duration.ofSeconds(2)));
            assertFalse(failure.getMessage().contains("stdout-secret"));
            assertTrue(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void redactsSecretShapedInvalidAcpStdoutPreview() throws Exception {
        final String secret = "sensitive-validation-value";
        try (ScriptedTransport transport =
                        new ScriptedTransport((request, output) -> output.stdout("launcher token=" + secret));
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            final AcpException failure =
                    assertThrows(AcpException.class, () -> client.initialize(Duration.ofSeconds(2)));
            assertTrue(failure.getMessage().contains("first line: <redacted>"));
            assertFalse(failure.getMessage().contains(secret));
        }
    }

    @Test
    void redactsBearerFromSessionListMetadata() throws Exception {
        final String secret = "Bearer session-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    } else if ("session/list".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of(
                                        "sessions",
                                        List.of(Map.of("sessionId", "sess-1", "title", secret, "updatedAt", secret))));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            final List<AcpSessionSummary> sessions = client.listSessions(Duration.ofSeconds(2));
            assertEquals("<redacted>", sessions.get(0).updatedAt());
            assertEquals("<redacted>", sessions.get(0).title());
        }
    }

    @Test
    void redactsCredentialMaterialFromForwardedStderr() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final String secret = "Bearer local-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.stderr("problem token=local-secret header=" + secret);
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void stderr(final AcpClient source, final String text) {
                        stderr.set(text);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            for (int count = 0; stderr.get() == null && count < 200; count++) {
                Thread.sleep(1L);
            }
            assertTrue(stderr.get() != null, "fake agent stderr was not forwarded");
            assertFalse(stderr.get().contains("local-secret"));
            assertTrue(stderr.get().contains("<redacted>"));
        }
    }

    @Test
    void redactsTruncatedStderrWhenItMayEndInsideTheBearer() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final String token = "s".repeat(128);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.stderr("x".repeat(16 * 1024 - 33) + " Bearer " + token);
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void stderr(final AcpClient source, final String text) {
                        stderr.set(text);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            for (int count = 0; stderr.get() == null && count < 200; count++) {
                Thread.sleep(1L);
            }
            assertEquals("<redacted>…", stderr.get());
            assertFalse(stderr.get().contains(token.substring(0, 32)));
        }
    }

    @Test
    void redactsMultipleBearerValuesWithoutConnectionCredentials() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final AtomicInteger sessions = new AtomicInteger();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of(
                                        "sessionId",
                                        "sess-" + sessions.incrementAndGet(),
                                        "configOptions",
                                        options("gateway", "model-a")));
                        if (sessions.get() == 2) {
                            output.stderr("old=Bearer previous-secret new=Bearer current-secret");
                        }
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void stderr(final AcpClient source, final String text) {
                        stderr.set(text);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            for (int count = 0; stderr.get() == null && count < 200; count++) {
                Thread.sleep(1L);
            }
            assertEquals("old=<redacted> new=<redacted>", stderr.get());
        }
    }

    @Test
    void redactsBearerMaterialFromRpcErrors() throws Exception {
        final String secret = "Bearer rpc-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.error((Long) request.get("id"), secret + " could not connect");
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {})) {
            client.initialize(Duration.ofSeconds(2));
            final AcpException failure = assertThrows(
                    AcpException.class, () -> client.newSession(Path.of("."), connection(), Duration.ofSeconds(2)));
            assertFalse(failure.getMessage().contains("rpc-secret"));
            assertTrue(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void permissionDetailsAreBoundedAndBearerRedacted() throws Exception {
        final AtomicReference<Long> promptId = new AtomicReference<>();
        final AtomicReference<AcpListener.PermissionRequest> permission = new AtomicReference<>();
        final String secret = "Bearer permission-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    } else if ("session/prompt".equals(method)) {
                        promptId.set((Long) request.get("id"));
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "sess-1",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "call-1",
                                                        "title", "Run command",
                                                        "kind", "execute",
                                                        "rawInput", oversizedPermissionInput(secret)),
                                        "options", permissionOptions()));
                    } else if (!request.containsKey("method")
                            && Long.valueOf(99L).equals(request.get("id"))) {
                        output.response(promptId.get(), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        permission.set(request);
                        return PermissionDecision.REJECT_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS);
            final AcpListener.PermissionRequest received = awaitPermission(permission);
            assertFalse(received.details().contains("permission-secret"));
            assertTrue(received.details().contains("<redacted>"));
            assertTrue(received.details().length() <= 32 * 1024 + 1);
        }
    }

    @Test
    void redactsAndBoundsAllListenerVisibleSessionMetadata() throws Exception {
        final String secret = "Bearer ui-secret";
        final AtomicReference<String> agentText = new AtomicReference<>();
        final AtomicReference<String> agentThought = new AtomicReference<>();
        final AtomicReference<List<String>> toolCall = new AtomicReference<>();
        final AtomicReference<List<String>> toolUpdate = new AtomicReference<>();
        final AtomicReference<String> toolInput = new AtomicReference<>();
        final var permissionOpened = new java.util.concurrent.CountDownLatch(1);
        final AtomicReference<AcpListener.PermissionRequest> permission = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    final String method = string(request.get("method"));
                    if ("initialize".equals(method)) {
                        output.response((Long) request.get("id"), initializeResult());
                    } else if ("session/new".equals(method)) {
                        output.response(
                                (Long) request.get("id"),
                                Map.of("sessionId", "sess-1", "configOptions", options("gateway", "model-a")));
                    } else if ("session/prompt".equals(method)) {
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_message_chunk",
                                                "content",
                                                Map.of(
                                                        "type",
                                                        "text",
                                                        "text",
                                                        secret + " " + "x".repeat(300 * 1024)))));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "agent_thought_chunk",
                                                "content",
                                                Map.of(
                                                        "type",
                                                        "text",
                                                        "text",
                                                        secret + " " + "t".repeat(300 * 1024)))));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "tool_call",
                                                "toolCallId",
                                                "new-call " + secret,
                                                "title",
                                                secret,
                                                "kind",
                                                "edit " + secret,
                                                "status",
                                                "pending " + secret,
                                                "rawInput",
                                                Map.of("command", "echo " + secret + " " + "i".repeat(300 * 1024)))));
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "sess-1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "tool_call_update",
                                                "toolCallId",
                                                "call " + secret,
                                                "status",
                                                "running " + secret,
                                                "content",
                                                List.of(Map.of("content", Map.of("type", "text", "text", secret))))));
                        output.request(
                                99L,
                                "session/request_permission",
                                Map.of(
                                        "sessionId", "sess-1",
                                        "toolCall",
                                                Map.of(
                                                        "toolCallId", "permission " + secret,
                                                        "title", "Approve " + secret,
                                                        "kind", "execute " + secret,
                                                        "rawInput", Map.of("authorization", secret)),
                                        "options", permissionOptions()));
                        assertTrue(permissionOpened.await(2, TimeUnit.SECONDS));
                        output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentText(final AcpClient source, final String sessionId, final String text) {
                        agentText.set(text);
                    }

                    @Override
                    public void agentThought(final AcpClient source, final String sessionId, final String text) {
                        agentThought.set(text);
                    }

                    @Override
                    public void toolCall(
                            final AcpClient source,
                            final String sessionId,
                            final String toolCallId,
                            final String title,
                            final String kind,
                            final String status) {
                        toolCall.set(List.of(toolCallId, title, kind, status));
                    }

                    @Override
                    public void toolCallUpdate(
                            final AcpClient source,
                            final String sessionId,
                            final String toolCallId,
                            final String status,
                            final String content) {
                        toolUpdate.set(List.of(toolCallId, status, content));
                    }

                    @Override
                    public void toolInput(
                            final AcpClient source,
                            final String sessionId,
                            final String toolCallId,
                            final String input) {
                        assertOpaqueToolKey(toolCallId);
                        toolInput.set(input);
                    }

                    @Override
                    public PermissionDecision permission(
                            final AcpClient source, final String sessionId, final PermissionRequest request) {
                        permission.set(request);
                        permissionOpened.countDown();
                        return PermissionDecision.REJECT_ONCE;
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), connection(), Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            for (int count = 0; permission.get() == null && count < 1000; count++) {
                Thread.sleep(1L);
            }
            assertRedacted(agentText.get());
            assertTrue(agentText.get().length() <= 256 * 1024);
            assertRedacted(agentThought.get());
            assertTrue(agentThought.get().length() <= 256 * 1024);
            assertOpaqueToolKey(toolCall.get().get(0));
            toolCall.get().subList(1, toolCall.get().size()).forEach(AcpClientTest::assertRedacted);
            assertOpaqueToolKey(toolUpdate.get().get(0));
            toolUpdate.get().subList(1, toolUpdate.get().size()).forEach(AcpClientTest::assertRedacted);
            assertRedacted(toolInput.get());
            assertTrue(toolInput.get().length() <= 256 * 1024);
            assertRedacted(permission.get().title());
            assertRedacted(permission.get().kind());
            assertRedacted(permission.get().toolCallId());
            assertRedacted(permission.get().details());
        }
    }

    private static Map<String, Object> initializeResult() {
        return initializeResult(true);
    }

    private static Map<String, Object> initializeResult(final boolean mcpHttp) {
        return Map.of(
                "protocolVersion", 1L,
                "agentInfo", Map.of("name", "test-agent", "version", "9.9.9"),
                "agentCapabilities",
                        Map.of(
                                "loadSession",
                                true,
                                "sessionCapabilities",
                                Map.of(
                                        "list", Map.of(),
                                        "resume", Map.of(),
                                        "close", Map.of()),
                                "mcpCapabilities",
                                Map.of("http", mcpHttp)));
    }

    private static McpHttpConnection connection() {
        return new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25");
    }

    private static McpHttpConnection stdioConnection() {
        return new McpHttpConnection(URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", stdioLaunch());
    }

    private static dev.turboism.sdk.mcp.McpStdioLaunch stdioLaunch() {
        return new dev.turboism.sdk.mcp.McpStdioLaunch(
                java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java")
                        .toAbsolutePath()
                        .toString(),
                List.of("-cp", "/plugins dir/mcp.jar", "dev.turboism.plugin.mcp.TurboismMcpBridge", "/state dir"));
    }

    private static List<Map<String, Object>> options(final String provider, final String model) {
        return List.of(
                Map.of(
                        "id", "provider",
                        "name", "Provider",
                        "category", "model",
                        "type", "select",
                        "currentValue", provider,
                        "options",
                                List.of(
                                        Map.of("value", "gateway", "name", "Vercel AI Gateway"),
                                        Map.of("value", "codex", "name", "Codex subscription"))),
                Map.of(
                        "id", "model",
                        "name", "Model",
                        "category", "model",
                        "type", "select",
                        "currentValue", model,
                        "options",
                                List.of(
                                        Map.of("value", "model-a", "name", "model-a"),
                                        Map.of("value", "model-b", "name", "model-b"))));
    }

    private static Map<String, Object> oversizedPermissionInput(final String secret) {
        final java.util.LinkedHashMap<String, Object> input = new java.util.LinkedHashMap<>();
        input.put("command", secret);
        input.put("padding", "x".repeat(40 * 1024));
        return input;
    }

    private static List<Map<String, Object>> permissionOptions() {
        return List.of(
                Map.of("optionId", "allow_once", "name", "Allow once", "kind", "allow_once"),
                Map.of("optionId", "allow_always", "name", "Allow for this session", "kind", "allow_always"),
                Map.of("optionId", "reject_once", "name", "Reject", "kind", "reject_once"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return (List<Object>) value;
    }

    private static String string(final Object value) {
        return value instanceof String text ? text : null;
    }

    private static void assertRedacted(final String value) {
        assertTrue(value != null, "listener value was not forwarded");
        assertFalse(value.contains("ui-secret"));
        assertTrue(value.contains("<redacted>"));
    }

    private static void assertOpaqueToolKey(final String value) {
        assertNotNull(value, "tool correlation key was not forwarded");
        assertTrue(value.matches("[0-9a-f]{64}"));
        assertFalse(value.contains("ui-secret"));
    }

    private static AcpListener.PermissionRequest awaitPermission(
            final AtomicReference<AcpListener.PermissionRequest> permission) throws InterruptedException {
        for (int count = 0; permission.get() == null && count < 1000; count++) {
            Thread.sleep(1L);
        }
        assertNotNull(permission.get(), "permission request was not forwarded");
        return permission.get();
    }

    @FunctionalInterface
    private interface Script {
        void handle(Map<String, Object> request, ScriptOutput output) throws Exception;
    }

    @Test
    void acpPoseImagesCarryExactToolFrameLabelsIntoDecodedSwingPreviews() throws Exception {
        var image = AcpAttachment.fromClipboard(
                new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        String secret = "caption-secret-".repeat(20);
        var left = AcpToolImageLabelTest.capture("first", "left", 1);
        left.put("state", Map.of("documentId", "doc", "modelId", "Bearer " + secret, "historyRevision", 7));
        var metadata =
                Map.<String, Object>of("frames", List.of(AcpToolImageLabelTest.capture("second", "right", 2), left));
        var contents = AcpToolImageLabelTest.contents(
                metadata,
                AcpToolImageLabelTest.wrap(image.promptBlock(true, true)),
                AcpToolImageLabelTest.wrap(image.promptBlock(true, true)));
        var images = new java.util.concurrent.CopyOnWriteArrayList<AcpAttachment>();
        var received = new java.util.concurrent.CountDownLatch(2);
        var text = new StringBuilder();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
                    long id = (Long) request.get("id");
                    if ("initialize".equals(request.get("method")))
                        output.response(id, Map.of("protocolVersion", 1L, "agentCapabilities", Map.of()));
                    else if ("session/prompt".equals(request.get("method"))) {
                        output.notification(
                                "session/update",
                                Map.of(
                                        "sessionId",
                                        "s1",
                                        "update",
                                        Map.of(
                                                "sessionUpdate",
                                                "tool_call_update",
                                                "toolCallId",
                                                "pose-tool",
                                                "status",
                                                "completed",
                                                "content",
                                                contents)));
                        output.response(id, Map.of("stopReason", "end_turn"));
                    }
                });
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentImage(AcpClient source, String sessionId, AcpAttachment attachment) {
                        images.add(attachment);
                        received.countDown();
                    }

                    @Override
                    public void toolCallUpdate(
                            AcpClient source, String sessionId, String toolId, String status, String content) {
                        text.append(content);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("s1", "inspect").get(2, TimeUnit.SECONDS));
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(2, images.size());
            assertTrue(images.get(0).name().contains("pose=left"));
            assertTrue(images.get(0).name().contains("capture=first"));
            assertFalse(images.get(0).name().contains("caption-secret-"));
            assertTrue(images.get(0).name().contains("<redacted>"));
            assertTrue(images.get(1).name().contains("pose=right"));
            assertTrue(images.get(1).name().contains("capture=second"));
            for (var attachment : images) {
                assertEquals(image.data(), attachment.data());
                assertNotNull(attachment.preview());
                var document = new javax.swing.text.DefaultStyledDocument();
                AcpMessageRenderer.insert(
                        document, 0, attachment.name(), new javax.swing.text.SimpleAttributeSet(), false);
                assertTrue(document.getText(0, document.getLength()).contains("reported:"));
            }
            assertFalse(text.toString().contains(image.data()));
        }
    }

    @Test
    void rawOutputOnlyReceiptsRemainReadableAndKeepOriginalToolStatus() throws Exception {
        for (String kind : List.of("tool_call", "tool_call_update")) {
            var statuses = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var texts = new java.util.concurrent.CopyOnWriteArrayList<String>();
            var receipt = Map.of(
                    "operationId",
                    "write-17",
                    "outcome",
                    "OUTCOME_UNKNOWN",
                    "retryable",
                    false,
                    "message",
                    "Bearer ui-secret");
            try (ScriptedTransport transport = rawOutputTransport(kind, Map.of("rawOutput", receipt));
                    AcpClient client = new AcpClient(transport, new AcpListener() {
                        @Override
                        public void toolCallUpdate(
                                AcpClient source, String sessionId, String toolId, String status, String content) {
                            statuses.add(status);
                            texts.add(content);
                        }
                    })) {
                client.initialize(Duration.ofSeconds(2));
                client.prompt("s1", "inspect").get(2, TimeUnit.SECONDS);
                assertEquals(List.of("failed"), statuses);
                assertEquals(1, texts.size());
                assertTrue(texts.get(0).contains("OUTCOME_UNKNOWN"));
                assertTrue(texts.get(0).contains("write-17"));
                assertTrue(texts.get(0).contains("false"));
                assertFalse(texts.get(0).contains("ui-secret"));
            }
        }
    }

    @Test
    void rawOutputMcpImageRetainsPixelsAndReportedCaptureIdentity() throws Exception {
        var image = AcpAttachment.fromClipboard(
                new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        var metadata = AcpToolImageLabelTest.capture("raw-capture", "", 1);
        metadata.put("ok", true);
        var raw = Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(metadata)), image.promptBlock(true, true)),
                "structuredContent",
                metadata);
        var images = new java.util.concurrent.CopyOnWriteArrayList<AcpAttachment>();
        var text = new StringBuilder();
        try (ScriptedTransport transport =
                        rawOutputTransport("tool_call_update", Map.of("rawOutput", raw, "content", List.of()));
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentImage(AcpClient source, String sessionId, AcpAttachment attachment) {
                        images.add(attachment);
                    }

                    @Override
                    public void toolCallUpdate(
                            AcpClient source, String sessionId, String toolId, String status, String content) {
                        text.append(content);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.prompt("s1", "inspect").get(2, TimeUnit.SECONDS);
            assertEquals(1, images.size());
            assertEquals(image.data(), images.get(0).data());
            assertNotNull(images.get(0).preview());
            assertTrue(images.get(0).name().contains("capture=raw-capture"));
            assertFalse(text.toString().contains(image.data()));
        }
    }

    @Test
    void explicitAcpContentWinsOverRawOutputWithoutDuplicateOrStaleImages() throws Exception {
        var images = new java.util.concurrent.atomic.AtomicInteger();
        var texts = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var image = AcpAttachment.fromClipboard(
                new java.awt.image.BufferedImage(2, 3, java.awt.image.BufferedImage.TYPE_INT_ARGB));
        var content = List.of(AcpToolImageLabelTest.wrap(Map.of("type", "text", "text", "canonical-result")));
        try (ScriptedTransport transport = rawOutputTransport(
                        "tool_call_update",
                        Map.of(
                                "content",
                                content,
                                "rawOutput",
                                Map.of("content", List.of(image.promptBlock(true, true)))));
                AcpClient client = new AcpClient(transport, new AcpListener() {
                    @Override
                    public void agentImage(AcpClient source, String sessionId, AcpAttachment attachment) {
                        images.incrementAndGet();
                    }

                    @Override
                    public void toolCallUpdate(
                            AcpClient source, String sessionId, String toolId, String status, String text) {
                        texts.add(text);
                    }
                })) {
            client.initialize(Duration.ofSeconds(2));
            client.prompt("s1", "inspect").get(2, TimeUnit.SECONDS);
            assertEquals(List.of("canonical-result"), texts);
            assertEquals(0, images.get());
        }
    }

    @Test
    void unsupportedRawOutputBinaryIsSummarizedAndTextUsesExistingUiBudget() throws Exception {
        for (Object raw : List.of(Map.of("type", "audio", "data", "raw-binary-secret"), "x".repeat(300 * 1024))) {
            var texts = new java.util.concurrent.CopyOnWriteArrayList<String>();
            try (ScriptedTransport transport = rawOutputTransport("tool_call_update", Map.of("rawOutput", raw));
                    AcpClient client = new AcpClient(transport, new AcpListener() {
                        @Override
                        public void toolCallUpdate(
                                AcpClient source, String sessionId, String toolId, String status, String text) {
                            texts.add(text);
                        }
                    })) {
                client.initialize(Duration.ofSeconds(2));
                client.prompt("s1", "inspect").get(2, TimeUnit.SECONDS);
                assertEquals(1, texts.size());
                assertFalse(texts.get(0).contains("raw-binary-secret"));
                assertTrue(texts.get(0).length() <= 256 * 1024 + 100);
                if (raw instanceof Map<?, ?>) assertTrue(texts.get(0).contains("Unsupported tool content: audio"));
            }
        }
    }

    private static ScriptedTransport rawOutputTransport(String kind, Map<String, Object> fields) throws IOException {
        return new ScriptedTransport((request, output) -> {
            long id = (Long) request.get("id");
            if ("initialize".equals(request.get("method")))
                output.response(id, Map.of("protocolVersion", 1L, "agentCapabilities", Map.of()));
            else if ("session/prompt".equals(request.get("method"))) {
                var update = new java.util.LinkedHashMap<String, Object>(fields);
                update.put("sessionUpdate", kind);
                update.put("toolCallId", "raw-tool");
                update.put("status", "failed");
                output.notification("session/update", Map.of("sessionId", "s1", "update", update));
                output.response(id, Map.of("stopReason", "end_turn"));
            }
        });
    }

    private static final class ScriptedTransport implements AcpTransport {
        private final java.io.PipedInputStream clientStdout = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStdout;
        private final java.io.PipedInputStream serverStdin = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream clientStdin;
        private final java.io.PipedInputStream clientStderr = new java.io.PipedInputStream();
        private final java.io.PipedOutputStream serverStderr;
        private final Thread server;
        private volatile boolean alive = true;
        private volatile int handled;

        private ScriptedTransport(final Script script) throws IOException {
            serverStdout = new java.io.PipedOutputStream(clientStdout);
            clientStdin = new java.io.PipedOutputStream(serverStdin);
            serverStderr = new java.io.PipedOutputStream(clientStderr);
            server = new Thread(() -> run(script), "fake-agent-acp");
            server.setDaemon(true);
            server.start();
        }

        private void run(final Script script) {
            try (BoundedLineReader lines = new BoundedLineReader(
                            new InputStreamReader(serverStdin, StandardCharsets.UTF_8), AcpClient.MAX_ACP_LINE_CHARS);
                    BufferedWriter output =
                            new BufferedWriter(new OutputStreamWriter(serverStdout, StandardCharsets.UTF_8));
                    BufferedWriter errors =
                            new BufferedWriter(new OutputStreamWriter(serverStderr, StandardCharsets.UTF_8))) {
                final ScriptOutput writer = new ScriptOutput(output, errors);
                for (String line; (line = lines.readLine()) != null; ) {
                    script.handle(object(Json.parseObject(line.getBytes(StandardCharsets.UTF_8))), writer);
                    handled++;
                }
            } catch (Exception failure) {
                alive = false;
            } finally {
                alive = false;
            }
        }

        private void awaitHandled(final int count) throws InterruptedException {
            for (int attempt = 0; handled < count && attempt < 1000; attempt++) {
                Thread.sleep(1L);
            }
            assertTrue(handled >= count, "fake agent did not handle all expected messages");
        }

        @Override
        public InputStream stdout() {
            return clientStdout;
        }

        @Override
        public InputStream stderr() {
            return clientStderr;
        }

        @Override
        public OutputStream stdin() {
            return clientStdin;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public void terminate(final Duration grace) {
            alive = false;
            try {
                clientStdin.close();
            } catch (IOException ignored) {
            }
            try {
                serverStdout.close();
            } catch (IOException ignored) {
            }
            try {
                serverStderr.close();
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() {
            terminate(Duration.ZERO);
        }
    }

    private record ScriptOutput(BufferedWriter output, BufferedWriter errors) {
        private void response(final long id, final Object result) throws IOException {
            final java.util.LinkedHashMap<String, Object> response = new java.util.LinkedHashMap<>();
            response.put("jsonrpc", "2.0");
            response.put("id", id);
            response.put("result", result);
            line(response);
        }

        private void error(final long id, final String message) throws IOException {
            line(Map.of("jsonrpc", "2.0", "id", id, "error", Map.of("code", -32000L, "message", message)));
        }

        private void request(final long id, final String method, final Object params) throws IOException {
            line(Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params));
        }

        private void notification(final String method, final Object params) throws IOException {
            line(Map.of("jsonrpc", "2.0", "method", method, "params", params));
        }

        private void stdout(final String text) throws IOException {
            output.write(text);
            output.write('\n');
            output.flush();
        }

        private void stderr(final String text) throws IOException {
            errors.write(text);
            errors.write('\n');
            errors.flush();
        }

        private void closeStdout() throws IOException {
            output.close();
        }

        private void line(final Map<String, ?> value) throws IOException {
            output.write(Json.stringify(value));
            output.write('\n');
            output.flush();
        }
    }
}
