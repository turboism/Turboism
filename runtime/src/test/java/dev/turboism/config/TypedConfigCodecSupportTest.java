package dev.turboism.config;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypedConfigCodecSupportTest {

    private enum Mode {
        SAFE,
        FAST
    }

    @Test
    void booleanAndIntegerRejectNoncanonicalOrOutOfRangeValues() {
        final ConfigKey<Boolean> enabled = new ConfigKey<>(
            "main", "enabled", true, ConfigCodecs.booleanValue()
        );
        assertEquals("true", TypedConfigCodecSupport.encode(enabled, true).orElseThrow());
        assertEquals(true, TypedConfigCodecSupport.decode(enabled, "true").orElseThrow());
        assertTrue(TypedConfigCodecSupport.decode(enabled, "TRUE").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(enabled, " true").isEmpty());

        final ConfigKey<Integer> count = new ConfigKey<>(
            "main", "count", 1, ConfigCodecs.boundedInt(-2, 4)
        );
        assertEquals("-2", TypedConfigCodecSupport.encode(count, -2).orElseThrow());
        assertEquals(4, TypedConfigCodecSupport.decode(count, "4").orElseThrow());
        assertTrue(TypedConfigCodecSupport.decode(count, "+1").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(count, "01").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(count, "-0").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(count, "5").isEmpty());
    }

    @Test
    void enumUsesExactNameAndDeclaringClass() {
        final ConfigKey<Mode> mode = new ConfigKey<>(
            "main", "mode", Mode.SAFE, ConfigCodecs.enumValue(Mode.class)
        );
        assertEquals("FAST", TypedConfigCodecSupport.encode(mode, Mode.FAST).orElseThrow());
        assertEquals(Mode.SAFE, TypedConfigCodecSupport.decode(mode, "SAFE").orElseThrow());
        assertTrue(TypedConfigCodecSupport.decode(mode, "safe").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(mode, "0").isEmpty());
    }

    @Test
    void stringListUsesStrictCanonicalJsonAndImmutableDecodedValue() {
        final ConfigKey<List<String>> values = new ConfigKey<>(
            "main",
            "values",
            List.of(),
            ConfigCodecs.boundedStringList(3, 32)
        );
        final String encoded = "[\"a\",\"line\\nquote\\\"slash\\\\\"]";
        assertEquals(
            encoded,
            TypedConfigCodecSupport.encode(
                values,
                List.of("a", "line\nquote\"slash\\")
            ).orElseThrow()
        );
        @SuppressWarnings("unchecked")
        final List<String> decoded = (List<String>) TypedConfigCodecSupport.decode(
            values,
            encoded
        ).orElseThrow();
        assertEquals(List.of("a", "line\nquote\"slash\\"), decoded);
        assertTrue(TypedConfigCodecSupport.decode(values, "[ \"a\"]").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(values, "[\"\\/\"]").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(values, "[\"\\u0061\"]").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(values, "[\"a\",]").isEmpty());
        assertTrue(TypedConfigCodecSupport.encode(
            values,
            List.of("a", "b", "c", "d")
        ).isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(values, "[\"\\u00AF\"]").isEmpty());
        assertTrue(TypedConfigCodecSupport.decode(values, "[\"\\ud800\"]").isEmpty());
        assertThrowsMutation(decoded);
    }

    private static void assertThrowsMutation(final List<String> values) {
        try {
            values.add("x");
            throw new AssertionError("decoded list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // expected
        }
    }
}
