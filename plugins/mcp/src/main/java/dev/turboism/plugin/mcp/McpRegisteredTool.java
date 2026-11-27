package dev.turboism.plugin.mcp;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** One typed MCP registration with separate standalone and in-process invocation paths. */
final class McpRegisteredTool {

    @FunctionalInterface
    interface Handler {
        Map<String, Object> call(Map<String, Object> arguments);
    }

    /** JSON-Pointer prefix that addresses a definition in the enclosing schema document root. */
    private static final String DEFINITIONS_POINTER = "#/$defs/";

    private static final int MAX_SCHEMA_DEPTH = 64;
    private static final String INPUT_SCHEMA = "inputSchema";
    private static final String OUTPUT_SCHEMA = "outputSchema";
    private static final String SCHEMA_TYPE = "type";
    private static final String OBJECT_TYPE = "object";

    private final Map<String, Object> publicDefinition;
    private final String name;
    private final McpOperationEffect effect;
    private final McpExecutionAffinity affinity;
    private final boolean transactionEligible;
    private final McpVersionSupport versionSupport;
    private final boolean exactOutputSchema;
    private final Handler rawHandler;
    private final Handler publicHandler;

    private McpRegisteredTool(
            final Map<String, Object> publicDefinition,
            final McpOperationEffect effect,
            final McpExecutionAffinity affinity,
            final boolean transactionEligible,
            final McpVersionSupport versionSupport,
            final boolean exactOutputSchema,
            final Handler rawHandler,
            final Handler publicHandler) {
        this.publicDefinition = immutableDefinition(publicDefinition);
        this.name = requiredText(this.publicDefinition, "name");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.affinity = Objects.requireNonNull(affinity, "affinity");
        this.transactionEligible = transactionEligible;
        this.versionSupport = Objects.requireNonNull(versionSupport, "versionSupport");
        this.exactOutputSchema = exactOutputSchema;
        this.rawHandler = Objects.requireNonNull(rawHandler, "rawHandler");
        this.publicHandler = Objects.requireNonNull(publicHandler, "publicHandler");
        validateRegistration();
    }

    static McpRegisteredTool typed(
            final Map<String, Object> publicDefinition,
            final McpOperationEffect effect,
            final McpExecutionAffinity affinity,
            final boolean transactionEligible,
            final McpExecutionBridge execution,
            final Handler rawHandler) {
        return typed(
                publicDefinition,
                effect,
                affinity,
                transactionEligible,
                McpVersionSupport.unscoped(),
                execution,
                rawHandler);
    }

    static McpRegisteredTool typed(
            final Map<String, Object> publicDefinition,
            final McpOperationEffect effect,
            final McpExecutionAffinity affinity,
            final boolean transactionEligible,
            final McpVersionSupport versionSupport,
            final McpExecutionBridge execution,
            final Handler rawHandler) {
        final Map<String, Object> definition = immutableDefinition(publicDefinition);
        requiredText(definition, "name");
        requiredText(definition, "title");
        requiredText(definition, "description");
        requiredSchema(definition, INPUT_SCHEMA);
        requiredSchema(definition, OUTPUT_SCHEMA);
        final McpExecutionBridge checkedExecution = Objects.requireNonNull(execution, "execution");
        final Handler checkedRaw = Objects.requireNonNull(rawHandler, "rawHandler");
        final Handler standalone = arguments -> switch (Objects.requireNonNull(affinity, "affinity")) {
            case DIRECT -> checkedExecution.direct(() -> checkedRaw.call(arguments));
            case UI_THREAD -> checkedExecution.ui(() -> checkedRaw.call(arguments));
            case COMPLETION_STAGE ->
                checkedExecution.stage(() -> CompletableFuture.completedFuture(checkedRaw.call(arguments)));
        };
        return new McpRegisteredTool(
                definition,
                effect,
                affinity,
                transactionEligible,
                Objects.requireNonNull(versionSupport, "versionSupport"),
                isExactSchema(definition.get(OUTPUT_SCHEMA), 0, definitionsOf(definition.get(OUTPUT_SCHEMA))),
                checkedRaw,
                standalone);
    }

    static McpRegisteredTool legacy(final Map<String, Object> publicDefinition, final Handler handler) {
        final Map<String, Object> definition = immutableDefinition(publicDefinition);
        final Handler checked = Objects.requireNonNull(handler, "handler");
        return new McpRegisteredTool(
                definition,
                inferLegacyEffect(definition),
                McpExecutionAffinity.DIRECT,
                false,
                McpVersionSupport.unscoped(),
                false,
                checked,
                checked);
    }

    McpRegisteredTool withPublicHandler(final Handler handler) {
        return withHandlers(Objects.requireNonNull(handler, "handler"), rawHandler);
    }

    McpRegisteredTool withHandlers(final Handler standaloneHandler, final Handler inProcessHandler) {
        return new McpRegisteredTool(
                publicDefinition,
                effect,
                affinity,
                transactionEligible,
                versionSupport,
                exactOutputSchema,
                Objects.requireNonNull(inProcessHandler, "inProcessHandler"),
                Objects.requireNonNull(standaloneHandler, "standaloneHandler"));
    }

    String name() {
        return name;
    }

    Map<String, Object> publicDefinition() {
        return publicDefinition;
    }

    McpOperationEffect effect() {
        return effect;
    }

    McpExecutionAffinity affinity() {
        return affinity;
    }

    boolean transactionEligible() {
        return transactionEligible;
    }

    McpVersionSupport versionSupport() {
        return versionSupport;
    }

    boolean exactOutputSchema() {
        return exactOutputSchema;
    }

    Map<String, Object> invokePublic(final Map<String, Object> arguments) {
        return publicHandler.call(arguments);
    }

