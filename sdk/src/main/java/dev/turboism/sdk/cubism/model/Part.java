package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** One Cubism Part. */
@PreviewApi
public interface Part {

    PartId id();


    /** Editor display name, or the ID text when no authoring name is available. */
    default String name() {
        return id().value();
    }

    float getOpacity();

    int parentIndex();

    void setOpacity(float opacity);
}
