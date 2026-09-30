package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.model.Parameter;

/** Override-based lifecycle hooks for Cubism parameters. */
public interface ParameterHooks {

    /**
     * Runs before a parameter value write; the returned value is passed to the next hook and the
     * final value is sent to the native call.
     */
    @CubismEditor({
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    })
    default float beforeSetParameterValue(final Parameter parameter, final float value) {
        return value;
    }

    /** Runs only when the parameter value actually changed. */
    @CubismEditor({
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    })
    default void onParameterValueChanged(final Parameter parameter, final float oldValue, final float newValue) {}

    /** Runs after the value write completed with the value that was applied. */
    @CubismEditor({
        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03",
        "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04"
    })
    default void afterSetParameterValue(final Parameter parameter, final float value) {}
}
