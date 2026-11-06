package dev.turboism.adapter.cubism.preview;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.cubism.preview.PreviewFrame;
import dev.turboism.sdk.cubism.preview.PreviewFrameSourceClosedException;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.ui.host.EdtDispatch;
import dev.turboism.ui.panel.SwingPanelViewRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Predicate;
import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Host-session-owned registry of the native preview surfaces whose framebuffer the runtime can read
 * and onto which a plugin may place its own control strip.
 *
 * <p>One registry instance outlives host generations. The physics-editor hook hands it every
 * preview panel it constructs; the registry keeps weak references so a rebuilt preview stays
 * collectable, resolves which of them has focus, reads the framebuffer on its own single thread and
 * renders the strip a plugin attached. A plugin never sees the host panel: it receives a
 * {@link RuntimePreviewFrameService} source that routes back here.</p>
 *
 * <p>Threading: registration, focus resolution and strip rendering happen on the host event
 * dispatch thread only; {@link #capture(Surface)} never reads back on the calling thread. Readbacks
 * are serialised on one thread, so two plugins recording at once cannot interleave OpenGL context
 * handoffs.</p>
 */
public final class RuntimePreviewSurfaceRegistry implements AutoCloseable {

    private final Object lock = new Object();
    private final List<Entry> entries = new ArrayList<>();
    private final ExecutorService readback = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "turboism-preview-readback");
        thread.setDaemon(true);
        return thread;
    });
    private final Predicate<JComponent> focusProbe;
    private boolean closed;

    /** Creates a registry whose focus probe is the host window's focus state. */
    public RuntimePreviewSurfaceRegistry() {
        this(RuntimePreviewSurfaceRegistry::isFocusedOnHost);
    }

    /**
     * @param focusProbe the predicate deciding whether a registered preview has focus; test seams
     *     replace the host window probe so the focus rule stays exercisable without a live host
     */
    RuntimePreviewSurfaceRegistry(final Predicate<JComponent> focusProbe) {
        this.focusProbe = Objects.requireNonNull(focusProbe, "focusProbe");
    }

    /**
     * Registers one constructed host preview panel.
     *
     * <p>Called from whatever host thread built the panel: the reference is taken on the host event
     * dispatch thread, and a panel registered twice is recorded once.</p>
     *
     * @param previewPanel the host preview panel, never {@code null}
     */
    public void register(final Object previewPanel) {
        Objects.requireNonNull(previewPanel, "previewPanel");
        EdtDispatch.post("preview surface register", () -> registerOnEdt(previewPanel));
    }

    /**
     * Returns the registered preview surface that currently has focus.
     *
     * @return the focused surface, or empty when no preview is open or none is focused
     */
    public Optional<Surface> focused() {
        return EdtDispatch.call("preview surface focus probe", this::focusOnEdt);
    }

    /**
     * Reads one frame of {@code surface} on the registry's readback thread.
     *
     * @param surface the surface to read, never {@code null}
     * @return a stage completing with the frame, or failing when the surface went away
     */
    public CompletionStage<PreviewFrame> capture(final Surface surface) {
        Objects.requireNonNull(surface, "surface");
        final CompletableFuture<PreviewFrame> result = new CompletableFuture<>();
        if (isClosed()) {
            result.completeExceptionally(new PreviewFrameSourceClosedException());
            return result;
        }
        try {
            readback.execute(() -> {
                try {
                    result.complete(readFrame(surface));
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException fenced) {
            // close() shut the readback thread down between the check and the submission.
            result.completeExceptionally(new PreviewFrameSourceClosedException());
        }
        return result;
    }

    /**
     * Places a plugin-owned control strip on {@code surface}, replacing any strip already there.
     *
     * @param surface the surface to attach to, never {@code null}
     * @param content the strip content the runtime renders, never {@code null}
     * @param actionIds sink receiving the action id of a control the plugin attached and, for a
     *     value-carrying control, the value it emitted
     * @return the registration that removes the strip when the plugin is done with it
     */
    public Registration attachControls(
            final Surface surface,
            final PanelView content,
            final BiConsumer<String, Optional<UiActionEvent>> actionIds) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(actionIds, "actionIds");
        if (isClosed()) throw new PreviewFrameSourceClosedException();
        EdtDispatch.call("preview controls attach", () -> {
            attachOnEdt(surface, content, actionIds);
            return null;
        });
        return () -> EdtDispatch.runEventually("preview controls detach", () -> detachOnEdt(surface));
    }

    /**
     * Replaces the content of the strip {@code surface} is showing.
     *
     * @param surface the surface whose strip to update, never {@code null}
     * @param content the new strip content, never {@code null}
     */
    public void updateControls(final Surface surface, final PanelView content) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(content, "content");
        if (isClosed()) return;
        EdtDispatch.runEventually("preview controls update", () -> updateOnEdt(surface, content));
    }

    /** @return {@code true} while this registry accepts registrations and readbacks */
    public boolean isAvailable() {
        return !isClosed();
    }

    @Override
    public void close() {
        final List<Surface> stale = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Entry entry : entries) {
                final Surface surface = entry.liveSurface();
                if (surface != null) stale.add(surface);
            }
            entries.clear();
        }
        EdtDispatch.runEventually("preview surfaces detach", () -> stale.forEach(this::detachOnEdt));
        readback.shutdownNow();
    }

    private boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void registerOnEdt(final Object previewPanel) {
        if (isClosed()) return;
        try {
            final Object component = PreviewFramebufferCapture.invoke(previewPanel, "getJComponent");
            if (!(component instanceof JComponent surface) || !(surface.getLayout() instanceof BorderLayout)) {
                return;
            }
            synchronized (lock) {
                for (Iterator<Entry> iterator = entries.iterator(); iterator.hasNext(); ) {
                    final Entry entry = iterator.next();
                    final JComponent registered = entry.liveView();
                    if (registered == null) iterator.remove();
                    else if (registered == surface) return;
                }
                entries.add(new Entry(new WeakReference<>(previewPanel), new WeakReference<>(surface)));
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            dev.turboism.runtime.log.RuntimeDiagnostics.error(
                    "preview-capture", "Preview surface could not be registered", failure);
        }
    }

    private Optional<Surface> focusOnEdt() {
        if (isClosed()) return Optional.empty();
        final List<Surface> candidates = new ArrayList<>();
        synchronized (lock) {
            for (Iterator<Entry> iterator = entries.iterator(); iterator.hasNext(); ) {
                final Entry entry = iterator.next();
                final Surface surface = entry.liveSurface();
                if (surface == null) iterator.remove();
                else candidates.add(surface);
            }
        }
        for (Surface surface : candidates) {
            if (focusProbe.test(surface.component())) return Optional.of(surface);
        }
        return Optional.empty();
    }

    /** The host focus rule: a displayable, showing preview inside the focused window. */
    private static boolean isFocusedOnHost(final JComponent component) {
        if (!component.isDisplayable() || !component.isShowing()) return false;
        final Window window = SwingUtilities.getWindowAncestor(component);
        return window != null && window.isFocused();
    }

    private void attachOnEdt(
            final Surface surface,
            final PanelView content,
            final BiConsumer<String, Optional<UiActionEvent>> actionIds) {
        final JComponent component = surface.component();
        if (!(component.getLayout() instanceof BorderLayout layout)) return;
        final Strip existing = stripOf(component);
        if (existing != null) {
            existing.replace(content, actionIds);
            return;
        }
        final JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        final Component original = layout.getLayoutComponent(BorderLayout.NORTH);
        if (original != null) wrapper.add(original, BorderLayout.CENTER);
        final Strip strip = new Strip(component, wrapper, original, content, actionIds);
        component.putClientProperty(Strip.MARKER, strip);
        strip.render();
        component.add(wrapper, BorderLayout.NORTH);
        component.revalidate();
        component.repaint();
    }

    private void updateOnEdt(final Surface surface, final PanelView content) {
        final Strip strip = stripOf(surface.component());
        if (strip != null) strip.replace(content, strip.actions);
    }

    private void detachOnEdt(final Surface surface) {
        final JComponent component = surface.component();
        final Strip strip = stripOf(component);
        if (strip == null) return;
        component.putClientProperty(Strip.MARKER, null);
        strip.dispose();
        component.revalidate();
        component.repaint();
    }

    private static Strip stripOf(final JComponent component) {
        return component.getClientProperty(Strip.MARKER) instanceof Strip strip ? strip : null;
    }

    private PreviewFrame readFrame(final Surface surface) throws Exception {
        if (isClosed() || !surface.component().isDisplayable()) throw new PreviewFrameSourceClosedException();
        // The capture object owns one readback buffer and is disposed with the frame, so a retired
        // OpenGL context is never reused by the next capture.
        try (PreviewFramebufferCapture capture = new PreviewFramebufferCapture(surface.panel())) {
            final BufferedImage image = capture.capture();
            final ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", encoded)) throw new IllegalStateException("PNG encoder unavailable");
            return new PreviewFrame(image.getWidth(), image.getHeight(), encoded.toByteArray());
        }
    }

    /**
     * One live preview surface: the host panel the readback drives and the component it renders in.
     *
     * @param surfaceId the stable identity reported to a plugin, never {@code null}
     * @param panel the host preview panel the framebuffer readback needs, never {@code null}
     * @param component the host preview component the strip is rendered into, never {@code null}
     */
    public record Surface(String surfaceId, Object panel, JComponent component) {

        public Surface {
            Objects.requireNonNull(surfaceId, "surfaceId");
            Objects.requireNonNull(panel, "panel");
            Objects.requireNonNull(component, "component");
        }
    }

    /** One registered host preview, held weakly so a rebuilt preview stays collectable. */
    private record Entry(WeakReference<Object> panel, WeakReference<JComponent> view) {

        private JComponent liveView() {
            return view.get();
        }

        private Surface liveSurface() {
            final Object host = panel.get();
            final JComponent component = view.get();
            if (host == null || component == null) return null;
            return new Surface(
                    "cubism.preview." + Integer.toHexString(System.identityHashCode(component)), host, component);
        }
    }

    /** One plugin-owned control strip: the wrapper this registry installed and the content it shows. */
    private static final class Strip {

        private static final String MARKER = "turboism.preview.controls";

        private final JComponent surface;
        private final JPanel wrapper;
        private final Component original;
        private final String surfaceId;
        private JComponent rendered;
        private PanelView content;
        private BiConsumer<String, Optional<UiActionEvent>> actions;

        private Strip(
                final JComponent surface,
                final JPanel wrapper,
                final Component original,
                final PanelView content,
                final BiConsumer<String, Optional<UiActionEvent>> actions) {
            this.surface = surface;
            this.wrapper = wrapper;
            this.original = original;
            this.surfaceId = "cubism.preview." + Integer.toHexString(System.identityHashCode(surface));
            this.content = content;
            this.actions = actions;
        }

        private void replace(final PanelView next, final BiConsumer<String, Optional<UiActionEvent>> nextActions) {
            content = next;
            actions = nextActions;
            render();
            wrapper.revalidate();
            wrapper.repaint();
        }

        private void render() {
            final JComponent previous = rendered;
            if (previous != null) wrapper.remove(previous);
            rendered = SwingPanelViewRenderer.render(content, this::dispatch);
            wrapper.add(rendered, BorderLayout.NORTH);
        }

        private void dispatch(final String actionId, final Optional<UiActionEvent> event) {
            final BiConsumer<String, Optional<UiActionEvent>> sink = actions;
            if (sink == null) return;
            try {
                sink.accept(actionId, event);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                dev.turboism.runtime.log.RuntimeDiagnostics.error(surfaceId, "Preview control action failed", failure);
            }
        }

        private void dispose() {
            wrapper.removeAll();
            surface.remove(wrapper);
            if (original != null) surface.add(original, BorderLayout.NORTH);
            rendered = null;
            content = null;
            actions = null;
        }
    }
}
