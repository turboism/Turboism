package dev.turboism.adapter.cubism.optimization.animation;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

/**
 * Publishes active gates, pooled evaluation contexts, and formatting caches for animation hooks.
 *
 * <p>Rewritten host classes consult {@link System#getProperties()} slots via {@code ConstantDynamic}
 * and {@link MethodHandle} invocations so no woven bytecode depends directly on Turboism types.</p>
 */
public final class AnimationAccelerationBridge implements AutoCloseable {

    /** Shared startup toggle property gating edit-mode acceleration hooks. */
    public static final String ENABLE_PROPERTY = AnimationAccelerationTarget.ENABLE_PROPERTY;

    /** Explicit validation override property isolated from launcher policy. */
    public static final String VALIDATION_PROPERTY = AnimationAccelerationTarget.VALIDATION_PROPERTY;

    /** System property slot holding the active {@link Predicate} gate for animation weaves. */
    public static final String ACTIVE_PROPERTY = "turboism.animation-acceleration.active";

    /** System property slot holding the {@link MethodHandle} acquiring a track context object. */
    public static final String ACQUIRE_TRACK_CTX_PROPERTY = "turboism.animation-acceleration.acquireTrackCtx";

    /** System property slot holding the {@link MethodHandle} releasing a track context object. */
    public static final String RELEASE_TRACK_CTX_PROPERTY = "turboism.animation-acceleration.releaseTrackCtx";

    /** System property slot holding the {@link MethodHandle} acquiring a frame-time wrapper. */
    public static final String ACQUIRE_FRAME_TIME_PROPERTY = "turboism.animation-acceleration.acquireFrameTime";

    /** System property slot holding the {@link MethodHandle} releasing a frame-time wrapper. */
    public static final String RELEASE_FRAME_TIME_PROPERTY = "turboism.animation-acceleration.releaseFrameTime";

    /** System property slot holding the {@link MethodHandle} caching {@link DecimalFormat} strings. */
    public static final String DECIMAL_FORMAT_PROPERTY = "turboism.animation-acceleration.formatDecimal";

    /** System property slot holding the {@link MethodHandle} caching sub-frame labels. */
    public static final String SUBFRAME_FORMAT_PROPERTY = "turboism.animation-acceleration.formatSubFrame";

    /** System property slot holding the {@link MethodHandle} caching timecode minute-second prefixes. */
    public static final String TIMECODE_PREFIX_PROPERTY = "turboism.animation-acceleration.formatTimecodePrefix";

    /** System property slot holding the {@link MethodHandle} caching zero-padded frame strings. */
    public static final String FRAME_PAD_PROPERTY = "turboism.animation-acceleration.formatPaddedFrame";

    /** System property slot holding the {@link MethodHandle} caching timeline ruler timecodes. */
    public static final String RULER_TIMECODE_PROPERTY = "turboism.animation-acceleration.formatRulerTimecode";

    /** System property slot holding the {@link MethodHandle} caching timeline ruler frame numbers. */
    public static final String RULER_FRAME_PROPERTY = "turboism.animation-acceleration.formatRulerFrame";

    private static final int MAX_POOL_DEPTH = 32;
    private static final int DECIMAL_CACHE_CAPACITY = 1024;
    private static final int DECIMAL_CACHE_MASK = DECIMAL_CACHE_CAPACITY - 1;
    private static final int RULER_CACHE_CAPACITY = 512;
    private static final int RULER_CACHE_MASK = RULER_CACHE_CAPACITY - 1;
    private static final int FRAME_TL_CACHE_CAPACITY = 256;
    private static final int FRAME_TL_CACHE_MASK = FRAME_TL_CACHE_CAPACITY - 1;
    private static final int HASH_MIX = 0x9E3779B9;
    private static final int HASH_SHIFT = 32;
    private static final int HASH_PRIME = 31;

    private static final int FPS_TWO_DIGIT_MIN = 10;
    private static final int FPS_THREE_DIGIT_MIN = 100;
    private static final int FPS_THREE_DIGIT_MAX = 1000;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int TIMECODE_CACHE_SECONDS = 3600;
    private static final int SUBFRAME_RAW_MIN = -256;
    private static final int SUBFRAME_RAW_MAX = 4096;
    private static final int FRAME_STATIC_MIN = -256;
    private static final int FRAME_STATIC_MAX = 4096;

    private static final String FMT_PAD2 = "%02d";
    private static final String FMT_PAD3 = "%03d";
    private static final String FMT_PAD5 = "%05d";
    private static final String FMT_PAD6 = "%06d";
    private static final String FMT_TIMECODE_POS = "%02d:%02d:";
    private static final String FMT_TIMECODE_NEG = "-%02d:%02d:";
    private static final String RULER_NEG_ZERO_PREFIX = "-0:";
    private static final String COLON = ":";