    Map<String, Object> invokeRaw(final Map<String, Object> arguments) {
        return rawHandler.call(arguments);
    }

    private void validateRegistration() {
        if (transactionEligible && effect != McpOperationEffect.READ && effect != McpOperationEffect.UNDOABLE_WRITE) {
            throw new IllegalArgumentException("Transaction-eligible MCP tool must be READ or UNDOABLE_WRITE: " + name);
        }
        if (transactionEligible && !exactOutputSchema) {
            throw new IllegalArgumentException(
                    "Transaction-eligible MCP tool requires an exact output schema: " + name);
        }
        if (transactionEligible && affinity == McpExecutionAffinity.COMPLETION_STAGE) {
            throw new IllegalArgumentException(
                    "Completion-stage MCP tool cannot run inside a synchronous authoring transaction: " + name);
        }
    }

    private static McpOperationEffect inferLegacyEffect(final Map<String, Object> definition) {
        final String name = requiredText(definition, "name");
        final Object annotationsValue = definition.get("annotations");
        if (annotationsValue instanceof Map<?, ?> annotations && Boolean.TRUE.equals(annotations.get("readOnlyHint"))) {
            return McpOperationEffect.READ;
        }
        if (name.startsWith("turboism.history.")) {
            return McpOperationEffect.HISTORY_CONTROL;
        }
        return McpOperationEffect.EXTERNAL_SIDE_EFFECT;
    }

    /**
     * Reports whether {@code value} pins every member of a projection.
     *
     * @param definitions the enclosing document definitions, or {@code null} when none apply
     */
    private static boolean isExactSchema(final Object value, final int depth, final Map<String, Object> definitions) {
        if (!(value instanceof Map<?, ?> schema) || depth > MAX_SCHEMA_DEPTH) return false;

        if (schema.get("$ref") instanceof String pointer) {
            return definitions != null
                    && pointer.startsWith(DEFINITIONS_POINTER)
                    && isExactSchema(
                            definitions.get(pointer.substring(DEFINITIONS_POINTER.length())), depth + 1, definitions);
        }

        final Object oneOf = schema.get("oneOf");
        if (oneOf instanceof java.util.List<?> alternatives) {
            return !alternatives.isEmpty()
                    && alternatives.stream()
                            .allMatch(alternative -> isExactSchema(alternative, depth + 1, definitions));
        }

        final Object type = schema.get("type");
        if (type instanceof java.util.List<?> alternatives) {
            if (alternatives.isEmpty()) return false;
            for (Object alternative : alternatives) {
                final LinkedHashMap<Object, Object> narrowed = new LinkedHashMap<>(schema);
                narrowed.put("type", alternative);
                if (!isExactSchema(narrowed, depth + 1, definitions)) return false;
            }
            return true;
        }

        if ("object".equals(type) || schema.containsKey("properties")) {
            if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) return false;
            final Object propertiesValue = schema.get("properties");
            if (!(propertiesValue instanceof Map<?, ?> properties)) return false;
            for (Object propertySchema : properties.values()) {
                if (!isExactSchema(propertySchema, depth + 1, definitions)) return false;
            }
            return true;
        }

        if ("array".equals(type) || schema.containsKey("items")) {
            return isExactSchema(schema.get("items"), depth + 1, definitions);
        }

        if (type instanceof String primitive) {
            return switch (primitive) {
                case "null", "boolean", "integer", "number", "string" -> true;
                default -> false;
            };
        }
        return schema.containsKey("const") || schema.get("enum") instanceof java.util.List<?>;
    }

    /** Returns the definitions a schema root advertises, or an empty map when it declares none. */
    private static Map<String, Object> definitionsOf(final Object value) {
        if (!(value instanceof Map<?, ?> schema)) return Map.of();
        return schema.get("$defs") instanceof Map<?, ?> definitions
                ? Collections.unmodifiableMap(new LinkedHashMap<>((Map<String, Object>) definitions))
                : Map.of();
    }

    private static Map<String, Object> immutableDefinition(final Map<String, Object> value) {
        Objects.requireNonNull(value, "publicDefinition");
        final LinkedHashMap<String, Object> definition = new LinkedHashMap<>(value);
        definition.computeIfPresent(INPUT_SCHEMA, McpRegisteredTool::toolObjectSchema);
        definition.computeIfPresent(OUTPUT_SCHEMA, McpRegisteredTool::toolObjectSchema);
        return Collections.unmodifiableMap(definition);
    }

    /** Declares the object root required by MCP while preserving the schema constraints. */
    private static Map<?, ?> toolObjectSchema(final String field, final Object value) {
        if (!(value instanceof Map<?, ?> schema)) {
            throw new IllegalArgumentException("MCP tool definition requires object " + field);
        }
        if (schema.containsKey(SCHEMA_TYPE)) {
            if (!OBJECT_TYPE.equals(schema.get(SCHEMA_TYPE))) {
                throw new IllegalArgumentException("MCP tool schema requires an object root: " + field);
            }
            return schema;
        }
        final LinkedHashMap<Object, Object> declared = new LinkedHashMap<>();
        declared.put(SCHEMA_TYPE, OBJECT_TYPE);
        declared.putAll(schema);
        return Collections.unmodifiableMap(declared);
    }

    private static String requiredText(final Map<String, Object> definition, final String field) {
        final Object value = definition.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP tool definition requires " + field);
        }
        return text;
    }

    private static Map<?, ?> requiredSchema(final Map<String, Object> definition, final String field) {
        final Object value = definition.get(field);
        if (!(value instanceof Map<?, ?> schema)) {
            throw new IllegalArgumentException("MCP tool definition requires object " + field);
        }
        return schema;
    }
}
