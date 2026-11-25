package dev.turboism.plugin.warprowcolumndeform;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformCursor;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformOption;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformPointerEvent;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformRenderScene;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformSession;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformTool;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformToolRegistry;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Registers the Row/Column Warp Temporary Deform Tool across Cubism's Modeling menu and Tool
 * Details deform-method dropdown.
 */
public final class WarpRowColumnDeformPlugin implements TurboismPlugin {

    static final String TOOL_ID = "warp-row-column-deform";
    static final String OPTION_AXIS_MODE = "axis-mode";
    static final String OPTION_GRID_KIND = "grid-kind";
    static final int TOOL_ORDER = 100;

    private static final String KEY_TOOL_LABEL = "tool.label";
    private static final String KEY_OPTION_AXIS = "option.axisMode.label";
    private static final String KEY_OPTION_AXIS_AUTO = "option.axisMode.auto";
    private static final String KEY_OPTION_AXIS_ROW = "option.axisMode.row";
    private static final String KEY_OPTION_AXIS_COLUMN = "option.axisMode.column";
    private static final String KEY_OPTION_GRID = "option.gridKind.label";
    private static final String KEY_OPTION_GRID_AUTO = "option.gridKind.auto";
    private static final String KEY_OPTION_GRID_BEZIER = "option.gridKind.bezier";
    private static final String KEY_OPTION_GRID_CONVERSION = "option.gridKind.conversion";

    private volatile RowColumnDeformPlanner.AxisMode axisMode = RowColumnDeformPlanner.AxisMode.AUTO;
    private volatile RowColumnDeformPlanner.GridKind gridKind = RowColumnDeformPlanner.GridKind.AUTO;
    private PluginContext context;
    private Registration activeRegistration;

