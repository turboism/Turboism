package dev.turboism.preview;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Single bounded watcher for plugin generations that could not be cleaned up inside their
 * deadline.
 *
 * <p>A generation is retained while a lifecycle worker is still running it or admitted event
 * callbacks have not quiesced: its ClassLoader, scope and registrations must stay valid until the
 * work actually settles. This watcher only probes readiness — worker completion is tracked by the
 * task's {@code workerDone} future, never by Future cancellation — and dispatches the idempotent
 * reclaim step back onto the bounded lifecycle lane. It never invokes plugin code itself and never
 * drives two cleanup passes over the same generation concurrently.</p>
 */
final class RetainedPluginGenerations {

    private static final long SCAN_MILLIS = 50L;

    private final PluginLifecycleLane lane;
    private final PreviewLog log;
    private final long retryIntervalNanos;
    private final Object monitor = new Object();
    private final List<RetainedGeneration> entries = new ArrayList<>();
    private final Thread watcher;
    private boolean retired;
    private Runnable whenDrained;
    private boolean drainedFired;

    RetainedPluginGenerations(
        final PluginLifecycleLane lane,
        final PluginLifecyclePolicy policy,
        final PreviewLog log
    ) {
        this.lane = Objects.requireNonNull(lane, "lane");
        this.log = Objects.requireNonNull(log, "log");
        this.retryIntervalNanos = Objects.requireNonNull(policy, "policy")
            .retentionRetryInterval().toNanos();
        watcher = new Thread(this::run, "turboism-plugin-retention");
        watcher.setDaemon(true);
        watcher.setContextClassLoader(RetainedPluginGenerations.class.getClassLoader());
        watcher.start();
    }

    /**
     * Tracks one generation whose cleanup could not finish within its deadline.
     *
     * @param generation readiness probes plus the idempotent reclaim step; the step runs on the
     *     lifecycle lane once the in-flight worker has exited, event callbacks have quiesced and
     *     admitted SDK calls have drained
     */
    void retain(final RetainedGeneration generation) {
        synchronized (monitor) {
            entries.add(Objects.requireNonNull(generation, "generation"));
            monitor.notifyAll();
        }
    }

    int retainedCount() {
        synchronized (monitor) {
            return entries.size();
        }
    }

    /**
     * Retires the watcher: no more generations are expected to arrive. When the set is (or becomes)
     * empty the drain callback runs once — releasing the lane — and the watcher exits. Generations
     * that never quiesce keep the daemon watcher alive for the JVM's remaining life rather than
     * abandoning cleanup that could still complete.
     */
    void retire(final Runnable whenDrained) {
        synchronized (monitor) {
            retired = true;
            this.whenDrained = Objects.requireNonNull(whenDrained, "whenDrained");
            monitor.notifyAll();
        }
    }

    private void run() {
        while (true) {
            final RetainedGeneration ready;
            synchronized (monitor) {
                if (entries.isEmpty()) {
                    if (retired) {
                        fireDrainedOnce();
                        return;
                    }
                    if (!awaitTick()) {
                        return;
                    }
                    continue;
                }
                ready = firstReady();
                if (ready == null) {
                    if (!awaitTick()) {
                        return;
                    }
                    continue;
                }
            }
            dispatch(ready);
        }
    }

