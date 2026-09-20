package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpSchemaValidationRegressionTest {
    @Test
    void oneOfRequiresExactlyOneMatchAndStillAppliesSiblingConstraints() {
        assertFalse(McpJsonSchema.validates(2, Map.of("oneOf", List.of(
            Map.of("type", "integer"), Map.of("type", "number")))));
        assertTrue(McpJsonSchema.validates(2.5, Map.of("oneOf", List.of(
            Map.of("type", "integer"), Map.of("type", "number")))));
        assertFalse(McpJsonSchema.validates(2, Map.of("minimum", 3, "oneOf", List.of(
            Map.of("type", "integer"), Map.of("type", "string")))));
    }

    @Test
    void strictNumericBoundsDoNotAdmitZeroSizedGeometry() {
        final Map<String, Object> schema = Map.of("type", "number", "exclusiveMinimum", 0,
            "exclusiveMaximum", 10);
        assertFalse(McpJsonSchema.validates(0, schema));
        assertFalse(McpJsonSchema.validates(10, schema));
        assertTrue(McpJsonSchema.validates(0.5, schema));
    }

    @Test
    void nonfiniteAndNonJsonValuesAreRejectedEvenBelowUnconstrainedMembers() {
        for (Number number : List.of(Double.NaN, Double.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertFalse(McpJsonSchema.validates(Map.of("extra", List.of(number)), Map.of("type", "object")));
            assertFalse(McpJsonSchema.validates(List.of(number), Map.of()));
        }
        assertFalse(McpJsonSchema.validates(Map.of("extra", new Object()), Map.of()));
    }

    @Test
    void stringLengthsCountUnicodeCodePoints() {
        assertTrue(McpJsonSchema.validates("😀", Map.of("type", "string", "minLength", 1, "maxLength", 1)));
        assertFalse(McpJsonSchema.validates("😀x", Map.of("type", "string", "maxLength", 1)));
    }
}
