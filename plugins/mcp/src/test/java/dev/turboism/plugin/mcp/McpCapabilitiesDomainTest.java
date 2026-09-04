package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpCapabilitiesDomainTest {

    @Test
    void readPublishesTheBuildGuardedSdkCoverageLedger() {
        final AtomicReference<McpToolCatalog> catalog = new AtomicReference<>();
        final McpCapabilitiesDomain domain = new McpCapabilitiesDomain(catalog::get);
        final McpToolCatalog combined = McpToolCatalog.combine(
            new McpToolCatalog(
                List.of(Map.of(
                    "name", "turboism.fixture.read",
                    "title", "Fixture read",
                    "description", "Fixture read operation.",
                    "inputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of(),
                        "additionalProperties", false
                    ),
                    "outputSchema", Map.of(
                        "type", "object",
                        "properties", Map.of("ok", Map.of("type", "boolean")),
                        "required", List.of("ok"),
                        "additionalProperties", false
                    ),
                    "annotations", Map.of("readOnlyHint", true)
                )),
                (name, arguments) -> Map.of()
            ),
            domain.tools()
        );
        catalog.set(combined);

        final Map<String, Object> output = output(combined.call(
            McpCapabilitiesDomain.CAPABILITIES_READ,
            Map.of()
        ));

        assertTrue((Boolean) output.get("ok"));
        final Map<String, Object> coverage = object(output.get("coverage"));
        assertEquals(1, ((Number) coverage.get("schemaVersion")).intValue());
        assertTrue(((List<?>) coverage.get("trackedOwners")).contains(
            "dev.turboism.sdk.cubism.model.Glue"
        ));
        assertTrue(((List<?>) coverage.get("entries")).size() >= 30);
        assertEquals(3, ((List<?>) coverage.get("temporaryPublicExceptions")).size());
        final String rendered = Json.stringify(coverage);
        assertFalse(rendered.contains("com.live2d"));
        assertFalse(rendered.contains("/home/"));
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
