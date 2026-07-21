package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Access to Cubism model objects. */
@PreviewApi
public interface CubismModelAccess {

    /**
     * Returns the active model.
     *
     * @throws IllegalStateException when no model is active
     */
    CubismModel active();
}
