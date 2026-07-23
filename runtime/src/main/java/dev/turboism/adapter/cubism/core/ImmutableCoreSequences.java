package dev.turboism.adapter.cubism.core;

import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.IntSequence;

import java.util.List;

final class ImmutableCoreSequences {

    private ImmutableCoreSequences() {
    }

    static IntSequence ints(final List<Integer> values) {
        final int[] copy = values.stream().mapToInt(Integer::intValue).toArray();
        return new IntSequence() {
            @Override public int size() { return copy.length; }
            @Override public int get(final int index) { return copy[index]; }
        };
    }

    static FloatSequence floats(final List<Float> values) {
        final float[] copy = new float[values.size()];
        for (int index = 0; index < copy.length; index++) copy[index] = values.get(index);
        return new FloatSequence() {
            @Override public int size() { return copy.length; }
            @Override public float get(final int index) { return copy[index]; }
        };
    }
}
