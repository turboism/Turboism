package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.core.MocInfo;

import java.util.List;
import java.util.Optional;

/** One Cubism model exposed as natural objects and methods. */
public interface CubismModel {

    /** Returns this model's stable identity within the current session. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    ModelId id();

    /**
     * Returns the model's display name.
     *
     * @throws UnsupportedOperationException when the backend does not expose it
     */
    default String name() {
        throw new UnsupportedOperationException("Cubism model name is unavailable.");
    }

    /**
     * Renames the model through the Editor authoring path.
     *
     * @throws UnsupportedOperationException when the backend does not support name editing
     */
    default void setName(final String name) {
        throw new UnsupportedOperationException("Cubism model-name editing is unavailable.");
    }

    /**
     * Returns the model's MOC metadata.
     *
     * @throws UnsupportedOperationException when the backend does not expose MOC inspection
     */
    default MocInfo mocInfo() {
        throw new UnsupportedOperationException("Cubism MOC metadata is unavailable.");
    }

    /** Returns the model's parameter-definition document projection. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default ParameterDefinitions parameterDefinitions() {
        throw new UnsupportedOperationException(
            "Cubism parameter-definition access is unavailable."
        );
    }

    /**
     * Returns the Editor model-instance list (host {@code CModelSource.getModelInstances()}).
     *
     * <p>Read-only: instance creation and switching are Editor-internal operations with
     * no verified authoring/undo evidence, so no write projection is declared.</p>
     */
    default List<ModelInstance> modelInstances() {
        throw new UnsupportedOperationException("Cubism model-instance access is unavailable.");
    }

    /**
     * Returns the Editor's current model instance, when one is selected.
     */
    default Optional<ModelInstance> currentModelInstance() {
        throw new UnsupportedOperationException("Cubism current-model-instance access is unavailable.");
    }

    /**
     * Returns whether the Editor is currently editing the model source.
     */
    default boolean modelEditing() {
        throw new UnsupportedOperationException("Cubism model-editing state is unavailable.");
    }

    /** Returns the model's read-only physics settings document projection. */
    default PhysicsSettings physicsSettings() {
        throw new UnsupportedOperationException(
            "Cubism physics-settings document access is unavailable."
        );
    }

    /** Returns the model's evaluated auto-Yure state. */
    default AutoYure autoYure() {
        throw new UnsupportedOperationException(
            "Cubism auto-Yure evaluation access is unavailable."
        );
    }

    /**
     * Returns the model's animation file-content documents.
     *
     * <p>No stable Editor document entry exists for auto-face evaluation state,
     * so no auto-face projection is declared; see the adapter evidence records.
     * Animation scene add/delete ({@code CAnimationFileContent.addScene /
     * deleteScene / setCurrentSceneDoc}) has no reviewed Undo registration in Cubism
     * 5.2.03, 5.3.02, or 5.3.03, so scene writes stay unavailable (fail closed).</p>
     */
    default List<AnimationDocument> animationDocuments() {
        throw new UnsupportedOperationException(
            "Cubism animation-document access is unavailable."
        );
    }

    /**
     * Returns the model's texture library projection.
     *
     * <p>Reads expose the Editor's {@code CTextureManager} document state (raw
     * images, model image groups, texture atlases); writes are Editor-authoring
     * operations inside the native Undo envelope.</p>
     */
    default ModelTextures textures() {
        throw new UnsupportedOperationException(
            "Cubism texture-library access is unavailable."
        );
    }



    /** Returns the model's structural and render-resource statistics. */
    default ModelStatistics statistics() {
        return ModelStatisticsCalculator.calculate(this);
    }

    /** Immutable PSD resource snapshots associated with this Editor model. */
    default List<PsdClipMaskDocumentSnapshot> psdDocuments() {
        throw new UnsupportedOperationException("Cubism PSD snapshot access is unavailable.");
    }

    /** Returns whether the Editor's default keyform is locked. */
    default boolean defaultKeyformLocked() {
        throw new UnsupportedOperationException(
            "Cubism default-keyform lock state is unavailable."
        );
    }

    /** Changes whether the Editor's default keyform is locked. */
    default void setDefaultKeyformLocked(final boolean locked) {
        throw new UnsupportedOperationException(
            "Cubism default-keyform lock editing is unavailable."
        );
    }

    /** Returns the active Cubism Editor model editing level. */
    default ModelEditLevel editLevel() {
        throw new UnsupportedOperationException(
            "Cubism model edit-level state is unavailable."
        );
    }

    /** Switches the active Cubism Editor model editing level. */
    default void setEditLevel(final ModelEditLevel level) {
        throw new UnsupportedOperationException(
            "Cubism model edit-level switching is unavailable."
        );
    }

    /** Returns the model's immutable canvas metrics. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    default Canvas canvas() {
        throw new UnsupportedOperationException("Cubism canvas access is unavailable.");
    }

    /**
     * Returns the Editor model profile metrics (pixels-per-unit and origin).
     *
     * @throws UnsupportedOperationException when the backend does not expose them
     */
    default ModelProfile profile() {
        throw new UnsupportedOperationException("Cubism model profile is unavailable.");
    }

    /** Returns the model's parameter collection. */
    Parameters parameters();

    /**
     * Returns the model's parameter-group collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose parameter groups
     */
    default ParameterGroups parameterGroups() {
        throw new UnsupportedOperationException("Cubism parameter-group access is unavailable.");
    }

    /**
     * Returns the binding-edit operations for one parameter.
     *
     * @param parameterId the parameter whose bindings are edited
     * @throws UnsupportedOperationException when the backend does not support binding edits
     */
    default ParameterBindingOperations parameterBindings(final dev.turboism.sdk.cubism.id.ParameterId parameterId) {
        throw new UnsupportedOperationException("Cubism parameter-binding editing is unavailable.");
    }

    /**
     * Returns the batch binding-edit operations spanning multiple parameters.
     *
     * @throws UnsupportedOperationException when the backend does not support batch edits
     */
    default ParameterBindingBatchOperations parameterBindingBatch() {
        throw new UnsupportedOperationException("Cubism parameter-binding batch editing is unavailable.");
    }

    /** Returns the model's part collection. */
    Parts parts();

    /** Returns the model's drawable (ArtMesh) collection. */
    Drawables drawables();

    /** Applies one conditional clip-mask replacement batch as one Editor edit. */
    default void replaceArtMeshClipMasks(final java.util.List<ClipMaskReplacement> replacements) {
        throw new UnsupportedOperationException(
            "Cubism clip-mask authoring replacement is unavailable."
        );
    }

    /** Returns the model's unified deformer collection. */
    Deformers deformers();

    /**
     * Returns the model's Warp Deformer collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose Warp Deformers
     */
    default WarpDeformers warpDeformers() {
        throw new UnsupportedOperationException("Cubism Warp Deformer access is unavailable.");
    }

    /**
     * Returns the model's Rotation Deformer collection.
     *
     * @throws UnsupportedOperationException when the backend does not expose Rotation Deformers
     */
    default RotationDeformers rotationDeformers() {
        throw new UnsupportedOperationException("Cubism Rotation Deformer access is unavailable.");
    }

    /** Returns the model's glue collection. */
    @CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
    Glues glues();

    /** Runs one host model-instance update pass, re-evaluating the model's current state. */
    void update();
}
