package dev.turboism.hook.ingress;

import dev.turboism.sdk.event.EventBus;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * Bounded MPSC mailbox with an explicitly claimed single consumer.
 *
 * <p>The mailbox only transports normalized hook metadata and SDK event DTOs.
 * Producers never invoke downstream processing and reject the newest entry when
 * capacity is exhausted.</p>
 */
final class BoundedHookEventMailbox {

    enum OfferOutcome { ACCEPTED, QUEUE_FULL, MAILBOX_CLOSED }

    enum DrainOutcome { DRAINED, EMPTY, BUSY, DOWNSTREAM_FAILED }

    enum DiagnosticCode {
        QUEUE_FULL,
        MAILBOX_CLOSED,
        PENDING_DROPPED_ON_CLOSE,
        DOWNSTREAM_FAILED
    }

    record Entry(HookIngressSpec spec, EventBus.TurboismEvent event) {
        Entry {
            Objects.requireNonNull(spec, "spec");
            Objects.requireNonNull(event, "event");
        }
    }

    record Diagnostic(DiagnosticCode code, String message, String hookId, long count) {
        Diagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
            Objects.requireNonNull(hookId, "hookId");
            if (count <= 0) {
                throw new IllegalArgumentException("count must be positive");
            }
        }
    }

    record DrainResult(DrainOutcome outcome, boolean diagnosticSinkFailed) {
        DrainResult {
            Objects.requireNonNull(outcome, "outcome");
        }
    }

    record Snapshot(
        int capacity,
        int pending,
        boolean closed,
        long accepted,
        long drained,
        long queueFullRejected,
        long closedRejected,
        long pendingDroppedOnClose,
        long downstreamFailures,
        long diagnosticSinkFailures,
        long busyDrains
    ) { }

    static final String MAILBOX_DIAGNOSTIC_ID = "hook-mailbox";

    private static final String QUEUE_FULL_MESSAGE = "Hook event mailbox capacity exhausted; newest entry rejected.";
    private static final String CLOSED_MESSAGE = "Hook event mailbox is closed; entry rejected.";
    private static final String DROPPED_MESSAGE = "Pending hook events dropped while closing mailbox.";
    private static final String DOWNSTREAM_MESSAGE = "Hook event downstream processing failed.";

    private final int capacity;
    private final Consumer<Entry> downstream;
    private final Consumer<Diagnostic> diagnosticSink;
    private final ArrayDeque<Entry> pending;
    private final Object stateLock = new Object();
    private final AtomicBoolean consumerClaimed = new AtomicBoolean();
    private final LongAdder accepted = new LongAdder();
    private final LongAdder drained = new LongAdder();
    private final LongAdder queueFullRejected = new LongAdder();
    private final LongAdder closedRejected = new LongAdder();
    private final LongAdder pendingDroppedOnClose = new LongAdder();
    private final LongAdder downstreamFailures = new LongAdder();
    private final LongAdder diagnosticSinkFailures = new LongAdder();
    private final LongAdder busyDrains = new LongAdder();

    private boolean closed;

    BoundedHookEventMailbox(int capacity, Consumer<Entry> downstream, Consumer<Diagnostic> diagnosticSink) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.downstream = Objects.requireNonNull(downstream, "downstream");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.pending = new ArrayDeque<>(capacity);
    }

    OfferOutcome offer(HookIngressSpec spec, EventBus.TurboismEvent event) {
        Entry entry = new Entry(spec, event);
        Diagnostic diagnostic = null;
        OfferOutcome outcome;
        synchronized (stateLock) {
            if (closed) {
                closedRejected.increment();
                outcome = OfferOutcome.MAILBOX_CLOSED;
                diagnostic = diagnostic(DiagnosticCode.MAILBOX_CLOSED, CLOSED_MESSAGE, spec.hookId(), 1);
            } else if (pending.size() == capacity) {
                queueFullRejected.increment();
                outcome = OfferOutcome.QUEUE_FULL;
                diagnostic = diagnostic(DiagnosticCode.QUEUE_FULL, QUEUE_FULL_MESSAGE, spec.hookId(), 1);
            } else {
                pending.addLast(entry);
                accepted.increment();
                outcome = OfferOutcome.ACCEPTED;
            }
        }
        if (diagnostic != null) {
            emitDiagnostic(diagnostic);
        }
        return outcome;
    }

    DrainResult drainOne() {
        if (!consumerClaimed.compareAndSet(false, true)) {
            busyDrains.increment();
            return new DrainResult(DrainOutcome.BUSY, false);
        }
        try {
            Entry entry;
            synchronized (stateLock) {
                entry = pending.pollFirst();
            }
            if (entry == null) {
                return new DrainResult(DrainOutcome.EMPTY, false);
            }
            try {
                downstream.accept(entry);
                drained.increment();
                return new DrainResult(DrainOutcome.DRAINED, false);
            } catch (RuntimeException downstreamFailure) {
                downstreamFailures.increment();
                boolean sinkFailed = !emitDiagnostic(diagnostic(
                    DiagnosticCode.DOWNSTREAM_FAILED,
                    DOWNSTREAM_MESSAGE,
                    entry.spec().hookId(),
                    1
                ));
                return new DrainResult(DrainOutcome.DOWNSTREAM_FAILED, sinkFailed);
            } catch (Error downstreamFailure) {
                downstreamFailures.increment();
                throw downstreamFailure;
            }
        } finally {
            consumerClaimed.set(false);
        }
    }

    int close() {
        int dropped;
        synchronized (stateLock) {
            if (closed) {
                return 0;
            }
            closed = true;
            dropped = pending.size();
            pending.clear();
            pendingDroppedOnClose.add(dropped);
        }
        if (dropped > 0) {
            emitDiagnostic(diagnostic(
                DiagnosticCode.PENDING_DROPPED_ON_CLOSE,
                DROPPED_MESSAGE,
                MAILBOX_DIAGNOSTIC_ID,
                dropped
            ));
        }
        return dropped;
    }

    Snapshot snapshot() {
        synchronized (stateLock) {
            return new Snapshot(
                capacity,
                pending.size(),
                closed,
                accepted.sum(),
                drained.sum(),
                queueFullRejected.sum(),
                closedRejected.sum(),
                pendingDroppedOnClose.sum(),
                downstreamFailures.sum(),
                diagnosticSinkFailures.sum(),
                busyDrains.sum()
            );
        }
    }

    Diagnostic diagnostic(DiagnosticCode code, String message, String hookId, long count) {
        return new Diagnostic(code, message, hookId, count);
    }

    boolean emitDiagnostic(Diagnostic diagnostic) {
        try {
            diagnosticSink.accept(diagnostic);
            return true;
        } catch (RuntimeException sinkFailure) {
            diagnosticSinkFailures.increment();
            return false;
        } catch (Error sinkFailure) {
            diagnosticSinkFailures.increment();
            throw sinkFailure;
        }
    }
}
