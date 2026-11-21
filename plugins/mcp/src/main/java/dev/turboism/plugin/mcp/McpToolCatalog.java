package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Discoverable MCP tool catalog and invocation boundary. */
final class McpToolCatalog {

    /** Leading Base64 characters that carry the eight-byte PNG signature. */
    private static final int BASE64_CHARACTERS_PER_PNG_SIGNATURE = 12;

    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private static final int MAX_CONTENT_BLOCKS = 10;

    /** JSON-Pointer prefix that addresses a definition in the enclosing schema document root. */
    private static final String DEFINITIONS_POINTER = "#/$defs/";

    private static final int MAX_IMAGE_BASE64_LENGTH = 4 * 1024 * 1024;

    private static final int MAX_IMAGE_BYTES = 3 * 1024 * 1024;

    private static final int MAX_SCHEMA_DEPTH = 64;

    private static final int MAX_BASE64_PADDING = 2;

    private static final int ASCII_TABLE_SIZE = 128;

    private static final boolean[] BASE64_CHARS = buildBase64Table();

    private static boolean[] buildBase64Table() {
        final boolean[] table = new boolean[ASCII_TABLE_SIZE];
        for (char c = 'A'; c <= 'Z'; c++) table[c] = true;
        for (char c = 'a'; c <= 'z'; c++) table[c] = true;
        for (char c = '0'; c <= '9'; c++) table[c] = true;
        table['+'] = true;
        table['/'] = true;
        return table;
    }

    /** Schema keywords that carry no constraint; they never require a definition lookup. */
    private static final Set<String> ANNOTATION_KEYWORDS =
            Set.of("$ref", "$defs", "$schema", "$id", "title", "description", "default", "examples");

    private static final Map<String, Object> DEFAULT_OUTPUT_SCHEMA = Map.of(
            "$schema",
            "https://json-schema.org/draft/2020-12/schema",
            "type",
            "object",
            "properties",
            Map.of(
                    "ok", Map.of("type", "boolean"),
                    "error",
                            Map.of(
                                    "type",
                                    "object",
                                    "properties",
                                    Map.of(
                                            "code", Map.of("type", "string"),
                                            "message", Map.of("type", "string")),
                                    "required",
                                    List.of("code", "message"),
                                    "additionalProperties",
                                    true)),
            "required",
            List.of("ok"),
            "additionalProperties",
            true);

    @FunctionalInterface
    interface Caller {
        Map<String, Object> call(String name, Map<String, Object> arguments);
    }

    /**
     * Observes one completed invocation envelope. Observers must not mutate the envelope: the same
     * instance continues on the wire.
     */
    @FunctionalInterface
    interface Observer {
        void observed(String name, Map<String, Object> envelope);
    }

    /**
     * Observes one failed invocation. Observers rethrow nothing; the original failure continues to
     * the MCP protocol boundary.
     */
    @FunctionalInterface
    interface FailureObserver {
        void observed(String name, RuntimeException failure);
    }

    private final McpToolRegistry registry;
    private final List<Map<String, Object>> definitions;
    private final Map<String, Map<String, Object>> outputSchemas;
    private final Observer observer;
    private final FailureObserver failureObserver;

    McpToolCatalog(final List<Map<String, Object>> definitions, final Caller caller) {
        this(legacyRegistry(definitions, caller), null, null);
    }

    private McpToolCatalog(final McpToolRegistry registry) {
        this(registry, null, null);
    }