    private static final String FIELD_A = "a";
    private static final String FIELD_B = "b";
    private static final String FIELD_C = "c";
    private static final String FIELD_D = "d";
    private static final String FIELD_E = "e";
    private static final String FIELD_F = "f";
    private static final String FIELD_G = "g";
    private static final String FIELD_H = "h";
    private static final String FIELD_I = "i";
    private static final String FIELD_J = "j";

    private static final String[] PAD2_TABLE = buildFormatTable(FMT_PAD2, 0, FPS_THREE_DIGIT_MIN);
    private static final String[] PAD3_TABLE = buildFormatTable(FMT_PAD3, 0, FPS_THREE_DIGIT_MAX);
    private static final String[] SUBFRAME_RAW_TABLE = buildRawTable(SUBFRAME_RAW_MIN, SUBFRAME_RAW_MAX);
    private static final String[] TIMECODE_POS_TABLE = buildTimecodeTable(false, TIMECODE_CACHE_SECONDS);
    private static final String[] TIMECODE_NEG_TABLE = buildTimecodeTable(true, TIMECODE_CACHE_SECONDS);
    private static final String[] PAD5_TABLE = buildFormatTable(FMT_PAD5, FRAME_STATIC_MIN, FRAME_STATIC_MAX);
    private static final String[] PAD6_TABLE = buildFormatTable(FMT_PAD6, FRAME_STATIC_MIN, FRAME_STATIC_MAX);

    private static volatile boolean configuredDefault = true;

    private final AtomicBoolean active = new AtomicBoolean();
    private final Predicate<Object> activeGate = ignored -> active.get() && isEnabled();
    private final ThreadLocal<ThreadState> threadState = ThreadLocal.withInitial(ThreadState::new);
    private final MethodHandle trackCtor;
    private final MethodHandle setTrackA;
    private final MethodHandle setTrackB;
    private final MethodHandle setTrackC;
    private final MethodHandle setTrackD;
    private final MethodHandle setTrackE;
    private final MethodHandle setTrackF;
    private final MethodHandle setTrackG;
    private final MethodHandle setTrackH;
    private final MethodHandle setTrackI;
    private final MethodHandle setTrackJ;
    private final MethodHandle frameCtor;
    private final MethodHandle setFrameA;

    private final MethodHandle acquireTrackHandle;
    private final MethodHandle releaseTrackHandle;
    private final MethodHandle acquireFrameHandle;
    private final MethodHandle releaseFrameHandle;
    private final MethodHandle decimalFormatHandle;
    private final MethodHandle subframeFormatHandle;
    private final MethodHandle timecodePrefixHandle;
    private final MethodHandle framePadHandle;
    private final MethodHandle rulerTimecodeHandle;
    private final MethodHandle rulerFrameHandle;

    private Properties installedProperties;
    private Object previousActiveSlot;
    private Object previousAcquireTrackSlot;
    private Object previousReleaseTrackSlot;
    private Object previousAcquireFrameSlot;
    private Object previousReleaseFrameSlot;
    private Object previousDecimalSlot;
    private Object previousSubframeSlot;
    private Object previousTimecodePrefixSlot;
    private Object previousFramePadSlot;
    private Object previousRulerTimecodeSlot;
    private Object previousRulerFrameSlot;

