package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Public MCP contract for the expanded tool/resource/prompt surface. */
final class McpExpandedProtocolTest {

    @Test
    void initializeAdvertisesToolsResourcesAndPrompts() {
        final McpProtocol protocol = McpProtocol.forCatalogs(
            McpToolCatalog.empty(),
            McpResourceCatalog.empty(),
            McpPromptCatalog.defaults()
        );

        final McpProtocol.Outcome outcome = protocol.handle(Map.of(
            "jsonrpc", "2.0",
            "id", 1,
            "method", "initialize",
            "params", Map.of(
                "protocolVersion", McpProtocol.VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", "test", "version", "1")
            )
        ));

        assertEquals(200, outcome.status());
        final Map<String, Object> response = object(outcome.body());
        final Map<String, Object> result = object(response.get("result"));
        final Map<String, Object> capabilities = object(result.get("capabilities"));
        assertTrue(capabilities.containsKey("tools"));
        assertTrue(capabilities.containsKey("resources"));
        assertTrue(capabilities.containsKey("prompts"));
    }

    @Test
    void toolCatalogAddsOutputContractAndRejectsUnknownToolsAtProtocolLevel() {
        final McpToolCatalog tools = new McpToolCatalog(
            java.util.List.of(Map.of(
                "name", "known",
                "description", "Known test tool",
                "inputSchema", Map.of("type", "object")
            )),
            (name, arguments) -> Map.of(
                "content", java.util.List.of(),
                "structuredContent", Map.of("ok", true),
                "isError", false
            )
        );
        final McpProtocol protocol = McpProtocol.forCatalogs(
            tools, McpResourceCatalog.empty(), McpPromptCatalog.defaults()
        );

        final Map<String, Object> listed = result(protocol.handle(request(
            1, "tools/list", Map.of()
        )));
        assertTrue(object(list(listed.get("tools")).get(0)).containsKey("outputSchema"));

        final Map<String, Object> envelope = object(protocol.handle(request(
            2, "tools/call", Map.of("name", "missing", "arguments", Map.of())
        )).body());
        assertEquals(-32602L, ((Number) object(envelope.get("error")).get("code")).longValue());
    }

    @Test
    void toolCatalogValidatesAdvertisedOutputAndTextEquivalence() {
        final Map<String, Object> outputSchema = Map.of(
            "type", "object",
            "properties", Map.of(
                "ok", Map.of("const", true),
                "value", Map.of("type", "string")
            ),
            "required", java.util.List.of("ok", "value"),
            "additionalProperties", false
        );
        final McpToolCatalog valid = new McpToolCatalog(
            java.util.List.of(Map.of(
                "name", "validated",
                "inputSchema", Map.of("type", "object"),
                "outputSchema", outputSchema
            )),
            (name, arguments) -> toolEnvelope(Map.of("ok", true, "value", "actual"))
        );

        final Map<String, Object> validEnvelope = valid.call("validated", Map.of());
        assertEquals(
            validEnvelope.get("structuredContent"),
            Json.parse(((String) object(list(validEnvelope.get("content")).get(0)).get("text"))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))
        );

