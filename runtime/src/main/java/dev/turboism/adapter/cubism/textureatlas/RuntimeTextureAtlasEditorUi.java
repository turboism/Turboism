package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.ui.HostViewAnchors;
import dev.turboism.ui.panel.RuntimeHostViewPanelRegistry;
import java.util.Objects;
import java.util.function.Consumer;

/** Binds the editor-session read service to the active host view and generation. */
public final class RuntimeTextureAtlasEditorUi implements AutoCloseable {

    private static final String ANCHOR = HostViewAnchors.TEXTURE_ATLAS_EDITOR;

    private final RuntimeHostViewPanelRegistry panels;
    private long boundGeneration;
    private VerifiedMemberResolver boundResolver;
    private boolean closed;

    /** Creates an adapter over a private registry, for callers that own no host session. */
    public RuntimeTextureAtlasEditorUi() {
        this(new RuntimeHostViewPanelRegistry());
    }

    /**
     * Creates the adapter that contributes into {@code panels}' texture-atlas anchor.
     *
     * @param panels the host-session-owned panel registry, never {@code null}
     */
    public RuntimeTextureAtlasEditorUi(final RuntimeHostViewPanelRegistry panels) {
        this.panels = Objects.requireNonNull(panels, "panels");
    }

    /** Loader-neutral ingress receiving the constructed host editor view. */
    public Consumer<Object> ingress() {
        return this::bindView;
    }

    /** Binds the verified resolver for the given host generation before its view is captured. */
    public synchronized void bind(final long generation, final VerifiedMemberResolver resolver) {
        if (closed) return;
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        boundGeneration = generation;
        boundResolver = Objects.requireNonNull(resolver, "resolver");
    }

    private void bindView(final Object view) {
        final boolean bound;
        synchronized (this) {
            bound = !closed && boundResolver != null;
        }
        if (!bound || view == null) return;
        panels.bind(ANCHOR, view);
    }

    /** Atomically captured resolver/view pair for the active host generation. */
    public synchronized RuntimeTextureAtlasEditorSession.GenerationBinding binding() {
        if (closed || boundResolver == null) return null;
        final Object view = panels.view(ANCHOR);
        return view == null
                ? null
                : new RuntimeTextureAtlasEditorSession.GenerationBinding(boundGeneration, boundResolver, view);
    }

    /** Current host editor view, for callers that only need the native component. */
    public Object view() {
        return closed ? null : panels.view(ANCHOR);
    }

    /**
     * Detaches from the current host generation while keeping this capability open.
     * Unlike {@link #close()}, it accepts the next exact-host ingress.
     */
    public synchronized void deactivate() {
        if (closed) return;
        boundGeneration = 0;
        boundResolver = null;
        panels.deactivate(ANCHOR);
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        boundGeneration = 0;
        boundResolver = null;
        panels.deactivate(ANCHOR);
    }
}
