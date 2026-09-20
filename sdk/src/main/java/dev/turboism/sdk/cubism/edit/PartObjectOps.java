package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.GlueId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed operations of the parts/object family of the editing surface: reading the parts palette
 * and individual objects, deleting objects, reordering palette entries, and creating or editing
 * parts, art meshes, and glue.
 *
 * <p>{@link #object(GetObject)} follows the official {@code GetObject} contract: the payload
 * records mirror the official 1.1.0 data blocks. Reads are admitted per kind where the bound
 * host record carries every member the block needs — WarpDeformer, RotationDeformer, and Glue
 * read on all supported hosts; Part and ArtMesh read on 5.3.x hosts whose models expose the
 * extended part/art-mesh readers, and fail closed on 5.2.03 where those members do not exist.
 * A glue target cannot be named by {@link ModelObjectReference#kind()}: it resolves through
 * the host's glue enumeration by id alone, so the declared kind is not consulted on that
 * route. {@link EditObjectKind#ART_PATH} has no official data payload and fails closed.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface PartObjectOps {

    /**
     * Reads the parts-palette structure tree ({@code GetPartStructure}).
     *
     * @return the palette root; its children are the parts in palette order
     */
    EditObjectNode partStructure() throws EditSessionException;

    /**
     * Reads one object with its typed property payload ({@code GetObject}).
     *
     * @return the object snapshot; glue reads resolve by id through the glue enumeration
     *     regardless of the declared reference kind, ArtPath reads fail closed, and
     *     Part/ArtMesh reads fail closed on hosts lacking the extended readers (5.2.03)
     */
    EditObjectSnapshot object(GetObject request) throws EditSessionException;

    /**
     * Deletes one object ({@code DeleteObject}).
     *
     * @return {@code true} when the object was deleted
     */
    boolean deleteObject(DeleteObject request) throws EditSessionException;

    /**
     * Moves an object to a new palette position or parent ({@code MoveObjectOnPartsPalette}).
     *
     * @return {@code true} when the object was moved
     */
    boolean moveObjectOnPartsPalette(MoveObjectOnPartsPalette request) throws EditSessionException;

    /** Adds a part ({@code AddPart}); returns {@code true} on success. */
    boolean addPart(AddPart request) throws EditSessionException;

    /** Edits one part ({@code EditPart}); returns {@code true} on success. */
    boolean editPart(EditPart request) throws EditSessionException;

    /** Edits one art mesh ({@code EditArtMesh}); returns {@code true} on success. */
    boolean editArtMesh(EditArtMesh request) throws EditSessionException;

    /** Edits one glue object ({@code EditGlue}); returns {@code true} on success. */
    boolean editGlue(EditGlue request) throws EditSessionException;

    /** Returns a fail-closed implementation in which every operation is unavailable. */
    static PartObjectOps unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements PartObjectOps {
        INSTANCE;

        @Override
        public EditObjectNode partStructure() throws EditSessionException {
            throw new EditUnavailableException("PartObjectOps.partStructure");
        }

        @Override
        public EditObjectSnapshot object(final GetObject request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.object");
        }

        @Override
        public boolean deleteObject(final DeleteObject request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.deleteObject");
        }

        @Override
        public boolean moveObjectOnPartsPalette(final MoveObjectOnPartsPalette request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.moveObjectOnPartsPalette");
        }

        @Override
        public boolean addPart(final AddPart request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.addPart");
        }

        @Override
        public boolean editPart(final EditPart request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.editPart");
        }

        @Override
        public boolean editArtMesh(final EditArtMesh request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.editArtMesh");
        }

        @Override
        public boolean editGlue(final EditGlue request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("PartObjectOps.editGlue");
        }
    }

    /**
     * {@code GetObject} request: reads {@code object} at the keyform state selected by
     * {@code parameters} (empty = default keyform). The official API does not support ArtPath
     * data reads.
     */
    record GetObject(ModelObjectReference object, List<EditParameterKeyCondition> parameters) {
        public GetObject {
            Objects.requireNonNull(object, "object");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
        }
    }

    /** {@code DeleteObject} request. */
    record DeleteObject(ModelObjectReference object) {
        public DeleteObject {
            Objects.requireNonNull(object, "object");
        }
    }

    /**
     * {@code MoveObjectOnPartsPalette} request: moves {@code object} under {@code parent} (absent
     * = keep/root) at a position given by {@code insertBefore} or {@code insertIndex}.
     */
    record MoveObjectOnPartsPalette(
            ModelObjectReference object,
            Optional<PartId> parent,
            Optional<ModelObjectId> insertBefore,
            Optional<Integer> insertIndex) {

        public MoveObjectOnPartsPalette {
            Objects.requireNonNull(object, "object");
            Objects.requireNonNull(parent, "parent");
            Objects.requireNonNull(insertBefore, "insertBefore");
            Objects.requireNonNull(insertIndex, "insertIndex");
            insertIndex.ifPresent(i -> {
                if (i < 0) {
                    throw new IllegalArgumentException("insertIndex must be non-negative");
                }
            });
        }
    }

    /**
     * {@code AddPart} request: creates a part optionally containing {@code ids} as children.
     * {@code nested} requests a nested part layout when the editor supports it.
     */
    record AddPart(
            Optional<String> name,
            Optional<PartId> id,
            Optional<Integer> drawOrder,
            List<ModelObjectId> ids,
            boolean nested) {

        public AddPart {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(drawOrder, "drawOrder");
            ids = List.copyOf(Objects.requireNonNull(ids, "ids"));
        }

        /** Returns a request creating an empty part with editor defaults. */
        public AddPart(final Optional<String> name, final Optional<PartId> id) {
            this(name, id, Optional.empty(), List.of(), false);
        }
    }

    /**
     * {@code EditPart} request: {@code id} selects the part; {@code parameters} selects the
     * keyform state the edit applies to (empty = default keyform); every remaining field is
     * applied only when present. {@code newId} renames the identifier.
     */
    record EditPart(
            PartId id,
            List<EditParameterKeyCondition> parameters,
            boolean exactMatch,
            Optional<PartId> newId,
            Optional<String> name,
            Optional<PartId> parentId,
            Optional<Boolean> grouped,
            Optional<Boolean> guidImage,
            Optional<Boolean> offscreen,
            Optional<List<ModelObjectId>> clippingIds,
            Optional<Boolean> reverseMask,
            Optional<Integer> drawOrder,
            Optional<Double> opacity,
            Optional<String> multiplyColor,
            Optional<String> screenColor,
            Optional<EditColorBlend> colorBlend,
            Optional<EditAlphaBlend> alphaBlend,
            Optional<EditLabelColor> labelColor) {

        public EditPart {
            Objects.requireNonNull(id, "id");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(grouped, "grouped");
            Objects.requireNonNull(guidImage, "guidImage");
            Objects.requireNonNull(offscreen, "offscreen");
            clippingIds = clippingIds.map(List::copyOf);
            Objects.requireNonNull(clippingIds, "clippingIds");
            Objects.requireNonNull(reverseMask, "reverseMask");
            Objects.requireNonNull(drawOrder, "drawOrder");
            Objects.requireNonNull(opacity, "opacity");
            opacity.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("opacity must be finite");
                }
            });
            Objects.requireNonNull(multiplyColor, "multiplyColor");
            Objects.requireNonNull(screenColor, "screenColor");
            Objects.requireNonNull(colorBlend, "colorBlend");
            Objects.requireNonNull(alphaBlend, "alphaBlend");
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }

    /**
     * {@code EditArtMesh} request: {@code id} selects the art mesh; field semantics match
     * {@link EditPart}. {@code parentDeformerId} reparents under a deformer.
     */
    record EditArtMesh(
            ArtMeshId id,
            List<EditParameterKeyCondition> parameters,
            boolean exactMatch,
            Optional<ArtMeshId> newId,
            Optional<String> name,
            Optional<PartId> parentId,
            Optional<DeformerId> parentDeformerId,
            Optional<List<ModelObjectId>> clippingIds,
            Optional<Boolean> reverseMask,
            Optional<Integer> drawOrder,
            Optional<Double> opacity,
            Optional<String> multiplyColor,
            Optional<String> screenColor,
            Optional<EditColorBlend> colorBlend,
            Optional<EditAlphaBlend> alphaBlend,
            Optional<Boolean> culling,
            Optional<EditLabelColor> labelColor) {

        public EditArtMesh {
            Objects.requireNonNull(id, "id");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(parentDeformerId, "parentDeformerId");
            clippingIds = clippingIds.map(List::copyOf);
            Objects.requireNonNull(clippingIds, "clippingIds");
            Objects.requireNonNull(reverseMask, "reverseMask");
            Objects.requireNonNull(drawOrder, "drawOrder");
            Objects.requireNonNull(opacity, "opacity");
            opacity.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("opacity must be finite");
                }
            });
            Objects.requireNonNull(multiplyColor, "multiplyColor");
            Objects.requireNonNull(screenColor, "screenColor");
            Objects.requireNonNull(colorBlend, "colorBlend");
            Objects.requireNonNull(alphaBlend, "alphaBlend");
            Objects.requireNonNull(culling, "culling");
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }

    /**
     * {@code EditGlue} request: {@code id} selects the glue object; field semantics match
     * {@link EditPart}. {@code intensity} adjusts the glue weight.
     */
    record EditGlue(
            GlueId id,
            List<EditParameterKeyCondition> parameters,
            boolean exactMatch,
            Optional<GlueId> newId,
            Optional<String> name,
            Optional<PartId> parentId,
            Optional<Double> intensity,
            Optional<EditLabelColor> labelColor) {

        public EditGlue {
            Objects.requireNonNull(id, "id");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(intensity, "intensity");
            intensity.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("intensity must be finite");
                }
            });
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }
}
