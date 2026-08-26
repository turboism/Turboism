package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Discoverable MCP tool catalog and invocation boundary. */
final class McpToolCatalog {

    private static final Map<String, Object> DEFAULT_OUTPUT_SCHEMA = Map.of(
        "$schema", "https://json-schema.org/draft/2020-12/schema",
        "type", "object",
        "properties", Map.of(
            "ok", Map.of("type", "boolean"),
            "error", Map.of(
                "type", "object",
                "properties", Map.of(
                    "code", Map.of("type", "string"),
                    "message", Map.of("type", "string")
                ),
                "required", List.of("code", "message"),
                "additionalProperties", true
            )
        ),
        "required", List.of("ok"),
        "additionalProperties", true
    );

    @FunctionalInterface
    interface Caller {
        Map<String, Object> call(String name, Map<String, Object> arguments);
    }

    private final List<Map<String, Object>> definitions;
    private final Set<String> names;
    private final Map<String, Map<String, Object>> outputSchemas;
    private final Caller caller;

    McpToolCatalog(final List<Map<String, Object>> definitions, final Caller caller) {
        Objects.requireNonNull(definitions, "definitions");
        final ArrayList<Map<String, Object>> normalized = new ArrayList<>(definitions.size());
        final LinkedHashSet<String> discoveredNames = new LinkedHashSet<>();
        final LinkedHashMap<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (Map<String, Object> definition : definitions) {
            final LinkedHashMap<String, Object> checked = new LinkedHashMap<>(
                Objects.requireNonNull(definition, "definition")
            );
            final Object nameValue = checked.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("MCP tool definition requires a name");
            }
            if (!discoveredNames.add(name)) {
                throw new IllegalArgumentException("Duplicate MCP tool: " + name);
            }
            checked.putIfAbsent("outputSchema", DEFAULT_OUTPUT_SCHEMA);
            final Object schemaValue = checked.get("outputSchema");
            if (!(schemaValue instanceof Map<?, ?> rawSchema)) {
                throw new IllegalArgumentException("MCP tool outputSchema must be an object: " + name);
            }
            final LinkedHashMap<String, Object> schema = stringMap(rawSchema, "outputSchema");
            checked.put("outputSchema", Map.copyOf(schema));
            schemas.put(name, Map.copyOf(schema));
            normalized.add(Map.copyOf(checked));
        }
        this.definitions = List.copyOf(normalized);
        this.names = Set.copyOf(discoveredNames);
        this.outputSchemas = Map.copyOf(schemas);
        this.caller = Objects.requireNonNull(caller, "caller");
    }

    static McpToolCatalog empty() {
        return new McpToolCatalog(List.of(), (name, arguments) -> {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        });
    }

    List<Map<String, Object>> definitions() {
        return definitions;
    }

    static McpToolCatalog combine(final McpToolCatalog... catalogs) {
        Objects.requireNonNull(catalogs, "catalogs");
        final ArrayList<Map<String, Object>> definitions = new ArrayList<>();
        final LinkedHashMap<String, McpToolCatalog> owners = new LinkedHashMap<>();
        for (McpToolCatalog catalog : catalogs) {
            final McpToolCatalog checked = Objects.requireNonNull(catalog, "catalog");
            definitions.addAll(checked.definitions());
            for (String name : checked.names) {
                if (owners.putIfAbsent(name, checked) != null) {
                    throw new IllegalArgumentException("Duplicate MCP tool: " + name);
                }
            }
        }
        return new McpToolCatalog(
            definitions,
            (name, arguments) -> owners.get(name).call(name, arguments)
        );
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        if (!names.contains(name)) {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        }
        final Map<String, Object> envelope = Objects.requireNonNull(
            caller.call(name, arguments), "MCP tool envelope"
        );
        final String violation = validateEnvelope(envelope, outputSchemas.get(name));
        return violation == null ? envelope : invalidOutput(violation);
    }

    private static String validateEnvelope(
        final Map<String, Object> envelope,
        final Map<String, Object> outputSchema
    ) {
        final Object structured = envelope.get("structuredContent");
        if (!(structured instanceof Map<?, ?> rawStructured)) {
            return "structuredContent must be an object";
        }
        final Map<String, Object> output;
        try {
            output = stringMap(rawStructured, "structuredContent");
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
        final Object contentValue = envelope.get("content");
        if (!(contentValue instanceof List<?> content) || content.size() != 1
            || !(content.get(0) instanceof Map<?, ?> rawBlock)) {
            return "content must contain one JSON text block";
        }
        final Map<String, Object> block;
        try {
            block = stringMap(rawBlock, "content[0]");
        } catch (IllegalArgumentException failure) {
            return failure.getMessage();
        }
        if (!"text".equals(block.get("type")) || !(block.get("text") instanceof String text)) {
            return "content[0] must be a text block";
        }
        try {
            final Object parsed = Json.parse(
                text.getBytes(java.nio.charset.StandardCharsets.UTF_8)
            );
            if (!Json.stringify(output).equals(Json.stringify(parsed))) {
                return "content text must equal structuredContent";
            }
        } catch (IllegalArgumentException failure) {
            return "content text must contain valid JSON";
        }
        return JsonSchemaValidator.validate(outputSchema, output);
    }

    private static Map<String, Object> invalidOutput(final String detail) {
        final Map<String, Object> output = Map.of(
            "ok", false,
            "error", Map.of(
                "code", "INTERNAL_OUTPUT_INVALID",
                "message", "MCP tool produced output outside its declared contract",
                "details", detail
            )
        );
        return Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", Json.stringify(output)
            )),
            "structuredContent", output,
            "isError", true
        );
    }

    private static LinkedHashMap<String, Object> stringMap(
        final Map<?, ?> raw,
        final String label
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static final class JsonSchemaValidator {
        private JsonSchemaValidator() {
        }

        static String validate(final Map<String, Object> schema, final Object value) {
            return validate(schema, value, "$", 0);
        }

        private static String validate(
            final Map<String, Object> schema,
            final Object value,
            final String path,
            final int depth
        ) {
            if (depth > 64) return path + " schema nesting exceeds 64 levels";
            final Object oneOfValue = schema.get("oneOf");
            if (oneOfValue instanceof List<?> alternatives) {
                int matches = 0;
                for (Object alternative : alternatives) {
                    if (alternative instanceof Map<?, ?> raw
                        && validate(stringMap(raw, "oneOf"), value, path, depth + 1) == null) {
                        matches++;
                    }
                }
                return matches == 1 ? null : path + " must match exactly one schema";
            }
            final Object constValue = schema.get("const");
            if (schema.containsKey("const") && !Objects.equals(constValue, value)) {
                return path + " must equal its declared constant";
            }
            final Object enumValue = schema.get("enum");
            if (enumValue instanceof List<?> values && !values.contains(value)) {
                return path + " is outside its declared enum";
            }
            final Object typeValue = schema.get("type");
            if (typeValue instanceof List<?> types) {
                for (Object type : types) {
                    final LinkedHashMap<String, Object> candidate = new LinkedHashMap<>(schema);
                    candidate.put("type", type);
                    if (validate(candidate, value, path, depth + 1) == null) return null;
                }
                return path + " has the wrong type";
            }
            if (typeValue instanceof String type && !typeMatches(type, value)) {
                return path + " must be " + type;
            }
            if (value instanceof Map<?, ?> rawObject
                && ("object".equals(typeValue) || schema.containsKey("properties"))) {
                final Map<String, Object> object;
                try {
                    object = stringMap(rawObject, path);
                } catch (IllegalArgumentException failure) {
                    return failure.getMessage();
                }
                if (schema.get("required") instanceof List<?> required) {
                    for (Object key : required) {
                        if (key instanceof String name && !object.containsKey(name)) {
                            return path + " is missing " + name;
                        }
                    }
                }
                final Map<String, Object> properties = schema.get("properties") instanceof Map<?, ?> raw
                    ? stringMap(raw, "properties") : Map.of();
                if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                    for (String key : object.keySet()) {
                        if (!properties.containsKey(key)) return path + " contains unknown field " + key;
                    }
                }
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    if (!object.containsKey(property.getKey())
                        || !(property.getValue() instanceof Map<?, ?> rawProperty)) continue;
                    final String violation = validate(
                        stringMap(rawProperty, "property"),
                        object.get(property.getKey()),
                        path + "." + property.getKey(),
                        depth + 1
                    );
                    if (violation != null) return violation;
                }
            }
            if (value instanceof List<?> values
                && ("array".equals(typeValue) || schema.containsKey("items"))) {
                if (schema.get("minItems") instanceof Number minimum
                    && values.size() < minimum.intValue()) {
                    return path + " has too few items";
                }
                if (schema.get("maxItems") instanceof Number maximum
                    && values.size() > maximum.intValue()) {
                    return path + " has too many items";
                }
                if (schema.get("items") instanceof Map<?, ?> rawItems) {
                    final Map<String, Object> itemSchema = stringMap(rawItems, "items");
                    for (int index = 0; index < values.size(); index++) {
                        final String violation = validate(
                            itemSchema, values.get(index), path + "[" + index + "]", depth + 1
                        );
                        if (violation != null) return violation;
                    }
                }
            }
            if (value instanceof String text) {
                if (schema.get("minLength") instanceof Number minimum
                    && text.length() < minimum.intValue()) return path + " is too short";
                if (schema.get("maxLength") instanceof Number maximum
                    && text.length() > maximum.intValue()) return path + " is too long";
            }
            if (value instanceof Number number) {
                final java.math.BigDecimal decimal = new java.math.BigDecimal(number.toString());
                if (schema.get("minimum") instanceof Number minimum
                    && decimal.compareTo(new java.math.BigDecimal(minimum.toString())) < 0) {
                    return path + " is below the minimum";
                }
                if (schema.get("maximum") instanceof Number maximum
                    && decimal.compareTo(new java.math.BigDecimal(maximum.toString())) > 0) {
                    return path + " is above the maximum";
                }
            }
            return null;
        }

        private static boolean typeMatches(final String type, final Object value) {
            return switch (type) {
                case "null" -> value == null;
                case "object" -> value instanceof Map<?, ?>;
                case "array" -> value instanceof List<?>;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number;
                case "integer" -> value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long
                    || value instanceof java.math.BigInteger
                    || value instanceof java.math.BigDecimal decimal
                        && decimal.stripTrailingZeros().scale() <= 0;
                default -> true;
            };
        }
    }
}
