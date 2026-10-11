package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import java.util.List;

/** Editor-authoring operations for typed model-object parameter bindings. */
@CubismEditor
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

    /** Binds {@code target} to the parameter with the given binding points. */
    void bind(ParameterBindingTarget target, List<ParameterBindingPoint> points);

    /** Adds one binding point to {@code target}'s binding. */
    void createPoint(ParameterBindingTarget target, ParameterBindingPoint point);

    /** Moves one existing binding point to {@code value}. */
    void movePoint(ParameterBindingTarget target, ParameterBindingPointId pointId, float value);

    /** Removes one binding point from {@code target}'s binding. */
    void deletePoint(ParameterBindingTarget target, ParameterBindingPointId pointId);

    /** Removes {@code target}'s binding to the parameter entirely. */
    void unbind(ParameterBindingTarget target);
}
