package dev.turboism.plugin.mcp;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Small fail-closed validator for the JSON-Schema subset used by MCP tool contracts. */
final class McpJsonSchema {

    private static final Set<String> JSON_TYPES =
            Set.of("null", "boolean", "integer", "number", "string", "array", "object");

    /**
     * Maximum recursion budget shared by the value walk and the schema walk. A recursive
     * {@code $ref} costs one value level plus one schema level per history group, so the budget must
     * stay well above the native group nesting depth.
     */
    private static final int MAX_DEPTH = 192;

    /**
     * Bound on cached schema patterns. Tool contracts declare a fixed, small pattern set, so the
     * bound only protects against unbounded schemas supplied by an external caller.
     */
    private static final int MAX_CACHED_PATTERNS = 256;

    /** Largest magnitude an {@code int} or {@code long} keeps when converted to {@code double}. */
    private static final long MAX_EXACT_DOUBLE = 1L << 53;

    /** JSON-Pointer prefix that addresses a definition in the enclosing schema document root. */
    private static final String DEFINITIONS_POINTER = "#/$defs/";

    /** Schema keywords that carry no constraint; they never require a definition lookup. */
    private static final Set<String> ANNOTATION_KEYWORDS =
            Set.of("$ref", "$defs", "$schema", "$id", "title", "description", "default", "examples");

    private static final ConcurrentHashMap<String, Pattern> PATTERNS = new ConcurrentHashMap<>();

    private McpJsonSchema() {}

    static boolean validates(final Object value, final Map<String, Object> schema) {
        Objects.requireNonNull(schema, "schema");
        final Object definitions = schema.get("$defs");
        return validates(value, schema, definitions instanceof Map<?, ?> raw ? asSchema(raw) : null, 0);
    }

