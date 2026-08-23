package dev.turboism.sdk.cubism.model;


/** Access to Cubism model objects. */
public interface CubismModelAccess {

    /**
     * Returns the active model.
     *
     * @throws IllegalStateException when no model is active
     */
    CubismModel active();
}
