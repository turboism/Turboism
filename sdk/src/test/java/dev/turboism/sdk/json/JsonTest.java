package dev.turboism.sdk.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class JsonTest {

    @Test
    void roundTripsSupportedJsonShapesInInsertionOrder() {
        final LinkedHashMap<String, Object> input = new LinkedHashMap<>();
        input.put("text", "line\n🚀");
        input.put("number", 42L);
        input.put("array", List.of(true, "value"));
        input.put("object", Map.of("nested", false));

        final String encoded = Json.stringify(input);

        assertEquals(
                "{\"text\":\"line\\n🚀\",\"number\":42,\"array\":[true,\"value\"],\"object\":{\"nested\":false}}",
                encoded);
        assertEquals(input, Json.parseObject(encoded.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void decodesNumbersAsLongOrBigDecimal() {
        assertEquals(42L, parse("42"));
        assertEquals(-7L, parse("-7"));
        assertEquals(0L, parse("0"));
        assertEquals(new BigDecimal("1.5"), parse("1.5"));
        assertEquals(new BigDecimal("3.0"), parse("3.0"));
        assertEquals(100L, parse("1e2"));
        assertEquals(new BigDecimal("9223372036854775808"), parse("9223372036854775808"));
        assertEquals(Long.MAX_VALUE, parse("9223372036854775807"));
        assertInstanceOf(Long.class, parse("42"));
        assertInstanceOf(BigDecimal.class, parse("1.5"));
    }

    @Test
    void decodesContainersAsOrderedMapsAndLists() {
        final Object value = parse("{\"b\":1,\"a\":[null,\"x\"]}");
        final Map<String, Object> root = assertInstanceOf(Map.class, value);
        assertEquals(List.of("b", "a"), List.copyOf(root.keySet()));
        assertInstanceOf(List.class, root.get("a"));
    }

    @Test
    void parseObjectRequiresAnObjectRoot() {
        assertEquals(Map.of("letter", "A"), Json.parseObject("{\"letter\":\"\\u0041\"}"));
        assertEquals(
                Map.of("letter", "A"), Json.parseObject("{\"letter\":\"\\u0041\"}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("null".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void parseArrayRequiresAnArrayRoot() {
        assertEquals(List.of(1L, "x"), Json.parseArray("[1,\"x\"]"));
        assertEquals(List.of(1L), Json.parseArray("[1]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IllegalArgumentException.class, () -> Json.parseArray("{}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseArray("1".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsMalformedUtf8BomDuplicatesAndTrailingContent() {
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(new byte[] {(byte) 0xc3, (byte) 0x28}));
        assertThrows(
                IllegalArgumentException.class,
                () -> Json.parseObject(new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("\uFEFF{}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject(""));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"id\":1,\"id\":2}"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{} []"));
        assertThrows(IllegalArgumentException.class, () -> Json.parseArray("["));
        assertThrows(IllegalArgumentException.class, () -> Json.parseObject("{\"a\":"));
    }

    @Test
    void rejectsNonJsonPrefixesBeforeNumberParsing() {
        final IllegalArgumentException failure =
                assertThrows(IllegalArgumentException.class, () -> parse("Picked up JAVA_TOOL_OPTIONS"));
        assertTrue(failure.getMessage().contains("invalid JSON value"));
        assertThrows(IllegalArgumentException.class, () -> parse("x"));
        assertThrows(IllegalArgumentException.class, () -> parse("truth"));
    }

    @Test
    void rejectsInvalidNumbersSurrogatesAndExcessiveNesting() {
        assertThrows(IllegalArgumentException.class, () -> parse("01"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e+-2"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e-+2"));
        assertThrows(IllegalArgumentException.class, () -> parse("-١"));
        assertThrows(IllegalArgumentException.class, () -> parse("١"));
        assertThrows(IllegalArgumentException.class, () -> parse("１２"));
        assertThrows(IllegalArgumentException.class, () -> parse("1.١"));
        assertThrows(IllegalArgumentException.class, () -> parse("1e١"));
        assertThrows(IllegalArgumentException.class, () -> parse("1١"));
        assertEquals("A", parse("\"\\u0041\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\u٠٠٤١\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\u００ＡＦ\""));
        assertThrows(IllegalArgumentException.class, () -> parse("\"\\ud800\""));
        assertThrows(IllegalArgumentException.class, () -> parse("[".repeat(65) + "0" + "]".repeat(65)));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", "\ud800")));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", "\udc00")));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", Float.POSITIVE_INFINITY)));
    }

    @Test
    void writerRejectsNonStringKeysAndUnknownTypes() {
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", Map.of(1, "x"))));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", new Object())));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Map.of("k", new StringBuilder("x"))));
        assertEquals("{\"k\":[1,2]}", Json.stringify(Map.of("k", new Object[] {1, 2})));
        assertEquals("[]", Json.stringify(List.of()));
        assertEquals("{}", Json.stringify(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify((List<?>) nest(70)));
    }

    @Test
    void sanitizeReturnsSameInstanceWhenNothingNeedsRewriting() {
        final Map<String, Object> clean = Map.of(
                "ok", true, "name", "plain text", "items", List.of(1, 2.5, "x"), "nested", Map.of("emoji", "😀"));
        org.junit.jupiter.api.Assertions.assertSame(clean, Json.sanitize(clean));
    }

    @Test
    void sanitizeReplacesUnpairedSurrogatesAndNonFiniteNumbers() {
        final String high = "a" + (char) 0xD800 + "b";
        final String low = "a" + (char) 0xDC00 + "b";
        final String pair = "a😀b";

        assertEquals("a\uFFFDb", Json.sanitize(high));
        assertEquals("a\uFFFDb", Json.sanitize(low));
        org.junit.jupiter.api.Assertions.assertSame(pair, Json.sanitize(pair), "valid surrogate pairs are untouched");

        org.junit.jupiter.api.Assertions.assertNull(Json.sanitize(Float.NaN));
        org.junit.jupiter.api.Assertions.assertNull(Json.sanitize(Double.POSITIVE_INFINITY));
        org.junit.jupiter.api.Assertions.assertNull(Json.sanitize(Double.NEGATIVE_INFINITY));
        assertEquals(1.5, Json.sanitize(1.5));

        final String bad = "x" + (char) 0xD800;
        final Map<String, Object> dirty =
                Map.of(bad, List.of("clean", bad, Map.of("deep", bad)), "other", List.of(1, bad));
        final Map<String, Object> safe = Json.sanitize(dirty);
        assertEquals(
                Map.of(
                        "x\uFFFD",
                        List.of("clean", "x\uFFFD", Map.of("deep", "x\uFFFD")),
                        "other",
                        List.of(1, "x\uFFFD")),
                safe);
        assertTrue(dirty.containsKey(bad), "input map must not be mutated");
    }

    private static Object nest(final int depth) {
        Object value = "x";
        for (int index = 0; index < depth; index++) {
            value = List.of(value);
        }
        return value;
    }

    private static Object parse(final String value) {
        // The public surface only exposes typed roots; wrap scalar fixtures in an array.
        return Json.parseArray("[" + value + "]").get(0);
    }
}
