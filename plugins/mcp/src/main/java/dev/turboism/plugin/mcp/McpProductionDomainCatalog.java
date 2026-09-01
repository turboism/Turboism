package dev.turboism.plugin.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The production MCP domain surface for the active Cubism document.
 *
 * <p>The older {@link McpTools} names remain an internal SDK translation layer only. This catalog
 * deliberately presents one structural batch operation and a small set of state resources to MCP
 * clients, keeping read state separate from mutations.
 */
final class McpProductionDomainCatalog {

    static final String APPLY = "turboism.model_objects.apply";
    static final String ACTIVE_DOCUMENT = "turboism://active/document";
    static final String MODEL_OVERVIEW = "turboism://active/model/overview";
    static final String MODEL_HIERARCHY = "turboism://active/model/hierarchy";
    static final String CLIP_MASKS = "turboism://active/model/clip-masks";

    private final McpTools legacyTools;

    McpProductionDomainCatalog(final McpTools legacyTools) {
        this.legacyTools = Objects.requireNonNull(legacyTools, "legacyTools");
    }

    McpToolCatalog tools() {
        return new McpToolCatalog(toolDefinitions(), this::call);
    }

    McpResourceCatalog resourceCatalog() {
        return new McpResourceCatalog(resources(), List.of(), this::read);
    }

    List<Map<String, Object>> toolDefinitions() {
        return List.of(applyDefinition());
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        if (!APPLY.equals(name)) {
            throw new IllegalArgumentException("Unknown MCP tool: " + name);
        }
        return apply(arguments);
    }

    List<Map<String, Object>> resources() {
        return List.of(
            resource(ACTIVE_DOCUMENT, "Active document", "The active Cubism document and its current snapshot."),
            resource(MODEL_OVERVIEW, "Active model overview", "The active model and current selection."),
            resource(MODEL_HIERARCHY, "Active model hierarchy", "The active model object hierarchy."),
            resource(CLIP_MASKS, "Active model clip masks", "The active model ArtMesh clip-mask records.")
        );
    }

    List<Map<String, Object>> read(final String uri) {
        final Map<String, Object> content = switch (uri) {
            case ACTIVE_DOCUMENT -> snapshot();
            case MODEL_OVERVIEW -> overview();
            case MODEL_HIERARCHY -> invoke(McpTools.MODEL_HIERARCHY_GET, Map.of());
            case CLIP_MASKS -> invoke(McpTools.CLIP_MASKS_LIST, Map.of());
            default -> throw new McpResourceCatalog.ResourceNotFound(uri);
        };
        return List.of(linked(
            entry("uri", uri),
            entry("mimeType", "application/json"),
            entry("text", Json.stringify(content))
        ));
    }

    private Map<String, Object> snapshot() {
        return invoke(McpTools.MODEL_SNAPSHOT_GET, Map.of());
    }

    private Map<String, Object> overview() {
        final Map<String, Object> snapshot = snapshot();
        final Map<String, Object> authoritative = invoke(McpTools.LIST, Map.of());
        if (!Boolean.TRUE.equals(authoritative.get("ok"))) {
            return linked(
                entry("ok", true),
                entry("availability", "UNAVAILABLE"),
                entry("document", snapshot.get("document")),
                entry("model", null),
                entry("selection", snapshot.get("selection")),
                entry("objects", null),
                entry("modelObjects", null),
                entry("diagnosticCode", "MODEL_OBJECT_PROVIDER_UNAVAILABLE")
            );
        }
        final List<Object> objects = array(authoritative.get("objects"), "objects");
        final Object model = modelWithObjects(snapshot.get("model"), objects);
        return linked(
            entry("ok", true),
            entry("availability", "AVAILABLE"),
            entry("document", snapshot.get("document")),
            entry("model", model),
            entry("selection", snapshot.get("selection")),
            entry("objects", objects),
            entry("modelObjects", objects),
            entry("diagnosticCode", null)
        );
    }

