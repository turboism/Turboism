package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;

/** Immutable indexed integer sequence. */
@PreviewApi
public interface IntSequence {

    int size();

    int get(int index);

    default boolean isEmpty() {
        return size() == 0;
    }
}