    /**
     * Constructs an animation acceleration bridge bound to reviewed host context types.
     *
     * @param hostLoader class loader supplying Cubism animation classes
     * @param target reviewed target metadata defining context class names
     */
    public AnimationAccelerationBridge(final ClassLoader hostLoader, final AnimationAccelerationTarget target) {
        Objects.requireNonNull(hostLoader, "hostLoader");
        Objects.requireNonNull(target, "target");
        try {
            final MethodHandles.Lookup caller = MethodHandles.lookup();
            final Class<?> trackType = HostHandles.load(hostLoader, target.trackContextOwner());
            final MethodHandles.Lookup trackLookup = MethodHandles.privateLookupIn(trackType, caller);
            trackCtor = trackLookup
                    .findConstructor(trackType, MethodType.methodType(void.class, boolean.class))
                    .asType(MethodType.methodType(Object.class, boolean.class));
            setTrackA = bindSetter(trackLookup, trackType, FIELD_A, boolean.class);
            setTrackB = bindSetter(trackLookup, trackType, FIELD_B, int.class);
            setTrackC = bindSetter(trackLookup, trackType, FIELD_C, boolean.class);
            setTrackD = bindSetter(trackLookup, trackType, FIELD_D, boolean.class);
            setTrackE = bindSetter(trackLookup, trackType, FIELD_E, Double.class);
            setTrackF = bindSetter(trackLookup, trackType, FIELD_F, boolean.class);
            setTrackG = bindSetter(trackLookup, trackType, FIELD_G, boolean.class);
            setTrackH = bindOptionalObjectSetter(trackLookup, trackType, FIELD_H);
            setTrackI = bindOptionalBooleanSetter(trackLookup, trackType, FIELD_I);
            setTrackJ = bindOptionalBooleanSetter(trackLookup, trackType, FIELD_J);

            final Class<?> frameType = HostHandles.load(hostLoader, AnimationAccelerationTarget.FRAME_TIME_OWNER);
            final MethodHandles.Lookup frameLookup = MethodHandles.privateLookupIn(frameType, caller);
            frameCtor = frameLookup
                    .findConstructor(frameType, MethodType.methodType(void.class, int.class))
                    .asType(MethodType.methodType(Object.class, int.class));
            setFrameA = bindSetter(frameLookup, frameType, FIELD_A, int.class);

            acquireTrackHandle = caller.bind(this, "acquireTrackContext", MethodType.methodType(Object.class));
            releaseTrackHandle =
                    caller.bind(this, "releaseTrackContext", MethodType.methodType(void.class, Object.class));
            acquireFrameHandle = caller.bind(this, "acquireFrameTime", MethodType.methodType(Object.class, int.class));
            releaseFrameHandle = caller.bind(this, "releaseFrameTime", MethodType.methodType(void.class, Object.class));
            decimalFormatHandle = caller.bind(
                    this, "formatDecimal", MethodType.methodType(String.class, DecimalFormat.class, double.class));
            subframeFormatHandle = caller.bind(
                    this, "formatSubFrame", MethodType.methodType(String.class, int.class, int.class, boolean.class));
            timecodePrefixHandle = caller.bind(
                    this,
                    "formatTimecode",
                    MethodType.methodType(String.class, int.class, boolean.class, String.class));
            framePadHandle = caller.bind(
                    this, "formatPaddedFrame", MethodType.methodType(String.class, int.class, boolean.class));
            rulerTimecodeHandle = caller.bind(
                    this,
                    "formatRulerTimecode",
                    MethodType.methodType(String.class, int.class, int.class, boolean.class, String.class));
            rulerFrameHandle = caller.bind(this, "formatRulerFrame", MethodType.methodType(String.class, int.class));
        } catch (final ReflectiveOperationException failure) {
            throw new IllegalStateException("failed to bind animation acceleration bridge handles", failure);
        }
    }

    /**
     * Records the policy-derived default activation state evaluated when system overrides are unset.
     *
     * @param enabled policy toggle state for the current launch
     */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /**
     * Reports whether animation acceleration should run under current system properties.
     *
     * @return {@code true} when active override or policy default enables acceleration
     */
    public static boolean isEnabled() {
        final String validation = System.getProperty(VALIDATION_PROPERTY);
        if (validation != null) {
            return Boolean.parseBoolean(validation);
        }
        final String property = System.getProperty(ENABLE_PROPERTY);
        return property == null ? configuredDefault : Boolean.parseBoolean(property);
    }

    /**
     * Publishes the active gate and formatting or pooling handles into {@link System#getProperties()}.
     */
    public synchronized void install() {
        final Properties properties = System.getProperties();
        if (installedProperties != null) {
            return;
        }
        previousActiveSlot = properties.get(ACTIVE_PROPERTY);
        previousAcquireTrackSlot = properties.get(ACQUIRE_TRACK_CTX_PROPERTY);
        previousReleaseTrackSlot = properties.get(RELEASE_TRACK_CTX_PROPERTY);
        previousAcquireFrameSlot = properties.get(ACQUIRE_FRAME_TIME_PROPERTY);
        previousReleaseFrameSlot = properties.get(RELEASE_FRAME_TIME_PROPERTY);
        previousDecimalSlot = properties.get(DECIMAL_FORMAT_PROPERTY);
        previousSubframeSlot = properties.get(SUBFRAME_FORMAT_PROPERTY);
        previousTimecodePrefixSlot = properties.get(TIMECODE_PREFIX_PROPERTY);
        previousFramePadSlot = properties.get(FRAME_PAD_PROPERTY);
        previousRulerTimecodeSlot = properties.get(RULER_TIMECODE_PROPERTY);
        previousRulerFrameSlot = properties.get(RULER_FRAME_PROPERTY);

        active.set(true);
        properties.put(ACTIVE_PROPERTY, activeGate);
        properties.put(ACQUIRE_TRACK_CTX_PROPERTY, acquireTrackHandle);
        properties.put(RELEASE_TRACK_CTX_PROPERTY, releaseTrackHandle);
        properties.put(ACQUIRE_FRAME_TIME_PROPERTY, acquireFrameHandle);
        properties.put(RELEASE_FRAME_TIME_PROPERTY, releaseFrameHandle);
        properties.put(DECIMAL_FORMAT_PROPERTY, decimalFormatHandle);
        properties.put(SUBFRAME_FORMAT_PROPERTY, subframeFormatHandle);
        properties.put(TIMECODE_PREFIX_PROPERTY, timecodePrefixHandle);
        properties.put(FRAME_PAD_PROPERTY, framePadHandle);
        properties.put(RULER_TIMECODE_PROPERTY, rulerTimecodeHandle);
        properties.put(RULER_FRAME_PROPERTY, rulerFrameHandle);
        installedProperties = properties;
    }

