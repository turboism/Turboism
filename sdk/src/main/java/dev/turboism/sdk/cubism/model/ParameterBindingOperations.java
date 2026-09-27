package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ParameterBindingPointId;

import java.util.List;

/** Editor-authoring operations for typed model-object parameter bindings. */
public interface ParameterBindingOperations {

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
