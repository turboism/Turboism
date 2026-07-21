package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.plugin.Registration;

/** Registrations for wrapped Cubism method lifecycles. */
@PreviewApi
public interface CubismCallbacks {

    Registration beforeSetParameterValue(BeforeSetParameterValue callback);

    Registration onParameterValueChanged(OnParameterValueChanged callback);

    Registration afterSetParameterValue(AfterSetParameterValue callback);
}
