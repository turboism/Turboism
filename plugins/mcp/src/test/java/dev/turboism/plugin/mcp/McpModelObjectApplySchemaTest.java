package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpModelObjectApplySchemaTest {

    @Test
    void operationSchemaIsAStrictDiscriminatedUnion() throws Exception {
        final Map<String, Object> schema = operationSchema();
        final List<Object> alternatives = list(schema.get("oneOf"));

        assertTrue(alternatives.size() >= 7);
        for (Object value : alternatives) {
            final Map<String, Object> alternative = object(value);
            assertTrue(Boolean.FALSE.equals(alternative.get("additionalProperties")));
            assertTrue(list(alternative.get("required")).contains("operation"));
            final Map<String, Object> operation = object(
                object(alternative.get("properties")).get("operation")
            );
            assertTrue(list(operation.get("enum")).size() == 1);
        }
    }

    @Test
    void schemaAcceptsEveryMinimalRuntimeOperation() throws Exception {
        final Map<String, Object> schema = operationSchema();
        for (Map<String, Object> input : List.<Map<String, Object>>of(
            Map.of("operation", "create", "kind", "part", "name", "Part"),
            Map.of("operation", "create", "kind", "art_mesh", "name", "Mesh"),
            Map.of(
                "operation", "create", "kind", "art_mesh", "name", "Mesh",
                "positions", List.of(point(0, 0), point(1, 0), point(0, 1)),
                "uvs", List.of(point(0, 0), point(1, 0), point(0, 1)),
                "triangleIndices", List.of(0, 1, 2)
            ),
            Map.of("operation", "create", "kind", "warp_deformer", "name", "Warp"),
            Map.of("operation", "create", "kind", "rotation_deformer", "name", "Rotation"),
            Map.of("operation", "rename", "kind", "part", "id", "Part1", "name", "Renamed"),
            Map.of(
                "operation", "reparent", "kind", "art_mesh", "id", "Mesh1",
                "parent", Map.of("kind", "part", "id", "Part1")
            ),
            Map.of("operation", "delete", "kind", "part", "id", "Part1")
        )) {
            assertTrue(accepts(schema, input), () -> "schema rejected " + input);
        }
    }

    @Test
    void schemaRejectsRuntimeForbiddenOrIncompleteCombinations() throws Exception {
        final Map<String, Object> schema = operationSchema();
        for (Map<String, Object> input : List.<Map<String, Object>>of(
            Map.of("operation", "create", "kind", "part", "name", "Part", "id", "client-id"),
            Map.of("operation", "create", "kind", "part", "name", "Part", "rows", 2),
            Map.of("operation", "create", "kind", "art_mesh", "name", "Mesh", "positions", List.of()),
            Map.of(
                "operation", "create", "kind", "art_mesh", "name", "Mesh",
                "positions", List.of(), "uvs", List.of(), "triangleIndices", List.of()
            ),
            Map.of(
                "operation", "create", "kind", "warp_deformer", "name", "Warp",
                "controlPoints", List.of()
            ),
            Map.of("operation", "rename", "kind", "part", "name", "Renamed"),
            Map.of("operation", "reparent", "kind", "part", "id", "Part1"),
            Map.of("operation", "delete", "kind", "part", "id", "Part1", "name", "forbidden"),
            Map.of("operation", "unknown")
        )) {
            assertFalse(accepts(schema, input), () -> "schema accepted " + input);
        }
    }

    private static Map<String, Object> operationSchema() throws Exception {
        final Method method = McpProductionDomainCatalog.class.getDeclaredMethod("operationSchema");
        method.setAccessible(true);
        return object(method.invoke(null));
    }

    private static Map<String, Object> point(final Number x, final Number y) {
        return Map.of("x", x, "y", y);
    }

    private static boolean accepts(final Map<String, Object> schema, final Object value) {
        if (schema.get("oneOf") instanceof List<?> alternatives) {
            int matches = 0;
            for (Object alternative : alternatives) {
                if (accepts(object(alternative), value)) matches++;
            }
            return matches == 1;
        }
        if (schema.get("enum") instanceof List<?> values && !values.contains(value)) return false;
        final Object type = schema.get("type");
        if ("object".equals(type)) {
            if (!(value instanceof Map<?, ?> raw)) return false;
            final Map<String, Object> objectValue = stringMap(raw);
            final Map<String, Object> properties = object(schema.get("properties"));
            if (schema.get("required") instanceof List<?> required
                && !objectValue.keySet().containsAll(required)) return false;
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))
                && !properties.keySet().containsAll(objectValue.keySet())) return false;
            for (Map.Entry<String, Object> entry : objectValue.entrySet()) {
                final Object propertySchema = properties.get(entry.getKey());
                if (propertySchema != null && !accepts(object(propertySchema), entry.getValue())) {
                    return false;
                }
            }
            return true;
        }
        if ("array".equals(type)) {
            if (!(value instanceof List<?> values)) return false;
            if (schema.get("minItems") instanceof Number minimum
                && values.size() < minimum.intValue()) return false;
            final Map<String, Object> items = object(schema.get("items"));
            return values.stream().allMatch(item -> accepts(items, item));
        }
        if ("string".equals(type)) {
            if (!(value instanceof String text)) return false;
            if (schema.get("minLength") instanceof Number minimum
                && text.length() < minimum.intValue()) return false;
            if (schema.get("maxLength") instanceof Number maximum
                && text.length() > maximum.intValue()) return false;
            return true;
        }
        if ("integer".equals(type)) {
            if (!(value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long)) return false;
            return inRange(schema, ((Number) value).doubleValue());
        }
        if ("number".equals(type)) {
            return value instanceof Number number && inRange(schema, number.doubleValue());
        }
        if ("boolean".equals(type)) return value instanceof Boolean;
        return true;
    }

    private static boolean inRange(final Map<String, Object> schema, final double value) {
        if (schema.get("minimum") instanceof Number minimum
            && value < minimum.doubleValue()) return false;
        return !(schema.get("maximum") instanceof Number maximum)
            || value <= maximum.doubleValue();
    }

    private static Map<String, Object> stringMap(final Map<?, ?> raw) {
        final java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            result.put((String) entry.getKey(), entry.getValue());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(final Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(final Object value) {
        return (List<Object>) value;
    }
}
