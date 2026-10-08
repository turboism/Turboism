package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterBindingPointId;

import java.util.List;

/** Editor-authoring operations for typed model-object parameter bindings. */
public interface ParameterBindingOperations {

    /** Reads the native settings; implementations without verified support fail closed. */
    default ParameterInterpolation interpolation(ParameterBindingTarget target) {
        throw new UnsupportedOperationException("Native interpolation settings are unavailable.");
    }

    /**
     * Changes one existing binding and rebuilds its native cache in the authoring transaction.
     * The verified 5.3.02 backend currently supports ordinary ArtMesh LINEAR/ELLIPSE
     * bindings without morph targets, subject to the complete generated-cache budget.
     */
    default void setInterpolation(ParameterBindingTarget target, ParameterInterpolation settings) {
        throw new UnsupportedOperationException("Native interpolation authoring is unavailable.");
    }

    void bind(ParameterBindingTarget target, List<ParameterBindingPoint> points);

    void createPoint(ParameterBindingTarget target, ParameterBindingPoint point);

    void movePoint(ParameterBindingTarget target, ParameterBindingPointId pointId, float value);

    void deletePoint(ParameterBindingTarget target, ParameterBindingPointId pointId);

    void unbind(ParameterBindingTarget target);
}