    private static Object modelWithObjects(final Object value, final List<Object> objects) {
        if (!(value instanceof Map<?, ?> raw)) return null;
        final LinkedHashMap<String, Object> model = new LinkedHashMap<>(stringMap(raw, "model"));
        model.put("objects", objects);
        model.put("modelObjects", objects);
        return model;
    }

    private Map<String, Object> apply(final Map<String, Object> arguments) {
        only(arguments, "operations", "stopOnError");
        final List<Object> operations = array(arguments.get("operations"), "operations");
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("operations must not be empty");
        }
        final boolean stopOnError = optionalBoolean(arguments, "stopOnError", false);
        final ArrayList<Map<String, Object>> results = new ArrayList<>(operations.size());
        int succeeded = 0;
        int failed = 0;
        boolean stopped = false;

        for (int index = 0; index < operations.size(); index++) {
            final Map<String, Object> operation = object(operations.get(index), "operations[" + index + "]");
            final String type = requiredString(operation, "operation");
            if (stopped) {
                results.add(skippedResult(index, type));
                continue;
            }
            final Map<String, Object> result = execute(type, operation);
            final boolean ok = Boolean.TRUE.equals(result.get("ok"));
            results.add(linked(
                entry("index", index),
                entry("operation", type),
                entry("ok", ok),
                entry("result", result)
            ));
            if (ok) {
                succeeded++;
            } else {
                failed++;
                stopped = stopOnError;
            }
        }
        if (stopped) {
            failed += operations.size() - results.size();
        }
        final boolean complete = failed == 0;
        return toolResult(linked(
            entry("ok", complete),
            entry("partialSuccess", succeeded > 0 && failed > 0),
            entry("stopOnError", stopOnError),
            entry("stopped", stopped),
            entry("succeeded", succeeded),
            entry("failed", failed),
            entry("results", results)
        ), !complete);
    }

    private Map<String, Object> execute(
        final String operation,
        final Map<String, Object> values
    ) {
        final String legacyName = switch (operation) {
            case "create" -> McpTools.CREATE;
            case "rename" -> McpTools.RENAME;
            case "reparent" -> McpTools.REPARENT;
            case "delete" -> McpTools.DELETE;
            default -> null;
        };
        if (legacyName == null) {
            return failure("INVALID_ARGUMENT", "operation must be create, rename, reparent, or delete");
        }
        final LinkedHashMap<String, Object> arguments = new LinkedHashMap<>(values);
        arguments.remove("operation");
        return withWriteOutcome(operation, invoke(legacyName, arguments));
    }

    private static Map<String, Object> withWriteOutcome(
        final String operation,
        final Map<String, Object> result
    ) {
        if (result.containsKey("outcome")) return result;
        final LinkedHashMap<String, Object> enriched = new LinkedHashMap<>(result);
        enriched.put("retryable", false);
        if (Boolean.TRUE.equals(result.get("ok"))) {
            enriched.put("outcome", "APPLIED");
            if ("create".equals(operation) && result.get("object") instanceof Map<?, ?> raw) {
                final Map<String, Object> object = stringMap(raw, "object");
                enriched.put("createdObjectId", object.get("id"));
                enriched.put("kind", object.get("kind"));
            }
            return enriched;
        }
        final Object errorValue = result.get("error");
        final String code = errorValue instanceof Map<?, ?> raw
            ? String.valueOf(raw.get("code"))
            : "FAILED";
        enriched.put(
            "outcome",
            "INVALID_ARGUMENT".equals(code) ? "NOT_APPLIED" : "OUTCOME_UNKNOWN"
        );
        return enriched;
    }

    private Map<String, Object> invoke(final String name, final Map<String, Object> arguments) {
        final Map<String, Object> response = legacyTools.call(name, arguments);
        final Object structured = response.get("structuredContent");
        if (!(structured instanceof Map<?, ?> raw)) {
            throw new IllegalStateException("Internal MCP tool did not return structured content");
        }
        return stringMap(raw, "structuredContent");
    }

    private static Map<String, Object> skippedResult(final int index, final String operation) {
        return linked(
            entry("index", index),
            entry("operation", operation),
            entry("ok", false),
            entry("skipped", true),
            entry("result", linked(
                entry("ok", false),
                entry("outcome", "NOT_APPLIED"),
                entry("retryable", false),
                entry("error", linked(
                    entry("code", "SKIPPED"),
                    entry("message", "not run because a previous operation failed")
                ))
            ))
        );
    }

    private static Map<String, Object> failure(final String code, final String message) {
        return linked(
            entry("ok", false),
            entry("error", linked(entry("code", code), entry("message", message)))
        );
    }

    private static Map<String, Object> toolResult(
        final Map<String, Object> output,
        final boolean error
    ) {
        return linked(
            entry("content", List.of(linked(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", error)
        );
    }

    private static Map<String, Object> applyDefinition() {
        return linked(
            entry("name", APPLY),
            entry("title", "Apply model object changes"),
            entry("description", "Applies ordered create, rename, reparent, and delete operations to the active Cubism model. Each operation reports its own result; stopOnError defaults to false so completed operations are preserved and partial success is explicit."),
            entry("inputSchema", objectSchema(
                linked(
                    entry("operations", linked(
                        entry("type", "array"),
                        entry("description", "Ordered model-object operations."),
                        entry("minItems", 1),
                        entry("items", operationSchema())
                    )),
                    entry("stopOnError", linked(
                        entry("type", "boolean"),
                        entry("default", false),
                        entry("description", "Stop after the first failed operation.")
                    ))
                ),
                List.of("operations")
            )),
            entry("outputSchema", McpOutputSchemas.modelObjectBatch()),
            entry("annotations", Map.of(
                "readOnlyHint", false,
                "destructiveHint", true,
                "idempotentHint", false
            ))
        );
    }

    private static Map<String, Object> operationSchema() {
        return linked(entry("oneOf", List.of(
            createPartSchema(),
            createArtMeshSchema(false),
            createArtMeshSchema(true),
            createWarpDeformerSchema(),
            createRotationDeformerSchema(),
            objectSchema(
                linked(
                    entry("operation", enumSchema(List.of("rename"))),
                    entry("kind", modelObjectKindSchema()),
                    entry("id", stringSchema()),
                    entry("name", stringSchema())
                ),
                List.of("operation", "kind", "id", "name")
            ),
            objectSchema(
                linked(
                    entry("operation", enumSchema(List.of("reparent"))),
                    entry("kind", modelObjectKindSchema()),
                    entry("id", stringSchema()),
                    entry("parent", parentSchema(modelObjectKindSchema())),
                    entry("index", Map.of("type", "integer", "minimum", -1))
                ),
                List.of("operation", "kind", "id", "parent")
            ),
            objectSchema(
                linked(
                    entry("operation", enumSchema(List.of("delete"))),
                    entry("kind", modelObjectKindSchema()),
                    entry("id", stringSchema()),
                    entry("policy", enumSchema(List.of("reject_referenced", "cascade")))
                ),
                List.of("operation", "kind", "id")
            )
        )));
    }

    private static Map<String, Object> createPartSchema() {
        return objectSchema(
            linked(
                entry("operation", enumSchema(List.of("create"))),
                entry("kind", enumSchema(List.of("part"))),
                entry("name", stringSchema()),
                entry("parent", parentSchema(enumSchema(List.of("part"))))
            ),
            List.of("operation", "kind", "name")
        );
    }

    private static Map<String, Object> createArtMeshSchema(final boolean explicitGeometry) {
        final LinkedHashMap<String, Object> properties = linked(
            entry("operation", enumSchema(List.of("create"))),
            entry("kind", enumSchema(List.of("art_mesh"))),
            entry("name", stringSchema()),
            entry("parent", parentSchema(parentKindSchema()))
        );
        if (explicitGeometry) {
            properties.put("positions", pointArraySchema(3));
            properties.put("uvs", pointArraySchema(3));
            properties.put("triangleIndices", linked(
                entry("type", "array"),
                entry("minItems", 3),
                entry("items", Map.of("type", "integer", "minimum", 0))
            ));
        }
        return objectSchema(
            properties,
            explicitGeometry
                ? List.of("operation", "kind", "name", "positions", "uvs", "triangleIndices")
                : List.of("operation", "kind", "name")
        );
    }

    private static Map<String, Object> createWarpDeformerSchema() {
        return objectSchema(
            linked(
                entry("operation", enumSchema(List.of("create"))),
                entry("kind", enumSchema(List.of("warp_deformer"))),
                entry("name", stringSchema()),
                entry("parent", parentSchema(parentKindSchema())),
                entry("rows", Map.of("type", "integer", "minimum", 1, "maximum", 64)),
                entry("columns", Map.of("type", "integer", "minimum", 1, "maximum", 64)),
                entry("quadTransform", Map.of("type", "boolean")),
                entry("controlPoints", pointArraySchema(4)),
                entry("originX", Map.of("type", "number")),
                entry("originY", Map.of("type", "number")),
                entry("width", Map.of("type", "number", "exclusiveMinimum", 0)),
                entry("height", Map.of("type", "number", "exclusiveMinimum", 0))
            ),
            List.of("operation", "kind", "name")
        );
    }

    private static Map<String, Object> createRotationDeformerSchema() {
        return objectSchema(
            linked(
                entry("operation", enumSchema(List.of("create"))),
                entry("kind", enumSchema(List.of("rotation_deformer"))),
                entry("name", stringSchema()),
                entry("parent", parentSchema(parentKindSchema())),
                entry("originX", Map.of("type", "number")),
                entry("originY", Map.of("type", "number")),
                entry("angle", Map.of("type", "number")),
                entry("scale", Map.of("type", "number", "exclusiveMinimum", 0)),
                entry("reflectedX", Map.of("type", "boolean")),
                entry("reflectedY", Map.of("type", "boolean"))
            ),
            List.of("operation", "kind", "name")
        );
    }

    private static Map<String, Object> parentSchema(final Map<String, Object> kind) {
        return objectSchema(
            linked(entry("kind", kind), entry("id", stringSchema())),
            List.of("kind", "id")
        );
    }

    private static Map<String, Object> modelObjectKindSchema() {
        return enumSchema(List.of("part", "art_mesh", "warp_deformer", "rotation_deformer"));
    }

    private static Map<String, Object> parentKindSchema() {
        return enumSchema(List.of("part", "warp_deformer", "rotation_deformer"));
    }

    private static Map<String, Object> resource(
        final String uri,
        final String title,
        final String description
    ) {
        return linked(
            entry("uri", uri),
            entry("name", title.toLowerCase(java.util.Locale.ROOT).replace(' ', '-')),
            entry("title", title),
            entry("description", description),
            entry("mimeType", "application/json")
        );
    }

    private static Map<String, Object> pointArraySchema(final int minimumItems) {
        return linked(
            entry("type", "array"),
            entry("minItems", minimumItems),
            entry("items", objectSchema(linked(
                entry("x", Map.of("type", "number")),
                entry("y", Map.of("type", "number"))
            ), List.of("x", "y")))
        );
    }

    private static Map<String, Object> objectSchema(
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

    private static Map<String, Object> enumSchema(final List<String> values) {
        return Map.of("type", "string", "enum", values);
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string", "minLength", 1, "maxLength", 256);
    }

    private static boolean optionalBoolean(
        final Map<String, Object> values,
        final String key,
        final boolean defaultValue
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return defaultValue;
        if (!(values.get(key) instanceof Boolean value)) {
            throw new IllegalArgumentException(key + " must be a boolean");
        }
        return value;
    }

    private static String requiredString(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private static Map<String, Object> object(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return stringMap(raw, label);
    }

    private static List<Object> array(final Object value, final String label) {
        if (!(value instanceof List<?> values)) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        return new ArrayList<>(values);
    }

    private static Map<String, Object> stringMap(final Map<?, ?> raw, final String label) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            values.put(key, entry.getValue());
        }
        return values;
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final java.util.Set<String> names = java.util.Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException("unknown argument: " + key);
            }
        }
    }

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

}
