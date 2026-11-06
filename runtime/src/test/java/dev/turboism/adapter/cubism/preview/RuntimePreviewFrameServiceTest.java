package dev.turboism.adapter.cubism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.preview.PreviewFrameService;
import dev.turboism.sdk.cubism.preview.PreviewFrameSource;
import dev.turboism.sdk.cubism.preview.PreviewFrameSourceClosedException;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.PanelView;
import java.awt.BorderLayout;
import java.awt.Component;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * The preview frame capability is generic: it resolves the focused preview, reads the framebuffer on
 * its own thread and renders a plugin-owned strip without ever handing the host panel to the plugin.
 */
class RuntimePreviewFrameServiceTest {

    private static final String CAPABILITY = "cubism.preview.capture";

    @Test
    void noRegisteredPreviewResolvesNothing() {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true)) {
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), new DisposableScope());

            assertTrue(service.focused().isEmpty());
            assertTrue(service.isAvailable());
        }
    }

    @Test
    void focusedPreviewResolvesOneSourceThatIsReusedUntilThePreviewChanges() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<JPanel> focused =
                new java.util.concurrent.atomic.AtomicReference<>();
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> component == focused.get())) {
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), new DisposableScope());
            final JPanel surface = surface();
            focused.set(surface);
            registry.register(new Panel(surface));
            flushEdt();

            final PreviewFrameSource first = service.focused().orElseThrow();
            assertEquals(first.surfaceId(), service.focused().orElseThrow().surfaceId(), "same preview, same id");
            assertSame(first, service.focused().orElseThrow(), "the resolved source is cached per surface");

            final JPanel reopened = surface();
            focused.set(reopened);
            registry.register(new Panel(reopened));
            flushEdt();
            final PreviewFrameSource second = service.focused().orElseThrow();
            assertNotEquals(first.surfaceId(), second.surfaceId(), "a rebuilt preview is a new surface");
        }
    }

    @Test
    void resolvingASourceRequiresTheViewportReadPermission() {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true)) {
            final PermissionChecker denyAll = (permissionId, operation) -> {
                throw new CubismPermissionException(permissionId + " not granted for " + operation);
            };
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, denyAll, new DisposableScope());

            assertThrows(CubismPermissionException.class, service::focused);
        }
    }

    @Test
    void captureNeverReadsTheFramebufferOnTheCallingThread() throws Exception {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true)) {
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), new DisposableScope());
            final Panel panel = new Panel(liveSurface());
            registry.register(panel);
            flushEdt();
            final PreviewFrameSource source = service.focused().orElseThrow();

            final CompletableFuture<dev.turboism.sdk.cubism.preview.PreviewFrame> stage =
                    source.capture().toCompletableFuture();
            final ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stage.get(30, TimeUnit.SECONDS));

            assertNotNull(failure.getCause(), "the readback failure reaches the stage, not the caller");
            assertEquals(
                    "turboism-preview-readback",
                    panel.readbackThread,
                    "the framebuffer is read on the registry's own thread");
            assertNotEquals(Thread.currentThread().getName(), panel.readbackThread);
        }
    }

    @Test
    void aClosedSourceFailsTheStageWithTheTypedStaleFailure() throws Exception {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true);
                var scope = new DisposableScope()) {
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), scope);
            registry.register(new Panel(surface()));
            flushEdt();
            final PreviewFrameSource source = service.focused().orElseThrow();

            source.close();

            final CompletableFuture<dev.turboism.sdk.cubism.preview.PreviewFrame> stage =
                    source.capture().toCompletableFuture();
            final ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stage.get(30, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PreviewFrameSourceClosedException);
            assertThrows(
                    PreviewFrameSourceClosedException.class,
                    () -> source.attachControls(PanelView.text("x"), (actionId, event) -> {}));
        }
    }

    @Test
    void closingThePluginScopeFencesTheResolvedSource() throws Exception {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true)) {
            final DisposableScope scope = new DisposableScope();
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), scope);
            registry.register(new Panel(surface()));
            flushEdt();
            final PreviewFrameSource source = service.focused().orElseThrow();

            scope.close();

            final CompletableFuture<dev.turboism.sdk.cubism.preview.PreviewFrame> stage =
                    source.capture().toCompletableFuture();
            final ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stage.get(30, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof PreviewFrameSourceClosedException);
            assertTrue(service.focused().isEmpty(), "a fenced scope resolves nothing");
        }
    }

    @Test
    void theControlStripReplacesTheHostChromeAndIsRestoredWhenItIsWithdrawn() throws Exception {
        try (var registry = new RuntimePreviewSurfaceRegistry(component -> true)) {
            final RuntimePreviewFrameService service =
                    new RuntimePreviewFrameService(registry, PermissionChecker.allowAll(), new DisposableScope());
            final JPanel surface = surface();
            final JLabel nativeControls = new JLabel("Native preview controls");
            surface.removeAll();
            surface.add(nativeControls, BorderLayout.NORTH);
            registry.register(new Panel(surface));
            flushEdt();
            final PreviewFrameSource source = service.focused().orElseThrow();

            final List<String> actions = new ArrayList<>();
            final Registration strip = source.attachControls(
                    PanelView.row(PanelView.button("record", "Record", "record"), PanelView.text("Recording 3")),
                    (actionId, event) -> actions.add(actionId));

            final JPanel wrapper = (JPanel) north(surface);
            assertNotNull(wrapper);
            assertTrue(wrapper.getComponentCount() > 0, "the plugin strip is rendered into the preview");
            assertEquals(
                    nativeControls, center(wrapper), "the host's own preview chrome stays reachable below the strip");

            strip.close();
            flushEdt();

            assertSame(nativeControls, north(surface), "withdrawing the strip restores the host chrome in place");
        }
    }

    @Test
    void theServiceIsNotAvailableWithoutTheDeclaredCapability() {
        final PluginDescriptor without = descriptor(List.of());
        final PluginDescriptor with = descriptor(List.of(CAPABILITY));

        assertFalse(RuntimePreviewFrameService.declaredBy(without));
        assertTrue(RuntimePreviewFrameService.declaredBy(with));
        assertEquals(
                PermissionIds.TURBOISM_UI_VIEWPORT_READ,
                RuntimePreviewFrameService.PERMISSION,
                "the capability is gated on the viewport-read permission");
        assertFalse(PreviewFrameService.unavailable().isAvailable());
    }

    private static PluginDescriptor descriptor(final List<String> capabilities) {
        return (PluginDescriptor) Proxy.newProxyInstance(
                PluginDescriptor.class.getClassLoader(),
                new Class<?>[] {PluginDescriptor.class},
                (proxy, method, arguments) -> "capabilities".equals(method.getName()) ? capabilities : null);
    }

    private static JPanel surface() {
        final JPanel surface = new JPanel(new BorderLayout());
        surface.setSize(640, 480);
        return surface;
    }

    /**
     * A surface that reports itself displayable without a window peer, modelling the live host
     * preview the readback path is allowed to read.
     */
    private static JPanel liveSurface() {
        final JPanel surface = new JPanel(new BorderLayout()) {
            @Override
            public boolean isDisplayable() {
                return true;
            }
        };
        surface.setSize(640, 480);
        return surface;
    }

    private static Component north(final JPanel panel) {
        return ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.NORTH);
    }

    private static Component center(final JPanel panel) {
        return ((BorderLayout) panel.getLayout()).getLayoutComponent(BorderLayout.CENTER);
    }

    private static JButton button(final Component component, final String label) {
        if (component instanceof JButton candidate && label.equals(candidate.getText())) return candidate;
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                final JButton found = buttonOrNull(child, label);
                if (found != null) return found;
            }
        }
        throw new AssertionError("missing button " + label);
    }

    private static JButton buttonOrNull(final Component component, final String label) {
        if (component instanceof JButton candidate && label.equals(candidate.getText())) return candidate;
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                final JButton found = buttonOrNull(child, label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void flushEdt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {});
    }

    /** A host preview panel: the registry only ever asks it for its component and its GL view. */
    static final class Panel {

        private final JPanel surface;
        private volatile String readbackThread;

        private Panel(final JPanel surface) {
            this.surface = surface;
        }

        public JPanel getJComponent() {
            return surface;
        }

        public View getViewPanel() {
            readbackThread = Thread.currentThread().getName();
            return new View();
        }
    }

    /** The JOGL view wrapper; the registry stops at class resolution, which has no GL profile. */
    static final class View {
        public Object getRaw_glJPanel() {
            return new Object();
        }
    }
}