    @Override
    public synchronized void close() {
        active.set(false);
        final Properties properties = installedProperties;
        if (properties == null) {
            return;
        }
        installedProperties = null;
        try {
            restoreSlot(properties, ACTIVE_PROPERTY, activeGate, previousActiveSlot);
            restoreSlot(properties, ACQUIRE_TRACK_CTX_PROPERTY, acquireTrackHandle, previousAcquireTrackSlot);
            restoreSlot(properties, RELEASE_TRACK_CTX_PROPERTY, releaseTrackHandle, previousReleaseTrackSlot);
            restoreSlot(properties, ACQUIRE_FRAME_TIME_PROPERTY, acquireFrameHandle, previousAcquireFrameSlot);
            restoreSlot(properties, RELEASE_FRAME_TIME_PROPERTY, releaseFrameHandle, previousReleaseFrameSlot);
            restoreSlot(properties, DECIMAL_FORMAT_PROPERTY, decimalFormatHandle, previousDecimalSlot);
            restoreSlot(properties, SUBFRAME_FORMAT_PROPERTY, subframeFormatHandle, previousSubframeSlot);
            restoreSlot(properties, TIMECODE_PREFIX_PROPERTY, timecodePrefixHandle, previousTimecodePrefixSlot);
            restoreSlot(properties, FRAME_PAD_PROPERTY, framePadHandle, previousFramePadSlot);
            restoreSlot(properties, RULER_TIMECODE_PROPERTY, rulerTimecodeHandle, previousRulerTimecodeSlot);
            restoreSlot(properties, RULER_FRAME_PROPERTY, rulerFrameHandle, previousRulerFrameSlot);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        } finally {
            previousActiveSlot = null;
            previousAcquireTrackSlot = null;
            previousReleaseTrackSlot = null;
            previousAcquireFrameSlot = null;
            previousReleaseFrameSlot = null;
            previousDecimalSlot = null;
            previousSubframeSlot = null;
            previousTimecodePrefixSlot = null;
            previousFramePadSlot = null;
            previousRulerTimecodeSlot = null;
            previousRulerFrameSlot = null;
        }
    }

    /**
     * Borrows a clean track evaluation context object from the caller thread's pool.
     *
     * @return reusable context instance initialized to match {@code new Ctx(false)}
     */
    public Object acquireTrackContext() {
        final ThreadState state = threadState.get();
        try {
            final int depth = state.trackDepth;
            if (depth >= MAX_POOL_DEPTH) {
                return (Object) trackCtor.invokeExact(false);
            }
            Object ctx = state.trackContexts[depth];
            if (ctx == null) {
                ctx = (Object) trackCtor.invokeExact(false);
                state.trackContexts[depth] = ctx;
            }
            state.trackDepth = depth + 1;
            return ctx;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("failed to acquire track context", failure);
        }
    }

    /**
     * Resets and returns a borrowed track evaluation context back to the caller thread's pool.
     *
     * @param ctx context instance returned by {@link #acquireTrackContext()}
     */
    public void releaseTrackContext(final Object ctx) {
        if (ctx == null) {
            return;
        }
        final ThreadState state = threadState.get();
        final int depth = state.trackDepth;
        if (depth <= 0 || state.trackContexts[depth - 1] != ctx) {
            return;
        }
        try {
            // Reset every mutable field to constructor defaults so pooled re-use leaks no prior pass state.
            setTrackA.invokeExact(ctx, false);
            setTrackB.invokeExact(ctx, 0);
            setTrackC.invokeExact(ctx, false);
            setTrackD.invokeExact(ctx, false);
            setTrackE.invokeExact(ctx, (Double) null);
            setTrackF.invokeExact(ctx, true);
            setTrackG.invokeExact(ctx, false);
            if (setTrackH != null) {
                setTrackH.invokeExact(ctx, (Object) null);
            }
            if (setTrackI != null) {
                setTrackI.invokeExact(ctx, false);
            }
            if (setTrackJ != null) {
                setTrackJ.invokeExact(ctx, true);
            }
            state.trackDepth = depth - 1;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.trackContexts[depth - 1] = null;
            state.trackDepth = depth - 1;
        }
    }

