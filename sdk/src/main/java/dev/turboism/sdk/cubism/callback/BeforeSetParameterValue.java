package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;

/** Hook invoked before Cubism sets a parameter value. */
@PreviewApi
public interface BeforeSetParameterValue {

    /**
     * Returns the value that should be passed to the next hook or to Cubism.
     */
    default float beforeSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
        return value;
    }
}
