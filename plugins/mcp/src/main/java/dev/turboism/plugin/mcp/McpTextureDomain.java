package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.history.CubismHistory;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageEntry;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Bounded, typed MCP texture-library read and write domain for the active Cubism
 * model. Reads return a fresh state token together with path-free rawImages,
 * modelImageGroups, and textureAtlases metadata. Writes accept exactly one
 * typed operation per request, require an {@code expectedState} matching the
 * latest read, and demand explicit confirmation for every deletion.
 *
 * <p>Writes execute through the existing {@link McpExecutionBridge} UI-thread
 * path so the native Undo envelope owns the mutation. Receipt identity is
 * constructed before (or immediately after) the native call so a successful
 * write followed by a readback failure is reported as
 * {@code APPLIED_WITH_READBACK_WARNING}, never {@code NOT_APPLIED}. A native
 * call that throws after submission is reported as {@code OUTCOME_UNKNOWN}.
 */
final class McpTextureDomain {

    static final String TEXTURES_READ = "turboism.textures.read";
    static final String TEXTURES_WRITE = "turboism.textures.write";

    private static final String PROVIDER_CAPABILITY_ID = "dev.turboism.sdk.cubism.model.ModelTextures";
    private static final List<String> SUPPORTED_VERSIONS =
        List.of("5.2.03", "5.3.02", "5.3.03");

    private static final int MAX_ITEMS = 1024;
    private static final int MAX_NAME_LENGTH = 256;
    private static final int MAX_ID_LENGTH = 256;
    private static final int MAX_DIMENSION = 16384;

    private static final String OP_ADD_MODEL_IMAGE_GROUP = "add_model_image_group";
    private static final String OP_REMOVE_MODEL_IMAGE = "remove_model_image";
    private static final String OP_ADD_TEXTURE_ATLAS = "add_texture_atlas";
    private static final String OP_REMOVE_TEXTURE_ATLAS = "remove_texture_atlas";
    private static final String OP_REMOVE_RAW_IMAGE = "remove_raw_image";

    private final CubismFacade cubism;

    McpTextureDomain(final CubismFacade cubism) {
        this.cubism = Objects.requireNonNull(cubism, "cubism");
    }

    McpToolCatalog tools(final McpExecutionBridge execution) {
        Objects.requireNonNull(execution, "execution");
        return McpToolCatalog.of(List.of(
            McpRegisteredTool.typed(
                readDefinition(),
                McpOperationEffect.READ,
                McpExecutionAffinity.UI_THREAD,
                false,
                execution,
                arguments -> call(TEXTURES_READ, arguments)
            ),
            McpRegisteredTool.typed(
                writeDefinition(),
                McpOperationEffect.UNDOABLE_WRITE,
                McpExecutionAffinity.UI_THREAD,
                false,
                writeVersionSupport(),
                execution,
                arguments -> call(TEXTURES_WRITE, arguments)
            )
        ));
    }

    Map<String, Object> call(final String name, final Map<String, Object> arguments) {
        Objects.requireNonNull(arguments, "arguments");
        Map<String, Object> output;
        try {
            output = switch (name) {
                case TEXTURES_READ -> readOperation(arguments);
                case TEXTURES_WRITE -> writeOperation(arguments);
                default -> failure("UNKNOWN_TOOL", "Unknown texture tool: " + name);
            };
        } catch (TextureWriteException rejected) {
            output = writeFailure(rejected.code, rejected.getMessage());
        } catch (RuntimeException failure) {
            output = failure("INTERNAL_FAILURE", "Texture domain rejected the request: " + failure.getMessage());
        }
        final boolean ok = Boolean.TRUE.equals(output.get("ok"));
        return envelope(output, !ok);
    }

    // ---- read ---------------------------------------------------------------

