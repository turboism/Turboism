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

    /**
     * Binds an unbound target using finite, nonempty, distinct parameter values, without
     * requiring client-invented binding-point identities. Native identities must be read
     * back after creation. Implementations may sort the values and reject near duplicates.
     * This operation does not promise preservation of the original form GUID at the
     * parameter default, a complete multi-axis grid, or visual correctness. Authoring
     * admission, transaction and rollback rules are identical to {@link #bind}.
     * Implementations without verified support fail closed.
     */
    default void bindValues(ParameterBindingTarget target, List<Float> values) {
        throw new UnsupportedOperationException("Native value-based parameter binding is unavailable.");
    }

    /** Adds one binding point to {@code target}'s binding. */
    void createPoint(ParameterBindingTarget target, ParameterBindingPoint point);

    /** Moves one existing binding point to {@code value}. */
    void movePoint(ParameterBindingTarget target, ParameterBindingPointId pointId, float value);

    /** Removes one binding point from {@code target}'s binding. */
    void deletePoint(ParameterBindingTarget target, ParameterBindingPointId pointId);

    /** Removes {@code target}'s binding to the parameter entirely. */
    void unbind(ParameterBindingTarget target);
}