    /**
     * Borrows a frame-time wrapper configured with {@code frame} from the caller thread's pool.
     *
     * @param frame timeline frame index
     * @return frame-time wrapper carrying {@code frame}
     */
    public Object acquireFrameTime(final int frame) {
        final ThreadState state = threadState.get();
        try {
            final int depth = state.frameDepth;
            if (depth >= MAX_POOL_DEPTH) {
                return (Object) frameCtor.invokeExact(frame);
            }
            Object wrapper = state.frameTimes[depth];
            if (wrapper == null) {
                wrapper = (Object) frameCtor.invokeExact(frame);
                state.frameTimes[depth] = wrapper;
            } else {
                setFrameA.invokeExact(wrapper, frame);
            }
            state.frameDepth = depth + 1;
            return wrapper;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("failed to acquire frame time wrapper", failure);
        }
    }

    /**
     * Returns a borrowed frame-time wrapper back to the caller thread's pool.
     *
     * @param wrapper wrapper instance returned by {@link #acquireFrameTime(int)}
     */
    public void releaseFrameTime(final Object wrapper) {
        if (wrapper == null) {
            return;
        }
        final ThreadState state = threadState.get();
        final int depth = state.frameDepth;
        if (depth > 0 && state.frameTimes[depth - 1] == wrapper) {
            state.frameDepth = depth - 1;
        }
    }

    /**
     * Formats {@code value} using {@code formatter}, serving cache hits without heap allocations.
     *
     * @param formatter host decimal formatter instance
     * @param value double value to format
     * @return formatted string matching {@code formatter.format(value)}
     */
    public String formatDecimal(final DecimalFormat formatter, final double value) {
        return formatDecimalCached(threadState.get().decimalCache, formatter, value);
    }

    /**
     * Stateless helper formatting {@code value} against a thread-local {@link DecimalCache} bucket.
     *
     * @param cache thread-local cache storage
     * @param formatter host decimal formatter
     * @param value double value to format
     * @return exact formatted text
     */
    static String formatDecimalCached(final DecimalCache cache, final DecimalFormat formatter, final double value) {
        cache.verifyFormatter(formatter);
        final long rawBits = Double.doubleToRawLongBits(value);
        final int bucket = mixBits(rawBits) & DECIMAL_CACHE_MASK;
        if (cache.occupied[bucket] == cache.epoch && cache.keys[bucket] == rawBits) {
            return cache.values[bucket];
        }
        final String formatted = formatter.format(value);
        cache.keys[bucket] = rawBits;
        cache.values[bucket] = formatted;
        cache.occupied[bucket] = cache.epoch;
        return formatted;
    }

    /**
     * Formats a timeline sub-frame number matching {@code CMvMovieInfo.getDisplayTimeSubFrameString}.
     *
     * @param subFrame raw sub-frame remainder returned by {@code getDisplayTimeSubFrame}
     * @param fpsInt integer frames per second
     * @param negateNegative whether negative sub-frames strip their minus sign (5.2+ behavior)
     * @return zero-padded or plain string representation
     */
    public String formatSubFrame(final int subFrame, final int fpsInt, final boolean negateNegative) {
        return formatSubFrameCached(subFrame, fpsInt, negateNegative);
    }

    /**
     * Formats a sub-frame string without allocating wrapper arrays for standard FPS ranges.
     *
     * @param subFrame raw sub-frame remainder
     * @param fpsInt integer frames per second
     * @param negateNegative whether negative sub-frames negate before zero-padding
     * @return exact string matching {@code CMvMovieInfo.getDisplayTimeSubFrameString}
     */
    static String formatSubFrameCached(final int subFrame, final int fpsInt, final boolean negateNegative) {
        if (fpsInt >= FPS_TWO_DIGIT_MIN && fpsInt < FPS_THREE_DIGIT_MIN) {
            final int val = (negateNegative && subFrame < 0) ? -subFrame : subFrame;
            if (val >= 0 && val < PAD2_TABLE.length) {
                return PAD2_TABLE[val];
            }
            return String.format(FMT_PAD2, val);
        }
        if (fpsInt >= FPS_THREE_DIGIT_MIN && fpsInt < FPS_THREE_DIGIT_MAX) {
            final int val = (negateNegative && subFrame < 0) ? -subFrame : subFrame;
            if (val >= 0 && val < PAD3_TABLE.length) {
                return PAD3_TABLE[val];
            }
            return String.format(FMT_PAD3, val);
        }
        if (subFrame >= SUBFRAME_RAW_MIN && subFrame < SUBFRAME_RAW_MAX) {
            return SUBFRAME_RAW_TABLE[subFrame - SUBFRAME_RAW_MIN];
        }
        return String.valueOf(subFrame);
    }

