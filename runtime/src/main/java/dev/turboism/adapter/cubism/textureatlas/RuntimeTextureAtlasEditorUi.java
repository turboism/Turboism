package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorPanel;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;

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
    private boolean closed;

    /** Loader-neutral ingress receiving the constructed host editor view. */
    public Consumer<Object> ingress() {
        return this::bindView;
    }

    private synchronized void bindView(final Object view) {
        if (closed || view == null) return;
        final WeakReference<Object> previous = currentView;
        final Object previousView = previous == null ? null : previous.get();
        if (previousView != null && previousView != view) {
            detachPanels(previousView);
        }
        currentView = new WeakReference<>(view);
        attachPanels(view);
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
            attachPanels(view.get());
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
        boolean changed = false;
        for (PanelHandle handle : panels) {
            final javax.swing.JLabel panel = handle.label();
            if (panel.getParent() == container) continue;
            if (panel.getParent() != null) {
                panel.getParent().remove(panel);
            }
            container.add(panel, BorderLayout.SOUTH);
            changed = true;
        }
        if (changed) {
            refresh(view);
        }
    }

    private void detachPanels(final Object view) {
        if (!(view instanceof Container container)) return;
        boolean changed = false;
        for (PanelHandle handle : panels) {
            final javax.swing.JLabel panel = handle.label();
            if (panel.getParent() == container) {
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

    private synchronized void unregister(final PanelHandle panel) {
        if (!panels.remove(panel)) return;
        final javax.swing.JLabel label = panel.label();
        final Container parent = label.getParent();
        if (parent != null) {
            parent.remove(label);
            refresh(parent);
        }
    }

    private final class PanelHandle implements TextureAtlasEditorPanel {
        private final javax.swing.JLabel label;
        private boolean closed;

        private PanelHandle(final javax.swing.JLabel label) {
            this.label = label;
        }

        @Override
        public synchronized void setText(final String text) {
            if (closed) return;
            label.setText(text);
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

    /** Current host editor view, for the read-only session. */
    public synchronized Object view() {
        if (closed) return null;
        final WeakReference<Object> view = currentView;
        return view == null ? null : view.get();
    }

    /**
     * Detaches from the current host generation while preserving plugin panels for a later bind.
     * Unlike {@link #close()}, this capability remains open and accepts the next exact-host ingress.
     */
    public synchronized void deactivate() {
        if (closed) return;
        final WeakReference<Object> view = currentView;
        final Object current = view == null ? null : view.get();
        if (current != null) {
            detachPanels(current);
        }
        currentView = null;
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
}
