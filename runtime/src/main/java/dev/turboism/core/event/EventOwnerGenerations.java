package dev.turboism.core.event;

import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.contract.RuntimeCancellationToken;
import dev.turboism.core.runtime.work.PluginExecutorSet;
import dev.turboism.sdk.event.EventBus;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Owns the admitted plugin generations of one broker session: their lifecycle state, bounded
 * delivery mailboxes, and the executor-set claims that fence a generation's shutdown.
 *
 * <p>Every mutation of one generation's state happens under that generation's own monitor, so the
 * lifecycle machine and its mailbox can never disagree. The monitor is also the wait/notify
 * rendezvous for {@link #awaitQuiescence}, which is why quiescence is expressed as "no drain
 * scheduled and no delivery running" rather than as a counter the caller could race.
 *
 * <p>Executor claims are tracked independently of the owner map on purpose: a failed load closes
 * the owner before its executor release runs, so a release after {@link #close} must still find
 * the claim and shut the set down.
 */
final class EventOwnerGenerations {

    private static final long NANOS_PER_MILLI = 1_000_000L;

    private final RuntimeScheduler scheduler;
    private final int mailboxCapacity;
    private final AtomicLong generationCounter = new AtomicLong();
    private final ConcurrentMap<String, AtomicLong> generations = new ConcurrentHashMap<>();
    private final ConcurrentMap<PluginEventOwnerKey, OwnerMailbox> owners = new ConcurrentHashMap<>();
    private final ConcurrentMap<PluginEventOwnerKey, PluginExecutorSet> executorClaims = new ConcurrentHashMap<>();

    EventOwnerGenerations(final RuntimeScheduler scheduler, final int mailboxCapacity) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.mailboxCapacity = mailboxCapacity;
    }

    /** Allocates the next generation number for one plugin identifier. */
    PluginEventOwnerKey nextGeneration(final String pluginId) {
        return new PluginEventOwnerKey(
                pluginId,
                generations
                        .computeIfAbsent(pluginId, ignored -> new AtomicLong())
                        .incrementAndGet());
    }

    /** Registers an admitted generation; a duplicate key is a programming error, not a retry. */
    OwnerMailbox register(final PluginEventOwnerKey key) {
        final OwnerMailbox mailbox = new OwnerMailbox(key, mailboxCapacity, RuntimeEventBroker.OwnerLifecycle.ADMITTED);
        if (owners.putIfAbsent(key, mailbox) != null) {
            throw new IllegalStateException("Plugin event owner generation already exists: " + key);
        }
        return mailbox;
    }

    /** Registers the generation-zero owner as already active, which is how integrations join. */
    OwnerMailbox registerActive(final PluginEventOwnerKey key) {
        return owners.computeIfAbsent(
                key, ignored -> new OwnerMailbox(key, mailboxCapacity, RuntimeEventBroker.OwnerLifecycle.ACTIVE));
    }

    /** Removes one generation registration; a stale mailbox never displaces its successor. */
    void unregister(final PluginEventOwnerKey key, final OwnerMailbox mailbox) {
        owners.remove(key, mailbox);
    }

    /** Returns the registered generation, or {@code null} when it was never admitted or is closed. */
    OwnerMailbox find(final PluginEventOwnerKey owner) {
        return owners.get(Objects.requireNonNull(owner, "owner"));
    }

    OwnerMailbox require(final PluginEventOwnerKey owner) {
        final PluginEventOwnerKey key = Objects.requireNonNull(owner, "owner");
        final OwnerMailbox mailbox = owners.get(key);
        if (mailbox == null) {
            throw new IllegalStateException("Unknown plugin event owner: " + key);
        }
        return mailbox;
    }

    /**
     * Requires a generation that may still accept subscriptions. Staged generations accept routes
     * before they are active so enabling can register subscribers without a second window.
     */
    PluginEventOwnerKey requireSubscribable(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = require(owner);
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current != RuntimeEventBroker.OwnerLifecycle.ADMITTED
                    && current != RuntimeEventBroker.OwnerLifecycle.INITIALIZING
                    && current != RuntimeEventBroker.OwnerLifecycle.ENABLING
                    && current != RuntimeEventBroker.OwnerLifecycle.ACTIVE) {
                throw new IllegalStateException(
                        "Plugin event owner does not accept subscriptions: " + owner + " state=" + current);
            }
        }
        return owner;
    }

    /** Requires a generation that may still publish or observe events for the named operation. */
    OwnerMailbox requireActive(final PluginEventOwnerKey owner, final String operation) {
        final OwnerMailbox mailbox = require(owner);
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current != RuntimeEventBroker.OwnerLifecycle.ACTIVE
                    && current != RuntimeEventBroker.OwnerLifecycle.ENABLING
                    && current != RuntimeEventBroker.OwnerLifecycle.INITIALIZING) {
                throw new IllegalStateException(
                        "Plugin event owner cannot " + operation + ": " + owner + " state=" + current);
            }
        }
        return mailbox;
    }

    void beginInitializing(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = require(owner);
        synchronized (mailbox.monitor()) {
            if (mailbox.lifecycle() != RuntimeEventBroker.OwnerLifecycle.ADMITTED) {
                throw new IllegalStateException(
                        "Plugin event owner cannot begin initializing: " + owner + " state=" + mailbox.lifecycle());
            }
            mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.INITIALIZING);
        }
    }

    void beginEnabling(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = require(owner);
        synchronized (mailbox.monitor()) {
            if (mailbox.lifecycle() != RuntimeEventBroker.OwnerLifecycle.INITIALIZING) {
                throw new IllegalStateException(
                        "Plugin event owner cannot begin enabling: " + owner + " state=" + mailbox.lifecycle());
            }
            mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.ENABLING);
        }
    }

    /**
     * Activates a staged generation. {@code activationHook} runs inside the owner monitor before
     * the lifecycle flips, so route activation and the state change stay one atomic step.
     */
    void activate(final PluginEventOwnerKey owner, final Runnable activationHook) {
        final OwnerMailbox mailbox = require(owner);
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current != RuntimeEventBroker.OwnerLifecycle.ADMITTED
                    && current != RuntimeEventBroker.OwnerLifecycle.ENABLING) {
                throw new IllegalStateException("Plugin event owner cannot activate: " + owner + " state=" + current);
            }
            activationHook.run();
            mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.ACTIVE);
        }
    }

    /**
     * Stops new deliveries and cancels the queued ones.
     *
     * @return {@code true} when this call performed the transition, so the caller removes the
     *     generation's routes exactly once; {@code false} for an unknown or already closing owner
     */
    boolean beginClosing(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = owners.get(Objects.requireNonNull(owner, "owner"));
        if (mailbox == null) {
            return false;
        }
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current == RuntimeEventBroker.OwnerLifecycle.CLOSING
                    || current == RuntimeEventBroker.OwnerLifecycle.QUIESCED
                    || current == RuntimeEventBroker.OwnerLifecycle.CLOSED) {
                return false;
            }
            mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.CLOSING);
            mailbox.cancelPending();
        }
        return true;
    }

    /**
     * Waits until the generation has no scheduled drain and no running delivery.
     *
     * @return {@code false} when the timeout elapsed first; the caller keeps ownership of the
     *     interrupted flag so a timed-out wait never loses an interrupt
     */
    boolean awaitQuiescence(final PluginEventOwnerKey owner, final Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        final OwnerMailbox mailbox = owners.get(Objects.requireNonNull(owner, "owner"));
        if (mailbox == null) {
            return true;
        }
        final long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current != RuntimeEventBroker.OwnerLifecycle.CLOSING
                    && current != RuntimeEventBroker.OwnerLifecycle.QUIESCED
                    && current != RuntimeEventBroker.OwnerLifecycle.CLOSED) {
                throw new IllegalStateException("Plugin event owner must begin closing before quiescence: " + owner);
            }
            while (!mailbox.quiescent()) {
                final long remaining = deadline - System.nanoTime();
                if (remaining <= 0L) {
                    if (interrupted) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }
                try {
                    mailbox.monitor().wait(remaining / NANOS_PER_MILLI, (int) (remaining % NANOS_PER_MILLI));
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (mailbox.lifecycle() == RuntimeEventBroker.OwnerLifecycle.CLOSING) {
                mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.QUIESCED);
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        return true;
    }

    /**
     * Releases one generation registration.
     *
     * @return {@code true} when the generation existed and is now closed, so the caller releases
     *     its routes, permissions, and failure advice
     */
    boolean close(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = owners.get(Objects.requireNonNull(owner, "owner"));
        if (mailbox == null) {
            return false;
        }
        synchronized (mailbox.monitor()) {
            final RuntimeEventBroker.OwnerLifecycle current = mailbox.lifecycle();
            if (current != RuntimeEventBroker.OwnerLifecycle.QUIESCED
                    && current != RuntimeEventBroker.OwnerLifecycle.CLOSED) {
                throw new IllegalStateException(
                        "Plugin event owner must quiesce before close: " + owner + " state=" + current);
            }
            mailbox.lifecycle(RuntimeEventBroker.OwnerLifecycle.CLOSED);
        }
        owners.remove(owner, mailbox);
        return true;
    }

    RuntimeEventBroker.OwnerLifecycle lifecycle(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = owners.get(Objects.requireNonNull(owner, "owner"));
        if (mailbox == null) {
            return RuntimeEventBroker.OwnerLifecycle.CLOSED;
        }
        synchronized (mailbox.monitor()) {
            return mailbox.lifecycle();
        }
    }

    long droppedDeliveries(final PluginEventOwnerKey owner) {
        final OwnerMailbox mailbox = owners.get(Objects.requireNonNull(owner, "owner"));
        if (mailbox == null) {
            return 0L;
        }
        synchronized (mailbox.monitor()) {
            return mailbox.droppedDeliveries();
        }
    }

    /**
     * Captures the executor set mapped to this generation's plugin id. Idempotent per key: the
     * first claim wins so a re-driven fence never swaps the captured set mid-close.
     */
    void claimExecutors(final PluginEventOwnerKey key) {
        executorClaims.computeIfAbsent(key, value -> scheduler.claimPluginExecutors(value.pluginId()));
    }

    /**
     * Removes this generation's executor claim and shuts the captured set down through
     * generation-safe compare-and-remove: a set the registry already replaced for a newer
     * generation keeps running, while this generation's own set always dies.
     */
    void releaseExecutors(final PluginEventOwnerKey key) {
        final PluginExecutorSet claimed = executorClaims.remove(key);
        if (claimed != null) {
            scheduler.releasePluginExecutors(key.pluginId(), claimed);
        }
    }

    /**
     * Removes this generation's executor claim and aborts the captured set without waiting for
     * running work. The owner is already fenced and closed on the failed-load path, so a graceful
     * drain cannot deliver anything and would only delay the caller.
     */
    void discardExecutors(final PluginEventOwnerKey key) {
        final PluginExecutorSet claimed = executorClaims.remove(key);
        if (claimed != null) {
            scheduler.discardPluginExecutors(key.pluginId(), claimed);
        }
    }

    /** Outcome of admitting one delivery into an owner mailbox. */
    enum EnqueueResult {
        /** The delivery was queued and this call must schedule the drain. */
        SCHEDULE,
        /** The delivery was queued behind an already scheduled drain. */
        QUEUED,
        /** The mailbox was full; the delivery was dropped and counted. */
        SATURATED,
        /** The owner lifecycle no longer admits deliveries. */
        REJECTED_LIFECYCLE
    }

    /**
     * One generation's bounded mailbox and lifecycle state. All fields are guarded by
     * {@link #monitor}; the accessors that skip the lock are only called while the caller already
     * holds it.
     */
    static final class OwnerMailbox {

        private final PluginEventOwnerKey key;
        private final int mailboxCapacity;
        private final Object monitor = new Object();
        private final ArrayDeque<EventDelivery> mailbox = new ArrayDeque<>();
        private RuntimeEventBroker.OwnerLifecycle lifecycle;
        private RuntimeCancellationToken drainToken = new RuntimeCancellationToken();
        private boolean drainScheduled;
        private int runningDeliveries;
        private long droppedDeliveries;

        private OwnerMailbox(
                final PluginEventOwnerKey key,
                final int mailboxCapacity,
                final RuntimeEventBroker.OwnerLifecycle lifecycle) {
            this.key = key;
            this.mailboxCapacity = mailboxCapacity;
            this.lifecycle = lifecycle;
        }

        PluginEventOwnerKey key() {
            return key;
        }

        Object monitor() {
            return monitor;
        }

        RuntimeEventBroker.OwnerLifecycle lifecycleSnapshot() {
            synchronized (monitor) {
                return lifecycle;
            }
        }

        RuntimeEventBroker.OwnerLifecycle lifecycle() {
            return lifecycle;
        }

        void lifecycle(final RuntimeEventBroker.OwnerLifecycle value) {
            lifecycle = value;
        }

        /**
         * Admits one delivery under the backpressure contract: a full mailbox drops the delivery
         * and counts it instead of blocking the publisher, and only the first queued delivery of a
         * burst schedules a drain.
         */
        EnqueueResult enqueue(final EventDelivery delivery) {
            synchronized (monitor) {
                if (lifecycle != RuntimeEventBroker.OwnerLifecycle.ACTIVE
                        && lifecycle != RuntimeEventBroker.OwnerLifecycle.ENABLING
                        && lifecycle != RuntimeEventBroker.OwnerLifecycle.INITIALIZING) {
                    return EnqueueResult.REJECTED_LIFECYCLE;
                }
                if (mailbox.size() >= mailboxCapacity) {
                    droppedDeliveries++;
                    return EnqueueResult.SATURATED;
                }
                mailbox.addLast(delivery);
                if (drainScheduled) {
                    return EnqueueResult.QUEUED;
                }
                drainScheduled = true;
                drainToken = new RuntimeCancellationToken();
                return EnqueueResult.SCHEDULE;
            }
        }

        /**
         * Reserves one synchronous delivery slot so quiescence cannot be reported while a
         * transform callback is still running.
         *
         * @return {@code false} when the route does not accept this lifecycle state
         */
        boolean beginSynchronousDeliverySnapshot(
                final EventSubscription<? extends EventBus.TurboismEvent> subscription) {
            synchronized (monitor) {
                if (!subscription.accepts(lifecycle)) {
                    return false;
                }
                runningDeliveries++;
                return true;
            }
        }

        RuntimeCancellationToken drainToken() {
            synchronized (monitor) {
                return drainToken;
            }
        }

        /** Takes the next queued delivery, dropping the backlog once the owner stops admitting. */
        EventDelivery nextDelivery() {
            synchronized (monitor) {
                if (lifecycle != RuntimeEventBroker.OwnerLifecycle.ACTIVE
                        && lifecycle != RuntimeEventBroker.OwnerLifecycle.ENABLING
                        && lifecycle != RuntimeEventBroker.OwnerLifecycle.INITIALIZING) {
                    droppedDeliveries += mailbox.size();
                    mailbox.clear();
                    return null;
                }
                final EventDelivery delivery = mailbox.pollFirst();
                if (delivery != null) {
                    runningDeliveries++;
                }
                return delivery;
            }
        }

        void deliveryFinished() {
            synchronized (monitor) {
                runningDeliveries--;
                monitor.notifyAll();
            }
        }

        /**
         * Ends one drain pass and reschedules when the mailbox refilled while draining, so a
         * delivery enqueued during the last callback is never stranded.
         */
        boolean drainFinished() {
            synchronized (monitor) {
                drainScheduled = false;
                if ((lifecycle == RuntimeEventBroker.OwnerLifecycle.ACTIVE
                                || lifecycle == RuntimeEventBroker.OwnerLifecycle.ENABLING
                                || lifecycle == RuntimeEventBroker.OwnerLifecycle.INITIALIZING)
                        && !mailbox.isEmpty()) {
                    drainScheduled = true;
                    drainToken = new RuntimeCancellationToken();
                    return true;
                }
                monitor.notifyAll();
                return false;
            }
        }

        /** Drops the backlog after the scheduler refused the drain task. */
        void rejectDrain() {
            synchronized (monitor) {
                droppedDeliveries += mailbox.size();
                mailbox.clear();
                drainScheduled = false;
                monitor.notifyAll();
            }
        }

        void cancelPending() {
            droppedDeliveries += mailbox.size();
            mailbox.clear();
            drainToken.cancel();
            monitor.notifyAll();
        }

        boolean quiescent() {
            return !drainScheduled && runningDeliveries == 0;
        }

        long droppedDeliveries() {
            return droppedDeliveries;
        }
    }
}
