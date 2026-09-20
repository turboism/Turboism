package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditPartObjectSelectorContract;
import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.PartId;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Parts/object family routed through the session's verified member surface (spec 046, T3):
 * {@code GetPartStructure}, {@code GetObject}, {@code DeleteObject},
 * {@code MoveObjectOnPartsPalette}, {@code AddPart}, {@code EditPart}, {@code EditArtMesh}, and
 * {@code EditGlue}.
 *
 * <p>{@code GetObject} fails closed per kind: the payload records require every field, and the
 * records carry no reader for {@code Grouped}, {@code ReverseMask}, part multiply/screen colors,
 * or {@code ColorBlend} on parts; for {@code ColorBlend}/{@code AlphaBlend} reads on art meshes;
 * for {@code BezierDiv*} on warp deformers; and for rotation-deformer {@code vertices}. Glue
 * payloads would be readable, but {@link dev.turboism.sdk.cubism.model.ModelObjectKind} cannot
 * address a glue object, so {@code object} reports typed unavailability for every kind until
 * the missing readers are admitted (T7).</p>
 *
 * <p>{@code DeleteObject} follows the official envelope — selection cleared through the
 * verified {@code set-selection} member, then the {@code model-handler.remove-objects} batch —
 * and rejects ArtPath objects (host-validated deletion evidence is still open). Reparenting
 * an existing object under a part requires {@code part-source.remove-child}, which no admitted
 * record carries, so {@code ParentId} requests are rejected (T7).</p>
 */
final class SessionPartObjectOps implements PartObjectOps {

    private final EditSessionOps ops;

    SessionPartObjectOps(final EditSessionOps ops) {
        this.ops = Objects.requireNonNull(ops, "ops");
    }

