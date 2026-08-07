package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/** Permission-checked metadata for the admitted Cubism Core runtime. */
@PreviewApi
public interface CoreRuntimeInfo {

    CoreVersion version();

    CoreCapabilities capabilities();

    MocInspector mocInspector();
}
