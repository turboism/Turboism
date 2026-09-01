package dev.turboism.plugin.mcp;

import dev.turboism.protocol.json.StrictJson;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.ArtMeshSnapshot;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.DeformerSnapshot;
import dev.turboism.sdk.cubism.DeformerType;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelObjectSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.PsdDocumentSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.SelectionSnapshot;
import dev.turboism.sdk.cubism.TextureAtlasSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.DocumentId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ProjectId;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.cubism.service.query.HierarchyNode;
import dev.turboism.sdk.cubism.service.query.ModelHierarchy;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterBounds;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterSummary;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.theme.ThemeStatusSnapshot;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.ui.workspace.WorkspaceService;
import dev.turboism.sdk.ui.workspace.layout.WorkspaceLayoutService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpHttpServerIntegrationTest {

    private static final String TOKEN = "test-token-0123456789-abcdef";
    private static final Map<URI, String> SESSIONS = new ConcurrentHashMap<>();

    @TempDir
    Path temporaryDirectory;

    @Test
    void exposesStableDefaultPortAndSupportsExplicitEphemeralBinding() throws Exception {
        assertEquals(43123, McpHttpServer.DEFAULT_PORT);
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices()
        ));
        try {
            assertTrue(server.endpoint().getPort() > 0);
        } finally {
            server.close();
        }
    }

    @Test
    void servesLifecycleAndObjectToolsOverAuthenticatedLoopbackHttp() throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.PART, "PartHead"),
            "Head",
            Optional.empty()
        ));
        final CapturingLogger logger = new CapturingLogger();
        final McpHttpServer server = McpHttpServer.start(dependencies(logger, objects, new FakeReadServices()));
        final Path connectionFile = server.connectionFile();
        try {
            assertEquals("127.0.0.1", server.endpoint().getHost());
            assertTrue(Files.isRegularFile(connectionFile));
            final Map<String, Object> connection = object(StrictJson.parse(Files.readAllBytes(connectionFile)));
            assertEquals(server.endpoint().toString(), connection.get("endpoint"));
            assertEquals("Bearer " + TOKEN, connection.get("authorization"));
            assertEquals(McpProtocol.VERSION, connection.get("protocolVersion"));

            final HttpResponse<byte[]> initialized = request(server.endpoint(), TOKEN, null, false, Map.of(
                "jsonrpc", "2.0",
                "id", 1,
                "method", "initialize",
                "params", Map.of(
                    "protocolVersion", McpProtocol.VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "integration-test", "version", "1.0")
                )
            ));
            assertEquals(200, initialized.statusCode());
            final Map<String, Object> initializeResult = result(initialized);
            assertEquals(McpProtocol.VERSION, initializeResult.get("protocolVersion"));
            final Map<String, Object> serverInfo = object(initializeResult.get("serverInfo"));
            assertEquals("turboism-mcp", serverInfo.get("name"));
            final String sessionId = initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
            SESSIONS.put(server.endpoint(), sessionId);

            final HttpResponse<byte[]> initializedNotification = request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            );
            assertEquals(202, initializedNotification.statusCode());

            final HttpResponse<byte[]> tools = request(server.endpoint(), TOKEN, null, true, sessionId, Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list",
                "params", Map.of()
            ));
            assertEquals(200, tools.statusCode());
            final List<Object> toolDefinitions = array(result(tools).get("tools"));
            assertEquals(5, toolDefinitions.size());
            assertEquals(
                McpProductionDomainCatalog.APPLY,
                object(toolDefinitions.get(0)).get("name")
            );

            final Map<String, Object> applied = structuredResult(toolCall(
                server.endpoint(),
                3,
                McpProductionDomainCatalog.APPLY,
                Map.of("operations", List.of(
                    Map.of(
                        "operation", "rename",
                        "kind", "part",
                        "id", "PartHead",
                        "name", "Head Renamed"
                    ),
                    Map.of(
                        "operation", "create",
                        "kind", "warp_deformer",
                        "name", "Face Warp",
                        "parent", Map.of("kind", "part", "id", "PartHead"),
                        "rows", 3,
                        "columns", 4,
                        "originX", -1,
                        "originY", -2,
                        "width", 2,
                        "height", 4
                    )
                ))
            ));
            assertEquals(Boolean.TRUE, applied.get("ok"));
            assertEquals(2L, integer(applied.get("succeeded")));
            final List<Object> appliedResults = array(applied.get("results"));
            final Map<String, Object> renameResult = object(
                object(appliedResults.get(0)).get("result")
            );
            assertEquals("APPLIED", renameResult.get("outcome"));
            assertEquals(Boolean.FALSE, renameResult.get("retryable"));
            final Map<String, Object> createResult = object(
                object(appliedResults.get(1)).get("result")
            );
            assertEquals("APPLIED", createResult.get("outcome"));
            assertEquals("Generated1", createResult.get("createdObjectId"));
            assertEquals("warp_deformer", createResult.get("kind"));
            assertEquals(Boolean.FALSE, createResult.get("retryable"));
            assertEquals("Head Renamed", objects.find(ModelObjectKind.PART, "PartHead").name());
            assertInstanceOf(ModelObjectCreateRequest.WarpDeformer.class, objects.lastCreate);
            final ModelObjectCreateRequest.WarpDeformer warp =
                (ModelObjectCreateRequest.WarpDeformer) objects.lastCreate;
            assertEquals(3, warp.grid().rows());
            assertEquals(4, warp.grid().columns());
            assertEquals(20, warp.grid().controlPoints().size());

            final HttpResponse<byte[]> resources = request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "id", 4, "method", "resources/list")
            );
            assertEquals(13, array(result(resources).get("resources")).size());
            final HttpResponse<byte[]> document = request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0",
                    "id", 5,
                    "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.ACTIVE_DOCUMENT)
                )
            );
            assertEquals(
                McpProductionDomainCatalog.ACTIVE_DOCUMENT,
                object(array(result(document).get("contents")).get(0)).get("uri")
            );

            final HttpResponse<byte[]> notification = request(
                server.endpoint(),
                TOKEN,
                null,
                true,
                sessionId,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            );
            assertEquals(202, notification.statusCode());
            assertEquals(0, notification.body().length);
        } finally {
            server.close();
        }
        assertFalse(Files.exists(connectionFile));
        assertTrue(logger.messages.stream().anyMatch(value -> value.contains("started")));
        assertTrue(logger.messages.stream().anyMatch(value -> value.contains("stopped")));
        assertFalse(logger.messages.stream().anyMatch(value -> value.contains(TOKEN)));
        assertFalse(logger.messages.stream().anyMatch(value -> value.contains(server.endpoint().toString())));
        assertFalse(logger.messages.stream().anyMatch(
            value -> value.contains(connectionFile.toAbsolutePath().toString())
        ));
    }

    @Test
    void rejectsSymlinkedConnectionFileWithoutTouchingItsTarget() throws Exception {
        final Path outside = temporaryDirectory.resolveSibling(
            temporaryDirectory.getFileName() + "-mcp-outside"
        );
        Files.writeString(outside, "sentinel", StandardCharsets.UTF_8);
        final Path connectionFile = temporaryDirectory.resolve("mcp-connection.json");
        try {
            Files.createSymbolicLink(connectionFile, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        }

        final McpHttpServer.McpStartupFailure failure = assertThrows(
            McpHttpServer.McpStartupFailure.class,
            () -> McpHttpServer.start(dependencies(
                new CapturingLogger(), new MutableObjects(), new FakeReadServices()
            ))
        );

        assertEquals("connection-file publication", failure.stage());
        assertTrue(Files.isSymbolicLink(connectionFile));
        assertEquals("sentinel", Files.readString(outside, StandardCharsets.UTF_8));
        Files.deleteIfExists(connectionFile);
        Files.deleteIfExists(outside);
    }

    @Test
    void enforcesStreamableHttpSessionLifecycle() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices()
        ));
        try {
            final HttpResponse<byte[]> get = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.endpoint())
                    .header("Accept", "text/event-stream")
                    .header("Authorization", "Bearer " + TOKEN)
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(405, get.statusCode());
            assertTrue(get.headers().firstValue("Allow").orElse("").contains("POST"));

            final HttpResponse<byte[]> initialize = request(
                server.endpoint(), TOKEN, null, false, Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of(
                        "protocolVersion", McpProtocol.VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "session-test", "version", "1")
                    )
                )
            );
            final String sessionId = initialize.headers().firstValue("MCP-Session-Id").orElseThrow();

            final HttpResponse<byte[]> beforeInitialized = request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list")
            );
            assertEquals(400, beforeInitialized.statusCode());

            assertEquals(202, request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            ).statusCode());
            assertEquals(200, request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list")
            ).statusCode());

            final HttpResponse<byte[]> deleted = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(server.endpoint())
                    .header("Authorization", "Bearer " + TOKEN)
                    .header("MCP-Session-Id", sessionId)
                    .DELETE()
                    .build(),
                HttpResponse.BodyHandlers.ofByteArray()
            );
            assertEquals(200, deleted.statusCode());
            assertEquals(404, request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "id", 4, "method", "ping")
            ).statusCode());
            final List<McpConnectionHistory.Entry> history = server.connectionHistory();
            assertEquals(List.of(
                McpConnectionHistory.Event.SESSION_CREATED,
                McpConnectionHistory.Event.SESSION_INITIALIZED,
                McpConnectionHistory.Event.REQUEST,
                McpConnectionHistory.Event.SESSION_CLOSED
            ), history.stream().map(McpConnectionHistory.Entry::event).toList());
            assertEquals("session-test", history.get(0).client());
            assertEquals("tools/list", history.get(2).detail());
            final String visible = history.stream()
                .map(entry -> entry.client() + " " + entry.detail())
                .collect(java.util.stream.Collectors.joining("\n"));
            assertFalse(visible.contains(TOKEN));
            assertFalse(visible.contains(sessionId));
        } finally {
            server.close();
        }
    }

    @Test
    void unsupportedProtocolVersionReturnsFxCompatibleNegotiationError() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            new FakeReadServices()
        ));
        try {
            final String requested = "2026-07-28";
            final HttpResponse<byte[]> response = request(
                server.endpoint(),
                TOKEN,
                null,
                requested,
                Map.of(
                    "jsonrpc", "2.0",
                    "id", 1,
                    "method", "server/discover",
                    "params", Map.of("_meta", Map.of())
                )
            );

            assertEquals(400, response.statusCode());
            assertEquals("application/json; charset=utf-8", response.headers()
                .firstValue("Content-Type").orElse(null));
            final Map<String, Object> envelope = object(StrictJson.parse(response.body()));
            assertEquals(null, envelope.get("id"));
            final Map<String, Object> error = object(envelope.get("error"));
            assertEquals(-32022L, integer(error.get("code")));
            assertEquals("Unsupported protocol version", error.get("message"));
            final Map<String, Object> data = object(error.get("data"));
            assertEquals(requested, data.get("requested"));
            assertEquals(
                List.of(McpProtocol.VERSION, "2025-06-18", "2025-03-26"),
                array(data.get("supported"))
            );
        } finally {
            server.close();
        }
    }

    @Test
    void doesNotCreateSessionForFailedInitialize() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices()
        ));
        try {
            final HttpResponse<byte[]> initialize = request(
                server.endpoint(), TOKEN, null, false, Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of(
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "invalid-test", "version", "1")
                    )
                )
            );
            assertEquals(200, initialize.statusCode());
            assertTrue(object(Json.parse(initialize.body())).containsKey("error"));
            assertTrue(initialize.headers().firstValue("MCP-Session-Id").isEmpty());
        } finally {
            server.close();
        }
    }

    @Test
    void bindsSessionToNegotiatedProtocolVersion() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices()
        ));
        try {
            final String olderVersion = "2025-03-26";
            final HttpResponse<byte[]> initialize = request(
                server.endpoint(), TOKEN, null, null, null, Map.of(
                    "jsonrpc", "2.0", "id", 1, "method", "initialize",
                    "params", Map.of(
                        "protocolVersion", olderVersion,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "version-test", "version", "1")
                    )
                )
            );
            assertEquals(olderVersion, result(initialize).get("protocolVersion"));
            final String sessionId = initialize.headers().firstValue("MCP-Session-Id").orElseThrow();

            assertEquals(202, request(
                server.endpoint(), TOKEN, null, olderVersion, sessionId,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            ).statusCode());
            assertEquals(200, request(
                server.endpoint(), TOKEN, null, olderVersion, sessionId,
                Map.of("jsonrpc", "2.0", "id", 2, "method", "ping")
            ).statusCode());
            assertEquals(400, request(
                server.endpoint(), TOKEN, null, McpProtocol.VERSION, sessionId,
                Map.of("jsonrpc", "2.0", "id", 3, "method", "ping")
            ).statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void pluginLifecyclePublishesAndRevokesTheAuthenticatedConnection() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        final RecordingUi ui = new RecordingUi();
        final CapturingLogger logger = new CapturingLogger();
        final PluginContext context = pluginContext(
            logger, new MutableObjects(), new FakeReadServices(), connections, ui
        );
        final McpPlugin plugin = new McpPlugin();
        plugin.init(context);

        plugin.enable();
        final McpHttpConnection published = connections.current.orElseThrow();
        final Path connectionFile = plugin.serverForTests().connectionFile();
        assertEquals(plugin.serverForTests().endpoint(), published.endpoint());
        assertEquals(McpProtocol.VERSION, published.protocolVersion());
        assertEquals(plugin.serverForTests().authorization(), published.authorization());
        assertTrue(published.authorization().startsWith("Bearer "));
        assertTrue(Files.isRegularFile(connectionFile));
        assertEquals(List.of(McpPlugin.CONNECTION_ACTION_ID), ui.actions.keySet().stream().toList());
        assertEquals(List.of("Turboism/MCP Connection"), ui.menus.stream()
            .map(MenuRegistry.MenuContribution::menuPath)
            .toList());

        plugin.disable();
        assertTrue(connections.current.isEmpty());
        assertTrue(ui.actions.isEmpty());
        assertTrue(ui.menus.isEmpty());
        assertFalse(Files.exists(connectionFile));
        assertEquals(1, connections.revocations);
        assertEquals(1, ui.actionRevocations);
        assertEquals(1, ui.menuRevocations);

        plugin.enable();
        assertTrue(connections.current.isPresent());
        assertTrue(ui.actions.containsKey(McpPlugin.CONNECTION_ACTION_ID));
        assertEquals(1, ui.menus.size());
        plugin.shutdown();
        assertTrue(connections.current.isEmpty());
        assertTrue(ui.actions.isEmpty());
        assertTrue(ui.menus.isEmpty());
        assertEquals(2, connections.revocations);
        assertEquals(2, ui.actionRevocations);
        assertEquals(2, ui.menuRevocations);
        assertFalse(logger.messages.stream().anyMatch(value -> value.contains(TOKEN)));
        assertFalse(logger.messages.stream().anyMatch(
            value -> value.contains(published.endpoint().toString())
        ));
        assertFalse(logger.messages.stream().anyMatch(
            value -> value.contains(connectionFile.toAbsolutePath().toString())
        ));
    }

    @Test
    void publicationFailureClosesTheStartedServerAndLeavesThePluginRetryable() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        connections.failNextPublish = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(pluginContext(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections
        ));

        assertThrows(IllegalStateException.class, plugin::enable);
        assertEquals(null, plugin.serverForTests());
        assertTrue(connections.current.isEmpty());
        assertFalse(Files.exists(temporaryDirectory.resolve("mcp-connection.json")));

        plugin.enable();
        assertTrue(connections.current.isPresent());
        plugin.shutdown();
        assertTrue(connections.current.isEmpty());
    }

    @Test
    void rejectsMissingTokenAndNonLoopbackOrigin() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            new FakeReadServices()
        ));
        try {
            final Map<String, Object> ping = Map.of(
                "jsonrpc", "2.0", "id", 1, "method", "ping"
            );
            assertEquals(
                401,
                request(server.endpoint(), "wrong-token-0123456789-abcdef", null, true, ping)
                    .statusCode()
            );
            assertEquals(
                403,
                request(server.endpoint(), TOKEN, "https://attacker.example", true, ping)
                    .statusCode()
            );
            ensureSession(server.endpoint());
            assertEquals(
                200,
                request(
                    server.endpoint(), TOKEN, "http://127.0.0.1", true,
                    SESSIONS.get(server.endpoint()), ping
                ).statusCode()
            );
        } finally {
            server.close();
        }
    }

    @Test
    void returnsStableToolErrorWhenStructuralProviderIsUnavailable() throws Exception {
        final ModelObjectService unavailable = new MutableObjects() {
            @Override public List<ModelObjectDescriptor> list() {
                throw unavailable();
            }

            @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw unavailable();
            }

            private ModelObjectOperationException unavailable() {
                return new ModelObjectOperationException(
                    ModelObjectOperationException.Code.UNAVAILABLE,
                    "structural provider is not verified"
                );
            }
        };
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(),
            unavailable,
            new FakeReadServices()
        ));
        try {
            final HttpResponse<byte[]> response = toolCall(
                server.endpoint(),
                6,
                McpProductionDomainCatalog.APPLY,
                Map.of("operations", List.of(Map.of(
                    "operation", "create",
                    "kind", "part",
                    "name", "Unavailable Part"
                )))
            );
            final Map<String, Object> result = object(result(response));
            assertEquals(Boolean.TRUE, result.get("isError"));
            final Map<String, Object> structured = object(result.get("structuredContent"));
            assertEquals(Boolean.FALSE, structured.get("ok"));
            final Map<String, Object> operation = object(array(structured.get("results")).get(0));
            final Map<String, Object> unavailableResult = object(operation.get("result"));
            assertEquals(
                "UNAVAILABLE",
                object(unavailableResult.get("error")).get("code")
            );
            assertEquals("OUTCOME_UNKNOWN", unavailableResult.get("outcome"));
            assertEquals(Boolean.FALSE, unavailableResult.get("retryable"));

            final String sessionId = SESSIONS.get(server.endpoint());
            final Map<String, Object> hierarchy = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 61, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.MODEL_HIERARCHY)
                )
            ));
            assertEquals("UNAVAILABLE", hierarchy.get("availability"));
            assertEquals(null, hierarchy.get("root"));
            assertEquals("MODEL_HIERARCHY_PROVIDER_UNAVAILABLE", hierarchy.get("diagnosticCode"));

            final Map<String, Object> overview = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 62, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.MODEL_OVERVIEW)
                )
            ));
            assertEquals("UNAVAILABLE", overview.get("availability"));
            assertEquals(null, overview.get("objects"));
            assertEquals("MODEL_OBJECT_PROVIDER_UNAVAILABLE", overview.get("diagnosticCode"));
        } finally {
            server.close();
        }
    }

    @Test
    void servesInspectionResourcesOverAuthenticatedLoopbackHttp() throws Exception {
        final FakeReadServices reads = new FakeReadServices();
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.PART, "PartBody"),
            "Body",
            Optional.empty()
        ));
        objects.put(new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.ART_MESH, "ArtMeshFace"),
            "Face",
            Optional.of(new ModelObjectReference(ModelObjectKind.PART, "PartBody"))
        ));
        reads.clipMasks.add(new ClipMaskRecord(
            "guid-face", "ArtMeshFace", "Face", false, List.of("guid-mask")
        ));
        final ParameterSnapshot parameter = new ParameterSnapshot(
            "ParamAngle", "Angle", 45.0, 0.0, -180.0, 180.0, true, true
        );
        final ModelSnapshot model = new ModelSnapshot(
            "ModelA", "Demo Model", List.of(parameter), List.of(parameter), List.of(), List.of()
        );
        final DocumentSnapshot document = new DocumentSnapshot(
            "DocA", "Demo Model", "Models/Demo.model3.json", Optional.empty(),
            Optional.of(model), DocumentKind.MODEL, Optional.empty(), Optional.empty()
        );
        reads.read.document(document);
        reads.read.model(model);

        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), objects, reads
        ));
        try {
            ensureSession(server.endpoint());
            final String sessionId = SESSIONS.get(server.endpoint());
            final HttpResponse<byte[]> listed = request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of("jsonrpc", "2.0", "id", 10, "method", "resources/list")
            );
            assertEquals(13, array(result(listed).get("resources")).size());

            final Map<String, Object> workspaceResource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 100, "method", "resources/read",
                    "params", Map.of("uri", McpDiagnosticsDomain.WORKSPACE)
                )
            ));
            assertEquals("UNAVAILABLE", workspaceResource.get("availability"));
            assertEquals("workspace.unavailable", workspaceResource.get("diagnosticCode"));

            final Map<String, Object> layoutResource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 101, "method", "resources/read",
                    "params", Map.of("uri", McpDiagnosticsDomain.WORKSPACE_LAYOUT)
                )
            ));
            assertEquals("UNAVAILABLE", layoutResource.get("availability"));
            assertEquals("workspace.layout.unavailable", layoutResource.get("diagnosticCode"));

            final Map<String, Object> diagnosticsResource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 102, "method", "resources/read",
                    "params", Map.of("uri", McpDiagnosticsDomain.DIAGNOSTICS)
                )
            ));
            assertEquals("1970-01-01T00:00:00Z", diagnosticsResource.get("createdAt"));
            assertEquals(List.of(), diagnosticsResource.get("problems"));
            assertEquals(Boolean.FALSE, diagnosticsResource.get("truncated"));

            final Map<String, Object> documentResource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 11, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.ACTIVE_DOCUMENT)
                )
            ));
            assertEquals(Boolean.TRUE, documentResource.get("ok"));
            assertEquals("DocA", object(documentResource.get("document")).get("documentId"));

            final Map<String, Object> hierarchyResource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 12, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.MODEL_HIERARCHY)
                )
            ));
            final Map<String, Object> hierarchyRoot = object(hierarchyResource.get("root"));
            assertEquals("AVAILABLE", hierarchyResource.get("availability"));
            assertEquals("active-model", hierarchyRoot.get("id"));
            final List<Object> hierarchyParts = array(hierarchyRoot.get("children"));
            assertEquals(1, hierarchyParts.size());
            assertEquals("PartBody", object(hierarchyParts.get(0)).get("id"));
            assertEquals(1, array(object(hierarchyParts.get(0)).get("children")).size());

            final Map<String, Object> overview = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 120, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.MODEL_OVERVIEW)
                )
            ));
            assertEquals("AVAILABLE", overview.get("availability"));
            assertEquals(2, array(overview.get("objects")).size());
            assertEquals(overview.get("objects"), overview.get("modelObjects"));

            final Map<String, Object> masks = resourceJson(request(
                server.endpoint(), TOKEN, null, true, sessionId,
                Map.of(
                    "jsonrpc", "2.0", "id", 13, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.CLIP_MASKS)
                )
            ));
            assertEquals(1L, integer(masks.get("count")));
        } finally {
            server.close();
        }
    }

    @Test
    void inspectionResourcesDoNotExposeFileSystemPaths() throws Exception {
        final FakeReadServices reads = new FakeReadServices();
        final ModelSnapshot model = new ModelSnapshot(
            "ModelA", "Demo Model", List.of(), List.of(), List.of(), List.of()
        );
        final DocumentSnapshot document = new DocumentSnapshot(
            "DocA", "Demo Model", "Models/Demo.cmo3", Optional.of(Path.of("Models/Demo.cmo3")),
            Optional.of(model), DocumentKind.MODEL, Optional.of("ContentA"), Optional.empty()
        );
        reads.read.project(new ProjectSnapshot(
            "ProjectA", "Demo Project", Optional.of(Path.of("Projects/Demo")),
            List.of(document), List.of(new ProjectContentSnapshot(
                "ContentA", "Demo Model", ProjectContentKind.MODEL,
                Optional.of(Path.of("Models/Demo.cmo3")), List.of("DocA"), List.of()
            ))
        ));
        reads.read.document(document);
        reads.read.model(model);

        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), reads
        ));
        try {
            ensureSession(server.endpoint());
            final Map<String, Object> resource = resourceJson(request(
                server.endpoint(), TOKEN, null, true, SESSIONS.get(server.endpoint()),
                Map.of(
                    "jsonrpc", "2.0", "id", 15, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.ACTIVE_DOCUMENT)
                )
            ));
            final String wire = Json.stringify(resource);
            assertFalse(wire.contains("projectDirectory"));
            assertFalse(wire.contains("filePath"));
            assertTrue(wire.contains("relativePath"));
        } finally {
            server.close();
        }
    }

    @Test
    void servesEmptyInspectionResources() throws Exception {
        final McpHttpServer server = McpHttpServer.start(dependencies(
            new CapturingLogger(), new MutableObjects(), new FakeReadServices()
        ));
        try {
            ensureSession(server.endpoint());
            final Map<String, Object> document = resourceJson(request(
                server.endpoint(), TOKEN, null, true, SESSIONS.get(server.endpoint()),
                Map.of(
                    "jsonrpc", "2.0", "id", 14, "method", "resources/read",
                    "params", Map.of("uri", McpProductionDomainCatalog.ACTIVE_DOCUMENT)
                )
            ));
            assertEquals(Boolean.TRUE, document.get("ok"));
            assertEquals(null, document.get("document"));
            assertEquals(null, document.get("model"));
        } finally {
            server.close();
        }
    }

    private HttpResponse<byte[]> toolCall(
        final URI endpoint,
        final int id,
        final String tool,
        final Map<String, Object> arguments
    ) throws Exception {
        ensureSession(endpoint);
        return request(endpoint, TOKEN, null, true, SESSIONS.get(endpoint), Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", tool, "arguments", arguments)
        ));
    }

    private static void ensureSession(final URI endpoint) throws Exception {
        if (SESSIONS.containsKey(endpoint)) return;
        final HttpResponse<byte[]> initialized = request(endpoint, TOKEN, null, false, Map.of(
            "jsonrpc", "2.0",
            "id", 0,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", McpProtocol.VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "integration-test", "version", "1.0")
            )
        ));
        final String sessionId = initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
        SESSIONS.put(endpoint, sessionId);
        final HttpResponse<byte[]> notification = request(
            endpoint, TOKEN, null, true, sessionId,
            Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
        );
        assertEquals(202, notification.statusCode());
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final boolean includeProtocolVersion,
        final Map<String, Object> body
    ) throws Exception {
        return request(endpoint, token, origin, includeProtocolVersion, null, body);
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final boolean includeProtocolVersion,
        final String sessionId,
        final Map<String, Object> body
    ) throws Exception {
        return request(
            endpoint,
            token,
            origin,
            includeProtocolVersion ? McpProtocol.VERSION : null,
            sessionId,
            body
        );
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final String protocolVersion,
        final Map<String, Object> body
    ) throws Exception {
        return request(endpoint, token, origin, protocolVersion, null, body);
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final String protocolVersion,
        final String sessionId,
        final Map<String, Object> body
    ) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(StrictJson.bytes(body)));
        if (origin != null) builder.header("Origin", origin);
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        if (sessionId != null) builder.header("MCP-Session-Id", sessionId);
        return HttpClient.newHttpClient().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private static Map<String, Object> result(final HttpResponse<byte[]> response) {
        final Map<String, Object> envelope = object(StrictJson.parse(response.body()));
        assertFalse(envelope.containsKey("error"), () -> new String(
            response.body(), StandardCharsets.UTF_8
        ));
        return object(envelope.get("result"));
    }

    private static Map<String, Object> structuredResult(
        final HttpResponse<byte[]> response
    ) {
        final Map<String, Object> toolResult = result(response);
        assertEquals(Boolean.FALSE, toolResult.get("isError"));
        return object(toolResult.get("structuredContent"));
    }

    private static Map<String, Object> resourceJson(
        final HttpResponse<byte[]> response
    ) {
        final Map<String, Object> read = result(response);
        final Map<String, Object> content = object(array(read.get("contents")).get(0));
        return object(Json.parse(((String) content.get("text")).getBytes(StandardCharsets.UTF_8)));
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                work.run();
                return () -> { };
            }
        };
    }

    private static Map<String, Object> object(final Object value) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new AssertionError("Expected JSON object but got " + value);
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        raw.forEach((key, item) -> result.put((String) key, item));
        return result;
    }

    private static List<Object> array(final Object value) {
        if (!(value instanceof List<?> values)) {
            throw new AssertionError("Expected JSON array but got " + value);
        }
        return new ArrayList<>(values);
    }

    private static long integer(final Object value) {
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected JSON number but got " + value);
        }
        return number.longValue();
    }

    private static double number(final Object value) {
        if (!(value instanceof Number number)) {
            throw new AssertionError("Expected JSON number but got " + value);
        }
        return number.doubleValue();
    }

    private McpHttpServer.Dependencies dependencies(
        final PluginLogger logger,
        final ModelObjectService objects,
        final FakeReadServices reads
    ) {
        return new McpHttpServer.Dependencies(
            logger,
            objects,
            reads.parameters,
            reads.hierarchy,
            reads.selection,
            reads.read,
            reads.clipMasks,
            immediateUi(),
            temporaryDirectory,
            0,
            TOKEN,
            120
        );
    }

    private PluginContext pluginContext(
        final PluginLogger logger,
        final ModelObjectService objects,
        final FakeReadServices reads,
        final McpConnectionService connections
    ) {
        return pluginContext(logger, objects, reads, connections, new RecordingUi());
    }

    private PluginContext pluginContext(
        final PluginLogger logger,
        final ModelObjectService objects,
        final FakeReadServices reads,
        final McpConnectionService connections,
        final RecordingUi ui
    ) {
        final PluginPaths paths = new PluginPaths() {
            @Override public Path dataDir() { return temporaryDirectory; }
            @Override public Path logsDir() { return temporaryDirectory; }
            @Override public Path stateDir() { return temporaryDirectory; }
            @Override public Path cacheDir() { return temporaryDirectory; }
        };
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] {PluginContext.class},
            (proxy, method, arguments) -> switch (method.getName()) {
                case "logger" -> logger;
                case "paths" -> paths;
                case "modelObjects" -> objects;
                case "parameterQuery" -> reads.parameters;
                case "modelHierarchyQuery" -> reads.hierarchy;
                case "selectionQuery" -> reads.selection;
                case "cubismRead" -> reads.read;
                case "cubismClipMasks" -> reads.clipMasks;
                case "cubism" -> McpHttpServer.Dependencies.unavailableCubism();
                case "workspace" -> WorkspaceService.unavailable();
                case "workspaceLayout" -> WorkspaceLayoutService.unavailable();
                case "diagnostics" -> McpHttpServer.Dependencies.emptyDiagnostics();
                case "editorCommands" -> EditorCommandService.unavailable();
                case "uiScheduler" -> immediateUi();
                case "mcpConnections" -> connections;
                case "actions" -> ui;
                case "menus" -> ui;
                case "localization" -> ui.localization;
                case "disposableScope" -> ui.scope;
                case "toString" -> "McpPluginTestContext";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                default -> throw new UnsupportedOperationException(
                    "unused PluginContext method: " + method.getName()
                );
            }
        );
    }

    static final class FakeReadServices {
        final FakeParameterQuery parameters = new FakeParameterQuery();
        final FakeHierarchyQuery hierarchy = new FakeHierarchyQuery();
        final FakeSelectionQuery selection = new FakeSelectionQuery();
        final FakeRead read = new FakeRead();
        final FakeClipMasks clipMasks = new FakeClipMasks();
    }

    static final class FakeParameterQuery implements ParameterQueryService {
        private final LinkedHashMap<String, ParameterSummary> values = new LinkedHashMap<>();

        void put(final ParameterSummary value) {
            values.put(value.id().value(), value);
        }

        @Override public Optional<ParameterSummary> findById(final ParameterId id) {
            return Optional.ofNullable(values.get(id.value()));
        }

        @Override public List<ParameterSummary> listAll() {
            return List.copyOf(values.values());
        }

        @Override public boolean exists(final ParameterId id) {
            return values.containsKey(id.value());
        }
    }

    static final class FakeHierarchyQuery implements ModelHierarchyQueryService {
        private final LinkedHashMap<String, HierarchyNode> nodes = new LinkedHashMap<>();

        void put(final HierarchyNode node) {
            nodes.put(node.id().value(), node);
        }

        @Override public Optional<ModelHierarchy> currentHierarchy() {
            final HierarchyNode root = nodes.values().stream()
                .filter(node -> node.parentId().isEmpty())
                .findFirst()
                .orElse(null);
            if (root == null) return Optional.empty();
            return Optional.of(new ModelHierarchy(root, List.copyOf(nodes.values())));
        }

        @Override public List<HierarchyNode> childrenOf(final ModelObjectId id) {
            final HierarchyNode node = nodes.get(id.value());
            if (node == null) return List.of();
            return node.childIds().stream()
                .map(child -> nodes.get(child.value()))
                .filter(Objects::nonNull)
                .toList();
        }

        @Override public Optional<HierarchyNode> findNode(final ModelObjectId id) {
            return Optional.ofNullable(nodes.get(id.value()));
        }
    }

    static final class FakeSelectionQuery implements SelectionQueryService {
        private SelectionSummary current = SelectionSummary.empty();

        void set(final SelectionSummary value) {
            current = value;
        }

        @Override public SelectionSummary currentSelection() {
            return current;
        }

        @Override public List<ModelObjectId> selectedIds(final HierarchyNode.Kind kind) {
            throw new UnsupportedOperationException("selectedIds is not used by MCP tools");
        }
    }

    static final class FakeRead implements CubismReadCapabilityService {
        private Optional<ProjectSnapshot> project = Optional.empty();
        private Optional<DocumentSnapshot> document = Optional.empty();
        private Optional<ModelSnapshot> model = Optional.empty();
        private SelectionSnapshot selection =
            new SelectionSnapshot(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
        private List<ParameterSnapshot> parameters = List.of();
        private List<ModelObjectSnapshot> modelObjects = List.of();
        private List<ArtMeshSnapshot> meshes = List.of();
        private List<DeformerSnapshot> deformers = List.of();
        private List<PsdDocumentSnapshot> psdDocuments = List.of();
        private List<ClipMaskSnapshot> clipMasks = List.of();
        private List<TextureAtlasSnapshot> textureAtlases = List.of();
        private Optional<RenderStatusSnapshot> renderStatus = Optional.empty();
        private Optional<WorkspaceSnapshot> workspace = Optional.empty();
        private Optional<ThemeStatusSnapshot> themeStatus = Optional.empty();

        void project(final ProjectSnapshot value) { project = Optional.of(value); }
        void document(final DocumentSnapshot value) { document = Optional.of(value); }
        void model(final ModelSnapshot value) { model = Optional.of(value); }
        void selection(final SelectionSnapshot value) { selection = value; }
        void parameters(final List<ParameterSnapshot> value) { parameters = value; }
        void modelObjects(final List<ModelObjectSnapshot> value) { modelObjects = value; }
        void meshes(final List<ArtMeshSnapshot> value) { meshes = value; }
        void deformers(final List<DeformerSnapshot> value) { deformers = value; }
        void psdDocuments(final List<PsdDocumentSnapshot> value) { psdDocuments = value; }
        void clipMasks(final List<ClipMaskSnapshot> value) { clipMasks = value; }
        void textureAtlases(final List<TextureAtlasSnapshot> value) { textureAtlases = value; }
        void renderStatus(final Optional<RenderStatusSnapshot> value) { renderStatus = value; }
        void workspace(final Optional<WorkspaceSnapshot> value) { workspace = value; }
        void themeStatus(final Optional<ThemeStatusSnapshot> value) { themeStatus = value; }

        @Override public Optional<ProjectSnapshot> activeProject() { return project; }
        @Override public Optional<DocumentSnapshot> activeDocument() { return document; }
        @Override public Optional<ModelSnapshot> activeModel() { return model; }
        @Override public SelectionSnapshot selection() { return selection; }
        @Override public List<ParameterSnapshot> parameters() { return parameters; }
        @Override public List<ModelObjectSnapshot> modelObjects() { return modelObjects; }
        @Override public List<ArtMeshSnapshot> meshes() { return meshes; }
        @Override public List<DeformerSnapshot> deformers() { return deformers; }
        @Override public List<PsdDocumentSnapshot> psdDocuments() { return psdDocuments; }
        @Override public List<ClipMaskSnapshot> clipMasks() { return clipMasks; }
        @Override public List<TextureAtlasSnapshot> textureAtlases() { return textureAtlases; }
        @Override public Optional<RenderStatusSnapshot> renderStatus() { return renderStatus; }
        @Override public Optional<WorkspaceSnapshot> workspace() { return workspace; }
        @Override public Optional<ThemeStatusSnapshot> themeStatus() { return themeStatus; }
    }

    static final class FakeClipMasks implements CubismClipMaskService {
        private final ArrayList<ClipMaskRecord> records = new ArrayList<>();

        void add(final ClipMaskRecord record) {
            records.add(record);
        }

        @Override public List<ClipMaskRecord> collectClipMaskRecords() {
            return List.copyOf(records);
        }
    }
    private static class MutableObjects implements ModelObjectService {
        private final LinkedHashMap<ModelObjectReference, ModelObjectDescriptor> values =
            new LinkedHashMap<>();
        private final AtomicInteger generated = new AtomicInteger();
        ModelObjectCreateRequest lastCreate;
        ModelObjectReference lastReparentTarget;
        ModelObjectReference lastReparentParent;
        int lastReparentIndex = Integer.MIN_VALUE;
        ModelObjectReference lastDelete;
        ModelObjectDeletePolicy lastDeletePolicy;

        void put(final ModelObjectDescriptor value) {
            values.put(value.reference(), value);
        }

        ModelObjectDescriptor find(final ModelObjectKind kind, final String id) {
            final ModelObjectDescriptor result = values.get(new ModelObjectReference(kind, id));
            if (result == null) throw new NoSuchElementException(id);
            return result;
        }

        boolean contains(final ModelObjectKind kind, final String id) {
            return values.containsKey(new ModelObjectReference(kind, id));
        }

        @Override public List<ModelObjectDescriptor> list() {
            return List.copyOf(values.values());
        }

        @Override public ModelObjectDescriptor rename(
            final ModelObjectReference target,
            final String name
        ) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            final ModelObjectDescriptor renamed = new ModelObjectDescriptor(
                current.reference(), name, current.parent()
            );
            values.put(target, renamed);
            return renamed;
        }

        @Override public ModelObjectDescriptor reparent(
            final ModelObjectReference target,
            final ModelObjectReference parent,
            final int index
        ) {
            final ModelObjectDescriptor current = values.get(target);
            if (current == null) throw new NoSuchElementException(target.id());
            if (!values.containsKey(parent)) throw new NoSuchElementException(parent.id());
            lastReparentTarget = target;
            lastReparentParent = parent;
            lastReparentIndex = index;
            final ModelObjectDescriptor reparented = new ModelObjectDescriptor(
                current.reference(), current.name(), Optional.of(parent)
            );
            values.put(target, reparented);
            return reparented;
        }

        @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
            lastCreate = request;
            final ModelObjectReference reference = new ModelObjectReference(
                request.kind(),
                "Generated" + generated.incrementAndGet()
            );
            final ModelObjectDescriptor created = new ModelObjectDescriptor(
                reference, request.name(), request.parent()
            );
            values.put(reference, created);
            return created;
        }

        @Override public void delete(
            final ModelObjectReference target,
            final ModelObjectDeletePolicy policy
        ) {
            lastDelete = target;
            lastDeletePolicy = policy;
            if (values.remove(target) == null) throw new NoSuchElementException(target.id());
        }
    }

    private static final class RecordingUi implements ActionRegistry, MenuRegistry {
        private final LinkedHashMap<String, Action> actions = new LinkedHashMap<>();
        private final ArrayList<MenuContribution> menus = new ArrayList<>();
        private final DisposableScope scope = new DisposableScope();
        private final PluginLocalization localization = new PluginLocalization() {
            @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
            @Override public String text(final String key) {
                return "menu.connection".equals(key) ? "MCP Connection" : key;
            }
            @Override public String format(final String key, final Object... arguments) {
                return text(key);
            }
            @Override public boolean contains(final String key) {
                return "menu.connection".equals(key);
            }
        };
        private int actionRevocations;
        private int menuRevocations;

        @Override public Registration register(final String id, final Action action) {
            if (!id.equals(action.id())) throw new IllegalArgumentException("action id mismatch");
            if (actions.putIfAbsent(id, action) != null) {
                throw new IllegalStateException("fixture action already registered");
            }
            final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                actions.remove(id, action);
                actionRevocations++;
            };
        }

        @Override public Registration contribute(final MenuContribution contribution) {
            menus.add(contribution);
            final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                menus.remove(contribution);
                menuRevocations++;
            };
        }
    }

    private static final class RecordingConnections implements McpConnectionService {
        private Optional<McpHttpConnection> current = Optional.empty();
        private boolean failNextPublish;
        private int revocations;

        @Override public Optional<McpHttpConnection> current() { return current; }

        @Override
        public Registration publish(final McpHttpConnection connection) {
            if (failNextPublish) {
                failNextPublish = false;
                throw new IllegalStateException("fixture publication failure");
            }
            if (current.isPresent()) {
                throw new IllegalStateException("fixture already has a connection");
            }
            current = Optional.of(connection);
            final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
            return () -> {
                if (!closed.compareAndSet(false, true)) return;
                current = Optional.empty();
                revocations++;
            };
        }
    }

    private static final class CapturingLogger implements PluginLogger {
        private final List<String> messages = new ArrayList<>();

        @Override public void debug(final String message) { messages.add(message); }
        @Override public void info(final String message) { messages.add(message); }
        @Override public void warn(final String message) { messages.add(message); }
        @Override public void error(final String message) { messages.add(message); }
        @Override public void error(final String message, final Throwable throwable) {
            messages.add(message);
        }
    }
}
