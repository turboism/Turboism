package dev.turboism.adapter.host;

import java.util.Objects;

final class SessionFloatSequence implements dev.turboism.sdk.cubism.model.FloatSequence {
    private final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.FloatSequence delegate;

    SessionFloatSequence(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.FloatSequence delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public int size() {
        return host.guarded(generation, delegate::size);
    }

    @Override
    public float get(final int index) {
        return host.guarded(generation, () -> delegate.get(index));
    }
}

final class SessionIntSequence implements dev.turboism.sdk.cubism.model.IntSequence {
    private final DynamicCubismModelAccess host;
    final long generation;
    final dev.turboism.sdk.cubism.model.IntSequence delegate;

    SessionIntSequence(
            final DynamicCubismModelAccess host,
            final long generation,
            final dev.turboism.sdk.cubism.model.IntSequence delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public int size() {
        return host.guarded(generation, delegate::size);
    }

    @Override
    public int get(final int index) {
        return host.guarded(generation, () -> delegate.get(index));
    }
}
