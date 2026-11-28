package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.exec.RuntimeThreads;
import java.lang.ref.WeakReference;
import java.lang.reflect.InvocationTargetException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Coalesces redundant post-load Parameter Palette structure rebuild bursts while preserving
 * immediate execution during interactive edits.
 */
public final class ParameterPaletteCoalesceBridge {

    private static final long INTERACTIVE_FORCE_DEBOUNCE_MS = 10L;
    private static final long POST_LOAD_SETTLE_WINDOW_MS = 150L;
    private static final long POST_LOAD_SETTLE_WINDOW_NANOS = TimeUnit.MILLISECONDS.toNanos(POST_LOAD_SETTLE_WINDOW_MS);
    private static final long LOAD_FLUSH_DEBOUNCE_MS = 45L;
    private static final int MAX_TRACKED_CONTROLLERS = 32;
    private static final String WORKER_THREAD_NAME = RuntimeThreads.THREAD_NAME_PREFIX + "-param-palette-coalescer";

    private static final ThreadLocal<Boolean> DIRECT_FLUSH_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final ThreadLocal<Boolean> FORCE_ARG_OVERRIDE = new ThreadLocal<>();

    private static final Object LOCK = new Object();
    private static final AtomicInteger ACTIVE_LOAD_DEPTH = new AtomicInteger();
    private static final Map<Object, PendingTarget> PENDING_TARGETS = new LinkedHashMap<>();

    private static volatile long settleWindowDeadlineNanos;
    private static ScheduledFuture<?> scheduledFlush;
    private static Consumer<Boolean> testUpdateManagerExecutor;

    private static final ScheduledExecutorService SCHEDULER =
            RuntimeThreads.serialTimer(RuntimeThreads.daemonThreadsWithFixedName(
                    WORKER_THREAD_NAME, RuntimeThreads.contextClassLoader(ParameterPaletteCoalesceBridge.class)));

    private ParameterPaletteCoalesceBridge() {}

    /**
     * Marks entry into document loading so intermediate parameter rebuilds batch until load settles.
     */
    public static void beginDocumentLoad() {
        ACTIVE_LOAD_DEPTH.incrementAndGet();
        synchronized (LOCK) {
            settleWindowDeadlineNanos = 0L;
        }
    }

    /**
     * Marks completion of document loading, releases transient fallback texture memory, and arms a
     * single post-load settle flush for queued Parameter Palette updates.
     */
    public static void endDocumentLoad() {
        DirectTextureUploadBridge.clearTransientFallbackBuffer();
        ACTIVE_LOAD_DEPTH.updateAndGet(depth -> depth > 0 ? depth - 1 : 0);
        synchronized (LOCK) {
            settleWindowDeadlineNanos = System.nanoTime() + POST_LOAD_SETTLE_WINDOW_NANOS;
            if (!PENDING_TARGETS.isEmpty()) {
                rescheduleFlushLocked(LOAD_FLUSH_DEBOUNCE_MS);
            }
        }
    }

    /**
     * Returns whether the current thread is executing an internal coalesced flush directly.
     *
     * @return {@code true} when running the direct un-delayed controller rebuild
     */
    public static boolean isDirectFlushActive() {
        return Boolean.TRUE.equals(DIRECT_FLUSH_ACTIVE.get());
    }

    /**
     * Returns the effective {@code forceStructure} boolean for an internal direct flush invocation.
     *
     * @param originalArg bytecode argument passed on the bypassed branch
     * @return coalesced force flag during flush, or {@code originalArg} otherwise
     */
    public static boolean effectiveForceArgument(final boolean originalArg) {
        final Boolean override = FORCE_ARG_OVERRIDE.get();
        return override != null ? override.booleanValue() : originalArg;
    }