    /**
     * Formats the full timeline status bar timecode string ({@code MM:SS:FF} or {@code -MM:SS:FF}).
     *
     * @param displaySec display second count
     * @param negative whether the frame position is negative
     * @param subFrameString zero-padded sub-frame suffix
     * @return formatted status bar timecode string
     */
    public String formatTimecode(final int displaySec, final boolean negative, final String subFrameString) {
        final RulerTimecodeCache cache = threadState.get().statusTimecodeCache;
        final int bucket = mixRulerKey(negative, displaySec, subFrameString) & RULER_CACHE_MASK;
        if (cache.occupied[bucket]
                && cache.negZero[bucket] == negative
                && cache.secs[bucket] == displaySec
                && Objects.equals(cache.subs[bucket], subFrameString)) {
            return cache.values[bucket];
        }
        final String result = formatTimecodePrefixCached(displaySec, negative).concat(subFrameString);
        cache.negZero[bucket] = negative;
        cache.secs[bucket] = displaySec;
        cache.subs[bucket] = subFrameString;
        cache.values[bucket] = result;
        cache.occupied[bucket] = true;
        return result;
    }

    /**
     * Formats the {@code MM:SS:} or {@code -MM:SS:} prefix for timeline status timecode readouts.
     *
     * @param displaySec display second count
     * @param negative whether the frame position is negative
     * @return formatted prefix string ending with {@code ':'}
     */
    public String formatTimecodePrefix(final int displaySec, final boolean negative) {
        return formatTimecodePrefixCached(displaySec, negative);
    }

    static String formatTimecodePrefixCached(final int displaySec, final boolean negative) {
        if (negative) {
            final int absSec = displaySec == Integer.MIN_VALUE ? Integer.MAX_VALUE : Math.abs(displaySec);
            if (absSec < TIMECODE_NEG_TABLE.length) {
                return TIMECODE_NEG_TABLE[absSec];
            }
            return String.format(
                    FMT_TIMECODE_NEG,
                    Math.abs(displaySec / SECONDS_PER_MINUTE),
                    Math.abs(displaySec % SECONDS_PER_MINUTE));
        }
        if (displaySec >= 0 && displaySec < TIMECODE_POS_TABLE.length) {
            return TIMECODE_POS_TABLE[displaySec];
        }
        return String.format(FMT_TIMECODE_POS, displaySec / SECONDS_PER_MINUTE, displaySec % SECONDS_PER_MINUTE);
    }

    /**
     * Formats a zero-padded display frame number ({@code %05d} or {@code %06d}) without array boxing.
     *
     * @param displayFrame display frame number
     * @param padSix whether negative-mode 6-digit padding applies
     * @return formatted display frame string
     */
    public String formatPaddedFrame(final int displayFrame, final boolean padSix) {
        return formatPaddedFrameCached(threadState.get().paddedFrameCache, displayFrame, padSix);
    }

    static String formatPaddedFrameCached(final PaddedFrameCache cache, final int displayFrame, final boolean padSix) {
        if (displayFrame >= FRAME_STATIC_MIN && displayFrame < FRAME_STATIC_MAX) {
            final int idx = displayFrame - FRAME_STATIC_MIN;
            return padSix ? PAD6_TABLE[idx] : PAD5_TABLE[idx];
        }
        final int bucket = mixPadFrameKey(displayFrame, padSix) & FRAME_TL_CACHE_MASK;
        if (cache.occupied[bucket] && cache.frames[bucket] == displayFrame && cache.padSix[bucket] == padSix) {
            return cache.values[bucket];
        }
        final String formatted = String.format(padSix ? FMT_PAD6 : FMT_PAD5, displayFrame);
        cache.frames[bucket] = displayFrame;
        cache.padSix[bucket] = padSix;
        cache.values[bucket] = formatted;
        cache.occupied[bucket] = true;
        return formatted;
    }

    /**
     * Formats timeline ruler tick timecodes ({@code "-0:" + sub} or {@code sec + ":" + sub}).
     *
     * @param tickFrame timeline frame at tick
     * @param sec display second value
     * @param supportNegZero whether {@code frame < 0 && sec == 0} emits {@code "-0:"}
     * @param subFrameString sub-frame string already formatted for {@code tickFrame}
     * @return formatted ruler timecode string
     */
    public String formatRulerTimecode(
            final int tickFrame, final int sec, final boolean supportNegZero, final String subFrameString) {
        final RulerTimecodeCache cache = threadState.get().rulerTimecodeCache;
        final boolean negZero = supportNegZero && tickFrame < 0 && sec == 0;
        final int bucket = mixRulerKey(negZero, sec, subFrameString) & RULER_CACHE_MASK;
        if (cache.occupied[bucket]
                && cache.negZero[bucket] == negZero
                && cache.secs[bucket] == sec
                && Objects.equals(cache.subs[bucket], subFrameString)) {
            return cache.values[bucket];
        }
        final String result = (negZero ? RULER_NEG_ZERO_PREFIX : (sec + COLON)) + subFrameString;
        cache.negZero[bucket] = negZero;
        cache.secs[bucket] = sec;
        cache.subs[bucket] = subFrameString;
        cache.values[bucket] = result;
        cache.occupied[bucket] = true;
        return result;
    }

