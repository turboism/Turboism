package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the zero-quality-loss clipping-mask deduplication optimization.
 *
 * <p>Inside {@code rendering/e.a(AGRenderer, a, CRect)} (and its 5.0-5.2 counterpart
 * {@code rendering/d}), the woven bytecode consults JDK functional-interface slots in
 * {@link System#getProperties()}:</p>
 *
 * <ul>
 *   <li>{@link #DEDUPE_PROPERTY} - {@code BiPredicate<Object, Object>} receiving
 *       {@code (renderSystem, renderer)}. On a dedupe hit, applies the cached mask texture
 *       and uniforms to {@code renderer.getCurMaterial()}, sets {@code renderSystem.f},
 *       and returns {@code true} so the host skips FBO allocation, bind, clear and draws.</li>
 *   <li>{@link #RECORD_PROPERTY} - {@code BiConsumer<Object, Object>} receiving
 *       {@code (renderSystem, renderer)}. On miss return, records {@code renderSystem.f}
 *       and keeps it alive for later passes in the same frame.</li>
 *   <li>{@link #RELEASE_PROPERTY} - {@code Predicate<Object>} receiving {@code renderSystem}.
 *       In {@code endClipping}, suppresses releasing {@code renderSystem.f} to the pool
 *       mid-frame if it is held in the dedupe cache.</li>
 *   <li>{@link #FRAME_PROPERTY} - {@code Runnable} at {@code CEViewContext.renderScene_exe}
 *       exit, releasing all held mask textures to the pool and clearing the cache.</li>
 *   <li>{@link #STATS_PROPERTY} - {@code Supplier<Map<String, Long>>} reporting hits,
 *       misses and frames.</li>
 * </ul>
 */
public final class ClipMaskDeduplicationBridge implements AutoCloseable {

    public static final String ENABLE_PROPERTY = "turboism.optimization.clipMaskDeduplication";
    public static final String VALIDATION_PROPERTY = "turboism.validation.clipMaskDeduplication";

    public static final String DEDUPE_PROPERTY = "turboism.clip-mask-dedupe.consult";
    public static final String RECORD_PROPERTY = "turboism.clip-mask-dedupe.record";
    public static final String RELEASE_PROPERTY = "turboism.clip-mask-dedupe.release";
    public static final String FRAME_PROPERTY = "turboism.clip-mask-dedupe.frame";
    public static final String STATS_PROPERTY = "turboism.clip-mask-dedupe.stats";

    private static final int MAX_HELD_LEASES = 128;

    private static volatile ClipMaskDeduplicationBridge current;

    private final ClassLoader loader;
    private final boolean isVersion53;

    // Reflected members
    private final Class<?> clippingType;
    private final Method getValidMasksMethod;
    private final Method isRemoveMethod;
    private final Method getInvertMethod;
    private final Method getEntityMethod;
    private final Method getComponentsMethod;
    private final Method getCurMaterialMethod;
    private final Method setTextureMethod;
    private final Method setVector2Method;
    private final Method setFloatMethod;
    private final Constructor<?> gVector2Constructor;
    private final Object zeroOffsetVector;

    private final Method getRenderTextureMethod;
    private final Method getTextureWidthMethod;
    private final Method getTextureHeightMethod;

    private final Field renderSystemFieldF;
    private final Object poolInstance;
    private final Method poolReleaseMethod;

    // Cache state (per-frame, single-thread EDT)
    private final Map<Long, CachedMaskEntry> currentFrameMasks = new HashMap<>(64);
    private final Set<Object> heldLeases = Collections.newSetFromMap(new IdentityHashMap<>(64));

    // Metrics
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong dedupeHits = new AtomicLong();
    private final AtomicLong dedupeMisses = new AtomicLong();
    private final AtomicLong distinctMaskSets = new AtomicLong();
    private final AtomicLong heldPeak = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    private final AtomicBoolean active = new AtomicBoolean();
    private Properties installedProperties;

    private final BiPredicate<Object, Object> dedupeConsult = this::onDedupeConsult;
    private final BiConsumer<Object, Object> recordConsult = this::onRecordConsult;
    private final Predicate<Object> releaseConsult = this::onReleaseConsult;
    private final Runnable frameBoundary = this::onFrameBoundary;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    record CachedMaskEntry(Object lease, Object renderTexture, Object scale) {}

    public ClipMaskDeduplicationBridge(final ClassLoader loader, final String cubismVersion)
            throws ReflectiveOperationException {
        this.loader = Objects.requireNonNull(loader, "loader");
        this.isVersion53 = cubismVersion != null && cubismVersion.startsWith("5.3.");

        final String renderSystemOwner =
                ClipMaskDeduplicationTarget.renderSystemOwner(Objects.requireNonNull(cubismVersion, "cubismVersion"));
        final Class<?> renderSystemClass = Class.forName(renderSystemOwner.replace('/', '.'), false, loader);
        renderSystemFieldF = renderSystemClass.getDeclaredField("f");
        renderSystemFieldF.setAccessible(true);

        clippingType = Class.forName("com.live2d.graphics3d.component.GClipping", false, loader);
        getValidMasksMethod = clippingType.getMethod("getClippingVaildMasks");
        isRemoveMethod = clippingType.getMethod("isRemove");
        getInvertMethod = clippingType.getMethod("getInvert");

        final Class<?> rendererClass = Class.forName("com.live2d.graphics3d.component.AGRenderer", false, loader);
        getEntityMethod = rendererClass.getMethod("getEntity");
        getCurMaterialMethod = rendererClass.getMethod("getCurMaterial");

        final Class<?> entityClass = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
        Method componentsGetter = null;
        for (final Method method : entityClass.getMethods()) {
            if (method.getName().equals("getComponents")
                    && method.getParameterCount() == 0
                    && !method.getReturnType().equals(List.class)) {
                componentsGetter = method;
                break;
            }
        }
        if (componentsGetter == null) {
            throw new NoSuchMethodException("getComponents returning non-List Components on GEntity");
        }
        getComponentsMethod = componentsGetter;

        final Class<?> materialClass = Class.forName("com.live2d.graphics3d.material.GMaterial", false, loader);
        final Class<?> textureClass = Class.forName("com.live2d.graphics3d.texture.GTexture", false, loader);
        final Class<?> gVector2Class = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);

        setTextureMethod = materialClass.getMethod("setTexture", String.class, textureClass);
        if (isVersion53) {
            setVector2Method = materialClass.getMethod("setVector2", String.class, gVector2Class, Integer.class);
            setFloatMethod = materialClass.getMethod("setFloat", String.class, float.class, Integer.class);
        } else {
            setVector2Method = materialClass.getMethod("setVector2", String.class, gVector2Class);
            setFloatMethod = materialClass.getMethod("setFloat", String.class, float.class);
        }

        gVector2Constructor = gVector2Class.getConstructor(float.class, float.class);
        zeroOffsetVector = gVector2Constructor.newInstance(0.0f, 0.0f);

        final Class<?> leaseClass = renderSystemFieldF.getType();
        getRenderTextureMethod = leaseClass.getMethod("b");

        final Class<?> renderTextureClass =
                Class.forName("com.live2d.graphics3d.texture.GRenderTexture", false, loader);
        getTextureWidthMethod = renderTextureClass.getMethod("getWidth");
        getTextureHeightMethod = renderTextureClass.getMethod("getHeight");

        if (isVersion53) {
            final Class<?> poolClass = Class.forName("com.live2d.graphics3d.rendering.b", false, loader);
            final Field poolFieldA = poolClass.getDeclaredField("a");
            poolFieldA.setAccessible(true);
            poolInstance = poolFieldA.get(null);
            poolReleaseMethod = poolClass.getMethod("a", leaseClass);
        } else {
            final Class<?> poolClass = Class.forName("com.live2d.graphics3d.rendering.a", false, loader);
            final Field poolFieldA = poolClass.getDeclaredField("a");
            poolFieldA.setAccessible(true);
            poolInstance = poolFieldA.get(null);
            poolReleaseMethod = poolClass.getMethod("a", leaseClass);
        }
    }

    private void setVector2(final Object material, final String name, final Object value)
            throws ReflectiveOperationException {
        if (isVersion53) setVector2Method.invoke(material, name, value, null);
        else setVector2Method.invoke(material, name, value);
    }

    private void setFloat(final Object material, final String name, final float value)
            throws ReflectiveOperationException {
        if (isVersion53) setFloatMethod.invoke(material, name, value, null);
        else setFloatMethod.invoke(material, name, value);
    }

    /** {@return true when either the explicit system property or the validation flag is enabled} */
    public static boolean enabledByPreference() {
        final String explicit = System.getProperty(ENABLE_PROPERTY);
        if (explicit != null) return Boolean.parseBoolean(explicit);
        return Boolean.getBoolean(VALIDATION_PROPERTY);
    }

    /** Rejects occupied slots rather than replacing another installation. */
    public synchronized void install() {
        if (!active.compareAndSet(false, true)) {
            throw new IllegalStateException("clip mask deduplication bridge already active");
        }
        final Properties properties = System.getProperties();
        synchronized (properties) {
            if (properties.containsKey(DEDUPE_PROPERTY)
                    || properties.containsKey(RECORD_PROPERTY)
                    || properties.containsKey(RELEASE_PROPERTY)
                    || properties.containsKey(FRAME_PROPERTY)) {
                throw new IllegalStateException("clip mask deduplication slots occupied");
            }
            properties.put(DEDUPE_PROPERTY, dedupeConsult);
            properties.put(RECORD_PROPERTY, recordConsult);
            properties.put(RELEASE_PROPERTY, releaseConsult);
            properties.put(FRAME_PROPERTY, frameBoundary);
            properties.put(STATS_PROPERTY, statistics);
            installedProperties = properties;
        }
        current = this;
    }

    @Override
    public synchronized void close() {
        if (!active.compareAndSet(true, false)) return;
        if (installedProperties != null) {
            synchronized (installedProperties) {
                installedProperties.remove(DEDUPE_PROPERTY, dedupeConsult);
                installedProperties.remove(RECORD_PROPERTY, recordConsult);
                installedProperties.remove(RELEASE_PROPERTY, releaseConsult);
                installedProperties.remove(FRAME_PROPERTY, frameBoundary);
                installedProperties.remove(STATS_PROPERTY, statistics);
                installedProperties = null;
            }
        }
        onFrameBoundary();
        if (current == this) current = null;
    }

    /** {@return the currently installed bridge, or {@code null}} */
    public static ClipMaskDeduplicationBridge current() {
        return current;
    }

    /** {@return a point-in-time copy of counters} */
    public Map<String, Long> snapshot() {
        final Map<String, Long> map = new LinkedHashMap<>();
        map.put("active", active.get() ? 1L : 0L);
        map.put("frames", frames.get());
        map.put("dedupeHits", dedupeHits.get());
        map.put("dedupeMisses", dedupeMisses.get());
        map.put("distinctMaskSets", distinctMaskSets.get());
        map.put("heldPeak", heldPeak.get());
        map.put("failures", failures.get());
        return Map.copyOf(map);
    }

    /**
     * Deduplication consult at the entry of {@code a(AGRenderer, a, CRect)}.
     *
     * @return {@code true} if a cached mask was applied and the mask pass should be skipped.
     */
    private boolean onDedupeConsult(final Object renderSystem, final Object renderer) {
        if (!active.get() || renderSystem == null || renderer == null) return false;
        try {
            final Object clipping = findClipping(renderer);
            if (clipping == null) return false;
            if (Boolean.TRUE.equals(isRemoveMethod.invoke(clipping))) return false;

            final List<?> masks = getValidMasks(clipping);
            if (masks.isEmpty()) return false;

            final long digest = digestOf(masks);
            final CachedMaskEntry cached = currentFrameMasks.get(digest);
            if (cached == null) return false;

            final Object curMaterial = getCurMaterialMethod.invoke(renderer);
            if (curMaterial == null) return false;

            applyMaterialSettings(curMaterial, clipping, cached);
            renderSystemFieldF.set(renderSystem, cached.lease());

            dedupeHits.incrementAndGet();
            return true;
        } catch (Throwable failure) {
            if (failures.get() == 0L) {
                System.err.println("[ClipMaskDedupe] Failure in onDedupeConsult: " + failure);
                failure.printStackTrace(System.err);
            }
            FatalErrors.rethrowIfFatal(failure);
            failures.incrementAndGet();
            return false;
        }
    }

    /**
     * Records the newly rendered mask lease on normal exit of {@code a(AGRenderer, a, CRect)}.
     */
    private void onRecordConsult(final Object renderSystem, final Object renderer) {
        if (!active.get() || renderSystem == null || renderer == null) return;
        try {
            final Object lease = renderSystemFieldF.get(renderSystem);
            if (lease == null) return;

            final Object clipping = findClipping(renderer);
            if (clipping == null) return;

            final List<?> masks = getValidMasks(clipping);
            if (masks.isEmpty()) return;

            final long digest = digestOf(masks);
            if (!currentFrameMasks.containsKey(digest)) {
                if (heldLeases.size() >= MAX_HELD_LEASES) return;

                final Object renderTexture = getRenderTextureMethod.invoke(lease);
                final int width = ((Number) getTextureWidthMethod.invoke(renderTexture)).intValue();
                final int height = ((Number) getTextureHeightMethod.invoke(renderTexture)).intValue();
                final Object scale = gVector2Constructor.newInstance(1.0f / (float) width, 1.0f / (float) height);

                final CachedMaskEntry entry = new CachedMaskEntry(lease, renderTexture, scale);
                currentFrameMasks.put(digest, entry);
                heldLeases.add(lease);
                distinctMaskSets.incrementAndGet();
                heldPeak.updateAndGet(p -> Math.max(p, heldLeases.size()));
                dedupeMisses.incrementAndGet();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.incrementAndGet();
        }
    }

    /**
     * Consult in {@code endClipping}: if the lease is held in dedupe cache, suppresses
     * returning it to the pool mid-frame and only clears {@code renderSystem.f}.
     */
    private boolean onReleaseConsult(final Object renderSystem) {
        if (!active.get() || renderSystem == null) return false;
        try {
            final Object lease = renderSystemFieldF.get(renderSystem);
            if (lease != null && heldLeases.contains(lease)) {
                renderSystemFieldF.set(renderSystem, null);
                return true; // Suppress pool release!
            }
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.incrementAndGet();
            return false;
        }
    }

    /**
     * Frame boundary: releases all held leases back to the pool and resets per-frame cache.
     */
    private void onFrameBoundary() {
        try {
            for (final Object lease : heldLeases) {
                try {
                    poolReleaseMethod.invoke(poolInstance, lease);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    failures.incrementAndGet();
                }
            }
        } finally {
            heldLeases.clear();
            currentFrameMasks.clear();
            frames.incrementAndGet();
        }
    }

    private Object findClipping(final Object renderer) throws ReflectiveOperationException {
        final Object entity = getEntityMethod.invoke(renderer);
        if (entity == null) return null;
        final Object components = getComponentsMethod.invoke(entity);
        if (!(components instanceof Iterable<?> iterable)) return null;
        for (final Object comp : iterable) {
            if (clippingType.isInstance(comp)) return comp;
        }
        return null;
    }

    private List<?> getValidMasks(final Object clipping) throws ReflectiveOperationException {
        final Object result = getValidMasksMethod.invoke(clipping);
        return result instanceof List<?> list ? list : List.of();
    }

    private void applyMaterialSettings(final Object curMaterial, final Object clipping, final CachedMaskEntry cached)
            throws ReflectiveOperationException {
        setTextureMethod.invoke(curMaterial, "_ClippingMaskTex", cached.renderTexture());
        setTextureMethod.invoke(curMaterial, "_MainTex", cached.renderTexture());
        setVector2(curMaterial, "clippingMaskOffset", zeroOffsetVector);
        setVector2(curMaterial, "clippingMaskScale", cached.scale());
        setFloat(curMaterial, "clippingMaskMin", 0.0f);

        final boolean invert = Boolean.TRUE.equals(getInvertMethod.invoke(clipping));
        setFloat(curMaterial, "invertClippingMask", invert ? 1.0f : 0.0f);
    }

    static long digestOf(final List<?> masks) {
        long digest = 0x9E3779B97F4A7C15L;
        for (final Object mask : masks) {
            digest = (digest ^ System.identityHashCode(mask)) * 0x100000001B3L;
        }
        return (digest * 31L) + masks.size();
    }
}
