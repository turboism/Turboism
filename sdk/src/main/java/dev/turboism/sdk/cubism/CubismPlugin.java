package dev.turboism.sdk.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.hook.ModelHooks;
import dev.turboism.sdk.cubism.hook.DrawableHooks;
import dev.turboism.sdk.cubism.hook.DeformerHooks;
import dev.turboism.sdk.cubism.hook.ParameterHooks;
import dev.turboism.sdk.cubism.hook.PartHooks;
import dev.turboism.sdk.cubism.hook.SemanticOperationHooks;
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
            DrawableHooks,
            DeformerHooks,
            ModelHooks,
            SemanticOperationHooks {
}
