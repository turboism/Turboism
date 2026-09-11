package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.runtime.log.RuntimeDiagnostics;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Owns one native edit ingress for the life of one host connection.
 *
 * <p>The host fires undo state-change listeners inline on whichever thread committed the edit, and
 * its listener loop has no exception isolation. The loop therefore only signals this session; the
 * session posts a single drain to the host's own event thread, so every host read and every
 * publication happens after the current host stack, including its undo admission, has unwound.</p>
 *
 * <p>A drain that re-enters an active Turboism operation on the same thread is rejected by
 * {@link dev.turboism.adapter.cubism.lifecycle.SemanticOperationLifecycleCoordinator#publishObserved},
 * and the ingress counts that as a failure rather than propagating it. Rebinding is keyed on the
 * exact native manager identity, so a document switch or a session replacement always closes the
 * old listener before a new one is registered.</p>
 *
 * <p>The native Editor's app-controller singleton does not exist yet when the host session
 * connects: the runtime admits the host as soon as the project-workspace classes are available,
 * which is before the Editor has built its own controllers, so the very first resolve returns
 * nothing. Binding therefore retries for a bounded window instead of treating that first miss as
 * "this host has no history", which would leave the ingress dead for the whole session and look
 * exactly like a host where nobody ever edited anything.</p>
 */
public final class NativeEditIngressSession implements AutoCloseable {

    private static final String COMPONENT = "native-edit-ingress";

    /** How long to keep re-resolving the native document after the connection admits the host. */
    static final RetryPolicy DEFAULT_RETRY = new RetryPolicy(1_000L, 120);

    /**
     * Bounded retry budget for the startup race.
     *
     * @param intervalMillis pause between attempts
     * @param maxAttempts total attempts before the session reports that it stayed inactive
     */
    record RetryPolicy(long intervalMillis, int maxAttempts) {
        RetryPolicy {
            if (intervalMillis <= 0L || maxAttempts <= 0) {
                throw new IllegalArgumentException("retry interval and attempts must be positive");
            }
        }
    }

    private final NativeEditIngress.Publisher publisher;
    private final NativeEditBeginBridge.BeforeSink beforeSink;
    private final Consumer<Runnable> eventThread;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final RetryPolicy retry;
    private final AtomicBoolean retrying = new AtomicBoolean();

    private final Object bindLock = new Object();
    private volatile Pending pending;
    private volatile Thread retryThread;
    private NativeEditIngress ingress;
    private Object manager;
    private long generation = -1;
    private long bindCount;
    private long attachFailureCount;
    private long drainRequestCount;
    private long drainCount;

    /**
     * Creates a session that never resolves a manager by itself.
     *
     * @param publisher   receives confirmed host-observed operations
     * @param beforeSink  receives native edit starts observed at the hook, off the host stack
     * @param eventThread posts a bounded amount of work onto the host event thread; it must never
     *                    run the work inline, because the caller is the host listener loop
     */
    public NativeEditIngressSession(
        final NativeEditIngress.Publisher publisher,
        final NativeEditBeginBridge.BeforeSink beforeSink,
        final Consumer<Runnable> eventThread
    ) {
        this(publisher, beforeSink, eventThread, DEFAULT_RETRY);
    }

    NativeEditIngressSession(
        final NativeEditIngress.Publisher publisher,
        final NativeEditBeginBridge.BeforeSink beforeSink,
        final Consumer<Runnable> eventThread,
        final RetryPolicy retry
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.beforeSink = Objects.requireNonNull(beforeSink, "beforeSink");
        this.eventThread = Objects.requireNonNull(eventThread, "eventThread");
        this.retry = Objects.requireNonNull(retry, "retry");
    }

    /**
     * Binds the session to the active document's native undo manager.
     *
     * <p>Re-binding to the same manager of the same generation is a no-op. Any other binding closes
     * the previous listener first. A resolver that cannot offer the listener selectors leaves the
     * session inactive instead of throwing, because a host session must still connect.</p>
     *
     * @param generation the editor-UI generation that owns this binding
     * @param resolver   the verified resolver of the active connection
     * @return {@code true} when a listener is attached for this binding
     */
    public boolean bind(final long generation, final VerifiedMemberResolver resolver) {
        Objects.requireNonNull(resolver, "resolver");
        if (closed.get()) return false;
        final Object resolved;
        try {
            resolved = EditorHistoryNativeBindings.undoManager(resolver);
        } catch (RuntimeException unavailable) {
            // The Editor builds its own controllers later than the host connection is admitted, so
            // the first resolve legitimately finds nothing. Treating that as "this host has no
            // history" would leave the ingress dead for the whole session.
            scheduleRetry(generation, resolver, unavailable);
            return false;
        }
        synchronized (bindLock) {
            if (closed.get()) return false;
            pending = null;
            if (ingress != null && this.manager == resolved && this.generation == generation) {
                return true;
            }
            closeLocked();
            final NativeEditIngress candidate = new NativeEditIngress(
                resolver,
                resolved,
                publisher,
                this::requestDrain
            );
            try {
                candidate.attach();
            } catch (RuntimeException refused) {
                attachFailureCount++;
                candidate.close();
                RuntimeDiagnostics.warn(
                    COMPONENT,
                    "Native edit ingress listener was refused on "
                        + resolved.getClass().getName() + ": " + describe(refused)
                );
                return false;
            }
            ingress = candidate;
            // The hook fires from the host's edit entry, which is not the listener's thread
            // contract, so the bridge only queues and asks this session for the same coalesced
            // drain. Binding it here keeps one drain order: observed starts first, then the
            // observation that the commit produced.
            NativeEditBeginBridge.bind(beforeSink, this::requestDrain);
            manager = resolved;
            this.generation = generation;
            bindCount++;
            RuntimeDiagnostics.info(
                COMPONENT,
                "Native edit ingress listening on " + resolved.getClass().getName()
                    + " for editor-UI generation " + generation
            );
            return true;
        }
    }

    /**
     * Detaches the native listener for the current connection but keeps the session reusable.
     *
     * <p>Cleanup runs on every disconnect, including a temporary drop to safe mode that a later
     * connect repairs, so detaching must not be terminal.</p>
     */
    public void deactivate() {
        cancelRetry();
        synchronized (bindLock) {
            closeLocked();
        }
    }

    /** Detaches the native listener permanently; later {@link #bind} calls are refused. */
    @Override
    public void close() {
        closed.set(true);
        cancelRetry();
        synchronized (bindLock) {
            closeLocked();
        }
    }

    /** {@return whether a native listener is currently attached} */
    public boolean isAttached() {
        synchronized (bindLock) {
            return ingress != null;
        }
    }

    /** {@return the number of successful bindings performed by this session} */
    public long bindCount() {
        synchronized (bindLock) {
            return bindCount;
        }
    }

    /** {@return the number of bindings refused because the listener selectors were unavailable} */
    public long attachFailureCount() {
        synchronized (bindLock) {
            return attachFailureCount;
        }
    }

    /** {@return the number of drains posted onto the host event thread} */
    public long notificationCount() {
        synchronized (bindLock) {
            return ingress == null ? 0 : ingress.notificationCount();
        }
    }

    /** {@return the number of drains posted onto the host event thread} */
    public long drainRequestCount() {
        synchronized (bindLock) {
            return drainRequestCount;
        }
    }

    /** {@return the number of drains actually executed} */
    public long drainCount() {
        synchronized (bindLock) {
            return drainCount;
        }
    }

    /**
     * Classifies and publishes everything observed since the previous drain.
     *
     * <p>Runs on the host event thread and never throws: a publication failure is counted by the
     * ingress. The observer's own sink failure is the only exception this reports, and it is the
     * ingress owner's contract that it does not throw.</p>
     */
    public void drain() {
        drainScheduled.set(false);
        final NativeEditIngress current;
        synchronized (bindLock) {
            current = ingress;
        }
        if (current == null) return;
        try {
            NativeEditBeginBridge.drain(beforeSink);
            current.drain();
            synchronized (bindLock) {
                drainCount++;
            }
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (RuntimeException ignored) {
            // A drain failure must not escape onto the host event thread.
        }
    }

    /** {@return the number of native edit starts the hook handed to this session} */
    public long observedStartCount() {
        return NativeEditBeginBridge.drainedCount();
    }

    private void requestDrain() {
        if (closed.get()) return;
        if (!drainScheduled.compareAndSet(false, true)) return;
        synchronized (bindLock) {
            drainRequestCount++;
        }
        try {
            eventThread.accept(this::drain);
        } catch (VirtualMachineError fatal) {
            throw fatal;
        } catch (Throwable refused) {
            // The host listener loop has no exception isolation.
            drainScheduled.set(false);
        }
    }

    private static String describe(final RuntimeException failure) {
        final String message = failure.getMessage();
        final String type = failure.getClass().getName();
        return message == null || message.isBlank() ? type : type + ": " + message;
    }

    /**
     * Starts the bounded retry for a binding whose native document is not available yet.
     *
     * <p>At most one retry thread exists at a time and every request supersedes the previous one,
     * so a later connection never races an older attempt onto the ingress.</p>
     */
    private void scheduleRetry(
        final long generation,
        final VerifiedMemberResolver resolver,
        final RuntimeException cause
    ) {
        synchronized (bindLock) {
            if (closed.get()) return;
            pending = new Pending(generation, cause);
        }
        if (!retrying.compareAndSet(false, true)) return;
        final Thread thread = new Thread(
            () -> retryUntilBound(generation, resolver),
            "turboism-native-edit-ingress-bind"
        );
        thread.setDaemon(true);
        retryThread = thread;
        thread.start();
    }

    private void retryUntilBound(final long generation, final VerifiedMemberResolver resolver) {
        RuntimeException lastFailure = null;
        try {
            for (int attempt = 1; attempt <= retry.maxAttempts(); attempt++) {
                Thread.sleep(retry.intervalMillis());
                final Pending current = pending;
                if (closed.get() || current == null || current.generation() != generation) return;
                if (bind(generation, resolver)) {
                    RuntimeDiagnostics.info(
                        COMPONENT,
                        "Native edit ingress attached on deferred attempt " + attempt
                            + " once the Editor document was ready"
                    );
                    return;
                }
                lastFailure = current.cause();
            }
            RuntimeDiagnostics.warn(
                COMPONENT,
                "Native edit ingress stayed inactive after " + retry.maxAttempts()
                    + " deferred attempts: " + describe(lastFailure)
            );
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            synchronized (bindLock) {
                if (pending != null && pending.generation() == generation) {
                    pending = null;
                }
            }
            retryThread = null;
            retrying.set(false);
        }
    }

    private void cancelRetry() {
        synchronized (bindLock) {
            pending = null;
        }
        final Thread thread = retryThread;
        retryThread = null;
        if (thread != null) thread.interrupt();
        retrying.set(false);
    }

    /** One binding request whose native document could not be resolved yet. */
    private record Pending(long generation, RuntimeException cause) {
    }

    private void closeLocked() {
        NativeEditBeginBridge.unbind();
        final NativeEditIngress current = ingress;
        ingress = null;
        manager = null;
        generation = -1;
        drainScheduled.set(false);
        if (current != null) current.close();
    }
}
