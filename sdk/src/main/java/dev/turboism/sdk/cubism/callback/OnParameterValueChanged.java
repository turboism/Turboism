package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Hook notified after a parameter value actually changes. */
@PreviewApi
public interface OnParameterValueChanged {

    default void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
    }
}
