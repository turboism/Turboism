package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;

/** Override-based lifecycle hooks for Cubism parameters. */
@PreviewApi
public interface ParameterHooks
    extends BeforeSetParameterValue,
            OnParameterValueChanged,
            AfterSetParameterValue {
}
