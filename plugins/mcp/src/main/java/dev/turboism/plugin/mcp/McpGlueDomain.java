package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.model.Glues;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** MCP read/write projection of the exact-version Cubism Glue SDK surface. */
final class McpGlueDomain {

    static final String GLUES_READ = "turboism.glues.read";
    static final String GLUES_WRITE = "turboism.glues.write";
    static final String GLUE_CAPABILITY_ID = "cubism.editor-model.glue-inspector.write";
    static final List<String> SUPPORTED_VERSIONS = List.of("5.2.03", "5.3.02", "5.3.03");

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 200;

    private final CubismFacade cubism;
    private final McpExecutionBridge execution;

    McpGlueDomain(final CubismFacade cubism, final McpExecutionBridge execution) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
        this.execution = Objects.requireNonNull(execution, "execution");
    }

    McpToolCatalog tools() {
        return McpToolCatalog.of(List.of(
            McpRegisteredTool.typed(
                readDefinition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                true,
                readVersionSupport(),
                execution,
                this::read
            ),
            McpRegisteredTool.typed(
                writeDefinition(),
                McpOperationEffect.UNDOABLE_WRITE,
                McpExecutionAffinity.UI_THREAD,
                true,
                writeVersionSupport(),
                execution,
                this::write
            )
        ));
    }

    private static McpVersionSupport readVersionSupport() {
        return McpVersionSupport.exact(
            GLUE_CAPABILITY_ID,
            SUPPORTED_VERSIONS,
            List.of(
                McpVersionSupport.OperationSupport.available(
                    "list",
                    McpOperationEffect.READ,
                    true,
                    McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                    SUPPORTED_VERSIONS,
                    "Exact-version Editor Glue collection projection."
                ),
                McpVersionSupport.OperationSupport.available(
                    "get",
                    McpOperationEffect.READ,
                    true,
                    McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                    SUPPORTED_VERSIONS,
                    "Exact-version Editor Glue lookup and state projection."
                )
            )
        );
    }

    private static McpVersionSupport writeVersionSupport() {
        final ArrayList<McpVersionSupport.OperationSupport> operations = new ArrayList<>();
        for (String operation : List.of(
            "set_name",
            "set_id",
            "set_intensity",
            "set_drawable_a",
            "set_drawable_b"
        )) {
            operations.add(McpVersionSupport.OperationSupport.available(
                operation,
                McpOperationEffect.UNDOABLE_WRITE,
                true,
                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                SUPPORTED_VERSIONS,
                "Verified selector contracts plus Runtime native Undo and transaction tests; "
                    + "exact-host readiness is tracked separately."
            ));
        }
        for (String operation : List.of("create", "delete")) {
            operations.add(McpVersionSupport.OperationSupport.unavailable(
                operation,
                McpOperationEffect.UNDOABLE_WRITE,
                "No verified Editor provider exists for Glue " + operation + "."
            ));
        }
        return McpVersionSupport.exact(
            GLUE_CAPABILITY_ID,
            SUPPORTED_VERSIONS,
            List.copyOf(operations)
        );
    }

    private Map<String, Object> read(final Map<String, Object> arguments) {
        String operation = "unknown";
        try {
            final Map<String, Object> checked = copyObject(arguments, "arguments");
            operation = requiredString(checked, "operation");
            return envelope(switch (operation) {
                case "list" -> list(checked);
                case "get" -> get(checked);
                default -> readFailure(
                    operation,
                    "AVAILABLE",
                    "INVALID_ARGUMENT",
                    "operation must be list or get",
                    safeStateToken()
                );
            });
        } catch (NoActiveModel failure) {
            return envelope(readFailure(
                operation,
                "UNAVAILABLE",
                "NO_ACTIVE_MODEL",
                "No active Cubism model document",
                null
            ));
        } catch (java.util.NoSuchElementException failure) {
            final Map<String, Object> state = safeStateToken();
            return envelope(readFailure(
                operation,
                state == null ? "UNAVAILABLE" : "AVAILABLE",
                "GLUE_NOT_FOUND",
                "The requested Glue is absent from the active model",
                state
            ));
        } catch (dev.turboism.sdk.permission.CubismPermissionException | SecurityException failure) {
            return envelope(readFailure(
                operation,
                "UNAVAILABLE",
                "PERMISSION_DENIED",
                "The MCP plugin is not permitted to read the active Cubism model",
                null
            ));
        } catch (UnsupportedOperationException failure) {
            return envelope(readFailure(
                operation,
                "UNAVAILABLE",
                "NO_VERIFIED_PROVIDER",
                "The active Cubism version has no admitted Glue reader",
                null
            ));
        } catch (IllegalArgumentException failure) {
            return envelope(readFailure(
                operation,
                safeStateToken() == null ? "UNAVAILABLE" : "AVAILABLE",
                "INVALID_ARGUMENT",
                safeMessage(failure),
                safeStateToken()
            ));
        } catch (RuntimeException failure) {
            return envelope(readFailure(
                operation,
                "UNAVAILABLE",
                errorCode(failure),
                safeMessage(failure),
                null
            ));
        }
    }

    private Map<String, Object> list(final Map<String, Object> arguments) {
        only(arguments, "operation", "offset", "limit");
        final int offset = optionalInteger(arguments, "offset", 0, 0, Integer.MAX_VALUE);
        final int limit = optionalInteger(arguments, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT);
        final Context context = context();
        final List<Glue> all = context.glues().all();
        final int from = Math.min(offset, all.size());
        final int to = Math.min(all.size(), from + limit);
        final ArrayList<Map<String, Object>> items = new ArrayList<>(to - from);
        for (int index = from; index < to; index++) {
            items.add(glue(all.get(index)));
        }
        final Integer nextOffset = to < all.size() ? to : null;
        return success(
            "list",
            context,
            linked(
                entry("items", List.copyOf(items)),
                entry("offset", offset),
                entry("limit", limit),
                entry("total", all.size()),
                entry("nextOffset", nextOffset)
            )
        );
    }

    private Map<String, Object> get(final Map<String, Object> arguments) {
        only(arguments, "operation", "id");
        final Context context = context();
        final Glue value = context.glues().find(
            new GlueId(requiredString(arguments, "id"))
        );
        return success("get", context, glue(value));
    }

    private Map<String, Object> write(final Map<String, Object> arguments) {
        final Map<String, Object> checked = copyObject(arguments, "arguments");
        final String operation = requiredString(checked, "operation");
        try {
            if ("create".equals(operation) || "delete".equals(operation)) {
                return envelope(writeFailure(
                    operation,
                    "UNAVAILABLE",
                    "NO_VERIFIED_PROVIDER",
                    "Glue create/delete has no verified Editor provider",
                    null,
                    null
                ));
            }
            return envelope(switch (operation) {
                case "set_name" -> setName(checked);
                case "set_id" -> setId(checked);
                case "set_intensity" -> setIntensity(checked);
                case "set_drawable_a" -> setDrawable(checked, true);
                case "set_drawable_b" -> setDrawable(checked, false);
                default -> writeFailure(
                    operation,
                    "NOT_APPLIED",
                    "INVALID_ARGUMENT",
                    "operation must be set_name, set_id, set_intensity, set_drawable_a, "
                        + "set_drawable_b, create, or delete",
                    null,
                    null
                );
            });
        } catch (StaleState failure) {
            return envelope(writeFailure(
                operation,
                "REJECTED_STALE",
                "STALE_STATE",
                failure.getMessage(),
                null,
                safeStateToken()
            ));
        } catch (java.util.NoSuchElementException failure) {
            return envelope(writeFailure(
                operation,
                "NOT_APPLIED",
                "GLUE_NOT_FOUND",
                "The requested Glue is absent from the active model",
                target(arguments.get("id")),
                safeStateToken()
            ));
        } catch (dev.turboism.sdk.permission.CubismPermissionException | SecurityException failure) {
            return envelope(writeFailure(
                operation,
                "NOT_APPLIED",
                "PERMISSION_DENIED",
                "The MCP plugin is not permitted to modify the active Cubism model",
                target(arguments.get("id")),
                safeStateToken()
            ));
        } catch (UnsupportedOperationException failure) {
            return envelope(writeFailure(
                operation,
                "UNAVAILABLE",
                "NO_VERIFIED_PROVIDER",
                "The active Cubism version has no admitted Glue writer",
                target(arguments.get("id")),
                safeStateToken()
            ));
        } catch (IllegalArgumentException failure) {
            return envelope(writeFailure(
                operation,
                "NOT_APPLIED",
                "INVALID_ARGUMENT",
                safeMessage(failure),
                target(arguments.get("id")),
                safeStateToken()
            ));
        } catch (IllegalStateException failure) {
            final boolean stale = safeMessage(failure).toLowerCase(java.util.Locale.ROOT)
                .contains("stale");
            return envelope(writeFailure(
                operation,
                stale ? "REJECTED_STALE" : "OUTCOME_UNKNOWN",
                stale ? "STALE_REFERENCE" : "WRITE_FAILED",
                stale
                    ? "The Glue reference became stale before the write completed"
                    : "The Glue write outcome could not be proven",
                target(arguments.get("id")),
                safeStateToken()
            ));
        } catch (RuntimeException failure) {
            return envelope(writeFailure(
                operation,
                "OUTCOME_UNKNOWN",
                "WRITE_FAILED",
                "The Glue write outcome could not be proven",
                target(arguments.get("id")),
                safeStateToken()
            ));
        }
    }

    private Map<String, Object> setName(final Map<String, Object> arguments) {
        only(arguments, "operation", "id", "name", "expectedState");
        final String requested = requiredBoundedString(arguments, "name", 1, 1024);
        return mutate(
            arguments,
            "set_name",
            requiredString(arguments, "id"),
            value -> value.setName(requested)
        );
    }

    private Map<String, Object> setId(final Map<String, Object> arguments) {
        only(arguments, "operation", "id", "newId", "expectedState");
        final String newId = requiredBoundedString(arguments, "newId", 1, 256);
        return mutate(
            arguments,
            "set_id",
            newId,
            value -> value.setId(new GlueId(newId))
        );
    }

    private Map<String, Object> setDrawable(
        final Map<String, Object> arguments,
        final boolean first
    ) {
        only(arguments, "operation", "id", "drawableId", "expectedState");
        final String drawableId = requiredBoundedString(
            arguments,
            "drawableId",
            1,
            256
        );
        return mutate(
            arguments,
            first ? "set_drawable_a" : "set_drawable_b",
            requiredString(arguments, "id"),
            value -> {
                if (first) {
                    value.setDrawableA(new ArtMeshId(drawableId));
                } else {
                    value.setDrawableB(new ArtMeshId(drawableId));
                }
            }
        );
    }

    private Map<String, Object> setIntensity(final Map<String, Object> arguments) {
        only(arguments, "operation", "id", "intensity", "expectedState");
        final float intensity = requiredFloat(arguments, "intensity", 0.0F, 1.0F);
        return mutate(
            arguments,
            "set_intensity",
            requiredString(arguments, "id"),
            value -> value.setIntensity(intensity)
        );
    }

    private Map<String, Object> mutate(
        final Map<String, Object> arguments,
        final String operation,
        final String readbackId,
        final java.util.function.Consumer<Glue> mutation
    ) {
        final Context beforeContext = context();
        requireExpectedState(arguments.get("expectedState"), beforeContext);
        final String originalId = requiredString(arguments, "id");
        final Glue value = beforeContext.glues().find(new GlueId(originalId));
        final Map<String, Object> before = glue(value);
        try {
            Objects.requireNonNull(mutation, "mutation").accept(value);
        } catch (dev.turboism.sdk.permission.CubismPermissionException
            | SecurityException
            | IllegalArgumentException
            | UnsupportedOperationException failure) {
            throw failure;
        } catch (IllegalStateException failure) {
            if (safeMessage(failure).toLowerCase(java.util.Locale.ROOT).contains("stale")) {
                throw failure;
            }
            return mutationFailure(
                operation,
                originalId,
                readbackId,
                before,
                failure
            );
        } catch (RuntimeException failure) {
            return mutationFailure(
                operation,
                originalId,
                readbackId,
                before,
                failure
            );
        }
        final Context afterContext = context();
        final Map<String, Object> after = glue(
            afterContext.glues().find(new GlueId(readbackId))
        );
        final String outcome = before.equals(after) ? "NO_CHANGE" : "APPLIED";
        return writeSuccess(
            operation,
            outcome,
            target(originalId),
            before,
            after,
            beforeContext,
            afterContext
        );
    }

    private Map<String, Object> mutationFailure(
        final String operation,
        final String originalId,
        final String readbackId,
        final Map<String, Object> before,
        final RuntimeException failure
    ) {
        try {
            final Context afterContext = context();
            final Glue current = findAfterFailure(
                afterContext.glues(),
                originalId,
                readbackId
            );
            final Map<String, Object> readback = glue(current);
            final boolean restored = before.equals(readback);
            return writeFailure(
                operation,
                restored ? "ROLLED_BACK" : "OUTCOME_UNKNOWN",
                restored ? "WRITE_ROLLED_BACK" : "WRITE_FAILED",
                restored
                    ? "The Glue write failed and restoration was verified"
                    : "The Glue write failed and restoration could not be verified",
                target(originalId),
                stateToken(afterContext)
            );
        } catch (RuntimeException readbackFailure) {
            return writeFailure(
                operation,
                "OUTCOME_UNKNOWN",
                "WRITE_FAILED",
                "The Glue write failed and the resulting state could not be read",
                target(originalId),
                safeStateToken()
            );
        }
    }

    private static Glue findAfterFailure(
        final Glues glues,
        final String originalId,
        final String readbackId
    ) {
        try {
            return glues.find(new GlueId(readbackId));
        } catch (java.util.NoSuchElementException absentAtReadbackId) {
            if (readbackId.equals(originalId)) throw absentAtReadbackId;
            return glues.find(new GlueId(originalId));
        }
    }

    private static void requireExpectedState(
        final Object expectedValue,
        final Context actual
    ) {
        if (expectedValue == null) return;
        final Map<String, Object> expected = copyObject(expectedValue, "expectedState");
        only(
            expected,
            "documentId",
            "modelId",
            "historyAvailability",
            "historyGeneration",
            "historyRevision",
            "providerVersion"
        );
        final Map<String, Object> current = stateToken(actual);
        for (String field : List.of(
            "documentId",
            "modelId",
            "historyAvailability",
            "historyGeneration",
            "historyRevision",
            "providerVersion"
        )) {
            if (!Objects.equals(expected.get(field), current.get(field))) {
                throw new StaleState("expectedState does not match current " + field);
            }
        }
    }

    private Map<String, Object> safeStateToken() {
        try {
            return stateToken(context());
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Map<String, Object> writeSuccess(
        final String operation,
        final String outcome,
        final Map<String, Object> target,
        final Map<String, Object> before,
        final Map<String, Object> after,
        final Context beforeContext,
        final Context afterContext
    ) {
        return linked(
            entry("ok", true),
            entry("operation", operation),
            entry("outcome", outcome),
            entry("target", target),
            entry("provider", provider(afterContext.providerVersion())),
            entry("result", linked(
                entry("before", before),
                entry("after", after),
                entry("readback", after)
            )),
            entry("history", history(
                beforeContext.history(),
                afterContext.history()
            )),
            entry("stateToken", stateToken(afterContext))
        );
    }

    private static Map<String, Object> writeFailure(
        final String operation,
        final String outcome,
        final String code,
        final String message,
        final Map<String, Object> target,
        final Map<String, Object> stateToken
    ) {
        return linked(
            entry("ok", false),
            entry("operation", operation),
            entry("outcome", outcome),
            entry("target", target),
            entry("provider", providerForState(stateToken)),
            entry("stateToken", stateToken),
            entry("error", linked(entry("code", code), entry("message", message)))
        );
    }

    private static Map<String, Object> history(
        final HistorySnapshot before,
        final HistorySnapshot after
    ) {
        final HistoryEntry appended = newHistoryEntry(before, after);
        final int entriesAdded = appended == null ? 0 : 1;
        final String label = appended == null ? null : appended.label();
        return linked(
            entry("availability", after.availability().name()),
            entry("generation", after.generation()),
            entry("revisionBefore", before.revision()),
            entry("revisionAfter", after.revision()),
            entry("positionBefore", before.position()),
            entry("positionAfter", after.position()),
            entry("entriesAdded", entriesAdded),
            entry("entryId", appended == null
                ? null : appended.entryId().map(id -> id.value()).orElse(null)),
            entry("transactionId", appended == null
                ? null : appended.transactionId().orElse(null)),
            entry("label", label)
        );
    }

    private static HistoryEntry newHistoryEntry(
        final HistorySnapshot before,
        final HistorySnapshot after
    ) {
        if (before.availability() != HistorySnapshot.Availability.AVAILABLE
            || after.availability() != HistorySnapshot.Availability.AVAILABLE
            || before.generation() != after.generation()
            || after.revision() <= before.revision()
            || !before.documentBindingId().equals(after.documentBindingId())
            || !before.managerBindingId().equals(after.managerBindingId())) {
            return null;
        }
        // A new edit preserves the applied prefix and replaces any redo tail with one entry.
        // Net list growth is not an entry count when the old history contains redo entries.
        final int index = before.position();
        if (after.position() != index + 1 || after.entries().size() != after.position()
            || !before.entries().subList(0, index).equals(after.entries().subList(0, index))) {
            return null;
        }
        final HistoryEntry appended = after.entries().get(index);
        for (HistoryEntry existing : before.entries()) {
            if (appended.entryId().isPresent()
                ? appended.entryId().equals(existing.entryId())
                : appended.equals(existing)) {
                return null; // Moving onto an existing redo entry is not a new commit.
            }
        }
        return appended;
    }

    private static Map<String, Object> target(final Object id) {
        if (!(id instanceof String text) || text.isBlank()) return null;
        return linked(entry("kind", "glue"), entry("id", text));
    }

    private Context context() {
        final DocumentSnapshot document = cubism.activeModelDocument().orElseThrow(
            () -> new NoActiveModel("No active Cubism model document")
        );
        final ModelSnapshot modelSnapshot = cubism.activeModel().orElseThrow(
            () -> new NoActiveModel("No active Cubism model")
        );
        final CubismModel model = cubism.model().active();
        final Glues glues = Objects.requireNonNull(model.glues(), "glues");
        final String providerVersion = glues.providerVersion()
            .filter(SUPPORTED_VERSIONS::contains)
            .orElseThrow(() -> new UnsupportedOperationException(
                "No exact-version Editor Glue provider is bound"
            ));
        final HistorySnapshot history = Objects.requireNonNull(
            cubism.history().snapshot(),
            "history snapshot"
        );
        return new Context(document, modelSnapshot, glues, providerVersion, history);
    }

    private static Map<String, Object> glue(final Glue value) {
        final Glue checked = Objects.requireNonNull(value, "glue");
        return linked(
            entry("id", checked.id().value()),
            entry("name", checked.name()),
            entry("index", checked.index()),
            entry("intensity", checked.intensity()),
            entry("drawableAId", checked.drawableAId().value()),
            entry("drawableBId", checked.drawableBId().value()),
            entry("parameterIds", checked.parameterIds().stream()
                .map(id -> id.value()).toList()),
            entry("availability", "AVAILABLE")
        );
    }

    private static Map<String, Object> success(
        final String operation,
        final Context context,
        final Object result
    ) {
        return linked(
            entry("ok", true),
            entry("operation", operation),
            entry("availability", "AVAILABLE"),
            entry("provider", provider(context.providerVersion())),
            entry("stateToken", stateToken(context)),
            entry("result", result)
        );
    }

    private static Map<String, Object> readFailure(
        final String operation,
        final String availability,
        final String code,
        final String message,
        final Map<String, Object> stateToken
    ) {
        return linked(
            entry("ok", false),
            entry("operation", operation),
            entry("availability", availability),
            entry("provider", providerForState(stateToken)),
            entry("stateToken", stateToken),
            entry("error", linked(entry("code", code), entry("message", message)))
        );
    }

    private static Map<String, Object> provider() {
        return provider(null);
    }

    private static Map<String, Object> provider(final String activeVersion) {
        return linked(
            entry("capabilityId", GLUE_CAPABILITY_ID),
            entry("supportedVersions", SUPPORTED_VERSIONS),
            entry("activeVersion", activeVersion)
        );
    }

    private static Map<String, Object> providerForState(
        final Map<String, Object> stateToken
    ) {
        if (stateToken != null
            && stateToken.get("providerVersion") instanceof String version
            && SUPPORTED_VERSIONS.contains(version)) {
            return provider(version);
        }
        return provider();
    }

    private static Map<String, Object> stateToken(final Context context) {
        return linked(
            entry("documentId", context.document().documentId()),
            entry("modelId", context.modelSnapshot().modelId()),
            entry("historyAvailability", context.history().availability().name()),
            entry("historyGeneration", context.history().generation()),
            entry("historyRevision", context.history().revision()),
            entry("providerVersion", context.providerVersion())
        );
    }

    private static Map<String, Object> envelope(final Map<String, Object> output) {
        return linked(
            entry("content", List.of(linked(
                entry("type", "text"),
                entry("text", Json.stringify(output))
            ))),
            entry("structuredContent", output),
            entry("isError", !Boolean.TRUE.equals(output.get("ok")))
        );
    }

    private static Map<String, Object> readDefinition() {
        return linked(
            entry("name", GLUES_READ),
            entry("title", "Read Cubism Glues"),
            entry("description", "Lists or reads Glue authoring state from the active Cubism model. "
                + "The same closed contract is exact-routed for Cubism Editor 5.2.03, 5.3.02, and 5.3.03."),
            entry("inputSchema", oneOf(
                objectSchema(
                    linked(
                        entry("operation", constant("list")),
                        entry("offset", integerSchema(0, null)),
                        entry("limit", integerSchema(1, MAX_LIMIT))
                    ),
                    List.of("operation")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("get")),
                        entry("id", boundedStringSchema(1, 256))
                    ),
                    List.of("operation", "id")
                )
            )),
            entry("outputSchema", readOutputSchema()),
            entry("annotations", linked(
                entry("readOnlyHint", true),
                entry("destructiveHint", false),
                entry("idempotentHint", true)
            ))
        );
    }

    private static Map<String, Object> writeDefinition() {
        return linked(
            entry("name", GLUES_WRITE),
            entry("title", "Write Cubism Glue"),
            entry("description", "Applies one Undo-aware Glue authoring mutation to the active model. "
                + "The exact provider is admitted independently for Cubism Editor 5.2.03, 5.3.02, and 5.3.03. "
                + "Glue creation and deletion remain explicit unavailable operations."),
            entry("inputSchema", oneOf(
                objectSchema(
                    linked(
                        entry("operation", constant("set_name")),
                        entry("id", boundedStringSchema(1, 256)),
                        entry("name", boundedStringSchema(1, 1024)),
                        entry("expectedState", stateTokenSchema())
                    ),
                    List.of("operation", "id", "name")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("set_id")),
                        entry("id", boundedStringSchema(1, 256)),
                        entry("newId", boundedStringSchema(1, 256)),
                        entry("expectedState", stateTokenSchema())
                    ),
                    List.of("operation", "id", "newId")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("set_intensity")),
                        entry("id", boundedStringSchema(1, 256)),
                        entry("intensity", linked(
                            entry("type", "number"),
                            entry("minimum", 0.0),
                            entry("maximum", 1.0)
                        )),
                        entry("expectedState", stateTokenSchema())
                    ),
                    List.of("operation", "id", "intensity")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("set_drawable_a")),
                        entry("id", boundedStringSchema(1, 256)),
                        entry("drawableId", boundedStringSchema(1, 256)),
                        entry("expectedState", stateTokenSchema())
                    ),
                    List.of("operation", "id", "drawableId")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("set_drawable_b")),
                        entry("id", boundedStringSchema(1, 256)),
                        entry("drawableId", boundedStringSchema(1, 256)),
                        entry("expectedState", stateTokenSchema())
                    ),
                    List.of("operation", "id", "drawableId")
                ),
                objectSchema(
                    linked(entry("operation", constant("create"))),
                    List.of("operation")
                ),
                objectSchema(
                    linked(
                        entry("operation", constant("delete")),
                        entry("id", boundedStringSchema(1, 256))
                    ),
                    List.of("operation", "id")
                )
            )),
            entry("outputSchema", writeOutputSchema()),
            entry("annotations", linked(
                entry("readOnlyHint", false),
                entry("destructiveHint", false),
                entry("idempotentHint", true)
            ))
        );
    }

    private static Map<String, Object> writeOutputSchema() {
        return linked(
            entry("$schema", "https://json-schema.org/draft/2020-12/schema"),
            entry("oneOf", List.of(
                objectSchema(
                    linked(
                        entry("ok", constant(true)),
                        entry("operation", enumSchema(List.of(
                            "set_name", "set_id", "set_intensity",
                            "set_drawable_a", "set_drawable_b"
                        ))),
                        entry("outcome", enumSchema(List.of("APPLIED", "NO_CHANGE"))),
                        entry("target", targetSchema()),
                        entry("provider", providerSchema()),
                        entry("result", writeResultSchema()),
                        entry("history", historySchema()),
                        entry("stateToken", stateTokenSchema())
                    ),
                    List.of(
                        "ok", "operation", "outcome", "target", "provider",
                        "result", "history", "stateToken"
                    )
                ),
                objectSchema(
                    linked(
                        entry("ok", constant(false)),
                        entry("operation", stringSchema()),
                        entry("outcome", enumSchema(List.of(
                            "NOT_APPLIED", "REJECTED_STALE", "UNAVAILABLE",
                            "ROLLED_BACK", "RECOVERY_FAILED", "OUTCOME_UNKNOWN"
                        ))),
                        entry("target", nullableObjectSchema(targetSchema())),
                        entry("provider", providerSchema()),
                        entry("stateToken", nullableObjectSchema(stateTokenSchema())),
                        entry("error", objectSchema(
                            linked(
                                entry("code", stringSchema()),
                                entry("message", stringSchema())
                            ),
                            List.of("code", "message")
                        ))
                    ),
                    List.of(
                        "ok", "operation", "outcome", "target", "provider",
                        "stateToken", "error"
                    )
                )
            ))
        );
    }

    private static Map<String, Object> writeResultSchema() {
        return objectSchema(
            linked(
                entry("before", glueSchema()),
                entry("after", glueSchema()),
                entry("readback", glueSchema())
            ),
            List.of("before", "after", "readback")
        );
    }

    private static Map<String, Object> targetSchema() {
        return objectSchema(
            linked(
                entry("kind", constant("glue")),
                entry("id", stringSchema())
            ),
            List.of("kind", "id")
        );
    }

    private static Map<String, Object> historySchema() {
        return objectSchema(
            linked(
                entry("availability", enumSchema(List.of("AVAILABLE", "UNAVAILABLE"))),
                entry("generation", integerSchema(0, null)),
                entry("revisionBefore", integerSchema(0, null)),
                entry("revisionAfter", integerSchema(0, null)),
                entry("positionBefore", integerSchema(0, null)),
                entry("positionAfter", integerSchema(0, null)),
                entry("entriesAdded", integerSchema(0, null)),
                entry("entryId", nullableStringSchema()),
                entry("transactionId", nullableStringSchema()),
                entry("label", nullableStringSchema())
            ),
            List.of(
                "availability", "generation", "revisionBefore", "revisionAfter",
                "positionBefore", "positionAfter", "entriesAdded", "entryId",
                "transactionId", "label"
            )
        );
    }

    private static Map<String, Object> readOutputSchema() {
        return linked(
            entry("$schema", "https://json-schema.org/draft/2020-12/schema"),
            entry("oneOf", List.of(
                objectSchema(
                    linked(
                        entry("ok", constant(true)),
                        entry("operation", constant("list")),
                        entry("availability", constant("AVAILABLE")),
                        entry("provider", providerSchema()),
                        entry("stateToken", stateTokenSchema()),
                        entry("result", pageSchema())
                    ),
                    List.of("ok", "operation", "availability", "provider", "stateToken", "result")
                ),
                objectSchema(
                    linked(
                        entry("ok", constant(true)),
                        entry("operation", constant("get")),
                        entry("availability", constant("AVAILABLE")),
                        entry("provider", providerSchema()),
                        entry("stateToken", stateTokenSchema()),
                        entry("result", glueSchema())
                    ),
                    List.of("ok", "operation", "availability", "provider", "stateToken", "result")
                ),
                failureSchema()
            ))
        );
    }

    private static Map<String, Object> pageSchema() {
        return objectSchema(
            linked(
                entry("items", arraySchema(glueSchema())),
                entry("offset", integerSchema(0, null)),
                entry("limit", integerSchema(1, MAX_LIMIT)),
                entry("total", integerSchema(0, null)),
                entry("nextOffset", nullableIntegerSchema(0))
            ),
            List.of("items", "offset", "limit", "total", "nextOffset")
        );
    }

    private static Map<String, Object> glueSchema() {
        return objectSchema(
            linked(
                entry("id", stringSchema()),
                entry("name", stringSchema()),
                entry("index", integerSchema(0, null)),
                entry("intensity", numberSchema()),
                entry("drawableAId", stringSchema()),
                entry("drawableBId", stringSchema()),
                entry("parameterIds", arraySchema(stringSchema())),
                entry("availability", constant("AVAILABLE"))
            ),
            List.of(
                "id", "name", "index", "intensity", "drawableAId",
                "drawableBId", "parameterIds", "availability"
            )
        );
    }

    private static Map<String, Object> providerSchema() {
        return objectSchema(
            linked(
                entry("capabilityId", constant(GLUE_CAPABILITY_ID)),
                entry("supportedVersions", linked(
                    entry("type", "array"),
                    entry("items", enumSchema(SUPPORTED_VERSIONS)),
                    entry("minItems", SUPPORTED_VERSIONS.size()),
                    entry("maxItems", SUPPORTED_VERSIONS.size())
                )),
                entry("activeVersion", linked(
                    entry("type", List.of("string", "null")),
                    entry("enum", java.util.Arrays.asList(
                        "5.2.03", "5.3.02", "5.3.03", null
                    ))
                ))
            ),
            List.of("capabilityId", "supportedVersions", "activeVersion")
        );
    }

    private static Map<String, Object> stateTokenSchema() {
        return objectSchema(
            linked(
                entry("documentId", stringSchema()),
                entry("modelId", stringSchema()),
                entry("historyAvailability", enumSchema(List.of("AVAILABLE", "UNAVAILABLE"))),
                entry("historyGeneration", integerSchema(0, null)),
                entry("historyRevision", integerSchema(0, null)),
                entry("providerVersion", enumSchema(SUPPORTED_VERSIONS))
            ),
            List.of(
                "documentId", "modelId", "historyAvailability",
                "historyGeneration", "historyRevision", "providerVersion"
            )
        );
    }

    private static Map<String, Object> failureSchema() {
        return objectSchema(
            linked(
                entry("ok", constant(false)),
                entry("operation", stringSchema()),
                entry("availability", enumSchema(List.of("AVAILABLE", "UNAVAILABLE"))),
                entry("provider", providerSchema()),
                entry("stateToken", nullableObjectSchema(stateTokenSchema())),
                entry("error", objectSchema(
                    linked(entry("code", stringSchema()), entry("message", stringSchema())),
                    List.of("code", "message")
                ))
            ),
            List.of("ok", "operation", "availability", "provider", "stateToken", "error")
        );
    }

    private static String requiredString(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return text;
    }

    private static String requiredBoundedString(
        final Map<String, Object> values,
        final String key,
        final int minimum,
        final int maximum
    ) {
        final String value = requiredString(values, key);
        if (value.length() < minimum || value.length() > maximum) {
            throw new IllegalArgumentException(key + " is outside the supported length");
        }
        return value;
    }

    private static int optionalInteger(
        final Map<String, Object> values,
        final String key,
        final int defaultValue,
        final int minimum,
        final int maximum
    ) {
        if (!values.containsKey(key)) return defaultValue;
        final Object value = values.get(key);
        if (!(value instanceof Byte || value instanceof Short
            || value instanceof Integer || value instanceof Long)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException(key + " is outside the supported range");
        }
        return (int) number;
    }

    private static void only(final Map<String, Object> values, final String... names) {
        final List<String> allowed = List.of(names);
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw new IllegalArgumentException("unsupported argument: " + key);
            }
        }
    }

    private static Map<String, Object> copyObject(
        final Map<String, Object> value,
        final String label
    ) {
        Objects.requireNonNull(value, label);
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static Map<String, Object> copyObject(final Object value, final String label) {
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static float requiredFloat(
        final Map<String, Object> values,
        final String key,
        final float minimum,
        final float maximum
    ) {
        final Object value = values.get(key);
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(key + " must be a number");
        }
        final double raw = number.doubleValue();
        if (!Double.isFinite(raw) || raw < minimum || raw > maximum) {
            throw new IllegalArgumentException(key + " is outside the supported range");
        }
        final float result = number.floatValue();
        if (!Float.isFinite(result)) {
            throw new IllegalArgumentException(key + " must be finite");
        }
        return result;
    }

    private static String errorCode(final RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        if (failure instanceof UnsupportedOperationException) return "NO_VERIFIED_PROVIDER";
        return "UNAVAILABLE";
    }

    private static String safeMessage(final RuntimeException failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank()
            ? failure.getClass().getSimpleName()
            : message;
    }

    private static Map<String, Object> nullableObjectSchema(final Map<String, Object> schema) {
        return linked(entry("oneOf", List.of(schema, Map.of("type", "null"))));
    }

    private static Map<String, Object> nullableIntegerSchema(final int minimum) {
        return linked(entry("type", List.of("integer", "null")), entry("minimum", minimum));
    }

    private static Map<String, Object> oneOf(final Map<String, Object>... alternatives) {
        return Map.of("oneOf", List.of(alternatives));
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

    private static Map<String, Object> arraySchema(final Map<String, Object> items) {
        return linked(entry("type", "array"), entry("items", items));
    }

    private static Map<String, Object> integerSchema(
        final Integer minimum,
        final Integer maximum
    ) {
        final LinkedHashMap<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "integer");
        if (minimum != null) schema.put("minimum", minimum);
        if (maximum != null) schema.put("maximum", maximum);
        return Collections.unmodifiableMap(schema);
    }

    private static Map<String, Object> numberSchema() {
        return Map.of("type", "number");
    }

    private static Map<String, Object> stringSchema() {
        return Map.of("type", "string");
    }

    private static Map<String, Object> nullableStringSchema() {
        return Map.of("type", List.of("string", "null"));
    }

    private static Map<String, Object> boundedStringSchema(
        final int minimum,
        final int maximum
    ) {
        return linked(
            entry("type", "string"),
            entry("minLength", minimum),
            entry("maxLength", maximum)
        );
    }

    private static Map<String, Object> enumSchema(final List<String> values) {
        return linked(entry("type", "string"), entry("enum", values));
    }

    private static Map<String, Object> constant(final Object value) {
        return Map.of("const", value);
    }

    @SafeVarargs
    private static Map<String, Object> linked(final Map.Entry<String, Object>... entries) {
        final LinkedHashMap<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) {
            values.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(values);
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return new java.util.AbstractMap.SimpleImmutableEntry<>(key, value);
    }

    private record Context(
        DocumentSnapshot document,
        ModelSnapshot modelSnapshot,
        Glues glues,
        String providerVersion,
        HistorySnapshot history
    ) {
    }

    private static final class StaleState extends IllegalArgumentException {
        private StaleState(final String message) {
            super(message);
        }
    }

    private static final class NoActiveModel extends IllegalStateException {
        private NoActiveModel(final String message) {
            super(message);
        }
    }
}
