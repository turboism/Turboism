package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Regression contracts for the single-pass catalog, schema, and diagnostics hot paths. */
final class McpCatalogHotPathTest {

    private static final byte[] PNG_HEADER = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    @Test
    void observingACatalogValidatesTheEnvelopeExactlyOnce() {
        final AtomicInteger validations = new AtomicInteger();
        final McpRegisteredTool registration = tool("turboism.observe.once", outputSchema(), arguments -> {
            validations.incrementAndGet();
            return envelope(Map.of("ok", true, "value", "observed"));
        });
        final McpToolCatalog observed = new McpRuntimeDiagnostics().observe(McpToolCatalog.of(List.of(registration)));

        final Map<String, Object> result = observed.call("turboism.observe.once", Map.of());

        assertEquals(1, validations.get(), "the handler must run once, not once per catalog layer");
        assertFalse((Boolean) result.get("isError"));
        assertEquals("observed", object(result.get("structuredContent")).get("value"));
    }

    @Test
    void pngSignatureAndBudgetAreCheckedWithoutDecodingWholePayloads() {
        assertFalse(isError(envelopeWithImages(List.of(image(validPng(64))))));

        final byte[] notPng = new byte[PNG_HEADER.length + 64];
        assertTrue(isError(envelopeWithImages(List.of(image(Base64.getEncoder().encodeToString(notPng))))));

        final byte[] oversized = new byte[3 * 1024 * 1024 + 1];
        System.arraycopy(PNG_HEADER, 0, oversized, 0, PNG_HEADER.length);
        assertTrue(isError(envelopeWithImages(List.of(image(Base64.getEncoder().encodeToString(oversized))))));
        assertTrue(isError(envelopeWithImages(List.of(image("A")))));
    }

    @Test
    void malformedBase64ImagesAreRejectedByTheEnvelopeCheck() {
        for (String data : List.of("broken", "iVBORw0KGgo$=", "====", "A")) {
            assertTrue(isError(envelopeWithImages(List.of(image(data)))), data + " must not reach the wire");
        }
    }

    @Test
    void compiledPatternsAreReusedAcrossStringValidations() {
        final Map<String, Object> schema = Map.of("type", "string", "pattern", "^[a-z]+$");

        assertTrue(McpJsonSchema.validates("abc", schema));
        assertTrue(McpJsonSchema.validates("def", schema));
        assertFalse(McpJsonSchema.validates("ABC", schema));
        assertFalse(McpJsonSchema.validates("abc", Map.of("type", "string", "pattern", "[")));
    }

    @Test
    void sharedDefinitionsValidateRecursiveDetailAtFullDepth() {
        final Map<String, Object> root = McpOutputSchemas.historyRead();
        final Map<String, Object> definitions = McpJsonSchema.definitionsOf(root);

        assertFalse(definitions.isEmpty(), "history contracts advertise shared definitions");
        assertTrue(
                McpJsonSchema.validates(
                        Map.of("ok", true, "snapshot", snapshot(detail(nest(3))), "diagnosticId", "diag"), root),
                "shared definitions must validate nested group detail threaded through group.children");

        final java.util.LinkedHashMap<String, Object> deep = new java.util.LinkedHashMap<>();
        deep.put("ok", true);
        deep.put("snapshot", snapshot(detail(nest(8))));
        deep.put("diagnosticId", null);
        assertTrue(
                McpJsonSchema.validates(deep, root),
                "recursion depth must exceed the four inline levels the previous shape admitted");

        final java.util.LinkedHashMap<String, Object> partial = new java.util.LinkedHashMap<>();
        partial.put("ok", true);
        partial.put("snapshot", Map.of());
        assertFalse(McpJsonSchema.validates(partial, root));
    }

    @Test
    void unresolvedSharedDefinitionFailsClosed() {
        final Map<String, Object> schema =
                Map.of("$ref", "#/$defs/missing", "$defs", Map.of("present", Map.of("type", "string")));

        assertFalse(McpJsonSchema.validates("value", schema));
    }

    @Test
    void cleanSuccessSkipsTheOutcomeStringScanButWarningsStillRecord() {
        final McpRuntimeDiagnostics clean = diagnosticsAfter(Map.of("ok", true, "vertices", List.of("lowercase")));
        assertEquals(0, clean.snapshot().events().size());

        final McpRuntimeDiagnostics warned = diagnosticsAfter(
                Map.of("ok", true, "results", List.of(Map.of("result", Map.of("outcome", "OUTCOME_UNKNOWN")))));
        assertEquals(
                List.of("OUTCOME_UNKNOWN"),
                warned.snapshot().events().stream()
                        .map(McpRuntimeDiagnostics.Event::kind)
                        .toList());
    }