    @Override
    public EditObjectNode partStructure() throws EditSessionException {
        return ops.dispatch("GetPartStructure", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.GET_PART_STRUCTURE_REQUIRED_ALIASES,
                "GetPartStructure");
            final Object root = access.invoke(
                "cubism.editor-model.model-source.root-part", access.modelSource());
            if (!access.isInstance("cubism.editor-model.part-source.class", root)) {
                throw ops.unavailable("Editor root part is unavailable.");
            }
            return partNode(access, root, new HashSet<>());
        });
    }

    @Override
    public EditObjectSnapshot object(final GetObject request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "GetObject");
        return ops.dispatch("GetObject", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.GET_OBJECT_REQUIRED_ALIASES,
                "GetObject");
            final Object source = ops.requireObjectSource(access, request.object());
            final EditObjectKind kind = ops.kindOf(access, source);
            // Every reachable payload needs fields with no verified reader; report the exact
            // gap instead of fabricating values.
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "GetObject(" + kind + ") is not verified on this Cubism host: "
                    + missingReaders(kind));
        });
    }

    @Override
    public boolean deleteObject(final DeleteObject request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("DeleteObject", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.DELETE_OBJECT_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.DELETE_OBJECT_REQUIRED_ALIASES,
                "DeleteObject");
            final Object source = ops.requireObjectSource(access, request.object());
            if (ops.kindOf(access, source) == EditObjectKind.ART_PATH) {
                // ArtPath deletion evidence is open — the drawable/deformer/part batch path is
                // not host-validated for art paths (feasibility matrix errata).
                throw new EditUnavailableException(
                    "cubism.edit.op-unverified",
                    "DeleteObject(ART_PATH) is not verified on this Cubism host");
            }
            ops.writeSelection(access, List.of());
            final Object undo = access.invoke(
                "cubism.editor-model.model-handler.remove-objects",
                ops.modelHandler(access),
                List.of(source),
                access.model(),
                Boolean.TRUE);
            ops.addUndo(access, undo, "Turboism: Delete Object");
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    @Override
    public boolean moveObjectOnPartsPalette(final MoveObjectOnPartsPalette request)
            throws EditSessionException {
        Objects.requireNonNull(request, "request");
        return ops.dispatch("MoveObjectOnPartsPalette", access -> {
            final Object source = ops.requireObjectSource(access, request.object());
            if (request.parent().isEmpty()) {
                ops.require(
                    access,
                    EditorEditPartObjectSelectorContract
                        .MOVE_OBJECT_ON_PARTS_PALETTE_CAPABILITY_ID,
                    EditorEditPartObjectSelectorContract
                        .MOVE_OBJECT_ON_PARTS_PALETTE_REQUIRED_ALIASES,
                    "MoveObjectOnPartsPalette");
                final int index = paletteIndex(access, request, null);
                final Object undo = access.invoke(
                    "cubism.editor-model.model-handler.add-source-undo",
                    ops.modelHandler(access),
                    source,
                    Integer.valueOf(index));
                ops.addUndo(access, undo, "Turboism: Move Object On Parts Palette");
            } else {
                // Reparenting under a part requires part-source.remove-child, which no
                // admitted record carries — fail closed until T7 verifies it.
                throw new EditUnavailableException(
                    "cubism.edit.op-unverified",
                    "MoveObjectOnPartsPalette reparenting is not verified on this Cubism host");
            }
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    @Override
    public boolean addPart(final AddPart request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        if (!request.ids().isEmpty()) {
            // ACParameterControllableHandler.changePart has no verified member on any record.
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "AddPart with Ids is not verified on this Cubism host");
        }
        if (request.nested()) {
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                "AddPart(nested) is not verified on this Cubism host");
        }
        return ops.dispatch("AddPart", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.ADD_PART_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.ADD_PART_REQUIRED_ALIASES,
                "AddPart");
            final Object root = access.invoke(
                "cubism.editor-model.model-source.root-part", access.modelSource());
            if (!access.isInstance("cubism.editor-model.part-source.class", root)) {
                throw ops.unavailable("Editor root part is unavailable.");
            }
            final Object created = access.construct(
                "cubism.editor-model.part-source.create", access.modelSource());
            if (!access.isInstance("cubism.editor-model.part-source.class", created)) {
                throw ops.unavailable("Editor part source construction is invalid.");
            }
            final String name = request.name().orElse("Part");
            final String id = request.id()
                .map(PartId::value)
                .orElseGet(() -> nextObjectId(access, "Part", name));
            access.invoke(
                "cubism.editor-model.part-source.set-id",
                created,
                access.construct("cubism.editor-model.part-id.create", id));
            access.invoke(
                "cubism.editor-model.part-source.set-guid",
                created,
                access.construct("cubism.editor-model.part-guid.create"));
            access.invoke(
                "cubism.editor-model.part-source.set-local-name", created, name);
            access.invoke(
                "cubism.editor-model.part-source.set-default-order",
                created,
                Integer.valueOf(request.drawOrder().orElse(0)));
            final Object rootHandler = partHandler(access, root);
            final int index = ops.list(
                access.invoke("cubism.editor-model.part-source.children", root),
                "Editor part children").size();
            final Object undo = access.invoke(
                "cubism.editor-model.part-handler.add-part-child",
                rootHandler,
                created,
                Integer.valueOf(index));
            ops.addUndo(access, undo, "Turboism: Add Part");
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    @Override
    public boolean editPart(final EditPart request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "EditPart");
        rejectUnverifiable(
            request.grouped().isPresent() || request.offscreen().isPresent()
                || request.clippingIds().isPresent() || request.reverseMask().isPresent()
                || request.multiplyColor().isPresent() || request.screenColor().isPresent()
                || request.colorBlend().isPresent() || request.parentId().isPresent(),
            "EditPart Grouped/IsOffscreen/ClippingIds/IsReverseMask/multiply/screen/"
                + "ColorBlend/ParentId");
        return ops.dispatch("EditPart", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.EDIT_PART_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.EDIT_PART_REQUIRED_ALIASES,
                "EditPart");
            final boolean extended = request.opacity().isPresent()
                || request.alphaBlend().isPresent();
            if (extended) {
                ops.require(
                    access,
                    EditorEditPartObjectSelectorContract.EDIT_PART_CAPABILITY_ID,
                    EditorEditPartObjectSelectorContract.EDIT_PART_EXTENDED_ALIASES,
                    "EditPart(extended)");
            }
            final Object source = ops.requirePartSource(access, request.id());
            ops.captureUndoForAllEdit(access, source, "Turboism: Edit Part");
            if (request.newId().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-source.set-id",
                    source,
                    access.construct(
                        "cubism.editor-model.part-id.create",
                        request.newId().get().value()));
                verifyModel(access);
            }
            if (request.name().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-source.set-local-name",
                    source,
                    request.name().get());
            }
            if (request.guidImage().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-source.set-sketch",
                    source,
                    Boolean.valueOf(request.guidImage().get()));
            }
            if (request.drawOrder().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-source.set-default-order",
                    source,
                    Integer.valueOf(request.drawOrder().get()));
            }
            if (request.opacity().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-form.set-opacity",
                    partForm(access, source),
                    Float.valueOf(request.opacity().get().floatValue()));
            }
            if (request.alphaBlend().isPresent()) {
                access.invoke(
                    "cubism.editor-model.part-source.set-alpha-composition",
                    source,
                    alphaComposition(access, request.alphaBlend().get()));
            }
            if (request.labelColor().isPresent()) {
                ops.writeLabelColor(
                    access,
                    access.invoke(
                        "cubism.editor-model.parameter-controllable-source.label-color",
                        source),
                    request.labelColor().get());
            }
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    @Override
    public boolean editArtMesh(final EditArtMesh request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "EditArtMesh");
        rejectUnverifiable(
            request.colorBlend().isPresent() || request.parentId().isPresent(),
            "EditArtMesh ColorBlend/ParentId");
        return ops.dispatch("EditArtMesh", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.EDIT_ART_MESH_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.EDIT_ART_MESH_REQUIRED_ALIASES,
                "EditArtMesh");
            if (request.alphaBlend().isPresent()) {
                ops.require(
                    access,
                    EditorEditPartObjectSelectorContract.EDIT_ART_MESH_CAPABILITY_ID,
                    EditorEditPartObjectSelectorContract.EDIT_ART_MESH_ALPHA_BLEND_ALIASES,
                    "EditArtMesh(alphaBlend)");
            }
            final Object source = ops.requireArtMeshSource(access, request.id());
            ops.captureUndoForAllEdit(access, source, "Turboism: Edit ArtMesh");
            if (request.newId().isPresent()) {
                final String next = request.newId().get().value();
                final Object idMap = access.invoke(
                    "cubism.editor-model.model-handler.id-map", ops.modelHandler(access));
                if (ops.flag(
                    access.invoke("cubism.editor-model.id-map.contains", idMap, next),
                    "Editor id map is unavailable.")) {
                    throw new IllegalArgumentException(
                        "Cubism object id is already in use: " + next);
                }
                access.invoke(
                    "cubism.editor-model.drawable-source.set-id",
                    source,
                    access.construct("cubism.editor-model.drawable-id.create", next));
                verifyModel(access);
            }
            if (request.name().isPresent()) {
                access.invoke(
                    "cubism.editor-model.parameter-controllable-source.set-local-name",
                    source,
                    request.name().get());
            }
            if (request.parentDeformerId().isPresent()) {
                final Object parent =
                    ops.requireDeformerSource(access, request.parentDeformerId().get());
                final Object parentGuid = access.invoke(
                    "cubism.editor-model.deformer-source.guid", parent);
                final Object undo = access.invoke(
                    "cubism.editor-model.parameter-controllable-handler.change-target-deformer-guid",
                    ops.controllableHandler(access, source),
                    access.model(),
                    parentGuid,
                    Boolean.FALSE);
                ops.addUndo(access, undo, "Turboism: Edit ArtMesh");
            }
            if (request.clippingIds().isPresent()) {
                final ArrayList<Object> guids = new ArrayList<>();
                for (final ModelObjectId id : request.clippingIds().get()) {
                    final Object target = ops.requireObjectSourceById(access, id);
                    if (!access.isInstance(
                        "cubism.editor-model.art-mesh-source.class", target)) {
                        throw new IllegalArgumentException(
                            "Cubism clipping target " + id.value() + " is not an art mesh");
                    }
                    guids.add(access.invoke(
                        "cubism.editor-model.art-mesh-source.guid", target));
                }
                final Object clipList = access.invoke(
                    "cubism.editor-model.art-mesh-source.clip-guid-list", source);
                access.invoke("cubism.editor-model.id-list.clear", clipList);
                access.invoke("cubism.editor-model.id-list.add-all", clipList, guids);
            }
            if (request.reverseMask().isPresent()) {
                access.invoke(
                    "cubism.editor-model.art-mesh-source.set-invert-clipping-mask",
                    source,
                    Boolean.valueOf(request.reverseMask().get()));
            }
            if (request.drawOrder().isPresent() || request.opacity().isPresent()
                || request.multiplyColor().isPresent() || request.screenColor().isPresent()) {
                final Object form = artMeshForm(access, source);
                if (request.drawOrder().isPresent()) {
                    access.invoke(
                        "cubism.editor-model.drawable-form.set-draw-order",
                        form,
                        Integer.valueOf(request.drawOrder().get()));
                }
                if (request.opacity().isPresent()) {
                    access.invoke(
                        "cubism.editor-model.drawable-form.set-opacity",
                        form,
                        Float.valueOf(request.opacity().get().floatValue()));
                }
                if (request.multiplyColor().isPresent()) {
                    writeFloatColor(
                        access,
                        access.invoke(
                            "cubism.editor-model.drawable-form.multiply-color", form),
                        request.multiplyColor().get());
                }
                if (request.screenColor().isPresent()) {
                    writeFloatColor(
                        access,
                        access.invoke(
                            "cubism.editor-model.drawable-form.screen-color", form),
                        request.screenColor().get());
                }
            }
            if (request.alphaBlend().isPresent()) {
                access.invoke(
                    "cubism.editor-model.art-mesh-source.set-alpha-composition",
                    source,
                    alphaComposition(access, request.alphaBlend().get()));
            }
            if (request.culling().isPresent()) {
                access.invoke(
                    "cubism.editor-model.art-mesh-source.set-culling",
                    source,
                    Boolean.valueOf(request.culling().get()));
            }
            if (request.labelColor().isPresent()) {
                ops.writeLabelColor(
                    access,
                    access.invoke(
                        "cubism.editor-model.parameter-controllable-source.label-color",
                        source),
                    request.labelColor().get());
            }
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    @Override
    public boolean editGlue(final EditGlue request) throws EditSessionException {
        Objects.requireNonNull(request, "request");
        rejectConditions(request.parameters(), "EditGlue");
        // CAffecterId construction, the glue-instance form reader, and part reparenting have
        // no admitted members.
        rejectUnverifiable(
            request.newId().isPresent() || request.intensity().isPresent()
                || request.parentId().isPresent(),
            "EditGlue NewId/Intensity/ParentId");
        return ops.dispatch("EditGlue", access -> {
            ops.require(
                access,
                EditorEditPartObjectSelectorContract.EDIT_GLUE_CAPABILITY_ID,
                EditorEditPartObjectSelectorContract.EDIT_GLUE_REQUIRED_ALIASES,
                "EditGlue");
            final Object source = ops.requireGlueSource(access, request.id());
            ops.captureUndoForAllEdit(access, source, "Turboism: Edit Glue");
            if (request.name().isPresent()) {
                access.invoke(
                    "cubism.editor-model.glue-source.set-local-name",
                    source,
                    request.name().get());
            }
            if (request.labelColor().isPresent()) {
                ops.writeLabelColor(
                    access,
                    access.invoke(
                        "cubism.editor-model.parameter-controllable-source.label-color",
                        source),
                    request.labelColor().get());
            }
            ops.finishWrite(access, false, true);
            return true;
        });
    }

    // ------------------------------------------------------------------
    // orchestration helpers
    // ------------------------------------------------------------------

    private EditObjectNode partNode(
        final EditSessionOpsAccess access,
        final Object source,
        final Set<String> visiting
    ) {
        final String key = ops.objectId(access, source);
        if (!visiting.add(key)) {
            throw ops.unavailable("Editor part tree contains a cycle.");
        }
        final ArrayList<EditObjectNode> children = new ArrayList<>();
        if (access.isInstance("cubism.editor-model.part-source.class", source)) {
            for (final Object child : ops.list(
                access.invoke("cubism.editor-model.part-source.children", source),
                "Editor part children")) {
                children.add(partNode(access, child, visiting));
            }
        }
        visiting.remove(key);
        return new EditObjectNode(
            ops.localName(access, source),
            new ModelObjectId(key),
            ops.kindOf(access, source),
            children);
    }

    private int paletteIndex(
        final EditSessionOpsAccess access,
        final MoveObjectOnPartsPalette request,
        final Object parent
    ) {
        if (request.insertIndex().isPresent()) {
            return request.insertIndex().get();
        }
        if (request.insertBefore().isPresent()) {
            final String before = request.insertBefore().get().value();
            final List<Object> siblings = parent == null
                ? ops.allObjectSources(access)
                : ops.list(
                    access.invoke("cubism.editor-model.part-source.children", parent),
                    "Editor part children");
            for (int i = 0; i < siblings.size(); i++) {
                if (ops.objectId(access, siblings.get(i)).equals(before)) {
                    return i;
                }
            }
            throw new java.util.NoSuchElementException(
                "Cubism insert-before object is absent: " + before);
        }
        return parent == null
            ? ops.allObjectSources(access).size()
            : ops.list(
                    access.invoke("cubism.editor-model.part-source.children", parent),
                    "Editor part children")
                .size();
    }

    private Object partHandler(final EditSessionOpsAccess access, final Object partSource) {
        final Object handler =
            access.invoke("cubism.editor-model.part-source.handler", partSource);
        if (!access.isInstance("cubism.editor-model.part-handler.class", handler)) {
            throw ops.unavailable("Editor part handler is unavailable.");
        }
        return handler;
    }

    private Object partForm(final EditSessionOpsAccess access, final Object partSource) {
        for (final Object instance : ops.list(
            access.invoke("cubism.editor-model.model.parts", access.model()),
            "Editor part instances")) {
            if (access.invoke("cubism.editor-model.part.source", instance) == partSource) {
                final Object form =
                    access.invoke("cubism.editor-model.part.current-keyform", instance);
                if (form == null) {
                    throw ops.unavailable("Editor part keyform is unavailable.");
                }
                return form;
            }
        }
        throw ops.unavailable("Editor part instance is unavailable.");
    }

    private Object artMeshForm(final EditSessionOpsAccess access, final Object source) {
        for (final Object instance : ops.list(
            access.invoke("cubism.editor-model.model.all-art-meshes", access.model()),
            "Editor art mesh instances")) {
            if (access.invoke("cubism.editor-model.art-mesh.source", instance) == source) {
                final Object form = access.invoke(
                    "cubism.editor-model.art-mesh.current-keyform", instance);
                if (form == null) {
                    throw ops.unavailable("Editor art mesh keyform is unavailable.");
                }
                return form;
            }
        }
        throw ops.unavailable("Editor art mesh instance is unavailable.");
    }

    private Object alphaComposition(
        final EditSessionOpsAccess access,
        final EditAlphaBlend blend
    ) {
        return access.readStaticField(
            "cubism.editor-model.alpha-composition."
                + blend.name().toLowerCase(java.util.Locale.ROOT));
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
        throw ops.unavailable("Could not allocate a unique Cubism Part ID.");
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

    private void rejectConditions(final List<?> parameters, final String label)
            throws EditUnavailableException {
        if (parameters != null && !parameters.isEmpty()) {
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                label + " Parameters[] condition selection is not verified on this Cubism host");
        }
    }

    private void rejectUnverifiable(final boolean present, final String fields)
            throws EditUnavailableException {
        if (present) {
            throw new EditUnavailableException(
                "cubism.edit.op-unverified",
                fields + " is not verified on this Cubism host");
        }
    }

    private static String missingReaders(final EditObjectKind kind) {
        return switch (kind) {
            case PART -> "grouped, reverseMask, multiply/screen colors, colorBlend, alphaBlend";
            case ART_MESH -> "colorBlend, alphaBlend";
            case WARP_DEFORMER -> "bezierDivH/bezierDivV";
            case ROTATION_DEFORMER -> "vertices";
            case GLUE -> "ModelObjectKind cannot address glue objects";
            case ART_PATH -> "the official API does not support ArtPath data reads";
        };
    }

}
