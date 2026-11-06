package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshCanvasSession;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class GeometricMeshLayoutPluginTest {
    @Test
    void registersMeshEditOnlyToolAndCleansUpOnDeactivateAndScopeClose() throws Exception {
        final RecordingRegistry registry = new RecordingRegistry();
        final DisposableScope scope = new DisposableScope();
        final GeometricMeshLayoutPlugin plugin = new GeometricMeshLayoutPlugin();
        plugin.init(context(registry, scope));
        plugin.enable();
        plugin.enable();

        assertEquals(1, registry.tools.size());
        assertEquals(0, registry.sliders.size());
        final MeshTool tool = registry.tools.get(0);
        assertEquals("geometric-mesh-layout", tool.id());
        assertEquals("Geometric Mesh Layout", tool.label());
        assertEquals(120, tool.order());
        assertEquals("icons/geometric-mesh-layout.png", tool.iconResourcePath());
        assertEquals("icons/geometric-mesh-layout-active.png", tool.activeIconResourcePath());
        assertEquals("icons/geometric-mesh-layout-rollover.png", tool.rollOverIconResourcePath());
        assertEquals("icons/geometric-mesh-layout-selected.png", tool.selectedIconResourcePath());
        assertEquals("icons/geometric-mesh-layout-disabled.png", tool.disabledIconResourcePath());
        assertEquals("icons/geometric-mesh-layout-disabled-selected.png", tool.disabledSelectedIconResourcePath());

        final RecordingSession session = new RecordingSession();
        SwingUtilities.invokeAndWait(() -> tool.activate(toolContext(session)));
        assertEquals(1, session.previews.size(), "activation paints the plugin-computed preview");
        assertEquals(16, session.previews.get(0).size());

        plugin.disable();
        assertEquals(1, session.closes.get());

        scope.close();
        assertEquals(1, registry.toolCloses.get());
    }

    @Test
    void shutdownReleasesTheToolAndEveryActivationHandle() throws Exception {
        final RecordingRegistry registry = new RecordingRegistry();
        final DisposableScope scope = new DisposableScope();
        final GeometricMeshLayoutPlugin plugin = new GeometricMeshLayoutPlugin();
        plugin.init(context(registry, scope));
        plugin.enable();

        final RecordingSession session = new RecordingSession();
        SwingUtilities.invokeAndWait(() -> registry.tools.get(0).activate(toolContext(session)));

        plugin.shutdown();

        assertEquals(1, session.closes.get());
        plugin.shutdown();
        assertEquals(1, session.closes.get(), "shutdown is idempotent");
        scope.close();
    }

    @Test
    void packagesManifestLocalizationAndIconsForMeshEditOnly() throws Exception {
        final ClassLoader loader = GeometricMeshLayoutPlugin.class.getClassLoader();
        final String manifest;
        try (var input = loader.getResourceAsStream("META-INF/turboism/plugin.json")) {
            assertNotNull(input);
            manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(manifest.contains("\"cubism.mesh.custom-tools\""));
        assertTrue(manifest.contains("\"cubism.mesh.point-and-edge-layout\""));
        assertFalse(manifest.contains("\"cubism.modeling.custom-tools\""));
        assertTrue(manifest.contains("\"turboism.ui.toolbar.mesh.contribute\""));
        assertTrue(manifest.contains("\"turboism.cubism.model.read\""));
        assertTrue(manifest.contains("\"turboism.cubism.model.write\""));
        for (String resource : List.of(
                "icons/geometric-mesh-layout.png",
                "icons/geometric-mesh-layout-active.png",
                "icons/geometric-mesh-layout-rollover.png",
                "icons/geometric-mesh-layout-selected.png",
                "icons/geometric-mesh-layout-disabled.png",
                "icons/geometric-mesh-layout-disabled-selected.png")) {
            try (var input = loader.getResourceAsStream(resource)) {
                assertNotNull(input, resource);
            }
        }
    }

    private static MeshToolContext toolContext(final RecordingSession session) {
        return new MeshToolContext() {
            @Override
            public MeshEditor editor() {
                throw new AssertionError("plugin must not access MeshEditor directly");
            }

            @Override
            public MeshBrush selectionBrush() {
                throw new AssertionError("the geometric tool must not request the selection brush");
            }

            @Override
            public MeshCanvasSession canvasSession() {
                return session;
            }
        };
    }

    private static PluginContext context(final RecordingRegistry registry, final DisposableScope scope) {
        final PluginLocalization localization = PluginCatalogs.english();
        final UiScheduler uiScheduler = new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                work.run();
                return () -> {};
            }

            @Override
            public boolean runUiTeardown(final Runnable cleanup, final Duration timeout) {
                cleanup.run();
                return true;
            }
        };
        return (PluginContext) Proxy.newProxyInstance(
                GeometricMeshLayoutPluginTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "services" ->
                        new dev.turboism.sdk.plugin.PluginServiceDirectory() {
                            @Override
                            public java.util.Set<dev.turboism.sdk.plugin.PluginService> installed() {
                                return java.util.Set.of(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS);
                            }

                            @Override
                            public <T> java.util.Optional<T> find(final Class<T> type) {
                                return type == MeshToolRegistry.class
                                        ? java.util.Optional.ofNullable(type.cast(registry))
                                        : java.util.Optional.empty();
                            }
                        };
                    case "disposableScope" -> scope;
                    case "localization" -> localization;
                    case "logger" -> NoopLogger.INSTANCE;
                    case "uiScheduler" -> uiScheduler;
                    case "toString" -> "GeometricMeshLayoutPluginTestContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private enum NoopLogger implements PluginLogger {
        INSTANCE;

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {}

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }

    private static final class RecordingRegistry implements MeshToolRegistry {
        final List<MeshTool> tools = new ArrayList<>();
        final List<MeshToolbarSlider> sliders = new ArrayList<>();
        final AtomicInteger toolCloses = new AtomicInteger();

        @Override
        public Registration register(final MeshTool tool) {
            tools.add(tool);
            final AtomicInteger closed = new AtomicInteger();
            return () -> {
                if (closed.compareAndSet(0, 1)) toolCloses.incrementAndGet();
            };
        }

        @Override
        public Registration contributeSlider(final MeshToolbarSlider slider) {
            sliders.add(slider);
            return () -> {};
        }

        @Override
        public void close() {}
    }

    private static final class RecordingSession implements MeshCanvasSession {
        final List<List<Point2>> previews = new ArrayList<>();
        final AtomicInteger previewClears = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        private Consumer<Point2> anchorListener;

        @Override
        public Point2 anchor() {
            return new Point2(0.0f, 0.0f);
        }

        @Override
        public Registration observeAnchorMoved(final Consumer<Point2> listener) {
            anchorListener = listener;
            return () -> anchorListener = null;
        }

        @Override
        public void showPreview(final List<Point2> vertices, final int[] edgePairs) {
            previews.add(List.copyOf(vertices));
        }

        @Override
        public void clearPreview() {
            previewClears.incrementAndGet();
        }

        @Override
        public List<Integer> commit(final List<Point2> vertices, final int[] edgePairs) {
            return List.of();
        }

        @Override
        public void deactivate() {}

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
