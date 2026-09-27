package dev.turboism.adapter.cubism;

import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerHandle;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.sdk.cubism.event.SelectionChangedEvent;
import dev.turboism.sdk.cubism.service.query.SelectionSummary;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Session-owned, query-independent publisher of {@link SelectionChangedEvent}.
 *
 * <p>The publisher schedules a fixed {@link #DEFAULT_INTERVAL interval} tick on the shared
 * {@link RuntimeScheduler} — no thread is created per subscription. A tick never touches the
 * host itself: it dispatches one observation onto the bounded serialized
 * {@link SharedAsyncHostReadLane} every plugin-scoped host read already uses, so a slow or
 * unresponsive host read cannot stall unrelated runtime timers or session close. At most one
 * physical observation is in flight at any time; a lane that is saturated or closed simply
 * skips the interval.</p>
 *
 * <p>Each completed observation commits through the runtime-owned baseline
 * ({@link RuntimeEventBroker#observationBaseline}) shared with the query path, via
 * {@link SelectionObservation#commit}: identical state observed through either path never
 * produces duplicate events, the first observation only establishes the baseline, and a
 * strictly older same-source revision cannot regress a newer committed baseline. The observed
 * identity excludes {@code activeProjectId} — see {@link SelectionSummaries#observedIdentity}.</p>
 *
 * <p>Demand gating: the poll chain runs only while the broker reports at least one active
 * subscription capable of receiving {@link SelectionChangedEvent}. When the last subscription
 * closes the chain parks itself; a later subscription re-activates it through
 * {@link #signalDemand()}, which the broker invokes from its subscription-demand hook.
 * {@link #close()} stops the loop for session teardown and fences any in-flight read from
 * committing or publishing afterwards.</p>
 */
public final class SelectionObservationPublisher implements AutoCloseable {

    /** Fixed interval between completed observations while demand exists. */
    public static final Duration DEFAULT_INTERVAL = Duration.ofMillis(250);

    private final HostSnapshotSource source;
    private final SharedAsyncHostReadLane hostReads;
    private final RuntimeScheduler scheduler;
    private final RuntimeEventBroker eventBroker;
    private final AtomicReference<SelectionObservation> observedSelection;
    private final Duration interval;
    private final Object lifecycle = new Object();
    private final ImmutableSnapshotFactory snapshots = new ImmutableSnapshotFactory();
    private final AtomicBoolean readInFlight = new AtomicBoolean();

    private RuntimeTimerHandle pendingTimer;
    private SelectionProbe lastProbe;
    private long lastProbeVersion = -1L;
    private boolean closed;

    public SelectionObservationPublisher(
        final HostSnapshotSource source,
        final SharedAsyncHostReadLane hostReads,
        final RuntimeScheduler scheduler,
        final RuntimeEventBroker eventBroker,
        final AtomicReference<SelectionObservation> observedSelection
    ) {
        this(source, hostReads, scheduler, eventBroker, observedSelection, DEFAULT_INTERVAL);
    }

    SelectionObservationPublisher(
        final HostSnapshotSource source,
        final SharedAsyncHostReadLane hostReads,
        final RuntimeScheduler scheduler,
        final RuntimeEventBroker eventBroker,
        final AtomicReference<SelectionObservation> observedSelection,
        final Duration interval
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.hostReads = Objects.requireNonNull(hostReads, "hostReads");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.eventBroker = Objects.requireNonNull(eventBroker, "eventBroker");
        this.observedSelection = Objects.requireNonNull(observedSelection, "observedSelection");
        this.interval = Objects.requireNonNull(interval, "interval");
        if (interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("interval must be positive");
        }
        this.eventBroker.addSubscriptionDemandListener(this::onSubscriptionDemand);
    }

    /**
     * Re-evaluates demand and starts the poll chain when subscriptions exist. Idempotent;
     * invoked when a relevant subscription is added and once at session wiring time.
     */
    public void signalDemand() {
        synchronized (lifecycle) {
            if (closed
                || pendingTimer != null
                || readInFlight.get()
                || !eventBroker.hasObserversFor(SelectionChangedEvent.class)) {
                return;
            }
            scheduleNext();
        }
    }

    private void onSubscriptionDemand(final Class<?> subscribedType) {
        if (subscribedType.isAssignableFrom(SelectionChangedEvent.class)) {
            signalDemand();
        }
    }

    private void scheduleNext() {
        final RuntimeTimerSubmission submission = scheduler.schedule(interval, this::tick);
        if (submission.accepted()) {
            pendingTimer = submission.handle();
        }
        // A rejected submission (closed scheduler / exhausted timer budget) leaves
        // pendingTimer null: the loop stays parked until the next demand signal.
    }

    private void tick() {
        synchronized (lifecycle) {
            pendingTimer = null;
            if (closed
                || !eventBroker.hasObserversFor(SelectionChangedEvent.class)) {
                // Park: no active subscription can receive the event. The next
                // relevant subscription re-arms the loop via signalDemand().
                return;
            }
        }
        dispatchObservation();
    }

    private void dispatchObservation() {
        if (!readInFlight.compareAndSet(false, true)) {
            // The previous read has not finished; wait one interval rather than
            // queueing host work — the lane serializes anyway.
            armNext();
            return;
        }
        if (!hostReads.offerSessionObservation(this::observeSafely)) {
            readInFlight.set(false);
            armNext();
        }
    }

    private void observeSafely() {
        try {
            observeOnce();
        } catch (Throwable ignored) {
            // A detached or mid-transition host must not kill the observer;
            // the probe retries on the next interval.
        } finally {
            readInFlight.set(false);
            armNext();
        }
    }

    private void armNext() {
        synchronized (lifecycle) {
            if (closed
                || pendingTimer != null
                || !eventBroker.hasObserversFor(SelectionChangedEvent.class)) {
                return;
            }
            scheduleNext();
        }
    }

    /** Runs on the shared host-read lane worker; never on the scheduler timer. */
    private void observeOnce() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
        }
        // No isHostPresent() gate: HostSnapshotSource implementations answer empty
        // when detached, so closing the last document/project must still be
        // observed as a transition to the empty selection, not skipped.
        // The token is sampled before the field reads: it lower-bounds the revision
        // this observation reflects, which is what the commit's same-source stale
        // guard needs.
        final long version = source.invalidationToken();
        final HostSnapshotSource.HostDocument document =
            source.activeDocument().orElse(null);
        final HostSnapshotSource.HostModel model = source.activeModel().orElse(null);
        final HostSnapshotSource.HostSelection selection = source.selection();
        final SelectionProbe probe = new SelectionProbe(
            document == null ? null : document.documentId(),
            model == null ? null : model.modelId(),
            selection == null ? List.of() : selection.selectedObjectIds()
        );
        // Commit and publication sit under the lifecycle monitor so close() is a
        // hard fence: a read finishing during teardown can neither commit nor
        // publish afterwards.
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            if (probe.equals(lastProbe) && version == lastProbeVersion) {
                return;
            }
            // Project identity is deliberately absent: it is permission-gated per plugin
            // and never participates in observed events (SelectionSummaries.observedIdentity).
            final SelectionSummary current = SelectionSummaries.observedIdentity(
                SelectionSummaries.fromRuntimeSnapshot(snapshots.runtime(
                    Optional.empty(),
                    Optional.ofNullable(document),
                    Optional.ofNullable(model),
                    selection
                ))
            );
            // lastProbe is marked only after classification and the commit/publish
            // attempt complete — a failure here must not permanently suppress the
            // next observation of the same host state.
            SelectionObservation.commit(
                observedSelection,
                new SelectionObservation(source, version, current),
                (previous, identity) -> eventBroker.publishRuntime(
                    new SelectionChangedEvent(previous, identity)
                )
            );
            lastProbe = probe;
            lastProbeVersion = version;
        }
    }

    @Override
    public void close() {
        final RuntimeTimerHandle timer;
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            timer = pendingTimer;
            pendingTimer = null;
        }
        if (timer != null) {
            timer.cancel();
        }
    }

    /** Cheap raw host probe compared before any snapshot classification work runs. */
    private record SelectionProbe(
        String documentId,
        String modelId,
        List<String> selectedObjectIds
    ) {
        private SelectionProbe {
            selectedObjectIds = List.copyOf(
                Objects.requireNonNull(selectedObjectIds, "selectedObjectIds")
            );
        }
    }
}
