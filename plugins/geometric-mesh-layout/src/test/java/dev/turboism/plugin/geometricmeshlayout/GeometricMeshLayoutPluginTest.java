package dev.turboism.plugin.geometricmeshlayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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

        final RecordingBrush handle = new RecordingBrush();
        tool.activate(new MeshToolContext() {
            @Override
            public MeshEditor editor() {
                throw new AssertionError("plugin must not access MeshEditor directly");
            }

            @Override
            public MeshBrush selectionBrush() {
                return handle;
            }
        });
        tool.deactivate();
        assertEquals(1, handle.closes.get());

        scope.close();
        assertEquals(1, registry.toolCloses.get());
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

    private static PluginContext context(final RecordingRegistry registry, final DisposableScope scope) {
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
                    case "localization" -> dev.turboism.sdk.i18n.PluginLocalization.unavailable();
                    case "toString" -> "GeometricMeshLayoutPluginTestContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
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

    private static final class RecordingBrush implements MeshBrush {
        final AtomicInteger closes = new AtomicInteger();
        int radius = 32;

        @Override
        public int radiusPixels() {
            return radius;
        }

        @Override
        public void setRadiusPixels(final int value) {
            radius = value;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
        }
    }
}
