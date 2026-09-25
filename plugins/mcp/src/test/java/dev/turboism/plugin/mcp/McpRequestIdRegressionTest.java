package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpRequestIdRegressionTest {
    @Test
    void invalidIdsAreProtocolErrorsBeforeDispatch() {
        final AtomicInteger calls = new AtomicInteger();
        final McpProtocol protocol = protocol(calls);
        for (Object id : Arrays.asList(null, List.of(1), Map.of("id", 1), true,
            new BigDecimal("1.5"), Double.NaN, Double.POSITIVE_INFINITY)) {
            final Map<String, Object> request = new LinkedHashMap<>();
            request.put("jsonrpc", "2.0");
            request.put("id", id);
            request.put("method", "tools/call");
            request.put("params", Map.of("name", "audit.write"));
            final Map<?, ?> response = (Map<?, ?>) protocol.handle(request).body();
            assertEquals(-32600, ((Map<?, ?>) response.get("error")).get("code"));
            assertNull(response.get("id"));
        }
        assertEquals(0, calls.get());
    }

    @Test
    void stringAndIntegralIdsKeepTheirIdentity() {
        final McpProtocol protocol = protocol(new AtomicInteger());
        for (Object id : List.of("req-1", 0, -3L, new BigDecimal("4.0"))) {
            final Map<?, ?> response = (Map<?, ?>) protocol.handle(Map.of(
                "jsonrpc", "2.0", "id", id, "method", "ping")).body();
            assertEquals(id, response.get("id"));
            assertTrue(response.containsKey("result"));
        }
    }

    @Test
    void genuineNotificationsNeverReceiveAResponseOrDispatchAWrite() {
        final AtomicInteger calls = new AtomicInteger();
        final McpProtocol protocol = protocol(calls);
        final McpProtocol.Outcome result = protocol.handle(Map.of(
            "jsonrpc", "2.0", "method", "tools/call", "params", Map.of("name", "audit.write")));
        assertEquals(202, result.status());
        assertNull(result.body());
        assertEquals(0, calls.get());
    }

    private static McpProtocol protocol(final AtomicInteger calls) {
        final McpToolCatalog catalog = new McpToolCatalog(List.of(Map.of(
            "name", "audit.write", "inputSchema", Map.of("type", "object")
        )), (name, arguments) -> {
            calls.incrementAndGet();
            final Map<String, Object> output = Map.of("ok", true);
            return Map.of("structuredContent", output, "isError", false,
                "content", List.of(Map.of("type", "text", "text", Json.stringify(output))));
        });
        return McpProtocol.forCatalogs(catalog, McpResourceCatalog.empty(), McpPromptCatalog.defaults());
    }
}
