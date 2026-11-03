package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
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
 * <p>The woven bytes are loader-neutral: the mask pass reports
 * {@code (renderSystem, renderer)} at entry, {@code (context)} immediately before the
 * clear, and runs restore/update callbacks after the clear and at every method exit.
 * All host objects stay inside this bridge.</p>
 */
public final class ClipMaskScissorBridge implements AutoCloseable {

    /**
     * Default-enabled production switch. The hook is installed with the clip-mask
     * optimization; this property is only a diagnostic escape hatch.
     */
    public static final String ENABLE_PROPERTY = "turboism.optimization.clipMaskScissorClear";

    public static final String BEGIN_PROPERTY = "turboism.clip-mask-scissor.begin";
    public static final String BEFORE_PROPERTY = "turboism.clip-mask-scissor.before-clear";
    public static final String AFTER_PROPERTY = "turboism.clip-mask-scissor.after-clear";
    public static final String END_PROPERTY = "turboism.clip-mask-scissor.end";
    public static final String STATS_PROPERTY = "turboism.clip-mask-scissor.stats";

    private static final int GL_SCISSOR_BOX = 0x0C10;
    private static final int GL_SCISSOR_TEST = 0x0C11;
    private static final int GL_VIEWPORT = 0x0BA2;
    private static final int MAX_TEXTURE_STATES = 128;

    private static volatile boolean configuredDefault = true;

    private final Field renderSystemLeaseField;
    private final Method contextCameraMethod;
    private final Method rendererEntityMethod;
    private final Method entityComponentsMethod;
    private final Method entityRendererMethod;
    private final Method entityTransformMethod;
    private final Method clippingValidMasksMethod;
    private final Method transformLocalToWorldMethod;
    private final Method meshContainerMethod;
    private final Method meshMethod;
    private final Method meshPositionsMethod;
    private final Method meshStepMethod;
    private final Method cameraProjectionMethod;
    private final Method cameraWorldToCameraMethod;
    private final Method matrixValuesMethod;
    private final Method leaseGlMethod;
    private final Method leaseTextureMethod;
    private final Method textureWidthMethod;
    private final Method textureHeightMethod;
    private final Method textureGlNameMethod;
    private final Method glGetIntegervMethod;
    private final Method glIsEnabledMethod;
    private final Method glEnableMethod;
    private final Method glDisableMethod;
    private final Method glScissorMethod;

    private final Class<?> clippingType;
    private final Class<?> meshRendererType;

    private final Map<Object, DirtyTexture> dirtyTextures = new IdentityHashMap<>();
    private final int[] viewport = new int[4];
    private final float[] localBounds = new float[6];
    private final float[] screenBox = new float[4];

    private final AtomicBoolean active = new AtomicBoolean();
    private final AtomicLong clearPasses = new AtomicLong();
    private final AtomicLong scissorPasses = new AtomicLong();
    private final AtomicLong fullClearPasses = new AtomicLong();
    private final AtomicLong clearedPixels = new AtomicLong();
    private final AtomicLong baselinePixels = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private final BiConsumer<Object, Object> beginConsult = this::onBegin;
    private final Consumer<Object> beforeClearConsult = this::onBeforeClear;
    private final Runnable afterClearConsult = this::onAfterClear;
    private final Runnable endConsult = this::onEnd;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    private Object currentRenderSystem;
    private Object currentRenderer;
    private PendingClear pending;
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
        renderSystemLeaseField = renderSystemType.getDeclaredField("f");
        renderSystemLeaseField.setAccessible(true);

        final Class<?> contextType = Class.forName("com.live2d.graphics3d.a", false, loader);
        contextCameraMethod = contextType.getMethod("b");

        final Class<?> rendererType = Class.forName("com.live2d.graphics3d.component.AGRenderer", false, loader);
        rendererEntityMethod = rendererType.getMethod("getEntity");

