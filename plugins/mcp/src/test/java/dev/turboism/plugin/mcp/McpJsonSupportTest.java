package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class McpJsonSupportTest {

    @Test
    void encodableReturnsTheSameInstanceWhenNothingNeedsRewriting() {
        final Map<String, Object> output = Map.of(
                "ok", true, "name", "plain text", "items", List.of(1, 2.5, "x"), "nested", Map.of("emoji", "😀"));

        assertSame(output, McpJsonSupport.encodable(output));
    }

    @Test
    void unpairedSurrogatesAreReplacedWithReplacementCharacter() {
        final String high = "a" + (char) 0xD800 + "b";
        final String low = "a" + (char) 0xDC00 + "b";
        final String pair = "a😀b";

        assertEquals("a�b", McpJsonSupport.encodable(high));
        assertEquals("a�b", McpJsonSupport.encodable(low));
        assertSame(pair, McpJsonSupport.encodable(pair), "valid surrogate pairs are untouched");
    }

    @Test
    void sanitizesNestedValuesAndMapKeys() {
        final String bad = "x" + (char) 0xD800;
        final Map<String, Object> output =
                Map.of(bad, List.of("clean", bad, Map.of("deep", bad)), "other", List.of(1, bad));

        final Map<String, Object> safe = McpJsonSupport.encodable(output);

        assertEquals(Map.of("x�", List.of("clean", "x�", Map.of("deep", "x�")), "other", List.of(1, "x�")), safe);
        // The input map is never mutated.
        assertTrue(output.containsKey(bad));
    }

    @Test
    void nonFiniteNumbersBecomeNull() {
        assertNull(McpJsonSupport.encodable(Float.NaN));
        assertNull(McpJsonSupport.encodable(Double.POSITIVE_INFINITY));
        assertNull(McpJsonSupport.encodable(Double.NEGATIVE_INFINITY));
        assertEquals(1.5, McpJsonSupport.encodable(1.5));
    }

    @Test
    void stringifyEncodesWhatStrictJsonWouldReject() {
        final Map<String, Object> output = Map.of("name", "a" + (char) 0xD800, "value", Double.NaN);

        final String encoded = McpJsonSupport.stringify(output);

        final Map<?, ?> reparsed =
                (Map<?, ?>) Json.parseObject(encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals("a�", reparsed.get("name"));
        assertTrue(reparsed.containsKey("value") && reparsed.get("value") == null);
    }
}
