package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;

/** One Cubism model exposed as natural objects and methods. */
@PreviewApi
public interface CubismModel {

    ModelId id();

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

    Parts parts();

    Drawables drawables();

    Deformers deformers();

    Glues glues();

    void update();
}