    private static McpRuntimeDiagnostics diagnosticsAfter(final Map<String, Object> structured) {
        final McpRuntimeDiagnostics diagnostics = new McpRuntimeDiagnostics(8, java.time.Clock.systemUTC());
        final McpToolCatalog observed = diagnostics.observe(McpToolCatalog.of(
                List.of(tool("turboism.outcome", outputSchema(), arguments -> envelope(structured)))));
        observed.call("turboism.outcome", Map.of());
        return diagnostics;
    }

    private static boolean isError(final Map<String, Object> structured) {
        final Map<String, Object> output = object(structured.get("output"));
        final McpToolCatalog catalog = McpToolCatalog.of(List.of(tool("turboism.images", outputSchema(), arguments -> {
            final Map<String, Object> result = new java.util.LinkedHashMap<>(envelope(output));
            result.put("content", structured.get("images"));
            return result;
        })));
        return Boolean.TRUE.equals(catalog.call("turboism.images", Map.of()).get("isError"));
    }

    private static Map<String, Object> envelopeWithImages(final List<Map<String, Object>> images) {
        final Map<String, Object> output = Map.of("ok", true);
        final java.util.ArrayList<Map<String, Object>> blocks = new java.util.ArrayList<>();
        blocks.add(Map.of("type", "text", "text", Json.stringify(output)));
        blocks.addAll(images);
        return Map.of("output", output, "images", List.copyOf(blocks));
    }

    private static Map<String, Object> image(final String data) {
        return Map.of("type", "image", "mimeType", "image/png", "data", data);
    }

    private static String validPng(final int extraBytes) {
        final byte[] png = new byte[PNG_HEADER.length + extraBytes];
        System.arraycopy(PNG_HEADER, 0, png, 0, PNG_HEADER.length);
        return Base64.getEncoder().encodeToString(png);
    }

    /** A complete history snapshot of one entry whose detail carries {@code group}. */
    private static Map<String, Object> snapshot(final Map<String, Object> detail) {
        final java.util.LinkedHashMap<String, Object> entry = new java.util.LinkedHashMap<>();
        entry.put("index", 0);
        entry.put("label", "Undo");
        entry.put("significant", true);
        entry.put("detailLevel", "FULL");
        entry.put("entryId", null);
        entry.put("transactionId", null);
        entry.put("action", null);
        entry.put("detail", detail);
        final java.util.LinkedHashMap<String, Object> snapshot = new java.util.LinkedHashMap<>();
        snapshot.put("availability", "AVAILABLE");
        snapshot.put("generation", 0);
        snapshot.put("revision", 0);
        snapshot.put("position", 0);
        snapshot.put("entries", List.of(entry));
        snapshot.put("canUndo", false);
        snapshot.put("canRedo", false);
        return snapshot;
    }

    private static Map<String, Object> detail(final Object group) {
        final java.util.LinkedHashMap<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("summary", "detail");
        value.put("detailLevel", "FULL");
        final java.util.LinkedHashMap<String, Object> origin = new java.util.LinkedHashMap<>();
        origin.put("kind", "TURBOISM");
        origin.put("producerId", null);
        origin.put("operationId", null);
        value.put("origin", origin);
        value.put("targets", List.of());
        value.put("changes", List.of());
        value.put("group", group);
        value.put("degradationCode", null);
        return value;
    }

    /** Builds {@code depth} nested groups, each holding one child detail. */
    private static Object nest(final int depth) {
        if (depth == 0) return null;
        return Map.of(
                "groupId",
                "group-" + depth,
                "observedChildCount",
                1,
                "truncated",
                false,
                "children",
                List.of(detail(depth == 1 ? null : nest(depth - 1))));
    }

    private static McpRegisteredTool tool(
            final String name, final Map<String, Object> outputSchema, final McpRegisteredTool.Handler handler) {
        return McpRegisteredTool.typed(
                Map.of(
                        "name",
                        name,
                        "title",
                        "Heat path fixture",
                        "description",
                        "Exercises the catalog hot path.",
                        "inputSchema",
                        Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                        "outputSchema",
                        outputSchema),
                McpOperationEffect.READ,
                McpExecutionAffinity.DIRECT,
                false,
                new McpExecutionBridge(FakeDirectUiScheduler.direct()),
                handler);
    }

    private static Map<String, Object> outputSchema() {
        return Map.of(
                "type",
                "object",
                "properties",
                Map.of("ok", Map.of("type", "boolean"), "value", Map.of("type", "string")),
                "required",
                List.of("ok"),
                "additionalProperties",
                true);
    }

    private static Map<String, Object> envelope(final Map<String, Object> structured) {
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(structured))),
                "structuredContent",
                structured,
                "isError",
                false);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }
}
