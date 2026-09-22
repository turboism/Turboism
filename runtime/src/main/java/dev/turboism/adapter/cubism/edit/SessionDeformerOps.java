package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditDeformerSelectorContract;
import dev.turboism.sdk.cubism.edit.EditDeformerAttachMode;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.DeformerOps;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deformer family routed through the session's verified member surface (spec 046, T3):
 * {@code GetDeformerStructure}, {@code AddRotationDeformer}, {@code AddWarpDeformer},
 * {@code EditRotationDeformer}, and {@code EditWarpDeformer}.
 *
 * <p>Creation mirrors the verified {@code EditorObjectHierarchyEditAccess} path: construct the
 * source, assign id/name, build the default form and keyform grid, then register the creation
 * through {@code model-handler.add-source-undo}. Attachment uses the verified reparent members —
 * {@code change-target-deformer-guid} for deformer parents and {@code part-handler.add-part-child}
 * for part parents.</p>
 *
 * <p>Fail-closed fields: {@code AddWarpDeformer}'s {@code bezierDivH}/{@code bezierDivV},
 * {@code considerChildKeyforms}, and {@code snapCenter}, and {@code EditWarpDeformer}'s
 * {@code bezierDiv*} carry no verified member on any supported record and are rejected before
 * admission. The {@code parameters} keyform condition is likewise unverifiable — no verified
 * member resolves a form by key coordinate — so conditioned requests are rejected. Reparenting
 * an existing object under a part requires {@code part-source.remove-child}, which no admitted
 * record carries, so {@code ParentId} requests are rejected (T7).</p>
 */
final class SessionDeformerOps implements DeformerOps {

    private final EditSessionOps ops;

    SessionDeformerOps(final EditSessionOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override
    public EditObjectNode deformerStructure() throws EditSessionException {
        return ops.dispatch("GetDeformerStructure", access -> {
            ops.require(
                access,
                EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.GET_DEFORMER_STRUCTURE_REQUIRED_ALIASES,
                "GetDeformerStructure");
            final List<Object> deformers = ops.allDeformerSources(access);
            final Map<String, List<Object>> childrenByParent = new HashMap<>();
            for (final Object source : deformers) {
                final Object parent = access.invoke(
                    "cubism.editor-model.parameter-controllable-source.target-deformer-source",
                    source);
                final String parentKey = parent == null
                    ? ""
                    : ops.guidValue(access, access.invoke(
                        "cubism.editor-model.deformer-source.guid", parent));
                childrenByParent.computeIfAbsent(parentKey, k -> new ArrayList<>()).add(source);
            }
            final Set<String> visiting = new HashSet<>();
            return new EditObjectNode(
                "",
                new ModelObjectId(""),
                EditObjectKind.PART,
                deformerChildren(access, childrenByParent, "", visiting));
        });
    }

    @Override
    public boolean addRotationDeformer(final AddRotationDeformer request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("AddRotationDeformer", access -> {
            ops.require(
                access,
                EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.ADD_ROTATION_DEFORMER_REQUIRED_ALIASES,
                "AddRotationDeformer");
            final Object modelSource = access.modelSource();
            final Object created = access.construct(
                "cubism.editor-model.rotation-source.create", modelSource);
            if (!access.isInstance("cubism.editor-model.rotation-source.class", created)) {
                throw ops.unavailable("Editor Rotation Deformer source construction is invalid.");
            }
            assignDeformerIdentity(
                access,
                created,
                request.id().map(DeformerId::value).orElse(null),
                request.name().orElse(null),
                "RotationDeformer");
            final Object form = access.construct(
                "cubism.editor-model.rotation-form.create",
                created,
                null,
                access.invokeStatic("cubism.editor-model.coord-type.canvas"));
            access.invoke("cubism.editor-model.rotation-form.set-angle", form, Float.valueOf(0f));
            access.invoke(
                "cubism.editor-model.rotation-form.set-origin-x", form, Float.valueOf(0f));
            access.invoke(
                "cubism.editor-model.rotation-form.set-origin-y", form, Float.valueOf(0f));
            access.invoke(
                "cubism.editor-model.rotation-form.set-scale", form, Float.valueOf(1f));
            access.invoke(
                "cubism.editor-model.rotation-form.set-reflect-x", form, Boolean.FALSE);
            access.invoke(
                "cubism.editor-model.rotation-form.set-reflect-y", form, Boolean.FALSE);
            initializeKeyformSource(
                access, created, form, "cubism.editor-model.rotation-source.keyforms");
            registerCreatedSource(access, created, "Turboism: Add Rotation Deformer");
            attach(access, created, request.parentId().orElse(null),
                request.targetObjectIds(), request.mode(),
                "Turboism: Add Rotation Deformer");
            ops.finishWrite(access);
            return true;
        });
    }

    @Override
    public boolean addWarpDeformer(final AddWarpDeformer request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        if (request.bezierDivH().isPresent() || request.bezierDivV().isPresent()
            || request.considerChildKeyforms().isPresent() || request.snapCenter().isPresent()) {
            // These fields carry no verified member on any supported record.
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "AddWarpDeformer bezierDiv/considerChildKeyforms/snapCenter are not verified "
                    + "on this Cubism host");
        }
        return ops.dispatch("AddWarpDeformer", access -> {
            ops.require(
                access,
                EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.ADD_WARP_DEFORMER_REQUIRED_ALIASES,
                "AddWarpDeformer");
            final Object modelSource = access.modelSource();
            final Object created = access.construct(
                "cubism.editor-model.warp-source.create", modelSource);
            if (!access.isInstance("cubism.editor-model.warp-source.class", created)) {
                throw ops.unavailable("Editor Warp Deformer source construction is invalid.");
            }
            assignDeformerIdentity(
                access,
                created,
                request.id().map(DeformerId::value).orElse(null),
                request.name().orElse(null),
                "WarpDeformer");
            access.invoke(
                "cubism.editor-model.warp-source.set-row",
                created,
                Integer.valueOf(request.warpDivV().orElse(2)));
            access.invoke(
                "cubism.editor-model.warp-source.set-col",
                created,
                Integer.valueOf(request.warpDivH().orElse(2)));
            access.invoke(
                "cubism.editor-model.warp-source.set-quad-transform",
                created,
                Boolean.FALSE);
            final Object form = access.construct(
                "cubism.editor-model.warp-form.create",
                created,
                null,
                access.invokeStatic("cubism.editor-model.coord-type.canvas"));
            access.invoke(
                "cubism.editor-model.warp-form.set-positions",
                form,
                defaultWarpPositions(
                    request.warpDivH().orElse(2), request.warpDivV().orElse(2)));
            initializeKeyformSource(
                access, created, form, "cubism.editor-model.warp-source.keyforms");
            registerCreatedSource(access, created, "Turboism: Add Warp Deformer");
            attach(access, created, request.parentId().orElse(null),
                request.targetObjectIds(), request.mode(),
                "Turboism: Add Warp Deformer");
            ops.finishWrite(access);
            return true;
        });
    }

