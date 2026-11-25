package dev.turboism.plugin.warprowcolumndeform;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformPointerEvent;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformSession;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformToolRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.plugin.FakePluginContext;
import dev.turboism.test.plugin.FakePluginDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WarpRowColumnDeformPluginTest {

    @Test
    void registersToolAndAppliesFullRowMoveAndModeSwitches() throws Exception {
        final Map<String, TemporaryDeformTool> registered = new LinkedHashMap<>();
        final TemporaryDeformToolRegistry fakeRegistry = new TemporaryDeformToolRegistry() {
            @Override
            public Registration register(final TemporaryDeformTool tool) {
                registered.put(tool.id(), tool);
                return () -> registered.remove(tool.id());
            }

            @Override
            public boolean activate(final String toolId) {
                return registered.containsKey(toolId);
            }

            @Override
            public void deactivate() {}

            @Override
            public Optional<String> activeToolId() {
                return Optional.empty();
            }

            @Override
            public void close() {
                registered.clear();
            }
        };
        final PluginLocalization i18n = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };
        final FakePluginContext context = FakePluginContext.builder()
                .descriptor(FakePluginDescriptor.testDescriptor("dev.turboism.plugin.warp-row-column-deform"))
                .localization(i18n)
                .service(TemporaryDeformToolRegistry.class, fakeRegistry)
                .build();

        final WarpRowColumnDeformPlugin plugin = new WarpRowColumnDeformPlugin();
        plugin.init(context);
        plugin.enable();

        final TemporaryDeformTool tool = registered.get(WarpRowColumnDeformPlugin.TOOL_ID);
        assertEquals(WarpRowColumnDeformPlugin.TOOL_ID, tool.id());
        assertTrue(tool.overlayButton().isEmpty());
        assertEquals(2, tool.options().size());

        final Map<Integer, Point2> recordedBezier = new LinkedHashMap<>();
        final Map<Integer, Point2> recordedConversion = new LinkedHashMap<>();
        final TemporaryDeformContext.GridLayer grid2x2 = new TemporaryDeformContext.GridLayer(
                2, 2, List.of(new Point2(0, 0), new Point2(100, 0), new Point2(0, 100), new Point2(100, 100)));
        final TemporaryDeformContext deformContext = new TemporaryDeformContext() {
            @Override
            public List<WarpDeformerTarget> selectedWarpDeformers() {
                return List.of(new WarpDeformerTarget() {
                    @Override
                    public DeformerId id() {
                        return new DeformerId("D_WARP");
                    }

                    @Override
                    public int editLevel() {
                        return 2;
                    }

                    @Override
                    public GridLayer conversionGrid() {
                        return grid2x2;
                    }

                    @Override
                    public Optional<GridLayer> bezierGrid() {
                        return Optional.of(grid2x2);
                    }

                    @Override
                    public void applyConversionDeltas(final Map<Integer, Point2> deltas) {
                        recordedConversion.clear();
                        recordedConversion.putAll(deltas);
                    }

                    @Override
                    public void applyBezierDeltas(final Map<Integer, Point2> deltas) {
                        recordedBezier.clear();
                        recordedBezier.putAll(deltas);
                    }
                });
            }

            @Override
            public void refreshToolDetails() {}

            @Override
            public void requestRepaint() {}

            @Override
            public void unbind() {}
        };

        final TemporaryDeformSession session = tool.createSession(deformContext);
        session.onPointerPress(new TemporaryDeformPointerEvent(-24.0F, 0.0F, 12.0F, false, false, false));
        session.onPointerDrag(new TemporaryDeformPointerEvent(-4.0F, 15.0F, 12.0F, false, false, false));
        assertEquals(new Point2(20.0F, 15.0F), recordedBezier.get(0));
        assertEquals(new Point2(20.0F, 15.0F), recordedBezier.get(1));

        session.onPointerRelease(new TemporaryDeformPointerEvent(-4.0F, 15.0F, 12.0F, false, false, false));
        session.onOptionChanged(WarpRowColumnDeformPlugin.OPTION_GRID_KIND, "conversion");
        session.onOptionChanged(WarpRowColumnDeformPlugin.OPTION_AXIS_MODE, "column");
        session.onPointerPress(new TemporaryDeformPointerEvent(100.0F, 50.0F, 12.0F, false, false, false));
        session.onPointerDrag(new TemporaryDeformPointerEvent(112.0F, 50.0F, 12.0F, false, false, false));
        assertEquals(new Point2(12.0F, 0.0F), recordedConversion.get(1));
        assertEquals(new Point2(12.0F, 0.0F), recordedConversion.get(3));

        final DisposableScope scope = context.disposableScope();
        scope.close();
        assertTrue(registered.isEmpty());
    }
}
