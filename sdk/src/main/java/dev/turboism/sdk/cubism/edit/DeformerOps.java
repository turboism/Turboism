package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Typed operations of the deformer family of the editing surface: reading the deformer tree and
 * creating or editing warp and rotation deformers.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface DeformerOps {

    /**
     * Reads the deformer structure tree ({@code GetDeformerStructure}).
     *
     * @return the deformer root; children are deformers in palette order
     */
    EditObjectNode deformerStructure() throws EditSessionException;

    /**
     * Creates a rotation deformer around {@code targetObjectIds}
     * ({@code AddRotationDeformer}); returns {@code true} on success.
     */
    boolean addRotationDeformer(AddRotationDeformer request) throws EditSessionException;

    /**
     * Creates a warp deformer around {@code targetObjectIds} ({@code AddWarpDeformer}); returns
     * {@code true} on success.
     */
    boolean addWarpDeformer(AddWarpDeformer request) throws EditSessionException;

    /** Edits one rotation deformer ({@code EditRotationDeformer}); returns {@code true} on success. */
    boolean editRotationDeformer(EditRotationDeformer request) throws EditSessionException;

    /** Edits one warp deformer ({@code EditWarpDeformer}); returns {@code true} on success. */
    boolean editWarpDeformer(EditWarpDeformer request) throws EditSessionException;

    /** Returns a fail-closed implementation in which every operation is unavailable. */
    static DeformerOps unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements DeformerOps {
        INSTANCE;

        @Override
        public EditObjectNode deformerStructure() throws EditSessionException {
            throw new EditUnavailableException("DeformerOps.deformerStructure");
        }

        @Override
        public boolean addRotationDeformer(final AddRotationDeformer request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("DeformerOps.addRotationDeformer");
        }

        @Override
        public boolean addWarpDeformer(final AddWarpDeformer request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("DeformerOps.addWarpDeformer");
        }

        @Override
        public boolean editRotationDeformer(final EditRotationDeformer request)
                throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("DeformerOps.editRotationDeformer");
        }

        @Override
        public boolean editWarpDeformer(final EditWarpDeformer request) throws EditSessionException {
            Objects.requireNonNull(request, "request");
            throw new EditUnavailableException("DeformerOps.editWarpDeformer");
        }
    }

    /**
     * {@code AddRotationDeformer} request: creates a rotation deformer for
     * {@code targetObjectIds}, attached per {@code mode}.
     */
    record AddRotationDeformer(
            Optional<String> name,
            Optional<DeformerId> id,
            Optional<PartId> parentId,
            List<ModelObjectId> targetObjectIds,
            EditDeformerAttachMode mode) {

        public AddRotationDeformer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(parentId, "parentId");
            targetObjectIds = List.copyOf(Objects.requireNonNull(targetObjectIds, "targetObjectIds"));
            Objects.requireNonNull(mode, "mode");
        }

        /** Returns a request that attaches the new deformer as parent of the targets. */
        public AddRotationDeformer(
                final Optional<String> name,
                final Optional<DeformerId> id,
                final Optional<PartId> parentId,
                final List<ModelObjectId> targetObjectIds) {
            this(name, id, parentId, targetObjectIds, EditDeformerAttachMode.AS_PARENT);
        }
    }

    /**
     * {@code AddWarpDeformer} request: creates a warp deformer for {@code targetObjectIds},
     * attached per {@code mode}. Lattice density is controlled by {@code warpDiv*} (grid cells)
     * and {@code bezierDiv*} (bezier subdivisions); {@code considerChildKeyforms} and
     * {@code snapCenter} mirror the official creation flags. All lattice fields are optional and
     * fall back to editor defaults.
     */
    record AddWarpDeformer(
            Optional<String> name,
            Optional<DeformerId> id,
            Optional<PartId> parentId,
            List<ModelObjectId> targetObjectIds,
            EditDeformerAttachMode mode,
            Optional<Integer> warpDivH,
            Optional<Integer> warpDivV,
            Optional<Integer> bezierDivH,
            Optional<Integer> bezierDivV,
            Optional<Boolean> considerChildKeyforms,
            Optional<Boolean> snapCenter) {

        public AddWarpDeformer {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(parentId, "parentId");
            targetObjectIds = List.copyOf(Objects.requireNonNull(targetObjectIds, "targetObjectIds"));
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(warpDivH, "warpDivH");
            Objects.requireNonNull(warpDivV, "warpDivV");
            Objects.requireNonNull(bezierDivH, "bezierDivH");
            Objects.requireNonNull(bezierDivV, "bezierDivV");
            Objects.requireNonNull(considerChildKeyforms, "considerChildKeyforms");
            Objects.requireNonNull(snapCenter, "snapCenter");
        }

        /** Returns a request with editor-default lattice settings, attached as parent. */
        public AddWarpDeformer(
                final Optional<String> name,
                final Optional<DeformerId> id,
                final Optional<PartId> parentId,
                final List<ModelObjectId> targetObjectIds) {
            this(
                name, id, parentId, targetObjectIds, EditDeformerAttachMode.AS_PARENT,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty());
        }
    }

    /**
     * {@code EditRotationDeformer} request: {@code id} selects the deformer; {@code parameters}
     * selects the keyform state the edit applies to; remaining fields apply when present.
     */
    record EditRotationDeformer(
            DeformerId id,
            List<EditParameterKeyCondition> parameters,
            boolean exactMatch,
            Optional<DeformerId> newId,
            Optional<String> name,
            Optional<PartId> parentId,
            Optional<DeformerId> parentDeformerId,
            Optional<Double> angle,
            Optional<Double> baseAngle,
            Optional<Double> scale,
            Optional<Double> opacity,
            Optional<String> multiplyColor,
            Optional<String> screenColor,
            Optional<EditLabelColor> labelColor) {

        public EditRotationDeformer {
            Objects.requireNonNull(id, "id");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(parentDeformerId, "parentDeformerId");
            Objects.requireNonNull(angle, "angle");
            Objects.requireNonNull(baseAngle, "baseAngle");
            Objects.requireNonNull(scale, "scale");
            Objects.requireNonNull(opacity, "opacity");
            for (final Optional<Double> field : List.of(angle, baseAngle, scale, opacity)) {
                field.ifPresent(v -> {
                    if (!Double.isFinite(v)) {
                        throw new IllegalArgumentException("numeric fields must be finite");
                    }
                });
            }
            Objects.requireNonNull(multiplyColor, "multiplyColor");
            Objects.requireNonNull(screenColor, "screenColor");
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }

    /**
     * {@code EditWarpDeformer} request: {@code id} selects the deformer; field semantics match
     * {@link EditRotationDeformer}; lattice fields reshape the grid when present.
     */
    record EditWarpDeformer(
            DeformerId id,
            List<EditParameterKeyCondition> parameters,
            boolean exactMatch,
            Optional<DeformerId> newId,
            Optional<String> name,
            Optional<PartId> parentId,
            Optional<DeformerId> parentDeformerId,
            Optional<Double> opacity,
            Optional<String> multiplyColor,
            Optional<String> screenColor,
            Optional<Integer> warpDivH,
            Optional<Integer> warpDivV,
            Optional<Integer> bezierDivH,
            Optional<Integer> bezierDivV,
            Optional<EditLabelColor> labelColor) {

        public EditWarpDeformer {
            Objects.requireNonNull(id, "id");
            parameters = List.copyOf(Objects.requireNonNull(parameters, "parameters"));
            Objects.requireNonNull(newId, "newId");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(parentId, "parentId");
            Objects.requireNonNull(parentDeformerId, "parentDeformerId");
            Objects.requireNonNull(opacity, "opacity");
            opacity.ifPresent(v -> {
                if (!Double.isFinite(v)) {
                    throw new IllegalArgumentException("opacity must be finite");
                }
            });
            Objects.requireNonNull(multiplyColor, "multiplyColor");
            Objects.requireNonNull(screenColor, "screenColor");
            Objects.requireNonNull(warpDivH, "warpDivH");
            Objects.requireNonNull(warpDivV, "warpDivV");
            Objects.requireNonNull(bezierDivH, "bezierDivH");
            Objects.requireNonNull(bezierDivV, "bezierDivV");
            Objects.requireNonNull(labelColor, "labelColor");
        }
    }
}