    @Override
    public boolean editRotationDeformer(final EditRotationDeformer request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "EditRotationDeformer");
        return ops.dispatch("EditRotationDeformer", access -> {
            ops.require(
                access,
                EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_REQUIRED_ALIASES,
                "EditRotationDeformer");
            final Object source = ops.requireDeformerSource(access, request.id());
            if (!access.isInstance("cubism.editor-model.rotation-source.class", source)) {
                throw new IllegalArgumentException(
                    "Cubism object " + request.id().value() + " is not a rotation deformer");
            }
            ops.captureUndoForAllEdit(
                access, source, "Turboism: Edit Rotation Deformer");
            editDeformerBase(
                access,
                source,
                request.newId().map(DeformerId::value).orElse(null),
                request.name().orElse(null),
                request.parentId().orElse(null),
                request.parentDeformerId().orElse(null),
                request.opacity().orElse(null),
                request.multiplyColor().orElse(null),
                request.screenColor().orElse(null),
                request.labelColor().orElse(null),
                "EditRotationDeformer",
                EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.EDIT_ROTATION_DEFORMER_REQUIRED_ALIASES);
            if (request.baseAngle().isPresent()) {
                access.invoke(
                    "cubism.editor-model.rotation-source.set-base-angle",
                    source,
                    Float.valueOf(request.baseAngle().get().floatValue()));
            }
            if (request.angle().isPresent() || request.scale().isPresent()) {
                final Object form = currentDeformerForm(access, source);
                if (request.angle().isPresent()) {
                    access.invoke(
                        "cubism.editor-model.rotation-form.set-angle",
                        form,
                        Float.valueOf(request.angle().get().floatValue()));
                }
                if (request.scale().isPresent()) {
                    access.invoke(
                        "cubism.editor-model.rotation-form.set-scale",
                        form,
                        Float.valueOf(request.scale().get().floatValue()));
                }
            }
            ops.finishWrite(access);
            return true;
        });
    }

    @Override
    public boolean editWarpDeformer(final EditWarpDeformer request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "EditWarpDeformer");
        if (request.bezierDivH().isPresent() || request.bezierDivV().isPresent()) {
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "EditWarpDeformer bezierDiv is not verified on this Cubism host");
        }
        return ops.dispatch("EditWarpDeformer", access -> {
            ops.require(
                access,
                EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_REQUIRED_ALIASES,
                "EditWarpDeformer");
            final Object source = ops.requireDeformerSource(access, request.id());
            if (!access.isInstance("cubism.editor-model.warp-source.class", source)) {
                throw new IllegalArgumentException(
                    "Cubism object " + request.id().value() + " is not a warp deformer");
            }
            ops.captureUndoForAllEdit(access, source, "Turboism: Edit Warp Deformer");
            editDeformerBase(
                access,
                source,
                request.newId().map(DeformerId::value).orElse(null),
                request.name().orElse(null),
                request.parentId().orElse(null),
                request.parentDeformerId().orElse(null),
                request.opacity().orElse(null),
                request.multiplyColor().orElse(null),
                request.screenColor().orElse(null),
                request.labelColor().orElse(null),
                "EditWarpDeformer",
                EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_CAPABILITY_ID,
                EditorEditDeformerSelectorContract.EDIT_WARP_DEFORMER_REQUIRED_ALIASES);
            if (request.warpDivH().isPresent()) {
                access.invoke(
                    "cubism.editor-model.warp-source.set-col",
                    source,
                    Integer.valueOf(request.warpDivH().get()));
            }
            if (request.warpDivV().isPresent()) {
                access.invoke(
                    "cubism.editor-model.warp-source.set-row",
                    source,
                    Integer.valueOf(request.warpDivV().get()));
            }
            ops.finishWrite(access);
            return true;
        });
    }

    // ------------------------------------------------------------------
    // shared deformer orchestration
    // ------------------------------------------------------------------

    private List<EditObjectNode> deformerChildren(
        final EditSessionOpsAccess access,
        final Map<String, List<Object>> childrenByParent,
        final String parentKey,
        final Set<String> visiting
    ) {
        final ArrayList<EditObjectNode> nodes = new ArrayList<>();
        for (final Object source : childrenByParent.getOrDefault(parentKey, List.of())) {
            final String guid = ops.guidValue(
                access, access.invoke("cubism.editor-model.deformer-source.guid", source));
            if (!visiting.add(guid)) {
                continue;
            }
            nodes.add(new EditObjectNode(
                ops.localName(access, source),
                new ModelObjectId(ops.objectId(access, source)),
                ops.kindOf(access, source),
                deformerChildren(access, childrenByParent, guid, visiting)));
            visiting.remove(guid);
        }
        return nodes;
    }

    private void assignDeformerIdentity(
        final EditSessionOpsAccess access,
        final Object created,
        final String requestedId,
        final String requestedName,
        final String prefix
    ) {
        final String name = requestedName == null || requestedName.isBlank()
            ? prefix
            : requestedName;
        final String id = requestedId == null || requestedId.isBlank()
            ? nextObjectId(access, prefix, name)
            : requestedId;
        access.invoke(
            "cubism.editor-model.deformer-source.set-id",
            created,
            access.construct("cubism.editor-model.deformer-id.create", id));
        access.invoke(
            "cubism.editor-model.parameter-controllable-source.set-local-name",
            created,
            name);
    }

    private String nextObjectId(
        final EditSessionOpsAccess access,
        final String prefix,
        final String name
    ) {
        final Set<String> existing = new HashSet<>();
        for (final Object source : ops.allObjectSources(access)) {
            existing.add(ops.objectId(access, source));
        }
        final String base = prefix + name.replaceAll("[^A-Za-z0-9_]+", "_");
        if (!existing.contains(base)) {
            return base;
        }
        for (int suffix = 2; suffix < 1_000_000; suffix++) {
            final String candidate = base + "_" + suffix;
            if (!existing.contains(candidate)) {
                return candidate;
            }
        }
        throw ops.unavailable("Could not allocate a unique Cubism deformer ID.");
    }

    private void initializeKeyformSource(
        final EditSessionOpsAccess access,
        final Object source,
        final Object form,
        final String keyformsAlias
    ) {
        final Object formGuid = access.construct("cubism.editor-model.form-guid.create");
        access.invoke("cubism.editor-model.form.set-guid", form, formGuid);
        final Object keyforms = access.invoke(keyformsAlias, source);
        access.invoke("cubism.editor-model.c-array-list.add", keyforms, form);
        final Object grid = access.construct(
            "cubism.editor-model.keyform-grid-source.create", source);
        access.invoke(
            "cubism.editor-model.keyform-grid-source.import-cubism21",
            grid,
            access.modelSource(),
            List.of(),
            List.of(formGuid),
            null);
        access.invoke(
            "cubism.editor-model.parameter-controllable-source.set-keyform-grid-source",
            source,
            grid);
    }

    private void registerCreatedSource(
        final EditSessionOpsAccess access,
        final Object created,
        final String label
    ) {
        final Object undo = access.invoke(
            "cubism.editor-model.model-handler.add-source-undo",
            ops.modelHandler(access),
            created,
            Integer.valueOf(ops.allObjectSources(access).size()));
        ops.addUndo(access, undo, label);
    }

    /**
     * Attaches a created deformer: under {@code parentId} when present; otherwise by {@code
     * mode} — {@code AS_PARENT} reparents every target under the new deformer through the
     * verified {@code change-target-deformer-guid} undo path, {@code AS_CHILD} attaches the new
     * deformer under the first target's own deformer parent (or part parent when the target has
     * no deformer parent).
     */
    private void attach(
        final EditSessionOpsAccess access,
        final Object created,
        final PartId parentId,
        final List<ModelObjectId> targetObjectIds,
        final EditDeformerAttachMode mode,
        final String label
    ) {
        if (parentId != null) {
            attachToPart(access, created, ops.requirePartSource(access, parentId), label);
            return;
        }
        if (targetObjectIds.isEmpty()) {
            return;
        }
        final Object createdGuid = access.invoke(
            "cubism.editor-model.deformer-source.guid", created);
        if (mode == EditDeformerAttachMode.AS_PARENT) {
            for (final ModelObjectId id : targetObjectIds) {
                final Object target = ops.requireObjectSourceById(access, id);
                final Object undo = access.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                    ops.controllableHandler(access, target),
                    access.model(),
                    createdGuid,
                    Boolean.FALSE);
                ops.addUndo(access, undo, label);
            }
            return;
        }
        // AS_CHILD: the new deformer takes the first target's own parent.
        final Object first = ops.requireObjectSourceById(access, targetObjectIds.get(0));
        final Object targetParent = access.invoke(
            "cubism.editor-model.parameter-controllable-source.target-deformer-source", first);
        if (targetParent != null
            && access.isInstance("cubism.editor-model.deformer-source.class", targetParent)) {
            final Object parentGuid = access.invoke(
                "cubism.editor-model.deformer-source.guid", targetParent);
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.set-target-deformer-guid",
                created,
                parentGuid);
            return;
        }
        final Object partParent = access.invoke(
            "cubism.editor-model.part-source.parent", first);
        if (partParent != null
            && access.isInstance("cubism.editor-model.part-source.class", partParent)) {
            attachToPart(access, created, partParent, label);
        }
    }

    private void attachToPart(
        final EditSessionOpsAccess access,
        final Object child,
        final Object partSource,
        final String label
    ) {
        final Object partHandler = access.invoke(
            "cubism.editor-model.part-source.handler", partSource);
        if (!access.isInstance("cubism.editor-model.part-handler.class", partHandler)) {
            throw ops.unavailable("Editor part handler is unavailable.");
        }
        final int index = ops.list(
            access.invoke("cubism.editor-model.part-source.children", partSource),
            "Editor part children").size();
        final Object undo = access.invoke(
            "cubism.editor-model.part-handler.add-part-child",
            partHandler,
            child,
            Integer.valueOf(index));
        ops.addUndo(access, undo, label);
    }

    /**
     * The shared edit surface of both deformer kinds: identity, name, reparenting, opacity,
     * multiply/screen colors, and label color.
     */
    private void editDeformerBase(
        final EditSessionOpsAccess access,
        final Object source,
        final String newId,
        final String name,
        final PartId parentId,
        final DeformerId parentDeformerId,
        final Double opacity,
        final String multiplyColor,
        final String screenColor,
        final EditLabelColor labelColor,
        final String label,
        final String capabilityId,
        final Set<String> requiredAliases
    ) throws EditSessionException {
        if (newId != null) {
            access.invoke(
                "cubism.editor-model.deformer-source.set-id",
                source,
                access.construct("cubism.editor-model.deformer-id.create", newId));
            verifyModel(access);
        }
        if (name != null) {
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.set-local-name",
                source,
                name);
        }
        if (parentDeformerId != null) {
            final Object parent = ops.requireDeformerSource(access, parentDeformerId);
            final Object parentGuid = access.invoke(
                "cubism.editor-model.deformer-source.guid", parent);
            final Object undo = access.invoke(
                "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                ops.controllableHandler(access, source),
                access.model(),
                parentGuid,
                Boolean.FALSE);
            ops.addUndo(access, undo, label);
        } else if (parentId != null) {
            // Reparenting under a part requires part-source.remove-child, which no admitted
            // record carries — fail closed until T7 verifies it.
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                label + " ParentId reparenting is not verified on this Cubism host");
        }
        if (opacity != null || multiplyColor != null || screenColor != null) {
            final Object form = currentDeformerForm(access, source);
            if (opacity != null) {
                access.invoke(
                    "cubism.editor-model.deformer-form.set-opacity",
                    form,
                    Float.valueOf(opacity.floatValue()));
            }
            if (multiplyColor != null) {
                writeFloatColor(
                    access,
                    access.invoke("cubism.editor-model.deformer-form.multiply-color", form),
                    multiplyColor);
            }
            if (screenColor != null) {
                writeFloatColor(
                    access,
                    access.invoke("cubism.editor-model.deformer-form.screen-color", form),
                    screenColor);
            }
        }
        if (labelColor != null) {
            ops.writeLabelColor(
                access,
                access.invoke(
                    "cubism.editor-model.parameter-controllable-source.label-color", source),
                labelColor);
        }
    }

    private Object currentDeformerForm(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        for (final Object instance : ops.list(
            access.invoke("cubism.editor-model.model.all-deformers", access.model()),
            "Editor deformer instances")) {
            if (access.invoke("cubism.editor-model.deformer.source", instance) == source) {
                final Object form = access.invoke(
                    "cubism.editor-model.deformer.current-keyform", instance);
                if (form == null) {
                    throw ops.unavailable("Editor deformer keyform is unavailable.");
                }
                return form;
            }
        }
        throw ops.unavailable("Editor deformer instance is unavailable.");
    }

    private void writeFloatColor(
        final EditSessionOpsAccess access,
        final Object color,
        final String hex
    ) {
        final String digits = hex.startsWith("#") ? hex.substring(1) : hex;
        if (!digits.matches("[0-9A-Fa-f]{6}([0-9A-Fa-f]{2})?")) {
            throw new IllegalArgumentException("color must be #RRGGBB or #RRGGBBAA");
        }
        access.invoke("cubism.editor-model.float-color.set-red", color,
            Float.valueOf(Integer.parseInt(digits.substring(0, 2), 16) / 255.0f));
        access.invoke("cubism.editor-model.float-color.set-green", color,
            Float.valueOf(Integer.parseInt(digits.substring(2, 4), 16) / 255.0f));
        access.invoke("cubism.editor-model.float-color.set-blue", color,
            Float.valueOf(Integer.parseInt(digits.substring(4, 6), 16) / 255.0f));
        access.invoke("cubism.editor-model.float-color.set-alpha", color,
            Float.valueOf(digits.length() == 8
                ? Integer.parseInt(digits.substring(6, 8), 16) / 255.0f
                : 1.0f));
    }

    private void verifyModel(final EditSessionOpsAccess access) {
        access.invokeStatic(
            "cubism.editor-model.model-source.verify",
            access.modelSource(),
            Boolean.TRUE,
            null,
            Integer.valueOf(2),
            null);
    }

    private float[] defaultWarpPositions(final int cols, final int rows) {
        final int points = (cols + 1) * (rows + 1);
        final float[] positions = new float[points * 2];
        int index = 0;
        for (int row = 0; row <= rows; row++) {
            for (int col = 0; col <= cols; col++) {
                positions[index++] = (float) col / cols;
                positions[index++] = (float) row / rows;
            }
        }
        return positions;
    }

    private void rejectConditions(
        final List<?> parameters,
        final String label
    ) throws EditUnavailableException {
        if (parameters != null && !parameters.isEmpty()) {
            // No verified member resolves a form by key coordinate; conditioned requests stay
            // fail-closed until the record admits a keyform-selection path (T7).
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                label + " Parameters[] condition selection is not verified on this Cubism host");
        }
    }

}
