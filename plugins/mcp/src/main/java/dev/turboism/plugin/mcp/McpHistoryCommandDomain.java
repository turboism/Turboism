package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.command.EditorCanvasSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorCommandService;
import dev.turboism.sdk.cubism.command.EditorExternalAppSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorGridSettingsRequest;
import dev.turboism.sdk.cubism.command.EditorModelingStatisticsRequest;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;
import dev.turboism.sdk.cubism.command.EditorResizeModelRequest;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryMoveResult;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.Color;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Standalone MCP domain for active-document history and safe Editor commands.
 *
 * <p>This class deliberately owns only immutable MCP contracts and SDK translation. Transport,
 * session, and plugin-context composition remain outside this seam.</p>
 */
final class McpHistoryCommandDomain {

    static final String HISTORY_RESOURCE = "turboism://active/document/history";
    static final String EDITOR_COMMANDS_RESOURCE = "turboism://host/editor-commands";
    static final String HISTORY_MOVE = "turboism.history.move";
    static final String EDITOR_COMMANDS_EXECUTE = "turboism.editor_commands.execute";

    private static final String JSON_MIME_TYPE = "application/json";
    private static final int MAX_ARGUMENT_STRING_LENGTH = 256;

    private final CubismHistory history;
    private final EditorCommandService editorCommands;

    McpHistoryCommandDomain(final CubismHistory history, final EditorCommandService editorCommands) {
        this.history = Objects.requireNonNull(history, "history");
        this.editorCommands = Objects.requireNonNull(editorCommands, "editorCommands");
    }

    List<ResourceDefinition> resourceDefinitions() {
        return List.of(
            new ResourceDefinition(
                HISTORY_RESOURCE,
                "Active document history",
                "Current immutable Undo-history snapshot for the active Cubism document.",
                JSON_MIME_TYPE
            ),
            new ResourceDefinition(
                EDITOR_COMMANDS_RESOURCE,
                "Editor command capabilities",
                "Currently available direct Editor commands and the fixed typed request contracts.",
                JSON_MIME_TYPE
            )
        );
    }

    McpToolCatalog tools() {
        final List<Map<String, Object>> definitions = toolDefinitions().stream()
            .map(definition -> immutableMap(
                entry("name", definition.name()),
                entry("title", definition.title()),
                entry("description", definition.description()),
                entry("inputSchema", definition.inputSchema()),
                entry("outputSchema", HISTORY_MOVE.equals(definition.name())
                    ? McpOutputSchemas.historyMove() : McpOutputSchemas.editorCommand()),
                entry("annotations", definition.annotations())
            ))
            .toList();
        return new McpToolCatalog(definitions, (name, arguments) -> {
            final ToolCallResult result = call(name, arguments);
            return toolEnvelope(result);
        });
    }

    McpResourceCatalog resources() {
        final List<Map<String, Object>> definitions = resourceDefinitions().stream()
            .map(definition -> immutableMap(
                entry("uri", definition.uri()),
                entry("name", definition.name().toLowerCase(java.util.Locale.ROOT).replace(' ', '-')),
                entry("title", definition.name()),
                entry("description", definition.description()),
                entry("mimeType", definition.mimeType())
            ))
            .toList();
        return new McpResourceCatalog(definitions, List.of(), uri -> {
            final ResourceReadResult result;
            try {
                result = read(uri);
            } catch (IllegalArgumentException failure) {
                throw new McpResourceCatalog.ResourceNotFound(uri);
            }
            return List.of(immutableMap(
                entry("uri", result.uri()),
                entry("mimeType", result.mimeType()),
                entry("text", Json.stringify(result.content()))
            ));
        });
    }

    List<ToolDefinition> toolDefinitions() {
        return List.of(
            new ToolDefinition(
                HISTORY_MOVE,
                "Move active document history",
                "Moves the active document Undo cursor using required generation and revision preconditions. "
                    + "undo and redo are translated to a guarded move_to from a freshly read snapshot.",
                historyMoveSchema(),
                immutableMap(
                    entry("readOnlyHint", false),
                    entry("destructiveHint", false),
                    entry("idempotentHint", true)
                )
            ),
            new ToolDefinition(
                EDITOR_COMMANDS_EXECUTE,
                "Execute an Editor command",
                "Executes a currently available direct EditorCommand or one of five exact typed request "
                    + "records. File-command requests and raw path fields are intentionally unsupported.",
                editorCommandSchema(),
                immutableMap(
                    entry("readOnlyHint", false),
                    entry("destructiveHint", false),
                    entry("idempotentHint", false)
                )
            )
        );
    }

