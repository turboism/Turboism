package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.callback.ModelHooks;
import dev.turboism.sdk.cubism.callback.ParameterHooks;
import dev.turboism.sdk.cubism.callback.PartHooks;
import dev.turboism.sdk.plugin.TurboismPlugin;

/**
 * Convenience plugin contract for Cubism integrations.
 *
 * <p>Plugins override only the lifecycle methods they need. Runtime discovery
 * is based on the inherited hook interfaces; no callback registration is
 * required.</p>
 */
@PreviewApi
public interface CubismPlugin
    extends TurboismPlugin,
            ParameterHooks,
            PartHooks,
            ModelHooks {
}