    static boolean compatible(final List<Map<String, Object>> sources, final List<Map<String, Object>> destinations) {
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

    static List<Map<String, Object>> schemasAtPath(final Map<String, Object> schema, final List<String> path) {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(path, "path");
        if (schema.isEmpty()) return List.of();
        final Map<String, Object> definitions = definitionsOf(schema);
        List<Map<String, Object>> current = alternatives(schema, definitions);
        for (String token : path) {
            final ArrayList<Map<String, Object>> next = new ArrayList<>();
            for (Map<String, Object> candidate : current) {
                for (Map<String, Object> alternative : alternatives(candidate, definitions)) {
                    final Map<String, Object> properties = objectMap(alternative.get("properties"));
                    if (properties != null && properties.get(token) instanceof Map<?, ?> child) {
                        next.add(asSchema(child));
                    }
                    if (isArraySchema(alternative)
                            && token.matches("0|[1-9][0-9]*")
                            && alternative.get("items") instanceof Map<?, ?> item) {
                        next.add(asSchema(item));
                    }
                    if (properties != null
                            && !properties.containsKey(token)
                            && alternative.get("additionalProperties") instanceof Map<?, ?> extra) {
                        next.add(asSchema(extra));
                    }
                }
            }
            if (next.isEmpty()) return List.of();
            current = deduplicate(next);
        }
        final ArrayList<Map<String, Object>> expanded = new ArrayList<>();
        for (Map<String, Object> candidate : current) expanded.addAll(alternatives(candidate, definitions));
        return deduplicate(expanded);
    }

    /** Validates against a schema that declares no shared definitions. */
    private static boolean validates(final Object value, final Map<String, Object> schema, final int depth) {
        return validates(value, schema, null, depth);
    }

    /**
     * Validates {@code value} against {@code schema} in one pass. Every visited member is also
     * checked for JSON representability, because a value the encoder cannot write must never reach
     * the wire even when the schema leaves that member unconstrained.
     *
     * @param definitions the enclosing document definitions, or {@code null} when none apply
     */
    private static boolean validates(
            final Object value,
            final Map<String, Object> schema,
            final Map<String, Object> definitions,
            final int depth) {
        if (depth > MAX_DEPTH || !jsonRepresentable(value)) return false;
        if (schema.isEmpty()) return isJsonValue(value, depth);
        final Map<String, Object> resolved = resolve(schema, definitions);
        if (resolved == null) return false;

        final Object oneOf = resolved.get("oneOf");
        if (oneOf instanceof List<?> alternatives) {
            int matches = 0;
            for (Object alternative : alternatives) {
                if (alternative instanceof Map<?, ?> candidate
                        && validates(value, asSchema(candidate), definitions, depth + 1)) {
                    matches++;
                }
            }
            if (matches != 1) return false;
            // oneOf does not override sibling constraints on the same schema object.
        }

        if (resolved.containsKey("const") && !jsonEquals(value, resolved.get("const"))) {
            return false;
        }
        if (resolved.get("enum") instanceof List<?> values
                && values.stream().noneMatch(candidate -> jsonEquals(value, candidate))) {
            return false;
        }

        if (!admittedType(value, resolved)) return false;
        if (value == null) return true;

        if (value instanceof String text) {
            final int length = text.codePointCount(0, text.length());
            if (!integerConstraint(length, resolved.get("minLength"), true)) return false;
            if (!integerConstraint(length, resolved.get("maxLength"), false)) return false;
            if (resolved.get("pattern") instanceof String expression) {
                final Pattern pattern = compiled(expression);
                if (pattern == null || !pattern.matcher(text).find()) return false;
            }
        }
        if (value instanceof Number number) {
            if (!withinBounds(number, resolved)) return false;
        }
        if (value instanceof List<?> values) {
            if (!integerConstraint(values.size(), resolved.get("minItems"), true)) return false;
            if (!integerConstraint(values.size(), resolved.get("maxItems"), false)) return false;
            if (resolved.get("items") instanceof Map<?, ?> itemSchema) {
                final Map<String, Object> item = asSchema(itemSchema);
                for (Object member : values) {
                    if (!validates(member, item, definitions, depth + 1)) return false;
                }
            } else {
                for (Object member : values) {
                    if (!isJsonValue(member, depth + 1)) return false;
                }
            }
        }
        if (value instanceof Map<?, ?> values) {
            final Map<String, Object> object;
            try {
                object = asSchema(values);
            } catch (IllegalArgumentException invalidObject) {
                return false;
            }
            final Map<String, Object> properties = objectMap(resolved.get("properties"));
            if (resolved.get("required") instanceof List<?> required) {
                for (Object field : required) {
                    if (!(field instanceof String name) || !object.containsKey(name)) return false;
                }
            }
            final Object additional = resolved.get("additionalProperties");
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                final Object declared = properties == null ? null : properties.get(entry.getKey());
                if (declared instanceof Map<?, ?> property) {
                    if (!validates(entry.getValue(), asSchema(property), definitions, depth + 1)) return false;
                    continue;
                }
                if (Boolean.FALSE.equals(additional)) return false;
                if (additional instanceof Map<?, ?> extra) {
                    if (!validates(entry.getValue(), asSchema(extra), definitions, depth + 1)) return false;
                    continue;
                }
                if (!isJsonValue(entry.getValue(), depth + 1)) return false;
            }
        }
        return true;
    }

