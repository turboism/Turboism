package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Hook invoked after a parameter value set operation completes normally. */
@PreviewApi
public interface AfterSetParameterValue {

    default void afterSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
    }
}
