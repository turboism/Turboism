package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.core.MocInfo;

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

    default Canvas canvas() {
        throw new UnsupportedOperationException("Cubism canvas access is unavailable.");
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