    /**
     * Intercepts {@code CEUpdateManager.updateParameter(boolean)} to coalesce burst rebuilds and
     * eliminate per-call sleep threads during document load or rapid structural updates.
     *
     * @param updateManager host {@code CEUpdateManager} instance (or test harness double)
     * @param forceStructure whether full structure rebuild was requested
     * @return {@code true} if handled/queued by the coalescer; {@code false} to execute synchronously
     */
    public static boolean tryInterceptUpdateParameter(final Object updateManager, final boolean forceStructure) {
        if (updateManager == null || isDirectFlushActive()) {
            return false;
        }
        final boolean inLoadBatch = isCoalescingWindowActive();
        if (!forceStructure && !inLoadBatch) {
            return false;
        }
        synchronized (LOCK) {
            enqueueTargetLocked(updateManager, TargetKind.UPDATE_MANAGER, forceStructure);
            if (inLoadBatch) {
                extendSettleWindowIfEndedLocked();
                rescheduleFlushLocked(LOAD_FLUSH_DEBOUNCE_MS);
            } else if (scheduledFlush == null || scheduledFlush.isDone()) {
                rescheduleFlushLocked(INTERACTIVE_FORCE_DEBOUNCE_MS);
            }
        }
        return true;
    }

    /**
     * Intercepts direct Parameter Palette controller update calls inside coalesced listener paths.
     *
     * @param controller host palette controller instance ({@code k}, {@code m}, or {@code s})
     * @param forceStructure whether full structure rebuild was requested
     * @return {@code true} if deferred into the load-settle flush; {@code false} to execute immediately
     */
    public static boolean tryDeferControllerUpdate(final Object controller, final boolean forceStructure) {
        if (controller == null || isDirectFlushActive() || !isCoalescingWindowActive()) {
            return false;
        }
        synchronized (LOCK) {
            enqueueTargetLocked(controller, TargetKind.CONTROLLER, forceStructure);
            extendSettleWindowIfEndedLocked();
            rescheduleFlushLocked(LOAD_FLUSH_DEBOUNCE_MS);
        }
        return true;
    }

    /**
     * Flushes all currently pending coalesced updates synchronously on the caller/EDT thread.
     */
    public static void flushNow() {
        final PendingTarget[] targets;
        synchronized (LOCK) {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            if (PENDING_TARGETS.isEmpty()) {
                return;
            }
            targets = PENDING_TARGETS.values().toArray(new PendingTarget[0]);
            PENDING_TARGETS.clear();
        }
        dispatchTargets(targets);
    }

    static boolean isCoalescingWindowActive() {
        if (ACTIVE_LOAD_DEPTH.get() > 0) {
            return true;
        }
        final long deadline = settleWindowDeadlineNanos;
        return deadline != 0L && (deadline - System.nanoTime()) > 0L;
    }

    static void resetForTest(final Consumer<Boolean> executor) {
        synchronized (LOCK) {
            if (scheduledFlush != null) {
                scheduledFlush.cancel(false);
                scheduledFlush = null;
            }
            PENDING_TARGETS.clear();
            ACTIVE_LOAD_DEPTH.set(0);
            settleWindowDeadlineNanos = 0L;
            testUpdateManagerExecutor = executor;
        }
    }

    private static void extendSettleWindowIfEndedLocked() {
        if (ACTIVE_LOAD_DEPTH.get() == 0 && settleWindowDeadlineNanos != 0L) {
            settleWindowDeadlineNanos = System.nanoTime() + POST_LOAD_SETTLE_WINDOW_NANOS;
        }
    }

