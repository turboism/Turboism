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

    private final Map<String, Object> publicDefinition;
    private final String name;
    private final McpOperationEffect effect;
    private final McpExecutionAffinity affinity;
    private final boolean transactionEligible;
    private final boolean exactOutputSchema;
    private final Handler rawHandler;
    private final Handler publicHandler;

    private McpRegisteredTool(
        final Map<String, Object> publicDefinition,
        final McpOperationEffect effect,
        final McpExecutionAffinity affinity,
        final boolean transactionEligible,
        final boolean exactOutputSchema,
        final Handler rawHandler,
        final Handler publicHandler
    ) {
        this.publicDefinition = immutableDefinition(publicDefinition);
        this.name = requiredText(this.publicDefinition, "name");
        this.effect = Objects.requireNonNull(effect, "effect");
        this.affinity = Objects.requireNonNull(affinity, "affinity");
        this.transactionEligible = transactionEligible;
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
        final Handler rawHandler
    ) {
        final Map<String, Object> definition = immutableDefinition(publicDefinition);
        requiredText(definition, "name");
        requiredText(definition, "title");
        requiredText(definition, "description");
        requiredSchema(definition, "inputSchema");
        requiredSchema(definition, "outputSchema");
        final McpExecutionBridge checkedExecution = Objects.requireNonNull(execution, "execution");
        final Handler checkedRaw = Objects.requireNonNull(rawHandler, "rawHandler");
        final Handler standalone = arguments -> switch (Objects.requireNonNull(affinity, "affinity")) {
            case DIRECT -> checkedExecution.direct(() -> checkedRaw.call(arguments));
            case UI_THREAD -> checkedExecution.ui(() -> checkedRaw.call(arguments));
            case COMPLETION_STAGE -> checkedExecution.stage(
                () -> CompletableFuture.completedFuture(checkedRaw.call(arguments))
            );
        };
        return new McpRegisteredTool(
            definition,
            effect,
            affinity,
            transactionEligible,
            isExactSchema(definition.get("outputSchema"), 0),
            checkedRaw,
            standalone
        );
    }

    static McpRegisteredTool legacy(
        final Map<String, Object> publicDefinition,
        final Handler handler
    ) {
        final Map<String, Object> definition = immutableDefinition(publicDefinition);
        final Handler checked = Objects.requireNonNull(handler, "handler");
        return new McpRegisteredTool(
            definition,
            inferLegacyEffect(definition),
            McpExecutionAffinity.DIRECT,
            false,
            false,
            checked,
            checked
        );
    }

    McpRegisteredTool withPublicHandler(final Handler handler) {
        return withHandlers(Objects.requireNonNull(handler, "handler"), rawHandler);
    }

    McpRegisteredTool withHandlers(
        final Handler standaloneHandler,
        final Handler inProcessHandler
    ) {
        return new McpRegisteredTool(
            publicDefinition,
            effect,
            affinity,
            transactionEligible,
            exactOutputSchema,
            Objects.requireNonNull(inProcessHandler, "inProcessHandler"),
            Objects.requireNonNull(standaloneHandler, "standaloneHandler")
        );
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
        if (transactionEligible
            && effect != McpOperationEffect.READ
            && effect != McpOperationEffect.UNDOABLE_WRITE) {
            throw new IllegalArgumentException(
                "Transaction-eligible MCP tool must be READ or UNDOABLE_WRITE: " + name
            );
        }
        if (transactionEligible && !exactOutputSchema) {
            throw new IllegalArgumentException(
                "Transaction-eligible MCP tool requires an exact output schema: " + name
            );
        }
        if (transactionEligible && affinity == McpExecutionAffinity.COMPLETION_STAGE) {
            throw new IllegalArgumentException(
                "Completion-stage MCP tool cannot run inside a synchronous authoring transaction: " + name
            );
        }
    }

    private static McpOperationEffect inferLegacyEffect(final Map<String, Object> definition) {
        final String name = requiredText(definition, "name");
        if (name.startsWith("turboism.history.")) return McpOperationEffect.HISTORY_CONTROL;
        final Object annotationsValue = definition.get("annotations");
        if (annotationsValue instanceof Map<?, ?> annotations
            && Boolean.TRUE.equals(annotations.get("readOnlyHint"))) {
            return McpOperationEffect.READ;
        }
        return McpOperationEffect.EXTERNAL_SIDE_EFFECT;
    }

    private static boolean isExactSchema(final Object value, final int depth) {
        if (!(value instanceof Map<?, ?> schema) || depth > 64) return false;

        final Object oneOf = schema.get("oneOf");
        if (oneOf instanceof java.util.List<?> alternatives) {
            return !alternatives.isEmpty()
                && alternatives.stream().allMatch(alternative -> isExactSchema(alternative, depth + 1));
        }

        final Object type = schema.get("type");
        if (type instanceof java.util.List<?> alternatives) {
            if (alternatives.isEmpty()) return false;
            for (Object alternative : alternatives) {
                final LinkedHashMap<Object, Object> narrowed = new LinkedHashMap<>(schema);
                narrowed.put("type", alternative);
                if (!isExactSchema(narrowed, depth + 1)) return false;
            }
            return true;
        }

        if ("object".equals(type) || schema.containsKey("properties")) {
            if (!Boolean.FALSE.equals(schema.get("additionalProperties"))) return false;
            final Object propertiesValue = schema.get("properties");
            if (!(propertiesValue instanceof Map<?, ?> properties)) return false;
            for (Object propertySchema : properties.values()) {
                if (!isExactSchema(propertySchema, depth + 1)) return false;
            }
            return true;
        }

        if ("array".equals(type) || schema.containsKey("items")) {
            return isExactSchema(schema.get("items"), depth + 1);
        }

        if (type instanceof String primitive) {
            return switch (primitive) {
                case "null", "boolean", "integer", "number", "string" -> true;
                default -> false;
            };
        }
        return schema.containsKey("const") || schema.get("enum") instanceof java.util.List<?>;
    }

    private static Map<String, Object> immutableDefinition(final Map<String, Object> value) {
        Objects.requireNonNull(value, "publicDefinition");
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String requiredText(final Map<String, Object> definition, final String field) {
        final Object value = definition.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("MCP tool definition requires " + field);
        }
        return text;
    }

    private static Map<?, ?> requiredSchema(
        final Map<String, Object> definition,
        final String field
    ) {
        final Object value = definition.get(field);
        if (!(value instanceof Map<?, ?> schema)) {
            throw new IllegalArgumentException("MCP tool definition requires object " + field);
        }
        return schema;
    }
}
