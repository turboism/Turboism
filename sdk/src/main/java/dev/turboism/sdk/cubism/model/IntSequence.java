package dev.turboism.sdk.cubism.model;


/** Immutable indexed integer sequence. */
public interface IntSequence {

    int size();

    int get(int index);

    default boolean isEmpty() {
        return size() == 0;
    }
}
