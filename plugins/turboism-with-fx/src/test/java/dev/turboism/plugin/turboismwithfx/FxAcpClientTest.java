package dev.turboism.plugin.turboismwithfx;

import dev.turboism.sdk.io.BoundedLineReader;
import dev.turboism.protocol.json.StrictJson;
import dev.turboism.sdk.mcp.McpHttpConnection;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class FxAcpClientTest {

    @Test
    void initializesCreatesSessionAndUsesFxConfigOptions() throws Exception {
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
                output.response(id, Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            } else if ("session/set_config_option".equals(method)) {
                final Map<String, Object> params = object(request.get("params"));
                assertEquals("sess-1", params.get("sessionId"));
                assertEquals("provider", params.get("configId"));
                assertEquals("codex", params.get("value"));
                output.response(id, Map.of(
                    "configOptions", options("codex", "model-b")
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            final FxAcpSession session = client.newSession(
                Path.of("."), connection(), Duration.ofSeconds(2)
            );
            assertEquals("sess-1", session.sessionId());
            assertEquals("gateway", session.option("provider").currentValue());

            final List<FxAcpConfigOption> changed = client
                .setConfigOption("sess-1", "provider", "codex")
                .result()
                .get(2, TimeUnit.SECONDS);
            assertEquals("codex", changed.stream()
                .filter(option -> option.id().equals("provider"))
                .findFirst().orElseThrow().currentValue());
        }
    }

    @Test
    void parsesTheExactAdvertisedSessionLifecycleSurface() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.response((Long) request.get("id"), initializeResult())
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            assertTrue(client.capabilities().loadSession());
            assertTrue(client.capabilities().listSessions());
            assertTrue(client.capabilities().closeSession());
        }

        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.response((Long) request.get("id"), Map.of(
                "protocolVersion", 1L,
                "agentInfo", Map.of("name", "fx", "version", "0.0.5"),
                "agentCapabilities", Map.of(
                    "loadSession", false,
                    "mcpCapabilities", Map.of("http", true, "sse", false)
                )
            ))
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
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
                output.response((Long) request.get("id"), Map.of(
                    "sessions", List.of(
                        Map.of(
                            "sessionId", "sess-new",
                            "cwd", "/workspace",
                            "updatedAt", "2026-08-25T10:00:00Z"
                        ),
                        Map.of(
                            "sessionId", "sess-old",
                            "cwd", "/workspace",
                            "updatedAt", "2026-08-24T09:00:00Z"
                        )
                    )
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            final List<FxAcpSessionSummary> sessions = client.listSessions(Duration.ofSeconds(2));
            assertEquals(List.of("sess-new", "sess-old"), sessions.stream()
                .map(FxAcpSessionSummary::sessionId)
                .toList());
            assertEquals("2026-08-25T10:00:00Z", sessions.get(0).updatedAt());
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
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", List.of(Map.of(
                        "id", "provider",
                        "name", "Provider " + secret,
                        "type", "select",
                        "currentValue", opaqueValue,
                        "options", List.of(Map.of(
                            "value", opaqueValue,
                            "name", "Gateway " + secret
                        ))
                    ))
                ));
            } else if ("session/set_config_option".equals(method)) {
                final Map<String, Object> params = object(request.get("params"));
                assertEquals("sess-1", params.get("sessionId"));
                assertEquals("provider", params.get("configId"));
                assertEquals(opaqueValue, params.get("value"));
                output.response((Long) request.get("id"), Map.of("configOptions", List.of()));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            final FxAcpSession session = client.newSession(
                Path.of("."),
                new McpHttpConnection(
                    URI.create("http://127.0.0.1:43123/mcp"),
                    "2025-11-25",
                    secret
                ),
                Duration.ofSeconds(2)
            );
            final FxAcpConfigOption option = session.option("provider");
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
    void rejectsAuthorizationMaterialReturnedAsSessionId() throws Exception {
        final String secret = "Bearer persisted-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", secret,
                    "configOptions", options("gateway", "model-a")
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> client.newSession(
                    Path.of("."),
                    new McpHttpConnection(
                        URI.create("http://127.0.0.1:43123/mcp"),
                        "2025-11-25",
                        secret
                    ),
                    Duration.ofSeconds(2)
                )
            );
            assertFalse(failure.getMessage().contains("persisted-secret"));
        }
    }

    @Test
    void rejectsNonFxOrUnreviewedFxVersions() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.response((Long) request.get("id"), Map.of(
                "protocolVersion", 1L,
                "agentInfo", Map.of("name", "fx", "version", "0.0.6")
            ))
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
            assertTrue(failure.getMessage().contains("install fx 0.0.5"));
        }

        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.response((Long) request.get("id"), Map.of(
                "protocolVersion", 1L,
                "agentInfo", Map.of("name", "not-fx", "version", "0.0.5")
            ))
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
            assertTrue(failure.getMessage().contains("not fx"));
        }
    }

    @Test
    void streamsUpdatesAndAnswersPermissionRequests() throws Exception {
        final AtomicReference<String> agentText = new AtomicReference<>();
        final AtomicReference<String> agentThought = new AtomicReference<>();
        final AtomicReference<FxAcpListener.PermissionRequest> permission = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/prompt".equals(method)) {
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "agent_message_chunk",
                        "content", Map.of("type", "text", "text", "hello")
                    )
                ));
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "agent_thought_chunk",
                        "content", Map.of("type", "text", "text", "private plan")
                    )
                ));
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "sess-1",
                    "toolCall", Map.of(
                        "toolCallId", "call-1",
                        "title", "Rename object",
                        "kind", "edit",
                        "rawInput", Map.of("name", "Renamed")
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            } else if (!request.containsKey("method") && Long.valueOf(99L).equals(request.get("id"))) {
                final Map<String, Object> result = object(request.get("result"));
                final Map<String, Object> outcome = object(result.get("outcome"));
                assertEquals("allow_once", outcome.get("optionId"));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public void agentText(final FxAcpClient source, final String sessionId, final String text) {
                     agentText.set(text);
                 }

                 @Override
                 public void agentThought(final FxAcpClient source, final String sessionId, final String text) {
                     agentThought.set(text);
                 }

                 @Override
                 public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
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
                output.notification("session/update", Map.of(
                    "sessionId", "opaque-old-session",
                    "update", Map.of(
                        "sessionUpdate", "agent_message_chunk",
                        "content", Map.of("type", "text", "text", "delayed")
                    )
                ));
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "opaque-old-session",
                    "toolCall", Map.of(
                        "toolCallId", "call-1",
                        "title", "Rename object",
                        "kind", "edit",
                        "rawInput", Map.of("name", "Renamed")
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public void agentText(
                     final FxAcpClient source,
                     final String sessionId,
                     final String text
                 ) {
                     update.set(List.of(sessionId, text));
                 }

                 @Override
                 public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
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
        final java.util.concurrent.CountDownLatch permissionOpened =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch releasePermission =
            new java.util.concurrent.CountDownLatch(1);
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/prompt".equals(method)) {
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "sess-1",
                    "toolCall", Map.of(
                        "toolCallId", "call-1",
                        "title", "Rename object",
                        "kind", "edit",
                        "rawInput", Map.of("name", "Renamed")
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
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
            final java.util.concurrent.CompletableFuture<String> prompt =
                client.prompt("sess-1", "work");
            assertTrue(permissionOpened.await(2, TimeUnit.SECONDS));
            assertEquals("end_turn", prompt.get(2, TimeUnit.SECONDS));
        } finally {
            releasePermission.countDown();
        }
    }

    @Test
    void permissionUiFailureCancelsWithoutTerminatingAcp() throws Exception {
        final AtomicReference<Map<String, Object>> permissionResponse = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/prompt".equals(method)) {
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "sess-1",
                    "toolCall", Map.of(
                        "toolCallId", "call-1",
                        "title", "Rename object",
                        "kind", "edit",
                        "rawInput", Map.of("name", "Renamed")
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            } else if ("session/list".equals(method)) {
                output.response((Long) request.get("id"), Map.of("sessions", List.of()));
            } else if (!request.containsKey("method")
                && Long.valueOf(99L).equals(request.get("id"))) {
                permissionResponse.set(request);
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
                     throw new AssertionError("host Swing UI delegate failed");
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            transport.awaitHandled(3);
            final Map<String, Object> result = object(
                permissionResponse.get().get("result")
            );
            final Map<String, Object> outcome = object(result.get("outcome"));
            assertEquals("cancelled", outcome.get("outcome"));
            assertTrue(client.listSessions(Duration.ofSeconds(2)).isEmpty());
            transport.awaitHandled(4);
        }
    }

    @Test
    void abandoningDerivedConfigUpdateReleasesItsPendingSlot() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> { });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            for (int attempt = 0; attempt < 70; attempt++) {
                final FxAcpClient.PendingConfigUpdate update =
                    client.setConfigOption("sess-1", "provider", "codex");
                client.abandon(update.request());
                assertTrue(update.result().isCompletedExceptionally() || update.result().isCancelled());
            }
        }
    }

    @Test
    void timedOutRequestsReleaseTheirPendingSlots() throws Exception {
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> { });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            for (int attempt = 0; attempt < 70; attempt++) {
                final FxAcpException failure = assertThrows(
                    FxAcpException.class,
                    () -> client.initialize(Duration.ZERO)
                );
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
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
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
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
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
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
            assertTrue(failure.getMessage().contains(diagnostic));
            assertFalse(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void reportsBoundedPrintablePreviewForInvalidAcpStdout() throws Exception {
        final String prefix = "launcher-output-";
        final String line = prefix + "x".repeat(180) + "TAIL";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.stdout(line)
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
            assertTrue(failure.getMessage().contains(prefix));
            assertFalse(failure.getMessage().contains("TAIL"));
            assertTrue(failure.getMessage().contains("first line:"));
        }
    }

    @Test
    void redactsKnownBearerFromInvalidAcpStdoutPreview() throws Exception {
        final String secret = "Bearer stdout-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            } else if ("session/list".equals(method)) {
                output.stdout("launch failed: stdout-secret");
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), new McpHttpConnection(
                URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", secret
            ), Duration.ofSeconds(2));
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.listSessions(Duration.ofSeconds(2))
            );
            assertFalse(failure.getMessage().contains("stdout-secret"));
            assertTrue(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void redactsSecretShapedInvalidAcpStdoutPreview() throws Exception {
        final String secret = "sensitive-validation-value";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) ->
            output.stdout("launcher token=" + secret)
        );
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            final FxAcpException failure = assertThrows(
                FxAcpException.class,
                () -> client.initialize(Duration.ofSeconds(2))
            );
            assertTrue(failure.getMessage().contains("first line: <redacted>"));
            assertFalse(failure.getMessage().contains(secret));
        }
    }

    @Test
    void redactsKnownBearerFromSessionListMetadata() throws Exception {
        final String secret = "Bearer session-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            } else if ("session/list".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessions", List.of(Map.of(
                        "sessionId", "sess-1",
                        "updatedAt", "session-secret"
                    ))
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), new McpHttpConnection(
                URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", secret
            ), Duration.ofSeconds(2));
            final List<FxAcpSessionSummary> sessions =
                client.listSessions(Duration.ofSeconds(2));
            assertEquals("<redacted>", sessions.get(0).updatedAt());
        }
    }

    @Test
    void redactsMcpBearerMaterialFromForwardedStderr() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final String secret = "Bearer local-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.stderr("problem token=local-secret header=" + secret);
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public void stderr(final FxAcpClient source, final String text) {
                     stderr.set(text);
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(
                Path.of("."),
                new McpHttpConnection(
                    URI.create("http://127.0.0.1:43123/mcp"),
                    "2025-11-25",
                    secret
                ),
                Duration.ofSeconds(2)
            );
            for (int count = 0; stderr.get() == null && count < 200; count++) {
                Thread.sleep(1L);
            }
            assertTrue(stderr.get() != null, "fake fx stderr was not forwarded");
            assertFalse(stderr.get().contains("local-secret"));
            assertTrue(stderr.get().contains("<redacted>"));
        }
    }

    @Test
    void redactsTruncatedStderrWhenItMayEndInsideTheBearer() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final String token = "s".repeat(128);
        final String secret = "Bearer " + token;
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.stderr("x".repeat(16 * 1024 - 32) + "Bearer " + token);
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public void stderr(final FxAcpClient source, final String text) {
                     stderr.set(text);
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(
                Path.of("."),
                new McpHttpConnection(
                    URI.create("http://127.0.0.1:43123/mcp"),
                    "2025-11-25",
                    secret
                ),
                Duration.ofSeconds(2)
            );
            for (int count = 0; stderr.get() == null && count < 200; count++) {
                Thread.sleep(1L);
            }
            assertEquals("<redacted>…", stderr.get());
            assertFalse(stderr.get().contains(token.substring(0, 32)));
        }
    }

    @Test
    void retainsPreviousBearerValuesForDelayedRedaction() throws Exception {
        final AtomicReference<String> stderr = new AtomicReference<>();
        final String previous = "Bearer previous-secret";
        final String current = "Bearer current-secret";
        final AtomicInteger sessions = new AtomicInteger();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-" + sessions.incrementAndGet(),
                    "configOptions", options("gateway", "model-a")
                ));
                if (sessions.get() == 2) {
                    output.stderr("old=previous-secret new=current-secret");
                }
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public void stderr(final FxAcpClient source, final String text) {
                     stderr.set(text);
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(Path.of("."), new McpHttpConnection(
                URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", previous
            ), Duration.ofSeconds(2));
            client.newSession(Path.of("."), new McpHttpConnection(
                URI.create("http://127.0.0.1:43123/mcp"), "2025-11-25", current
            ), Duration.ofSeconds(2));
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
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() { })) {
            client.initialize(Duration.ofSeconds(2));
            final FxAcpException failure = assertThrows(FxAcpException.class, () ->
                client.newSession(
                    Path.of("."),
                    new McpHttpConnection(
                        URI.create("http://127.0.0.1:43123/mcp"),
                        "2025-11-25",
                        secret
                    ),
                    Duration.ofSeconds(2)
                )
            );
            assertFalse(failure.getMessage().contains("rpc-secret"));
            assertTrue(failure.getMessage().contains("<redacted>"));
        }
    }

    @Test
    void permissionDetailsAreBoundedAndBearerRedacted() throws Exception {
        final AtomicReference<FxAcpListener.PermissionRequest> permission = new AtomicReference<>();
        final String secret = "Bearer permission-secret";
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            } else if ("session/prompt".equals(method)) {
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "sess-1",
                    "toolCall", Map.of(
                        "toolCallId", "call-1",
                        "title", "Run command",
                        "kind", "execute",
                        "rawInput", oversizedPermissionInput(secret)
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override
                 public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
                     permission.set(request);
                     return PermissionDecision.REJECT_ONCE;
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(
                Path.of("."),
                new McpHttpConnection(
                    URI.create("http://127.0.0.1:43123/mcp"),
                    "2025-11-25",
                    secret
                ),
                Duration.ofSeconds(2)
            );
            client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS);
            final FxAcpListener.PermissionRequest received = awaitPermission(permission);
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
        final AtomicReference<FxAcpListener.PermissionRequest> permission = new AtomicReference<>();
        try (ScriptedTransport transport = new ScriptedTransport((request, output) -> {
            final String method = string(request.get("method"));
            if ("initialize".equals(method)) {
                output.response((Long) request.get("id"), initializeResult());
            } else if ("session/new".equals(method)) {
                output.response((Long) request.get("id"), Map.of(
                    "sessionId", "sess-1",
                    "configOptions", options("gateway", "model-a")
                ));
            } else if ("session/prompt".equals(method)) {
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "agent_message_chunk",
                        "content", Map.of(
                            "type", "text",
                            "text", secret + " " + "x".repeat(300 * 1024)
                        )
                    )
                ));
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "agent_thought_chunk",
                        "content", Map.of(
                            "type", "text",
                            "text", secret + " " + "t".repeat(300 * 1024)
                        )
                    )
                ));
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "tool_call",
                        "toolCallId", "new-call " + secret,
                        "title", secret,
                        "kind", "edit " + secret,
                        "status", "pending " + secret
                    )
                ));
                output.notification("session/update", Map.of(
                    "sessionId", "sess-1",
                    "update", Map.of(
                        "sessionUpdate", "tool_call_update",
                        "toolCallId", "call " + secret,
                        "status", "running " + secret,
                        "content", List.of(Map.of(
                            "content", Map.of("type", "text", "text", secret)
                        ))
                    )
                ));
                output.request(99L, "session/request_permission", Map.of(
                    "sessionId", "sess-1",
                    "toolCall", Map.of(
                        "toolCallId", "permission " + secret,
                        "title", "Approve " + secret,
                        "kind", "execute " + secret,
                        "rawInput", Map.of("authorization", secret)
                    ),
                    "options", permissionOptions()
                ));
                output.response((Long) request.get("id"), Map.of("stopReason", "end_turn"));
            }
        });
             FxAcpClient client = new FxAcpClient(transport, new FxAcpListener() {
                 @Override public void agentText(
                     final FxAcpClient source,
                     final String sessionId,
                     final String text
                 ) { agentText.set(text); }
                 @Override public void agentThought(
                     final FxAcpClient source,
                     final String sessionId,
                     final String text
                 ) { agentThought.set(text); }
                 @Override public void toolCall(
                     final FxAcpClient source,
                     final String sessionId,
                     final String toolCallId,
                     final String title,
                     final String kind,
                     final String status
                 ) { toolCall.set(List.of(toolCallId, title, kind, status)); }
                 @Override public void toolCallUpdate(
                     final FxAcpClient source,
                     final String sessionId,
                     final String toolCallId,
                     final String status,
                     final String content
                 ) { toolUpdate.set(List.of(toolCallId, status, content)); }
                 @Override public PermissionDecision permission(
                     final FxAcpClient source,
                     final String sessionId,
                     final PermissionRequest request
                 ) {
                     permission.set(request);
                     return PermissionDecision.REJECT_ONCE;
                 }
             })) {
            client.initialize(Duration.ofSeconds(2));
            client.newSession(
                Path.of("."),
                new McpHttpConnection(
                    URI.create("http://127.0.0.1:43123/mcp"),
                    "2025-11-25",
                    secret
                ),
                Duration.ofSeconds(2)
            );
            assertEquals("end_turn", client.prompt("sess-1", "work").get(2, TimeUnit.SECONDS));
            for (int count = 0; permission.get() == null && count < 1000; count++) {
                Thread.sleep(1L);
            }
            assertRedacted(agentText.get());
            assertTrue(agentText.get().length() <= 256 * 1024);
            assertRedacted(agentThought.get());
            assertTrue(agentThought.get().length() <= 256 * 1024);
            assertOpaqueToolKey(toolCall.get().get(0));
            toolCall.get().subList(1, toolCall.get().size())
                .forEach(FxAcpClientTest::assertRedacted);
            assertOpaqueToolKey(toolUpdate.get().get(0));
            toolUpdate.get().subList(1, toolUpdate.get().size())
                .forEach(FxAcpClientTest::assertRedacted);
            assertRedacted(permission.get().title());
            assertRedacted(permission.get().kind());
            assertRedacted(permission.get().toolCallId());
            assertRedacted(permission.get().details());
        }
    }

    @Test
    void stockFxRefusesDefaultMcpOnlyLaunchMode() {
        final FxLaunchConfiguration configuration = new FxLaunchConfiguration(
            "fx", Path.of("."), FxSecurityMode.MCP_ONLY
        );

        assertThrows(IllegalStateException.class, () -> FxProcessTransport.start(configuration));
    }

    private static Map<String, Object> initializeResult() {
        return Map.of(
            "protocolVersion", 1L,
            "agentInfo", Map.of(
                "name", "fx",
                "version", FxAcpClient.SUPPORTED_FX_VERSION
            ),
            "agentCapabilities", Map.of(
                "loadSession", true,
                "sessionCapabilities", Map.of(
                    "list", Map.of(),
                    "resume", Map.of(),
                    "close", Map.of()
                )
            )
        );
    }

    private static McpHttpConnection connection() {
        return new McpHttpConnection(
            URI.create("http://127.0.0.1:43123/mcp"),
            "2025-11-25",
            "Bearer secret"
        );
    }

    private static List<Map<String, Object>> options(
        final String provider,
        final String model
    ) {
        return List.of(
            Map.of(
                "id", "provider",
                "name", "Provider",
                "category", "model",
                "type", "select",
                "currentValue", provider,
                "options", List.of(
                    Map.of("value", "gateway", "name", "Vercel AI Gateway"),
                    Map.of("value", "codex", "name", "Codex subscription")
                )
            ),
            Map.of(
                "id", "model",
                "name", "Model",
                "category", "model",
                "type", "select",
                "currentValue", model,
                "options", List.of(
                    Map.of("value", "model-a", "name", "model-a"),
                    Map.of("value", "model-b", "name", "model-b")
                )
            )
        );
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
            Map.of("optionId", "reject_once", "name", "Reject", "kind", "reject_once")
        );
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

    private static FxAcpListener.PermissionRequest awaitPermission(
        final AtomicReference<FxAcpListener.PermissionRequest> permission
    ) throws InterruptedException {
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

    private static final class ScriptedTransport implements FxAcpTransport {
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
            server = new Thread(() -> run(script), "fake-fx-acp");
            server.setDaemon(true);
            server.start();
        }

        private void run(final Script script) {
            try (BoundedLineReader lines = new BoundedLineReader(
                new InputStreamReader(serverStdin, StandardCharsets.UTF_8),
                FxAcpClient.MAX_ACP_LINE_CHARS
            );
                 BufferedWriter output = new BufferedWriter(new OutputStreamWriter(
                     serverStdout, StandardCharsets.UTF_8
                 ));
                 BufferedWriter errors = new BufferedWriter(new OutputStreamWriter(
                     serverStderr, StandardCharsets.UTF_8
                 ))) {
                final ScriptOutput writer = new ScriptOutput(output, errors);
                for (String line; (line = lines.readLine()) != null;) {
                    script.handle(object(StrictJson.parse(line.getBytes(StandardCharsets.UTF_8))), writer);
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
            assertTrue(handled >= count, "fake fx did not handle all expected messages");
        }

        @Override public InputStream stdout() { return clientStdout; }
        @Override public InputStream stderr() { return clientStderr; }
        @Override public OutputStream stdin() { return clientStdin; }
        @Override public boolean isAlive() { return alive; }

        @Override
        public void terminate(final Duration grace) {
            alive = false;
            try { clientStdin.close(); } catch (IOException ignored) { }
            try { serverStdout.close(); } catch (IOException ignored) { }
            try { serverStderr.close(); } catch (IOException ignored) { }
        }

        @Override
        public void close() {
            terminate(Duration.ZERO);
        }
    }

    private record ScriptOutput(BufferedWriter output, BufferedWriter errors) {
        private void response(final long id, final Object result) throws IOException {
            line(Map.of("jsonrpc", "2.0", "id", id, "result", result));
        }

        private void error(final long id, final String message) throws IOException {
            line(Map.of(
                "jsonrpc", "2.0",
                "id", id,
                "error", Map.of("code", -32000L, "message", message)
            ));
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

        private void line(final Object value) throws IOException {
            output.write(StrictJson.stringify(value));
            output.write('\n');
            output.flush();
        }
    }
}
