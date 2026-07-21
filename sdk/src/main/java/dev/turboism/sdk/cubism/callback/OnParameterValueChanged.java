package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Notifies after the authoritative parameter value actually changes. */
@PreviewApi
@FunctionalInterface
public interface OnParameterValueChanged {

    void onParameterValueChanged(
        Parameter parameter,
        float oldValue,
        float newValue
    );
}
