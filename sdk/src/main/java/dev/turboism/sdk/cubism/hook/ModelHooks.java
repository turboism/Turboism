package dev.turboism.sdk.cubism.hook;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.CubismModel;

/** Override-based lifecycle hooks for Cubism model operations. */
@PreviewApi
public interface ModelHooks {

    default void beforeUpdateModel(final CubismModel model) {
    }

    default void onModelUpdated(final CubismModel model) {
    }

    default void afterUpdateModel(final CubismModel model) {
    }
}
