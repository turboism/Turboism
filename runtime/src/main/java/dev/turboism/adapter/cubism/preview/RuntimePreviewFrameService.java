package dev.turboism.adapter.cubism.preview;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.cubism.preview.PreviewFrame;
import dev.turboism.sdk.cubism.preview.PreviewFrameService;
import dev.turboism.sdk.cubism.preview.PreviewFrameSource;
import dev.turboism.sdk.cubism.preview.PreviewFrameSourceClosedException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiConsumer;

/**
 * Runtime {@link PreviewFrameService}: the permission-gated, generation-scoped view of the
 * host-session-owned {@link RuntimePreviewSurfaceRegistry}.
 *
 * <p>Two invariants live here rather than in the registry, because they are per plugin: resolving a
 * source demands {@link PermissionIds#TURBOISM_UI_VIEWPORT_READ}, and every source is adopted into
 * the plugin's {@link DisposableScope} so the generation fence closes it instead of leaving a live
 * readback handle behind a retired plugin. A source whose preview went away is replaced on the next
 * {@link #focused()} call, never reused.</p>
 */
public final class RuntimePreviewFrameService implements PreviewFrameService {

    /** Permission gating every read of the host preview. */
    public static final String PERMISSION = PermissionIds.TURBOISM_UI_VIEWPORT_READ;

    /** Operation identity reported by the permission gate for a capture request. */
    public static final String OPERATION = "cubism.preview.capture";

    /**
     * Manifest capability a plugin declares to receive this service. Registered as the
     * {@code cubism.preview.frames} admission row so the preview reports can see it.
     */
    public static final String CAPABILITY = "cubism.preview.capture";

    private final RuntimePreviewSurfaceRegistry registry;
    private final PermissionChecker permissionChecker;
    private final DisposableScope scope;
    private final Object lock = new Object();
    private Source current;
    private boolean closed;

    /**
     * @param registry the host-session-owned preview surface registry, never {@code null}
     * @param permissionChecker the plugin's checker, never {@code null}
     * @param scope the plugin's disposable scope that owns the resolved sources, never {@code null}
     */
    public RuntimePreviewFrameService(
            final RuntimePreviewSurfaceRegistry registry,
            final PermissionChecker permissionChecker,
            final DisposableScope scope) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.scope = Objects.requireNonNull(scope, "scope");
    }

    /**
     * @param descriptor the calling plugin's descriptor, never {@code null}
     * @return {@code true} when the plugin declares the preview capture capability
     */
    public static boolean declaredBy(final PluginDescriptor descriptor) {
        return Objects.requireNonNull(descriptor, "descriptor").capabilities().contains(CAPABILITY);
    }

    @Override
    public Optional<PreviewFrameSource> focused() {
        permissionChecker.check(PERMISSION, OPERATION);
        if (closed || !registry.isAvailable() || scope.isSealed()) {
            retire();
            return Optional.empty();
        }
        final Optional<RuntimePreviewSurfaceRegistry.Surface> focused = registry.focused();
        if (focused.isEmpty()) {
            retire();
            return Optional.empty();
        }
        final RuntimePreviewSurfaceRegistry.Surface surface = focused.get();
        synchronized (lock) {
            if (current != null && current.surfaceId().equals(surface.surfaceId()) && !current.isClosed()) {
                return Optional.of(current);
            }
        }
        retire();
        final Source source = new Source(surface);
        try {
            source.adopt(scope.register(source));
        } catch (RuntimeException fenced) {
            // The plugin generation is already fenced: hand back nothing rather than a live source
            // no scope owns.
            source.close();
            return Optional.empty();
        }
        synchronized (lock) {
            if (closed) {
                source.close();
                return Optional.empty();
            }
            current = source;
        }
        return Optional.of(source);
    }

    @Override
    public boolean isAvailable() {
        return !closed && registry.isAvailable() && !scope.isSealed();
    }

    /**
     * Releases the cached source. Called by the owning scope on generation close, and internally
     * whenever the focused preview changes.
     */
    public void close() {
        closed = true;
        retire();
    }

    private void retire() {
        final Source stale;
        synchronized (lock) {
            stale = current;
            current = null;
        }
        if (stale != null) stale.close();
    }

    /** One resolved source: the registry surface plus this plugin's view of it. */
    private final class Source implements PreviewFrameSource {

        private final RuntimePreviewSurfaceRegistry.Surface surface;
        private volatile boolean sourceClosed;
        private Registration adoption = () -> {};

        private Source(final RuntimePreviewSurfaceRegistry.Surface surface) {
            this.surface = surface;
        }

        @Override
        public String surfaceId() {
            return surface.surfaceId();
        }

        @Override
        public int width() {
            return Math.max(1, surface.component().getWidth());
        }

        @Override
        public int height() {
            return Math.max(1, surface.component().getHeight());
        }

        @Override
        public CompletionStage<PreviewFrame> capture() {
            if (sourceClosed || closed || !surface.component().isDisplayable()) {
                final CompletableFuture<PreviewFrame> fenced = new CompletableFuture<>();
                fenced.completeExceptionally(new PreviewFrameSourceClosedException());
                return fenced;
            }
            return registry.capture(surface);
        }

        @Override
        public Registration attachControls(
                final PanelView content, final BiConsumer<String, Optional<UiActionEvent>> actionIds) {
            if (sourceClosed || closed) throw new PreviewFrameSourceClosedException();
            return registry.attachControls(surface, content, actionIds);
        }

        @Override
        public void updateControls(final PanelView content) {
            if (sourceClosed || closed) return;
            registry.updateControls(surface, content);
        }

        private boolean isClosed() {
            return sourceClosed;
        }

        private void adopt(final Registration registration) {
            adoption = registration;
        }

        @Override
        public void close() {
            sourceClosed = true;
            adoption.close();
        }
    }
}
