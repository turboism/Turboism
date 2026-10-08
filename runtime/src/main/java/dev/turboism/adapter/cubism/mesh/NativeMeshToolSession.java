package dev.turboism.adapter.cubism.mesh;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.Drawable;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime-owned adapter for one exact native mesh-editor session. */
public final class NativeMeshToolSession implements MeshToolCoordinator.Session, AutoCloseable {
    private final long hostGeneration;
    private final MeshToolSessionResolver resolver;
    private final MeshEditSessionIdentity identity;
    private final Drawable drawable;
    private final NativeVertexSelectionAdapter selectionAdapter;
    private final AtomicBoolean active = new AtomicBoolean(true);

    NativeMeshToolSession(
            final long hostGeneration,
            final MeshToolSessionResolver resolver,
            final MeshToolSessionResolver.Snapshot snapshot) {
        if (hostGeneration < 0L) throw new IllegalArgumentException("hostGeneration must be non-negative");
        this.hostGeneration = hostGeneration;
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.identity = new MeshEditSessionIdentity(snapshot);
        this.drawable = snapshot.drawable() instanceof Drawable value ? value : null;
        this.selectionAdapter =
                resolver.resolver() == null ? null : new NativeVertexSelectionAdapter(this, resolver.resolver());
    }

    /** Returns the host generation captured when this session opened. */
    public long hostGeneration() {
        return hostGeneration;
    }

    /** Returns the opaque exact native identity tuple. */
    public MeshEditSessionIdentity identity() {
        return identity;
    }

    /** Returns the SDK drawable paired with this session. */
    public Drawable drawable() {
        return drawable;
    }

    VerifiedMemberResolver resolver() {
        return resolver.resolver();
    }

    /** Re-resolves and verifies every captured native identity. */
    public boolean revalidate() {
        if (!active.get()) return false;
        return resolver.currentSnapshot(identity.mode(), identity.artMesh())
                .filter(identity::matches)
                .isPresent();
    }

    @Override
    public dev.turboism.sdk.cubism.mesh.VertexSelection selection() {
        if (selectionAdapter == null) throw new IllegalStateException("Exact native selection is unavailable.");
        return selectionAdapter.selection();
    }

    @Override
    public void select(
            final dev.turboism.sdk.cubism.mesh.VertexSelection selection,
            final dev.turboism.sdk.cubism.mesh.SelectionMode mode) {
        if (selectionAdapter == null) throw new IllegalStateException("Exact native selection is unavailable.");
        selectionAdapter.select(selection, mode);
    }

    @Override
    public void close() {
        active.set(false);
    }
}
