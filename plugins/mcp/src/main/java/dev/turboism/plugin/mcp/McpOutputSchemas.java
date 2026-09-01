package dev.turboism.plugin.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Exact structured-output schemas shared by the production MCP tool domains. */
final class McpOutputSchemas {

    private static final String DRAFT = "https://json-schema.org/draft/2020-12/schema";

    private McpOutputSchemas() {
    }

    static Map<String, Object> modelObjectBatch() {
        return successOrFailure(object(
            properties(
                entry("ok", booleanSchema()),
                entry("partialSuccess", booleanSchema()),
                entry("stopOnError", booleanSchema()),
                entry("stopped", booleanSchema()),
                entry("succeeded", nonNegativeInteger()),
                entry("failed", nonNegativeInteger()),
                entry("results", array(modelObjectOperationResult()))
            ),
            List.of(
                "ok", "partialSuccess", "stopOnError", "stopped",
                "succeeded", "failed", "results"
            )
        ));
    }

    static Map<String, Object> parameterBatch() {
        return successOrFailure(object(
            properties(
                entry("ok", booleanSchema()),
                entry("stopOnError", booleanSchema()),
                entry("results", array(parameterOperationResult())),
                entry("parameters", array(parameter()))
            ),
            List.of("ok", "stopOnError", "results", "parameters")
        ));
    }

    static Map<String, Object> bindingBatch() {
        return successOrFailure(object(
            properties(
                entry("ok", booleanSchema()),
                entry("stopOnError", booleanSchema()),
                entry("results", array(bindingOperationResult()))
            ),
            List.of("ok", "stopOnError", "results")
        ));
    }

    static Map<String, Object> historyMove() {
        return successOrFailure(object(
            properties(
                entry("ok", booleanSchema()),
                entry("outcome", enumSchema(List.of(
                    "MOVED", "NO_CHANGE", "REJECTED_STALE", "INVALID_POSITION",
                    "PARTIAL_MOVE", "UNAVAILABLE", "FAILED_UNKNOWN_POSITION"
                ))),
                entry("snapshot", historySnapshot()),
                entry("diagnosticId", nullableString())
            ),
            List.of("ok", "outcome", "snapshot", "diagnosticId")
        ));
    }

    static Map<String, Object> editorCommand() {
        return successOrFailure(object(
            properties(
                entry("ok", booleanSchema()),
                entry("status", enumSchema(List.of(
                    "EXECUTED", "UNAVAILABLE", "INVALID_STATE", "UNSUPPORTED_VERSION",
                    "PERMISSION_DENIED", "REJECTED", "FAILED"
                ))),
                entry("commandId", stringSchema()),
                entry("executed", booleanSchema())
            ),
            List.of("ok", "status", "commandId", "executed")
        ));
    }

    private static Map<String, Object> modelObjectOperationResult() {
        final Map<String, Object> common = properties(
            entry("index", nonNegativeInteger()),
            entry("operation", stringSchema()),
            entry("ok", constant(true)),
            entry("result", modelObjectSuccess())
        );
        final Map<String, Object> failed = properties(
            entry("index", nonNegativeInteger()),
            entry("operation", stringSchema()),
            entry("ok", constant(false)),
            entry("result", modelObjectFailure())
        );
        final Map<String, Object> skipped = properties(
            entry("index", nonNegativeInteger()),
            entry("operation", stringSchema()),
            entry("ok", constant(false)),
            entry("skipped", constant(true)),
            entry("result", modelObjectFailure())
        );
        return oneOf(
            object(common, List.of("index", "operation", "ok", "result")),
            object(failed, List.of("index", "operation", "ok", "result")),
            object(skipped, List.of("index", "operation", "ok", "skipped", "result"))
        );
    }

