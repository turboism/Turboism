package dev.turboism.protocol.json;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StrictJsonTest {

    @Test
    void roundTripsSupportedJsonShapesInInsertionOrder() {
        final LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("text", "line\n\uD83D\uDE80");
        input.put("number", 42L);
        input.put("array", List.of(true, "value"));
        input.put("object", Map.of("nested", false));

        final String encoded = StrictJson.stringify(input);

        assertEquals(
            "{\"text\":\"line\\n\uD83D\uDE80\",\"number\":42,\"array\":[true,\"value\"],\"object\":{\"nested\":false}}",
            encoded
        );
        assertEquals(input, StrictJson.parse(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedUtf8BomDuplicatesAndTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> StrictJson.parse(new byte[] {
            (byte) 0xc3, (byte) 0x28
        }));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.parse(new byte[] {
            (byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'
        }));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"id\":1,\"id\":2}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{} []"));
    }

    @Test
    void rejectsNonJsonPrefixesBeforeNumberParsing() {
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> parse("Picked up JAVA_TOOL_OPTIONS")
        );
        assertTrue(failure.getMessage().contains("invalid JSON value"));
    }

    @Test
    void rejectsInvalidNumbersSurrogatesAndExcessiveNesting() {
        assertThrows(IllegalArgumentException.class, () -> parse("01"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e+-2"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e-+2"));
        assertThrows(IllegalArgumentException.class, () -> parse("-١"));
        assertThrows(IllegalArgumentException.class, () -> parse("1.١"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e١"));
        assertThrows(IllegalArgumentException.class, () -> parse("1١"));
        assertEquals("A", parse("\"\\u0041\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\u٠٠٤١\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\u００ＡＦ\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\ud800\""));
        assertThrows(IllegalArgumentException.class, () -> parse("[".repeat(65) + "0" + "]".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.stringify("\ud800"));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.stringify("\udc00"));
        assertThrows(IllegalArgumentException.class, () -> StrictJson.stringify(Double.NaN));
    }

    private static Object parse(final String value) {
        return StrictJson.parse(value.getBytes(StandardCharsets.UTF_8));
    }
}
