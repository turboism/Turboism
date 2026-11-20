package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class McpCapabilitiesDomainTest {

    @Test
    void readPublishesTheBuildGuardedSdkCoverageLedger() {
        final AtomicReference<McpToolCatalog> catalog = new AtomicReference<>();
        final McpCapabilitiesDomain domain = new McpCapabilitiesDomain(catalog::get);
        final McpToolCatalog combined = McpToolCatalog.combine(
                new McpToolCatalog(
                        List.of(Map.of(
                                "name",
                                "turboism.fixture.read",
                                "title",
                                "Fixture read",
                                "description",
                                "Fixture read operation.",
                                "inputSchema",
                                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                                "outputSchema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of("ok", Map.of("type", "boolean")),
                                        "required",
                                        List.of("ok"),
                                        "additionalProperties",
                                        false),
                                "annotations",
                                Map.of("readOnlyHint", true))),
                        (name, arguments) -> Map.of()),
                domain.tools());
        catalog.set(combined);

        final Map<String, Object> output = output(combined.call(McpCapabilitiesDomain.CAPABILITIES_READ, Map.of()));

        assertTrue((Boolean) output.get("ok"));
        final Map<String, Object> coverage = object(output.get("coverage"));
        assertEquals(1, ((Number) coverage.get("schemaVersion")).intValue());
        assertTrue(((List<?>) coverage.get("trackedOwners")).contains("dev.turboism.sdk.cubism.model.Glue"));
        assertTrue(((List<?>) coverage.get("entries")).size() >= 30);
        assertEquals(3, ((List<?>) coverage.get("temporaryPublicExceptions")).size());
        final String rendered = Json.stringify(coverage);
        assertFalse(rendered.contains("com.live2d"));
        assertFalse(rendered.contains("/home/"));
    }

    @Test
    void toolAndIncludeCoverageFiltersKeepTheLedgerOutOfSingleToolReads() {
        final AtomicReference<McpToolCatalog> catalog = new AtomicReference<>();
        final McpCapabilitiesDomain domain = new McpCapabilitiesDomain(catalog::get);
        final McpToolCatalog combined = McpToolCatalog.combine(
                new McpToolCatalog(
                        List.of(fixture("turboism.fixture.alpha"), fixture("turboism.fixture.beta")),
                        (name, arguments) -> Map.of()),
                domain.tools());
        catalog.set(combined);

        final Map<String, Object> filtered = output(combined.call(
                McpCapabilitiesDomain.CAPABILITIES_READ,
                Map.of("tool", "turboism.fixture.beta", "includeCoverage", false)));

        assertEquals(List.of("turboism.fixture.beta"), names(filtered));
        assertTrue(filtered.containsKey("coverage"));
        assertEquals(null, filtered.get("coverage"));

        final Map<String, Object> unknown = output(
                combined.call(McpCapabilitiesDomain.CAPABILITIES_READ, Map.of("tool", "turboism.fixture.missing")));
        assertEquals(Boolean.FALSE, unknown.get("ok"));
        assertEquals("TOOL_NOT_REGISTERED", unknown.get("code"));
        assertEquals(List.of(), unknown.get("operations"));

        final Map<String, Object> omitted =
                output(combined.call(McpCapabilitiesDomain.CAPABILITIES_READ, Map.of("includeCoverage", false)));
        assertTrue(names(omitted).containsAll(List.of("turboism.fixture.alpha", "turboism.fixture.beta")));
        assertEquals(null, omitted.get("coverage"));
    }

    @Test
    void unknownOrMalformedFiltersAreRejectedBeforeAnyLedgerWork() {
        final AtomicReference<McpToolCatalog> catalog = new AtomicReference<>();
        final McpCapabilitiesDomain domain = new McpCapabilitiesDomain(catalog::get);
        catalog.set(McpToolCatalog.combine(
                new McpToolCatalog(List.of(fixture("turboism.fixture.alpha")), (name, arguments) -> Map.of()),
                domain.tools()));

        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
                Map.of("domain", "authoring"),
                Map.of("tool", 7),
                Map.of("tool", "  "),
                Map.of("includeCoverage", "no"))) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> catalog.get().call(McpCapabilitiesDomain.CAPABILITIES_READ, arguments));
        }
    }

    private static Map<String, Object> fixture(final String name) {
        return Map.of(
                "name",
                name,
                "title",
                "Fixture",
                "description",
                "Fixture read operation.",
                "inputSchema",
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false),
                "outputSchema",
                Map.of(
                        "type",
                        "object",
                        "properties",
                        Map.of("ok", Map.of("type", "boolean")),
                        "required",
                        List.of("ok"),
                        "additionalProperties",
                        false),
                "annotations",
                Map.of("readOnlyHint", true));
    }

    private static List<Object> names(final Map<String, Object> output) {
        return ((List<?>) output.get("operations"))
                .stream()
                        .map(McpCapabilitiesDomainTest::object)
                        .map(operation -> operation.get("name"))
                        .toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> output(final Map<String, Object> envelope) {
        return (Map<String, Object>) envelope.get("structuredContent");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }
}