        final McpToolCatalog invalid = new McpToolCatalog(
            java.util.List.of(Map.of(
                "name", "invalid",
                "inputSchema", Map.of("type", "object"),
                "outputSchema", outputSchema
            )),
            (name, arguments) -> toolEnvelope(Map.of("ok", true, "value", 7))
        );
        final Map<String, Object> invalidEnvelope = invalid.call("invalid", Map.of());
        assertTrue((Boolean) invalidEnvelope.get("isError"));
        final Map<String, Object> invalidOutput = object(invalidEnvelope.get("structuredContent"));
        assertFalse((Boolean) invalidOutput.get("ok"));
        assertEquals("INTERNAL_OUTPUT_INVALID", object(invalidOutput.get("error")).get("code"));
    }

    @Test
    void productionSchemasAreStandardMcpObjectSchemas() {
        for (Map<String, Object> schema : java.util.List.of(
            McpOutputSchemas.modelObjectBatch(),
            McpOutputSchemas.parameterBatch(),
            McpOutputSchemas.bindingBatch(),
            McpOutputSchemas.historyMove(),
            McpOutputSchemas.editorCommand()
        )) {
            assertEquals("object", schema.get("type"));
            assertTrue(schema.containsKey("oneOf"));
        }
        final Map<String, Object> editorInput = object(
            new McpHistoryCommandDomain(
                dev.turboism.sdk.cubism.history.CubismHistory.unavailable(),
                dev.turboism.sdk.cubism.command.EditorCommandService.unavailable()
            ).tools().definitions().stream()
                .filter(definition -> McpHistoryCommandDomain.EDITOR_COMMANDS_EXECUTE.equals(
                    definition.get("name")
                ))
                .findFirst()
                .orElseThrow()
                .get("inputSchema")
        );
        assertEquals("object", editorInput.get("type"));
        assertTrue(editorInput.containsKey("oneOf"));
    }

    @Test
    void productionOutputSchemasRejectUnknownNestedOperationPayloads() {
        final McpToolCatalog catalog = new McpToolCatalog(
            java.util.List.of(Map.of(
                "name", "parameter-batch",
                "inputSchema", Map.of("type", "object"),
                "outputSchema", McpOutputSchemas.parameterBatch()
            )),
            (name, arguments) -> toolEnvelope(Map.of(
                "ok", true,
                "stopOnError", false,
                "results", java.util.List.of(Map.of(
                    "index", 0,
                    "operation", "set_value",
                    "ok", true,
                    "result", Map.of("unexpected", true)
                )),
                "parameters", java.util.List.of()
            ))
        );

        final Map<String, Object> envelope = catalog.call("parameter-batch", Map.of());
        assertTrue((Boolean) envelope.get("isError"));
        assertEquals(
            "INTERNAL_OUTPUT_INVALID",
            object(object(envelope.get("structuredContent")).get("error")).get("code")
        );
    }

    @Test
    void listCursorsAreOpaqueAndBoundToTheSessionAndMethod() {
        final java.util.List<Map<String, Object>> definitions = new java.util.ArrayList<>();
        for (int index = 0; index < 55; index++) {
            definitions.add(Map.of(
                "name", "tool_" + index,
                "description", "Tool " + index,
                "inputSchema", Map.of("type", "object")
            ));
        }
        final McpProtocol protocol = McpProtocol.forCatalogs(
            new McpToolCatalog(definitions, (name, arguments) -> Map.of()),
            McpResourceCatalog.empty(),
            McpPromptCatalog.defaults()
        );

        final Map<String, Object> first = result(protocol.handle(
            request(1, "tools/list", Map.of()), "session-a"
        ));
        assertEquals(50, list(first.get("tools")).size());
        final String cursor = (String) first.get("nextCursor");

        final Map<String, Object> second = result(protocol.handle(
            request(2, "tools/list", Map.of("cursor", cursor)), "session-a"
        ));
        assertEquals(5, list(second.get("tools")).size());
        assertTrue(!second.containsKey("nextCursor"));

        final Map<String, Object> wrongSession = object(protocol.handle(
            request(3, "tools/list", Map.of("cursor", cursor)), "session-b"
        ).body());
        assertEquals(-32602L,
            ((Number) object(wrongSession.get("error")).get("code")).longValue());

        final Map<String, Object> wrongMethod = object(protocol.handle(
            request(4, "prompts/list", Map.of("cursor", cursor)), "session-a"
        ).body());
        assertEquals(-32602L,
            ((Number) object(wrongMethod.get("error")).get("code")).longValue());
    }

    @Test
    void cancellationNotificationCancelsMatchingActiveRequest() throws Exception {
        final McpRequestRegistry requests = new McpRequestRegistry();
        final McpProtocol protocol = McpProtocol.forCatalogs(
            McpToolCatalog.empty(),
            McpResourceCatalog.empty(),
            McpPromptCatalog.defaults(),
            requests
        );
        final java.util.concurrent.CountDownLatch active = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        final Thread worker = new Thread(() -> {
            try (McpRequestRegistry.Scope ignored = requests.enter("session-a", 7)) {
                active.countDown();
                while (!McpRequestRegistry.cancelled()) Thread.onSpinWait();
            } finally {
                released.countDown();
            }
        });
        worker.start();
        assertTrue(active.await(5, java.util.concurrent.TimeUnit.SECONDS));

        final McpProtocol.Outcome outcome = protocol.handle(Map.of(
            "jsonrpc", "2.0",
            "method", "notifications/cancelled",
            "params", Map.of("requestId", 7, "reason", "test")
        ), "session-a");
        assertEquals(202, outcome.status());
        assertTrue(released.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(!requests.cancel("session-a", 7));
    }

    @Test
    void cancelledToolRequestReturnsStableToolErrorEnvelope() {
        final McpToolCatalog tools = new McpToolCatalog(
            java.util.List.of(Map.of(
                "name", "cancelled",
                "inputSchema", Map.of("type", "object")
            )),
            (name, arguments) -> { throw new java.util.concurrent.CancellationException(); }
        );
        final McpProtocol protocol = McpProtocol.forCatalogs(
            tools, McpResourceCatalog.empty(), McpPromptCatalog.defaults()
        );

        final Map<String, Object> result = result(protocol.handle(request(
            8, "tools/call", Map.of("name", "cancelled", "arguments", Map.of())
        )));
        assertTrue((Boolean) result.get("isError"));
        final Map<String, Object> output = object(result.get("structuredContent"));
        assertFalse((Boolean) output.get("ok"));
        assertEquals("CANCELLED", object(output.get("error")).get("code"));
    }

    @Test
    void cancelledResourceRequestUsesJsonRpcErrorRatherThanToolEnvelope() {
        final McpResourceCatalog resources = new McpResourceCatalog(
            java.util.List.of(),
            java.util.List.of(),
            uri -> { throw new java.util.concurrent.CancellationException(); }
        );
        final McpProtocol protocol = McpProtocol.forCatalogs(
            McpToolCatalog.empty(), resources, McpPromptCatalog.defaults()
        );

        final Map<String, Object> envelope = object(protocol.handle(request(
            9, "resources/read", Map.of("uri", "turboism://active/document")
        )).body());
        final Map<String, Object> error = object(envelope.get("error"));
        assertEquals(-32800L, ((Number) error.get("code")).longValue());
        assertEquals("Request cancelled", error.get("message"));
        assertFalse(envelope.containsKey("result"));
    }

    @Test
    void initializeNegotiatesSupportedAndUnknownProtocolVersions() {
        final McpProtocol protocol = McpProtocol.forCatalogs(
            McpToolCatalog.empty(),
            McpResourceCatalog.empty(),
            McpPromptCatalog.defaults()
        );

        final Map<String, Object> supported = result(protocol.handle(initializeRequest(
            1, "2025-06-18"
        )));
        assertEquals("2025-06-18", supported.get("protocolVersion"));

        final Map<String, Object> unknown = result(protocol.handle(initializeRequest(
            2, "2099-01-01"
        )));
        assertEquals(McpProtocol.VERSION, unknown.get("protocolVersion"));
    }

    @Test
    void exposesResourcesAndWorkflowPrompts() {
        final McpResourceCatalog resources = new McpResourceCatalog(
            java.util.List.of(Map.of(
                "uri", "turboism://active/document",
                "name", "active-document",
                "mimeType", "application/json"
            )),
            java.util.List.of(),
            uri -> {
                if (!"turboism://active/document".equals(uri)) {
                    throw new McpResourceCatalog.ResourceNotFound(uri);
                }
                return java.util.List.of(Map.of(
                    "uri", uri,
                    "mimeType", "application/json",
                    "text", "{\"ok\":true}"
                ));
            }
        );
        final McpProtocol protocol = McpProtocol.forCatalogs(
            McpToolCatalog.empty(), resources, McpPromptCatalog.defaults()
        );

        final Map<String, Object> listedResources = result(protocol.handle(request(
            1, "resources/list", Map.of()
        )));
        assertEquals(1, list(listedResources.get("resources")).size());

        final Map<String, Object> read = result(protocol.handle(request(
            2, "resources/read", Map.of("uri", "turboism://active/document")
        )));
        assertEquals("turboism://active/document",
            object(list(read.get("contents")).get(0)).get("uri"));

        final Map<String, Object> listedPrompts = result(protocol.handle(request(
            3, "prompts/list", Map.of()
        )));
        assertEquals(8, list(listedPrompts.get("prompts")).size());
        final java.util.Set<Object> promptNames = list(listedPrompts.get("prompts")).stream()
            .map(McpExpandedProtocolTest::object)
            .map(definition -> definition.get("name"))
            .collect(java.util.stream.Collectors.toSet());
        assertTrue(promptNames.contains("diagnose_environment"));
        assertTrue(promptNames.contains("inspect_model_diagnostics"));

        final Map<String, Object> prompt = result(protocol.handle(request(
            4, "prompts/get", Map.of("name", "inspect_active_document")
        )));
        assertEquals(1, list(prompt.get("messages")).size());
        for (String name : java.util.List.of("diagnose_environment", "inspect_model_diagnostics")) {
            final Map<String, Object> diagnosticPrompt = result(protocol.handle(request(
                40 + name.length(), "prompts/get", Map.of("name", name)
            )));
            assertEquals(1, list(diagnosticPrompt.get("messages")).size());
        }

        final Map<String, Object> missingEnvelope = object(protocol.handle(request(
            5, "resources/read", Map.of("uri", "turboism://missing")
        )).body());
        assertEquals(-32002L,
            ((Number) object(missingEnvelope.get("error")).get("code")).longValue());
    }

    @Test
    void resourceFailuresHaveStableRpcClassification() {
        final McpProtocol unavailable = protocolWithResourceFailure(
            new UnsupportedOperationException("active model is unavailable")
        );
        final Map<String, Object> unavailableError = rpcError(unavailable, 1);
        assertEquals(-32003L, ((Number) unavailableError.get("code")).longValue());
        assertEquals("Resource unavailable", unavailableError.get("message"));
        assertEquals("active model is unavailable", unavailableError.get("data"));

        final McpProtocol denied = protocolWithResourceFailure(
            new dev.turboism.sdk.permission.CubismPermissionException("model read permission denied")
        );
        final Map<String, Object> deniedError = rpcError(denied, 2);
        assertEquals(-32001L, ((Number) deniedError.get("code")).longValue());
        assertEquals("Resource permission denied", deniedError.get("message"));
        assertEquals("model read permission denied", deniedError.get("data"));

        final McpProtocol backend = protocolWithResourceFailure(
            new IllegalStateException("native host state is stale")
        );
        final Map<String, Object> backendError = rpcError(backend, 3);
        assertEquals(-32603L, ((Number) backendError.get("code")).longValue());
        assertEquals("Internal error", backendError.get("message"));
        assertEquals("resource read failed", backendError.get("data"));

        final McpProtocol timeout = protocolWithResourceFailure(
            new McpExecutionBridge.ExecutionFailure(
                "MCP operation timed out",
                new java.util.concurrent.TimeoutException()
            )
        );
        final Map<String, Object> timeoutError = rpcError(timeout, 4);
        assertEquals(-32004L, ((Number) timeoutError.get("code")).longValue());
        assertEquals("Resource read timed out", timeoutError.get("message"));

        final McpProtocol internalToolDenied = protocolWithResourceOutput(Map.of(
            "ok", false,
            "error", Map.of("code", "PERMISSION_DENIED", "message", "denied")
        ));
        final Map<String, Object> internalToolDeniedError = rpcError(internalToolDenied, 4);
        assertEquals(-32001L, ((Number) internalToolDeniedError.get("code")).longValue());

        final McpProtocol internalToolUnavailable = protocolWithResourceOutput(Map.of(
            "ok", false,
            "error", Map.of("code", "UNAVAILABLE", "message", "unavailable")
        ));
        final Map<String, Object> internalToolUnavailableError = rpcError(internalToolUnavailable, 5);
        assertEquals(-32003L, ((Number) internalToolUnavailableError.get("code")).longValue());
    }

    private static McpProtocol protocolWithResourceFailure(final RuntimeException failure) {
        return McpProtocol.forCatalogs(
            McpToolCatalog.empty(),
            new McpResourceCatalog(java.util.List.of(), java.util.List.of(), uri -> {
                throw failure;
            }),
            McpPromptCatalog.defaults()
        );
    }

    private static McpProtocol protocolWithResourceOutput(final Map<String, Object> output) {
        return McpProtocol.forCatalogs(
            McpToolCatalog.empty(),
            new McpResourceCatalog(java.util.List.of(), java.util.List.of(), uri ->
                java.util.List.of(Map.of(
                    "uri", uri,
                    "mimeType", "application/json",
                    "text", Json.stringify(output)
                ))
            ),
            McpPromptCatalog.defaults()
        );
    }

    private static Map<String, Object> rpcError(final McpProtocol protocol, final int id) {
        return object(object(protocol.handle(request(
            id, "resources/read", Map.of("uri", "turboism://active/document")
        )).body()).get("error"));
    }

    private static Map<String, Object> toolEnvelope(final Map<String, Object> output) {
        return Map.of(
            "content", java.util.List.of(Map.of(
                "type", "text",
                "text", Json.stringify(output)
            )),
            "structuredContent", output,
            "isError", !Boolean.TRUE.equals(output.get("ok"))
        );
    }

    private static Map<String, Object> initializeRequest(
        final int id,
        final String protocolVersion
    ) {
        return request(id, "initialize", Map.of(
            "protocolVersion", protocolVersion,
            "capabilities", Map.of(),
            "clientInfo", Map.of("name", "test", "version", "1")
        ));
    }

    private static Map<String, Object> request(
        final int id,
        final String method,
        final Map<String, Object> params
    ) {
        return Map.of("jsonrpc", "2.0", "id", id, "method", method, "params", params);
    }

    private static Map<String, Object> result(final McpProtocol.Outcome outcome) {
        return object(object(outcome.body()).get("result"));
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> list(final Object value) {
        return (java.util.List<Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }
}