    private static void enqueueTargetLocked(final Object target, final TargetKind kind, final boolean force) {
        pruneCollectedTargetsLocked();
        final IdentityKey key = new IdentityKey(target);
        final PendingTarget existing = PENDING_TARGETS.get(key);
        if (existing != null) {
            existing.forceStructure |= force;
            return;
        }
        if (PENDING_TARGETS.size() >= MAX_TRACKED_CONTROLLERS) {
            final Iterator<Object> it = PENDING_TARGETS.keySet().iterator();
            if (it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        PENDING_TARGETS.put(key, new PendingTarget(target, kind, force));
    }

    private static void pruneCollectedTargetsLocked() {
        PENDING_TARGETS.entrySet().removeIf(entry -> ((IdentityKey) entry.getKey()).ref.get() == null);
    }

    private static void rescheduleFlushLocked(final long delayMs) {
        if (scheduledFlush != null) {
            scheduledFlush.cancel(false);
        }
        scheduledFlush =
                SCHEDULER.schedule(ParameterPaletteCoalesceBridge::scheduleEdtFlush, delayMs, TimeUnit.MILLISECONDS);
    }

    private static void scheduleEdtFlush() {
        if (ACTIVE_LOAD_DEPTH.get() > 0) {
            synchronized (LOCK) {
                if (!PENDING_TARGETS.isEmpty()) {
                    rescheduleFlushLocked(LOAD_FLUSH_DEBOUNCE_MS);
                }
            }
            return;
        }
        if (testUpdateManagerExecutor != null) {
            flushNow();
            return;
        }
        if (SwingUtilities.isEventDispatchThread()) {
            flushNow();
        } else {
            SwingUtilities.invokeLater(ParameterPaletteCoalesceBridge::flushNow);
        }
    }

    private static void dispatchTargets(final PendingTarget[] targets) {
        for (final PendingTarget pending : targets) {
            final Object target = pending.ref.get();
            if (target == null) {
                continue;
            }
            final boolean force = pending.forceStructure;
            if (pending.kind == TargetKind.UPDATE_MANAGER && testUpdateManagerExecutor != null) {
                invokeDirectWithFlags(() -> testUpdateManagerExecutor.accept(force), force);
                continue;
            }
            try {
                if (pending.kind == TargetKind.UPDATE_MANAGER) {
                    final var method = target.getClass().getMethod("updateParameter", boolean.class);
                    invokeDirectWithFlags(
                            () -> {
                                try {
                                    method.invoke(target, force);
                                } catch (final IllegalAccessException | InvocationTargetException failure) {
                                    throw new IllegalStateException(failure);
                                }
                            },
                            force);
                } else {
                    final var method = target.getClass().getMethod("a", boolean.class);
                    invokeDirectWithFlags(
                            () -> {
                                try {
                                    method.invoke(target, force);
                                } catch (final IllegalAccessException | InvocationTargetException failure) {
                                    throw new IllegalStateException(failure);
                                }
                            },
                            force);
                }
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
            }
        }
    }

    private static void invokeDirectWithFlags(final Runnable action, final boolean force) {
        final Boolean prevDirect = DIRECT_FLUSH_ACTIVE.get();
        final Boolean prevForce = FORCE_ARG_OVERRIDE.get();
        DIRECT_FLUSH_ACTIVE.set(Boolean.TRUE);
        FORCE_ARG_OVERRIDE.set(Boolean.valueOf(force));
        try {
            action.run();
        } finally {
            DIRECT_FLUSH_ACTIVE.set(prevDirect);
            if (prevForce == null) {
                FORCE_ARG_OVERRIDE.remove();
            } else {
                FORCE_ARG_OVERRIDE.set(prevForce);
            }
        }
    }

    private enum TargetKind {
        UPDATE_MANAGER,
        CONTROLLER
    }

    private static final class PendingTarget {
        final WeakReference<Object> ref;
        final TargetKind kind;
        boolean forceStructure;

        PendingTarget(final Object target, final TargetKind kind, final boolean forceStructure) {
            this.ref = new WeakReference<>(target);
            this.kind = kind;
            this.forceStructure = forceStructure;
        }
    }

    private static final class IdentityKey {
        final WeakReference<Object> ref;
        final int identityHash;

        IdentityKey(final Object target) {
            this.ref = new WeakReference<>(target);
            this.identityHash = System.identityHashCode(target);
        }

        @Override
        public int hashCode() {
            return identityHash;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof IdentityKey other)) {
                return false;
            }
            final Object self = ref.get();
            return self != null && self == other.ref.get();
        }
    }
}
