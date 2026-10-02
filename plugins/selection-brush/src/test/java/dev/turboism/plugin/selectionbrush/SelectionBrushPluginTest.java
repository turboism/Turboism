package dev.turboism.plugin.selectionbrush;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.mesh.MeshBrush;
import dev.turboism.sdk.cubism.mesh.MeshEditor;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SelectionBrushPluginTest {
    @Test
    void registersOrdinaryToolAfterNativeBrushAndSharesRadiusAndGesturesAcrossModes() throws Exception {
        final RecordingRegistry mesh = new RecordingRegistry();
        final List<dev.turboism.sdk.cubism.modeling.ModelingTool> tools = new ArrayList<>();
        final List<dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Placement> placements = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        final var ordinary = new dev.turboism.sdk.cubism.modeling.ModelingToolRegistry() {
            public Registration register(
                    dev.turboism.sdk.cubism.modeling.ModelingTool tool,
                    dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Placement placement) {
                tools.add(tool);
                placements.add(placement);
                return RecordingRegistry.once(closes);
            }

            public void close() {}
        };
        final DisposableScope scope = new DisposableScope();
        final SelectionBrushPlugin plugin = new SelectionBrushPlugin();
        plugin.init(context(mesh, scope, ordinary));
        plugin.enable();
        assertEquals(1, tools.size());
        assertEquals(
                dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Placement.after(
                        dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Anchor.HOST_BRUSH_SELECTION_TOOL),
                placements.get(0));
        final RecordingBrush brush = new RecordingBrush();
        mesh.sliders.get(0).setValue(64);
        tools.get(0).activate(() -> brush);
        assertEquals(List.of(64), brush.values);
        mesh.sliders.get(0).setValue(8);
        assertEquals(List.of(64, 8), brush.values);
        assertEquals(SelectionMode.REPLACE, tools.get(0).strokeSelectionMode(false, false, false));
        assertEquals(SelectionMode.ADD, tools.get(0).strokeSelectionMode(true, false, false));
        assertEquals(SelectionMode.REMOVE, tools.get(0).strokeSelectionMode(true, true, false));
        assertEquals(mesh.tools.get(0).selectedIconResourcePath(), tools.get(0).selectedIconResourcePath());
        tools.get(0).deactivate();
        assertEquals(1, brush.closes.get());
        final RecordingBrush next = new RecordingBrush();
        mesh.tools.get(0).activate(toolContext(next));
        assertEquals(List.of(8), next.values);
        plugin.disable();
        assertEquals(1, next.closes.get());
        scope.close();
        assertEquals(1, closes.get());
    }

    @Test
    void contributesExactlyOneToolAndSliderWithFrozenMetadataAndScopeCleanup() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        DisposableScope scope = new DisposableScope();
        SelectionBrushPlugin plugin = new SelectionBrushPlugin();
        plugin.init(context(registry, scope));
        plugin.enable();
        plugin.enable();

        assertEquals(1, registry.tools.size());
        assertEquals(1, registry.sliders.size());
        MeshTool tool = registry.tools.get(0);
        assertEquals("selection-brush", tool.id());
        assertEquals("Selection Brush", tool.label());
        assertEquals(100, tool.order());
        assertEquals("icons/selection-brush.png", tool.iconResourcePath());
        assertEquals("icons/selection-brush-active.png", tool.activeIconResourcePath());
        assertEquals("icons/selection-brush-rollover.png", tool.rollOverIconResourcePath());
        assertEquals("icons/selection-brush-selected.png", tool.selectedIconResourcePath());
        assertEquals("icons/selection-brush-disabled.png", tool.disabledIconResourcePath());
        assertEquals("icons/selection-brush-disabled-selected.png", tool.disabledSelectedIconResourcePath());

        MeshToolbarSlider slider = registry.sliders.get(0);
        assertEquals("selection-brush.radius", slider.id());
        assertEquals("Brush Radius", slider.label());
        assertEquals(8, slider.minimum());
        assertEquals(128, slider.maximum());
        assertEquals(32, slider.value());
        assertEquals(110, slider.order());

        scope.close();
        assertEquals(1, registry.toolCloses.get());
        assertEquals(1, registry.sliderCloses.get());
    }

    @Test
    void radiusPropagatesToActiveAndNextBrushWhileRuntimeOwnsStrokeSnapshot() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        DisposableScope scope = new DisposableScope();
        SelectionBrushPlugin plugin = new SelectionBrushPlugin();
        plugin.init(context(registry, scope));
        plugin.enable();
        MeshTool tool = registry.tools.get(0);
        MeshToolbarSlider slider = registry.sliders.get(0);
        RecordingBrush first = new RecordingBrush();
        tool.activate(toolContext(first));
        assertEquals(List.of(32), first.values);

        slider.setValue(8);
        slider.setValue(128);
        assertEquals(List.of(32, 8, 128), first.values);
        assertEquals(128, slider.value());
        assertThrows(IllegalArgumentException.class, () -> slider.setValue(7));
        assertThrows(IllegalArgumentException.class, () -> slider.setValue(129));

        tool.deactivate();
        assertEquals(1, first.closes.get());
        RecordingBrush second = new RecordingBrush();
        tool.activate(toolContext(second));
        assertEquals(List.of(128), second.values);
        tool.deactivate();
        tool.deactivate();
        assertEquals(1, second.closes.get());
        scope.close();
    }

    @Test
    void declaresNativeConsistentStrokeGesturesWithControlPrecedence() throws Exception {
        RecordingRegistry registry = new RecordingRegistry();
        DisposableScope scope = new DisposableScope();
        SelectionBrushPlugin plugin = new SelectionBrushPlugin();
        plugin.init(context(registry, scope));
        plugin.enable();
        MeshTool tool = registry.tools.get(0);

        assertEquals(SelectionMode.REPLACE, tool.strokeSelectionMode(false, false, false));
        assertEquals(SelectionMode.ADD, tool.strokeSelectionMode(true, false, false));
        assertEquals(SelectionMode.REMOVE, tool.strokeSelectionMode(false, true, false));
        assertEquals(SelectionMode.REMOVE, tool.strokeSelectionMode(true, true, false));
        assertEquals(SelectionMode.REMOVE, tool.strokeSelectionMode(true, true, true));

        scope.close();
    }

    @Test
    void packagesExactManifestLocalizationAndSixPngStates() throws Exception {
        ClassLoader loader = SelectionBrushPlugin.class.getClassLoader();
        String manifest;
        try (var input = loader.getResourceAsStream("META-INF/turboism/plugin.json")) {
            assertNotNull(input);
            manifest = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        assertTrue(manifest.contains("\"cubism.mesh.custom-tools\""));
        assertTrue(manifest.contains("\"turboism.ui.toolbar.mesh.contribute\""));
        assertTrue(manifest.contains("\"turboism.cubism.model.read\""));
        assertTrue(manifest.contains("\"turboism.cubism.model.write\""));
        for (String resource : List.of(
                "icons/selection-brush.png",
                "icons/selection-brush-active.png",
                "icons/selection-brush-rollover.png",
                "icons/selection-brush-selected.png",
                "icons/selection-brush-disabled.png",
                "icons/selection-brush-disabled-selected.png")) {
            try (var input = loader.getResourceAsStream(resource)) {
                assertNotNull(input, resource);
                byte[] signature = input.readNBytes(8);
                assertEquals(
                        List.of(-119, 80, 78, 71, 13, 10, 26, 10),
                        java.util.stream.IntStream.range(0, signature.length)
                                .map(index -> signature[index])
                                .boxed()
                                .toList(),
                        resource);
            }
        }
        for (String locale : List.of("", "_en", "_ja", "_ko", "_zh_Hans", "_zh_Hant")) {
            assertNotNull(loader.getResource("META-INF/turboism/i18n/messages" + locale + ".properties"));
        }
    }

    private static PluginContext context(RecordingRegistry registry, DisposableScope scope) {
        return context(registry, scope, null);
    }

    private static PluginContext context(
            RecordingRegistry registry,
            DisposableScope scope,
            dev.turboism.sdk.cubism.modeling.ModelingToolRegistry ordinary) {
        return (PluginContext) Proxy.newProxyInstance(
                SelectionBrushPluginTest.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "services" ->
                        new dev.turboism.sdk.plugin.PluginServiceDirectory() {
                            @Override
                            public java.util.Set<dev.turboism.sdk.plugin.PluginService> installed() {
                                return java.util.Set.of(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS);
                            }

                            @Override
                            public <T> T get(Class<T> type) {
                                if (type == dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class)
                                    return type.cast(ordinary);
                                return type == dev.turboism.sdk.cubism.mesh.MeshToolRegistry.class
                                        ? type.cast(registry)
                                        : null;
                            }
                        };
                    case "disposableScope" -> scope;
                    case "toString" -> "SelectionBrushPluginTestContext";
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static MeshToolContext toolContext(MeshBrush brush) {
        return new MeshToolContext() {
            @Override
            public MeshEditor editor() {
                throw new AssertionError("plugin must not access MeshEditor");
            }

            @Override
            public MeshBrush selectionBrush() {
                return brush;
            }
        };
    }

    private static final class RecordingRegistry implements MeshToolRegistry {
        final List<MeshTool> tools = new ArrayList<>();
        final List<MeshToolbarSlider> sliders = new ArrayList<>();
        final AtomicInteger toolCloses = new AtomicInteger();
        final AtomicInteger sliderCloses = new AtomicInteger();

        @Override
        public Registration register(MeshTool tool) {
            tools.add(tool);
            return once(toolCloses);
        }

        @Override
        public Registration contributeSlider(MeshToolbarSlider slider) {
            sliders.add(slider);
            return once(sliderCloses);
        }

        @Override
        public void close() {}

        private static Registration once(AtomicInteger count) {
            AtomicInteger closed = new AtomicInteger();
            return () -> {
                if (closed.compareAndSet(0, 1)) count.incrementAndGet();
            };
        }
    }

    private static final class RecordingBrush implements MeshBrush, dev.turboism.sdk.cubism.modeling.ModelingBrush {
        final List<Integer> values = new ArrayList<>();
        final AtomicInteger closes = new AtomicInteger();
        int radius = 32;
        boolean closed;

        @Override
        public int radiusPixels() {
            return radius;
        }

        @Override
        public void setRadiusPixels(int value) {
            radius = value;
            values.add(value);
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                closes.incrementAndGet();
            }
        }
    }
}