    @Override
    public void init(final PluginContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public void enable() {
        if (context == null || activeRegistration != null) {
            return;
        }
        final TemporaryDeformToolRegistry registry = context.services()
                .find(TemporaryDeformToolRegistry.class)
                .orElse(TemporaryDeformToolRegistry.unavailable());
        if (!registry.isAvailable()) {
            return;
        }
        final PluginLocalization i18n = context.localization();
        activeRegistration = registry.register(createTool(i18n));
        context.disposableScope().register(activeRegistration);
    }

    @Override
    public void disable() {
        if (activeRegistration != null) {
            activeRegistration.close();
            activeRegistration = null;
        }
    }

    TemporaryDeformTool createTool(final PluginLocalization i18n) {
        return new TemporaryDeformTool() {
            @Override
            public String id() {
                return TOOL_ID;
            }

            @Override
            public String label() {
                return i18n.text(KEY_TOOL_LABEL);
            }

            @Override
            public int order() {
                return TOOL_ORDER;
            }

            @Override
            public List<TemporaryDeformOption> options() {
                return List.of(
                        new TemporaryDeformOption.Choice(
                                OPTION_AXIS_MODE,
                                i18n.text(KEY_OPTION_AXIS),
                                List.of(
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.AxisMode.AUTO.optionValue(),
                                                i18n.text(KEY_OPTION_AXIS_AUTO)),
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.AxisMode.ROW.optionValue(),
                                                i18n.text(KEY_OPTION_AXIS_ROW)),
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.AxisMode.COLUMN.optionValue(),
                                                i18n.text(KEY_OPTION_AXIS_COLUMN))),
                                axisMode.optionValue()),
                        new TemporaryDeformOption.Choice(
                                OPTION_GRID_KIND,
                                i18n.text(KEY_OPTION_GRID),
                                List.of(
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.GridKind.AUTO.optionValue(),
                                                i18n.text(KEY_OPTION_GRID_AUTO)),
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.GridKind.BEZIER.optionValue(),
                                                i18n.text(KEY_OPTION_GRID_BEZIER)),
                                        new TemporaryDeformOption.ChoiceItem(
                                                RowColumnDeformPlanner.GridKind.CONVERSION.optionValue(),
                                                i18n.text(KEY_OPTION_GRID_CONVERSION))),
                                gridKind.optionValue()));
            }

            @Override
            public void onOptionChanged(final String optionId, final String value) {
                applyOptionChange(optionId, value);
            }

            @Override
            public TemporaryDeformSession createSession(final TemporaryDeformContext deformContext) {
                return new RowColumnDeformSession(deformContext);
            }
        };
    }

    private void applyOptionChange(final String optionId, final String value) {
        if (OPTION_AXIS_MODE.equals(optionId)) {
            axisMode = RowColumnDeformPlanner.AxisMode.fromOptionValue(value);
        } else if (OPTION_GRID_KIND.equals(optionId)) {
            gridKind = RowColumnDeformPlanner.GridKind.fromOptionValue(value);
        }
    }

    private final class RowColumnDeformSession implements TemporaryDeformSession {

        private final TemporaryDeformContext deformContext;
        private RowColumnDeformPlanner.SelectionTarget hovered;
        private RowColumnDeformPlanner.SelectionTarget active;
        private List<RowColumnDeformPlanner.GridGeometry> strokeGrids = List.of();
        private Point2 pressPos;
        private float lastCameraScale = 1.0F;

        private RowColumnDeformSession(final TemporaryDeformContext deformContext) {
            this.deformContext = Objects.requireNonNull(deformContext, "deformContext");
        }

        @Override
        public TemporaryDeformCursor onPointerHover(final TemporaryDeformPointerEvent event) {
            final List<RowColumnDeformPlanner.GridGeometry> grids = snapshotGrids();
            final float handleOffset = RowColumnDeformPlanner.canvasHandleOffset(lastCameraScale);
            hovered = RowColumnDeformPlanner.hitTest(
                            grids, event.canvasX(), event.canvasY(), event.hitRadius(), axisMode, handleOffset)
                    .orElse(null);
            return hovered != null ? TemporaryDeformCursor.HAND : TemporaryDeformCursor.DEFAULT;
        }

        @Override
        public void onPointerPress(final TemporaryDeformPointerEvent event) {
            final List<RowColumnDeformPlanner.GridGeometry> grids = snapshotGrids();
            final float handleOffset = RowColumnDeformPlanner.canvasHandleOffset(lastCameraScale);
            active = RowColumnDeformPlanner.hitTest(
                            grids, event.canvasX(), event.canvasY(), event.hitRadius(), axisMode, handleOffset)
                    .orElse(null);
            if (active != null) {
                strokeGrids = grids;
                pressPos = new Point2(event.canvasX(), event.canvasY());
            } else {
                strokeGrids = List.of();
                pressPos = null;
            }
        }

        @Override
        public void onPointerDrag(final TemporaryDeformPointerEvent event) {
            if (active == null || pressPos == null || strokeGrids.isEmpty()) {
                return;
            }
            final int targetIdx = active.targetIndex();
            final List<TemporaryDeformContext.WarpDeformerTarget> targets = deformContext.selectedWarpDeformers();
            if (targetIdx < 0 || targetIdx >= targets.size() || targetIdx >= strokeGrids.size()) {
                return;
            }
            final RowColumnDeformPlanner.GridGeometry grid = strokeGrids.get(targetIdx);
            final Map<Integer, Point2> deltas = RowColumnDeformPlanner.computeDeltas(
                    grid, active, pressPos, new Point2(event.canvasX(), event.canvasY()), event.shiftDown());
            if (deltas.isEmpty()) {
                return;
            }
            final TemporaryDeformContext.WarpDeformerTarget target = targets.get(targetIdx);
            if (grid.bezier()) {
                target.applyBezierDeltas(deltas);
            } else {
                target.applyConversionDeltas(deltas);
            }
        }

        @Override
        public void onPointerRelease(final TemporaryDeformPointerEvent event) {
            active = null;
            strokeGrids = List.of();
            pressPos = null;
        }

        @Override
        public void onOptionChanged(final String optionId, final String value) {
            applyOptionChange(optionId, value);
            hovered = null;
            active = null;
            deformContext.refreshToolDetails();
            deformContext.requestRepaint();
        }

        @Override
        public TemporaryDeformRenderScene render(final float cameraScale, final boolean valid) {
            lastCameraScale = cameraScale;
            return RowColumnDeformPlanner.buildRenderScene(
                    snapshotGrids(), hovered, active, axisMode, cameraScale, valid);
        }

        @Override
        public void deactivate() {
            hovered = null;
            active = null;
            strokeGrids = List.of();
            pressPos = null;
        }

        private List<RowColumnDeformPlanner.GridGeometry> snapshotGrids() {
            final List<TemporaryDeformContext.WarpDeformerTarget> targets = deformContext.selectedWarpDeformers();
            final List<RowColumnDeformPlanner.GridGeometry> result = new ArrayList<>(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                result.add(RowColumnDeformPlanner.resolveGrid(i, targets.get(i), gridKind));
            }
            return List.copyOf(result);
        }
    }
}
