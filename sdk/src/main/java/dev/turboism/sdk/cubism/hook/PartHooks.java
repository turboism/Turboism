package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Part;

/** Override-based lifecycle hooks for Cubism parts. */
@PreviewApi
public interface PartHooks {

    default float beforeSetPartOpacity(
        final Part part,
        final float opacity
    ) {
        return opacity;
    }

    default void onPartOpacityChanged(
        final Part part,
        final float oldOpacity,
        final float newOpacity
    ) {
    }

    default void afterSetPartOpacity(
        final Part part,
        final float opacity
    ) {
    }
}
