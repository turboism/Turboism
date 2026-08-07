package dev.turboism.adapter.cubism.lifecycle;

import java.util.Objects;

/** Owns object-specific and shared semantic lifecycle coordinators for one host session. */
public final class EditorObjectLifecycleCoordinator implements AutoCloseable {
    private final DrawableLifecycleCoordinator drawable;
    private final DeformerLifecycleCoordinator deformer;
    private final SemanticOperationLifecycleCoordinator semantic;

    public EditorObjectLifecycleCoordinator() {
        this(
            new DrawableLifecycleCoordinator(),
            new DeformerLifecycleCoordinator(),
            new SemanticOperationLifecycleCoordinator()
        );
    }

    public EditorObjectLifecycleCoordinator(
        final DrawableLifecycleCoordinator drawable,
        final DeformerLifecycleCoordinator deformer
    ) {
        this(drawable, deformer, new SemanticOperationLifecycleCoordinator());
    }

    public EditorObjectLifecycleCoordinator(
        final DrawableLifecycleCoordinator drawable,
        final DeformerLifecycleCoordinator deformer,
        final SemanticOperationLifecycleCoordinator semantic
    ) {
        this.drawable = Objects.requireNonNull(drawable, "drawable");
        this.deformer = Objects.requireNonNull(deformer, "deformer");
        this.semantic = Objects.requireNonNull(semantic, "semantic");
    }

    public DrawableLifecycleCoordinator drawable() { return drawable; }
    public DeformerLifecycleCoordinator deformer() { return deformer; }
    public SemanticOperationLifecycleCoordinator semantic() { return semantic; }

    @Override public void close() {
        semantic.close();
        deformer.close();
        drawable.close();
    }
}
