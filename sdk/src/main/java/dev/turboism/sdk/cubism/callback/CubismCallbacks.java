package dev.turboism.sdk.cubism.callback;

import dev.turboism.sdk.PreviewApi;

/**
 * Legacy Preview alias for parameter hooks.
 *
 * @deprecated Implement {@link ParameterHooks} or
 * {@link dev.turboism.sdk.cubism.CubismPlugin} and override lifecycle methods.
 */
@Deprecated(forRemoval = true)
@PreviewApi
public interface CubismCallbacks extends ParameterHooks {
}
