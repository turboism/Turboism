package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** MCP tool catalog and strict argument-to-SDK translation. */
final class McpTools {

    static final String LIST = "turboism_model_objects_list";
    static final String RENAME = "turboism_model_object_rename";
    static final String REPARENT = "turboism_model_object_reparent";
    static final String CREATE = "turboism_model_object_create";
    static final String DELETE = "turboism_model_object_delete";

    private final ModelObjectService service;
    private final PluginLogger logger;
    private final UiScheduler uiScheduler;

    McpTools(
        final ModelObjectService service,
        final PluginLogger logger,
        final UiScheduler uiScheduler
    ) {
        this.service = Objects.requireNonNull(service, "service");
        this.logger = Objects.requireNonNull(logger, "logger");
        this.uiScheduler = Objects.requireNonNull(uiScheduler, "uiScheduler");
    }

    List<Map<String, Object>> definitions() {
        return List.of(
            tool(
                LIST,
                "List Cubism model objects",
                "Lists Parts, ArtMeshes, Warp Deformers, and Rotation Deformers in the active modeling document.",
                objectSchema(
                    properties(entry("kind", kindSchema("Optional object-family filter."))),
                    List.of()
                ),
                Map.of("readOnlyHint", true, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                RENAME,
                "Rename a Cubism model object",
                "Renames one object by typed kind and stable Cubism ID through the Turboism authoring API.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("name", stringSchema("New display name.", 1, 256))
                    ),
                    List.of("kind", "id", "name")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                REPARENT,
                "Reparent a Cubism model object",
                "Moves an existing object under a new parent. Part targets accept only Part parents; ArtMesh and Deformer targets accept Part or Deformer parents. Index -1 appends to the parent.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family to move.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("parent", objectSchema(
                            properties(
                                entry("kind", kindSchema("New parent object family.")),
                                entry("id", stringSchema("New parent object ID.", 1, 256))
                            ),
                            List.of("kind", "id")
                        )),
                        entry("index", integerSchema(
                            "Sibling index under the new parent; -1 appends.",
                            -1,
                            Integer.MAX_VALUE
                        ))
                    ),
                    List.of("kind", "id", "parent")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", true)
            ),
            tool(
                CREATE,
                "Create a Cubism model object",
                "Creates a Part, ArtMesh, Warp Deformer, or Rotation Deformer. ArtMesh defaults to a unit triangle; Warp defaults to a 2x2 unit grid; Rotation defaults to origin (0,0), angle 0, scale 1.",
                createSchema(),
                Map.of("readOnlyHint", false, "destructiveHint", false, "idempotentHint", false)
            ),
            tool(
                DELETE,
                "Delete a Cubism model object",
                "Deletes one object by kind and ID. The default policy rejects referenced objects; cascade must be explicit.",
                objectSchema(
                    properties(
                        entry("kind", kindSchema("Object family.")),
                        entry("id", stringSchema("Stable Cubism object ID.", 1, 256)),
                        entry("policy", enumSchema(
                            "Reference handling policy.",
                            List.of("reject_referenced", "cascade")
                        ))
                    ),
                    List.of("kind", "id")
                ),
                Map.of("readOnlyHint", false, "destructiveHint", true, "idempotentHint", true)
            )
        );
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        final String toolName = Objects.requireNonNull(name, "name");
        final Map<String, Object> checkedArguments = new LinkedHashMap<>(
            Objects.requireNonNull(arguments, "arguments")
        );
        try {
            final Map<String, Object> output = switch (toolName) {
                case LIST -> list(checkedArguments);
                case RENAME -> rename(checkedArguments);
                case REPARENT -> reparent(checkedArguments);
                case CREATE -> create(checkedArguments);
                case DELETE -> delete(checkedArguments);
                default -> throw new ToolInputException("Unknown MCP tool: " + toolName);
            };
            return toolResult(output, false);
        } catch (ToolInputException failure) {
            return toolFailure("INVALID_ARGUMENT", failure.getMessage(), failure, false);
        } catch (ModelObjectOperationException failure) {
            return toolFailure(failure.code().name(), safeMessage(failure), failure, false);
        } catch (SecurityException failure) {
            return toolFailure("PERMISSION_DENIED", safeMessage(failure), failure, false);
        } catch (RuntimeException failure) {
            return toolFailure("FAILED", safeMessage(failure), failure, true);
        }
    }

    private Map<String, Object> list(final Map<String, Object> arguments) {
        only(arguments, "kind");
        final Optional<ModelObjectKind> filter = optionalString(arguments, "kind")
            .map(McpTools::kind);
        final List<Map<String, Object>> objects = onUi(service::list).stream()
            .filter(value -> filter.isEmpty() || value.reference().kind() == filter.orElseThrow())
            .map(McpTools::descriptor)
            .toList();
        return linked(
            entry("ok", true),
            entry("count", objects.size()),
            entry("objects", objects)
        );
    }

    private Map<String, Object> rename(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "name");
        final ModelObjectReference target = reference(arguments);
        final String name = requiredString(arguments, "name", 256);
        return linked(
            entry("ok", true),
            entry("object", descriptor(onUi(() -> service.rename(target, name))))
        );
    }

    private Map<String, Object> reparent(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "parent", "index");
        final ModelObjectReference target = reference(arguments);
        final Object parentValue = arguments.get("parent");
        if (parentValue == null) {
            throw new ToolInputException("parent is required");
        }
        final ModelObjectReference parent = reference(object(parentValue, "parent"));
        final int index = optionalInteger(arguments, "index").orElse(-1);
        if (index < -1) {
            throw new ToolInputException("index must be -1 or greater");
        }
        return linked(
            entry("ok", true),
            entry("object", descriptor(onUi(() -> service.reparent(target, parent, index))))
        );
    }

    private Map<String, Object> create(final Map<String, Object> arguments) {
        only(
            arguments,
            "kind", "name", "parent", "positions", "uvs", "triangleIndices",
            "rows", "columns", "quadTransform", "controlPoints", "originX", "originY",
            "width", "height", "angle", "scale", "reflectedX", "reflectedY"
        );
        final ModelObjectKind kind = kind(requiredString(arguments, "kind", 64));
        final String name = requiredString(arguments, "name", 256);
        final Optional<ModelObjectReference> parent = optionalParent(arguments);
        final ModelObjectCreateRequest request = switch (kind) {
            case PART -> new ModelObjectCreateRequest.Part(name, parent);
            case ART_MESH -> new ModelObjectCreateRequest.ArtMesh(
                name,
                parent,
                artMeshGeometry(arguments)
            );
            case WARP_DEFORMER -> new ModelObjectCreateRequest.WarpDeformer(
                name,
                parent,
                warpGrid(arguments)
            );
            case ROTATION_DEFORMER -> new ModelObjectCreateRequest.RotationDeformer(
                name,
                parent,
                rotationForm(arguments)
            );
        };
        return linked(
            entry("ok", true),
            entry("object", descriptor(onUi(() -> service.create(request))))
        );
    }

    private Map<String, Object> delete(final Map<String, Object> arguments) {
        only(arguments, "kind", "id", "policy");
        final ModelObjectReference target = reference(arguments);
        final ModelObjectDeletePolicy policy = optionalString(arguments, "policy")
            .map(value -> switch (value) {
                case "reject_referenced" -> ModelObjectDeletePolicy.REJECT_REFERENCED;
                case "cascade" -> ModelObjectDeletePolicy.CASCADE;
                default -> throw new ToolInputException(
                    "policy must be reject_referenced or cascade"
                );
            })
            .orElse(ModelObjectDeletePolicy.REJECT_REFERENCED);
        onUi(() -> {
            service.delete(target, policy);
            return null;
        });
        return linked(
            entry("ok", true),
            entry("deleted", true),
            entry("target", reference(target)),
            entry("policy", policy == ModelObjectDeletePolicy.CASCADE
                ? "cascade" : "reject_referenced")
        );
    }

    private Map<String, Object> toolFailure(
        final String code,
        final String message,
        final RuntimeException failure,
        final boolean logStack
    ) {
        if (logStack) {
            logger.error("MCP tool execution failed: " + code + ": " + message, failure);
        } else {
            logger.warn("MCP tool rejected: " + code + ": " + message);
        }
        return toolResult(linked(
            entry("ok", false),
            entry("error", linked(entry("code", code), entry("message", message)))
        ), true);
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

    private <T> T onUi(final Supplier<T> invocation) {
        final CompletableFuture<T> result = new CompletableFuture<>();
        final Registration registration = uiScheduler.runOnUiThread(() -> {
            try {
                result.complete(invocation.get());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            return result.get(30, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.FAILED,
                "MCP model-object operation was interrupted",
                failure
            );
        } catch (TimeoutException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.UNAVAILABLE,
                "MCP model-object operation did not complete on the UI thread",
                failure
            );
        } catch (ExecutionException failure) {
            final Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.FAILED,
                "MCP model-object operation failed",
                cause
            );
        } finally {
            registration.close();
        }
    }

    private static ArtMeshGeometry artMeshGeometry(final Map<String, Object> arguments) {
        final Optional<Object> positionsValue = Optional.ofNullable(arguments.get("positions"));
        final Optional<Object> uvsValue = Optional.ofNullable(arguments.get("uvs"));
        final Optional<Object> indicesValue = Optional.ofNullable(arguments.get("triangleIndices"));
        if (positionsValue.isEmpty() && uvsValue.isEmpty() && indicesValue.isEmpty()) {
            return new ArtMeshGeometry(
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(new Point2(0.0f, 0.0f), new Point2(1.0f, 0.0f), new Point2(0.0f, 1.0f)),
                List.of(0, 1, 2)
            );
        }
        if (positionsValue.isEmpty() || uvsValue.isEmpty() || indicesValue.isEmpty()) {
            throw new ToolInputException(
                "positions, uvs, and triangleIndices must be supplied together"
            );
        }
        return new ArtMeshGeometry(
            points(positionsValue.orElseThrow(), "positions"),
            points(uvsValue.orElseThrow(), "uvs"),
            integers(indicesValue.orElseThrow(), "triangleIndices")
        );
    }

    private static WarpGrid warpGrid(final Map<String, Object> arguments) {
        final int rows = optionalInteger(arguments, "rows").orElse(2);
        final int columns = optionalInteger(arguments, "columns").orElse(2);
        if (rows < 1 || rows > 64 || columns < 1 || columns > 64) {
            throw new ToolInputException("rows and columns must be between 1 and 64");
        }
        final boolean quadTransform = optionalBoolean(arguments, "quadTransform").orElse(false);
        final List<Point2> controlPoints;
        if (arguments.containsKey("controlPoints")) {
            controlPoints = points(arguments.get("controlPoints"), "controlPoints");
        } else {
            final float originX = optionalFloat(arguments, "originX").orElse(0.0f);
            final float originY = optionalFloat(arguments, "originY").orElse(0.0f);
            final float width = optionalFloat(arguments, "width").orElse(1.0f);
            final float height = optionalFloat(arguments, "height").orElse(1.0f);
            if (!(width > 0.0f) || !(height > 0.0f)) {
                throw new ToolInputException("width and height must be positive");
            }
            final ArrayList<Point2> generated = new ArrayList<>((rows + 1) * (columns + 1));
            for (int row = 0; row <= rows; row++) {
                final float y = originY + height * row / rows;
                for (int column = 0; column <= columns; column++) {
                    final float x = originX + width * column / columns;
                    generated.add(new Point2(x, y));
                }
            }
            controlPoints = List.copyOf(generated);
        }
        return new WarpGrid(rows, columns, quadTransform, controlPoints);
    }

    private static RotationDeformerForm rotationForm(final Map<String, Object> arguments) {
        return new RotationDeformerForm(
            optionalFloat(arguments, "angle").orElse(0.0f),
            optionalFloat(arguments, "originX").orElse(0.0f),
            optionalFloat(arguments, "originY").orElse(0.0f),
            optionalFloat(arguments, "scale").orElse(1.0f),
            optionalBoolean(arguments, "reflectedX").orElse(false),
            optionalBoolean(arguments, "reflectedY").orElse(false)
        );
    }

    private static Optional<ModelObjectReference> optionalParent(
        final Map<String, Object> arguments
    ) {
        final Object value = arguments.get("parent");
        if (value == null) return Optional.empty();
        return Optional.of(reference(object(value, "parent")));
    }

    private static ModelObjectReference reference(final Map<String, Object> values) {
        return new ModelObjectReference(
            kind(requiredString(values, "kind", 64)),
            requiredString(values, "id", 256)
        );
    }

    private static ModelObjectKind kind(final String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "part" -> ModelObjectKind.PART;
            case "art_mesh", "artmesh" -> ModelObjectKind.ART_MESH;
            case "warp_deformer", "warp" -> ModelObjectKind.WARP_DEFORMER;
            case "rotation_deformer", "rotation" -> ModelObjectKind.ROTATION_DEFORMER;
            default -> throw new ToolInputException(
                "kind must be part, art_mesh, warp_deformer, or rotation_deformer"
            );
        };
    }

    private static Map<String, Object> descriptor(final ModelObjectDescriptor value) {
        return linked(
            entry("kind", wire(value.reference().kind())),
            entry("id", value.reference().id()),
            entry("name", value.name()),
            entry("parent", value.parent().map(McpTools::reference).orElse(null))
        );
    }

    private static Map<String, Object> reference(final ModelObjectReference value) {
        return linked(entry("kind", wire(value.kind())), entry("id", value.id()));
    }

    private static String wire(final ModelObjectKind kind) {
        return switch (kind) {
            case PART -> "part";
            case ART_MESH -> "art_mesh";
            case WARP_DEFORMER -> "warp_deformer";
            case ROTATION_DEFORMER -> "rotation_deformer";
        };
    }

    private static List<Point2> points(final Object value, final String label) {
        final List<Object> values = array(value, label);
        final ArrayList<Point2> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            final Map<String, Object> point = object(values.get(index), label + "[" + index + "]");
            only(point, "x", "y");
            result.add(new Point2(
                requiredFloat(point, "x"),
                requiredFloat(point, "y")
            ));
        }
        return List.copyOf(result);
    }

    private static List<Integer> integers(final Object value, final String label) {
        final List<Object> values = array(value, label);
        final ArrayList<Integer> result = new ArrayList<>(values.size());
        for (int index = 0; index < values.size(); index++) {
            result.add(integer(values.get(index), label + "[" + index + "]"));
        }
        return List.copyOf(result);
    }

    private static String requiredString(
        final Map<String, Object> values,
        final String key,
        final int maxLength
    ) {
        final Object value = values.get(key);
        if (!(value instanceof String text)) {
            throw new ToolInputException(key + " must be a string");
        }
        final String normalized = text.strip();
        if (normalized.isEmpty()) throw new ToolInputException(key + " must not be blank");
        if (normalized.length() > maxLength) {
            throw new ToolInputException(key + " must not exceed " + maxLength + " characters");
        }
        return normalized;
    }

    private static Optional<String> optionalString(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(requiredString(values, key, 256));
    }

    private static float requiredFloat(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key)) throw new ToolInputException(key + " is required");
        return floating(values.get(key), key);
    }

    private static Optional<Float> optionalFloat(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(floating(values.get(key), key));
    }

    private static float floating(final Object value, final String label) {
        if (!(value instanceof Number number)) {
            throw new ToolInputException(label + " must be a number");
        }
        final float result = number.floatValue();
        if (!Float.isFinite(result)) throw new ToolInputException(label + " must be finite");
        return result;
    }

    private static Optional<Integer> optionalInteger(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        return Optional.of(integer(values.get(key), key));
    }

    private static int integer(final Object value, final String label) {
        try {
            if (value instanceof BigDecimal decimal) return decimal.intValueExact();
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).intValue();
            }
            if (value instanceof Long number) return Math.toIntExact(number);
        } catch (ArithmeticException failure) {
            throw new ToolInputException(label + " must be a 32-bit integer");
        }
        throw new ToolInputException(label + " must be an integer");
    }

    private static Optional<Boolean> optionalBoolean(
        final Map<String, Object> values,
        final String key
    ) {
        if (!values.containsKey(key) || values.get(key) == null) return Optional.empty();
        final Object value = values.get(key);
        if (!(value instanceof Boolean flag)) {
            throw new ToolInputException(key + " must be a boolean");
        }
        return Optional.of(flag);
    }

    private static Map<String, Object> object(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new ToolInputException(label + " must be an object");
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new ToolInputException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }

    private static List<Object> array(final Object value, final String label) {
        if (!(value instanceof List<?> values)) {
            throw new ToolInputException(label + " must be an array");
        }
        return new ArrayList<>(values);
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final java.util.Set<String> names = java.util.Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw new ToolInputException("unknown argument: " + key);
        }
    }

    private static Map<String, Object> tool(
        final String name,
        final String title,
        final String description,
        final Map<String, Object> inputSchema,
        final Map<String, Object> annotations
    ) {
        return linked(
            entry("name", name),
            entry("title", title),
            entry("description", description),
            entry("inputSchema", inputSchema),
            entry("annotations", annotations)
        );
    }

    private static Map<String, Object> createSchema() {
        return objectSchema(
            properties(
                entry("kind", kindSchema("Object family to create.")),
                entry("name", stringSchema("Display name.", 1, 256)),
                entry("parent", objectSchema(
                    properties(
                        entry("kind", kindSchema("Parent object family.")),
                        entry("id", stringSchema("Parent object ID.", 1, 256))
                    ),
                    List.of("kind", "id")
                )),
                entry("positions", pointArraySchema("ArtMesh vertex positions.")),
                entry("uvs", pointArraySchema("ArtMesh UV coordinates.")),
                entry("triangleIndices", linked(
                    entry("type", "array"),
                    entry("description", "ArtMesh triangle vertex indices."),
                    entry("items", Map.of("type", "integer", "minimum", 0))
                )),
                entry("rows", integerSchema("Warp grid rows.", 1, 64)),
                entry("columns", integerSchema("Warp grid columns.", 1, 64)),
                entry("quadTransform", Map.of("type", "boolean")),
                entry("controlPoints", pointArraySchema("Explicit Warp control points.")),
                entry("originX", Map.of("type", "number")),
                entry("originY", Map.of("type", "number")),
                entry("width", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("height", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("angle", Map.of("type", "number")),
                entry("scale", linked(entry("type", "number"), entry("exclusiveMinimum", 0))),
                entry("reflectedX", Map.of("type", "boolean")),
                entry("reflectedY", Map.of("type", "boolean"))
            ),
            List.of("kind", "name")
        );
    }

    private static Map<String, Object> pointArraySchema(final String description) {
        return linked(
            entry("type", "array"),
            entry("description", description),
            entry("items", objectSchema(
                properties(
                    entry("x", Map.of("type", "number")),
                    entry("y", Map.of("type", "number"))
                ),
                List.of("x", "y")
            ))
        );
    }

    private static Map<String, Object> kindSchema(final String description) {
        return enumSchema(
            description,
            List.of("part", "art_mesh", "warp_deformer", "rotation_deformer")
        );
    }

    private static Map<String, Object> enumSchema(
        final String description,
        final List<String> values
    ) {
        return linked(
            entry("type", "string"),
            entry("description", description),
            entry("enum", values)
        );
    }

    private static Map<String, Object> stringSchema(
        final String description,
        final int minimum,
        final int maximum
    ) {
        return linked(
            entry("type", "string"),
            entry("description", description),
            entry("minLength", minimum),
            entry("maxLength", maximum)
        );
    }

    private static Map<String, Object> integerSchema(
        final String description,
        final int minimum,
        final int maximum
    ) {
        return linked(
            entry("type", "integer"),
            entry("description", description),
            entry("minimum", minimum),
            entry("maximum", maximum)
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

    @SafeVarargs
    private static Map<String, Object> properties(
        final Map.Entry<String, Object>... entries
    ) {
        return linked(entries);
    }

    @SafeVarargs
    private static Map<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    private static final class ToolInputException extends RuntimeException {
        private ToolInputException(final String message) {
            super(message);
        }
    }
}
