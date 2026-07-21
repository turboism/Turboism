package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Immutable indexed float sequence. */
@PreviewApi
public interface FloatSequence {

    int size();

    float get(int index);

    default boolean isEmpty() {
        return size() == 0;
    }
}