    private boolean awaitTick() {
        try {
            monitor.wait(SCAN_MILLIS);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void fireDrainedOnce() {
        if (drainedFired) {
            return;
        }
        drainedFired = true;
        final Runnable callback = whenDrained;
        if (callback != null) {
            try {
                callback.run();
            } catch (Throwable failure) {
                log.error(
                    "plugin-lifecycle",
                    "Retained-generation drain callback failed safely",
                    failure
                );
            }
        }
    }

    /** Caller holds {@link #monitor}. */
    private RetainedGeneration firstReady() {
        for (RetainedGeneration generation : entries) {
            if (ready(generation)) {
                return generation;
            }
        }
        return null;
    }

    /** Non-blocking readiness probes; never runs plugin code. Caller holds {@link #monitor}. */
    private boolean ready(final RetainedGeneration generation) {
        if (generation.cleanupRunning.get()) {
            return false;
        }
        final CompletableFuture<Void> inFlight = generation.inFlight;
        if (inFlight != null && !inFlight.isDone()) {
            return false;
        }
        if (generation.eventOwner != null && !eventQuiesced(generation.eventOwner)) {
            return false;
        }
        if (generation.guard != null && !generation.guard.drained()) {
            return false;
        }
        return System.nanoTime() - generation.nextAttemptNanos >= 0L;
    }

    private boolean eventQuiesced(
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner
    ) {
        return switch (eventOwner.lifecycle()) {
            case QUIESCED, CLOSED -> true;
            case CLOSING -> eventOwner.awaitQuiescence(Duration.ZERO);
            // ADMITTED/INITIALIZING/ENABLING/ACTIVE owners have not begun closing; retained
            // generations are fenced to CLOSING before they are tracked, so this is defensive.
            default -> false;
        };
    }

    private void dispatch(final RetainedGeneration generation) {
        if (!generation.cleanupRunning.compareAndSet(false, true)) {
            return;
        }
        final PluginLifecycleLane.Invocation<Boolean> invocation = lane.submitRetained(
            generation.pluginId,
            "retained-cleanup",
            generation.reclaim::call
        );
        if (invocation.rejected) {
            // Saturated lane: back off before this generation can be selected again, or the
            // watcher would busy-spin dispatching a task the queue keeps refusing.
            generation.nextAttemptNanos = System.nanoTime() + retryIntervalNanos;
            generation.cleanupRunning.set(false);
            return;
        }
        generation.inFlight = invocation.workerDone;
        invocation.result.whenComplete((reclaimed, failure) -> {
            final boolean removed;
            synchronized (monitor) {
                generation.cleanupRunning.set(false);
                if (failure == null && Boolean.TRUE.equals(reclaimed)) {
                    entries.remove(generation);
                    removed = true;
                } else {
                    generation.nextAttemptNanos = System.nanoTime() + retryIntervalNanos;
                    removed = false;
                }
                monitor.notifyAll();
            }
            if (removed) {
                log.info(
                    generation.pluginId,
                    "Retained plugin generation cleanup succeeded"
                );
            } else if (failure != null) {
                log.error(
                    generation.pluginId,
                    "Retained plugin generation cleanup attempt failed safely",
                    failure
                );
            }
        });
    }

    /**
     * One retained generation: probes that decide when cleanup may run again and the idempotent
     * reclaim step itself.
     *
     * @param inFlight workerDone of the lane task currently operating on this generation, or
     *     {@code null} when nothing is running; replaced by the watcher on each dispatch
     * @param eventOwner admitted event owner to quiesce before reclaim, or {@code null}
     * @param guard admission gate whose in-flight SDK calls must drain, or {@code null}
     * @param reclaim idempotent cleanup step run on the lifecycle lane; {@code true} reclaims the
     *     generation and removes it, {@code false} keeps it retained for a later attempt
     */
    static final class RetainedGeneration {
        final String pluginId;
        volatile CompletableFuture<Void> inFlight;
        final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner;
        final PluginGenerationGuard guard;
        final Callable<Boolean> reclaim;
        final AtomicBoolean cleanupRunning = new AtomicBoolean();
        volatile long nextAttemptNanos;

        RetainedGeneration(
            final String pluginId,
            final CompletableFuture<Void> inFlight,
            final dev.turboism.core.event.RuntimeEventBroker.Owner eventOwner,
            final PluginGenerationGuard guard,
            final Callable<Boolean> reclaim
        ) {
            this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
            this.inFlight = inFlight;
            this.eventOwner = eventOwner;
            this.guard = guard;
            this.reclaim = Objects.requireNonNull(reclaim, "reclaim");
        }
    }
}
