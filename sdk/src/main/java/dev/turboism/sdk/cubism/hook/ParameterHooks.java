package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.cubism.model.Parameter;

/** Override-based lifecycle hooks for Cubism parameters. */
public interface ParameterHooks {

    default float beforeSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
        return value;
    }

    default void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
    }

    default void afterSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
    }
}