    ResourceReadResult read(final String uri) {
        final String requested = requireText(uri, "uri");
        return switch (requested) {
            case HISTORY_RESOURCE -> new ResourceReadResult(requested, JSON_MIME_TYPE, historySnapshot(history.snapshot()));
            case EDITOR_COMMANDS_RESOURCE -> new ResourceReadResult(
                requested,
                JSON_MIME_TYPE,
                immutableMap(
                    entry("availableDirectCommands", availableDirectCommandIds()),
                    entry("typedContracts", typedContracts())
                )
            );
            default -> throw new IllegalArgumentException("Unknown MCP resource: " + requested);
        };
    }

    ToolCallResult call(final String toolName, final Map<String, Object> arguments) {
        final String requested = requireText(toolName, "toolName");
        final Map<String, Object> checked = immutableArguments(arguments);
        try {
            return switch (requested) {
                case HISTORY_MOVE -> ToolCallResult.success(moveHistory(checked));
                case EDITOR_COMMANDS_EXECUTE -> ToolCallResult.success(executeEditorCommand(checked));
                default -> ToolCallResult.failure("INVALID_ARGUMENT", "Unknown MCP tool: " + requested);
            };
        } catch (InputException failure) {
            return ToolCallResult.failure("INVALID_ARGUMENT", failure.getMessage());
        } catch (RuntimeException failure) {
            return ToolCallResult.failure("FAILED", safeMessage(failure));
        }
    }

    private Map<String, Object> moveHistory(final Map<String, Object> arguments) {
        only(arguments, "operation", "expectedGeneration", "expectedRevision", "position", "steps");
        final String operation = requiredString(arguments, "operation");
        final long expectedGeneration = requiredNonNegativeLong(arguments, "expectedGeneration");
        final long expectedRevision = requiredNonNegativeLong(arguments, "expectedRevision");
        final HistorySnapshot current = history.snapshot();
        final HistoryMoveResult result;
        if (current.availability() != HistorySnapshot.Availability.AVAILABLE) {
            result = unavailable(current, "mcp.history.unavailable");
        } else if (current.generation() != expectedGeneration || current.revision() != expectedRevision) {
            result = stale(current);
        } else {
            result = switch (operation) {
                case "move_to" -> history.moveTo(
                    expectedGeneration,
                    expectedRevision,
                    requiredInteger(arguments, "position")
                );
                case "undo" -> history.moveTo(
                    expectedGeneration,
                    expectedRevision,
                    Math.max(0, current.position() - requiredPositiveInteger(arguments, "steps"))
                );
                case "redo" -> history.moveTo(
                    expectedGeneration,
                    expectedRevision,
                    Math.min(
                        current.entries().size(),
                        current.position() + requiredPositiveInteger(arguments, "steps")
                    )
                );
                default -> throw new InputException("operation must be move_to, undo, or redo");
            };
        }
        return historyMoveResult(result);
    }

    private Map<String, Object> executeEditorCommand(final Map<String, Object> arguments) {
        only(
            arguments,
            "kind",
            "commandId",
            "widthPixels",
            "heightPixels",
            "port",
            "allowRemoteConnections",
            "spacingPixels",
            "color",
            "autoUpdate",
            "percent"
        );
        final String kind = requiredString(arguments, "kind");
        final EditorCommandResult result = switch (kind) {
            case "direct" -> executeDirect(requiredString(arguments, "commandId"));
            case "canvas_settings" -> editorCommands.execute(new EditorCanvasSettingsRequest(
                requiredInteger(arguments, "widthPixels"),
                requiredInteger(arguments, "heightPixels")
            ));
            case "external_app_settings" -> editorCommands.execute(new EditorExternalAppSettingsRequest(
                requiredInteger(arguments, "port"),
                requiredBoolean(arguments, "allowRemoteConnections")
            ));
            case "grid_settings" -> editorCommands.execute(new EditorGridSettingsRequest(
                requiredInteger(arguments, "spacingPixels"),
                color(requiredObject(arguments, "color"))
            ));
            case "modeling_statistics" -> editorCommands.execute(new EditorModelingStatisticsRequest(
                requiredBoolean(arguments, "autoUpdate")
            ));
            case "resize_model" -> editorCommands.execute(new EditorResizeModelRequest(
                requiredInteger(arguments, "percent")
            ));
            default -> throw new InputException(
                "kind must be direct, canvas_settings, external_app_settings, grid_settings, "
                    + "modeling_statistics, or resize_model"
            );
        };
        return commandResult(result);
    }