    /**
     * Formats timeline ruler integer frame labels without repeated string conversion overhead.
     *
     * @param displayFrame display frame number
     * @return string representation matching {@code String.valueOf(displayFrame)}
     */
    public String formatRulerFrame(final int displayFrame) {
        if (displayFrame >= FRAME_STATIC_MIN && displayFrame < FRAME_STATIC_MAX) {
            return PAD_RAW(displayFrame);
        }
        final RawFrameCache cache = threadState.get().rawFrameCache;
        final int bucket = (displayFrame * HASH_MIX) & FRAME_TL_CACHE_MASK;
        if (cache.occupied[bucket] && cache.frames[bucket] == displayFrame) {
            return cache.values[bucket];
        }
        final String formatted = String.valueOf(displayFrame);
        cache.frames[bucket] = displayFrame;
        cache.values[bucket] = formatted;
        cache.occupied[bucket] = true;
        return formatted;
    }

    private static String PAD_RAW(final int displayFrame) {
        if (displayFrame >= SUBFRAME_RAW_MIN && displayFrame < SUBFRAME_RAW_MAX) {
            return SUBFRAME_RAW_TABLE[displayFrame - SUBFRAME_RAW_MIN];
        }
        return Integer.toString(displayFrame);
    }

    private static int mixBits(final long bits) {
        return (int) (bits ^ (bits >>> HASH_SHIFT)) * HASH_MIX;
    }

    private static int mixPadFrameKey(final int frame, final boolean padSix) {
        return ((frame * HASH_PRIME) + (padSix ? 1 : 0)) * HASH_MIX;
    }

    private static int mixRulerKey(final boolean negZero, final int sec, final String sub) {
        int h = negZero ? 1 : 0;
        h = HASH_PRIME * h + sec;
        h = HASH_PRIME * h + (sub == null ? 0 : sub.hashCode());
        return h * HASH_MIX;
    }

    private static String[] buildFormatTable(final String pattern, final int minInclusive, final int maxExclusive) {
        final String[] table = new String[maxExclusive - minInclusive];
        for (int i = 0; i < table.length; i++) {
            table[i] = String.format(pattern, minInclusive + i);
        }
        return table;
    }

    private static String[] buildRawTable(final int minInclusive, final int maxExclusive) {
        final String[] table = new String[maxExclusive - minInclusive];
        for (int i = 0; i < table.length; i++) {
            table[i] = String.valueOf(minInclusive + i);
        }
        return table;
    }

    private static String[] buildTimecodeTable(final boolean negative, final int seconds) {
        final String[] table = new String[seconds];
        final String pattern = negative ? FMT_TIMECODE_NEG : FMT_TIMECODE_POS;
        for (int s = 0; s < seconds; s++) {
            table[s] = String.format(pattern, s / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE);
        }
        return table;
    }

    private static MethodHandle bindSetter(
            final MethodHandles.Lookup lookup, final Class<?> owner, final String name, final Class<?> fieldType)
            throws ReflectiveOperationException {
        return lookup.findSetter(owner, name, fieldType)
                .asType(MethodType.methodType(void.class, Object.class, fieldType));
    }

    private static MethodHandle bindOptionalBooleanSetter(
            final MethodHandles.Lookup lookup, final Class<?> owner, final String name) throws IllegalAccessException {
        try {
            return lookup.findSetter(owner, name, boolean.class)
                    .asType(MethodType.methodType(void.class, Object.class, boolean.class));
        } catch (final NoSuchFieldException absent) {
            return null;
        }
    }

    private static MethodHandle bindOptionalObjectSetter(
            final MethodHandles.Lookup lookup, final Class<?> owner, final String name) throws IllegalAccessException {
        try {
            final Field field = owner.getDeclaredField(name);
            return lookup.findSetter(owner, name, field.getType())
                    .asType(MethodType.methodType(void.class, Object.class, Object.class));
        } catch (final NoSuchFieldException absent) {
            return null;
        }
    }

