package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
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
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpHttpServerIntegrationTest {

    private static final String TOKEN = "test-token-0123456789-abcdef";

    @TempDir
    Path temporaryDirectory;

    @Test
    void servesLifecycleAndObjectToolsOverAuthenticatedLoopbackHttp() throws Exception {
        final MutableObjects objects = new MutableObjects();
        objects.put(new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.PART, "PartHead"),
            "Head",
            Optional.empty()
        ));
        final CapturingLogger logger = new CapturingLogger();
        final McpHttpServer server = McpHttpServer.start(new McpHttpServer.Dependencies(
            logger,
            objects,
            immediateUi(),
            temporaryDirectory,
            0,
            TOKEN,
            120
        ));
        final Path connectionFile = server.connectionFile();
        try {
            assertEquals("127.0.0.1", server.endpoint().getHost());
            assertTrue(Files.isRegularFile(connectionFile));
            final Map<String, Object> connection = object(Json.parse(Files.readAllBytes(connectionFile)));
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

            final HttpResponse<byte[]> tools = request(server.endpoint(), TOKEN, null, true, Map.of(
                "jsonrpc", "2.0",
                "id", 2,
                "method", "tools/list",
                "params", Map.of()
            ));
            assertEquals(200, tools.statusCode());
            assertEquals(5, array(result(tools).get("tools")).size());

            final HttpResponse<byte[]> renamed = toolCall(
                server.endpoint(),
                3,
                McpTools.RENAME,
                Map.of("kind", "part", "id", "PartHead", "name", "Head Renamed")
            );
            final Map<String, Object> renamedOutput = structuredResult(renamed);
            assertEquals(Boolean.TRUE, renamedOutput.get("ok"));
            assertEquals(
                "Head Renamed",
                object(renamedOutput.get("object")).get("name")
            );
            assertEquals("Head Renamed", objects.find(ModelObjectKind.PART, "PartHead").name());

            final HttpResponse<byte[]> created = toolCall(
                server.endpoint(),
                4,
                McpTools.CREATE,
                Map.of(
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
            );
            final Map<String, Object> createdOutput = structuredResult(created);
            assertEquals(Boolean.TRUE, createdOutput.get("ok"));
            assertInstanceOf(ModelObjectCreateRequest.WarpDeformer.class, objects.lastCreate);
            final ModelObjectCreateRequest.WarpDeformer warp =
                (ModelObjectCreateRequest.WarpDeformer) objects.lastCreate;
            assertEquals(3, warp.grid().rows());
            assertEquals(4, warp.grid().columns());
            assertEquals(20, warp.grid().controlPoints().size());
            final Map<String, Object> createdObject = object(createdOutput.get("object"));
            final String createdId = (String) createdObject.get("id");
            assertNotNull(createdId);

            final HttpResponse<byte[]> reparented = toolCall(
                server.endpoint(),
                5,
                McpTools.REPARENT,
                Map.of(
                    "kind", "warp_deformer",
                    "id", createdId,
                    "parent", Map.of("kind", "part", "id", "PartHead"),
                    "index", 2
                )
            );
            final Map<String, Object> reparentedOutput = structuredResult(reparented);
            assertEquals(Boolean.TRUE, reparentedOutput.get("ok"));
            assertEquals(
                Map.of("kind", "part", "id", "PartHead"),
                object(object(reparentedOutput.get("object")).get("parent"))
            );
            assertEquals(
                new ModelObjectReference(ModelObjectKind.WARP_DEFORMER, createdId),
                objects.lastReparentTarget
            );
            assertEquals(
                new ModelObjectReference(ModelObjectKind.PART, "PartHead"),
                objects.lastReparentParent
            );
            assertEquals(2, objects.lastReparentIndex);

            final HttpResponse<byte[]> deleted = toolCall(
                server.endpoint(),
                6,
                McpTools.DELETE,
                Map.of(
                    "kind", "warp_deformer",
                    "id", createdId,
                    "policy", "cascade"
                )
            );
            final Map<String, Object> deletedOutput = structuredResult(deleted);
            assertEquals(Boolean.TRUE, deletedOutput.get("deleted"));
            assertEquals(ModelObjectDeletePolicy.CASCADE, objects.lastDeletePolicy);
            assertFalse(objects.contains(ModelObjectKind.WARP_DEFORMER, createdId));

            final HttpResponse<byte[]> notification = request(
                server.endpoint(),
                TOKEN,
                null,
                true,
                Map.of("jsonrpc", "2.0", "method", "notifications/initialized")
            );
            assertEquals(202, notification.statusCode());
            assertEquals(0, notification.body().length);
        } finally {
            server.close();
        }
        assertFalse(Files.exists(connectionFile));
        assertTrue(logger.info.stream().anyMatch(value -> value.contains("listening")));
        assertTrue(logger.info.stream().anyMatch(value -> value.contains("stopped")));
    }

    @Test
    void rejectsMissingTokenAndNonLoopbackOrigin() throws Exception {
        final McpHttpServer server = McpHttpServer.start(new McpHttpServer.Dependencies(
            new CapturingLogger(),
            new MutableObjects(),
            immediateUi(),
            temporaryDirectory,
            0,
            TOKEN,
            120
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
            assertEquals(
                200,
                request(server.endpoint(), TOKEN, "http://127.0.0.1", true, ping)
                    .statusCode()
            );
        } finally {
            server.close();
        }
    }

    @Test
    void returnsStableToolErrorWhenStructuralProviderIsUnavailable() throws Exception {
        final ModelObjectService unavailable = new MutableObjects() {
            @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                throw new ModelObjectOperationException(
                    ModelObjectOperationException.Code.UNAVAILABLE,
                    "structural provider is not verified"
                );
            }
        };
        final McpHttpServer server = McpHttpServer.start(new McpHttpServer.Dependencies(
            new CapturingLogger(), unavailable, immediateUi(), temporaryDirectory, 0, TOKEN, 120
        ));
        try {
            final HttpResponse<byte[]> response = toolCall(
                server.endpoint(),
                6,
                McpTools.CREATE,
                Map.of("kind", "part", "name", "Unavailable Part")
            );
            final Map<String, Object> result = object(result(response));
            assertEquals(Boolean.TRUE, result.get("isError"));
            final Map<String, Object> structured = object(result.get("structuredContent"));
            assertEquals(Boolean.FALSE, structured.get("ok"));
            assertEquals(
                "UNAVAILABLE",
                object(structured.get("error")).get("code")
            );
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
        return request(endpoint, TOKEN, null, true, Map.of(
            "jsonrpc", "2.0",
            "id", id,
            "method", "tools/call",
            "params", Map.of("name", tool, "arguments", arguments)
        ));
    }

    private static HttpResponse<byte[]> request(
        final URI endpoint,
        final String token,
        final String origin,
        final boolean includeProtocolVersion,
        final Map<String, Object> body
    ) throws Exception {
        final HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json, text/event-stream")
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(Json.bytes(body)));
        if (origin != null) builder.header("Origin", origin);
        if (includeProtocolVersion) {
            builder.header("MCP-Protocol-Version", McpProtocol.VERSION);
        }
        return HttpClient.newHttpClient().send(
            builder.build(),
            HttpResponse.BodyHandlers.ofByteArray()
        );
    }

    private static Map<String, Object> result(final HttpResponse<byte[]> response) {
        final Map<String, Object> envelope = object(Json.parse(response.body()));
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

    private static final class CapturingLogger implements PluginLogger {
        private final List<String> info = new ArrayList<>();

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { info.add(message); }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) { }
    }
}