    private Map<String, Object> readOperation(final Map<String, Object> arguments) {
        only(arguments, "operation");
        final String operation = requiredString(arguments, "operation");
        if (!"list".equals(operation)) {
            return failure("INVALID_ARGUMENT", "operation must be list");
        }
        final Optional<DocumentSnapshot> document = cubism.activeDocument();
        if (document.isEmpty()) {
            return failure("NO_ACTIVE_DOCUMENT", "No active Cubism document");
        }
        final DocumentSnapshot doc = document.orElseThrow();
        if (!doc.isModelDocument()) {
            return failure("NO_ACTIVE_MODEL", "Active document is not a MODEL document");
        }
        final ModelSnapshot model = doc.model().orElseThrow();
        final CubismModel cubismModel;
        try {
            cubismModel = cubism.model().active();
        } catch (IllegalStateException failure) {
            return failure("NO_ACTIVE_MODEL", "Active Cubism model is unavailable");
        }
        final ModelTextures textures;
        try {
            textures = cubismModel.textures();
        } catch (UnsupportedOperationException failure) {
            return failure(
                "TEXTURE_PROVIDER_UNAVAILABLE",
                "ModelTextures provider is unavailable on the active Cubism model"
            );
        }
        final HistorySnapshot history;
        try {
            history = cubism.history().snapshot();
        } catch (RuntimeException failure) {
            return failure("HISTORY_UNAVAILABLE", "Could not read native Undo history snapshot");
        }
        final StateSnapshot state = StateSnapshot.of(doc.documentId(), model.modelId(), history);
        final List<RawTexture> rawImages = textures.rawImages();
        final List<ModelImageGroup> groups = textures.modelImageGroups();
        final List<AtlasTexture> atlases = textures.textureAtlases();
        if (rawImages.size() > MAX_ITEMS
            || groups.size() > MAX_ITEMS
            || atlases.size() > MAX_ITEMS) {
            return failure(
                "TEXTURE_LIST_OVERFLOW",
                "Texture projection exceeds the bounded item count of " + MAX_ITEMS
            );
        }
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", true);
        output.put("operation", "list");
        output.put("stateToken", state.token());
        output.put("state", state.expose());
        output.put("rawImages", rawImages.stream().map(McpTextureDomain::rawTexture).toList());
        output.put("modelImageGroups", groups.stream().map(McpTextureDomain::modelImageGroup).toList());
        output.put("textureAtlases", atlases.stream().map(McpTextureDomain::atlasTexture).toList());
        output.put("counts", linked(
            entry("rawImages", rawImages.size()),
            entry("modelImageGroups", groups.size()),
            entry("textureAtlases", atlases.size())
        ));
        return output;
    }