    private static void restoreSlot(
            final Properties properties, final String key, final Object expected, final Object previous) {
        if (properties.get(key) == expected) {
            if (previous == null) {
                properties.remove(key);
            } else {
                properties.put(key, previous);
            }
        }
    }

    private static final class ThreadState {
        final Object[] trackContexts = new Object[MAX_POOL_DEPTH];
        final Object[] frameTimes = new Object[MAX_POOL_DEPTH];
        final DecimalCache decimalCache = new DecimalCache();
        final PaddedFrameCache paddedFrameCache = new PaddedFrameCache();
        final RulerTimecodeCache statusTimecodeCache = new RulerTimecodeCache();
        final RulerTimecodeCache rulerTimecodeCache = new RulerTimecodeCache();
        final RawFrameCache rawFrameCache = new RawFrameCache();
        int trackDepth;
        int frameDepth;
    }

    /** Per-thread bounded direct-mapped cache for {@link DecimalFormat#format(double)} outputs. */
    static final class DecimalCache {
        final long[] keys = new long[DECIMAL_CACHE_CAPACITY];
        final String[] values = new String[DECIMAL_CACHE_CAPACITY];
        final int[] occupied = new int[DECIMAL_CACHE_CAPACITY];
        WeakReference<DecimalFormat> lastFormatter = new WeakReference<>(null);
        int epoch = 1;
        int minFrac;
        int maxFrac;
        int minInt;
        int maxInt;
        int multiplier;
        int groupingSize;
        boolean groupingUsed;
        RoundingMode roundingMode;
        String posPrefix;
        String negPrefix;
        String posSuffix;
        String negSuffix;

        void verifyFormatter(final DecimalFormat formatter) {
            final DecimalFormat prev = lastFormatter.get();
            final int fMinFrac = formatter.getMinimumFractionDigits();
            final int fMaxFrac = formatter.getMaximumFractionDigits();
            final int fMinInt = formatter.getMinimumIntegerDigits();
            final int fMaxInt = formatter.getMaximumIntegerDigits();
            final int fMult = formatter.getMultiplier();
            final int fGroupSize = formatter.getGroupingSize();
            final boolean fGrouping = formatter.isGroupingUsed();
            final RoundingMode fMode = formatter.getRoundingMode();
            final String fPosPre = formatter.getPositivePrefix();
            final String fNegPre = formatter.getNegativePrefix();
            final String fPosSuf = formatter.getPositiveSuffix();
            final String fNegSuf = formatter.getNegativeSuffix();
            if (Objects.equals(prev, formatter)
                    && minFrac == fMinFrac
                    && maxFrac == fMaxFrac
                    && minInt == fMinInt
                    && maxInt == fMaxInt
                    && multiplier == fMult
                    && groupingSize == fGroupSize
                    && groupingUsed == fGrouping
                    && roundingMode == fMode
                    && Objects.equals(posPrefix, fPosPre)
                    && Objects.equals(negPrefix, fNegPre)
                    && Objects.equals(posSuffix, fPosSuf)
                    && Objects.equals(negSuffix, fNegSuf)) {
                return;
            }
            lastFormatter = new WeakReference<>(formatter);
            minFrac = fMinFrac;
            maxFrac = fMaxFrac;
            minInt = fMinInt;
            maxInt = fMaxInt;
            multiplier = fMult;
            groupingSize = fGroupSize;
            groupingUsed = fGrouping;
            roundingMode = fMode;
            posPrefix = fPosPre;
            negPrefix = fNegPre;
            posSuffix = fPosSuf;
            negSuffix = fNegSuf;
            epoch++;
            if (epoch == 0) {
                java.util.Arrays.fill(occupied, 0);
                epoch = 1;
            }
        }
    }

    static final class PaddedFrameCache {
        final int[] frames = new int[FRAME_TL_CACHE_CAPACITY];
        final boolean[] padSix = new boolean[FRAME_TL_CACHE_CAPACITY];
        final String[] values = new String[FRAME_TL_CACHE_CAPACITY];
        final boolean[] occupied = new boolean[FRAME_TL_CACHE_CAPACITY];
    }

    private static final class RulerTimecodeCache {
        final boolean[] negZero = new boolean[RULER_CACHE_CAPACITY];
        final int[] secs = new int[RULER_CACHE_CAPACITY];
        final String[] subs = new String[RULER_CACHE_CAPACITY];
        final String[] values = new String[RULER_CACHE_CAPACITY];
        final boolean[] occupied = new boolean[RULER_CACHE_CAPACITY];
    }

    private static final class RawFrameCache {
        final int[] frames = new int[FRAME_TL_CACHE_CAPACITY];
        final String[] values = new String[FRAME_TL_CACHE_CAPACITY];
        final boolean[] occupied = new boolean[FRAME_TL_CACHE_CAPACITY];
    }
}
