package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** One Cubism Part. */
@PreviewApi
public interface Part {

    PartId id();

    float getOpacity();

    void setOpacity(float opacity);
}
