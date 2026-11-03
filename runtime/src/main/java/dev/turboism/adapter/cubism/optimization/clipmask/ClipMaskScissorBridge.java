package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Scissor-limited clears for clipping-mask render textures.
 *
 * <p>The host clears each pooled grayscale mask attachment with an unscissored
 * {@code glClear(GL_COLOR_BUFFER_BIT)} before drawing the mask meshes. The mask
 * geometry usually covers a tiny fraction of the canvas, so this bridge narrows the
 * clear to the union of the mask's screen-space bounding box and whatever the same
 * pooled texture still had written by an earlier pass. The dirty rectangle is tracked
 * per {@code GRenderTexture} and invalidated when its GL name, size or context
 * changes; the first use of a texture therefore keeps the original full clear.</p>
 *
 * <p>The woven bytes stay loader-neutral: {@code rendering/e|d.a(AGRenderer, a, CRect)}
 * reports its pass through {@link ClipMaskDeduplicationBridge} before the mask clear,
 * runs the {@code Function<Object, Runnable>} slot in {@link #CLEAR_PROPERTY} right
 * before {@code glClear}, and runs the returned restore action right after it. All
 * host objects stay inside this bridge.</p>
 *
 * <p>Camera zoom and pan change the projection every frame, so this bridge keeps its
 * cost off the per-drawable path: host members are resolved once into
 * {@link MethodHandle}s (see {@link ClipMaskHandles}), the viewport is read from the
 * driver at most once per mask-texture size per frame instead of once per clear, and
 * the mask silhouettes' local bounds, their projected rectangles and the camera
 * matrices are memoized for the duration of one frame. Those memoized values are
 * camera- and pose-invariant within a single scene render — the host updates the
 * model before it renders and does not mutate mesh positions or component lists
 * during the pass — and are dropped at every frame boundary, so a pan or zoom is
 * never measured against a previous frame's geometry.</p>
 */
public final class ClipMaskScissorBridge implements AutoCloseable {

    /**
     * Default-enabled production switch. The hook is installed with the clip-mask
     * optimization; this property is only a diagnostic escape hatch.
     */
    public static final String ENABLE_PROPERTY = "turboism.optimization.clipMaskScissorClear";

    /**
     * Slot for the {@code Function<Object, Runnable>} bracketing the mask {@code glClear}:
     * the argument is the host render context, the result is the restore action to run
     * after the clear, or {@code null} when nothing was changed and nothing is owed.
     */
    public static final String CLEAR_PROPERTY = "turboism.clip-mask-scissor.clear";

    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.clip-mask-scissor.stats";

    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int MAX_TEXTURE_STATES = 128;
    private static final int MAX_FRAME_ENTRIES = 512;

    private static volatile boolean configuredDefault = true;

    private final ClipMaskHandles.FieldAccess renderSystemLease;
    private final MethodHandle contextCamera;
    private final MethodHandle rendererEntity;
    private final MethodHandle entityComponents;
    private final MethodHandle entityRenderer;
    private final MethodHandle entityTransform;
    private final MethodHandle clippingValidMasks;
    private final MethodHandle transformLocalToWorld;
    private final MethodHandle meshContainer;
    private final MethodHandle mesh;
    private final MethodHandle meshPositions;
    private final MethodHandle meshStep;
    private final MethodHandle cameraProjection;
    private final MethodHandle cameraWorldToCamera;
    private final MethodHandle matrixValues;
    private final MethodHandle leaseGl;
    private final MethodHandle leaseTexture;
    private final MethodHandle textureWidth;
    private final MethodHandle textureHeight;
    private final MethodHandle textureGlName;
    private final MethodHandle glGetIntegerv;
    private final MethodHandle glIsEnabled;
    private final MethodHandle glEnable;
    private final MethodHandle glDisable;
    private final MethodHandle glScissor;

    private final Class<?> clippingType;
    private final Class<?> meshRendererType;

    /** Per-frame memo: mask mesh -> local-space bounds. */
    private final Map<Object, float[]> localBoundsByMesh = new IdentityHashMap<>();
    /** Per-frame memo: mask entity -> screen rectangle plus the camera it was projected with. */
    private final Map<Object, ProjectedRect> rectByMaskEntity = new IdentityHashMap<>();
    /** Per-frame memo: camera -> {projection, worldToCamera} values. */
    private final Map<Object, float[][]> matricesByCamera = new IdentityHashMap<>();

    private final Map<Object, DirtyTexture> dirtyTextures = new IdentityHashMap<>();
    private final int[] viewport = new int[4];
    private final int[] clearRect = new int[ClipMaskScissorGeometry.RECT_VALUES];
    private final int[] currentRect = new int[ClipMaskScissorGeometry.RECT_VALUES];
    private final int[] projectedRect = new int[ClipMaskScissorGeometry.RECT_VALUES];
    private final float[] localBounds = new float[6];
    private final float[] screenBox = new float[4];

    private final AtomicBoolean active = new AtomicBoolean();
    private final LongAdder clearPasses = new LongAdder();
    private final LongAdder scissorPasses = new LongAdder();
    private final LongAdder fullClearPasses = new LongAdder();
    private final LongAdder clearedPixels = new LongAdder();
    private final LongAdder baselinePixels = new LongAdder();
    private final LongAdder failures = new LongAdder();

    private final Function<Object, Runnable> clearConsult = this::beforeClear;
    private final Runnable afterClearAction = this::afterClear;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private final PendingClear pending = new PendingClear();
    private Object currentRenderSystem;
    private Object currentRenderer;
    private Object viewportGl;
    private int viewportTextureWidth = -1;
    private int viewportTextureHeight = -1;
    private boolean viewportValid;
    private boolean pendingActive;
    private boolean enabled = configuredDefault;
    private Properties installedProperties;
    private boolean reportedFailure;

    /**
     * @param loader        admitted host loader
     * @param cubismVersion exact reviewed Cubism version
     * @throws ReflectiveOperationException when a reviewed host member is absent
     */
    public ClipMaskScissorBridge(final ClassLoader loader, final String cubismVersion)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        final String renderSystemOwner = ClipMaskDeduplicationTarget.renderSystemOwner(cubismVersion);
        final Class<?> renderSystemType = Class.forName(renderSystemOwner.replace('/', '.'), false, loader);
        renderSystemLease = ClipMaskHandles.field(renderSystemType, "f");

        final Class<?> contextType = Class.forName("com.live2d.graphics3d.a", false, loader);
        contextCamera = ClipMaskHandles.virtual(contextType, "b", MethodType.methodType(Object.class, Object.class));

        final Class<?> rendererType = Class.forName("com.live2d.graphics3d.component.AGRenderer", false, loader);
        rendererEntity =
                ClipMaskHandles.virtual(rendererType, "getEntity", MethodType.methodType(Object.class, Object.class));

        final Class<?> entityType = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
        java.lang.reflect.Method componentsGetter = null;
        for (final java.lang.reflect.Method method : entityType.getMethods()) {
            if (method.getName().equals("getComponents")
                    && method.getParameterCount() == 0
                    && !method.getReturnType().equals(java.util.List.class)) {
                componentsGetter = method;
                break;
            }
        }
        if (componentsGetter == null) {
            throw new NoSuchMethodException("getComponents returning non-List Components on GEntity");
        }
        entityComponents = ClipMaskHandles.virtual(componentsGetter, MethodType.methodType(Object.class, Object.class));
        entityRenderer =
                ClipMaskHandles.virtual(entityType, "getRenderer", MethodType.methodType(Object.class, Object.class));
        entityTransform =
                ClipMaskHandles.virtual(entityType, "getTransform", MethodType.methodType(Object.class, Object.class));

        clippingType = Class.forName("com.live2d.graphics3d.component.GClipping", false, loader);
        clippingValidMasks = ClipMaskHandles.virtual(
                clippingType, "getClippingVaildMasks", MethodType.methodType(Object.class, Object.class));

        final Class<?> transformType = Class.forName("com.live2d.graphics3d.component.GTransform", false, loader);
        transformLocalToWorld = ClipMaskHandles.virtual(
                transformType, "getLocalToWorldMatrix", MethodType.methodType(Object.class, Object.class));

        meshRendererType = Class.forName("com.live2d.graphics3d.component.GMeshRenderer", false, loader);
        meshContainer = ClipMaskHandles.virtual(
                meshRendererType, "getMeshContainer", MethodType.methodType(Object.class, Object.class));
        final Class<?> meshContainerType =
                Class.forName("com.live2d.graphics3d.component.GMeshContainer", false, loader);
        mesh = ClipMaskHandles.virtual(meshContainerType, "getMesh", MethodType.methodType(Object.class, Object.class));
        final Class<?> meshType = Class.forName("com.live2d.graphics3d.mesh.a", false, loader);
        meshPositions =
                ClipMaskHandles.virtual(meshType, "getPositions", MethodType.methodType(float[].class, Object.class));
        meshStep = ClipMaskHandles.virtual(meshType, "getStep", MethodType.methodType(int.class, Object.class));

        final Class<?> cameraType = Class.forName("com.live2d.graphics3d.GCamera", false, loader);
        cameraProjection = ClipMaskHandles.virtual(
                cameraType, "getProjectionMatrix", MethodType.methodType(Object.class, Object.class));
        cameraWorldToCamera = ClipMaskHandles.virtual(
                cameraType, "getWorldToCameraMatrix", MethodType.methodType(Object.class, Object.class));
        final Class<?> matrixType = Class.forName("com.live2d.graphics3d.type.GMatrix44", false, loader);
        matrixValues = ClipMaskHandles.virtual(matrixType, "a", MethodType.methodType(float[].class, Object.class));

        final Class<?> leaseType = renderSystemLease.type();
        leaseGl = ClipMaskHandles.virtual(leaseType, "a", MethodType.methodType(Object.class, Object.class));
        leaseTexture = ClipMaskHandles.virtual(leaseType, "b", MethodType.methodType(Object.class, Object.class));
        final Class<?> textureType = Class.forName("com.live2d.graphics3d.texture.GRenderTexture", false, loader);
        textureWidth = ClipMaskHandles.virtual(textureType, "getWidth", MethodType.methodType(int.class, Object.class));
        textureHeight =
                ClipMaskHandles.virtual(textureType, "getHeight", MethodType.methodType(int.class, Object.class));
        textureGlName = ClipMaskHandles.virtual(
                textureType, "getGlTextureName", MethodType.methodType(int.class, Object.class));

        final Class<?> glType = Class.forName("com.jogamp.opengl.GL", false, loader);
        glGetIntegerv = ClipMaskHandles.virtual(
                glType,
                "glGetIntegerv",
                MethodType.methodType(void.class, Object.class, int.class, int[].class, int.class),
                int.class,
                int[].class,
                int.class);
        glIsEnabled = ClipMaskHandles.virtual(
                glType, "glIsEnabled", MethodType.methodType(boolean.class, Object.class, int.class), int.class);
        glEnable = ClipMaskHandles.virtual(
                glType, "glEnable", MethodType.methodType(void.class, Object.class, int.class), int.class);
        glDisable = ClipMaskHandles.virtual(
                glType, "glDisable", MethodType.methodType(void.class, Object.class, int.class), int.class);
        glScissor = ClipMaskHandles.virtual(
                glType,
                "glScissor",
                MethodType.methodType(void.class, Object.class, int.class, int.class, int.class, int.class),
                int.class,
                int.class,
                int.class,
                int.class);
    }

    /** Sets the startup-configured default when the diagnostic property is absent. */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /** True unless the diagnostic property explicitly disables scissor clears. */
    public static boolean enabledByPreference() {
        final String value = System.getProperty(ENABLE_PROPERTY);
        return value == null ? configuredDefault : Boolean.parseBoolean(value);
    }

    /** Rejects occupied slots rather than replacing another installation. */
    public synchronized void install() {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("clip mask scissor bridge already active");
        }
        enabled = enabledByPreference();
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(CLEAR_PROPERTY) || properties.containsKey(STATS_PROPERTY)) {
                active.set(false);
                throw new IllegalStateException("clip mask scissor slots occupied");
            }
            properties.put(CLEAR_PROPERTY, clearConsult);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
        }
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) return;
        abortPending();
        currentRenderSystem = null;
        currentRenderer = null;
        dropFrameCaches();
        if (!dirtyTextures.isEmpty()) dirtyTextures.clear();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(CLEAR_PROPERTY, clearConsult);
                properties.remove(STATS_PROPERTY, statistics);
            }
        }
    }

    /** Reads the installed bridge's counters without holding a host object. */
    public static Map<String, Long> installedSnapshot() {
        final Object slot = System.getProperties().get(STATS_PROPERTY);
        if (!(slot instanceof Supplier<?> supplier)) return Map.of();
        try {
            final Object value = supplier.get();
            if (!(value instanceof Map<?, ?> map)) return Map.of();
            final java.util.LinkedHashMap<String, Long> copy = new java.util.LinkedHashMap<>();
            map.forEach((key, count) -> {
                if (key instanceof String name && count instanceof Number number) {
                    copy.put(name, number.longValue());
                }
            });
            return Map.copyOf(copy);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Map.of();
        }
    }

    /** Work and bandwidth counters; no per-frame timing is inferred from them. */
    public Map<String, Long> snapshot() {
        final Map<String, Long> map = new LinkedHashMap<>();
        final long baseline = baselinePixels.sum();
        final long cleared = clearedPixels.sum();
        final long saved = Math.max(0L, baseline - cleared);
        map.put("active", active.get() ? 1L : 0L);
        map.put("enabled", enabledByPreference() ? 1L : 0L);
        map.put("clearPasses", clearPasses.sum());
        map.put("scissorPasses", scissorPasses.sum());
        map.put("fullClearPasses", fullClearPasses.sum());
        map.put("clearedPixels", cleared);
        map.put("baselinePixels", baseline);
        map.put("savedPixels", saved);
        map.put("savedPermille", baseline == 0L ? 0L : (saved * 1000L) / baseline);
        map.put("trackedTextures", (long) dirtyTextures.size());
        map.put("cachedMeshes", (long) localBoundsByMesh.size());
        map.put("cachedRects", (long) rectByMaskEntity.size());
        map.put("failures", failures.sum());
        return Map.copyOf(map);
    }

    /**
     * Entry of the woven mask pass, called through
     * {@link ClipMaskDeduplicationBridge} on a deduplication miss.
     *
     * @param renderSystem host render system owning the pooled mask attachment
     * @param renderer     clipped renderer whose mask pass is about to run
     */
    void beganPass(final Object renderSystem, final Object renderer) {
        if (!active.get()) return;
        abortPending();
        currentRenderSystem = renderSystem;
        currentRenderer = renderer;
    }

    /** Normal exit of the woven mask pass. */
    void endedPass() {
        abortPending();
        currentRenderSystem = null;
        currentRenderer = null;
    }

    /**
     * Frame boundary: everything measured in this frame is pose- and camera-specific, so the
     * memoized silhouettes, projected rectangles, camera matrices and the cached viewport are
     * dropped here rather than trusted into a pan, zoom or model update.
     */
    void endedFrame() {
        abortPending();
        dropFrameCaches();
        enabled = enabledByPreference();
    }

    /**
     * Woven consult immediately before the mask attachment's {@code glClear}.
     *
     * @param context host render context carrying the camera
     * @return the restore action to run right after the clear, or {@code null} when the clear
     *     must stay untouched
     */
    private Runnable beforeClear(final Object context) {
        if (!active.get()) return null;
        if (!enabled) {
            abortPending();
            if (!dirtyTextures.isEmpty()) dirtyTextures.clear();
            return null;
        }
        final Object renderSystem = currentRenderSystem;
        final Object renderer = currentRenderer;
        if (renderSystem == null || renderer == null || context == null) return null;
        abortPending();
        pending.reset();
        pendingActive = true;
        try {
            prepareClear(renderSystem, renderer, context);
        } catch (Throwable failure) {
            observeFailure(failure);
            invalidatePending();
        }
        return afterClearAction;
    }

    private void prepareClear(final Object renderSystem, final Object renderer, final Object context) throws Throwable {
        final Object lease = renderSystemLease.get(renderSystem);
        if (lease == null) {
            invalidatePending();
            return;
        }
        final Object gl = leaseGl.invokeExact(lease);
        final Object texture = leaseTexture.invokeExact(lease);
        if (gl == null || texture == null) {
            invalidatePending();
            return;
        }
        final int width = (int) textureWidth.invokeExact(texture);
        final int height = (int) textureHeight.invokeExact(texture);
        final int glName = (int) textureGlName.invokeExact(texture);
        pending.gl = gl;
        pending.texture = texture;
        pending.textureGlName = glName;
        pending.textureWidth = width;
        pending.textureHeight = height;

        if (!ensureViewport(gl, width, height)) {
            invalidatePending();
            return;
        }
        pending.baselinePixels = (long) viewport[2] * viewport[3];
        final int[] bounds = maskBounds(context, renderer);
        if (bounds == null) {
            invalidatePending();
            return;
        }
        ClipMaskScissorGeometry.copy(bounds, pending.currentRect);

        final DirtyTexture previous = dirtyTextures.get(texture);
        final boolean reusable = previous != null && previous.matches(gl, glName, width, height);
        if (reusable) {
            ClipMaskScissorGeometry.union(previous.rect, bounds, clearRect);
        } else {
            ClipMaskScissorGeometry.copy(bounds, clearRect);
        }
        pending.fullClear = !reusable || ClipMaskScissorGeometry.coversViewport(clearRect, viewport);
        if (pending.fullClear) {
            pending.clearPixels = pending.baselinePixels;
            if (!disableScissorForFullClear(gl)) invalidatePending();
            return;
        }
        pending.clearPixels = ClipMaskScissorGeometry.area(clearRect);
        if (!applyScissor(gl)) invalidatePending();
    }

    private void afterClear() {
        if (!pendingActive) return;
        pendingActive = false;
        try {
            restoreScissor();
            if (pending.invalid) {
                dropDirty(pending);
                countClear(0L, false, pending.baselinePixels);
                return;
            }
            rememberDirty();
            countClear(pending.clearPixels, !pending.fullClear, pending.baselinePixels);
        } catch (Throwable failure) {
            observeFailure(failure);
        }
    }

    private void countClear(final long cleared, final boolean scissored, final long baseline) {
        clearPasses.increment();
        if (scissored) {
            scissorPasses.increment();
            clearedPixels.add(cleared);
            baselinePixels.add(baseline);
            return;
        }
        fullClearPasses.increment();
        clearedPixels.add(baseline);
        baselinePixels.add(baseline);
    }

    /**
     * Reads the driver's viewport once per mask-texture size and GL context per frame.
     *
     * <p>The rect math assumes the mask attachment's viewport matches the attachment, so a
     * different render target (or a resized one) always re-reads. The frame boundary drops the
     * cache as well, bounding any stale value to the frame it was read in.</p>
     */
    private boolean ensureViewport(final Object gl, final int width, final int height) throws Throwable {
        if (viewportValid && viewportGl == gl && viewportTextureWidth == width && viewportTextureHeight == height) {
            return true;
        }
        viewportValid = false;
        viewport[0] = 0;
        viewport[1] = 0;
        viewport[2] = 0;
        viewport[3] = 0;
        glGetIntegerv.invokeExact(gl, GL_VIEWPORT, viewport, 0);
        if (viewport[2] <= 0 || viewport[3] <= 0) {
            viewport[2] = 0;
            viewport[3] = 0;
            return false;
        }
        viewportGl = gl;
        viewportTextureWidth = width;
        viewportTextureHeight = height;
        viewportValid = true;
        return true;
    }

    /**
     * Unions the projected screen bounds of every mask of this pass.
     *
     * @return the shared {@link #currentRect} scratch, or {@code null} when any mask cannot be
     *     projected and the caller has to keep the full clear
     */
    private int[] maskBounds(final Object context, final Object renderer) throws Throwable {
        final Object clipping = clippingOf(renderer);
        if (clipping == null) return null;
        final Object masksValue = clippingValidMasks.invokeExact(clipping);
        if (!(masksValue instanceof List<?> masks) || masks.isEmpty()) return null;
        final Object camera = contextCamera.invokeExact(context);
        if (camera == null) return null;
        final float[][] matrices = cameraMatrices(camera);
        if (matrices == null) return null;

        boolean any = false;
        for (final Object mask : masks) {
            if (mask == null) return null;
            if (!projectMask(mask, camera, matrices)) return null;
            if (!any) {
                ClipMaskScissorGeometry.copy(projectedRect, currentRect);
                any = true;
            } else {
                ClipMaskScissorGeometry.union(currentRect, projectedRect, currentRect);
            }
        }
        return any ? currentRect : null;
    }

    /**
     * Projects one mask entity, reusing the rectangle already computed for it in this frame.
     *
     * <p>Writes {@link #projectedRect}. A mask silhouette is shared by every clipped renderer
     * that names it, so this memo removes the repeated vertex scan and matrix work for the
     * second and later mask sets of the same frame.</p>
     */
    private boolean projectMask(final Object mask, final Object camera, final float[][] matrices) throws Throwable {
        final Object maskRenderer = entityRenderer.invokeExact(mask);
        if (maskRenderer == null || !meshRendererType.isInstance(maskRenderer)) return false;
        final ProjectedRect memo = rectByMaskEntity.get(maskRenderer);
        if (memo != null && memo.camera == camera) {
            ClipMaskScissorGeometry.copy(memo.rect, projectedRect);
            return true;
        }
        if (!projectRenderer(maskRenderer, matrices)) return false;
        if (memo == null) {
            if (rectByMaskEntity.size() >= MAX_FRAME_ENTRIES) rectByMaskEntity.clear();
            rectByMaskEntity.put(maskRenderer, new ProjectedRect(camera, projectedRect));
        } else {
            memo.camera = camera;
            ClipMaskScissorGeometry.copy(projectedRect, memo.rect);
        }
        return true;
    }

    /** Projects one mask mesh renderer into {@link #projectedRect}. */
    private boolean projectRenderer(final Object renderer, final float[][] matrices) throws Throwable {
        final Object entity = rendererEntity.invokeExact(renderer);
        if (entity == null) return false;
        final Object transform = entityTransform.invokeExact(entity);
        final float[] localToWorld;
        if (transform == null) {
            localToWorld = IDENTITY;
        } else {
            final Object matrix = transformLocalToWorld.invokeExact(transform);
            if (matrix == null) return false;
            localToWorld = (float[]) matrixValues.invokeExact(matrix);
            if (localToWorld == null) return false;
        }

        final Object container = meshContainer.invokeExact(renderer);
        if (container == null) return false;
        final Object meshObject = mesh.invokeExact(container);
        if (meshObject == null) return false;
        final float[] bounds = localBoundsOf(meshObject);
        if (bounds == null) return false;
        if (!ClipMaskScissorGeometry.projectBox(bounds, localToWorld, matrices[1], matrices[0], viewport, screenBox)) {
            return false;
        }
        ClipMaskScissorGeometry.screenToRect(screenBox, projectedRect, viewport);
        return true;
    }

    /**
     * Local-space bounds of a mask mesh, scanned at most once per mesh per frame.
     *
     * <p>The host deforms meshes during its model update, which completes before the scene is
     * rendered, so a mesh's positions are fixed for the frame and the scan result can be reused
     * by every mask pass that draws it.</p>
     */
    private float[] localBoundsOf(final Object meshObject) throws Throwable {
        final float[] cached = localBoundsByMesh.get(meshObject);
        if (cached != null) return cached;
        final float[] positions = (float[]) meshPositions.invokeExact(meshObject);
        if (positions == null) return null;
        final int step = (int) meshStep.invokeExact(meshObject);
        if (!ClipMaskScissorGeometry.localBounds(positions, step, localBounds)) return null;
        final float[] bounds = localBounds.clone();
        if (localBoundsByMesh.size() >= MAX_FRAME_ENTRIES) localBoundsByMesh.clear();
        localBoundsByMesh.put(meshObject, bounds);
        return bounds;
    }

    private float[][] cameraMatrices(final Object camera) throws Throwable {
        final float[][] cached = matricesByCamera.get(camera);
        if (cached != null) return cached;
        final Object projection = cameraProjection.invokeExact(camera);
        final Object worldToCamera = cameraWorldToCamera.invokeExact(camera);
        if (projection == null || worldToCamera == null) return null;
        final float[] projectionValues = (float[]) matrixValues.invokeExact(projection);
        final float[] viewValues = (float[]) matrixValues.invokeExact(worldToCamera);
        if (projectionValues == null || viewValues == null) return null;
        final float[][] entry = {projectionValues, viewValues};
        if (matricesByCamera.size() >= MAX_FRAME_ENTRIES) matricesByCamera.clear();
        matricesByCamera.put(camera, entry);
        return entry;
    }

    private Object clippingOf(final Object renderer) throws Throwable {
        final Object entity = rendererEntity.invokeExact(renderer);
        if (entity == null) return null;
        final Object components = entityComponents.invokeExact(entity);
        if (!(components instanceof Iterable<?> iterable)) return null;
        for (final Object component : iterable) {
            if (clippingType.isInstance(component)) return component;
        }
        return null;
    }

    private boolean applyScissor(final Object gl) throws Throwable {
        pending.previousScissorEnabled = (boolean) glIsEnabled.invokeExact(gl, GL_SCISSOR_TEST);
        if (pending.previousScissorEnabled) glGetIntegerv.invokeExact(gl, GL_SCISSOR_BOX, pending.previousBox, 0);
        glEnable.invokeExact(gl, GL_SCISSOR_TEST);
        pending.scissorChanged = true;
        glScissor.invokeExact(gl, clearRect[0], clearRect[1], clearRect[2] - clearRect[0], clearRect[3] - clearRect[1]);
        return true;
    }

    private boolean disableScissorForFullClear(final Object gl) throws Throwable {
        pending.previousScissorEnabled = (boolean) glIsEnabled.invokeExact(gl, GL_SCISSOR_TEST);
        if (!pending.previousScissorEnabled) return true;
        glGetIntegerv.invokeExact(gl, GL_SCISSOR_BOX, pending.previousBox, 0);
        glDisable.invokeExact(gl, GL_SCISSOR_TEST);
        pending.scissorChanged = true;
        return true;
    }

    private void restoreScissor() throws Throwable {
        if (!pending.scissorChanged) return;
        final Object gl = pending.gl;
        if (pending.previousScissorEnabled) {
            glEnable.invokeExact(gl, GL_SCISSOR_TEST);
            glScissor.invokeExact(
                    gl, pending.previousBox[0], pending.previousBox[1], pending.previousBox[2], pending.previousBox[3]);
        } else {
            glDisable.invokeExact(gl, GL_SCISSOR_TEST);
        }
    }

    private void rememberDirty() {
        final Object texture = pending.texture;
        if (texture == null) return;
        final DirtyTexture previous = dirtyTextures.get(texture);
        if (previous != null
                && previous.matches(pending.gl, pending.textureGlName, pending.textureWidth, pending.textureHeight)) {
            ClipMaskScissorGeometry.copy(pending.currentRect, previous.rect);
            return;
        }
        if (dirtyTextures.size() >= MAX_TEXTURE_STATES) dirtyTextures.clear();
        dirtyTextures.put(
                texture,
                new DirtyTexture(
                        pending.gl,
                        pending.textureGlName,
                        pending.textureWidth,
                        pending.textureHeight,
                        pending.currentRect));
    }

    private void dropDirty(final PendingClear clear) {
        if (clear.texture == null) return;
        // We do not know how far the interrupted pass got; a full clear next
        // time is cheaper than trusting a dirty rectangle we could not update.
        dirtyTextures.remove(clear.texture);
    }

    private void invalidatePending() {
        pending.invalid = true;
    }

    private void abortPending() {
        if (!pendingActive) return;
        pendingActive = false;
        final boolean changed = pending.scissorChanged;
        pending.scissorChanged = false;
        dropDirty(pending);
        if (!changed) return;
        try {
            restoreScissor();
        } catch (Throwable failure) {
            observeFailure(failure);
        }
    }

    private void dropFrameCaches() {
        viewportValid = false;
        if (!localBoundsByMesh.isEmpty()) localBoundsByMesh.clear();
        if (!rectByMaskEntity.isEmpty()) rectByMaskEntity.clear();
        if (!matricesByCamera.isEmpty()) matricesByCamera.clear();
    }

    private void observeFailure(final Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        failures.increment();
        if (!reportedFailure) {
            reportedFailure = true;
            final Throwable cause = failure instanceof java.lang.reflect.InvocationTargetException invoked
                            && invoked.getTargetException() != null
                    ? invoked.getTargetException()
                    : failure;
            System.err.println("[ClipMaskScissor] Observer failure: " + cause);
            cause.printStackTrace(System.err);
        }
    }

    private static final float[] IDENTITY = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    /** One projected mask rectangle plus the camera it was projected with. */
    private static final class ProjectedRect {
        private Object camera;
        private final int[] rect = new int[ClipMaskScissorGeometry.RECT_VALUES];

        ProjectedRect(final Object camera, final int[] source) {
            this.camera = camera;
            ClipMaskScissorGeometry.copy(source, rect);
        }
    }

    private static final class DirtyTexture {
        final Object gl;
        final int glTextureName;
        final int width;
        final int height;
        final int[] rect;

        DirtyTexture(final Object gl, final int glTextureName, final int width, final int height, final int[] source) {
            this.gl = gl;
            this.glTextureName = glTextureName;
            this.width = width;
            this.height = height;
            this.rect = new int[ClipMaskScissorGeometry.RECT_VALUES];
            ClipMaskScissorGeometry.copy(source, this.rect);
        }

        boolean matches(final Object gl, final int glTextureName, final int width, final int height) {
            return this.gl == gl && this.glTextureName == glTextureName && this.width == width && this.height == height;
        }
    }

    /** Reused scratch for one mask clear, so the mask path allocates nothing per pass. */
    private static final class PendingClear {
        final int[] currentRect = new int[ClipMaskScissorGeometry.RECT_VALUES];
        final int[] previousBox = new int[4];
        Object gl;
        Object texture;
        int textureGlName;
        int textureWidth;
        int textureHeight;
        long baselinePixels;
        long clearPixels;
        boolean fullClear;
        boolean invalid;
        boolean scissorChanged;
        boolean previousScissorEnabled;

        void reset() {
            gl = null;
            texture = null;
            textureGlName = 0;
            textureWidth = 0;
            textureHeight = 0;
            baselinePixels = 0L;
            clearPixels = 0L;
            fullClear = false;
            invalid = false;
            scissorChanged = false;
            previousScissorEnabled = false;
        }
    }
}
