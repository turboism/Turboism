package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class JsonTest {

    @Test
    void decodesOnlyAsciiHexDigitsInUnicodeEscapes() {
        assertEquals(
            Map.of("letter", "A"),
            Json.parse("{\"letter\":\"\\u0041\"}".getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.parse("{\"letter\":\"\\u٠٠٤١\"}".getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.parse("{\"letter\":\"\\u００４１\"}".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    void rejectsNonAsciiNumberDigits() {
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.parse("١".getBytes(StandardCharsets.UTF_8))
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> Json.parse("１２".getBytes(StandardCharsets.UTF_8))
        );
    }
}
