package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelImageId;

/** One model image (texture slot) inside a model image group. */
@PreviewApi
public interface ModelImageEntry {

    ModelImageId id();

    String name();

    int width();

    int height();
}