    /**
     * Returns the schema that constrains the value. A {@code $ref} resolves against the enclosing
     * document definitions; sibling keywords stay in force. Returns {@code null} for an
     * unresolvable reference.
     */
    private static Map<String, Object> resolve(
            final Map<String, Object> schema, final Map<String, Object> definitions) {
        final Object reference = schema.get("$ref");
        if (!(reference instanceof String pointer)) return schema;
        final Map<String, Object> target = definition(pointer, definitions);
        if (target == null) return null;
        boolean constrained = false;
        for (String key : schema.keySet()) {
            if (!ANNOTATION_KEYWORDS.contains(key)) {
                constrained = true;
                break;
            }
        }
        if (!constrained) return target;
        final java.util.LinkedHashMap<String, Object> merged = new java.util.LinkedHashMap<>(target);
        for (Map.Entry<String, Object> entry : schema.entrySet()) {
            if (!"$ref".equals(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    /** Returns the definition a local JSON pointer names, or {@code null} when it does not exist. */
    private static Map<String, Object> definition(final String pointer, final Map<String, Object> definitions) {
        if (definitions == null || !pointer.startsWith(DEFINITIONS_POINTER)) return null;
        final Object candidate = definitions.get(pointer.substring(DEFINITIONS_POINTER.length()));
        return candidate instanceof Map<?, ?> map ? asSchema(map) : null;
    }

    /**
     * Returns {@code true} when {@code value} can be written as JSON and satisfies the declared
     * type set of {@code schema}. Declared types stay authoritative: an inferred type set is only
     * used when the schema declares none.
     */
    private static boolean admittedType(final Object value, final Map<String, Object> schema) {
        final Object type = schema.get("type");
        if (type instanceof String declared && JSON_TYPES.contains(declared)) {
            if (matchesType(declared, value)) return true;
            if (value == null) return false;
            return "number".equals(declared) && "integer".equals(jsonType(value));
        }
        final Set<String> declaredTypes = declaredTypes(schema);
        if (declaredTypes.isEmpty()) return true;
        if (declaredTypes.contains(jsonType(value))) return true;
        return value != null && declaredTypes.contains("number") && "integer".equals(jsonType(value));
    }

    private static boolean matchesType(final String declared, final Object value) {
        return switch (declared) {
            case "null" -> value == null;
            case "boolean" -> value instanceof Boolean;
            case "integer" -> value instanceof Number number && isInteger(number);
            case "number" -> value instanceof Number;
            case "string" -> value instanceof String;
            case "array" -> value instanceof List<?>;
            case "object" -> value instanceof Map<?, ?>;
            default -> false;
        };
    }

    /**
     * Returns {@code true} when every declared numeric bound admits {@code number}.
     *
     * <p>Non-finite floating-point values are rejected here: strict JSON cannot encode them.</p>
     */
    private static boolean withinBounds(final Number number, final Map<String, Object> schema) {
        if (isFloatingPrimitive(number) && !Double.isFinite(number.doubleValue())) return false;
        if (!inclusive(number, schema.get("minimum"), true)) return false;
        if (!inclusive(number, schema.get("maximum"), false)) return false;
        if (!exclusive(number, schema.get("exclusiveMinimum"), true)) return false;
        return exclusive(number, schema.get("exclusiveMaximum"), false);
    }

    /** Compares against an inclusive bound; {@code minimum} selects the rejected side. */
    private static boolean inclusive(final Number number, final Object raw, final boolean minimum) {
        if (!(raw instanceof Number bound)) return true;
        final int comparison = compare(number, bound);
        return minimum ? comparison >= 0 : comparison <= 0;
    }

    /** Compares against an exclusive bound; the bound value itself fails. */
    private static boolean exclusive(final Number number, final Object raw, final boolean minimum) {
        if (!(raw instanceof Number bound)) return true;
        final int comparison = compare(number, bound);
        return minimum ? comparison > 0 : comparison < 0;
    }

    /**
     * Compares two JSON numbers exactly. Integral and exactly representable floating-point pairs use
     * primitive comparison; other pairs fall back to {@link BigDecimal}.
     *
     * @throws IllegalArgumentException when either operand is not a JSON number
     */
    private static int compare(final Number value, final Number bound) {
        final Integer sign = signum(value, bound);
        if (sign != null) return sign;
        final BigDecimal left = decimal(value);
        final BigDecimal right = decimal(bound);
        if (left == null || right == null) throw new IllegalArgumentException("number must be finite");
        return left.compareTo(right);
    }

    /**
     * Returns the sign of {@code value} minus {@code bound} through a primitive fast path, or
     * {@code null} when only an exact decimal comparison is safe.
     */
    private static Integer signum(final Number value, final Number bound) {
        final boolean valueIntegral = isIntegralPrimitive(value);
        final boolean boundIntegral = isIntegralPrimitive(bound);
        if (valueIntegral && boundIntegral) return Long.compare(value.longValue(), bound.longValue());
        if (isFloatingPrimitive(value) && boundIntegral && Math.abs(bound.longValue()) <= MAX_EXACT_DOUBLE) {
            return Double.compare(value.doubleValue(), bound.longValue());
        }
        if (valueIntegral && isFloatingPrimitive(bound) && Math.abs(value.longValue()) <= MAX_EXACT_DOUBLE) {
            return Double.compare(value.longValue(), bound.doubleValue());
        }
        if (isFloatingPrimitive(value) && isFloatingPrimitive(bound)) {
            return Double.compare(value.doubleValue(), bound.doubleValue());
        }
        return null;
    }

    private static boolean isIntegralPrimitive(final Number number) {
        return number instanceof Byte || number instanceof Short || number instanceof Integer || number instanceof Long;
    }

    private static boolean isFloatingPrimitive(final Number number) {
        return number instanceof Float || number instanceof Double;
    }

    private static boolean compatible(final Map<String, Object> source, final Map<String, Object> destination) {
        if (destination.isEmpty()) return true;
        if (source.isEmpty()) return false;

        final Object sourceConst = source.get("const");
        if (sourceConst != null || source.containsKey("const")) {
            return validates(sourceConst, destination, null, 0);
        }
        if (source.get("enum") instanceof List<?> values) {
            return !values.isEmpty() && values.stream().allMatch(value -> validates(value, destination, null, 0));
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
                    result.addAll(normalizedTypes(asSchema(candidate)));
                }
            }
            return Set.copyOf(result);
        }
        return declaredTypes(schema);
    }

    private static Set<String> declaredTypes(final Map<String, Object> schema) {
        final Object type = schema.get("type");
        if (type instanceof String value && JSON_TYPES.contains(value)) return Set.of(value);
        if (type instanceof List<?> values) {
            final LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object value : values) {
                if (value instanceof String text && JSON_TYPES.contains(text)) result.add(text);
            }
            if (!result.isEmpty()) return Set.copyOf(result);
            return inferredTypes(schema);
        }
        return inferredTypes(schema);
    }

    /** Infers the declared type set a schema implies through its keyword shape. */
    private static Set<String> inferredTypes(final Map<String, Object> schema) {
        if (schema.containsKey("properties") || schema.containsKey("required")) return Set.of("object");
        if (schema.containsKey("items")) return Set.of("array");
        if (schema.containsKey("pattern") || schema.containsKey("minLength") || schema.containsKey("maxLength")) {
            return Set.of("string");
        }
        if (schema.containsKey("minimum") || schema.containsKey("maximum")) return Set.of("number");
        if (schema.containsKey("const")) return Set.of(jsonType(schema.get("const")));
        if (schema.get("enum") instanceof List<?> values) {
            final LinkedHashSet<String> result = new LinkedHashSet<>();
            for (Object value : values) result.add(jsonType(value));
            return Set.copyOf(result);
        }
        return Set.of();
    }

    private static List<Map<String, Object>> alternatives(
            final Map<String, Object> schema, final Map<String, Object> definitions) {
        final Map<String, Object> resolved = resolve(schema, definitions);
        if (resolved == null) return List.of();
        if (resolved.get("oneOf") instanceof List<?> values) {
            final ArrayList<Map<String, Object>> result = new ArrayList<>();
            for (Object value : values) {
                if (value instanceof Map<?, ?> alternative) result.add(asSchema(alternative));
            }
            return result.isEmpty() ? List.of(resolved) : List.copyOf(result);
        }
        return List.of(resolved);
    }

    private static List<Map<String, Object>> deduplicate(final List<Map<String, Object>> schemas) {
        return List.copyOf(new LinkedHashSet<>(schemas));
    }

    private static boolean isArraySchema(final Map<String, Object> schema) {
        return declaredTypes(schema).contains("array") || schema.containsKey("items");
    }

    private static boolean integerConstraint(final int actual, final Object raw, final boolean minimum) {
        if (raw == null) return true;
        if (!(raw instanceof Number number)) return false;
        if (isIntegralPrimitive(number)) {
            final long expected = number.longValue();
            return minimum ? actual >= expected : actual <= expected;
        }
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
        if (isIntegralPrimitive(number) || number instanceof BigInteger) return true;
        final BigDecimal value = decimal(number);
        return value != null && value.stripTrailingZeros().scale() <= 0;
    }

    private static boolean jsonRepresentable(final Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return true;
        if (value instanceof Number number)
            return number instanceof BigDecimal
                    || number instanceof BigInteger
                    || isIntegralPrimitive(number)
                    || Double.isFinite(number.doubleValue());
        return value instanceof List<?> || value instanceof Map<?, ?>;
    }

    private static boolean isJsonValue(final Object value, final int depth) {
        if (depth > MAX_DEPTH) return false;
        if (value == null || value instanceof String || value instanceof Boolean) return true;
        if (value instanceof Number number) return decimal(number) != null;
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (!isJsonValue(item, depth + 1)) return false;
            }
            return true;
        }
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String) || !isJsonValue(entry.getValue(), depth + 1)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private static BigDecimal decimal(final Number number) {
        if (number instanceof BigDecimal value) return value;
        if (number instanceof BigInteger value) return new BigDecimal(value);
        if (isIntegralPrimitive(number)) return BigDecimal.valueOf(number.longValue());
        final double value = number.doubleValue();
        return Double.isFinite(value) ? BigDecimal.valueOf(value) : null;
    }

    private static boolean jsonEquals(final Object left, final Object right) {
        if (left instanceof Number leftNumber && right instanceof Number rightNumber) {
            final BigDecimal leftDecimal = decimal(leftNumber);
            final BigDecimal rightDecimal = decimal(rightNumber);
            return leftDecimal != null && rightDecimal != null && leftDecimal.compareTo(rightDecimal) == 0;
        }
        return Objects.equals(left, right);
    }

    private static Map<String, Object> objectMap(final Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        return asSchema(map);
    }

    /**
     * Returns the definitions advertised by {@code schema}, or an empty map when the schema declares
     * none. Callers describe a document root before resolving its references.
     */
    static Map<String, Object> definitionsOf(final Map<String, Object> schema) {
        final Object value = schema.get("$defs");
        return value instanceof Map<?, ?> raw ? asSchema(raw) : Map.of();
    }

    /**
     * Returns {@code source} as a read-only string-keyed schema view without copying its members.
     * Schemas are immutable and reused across validations, so copying them per node dominates the
     * validation cost.
     *
     * @throws IllegalArgumentException when a key is not a string
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> asSchema(final Map<?, ?> source) {
        for (Map.Entry<?, ?> entry : source.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new IllegalArgumentException("JSON object key must be a string");
            }
        }
        return (Map<String, Object>) source;
    }

    /**
     * Returns the compiled pattern for {@code expression}, or {@code null} when the expression is
     * not a valid regular expression.
     */
    private static Pattern compiled(final String expression) {
        final Pattern cached = PATTERNS.get(expression);
        if (cached != null) return cached;
        final Pattern compiled;
        try {
            compiled = Pattern.compile(expression);
        } catch (PatternSyntaxException invalidSchema) {
            return null;
        }
        if (PATTERNS.size() < MAX_CACHED_PATTERNS) PATTERNS.putIfAbsent(expression, compiled);
        return compiled;
    }
}
