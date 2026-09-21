package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;

/** Override-based lifecycle hooks for Cubism parameters. */
public interface ParameterHooks {

    /**
     * Runs before a parameter value write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    @CubismEditor({"5.3.02", "5.3.03"})
    default float beforeSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
        return value;
    }

    /** Runs only when the parameter value actually changed. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
    }

    /** Runs after the value write completed with the value that was applied. */
    @CubismEditor({"5.3.02", "5.3.03"})
    default void afterSetParameterValue(
        final Parameter parameter,
        final float value
    ) {
    }
}
