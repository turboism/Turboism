package dev.turboism.sdk.cubism.core;

import dev.turboism.sdk.PreviewApi;

/** Permission-checked MOC byte inspection service. */
@PreviewApi
public interface MocInspector {

    MocVersion latestVersion();

    MocInfo inspect(MocData data);
}
