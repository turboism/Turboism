package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;

/** Override-based lifecycle hooks for Cubism parameters. */
public interface ParameterHooks {

    @CubismEditor({"5.3.02", "5.3.03"})
    default float beforeSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
        return value;
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
    }

    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
    }
}
