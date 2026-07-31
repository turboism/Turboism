package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;

import java.util.List;

/** Editor-authoring operations for typed model-object parameter bindings. */
@PreviewApi
public interface ParameterBindingOperations {

    void bind(ParameterBindingTarget target, List<ParameterBindingPoint> points);

    void createPoint(ParameterBindingTarget target, ParameterBindingPoint point);

    void movePoint(ParameterBindingTarget target, ParameterBindingPointId pointId, float value);

    void deletePoint(ParameterBindingTarget target, ParameterBindingPointId pointId);

    void unbind(ParameterBindingTarget target);
}
