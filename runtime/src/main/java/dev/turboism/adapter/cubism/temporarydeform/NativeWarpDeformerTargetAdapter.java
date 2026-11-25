package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformContext;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Native-backed {@link TemporaryDeformContext.WarpDeformerTarget} wrapping one selected
 * {@code CWarpDeformer} for conversion and Bezier grid edits.
 */
final class NativeWarpDeformerTargetAdapter implements TemporaryDeformContext.WarpDeformerTarget {

    private static final String WARP_BEZIER_EXTENSION_CLASS =
            "com.live2d.cubism.doc.model.extension.warpBezier.CWarpDeformerBezierExtension";
    private static final String BEZIER_GRID_CLASS = "com.live2d.cubism.doc.model.deformer.warp.bezier.CBezierGrid";
    private static final String BEZIER_CACHE_KEY_CLASS = "com.live2d.cubism.doc.model.extension.warpBezier.d";
    private static final String GUID_CLASS = "com.live2d.type.Guid";
    private static final String GVECTOR2_CLASS = "com.live2d.graphics3d.type.GVector2";
    private static final String LOG_COMPONENT = "temporary-deform";

    private static final int STRIDE_2D = 2;
    private static final float FULL_WEIGHT = 1.0F;
    private static final int DEFAULT_FORM_HASH = 0;

    private final Object deformer;
    private final Object source;
    private final Object model;
    private final DeformerId deformerId;
    private final int editLevel;
    private final ClassLoader hostLoader;

    private Object actionContext;
    private List<Point2> strokeConversionBaseline;
    private Object cachedBezierGrid;
    private int bezierCols;
    private int bezierRows;
    private List<BezierPtBaseline> strokeBezierBaseline;

    NativeWarpDeformerTargetAdapter(
            final Object deformer, final Object model, final int editLevel, final ClassLoader hostLoader)
            throws ReflectiveOperationException {
        this.deformer = Objects.requireNonNull(deformer, "deformer");
        this.model = Objects.requireNonNull(model, "model");
        this.editLevel = editLevel;
        this.hostLoader = Objects.requireNonNull(hostLoader, "hostLoader");
        this.source = deformer.getClass().getMethod("getSource").invoke(deformer);
        final Object rawId = this.source.getClass().getMethod("getId").invoke(this.source);
        this.deformerId = new DeformerId(String.valueOf(rawId));
    }

    Object deformer() {
        return deformer;
    }

    Object source() {
        return source;
    }

    /** Binds the active stroke action context used to resolve the editable keyform. */
    void setActionContext(final Object actionContext) {
        this.actionContext = actionContext;
    }

    @Override
    public DeformerId id() {
        return deformerId;
    }

    @Override
    public int editLevel() {
        return editLevel;
    }

