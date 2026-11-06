package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;
import dev.turboism.sdk.ui.HostViewAnchors;
import dev.turboism.sdk.ui.HostViewPanelHandle;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.panel.RuntimeHostViewPanelRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Deprecated {@link TextureAtlasEditorUi} adapter over the generalized
 * {@link RuntimeHostViewPanelRegistry}.
 *
 * <p>The attachment machinery — host-view ingress, the plugin container, EDT marshalling and
 * visibility reporting — lives once in the registry. This class keeps only what the released
 * interface adds: the exact-host generation binding the texture-atlas editor session resolves
 * through, the single-text-line panel shape, and close-on-fence of the panels it created. New
 * code contributes {@link PanelView} content through {@link dev.turboism.sdk.ui.HostViewPanel}
 * with the {@link HostViewAnchors#TEXTURE_ATLAS_EDITOR} anchor instead.</p>
 */
@SuppressWarnings("removal") // the released surface this adapter keeps working
public final class RuntimeTextureAtlasEditorUi implements TextureAtlasEditorUi, AutoCloseable {

    private static final String ANCHOR = HostViewAnchors.TEXTURE_ATLAS_EDITOR;

    private final RuntimeHostViewPanelRegistry panels;
    private final List<TextPanel> attached = new ArrayList<>();
    private long nextOrder;
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

    @Override
    public synchronized boolean isAvailable() {
        return !closed;
    }

    @Override
    public synchronized TextureAtlasEditorPanel attach() {
        if (closed) {
            throw new IllegalStateException("Texture-atlas editor UI is closed.");
        }
        attached.removeIf(TextPanel::isClosed);
        final TextPanel panel = new TextPanel(panels.attach(ANCHOR, PanelView.text(""), (int) nextOrder++));
        attached.add(panel);
        return panel;
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
     * Detaches from the current host generation while preserving plugin panels for a later bind.
     * Unlike {@link #close()}, this capability remains open and accepts the next exact-host ingress.
     */
    public synchronized void deactivate() {
        if (closed) return;
        boundGeneration = 0;
        boundResolver = null;
        panels.deactivate(ANCHOR);
    }

    @Override
    public void close() {
        final List<TextPanel> registered;
        synchronized (this) {
            if (closed) return;
            closed = true;
            boundGeneration = 0;
            boundResolver = null;
            registered = List.copyOf(attached);
            attached.clear();
        }
        panels.deactivate(ANCHOR);
        registered.forEach(TextPanel::close);
    }

    /** One released single-text-line panel, rendered as {@link PanelView#text} content. */
    private final class TextPanel implements TextureAtlasEditorPanel {

        private final HostViewPanelHandle handle;
        private volatile boolean closed;

        private TextPanel(final HostViewPanelHandle handle) {
            this.handle = handle;
        }

        @Override
        public void setText(final String text) {
            handle.setContent(PanelView.text(text == null ? "" : text));
        }

        @Override
        public boolean isVisible() {
            return !closed && handle.isVisible();
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            synchronized (RuntimeTextureAtlasEditorUi.this) {
                attached.remove(this);
            }
            handle.close();
        }

        private boolean isClosed() {
            return closed;
        }
    }
}