    private static Map<String, Object> modelObjectSuccess() {
        return oneOf(
            object(
                properties(
                    entry("ok", constant(true)),
                    entry("outcome", writeSuccessOutcome()),
                    entry("retryable", constant(false)),
                    entry("object", modelObjectDescriptor()),
                    entry("createdObjectId", stringSchema()),
                    entry("kind", modelObjectKind()),
                    entry("readbackWarning", stringSchema()),
                    entry("diagnosticId", stringSchema())
                ),
                List.of("ok", "outcome", "retryable", "object")
            ),
            object(
                properties(
                    entry("ok", constant(true)),
                    entry("outcome", writeSuccessOutcome()),
                    entry("retryable", constant(false)),
                    entry("deleted", constant(true)),
                    entry("target", modelObjectReference()),
                    entry("policy", enumSchema(List.of("reject_referenced", "cascade"))),
                    entry("readbackWarning", stringSchema()),
                    entry("diagnosticId", stringSchema())
                ),
                List.of("ok", "outcome", "retryable", "deleted", "target", "policy")
            )
        );
    }

    private static Map<String, Object> modelObjectFailure() {
        return object(
            properties(
                entry("ok", constant(false)),
                entry("outcome", writeFailureOutcome()),
                entry("retryable", constant(false)),
                entry("error", error()),
                entry("diagnosticId", stringSchema())
            ),
            List.of("ok", "outcome", "retryable", "error")
        );
    }

    private static Map<String, Object> writeSuccessOutcome() {
        return enumSchema(List.of("APPLIED", "APPLIED_WITH_READBACK_WARNING"));
    }

    private static Map<String, Object> writeFailureOutcome() {
        return enumSchema(List.of("NOT_APPLIED", "ROLLED_BACK", "OUTCOME_UNKNOWN"));
    }

    private static Map<String, Object> modelObjectDescriptor() {
        return object(
            properties(
                entry("kind", modelObjectKind()),
                entry("id", stringSchema()),
                entry("name", stringSchema()),
                entry("parent", nullableObject(modelObjectReference()))
            ),
            List.of("kind", "id", "name", "parent")
        );
    }

    private static Map<String, Object> modelObjectReference() {
        return object(
            properties(entry("kind", modelObjectKind()), entry("id", stringSchema())),
            List.of("kind", "id")
        );
    }

    private static Map<String, Object> modelObjectKind() {
        return enumSchema(List.of("part", "art_mesh", "warp_deformer", "rotation_deformer"));
    }

    private static Map<String, Object> parameterOperationResult() {
        return operationResult(oneOf(
            parameter(),
            object(properties(entry("created", array(parameter()))), List.of("created")),
            object(
                properties(entry("parameterId", stringSchema()), entry("removed", constant(true))),
                List.of("parameterId", "removed")
            ),
            object(
                properties(entry("parameterIds", array(stringSchema())), entry("removed", constant(true))),
                List.of("parameterIds", "removed")
            )
        ));
    }

    private static Map<String, Object> bindingOperationResult() {
        return operationResult(oneOf(
            bindingResult(),
            bindingResults(),
            object(
                properties(entry("source", bindingResults()), entry("target", bindingResults())),
                List.of("source", "target")
            )
        ));
    }

    private static Map<String, Object> operationResult(final Map<String, Object> successResult) {
        return oneOf(
            object(
                properties(
                    entry("index", nonNegativeInteger()),
                    entry("operation", stringSchema()),
                    entry("ok", constant(true)),
                    entry("result", successResult)
                ),
                List.of("index", "operation", "ok", "result")
            ),
            object(
                properties(
                    entry("index", nonNegativeInteger()),
                    entry("operation", nullableString()),
                    entry("ok", constant(false)),
                    entry("error", error())
                ),
                List.of("index", "operation", "ok", "error")
            )
        );
    }

    private static Map<String, Object> parameter() {
        return object(
            properties(
                entry("id", stringSchema()),
                entry("name", stringSchema()),
                entry("value", numberSchema()),
                entry("minimumValue", numberSchema()),
                entry("defaultValue", numberSchema()),
                entry("maximumValue", numberSchema()),
                entry("type", enumSchema(List.of("normal", "blend_shape"))),
                entry("repeat", booleanSchema())
            ),
            List.of(
                "id", "name", "value", "minimumValue", "defaultValue",
                "maximumValue", "type", "repeat"
            )
        );
    }

    private static Map<String, Object> bindingResult() {
        return object(
            properties(
                entry("parameterId", stringSchema()),
                entry("target", bindingTarget()),
                entry("binding", nullableObject(binding()))
            ),
            List.of("parameterId", "target", "binding")
        );
    }

    private static Map<String, Object> bindingResults() {
        return object(
            properties(
                entry("parameterId", stringSchema()),
                entry("bindings", array(bindingResult()))
            ),
            List.of("parameterId", "bindings")
        );
    }