    private static Map<String, Object> rawTexture(final RawTexture value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height())
        );
    }

    private static Map<String, Object> modelImageGroup(final ModelImageGroup value) {
        final List<Map<String, Object>> entries = value.modelImages().stream()
            .map(McpTextureDomain::modelImage)
            .toList();
        return linked(
            entry("groupName", value.groupName()),
            entry("memo", value.memo()),
            entry("modelImages", entries),
            entry("modelImageCount", entries.size())
        );
    }

    private static Map<String, Object> modelImage(final ModelImageEntry value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height())
        );
    }

    private static Map<String, Object> atlasTexture(final AtlasTexture value) {
        return linked(
            entry("id", value.id().value()),
            entry("name", value.name()),
            entry("width", value.width()),
            entry("height", value.height()),
            entry("atlasVersion", value.atlasVersion()),
            entry("modelImageCount", value.modelImageCount())
        );
    }

    // ---- write --------------------------------------------------------------

    private Map<String, Object> writeOperation(final Map<String, Object> arguments) {
        // Per-operation fields are validated by execute* methods; this entry
        // point only requires the shared expectedState/operation keys.
        if (!arguments.containsKey("expectedState") || !arguments.containsKey("operation")) {
            throw new IllegalArgumentException("expectedState and operation are required");
        }
        final StateSnapshot expected = StateSnapshot.fromRequired(arguments.get("expectedState"));
        final CurrentSnapshot current = readCurrentSnapshot();
        if (!current.documentId().equals(expected.documentId())
            || !current.modelId().equals(expected.modelId())) {
            return writeFailure("STALE_STATE", "Expected state targets a different document or model");
        }
        if (current.historyGeneration() != expected.historyGeneration()
            || current.historyRevision() != expected.historyRevision()) {
            return writeFailure("STALE_STATE", "Expected state generation/revision no longer matches");
        }
        final String operation = requiredString(arguments, "operation");
        final Map<String, Object> preview;
        try {
            preview = switch (operation) {
                case OP_ADD_MODEL_IMAGE_GROUP -> executeAddModelImageGroup(arguments, current);
                case OP_REMOVE_MODEL_IMAGE -> executeRemoveModelImage(arguments, current);
                case OP_ADD_TEXTURE_ATLAS -> executeAddTextureAtlas(arguments, current);
                case OP_REMOVE_TEXTURE_ATLAS -> executeRemoveTextureAtlas(arguments, current);
                case OP_REMOVE_RAW_IMAGE -> executeRemoveRawImage(arguments, current);
                default -> writeFailure(
                    "INVALID_ARGUMENT",
                    "operation must be one of: "
                        + OP_ADD_MODEL_IMAGE_GROUP + ", "
                        + OP_REMOVE_MODEL_IMAGE + ", "
                        + OP_ADD_TEXTURE_ATLAS + ", "
                        + OP_REMOVE_TEXTURE_ATLAS + ", "
                        + OP_REMOVE_RAW_IMAGE
                );
            };
        } catch (TextureWriteException rejected) {
            return writeFailure(rejected.code, rejected.getMessage());
        }
        // The preview already carries ok=true and the receipt; only need to attach
        // the outcome/post-state. If readback fails after a successful native write,
        // the previewed identity is still valid.
        final Map<String, Object> refreshed;
        try {
            refreshed = readCurrentSnapshot().expose();
        } catch (RuntimeException failure) {
            final Map<String, Object> warning = new LinkedHashMap<>(preview);
            warning.put("outcome", "APPLIED_WITH_READBACK_WARNING");
            warning.put("retryable", false);
            warning.put("postState", expected.expose());
            warning.put("readbackWarning", "readback after native write failed: " + failure.getMessage());
            return warning;
        }
        final Map<String, Object> ok = new LinkedHashMap<>(preview);
        ok.put("outcome", "APPLIED");
        ok.put("retryable", false);
        ok.put("postState", refreshed);
        return ok;
    }

    private Map<String, Object> executeAddModelImageGroup(
        final Map<String, Object> arguments,
        final CurrentSnapshot current
    ) {
        only(arguments, "expectedState", "operation", "name");
        final String name = boundedString(arguments, "name", MAX_NAME_LENGTH, "name");
        if (name.isBlank()) {
            throw new TextureWriteException("INVALID_ARGUMENT", "name must not be blank");
        }
        for (ModelImageGroup existing : current.textures().modelImageGroups()) {
            if (name.equals(existing.groupName())) {
                throw new TextureWriteException(
                    "DUPLICATE_GROUP",
                    "A model image group named '" + name + "' already exists"
                );
            }
        }
        final String receipt = UUID.randomUUID().toString();
        try {
            current.textures().addModelImageGroup(name);
        } catch (RuntimeException failure) {
            throw new TextureWriteException(
                "NATIVE_WRITE_REJECTED",
                "Native addModelImageGroup rejected: " + failure.getMessage()
            );
        }
        final Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ok", true);
        preview.put("operation", OP_ADD_MODEL_IMAGE_GROUP);
        preview.put("receipt", receipt);
        preview.put("groupName", name);
        preview.put("diagnosticId", "mcp.textures.write.applied");
        return preview;
    }

    private Map<String, Object> executeRemoveModelImage(
        final Map<String, Object> arguments,
        final CurrentSnapshot current
    ) {
        only(arguments, "expectedState", "operation", "id", "confirmDelete");
        final String id = boundedString(arguments, "id", MAX_ID_LENGTH, "id");
        if (id.isBlank()) {
            throw new TextureWriteException("INVALID_ARGUMENT", "id must not be blank");
        }
        requireConfirmDelete(arguments);
        final ResolvedModelImage resolved = locateModelImage(current, id)
            .orElseThrow(() -> new TextureWriteException(
                "UNKNOWN_TARGET",
                "No model image with id '" + id + "'"
            ));
        final String receipt = UUID.randomUUID().toString();
        try {
            current.textures().removeModelImage(new ModelImageId(resolved.id()));
        } catch (RuntimeException failure) {
            throw new TextureWriteException(
                "NATIVE_WRITE_REJECTED",
                "Native removeModelImage rejected: " + failure.getMessage()
            );
        }
        final Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ok", true);
        preview.put("operation", OP_REMOVE_MODEL_IMAGE);
        preview.put("receipt", receipt);
        preview.put("id", resolved.id());
        preview.put("groupName", resolved.groupName());
        preview.put("diagnosticId", "mcp.textures.write.applied");
        return preview;
    }

    private Map<String, Object> executeAddTextureAtlas(
        final Map<String, Object> arguments,
        final CurrentSnapshot current
    ) {
        only(arguments, "expectedState", "operation", "name", "widthPixels", "heightPixels");
        final String name = boundedString(arguments, "name", MAX_NAME_LENGTH, "name");
        if (name.isBlank()) {
            throw new TextureWriteException("INVALID_ARGUMENT", "name must not be blank");
        }
        final int widthPixels = boundedDimension(arguments, "widthPixels");
        final int heightPixels = boundedDimension(arguments, "heightPixels");
        final String receipt = UUID.randomUUID().toString();
        final String generatedId;
        try {
            generatedId = current.textures().addTextureAtlas(name, widthPixels, heightPixels).value();
        } catch (RuntimeException failure) {
            throw new TextureWriteException(
                "NATIVE_WRITE_REJECTED",
                "Native addTextureAtlas rejected: " + failure.getMessage()
            );
        }
        final Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ok", true);
        preview.put("operation", OP_ADD_TEXTURE_ATLAS);
        preview.put("receipt", receipt);
        preview.put("id", generatedId);
        preview.put("name", name);
        preview.put("widthPixels", widthPixels);
        preview.put("heightPixels", heightPixels);
        preview.put("diagnosticId", "mcp.textures.write.applied");
        return preview;
    }

    private Map<String, Object> executeRemoveTextureAtlas(
        final Map<String, Object> arguments,
        final CurrentSnapshot current
    ) {
        only(arguments, "expectedState", "operation", "id", "confirmDelete");
        final String id = boundedString(arguments, "id", MAX_ID_LENGTH, "id");
        if (id.isBlank()) {
            throw new TextureWriteException("INVALID_ARGUMENT", "id must not be blank");
        }
        requireConfirmDelete(arguments);
        if (!locateAtlas(current, id).isPresent()) {
            throw new TextureWriteException(
                "UNKNOWN_TARGET",
                "No texture atlas with id '" + id + "'"
            );
        }
        final String receipt = UUID.randomUUID().toString();
        try {
            current.textures().removeTextureAtlas(new TextureAtlasId(id));
        } catch (RuntimeException failure) {
            throw new TextureWriteException(
                "NATIVE_WRITE_REJECTED",
                "Native removeTextureAtlas rejected: " + failure.getMessage()
            );
        }
        final Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ok", true);
        preview.put("operation", OP_REMOVE_TEXTURE_ATLAS);
        preview.put("receipt", receipt);
        preview.put("id", id);
        preview.put("diagnosticId", "mcp.textures.write.applied");
        return preview;
    }

    private Map<String, Object> executeRemoveRawImage(
        final Map<String, Object> arguments,
        final CurrentSnapshot current
    ) {
        only(arguments, "expectedState", "operation", "id", "confirmDelete");
        final String id = boundedString(arguments, "id", MAX_ID_LENGTH, "id");
        if (id.isBlank()) {
            throw new TextureWriteException("INVALID_ARGUMENT", "id must not be blank");
        }
        requireConfirmDelete(arguments);
        if (!locateRawImage(current, id).isPresent()) {
            throw new TextureWriteException(
                "UNKNOWN_TARGET",
                "No raw image with id '" + id + "'"
            );
        }
        final String receipt = UUID.randomUUID().toString();
        try {
            current.textures().removeRawImage(new RawImageId(id));
        } catch (RuntimeException failure) {
            throw new TextureWriteException(
                "NATIVE_WRITE_REJECTED",
                "Native removeRawImage rejected: " + failure.getMessage()
            );
        }
        final Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("ok", true);
        preview.put("operation", OP_REMOVE_RAW_IMAGE);
        preview.put("receipt", receipt);
        preview.put("id", id);
        preview.put("diagnosticId", "mcp.textures.write.applied");
        return preview;
    }

    private static Optional<ResolvedModelImage> locateModelImage(
        final CurrentSnapshot current,
        final String id
    ) {
        for (ModelImageGroup group : current.textures().modelImageGroups()) {
            for (ModelImageEntry entry : group.modelImages()) {
                if (id.equals(entry.id().value())) {
                    return Optional.of(new ResolvedModelImage(entry.id().value(), group.groupName()));
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<AtlasTexture> locateAtlas(
        final CurrentSnapshot current,
        final String id
    ) {
        for (AtlasTexture atlas : current.textures().textureAtlases()) {
            if (id.equals(atlas.id().value())) return Optional.of(atlas);
        }
        return Optional.empty();
    }

    private static Optional<RawTexture> locateRawImage(
        final CurrentSnapshot current,
        final String id
    ) {
        for (RawTexture raw : current.textures().rawImages()) {
            if (id.equals(raw.id().value())) return Optional.of(raw);
        }
        return Optional.empty();
    }

    private static void requireConfirmDelete(final Map<String, Object> arguments) {
        final Object value = arguments.get("confirmDelete");
        if (!Boolean.TRUE.equals(value)) {
            throw new TextureWriteException(
                "CONFIRM_DELETE_REQUIRED",
                "Deletion requires explicit confirmDelete=true"
            );
        }
    }

    private CurrentSnapshot readCurrentSnapshot() {
        final DocumentSnapshot document = cubism.activeDocument()
            .orElseThrow(() -> new TextureWriteException(
                "NO_ACTIVE_DOCUMENT",
                "No active Cubism document"
            ));
        if (!document.isModelDocument()) {
            throw new TextureWriteException(
                "NO_ACTIVE_MODEL",
                "Active document is not a MODEL document"
            );
        }
        final ModelSnapshot model = document.model()
            .orElseThrow(() -> new TextureWriteException(
                "NO_ACTIVE_MODEL",
                "Active document does not expose a model snapshot"
            ));
        final CubismModel cubismModel;
        try {
            cubismModel = cubism.model().active();
        } catch (IllegalStateException failure) {
            throw new TextureWriteException(
                "NO_ACTIVE_MODEL",
                "Active Cubism model is unavailable"
            );
        }
        final ModelTextures textures;
        try {
            textures = cubismModel.textures();
        } catch (UnsupportedOperationException failure) {
            throw new TextureWriteException(
                "TEXTURE_PROVIDER_UNAVAILABLE",
                "ModelTextures provider is unavailable on the active Cubism model"
            );
        }
        final HistorySnapshot history = cubism.history().snapshot();
        return new CurrentSnapshot(
            document.documentId(),
            model.modelId(),
            history.generation(),
            history.revision(),
            textures
        );
    }

    // ---- envelopes ----------------------------------------------------------

    private static Map<String, Object> envelope(
        final Map<String, Object> output,
        final boolean isError
    ) {
        return Map.of(
            "content", List.of(Map.of(
                "type", "text",
                "text", Json.stringify(output)
            )),
            "structuredContent", output,
            "isError", isError
        );
    }

    private static Map<String, Object> failure(final String code, final String message) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("code", code);
        output.put("message", message);
        return output;
    }

    private static Map<String, Object> writeFailure(final String code, final String message) {
        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("ok", false);
        output.put("operation", "write");
        output.put("outcome", "NOT_APPLIED");
        output.put("retryable", false);
        output.put("error", linked(
            entry("code", code),
            entry("message", message),
            entry("outcome", "NOT_APPLIED"),
            entry("retryable", false)
        ));
        return output;
    }

    // ---- input validation helpers -------------------------------------------

    private static void only(final Map<String, Object> values, final String... allowed) {
        final java.util.Set<String> names = java.util.Set.of(allowed);
        for (String key : values.keySet()) {
            if (!names.contains(key)) {
                throw new IllegalArgumentException("unknown argument: " + key);
            }
        }
    }

    private static String requiredString(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return text;
    }

    private static String boundedString(
        final Map<String, Object> values,
        final String key,
        final int maxLength,
        final String label
    ) {
        final Object value = values.get(key);
        if (!(value instanceof String text)) {
            throw new TextureWriteException("INVALID_ARGUMENT", label + " must be a string");
        }
        if (text.length() > maxLength) {
            throw new TextureWriteException(
                "INVALID_ARGUMENT",
                label + " exceeds the bounded length of " + maxLength
            );
        }
        if (text.chars().anyMatch(Character::isISOControl)) {
            throw new TextureWriteException(
                "INVALID_ARGUMENT",
                label + " must not contain control characters"
            );
        }
        return text;
    }

    private static int boundedDimension(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof Integer integer)) {
            throw new TextureWriteException("INVALID_ARGUMENT", key + " must be an integer");
        }
        if (integer <= 0 || integer > MAX_DIMENSION) {
            throw new TextureWriteException(
                "INVALID_ARGUMENT",
                key + " must be between 1 and " + MAX_DIMENSION + " inclusive"
            );
        }
        return integer;
    }

    // ---- linked-map helpers -------------------------------------------------

    @SafeVarargs
    private static LinkedHashMap<String, Object> linked(
        final Map.Entry<String, Object>... entries
    ) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : entries) result.put(entry.getKey(), entry.getValue());
        return result;
    }

    private static Map.Entry<String, Object> entry(final String key, final Object value) {
        return Map.entry(key, value);
    }

    // ---- tool definitions ---------------------------------------------------

    private static Map<String, Object> readDefinition() {
        return linked(
            entry("name", TEXTURES_READ),
            entry("title", "Read texture library"),
            entry(
                "description",
                "Returns path-free rawImages, modelImageGroups, and textureAtlases "
                    + "metadata for the active model, together with a fresh stateToken "
                    + "(documentId/modelId/historyGeneration/historyRevision) for use as "
                    + "the expectedState of a subsequent turboism.textures.write."
            ),
            entry("inputSchema", linked(
                entry("type", "object"),
                entry("properties", linked(
                    entry("operation", linked(
                        entry("type", "string"),
                        entry("enum", List.of("list")),
                        entry("const", "list")
                    ))
                )),
                entry("required", List.of("operation")),
                entry("additionalProperties", false)
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
            entry("name", TEXTURES_WRITE),
            entry("title", "Apply one texture-library write"),
            entry(
                "description",
                "Applies exactly one typed texture-library operation to the active "
                    + "model under the native Undo envelope. Requires an expectedState "
                    + "matching the latest turboism.textures.read. Deletions require "
                    + "explicit confirmDelete=true. Model image groups are addressed "
                    + "by groupName (no stable GUID exists), so duplicate names are "
                    + "rejected before creation."
            ),
            entry("inputSchema", writeInputSchema()),
            entry("outputSchema", writeOutputSchema()),
            entry("annotations", linked(
                entry("readOnlyHint", false),
                entry("destructiveHint", true),
                entry("idempotentHint", false)
            ))
        );
    }

    private static McpVersionSupport writeVersionSupport() {
        return McpVersionSupport.exact(
            PROVIDER_CAPABILITY_ID,
            SUPPORTED_VERSIONS,
            List.of(McpVersionSupport.OperationSupport.available(
                "turboism.textures.write",
                McpOperationEffect.UNDOABLE_WRITE,
                false,
                McpVersionSupport.UndoVerification.RUNTIME_VERIFIED,
                SUPPORTED_VERSIONS,
                "Standalone native-Undo write; transaction grouping not yet proven, so transactionEligible=false."
            ))
        );
    }

    private static Map<String, Object> writeInputSchema() {
        final Map<String, Object> state = stateSchema();
        // The handler enforces the bounded name length so it can emit the
        // precise INVALID_ARGUMENT code; the schema only constrains the type.
        final Map<String, Object> nameField = linked(
            entry("type", "string"),
            entry("minLength", 1)
        );
        final Map<String, Object> idField = linked(
            entry("type", "string"),
            entry("minLength", 1),
            entry("maxLength", MAX_ID_LENGTH)
        );
        final Map<String, Object> confirmDelete = linked(entry("type", "boolean"));
        // The handler enforces bounds (1..MAX_DIMENSION) so it can emit the
        // precise INVALID_ARGUMENT code; the schema only constrains the type.
        final Map<String, Object> dimension = linked(entry("type", "integer"));
        final Map<String, Object> addGroup = objectSchema(linked(
            entry("operation", enumSchema(List.of(OP_ADD_MODEL_IMAGE_GROUP))),
            entry("expectedState", state),
            entry("name", nameField)
        ), List.of("operation", "expectedState", "name"));
        final Map<String, Object> removeModelImage = objectSchema(linked(
            entry("operation", enumSchema(List.of(OP_REMOVE_MODEL_IMAGE))),
            entry("expectedState", state),
            entry("id", idField),
            entry("confirmDelete", confirmDelete)
        ), List.of("operation", "expectedState", "id"));
        final Map<String, Object> addAtlas = objectSchema(linked(
            entry("operation", enumSchema(List.of(OP_ADD_TEXTURE_ATLAS))),
            entry("expectedState", state),
            entry("name", nameField),
            entry("widthPixels", dimension),
            entry("heightPixels", dimension)
        ), List.of("operation", "expectedState", "name", "widthPixels", "heightPixels"));
        final Map<String, Object> removeAtlas = objectSchema(linked(
            entry("operation", enumSchema(List.of(OP_REMOVE_TEXTURE_ATLAS))),
            entry("expectedState", state),
            entry("id", idField),
            entry("confirmDelete", confirmDelete)
        ), List.of("operation", "expectedState", "id"));
        final Map<String, Object> removeRaw = objectSchema(linked(
            entry("operation", enumSchema(List.of(OP_REMOVE_RAW_IMAGE))),
            entry("expectedState", state),
            entry("id", idField),
            entry("confirmDelete", confirmDelete)
        ), List.of("operation", "expectedState", "id"));
        return linked(
            entry("type", "object"),
            entry("properties", linked(
                entry("expectedState", state),
                entry("operation", linked(
                    entry("type", "string"),
                    entry("enum", List.of(
                        OP_ADD_MODEL_IMAGE_GROUP,
                        OP_REMOVE_MODEL_IMAGE,
                        OP_ADD_TEXTURE_ATLAS,
                        OP_REMOVE_TEXTURE_ATLAS,
                        OP_REMOVE_RAW_IMAGE
                    ))
                ))
            )),
            entry("required", List.of("expectedState", "operation")),
            entry("oneOf", List.of(addGroup, removeModelImage, addAtlas, removeAtlas, removeRaw))
        );
    }

    private static Map<String, Object> readOutputSchema() {
        final Map<String, Object> success = linked(
            entry("type", "object"),
            entry("properties", linked(
                entry("ok", linked(entry("type", "boolean"), entry("const", true))),
                entry("operation", linked(entry("type", "string"), entry("const", "list"))),
                entry("stateToken", linked(
                    entry("type", "string"),
                    entry("minLength", 1),
                    entry("maxLength", 256)
                )),
                entry("state", stateSchema()),
                entry("rawImages", linked(
                    entry("type", "array"),
                    entry("maxItems", MAX_ITEMS),
                    entry("items", rawTextureSchema())
                )),
                entry("modelImageGroups", linked(
                    entry("type", "array"),
                    entry("maxItems", MAX_ITEMS),
                    entry("items", modelImageGroupSchema())
                )),
                entry("textureAtlases", linked(
                    entry("type", "array"),
                    entry("maxItems", MAX_ITEMS),
                    entry("items", atlasTextureSchema())
                )),
                entry("counts", linked(
                    entry("type", "object"),
                    entry("properties", linked(
                        entry("rawImages", linked(entry("type", "integer"), entry("minimum", 0))),
                        entry("modelImageGroups", linked(entry("type", "integer"), entry("minimum", 0))),
                        entry("textureAtlases", linked(entry("type", "integer"), entry("minimum", 0)))
                    )),
                    entry("required", List.of("rawImages", "modelImageGroups", "textureAtlases")),
                    entry("additionalProperties", false)
                ))
            )),
            entry("required", List.of(
                "ok", "operation", "stateToken", "state",
                "rawImages", "modelImageGroups", "textureAtlases", "counts"
            )),
            entry("additionalProperties", false)
        );
        final Map<String, Object> failure = linked(
            entry("type", "object"),
            entry("properties", linked(
                entry("ok", linked(entry("type", "boolean"), entry("const", false))),
                entry("code", linked(entry("type", "string"), entry("minLength", 1))),
                entry("message", linked(entry("type", "string"), entry("minLength", 1)))
            )),
            entry("required", List.of("ok", "code", "message")),
            entry("additionalProperties", false)
        );
        return linked(
            entry("$schema", "https://json-schema.org/draft/2020-12/schema"),
            entry("oneOf", List.of(success, failure))
        );
    }

    private static Map<String, Object> writeOutputSchema() {
        // The write success envelope enumerates every operation-specific identity
        // field as an optional property so that the schema remains exact while
        // remaining honest about which identities the domain actually returns.
        final Map<String, Object> stringField = linked(
            entry("type", "string"),
            entry("minLength", 1),
            entry("maxLength", MAX_NAME_LENGTH)
        );
        final Map<String, Object> idField = linked(
            entry("type", "string"),
            entry("minLength", 1),
            entry("maxLength", MAX_ID_LENGTH)
        );
        final Map<String, Object> ok = objectSchema(linked(
            entry("ok", linked(entry("type", "boolean"), entry("const", true))),
            entry("operation", linked(entry("type", "string"))),
            entry("receipt", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", 256)
            )),
            entry("outcome", linked(entry("type", "string"), entry("const", "APPLIED"))),
            entry("retryable", linked(entry("type", "boolean"), entry("const", false))),
            entry("postState", stateSchema()),
            entry("diagnosticId", linked(entry("type", "string"), entry("minLength", 1))),
            entry("groupName", stringField),
            entry("id", idField),
            entry("name", stringField),
            entry("widthPixels", linked(entry("type", "integer"), entry("minimum", 1))),
            entry("heightPixels", linked(entry("type", "integer"), entry("minimum", 1)))
        ), List.of("ok", "operation", "receipt", "outcome", "retryable", "postState"));
        final Map<String, Object> okWithWarning = objectSchema(linked(
            entry("ok", linked(entry("type", "boolean"), entry("const", true))),
            entry("operation", linked(entry("type", "string"))),
            entry("receipt", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", 256)
            )),
            entry("outcome", linked(entry("type", "string"), entry("const", "APPLIED_WITH_READBACK_WARNING"))),
            entry("retryable", linked(entry("type", "boolean"), entry("const", false))),
            entry("postState", stateSchema()),
            entry("readbackWarning", linked(entry("type", "string"), entry("minLength", 1))),
            entry("diagnosticId", linked(entry("type", "string"), entry("minLength", 1))),
            entry("groupName", stringField),
            entry("id", idField),
            entry("name", stringField),
            entry("widthPixels", linked(entry("type", "integer"), entry("minimum", 1))),
            entry("heightPixels", linked(entry("type", "integer"), entry("minimum", 1)))
        ), List.of("ok", "operation", "receipt", "outcome", "retryable", "postState", "readbackWarning"));
        final Map<String, Object> failure = objectSchema(linked(
            entry("ok", linked(entry("type", "boolean"), entry("const", false))),
            entry("operation", linked(entry("type", "string"))),
            entry("outcome", linked(
                entry("type", "string"),
                entry("enum", List.of("NOT_APPLIED", "OUTCOME_UNKNOWN"))
            )),
            entry("retryable", linked(entry("type", "boolean"), entry("const", false))),
            entry("error", linked(
                entry("type", "object"),
                entry("properties", linked(
                    entry("code", linked(entry("type", "string"), entry("minLength", 1))),
                    entry("message", linked(entry("type", "string"), entry("minLength", 1))),
                    entry("outcome", linked(entry("type", "string"), entry("enum", List.of("NOT_APPLIED", "OUTCOME_UNKNOWN")))),
                    entry("retryable", linked(entry("type", "boolean"), entry("const", false)))
                )),
                entry("required", List.of("code", "message", "outcome", "retryable")),
                entry("additionalProperties", false)
            ))
        ), List.of("ok", "operation", "outcome", "retryable", "error"));
        return linked(
            entry("$schema", "https://json-schema.org/draft/2020-12/schema"),
            entry("oneOf", List.of(ok, okWithWarning, failure))
        );
    }

    private static Map<String, Object> stateSchema() {
        return linked(
            entry("type", "object"),
            entry("properties", linked(
                entry("documentId", linked(
                    entry("type", "string"),
                    entry("minLength", 1),
                    entry("maxLength", MAX_ID_LENGTH)
                )),
                entry("modelId", linked(
                    entry("type", "string"),
                    entry("minLength", 1),
                    entry("maxLength", MAX_ID_LENGTH)
                )),
                entry("historyGeneration", linked(entry("type", "integer"), entry("minimum", 0))),
                entry("historyRevision", linked(entry("type", "integer"), entry("minimum", 0)))
            )),
            entry("required", List.of("documentId", "modelId", "historyGeneration", "historyRevision")),
            entry("additionalProperties", false)
        );
    }

    private static Map<String, Object> rawTextureSchema() {
        return objectSchema(linked(
            entry("id", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", MAX_ID_LENGTH)
            )),
            entry("name", linked(entry("type", "string"), entry("maxLength", MAX_NAME_LENGTH))),
            entry("width", linked(entry("type", "integer"), entry("minimum", 0))),
            entry("height", linked(entry("type", "integer"), entry("minimum", 0)))
        ), List.of("id", "name", "width", "height"));
    }

    private static Map<String, Object> modelImageGroupSchema() {
        return objectSchema(linked(
            entry("groupName", linked(entry("type", "string"), entry("maxLength", MAX_NAME_LENGTH))),
            entry("memo", linked(entry("type", "string"))),
            entry("modelImages", linked(
                entry("type", "array"),
                entry("maxItems", MAX_ITEMS),
                entry("items", modelImageSchema())
            )),
            entry("modelImageCount", linked(entry("type", "integer"), entry("minimum", 0)))
        ), List.of("groupName", "memo", "modelImages", "modelImageCount"));
    }

    private static Map<String, Object> modelImageSchema() {
        return objectSchema(linked(
            entry("id", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", MAX_ID_LENGTH)
            )),
            entry("name", linked(entry("type", "string"), entry("maxLength", MAX_NAME_LENGTH))),
            entry("width", linked(entry("type", "integer"), entry("minimum", 0))),
            entry("height", linked(entry("type", "integer"), entry("minimum", 0)))
        ), List.of("id", "name", "width", "height"));
    }

    private static Map<String, Object> atlasTextureSchema() {
        return objectSchema(linked(
            entry("id", linked(
                entry("type", "string"),
                entry("minLength", 1),
                entry("maxLength", MAX_ID_LENGTH)
            )),
            entry("name", linked(entry("type", "string"), entry("maxLength", MAX_NAME_LENGTH))),
            entry("width", linked(entry("type", "integer"), entry("minimum", 0))),
            entry("height", linked(entry("type", "integer"), entry("minimum", 0))),
            entry("atlasVersion", linked(entry("type", "integer"))),
            entry("modelImageCount", linked(entry("type", "integer"), entry("minimum", 0)))
        ), List.of("id", "name", "width", "height", "atlasVersion", "modelImageCount"));
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

    // ---- state helpers ------------------------------------------------------

    private static final class StateSnapshot {
        private final String documentId;
        private final String modelId;
        private final long historyGeneration;
        private final long historyRevision;
        private final String token;

        private StateSnapshot(
            final String documentId,
            final String modelId,
            final long historyGeneration,
            final long historyRevision,
            final String token
        ) {
            this.documentId = documentId;
            this.modelId = modelId;
            this.historyGeneration = historyGeneration;
            this.historyRevision = historyRevision;
            this.token = token;
        }

        static StateSnapshot of(
            final String documentId,
            final String modelId,
            final HistorySnapshot history
        ) {
            return new StateSnapshot(
                documentId,
                modelId,
                history.generation(),
                history.revision(),
                UUID.randomUUID().toString()
            );
        }

        static StateSnapshot fromRequired(final Object raw) {
            if (!(raw instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("expectedState must be an object");
            }
            final Map<String, Object> values = stringMap(map, "expectedState");
            final String documentId = requireNonBlank(values, "documentId");
            final String modelId = requireNonBlank(values, "modelId");
            if (documentId.length() > MAX_ID_LENGTH || modelId.length() > MAX_ID_LENGTH) {
                throw new IllegalArgumentException("expectedState identity fields exceed the bounded length");
            }
            final Object generationValue = values.get("historyGeneration");
            final Object revisionValue = values.get("historyRevision");
            if (!(generationValue instanceof Number) || !(revisionValue instanceof Number)) {
                throw new IllegalArgumentException("expectedState generation/revision must be numeric");
            }
            final long generation = ((Number) generationValue).longValue();
            final long revision = ((Number) revisionValue).longValue();
            if (generation < 0 || revision < 0) {
                throw new IllegalArgumentException("expectedState generation/revision must not be negative");
            }
            return new StateSnapshot(documentId, modelId, generation, revision, "");
        }

        String documentId() { return documentId; }
        String modelId() { return modelId; }
        long historyGeneration() { return historyGeneration; }
        long historyRevision() { return historyRevision; }
        String token() { return token; }

        Map<String, Object> expose() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId);
            result.put("modelId", modelId);
            result.put("historyGeneration", historyGeneration);
            result.put("historyRevision", historyRevision);
            return result;
        }
    }

    private static final class CurrentSnapshot {
        private final String documentId;
        private final String modelId;
        private final long historyGeneration;
        private final long historyRevision;
        private final ModelTextures textures;

        CurrentSnapshot(
            final String documentId,
            final String modelId,
            final long historyGeneration,
            final long historyRevision,
            final ModelTextures textures
        ) {
            this.documentId = documentId;
            this.modelId = modelId;
            this.historyGeneration = historyGeneration;
            this.historyRevision = historyRevision;
            this.textures = textures;
        }

        String documentId() { return documentId; }
        String modelId() { return modelId; }
        long historyGeneration() { return historyGeneration; }
        long historyRevision() { return historyRevision; }
        ModelTextures textures() { return textures; }

        Map<String, Object> expose() {
            final Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId);
            result.put("modelId", modelId);
            result.put("historyGeneration", historyGeneration);
            result.put("historyRevision", historyRevision);
            return result;
        }
    }

    private record ResolvedModelImage(String id, String groupName) {}

    private static final class TextureWriteException extends RuntimeException {
        private final String code;

        TextureWriteException(final String code, final String message) {
            super(message);
            this.code = code;
        }
    }

    private static String requireNonBlank(final Map<String, Object> values, final String key) {
        final Object value = values.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(key + " must be a non-blank string");
        }
        return text;
    }

    private static Map<String, Object> stringMap(final Map<?, ?> raw, final String label) {
        final LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IllegalArgumentException(label + " contains a non-string key");
            }
            result.put(key, entry.getValue());
        }
        return result;
    }
}
