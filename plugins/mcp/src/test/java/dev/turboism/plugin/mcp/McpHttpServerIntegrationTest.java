package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpHttpServerTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.cubism.DocumentKind;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.ParameterSnapshot;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpConnectionService;
import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.io.IOException;
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
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

final class McpHttpServerIntegrationTest {

    private static final Map<URI, String> SESSIONS = new ConcurrentHashMap<>();

    @TempDir
    Path temporaryDirectory;

    private String bearer() throws IOException {
        return Files.readString(temporaryDirectory.resolve(McpAccessToken.FILE_NAME), StandardCharsets.UTF_8)
                .strip();
    }

    @Test
    void exposesStableDefaultPortAndSupportsExplicitEphemeralBinding() throws Exception {
        assertEquals(43123, McpHttpServer.DEFAULT_PORT);
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            assertTrue(server.endpoint().getPort() > 0);
        } finally {
            server.close();
        }
    }

    @Test
    void startsTransportWhenExactLegacyEditorHasNoOptionalTransactionBackend() throws Exception {
        final var original = dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices());
        final var unavailable = new dev.turboism.sdk.cubism.CubismEditorApiUnavailableException(
                "dev.turboism.sdk.cubism.CubismFacade#authoringTransactions()",
                Optional.of("5.0.02"),
                List.of("5.2.03"));
        final var legacy = new McpHttpServer.Dependencies(
                original.logger(),
                original.modelObjects(),
                original.hierarchyQuery(),
                original.selectionQuery(),
                original.read(),
                original.clipMasks(),
                McpOptionalTransactionTest.facade(unavailable),
                original.history(),
                original.workspace(),
                original.workspaceLayout(),
                original.diagnostics(),
                original.editorCommands(),
                original.uiScheduler(),
                original.stateDir(),
                original.port(),
                original.requestsPerMinute(),
                original.requireAuthForRead());
        try (final McpHttpServer server = McpHttpServer.start(legacy)) {
            assertTrue(server.endpoint().getPort() > 0);
            assertTrue(Files.isRegularFile(temporaryDirectory.resolve("mcp-connection.json")));
        }
        assertFalse(Files.exists(temporaryDirectory.resolve("mcp-connection.json")));
    }

    @Test
    void acceptsCredentialFreeLoopbackReadsAndPublishesNoSecretMaterial() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final String connectionText = Files.readString(server.connectionFile());
            final Map<String, Object> connection =
                    object(Json.parseObject(connectionText.getBytes(StandardCharsets.UTF_8)));
            assertFalse(connection.containsKey("authorization"));
            final Map<String, Object> authentication = object(connection.get("authentication"));
            assertEquals("bearer", authentication.get("scheme"));
            assertEquals(McpAccessToken.FILE_NAME, authentication.get("tokenFile"));
            assertFalse(connectionText.contains(bearer()));

            final HttpRequest request = HttpRequest.newBuilder(server.endpoint())
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "protocolVersion", McpProtocol.VERSION,
                                    "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "no-auth-agent", "version", "1.0"))))))
                    .build();
            final HttpResponse<byte[]> response =
                    HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());

            assertEquals(200, response.statusCode());
            assertEquals(McpProtocol.VERSION, result(response).get("protocolVersion"));
        } finally {
            server.close();
        }
    }

    @Test
    void servesLifecycleAndObjectToolsOverLoopbackHttp() throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"), "Head", Optional.empty()));
        final CapturingLogger logger = new CapturingLogger();
        final McpHttpServer server = McpHttpServer.start(dependencies(logger, objects, new FakeReadServices()));
        final Path connectionFile = server.connectionFile();
        try {
            assertEquals("127.0.0.1", server.endpoint().getHost());
            assertTrue(Files.isRegularFile(connectionFile));
            final Map<String, Object> connection = object(Json.parseObject(Files.readAllBytes(connectionFile)));
            assertEquals(server.endpoint().toString(), connection.get("endpoint"));
            assertFalse(connection.containsKey("authorization"));
            assertEquals(McpProtocol.VERSION, connection.get("protocolVersion"));

            final HttpResponse<byte[]> initialized = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    false,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "protocolVersion", McpProtocol.VERSION,
                                    "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "integration-test", "version", "1.0"))));
            assertEquals(200, initialized.statusCode());
            final Map<String, Object> initializeResult = result(initialized);
            assertEquals(McpProtocol.VERSION, initializeResult.get("protocolVersion"));
            final Map<String, Object> serverInfo = object(initializeResult.get("serverInfo"));
            assertEquals("turboism-mcp", serverInfo.get("name"));
            final String sessionId =
                    initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
            SESSIONS.put(server.endpoint(), sessionId);

            final HttpResponse<byte[]> initializedNotification = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            assertEquals(202, initializedNotification.statusCode());

            final HttpResponse<byte[]> tools = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", Map.of()));
            assertEquals(200, tools.statusCode());
            final List<Object> toolDefinitions =
                    new ArrayList<>(array(result(tools).get("tools")));
            assertEquals(50, toolDefinitions.size());
            Object toolCursor = result(tools).get("nextCursor");
            assertInstanceOf(String.class, toolCursor);
            while (toolCursor != null) {
                var page = request(
                        server.endpoint(),
                        bearer(),
                        null,
                        true,
                        sessionId,
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                202,
                                "method",
                                "tools/list",
                                "params",
                                Map.of("cursor", toolCursor)));
                assertEquals(200, page.statusCode());
                toolDefinitions.addAll(array(result(page).get("tools")));
                toolCursor = result(page).get("nextCursor");
            }
            assertEquals(80, toolDefinitions.size());
            assertEquals(
                    McpProductionDomainCatalog.APPLY,
                    object(toolDefinitions.get(0)).get("name"));
            final Set<Object> toolNames = toolDefinitions.stream()
                    .map(McpHttpServerIntegrationTest::object)
                    .map(definition -> definition.get("name"))
                    .collect(java.util.stream.Collectors.toSet());
            assertTrue(toolNames.containsAll(Set.of(
                    McpAssetSourceDomain.SOURCES,
                    McpAssetSourceDomain.RELATIONS,
                    McpContinuityDomain.VALIDATE,
                    McpRigTransferDomain.PLAN,
                    McpBindingInitializationDomain.PLAN,
                    McpBindingInitializationDomain.APPLY,
                    McpBindingInitializationDomain.RESULT,
                    McpDocumentLifecycleDomain.READ,
                    McpDocumentLifecycleDomain.CREATE,
                    McpDocumentLifecycleDomain.RESULT,
                    McpMeshCreationDomain.TRIANGULATE,
                    McpMeshCreationDomain.PLAN,
                    McpMeshCreationDomain.CREATE,
                    McpMeshCreationDomain.RESULT,
                    McpParameterStructureDomain.READ,
                    McpParameterStructureDomain.PLAN,
                    McpParameterStructureDomain.APPLY,
                    McpParameterStructureDomain.RESULT,
                    McpFileInspectDomain.INSPECT,
                    McpFileInspectDomain.RESULT,
                    McpDocumentFileDomain.SAVE_AS,
                    McpDocumentFileDomain.RESULT,
                    McpFileGrantDomain.REQUEST,
                    McpFileGrantDomain.STATUS,
                    McpFileGrantDomain.REVOKE,
                    McpSourcePropertiesDomain.READ,
                    McpSourcePropertiesDomain.WRITE,
                    McpSourcePropertiesDomain.RESULT,
                    McpEvaluatedMeshDomain.READ,
                    McpGeometryValidationDomain.VALIDATE,
                    McpRasterValidationSchemas.TOOL,
                    McpWarpValidationDomain.VALIDATE,
                    McpMeshRigDomain.PLAN,
                    McpMaskDomain.READ,
                    McpMaskDomain.WRITE,
                    McpMaskDomain.RESULT,
                    McpGlueDomain.GLUES_READ,
                    McpGlueDomain.GLUES_WRITE,
                    McpHistoryCommandDomain.HISTORY_READ,
                    McpHistoryCommandDomain.HISTORY_UNDO,
                    McpHistoryCommandDomain.HISTORY_REDO,
                    McpTransactionDomain.TRANSACTION_EXECUTE,
                    McpCapabilitiesDomain.CAPABILITIES_READ,
                    "turboism.textures.read",
                    "turboism.textures.write",
                    McpRenderDomain.STATE,
                    McpRenderDomain.CAPTURE,
                    McpStoredAuthoringDomain.MESHES_READ,
                    McpStoredAuthoringDomain.WARPS_READ,
                    McpStoredAuthoringDomain.ROTATIONS_READ,
                    McpKeyformWriteDomain.WRITE,
                    McpKeyformWriteDomain.PREVIEW,
                    McpImageBindingDomain.READ,
                    McpImageRegistrationDomain.START,
                    McpAtlasDomain.READ,
                    McpAtlasDomain.PLAN,
                    McpMocExportDomain.PLAN,
                    McpMocExportDomain.RESULT,
                    McpAtlasDomain.RESULT,
                    McpImageRegistrationDomain.RESULT,
                    McpImageRegistrationDomain.CANCEL,
                    McpAtlasDomain.APPLY,
                    McpAtlasDomain.CANCEL,
                    McpMocExportDomain.EXECUTE,
                    McpMocExportDomain.CANCEL,
                    McpImageBindingDomain.BIND,
                    McpImageBindingDomain.RESULT,
                    McpKeyformWriteDomain.RESULT,
                    McpPoseGridDomain.SCAN,
                    McpPoseGridDomain.STATUS,
                    McpPoseGridDomain.CANCEL,
                    McpPoseGridDomain.RESULT,
                    McpPoseGridDomain.RELEASE)));
            assertFalse(toolNames.contains("turboism.history.move"));
            final var missingStructureEnvelope = result(toolCall(
                    server.endpoint(),
                    9001,
                    McpParameterStructureDomain.RESULT,
                    Map.of("operationId", "missing-structure-operation")));
            assertEquals(Boolean.TRUE, missingStructureEnvelope.get("isError"));
            final var missingStructure = object(missingStructureEnvelope.get("structuredContent"));
            assertEquals("OUTCOME_UNKNOWN", missingStructure.get("outcome"));
            assertEquals("NOT_FOUND", missingStructure.get("code"));
            assertEquals(List.of(), missingStructure.get("createdGroups"));
            final var missingMeshEnvelope = result(toolCall(
                    server.endpoint(),
                    9002,
                    McpMeshCreationDomain.RESULT,
                    Map.of("operationId", "missing-mesh-operation")));
            assertEquals(Boolean.TRUE, missingMeshEnvelope.get("isError"));
            final var missingMesh = object(missingMeshEnvelope.get("structuredContent"));
            assertEquals("OUTCOME_UNKNOWN", missingMesh.get("outcome"));
            assertEquals("NOT_FOUND", missingMesh.get("code"));
            assertEquals(List.of(), missingMesh.get("createdMeshes"));
            assertEquals(Boolean.FALSE, missingMesh.get("renderVerified"));
            final var missingDocumentEnvelope = result(toolCall(
                    server.endpoint(),
                    9003,
                    McpDocumentLifecycleDomain.RESULT,
                    Map.of("operationId", "missing-document-operation")));
            assertEquals(Boolean.TRUE, missingDocumentEnvelope.get("isError"));
            final var missingDocument = object(missingDocumentEnvelope.get("structuredContent"));
            assertEquals("OUTCOME_UNKNOWN", missingDocument.get("outcome"));
            assertEquals("NOT_FOUND", missingDocument.get("code"));
            assertEquals(Boolean.FALSE, missingDocument.get("authoringReady"));

            final Map<String, Object> applied = structuredResult(toolCall(
                    server.endpoint(),
                    3,
                    McpProductionDomainCatalog.APPLY,
                    Map.of(
                            "operations",
                            List.of(
                                    Map.of(
                                            "operation", "rename",
                                            "kind", "part",
                                            "id", "PartHead",
                                            "name", "Head Renamed"),
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
                                            "height", 4)))));
            assertEquals(Boolean.TRUE, applied.get("ok"));
            assertEquals(2L, integer(applied.get("succeeded")));
            final List<Object> appliedResults = array(applied.get("results"));
            final Map<String, Object> renameResult =
                    object(object(appliedResults.get(0)).get("result"));
            assertEquals("APPLIED", renameResult.get("outcome"));
            assertEquals(Boolean.FALSE, renameResult.get("retryable"));
            final Map<String, Object> createResult =
                    object(object(appliedResults.get(1)).get("result"));
            assertEquals("APPLIED", createResult.get("outcome"));
            assertEquals("Generated1", createResult.get("createdObjectId"));
            assertEquals("warp_deformer", createResult.get("kind"));
            assertEquals(Boolean.FALSE, createResult.get("retryable"));
            assertEquals(
                    "Head Renamed",
                    objects.find(ModelObjectKind.PART, "PartHead").name());
            assertInstanceOf(ModelObjectCreateRequest.WarpDeformer.class, objects.lastCreate);
            final ModelObjectCreateRequest.WarpDeformer warp =
                    (ModelObjectCreateRequest.WarpDeformer) objects.lastCreate;
            assertEquals(3, warp.grid().rows());
            assertEquals(4, warp.grid().columns());
            assertEquals(20, warp.grid().controlPoints().size());

            final HttpResponse<byte[]> resources = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "id", 4, "method", "resources/list"));
            assertEquals(15, array(result(resources).get("resources")).size());
            final HttpResponse<byte[]> document = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            5,
                            "method",
                            "resources/read",
                            "params",
                            Map.of("uri", McpProductionDomainCatalog.ACTIVE_DOCUMENT)));
            assertEquals(
                    McpProductionDomainCatalog.ACTIVE_DOCUMENT,
                    object(array(result(document).get("contents")).get(0)).get("uri"));

            final HttpResponse<byte[]> notification = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
            assertEquals(202, notification.statusCode());
            assertEquals(0, notification.body().length);
        } finally {
            server.close();
        }
        assertFalse(Files.exists(connectionFile));
        assertTrue(logger.messages.stream().anyMatch(value -> value.contains("started")));
        assertTrue(logger.messages.stream().anyMatch(value -> value.contains("stopped")));
        final String publishedToken = bearer();
        assertFalse(logger.messages.stream().anyMatch(value -> value.contains(publishedToken)));
        assertFalse(logger.messages.stream()
                .anyMatch(value -> value.contains(server.endpoint().toString())));
        assertFalse(logger.messages.stream()
                .anyMatch(
                        value -> value.contains(connectionFile.toAbsolutePath().toString())));
    }

    @Test
    void allThreeLegacyApplyToolsAreDiscoverablyExcludedFromTransactions() throws Exception {
        try (McpHttpServer server = McpHttpServer.start(
                dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()))) {
            final Map<String, Object> capabilities = structuredResult(
                    toolCall(server.endpoint(), 90, McpCapabilitiesDomain.CAPABILITIES_READ, Map.of()));
            final Map<Object, Map<String, Object>> registrations = new java.util.LinkedHashMap<>();
            for (Object raw : array(capabilities.get("operations"))) {
                final Map<String, Object> registration = object(raw);
                registrations.put(registration.get("name"), registration);
            }
            for (String name : List.of(
                    McpProductionDomainCatalog.APPLY,
                    McpParameterDomain.PARAMETERS_APPLY,
                    McpParameterDomain.BINDINGS_APPLY)) {
                assertEquals(Boolean.FALSE, registrations.get(name).get("transactionEligible"));
                final Map<String, Object> envelope = result(toolCall(
                        server.endpoint(),
                        91,
                        McpTransactionDomain.TRANSACTION_EXECUTE,
                        Map.of(
                                "label",
                                "Excluded legacy operation",
                                "steps",
                                List.of(Map.of("id", "legacy", "tool", name, "arguments", Map.of())))));
                assertEquals(Boolean.TRUE, envelope.get("isError"));
                final Map<String, Object> rejected = object(envelope.get("structuredContent"));
                assertEquals("REJECTED_REQUEST", rejected.get("outcome"));
                assertEquals("mcp.transaction.tool_not_eligible", rejected.get("diagnosticId"));
            }
        }
    }

    @ParameterizedTest(name = "malformed write rejected with code {0}")
    @CsvSource({"-32602, malformed_tail", "-32600, invalid_id"})
    void invalidStructuralWriteIsRejectedOverHttpBeforeAnyRename(long expectedErrorCode, String caseKind)
            throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"), "Head", Optional.empty()));
        try (McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices()))) {
            ensureSession(server.endpoint());
            final Object requestId = "invalid_id".equals(caseKind) ? List.of(100) : 100;
            final Object operations = "malformed_tail".equals(caseKind)
                    ? List.of(Map.of("operation", "rename", "kind", "part", "id", "PartHead", "name", "Changed"), 7)
                    : List.of(Map.of("operation", "rename", "kind", "part", "id", "PartHead", "name", "Changed"));
            final HttpResponse<byte[]> response = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    SESSIONS.get(server.endpoint()),
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            requestId,
                            "method",
                            "tools/call",
                            "params",
                            Map.of(
                                    "name",
                                    McpProductionDomainCatalog.APPLY,
                                    "arguments",
                                    Map.of("operations", operations))));
            assertEquals(200, response.statusCode());
            final Map<String, Object> body = object(Json.parseObject(response.body()));
            assertEquals(expectedErrorCode, integer(object(body.get("error")).get("code")));
            assertEquals("Head", objects.find(ModelObjectKind.PART, "PartHead").name());
        }
    }

    @ParameterizedTest(name = "rejects symlinked {0} without touching target")
    @CsvSource({
        "connection-file, mcp-connection.json, -mcp-outside, connection-file publication",
        "access-token, mcp.token, -token-outside, access-token publication"
    })
    void rejectsSymlinkedProtectedFileWithoutTouchingItsTarget(
            String name, String fileName, String suffix, String expectedStage) throws Exception {
        final Path outside = temporaryDirectory.resolveSibling(temporaryDirectory.getFileName() + suffix);
        Files.writeString(outside, "sentinel", StandardCharsets.UTF_8);
        final Path targetFile = temporaryDirectory.resolve(fileName);
        try {
            Files.createSymbolicLink(targetFile, outside);
        } catch (UnsupportedOperationException | IOException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "symbolic links unavailable");
        }

        final McpHttpServer.McpStartupFailure failure = assertThrows(
                McpHttpServer.McpStartupFailure.class,
                () -> McpHttpServer.start(
                        dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices())));

        assertEquals(expectedStage, failure.stage());
        assertTrue(Files.isSymbolicLink(targetFile));
        assertEquals("sentinel", Files.readString(outside, StandardCharsets.UTF_8));
        Files.deleteIfExists(targetFile);
        Files.deleteIfExists(outside);
    }

    @Test
    void enforcesStreamableHttpSessionLifecycle() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final HttpResponse<byte[]> get = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("Accept", "text/event-stream")
                                    .GET()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(405, get.statusCode());
            assertTrue(get.headers().firstValue("Allow").orElse("").contains("POST"));

            final HttpResponse<byte[]> initialize = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    false,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "protocolVersion", McpProtocol.VERSION,
                                    "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "session-test", "version", "1"))));
            final String sessionId =
                    initialize.headers().firstValue("MCP-Session-Id").orElseThrow();

            final HttpResponse<byte[]> beforeInitialized = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
            assertEquals(400, beforeInitialized.statusCode());

            assertEquals(
                    202,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    true,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                            .statusCode());
            assertEquals(
                    200,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    true,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/list"))
                            .statusCode());

            final HttpResponse<byte[]> deleted = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("MCP-Session-Id", sessionId)
                                    .header("Authorization", "Bearer " + bearer())
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, deleted.statusCode());
            assertEquals(
                    404,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    true,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "id", 4, "method", "ping"))
                            .statusCode());
            final List<McpConnectionHistory.Entry> history = server.connectionHistory();
            assertEquals(
                    List.of(
                            McpConnectionHistory.Event.SESSION_CREATED,
                            McpConnectionHistory.Event.SESSION_INITIALIZED,
                            McpConnectionHistory.Event.REQUEST,
                            McpConnectionHistory.Event.SESSION_CLOSED),
                    history.stream().map(McpConnectionHistory.Entry::event).toList());
            assertEquals("session-test", history.get(0).client());
            assertEquals("tools/list", history.get(2).detail());
            final String visible = history.stream()
                    .map(entry -> entry.client() + " " + entry.detail())
                    .collect(java.util.stream.Collectors.joining("\n"));
            final String publishedToken = bearer();
            assertFalse(visible.contains(publishedToken));
            assertFalse(visible.contains(sessionId));
        } finally {
            server.close();
        }
    }

    @Test
    void unsupportedProtocolVersionReturnsFxCompatibleNegotiationError() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final String requested = "2026-07-28";
            final HttpResponse<byte[]> response = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    requested,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "server/discover",
                            "params",
                            Map.of("_meta", Map.of())));

            assertEquals(400, response.statusCode());
            assertEquals(
                    "application/json; charset=utf-8",
                    response.headers().firstValue("Content-Type").orElse(null));
            final Map<String, Object> envelope = object(Json.parseObject(response.body()));
            assertEquals(null, envelope.get("id"));
            final Map<String, Object> error = object(envelope.get("error"));
            assertEquals(-32022L, integer(error.get("code")));
            assertEquals("Unsupported protocol version", error.get("message"));
            final Map<String, Object> data = object(error.get("data"));
            assertEquals(requested, data.get("requested"));
            assertEquals(List.of(McpProtocol.VERSION, "2025-06-18", "2025-03-26"), array(data.get("supported")));
        } finally {
            server.close();
        }
    }

    @Test
    void doesNotCreateSessionForFailedInitialize() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final HttpResponse<byte[]> initialize = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    false,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "invalid-test", "version", "1"))));
            assertEquals(200, initialize.statusCode());
            assertTrue(object(Json.parseObject(initialize.body())).containsKey("error"));
            assertTrue(initialize.headers().firstValue("MCP-Session-Id").isEmpty());
        } finally {
            server.close();
        }
    }

    @Test
    void bindsSessionToNegotiatedProtocolVersion() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final String olderVersion = "2025-03-26";
            final HttpResponse<byte[]> initialize = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    null,
                    null,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            1,
                            "method",
                            "initialize",
                            "params",
                            Map.of(
                                    "protocolVersion", olderVersion,
                                    "capabilities", Map.of(),
                                    "clientInfo", Map.of("name", "version-test", "version", "1"))));
            assertEquals(olderVersion, result(initialize).get("protocolVersion"));
            final String sessionId =
                    initialize.headers().firstValue("MCP-Session-Id").orElseThrow();

            assertEquals(
                    202,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    olderVersion,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
                            .statusCode());
            assertEquals(
                    200,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    olderVersion,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "id", 2, "method", "ping"))
                            .statusCode());
            assertEquals(
                    400,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    null,
                                    McpProtocol.VERSION,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "id", 3, "method", "ping"))
                            .statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void queuedConnectionOpenAndDeferredCleanupStayInTheirOwnGeneration() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(java.awt.GraphicsEnvironment.isHeadless());
        final var scheduler = new TestUiScheduler();
        scheduler.queueOpens = true;
        final var ui = new RecordingUi();
        final var base = pluginContext(
                new CapturingLogger(), new MutableObjects(), new FakeReadServices(), new RecordingConnections(), ui);
        final var plugin = new McpPlugin();
        plugin.init(withScheduler(base, scheduler));
        plugin.enable();
        ui.actions.get(McpPlugin.CONNECTION_ACTION_ID).handler().accept(new ActionRegistry.ActionContext() {});
        assertEquals(1, scheduler.opens.size());
        plugin.disable();
        plugin.enable();
        onEdt(scheduler.opens.remove(0));
        assertEquals(null, field(plugin, "window"));
        ui.actions.get(McpPlugin.CONNECTION_ACTION_ID).handler().accept(new ActionRegistry.ActionContext() {});
        onEdt(scheduler.opens.remove(0));
        final var old = (java.awt.Window) field(field(plugin, "window"), "frame");
        scheduler.fenced = true;
        scheduler.deferTeardown = true;
        plugin.disable();
        assertTrue(old.isDisplayable());
        assertEquals(1, scheduler.teardowns);
        scheduler.fenced = false;
        plugin.enable();
        ui.actions.get(McpPlugin.CONNECTION_ACTION_ID).handler().accept(new ActionRegistry.ActionContext() {});
        onEdt(scheduler.opens.remove(0));
        final var fresh = (java.awt.Window) field(field(plugin, "window"), "frame");
        try {
            onEdt(scheduler.cleanups.get(0));
            onEdt(scheduler.cleanups.get(0));
            assertFalse(old.isDisplayable());
            assertTrue(fresh.isDisplayable());
        } finally {
            scheduler.deferTeardown = false;
            onEdt(() -> {
                try {
                    plugin.shutdown();
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            });
            plugin.disable();
        }
        assertFalse(fresh.isDisplayable());
        assertEquals(2, scheduler.teardowns);
    }

    private static final class TestUiScheduler implements dev.turboism.sdk.ui.UiScheduler {
        boolean fenced;
        boolean deferTeardown;
        boolean queueOpens;
        int teardowns;
        final java.util.List<Runnable> opens = new java.util.ArrayList<>();
        final java.util.List<Runnable> cleanups = new java.util.ArrayList<>();

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThread(final Runnable work) {
            if (!fenced) {
                if (queueOpens) opens.add(work);
                else javax.swing.SwingUtilities.invokeLater(work);
            }
            return () -> {};
        }

        @Override
        public dev.turboism.sdk.plugin.Registration runOnUiThreadLater(
                final Runnable work, final java.time.Duration delay) {
            return runOnUiThread(work);
        }

        @Override
        public boolean runUiTeardown(final Runnable cleanup, final java.time.Duration timeout) {
            teardowns++;
            if (deferTeardown) {
                cleanups.add(cleanup);
                return false;
            }
            onEdt(cleanup);
            return true;
        }
    }

    private static void onEdt(final Runnable work) {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) work.run();
        else {
            try {
                javax.swing.SwingUtilities.invokeAndWait(work);
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
        }
    }

    private static PluginContext withScheduler(final PluginContext base, final TestUiScheduler scheduler) {
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(), new Class<?>[] {PluginContext.class}, (proxy, method, args) -> {
                    if (method.getName().equals("uiScheduler")) return scheduler;
                    try {
                        return method.invoke(base, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private static Object field(final Object target, final String name) throws Exception {
        final var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    void pluginLifecyclePublishesAndRevokesTheConnection() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        final RecordingUi ui = new RecordingUi();
        final CapturingLogger logger = new CapturingLogger();
        final PluginContext context =
                pluginContext(logger, new MutableObjects(), new FakeReadServices(), connections, ui);
        final McpPlugin plugin = new McpPlugin();
        plugin.init(context);

        plugin.enable();
        final McpHttpConnection published = connections.current.orElseThrow();
        final Path connectionFile = plugin.serverForTests().connectionFile();
        assertEquals(plugin.serverForTests().endpoint(), published.endpoint());
        assertEquals(McpProtocol.VERSION, published.protocolVersion());
        assertTrue(Files.isRegularFile(connectionFile));
        assertEquals(
                List.of(McpPlugin.CONNECTION_ACTION_ID),
                ui.actions.keySet().stream().toList());
        assertEquals(
                List.of("Turboism/MCP Connection"),
                ui.menus.stream().map(MenuRegistry.MenuContribution::menuPath).toList());

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
        final String publishedToken = bearer();
        assertFalse(logger.messages.stream().anyMatch(value -> value.contains(publishedToken)));
        assertFalse(logger.messages.stream()
                .anyMatch(value -> value.contains(published.endpoint().toString())));
        assertFalse(logger.messages.stream()
                .anyMatch(
                        value -> value.contains(connectionFile.toAbsolutePath().toString())));
    }

    @Test
    void publicationFailureClosesTheStartedServerAndLeavesThePluginRetryable() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        connections.failNextPublish = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections));

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
    void actionFailureDuringEnableRollsBackPublicationAndClosesServer() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        final RecordingUi ui = new RecordingUi();
        ui.failNextAction = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(
                pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections, ui));

        assertThrows(IllegalStateException.class, plugin::enable);
        assertEquals(null, plugin.serverForTests());
        assertTrue(connections.current.isEmpty());
        assertEquals(1, connections.revocations, "publication must be revoked on partial enable failure");
    }

    @Test
    void menuFailureDuringEnableRollsBackActionAndPublicationAndClosesServer() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        final RecordingUi ui = new RecordingUi();
        ui.failNextMenu = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(
                pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections, ui));

        assertThrows(IllegalStateException.class, plugin::enable);
        assertEquals(null, plugin.serverForTests());
        assertTrue(connections.current.isEmpty());
        assertEquals(1, connections.revocations, "publication must be revoked on partial enable failure");
        assertEquals(1, ui.actionRevocations, "action must be revoked on partial enable failure");
    }

    @Test
    void stopUnpublishesRegistrationsBeforeClosingServer() throws Exception {
        final List<String> order = new ArrayList<>();
        final RecordingConnections connections = new RecordingConnections();
        connections.closeListener = order::add;
        final RecordingUi ui = new RecordingUi();
        ui.closeListener = order::add;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(
                pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections, ui));
        plugin.enable();

        final Path connectionFile = plugin.serverForTests().connectionFile();
        assertTrue(Files.isRegularFile(connectionFile));

        plugin.disable();

        assertEquals(List.of("unpublish-menu", "unpublish-action", "unpublish-connection"), order);
        assertFalse(Files.exists(connectionFile));
    }

    @Test
    void closeFailureDuringDisableClosesServerAndCleansUp() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        connections.failOnClose = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections));
        plugin.enable();

        final Path connectionFile = plugin.serverForTests().connectionFile();
        assertTrue(Files.isRegularFile(connectionFile));

        final Exception error = assertThrows(Exception.class, plugin::disable);
        assertEquals("connection unpublish failure", error.getMessage());

        assertEquals(null, plugin.serverForTests());
        assertFalse(Files.exists(connectionFile), "HTTP server must be stopped on close failure");
    }

    @Test
    void closeFailureDuringShutdownClearsContext() throws Exception {
        final RecordingConnections connections = new RecordingConnections();
        connections.failOnClose = true;
        final McpPlugin plugin = new McpPlugin();
        plugin.init(pluginContext(new CapturingLogger(), new MutableObjects(), new FakeReadServices(), connections));
        plugin.enable();

        final Exception error = assertThrows(Exception.class, plugin::shutdown);
        assertEquals("connection unpublish failure", error.getMessage());

        assertThrows(
                IllegalStateException.class,
                plugin::context,
                "context must be cleared after shutdown even on close failure");
    }

    @Test
    void readMethodsIgnoreBearerAndWritesRequireIt() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final Map<String, Object> ping = Map.of("jsonrpc", "2.0", "id", 1, "method", "ping");
            ensureSession(server.endpoint());
            assertEquals(
                    200,
                    requestWithAuthorization(
                                    server.endpoint(),
                                    "Bearer ignored-authorization-value",
                                    SESSIONS.get(server.endpoint()),
                                    ping)
                            .statusCode());
            assertEquals(
                    403,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    "https://attacker.example",
                                    true,
                                    SESSIONS.get(server.endpoint()),
                                    ping)
                            .statusCode());
            assertEquals(
                    200,
                    request(
                                    server.endpoint(),
                                    bearer(),
                                    "http://127.0.0.1",
                                    true,
                                    SESSIONS.get(server.endpoint()),
                                    ping)
                            .statusCode());

            final Map<String, Object> writeCall = Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    9,
                    "method",
                    "tools/call",
                    "params",
                    Map.of("name", McpProductionDomainCatalog.APPLY, "arguments", Map.of("operations", List.of())));
            final HttpResponse<byte[]> unauthenticated =
                    request(server.endpoint(), null, null, true, SESSIONS.get(server.endpoint()), writeCall);
            assertEquals(401, unauthenticated.statusCode());
            assertTrue(unauthenticated
                    .headers()
                    .firstValue("WWW-Authenticate")
                    .orElse("")
                    .contains("Bearer"));
            assertEquals(
                    401,
                    requestWithAuthorization(
                                    server.endpoint(), "Bearer wrong-token", SESSIONS.get(server.endpoint()), writeCall)
                            .statusCode());
            assertEquals(
                    200,
                    requestWithAuthorization(
                                    server.endpoint(), "Bearer " + bearer(), SESSIONS.get(server.endpoint()), writeCall)
                            .statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void rigTransferPlanRemainsReadOnlyWithoutBearer() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            var args = new LinkedHashMap<String, Object>(Map.of(
                    "documentId",
                    "unbound-document",
                    "modelId",
                    "unbound-model",
                    "operationId",
                    "transfer",
                    "kind",
                    "warp_deformer",
                    "policy",
                    "REPLACE_FROM_SOURCE_DEFAULT_DELTAS",
                    "source",
                    Map.of("objectId", "source", "snapshotId", "missing-source"),
                    "target",
                    Map.of("objectId", "target", "snapshotId", "missing-target"),
                    "axes",
                    List.of(Map.of(
                            "sourceParameterGuid",
                            "source-axis",
                            "targetParameterGuid",
                            "target-axis",
                            "keys",
                            List.of(
                                    Map.of("sourceValue", 0, "targetValue", 0),
                                    Map.of("sourceValue", 1, "targetValue", 1)))),
                    "deltaTransform",
                    Map.of("xx", 1, "xy", 0, "yx", 0, "yy", 1)));
            args.put("sourcePointIndices", List.of(0, 1, 2, 3));
            var response = request(
                    server.endpoint(),
                    null,
                    null,
                    true,
                    SESSIONS.get(server.endpoint()),
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            101,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpRigTransferDomain.PLAN, "arguments", args)));
            assertEquals(200, response.statusCode());
            var output = object(result(response).get("structuredContent"));
            assertEquals(false, output.get("ok"));
            assertTrue(
                    Set.of("STALE_STATE", "NO_ACTIVE_MODEL", "UNAVAILABLE")
                            .contains(object(output.get("error")).get("code")),
                    output.toString());
        } finally {
            server.close();
        }
    }

    @Test
    void continuityDiagnosticsRemainReadOnlyWithoutBearer() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            var args = Map.of(
                    "documentId",
                    "unbound-document",
                    "modelId",
                    "unbound-model",
                    "kind",
                    "art_mesh",
                    "objectId",
                    "mesh",
                    "snapshotId",
                    "missing",
                    "maxFindings",
                    10,
                    "thresholds",
                    Map.of(
                            "maximumPositionSlopeChange",
                            1,
                            "maximumAngleSlopeChange",
                            1,
                            "maximumScaleSlopeChange",
                            1));
            var response = request(
                    server.endpoint(),
                    null,
                    null,
                    true,
                    SESSIONS.get(server.endpoint()),
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            100,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpContinuityDomain.VALIDATE, "arguments", args)));
            assertEquals(200, response.statusCode());
            var output = object(result(response).get("structuredContent"));
            assertEquals(false, output.get("ok"));
            assertTrue(
                    Set.of("STALE_STATE", "NO_ACTIVE_MODEL", "UNAVAILABLE")
                            .contains(object(output.get("error")).get("code")),
                    output.toString());
        } finally {
            server.close();
        }
    }

    @Test
    void assetMetadataReadToolsRemainReadOnlyWithoutBearer() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            for (String name : List.of(McpAssetSourceDomain.SOURCES, McpAssetSourceDomain.RELATIONS)) {
                var args = new LinkedHashMap<String, Object>(
                        Map.of("documentId", "unbound-document", "modelId", "unbound-model"));
                if (name.equals(McpAssetSourceDomain.SOURCES))
                    args.put("query", Map.of("artMeshIds", List.of(), "modelImageIds", List.of()));
                var response = request(
                        server.endpoint(),
                        null,
                        null,
                        true,
                        SESSIONS.get(server.endpoint()),
                        Map.of(
                                "jsonrpc",
                                "2.0",
                                "id",
                                99,
                                "method",
                                "tools/call",
                                "params",
                                Map.of("name", name, "arguments", args)));
                assertEquals(200, response.statusCode());
                var output = object(result(response).get("structuredContent"));
                assertEquals(false, output.get("ok"));
                assertTrue(
                        Set.of("STALE_STATE", "NO_ACTIVE_MODEL", "UNAVAILABLE").contains(output.get("error")),
                        output.toString());
            }
        } finally {
            server.close();
        }
    }

    @Test
    void keyformWriteRequiresBearerWhileResultQueryRemainsARead() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            final String session = SESSIONS.get(server.endpoint());
            final var write = Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    71,
                    "method",
                    "tools/call",
                    "params",
                    Map.of("name", McpKeyformWriteDomain.WRITE, "arguments", Map.of()));
            assertEquals(
                    401,
                    request(server.endpoint(), null, null, true, session, write).statusCode());
            for (String name : List.of(
                    McpPoseGridDomain.SCAN,
                    McpPoseGridDomain.CANCEL,
                    McpPoseGridDomain.RELEASE,
                    McpMaskDomain.WRITE,
                    McpSourcePropertiesDomain.WRITE,
                    McpImageBindingDomain.BIND,
                    McpBindingInitializationDomain.APPLY,
                    McpImageRegistrationDomain.START,
                    McpImageRegistrationDomain.CANCEL,
                    McpAtlasDomain.APPLY,
                    McpAtlasDomain.CANCEL,
                    McpMocExportDomain.EXECUTE,
                    McpMocExportDomain.CANCEL,
                    McpFileGrantDomain.REQUEST,
                    McpFileGrantDomain.REVOKE,
                    McpDocumentFileDomain.SAVE_AS,
                    McpFileInspectDomain.INSPECT)) {
                var mutating = Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        73,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", name, "arguments", Map.of("operationId", "not-retained")));
                assertEquals(
                        401,
                        request(server.endpoint(), null, null, true, session, mutating)
                                .statusCode());
            }
            for (String name : List.of(
                    McpPoseGridDomain.STATUS,
                    McpPoseGridDomain.RESULT,
                    McpMaskDomain.RESULT,
                    McpSourcePropertiesDomain.RESULT,
                    McpImageBindingDomain.RESULT,
                    McpBindingInitializationDomain.RESULT,
                    McpImageRegistrationDomain.RESULT,
                    McpAtlasDomain.RESULT,
                    McpMocExportDomain.RESULT,
                    McpFileGrantDomain.STATUS,
                    McpDocumentFileDomain.RESULT,
                    McpFileInspectDomain.RESULT)) {
                var queryPose = Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        74,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", name, "arguments", Map.of("operationId", "not-retained")));
                var resultPose = request(server.endpoint(), null, null, true, session, queryPose);
                assertEquals(200, resultPose.statusCode());
                var structured = object(result(resultPose).get("structuredContent"));
                if (name.equals(McpFileGrantDomain.STATUS)
                        || name.equals(McpFileInspectDomain.RESULT)
                        || name.equals(McpImageRegistrationDomain.RESULT)
                        || name.equals(McpAtlasDomain.RESULT)
                        || name.equals(McpMocExportDomain.RESULT)) assertEquals("NOT_FOUND", structured.get("error"));
                else if (name.equals(McpDocumentFileDomain.RESULT))
                    assertEquals("OUTCOME_UNKNOWN", structured.get("error"));
                else assertEquals("OUTCOME_UNKNOWN", structured.get("outcome"));
            }
            final var query = Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    72,
                    "method",
                    "tools/call",
                    "params",
                    Map.of("name", McpKeyformWriteDomain.RESULT, "arguments", Map.of("operationId", "not-retained")));
            final var read = request(server.endpoint(), null, null, true, session, query);
            assertEquals(200, read.statusCode());
            assertEquals(true, result(read).get("isError"));
            final var output = object(result(read).get("structuredContent"));
            assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
            assertEquals("NOT_FOUND", output.get("code"));
        } finally {
            server.close();
        }
    }

    @Test
    void committedCreateReturnsStableIdAndReadbackWarningWithoutInvitingRetry() throws Exception {
        final ModelObjectReference committed = new ModelObjectReference(ModelObjectKind.PART, "PartCommitted");
        final ModelObjectService objects = new MutableObjects() {
            @Override
            public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw new ModelObjectOperationException(
                        ModelObjectOperationException.Code.COMMITTED,
                        "descriptor readback failed",
                        new IllegalStateException("readback unavailable"),
                        Optional.of(committed));
            }
        };
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices()));
        try {
            final HttpResponse<byte[]> response = toolCall(
                    server.endpoint(),
                    60,
                    McpProductionDomainCatalog.APPLY,
                    Map.of(
                            "operations",
                            List.of(Map.of(
                                    "operation", "create",
                                    "kind", "part",
                                    "name", "Committed Part"))));
            final Map<String, Object> envelope = object(result(response));
            assertEquals(Boolean.FALSE, envelope.get("isError"));
            final Map<String, Object> batch = object(envelope.get("structuredContent"));
            assertEquals(Boolean.TRUE, batch.get("ok"));
            final Map<String, Object> result =
                    object(object(array(batch.get("results")).get(0)).get("result"));
            assertEquals("APPLIED_WITH_READBACK_WARNING", result.get("outcome"));
            assertEquals(Boolean.FALSE, result.get("retryable"));
            assertEquals("PartCommitted", result.get("createdObjectId"));
            assertEquals("part", result.get("kind"));
            // The wire warning is sanitized fixed text; the internal exception
            // detail stays in the server-side log.
            assertEquals(
                    "The create applied but post-commit verification reported a warning",
                    result.get("readbackWarning"));
            assertNotNull(result.get("diagnosticId"));

            final Map<String, Object> diagnostics = readResourceJson(
                    server, SESSIONS.get(server.endpoint()), 61, McpDiagnosticsDomain.RUNTIME_DIAGNOSTICS);
            final Map<String, Object> event =
                    object(array(diagnostics.get("events")).get(0));
            assertEquals(result.get("diagnosticId"), event.get("diagnosticId"));
            assertEquals("create", event.get("operation"));
            assertEquals("APPLIED_WITH_READBACK_WARNING", event.get("outcome"));
        } finally {
            server.close();
        }
    }

    @Test
    void returnsStableToolErrorWhenStructuralProviderIsUnavailable() throws Exception {
        final ModelObjectService unavailable = new MutableObjects() {
            @Override
            public List<ModelObjectDescriptor> list() {
                throw unavailable();
            }

            @Override
            public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw unavailable();
            }

            private ModelObjectOperationException unavailable() {
                return new ModelObjectOperationException(
                        ModelObjectOperationException.Code.UNAVAILABLE, "structural provider is not verified");
            }
        };
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), unavailable, new FakeReadServices()));
        try {
            final HttpResponse<byte[]> response = toolCall(
                    server.endpoint(),
                    6,
                    McpProductionDomainCatalog.APPLY,
                    Map.of(
                            "operations",
                            List.of(Map.of(
                                    "operation", "create",
                                    "kind", "part",
                                    "name", "Unavailable Part"))));
            final Map<String, Object> result = object(result(response));
            assertEquals(Boolean.TRUE, result.get("isError"));
            final Map<String, Object> structured = object(result.get("structuredContent"));
            assertEquals(Boolean.FALSE, structured.get("ok"));
            final Map<String, Object> operation =
                    object(array(structured.get("results")).get(0));
            final Map<String, Object> unavailableResult = object(operation.get("result"));
            assertEquals("UNAVAILABLE", object(unavailableResult.get("error")).get("code"));
            assertEquals("OUTCOME_UNKNOWN", unavailableResult.get("outcome"));
            assertEquals(Boolean.FALSE, unavailableResult.get("retryable"));

            final String sessionId = SESSIONS.get(server.endpoint());
            final Map<String, Object> hierarchy =
                    readResourceJson(server, sessionId, 61, McpProductionDomainCatalog.MODEL_HIERARCHY);
            assertEquals("UNAVAILABLE", hierarchy.get("availability"));
            assertEquals(null, hierarchy.get("root"));
            assertEquals("MODEL_HIERARCHY_PROVIDER_UNAVAILABLE", hierarchy.get("diagnosticCode"));

            final Map<String, Object> overview =
                    readResourceJson(server, sessionId, 62, McpProductionDomainCatalog.MODEL_OVERVIEW);
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
                new ModelObjectReference(ModelObjectKind.PART, "PartBody"), "Body", Optional.empty()));
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.ART_MESH, "ArtMeshFace"),
                "Face",
                Optional.of(new ModelObjectReference(ModelObjectKind.PART, "PartBody"))));
        reads.clipMasks.add(new ClipMaskRecord("guid-face", "ArtMeshFace", "Face", false, List.of("guid-mask")));
        final ParameterSnapshot parameter =
                new ParameterSnapshot("ParamAngle", "Angle", 45.0, 0.0, -180.0, 180.0, true, true);
        final ModelSnapshot model =
                new ModelSnapshot("ModelA", "Demo Model", List.of(parameter), List.of(parameter), List.of(), List.of());
        final DocumentSnapshot document = new DocumentSnapshot(
                "DocA",
                "Demo Model",
                "Models/Demo.model3.json",
                Optional.empty(),
                Optional.of(model),
                DocumentKind.MODEL,
                Optional.empty(),
                Optional.empty());
        reads.read.document(document);
        reads.read.model(model);

        final McpHttpServer server = McpHttpServer.start(dependencies(new CapturingLogger(), objects, reads));
        try {
            ensureSession(server.endpoint());
            final String sessionId = SESSIONS.get(server.endpoint());
            final HttpResponse<byte[]> listed = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    sessionId,
                    Map.of("jsonrpc", "2.0", "id", 10, "method", "resources/list"));
            assertEquals(15, array(result(listed).get("resources")).size());

            final Map<String, Object> workspaceResource =
                    readResourceJson(server, sessionId, 100, McpDiagnosticsDomain.WORKSPACE);
            assertEquals("UNAVAILABLE", workspaceResource.get("availability"));
            assertEquals("workspace.unavailable", workspaceResource.get("diagnosticCode"));

            final Map<String, Object> layoutResource =
                    readResourceJson(server, sessionId, 101, McpDiagnosticsDomain.WORKSPACE_LAYOUT);
            assertEquals("UNAVAILABLE", layoutResource.get("availability"));
            assertEquals("workspace.layout.unavailable", layoutResource.get("diagnosticCode"));

            final Map<String, Object> diagnosticsResource =
                    readResourceJson(server, sessionId, 102, McpDiagnosticsDomain.DIAGNOSTICS);
            assertEquals("startup", diagnosticsResource.get("kind"));
            assertEquals("turboism", diagnosticsResource.get("provider"));
            assertEquals(null, diagnosticsResource.get("createdAt"));
            assertTrue(diagnosticsResource.containsKey("asOf"));
            assertEquals(List.of(), diagnosticsResource.get("problems"));
            assertEquals(Boolean.FALSE, diagnosticsResource.get("truncated"));

            final Map<String, Object> runtimeDiagnostics =
                    readResourceJson(server, sessionId, 103, McpDiagnosticsDomain.RUNTIME_DIAGNOSTICS);
            assertEquals("runtime", runtimeDiagnostics.get("kind"));
            assertEquals("turboism-mcp", runtimeDiagnostics.get("provider"));
            assertFalse(runtimeDiagnostics.containsKey("createdAt"));
            assertTrue(runtimeDiagnostics.containsKey("asOf"));
            assertEquals(List.of(), runtimeDiagnostics.get("events"));
            assertEquals(Boolean.FALSE, runtimeDiagnostics.get("truncated"));
            assertEquals(0L, integer(runtimeDiagnostics.get("dropped")));

            final Map<String, Object> documentResource =
                    readResourceJson(server, sessionId, 11, McpProductionDomainCatalog.ACTIVE_DOCUMENT);
            assertEquals(Boolean.TRUE, documentResource.get("ok"));
            assertEquals("DocA", object(documentResource.get("document")).get("documentId"));

            final Map<String, Object> hierarchyResource =
                    readResourceJson(server, sessionId, 12, McpProductionDomainCatalog.MODEL_HIERARCHY);
            final Map<String, Object> hierarchyRoot = object(hierarchyResource.get("root"));
            assertEquals("AVAILABLE", hierarchyResource.get("availability"));
            assertEquals("active-model", hierarchyRoot.get("id"));
            final List<Object> hierarchyParts = array(hierarchyRoot.get("children"));
            assertEquals(1, hierarchyParts.size());
            assertEquals("PartBody", object(hierarchyParts.get(0)).get("id"));
            assertEquals(1, array(object(hierarchyParts.get(0)).get("children")).size());

            final Map<String, Object> overview =
                    readResourceJson(server, sessionId, 120, McpProductionDomainCatalog.MODEL_OVERVIEW);
            assertEquals("AVAILABLE", overview.get("availability"));
            assertEquals(2, array(overview.get("objects")).size());
            assertEquals(overview.get("objects"), overview.get("modelObjects"));

            final Map<String, Object> masks =
                    readResourceJson(server, sessionId, 13, McpProductionDomainCatalog.CLIP_MASKS);
            assertEquals(1L, integer(masks.get("count")));
        } finally {
            server.close();
        }
    }

    @Test
    void inspectionResourcesDoNotExposeFileSystemPaths() throws Exception {
        final FakeReadServices reads = new FakeReadServices();
        final ModelSnapshot model =
                new ModelSnapshot("ModelA", "Demo Model", List.of(), List.of(), List.of(), List.of());
        final DocumentSnapshot document = new DocumentSnapshot(
                "DocA",
                "Demo Model",
                "Models/Demo.cmo3",
                Optional.of(Path.of("Models/Demo.cmo3")),
                Optional.of(model),
                DocumentKind.MODEL,
                Optional.of("ContentA"),
                Optional.empty());
        reads.read.project(new ProjectSnapshot(
                "ProjectA",
                "Demo Project",
                Optional.of(Path.of("Projects/Demo")),
                List.of(document),
                List.of(new ProjectContentSnapshot(
                        "ContentA",
                        "Demo Model",
                        ProjectContentKind.MODEL,
                        Optional.of(Path.of("Models/Demo.cmo3")),
                        List.of("DocA"),
                        List.of()))));
        reads.read.document(document);
        reads.read.model(model);

        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), reads));
        try {
            ensureSession(server.endpoint());
            final Map<String, Object> resource = readResourceJson(
                    server, SESSIONS.get(server.endpoint()), 15, McpProductionDomainCatalog.ACTIVE_DOCUMENT);
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
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            final Map<String, Object> document = readResourceJson(
                    server, SESSIONS.get(server.endpoint()), 14, McpProductionDomainCatalog.ACTIVE_DOCUMENT);
            assertEquals(Boolean.TRUE, document.get("ok"));
            assertEquals(null, document.get("document"));
            assertEquals(null, document.get("model"));
        } finally {
            server.close();
        }
    }

    @Test
    void mutatingToolCallsRequireThePublishedBearerToken() throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"), "Head", Optional.empty()));
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            final String sessionId = SESSIONS.get(server.endpoint());
            final Map<String, Object> rename = Map.of(
                    "jsonrpc",
                    "2.0",
                    "id",
                    50,
                    "method",
                    "tools/call",
                    "params",
                    Map.of(
                            "name",
                            McpProductionDomainCatalog.APPLY,
                            "arguments",
                            Map.of(
                                    "operations",
                                    List.of(Map.of(
                                            "operation",
                                            "rename",
                                            "kind",
                                            "part",
                                            "id",
                                            "PartHead",
                                            "name",
                                            "Renamed")))));
            final HttpResponse<byte[]> denied = request(server.endpoint(), null, null, true, sessionId, rename);
            assertEquals(401, denied.statusCode());
            assertTrue(
                    denied.headers().firstValue("WWW-Authenticate").orElse("").contains("turboism-mcp"));
            assertEquals("Head", objects.find(ModelObjectKind.PART, "PartHead").name());

            final HttpResponse<byte[]> granted =
                    requestWithAuthorization(server.endpoint(), "Bearer " + bearer(), sessionId, rename);
            assertEquals(200, granted.statusCode());
            assertEquals(
                    "Renamed", objects.find(ModelObjectKind.PART, "PartHead").name());

            final HttpResponse<byte[]> readTool = request(
                    server.endpoint(),
                    null,
                    null,
                    true,
                    sessionId,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            51,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpCapabilitiesDomain.CAPABILITIES_READ, "arguments", Map.of())));
            assertEquals(200, readTool.statusCode());
            assertEquals(
                    200,
                    request(
                                    server.endpoint(),
                                    null,
                                    null,
                                    true,
                                    sessionId,
                                    Map.of("jsonrpc", "2.0", "id", 52, "method", "tools/list"))
                            .statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void sessionDeletionAndServerCloseDisposeNativeFileGrants() throws Exception {
        final var fixture = new McpFileGrantDomainTest.Fixture();
        final var base = dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices());
        final var dependencies = new McpHttpServer.Dependencies(
                base.logger(),
                base.modelObjects(),
                base.hierarchyQuery(),
                base.selectionQuery(),
                base.read(),
                base.clipMasks(),
                base.cubism(),
                base.history(),
                base.workspace(),
                base.workspaceLayout(),
                base.diagnostics(),
                base.editorCommands(),
                base.uiScheduler(),
                base.stateDir(),
                base.port(),
                base.requestsPerMinute(),
                base.requireAuthForRead(),
                fixture.service);
        final var server = McpHttpServer.start(dependencies);
        try {
            ensureSession(server.endpoint());
            String session = SESSIONS.get(server.endpoint());
            var response = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            501,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpFileGrantDomain.REQUEST, "arguments", fixture.args("late"))));
            assertEquals("PENDING", structuredResult(response).get("state"));
            var deleted = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("MCP-Session-Id", session)
                                    .header("Authorization", "Bearer " + bearer())
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, deleted.statusCode());
            var late = new McpFileGrantDomainTest.Handle();
            fixture.grant(late);
            assertEquals(1, late.closes);
            SESSIONS.remove(server.endpoint());
            ensureSession(server.endpoint());
            session = SESSIONS.get(server.endpoint());
            var missing = request(
                    server.endpoint(),
                    null,
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            502,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpFileGrantDomain.STATUS, "arguments", Map.of("operationId", "late"))));
            assertEquals(
                    "NOT_FOUND",
                    object(result(missing).get("structuredContent")).get("error"));
            request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            503,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpFileGrantDomain.REQUEST, "arguments", fixture.args("active"))));
            var active = new McpFileGrantDomainTest.Handle();
            fixture.grant(active);
            server.close();
            assertEquals(1, active.closes);
        } finally {
            server.close();
        }
    }

    @Test
    void fileInspectionSessionDeletionDisposesLateNativeReadWithoutCancellation() throws Exception {
        var fixture = new McpFileGrantDomainTest.Fixture();
        var read = new java.util.concurrent.CompletableFuture<dev.turboism.sdk.ui.UserFileReadResult<byte[]>>();
        var service = (dev.turboism.sdk.ui.UserFileAccessService) java.lang.reflect.Proxy.newProxyInstance(
                dev.turboism.sdk.ui.UserFileAccessService.class.getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.ui.UserFileAccessService.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("isAvailable")) return true;
                    if (method.getName().equals("request"))
                        return fixture.service.request((dev.turboism.sdk.ui.UserFileRequest) args[0]);
                    if (method.getName().equals("readBytesBounded")) return read;
                    throw new AssertionError(method.getName());
                });
        var base = dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices());
        var deps = new McpHttpServer.Dependencies(
                base.logger(),
                base.modelObjects(),
                base.hierarchyQuery(),
                base.selectionQuery(),
                base.read(),
                base.clipMasks(),
                base.cubism(),
                base.history(),
                base.workspace(),
                base.workspaceLayout(),
                base.diagnostics(),
                base.editorCommands(),
                base.uiScheduler(),
                base.stateDir(),
                base.port(),
                base.requestsPerMinute(),
                base.requireAuthForRead(),
                service);
        try (var server = McpHttpServer.start(deps)) {
            ensureSession(server.endpoint());
            String session = SESSIONS.get(server.endpoint());
            request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            601,
                            "method",
                            "tools/call",
                            "params",
                            Map.of("name", McpFileGrantDomain.REQUEST, "arguments", fixture.args("read-grant"))));
            var handle = new McpFileGrantDomainTest.Handle();
            fixture.grant(handle);
            var grant = request(
                    server.endpoint(),
                    null,
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            602,
                            "method",
                            "tools/call",
                            "params",
                            Map.of(
                                    "name",
                                    McpFileGrantDomain.STATUS,
                                    "arguments",
                                    Map.of("operationId", "read-grant"))));
            String grantId = (String) structuredResult(grant).get("grantId");
            var observed = request(
                    server.endpoint(),
                    bearer(),
                    null,
                    true,
                    session,
                    Map.of(
                            "jsonrpc",
                            "2.0",
                            "id",
                            603,
                            "method",
                            "tools/call",
                            "params",
                            Map.of(
                                    "name",
                                    McpFileInspectDomain.INSPECT,
                                    "arguments",
                                    Map.of("operationId", "read", "grantId", grantId, "maxBytes", 16))));
            assertEquals("RUNNING", structuredResult(observed).get("outcome"));
            var deleted = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("MCP-Session-Id", session)
                                    .header("Authorization", "Bearer " + bearer())
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, deleted.statusCode());
            assertFalse(read.isCancelled());
            assertEquals(0, handle.closes);
            read.complete(new dev.turboism.sdk.ui.UserFileReadResult<>(
                    java.util.Optional.of(new byte[] {1}), java.util.Optional.empty(), false));
            assertEquals(1, handle.closes);
        }
    }

    @Test
    void sessionCloseRequiresThePublishedBearerToken() throws Exception {
        final McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            ensureSession(server.endpoint());
            final String sessionId = SESSIONS.get(server.endpoint());

            final HttpResponse<byte[]> denied = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("MCP-Session-Id", sessionId)
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, denied.statusCode());

            final HttpResponse<byte[]> granted = HttpClient.newHttpClient()
                    .send(
                            HttpRequest.newBuilder(server.endpoint())
                                    .header("MCP-Session-Id", sessionId)
                                    .header("Authorization", "Bearer " + bearer())
                                    .DELETE()
                                    .build(),
                            HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, granted.statusCode());
        } finally {
            server.close();
        }
    }

    @Test
    void publishesPersistentOwnerOnlyTokenAndStdioBridge() throws Exception {
        final McpHttpServer first =
                McpHttpServer.start(dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()));
        try {
            final Path tokenFile = temporaryDirectory.resolve(McpAccessToken.FILE_NAME);
            assertTrue(Files.isRegularFile(tokenFile));
            assertTrue(bearer().matches("[0-9a-f]{64}"));
            try {
                assertEquals(
                        "rw-------",
                        java.nio.file.attribute.PosixFilePermissions.toString(
                                Files.getPosixFilePermissions(tokenFile)));
            } catch (UnsupportedOperationException noPosix) {
                // ACL/DOS-backed stores enforce owner-only through other views.
            }

            final Path bridge = temporaryDirectory.resolve(McpStdioBridge.FILE_NAME);
            assertTrue(Files.isRegularFile(bridge));
            final String source = Files.readString(bridge, StandardCharsets.UTF_8);
            assertTrue(source.contains(
                    temporaryDirectory.toAbsolutePath().toString().replace("\\", "\\\\")));
            assertTrue(source.contains("mcp.token"));
            assertTrue(source.contains("Bearer "));

            final Map<String, Object> connection = object(Json.parseObject(Files.readAllBytes(first.connectionFile())));
            final Map<String, Object> stdio = object(connection.get("stdio"));
            assertEquals("java", stdio.get("command"));
            assertEquals(List.of(bridge.toAbsolutePath().toString()), array(stdio.get("args")));
            assertTrue(first.stdioClientConfig().contains("TurboismMcpBridge.java"));
            first.close();

            final String persisted = bearer();
            try (McpHttpServer second = McpHttpServer.start(
                    dependencies(new CapturingLogger(), new MutableObjects(), new FakeReadServices()))) {
                assertEquals(persisted, bearer());
                assertEquals(
                        second.stdioClientConfig(),
                        Json.stringify(Map.of(
                                "command",
                                "java",
                                "args",
                                List.of(bridge.toAbsolutePath().toString()))));
            }
        } finally {
            first.close();
        }
    }

    @ParameterizedTest(name = "stdio bridge survives state dir: {0}")
    @ValueSource(strings = {"standard", "spaced state dir"})
    void stdioLaunchDescriptorSpawnsBridgeForMutatingCallsAcrossStateDirs(String stateDirType) throws Exception {
        final Path stateDir = "spaced state dir".equals(stateDirType)
                ? Files.createDirectory(temporaryDirectory.resolve("spaced state dir"))
                : temporaryDirectory;
        final String partId = "spaced state dir".equals(stateDirType) ? "PartArm" : "PartHead";
        final String initialName = "spaced state dir".equals(stateDirType) ? "Arm" : "Head";
        final String newName = "spaced state dir".equals(stateDirType) ? "Arm Moved" : "Renamed via stdio";

        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.PART, partId), initialName, Optional.empty()));
        final McpHttpServer server = "spaced state dir".equals(stateDirType)
                ? McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices(), stateDir))
                : McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices()));
        try {
            final dev.turboism.sdk.mcp.McpStdioLaunch launch =
                    server.stdioLaunch().orElseThrow();
            if ("standard".equals(stateDirType)) {
                assertLaunchDescriptor(launch, temporaryDirectory);
                final String token = bearer();
                assertFalse(launch.command().contains(token));
                launch.args().forEach(arg -> assertFalse(arg.contains(token)));
            } else {
                assertEquals(
                        stateDir.toAbsolutePath().normalize().toString(),
                        launch.args().get(launch.args().size() - 1));
            }

            try (BridgeProcess bridge = spawnBridge(launch)) {
                final Map<String, Object> initialized = bridge.call(initializeMessage(1));
                if ("standard".equals(stateDirType)) {
                    assertEquals(
                            McpProtocol.VERSION,
                            object(initialized.get("result")).get("protocolVersion"));
                }
                bridge.notify(initializedNotification());
                final Map<String, Object> renamed = bridge.call(renameCall(2, partId, newName));
                assertEquals(Boolean.FALSE, object(renamed.get("result")).get("isError"));
                assertEquals(newName, objects.find(ModelObjectKind.PART, partId).name());
            }

            if ("standard".equals(stateDirType)) {
                ensureSession(server.endpoint());
                final HttpResponse<byte[]> denied =
                        request(server.endpoint(), null, null, true, SESSIONS.get(server.endpoint()), renameCall(9));
                assertEquals(401, denied.statusCode());
            }
        } finally {
            server.close();
        }
    }

    @ParameterizedTest(name = "stdio write with stale credential, refreshed token available: {0}")
    @ValueSource(booleans = {true, false})
    void stdioWritesRefreshStaleCredentialsOrReportAuthenticationFailure(boolean refreshedTokenAvailable)
            throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"), "Head", Optional.empty()));
        try (McpHttpServer server =
                McpHttpServer.start(dependencies(new CapturingLogger(), objects, new FakeReadServices()))) {
            final Path tokenFile = temporaryDirectory.resolve(McpAccessToken.FILE_NAME);
            final String currentToken = bearer();
            final String staleToken = "0".repeat(64).equals(currentToken) ? "1".repeat(64) : "0".repeat(64);
            Files.writeString(tokenFile, staleToken + "\n");
            try (BridgeProcess bridge = spawnBridge(server.stdioLaunch().orElseThrow())) {
                bridge.call(initializeMessage(1));
                bridge.notify(initializedNotification());
                final Map<String, Object> catalog =
                        bridge.call(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"));
                assertTrue(array(object(catalog.get("result")).get("tools")).stream()
                        .map(McpHttpServerIntegrationTest::object)
                        .anyMatch(tool -> McpProductionDomainCatalog.APPLY.equals(tool.get("name"))));

                // The running bridge cached the old credential; discovery still succeeds.
                if (refreshedTokenAvailable) Files.writeString(tokenFile, currentToken + "\n");
                final Map<String, Object> response = bridge.call(renameCall(3));
                assertEquals(3L, response.get("id"));
                if (refreshedTokenAvailable) {
                    assertEquals(Boolean.FALSE, object(response.get("result")).get("isError"));
                    assertEquals(
                            "Renamed via stdio",
                            objects.find(ModelObjectKind.PART, "PartHead").name());
                } else {
                    final Map<String, Object> error = object(response.get("error"));
                    assertEquals(-32001L, error.get("code"));
                    assertTrue(error.get("message").toString().contains("authentication"));
                    assertFalse(response.toString().contains(staleToken));
                    assertFalse(response.toString().contains(currentToken));
                    assertEquals(
                            "Head",
                            objects.find(ModelObjectKind.PART, "PartHead").name());
                }
            } finally {
                Files.writeString(tokenFile, currentToken + "\n");
            }
        }
    }

    private void assertLaunchDescriptor(final dev.turboism.sdk.mcp.McpStdioLaunch launch, final Path stateDir) {
        final Path expectedLauncher = Path.of(System.getProperty("java.home"), "bin", javaExecutable());
        assertEquals(expectedLauncher.toAbsolutePath().normalize().toString(), launch.command());
        assertTrue(Files.isRegularFile(Path.of(launch.command())));
        assertEquals(4, launch.args().size());
        assertEquals("-cp", launch.args().get(0));
        assertTrue(Files.exists(Path.of(launch.args().get(1))));
        assertEquals(McpStdioBridge.MAIN_CLASS, launch.args().get(2));
        assertEquals(
                stateDir.toAbsolutePath().normalize().toString(), launch.args().get(3));
    }

    private static String javaExecutable() {
        return System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win")
                ? "java.exe"
                : "java";
    }

    /**
     * Launches the compiled bridge exactly the way the published descriptor prescribes, plus the
     * {@code --limit-modules} profile a bundled JRE without {@code jdk.compiler} can run.
     */
    private static BridgeProcess spawnBridge(final dev.turboism.sdk.mcp.McpStdioLaunch launch) throws IOException {
        final List<String> argv = new ArrayList<>();
        argv.add(launch.command());
        argv.add("--limit-modules");
        argv.add("java.base,java.net.http");
        argv.addAll(launch.args());
        return new BridgeProcess(new ProcessBuilder(argv).start());
    }

    private static Map<String, Object> initializeMessage(final int id) {
        return Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "method",
                "initialize",
                "params",
                Map.of(
                        "protocolVersion", McpProtocol.VERSION,
                        "capabilities", Map.of(),
                        "clientInfo", Map.of("name", "stdio-bridge-test", "version", "1.0")));
    }

    private static Map<String, Object> initializedNotification() {
        return Map.of("jsonrpc", "2.0", "method", "notifications/initialized");
    }

    private static Map<String, Object> renameCall(final int id) {
        return renameCall(id, "PartHead", "Renamed via stdio");
    }

    private static Map<String, Object> renameCall(final int id, final String objectId, final String name) {
        return Map.of(
                "jsonrpc",
                "2.0",
                "id",
                id,
                "method",
                "tools/call",
                "params",
                Map.of(
                        "name",
                        McpProductionDomainCatalog.APPLY,
                        "arguments",
                        Map.of(
                                "operations",
                                List.of(Map.of("operation", "rename", "kind", "part", "id", objectId, "name", name)))));
    }

    /** Line-delimited JSON-RPC conversation with one bridge subprocess on a background reader. */
    private static final class BridgeProcess implements AutoCloseable {
        private final Process process;
        private final java.io.BufferedWriter stdin;
        private final java.util.concurrent.BlockingQueue<String> lines =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
        private final Thread stdoutReader;
        private final Thread stderrReader;

        private BridgeProcess(final Process process) {
            this.process = process;
            this.stdin = new java.io.BufferedWriter(
                    new java.io.OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            stdoutReader = drain(process.getInputStream(), lines);
            stderrReader = new Thread(() -> {
                try {
                    process.getErrorStream().transferTo(stderr);
                } catch (IOException ignored) {
                    // Process exit closes the stream mid-copy.
                }
            });
            stderrReader.setDaemon(true);
            stderrReader.start();
        }

        private static Thread drain(
                final java.io.InputStream stream, final java.util.concurrent.BlockingQueue<String> sink) {
            final Thread reader = new Thread(() -> {
                try (java.io.BufferedReader buffered =
                        new java.io.BufferedReader(new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    for (String line; (line = buffered.readLine()) != null; ) {
                        sink.add(line);
                    }
                } catch (IOException ignored) {
                    // Process exit closes the stream mid-read.
                }
            });
            reader.setDaemon(true);
            reader.start();
            return reader;
        }

        private void write(final Map<String, Object> message) throws IOException {
            stdin.write(new String(Json.bytes(message), StandardCharsets.UTF_8));
            stdin.newLine();
            stdin.flush();
        }

        /** Sends a request and waits up to 30 seconds for its response line. */
        private Map<String, Object> call(final Map<String, Object> message) throws Exception {
            write(message);
            final String line = pollLine();
            assertNotNull(line, "bridge produced no response line");
            return object(Json.parseObject(line.getBytes(StandardCharsets.UTF_8)));
        }

        private void notify(final Map<String, Object> message) throws IOException {
            write(message);
        }

        private String pollLine() throws InterruptedException {
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            String line;
            while ((line = lines.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS)) == null) {
                if (System.nanoTime() > deadline || !process.isAlive()) {
                    return null;
                }
            }
            return line;
        }

        @Override
        public void close() throws Exception {
            stdin.close();
            assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS), "bridge did not exit");
            final String errors = stderr.toString(StandardCharsets.UTF_8);
            assertTrue(errors.isBlank(), "bridge stderr: " + errors);
            stdoutReader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
            stderrReader.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        }
    }

    private Map<String, Object> readResourceJson(
            final McpHttpServer server, final String sessionId, final int id, final String uri) throws Exception {
        return resourceJson(request(
                server.endpoint(),
                bearer(),
                null,
                true,
                sessionId,
                Map.of("jsonrpc", "2.0", "id", id, "method", "resources/read", "params", Map.of("uri", uri))));
    }

    private HttpResponse<byte[]> toolCall(
            final URI endpoint, final int id, final String tool, final Map<String, Object> arguments) throws Exception {
        ensureSession(endpoint);
        return request(
                endpoint,
                bearer(),
                null,
                true,
                SESSIONS.get(endpoint),
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        id,
                        "method",
                        "tools/call",
                        "params",
                        Map.of("name", tool, "arguments", arguments)));
    }

    private void ensureSession(final URI endpoint) throws Exception {
        if (SESSIONS.containsKey(endpoint)) return;
        final HttpResponse<byte[]> initialized = request(
                endpoint,
                bearer(),
                null,
                false,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        0,
                        "method",
                        "initialize",
                        "params",
                        Map.of(
                                "protocolVersion", McpProtocol.VERSION,
                                "capabilities", Map.of(),
                                "clientInfo", Map.of("name", "integration-test", "version", "1.0"))));
        final String sessionId =
                initialized.headers().firstValue("MCP-Session-Id").orElseThrow();
        SESSIONS.put(endpoint, sessionId);
        final HttpResponse<byte[]> notification = request(
                endpoint,
                bearer(),
                null,
                true,
                sessionId,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized"));
        assertEquals(202, notification.statusCode());
    }

    private static HttpResponse<byte[]> request(
            final URI endpoint,
            final String token,
            final String origin,
            final boolean includeProtocolVersion,
            final Map<String, Object> body)
            throws Exception {
        return request(endpoint, token, origin, includeProtocolVersion, null, body);
    }

    private static HttpResponse<byte[]> request(
            final URI endpoint,
            final String token,
            final String origin,
            final boolean includeProtocolVersion,
            final String sessionId,
            final Map<String, Object> body)
            throws Exception {
        return request(endpoint, token, origin, includeProtocolVersion ? McpProtocol.VERSION : null, sessionId, body);
    }

    private static HttpResponse<byte[]> request(
            final URI endpoint,
            final String token,
            final String origin,
            final String protocolVersion,
            final Map<String, Object> body)
            throws Exception {
        return request(endpoint, token, origin, protocolVersion, null, body);
    }

    private static HttpResponse<byte[]> request(
            final URI endpoint,
            final String token,
            final String origin,
            final String protocolVersion,
            final String sessionId,
            final Map<String, Object> body)
            throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        if (origin != null) builder.header("Origin", origin);
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        if (sessionId != null) builder.header("MCP-Session-Id", sessionId);
        if (token != null) builder.header("Authorization", "Bearer " + token);
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private static HttpResponse<byte[]> requestWithAuthorization(
            final URI endpoint, final String authorization, final String sessionId, final Map<String, Object> body)
            throws Exception {
        final HttpRequest request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(10))
                .header("Accept", "application/json, text/event-stream")
                .header("Content-Type", "application/json")
                .header("Authorization", authorization)
                .header("MCP-Protocol-Version", McpProtocol.VERSION)
                .header("MCP-Session-Id", sessionId)
                .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)))
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static Map<String, Object> result(final HttpResponse<byte[]> response) {
        final Map<String, Object> envelope = object(Json.parseObject(response.body()));
        assertFalse(envelope.containsKey("error"), () -> new String(response.body(), StandardCharsets.UTF_8));
        return object(envelope.get("result"));
    }

    private static Map<String, Object> structuredResult(final HttpResponse<byte[]> response) {
        final Map<String, Object> toolResult = result(response);
        assertEquals(Boolean.FALSE, toolResult.get("isError"));
        return object(toolResult.get("structuredContent"));
    }

    private static Map<String, Object> resourceJson(final HttpResponse<byte[]> response) {
        final Map<String, Object> read = result(response);
        final Map<String, Object> content = object(array(read.get("contents")).get(0));
        return object(Json.parseObject(((String) content.get("text")).getBytes(StandardCharsets.UTF_8)));
    }

    private static UiScheduler immediateUi() {
        return new UiScheduler() {
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
            final PluginLogger logger, final ModelObjectService objects, final FakeReadServices reads) {
        return dependencies(logger, objects, reads, temporaryDirectory);
    }

    private McpHttpServer.Dependencies dependencies(
            final PluginLogger logger,
            final ModelObjectService objects,
            final FakeReadServices reads,
            final Path stateDir) {
        return new McpHttpServer.Dependencies(
                logger,
                objects,
                reads.hierarchy,
                reads.selection,
                reads.read,
                reads.clipMasks,
                immediateUi(),
                stateDir,
                0,
                120);
    }

    private PluginContext pluginContext(
            final PluginLogger logger,
            final ModelObjectService objects,
            final FakeReadServices reads,
            final McpConnectionService connections) {
        return pluginContext(logger, objects, reads, connections, new RecordingUi());
    }

    private PluginContext pluginContext(
            final PluginLogger logger,
            final ModelObjectService objects,
            final FakeReadServices reads,
            final McpConnectionService connections,
            final RecordingUi ui) {
        final PluginPaths paths = new PluginPaths() {
            @Override
            public Path dataDir() {
                return temporaryDirectory;
            }

            @Override
            public Path logsDir() {
                return temporaryDirectory;
            }

            @Override
            public Path stateDir() {
                return temporaryDirectory;
            }

            @Override
            public Path cacheDir() {
                return temporaryDirectory;
            }
        };
        return (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "logger" -> logger;
                    case "paths" -> paths;
                    case "modelObjects" -> objects;
                    case "modelHierarchyQuery" -> reads.hierarchy;
                    case "selectionQuery" -> reads.selection;
                    case "cubismRead" -> reads.read;
                    case "services" ->
                        dev.turboism.sdk.plugin.PluginServices.builder()
                                .install(
                                        dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.class,
                                        reads.clipMasks)
                                .install(dev.turboism.sdk.mcp.McpConnectionService.class, connections)
                                .fallback(dev.turboism.sdk.plugin.PluginServices.of((PluginContext) proxy))
                                .build();
                    case "cubism" -> McpHttpServer.Dependencies.unavailableCubism();
                    case "diagnostics" -> McpHttpServer.Dependencies.emptyDiagnostics();
                    case "userFiles" -> dev.turboism.sdk.ui.UserFileAccessService.unavailable();
                    case "uiScheduler" -> immediateUi();
                    case "actions" -> ui;
                    case "menus" -> ui;
                    case "localization" -> ui.localization;
                    case "disposableScope" -> ui.scope;
                    case "toString" -> "McpPluginTestContext";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                    default ->
                        throw new UnsupportedOperationException("unused PluginContext method: " + method.getName());
                });
    }

    static final class FakeReadServices extends McpHttpServerTestFixtures.FakeReadServices {}
}
