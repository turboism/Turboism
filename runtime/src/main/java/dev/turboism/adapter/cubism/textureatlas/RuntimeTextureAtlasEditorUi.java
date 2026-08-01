package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasEditorUi;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Framework capability: attaches plugin-owned UI panels to the native texture-atlas
 * editor view. The exact-host ingress ({@link #ingress()}) records the constructed
 * editor view and attaches any panels registered so far; {@link #attach} attaches
 * immediately when the editor view is already live.
 */
public final class RuntimeTextureAtlasEditorUi implements TextureAtlasEditorUi {

    private final List<WeakReference<javax.swing.JComponent>> panels = new ArrayList<>();
    private volatile WeakReference<Object> currentView;

    /** Loader-neutral ingress receiving the constructed host editor view. */
    public Consumer<Object> ingress() {
        return this::bindView;
    }

    private synchronized void bindView(final Object view) {
        if (view == null) return;
        currentView = new WeakReference<>(view);
        attachPanels(view);
    }

    @Override
    public synchronized void attach(final javax.swing.JComponent panel) {
        Objects.requireNonNull(panel, "panel");
        panels.removeIf(reference -> reference.get() == null);
        panels.add(new WeakReference<>(panel));
        final WeakReference<Object> view = currentView;
        if (view != null && view.get() != null) {
            attachPanels(view.get());
        }
    }

    private void attachPanels(final Object view) {
        if (!(view instanceof Container container)) {
            return;
        }
        panels.removeIf(reference -> reference.get() == null);
        for (WeakReference<javax.swing.JComponent> reference : panels) {
            final javax.swing.JComponent panel = reference.get();
            if (panel == null || panel.getParent() == container) continue;
            container.add(panel, BorderLayout.SOUTH);
        }
        if (view instanceof Component component) {
            component.revalidate();
            component.repaint();
        }
    }

    /** Current host editor view, for the read-only session. */
    public Object view() {
        final WeakReference<Object> view = currentView;
        return view == null ? null : view.get();
    }
}