        final Class<?> entityType = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
        Method componentsGetter = null;
        for (final Method method : entityType.getMethods()) {
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
        entityComponentsMethod = componentsGetter;
        entityRendererMethod = entityType.getMethod("getRenderer");
        entityTransformMethod = entityType.getMethod("getTransform");

        clippingType = Class.forName("com.live2d.graphics3d.component.GClipping", false, loader);
        clippingValidMasksMethod = clippingType.getMethod("getClippingVaildMasks");

        final Class<?> transformType = Class.forName("com.live2d.graphics3d.component.GTransform", false, loader);
        transformLocalToWorldMethod = transformType.getMethod("getLocalToWorldMatrix");

        meshRendererType = Class.forName("com.live2d.graphics3d.component.GMeshRenderer", false, loader);
        meshContainerMethod = meshRendererType.getMethod("getMeshContainer");
        final Class<?> meshContainerType =
                Class.forName("com.live2d.graphics3d.component.GMeshContainer", false, loader);
        meshMethod = meshContainerType.getMethod("getMesh");
        final Class<?> meshType = Class.forName("com.live2d.graphics3d.mesh.a", false, loader);
        meshPositionsMethod = meshType.getMethod("getPositions");
        meshStepMethod = meshType.getMethod("getStep");

        final Class<?> cameraType = Class.forName("com.live2d.graphics3d.GCamera", false, loader);
        cameraProjectionMethod = cameraType.getMethod("getProjectionMatrix");
        cameraWorldToCameraMethod = cameraType.getMethod("getWorldToCameraMatrix");
        final Class<?> matrixType = Class.forName("com.live2d.graphics3d.type.GMatrix44", false, loader);
        matrixValuesMethod = matrixType.getMethod("a");

        final Class<?> leaseType = renderSystemLeaseField.getType();
        leaseGlMethod = leaseType.getMethod("a");
        leaseTextureMethod = leaseType.getMethod("b");
        final Class<?> textureType = Class.forName("com.live2d.graphics3d.texture.GRenderTexture", false, loader);
        textureWidthMethod = textureType.getMethod("getWidth");
        textureHeightMethod = textureType.getMethod("getHeight");
        textureGlNameMethod = textureType.getMethod("getGlTextureName");

        final Class<?> glType = Class.forName("com.jogamp.opengl.GL", false, loader);
        glGetIntegervMethod = glType.getMethod("glGetIntegerv", int.class, int[].class, int.class);
        glIsEnabledMethod = glType.getMethod("glIsEnabled", int.class);
        glEnableMethod = glType.getMethod("glEnable", int.class);
        glDisableMethod = glType.getMethod("glDisable", int.class);
        glScissorMethod = glType.getMethod("glScissor", int.class, int.class, int.class, int.class);
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
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(BEGIN_PROPERTY)
                    || properties.containsKey(BEFORE_PROPERTY)
                    || properties.containsKey(AFTER_PROPERTY)
                    || properties.containsKey(END_PROPERTY)
                    || properties.containsKey(STATS_PROPERTY)) {
                active.set(false);
                throw new IllegalStateException("clip mask scissor slots occupied");
            }
            properties.put(BEGIN_PROPERTY, beginConsult);
            properties.put(BEFORE_PROPERTY, beforeClearConsult);
            properties.put(AFTER_PROPERTY, afterClearConsult);
            properties.put(END_PROPERTY, endConsult);
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
        dirtyTextures.clear();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                properties.remove(BEGIN_PROPERTY, beginConsult);
                properties.remove(BEFORE_PROPERTY, beforeClearConsult);
                properties.remove(AFTER_PROPERTY, afterClearConsult);
                properties.remove(END_PROPERTY, endConsult);
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
        final long baseline = baselinePixels.get();
        final long cleared = clearedPixels.get();
        final long saved = Math.max(0L, baseline - cleared);
        map.put("active", active.get() ? 1L : 0L);
        map.put("enabled", enabledByPreference() ? 1L : 0L);
        map.put("clearPasses", clearPasses.get());
        map.put("scissorPasses", scissorPasses.get());
        map.put("fullClearPasses", fullClearPasses.get());
        map.put("clearedPixels", cleared);
        map.put("baselinePixels", baseline);
        map.put("savedPixels", saved);
        map.put("savedPermille", baseline == 0L ? 0L : (saved * 1000L) / baseline);
        map.put("trackedTextures", (long) dirtyTextures.size());
        map.put("failures", failures.get());
        return Map.copyOf(map);
    }

    private void onBegin(final Object renderSystem, final Object renderer) {
        if (!active.get()) return;
        if (!enabledByPreference()) {
            abortPending();
            dirtyTextures.clear();
            currentRenderSystem = null;
            currentRenderer = null;
            return;
        }
        if (pending != null) abortPending();
        currentRenderSystem = renderSystem;
        currentRenderer = renderer;
    }

    private void onBeforeClear(final Object context) {
        if (!active.get()) return;
        if (!enabledByPreference()) {
            abortPending();
            dirtyTextures.clear();
            return;
        }
        final Object renderSystem = currentRenderSystem;
        final Object renderer = currentRenderer;
        if (renderSystem == null || renderer == null || context == null) return;
        if (pending != null) abortPending();
        viewport[0] = 0;
        viewport[1] = 0;
        viewport[2] = 0;
        viewport[3] = 0;

        final PendingClear clear = new PendingClear();
        pending = clear;
        try {
            final Object lease = renderSystemLeaseField.get(renderSystem);
            if (lease == null) {
                clear.invalidate = true;
                return;
            }
            final Object gl = leaseGlMethod.invoke(lease);
            final Object texture = leaseTextureMethod.invoke(lease);
            if (gl == null || texture == null) {
                clear.invalidate = true;
                return;
            }
            final int textureWidth = ((Number) textureWidthMethod.invoke(texture)).intValue();
            final int textureHeight = ((Number) textureHeightMethod.invoke(texture)).intValue();
            final int textureGlName = ((Number) textureGlNameMethod.invoke(texture)).intValue();
            glGetIntegervMethod.invoke(gl, GL_VIEWPORT, viewport, 0);
            if (viewport[2] <= 0 || viewport[3] <= 0) {
                clear.invalidate = true;
                return;
            }

            final int[] currentRect = maskBounds(context, renderer);
            if (currentRect == null) {
                clear.invalidate = true;
                return;
            }

            final DirtyTexture previous = dirtyTextures.get(texture);
            final boolean reusable =
                    previous != null && previous.matches(gl, textureGlName, textureWidth, textureHeight);
            final int[] clearRect = new int[ClipMaskScissorGeometry.RECT_VALUES];
            if (reusable) {
                ClipMaskScissorGeometry.union(previous.rect, currentRect, clearRect);
            } else {
                ClipMaskScissorGeometry.copy(currentRect, clearRect);
            }
            final boolean full = !reusable || ClipMaskScissorGeometry.coversViewport(clearRect, viewport);

            clear.gl = gl;
            clear.texture = texture;
            clear.textureGlName = textureGlName;
            clear.textureWidth = textureWidth;
            clear.textureHeight = textureHeight;
            clear.currentRect = currentRect;
            clear.baselinePixels = (long) viewport[2] * viewport[3];
            clear.fullClear = full;

            if (!full) {
                if (!applyScissor(clear, gl, clearRect)) {
                    clear.invalidate = true;
                    return;
                }
                clear.clearPixels = ClipMaskScissorGeometry.area(clearRect);
            } else {
                clear.clearPixels = clear.baselinePixels;
                if (!disableScissorForFullClear(clear, gl)) clear.invalidate = true;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observeFailure(failure);
            clear.invalidate = true;
        }
    }

    private void onAfterClear() {
        final PendingClear completed = pending;
        if (completed == null) return;
        pending = null;
        try {
            restoreScissor(completed);
            if (completed.invalidate) {
                if (completed.texture != null) dirtyTextures.remove(completed.texture);
                countClear(0L, false);
                return;
            }
            if (completed.texture != null && completed.currentRect != null) {
                dirtyTextures.put(
                        completed.texture,
                        new DirtyTexture(
                                completed.gl,
                                completed.textureGlName,
                                completed.textureWidth,
                                completed.textureHeight,
                                completed.currentRect));
                trimStates();
            }
            countClear(completed.clearPixels, !completed.fullClear);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observeFailure(failure);
        }
    }

    private void onEnd() {
        if (pending != null) abortPending();
        currentRenderSystem = null;
        currentRenderer = null;
    }

    private void countClear(final long cleared, final boolean scissored) {
        clearPasses.incrementAndGet();
        if (scissored) {
            scissorPasses.incrementAndGet();
            clearedPixels.addAndGet(cleared);
            baselinePixels.addAndGet((long) viewport[2] * viewport[3]);
            return;
        }
        fullClearPasses.incrementAndGet();
        final long full = (long) viewport[2] * viewport[3];
        clearedPixels.addAndGet(full);
        baselinePixels.addAndGet(full);
    }

    private int[] maskBounds(final Object context, final Object renderer) throws Exception {
        final Object clipping = findClipping(renderer);
        if (clipping == null) return null;
        final Object masksResult = clippingValidMasksMethod.invoke(clipping);
        if (!(masksResult instanceof Iterable<?> masks)) return null;

        final Object camera = contextCameraMethod.invoke(context);
        if (camera == null) return null;
        final float[] projection = (float[]) matrixValuesMethod.invoke(cameraProjectionMethod.invoke(camera));
        final float[] worldToCamera = (float[]) matrixValuesMethod.invoke(cameraWorldToCameraMethod.invoke(camera));
        if (projection == null || worldToCamera == null) return null;

        final int[] union = new int[ClipMaskScissorGeometry.RECT_VALUES];
        boolean any = false;
        for (final Object mask : masks) {
            if (mask == null) continue;
            final Object maskRenderer = entityRendererMethod.invoke(mask);
            if (maskRenderer == null || !meshRendererType.isInstance(maskRenderer)) return null;
            final int[] rect = rendererBounds(maskRenderer, projection, worldToCamera);
            if (rect == null) return null;
            if (!any) {
                ClipMaskScissorGeometry.copy(rect, union);
                any = true;
            } else {
                ClipMaskScissorGeometry.union(union, rect, union);
            }
        }
        return any ? union : null;
    }

    private int[] rendererBounds(final Object renderer, final float[] projection, final float[] worldToCamera)
            throws Exception {
        final Object entity = rendererEntityMethod.invoke(renderer);
        if (entity == null) return null;
        final Object transform = entityTransformMethod.invoke(entity);
        final float[] localToWorld;
        if (transform == null) {
            localToWorld = identity();
        } else {
            final Object matrix = transformLocalToWorldMethod.invoke(transform);
            if (matrix == null) return null;
            localToWorld = (float[]) matrixValuesMethod.invoke(matrix);
        }
        if (localToWorld == null) return null;

        final Object container = meshContainerMethod.invoke(renderer);
        if (container == null) return null;
        final Object mesh = meshMethod.invoke(container);
        if (mesh == null) return null;
        final float[] positions = (float[]) meshPositionsMethod.invoke(mesh);
        final int step = ((Number) meshStepMethod.invoke(mesh)).intValue();
        if (!ClipMaskScissorGeometry.localBounds(positions, step, localBounds)) return null;
        if (!ClipMaskScissorGeometry.projectBox(
                localBounds, localToWorld, worldToCamera, projection, viewport, screenBox)) {
            return null;
        }
        final int[] rect = new int[ClipMaskScissorGeometry.RECT_VALUES];
        ClipMaskScissorGeometry.screenToRect(screenBox, rect, viewport);
        return rect;
    }

    private Object findClipping(final Object renderer) throws Exception {
        final Object entity = rendererEntityMethod.invoke(renderer);
        if (entity == null) return null;
        final Object components = entityComponentsMethod.invoke(entity);
        if (!(components instanceof Iterable<?> iterable)) return null;
        for (final Object component : iterable) {
            if (clippingType.isInstance(component)) return component;
        }
        return null;
    }

    private boolean applyScissor(final PendingClear clear, final Object gl, final int[] rect) {
        try {
            clear.previousScissorEnabled = (Boolean) glIsEnabledMethod.invoke(gl, GL_SCISSOR_TEST);
            if (clear.previousScissorEnabled) {
                glGetIntegervMethod.invoke(gl, GL_SCISSOR_BOX, clear.previousBox, 0);
            }
            glEnableMethod.invoke(gl, GL_SCISSOR_TEST);
            clear.scissorChanged = true;
            glScissorMethod.invoke(gl, rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1]);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observeFailure(failure);
            return false;
        }
    }

    private boolean disableScissorForFullClear(final PendingClear clear, final Object gl) {
        try {
            clear.previousScissorEnabled = (Boolean) glIsEnabledMethod.invoke(gl, GL_SCISSOR_TEST);
            if (!clear.previousScissorEnabled) return true;
            glGetIntegervMethod.invoke(gl, GL_SCISSOR_BOX, clear.previousBox, 0);
            glDisableMethod.invoke(gl, GL_SCISSOR_TEST);
            clear.scissorChanged = true;
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observeFailure(failure);
            return false;
        }
    }

    private void restoreScissor(final PendingClear completed) throws Exception {
        if (!completed.scissorChanged) return;
        if (completed.previousScissorEnabled) {
            glEnableMethod.invoke(completed.gl, GL_SCISSOR_TEST);
            glScissorMethod.invoke(
                    completed.gl,
                    completed.previousBox[0],
                    completed.previousBox[1],
                    completed.previousBox[2],
                    completed.previousBox[3]);
        } else {
            glDisableMethod.invoke(completed.gl, GL_SCISSOR_TEST);
        }
    }

    private void abortPending() {
        final PendingClear aborted = pending;
        pending = null;
        if (aborted == null) return;
        if (aborted.texture != null) {
            // We do not know how far the interrupted pass got; a full clear next
            // time is cheaper than trusting a dirty rectangle we could not update.
            dirtyTextures.remove(aborted.texture);
        }
        try {
            restoreScissor(aborted);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            observeFailure(failure);
        }
    }

    private void trimStates() {
        if (dirtyTextures.size() <= MAX_TEXTURE_STATES) return;
        dirtyTextures.clear();
    }

    private void observeFailure(final Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        failures.incrementAndGet();
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

    private static float[] identity() {
        return new float[] {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, 1.0f
        };
    }

    private static final class DirtyTexture {
        final Object gl;
        final int glTextureName;
        final int width;
        final int height;
        final int[] rect;

        DirtyTexture(final Object gl, final int glTextureName, final int width, final int height, final int[] rect) {
            this.gl = gl;
            this.glTextureName = glTextureName;
            this.width = width;
            this.height = height;
            this.rect = rect;
        }

        boolean matches(final Object gl, final int glTextureName, final int width, final int height) {
            return this.gl == gl && this.glTextureName == glTextureName && this.width == width && this.height == height;
        }
    }

    private static final class PendingClear {
        Object gl;
        Object texture;
        int textureGlName;
        int textureWidth;
        int textureHeight;
        int[] currentRect;
        long baselinePixels;
        long clearPixels;
        boolean fullClear;
        boolean invalidate;
        boolean scissorChanged;
        boolean previousScissorEnabled;
        final int[] previousBox = new int[4];
    }
}