    private static Map<String, Object> binding() {
        return object(
            properties(
                entry("parameterId", stringSchema()),
                entry("target", bindingTarget()),
                entry("family", enumSchema(List.of("keyform_grid", "blend_shape"))),
                entry("points", array(bindingPoint()))
            ),
            List.of("parameterId", "target", "family", "points")
        );
    }

    private static Map<String, Object> bindingTarget() {
        return object(
            properties(
                entry("type", enumSchema(List.of("art_mesh", "warp_deformer", "rotation_deformer"))),
                entry("id", stringSchema())
            ),
            List.of("type", "id")
        );
    }

    private static Map<String, Object> bindingPoint() {
        return object(
            properties(entry("id", stringSchema()), entry("value", numberSchema())),
            List.of("id", "value")
        );
    }

    private static Map<String, Object> historySnapshot() {
        return object(
            properties(
                entry("availability", enumSchema(List.of("AVAILABLE", "UNAVAILABLE"))),
                entry("generation", nonNegativeInteger()),
                entry("revision", nonNegativeInteger()),
                entry("position", nonNegativeInteger()),
                entry("entries", array(historyEntry())),
                entry("canUndo", booleanSchema()),
                entry("canRedo", booleanSchema())
            ),
            List.of(
                "availability", "generation", "revision", "position",
                "entries", "canUndo", "canRedo"
            )
        );
    }

    private static Map<String, Object> historyEntry() {
        return object(
            properties(
                entry("index", nonNegativeInteger()),
                entry("label", stringSchema()),
                entry("significant", booleanSchema()),
                entry("detailLevel", enumSchema(List.of("FULL", "PARTIAL", "LABEL_ONLY"))),
                entry("action", nullableObject(historyAction()))
            ),
            List.of("index", "label", "significant", "detailLevel", "action")
        );
    }

    private static Map<String, Object> historyAction() {
        return object(
            properties(
                entry("kind", enumSchema(List.of("SET_PARAMETER_VALUE", "UNKNOWN"))),
                entry("targetType", stringSchema()),
                entry("targetId", stringSchema()),
                entry("property", stringSchema()),
                entry("before", nullableString()),
                entry("after", nullableString()),
                entry("detailLevel", enumSchema(List.of("FULL", "PARTIAL")))
            ),
            List.of(
                "kind", "targetType", "targetId", "property",
                "before", "after", "detailLevel"
            )
        );
    }

    private static Map<String, Object> successOrFailure(final Map<String, Object> success) {
        return linked(
            entry("$schema", DRAFT),
            entry("type", "object"),
            entry("oneOf", List.of(success, failure()))
        );
    }

    private static Map<String, Object> failure() {
        return object(
            properties(entry("ok", constant(false)), entry("error", error())),
            List.of("ok", "error")
        );
    }

    private static Map<String, Object> error() {
        return object(
            properties(
                entry("code", stringSchema()),
                entry("message", stringSchema()),
                entry("details", stringSchema())
            ),
            List.of("code", "message")
        );
    }

    private static Map<String, Object> nullableObject(final Map<String, Object> object) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>(object);
        result.put("type", List.of("object", "null"));
        return result;
    }

    private static Map<String, Object> oneOf(final Map<String, Object>... alternatives) {
        return Map.of("oneOf", List.of(alternatives));
    }

    private static Map<String, Object> object(
        final Map<String, Object> properties,
        final List<String> required
    ) {
        return linked(
            entry("type", "object"),
            entry("properties", properties),
            entry("required", required),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> array(final Map<String, Object> items) {
        return Map.of("type", "array", "items", items);
    }

    private static Map<String, Object> constant(final Object value) {
        return Map.of("const", value);
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> nullableString() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> booleanSchema() {
        return Map.of("type", "boolean");
    }

    private static Map<String, Object> numberSchema() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> nonNegativeInteger() {
        return Map.of("type", "integer", "minimum", 0);
    }

    private static Map<String, Object> enumSchema(final List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    @SafeVarargs
    private static Map<String, Object> properties(final Map.Entry<String, Object>... entries) {
        return linked(entries);
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(
        final Map.Entry<String, Object>... entries
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }
}
