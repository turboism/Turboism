package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.cubism.model.IntSequence;

/**
 * Int sequences for event payload tests.
 *
 * <p>{@code :sdk} has no dependencies, so the shared test-support stubs are unavailable here.
 */
final class TestSequences {

    private TestSequences() {}

    /** Returns an empty sequence whose indexed access reports the requested index. */
    static IntSequence ints() {
        return EMPTY;
    }

    private static final IntSequence EMPTY = new IntSequence() {
        @Override
        public int size() {
            return 0;
        }

        @Override
        public int get(final int index) {
            throw new IndexOutOfBoundsException(index);
        }
    };
}