    private McpToolCatalog(
            final McpToolRegistry registry, final Observer observer, final FailureObserver failureObserver) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.definitions = registry.definitions();
        final LinkedHashMap<String, Map<String, Object>> schemas = new LinkedHashMap<>();
        for (Map<String, Object> definition : definitions) {
            final String name = (String) definition.get("name");
            final Object schemaValue = definition.get("outputSchema");
            if (!(schemaValue instanceof Map<?, ?> rawSchema)) {
                throw new IllegalArgumentException("MCP tool outputSchema must be an object: " + name);
            }
            schemas.put(name, Map.copyOf(stringMap(rawSchema, "outputSchema")));
        }
        this.outputSchemas = Map.copyOf(schemas);
        this.observer = observer;
        this.failureObserver = failureObserver;
    }

    static McpToolCatalog of(final List<McpRegisteredTool> registrations) {
        return new McpToolCatalog(new McpToolRegistry(registrations));
    }

    static McpToolCatalog empty() {
        return new McpToolCatalog(List.of(), (name, arguments) -> {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        });
    }

    /**
     * Returns a catalog that reports every outcome to {@code observer} without adding another
     * validating layer. The delegate already validates arguments and envelopes; a second
     * {@code McpToolCatalog} wrapper would repeat that work on every call.
     */
    McpToolCatalog withObserver(final Observer observer, final FailureObserver failureObserver) {
        return new McpToolCatalog(
                registry,
                Objects.requireNonNull(observer, "observer"),
                Objects.requireNonNull(failureObserver, "failureObserver"));
    }

    List<Map<String, Object>> definitions() {
        return definitions;
    }

    List<McpRegisteredTool> registrations() {
        return registry.registrations();
    }

    McpRegisteredTool registration(final String name) {
        return registry.registration(name);
    }

    static McpToolCatalog combine(final McpToolCatalog... catalogs) {
        Objects.requireNonNull(catalogs, "catalogs");
        final ArrayList<McpRegisteredTool> registrations = new ArrayList<>();
        for (McpToolCatalog catalog : catalogs) {
            registrations.addAll(Objects.requireNonNull(catalog, "catalog").registrations());
        }
        return of(registrations);
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        return invoke(name, arguments, false);
    }

    Map<String, Object> callRaw(final String name, final Map<String, Object> arguments) {
        return invoke(name, arguments, true);
    }

    private Map<String, Object> invoke(final String name, final Map<String, Object> arguments, final boolean raw) {
        validateArguments(name, arguments);
        final Map<String, Object> envelope;
        try {
            envelope = raw ? registry.callRaw(name, arguments) : registry.call(name, arguments);
        } catch (RuntimeException failure) {
            if (failureObserver != null) failureObserver.observed(name, failure);
            throw failure;
        }
        final Map<String, Object> validated = validate(name, envelope);
        if (observer != null) observer.observed(name, validated);
        return validated;
    }

    private void validateArguments(final String name, final Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        final Object schema = registry.registration(name).publicDefinition().get("inputSchema");
        if (schema instanceof Map<?, ?> raw && !McpJsonSchema.validates(arguments, stringMap(raw, "inputSchema"))) {
            throw new IllegalArgumentException(
                    "Arguments for " + name + " do not match its inputSchema; no operation was submitted");
        }
    }

    private Map<String, Object> validate(final String name, final Map<String, Object> envelope) {
        final Map<String, Object> checked = Objects.requireNonNull(envelope, "MCP tool envelope");
        final String violation = validateEnvelope(checked, outputSchemas.get(name));
        return violation == null ? checked : invalidOutput(violation);
    }

    private static String validateEnvelope(final Map<String, Object> envelope, final Map<String, Object> outputSchema) {
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
        if (!(contentValue instanceof List<?> content)
                || content.isEmpty()
                || !(content.get(0) instanceof Map<?, ?> rawBlock)) {
            return "content must start with one JSON text block";
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
        final String expected = McpJsonSupport.stringify(output);
        if (!expected.equals(text) && !parsesTo(text, expected)) {
            return "content text must equal structuredContent";
        }
        if (content.size() > MAX_CONTENT_BLOCKS) return "content exceeds the attachment limit";
        int imageBytes = 0;
        for (int index = 1; index < content.size(); index++) {
            final Integer decodedLength = pngByteLength(content.get(index));
            if (decodedLength == null) return "additional content must be bounded PNG image blocks";
            imageBytes += decodedLength;
            if (imageBytes > MAX_IMAGE_BYTES) return "image content exceeds the total byte budget";
        }
        return JsonSchemaValidator.validate(outputSchema, output);
    }

    /**
     * Confirms the text block repeats the structured payload. Internal helpers already encode
     * {@code expected}, so the equality check above settles every trusted envelope in one string
     * comparison. A legacy caller that reordered keys or reformatted whitespace still matches.
     */
    private static boolean parsesTo(final String text, final String expected) {
        final Map<String, ?> parsed;
        try {
            parsed = Json.parseObject(text.getBytes(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException failure) {
            return false;
        }
        return expected.equals(Json.stringify(parsed));
    }

    /**
     * Returns the decoded PNG byte length of one image block, or {@code null} when the block is not a
     * bounded PNG attachment. The check reads only the leading Base64 characters instead of decoding
     * the whole payload.
     */
    private static Integer pngByteLength(final Object value) {
        if (!(value instanceof Map<?, ?> image)
                || !image.keySet().equals(Set.of("type", "mimeType", "data"))
                || !"image".equals(image.get("type"))
                || !"image/png".equals(image.get("mimeType"))
                || !(image.get("data") instanceof String data)
                || data.length() > MAX_IMAGE_BASE64_LENGTH) return null;
        final int padding = padding(data);
        if (padding > MAX_BASE64_PADDING) return null;
        final int visible = data.length() - padding;
        if (visible <= 0 || !allBase64Chars(data, visible)) return null;
        if (padding > 0 && data.length() % 4 != 0) return null;
        if (visible % 4 == 1) return null;
        final long decodedLength = (long) visible * 3 / 4;
        if (decodedLength > MAX_IMAGE_BYTES) return null;
        final int signatureCharacters = Math.min(BASE64_CHARACTERS_PER_PNG_SIGNATURE, data.length());
        final byte[] signature;
        try {
            signature = decodeFragment(data.substring(0, signatureCharacters));
        } catch (IllegalArgumentException failure) {
            return null;
        }
        if (signature.length < PNG_SIGNATURE.length) return null;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (signature[index] != PNG_SIGNATURE[index]) return null;
        }
        return (int) decodedLength;
    }

    /** Rejects non-Base64 characters without invoking the regex engine across megabyte payloads. */
    private static boolean allBase64Chars(final String data, final int limit) {
        final boolean[] table = BASE64_CHARS;
        for (int index = 0; index < limit; index++) {
            final char c = data.charAt(index);
            if (c >= ASCII_TABLE_SIZE || !table[c]) return false;
        }
        return true;
    }

    private static int padding(final String data) {
        int padding = 0;
        while (padding < data.length() && data.charAt(data.length() - 1 - padding) == '=') padding++;
        return padding;
    }

    /** Decodes a Base64 fragment whose character count is not a multiple of four. */
    private static byte[] decodeFragment(final String fragment) {
        final StringBuilder padded = new StringBuilder(fragment);
        while (padded.length() % 4 != 0) padded.append('=');
        return Base64.getDecoder().decode(padded.toString());
    }

    private static Map<String, Object> invalidOutput(final String detail) {
        final Map<String, Object> output = Map.of(
                "ok",
                false,
                "error",
                Map.of(
                        "code",
                        "INTERNAL_OUTPUT_INVALID",
                        "message",
                        "MCP tool produced output outside its declared contract",
                        "details",
                        detail));
        return Map.of(
                "content",
                List.of(Map.of("type", "text", "text", Json.stringify(output))),
                "structuredContent",
                output,
                "isError",
                true);
    }

    private static McpToolRegistry legacyRegistry(final List<Map<String, Object>> definitions, final Caller caller) {
        Objects.requireNonNull(definitions, "definitions");
        final Caller checkedCaller = Objects.requireNonNull(caller, "caller");
        final ArrayList<McpRegisteredTool> registrations = new ArrayList<>(definitions.size());
        for (Map<String, Object> definition : definitions) {
            final LinkedHashMap<String, Object> checked =
                    new LinkedHashMap<>(Objects.requireNonNull(definition, "definition"));
            final Object nameValue = checked.get("name");
            if (!(nameValue instanceof String name) || name.isBlank()) {
                throw new IllegalArgumentException("MCP tool definition requires a name");
            }
            checked.putIfAbsent("outputSchema", DEFAULT_OUTPUT_SCHEMA);
            final Object schemaValue = checked.get("outputSchema");
            if (!(schemaValue instanceof Map<?, ?> rawSchema)) {
                throw new IllegalArgumentException("MCP tool outputSchema must be an object: " + name);
            }
            checked.put("outputSchema", Map.copyOf(stringMap(rawSchema, "outputSchema")));
            registrations.add(
                    McpRegisteredTool.legacy(Map.copyOf(checked), arguments -> checkedCaller.call(name, arguments)));
        }
        return new McpToolRegistry(registrations);
    }

    private static LinkedHashMap<String, Object> stringMap(final Map<?, ?> raw, final String label) {
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
        private JsonSchemaValidator() {}

        static String validate(final Map<String, Object> schema, final Object value) {
            return validate(schema, value, "$", 0, definitions(schema));
        }

        private static String validate(
                final Map<String, Object> schema,
                final Object value,
                final String path,
                final int depth,
                final Map<String, Object> definitions) {
            if (depth > MAX_SCHEMA_DEPTH) return path + " schema nesting exceeds " + MAX_SCHEMA_DEPTH + " levels";
            final Map<String, Object> resolved = resolve(schema, definitions, path);
            if (resolved == null) return path + " references an unknown definition";
            final Object oneOfValue = resolved.get("oneOf");
            if (oneOfValue instanceof List<?> alternatives) {
                int matches = 0;
                for (Object alternative : alternatives) {
                    if (alternative instanceof Map<?, ?> raw
                            && validate(stringMap(raw, "oneOf"), value, path, depth + 1, definitions) == null) {
                        matches++;
                    }
                }
                return matches == 1 ? null : path + " must match exactly one schema";
            }
            return validateConstraints(resolved, value, path, depth, definitions);
        }

        /**
         * Returns the schema that constrains the value. A {@code $ref} resolves against the enclosing
         * document definitions; sibling keywords stay in force.
         */
        private static Map<String, Object> resolve(
                final Map<String, Object> schema, final Map<String, Object> definitions, final String path) {
            if (!(schema.get("$ref") instanceof String pointer) || !pointer.startsWith(DEFINITIONS_POINTER)) {
                return schema;
            }
            final Object target = definitions.get(pointer.substring(DEFINITIONS_POINTER.length()));
            if (!(target instanceof Map<?, ?> raw)) return null;
            final Map<String, Object> resolved = stringMap(raw, path);
            boolean constrained = false;
            for (String key : schema.keySet()) {
                if (!ANNOTATION_KEYWORDS.contains(key)) {
                    constrained = true;
                    break;
                }
            }
            if (!constrained) return resolved;
            final LinkedHashMap<String, Object> merged = new LinkedHashMap<>(resolved);
            for (Map.Entry<String, Object> entry : schema.entrySet()) {
                if (!"$ref".equals(entry.getKey())) merged.put(entry.getKey(), entry.getValue());
            }
            return merged;
        }

        /** Returns the definitions the enclosing document advertises. */
        private static Map<String, Object> definitions(final Map<String, Object> schema) {
            return schema.get("$defs") instanceof Map<?, ?> raw ? stringMap(raw, "$defs") : Map.of();
        }

        private static String validateConstraints(
                final Map<String, Object> schema,
                final Object value,
                final String path,
                final int depth,
                final Map<String, Object> definitions) {
            final Object constValue = schema.get("const");
            if (schema.containsKey("const") && !Objects.equals(constValue, value)) {
                return path + " must equal its declared constant";
            }
            final Object enumValue = schema.get("enum");
            if (enumValue instanceof List<?> values
                    && values.stream().noneMatch(candidate -> Objects.equals(candidate, value))) {
                return path + " is outside its declared enum";
            }
            final Object typeValue = schema.get("type");
            if (typeValue instanceof List<?> types) {
                for (Object type : types) {
                    final LinkedHashMap<String, Object> candidate = new LinkedHashMap<>(schema);
                    candidate.put("type", type);
                    if (validate(candidate, value, path, depth + 1, definitions) == null) return null;
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
                final Map<String, Object> properties =
                        schema.get("properties") instanceof Map<?, ?> raw ? stringMap(raw, "properties") : Map.of();
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
                            depth + 1,
                            definitions);
                    if (violation != null) return violation;
                }
            }
            if (value instanceof List<?> values && ("array".equals(typeValue) || schema.containsKey("items"))) {
                if (schema.get("minItems") instanceof Number minimum && values.size() < minimum.intValue()) {
                    return path + " has too few items";
                }
                if (schema.get("maxItems") instanceof Number maximum && values.size() > maximum.intValue()) {
                    return path + " has too many items";
                }
                if (schema.get("items") instanceof Map<?, ?> rawItems) {
                    final Map<String, Object> itemSchema = stringMap(rawItems, "items");
                    for (int index = 0; index < values.size(); index++) {
                        final String violation = validate(
                                itemSchema, values.get(index), path + "[" + index + "]", depth + 1, definitions);
                        if (violation != null) return violation;
                    }
                }
            }
            if (value instanceof String text) {
                final int length = text.codePointCount(0, text.length());
                if (schema.get("minLength") instanceof Number minimum && length < minimum.intValue())
                    return path + " is too short";
                if (schema.get("maxLength") instanceof Number maximum && length > maximum.intValue())
                    return path + " is too long";
            }
            if (value instanceof Number number) {
                if (schema.get("minimum") instanceof Number minimum && compareDecimal(number, minimum) < 0) {
                    return path + " is below the minimum";
                }
                if (schema.get("maximum") instanceof Number maximum && compareDecimal(number, maximum) > 0) {
                    return path + " is above the maximum";
                }
            }
            return null;
        }

        /** Compares two JSON numbers through a primitive fast path before an exact decimal compare. */
        private static int compareDecimal(final Number value, final Number bound) {
            if (isIntegral(value) && isIntegral(bound)) return Long.compare(value.longValue(), bound.longValue());
            if (value instanceof Float
                    || value instanceof Double
                    || bound instanceof Float
                    || bound instanceof Double) {
                final double left = value.doubleValue();
                final double right = bound.doubleValue();
                if (Double.isFinite(left) && Double.isFinite(right)) return Double.compare(left, right);
            }
            return new java.math.BigDecimal(value.toString()).compareTo(new java.math.BigDecimal(bound.toString()));
        }

        private static boolean isIntegral(final Number number) {
            return number instanceof Byte
                    || number instanceof Short
                    || number instanceof Integer
                    || number instanceof Long;
        }

        private static boolean typeMatches(final String type, final Object value) {
            return switch (type) {
                case "null" -> value == null;
                case "object" -> value instanceof Map<?, ?>;
                case "array" -> value instanceof List<?>;
                case "string" -> value instanceof String;
                case "boolean" -> value instanceof Boolean;
                case "number" -> value instanceof Number;
                case "integer" ->
                    value instanceof Byte
                            || value instanceof Short
                            || value instanceof Integer
                            || value instanceof Long
                            || value instanceof BigInteger
                            || value instanceof BigDecimal decimal
                                    && decimal.stripTrailingZeros().scale() <= 0;
                default -> true;
            };
        }
    }
}
