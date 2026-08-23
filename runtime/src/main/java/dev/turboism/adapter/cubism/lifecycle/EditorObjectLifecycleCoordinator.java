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

    /** @return the ArtMesh write coordinator owned by this session. */
    public DrawableLifecycleCoordinator drawable() { return drawable; }
    /** @return the Warp and Rotation Deformer write coordinator owned by this session. */
    public DeformerLifecycleCoordinator deformer() { return deformer; }
    /** @return the coordinator for semantic operations shared across editor object kinds. */
    public SemanticOperationLifecycleCoordinator semantic() { return semantic; }

    /** Attaches the session event broker to every migrated editor-object family. */
    public void attachEventBroker(final dev.turboism.core.event.RuntimeEventBroker broker) {
        final dev.turboism.core.event.RuntimeEventBroker value = Objects.requireNonNull(
            broker,
            "broker"
        );
        drawable.attachEventBroker(value);
        deformer.attachEventBroker(value);
    }

    @Override public void close() {
        semantic.close();
        deformer.close();
        drawable.close();
    }
}
