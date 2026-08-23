package dev.turboism.sdk.cubism.model;


/** Immutable indexed float sequence. */
public interface FloatSequence {

    int size();

    float get(int index);

    default boolean isEmpty() {
        return size() == 0;
    }
}