    private EditorCommandResult executeDirect(final String commandId) {
        final EditorCommand command = directCommand(commandId);
        final Set<EditorCommand> available = editorCommands.available();
        // The resource is informational only. The availability check immediately before execution
        // closes the interval between discovery and invocation without trying to infer host state.
        if (available == null || !available.contains(command)) {
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.id());
        }
        return editorCommands.execute(command);
    }

    private static EditorCommand directCommand(final String commandId) {
        for (EditorCommand command : EditorCommand.values()) {
            if (command.id().equals(commandId)) return command;
        }
        throw new InputException("commandId is not a direct EditorCommand");
    }

    private List<String> availableDirectCommandIds() {
        final Set<EditorCommand> available = editorCommands.available();
        if (available == null || available.isEmpty()) return List.of();
        final ArrayList<String> ids = new ArrayList<>();
        for (EditorCommand command : EditorCommand.values()) {
            if (available.contains(command)) ids.add(command.id());
        }
        return List.copyOf(ids);
    }

    private static List<Map<String, Object>> typedContracts() {
        return List.of(
            typedContract("canvas_settings", "EditorCanvasSettingsRequest", "model.setting", "widthPixels", "heightPixels"),
            typedContract("external_app_settings", "EditorExternalAppSettingsRequest", "external.app.setting", "port", "allowRemoteConnections"),
            typedContract("grid_settings", "EditorGridSettingsRequest", "grid.setting", "spacingPixels", "color"),
            typedContract("modeling_statistics", "EditorModelingStatisticsRequest", "modeling.statistics", "autoUpdate"),
            typedContract("resize_model", "EditorResizeModelRequest", "resize.model.document", "percent")
        );
    }

    private static Map<String, Object> typedContract(
        final String kind,
        final String requestType,
        final String commandId,
        final String... fields
    ) {
        return immutableMap(
            entry("kind", kind),
            entry("requestType", requestType),
            entry("commandId", commandId),
            entry("fields", List.of(fields))
        );
    }

    private static HistoryMoveResult stale(final HistorySnapshot current) {
        return new HistoryMoveResult(
            HistoryMoveResult.Outcome.REJECTED_STALE,
            current,
            Optional.of("mcp.history.precondition.stale")
        );
    }

    private static HistoryMoveResult unavailable(final HistorySnapshot current, final String diagnosticId) {
        return new HistoryMoveResult(
            HistoryMoveResult.Outcome.UNAVAILABLE,
            current,
            Optional.of(diagnosticId)
        );
    }

    private static Map<String, Object> historyMoveResult(final HistoryMoveResult result) {
        return immutableMap(
            entry("ok", result.outcome() == HistoryMoveResult.Outcome.MOVED
                || result.outcome() == HistoryMoveResult.Outcome.NO_CHANGE),
            entry("outcome", result.outcome().name()),
            entry("snapshot", historySnapshot(result.snapshot())),
            entry("diagnosticId", result.diagnosticId().orElse(null))
        );
    }

    private static Map<String, Object> historySnapshot(final HistorySnapshot snapshot) {
        return immutableMap(
            entry("availability", snapshot.availability().name()),
            entry("generation", snapshot.generation()),
            entry("revision", snapshot.revision()),
            entry("position", snapshot.position()),
            entry("entries", snapshot.entries().stream().map(McpHistoryCommandDomain::historyEntry).toList()),
            entry("canUndo", snapshot.canUndo()),
            entry("canRedo", snapshot.canRedo())
        );
    }

    private static Map<String, Object> historyEntry(final HistoryEntry value) {
        return immutableMap(
            entry("index", value.index()),
            entry("label", value.label()),
            entry("significant", value.significant()),
            entry("detailLevel", value.detailLevel().name()),
            entry("action", value.action().map(McpHistoryCommandDomain::historyAction).orElse(null))
        );
    }

    private static Map<String, Object> historyAction(final HistoryAction value) {
        return immutableMap(
            entry("kind", value.kind().name()),
            entry("targetType", value.targetType()),
            entry("targetId", value.targetId()),
            entry("property", value.property()),
            entry("before", value.before().orElse(null)),
            entry("after", value.after().orElse(null)),
            entry("detailLevel", value.detailLevel().name())
        );
    }

    private static Map<String, Object> commandResult(final EditorCommandResult result) {
        return immutableMap(
            entry("ok", result.executed()),
            entry("status", result.status().name()),
            entry("commandId", result.commandId()),
            entry("executed", result.executed())
        );
    }

    private static Map<String, Object> toolEnvelope(final ToolCallResult result) {
        final boolean semanticError = result.isError()
            || Boolean.FALSE.equals(result.structuredContent().get("ok"));
        return immutableMap(
            entry("content", List.of(immutableMap(
                entry("type", "text"),
                entry("text", Json.stringify(result.structuredContent()))
            ))),
            entry("structuredContent", result.structuredContent()),
            entry("isError", semanticError)
        );
    }

    private static Color color(final Map<String, Object> values) {
        only(values, "red", "green", "blue", "alpha");
        return new Color(
            requiredFloat(values, "red"),
            requiredFloat(values, "green"),
            requiredFloat(values, "blue"),
            requiredFloat(values, "alpha")
        );
    }

    private static Map<String, Object> historyMoveSchema() {
        return objectSchema(
            immutableMap(
                entry("operation", enumSchema("The requested history operation.", List.of("move_to", "undo", "redo"))),
                entry("expectedGeneration", nonNegativeLongSchema("Generation obtained from the history resource.")),
                entry("expectedRevision", nonNegativeLongSchema("Revision obtained from the history resource.")),
                entry("position", integerSchema("Target cursor position for move_to.", 0, Integer.MAX_VALUE)),
                entry("steps", integerSchema("Positive number of entries for undo or redo.", 1, Integer.MAX_VALUE))
            ),
            List.of("operation", "expectedGeneration", "expectedRevision")
        );
    }

    private static Map<String, Object> editorCommandSchema() {
        return immutableMap(
            entry("type", "object"),
            entry("oneOf", List.of(
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("direct")),
                        entry("commandId", stringSchema(
                            "A direct command currently listed by turboism://host/editor-commands.", 1, MAX_ARGUMENT_STRING_LENGTH
                        ))
                    ),
                    List.of("kind", "commandId")
                ),
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("canvas_settings")),
                        entry("widthPixels", integerSchema("Canvas width in pixels.", 16, 30_000)),
                        entry("heightPixels", integerSchema("Canvas height in pixels.", 16, 30_000))
                    ),
                    List.of("kind", "widthPixels", "heightPixels")
                ),
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("external_app_settings")),
                        entry("port", integerSchema("External-application port.", 1, 65_535)),
                        entry("allowRemoteConnections", booleanSchema())
                    ),
                    List.of("kind", "port", "allowRemoteConnections")
                ),
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("grid_settings")),
                        entry("spacingPixels", integerSchema("Grid spacing in pixels.", 1, 30_000)),
                        entry("color", objectSchema(
                            immutableMap(
                                entry("red", numberSchema()),
                                entry("green", numberSchema()),
                                entry("blue", numberSchema()),
                                entry("alpha", constantSchema(1))
                            ),
                            List.of("red", "green", "blue", "alpha")
                        ))
                    ),
                    List.of("kind", "spacingPixels", "color")
                ),
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("modeling_statistics")),
                        entry("autoUpdate", booleanSchema())
                    ),
                    List.of("kind", "autoUpdate")
                ),
                objectSchema(
                    immutableMap(
                        entry("kind", constantSchema("resize_model")),
                        entry("percent", integerSchema("Model scale percentage.", 1, 5_000))
                    ),
                    List.of("kind", "percent")
                )
            ))
        );
    }

    private static Map<String, Object> objectSchema(
        final Map<String, Object> properties,
        final List<String> required
    ) {
        return immutableMap(
            entry("type", "object"),
            entry("properties", properties),
            entry("required", List.copyOf(required)),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> enumSchema(final String description, final List<String> values) {
        return immutableMap(
            entry("type", "string"),
            entry("description", description),
            entry("enum", List.copyOf(values))
        );
    }

    private static Map<String, Object> constantSchema(final Object value) {
        return immutableMap(entry("const", value));
    }

    private static Map<String, Object> stringSchema(
        final String description,
        final int minimum,
        final int maximum
    ) {
        return immutableMap(
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
        return immutableMap(
            entry("type", "integer"),
            entry("description", description),
            entry("minimum", minimum),
            entry("maximum", maximum)
        );
    }

    private static Map<String, Object> nonNegativeLongSchema(final String description) {
        return immutableMap(
            entry("type", "integer"),
            entry("description", description),
            entry("minimum", 0)
        );
    }

    private static Map<String, Object> booleanSchema() {
        return immutableMap(entry("type", "boolean"));
    }

    private static Map<String, Object> numberSchema() {
        return immutableMap(entry("type", "number"));
    }

    private static Map<String, Object> immutableArguments(final Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : arguments.entrySet()) {
            if (entry.getKey() == null) throw new InputException("arguments contains a null key");
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> requiredObject(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof Map<?, ?> raw)) throw new InputException(key + " must be an object");
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String name)) {
                throw new InputException(key + " contains a non-string key");
            }
            result.put(name, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static String requiredString(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof String text)) throw new InputException(key + " must be a string");
        final String normalized = text.strip();
        if (normalized.isEmpty()) throw new InputException(key + " must not be blank");
        if (normalized.length() > MAX_ARGUMENT_STRING_LENGTH) {
            throw new InputException(key + " must not exceed " + MAX_ARGUMENT_STRING_LENGTH + " characters");
        }
        return normalized;
    }

    private static boolean requiredBoolean(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof Boolean result)) throw new InputException(key + " must be a boolean");
        return result;
    }

    private static int requiredInteger(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key)) throw new InputException(key + " is required");
        return integer(values.get(key), key);
    }

    private static int requiredPositiveInteger(final Map<String, Object> values, final String key) {
        final int value = requiredInteger(values, key);
        if (value < 1) throw new InputException(key + " must be at least 1");
        return value;
    }

    private static long requiredNonNegativeLong(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key)) throw new InputException(key + " is required");
        final Object value = values.get(key);
        try {
            final long result;
            if (value instanceof BigDecimal decimal) {
                result = decimal.longValueExact();
            } else if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
                result = ((Number) value).longValue();
            } else {
                throw new InputException(key + " must be an integer");
            }
            if (result < 0) throw new InputException(key + " must not be negative");
            return result;
        } catch (ArithmeticException failure) {
            throw new InputException(key + " must be a 64-bit integer");
        }
    }

    private static int integer(final Object value, final String label) {
        try {
            if (value instanceof BigDecimal decimal) return decimal.intValueExact();
            if (value instanceof Byte || value instanceof Short || value instanceof Integer) {
                return ((Number) value).intValue();
            }
            if (value instanceof Long number) return Math.toIntExact(number);
        } catch (ArithmeticException failure) {
            throw new InputException(label + " must be a 32-bit integer");
        }
        throw new InputException(label + " must be an integer");
    }

    private static float requiredFloat(final Map<String, Object> values, final String key) {
        if (!values.containsKey(key)) throw new InputException(key + " is required");
        final Object value = values.get(key);
        if (!(value instanceof Number number)) throw new InputException(key + " must be a number");
        final float result = number.floatValue();
        if (!Float.isFinite(result)) throw new InputException(key + " must be finite");
        return result;
    }

    private static void only(final Map<String, Object> values, final String... allowed) {
        final Set<String> names = Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) throw new InputException("unknown argument: " + key);
        }
    }

    private static String requireText(final String value, final String name) {
        final String result = Objects.requireNonNull(value, name).strip();
        if (result.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return result;
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }

    @SafeVarargs
    private static Map<String, Object> immutableMap(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            result.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    record ResourceDefinition(String uri, String name, String description, String mimeType) {
        ResourceDefinition {
            uri = requireText(uri, "uri");
            name = requireText(name, "name");
            description = requireText(description, "description");
            mimeType = requireText(mimeType, "mimeType");
        }
    }

    record ToolDefinition(
        String name,
        String title,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> annotations
    ) {
        ToolDefinition {
            name = requireText(name, "name");
            title = requireText(title, "title");
            description = requireText(description, "description");
            inputSchema = immutableArguments(inputSchema);
            annotations = immutableArguments(annotations);
        }
    }

    record ResourceReadResult(String uri, String mimeType, Map<String, Object> content) {
        ResourceReadResult {
            uri = requireText(uri, "uri");
            mimeType = requireText(mimeType, "mimeType");
            content = immutableArguments(content);
        }
    }

    record ToolCallResult(boolean isError, Map<String, Object> structuredContent) {
        ToolCallResult {
            structuredContent = immutableArguments(structuredContent);
        }

        static ToolCallResult success(final Map<String, Object> output) {
            return new ToolCallResult(false, output);
        }

        static ToolCallResult failure(final String code, final String message) {
            return new ToolCallResult(true, immutableMap(
                entry("ok", false),
                entry("error", immutableMap(entry("code", code), entry("message", message)))
            ));
        }
    }

    private static final class InputException extends RuntimeException {
        private InputException(final String message) {
            super(message);
        }
    }
}
