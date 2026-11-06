package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.HostViewAnchors;
import dev.turboism.sdk.ui.HostViewPanel;
import dev.turboism.sdk.ui.HostViewPanelHandle;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.HierarchyEvent;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Runtime {@link HostViewPanel}: attaches plugin-owned {@link PanelView} content to a named host
 * view anchor.
 *
 * <p>One registry instance is owned by the host session and outlives host generations. A host
 * adapter hands the constructed view to {@link #ingress(String)}; the registry renders every
 * attached panel into one container it adds at the host view's south edge, and re-renders that
 * container after a host rebuild. Plugins only ever see {@link HostViewPanelHandle}: the host
 * components, the container and the ordering stay runtime-owned.</p>
 *
 * <p>Threading: panel content is rendered and attached on the host event dispatch thread only.
 * {@link HostViewPanelHandle#setContent} may be called from any thread and is marshalled there. An
 * update that arrives after its handle was closed — a retired plugin generation, or a closed anchor
 * — is dropped silently: a plugin's late write must never surface in the host as an exception.</p>
 */
public final class RuntimeHostViewPanelRegistry implements HostViewPanel, AutoCloseable {

    /** Anchors the runtime has an adapter for; a new host window adds a constant and an ingress. */
    private static final Set<String> SUPPORTED_ANCHORS = Set.of(HostViewAnchors.TEXTURE_ATLAS_EDITOR);

    private static final Comparator<Handle> ORDER =
            Comparator.comparingInt(Handle::order).thenComparingLong(Handle::sequence);

    private final Map<String, AnchorSurface> anchors = new HashMap<>();
    private volatile boolean closed;

    @Override
    public boolean isAvailable() {
        return !closed;
    }

    @Override
    public HostViewPanelHandle attach(final String anchor, final PanelView content, final int order) {
        Objects.requireNonNull(content, "content");
        return surface(anchor).attach(content, order);
    }

    /**
     * Returns the loader-neutral ingress that receives the host view constructed for {@code anchor}.
     * A runtime adapter wires it where it observes the host window come alive.
     *
     * @param anchor the host view anchor to bind, one of the {@link HostViewAnchors} constants
     * @return the sink receiving the constructed host view, never {@code null}
     * @throws IllegalArgumentException when this registry has no adapter for {@code anchor}
     * @throws IllegalStateException when this registry is already closed
     */
    public Consumer<Object> ingress(final String anchor) {
        surface(anchor);
        return view -> bind(anchor, view);
    }

    /**
     * Binds the current host view for {@code anchor} and renders the attached panels into it.
     * Panels attached earlier move to the new view; the retired view is cleaned up afterwards.
     *
     * @param anchor the host view anchor
     * @param view the constructed host view; {@code null} is ignored
     */
    public void bind(final String anchor, final Object view) {
        surface(anchor).bind(view);
    }

    /**
     * Detaches the current host view for {@code anchor} while preserving attached panels for the
     * next host generation.
     *
     * @param anchor the host view anchor
     */
    public void deactivate(final String anchor) {
        final AnchorSurface surface = surfaceOrNull(anchor);
        if (surface != null) surface.deactivate();
    }

    /**
     * Detaches every panel from {@code anchor} and refuses further attachment there; other anchors
     * stay live.
     *
     * @param anchor the host view anchor
     */
    public void closeAnchor(final String anchor) {
        final AnchorSurface surface = surfaceOrNull(anchor);
        if (surface != null) surface.close();
    }

    /**
     * @param anchor the host view anchor
     * @return the bound host view, or {@code null} when none is bound
     */
    public Object view(final String anchor) {
        final AnchorSurface surface = surfaceOrNull(anchor);
        return surface == null ? null : surface.view();
    }

    @Override
    public void close() {
        final List<AnchorSurface> surfaces;
        synchronized (this) {
            if (closed) return;
            closed = true;
            surfaces = List.copyOf(anchors.values());
        }
        surfaces.forEach(AnchorSurface::close);
    }

    private AnchorSurface surface(final String anchor) {
        Objects.requireNonNull(anchor, "anchor");
        if (!SUPPORTED_ANCHORS.contains(anchor)) {
            throw new IllegalArgumentException("host view panel anchor is not supported: " + anchor);
        }
        synchronized (this) {
            if (closed) {
                throw new IllegalStateException("host view panel registry is closed");
            }
            return anchors.computeIfAbsent(anchor, AnchorSurface::new);
        }
    }

    private AnchorSurface surfaceOrNull(final String anchor) {
        Objects.requireNonNull(anchor, "anchor");
        synchronized (this) {
            return anchors.get(anchor);
        }
    }

    /** Attachment state of one host view anchor; the registry's unit of generation fencing. */
    private final class AnchorSurface {

        private final String anchor;
        private final List<Handle> handles = new ArrayList<>();
        private WeakReference<Object> view = new WeakReference<>(null);
        private long sequence;
        private boolean closed;

        private AnchorSurface(final String anchor) {
            this.anchor = anchor;
        }

        private HostViewPanelHandle attach(final PanelView content, final int order) {
            final Handle handle;
            final Object current;
            synchronized (this) {
                if (closed) {
                    throw new IllegalStateException("host view panel anchor is closed: " + anchor);
                }
                handle = new Handle(this, content, order, ++sequence);
                handles.add(handle);
                handles.sort(ORDER);
                current = view.get();
            }
            if (current != null) {
                onEdt("host view panel attach", () -> sync(current));
            }
            return handle;
        }

        private void bind(final Object next) {
            final Object previous;
            synchronized (this) {
                if (closed || next == null) return;
                previous = view.get();
                view = new WeakReference<>(next);
            }
            if (previous != null && previous != next) {
                onEdtEventually("host view panel detach", () -> detach(previous));
            }
            onEdt("host view panel attach", () -> sync(next));
        }

        private void deactivate() {
            final Object current;
            synchronized (this) {
                if (closed) return;
                current = view.get();
                view = new WeakReference<>(null);
            }
            if (current != null) {
                onEdtEventually("host view panel detach", () -> detach(current));
            }
        }

        private void close() {
            final List<Handle> registered;
            final Object current;
            synchronized (this) {
                if (closed) return;
                closed = true;
                registered = List.copyOf(handles);
                handles.clear();
                current = view.get();
                view = new WeakReference<>(null);
            }
            registered.forEach(Handle::close);
            if (current != null) {
                onEdtEventually("host view panel detach", () -> detach(current));
            }
        }

        private Object view() {
            synchronized (this) {
                return closed ? null : view.get();
            }
        }

        private List<Handle> snapshot() {
            synchronized (this) {
                return List.copyOf(handles);
            }
        }

        private boolean isClosed() {
            synchronized (this) {
                return closed;
            }
        }

        private void remove(final Handle handle) {
            final Object current;
            synchronized (this) {
                if (!handles.remove(handle)) return;
                current = view.get();
            }
            if (current != null) {
                onEdtEventually("host view panel removal", () -> sync(current));
            }
        }

        /** Renders every live panel into the bound view's plugin container, in attach order. */
        private void sync(final Object hostView) {
            if (!(hostView instanceof Container container) || isClosed()) return;
            // A queued update may reach the EDT after the host moved on; a retired view is never
            // repopulated, so a fenced panel cannot reappear on a stale host window.
            if (view() != hostView) return;
            final List<JComponent> rendered = new ArrayList<>();
            for (Handle handle : snapshot()) {
                final JComponent component = handle.rendered();
                if (component != null) rendered.add(component);
            }
            final JPanel existing = pluginPanels(container);
            if (rendered.isEmpty()) {
                if (existing != null) {
                    container.remove(existing);
                    existing.removeAll();
                    refresh(container);
                }
                return;
            }
            final JPanel target = existing != null ? existing : createPluginPanels(container);
            if (!Arrays.asList(target.getComponents()).equals(rendered)) {
                target.removeAll();
                for (JComponent component : rendered) {
                    target.add(component);
                }
                refresh(container);
            }
        }

        /** Removes this anchor's plugin container, and the content it held, from a retired host view. */
        private void detach(final Object hostView) {
            if (!(hostView instanceof Container container)) return;
            final JPanel existing = pluginPanels(container);
            if (existing == null) return;
            container.remove(existing);
            existing.removeAll();
            refresh(container);
        }

        private JPanel pluginPanels(final Container container) {
            for (Component component : container.getComponents()) {
                if (component instanceof JPanel panel && Boolean.TRUE.equals(panel.getClientProperty(this))) {
                    return panel;
                }
            }
            return null;
        }

        private JPanel createPluginPanels(final Container container) {
            final JPanel panel = new JPanel();
            panel.putClientProperty(this, Boolean.TRUE);
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            container.add(panel, BorderLayout.SOUTH);
            return panel;
        }
    }

    /** One plugin-owned attachment: the registry's unit of visibility reporting and fencing. */
    private final class Handle implements HostViewPanelHandle {

        private final AnchorSurface surface;
        private final int order;
        private final long sequence;
        private PanelView content;
        private volatile JComponent rendered;
        private volatile boolean visible;
        private volatile boolean closed;

        private Handle(final AnchorSurface surface, final PanelView content, final int order, final long sequence) {
            this.surface = surface;
            this.content = content;
            this.order = order;
            this.sequence = sequence;
        }

        @Override
        public void setContent(final PanelView next) {
            Objects.requireNonNull(next, "content");
            final Object current;
            synchronized (this) {
                if (closed) return;
                content = next;
                if (rendered == null) return;
            }
            current = surface.view();
            if (current == null) return;
            onEdt("host view panel content", () -> {
                rebuild();
                surface.sync(current);
            });
        }

        @Override
        public boolean isVisible() {
            return !closed && visible;
        }

        @Override
        public void close() {
            synchronized (this) {
                if (closed) return;
                closed = true;
            }
            surface.remove(this);
        }

        private int order() {
            return order;
        }

        private long sequence() {
            return sequence;
        }

        /**
         * Renders the current content once and reports visibility from the rendered component.
         * Must run on the host event dispatch thread: it builds live components.
         */
        private void rebuild() {
            final PanelView value;
            synchronized (this) {
                if (closed) return;
                value = content;
            }
            final JComponent component = SwingPanelViewRenderer.render(value, (actionId, event) -> {});
            // The renderer is Swing-owned, so show/hide transitions are observed on the host
            // thread and published as one volatile flag. A polling plugin then reads a plain
            // boolean instead of touching the host component from its own thread. Hierarchy
            // events are posted, so a rebuilt component ignores the retired tree's late events
            // instead of overwriting the new component's visibility.
            component.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && rendered == component) {
                    visible = component.isShowing();
                }
            });
            synchronized (this) {
                if (closed) return;
                rendered = component;
                visible = component.isShowing();
            }
        }

        private JComponent rendered() {
            if (closed) return null;
            synchronized (this) {
                if (rendered == null) {
                    rebuild();
                }
                return rendered;
            }
        }
    }

    private static void refresh(final Object hostView) {
        if (hostView instanceof Component component) {
            component.revalidate();
            component.repaint();
        }
    }

    private static void onEdt(final String label, final Runnable task) {
        EdtDispatch.call(label, () -> {
            task.run();
            return null;
        });
    }

    /**
     * Idempotent detach/removal work: on acceptance timeout the task stays queued and still runs
     * exactly once when the EDT drains, so a detached or closed panel is never orphaned on a stale
     * host view.
     */
    private static void onEdtEventually(final String label, final Runnable task) {
        EdtDispatch.runEventually(label, task);
    }
}