    /** Captures canvas and local control-point baselines before a drag stroke begins. */
    void snapshotStrokeBaseline() {
        try {
            strokeConversionBaseline = captureConversionCanvasPoints();
            cachedBezierGrid = resolveBezierGrid();
            if (cachedBezierGrid != null) {
                captureBezierBaseline(cachedBezierGrid);
            } else {
                strokeBezierBaseline = null;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Failed to snapshot warp baseline: " + failure.getClass().getSimpleName());
        }
    }

    /** Clears transient per-stroke baselines after pointer release. */
    void clearStrokeBaseline() {
        strokeConversionBaseline = null;
        strokeBezierBaseline = null;
    }

    @Override
    public TemporaryDeformContext.GridLayer conversionGrid() {
        try {
            final int colSubdiv =
                    ((Number) source.getClass().getMethod("getCol").invoke(source)).intValue();
            final int rowSubdiv =
                    ((Number) source.getClass().getMethod("getRow").invoke(source)).intValue();
            final int cols = Math.max(1, colSubdiv + 1);
            final int rows = Math.max(1, rowSubdiv + 1);
            final List<Point2> points = captureConversionCanvasPoints();
            if (points.size() != rows * cols) {
                throw new IllegalStateException("conversion point count mismatch");
            }
            return new TemporaryDeformContext.GridLayer(rows, cols, points);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to read conversion grid", failure);
        }
    }

    @Override
    public Optional<TemporaryDeformContext.GridLayer> bezierGrid() {
        try {
            final Object grid = cachedBezierGrid != null ? cachedBezierGrid : resolveBezierGrid();
            if (grid == null) {
                return Optional.empty();
            }
            cachedBezierGrid = grid;
            final Object[][] refs =
                    (Object[][]) grid.getClass().getMethod("getBezierPtRef").invoke(grid);
            if (refs == null || refs.length == 0 || refs[0] == null || refs[0].length == 0) {
                return Optional.empty();
            }
            final int cols = refs.length;
            final int rows = refs[0].length;
            final List<Point2> points = new ArrayList<>(cols * rows);
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    final Object pt = refs[c][r];
                    final Object anchor = pt.getClass().getMethod("getAnchor").invoke(pt);
                    points.add(new Point2(readVecX(anchor), readVecY(anchor)));
                }
            }
            return Optional.of(new TemporaryDeformContext.GridLayer(rows, cols, points));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Bezier grid lookup failed safely: " + failure.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    @Override
    public void applyConversionDeltas(final Map<Integer, Point2> deltaByPointIndex) {
        Objects.requireNonNull(deltaByPointIndex, "deltaByPointIndex");
        if (deltaByPointIndex.isEmpty()) {
            return;
        }
        try {
            final List<Point2> baseline =
                    strokeConversionBaseline != null ? strokeConversionBaseline : captureConversionCanvasPoints();
            final List<?> refs = resolveEditablePointRefExList();
            if (refs == null || refs.size() != baseline.size()) {
                return;
            }
            final Class<?> vecClass = Class.forName(GVECTOR2_CLASS, false, hostLoader);
            final Constructor<?> vecCtor = vecClass.getConstructor(float.class, float.class);
            Method moveCanvasMethod = null;
            for (final Map.Entry<Integer, Point2> entry : deltaByPointIndex.entrySet()) {
                final int index = entry.getKey();
                if (index < 0 || index >= refs.size()) {
                    continue;
                }
                final Point2 base = baseline.get(index);
                final Point2 delta = entry.getValue();
                final Object ref = refs.get(index);
                if (moveCanvasMethod == null) {
                    moveCanvasMethod = ref.getClass().getMethod("a", vecClass, float.class);
                }
                final Object targetVec = vecCtor.newInstance(base.x() + delta.x(), base.y() + delta.y());
                moveCanvasMethod.invoke(ref, targetVec, FULL_WEIGHT);
            }
            propagateModelChanges();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to apply conversion deltas", failure);
        }
    }

    @Override
    public void applyBezierDeltas(final Map<Integer, Point2> deltaByAnchorIndex) {
        Objects.requireNonNull(deltaByAnchorIndex, "deltaByAnchorIndex");
        if (deltaByAnchorIndex.isEmpty()) {
            return;
        }
        try {
            final Object grid = cachedBezierGrid != null ? cachedBezierGrid : resolveBezierGrid();
            if (grid == null) {
                return;
            }
            if (strokeBezierBaseline == null) {
                captureBezierBaseline(grid);
            }
            final Object[][] refs =
                    (Object[][]) grid.getClass().getMethod("getBezierPtRef").invoke(grid);
            if (refs == null || refs.length != bezierCols || refs[0].length != bezierRows) {
                return;
            }
            final int total = bezierCols * bezierRows;
            final Class<?> vecClass = Class.forName(GVECTOR2_CLASS, false, hostLoader);
            final Constructor<?> vecCtor = vecClass.getConstructor(float.class, float.class);

            boolean anyApplied = false;
            for (final Map.Entry<Integer, Point2> entry : deltaByAnchorIndex.entrySet()) {
                final int idx = entry.getKey();
                if (idx < 0 || idx >= total) {
                    continue;
                }
                final int r = idx / bezierCols;
                final int c = idx % bezierCols;
                final Object pt = refs[c][r];
                final BezierPtBaseline base = strokeBezierBaseline.get(idx);
                final Point2 delta = entry.getValue();
                final float dx = delta.x();
                final float dy = delta.y();

                setPointField(pt, "setAnchor", vecCtor, base.ax + dx, base.ay + dy);
                if (base.hasCn) {
                    setPointField(pt, "setCn", vecCtor, base.cnx + dx, base.cny + dy);
                }
                if (base.hasCs) {
                    setPointField(pt, "setCs", vecCtor, base.csx + dx, base.csy + dy);
                }
                if (base.hasCw) {
                    setPointField(pt, "setCw", vecCtor, base.cwx + dx, base.cwy + dy);
                }
                if (base.hasCe) {
                    setPointField(pt, "setCe", vecCtor, base.cex + dx, base.cey + dy);
                }
                anyApplied = true;
            }
            if (!anyApplied) {
                return;
            }
            grid.getClass().getMethod("setDirty").invoke(grid);
            grid.getClass().getMethod("update").invoke(grid);

            final int convCols = ((Number) source.getClass().getMethod("getCol").invoke(source)).intValue() + 1;
            final int convRows = ((Number) source.getClass().getMethod("getRow").invoke(source)).intValue() + 1;
            final float[] projectedCanvas = (float[]) grid.getClass()
                    .getMethod("createPointsOnBezier", int.class, int.class)
                    .invoke(grid, convCols, convRows);
            final List<?> convRefs = resolveEditablePointRefExList();
            if (convRefs != null && projectedCanvas != null && projectedCanvas.length >= convRefs.size() * STRIDE_2D) {
                Method moveCanvasMethod = null;
                for (int i = 0; i < convRefs.size(); i++) {
                    final Object ref = convRefs.get(i);
                    if (moveCanvasMethod == null) {
                        moveCanvasMethod = ref.getClass().getMethod("a", vecClass, float.class);
                    }
                    final Object canvasVec =
                            vecCtor.newInstance(projectedCanvas[i * STRIDE_2D], projectedCanvas[i * STRIDE_2D + 1]);
                    moveCanvasMethod.invoke(ref, canvasVec, FULL_WEIGHT);
                }
            }
            propagateModelChanges();
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to apply bezier deltas", failure);
        }
    }

    private List<Point2> captureConversionCanvasPoints() throws ReflectiveOperationException {
        final List<?> refs = resolveEditablePointRefExList();
        if (refs == null) {
            return List.of();
        }
        final List<Point2> result = new ArrayList<>(refs.size());
        Method canvasPosMethod = null;
        for (final Object ref : refs) {
            if (canvasPosMethod == null) {
                canvasPosMethod = ref.getClass().getMethod("e");
            }
            final Object vec = canvasPosMethod.invoke(ref);
            result.add(new Point2(readVecX(vec), readVecY(vec)));
        }
        return result;
    }

    private void captureBezierBaseline(final Object grid) throws ReflectiveOperationException {
        final Object[][] refs =
                (Object[][]) grid.getClass().getMethod("getBezierPtRef").invoke(grid);
        if (refs == null || refs.length == 0 || refs[0] == null || refs[0].length == 0) {
            bezierCols = 0;
            bezierRows = 0;
            strokeBezierBaseline = List.of();
            return;
        }
        bezierCols = refs.length;
        bezierRows = refs[0].length;
        final int total = bezierCols * bezierRows;
        final List<BezierPtBaseline> baselines = new ArrayList<>(total);
        for (int r = 0; r < bezierRows; r++) {
            for (int c = 0; c < bezierCols; c++) {
                final Object pt = refs[c][r];
                final Object anchor = pt.getClass().getMethod("getAnchor").invoke(pt);
                final Object cn = pt.getClass().getMethod("getCn").invoke(pt);
                final Object cs = pt.getClass().getMethod("getCs").invoke(pt);
                final Object cw = pt.getClass().getMethod("getCw").invoke(pt);
                final Object ce = pt.getClass().getMethod("getCe").invoke(pt);
                baselines.add(new BezierPtBaseline(
                        readVecX(anchor),
                        readVecY(anchor),
                        cn != null,
                        cn != null ? readVecX(cn) : 0.0F,
                        cn != null ? readVecY(cn) : 0.0F,
                        cs != null,
                        cs != null ? readVecX(cs) : 0.0F,
                        cs != null ? readVecY(cs) : 0.0F,
                        cw != null,
                        cw != null ? readVecX(cw) : 0.0F,
                        cw != null ? readVecY(cw) : 0.0F,
                        ce != null,
                        ce != null ? readVecX(ce) : 0.0F,
                        ce != null ? readVecY(ce) : 0.0F));
            }
        }
        strokeBezierBaseline = baselines;
    }

    private Object resolveBezierGrid() throws ReflectiveOperationException {
        final Object cache = ensureBezierControllerCache();
        if (cache == null) {
            return null;
        }
        for (final Method method : cache.getClass().getDeclaredMethods()) {
            if (method.getParameterCount() == 0
                    && BEZIER_GRID_CLASS.equals(method.getReturnType().getName())) {
                method.setAccessible(true);
                return method.invoke(cache);
            }
        }
        return null;
    }

    private Object ensureBezierControllerCache() throws ReflectiveOperationException {
        try {
            final Object indexed = deformer.getClass()
                    .getMethod("getBezierControllerCache", int.class)
                    .invoke(deformer, editLevel);
            if (indexed != null) {
                return indexed;
            }
        } catch (NoSuchMethodException ignored) {
        }
        final List<?> caches = (List<?>) deformer.getClass()
                .getMethod("getBezierControllerCache_forEditor")
                .invoke(deformer);
        if (caches != null) {
            for (final Object cache : caches) {
                if (cache != null) {
                    return cache;
                }
            }
        }
        final Object extension = findBezierExtension();
        if (extension == null) {
            return null;
        }
        for (final Method method : extension.getClass().getMethods()) {
            if ("setupBezier".equals(method.getName()) && method.getParameterCount() == 3) {
                final Object cacheKey = buildBezierCacheKey(extension);
                final Object created = method.invoke(extension, deformer, null, cacheKey);
                if (created != null) {
                    try {
                        deformer.getClass()
                                .getMethod("setBezierControllerCache", int.class, created.getClass())
                                .invoke(deformer, editLevel, created);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                return created;
            }
        }
        for (final Method method : extension.getClass().getMethods()) {
            if ("setupBezier".equals(method.getName()) && method.getParameterCount() == 2) {
                return method.invoke(extension, deformer, null);
            }
        }
        return null;
    }

    private Object buildBezierCacheKey(final Object extension) throws ReflectiveOperationException {
        final int bCol =
                ((Number) extension.getClass().getMethod("getBezierCol").invoke(extension)).intValue();
        final int bRow =
                ((Number) extension.getClass().getMethod("getBezierRow").invoke(extension)).intValue();
        final int col = ((Number) source.getClass().getMethod("getCol").invoke(source)).intValue();
        final int row = ((Number) source.getClass().getMethod("getRow").invoke(source)).intValue();
        final int version =
                ((Number) source.getClass().getMethod("getUpdateVersion").invoke(source)).intValue();
        final Class<?> keyClass = Class.forName(BEZIER_CACHE_KEY_CLASS, false, hostLoader);
        return keyClass.getConstructor(int.class, int.class, int.class, int.class, int.class, int.class)
                .newInstance(bCol, bRow, col, row, DEFAULT_FORM_HASH, version);
    }

    private Object findBezierExtension() throws ReflectiveOperationException {
        final List<?> extensions =
                (List<?>) source.getClass().getMethod("getExtensions").invoke(source);
        if (extensions == null) {
            return null;
        }
        Object fallback = null;
        for (final Object ext : extensions) {
            if (ext != null && WARP_BEZIER_EXTENSION_CLASS.equals(ext.getClass().getName())) {
                if (fallback == null) {
                    fallback = ext;
                }
                try {
                    final int extLevel =
                            ((Number) ext.getClass().getMethod("getEditLevel").invoke(ext)).intValue();
                    if (extLevel == editLevel) {
                        return ext;
                    }
                } catch (NoSuchMethodException ignored) {
                    return ext;
                }
            }
        }
        return fallback;
    }

    private List<?> resolveEditablePointRefExList() throws ReflectiveOperationException {
        if (actionContext != null) {
            try {
                final Object guid = source.getClass().getMethod("getGuid").invoke(source);
                if (guid != null) {
                    final Class<?> guidClass = Class.forName(GUID_CLASS, false, hostLoader);
                    final Object editableForm = actionContext
                            .getClass()
                            .getMethod("a", guidClass, boolean.class)
                            .invoke(actionContext, guid, false);
                    if (editableForm != null) {
                        final List<?> editableRefs = (List<?>) editableForm
                                .getClass()
                                .getMethod("getAllPointRefEx", model.getClass())
                                .invoke(editableForm, model);
                        if (editableRefs != null) {
                            return editableRefs;
                        }
                    }
                }
            } catch (NoSuchMethodException | ClassNotFoundException ignored) {
                // Fall back to deformer point refs when test stubs omit host keyform helpers.
            }
        }
        return (List<?>) deformer.getClass().getMethod("getAllPointRefEx").invoke(deformer);
    }

    private void propagateModelChanges() throws ReflectiveOperationException {
        try {
            model.getClass().getMethod("update").invoke(model);
        } catch (NoSuchMethodException ignored) {
            // Live Cubism updates calculated deformer forms via CECompletePack.updateParameter(false).
        }
    }

    private static void setPointField(
            final Object bezierPt, final String setterName, final Constructor<?> vecCtor, final float x, final float y)
            throws ReflectiveOperationException {
        final Object vec = vecCtor.newInstance(x, y);
        bezierPt.getClass().getMethod(setterName, vec.getClass()).invoke(bezierPt, vec);
    }

    private static float readVecX(final Object vec) throws ReflectiveOperationException {
        return ((Number) vec.getClass().getMethod("getX").invoke(vec)).floatValue();
    }

    private static float readVecY(final Object vec) throws ReflectiveOperationException {
        return ((Number) vec.getClass().getMethod("getY").invoke(vec)).floatValue();
    }

    private record BezierPtBaseline(
            float ax,
            float ay,
            boolean hasCn,
            float cnx,
            float cny,
            boolean hasCs,
            float csx,
            float csy,
            boolean hasCw,
            float cwx,
            float cwy,
            boolean hasCe,
            float cex,
            float cey) {}
}
