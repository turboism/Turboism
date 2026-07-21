package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Runs before a parameter value is passed to Cubism. */
@PreviewApi
@FunctionalInterface
public interface BeforeSetParameterValue {

    float beforeSetParameterValue(Parameter parameter, float value);
}
