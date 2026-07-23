package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;

/** One Cubism model exposed as natural objects and methods. */
@PreviewApi
public interface CubismModel {

    ModelId id();

    default Canvas canvas() {
        throw new UnsupportedOperationException("Cubism canvas access is unavailable.");
    }

    Parameters parameters();

    Parts parts();

    Drawables drawables();

    Deformers deformers();

    Glues glues();

    void update();
}
