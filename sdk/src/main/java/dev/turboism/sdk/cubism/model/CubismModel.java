package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.core.MocInfo;

import java.util.List;
import java.util.Optional;

/** One Cubism model exposed as natural objects and methods. */
@PreviewApi
public interface CubismModel {

    ModelId id();

    default String name() {
        throw new UnsupportedOperationException("Cubism model name is unavailable.");
    }

    default void setName(final String name) {
        throw new UnsupportedOperationException("Cubism model-name editing is unavailable.");
    }

    default MocInfo mocInfo() {
        throw new UnsupportedOperationException("Cubism MOC metadata is unavailable.");
    }

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
     * so no auto-face projection is declared; see the adapter evidence records.</p>
     */
    default List<AnimationDocument> animationDocuments() {
        throw new UnsupportedOperationException(
            "Cubism animation-document access is unavailable."
        );
    }

    /** Returns the model's structural and render-resource statistics. */
    default ModelStatistics statistics() {
        return ModelStatisticsCalculator.calculate(this);
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

    Parameters parameters();

    default ParameterGroups parameterGroups() {
        throw new UnsupportedOperationException("Cubism parameter-group access is unavailable.");
    }

    default ParameterBindingOperations parameterBindings(final dev.turboism.sdk.cubism.id.ParameterId parameterId) {
        throw new UnsupportedOperationException("Cubism parameter-binding editing is unavailable.");
    }

    default ParameterBindingBatchOperations parameterBindingBatch() {
        throw new UnsupportedOperationException("Cubism parameter-binding batch editing is unavailable.");
    }

    Parts parts();

    Drawables drawables();

    Deformers deformers();

    default WarpDeformers warpDeformers() {
        throw new UnsupportedOperationException("Cubism Warp Deformer access is unavailable.");
    }

    default RotationDeformers rotationDeformers() {
        throw new UnsupportedOperationException("Cubism Rotation Deformer access is unavailable.");
    }

    Glues glues();

    void update();
}
