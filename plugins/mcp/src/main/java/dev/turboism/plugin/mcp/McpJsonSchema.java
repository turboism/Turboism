package dev.turboism.plugin.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Small fail-closed validator for the JSON-Schema subset used by MCP tool contracts. */
final class McpJsonSchema {

    private static final Set<String> JSON_TYPES = Set.of(
        "null", "boolean", "integer", "number", "string", "array", "object"
    );

    private McpJsonSchema() {
    }

    static boolean validates(final Object value, final Map<String, Object> schema) {
        Objects.requireNonNull(schema, "schema");
        if (schema.isEmpty()) return true;
        return validates(value, schema, 0);
    }

    static boolean compatible(
        final List<Map<String, Object>> sources,
        final List<Map<String, Object>> destinations
    ) {
        if (sources.isEmpty() || destinations.isEmpty()) return false;
        for (Map<String, Object> source : sources) {
            boolean admitted = false;
            for (Map<String, Object> destination : destinations) {
                if (compatible(source, destination)) {
                    admitted = true;
                    break;
                }
            }
            if (!admitted) return false;
        }
        return true;
    }

    static List<Map<String, Object>> schemasAtPath(
        final Map<String, Object> schema,
        final List<String> path
    ) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(path, "path");
        if (schema.isEmpty()) return List.of();
        List<Map<String, Object>> current = alternatives(schema);
        for (String token : path) {
            final ArrayList<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> candidate : current) {
                for (Map<String, Object> alternative : alternatives(candidate)) {
                    final Map<String, Object> properties = objectMap(alternative.get("properties"));
                    if (properties != null && properties.get(token) instanceof Map<?, ?> child) {
                        next.add(stringMap(child));
                    }
                    if (isArraySchema(alternative)
                        && token.matches("0|[1-9][0-9]*")
                        && alternative.get("items") instanceof Map<?, ?> item) {
                        next.add(stringMap(item));
                    }
                    if (properties != null
                        && !properties.containsKey(token)
                        && alternative.get("additionalProperties") instanceof Map<?, ?> extra) {
                        next.add(stringMap(extra));
                    }
                }
            }
            if (next.isEmpty()) return List.of();
            current = deduplicate(next);
        }
        final ArrayList<Map<String, Object>> expanded = new ArrayList<>();
        for (Map<String, Object> candidate : current) expanded.addAll(alternatives(candidate));
        return deduplicate(expanded);
    }

    private static boolean validates(
        final Object value,
        final Map<String, Object> schema,
        final int depth
    ) {
        if (depth > 96) return false;
        final Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List<?> alternatives) {
            for (Object alternative : alternatives) {
                if (alternative instanceof Map<?, ?> candidate
                    && validates(value, stringMap(candidate), depth + 1)) {
                    return true;
                }
            }
            return false;
        }

        if (schema.containsKey("const") && !jsonEquals(value, schema.get("const"))) {
            return false;
        }
        if (schema.get("enum") instanceof List<?> values
            && values.stream().noneMatch(candidate -> jsonEquals(value, candidate))) {
            return false;
        }

        final Set<String> declaredTypes = declaredTypes(schema);
        if (!declaredTypes.isEmpty() && !declaredTypes.contains(jsonType(value))) {
            if (!("integer".equals(jsonType(value)) && declaredTypes.contains("number"))) {
                return false;
            }
        }
        if (value == null) return declaredTypes.isEmpty() || declaredTypes.contains("null");

        if (value instanceof String text) {
            if (!integerConstraint(text.length(), schema.get("minLength"), true)) return false;
            if (!integerConstraint(text.length(), schema.get("maxLength"), false)) return false;
            if (schema.get("pattern") instanceof String expression) {
                try {
                    if (!Pattern.compile(expression).matcher(text).find()) return false;
                } catch (PatternSyntaxException invalidSchema) {
                    return false;
                }
            }
        }
        if (value instanceof Number number) {
            final BigDecimal decimal = decimal(number);
            if (decimal == null) return false;
            if (schema.get("minimum") instanceof Number minimum
                && decimal.compareTo(Objects.requireNonNull(decimal(minimum))) < 0) {
                return false;
            }
            if (schema.get("maximum") instanceof Number maximum
                && decimal.compareTo(Objects.requireNonNull(decimal(maximum))) > 0) {
                return false;
            }
        }
        if (value instanceof List<?> values) {
            if (!integerConstraint(values.size(), schema.get("minItems"), true)) return false;
            if (!integerConstraint(values.size(), schema.get("maxItems"), false)) return false;
            if (schema.get("items") instanceof Map<?, ?> itemSchema) {
                final Map<String, Object> item = stringMap(itemSchema);
                for (Object member : values) {
                    if (!validates(member, item, depth + 1)) return false;
                }
            }
        }
        if (value instanceof Map<?, ?> values) {
            final Map<String, Object> object;
            try {
                object = stringMap(values);
            } catch (IllegalArgumentException invalidObject) {
                return false;
            }
            final Map<String, Object> properties = objectMap(schema.get("properties"));
            if (schema.get("required") instanceof List<?> required) {
                for (Object field : required) {
                    if (!(field instanceof String name) || !object.containsKey(name)) return false;
                }
            }
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                final Object propertySchema = properties == null ? null : properties.get(entry.getKey());
                if (propertySchema instanceof Map<?, ?> property) {
                    if (!validates(entry.getValue(), stringMap(property), depth + 1)) return false;
                    continue;
                }
                if (Boolean.FALSE.equals(schema.get("additionalProperties"))) return false;
                if (schema.get("additionalProperties") instanceof Map<?, ?> additional
                    && !validates(entry.getValue(), stringMap(additional), depth + 1)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean compatible(
        final Map<String, Object> source,
        final Map<String, Object> destination
    ) {
        if (destination.isEmpty()) return true;
        if (source.isEmpty()) return false;

        final Object sourceConst = source.get("const");
        if (sourceConst != null || source.containsKey("const")) {
            return validates(sourceConst, destination);
        }
        if (source.get("enum") instanceof List<?> values) {
            return !values.isEmpty() && values.stream().allMatch(value -> validates(value, destination));
        }

        final Set<String> sourceTypes = normalizedTypes(source);
        final Set<String> destinationTypes = normalizedTypes(destination);
        if (destinationTypes.isEmpty()) return true;
        if (sourceTypes.isEmpty()) return false;
        for (String sourceType : sourceTypes) {
            if (destinationTypes.contains(sourceType)) continue;
            if ("integer".equals(sourceType) && destinationTypes.contains("number")) continue;
            return false;
        }
        return true;
    }

    private static Set<String> normalizedTypes(final Map<String, Object> schema) {
        final Object oneOf = schema.get("oneOf");
        if (oneOf instanceof List<?> alternatives) {
            final LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object alternative : alternatives) {
                if (alternative instanceof Map<?, ?> candidate) {
                    result.addAll(normalizedTypes(stringMap(candidate)));
                }
            }
            return Set.copyOf(result);
        }
        return declaredTypes(schema);
    }

    private static Set<String> declaredTypes(final Map<String, Object> schema) {
        final LinkedHashSet<String> result = new LinkedHashSet<>();
        final Object type = schema.get("type");
        if (type instanceof String value && JSON_TYPES.contains(value)) result.add(value);
        if (type instanceof List<?> values) {
            for (Object value : values) {
                if (value instanceof String text && JSON_TYPES.contains(text)) result.add(text);
            }
        }
        if (result.isEmpty()) {
            if (schema.containsKey("properties") || schema.containsKey("required")) result.add("object");
            else if (schema.containsKey("items")) result.add("array");
            else if (schema.containsKey("pattern") || schema.containsKey("minLength")
                || schema.containsKey("maxLength")) result.add("string");
            else if (schema.containsKey("minimum") || schema.containsKey("maximum")) {
                result.add("number");
            } else if (schema.containsKey("const")) {
                result.add(jsonType(schema.get("const")));
            } else if (schema.get("enum") instanceof List<?> values) {
                for (Object value : values) result.add(jsonType(value));
            }
        }
        return Set.copyOf(result);
    }

    private static List<Map<String, Object>> alternatives(final Map<String, Object> schema) {
        if (schema.get("oneOf") instanceof List<?> values) {
            final ArrayList<Map<String, Object>> result = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> alternative) result.add(stringMap(alternative));
            }
            return result.isEmpty() ? List.of(schema) : List.copyOf(result);
        }
        return List.of(schema);
    }

    private static List<Map<String, Object>> deduplicate(
        final List<Map<String, Object>> schemas
    ) {
        return List.copyOf(new LinkedHashSet<>(schemas));
    }

    private static boolean isArraySchema(final Map<String, Object> schema) {
        return declaredTypes(schema).contains("array") || schema.containsKey("items");
    }

    private static boolean integerConstraint(
        final int actual,
        final Object raw,
        final boolean minimum
    ) {
        if (raw == null) return true;
        if (!(raw instanceof Number number)) return false;
        final BigDecimal expected = decimal(number);
        if (expected == null || expected.stripTrailingZeros().scale() > 0) return false;
        final int compared = BigDecimal.valueOf(actual).compareTo(expected);
        return minimum ? compared >= 0 : compared <= 0;
    }

    private static String jsonType(final Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof String) return "string";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof Number number) return isInteger(number) ? "integer" : "number";
        return "unknown";
    }

    private static boolean isInteger(final Number number) {
        if (number instanceof Byte || number instanceof Short || number instanceof Integer
            || number instanceof Long || number instanceof BigInteger) return true;
        final BigDecimal value = decimal(number);
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal decimal(final Number number) {
        if (number instanceof BigDecimal value) return value;
        if (number instanceof BigInteger value) return new BigDecimal(value);
        if (number instanceof Byte || number instanceof Short || number instanceof Integer
            || number instanceof Long) return BigDecimal.valueOf(number.longValue());
        final double value = number.doubleValue();
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private static boolean jsonEquals(final Object left, final Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            final BigDecimal leftDecimal = decimal(leftNumber);
            final BigDecimal rightDecimal = decimal(rightNumber);
            return leftDecimal != null && rightDecimal != null
                && leftDecimal.compareTo(rightDecimal) == 0;
        }
        return Objects.equals(left, right);
    }

    private static Map<String, Object> objectMap(final Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return stringMap(map);
    }

    private static Map<String, Object> stringMap(final Map<?, ?> source) {
        final java.util.LinkedHashMap<String, Object> result = new java.util.LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}
