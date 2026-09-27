package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class McpInputPreflightTest {
    @Test
    void bothPublicAndRawEntrypointsRejectInvalidInputWithoutCallingTheHandler() {
        final AtomicInteger calls = new AtomicInteger();
        final McpToolCatalog catalog = catalog(calls);
        for (Map<String, Object> arguments : List.<Map<String, Object>>of(
            Map.of("values", List.of()),
            Map.of("values", List.of(1, "invalid")),
            Map.of("values", List.of(1), "unknown", true),
            Map.of("values", List.of(Double.NaN)),
            Map.of("values", List.of(Double.POSITIVE_INFINITY))
        )) {
            assertThrows(IllegalArgumentException.class, () -> catalog.call("audit.write", arguments));
            assertThrows(IllegalArgumentException.class, () -> catalog.callRaw("audit.write", arguments));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void bothEntrypointsStillDispatchValidArgumentsExactlyOnce() {
        final AtomicInteger calls = new AtomicInteger();
        final McpToolCatalog catalog = catalog(calls);
        catalog.call("audit.write", Map.of("values", List.of(1, 2)));
        catalog.callRaw("audit.write", Map.of("values", List.of(3, 4)));
        assertEquals(2, calls.get());
    }

    private static McpToolCatalog catalog(final AtomicInteger calls) {
        final Map<String, Object> definition = Map.of(
            "name", "audit.write", "title", "Audit write", "description", "Regression fixture",
            "inputSchema", Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("values", Map.of("type", "array", "minItems", 1,
                    "items", Map.of("type", "number"))), "required", List.of("values")),
            "outputSchema", Map.of("type", "object", "additionalProperties", false,
                "properties", Map.of("ok", Map.of("type", "boolean")), "required", List.of("ok"))
        );
        return McpToolCatalog.of(List.of(McpRegisteredTool.typed(
            definition, McpOperationEffect.UNDOABLE_WRITE, McpExecutionAffinity.DIRECT, true,
            new McpExecutionBridge(new UiScheduler() {
                @Override public Registration runOnUiThread(final Runnable work) {
                    work.run();
                    return () -> { };
                }
                @Override public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                    throw new UnsupportedOperationException();
                }
            }),
            arguments -> {
                calls.incrementAndGet();
                final Map<String, Object> output = Map.of("ok", true);
                return Map.of("structuredContent", output, "isError", false,
                    "content", List.of(Map.of("type", "text", "text", Json.stringify(output))));
            }
        )));
    }
}
