package dev.turboism.adapter.cubism.optimization.warpdeform;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge backing edit-mode warp deformation, overlay dirty-gating, and Bezier grid fast paths.
 *
 * <p>All hot-path callbacks are published into {@link System#getProperties()} as exact-typed JDK
 * {@link MethodHandle} slots so woven host bytecode invokes them without boxing or host-type leakage.</p>
 */
public final class WarpDeformEditBridge implements AutoCloseable {

    /** Explicit system-property switch overriding the derived startup preference. */
    public static final String ENABLE_PROPERTY = WarpDeformEditTarget.ENABLE_PROPERTY;

    /** Shared {@link AtomicBoolean} slot gating inline bytecode loops when the weave is active. */
    public static final String ACTIVE_PROPERTY = "turboism.warp-deform-edit.active";

    /** {@code MethodHandle} slot: {@code (Object form, Object pt) -> int} ({@code -1} fallback, {@code 0/1} result). */
    public static final String FORM_IS_INSIDE_PROPERTY = "turboism.warp-deform-edit.form-is-inside";

    /** {@code MethodHandle} slot: {@code (Object deformer, float[] src, float[] dst, int numPoint) -> boolean}. */
    public static final String TRANSFORM_CANVAS_TO_LOCAL_PROPERTY = "turboism.warp-deform-edit.canvas-to-local";

    /** {@code MethodHandle} slot: {@code (Object simpleGrid, float x, float y, Object target, float[] dst) -> Object}. */
    public static final String GET01_EX_QUAD_PROPERTY = "turboism.warp-deform-edit.get01-ex-quad";

    /** {@code MethodHandle} slot: {@code (Object transform, Object src, Object dst) -> Object}. */
    public static final String INVERSE_QUAD_PROPERTY = "turboism.warp-deform-edit.inverse-quad";

    /** {@code MethodHandle} slot: {@code (Object transform, Object pt, boolean isDst) -> int}. */
    public static final String GRID_TRANSFORM_IS_INSIDE_PROPERTY = "turboism.warp-deform-edit.grid-is-inside";

    /** {@code MethodHandle} slot: {@code (Object bezierGrid, int col, int row) -> float[]}. */
    public static final String CREATE_POINTS_ON_BEZIER_PROPERTY = "turboism.warp-deform-edit.bezier-points";

    /** {@code MethodHandle} slot: {@code (Object draw, float[] pos, int col, int row, float opacity, float size, Object color) -> boolean}. */
    public static final String DRAW_CORNER_TRIANGLE_PROPERTY = "turboism.warp-deform-edit.draw-corner-triangle";

    /** {@code MethodHandle} slot: {@code (Object draw, float[] pos, int col, int row, Object color, boolean hide) -> boolean}. */
    public static final String DRAW_MAJOR_LINES_PROPERTY = "turboism.warp-deform-edit.draw-major-lines";

    /** {@code MethodHandle} slot: {@code (Object draw, float[] pos, int col, int row, Object color, boolean show) -> boolean}. */
    public static final String DRAW_DETAIL_LINES_PROPERTY = "turboism.warp-deform-edit.draw-detail-lines";

    /** {@code MethodHandle} slot: {@code (Object bufferWrapper, Object pointRenderer, boolean isPositions) -> boolean}. */
    public static final String DRAW_SELECTED_POINT_UPLOAD_PROPERTY = "turboism.warp-deform-edit.draw-selected-upload";

    /** {@code MethodHandle} slot: {@code (Object draw, Object selection, Object baseColor, float[] pos, int col, int row) -> boolean}. */
    public static final String DRAW_SOFT_SELECTION_PROPERTY = "turboism.warp-deform-edit.draw-soft-selection";

    /** {@code MethodHandle} slot: {@code (updater, model, visibleMeshes, updCtx, viewCtx, ctx5) -> boolean}. */
    public static final String EDIT_SUBTREE_UPDATE_MODEL_PROPERTY = "turboism.warp-deform-edit.subtree-update-model";

    /** {@code MethodHandle} slot: {@code (updater, model, visibleMeshes, updCtx, viewCtx, ctx5) -> void}. */
    public static final String EDIT_SUBTREE_END_MODEL_PROPERTY = "turboism.warp-deform-edit.subtree-end-model";

    /** {@code MethodHandle} slot: {@code (artMesh, model) -> boolean}. */
    public static final String EDIT_SUBTREE_SKIP_MESH_APPEARANCE_PROPERTY =
            "turboism.warp-deform-edit.subtree-skip-mesh-appearance";

    /** {@code MethodHandle} slot: {@code (deformer, sceneGraph, hideSelected, visible, rotMode, actionPack) -> boolean}. */
    public static final String EDIT_OVERLAY_ELIDE_PROPERTY = "turboism.warp-deform-edit.overlay-elide";

    /** {@code MethodHandle} slot: {@code (deformer, hideSelected, visible, rotMode, actionPack) -> void}. */
    public static final String EDIT_OVERLAY_RECORD_PROPERTY = "turboism.warp-deform-edit.overlay-record";

    /** {@code MethodHandle} slot: {@code (jView) -> boolean}. */
    public static final String EDIT_LEGACY_OVERLAY_ELIDE_PROPERTY = "turboism.warp-deform-edit.legacy-overlay-elide";

    /** {@code MethodHandle} slot: {@code (jView) -> void}. */
    public static final String EDIT_LEGACY_OVERLAY_RECORD_PROPERTY = "turboism.warp-deform-edit.legacy-overlay-record";

    /** Diagnostic counter snapshot slot ({@code Supplier<Map<String, Long>>}). */
    public static final String STATS_PROPERTY = "turboism.warp-deform-edit.stats";

    private static final String IC_GRID_OWNER = "com/live2d/cubism/doc/model/deformer/warp/grid/ICGrid";
    private static final String POINT_REF_BASE_NAME = "baseColor";
    private static final String GET_POSITIONS_METHOD = "getPositions";
    private static final String SET_POSITIONS_METHOD = "setPositions";
    private static final String GET_INDICES_METHOD = "getIndices";
    private static final String SET_INDICES_METHOD = "setIndices";
    private static final String GET_COLORS_METHOD = "getColors";
    private static final String SET_COLORS_METHOD = "setColors";
    private static final String GET_STEP_METHOD = "getStep";
    private static final String SET_STEP_METHOD = "setStep";
    private static final String UPDATE_MESH_DATA_METHOD = "updateMeshData";
    private static final String BUFFER_UPLOAD_METHOD = "l";
    private static final String SET_COLOR_METHOD = "setColor";
    private static final String TO_PREMULTIPLIED_METHOD = "toPremultiplied";
    private static final String FLOAT_ARRAY_DESCRIPTOR = "[F";
    private static final String INT_ARRAY_DESCRIPTOR = "[I";
    private static final String VOID_DESCRIPTOR = "()V";
    private static final String BOOLEAN_DESCRIPTOR = "()Z";
    private static final String INT_DESCRIPTOR = "()I";
    private static final String FLOAT_DESCRIPTOR = "()F";
    private static final String SET_FLOAT_ARRAY_DESCRIPTOR = "([F)V";
    private static final String SET_INT_ARRAY_DESCRIPTOR = "([I)V";
    private static final String SET_INT_DESCRIPTOR = "(I)V";
    private static final String SET_BOOLEAN_DESCRIPTOR = "(Z)V";
    private static final String SET_COLOR_TWO_ARG_DESCRIPTOR =
            "(Ljava/lang/String;L" + WarpDeformEditTarget.CCOLOR_OWNER + ";)V";
    private static final String SET_COLOR_THREE_ARG_DESCRIPTOR =
            "(Ljava/lang/String;L" + WarpDeformEditTarget.CCOLOR_OWNER + ";Ljava/lang/Integer;)V";
    private static final String FIELD_WHITE_CORNER = "eTriangleOnCornerWhite";
    private static final String FIELD_BLACK_CORNER = "eTriangleOnCornerBlack";
    private static final String FIELD_LINE_NORMAL = "eWarpGridLineNormal";
    private static final String FIELD_LINE_SELECTED = "eWarpGridLineSelected";
    private static final String FIELD_WEIGHT_MAP = "eWarpDeformerWeightMap";
    private static final String FIELD_OFF_SET = "OFF_SET";
    private static final String FIELD_MIN_DIV = "MIN_DIV";
    private static final String FIELD_MAX_DIV = "MAX_DIV";
    private static final String FIELD_COMPANION = "Companion";
    private static final String FIELD_SORTING_LAYER_A = "a";

    private static final int CORNER_POSITION_FLOATS = 24;
    private static final int CORNER_INDEX_COUNT = 12;
    private static final float CORNER_SIZE_MULTIPLIER = 3.0f;
    private static final float WHITE_CORNER_Z = 0.2f;
    private static final float BLACK_CORNER_Z = 0.1f;
    private static final float WHITE_CORNER_SCALE = 0.6f;
    private static final float BLACK_CORNER_SCALE = 1.0f;
    private static final int STEP_2D = 2;
    private static final int COLOR_STRIDE = 4;
    private static final float WEIGHT_NEIGHBOR_DIVISOR = 4.0f;
    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final float WEIGHT_UNSET = Float.NaN;
    private static final String STAT_ACTIVE = "active";
    private static final String STAT_FORM_IS_INSIDE = "formIsInsideCalls";
    private static final String STAT_CANVAS_TO_LOCAL = "canvasToLocalCalls";
    private static final String STAT_GET01_EX_QUAD = "get01ExQuadCalls";
    private static final String STAT_INVERSE_QUAD = "inverseQuadCalls";
    private static final String STAT_GRID_IS_INSIDE = "gridIsInsideCalls";
    private static final String STAT_BEZIER_POINTS = "bezierPointsCalls";
    private static final String STAT_CORNER_TRIANGLE = "cornerTriangleCalls";
    private static final String STAT_MAJOR_LINES = "majorLinesCalls";
    private static final String STAT_DETAIL_LINES = "detailLinesCalls";
    private static final String STAT_SELECTED_UPLOAD_ELIDED = "selectedUploadElided";
    private static final String STAT_SOFT_SELECTION = "softSelectionCalls";
    private static final String STAT_OVERLAY_UPLOADS_ELIDED = "overlayUploadsElided";
    private static final String STAT_FAST_MODEL_UPDATES = "fastModelUpdates";
    private static final String STAT_FALLBACK_MODEL_UPDATES = "fallbackModelUpdates";
    private static final String STAT_DIRTY_DEFORMERS = "dirtyDeformersProcessed";
    private static final String STAT_SKIPPED_DEFORMERS = "skippedDeformers";
    private static final String STAT_DIRTY_MESHES = "dirtyMeshesProcessed";
    private static final String STAT_SKIPPED_MESHES = "skippedMeshes";
    private static final String STAT_APPEARANCE_SKIPPED = "appearanceSkipped";
    private static final String STAT_FULL_ARRAY_HASHES = "fullArrayHashes";
    private static final String STAT_PARALLEL_DEFORMER_LEVELS = "parallelDeformerLevels";
    private static final String STAT_PARALLEL_MESH_BATCHES = "parallelMeshBatches";
    private static final String STAT_OVERLAY_ELISIONS = "overlayElisions";
    private static final String STAT_OVERLAY_UPDATES = "overlayUpdates";
    private static final String STAT_FAILURES = "failures";

    private static final ThreadLocal<double[]> DOUBLE2_SCRATCH = ThreadLocal.withInitial(() -> new double[2]);
    private static final ThreadLocal<float[]> FLOAT2_SCRATCH = ThreadLocal.withInitial(() -> new float[2]);
    private static final ThreadLocal<float[]> CORNER_SCRATCH =
            ThreadLocal.withInitial(() -> new float[CORNER_POSITION_FLOATS]);
    private static final ThreadLocal<float[]> WEIGHT_SCRATCH = new ThreadLocal<>();

    private static volatile boolean configuredDefault = true;

    private final Class<?> warpFormClass;
    private final Class<?> warpDeformerClass;
    private final Class<?> simpleGridClass;
    private final Class<?> rectangleGridClass;
    private final Class<?> gmesh2Class;
    private final Class<?> warpPointRefClass;
    private final Object sortingLayerNormal;
    private final Object opaqueWhiteColor;
    private final int bezierOffsetPow;
    private final int bezierMinDiv;
    private final int bezierMaxDiv;

    private final MethodHandle formGetGridTestImpl;
    private final MethodHandle formGetPositions;
    private final MethodHandle legacySimpleGridGet01;
    private final MethodHandle debugFlagGetter;
    private final MethodHandle warpDeformerGetSource;
    private final MethodHandle warpDeformerGetCalculatedForm;
    private final MethodHandle warpSourceIsQuadTransform;
    private final MethodHandle warpSourceGetCol;
    private final MethodHandle warpSourceGetRow;
    private final MethodHandle simpleGridGetCol;
    private final MethodHandle simpleGridGetRow;
    private final MethodHandle simpleGridGetPoints;
    private final MethodHandle simpleGridGetBoundsRect;
    private final MethodHandle gridTransformGetSrcGrid;
    private final MethodHandle gridTransformGetDstGrid;
    private final MethodHandle icGridGet01;
    private final MethodHandle rectangleGridGetRect;
    private final MethodHandle grectfGetX;
    private final MethodHandle grectfGetY;
    private final MethodHandle grectfGetWidth;
    private final MethodHandle grectfGetHeight;
    private final MethodHandle grectfContains;
    private final MethodHandle vec2CtorNoArgs;
    private final MethodHandle vec2GetX;
    private final MethodHandle vec2GetY;
    private final MethodHandle vec2SetX;
    private final MethodHandle vec2SetY;
    private final MethodHandle vec3SetZ;
    private final MethodHandle bezierGridGetBezierCol;
    private final MethodHandle bezierGridGetBezierRow;
    private final MethodHandle bezierGridGetBezierPtRef;
    private final MethodHandle bezierPtGetAnchor;
    private final MethodHandle bezierPtGetCn;
    private final MethodHandle bezierPtGetCs;
    private final MethodHandle bezierPtGetCw;
    private final MethodHandle bezierPtGetCe;
    private final MethodHandle entitySetEnabled;
    private final MethodHandle entityGetTransform;
    private final MethodHandle transformGetPosition;
    private final MethodHandle simpleMeshEntityGetContainer;
    private final MethodHandle simpleMeshEntityGetRenderer;
    private final MethodHandle meshContainerGetMesh;
    private final MethodHandle meshRendererGetMaterial;
    private final MethodHandle simpleLinesEntityGetRenderer;
    private final MethodHandle lineRendererSetSortingLayer;
    private final MethodHandle lineRendererGetStep;
    private final MethodHandle lineRendererSetStep;
    private final MethodHandle lineRendererGetPositions;
    private final MethodHandle lineRendererSetPositions;
    private final MethodHandle lineRendererGetIndices;
    private final MethodHandle lineRendererSetIndices;
    private final MethodHandle lineRendererUpdateMeshData;
    private final MethodHandle lineRendererGetPositionsBuffer;
    private final MethodHandle lineRendererGetMaterial;
    private final MethodHandle pointRendererGetPositions;
    private final MethodHandle pointRendererGetColors;
    private final MethodHandle meshGetPositions;
    private final MethodHandle meshSetPositions;
    private final MethodHandle meshGetIndices;
    private final MethodHandle meshSetIndices;
    private final MethodHandle meshGetColors;
    private final MethodHandle meshSetColors;
    private final MethodHandle meshGetPositionsBuffer;
    private final MethodHandle meshGetIndicesBuffer;
    private final MethodHandle meshGetUvsBuffer;
    private final MethodHandle meshGetColorsBuffer;
    private final MethodHandle floatBufferUpload;
    private final MethodHandle intBufferUpload;
    private final MethodHandle materialSetColor;
    private final MethodHandle colorCtorFloats;
    private final MethodHandle colorToPremultiplied;
    private final MethodHandle colorGetRed;
    private final MethodHandle colorGetGreen;
    private final MethodHandle colorGetBlue;
    private final MethodHandle colorGetAlpha;
    private final MethodHandle selectionGetPointSelector;
    private final MethodHandle pointSelectorGetSelectedPoints;
    private final MethodHandle pointSelectorGetWeightMap;
    private final MethodHandle warpPointRefIndex;

    private final HostHandles.HostField drawWhiteCornerField;
    private final HostHandles.HostField drawBlackCornerField;
    private final HostHandles.HostField drawLineNormalField;
    private final HostHandles.HostField drawLineSelectedField;
    private final HostHandles.HostField drawWeightMapField;

    private final WeakIdentityCache<Object, CornerOverlayState> cornerCache = new WeakIdentityCache<>();
    private final WeakIdentityCache<Object, LineOverlayState> lineCache = new WeakIdentityCache<>();
    private final WeakIdentityCache<Object, FloatArraySnapshot> selectedBufferCache = new WeakIdentityCache<>();
    private final WeakIdentityCache<Object, WeightMapOverlayState> weightMapCache = new WeakIdentityCache<>();
    private final ClassLoader hostLoader;
    private final SubtreeParallelExecutor parallelExecutor;
    private final EditSubtreeCoordinator editSubtreeCoordinator;
    private final EditOverlayElisionCoordinator editOverlayCoordinator;

    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicBoolean activeSlot = new AtomicBoolean();
    private final Map<String, Object> publishedSlots;
    private Properties installedProperties;

    private final LongAdder formIsInsideCalls = new LongAdder();
    private final LongAdder canvasToLocalCalls = new LongAdder();
    private final LongAdder get01ExQuadCalls = new LongAdder();
    private final LongAdder inverseQuadCalls = new LongAdder();
    private final LongAdder gridIsInsideCalls = new LongAdder();
    private final LongAdder bezierPointsCalls = new LongAdder();
    private final LongAdder cornerTriangleCalls = new LongAdder();
    private final LongAdder majorLinesCalls = new LongAdder();
    private final LongAdder detailLinesCalls = new LongAdder();
    private final LongAdder selectedUploadElided = new LongAdder();
    private final LongAdder softSelectionCalls = new LongAdder();
    private final LongAdder overlayUploadsElided = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    /** Sets the default enable state used when {@link #ENABLE_PROPERTY} is unset. */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /** Returns whether this optimization is enabled by explicit property or configured default. */
    public static boolean enabledByPreference() {
        final String property = System.getProperty(ENABLE_PROPERTY);
        return property == null ? configuredDefault : Boolean.parseBoolean(property);
    }

    /**
     * Resolves all reviewed host members on {@code loader} for fail-closed bridge installation.
     *
     * @param loader admitted Cubism host class loader
     * @throws ReflectiveOperationException when any required host class, field, or method is missing
     */
    public WarpDeformEditBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this.hostLoader = Objects.requireNonNull(loader, "loader");

        warpFormClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_FORM_OWNER);
        warpDeformerClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_DEFORMER_OWNER);
        final Class<?> warpSourceClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_SOURCE_OWNER);
        simpleGridClass = HostHandles.load(loader, WarpDeformEditTarget.SIMPLE_GRID_OWNER);
        final Class<?> gridTransformClass = HostHandles.load(loader, WarpDeformEditTarget.GRID_TRANSFORM_OWNER);
        final Class<?> icGridClass = HostHandles.load(loader, IC_GRID_OWNER);
        rectangleGridClass = HostHandles.load(loader, WarpDeformEditTarget.RECTANGLE_GRID_OWNER);
        final Class<?> bezierGridClass = HostHandles.load(loader, WarpDeformEditTarget.BEZIER_GRID_OWNER);
        final Class<?> bezierPtClass = HostHandles.load(loader, WarpDeformEditTarget.BEZIER_PT_OWNER);
        final Class<?> warpDrawClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_DRAW_OWNER);
        final Class<?> warpSelectionClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_SELECTION_OWNER);
        warpPointRefClass = HostHandles.load(loader, WarpDeformEditTarget.WARP_POINT_REF_OWNER);
        final Class<?> pointSelectorClass = HostHandles.load(loader, WarpDeformEditTarget.POINT_SELECTOR_OWNER);
        final Class<?> rectClass = HostHandles.load(loader, WarpDeformEditTarget.GRECTF_OWNER);
        final Class<?> vec2Class = HostHandles.load(loader, WarpDeformEditTarget.GVECTOR2_OWNER);
        final Class<?> vec3Class = HostHandles.load(loader, WarpDeformEditTarget.GVECTOR3_OWNER);
        final Class<?> entityClass = HostHandles.load(loader, WarpDeformEditTarget.GENTITY_OWNER);
        final Class<?> simpleMeshEntityClass = HostHandles.load(loader, WarpDeformEditTarget.SIMPLE_MESH_ENTITY_OWNER);
        final Class<?> simpleLinesEntityClass =
                HostHandles.load(loader, WarpDeformEditTarget.SIMPLE_LINES_ENTITY_OWNER);
        final Class<?> transformClass = HostHandles.load(loader, WarpDeformEditTarget.GTRANSFORM_OWNER);
        final Class<?> meshContainerClass = HostHandles.load(loader, WarpDeformEditTarget.GMESH_CONTAINER_OWNER);
        final Class<?> meshRendererClass = HostHandles.load(loader, WarpDeformEditTarget.GMESH_RENDERER_OWNER);
        final Class<?> lineRendererClass = HostHandles.load(loader, WarpDeformEditTarget.GLINE_RENDERER_OWNER);
        final Class<?> pointRendererClass = HostHandles.load(loader, WarpDeformEditTarget.GPOINT_RENDERER_OWNER);
        final Class<?> sortingLayerValueClass =
                HostHandles.load(loader, WarpDeformEditTarget.SORTING_LAYER_VALUE_OWNER);
        final Class<?> meshBaseClass = HostHandles.load(loader, WarpDeformEditTarget.MESH_BASE_OWNER);
        gmesh2Class = HostHandles.load(loader, WarpDeformEditTarget.GMESH2_OWNER);
        final Class<?> floatBufferClass = HostHandles.load(loader, WarpDeformEditTarget.FLOAT_BUFFER_OWNER);
        final Class<?> intBufferClass = HostHandles.load(loader, WarpDeformEditTarget.INT_BUFFER_OWNER);
        final Class<?> materialClass = HostHandles.load(loader, WarpDeformEditTarget.GMATERIAL_OWNER);
        final Class<?> colorClass = HostHandles.load(loader, WarpDeformEditTarget.CCOLOR_OWNER);
        final Class<?> colorCompanionClass = HostHandles.load(loader, WarpDeformEditTarget.CCOLOR_COMPANION_OWNER);
        final Class<?> legacyGridClass = HostHandles.load(loader, WarpDeformEditTarget.LEGACY_SIMPLE_GRID_OWNER);
        final Class<?> debugFlagClass = HostHandles.load(loader, WarpDeformEditTarget.DEBUG_FLAG_OWNER);

        final MethodType objUnary = MethodType.methodType(Object.class, Object.class);
        final MethodType intUnary = MethodType.methodType(int.class, Object.class);
        final MethodType floatUnary = MethodType.methodType(float.class, Object.class);
        final MethodType boolUnary = MethodType.methodType(boolean.class, Object.class);
        final MethodType floatArrUnary = MethodType.methodType(float[].class, Object.class);
        final MethodType intArrUnary = MethodType.methodType(int[].class, Object.class);
        final MethodType voidUnary = MethodType.methodType(void.class, Object.class);

        formGetGridTestImpl = HostHandles.virtual(
                warpFormClass,
                "getGrid_testImpl",
                "()L" + WarpDeformEditTarget.LEGACY_SIMPLE_GRID_OWNER + ";",
                objUnary);
        formGetPositions = HostHandles.virtual(warpFormClass, GET_POSITIONS_METHOD, "()[F", floatArrUnary);
        legacySimpleGridGet01 = HostHandles.virtual(
                legacyGridClass,
                "get01",
                "(DD[D)[D",
                MethodType.methodType(Object.class, Object.class, double.class, double.class, double[].class));
        debugFlagGetter =
                HostHandles.staticCall(debugFlagClass, "b", BOOLEAN_DESCRIPTOR, MethodType.methodType(boolean.class));

        warpDeformerGetSource = HostHandles.virtual(
                warpDeformerClass, "getSource", "()L" + WarpDeformEditTarget.WARP_SOURCE_OWNER + ";", objUnary);
        warpDeformerGetCalculatedForm = HostHandles.virtual(
                warpDeformerClass, "getCalculatedForm", "()L" + WarpDeformEditTarget.WARP_FORM_OWNER + ";", objUnary);
        warpSourceIsQuadTransform =
                HostHandles.virtual(warpSourceClass, "isQuadTransform", BOOLEAN_DESCRIPTOR, boolUnary);
        warpSourceGetCol = HostHandles.virtual(warpSourceClass, "getCol", INT_DESCRIPTOR, intUnary);
        warpSourceGetRow = HostHandles.virtual(warpSourceClass, "getRow", INT_DESCRIPTOR, intUnary);

        simpleGridGetCol = HostHandles.virtual(simpleGridClass, "getCol", INT_DESCRIPTOR, intUnary);
        simpleGridGetRow = HostHandles.virtual(simpleGridClass, "getRow", INT_DESCRIPTOR, intUnary);
        simpleGridGetPoints = HostHandles.virtual(simpleGridClass, "getPoints", "()[F", floatArrUnary);
        simpleGridGetBoundsRect = HostHandles.virtual(
                simpleGridClass, "getBoundsRect", "()L" + WarpDeformEditTarget.GRECTF_OWNER + ";", objUnary);

        gridTransformGetSrcGrid =
                HostHandles.virtual(gridTransformClass, "getSrcGrid", "()L" + IC_GRID_OWNER + ";", objUnary);
        gridTransformGetDstGrid =
                HostHandles.virtual(gridTransformClass, "getDstGrid", "()L" + IC_GRID_OWNER + ";", objUnary);
        icGridGet01 = HostHandles.virtual(
                icGridClass,
                "get01",
                "(FF[F)[F",
                MethodType.methodType(float[].class, Object.class, float.class, float.class, float[].class));
        rectangleGridGetRect = HostHandles.virtual(
                rectangleGridClass, "getRect$cubism", "()L" + WarpDeformEditTarget.GRECTF_OWNER + ";", objUnary);

        grectfGetX = HostHandles.virtual(rectClass, "getX", FLOAT_DESCRIPTOR, floatUnary);
        grectfGetY = HostHandles.virtual(rectClass, "getY", FLOAT_DESCRIPTOR, floatUnary);
        grectfGetWidth = HostHandles.virtual(rectClass, "getWidth", FLOAT_DESCRIPTOR, floatUnary);
        grectfGetHeight = HostHandles.virtual(rectClass, "getHeight", FLOAT_DESCRIPTOR, floatUnary);
        grectfContains = HostHandles.virtual(
                rectClass,
                "contains",
                "(FF)Z",
                MethodType.methodType(boolean.class, Object.class, float.class, float.class));

        vec2CtorNoArgs = HostHandles.construct(vec2Class, VOID_DESCRIPTOR, MethodType.methodType(Object.class));
        vec2GetX = HostHandles.virtual(vec2Class, "getX", FLOAT_DESCRIPTOR, floatUnary);
        vec2GetY = HostHandles.virtual(vec2Class, "getY", FLOAT_DESCRIPTOR, floatUnary);
        vec2SetX = HostHandles.virtual(
                vec2Class, "setX", "(F)V", MethodType.methodType(void.class, Object.class, float.class));
        vec2SetY = HostHandles.virtual(
                vec2Class, "setY", "(F)V", MethodType.methodType(void.class, Object.class, float.class));
        vec3SetZ = HostHandles.virtual(
                vec3Class, "setZ", "(F)V", MethodType.methodType(void.class, Object.class, float.class));

        bezierGridGetBezierCol = HostHandles.virtual(bezierGridClass, "getBezierCol", INT_DESCRIPTOR, intUnary);
        bezierGridGetBezierRow = HostHandles.virtual(bezierGridClass, "getBezierRow", INT_DESCRIPTOR, intUnary);
        bezierGridGetBezierPtRef = HostHandles.virtual(
                bezierGridClass,
                "getBezierPtRef",
                "()[[L" + WarpDeformEditTarget.BEZIER_PT_OWNER + ";",
                MethodType.methodType(Object[][].class, Object.class));
        bezierPtGetAnchor = HostHandles.virtual(
                bezierPtClass, "getAnchor", "()L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";", objUnary);
        bezierPtGetCn = HostHandles.virtual(
                bezierPtClass, "getCn", "()L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";", objUnary);
        bezierPtGetCs = HostHandles.virtual(
                bezierPtClass, "getCs", "()L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";", objUnary);
        bezierPtGetCw = HostHandles.virtual(
                bezierPtClass, "getCw", "()L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";", objUnary);
        bezierPtGetCe = HostHandles.virtual(
                bezierPtClass, "getCe", "()L" + WarpDeformEditTarget.GVECTOR2_OWNER + ";", objUnary);

        entitySetEnabled = HostHandles.virtual(
                entityClass,
                "setEnabled",
                SET_BOOLEAN_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, boolean.class));
        entityGetTransform = HostHandles.virtual(
                entityClass, "getTransform", "()L" + WarpDeformEditTarget.GTRANSFORM_OWNER + ";", objUnary);
        transformGetPosition = HostHandles.virtual(
                transformClass, "getPosition", "()L" + WarpDeformEditTarget.GVECTOR3_OWNER + ";", objUnary);

        simpleMeshEntityGetContainer = HostHandles.virtual(
                simpleMeshEntityClass,
                "getMeshContainer",
                "()L" + WarpDeformEditTarget.GMESH_CONTAINER_OWNER + ";",
                objUnary);
        simpleMeshEntityGetRenderer = HostHandles.virtual(
                simpleMeshEntityClass,
                "getMeshRenderer",
                "()L" + WarpDeformEditTarget.GMESH_RENDERER_OWNER + ";",
                objUnary);
        meshContainerGetMesh = HostHandles.virtual(
                meshContainerClass, "getMesh", "()L" + WarpDeformEditTarget.MESH_BASE_OWNER + ";", objUnary);
        meshRendererGetMaterial = HostHandles.virtual(
                meshRendererClass, "getMaterial", "()L" + WarpDeformEditTarget.GMATERIAL_OWNER + ";", objUnary);
        simpleLinesEntityGetRenderer = HostHandles.virtual(
                simpleLinesEntityClass,
                "getLineRenderer",
                "()L" + WarpDeformEditTarget.GLINE_RENDERER_OWNER + ";",
                objUnary);

        lineRendererSetSortingLayer = HostHandles.virtual(
                lineRendererClass,
                "setSortingLayer",
                "(Lcom/live2d/graphics3d/component/a/c;)V",
                MethodType.methodType(void.class, Object.class, Object.class));
        lineRendererGetStep = HostHandles.virtual(lineRendererClass, GET_STEP_METHOD, INT_DESCRIPTOR, intUnary);
        lineRendererSetStep = HostHandles.virtual(
                lineRendererClass,
                SET_STEP_METHOD,
                SET_INT_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, int.class));
        lineRendererGetPositions = HostHandles.virtual(
                lineRendererClass, GET_POSITIONS_METHOD, "()" + FLOAT_ARRAY_DESCRIPTOR, floatArrUnary);
        lineRendererSetPositions = HostHandles.virtual(
                lineRendererClass,
                SET_POSITIONS_METHOD,
                SET_FLOAT_ARRAY_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, float[].class));
        lineRendererGetIndices =
                HostHandles.virtual(lineRendererClass, GET_INDICES_METHOD, "()" + INT_ARRAY_DESCRIPTOR, intArrUnary);
        lineRendererSetIndices = HostHandles.virtual(
                lineRendererClass,
                SET_INDICES_METHOD,
                SET_INT_ARRAY_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, int[].class));
        lineRendererUpdateMeshData =
                HostHandles.virtual(lineRendererClass, UPDATE_MESH_DATA_METHOD, VOID_DESCRIPTOR, voidUnary);
        lineRendererGetPositionsBuffer = HostHandles.virtual(
                lineRendererClass,
                "getPositionsBuffer",
                "()L" + WarpDeformEditTarget.FLOAT_BUFFER_OWNER + ";",
                objUnary);
        lineRendererGetMaterial = HostHandles.virtual(
                lineRendererClass, "getMaterial", "()L" + WarpDeformEditTarget.GMATERIAL_OWNER + ";", objUnary);
        pointRendererGetPositions = HostHandles.virtual(
                pointRendererClass, GET_POSITIONS_METHOD, "()" + FLOAT_ARRAY_DESCRIPTOR, floatArrUnary);
        pointRendererGetColors = HostHandles.virtual(
                pointRendererClass, GET_COLORS_METHOD, "()" + FLOAT_ARRAY_DESCRIPTOR, floatArrUnary);

        meshGetPositions =
                HostHandles.virtual(meshBaseClass, GET_POSITIONS_METHOD, "()" + FLOAT_ARRAY_DESCRIPTOR, floatArrUnary);
        meshSetPositions = HostHandles.virtual(
                meshBaseClass,
                SET_POSITIONS_METHOD,
                SET_FLOAT_ARRAY_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, float[].class));
        meshGetIndices =
                HostHandles.virtual(meshBaseClass, GET_INDICES_METHOD, "()" + INT_ARRAY_DESCRIPTOR, intArrUnary);
        meshSetIndices = HostHandles.virtual(
                meshBaseClass,
                SET_INDICES_METHOD,
                SET_INT_ARRAY_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, int[].class));
        meshGetColors =
                HostHandles.virtual(meshBaseClass, GET_COLORS_METHOD, "()" + FLOAT_ARRAY_DESCRIPTOR, floatArrUnary);
        meshSetColors = HostHandles.virtual(
                meshBaseClass,
                SET_COLORS_METHOD,
                SET_FLOAT_ARRAY_DESCRIPTOR,
                MethodType.methodType(void.class, Object.class, float[].class));
        meshGetPositionsBuffer = HostHandles.virtual(
                meshBaseClass, "getPositionsBuffer", "()L" + WarpDeformEditTarget.FLOAT_BUFFER_OWNER + ";", objUnary);
        meshGetIndicesBuffer = HostHandles.virtual(
                meshBaseClass, "getIndicesBuffer", "()L" + WarpDeformEditTarget.INT_BUFFER_OWNER + ";", objUnary);
        meshGetUvsBuffer = HostHandles.virtual(
                meshBaseClass, "getUvsBuffer", "()L" + WarpDeformEditTarget.FLOAT_BUFFER_OWNER + ";", objUnary);
        meshGetColorsBuffer = HostHandles.virtual(
                meshBaseClass, "getColorsBuffer", "()L" + WarpDeformEditTarget.FLOAT_BUFFER_OWNER + ";", objUnary);
        floatBufferUpload = HostHandles.virtual(floatBufferClass, BUFFER_UPLOAD_METHOD, VOID_DESCRIPTOR, voidUnary);
        intBufferUpload = HostHandles.virtual(intBufferClass, BUFFER_UPLOAD_METHOD, VOID_DESCRIPTOR, voidUnary);

        materialSetColor = resolveMaterialSetColor(materialClass);
        colorCtorFloats = HostHandles.construct(
                colorClass,
                "(FFFF)V",
                MethodType.methodType(Object.class, float.class, float.class, float.class, float.class));
        colorToPremultiplied = HostHandles.virtual(
                colorClass,
                TO_PREMULTIPLIED_METHOD,
                "(FFFF)L" + WarpDeformEditTarget.CCOLOR_OWNER + ";",
                MethodType.methodType(Object.class, Object.class, float.class, float.class, float.class, float.class));
        colorGetRed = HostHandles.virtual(colorClass, "getRed", FLOAT_DESCRIPTOR, floatUnary);
        colorGetGreen = HostHandles.virtual(colorClass, "getGreen", FLOAT_DESCRIPTOR, floatUnary);
        colorGetBlue = HostHandles.virtual(colorClass, "getBlue", FLOAT_DESCRIPTOR, floatUnary);
        colorGetAlpha = HostHandles.virtual(colorClass, "getAlpha", FLOAT_DESCRIPTOR, floatUnary);

        selectionGetPointSelector = HostHandles.virtual(
                warpSelectionClass,
                "getPointSelector",
                "()L" + WarpDeformEditTarget.POINT_SELECTOR_OWNER + ";",
                objUnary);
        pointSelectorGetSelectedPoints =
                HostHandles.virtual(pointSelectorClass, "getSelectedPoints", "()Ljava/util/List;", objUnary);
        pointSelectorGetWeightMap =
                HostHandles.virtual(pointSelectorClass, "getWeightMap", "()Ljava/util/HashMap;", objUnary);
        warpPointRefIndex = HostHandles.virtual(warpPointRefClass, "a", INT_DESCRIPTOR, intUnary);

        drawWhiteCornerField = HostHandles.field(warpDrawClass, FIELD_WHITE_CORNER);
        drawBlackCornerField = HostHandles.field(warpDrawClass, FIELD_BLACK_CORNER);
        drawLineNormalField = HostHandles.field(warpDrawClass, FIELD_LINE_NORMAL);
        drawLineSelectedField = HostHandles.field(warpDrawClass, FIELD_LINE_SELECTED);
        drawWeightMapField = HostHandles.field(warpDrawClass, FIELD_WEIGHT_MAP);

        final MethodHandle companionWhiteHandle = HostHandles.virtual(
                colorCompanionClass, "a", "()L" + WarpDeformEditTarget.CCOLOR_OWNER + ";", objUnary);
        try {
            bezierOffsetPow =
                    (int) HostHandles.field(bezierGridClass, FIELD_OFF_SET).get(null);
            bezierMinDiv =
                    (int) HostHandles.field(bezierGridClass, FIELD_MIN_DIV).get(null);
            bezierMaxDiv =
                    (int) HostHandles.field(bezierGridClass, FIELD_MAX_DIV).get(null);
            sortingLayerNormal = Objects.requireNonNull(
                    HostHandles.field(sortingLayerValueClass, FIELD_SORTING_LAYER_A)
                            .get(null),
                    "sortingLayerNormal");
            final Object companion = Objects.requireNonNull(
                    HostHandles.field(colorClass, FIELD_COMPANION).get(null), "CColor.Companion");
            opaqueWhiteColor = Objects.requireNonNull(companionWhiteHandle.invokeExact(companion), "opaqueWhiteColor");
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new ReflectiveOperationException("Failed reading host static constants", failure);
        }

        final SubtreeParallelExecutor executor = new SubtreeParallelExecutor(loader);
        try {
            final EditSubtreeHandles subtreeHandles = EditSubtreeHandles.resolve(loader);
            parallelExecutor = executor;
            editSubtreeCoordinator = new EditSubtreeCoordinator(subtreeHandles, executor);
            editOverlayCoordinator = new EditOverlayElisionCoordinator(subtreeHandles, editSubtreeCoordinator);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            executor.close();
            if (failure instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            throw new ReflectiveOperationException("Failed resolving edit subtree handles", failure);
        }

        publishedSlots = buildSlotMap();
    }

    /** Atomically publishes bridge callback slots into {@link System#getProperties()}. */
    public synchronized void install() {
        if (active.get()) {
            throw new IllegalStateException("warp deform edit bridge already installed");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (final String key : publishedSlots.keySet()) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException("warp deform edit slot occupied: " + key);
                }
            }
            try {
                properties.putAll(publishedSlots);
                installedProperties = properties;
                activeSlot.set(enabledByPreference());
                active.set(true);
            } catch (RuntimeException | Error failure) {
                FatalErrors.rethrowIfFatal(failure);
                for (final Map.Entry<String, Object> entry : publishedSlots.entrySet()) {
                    properties.remove(entry.getKey(), entry.getValue());
                }
                throw failure;
            }
        }
    }

    @Override
    public synchronized void close() {
        activeSlot.set(false);
        if (!active.compareAndSet(true, false)) {
            clearCaches();
            parallelExecutor.close();
            return;
        }
        final Properties properties = installedProperties;
        if (properties != null) {
            synchronized (properties) {
                for (final Map.Entry<String, Object> entry : publishedSlots.entrySet()) {
                    properties.remove(entry.getKey(), entry.getValue());
                }
            }
        }
        installedProperties = null;
        clearCaches();
        parallelExecutor.close();
    }

    /** Returns immutable telemetry counters for bridge invocations and elided uploads. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> counters = new LinkedHashMap<>();
        counters.put(STAT_ACTIVE, isOperational() ? 1L : 0L);
        counters.put(STAT_FORM_IS_INSIDE, formIsInsideCalls.sum());
        counters.put(STAT_CANVAS_TO_LOCAL, canvasToLocalCalls.sum());
        counters.put(STAT_GET01_EX_QUAD, get01ExQuadCalls.sum());
        counters.put(STAT_INVERSE_QUAD, inverseQuadCalls.sum());
        counters.put(STAT_GRID_IS_INSIDE, gridIsInsideCalls.sum());
        counters.put(STAT_BEZIER_POINTS, bezierPointsCalls.sum());
        counters.put(STAT_CORNER_TRIANGLE, cornerTriangleCalls.sum());
        counters.put(STAT_MAJOR_LINES, majorLinesCalls.sum());
        counters.put(STAT_DETAIL_LINES, detailLinesCalls.sum());
        counters.put(STAT_SELECTED_UPLOAD_ELIDED, selectedUploadElided.sum());
        counters.put(STAT_SOFT_SELECTION, softSelectionCalls.sum());
        counters.put(STAT_OVERLAY_UPLOADS_ELIDED, overlayUploadsElided.sum());
        counters.put(STAT_FAST_MODEL_UPDATES, editSubtreeCoordinator.fastModelUpdates.sum());
        counters.put(STAT_FALLBACK_MODEL_UPDATES, editSubtreeCoordinator.fallbackModelUpdates.sum());
        counters.put(STAT_DIRTY_DEFORMERS, editSubtreeCoordinator.dirtyDeformersProcessed.sum());
        counters.put(STAT_SKIPPED_DEFORMERS, editSubtreeCoordinator.skippedDeformers.sum());
        counters.put(STAT_DIRTY_MESHES, editSubtreeCoordinator.dirtyMeshesProcessed.sum());
        counters.put(STAT_SKIPPED_MESHES, editSubtreeCoordinator.skippedMeshes.sum());
        counters.put(STAT_APPEARANCE_SKIPPED, editSubtreeCoordinator.appearanceSkipped.sum());
        counters.put(STAT_FULL_ARRAY_HASHES, editSubtreeCoordinator.fullArrayHashes.sum());
        counters.put(STAT_PARALLEL_DEFORMER_LEVELS, editSubtreeCoordinator.parallelDeformerLevels.sum());
        counters.put(STAT_PARALLEL_MESH_BATCHES, editSubtreeCoordinator.parallelMeshBatches.sum());
        counters.put(STAT_OVERLAY_ELISIONS, editOverlayCoordinator.overlayElisions.sum());
        counters.put(STAT_OVERLAY_UPDATES, editOverlayCoordinator.overlayUpdates.sum());
        counters.put(STAT_FAILURES, failures.sum());
        return Map.copyOf(counters);
    }

    private Map<String, Object> buildSlotMap() throws ReflectiveOperationException {
        final MethodHandles.Lookup lookup = MethodHandles.lookup();
        final Map<String, Object> slots = new LinkedHashMap<>();
        slots.put(ACTIVE_PROPERTY, activeSlot);
        slots.put(
                FORM_IS_INSIDE_PROPERTY,
                lookup.bind(this, "formIsInside", MethodType.methodType(int.class, Object.class, Object.class)));
        slots.put(
                TRANSFORM_CANVAS_TO_LOCAL_PROPERTY,
                lookup.bind(
                        this,
                        "transformCanvasToLocalQuad",
                        MethodType.methodType(boolean.class, Object.class, float[].class, float[].class, int.class)));
        slots.put(
                GET01_EX_QUAD_PROPERTY,
                lookup.bind(
                        this,
                        "gridGet01ExQuad",
                        MethodType.methodType(
                                Object.class, Object.class, float.class, float.class, Object.class, float[].class)));
        slots.put(
                INVERSE_QUAD_PROPERTY,
                lookup.bind(
                        this,
                        "gridTransformInverseQuad",
                        MethodType.methodType(Object.class, Object.class, Object.class, Object.class)));
        slots.put(
                GRID_TRANSFORM_IS_INSIDE_PROPERTY,
                lookup.bind(
                        this,
                        "gridTransformIsInside",
                        MethodType.methodType(int.class, Object.class, Object.class, boolean.class)));
        slots.put(
                CREATE_POINTS_ON_BEZIER_PROPERTY,
                lookup.bind(
                        this,
                        "createPointsOnBezier",
                        MethodType.methodType(float[].class, Object.class, int.class, int.class)));
        slots.put(
                DRAW_CORNER_TRIANGLE_PROPERTY,
                lookup.bind(
                        this,
                        "drawCornerTriangle",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                float[].class,
                                int.class,
                                int.class,
                                float.class,
                                float.class,
                                Object.class)));
        slots.put(
                DRAW_MAJOR_LINES_PROPERTY,
                lookup.bind(
                        this,
                        "drawMajorLines",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                float[].class,
                                int.class,
                                int.class,
                                Object.class,
                                boolean.class)));
        slots.put(
                DRAW_DETAIL_LINES_PROPERTY,
                lookup.bind(
                        this,
                        "drawDetailLines",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                float[].class,
                                int.class,
                                int.class,
                                Object.class,
                                boolean.class)));
        slots.put(
                DRAW_SELECTED_POINT_UPLOAD_PROPERTY,
                lookup.bind(
                        this,
                        "shouldUploadSelectedPointBuffer",
                        MethodType.methodType(boolean.class, Object.class, Object.class, boolean.class)));
        slots.put(
                DRAW_SOFT_SELECTION_PROPERTY,
                lookup.bind(
                        this,
                        "drawSoftSelectionBody",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                float[].class,
                                int.class,
                                int.class)));
        slots.put(
                EDIT_SUBTREE_UPDATE_MODEL_PROPERTY,
                lookup.bind(
                        this,
                        "tryFastCoreUpdate",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class)));
        slots.put(
                EDIT_SUBTREE_END_MODEL_PROPERTY,
                lookup.bind(
                        this,
                        "afterCoreUpdate",
                        MethodType.methodType(
                                void.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class,
                                Object.class)));
        slots.put(
                EDIT_SUBTREE_SKIP_MESH_APPEARANCE_PROPERTY,
                lookup.bind(
                        this,
                        "shouldSkipCleanArtMeshAppearance",
                        MethodType.methodType(boolean.class, Object.class, Object.class)));
        slots.put(
                EDIT_OVERLAY_ELIDE_PROPERTY,
                lookup.bind(
                        this,
                        "tryElideDeformerOverlay",
                        MethodType.methodType(
                                boolean.class,
                                Object.class,
                                Object.class,
                                boolean.class,
                                boolean.class,
                                Object.class,
                                Object.class)));
        slots.put(
                EDIT_OVERLAY_RECORD_PROPERTY,
                lookup.bind(
                        this,
                        "recordDeformerOverlay",
                        MethodType.methodType(
                                void.class, Object.class, boolean.class, boolean.class, Object.class, Object.class)));
        slots.put(
                EDIT_LEGACY_OVERLAY_ELIDE_PROPERTY,
                lookup.bind(this, "tryElideLegacyDeformerOverlay", MethodType.methodType(boolean.class, Object.class)));
        slots.put(
                EDIT_LEGACY_OVERLAY_RECORD_PROPERTY,
                lookup.bind(this, "recordLegacyDeformerOverlay", MethodType.methodType(void.class, Object.class)));
        slots.put(STATS_PROPERTY, statistics);
        return Map.copyOf(slots);
    }

    private boolean tryFastCoreUpdate(
            final Object updater,
            final Object model,
            final Object visibleMeshes,
            final Object updCtx,
            final Object viewCtx,
            final Object ctx5) {
        if (!isOperational()) {
            return false;
        }
        return editSubtreeCoordinator.tryFastCoreUpdate(updater, model, visibleMeshes, updCtx, viewCtx, ctx5);
    }

    private void afterCoreUpdate(
            final Object updater,
            final Object model,
            final Object visibleMeshes,
            final Object updCtx,
            final Object viewCtx,
            final Object ctx5) {
        if (!isOperational()) {
            return;
        }
        editSubtreeCoordinator.afterNativeCoreUpdate(updater, model, visibleMeshes, updCtx, viewCtx, ctx5);
    }

    private boolean shouldSkipCleanArtMeshAppearance(final Object artMesh, final Object model) {
        if (!isOperational()) {
            return false;
        }
        return editSubtreeCoordinator.shouldSkipCleanArtMeshAppearance(artMesh, model);
    }

    private boolean tryElideDeformerOverlay(
            final Object deformer,
            final Object sceneGraph,
            final boolean hideSelected,
            final boolean isDeformerVisible,
            final Object rotMode,
            final Object actionPack) {
        if (!isOperational()) {
            return false;
        }
        return editOverlayCoordinator.tryElideOverlay(
                deformer, sceneGraph, hideSelected, isDeformerVisible, rotMode, actionPack);
    }

    private void recordDeformerOverlay(
            final Object deformer,
            final boolean hideSelected,
            final boolean isDeformerVisible,
            final Object rotMode,
            final Object actionPack) {
        if (!isOperational()) {
            return;
        }
        editOverlayCoordinator.recordUpdatedOverlay(deformer, hideSelected, isDeformerVisible, rotMode, actionPack);
    }

    private boolean tryElideLegacyDeformerOverlay(final Object viewOverlay) {
        if (!isOperational()) {
            return false;
        }
        return editOverlayCoordinator.tryElideLegacyOverlayLoop(viewOverlay);
    }

    private void recordLegacyDeformerOverlay(final Object viewOverlay) {
        if (!isOperational()) {
            return;
        }
        editOverlayCoordinator.recordLegacyOverlayLoop(viewOverlay);
    }

    private boolean isOperational() {
        final boolean enabled = active.get() && enabledByPreference();
        if (activeSlot.get() != enabled) {
            activeSlot.set(enabled);
        }
        return enabled;
    }

    private void clearCaches() {
        cornerCache.clear();
        lineCache.clear();
        selectedBufferCache.clear();
        weightMapCache.clear();
        editSubtreeCoordinator.clearCaches();
        editOverlayCoordinator.clearCaches();
    }

    private int formIsInside(final Object form, final Object pt) {
        if (!isOperational() || form == null || pt == null || !warpFormClass.isInstance(form)) {
            return -1;
        }
        formIsInsideCalls.increment();
        try {
            final Object grid = (Object) formGetGridTestImpl.invokeExact(form);
            if (grid == null) {
                return -1;
            }
            final float x = (float) vec2GetX.invokeExact(pt);
            final float y = (float) vec2GetY.invokeExact(pt);
            final double[] scratch = DOUBLE2_SCRATCH.get();
            final Object res = (Object) legacySimpleGridGet01.invokeExact(grid, (double) x, (double) y, scratch);
            return res != null ? 1 : 0;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return -1;
        }
    }

    private boolean transformCanvasToLocalQuad(
            final Object deformer, final float[] src, final float[] dst, final int numPoint) {
        if (!isOperational()
                || deformer == null
                || src == null
                || dst == null
                || !warpDeformerClass.isInstance(deformer)) {
            return false;
        }
        if (numPoint < 0 || src.length < numPoint * 2 || dst.length < numPoint * 2) {
            return false;
        }
        canvasToLocalCalls.increment();
        try {
            if ((boolean) debugFlagGetter.invokeExact()) {
                return false;
            }
            final Object source = (Object) warpDeformerGetSource.invokeExact(deformer);
            if (source == null || !(boolean) warpSourceIsQuadTransform.invokeExact(source)) {
                return false;
            }
            final int col = (int) warpSourceGetCol.invokeExact(source);
            final int row = (int) warpSourceGetRow.invokeExact(source);
            if (col <= 0 || row <= 0) {
                return false;
            }
            final Object form = (Object) warpDeformerGetCalculatedForm.invokeExact(deformer);
            if (form == null) {
                return false;
            }
            final float[] points = (float[]) formGetPositions.invokeExact(form);
            final int required = (col + 1) * (row + 1) * 2;
            if (points == null || points.length < required || numPoint == 0) {
                return numPoint == 0;
            }
            // Match CSimpleGrid.getBoundsRect + GRectF.contains before running cell inversion.
            float minX = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < required; i += 2) {
                final float px = points[i];
                final float py = points[i + 1];
                if (px < minX) minX = px;
                if (px > maxX) maxX = px;
                if (py < minY) minY = py;
                if (py > maxY) maxY = py;
            }
            final float[] uv = FLOAT2_SCRATCH.get();
            for (int i = 0; i < numPoint; i++) {
                final float x = src[i * 2];
                final float y = src[i * 2 + 1];
                if (x < minX || x >= maxX || y < minY || y >= maxY) {
                    return false;
                }
                if (WarpDeformMath.get01ExQuad(points, col, row, x, y, ZERO_F, ZERO_F, false, uv) == null) {
                    return false;
                }
                dst[i * 2] = uv[0];
                dst[i * 2 + 1] = uv[1];
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    private Object gridGet01ExQuad(
            final Object simpleGrid, final float x, final float y, final Object targetVec, final float[] dst) {
        if (!isOperational()
                || simpleGrid == null
                || dst == null
                || dst.length < 2
                || !simpleGridClass.isInstance(simpleGrid)) {
            return Boolean.FALSE;
        }
        get01ExQuadCalls.increment();
        try {
            final Object bounds = (Object) simpleGridGetBoundsRect.invokeExact(simpleGrid);
            if (bounds == null) {
                return Boolean.FALSE;
            }
            if (!(boolean) grectfContains.invokeExact(bounds, x, y)) {
                return null;
            }
            final int col = (int) simpleGridGetCol.invokeExact(simpleGrid);
            final int row = (int) simpleGridGetRow.invokeExact(simpleGrid);
            final float[] points = (float[]) simpleGridGetPoints.invokeExact(simpleGrid);
            if (points == null) {
                return Boolean.FALSE;
            }
            final boolean hasTarget = targetVec != null;
            final float tx = hasTarget ? (float) vec2GetX.invokeExact(targetVec) : ZERO_F;
            final float ty = hasTarget ? (float) vec2GetY.invokeExact(targetVec) : ZERO_F;
            return WarpDeformMath.get01ExQuad(points, col, row, x, y, tx, ty, hasTarget, dst);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return Boolean.FALSE;
        }
    }

    private Object gridTransformInverseQuad(final Object transform, final Object srcVec, final Object dstVec) {
        if (!isOperational() || transform == null || srcVec == null) {
            return null;
        }
        inverseQuadCalls.increment();
        try {
            if ((boolean) debugFlagGetter.invokeExact()) {
                return null;
            }
            final Object srcGrid = (Object) gridTransformGetSrcGrid.invokeExact(transform);
            final Object dstGrid = (Object) gridTransformGetDstGrid.invokeExact(transform);
            if (!rectangleGridClass.isInstance(srcGrid) || !simpleGridClass.isInstance(dstGrid)) {
                return null;
            }
            final float x = (float) vec2GetX.invokeExact(srcVec);
            final float y = (float) vec2GetY.invokeExact(srcVec);
            final Object bounds = (Object) simpleGridGetBoundsRect.invokeExact(dstGrid);
            if (bounds == null || !(boolean) grectfContains.invokeExact(bounds, x, y)) {
                return null;
            }
            final int col = (int) simpleGridGetCol.invokeExact(dstGrid);
            final int row = (int) simpleGridGetRow.invokeExact(dstGrid);
            final float[] points = (float[]) simpleGridGetPoints.invokeExact(dstGrid);
            final float[] uv = FLOAT2_SCRATCH.get();
            if (WarpDeformMath.get01ExQuad(points, col, row, x, y, ZERO_F, ZERO_F, false, uv) == null) {
                return null;
            }
            final Object rect = (Object) rectangleGridGetRect.invokeExact(srcGrid);
            if (rect == null) {
                return null;
            }
            final float outX = (float) grectfGetX.invokeExact(rect) + (float) grectfGetWidth.invokeExact(rect) * uv[0];
            final float outY = (float) grectfGetY.invokeExact(rect) + (float) grectfGetHeight.invokeExact(rect) * uv[1];
            final Object result = dstVec != null ? dstVec : (Object) vec2CtorNoArgs.invokeExact();
            vec2SetX.invokeExact(result, outX);
            vec2SetY.invokeExact(result, outY);
            return result;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    private int gridTransformIsInside(final Object transform, final Object ptVec, final boolean isDst) {
        if (!isOperational() || transform == null || ptVec == null) {
            return -1;
        }
        gridIsInsideCalls.increment();
        try {
            final Object grid = isDst
                    ? (Object) gridTransformGetDstGrid.invokeExact(transform)
                    : (Object) gridTransformGetSrcGrid.invokeExact(transform);
            if (grid == null) {
                return -1;
            }
            final float x = (float) vec2GetX.invokeExact(ptVec);
            final float y = (float) vec2GetY.invokeExact(ptVec);
            final float[] scratch = FLOAT2_SCRATCH.get();
            return (float[]) icGridGet01.invokeExact(grid, x, y, scratch) != null ? 1 : 0;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return -1;
        }
    }

    private float[] createPointsOnBezier(final Object bezierGrid, final int col, final int row) {
        if (!isOperational() || bezierGrid == null || col <= 1 || row <= 1) {
            return null;
        }
        bezierPointsCalls.increment();
        try {
            final int bCol = (int) bezierGridGetBezierCol.invokeExact(bezierGrid);
            final int bRow = (int) bezierGridGetBezierRow.invokeExact(bezierGrid);
            if (bCol <= 0 || bRow <= 0) {
                return null;
            }
            final Object[][] ptRef = (Object[][]) bezierGridGetBezierPtRef.invokeExact(bezierGrid);
            if (ptRef == null || ptRef.length <= bCol) {
                return null;
            }
            for (int px = 0; px <= bCol; px++) {
                if (ptRef[px] == null || ptRef[px].length <= bRow) {
                    return null;
                }
            }
            final float[] out = new float[2 * row * col];
            for (int py = 0; py < bRow; py++) {
                for (int px = 0; px < bCol; px++) {
                    if (!fillBezierPatchPoints(ptRef, bCol, bRow, px, py, col, row, out)) {
                        return null;
                    }
                }
            }
            return out;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return null;
        }
    }

    private boolean fillBezierPatchPoints(
            final Object[][] ptRef,
            final int bCol,
            final int bRow,
            final int px,
            final int py,
            final int col,
            final int row,
            final float[] out)
            throws Throwable {
        final Object nw = ptRef[px][py];
        final Object ne = ptRef[px + 1][py];
        final Object sw = ptRef[px][py + 1];
        final Object se = ptRef[px + 1][py + 1];
        if (nw == null || ne == null || sw == null || se == null) {
            return false;
        }
        final Object nwA = (Object) bezierPtGetAnchor.invokeExact(nw);
        final Object nwCe = (Object) bezierPtGetCe.invokeExact(nw);
        final Object nwCs = (Object) bezierPtGetCs.invokeExact(nw);
        final Object neA = (Object) bezierPtGetAnchor.invokeExact(ne);
        final Object neCw = (Object) bezierPtGetCw.invokeExact(ne);
        final Object neCs = (Object) bezierPtGetCs.invokeExact(ne);
        final Object swA = (Object) bezierPtGetAnchor.invokeExact(sw);
        final Object swCe = (Object) bezierPtGetCe.invokeExact(sw);
        final Object swCn = (Object) bezierPtGetCn.invokeExact(sw);
        final Object seA = (Object) bezierPtGetAnchor.invokeExact(se);
        final Object seCw = (Object) bezierPtGetCw.invokeExact(se);
        final Object seCn = (Object) bezierPtGetCn.invokeExact(se);
        if (nwA == null
                || nwCe == null
                || nwCs == null
                || neA == null
                || neCw == null
                || neCs == null
                || swA == null
                || swCe == null
                || swCn == null
                || seA == null
                || seCw == null
                || seCn == null) {
            return false;
        }

        final float nwX = (float) vec2GetX.invokeExact(nwA), nwY = (float) vec2GetY.invokeExact(nwA);
        final float nwCeX = (float) vec2GetX.invokeExact(nwCe), nwCeY = (float) vec2GetY.invokeExact(nwCe);
        final float nwCsX = (float) vec2GetX.invokeExact(nwCs), nwCsY = (float) vec2GetY.invokeExact(nwCs);
        final float neX = (float) vec2GetX.invokeExact(neA), neY = (float) vec2GetY.invokeExact(neA);
        final float neCwX = (float) vec2GetX.invokeExact(neCw), neCwY = (float) vec2GetY.invokeExact(neCw);
        final float neCsX = (float) vec2GetX.invokeExact(neCs), neCsY = (float) vec2GetY.invokeExact(neCs);
        final float swX = (float) vec2GetX.invokeExact(swA), swY = (float) vec2GetY.invokeExact(swA);
        final float swCeX = (float) vec2GetX.invokeExact(swCe), swCeY = (float) vec2GetY.invokeExact(swCe);
        final float swCnX = (float) vec2GetX.invokeExact(swCn), swCnY = (float) vec2GetY.invokeExact(swCn);
        final float seX = (float) vec2GetX.invokeExact(seA), seY = (float) vec2GetY.invokeExact(seA);
        final float seCwX = (float) vec2GetX.invokeExact(seCw), seCwY = (float) vec2GetY.invokeExact(seCw);
        final float seCnX = (float) vec2GetX.invokeExact(seCn), seCnY = (float) vec2GetY.invokeExact(seCn);

        final int packedDiv = WarpDeformMath.computeCellDivisions(
                nwX,
                nwY,
                nwCeX,
                nwCeY,
                nwCsX,
                nwCsY,
                neX,
                neY,
                neCwX,
                neCwY,
                neCsX,
                neCsY,
                swX,
                swY,
                swCeX,
                swCeY,
                swCnX,
                swCnY,
                seX,
                seY,
                seCwX,
                seCwY,
                seCnX,
                seCnY,
                bezierOffsetPow,
                bezierMinDiv,
                bezierMaxDiv);
        final int divX = packedDiv >>> 16;
        final int divY = packedDiv & 0xFFFF;
        final float[] lattice = WarpDeformMath.buildPatchCornerLattice(
                nwX, nwY, nwCeX, nwCeY, nwCsX, nwCsY, neX, neY, neCwX, neCwY, neCsX, neCsY, swX, swY, swCeX, swCeY,
                swCnX, swCnY, seX, seY, seCwX, seCwY, seCnX, seCnY, divX, divY);

        final float rowDenom = row - 1;
        final float colDenom = col - 1;
        for (int iy = 0; iy < row; iy++) {
            final float v = (float) iy / rowDenom;
            int cellY = (int) (v * bRow);
            if (cellY < 0) cellY = 0;
            else if (cellY >= bRow) cellY = bRow - 1;
            if (cellY != py) {
                continue;
            }
            float lv = v * bRow - py;
            if (lv < ZERO_F) lv = ZERO_F;
            else if (lv > ONE_F) lv = ONE_F;

            for (int ix = 0; ix < col; ix++) {
                final float u = (float) ix / colDenom;
                int cellX = (int) (u * bCol);
                if (cellX < 0) cellX = 0;
                else if (cellX >= bCol) cellX = bCol - 1;
                if (cellX != px) {
                    continue;
                }
                float lu = u * bCol - px;
                if (lu < ZERO_F) lu = ZERO_F;
                else if (lu > ONE_F) lu = ONE_F;
                WarpDeformMath.evaluatePatchLatticePoint(lattice, divX, divY, lu, lv, out, (iy * col + ix) * 2);
            }
        }
        return true;
    }

    private boolean drawCornerTriangle(
            final Object draw,
            final float[] pos,
            final int col,
            final int row,
            final float opacity,
            final float size,
            final Object color) {
        if (!isOperational() || draw == null || pos == null || color == null || col < 1 || row < 1) {
            return false;
        }
        final int needed = (col + 1) * (row + 1) * 2;
        if (pos.length < needed) {
            return false;
        }
        cornerTriangleCalls.increment();
        try {
            final Object whiteEntity = drawWhiteCornerField.get(draw);
            final Object blackEntity = drawBlackCornerField.get(draw);
            if (whiteEntity == null || blackEntity == null) {
                return false;
            }
            final float sx = size * CORNER_SIZE_MULTIPLIER;
            final float sy = size * CORNER_SIZE_MULTIPLIER;
            final float cr = (float) colorGetRed.invokeExact(color);
            final float cg = (float) colorGetGreen.invokeExact(color);
            final float cb = (float) colorGetBlue.invokeExact(color);

            final boolean whiteOk = updateCornerEntity(
                    whiteEntity,
                    WHITE_CORNER_Z,
                    WHITE_CORNER_SCALE,
                    pos,
                    col,
                    sx,
                    sy,
                    row,
                    ONE_F,
                    ONE_F,
                    ONE_F,
                    opacity);
            final boolean blackOk = updateCornerEntity(
                    blackEntity, BLACK_CORNER_Z, BLACK_CORNER_SCALE, pos, col, sx, sy, row, cr, cg, cb, opacity);
            return whiteOk && blackOk;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    private boolean updateCornerEntity(
            final Object entity,
            final float z,
            final float scale,
            final float[] pos,
            final int col,
            final float sizeX,
            final float sizeY,
            final int row,
            final float red,
            final float green,
            final float blue,
            final float opacity)
            throws Throwable {
        final Object transform = (Object) entityGetTransform.invokeExact(entity);
        vec3SetZ.invokeExact((Object) transformGetPosition.invokeExact(transform), z);
        final Object container = (Object) simpleMeshEntityGetContainer.invokeExact(entity);
        final Object mesh = (Object) meshContainerGetMesh.invokeExact(container);
        if (mesh == null || !gmesh2Class.isInstance(mesh)) {
            return false;
        }
        final float[] meshPositions = (float[]) meshGetPositions.invokeExact(mesh);
        final int[] meshIndices = (int[]) meshGetIndices.invokeExact(mesh);
        if (meshPositions == null
                || meshPositions.length != CORNER_POSITION_FLOATS
                || meshIndices == null
                || meshIndices.length != CORNER_INDEX_COUNT) {
            return false;
        }

        final float[] scratch = CORNER_SCRATCH.get();
        fillCornerPositions(scratch, pos, col, row, sizeX * scale, sizeY * scale);
        final CornerOverlayState cache = cornerCache.getOrCompute(entity, CornerOverlayState::new);
        final boolean geomDirty = !cache.matchesGeometry(meshPositions, meshIndices, scratch);
        if (geomDirty) {
            System.arraycopy(scratch, 0, meshPositions, 0, CORNER_POSITION_FLOATS);
            for (int i = 0; i < CORNER_INDEX_COUNT; i++) {
                meshIndices[i] = i;
            }
            floatBufferUpload.invokeExact((Object) meshGetPositionsBuffer.invokeExact(mesh));
            intBufferUpload.invokeExact((Object) meshGetIndicesBuffer.invokeExact(mesh));
            cache.recordGeometry(meshPositions, meshIndices, scratch);
        } else {
            overlayUploadsElided.increment();
        }

        if (!cache.matchesColor(red, green, blue, opacity)) {
            final Object renderer = (Object) simpleMeshEntityGetRenderer.invokeExact(entity);
            final Object material = (Object) meshRendererGetMaterial.invokeExact(renderer);
            if (material == null) {
                return false;
            }
            final Object rawColor = (Object) colorCtorFloats.invokeExact(red, green, blue, opacity);
            final Object premul = (Object) colorToPremultiplied.invokeExact(rawColor, ONE_F, ONE_F, ONE_F, ONE_F);
            materialSetColor.invokeExact(material, POINT_REF_BASE_NAME, premul);
            cache.recordColor(red, green, blue, opacity);
        }
        return true;
    }

    private static void fillCornerPositions(
            final float[] out, final float[] pos, final int col, final int row, final float fx, final float fy) {
        final int stride = col + 1;
        final int nwIdx = 0;
        final int nwS = stride * 2;
        final int nwE = 2;
        final int neIdx = col * 2;
        final int neS = (stride + col) * 2;
        final int neW = (col - 1) * 2;
        final int swIdx = (row * stride) * 2;
        final int swN = ((row - 1) * stride) * 2;
        final int swE = (row * stride + 1) * 2;
        final int seIdx = (row * stride + col) * 2;
        final int seN = ((row - 1) * stride + col) * 2;
        final int seW = (row * stride + col - 1) * 2;

        // Triangle 0: NW
        out[0] = pos[nwIdx];
        out[1] = pos[nwIdx + 1];
        out[2] = lerp(pos[nwIdx], pos[nwE], fx);
        out[3] = lerp(pos[nwIdx + 1], pos[nwE + 1], fy);
        out[4] = lerp(pos[nwIdx], pos[nwS], fx);
        out[5] = lerp(pos[nwIdx + 1], pos[nwS + 1], fy);
        // Triangle 1: NE
        out[6] = pos[neIdx];
        out[7] = pos[neIdx + 1];
        out[8] = lerp(pos[neIdx], pos[neS], fx);
        out[9] = lerp(pos[neIdx + 1], pos[neS + 1], fy);
        out[10] = lerp(pos[neIdx], pos[neW], fx);
        out[11] = lerp(pos[neIdx + 1], pos[neW + 1], fy);
        // Triangle 2: SW
        out[12] = pos[swIdx];
        out[13] = pos[swIdx + 1];
        out[14] = lerp(pos[swIdx], pos[swN], fx);
        out[15] = lerp(pos[swIdx + 1], pos[swN + 1], fy);
        out[16] = lerp(pos[swIdx], pos[swE], fx);
        out[17] = lerp(pos[swIdx + 1], pos[swE + 1], fy);
        // Triangle 3: SE
        out[18] = pos[seIdx];
        out[19] = pos[seIdx + 1];
        out[20] = lerp(pos[seIdx], pos[seW], fx);
        out[21] = lerp(pos[seIdx + 1], pos[seW + 1], fy);
        out[22] = lerp(pos[seIdx], pos[seN], fx);
        out[23] = lerp(pos[seIdx + 1], pos[seN + 1], fy);
    }

    private static float lerp(final float a, final float b, final float t) {
        return a + (b - a) * t;
    }

    private boolean drawMajorLines(
            final Object draw,
            final float[] pos,
            final int col,
            final int row,
            final Object color,
            final boolean hide) {
        if (!isOperational() || draw == null || pos == null || color == null) {
            return false;
        }
        majorLinesCalls.increment();
        try {
            final Object entity = drawLineNormalField.get(draw);
            if (entity == null) {
                return false;
            }
            if (hide) {
                entitySetEnabled.invokeExact(entity, false);
                return true;
            }
            entitySetEnabled.invokeExact(entity, true);
            return updateLineEntity(entity, pos, col, row, color, true);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    private boolean drawDetailLines(
            final Object draw,
            final float[] pos,
            final int col,
            final int row,
            final Object color,
            final boolean show) {
        if (!isOperational() || draw == null || pos == null || color == null) {
            return false;
        }
        detailLinesCalls.increment();
        try {
            final Object entity = drawLineSelectedField.get(draw);
            if (entity == null) {
                return false;
            }
            if (!show) {
                entitySetEnabled.invokeExact(entity, false);
                return true;
            }
            entitySetEnabled.invokeExact(entity, true);
            return updateLineEntity(entity, pos, col, row, color, false);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    private boolean updateLineEntity(
            final Object entity,
            final float[] pos,
            final int col,
            final int row,
            final Object color,
            final boolean majorOnly)
            throws Throwable {
        final Object renderer = (Object) simpleLinesEntityGetRenderer.invokeExact(entity);
        lineRendererSetSortingLayer.invokeExact(renderer, sortingLayerNormal);

        final int expectedIndices = majorOnly ? 4 * row + 4 * col : 2 * (col + 1) * row + 2 * col * (row + 1);
        final int step = (int) lineRendererGetStep.invokeExact(renderer);
        final float[] existingPos = (float[]) lineRendererGetPositions.invokeExact(renderer);
        final int[] existingIdx = (int[]) lineRendererGetIndices.invokeExact(renderer);

        boolean uploadedViaUpdateMeshData = false;
        if (step != STEP_2D
                || existingPos == null
                || existingPos.length != pos.length
                || existingIdx == null
                || existingIdx.length != expectedIndices) {
            lineRendererSetPositions.invokeExact(renderer, pos);
            lineRendererSetStep.invokeExact(renderer, STEP_2D);
            final int[] indices = majorOnly
                    ? buildMajorLineIndices(col, row, expectedIndices)
                    : buildDetailLineIndices(col, row, expectedIndices);
            lineRendererSetIndices.invokeExact(renderer, indices);
            lineRendererUpdateMeshData.invokeExact(renderer);
            uploadedViaUpdateMeshData = true;
        } else if (existingPos != pos) {
            lineRendererSetPositions.invokeExact(renderer, pos);
            lineRendererUpdateMeshData.invokeExact(renderer);
            uploadedViaUpdateMeshData = true;
        }

        final LineOverlayState cache = lineCache.getOrCompute(entity, LineOverlayState::new);
        if (uploadedViaUpdateMeshData || !cache.matchesPositions(pos, col, row)) {
            if (!uploadedViaUpdateMeshData) {
                floatBufferUpload.invokeExact((Object) lineRendererGetPositionsBuffer.invokeExact(renderer));
            }
            cache.recordPositions(pos, col, row);
        } else {
            overlayUploadsElided.increment();
        }

        final float r = (float) colorGetRed.invokeExact(color);
        final float g = (float) colorGetGreen.invokeExact(color);
        final float b = (float) colorGetBlue.invokeExact(color);
        final float a = (float) colorGetAlpha.invokeExact(color);
        if (!cache.matchesColor(r, g, b, a)) {
            final Object material = (Object) lineRendererGetMaterial.invokeExact(renderer);
            if (material == null) {
                return false;
            }
            final Object premul = (Object) colorToPremultiplied.invokeExact(color, ONE_F, ONE_F, ONE_F, ONE_F);
            materialSetColor.invokeExact(material, POINT_REF_BASE_NAME, premul);
            cache.recordColor(r, g, b, a);
        }
        return true;
    }

    private static int[] buildMajorLineIndices(final int col, final int row, final int len) {
        final int[] indices = new int[len];
        final int stride = col + 1;
        int idx = 0;
        for (int c = 0; c <= col; c += Math.max(col, 1)) {
            for (int r = 0; r < row; r++) {
                indices[idx++] = c + r * stride;
                indices[idx++] = c + (r + 1) * stride;
            }
            if (col == 0) {
                break;
            }
        }
        for (int r = 0; r <= row; r += Math.max(row, 1)) {
            for (int c = 0; c < col; c++) {
                indices[idx++] = c + r * stride;
                indices[idx++] = c + 1 + r * stride;
            }
            if (row == 0) {
                break;
            }
        }
        return indices;
    }

    private static int[] buildDetailLineIndices(final int col, final int row, final int len) {
        final int[] indices = new int[len];
        final int stride = col + 1;
        int idx = 0;
        for (int c = 0; c <= col; c++) {
            for (int r = 0; r < row; r++) {
                indices[idx++] = c + r * stride;
                indices[idx++] = c + (r + 1) * stride;
            }
        }
        for (int r = 0; r <= row; r++) {
            for (int c = 0; c < col; c++) {
                indices[idx++] = c + r * stride;
                indices[idx++] = c + 1 + r * stride;
            }
        }
        return indices;
    }

    private boolean shouldUploadSelectedPointBuffer(
            final Object bufferWrapper, final Object pointRenderer, final boolean isPositions) {
        if (!isOperational() || bufferWrapper == null || pointRenderer == null) {
            return true;
        }
        try {
            final float[] current = isPositions
                    ? (float[]) pointRendererGetPositions.invokeExact(pointRenderer)
                    : (float[]) pointRendererGetColors.invokeExact(pointRenderer);
            if (current == null) {
                return true;
            }
            final FloatArraySnapshot snapshot =
                    selectedBufferCache.getOrCompute(bufferWrapper, FloatArraySnapshot::new);
            if (snapshot.matchesAndUpdate(current)) {
                selectedUploadElided.increment();
                return false;
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return true;
        }
    }

    private boolean drawSoftSelectionBody(
            final Object draw,
            final Object selection,
            final Object baseColor,
            final float[] pos,
            final int col,
            final int row) {
        if (!isOperational()
                || draw == null
                || selection == null
                || baseColor == null
                || pos == null
                || col < 1
                || row < 1) {
            return false;
        }
        softSelectionCalls.increment();
        try {
            final Object entity = drawWeightMapField.get(draw);
            if (entity == null) {
                return false;
            }
            final Object transform = (Object) entityGetTransform.invokeExact(entity);
            vec3SetZ.invokeExact((Object) transformGetPosition.invokeExact(transform), ZERO_F);
            final Object renderer = (Object) simpleMeshEntityGetRenderer.invokeExact(entity);
            lineRendererSetSortingLayer_meshRendererSafe(renderer);

            final int meshPosLen = pos.length + col * row * 2;
            final int meshIdxLen = col * row * 12;
            final int colorsLen = meshPosLen * 2;
            final Object container = (Object) simpleMeshEntityGetContainer.invokeExact(entity);
            final Object mesh = (Object) meshContainerGetMesh.invokeExact(container);
            if (mesh == null) {
                return false;
            }

            final WeightMapOverlayState cache = weightMapCache.getOrCompute(entity, WeightMapOverlayState::new);
            float[] meshPositions = (float[]) meshGetPositions.invokeExact(mesh);
            int[] meshIndices = (int[]) meshGetIndices.invokeExact(mesh);
            float[] meshColors = (float[]) meshGetColors.invokeExact(mesh);
            final boolean resizedPositions = meshPositions == null || meshPositions.length != meshPosLen;
            final boolean resizedIndices = meshIndices == null || meshIndices.length != meshIdxLen;
            final boolean resizedColors = meshColors == null || meshColors.length != colorsLen;
            if (resizedPositions) {
                meshPositions = new float[meshPosLen];
                meshSetPositions.invokeExact(mesh, meshPositions);
            }
            if (resizedIndices) {
                meshIndices = new int[meshIdxLen];
                meshSetIndices.invokeExact(mesh, meshIndices);
            }
            if (resizedColors) {
                meshColors = new float[colorsLen];
                meshSetColors.invokeExact(mesh, meshColors);
            }

            if (resizedPositions
                    || resizedIndices
                    || !cache.matchesGeometry(meshPositions, meshIndices, pos, col, row)) {
                fillWeightMapGeometry(pos, col, row, meshPositions, meshIndices);
                floatBufferUpload.invokeExact((Object) meshGetPositionsBuffer.invokeExact(mesh));
                if (resizedIndices || !cache.matchesDimensions(meshIndices, col, row)) {
                    intBufferUpload.invokeExact((Object) meshGetIndicesBuffer.invokeExact(mesh));
                }
                floatBufferUpload.invokeExact((Object) meshGetUvsBuffer.invokeExact(mesh));
                cache.recordGeometry(meshPositions, meshIndices, pos, col, row);
            } else {
                overlayUploadsElided.increment();
            }

            final float baseAlpha = (float) colorGetAlpha.invokeExact(baseColor);
            final float baseRed = (float) colorGetRed.invokeExact(baseColor);
            final float baseGreen = (float) colorGetGreen.invokeExact(baseColor);
            final float baseBlue = (float) colorGetBlue.invokeExact(baseColor);
            final int gridPointCount = pos.length / 2;
            final float[] weights = collectGridPointWeights(selection, gridPointCount);

            if (resizedColors
                    || !cache.matchesColors(
                            meshColors, weights, gridPointCount, baseRed, baseGreen, baseBlue, baseAlpha)) {
                fillWeightMapColors(
                        weights,
                        gridPointCount,
                        meshPosLen / 2,
                        col,
                        baseRed,
                        baseGreen,
                        baseBlue,
                        baseAlpha,
                        meshColors);
                floatBufferUpload.invokeExact((Object) meshGetColorsBuffer.invokeExact(mesh));
                cache.recordColors(meshColors, weights, gridPointCount, baseRed, baseGreen, baseBlue, baseAlpha);
            } else {
                overlayUploadsElided.increment();
            }

            if (!cache.materialInitialized) {
                final Object material = (Object) meshRendererGetMaterial.invokeExact(renderer);
                if (material == null) {
                    return false;
                }
                materialSetColor.invokeExact(material, POINT_REF_BASE_NAME, opaqueWhiteColor);
                cache.materialInitialized = true;
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    private void lineRendererSetSortingLayer_meshRendererSafe(final Object renderer) throws Throwable {
        // MeshRenderer and LineRenderer share AGRenderer sorting-layer and order methods, bound dynamically below.
        meshRendererSetSortingLayerHandle().invokeExact(renderer, sortingLayerNormal);
        meshRendererSetOrderInLayerHandle().invokeExact(renderer, 1);
    }

    private volatile MethodHandle meshRendererSetSortingLayer;
    private volatile MethodHandle meshRendererSetOrderInLayer;

    private MethodHandle meshRendererSetSortingLayerHandle() throws ReflectiveOperationException {
        MethodHandle handle = meshRendererSetSortingLayer;
        if (handle == null) {
            final Class<?> rendererClass = HostHandles.load(hostLoader, WarpDeformEditTarget.GMESH_RENDERER_OWNER);
            handle = HostHandles.virtual(
                    rendererClass,
                    "setSortingLayer",
                    "(Lcom/live2d/graphics3d/component/a/c;)V",
                    MethodType.methodType(void.class, Object.class, Object.class));
            meshRendererSetSortingLayer = handle;
        }
        return handle;
    }

    private MethodHandle meshRendererSetOrderInLayerHandle() throws ReflectiveOperationException {
        MethodHandle handle = meshRendererSetOrderInLayer;
        if (handle == null) {
            final Class<?> rendererClass = HostHandles.load(hostLoader, WarpDeformEditTarget.GMESH_RENDERER_OWNER);
            handle = HostHandles.virtual(
                    rendererClass,
                    "setOrderInLayer",
                    SET_INT_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, int.class));
            meshRendererSetOrderInLayer = handle;
        }
        return handle;
    }

    private float[] collectGridPointWeights(final Object selection, final int gridPointCount) throws Throwable {
        float[] weights = WEIGHT_SCRATCH.get();
        if (weights == null || weights.length < gridPointCount) {
            weights = new float[gridPointCount];
            WEIGHT_SCRATCH.set(weights);
        }
        Arrays.fill(weights, 0, gridPointCount, WEIGHT_UNSET);

        final Object selector = (Object) selectionGetPointSelector.invokeExact(selection);
        final List<?> selected = (List<?>) (Object) pointSelectorGetSelectedPoints.invokeExact(selector);
        final Map<?, ?> weightMap = (Map<?, ?>) (Object) pointSelectorGetWeightMap.invokeExact(selector);
        for (int i = 0, size = selected.size(); i < size; i++) {
            final Object ref = selected.get(i);
            if (!warpPointRefClass.isInstance(ref)) {
                continue;
            }
            final int pointIndex = (int) warpPointRefIndex.invokeExact(ref);
            if (pointIndex < 0 || pointIndex >= gridPointCount || !Float.isNaN(weights[pointIndex])) {
                continue;
            }
            final Object rawWeight = weightMap.get(ref);
            weights[pointIndex] = rawWeight instanceof Float f ? f.floatValue() : ZERO_F;
        }
        return weights;
    }

    private static void fillWeightMapGeometry(
            final float[] pos, final int col, final int row, final float[] meshPositions, final int[] meshIndices) {
        final int colPlus1 = col + 1;
        int posWrite = 0;
        int idxWrite = 0;
        for (int r = 0; r < row; r++) {
            final int rowGridOffset = r * colPlus1;
            final int centersStart = posWrite + colPlus1 * 2;
            for (int c = 0; c < col; c++) {
                final int tl = rowGridOffset + c;
                final int tr = tl + 1;
                final int bl = tl + colPlus1;
                final int br = bl + 1;

                meshPositions[posWrite++] = pos[tl * 2];
                meshPositions[posWrite++] = pos[tl * 2 + 1];

                final int centerOut = centersStart + c * 2;
                meshPositions[centerOut] =
                        (pos[tl * 2] + pos[tr * 2] + pos[bl * 2] + pos[br * 2]) / WEIGHT_NEIGHBOR_DIVISOR;
                meshPositions[centerOut + 1] = (pos[tl * 2 + 1] + pos[tr * 2 + 1] + pos[bl * 2 + 1] + pos[br * 2 + 1])
                        / WEIGHT_NEIGHBOR_DIVISOR;

                final int tlMesh = r * (colPlus1 + col) + c;
                final int trMesh = tlMesh + 1;
                final int centerMesh = tlMesh + colPlus1;
                final int blMesh = centerMesh + col;
                final int brMesh = blMesh + 1;

                meshIndices[idxWrite++] = centerMesh;
                meshIndices[idxWrite++] = tlMesh;
                meshIndices[idxWrite++] = trMesh;
                meshIndices[idxWrite++] = centerMesh;
                meshIndices[idxWrite++] = trMesh;
                meshIndices[idxWrite++] = brMesh;
                meshIndices[idxWrite++] = centerMesh;
                meshIndices[idxWrite++] = brMesh;
                meshIndices[idxWrite++] = blMesh;
                meshIndices[idxWrite++] = centerMesh;
                meshIndices[idxWrite++] = blMesh;
                meshIndices[idxWrite++] = tlMesh;
            }
            final int rightEnd = (rowGridOffset + col) * 2;
            meshPositions[posWrite++] = pos[rightEnd];
            meshPositions[posWrite++] = pos[rightEnd + 1];
            posWrite += col * 2;
        }
        final int bottomRowOffset = row * colPlus1 * 2;
        System.arraycopy(pos, bottomRowOffset, meshPositions, posWrite, colPlus1 * 2);
    }

    private static void fillWeightMapColors(
            final float[] weights,
            final int gridPointCount,
            final int meshVertexCount,
            final int col,
            final float red,
            final float green,
            final float blue,
            final float baseAlpha,
            final float[] colors) {
        Arrays.fill(colors, ZERO_F);
        final int colPlus1 = col + 1;
        for (int v = 0; v < gridPointCount; v++) {
            final float raw = weights[v];
            final float alpha = (Float.isNaN(raw) || raw == ZERO_F) ? ZERO_F : raw * baseAlpha;
            final int rowIdx = v / colPlus1;
            final int colIdx = v % colPlus1;
            final int meshIdx = v + rowIdx * col;
            final int colorOff = meshIdx * COLOR_STRIDE;
            colors[colorOff] = red * alpha;
            colors[colorOff + 1] = green * alpha;
            colors[colorOff + 2] = blue * alpha;
            colors[colorOff + 3] = alpha;
            if (alpha == ZERO_F) {
                continue;
            }
            final float delta = alpha / WEIGHT_NEIGHBOR_DIVISOR;
            final int tlCenter = meshIdx - col - 1;
            final int trCenter = meshIdx - col;
            final int blCenter = meshIdx + col;
            final int brCenter = meshIdx + col + 1;
            if (colIdx == 0) {
                accumulateCenterColor(colors, trCenter, meshVertexCount, delta, red, green, blue);
                accumulateCenterColor(colors, brCenter, meshVertexCount, delta, red, green, blue);
            } else if (colIdx == col) {
                accumulateCenterColor(colors, tlCenter, meshVertexCount, delta, red, green, blue);
                accumulateCenterColor(colors, blCenter, meshVertexCount, delta, red, green, blue);
            } else {
                accumulateCenterColor(colors, tlCenter, meshVertexCount, delta, red, green, blue);
                accumulateCenterColor(colors, trCenter, meshVertexCount, delta, red, green, blue);
                accumulateCenterColor(colors, blCenter, meshVertexCount, delta, red, green, blue);
                accumulateCenterColor(colors, brCenter, meshVertexCount, delta, red, green, blue);
            }
        }
    }

    private static void accumulateCenterColor(
            final float[] colors,
            final int vertexIndex,
            final int meshVertexCount,
            final float delta,
            final float red,
            final float green,
            final float blue) {
        if (vertexIndex < 0 || vertexIndex >= meshVertexCount) {
            return;
        }
        final int base = vertexIndex * COLOR_STRIDE;
        final float nextAlpha = colors[base + 3] + delta;
        colors[base] = red * nextAlpha;
        colors[base + 1] = green * nextAlpha;
        colors[base + 2] = blue * nextAlpha;
        colors[base + 3] = nextAlpha;
    }

    private static MethodHandle resolveMaterialSetColor(final Class<?> materialClass)
            throws HostHandles.HostMemberRefused {
        try {
            final MethodHandle threeArg = HostHandles.virtual(
                    materialClass,
                    SET_COLOR_METHOD,
                    SET_COLOR_THREE_ARG_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, String.class, Object.class, Object.class));
            return MethodHandles.insertArguments(threeArg, 3, new Object[] {null});
        } catch (HostHandles.HostMemberRefused missingThreeArg) {
            return HostHandles.virtual(
                    materialClass,
                    SET_COLOR_METHOD,
                    SET_COLOR_TWO_ARG_DESCRIPTOR,
                    MethodType.methodType(void.class, Object.class, String.class, Object.class));
        }
    }

    private static final class CornerOverlayState {

        private final float[] lastPositions = new float[CORNER_POSITION_FLOATS];
        private float[] lastHostPositions;
        private int[] lastHostIndices;
        private boolean hasColor;
        private float lastR;
        private float lastG;
        private float lastB;
        private float lastA;

        private boolean matchesGeometry(final float[] hostPos, final int[] hostIdx, final float[] candidate) {
            return lastHostPositions == hostPos
                    && lastHostIndices == hostIdx
                    && Arrays.equals(lastPositions, candidate);
        }

        private void recordGeometry(final float[] hostPos, final int[] hostIdx, final float[] candidate) {
            lastHostPositions = hostPos;
            lastHostIndices = hostIdx;
            System.arraycopy(candidate, 0, lastPositions, 0, CORNER_POSITION_FLOATS);
        }

        private boolean matchesColor(final float r, final float g, final float b, final float a) {
            return hasColor
                    && Float.floatToIntBits(lastR) == Float.floatToIntBits(r)
                    && Float.floatToIntBits(lastG) == Float.floatToIntBits(g)
                    && Float.floatToIntBits(lastB) == Float.floatToIntBits(b)
                    && Float.floatToIntBits(lastA) == Float.floatToIntBits(a);
        }

        private void recordColor(final float r, final float g, final float b, final float a) {
            hasColor = true;
            lastR = r;
            lastG = g;
            lastB = b;
            lastA = a;
        }
    }

    private static final class LineOverlayState {

        private float[] lastPositions;
        private int lastCol = -1;
        private int lastRow = -1;
        private boolean hasColor;
        private float lastR;
        private float lastG;
        private float lastB;
        private float lastA;

        private boolean matchesPositions(final float[] pos, final int col, final int row) {
            return lastCol == col && lastRow == row && lastPositions != null && Arrays.equals(lastPositions, pos);
        }

        private void recordPositions(final float[] pos, final int col, final int row) {
            lastCol = col;
            lastRow = row;
            if (lastPositions == null || lastPositions.length != pos.length) {
                lastPositions = new float[pos.length];
            }
            System.arraycopy(pos, 0, lastPositions, 0, pos.length);
        }

        private boolean matchesColor(final float r, final float g, final float b, final float a) {
            return hasColor
                    && Float.floatToIntBits(lastR) == Float.floatToIntBits(r)
                    && Float.floatToIntBits(lastG) == Float.floatToIntBits(g)
                    && Float.floatToIntBits(lastB) == Float.floatToIntBits(b)
                    && Float.floatToIntBits(lastA) == Float.floatToIntBits(a);
        }

        private void recordColor(final float r, final float g, final float b, final float a) {
            hasColor = true;
            lastR = r;
            lastG = g;
            lastB = b;
            lastA = a;
        }
    }

    private static final class FloatArraySnapshot {

        private float[] snapshot;

        private boolean matchesAndUpdate(final float[] current) {
            if (snapshot != null && Arrays.equals(snapshot, current)) {
                return true;
            }
            if (snapshot == null || snapshot.length != current.length) {
                snapshot = new float[current.length];
            }
            System.arraycopy(current, 0, snapshot, 0, current.length);
            return false;
        }
    }

    private static final class WeightMapOverlayState {

        private float[] lastHostMeshPositions;
        private int[] lastHostMeshIndices;
        private float[] lastHostMeshColors;
        private float[] lastPositions;
        private int lastCol = -1;
        private int lastRow = -1;
        private float[] lastWeights;
        private int lastWeightCount = -1;
        private float lastR;
        private float lastG;
        private float lastB;
        private float lastA;
        private boolean materialInitialized;

        private boolean matchesDimensions(final int[] hostIndices, final int col, final int row) {
            return lastHostMeshIndices == hostIndices && lastCol == col && lastRow == row;
        }

        private boolean matchesGeometry(
                final float[] hostPositions, final int[] hostIndices, final float[] pos, final int col, final int row) {
            return lastHostMeshPositions == hostPositions
                    && lastHostMeshIndices == hostIndices
                    && lastCol == col
                    && lastRow == row
                    && lastPositions != null
                    && Arrays.equals(lastPositions, pos);
        }

        private void recordGeometry(
                final float[] hostPositions, final int[] hostIndices, final float[] pos, final int col, final int row) {
            lastHostMeshPositions = hostPositions;
            lastHostMeshIndices = hostIndices;
            lastCol = col;
            lastRow = row;
            if (lastPositions == null || lastPositions.length != pos.length) {
                lastPositions = new float[pos.length];
            }
            System.arraycopy(pos, 0, lastPositions, 0, pos.length);
        }

        private boolean matchesColors(
                final float[] hostColors,
                final float[] weights,
                final int weightCount,
                final float r,
                final float g,
                final float b,
                final float a) {
            if (lastHostMeshColors != hostColors
                    || lastWeights == null
                    || lastWeightCount != weightCount
                    || Float.floatToIntBits(lastR) != Float.floatToIntBits(r)
                    || Float.floatToIntBits(lastG) != Float.floatToIntBits(g)
                    || Float.floatToIntBits(lastB) != Float.floatToIntBits(b)
                    || Float.floatToIntBits(lastA) != Float.floatToIntBits(a)) {
                return false;
            }
            return Arrays.equals(lastWeights, 0, weightCount, weights, 0, weightCount);
        }

        private void recordColors(
                final float[] hostColors,
                final float[] weights,
                final int weightCount,
                final float r,
                final float g,
                final float b,
                final float a) {
            lastHostMeshColors = hostColors;
            lastWeightCount = weightCount;
            if (lastWeights == null || lastWeights.length < weightCount) {
                lastWeights = new float[weightCount];
            }
            System.arraycopy(weights, 0, lastWeights, 0, weightCount);
            lastR = r;
            lastG = g;
            lastB = b;
            lastA = a;
        }
    }
}
