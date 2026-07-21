package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Runs after a parameter setter completes normally. */
@PreviewApi
@FunctionalInterface
public interface AfterSetParameterValue {

    void afterSetParameterValue(Parameter parameter, float value);
}
