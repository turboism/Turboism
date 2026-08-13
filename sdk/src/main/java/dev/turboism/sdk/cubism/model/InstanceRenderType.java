package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/**
 * Render kind of one Editor model instance.
 *
 * <p>Mirrors the exact host enum {@code com.live2d.graphics3d.rendering.RenderType}
 * observed in Cubism 5.2.03 and 5.3.02. {@link #ONION_SKIN_FOR_MODELING} exists
 * only in 5.3.02; adapters fail closed instead of inventing host values.</p>
 */
@PreviewApi
public enum InstanceRenderType {
    NORMAL,
    PSD_EXPORT,
    ART_PATH,
    ART_PATH_ILLEGAL,
    ONION_SKIN_FOR_MODELING
}
