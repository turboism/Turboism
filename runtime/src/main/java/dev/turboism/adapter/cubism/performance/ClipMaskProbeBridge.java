package dev.turboism.adapter.cubism.performance;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Loader-neutral bridge for the measurement-only clipping-mask probe. The woven
 * bytes consult JDK functional-interface slots in {@link System#getProperties()},
 * so no runtime type has to be visible from the host loader and no host control
 * flow is touched:
 *
 * <ul>
 *   <li>{@link #FRAME_PROPERTY} - {@code Runnable} at {@code CEViewContext.renderScene_exe}
 *   entry; closes the previous frame's per-frame counters;</li>
 *   <li>{@link #MASK_DISPATCH_ENTER_PROPERTY}/{@link #MASK_DISPATCH_EXIT_PROPERTY} -
 *   {@code LongSupplier} and {@code LongConsumer} around the render system's mask
 *   dispatch {@code a(AGRenderer, a, CRect)};</li>
 *   <li>{@link #MASK_SET_PROPERTY} - {@code Consumer<Object>} on the {@code GClipping}
 *   receiver of {@code getClippingVaildMasks()}, which the render system calls exactly
 *   once per real mask pass;</li>
 *   <li>{@link #BIND_PROPERTY} - {@code Consumer<Object>} on the {@code GRenderTexture}
 *   receiver of {@code bind(a)}, classified by the pass in flight;</li>
 *   <li>{@link #GROUP_ENTER_PROPERTY}/{@link #GROUP_EXIT_PROPERTY} -
 *   {@code LongSupplier} and {@code LongConsumer} around {@code GGroupRenderer.render};</li>
 *   <li>{@link #STATS_PROPERTY} - {@code Supplier<Map<String, Long>>} read by the
 *   workload after each leg and by {@link PerformanceProbeReportWriter}.</li>
 * </ul>
 *
 * <p>Every callback swallows its own failures and counts them, so a probe defect can
 * never change what the host renders - it only stops measuring. Nothing is elided,
 * reordered or cached.</p>
 */
public final class ClipMaskProbeBridge implements AutoCloseable {

    /** {@code Runnable} frame-boundary slot. */
    public static final String FRAME_PROPERTY = "turboism.clip-mask-probe.frame";

    /** {@code LongSupplier} mask-dispatch entry slot. */
    public static final String MASK_DISPATCH_ENTER_PROPERTY = "turboism.clip-mask-probe.mask-dispatch-enter";

    /** {@code LongConsumer} mask-dispatch exit slot. */
    public static final String MASK_DISPATCH_EXIT_PROPERTY = "turboism.clip-mask-probe.mask-dispatch-exit";

    /** {@code Consumer<Object>} mask-set slot. */
    public static final String MASK_SET_PROPERTY = "turboism.clip-mask-probe.mask-set";

    /** {@code Consumer<Object>} render-texture bind slot. */
    public static final String BIND_PROPERTY = "turboism.clip-mask-probe.bind";

    /** {@code LongSupplier} group-render entry slot. */
    public static final String GROUP_ENTER_PROPERTY = "turboism.clip-mask-probe.group-enter";

    /** {@code LongConsumer} group-render exit slot. */
    public static final String GROUP_EXIT_PROPERTY = "turboism.clip-mask-probe.group-exit";

    /** {@code Supplier<Map<String,Long>>} statistics slot. */
    public static final String STATS_PROPERTY = "turboism.clip-mask-probe.stats";

    private static volatile ClipMaskProbeBridge current;

    private final Class<?> clippingType;
    private final Method clippingMasks;
    private final Method textureWidth;
    private final Method textureHeight;
    private final ClipMaskProbeCounters counters = new ClipMaskProbeCounters();
    private final Runnable frame = this::onFrame;
    private final LongSupplier maskEnter = this::onMaskEnter;
    private final LongConsumer maskExit = this::onMaskExit;
    private final Consumer<Object> maskSet = this::onMaskSet;
    private final Consumer<Object> bind = this::onBind;
    private final LongSupplier groupEnter = this::onGroupEnter;
    private final LongConsumer groupExit = this::onGroupExit;
    private final Supplier<Map<String, Long>> statistics = counters::snapshot;
    private Properties installed;
    private volatile boolean active;

    /**
     * @param loader the admitted host class loader, used only to resolve the two
     *     reviewed read-only accessors the probe reports sizes from
     * @throws ReflectiveOperationException when a reviewed accessor is absent
     */
    public ClipMaskProbeBridge(final ClassLoader loader) throws ReflectiveOperationException {
        clippingType = Class.forName(ClipMaskProbeTargets.MASK_SET_OWNER.replace('/', '.'), false, loader);
        clippingMasks = clippingType.getMethod("getClippingMasks");
        final Class<?> texture = Class.forName(ClipMaskProbeTargets.BIND_OWNER.replace('/', '.'), false, loader);
        textureWidth = texture.getMethod("getWidth");
        textureHeight = texture.getMethod("getHeight");
    }

    /**
     * @return the installed bridge, or {@code null} when the probe is not installed
     */
    public static ClipMaskProbeBridge installed() {
        return current;
    }

    /**
     * @return the installed probe's counters, or an empty map when it is not installed
     */
    public static Map<String, Long> installedSnapshot() {
        final ClipMaskProbeBridge bridge = current;
        return bridge == null ? Map.of() : bridge.counters.snapshot();
    }

    /** Occupies every slot; refuses to replace another installation. */
    public synchronized void install() {
        if (active) throw new IllegalStateException("clip mask probe already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (String key : List.of(
                    FRAME_PROPERTY,
                    MASK_DISPATCH_ENTER_PROPERTY,
                    MASK_DISPATCH_EXIT_PROPERTY,
                    MASK_SET_PROPERTY,
                    BIND_PROPERTY,
                    GROUP_ENTER_PROPERTY,
                    GROUP_EXIT_PROPERTY,
                    STATS_PROPERTY)) {
                if (properties.containsKey(key)) throw new IllegalStateException("clip mask probe slots occupied");
            }
            try {
                properties.put(FRAME_PROPERTY, frame);
                properties.put(MASK_DISPATCH_ENTER_PROPERTY, maskEnter);
                properties.put(MASK_DISPATCH_EXIT_PROPERTY, maskExit);
                properties.put(MASK_SET_PROPERTY, maskSet);
                properties.put(BIND_PROPERTY, bind);
                properties.put(GROUP_ENTER_PROPERTY, groupEnter);
                properties.put(GROUP_EXIT_PROPERTY, groupExit);
                properties.put(STATS_PROPERTY, statistics);
            } catch (RuntimeException | Error failure) {
                removeSlots(properties);
                throw failure;
            }
            installed = properties;
            active = true;
        }
        current = this;
    }

    /** @return a reading of every counter */
    public Map<String, Long> snapshot() {
        return counters.snapshot();
    }

    @Override
    public synchronized void close() {
        if (!active) return;
        active = false;
        if (installed != null) removeSlots(installed);
        installed = null;
        if (current == this) current = null;
    }

    private void removeSlots(final Properties properties) {
        properties.remove(FRAME_PROPERTY, frame);
        properties.remove(MASK_DISPATCH_ENTER_PROPERTY, maskEnter);
        properties.remove(MASK_DISPATCH_EXIT_PROPERTY, maskExit);
        properties.remove(MASK_SET_PROPERTY, maskSet);
        properties.remove(BIND_PROPERTY, bind);
        properties.remove(GROUP_ENTER_PROPERTY, groupEnter);
        properties.remove(GROUP_EXIT_PROPERTY, groupExit);
        properties.remove(STATS_PROPERTY, statistics);
    }

    private void onFrame() {
        if (!active) return;
        try {
            counters.frame();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
        }
    }

    private long onMaskEnter() {
        if (!active) return 0L;
        try {
            counters.enterMaskDispatch();
            return 1L;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
            return 0L;
        }
    }

    private void onMaskExit(final long started) {
        if (!active || started == 0L) return;
        try {
            counters.exitMaskDispatch();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
        }
    }

    private void onMaskSet(final Object clipping) {
        if (!active) return;
        try {
            final List<?> masks = masksOf(clipping);
            counters.maskPass(digestOf(masks), masks.size());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
        }
    }

    private void onBind(final Object texture) {
        if (!active) return;
        try {
            counters.bind(
                    ((Number) textureWidth.invoke(texture)).intValue(),
                    ((Number) textureHeight.invoke(texture)).intValue());
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
        }
    }

    private long onGroupEnter() {
        if (!active) return 0L;
        try {
            counters.enterGroupRender();
            return 1L;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
            return 0L;
        }
    }

    private void onGroupExit(final long started) {
        if (!active || started == 0L) return;
        try {
            counters.exitGroupRender();
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            counters.fail();
        }
    }

    private List<?> masksOf(final Object clipping) throws ReflectiveOperationException {
        if (!clippingType.isInstance(clipping)) return List.of();
        final Object value = clippingMasks.invoke(clipping);
        return value instanceof List<?> list ? list : List.of();
    }

    /**
     * Order-sensitive digest of the mask entities' identities: two clipped renderers
     * with the same mask entities in the same order render identical mask content in
     * one frame, so an equal digest is the same-frame dedupe key.
     *
     * <p>The digest is built from {@code getClippingMasks()}, the un-trimmed list, because
     * the probe is consulted at the entry of the trimmed accessor and re-entering it would
     * recurse. {@code GRemovableClipping} trims invisible entities from a copy, so two
     * removable clippings whose lists differ only in invisible members can hash equal - a
     * bounded undercount of distinct sets, never an effect on host rendering.</p>
     */
    static long digestOf(final List<?> masks) {
        long digest = 0x9E3779B97F4A7C15L;
        for (final Object mask : masks) {
            digest = (digest ^ System.identityHashCode(mask)) * 0x100000001B3L;
        }
        return (digest * 31L) + masks.size();
    }

    /**
     * @param stats a reading from {@link #snapshot()}
     * @return the close-marker fragment for the reviewed counters
     */
    public static String report(final Map<String, Long> stats) {
        final StringBuilder text = new StringBuilder();
        for (final Map.Entry<String, Long> entry : new LinkedHashMap<>(stats).entrySet()) {
            if ("active".equals(entry.getKey())) continue;
            text.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        return text.toString();
    }
}
