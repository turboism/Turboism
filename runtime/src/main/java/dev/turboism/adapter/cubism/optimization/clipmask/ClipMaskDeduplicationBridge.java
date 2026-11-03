package dev.turboism.adapter.cubism.optimization.clipmask;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
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
 *       {@code (renderSystem, renderer)} at mask-pass entry. On a dedupe hit, applies the
 *       cached mask texture and uniforms to {@code renderer.getCurMaterial()}, sets
 *       {@code renderSystem.f}, and returns {@code true} so the host skips FBO allocation,
 *       bind, clear and draws. On a miss it starts the scissor pass of
 *       {@link ClipMaskScissorBridge} and returns {@code false}: one slot carries both
 *       decisions, so a mask pass costs one property lookup at entry instead of two.</li>
 *   <li>{@link #RECORD_PROPERTY} - {@code BiConsumer<Object, Object>} receiving
 *       {@code (renderSystem, renderer)} on miss return. Records {@code renderSystem.f} and
 *       keeps it alive for later passes in the same frame, then ends the scissor pass.</li>
 *   <li>{@link #RELEASE_PROPERTY} - {@code Predicate<Object>} receiving {@code renderSystem}.
 *       In {@code endClipping}, suppresses releasing {@code renderSystem.f} to the pool
 *       mid-frame if it is held in the dedupe cache.</li>
 *   <li>{@link #FRAME_PROPERTY} - {@code Runnable} at {@code CEViewContext.renderScene_exe}
 *       exit, releasing all held mask textures to the pool and dropping every per-frame
 *       cache of both bridges.</li>
 *   <li>{@link #STATS_PROPERTY} - {@code Supplier<Map<String, Long>>} reporting hits,
 *       misses and frames.</li>
 * </ul>
 *
 * <p>Every host member is resolved once into a {@link MethodHandle} (see
 * {@link ClipMaskHandles}), and the consult itself is memoized per frame: the renderer's
 * clipping component is looked up once, and mask digests live in a primitive-keyed table so
 * no consult boxes a {@link Long}. A cached value describes exactly one rendered frame — the
 * host updates the model before it renders and does not mutate component lists during the
 * pass — and is dropped at the frame boundary, so camera pan and zoom re-resolve the current
 * pose instead of a stale one.</p>
 */
public final class ClipMaskDeduplicationBridge implements AutoCloseable {

    public static final String ENABLE_PROPERTY = "turboism.optimization.clipMaskDeduplication";
    public static final String VALIDATION_PROPERTY = "turboism.validation.clipMaskDeduplication";

    /** Slot for the {@code BiPredicate<Object, Object>} mask-pass entry consult. */
    public static final String DEDUPE_PROPERTY = "turboism.clip-mask-dedupe.consult";

    /** Slot for the {@code BiConsumer<Object, Object>} mask-pass exit consult. */
    public static final String RECORD_PROPERTY = "turboism.clip-mask-dedupe.record";

    /** Slot for the {@code Predicate<Object>} end-clipping release consult. */
    public static final String RELEASE_PROPERTY = "turboism.clip-mask-dedupe.release";

    /** Slot for the {@code Runnable} frame boundary. */
    public static final String FRAME_PROPERTY = "turboism.clip-mask-dedupe.frame";

    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.clip-mask-dedupe.stats";

    private static final int MAX_HELD_LEASES = 128;
    private static final int MAX_FRAME_RENDERERS = 4096;

    private static volatile ClipMaskDeduplicationBridge current;

    private final ClassLoader loader;

    // Resolved host members
    private final Class<?> clippingType;
    private final MethodHandle getValidMasks;
    private final MethodHandle isRemove;
    private final MethodHandle getInvert;
    private final MethodHandle getEntity;
    private final MethodHandle getComponents;
    private final MethodHandle getCurMaterial;
    private final MethodHandle setTexture;
    private final MethodHandle setVector2;
    private final MethodHandle setFloat;
    private final MethodHandle gVector2Constructor;
    private final Object zeroOffsetVector;

    private final MethodHandle getRenderTexture;
    private final MethodHandle getTextureWidth;
    private final MethodHandle getTextureHeight;

    private final ClipMaskHandles.FieldAccess renderSystemLease;
    private final Object poolInstance;
    private final MethodHandle poolRelease;

    // Per-frame caches (host rendering is single-threaded)
    private final DigestTable currentFrameMasks = new DigestTable(64);
    private final Map<Object, ResolvedClip> resolvedRenderers = new IdentityHashMap<>(64);
    private final Set<Object> heldLeases = Collections.newSetFromMap(new IdentityHashMap<>(64));

    // Metrics
    private final LongAdder frames = new LongAdder();
    private final LongAdder dedupeHits = new LongAdder();
    private final LongAdder dedupeMisses = new LongAdder();
    private final LongAdder distinctMaskSets = new LongAdder();
    private final LongAdder failures = new LongAdder();
    private final AtomicLong heldPeak = new AtomicLong();

    private final AtomicBoolean active = new AtomicBoolean();
    private Properties installedProperties;
    private ClipMaskScissorBridge scissor;

    private final BiPredicate<Object, Object> passEntry = this::onPassEntry;
    private final BiConsumer<Object, Object> passExit = this::onPassExit;
    private final Predicate<Object> releaseConsult = this::onReleaseConsult;
    private final Runnable frameBoundary = this::onFrameBoundary;
    private final Supplier<Map<String, Long>> statistics = this::snapshot;

    /**
     * One renderer's structural clip state for the current frame.
     *
     * <p>{@code remove} and the clipping component come from the entity's component list, which
     * the host does not mutate while it renders; the mask list and digest are refreshed on every
     * consult so a swapped mask set is never deduplicated against the previous one.</p>
     */
    private static final class ResolvedClip {
        final Object clipping;
        final boolean remove;
        List<?> masks;
        long digest;

        ResolvedClip(final Object clipping, final boolean remove) {
            this.clipping = clipping;
            this.remove = remove;
            this.masks = List.of();
            this.digest = 0L;
        }
    }

    private record CachedMaskEntry(Object lease, Object renderTexture, Object scale) {}

    public ClipMaskDeduplicationBridge(final ClassLoader loader, final String cubismVersion)
            throws ReflectiveOperationException {
        this.loader = Objects.requireNonNull(loader, "loader");
        final boolean isVersion53 = cubismVersion != null && cubismVersion.startsWith("5.3.");

        final String renderSystemOwner =
                ClipMaskDeduplicationTarget.renderSystemOwner(Objects.requireNonNull(cubismVersion, "cubismVersion"));
        final Class<?> renderSystemClass = Class.forName(renderSystemOwner.replace('/', '.'), false, loader);
        renderSystemLease = ClipMaskHandles.field(renderSystemClass, "f");

        clippingType = Class.forName("com.live2d.graphics3d.component.GClipping", false, loader);
        getValidMasks = ClipMaskHandles.virtual(
                clippingType, "getClippingVaildMasks", MethodType.methodType(Object.class, Object.class));
        isRemove =
                ClipMaskHandles.virtual(clippingType, "isRemove", MethodType.methodType(boolean.class, Object.class));
        getInvert =
                ClipMaskHandles.virtual(clippingType, "getInvert", MethodType.methodType(boolean.class, Object.class));

        final Class<?> rendererClass = Class.forName("com.live2d.graphics3d.component.AGRenderer", false, loader);
        getEntity =
                ClipMaskHandles.virtual(rendererClass, "getEntity", MethodType.methodType(Object.class, Object.class));
        getCurMaterial = ClipMaskHandles.virtual(
                rendererClass, "getCurMaterial", MethodType.methodType(Object.class, Object.class));

        final Class<?> entityClass = Class.forName("com.live2d.graphics3d.entity.GEntity", false, loader);
        java.lang.reflect.Method componentsGetter = null;
        for (final java.lang.reflect.Method method : entityClass.getMethods()) {
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
        getComponents = ClipMaskHandles.virtual(
                entityClass,
                componentsGetter.getName(),
                MethodType.methodType(Object.class, Object.class),
                componentsGetter.getParameterTypes());

        final Class<?> materialClass = Class.forName("com.live2d.graphics3d.material.GMaterial", false, loader);
        final Class<?> textureClass = Class.forName("com.live2d.graphics3d.texture.GTexture", false, loader);
        final Class<?> gVector2Class = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);

        setTexture = ClipMaskHandles.virtual(
                materialClass,
                "setTexture",
                MethodType.methodType(void.class, Object.class, String.class, Object.class),
                String.class,
                textureClass);
        setVector2 = materialSetter(
                materialClass,
                "setVector2",
                MethodType.methodType(void.class, Object.class, String.class, Object.class, Object.class),
                String.class,
                gVector2Class);
        setFloat = materialSetter(
                materialClass,
                "setFloat",
                MethodType.methodType(void.class, Object.class, String.class, float.class, Object.class),
                String.class,
                float.class);

        gVector2Constructor = ClipMaskHandles.construct(
                gVector2Class, MethodType.methodType(Object.class, float.class, float.class), float.class, float.class);
        zeroOffsetVector = open(gVector2Constructor, 0.0f, 0.0f);

        final Class<?> leaseClass = renderSystemLease.type();
        getRenderTexture = ClipMaskHandles.virtual(leaseClass, "b", MethodType.methodType(Object.class, Object.class));

        final Class<?> renderTextureClass =
                Class.forName("com.live2d.graphics3d.texture.GRenderTexture", false, loader);
        getTextureWidth =
                ClipMaskHandles.virtual(renderTextureClass, "getWidth", MethodType.methodType(int.class, Object.class));
        getTextureHeight = ClipMaskHandles.virtual(
                renderTextureClass, "getHeight", MethodType.methodType(int.class, Object.class));

        final Class<?> poolClass = Class.forName(
                isVersion53 ? "com.live2d.graphics3d.rendering.b" : "com.live2d.graphics3d.rendering.a", false, loader);
        try {
            poolInstance = ClipMaskHandles.field(poolClass, "a").get(null);
        } catch (Throwable failure) {
            throw new ReflectiveOperationException("mask lease pool unavailable", failure);
        }
        poolRelease = ClipMaskHandles.virtual(
                poolClass, "a", MethodType.methodType(void.class, Object.class, Object.class), leaseClass);
    }

    /**
     * Resolves a reviewed material uniform setter across the admitted host families.
     *
     * <p>Cubism 5.3 declares {@code setFloat(String, float, Integer)} and
     * {@code setVector2(String, GVector2, Integer)}; 5.0-5.2 declare the same setters without the
     * trailing render unit. Both adapt to one four-argument call shape — the missing unit is
     * dropped by the handle — so the dedupe hit path stays a single {@code invokeExact} on every
     * version instead of refusing the optimization on the older families.</p>
     */
    private static MethodHandle materialSetter(
            final Class<?> owner, final String name, final MethodType signature, final Class<?>... parameters)
            throws ReflectiveOperationException {
        final Class<?>[] withUnit = Arrays.copyOf(parameters, parameters.length + 1);
        withUnit[parameters.length] = Integer.class;
        try {
            return ClipMaskHandles.declared(owner, name, withUnit).asType(signature);
        } catch (NoSuchMethodException withoutRenderUnit) {
            final MethodHandle plain = ClipMaskHandles.declared(owner, name, parameters);
            // The render unit is appended after every declared parameter, so the older family's
            // call shape stays (receiver, name, value, ignored).
            return MethodHandles.dropArguments(plain, plain.type().parameterCount(), Object.class)
                    .asType(signature);
        }
    }

    /** Resolves the reviewed zero offset vector once, failing the bridge when the host refuses it. */
    private static Object open(final MethodHandle constructor, final float x, final float y)
            throws ReflectiveOperationException {
        try {
            return (Object) constructor.invokeExact(x, y);
        } catch (Throwable failure) {
            throw new ReflectiveOperationException("GVector2 constructor refused", failure);
        }
    }

    public static boolean enabledByPreference() {
        final String explicit = System.getProperty(ENABLE_PROPERTY);
        if (explicit != null) return Boolean.parseBoolean(explicit);
        return Boolean.getBoolean(VALIDATION_PROPERTY);
    }

    /**
     * Attaches the scissor-limit bridge so its mask-pass state follows the dedupe decision: a
     * pass begins only when the dedupe misses, and it ends on the pass' normal exit.
     *
     * @param scissorBridge installed scissor bridge, or {@code null} when it was refused
     */
    public void attachScissorBridge(final ClipMaskScissorBridge scissorBridge) {
        this.scissor = scissorBridge;
    }

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
                active.set(false);
                throw new IllegalStateException("clip mask deduplication slots occupied");
            }
            properties.put(DEDUPE_PROPERTY, passEntry);
            properties.put(RECORD_PROPERTY, passExit);
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
                installedProperties.remove(DEDUPE_PROPERTY, passEntry);
                installedProperties.remove(RECORD_PROPERTY, passExit);
                installedProperties.remove(RELEASE_PROPERTY, releaseConsult);
                installedProperties.remove(FRAME_PROPERTY, frameBoundary);
                installedProperties.remove(STATS_PROPERTY, statistics);
                installedProperties = null;
            }
        }
        onFrameBoundary();
        if (current == this) current = null;
    }

    public static ClipMaskDeduplicationBridge current() {
        return current;
    }

    public Map<String, Long> snapshot() {
        final Map<String, Long> map = new LinkedHashMap<>();
        map.put("active", active.get() ? 1L : 0L);
        map.put("frames", frames.sum());
        map.put("dedupeHits", dedupeHits.sum());
        map.put("dedupeMisses", dedupeMisses.sum());
        map.put("distinctMaskSets", distinctMaskSets.sum());
        map.put("heldPeak", heldPeak.get());
        map.put("cachedRenderers", (long) resolvedRenderers.size());
        map.put("failures", failures.sum());
        return Map.copyOf(map);
    }

    /**
     * Entry consult of the woven mask pass.
     *
     * @return {@code true} if a cached mask was applied and the mask pass should be skipped
     */
    private boolean onPassEntry(final Object renderSystem, final Object renderer) {
        if (!active.get() || renderSystem == null || renderer == null) return false;
        try {
            final ResolvedClip resolved = resolveRenderer(renderer);
            if (resolved == null || resolved.masks.isEmpty()) return false;

            final CachedMaskEntry cached = frameMask(resolved.digest);
            if (cached != null) {
                final Object curMaterial = getCurMaterial.invokeExact(renderer);
                if (curMaterial != null) {
                    applyMaterialSettings(curMaterial, resolved.clipping, cached);
                    renderSystemLease.set(renderSystem, cached.lease());
                    dedupeHits.increment();
                    return true;
                }
            }
            final ClipMaskScissorBridge scissorBridge = scissor;
            if (scissorBridge != null) scissorBridge.beganPass(renderSystem, renderer);
            return false;
        } catch (Throwable failure) {
            reportFailure("onPassEntry", failure);
            return false;
        }
    }

    /** Record consult on the mask pass' normal exit. */
    private void onPassExit(final Object renderSystem, final Object renderer) {
        final ClipMaskScissorBridge scissorBridge = scissor;
        if (scissorBridge != null) scissorBridge.endedPass();
        if (!active.get() || renderSystem == null || renderer == null) return;
        try {
            final ResolvedClip resolved = resolveRenderer(renderer);
            if (resolved == null || resolved.masks.isEmpty()) return;
            if (frameMask(resolved.digest) != null) return;
            if (heldLeases.size() >= MAX_HELD_LEASES) return;

            final Object lease = renderSystemLease.get(renderSystem);
            if (lease == null) return;
            final Object renderTexture = getRenderTexture.invokeExact(lease);
            if (renderTexture == null) return;
            final int width = (int) getTextureWidth.invokeExact(renderTexture);
            final int height = (int) getTextureHeight.invokeExact(renderTexture);
            if (width <= 0 || height <= 0) return;
            final Object scale = gVector2Constructor.invokeExact(1.0f / (float) width, 1.0f / (float) height);

            currentFrameMasks.put(resolved.digest, new CachedMaskEntry(lease, renderTexture, scale));
            heldLeases.add(lease);
            distinctMaskSets.increment();
            heldPeak.getAndUpdate(peak -> Math.max(peak, heldLeases.size()));
            dedupeMisses.increment();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
        }
    }

    /**
     * Consult in {@code endClipping}: if the lease is held in dedupe cache, suppresses
     * returning it to the pool mid-frame and only clears {@code renderSystem.f}.
     */
    private boolean onReleaseConsult(final Object renderSystem) {
        if (!active.get() || renderSystem == null) return false;
        try {
            final Object lease = renderSystemLease.get(renderSystem);
            if (lease != null && heldLeases.contains(lease)) {
                renderSystemLease.set(renderSystem, null);
                return true; // Suppress pool release!
            }
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    /**
     * Frame boundary: releases all held leases back to the pool and drops every per-frame cache.
     */
    private void onFrameBoundary() {
        final ClipMaskScissorBridge scissorBridge = scissor;
        try {
            for (final Object lease : heldLeases) {
                try {
                    poolRelease.invokeExact(poolInstance, lease);
                } catch (Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    failures.increment();
                }
            }
        } finally {
            heldLeases.clear();
            currentFrameMasks.clear();
            resolvedRenderers.clear();
            frames.increment();
            if (scissorBridge != null) scissorBridge.endedFrame();
        }
    }

    /**
     * Resolves the renderer's clipping component once per frame and refreshes its mask digest.
     *
     * <p>The mask list is re-read on every consult and compared with the cached one, so a host
     * that swaps the clipping's masks still gets a fresh digest; only the component walk and the
     * {@code isRemove} probe are memoized.</p>
     */
    private ResolvedClip resolveRenderer(final Object renderer) throws Throwable {
        ResolvedClip resolved = resolvedRenderers.get(renderer);
        if (resolved == null) {
            final Object clipping = findClipping(renderer);
            if (clipping == null) return null;
            resolved = new ResolvedClip(clipping, (boolean) isRemove.invokeExact(clipping));
            if (resolvedRenderers.size() >= MAX_FRAME_RENDERERS) resolvedRenderers.clear();
            resolvedRenderers.put(renderer, resolved);
        }
        if (resolved.remove) return null;
        final Object masksValue = getValidMasks.invokeExact(resolved.clipping);
        final List<?> masks = masksValue instanceof List<?> list ? list : List.of();
        if (!masks.equals(resolved.masks)) {
            resolved.masks = masks;
            resolved.digest = digestOf(masks);
        }
        return resolved;
    }

    private Object findClipping(final Object renderer) throws Throwable {
        final Object entity = getEntity.invokeExact(renderer);
        if (entity == null) return null;
        final Object components = getComponents.invokeExact(entity);
        if (!(components instanceof Iterable<?> iterable)) return null;
        for (final Object component : iterable) {
            if (clippingType.isInstance(component)) return component;
        }
        return null;
    }

    private void applyMaterialSettings(final Object curMaterial, final Object clipping, final CachedMaskEntry cached)
            throws Throwable {
        setTexture.invokeExact(curMaterial, "_ClippingMaskTex", cached.renderTexture());
        setTexture.invokeExact(curMaterial, "_MainTex", cached.renderTexture());
        setVector2.invokeExact(curMaterial, "clippingMaskOffset", zeroOffsetVector, (Object) null);
        setVector2.invokeExact(curMaterial, "clippingMaskScale", cached.scale(), (Object) null);
        setFloat.invokeExact(curMaterial, "clippingMaskMin", 0.0f, (Object) null);

        final boolean invert = (boolean) getInvert.invokeExact(clipping);
        setFloat.invokeExact(curMaterial, "invertClippingMask", invert ? 1.0f : 0.0f, (Object) null);
    }

    private void reportFailure(final String site, final Throwable failure) {
        FatalErrors.rethrowIfFatal(failure);
        failures.increment();
        if (failures.sum() == 1L) {
            System.err.println("[ClipMaskDedupe] Failure in " + site + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    private CachedMaskEntry frameMask(final long digest) {
        return (CachedMaskEntry) currentFrameMasks.get(digest);
    }

    static long digestOf(final List<?> masks) {
        long digest = 0x9E3779B97F4A7C15L;
        for (final Object mask : masks) {
            digest = (digest ^ System.identityHashCode(mask)) * 0x100000001B3L;
        }
        return (digest * 31L) + masks.size();
    }

    /**
     * Open-addressed {@code long -> Object} table for one frame's mask digests.
     *
     * <p>Every mask pass of a deduplicated model consults this table, so a
     * {@code HashMap<Long, ...>} would box a {@link Long} per lookup. Keys are probed linearly
     * and the table is sized for the frame's distinct mask sets.</p>
     */
    private static final class DigestTable {

        private long[] keys;
        private Object[] values;
        private boolean[] used;
        private int size;

        DigestTable(final int capacity) {
            final int table = Integer.highestOneBit(Math.max(4, capacity - 1)) * 2;
            keys = new long[table];
            values = new Object[table];
            used = new boolean[table];
        }

        Object get(final long digest) {
            final int mask = keys.length - 1;
            int slot = hash(digest) & mask;
            while (used[slot]) {
                if (keys[slot] == digest) return values[slot];
                slot = (slot + 1) & mask;
            }
            return null;
        }

        void put(final long digest, final Object value) {
            if ((size + 1) * 2 > keys.length) grow();
            final int mask = keys.length - 1;
            int slot = hash(digest) & mask;
            while (used[slot]) {
                if (keys[slot] == digest) {
                    values[slot] = value;
                    return;
                }
                slot = (slot + 1) & mask;
            }
            used[slot] = true;
            keys[slot] = digest;
            values[slot] = value;
            size++;
        }

        void clear() {
            if (size == 0) return;
            Arrays.fill(used, false);
            Arrays.fill(values, null);
            size = 0;
        }

        private void grow() {
            final long[] previousKeys = keys;
            final Object[] previousValues = values;
            final boolean[] previousUsed = used;
            keys = new long[previousKeys.length * 2];
            values = new Object[keys.length];
            used = new boolean[keys.length];
            size = 0;
            for (int index = 0; index < previousUsed.length; index++) {
                if (previousUsed[index]) put(previousKeys[index], previousValues[index]);
            }
        }

        private static int hash(final long digest) {
            return (int) (digest ^ (digest >>> 32)) * 0x9E3779B1;
        }
    }
}
