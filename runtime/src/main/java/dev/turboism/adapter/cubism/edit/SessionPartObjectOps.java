package dev.turboism.adapter.cubism.edit;

import dev.turboism.mapping.verification.selector.EditorEditPartObjectSelectorContract;
import dev.turboism.sdk.cubism.edit.EditAlphaBlend;
import dev.turboism.sdk.cubism.edit.EditArtMeshData;
import dev.turboism.sdk.cubism.edit.EditColorBlend;
import dev.turboism.sdk.cubism.edit.EditLabelColor;
import dev.turboism.sdk.cubism.edit.EditObjectData;
import dev.turboism.sdk.cubism.edit.EditObjectKind;
import dev.turboism.sdk.cubism.edit.EditObjectNode;
import dev.turboism.sdk.cubism.edit.EditObjectSnapshot;
import dev.turboism.sdk.cubism.edit.EditPartData;
import dev.turboism.sdk.cubism.edit.EditRectangle;
import dev.turboism.sdk.cubism.edit.EditRotationDeformerData;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.edit.EditWarpDeformerData;
import dev.turboism.sdk.cubism.edit.PartObjectOps;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Parts/object family routed through the session's verified member surface (spec 046, T3):
 * {@code GetPartStructure}, {@code GetObject}, {@code DeleteObject},
 * {@code MoveObjectOnPartsPalette}, {@code AddPart}, {@code EditPart}, {@code EditArtMesh}, and
 * {@code EditGlue}.
 *
 * <p>{@code GetObject} reads the official external API 1.1.0 data blocks: warp and rotation
 * deformers are readable on every supported host; parts and art meshes stay closed on 5.2.03
 * where the extended readers ({@code useOffscreen}, part clip list, part color/alpha
 * composition, part-form visual members, art-mesh alpha composition) are absent from the host;
 * glue payloads are unreachable because {@link
 * dev.turboism.sdk.cubism.model.ModelObjectKind} cannot address a glue object, and ArtPath
 * reads fail closed because the official API defines no ArtPath payload. {@code Parameters[]}
 * keyform conditions stay rejected: the official condition gate rewrites the model's parameter
 * set during the read, which is not host-validated here (T7).</p>
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
            final EditObjectData data = switch (kind) {
                case PART -> partData(access, source);
                case ART_MESH -> artMeshData(access, source);
                case WARP_DEFORMER -> warpDeformerData(access, source);
                case ROTATION_DEFORMER -> rotationDeformerData(access, source);
                // GLUE is not addressable through ModelObjectKind and ART_PATH has no
                // official payload — requireObjectSource already rejected the reference.
                case ART_PATH, GLUE -> throw new EditUnavailableException(
                    "cubism.edit.op-unverified",
                    "GetObject(" + kind + ") is not verified on this Cubism host");
            };
            return new EditObjectSnapshot(
                new ModelObjectId(ops.objectId(access, source)), data);
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
    // GetObject per-kind readers — field order follows the official 1.1.0 data blocks
    // ------------------------------------------------------------------

    private EditPartData partData(final EditSessionOpsAccess access, final Object source)
        throws EditSessionException {
        ops.require(
            access,
            EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_OBJECT_PART_EXTENDED_ALIASES,
            "GetObject(PART)");
        final Object form = interpolatedForm(
            access,
            source,
            "cubism.editor-model.model.parts",
            "cubism.editor-model.part.source",
            "cubism.editor-model.part.class");
        return new EditPartData(
            nameOrId(access, source),
            parentPartId(access, source),
            ops.flag(
                access.invoke(
                    "cubism.editor-model.part-source.enable-draw-order-group", source),
                "Editor part grouped flag"),
            ops.flag(
                access.invoke("cubism.editor-model.part-source.sketch", source),
                "Editor part guide-image flag"),
            ops.flag(
                access.invoke("cubism.editor-model.part-source.use-offscreen", source),
                "Editor part offscreen flag"),
            clippingIds(
                access,
                access.invoke("cubism.editor-model.part-source.clip-guid-list", source)),
            ops.flag(
                access.invoke(
                    "cubism.editor-model.part-source.invert-clipping-mask", source),
                "Editor part reverse-mask flag"),
            ops.integer(
                access.invoke("cubism.editor-model.part-form.draw-order", form),
                "Editor part draw order"),
            ops.number(
                    access.invoke("cubism.editor-model.part-form.opacity", form),
                    "Editor part opacity")
                * 100.0,
            hexColor(
                access, access.invoke("cubism.editor-model.part-form.multiply-color", form)),
            hexColor(
                access, access.invoke("cubism.editor-model.part-form.screen-color", form)),
            colorBlendOf(
                access,
                access.invoke(
                    "cubism.editor-model.part-source.color-composition", source)),
            alphaBlendOf(
                access,
                access.invoke(
                    "cubism.editor-model.part-source.alpha-composition", source)),
            labelColor(access, source));
    }

    private EditArtMeshData artMeshData(
        final EditSessionOpsAccess access,
        final Object source
    ) throws EditSessionException {
        ops.require(
            access,
            EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_OBJECT_ART_MESH_EXTENDED_ALIASES,
            "GetObject(ART_MESH)");
        final Object form = interpolatedForm(
            access,
            source,
            "cubism.editor-model.model.all-art-meshes",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh.class");
        return new EditArtMeshData(
            ops.localName(access, source),
            parentPartId(access, source),
            parentDeformerId(access, source),
            clippingIds(
                access,
                access.invoke("cubism.editor-model.art-mesh-source.clip-guid-list", source)),
            ops.flag(
                access.invoke("cubism.editor-model.art-mesh-source.inverted-mask", source),
                "Editor art mesh reverse-mask flag"),
            ops.integer(
                access.invoke("cubism.editor-model.drawable-form.draw-order", form),
                "Editor art mesh draw order"),
            ops.number(
                    access.invoke("cubism.editor-model.drawable-form.opacity", form),
                    "Editor art mesh opacity")
                * 100.0,
            hexColor(
                access,
                access.invoke("cubism.editor-model.drawable-form.multiply-color", form)),
            hexColor(
                access,
                access.invoke("cubism.editor-model.drawable-form.screen-color", form)),
            colorBlendOf(
                access,
                access.invoke(
                    "cubism.editor-model.art-mesh-source.color-composition", source)),
            alphaBlendOf(
                access,
                access.invoke(
                    "cubism.editor-model.art-mesh-source.alpha-composition", source)),
            ops.flag(
                access.invoke("cubism.editor-model.art-mesh-source.culling", source),
                "Editor art mesh culling flag"),
            labelColor(access, source),
            ops.floatArray(
                        access.invoke(
                            "cubism.editor-model.art-mesh-source.positions", source),
                        "Editor art mesh positions")
                    .length
                / 2);
    }

    private EditWarpDeformerData warpDeformerData(
        final EditSessionOpsAccess access,
        final Object source
    ) throws EditSessionException {
        ops.require(
            access,
            EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_OBJECT_WARP_ALIASES,
            "GetObject(WARP_DEFORMER)");
        final Object form = interpolatedForm(
            access,
            source,
            "cubism.editor-model.model.all-deformers",
            "cubism.editor-model.deformer.source",
            "cubism.editor-model.warp.class");
        final int col = ops.integer(
            access.invoke("cubism.editor-model.warp-source.col", source),
            "Editor warp column count");
        final int row = ops.integer(
            access.invoke("cubism.editor-model.warp-source.row", source),
            "Editor warp row count");
        final Object bezier = bezierExtension(access, source);
        return new EditWarpDeformerData(
            nameOrId(access, source),
            parentPartId(access, source),
            parentDeformerId(access, source),
            ops.number(
                    access.invoke("cubism.editor-model.deformer-form.opacity", form),
                    "Editor warp opacity")
                * 100.0,
            hexColor(
                access,
                access.invoke("cubism.editor-model.deformer-form.multiply-color", form)),
            hexColor(
                access,
                access.invoke("cubism.editor-model.deformer-form.screen-color", form)),
            col,
            row,
            bezier == null
                ? Optional.empty()
                : Optional.of(ops.integer(
                    access.invoke(
                        "cubism.editor-model.warp-bezier-extension.bezier-col", bezier),
                    "Editor warp bezier divisions")),
            bezier == null
                ? Optional.empty()
                : Optional.of(ops.integer(
                    access.invoke(
                        "cubism.editor-model.warp-bezier-extension.bezier-row", bezier),
                    "Editor warp bezier divisions")),
            labelColor(access, source),
            warpRectangle(access, form, col, row));
    }

    private EditRotationDeformerData rotationDeformerData(
        final EditSessionOpsAccess access,
        final Object source
    ) throws EditSessionException {
        ops.require(
            access,
            EditorEditPartObjectSelectorContract.GET_OBJECT_CAPABILITY_ID,
            EditorEditPartObjectSelectorContract.GET_OBJECT_REQUIRED_ALIASES,
            "GetObject(ROTATION_DEFORMER)");
        final Object form = interpolatedForm(
            access,
            source,
            "cubism.editor-model.model.all-deformers",
            "cubism.editor-model.deformer.source",
            "cubism.editor-model.rotation.class");
        return new EditRotationDeformerData(
            nameOrId(access, source),
            parentPartId(access, source),
            parentDeformerId(access, source),
            ops.number(
                access.invoke("cubism.editor-model.rotation-form.angle", form),
                "Editor rotation angle"),
            ops.number(
                access.invoke("cubism.editor-model.rotation-source.base-angle", source),
                "Editor rotation base angle"),
            ops.number(
                    access.invoke("cubism.editor-model.rotation-form.scale", form),
                    "Editor rotation scale")
                * 100.0,
            ops.number(
                    access.invoke("cubism.editor-model.deformer-form.opacity", form),
                    "Editor rotation opacity")
                * 100.0,
            hexColor(
                access,
                access.invoke("cubism.editor-model.deformer-form.multiply-color", form)),
            hexColor(
                access,
                access.invoke("cubism.editor-model.deformer-form.screen-color", form)),
            labelColor(access, source),
            new Point2(
                (float) ops.number(
                    access.invoke("cubism.editor-model.rotation-form.origin-x", form),
                    "Editor rotation origin"),
                (float) ops.number(
                    access.invoke("cubism.editor-model.rotation-form.origin-y", form),
                    "Editor rotation origin")));
    }

    /**
     * The interpolated form of {@code source}'s live instance, matching the official {@code
     * GetObject} read of {@code getInterpolatedForm()}: form-state fields report the
     * interpolated state, not the selected keyform cell.
     */
    private Object interpolatedForm(
        final EditSessionOpsAccess access,
        final Object source,
        final String instancesAlias,
        final String sourceAlias,
        final String instanceClassAlias
    ) {
        for (final Object instance : ops.list(
            access.invoke(instancesAlias, access.model()), "Editor object instances")) {
            if (access.invoke(sourceAlias, instance) == source) {
                if (!access.isInstance(instanceClassAlias, instance)) {
                    throw ops.unavailable("Editor object instance kind is invalid.");
                }
                final Object form = access.invoke(
                    "cubism.editor-model.parameter-controllable.interpolated-form", instance);
                if (form == null) {
                    throw ops.unavailable("Editor interpolated form is unavailable.");
                }
                return form;
            }
        }
        throw ops.unavailable("Editor object instance is unavailable.");
    }

    /**
     * The official {@code ParentId} normalization: a missing parent or the synthetic root part
     * both serialize as {@code %Root}; the typed surface reports {@link Optional#empty()}.
     */
    private Optional<PartId> parentPartId(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        final Object parent = access.invoke(
            "cubism.editor-model.part-source.parent", source);
        if (parent == null) {
            return Optional.empty();
        }
        final String value = ops.partIdValue(
            access, access.invoke("cubism.editor-model.part-source.id", parent));
        final Object root = access.invoke(
            "cubism.editor-model.model-source.root-part", access.modelSource());
        final String rootId = ops.partIdValue(
            access, access.invoke("cubism.editor-model.part-source.id", root));
        return value.equals(rootId) ? Optional.empty() : Optional.of(new PartId(value));
    }

    /**
     * The official {@code ParentDeformerId} normalization: a missing target deformer serializes
     * as {@code %Root}; the typed surface reports {@link Optional#empty()}.
     */
    private Optional<DeformerId> parentDeformerId(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        final Object target = access.invoke(
            "cubism.editor-model.parameter-controllable-source.target-deformer-id", source);
        return target == null
            ? Optional.empty()
            : Optional.of(new DeformerId(ops.idValue(access, target)));
    }

    /**
     * Translates a host clip-guid list to object ids. Guids that no longer resolve are skipped —
     * the host can retain stale entries.
     */
    private List<ModelObjectId> clippingIds(
        final EditSessionOpsAccess access,
        final Object guidList
    ) {
        final ArrayList<ModelObjectId> ids = new ArrayList<>();
        for (final Object guid : ops.list(guidList, "Editor clipping guid list")) {
            final String value = ops.guidValue(access, guid);
            for (final Object candidate : ops.allObjectSources(access)) {
                if (ops.sourceGuid(access, candidate).equals(value)) {
                    ids.add(new ModelObjectId(ops.objectId(access, candidate)));
                    break;
                }
            }
        }
        return List.copyOf(ids);
    }

    private String nameOrId(final EditSessionOpsAccess access, final Object source) {
        return ops.text(
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.name-or-id-string",
                source),
            "Editor object name");
    }

    private Optional<String> hexColor(
        final EditSessionOpsAccess access,
        final Object color
    ) {
        return color == null
            ? Optional.empty()
            : Optional.of(ops.text(
                access.invoke("cubism.editor-model.float-color.hex-rgb", color),
                "Editor form color"));
    }

    /**
     * Maps a host {@code ColorComposition} constant to the SDK enum by declaration order —
     * the 5.3.x host enum and {@link EditColorBlend} share the same constant sequence.
     */
    private EditColorBlend colorBlendOf(
        final EditSessionOpsAccess access,
        final Object composition
    ) {
        final Object[] values = enumValues(
            access, "cubism.editor-model.color-composition.values", "color blend");
        final EditColorBlend[] blends = EditColorBlend.values();
        for (int i = 0; i < values.length && i < blends.length; i++) {
            if (values[i] == composition) {
                return blends[i];
            }
        }
        throw ops.unavailable("Editor color blend is unsupported.");
    }

    /**
     * Maps a host {@code AlphaComposition} constant to the SDK enum by declaration order —
     * the host enum and {@link EditAlphaBlend} share the same constant sequence.
     */
    private EditAlphaBlend alphaBlendOf(
        final EditSessionOpsAccess access,
        final Object composition
    ) {
        final Object[] values = enumValues(
            access, "cubism.editor-model.alpha-composition.values", "alpha blend");
        final EditAlphaBlend[] blends = EditAlphaBlend.values();
        for (int i = 0; i < values.length && i < blends.length; i++) {
            if (values[i] == composition) {
                return blends[i];
            }
        }
        throw ops.unavailable("Editor alpha blend is unsupported.");
    }

    private Object[] enumValues(
        final EditSessionOpsAccess access,
        final String valuesAlias,
        final String label
    ) {
        final Object values = access.invokeStatic(valuesAlias);
        if (!(values instanceof Object[] array)) {
            throw ops.unavailable("Editor " + label + " values are unavailable.");
        }
        return array;
    }

    /**
     * The edit-level-2 bezier subdivision extension the official {@code GetObject} reports
     * ({@code BezierDivH}/{@code BezierDivV}); {@code null} when the warp has none.
     */
    private Object bezierExtension(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        for (final Object extension : ops.list(
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.extensions", source),
            "Editor warp extensions")) {
            if (access.isInstance(
                    "cubism.editor-model.warp-bezier-extension.class", extension)
                && ops.integer(
                        access.invoke(
                            "cubism.editor-model.warp-bezier-extension.edit-level",
                            extension),
                        "Editor warp bezier edit level")
                    == 2) {
                return extension;
            }
        }
        return null;
    }

    /**
     * The official {@code Rectangle} read: corner positions of the interpolated lattice,
     * {@code positions[2*(i + j*(col+1))]}, reported in the official constructor order
     * (top-left, bottom-left, top-right, bottom-right).
     */
    private EditRectangle warpRectangle(
        final EditSessionOpsAccess access,
        final Object form,
        final int col,
        final int row
    ) {
        final float[] positions = ops.floatArray(
            access.invoke("cubism.editor-model.warp-form.positions", form),
            "Editor warp positions");
        if (col < 0 || row < 0 || positions.length < 2 * (col + 1) * (row + 1)) {
            throw ops.unavailable("Editor warp positions are malformed.");
        }
        return new EditRectangle(
            warpCorner(positions, 0),
            warpCorner(positions, 2 * row * (col + 1)),
            warpCorner(positions, 2 * col),
            warpCorner(positions, 2 * (col + row * (col + 1))));
    }

    private Point2 warpCorner(final float[] positions, final int offset) {
        return new Point2(positions[offset], positions[offset + 1]);
    }

    private EditLabelColor labelColor(
        final EditSessionOpsAccess access,
        final Object source
    ) {
        return ops.readLabelColor(
            access,
            access.invoke(
                "cubism.editor-model.parameter-controllable-source.label-color", source));
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

}
