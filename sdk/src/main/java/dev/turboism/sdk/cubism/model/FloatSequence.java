package dev.turboism.sdk.cubism.model;


/** Immutable indexed float sequence. */
public interface FloatSequence {

    /** Returns the number of elements. */
    int size();

    /**
     * Returns the element at {@code index}.
     *
     * @param index zero-based position within {@code [0, size())}
     */
    float get(int index);

    /** Returns whether the sequence contains no elements. */
    default boolean isEmpty() {
        return size() == 0;
    }
}
