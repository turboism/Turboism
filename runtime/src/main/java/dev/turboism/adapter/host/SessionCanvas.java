package dev.turboism.adapter.host;

import dev.turboism.sdk.cubism.model.Canvas;
import java.util.Objects;

final class SessionCanvas implements Canvas {
    private final DynamicCubismModelAccess host;
    private final long generation;
    private final Canvas delegate;

    SessionCanvas(final DynamicCubismModelAccess host, final long generation, final Canvas delegate) {
        this.host = Objects.requireNonNull(host, "host");
        this.generation = generation;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public float widthPixels() {
        return host.guarded(generation, delegate::widthPixels);
    }

    @Override
    public float heightPixels() {
        return host.guarded(generation, delegate::heightPixels);
    }

    @Override
    public float originXPixels() {
        return host.guarded(generation, delegate::originXPixels);
    }

    @Override
    public float originYPixels() {
        return host.guarded(generation, delegate::originYPixels);
    }

    @Override
    public float pixelsPerUnit() {
        return host.guarded(generation, delegate::pixelsPerUnit);
    }
}
