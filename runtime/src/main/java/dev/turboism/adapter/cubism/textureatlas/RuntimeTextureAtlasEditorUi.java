package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;

import javax.swing.BoxLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Framework capability: attaches plugin-owned UI panels to the native texture-atlas
 * editor view. The exact-host ingress ({@link #ingress()}) records the constructed
 * editor view and attaches any panels registered so far; {@link #attach} attaches
 * immediately when the editor view is already live. Panels are rendered as Swing
 * labels owned by this adapter; plugins only see the semantic panel handle.
 */
public final class RuntimeTextureAtlasEditorUi implements TextureAtlasEditorUi, AutoCloseable {

    private final List<PanelHandle> panels = new ArrayList<>();
    private volatile WeakReference<Object> currentView;
    private long boundGeneration;
    private VerifiedMemberResolver boundResolver;
    private boolean closed;

    /** Loader-neutral ingress receiving the constructed host editor view. */
    public Consumer<Object> ingress() {
        return this::bindView;
    }

    /** Binds the verified resolver for the given host generation before its view is captured. */
    public synchronized void bind(final long generation, final VerifiedMemberResolver resolver) {
        if (closed) return;
        if (generation < 1) throw new IllegalArgumentException("generation must be positive");
        boundGeneration = generation;
        boundResolver = java.util.Objects.requireNonNull(resolver, "resolver");
    }

    private synchronized void bindView(final Object view) {
        if (closed || view == null || boundResolver == null) return;
        final WeakReference<Object> previous = currentView;
        final Object previousView = previous == null ? null : previous.get();
        if (previousView != null && previousView != view) {
            onEdt(() -> detachPanels(previousView));
        }
        currentView = new WeakReference<>(view);
        onEdt(() -> attachPanels(view));
    }

    @Override
    public synchronized TextureAtlasEditorPanel attach() {
        if (closed) {
            throw new IllegalStateException("Texture-atlas editor UI is closed.");
        }
        final PanelHandle panel = new PanelHandle(new javax.swing.JLabel());
        panels.removeIf(PanelHandle::closed);
        panels.add(panel);
        final WeakReference<Object> view = currentView;
        if (view != null && view.get() != null) {
            onEdt(() -> attachPanels(view.get()));
        }
        return panel;
    }

    private void attachPanels(final Object view) {
        if (!(view instanceof Container container)) {
            return;
        }
        panels.removeIf(PanelHandle::closed);
        if (panels.isEmpty()) {
            return;
        }
        JPanel pluginPanels = pluginPanels(container);
        boolean changed = false;
        for (PanelHandle handle : panels) {
            final javax.swing.JLabel panel = handle.label();
            if (panel.getParent() == pluginPanels) continue;
            if (panel.getParent() != null) {
                panel.getParent().remove(panel);
            }
            pluginPanels.add(panel);
            changed = true;
        }
        if (changed) {
            refresh(view);
        }
    }

    private static JPanel pluginPanels(final Container container) {
        for (Component component : container.getComponents()) {
            if (component instanceof JPanel panel && Boolean.TRUE.equals(
                panel.getClientProperty(RuntimeTextureAtlasEditorUi.class)
            )) {
                return panel;
            }
        }
        final JPanel panel = new JPanel();
        panel.putClientProperty(RuntimeTextureAtlasEditorUi.class, Boolean.TRUE);
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        container.add(panel, BorderLayout.SOUTH);
        return panel;
    }

    private void detachPanels(final Object view) {
        if (!(view instanceof Container container)) return;
        boolean changed = false;
        for (PanelHandle handle : panels) {
            final javax.swing.JLabel panel = handle.label();
            if (panel.getParent() != null && panel.getParent().getParent() == container) {
                panel.getParent().remove(panel);
                changed = true;
            }
        }
        for (Component component : container.getComponents()) {
            if (component instanceof JPanel panel && Boolean.TRUE.equals(
                panel.getClientProperty(RuntimeTextureAtlasEditorUi.class)
            ) && panel.getComponentCount() == 0) {
                container.remove(panel);
                changed = true;
            }
        }
        if (changed) {
            refresh(view);
        }
    }

    private static void refresh(final Object view) {
        if (view instanceof Component component) {
            component.revalidate();
            component.repaint();
        }
    }

    private void unregister(final PanelHandle panel) {
        synchronized (this) {
            if (!panels.remove(panel)) return;
        }
        onEdt(() -> {
            final javax.swing.JLabel label = panel.label();
            final Container parent = label.getParent();
            if (parent != null) {
                final Container view = parent.getParent();
                parent.remove(label);
                if (view != null && parent.getComponentCount() == 0) {
                    view.remove(parent);
                }
                refresh(view == null ? parent : view);
            }
        });
    }

    private final class PanelHandle implements TextureAtlasEditorPanel {
        private final javax.swing.JLabel label;
        private boolean closed;

        private PanelHandle(final javax.swing.JLabel label) {
            this.label = label;
        }

        @Override
        public void setText(final String text) {
            synchronized (this) {
                if (closed) return;
            }
            onEdt(() -> label.setText(text));
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            unregister(this);
        }

        private javax.swing.JLabel label() {
            return label;
        }

        private synchronized boolean closed() {
            return closed;
        }
    }

    /** Atomically captured resolver/view pair for the active host generation. */
    public synchronized RuntimeTextureAtlasEditorSession.GenerationBinding binding() {
        if (closed || boundResolver == null) return null;
        final Object view = currentView == null ? null : currentView.get();
        return view == null ? null : new RuntimeTextureAtlasEditorSession.GenerationBinding(
            boundGeneration, boundResolver, view
        );
    }

    /** Current host editor view, for callers that only need the native component. */
    public synchronized Object view() {
        return closed || currentView == null ? null : currentView.get();
    }

    /**
     * Detaches from the current host generation while preserving plugin panels for a later bind.
     * Unlike {@link #close()}, this capability remains open and accepts the next exact-host ingress.
     */
    public synchronized void deactivate() {
        if (closed) return;
        final WeakReference<Object> view = currentView;
        final Object current = view == null ? null : view.get();
        boundGeneration = 0;
        boundResolver = null;
        currentView = null;
        if (current != null) {
            onEdt(() -> detachPanels(current));
        }
    }

    @Override
    public void close() {
        final List<PanelHandle> registered;
        synchronized (this) {
            if (closed) return;
            deactivate();
            closed = true;
            registered = List.copyOf(panels);
        }
        registered.forEach(PanelHandle::close);
    }

    private static void onEdt(final Runnable task) {
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while updating the texture-atlas editor UI.", exception);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                final Throwable cause = exception.getCause();
                if (cause instanceof RuntimeException runtime) throw runtime;
                if (cause instanceof Error error) throw error;
                throw new IllegalStateException("Texture-atlas editor UI update failed.", cause);
            }
        }
    }

}
