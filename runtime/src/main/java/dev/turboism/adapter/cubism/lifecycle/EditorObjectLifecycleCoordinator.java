package dev.turboism.adapter.cubism.lifecycle;

import java.util.Objects;

/** Owns the ArtMesh and Deformer SDK-write lifecycle coordinators for one host session. */
public final class EditorObjectLifecycleCoordinator implements AutoCloseable {
    private final DrawableLifecycleCoordinator drawable;
    private final DeformerLifecycleCoordinator deformer;

    public EditorObjectLifecycleCoordinator() {
        this(new DrawableLifecycleCoordinator(), new DeformerLifecycleCoordinator());
    }

    public EditorObjectLifecycleCoordinator(
        final DrawableLifecycleCoordinator drawable,
        final DeformerLifecycleCoordinator deformer
    ) {
        this.drawable = Objects.requireNonNull(drawable, "drawable");
        this.deformer = Objects.requireNonNull(deformer, "deformer");
    }

    public DrawableLifecycleCoordinator drawable() { return drawable; }
    public DeformerLifecycleCoordinator deformer() { return deformer; }

    @Override public void close() {
        deformer.close();
        drawable.close();
    }
}
