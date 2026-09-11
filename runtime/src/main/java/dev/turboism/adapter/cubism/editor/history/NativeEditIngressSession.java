package dev.turboism.adapter.cubism.editor.history;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

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
 */
public final class NativeEditIngressSession implements AutoCloseable {

    private final NativeEditIngress.Publisher publisher;
    private final Consumer<Runnable> eventThread;
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    private final Object bindLock = new Object();
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
     * @param eventThread posts a bounded amount of work onto the host event thread; it must never
     *                    run the work inline, because the caller is the host listener loop
     */
    public NativeEditIngressSession(
        final NativeEditIngress.Publisher publisher,
        final Consumer<Runnable> eventThread
    ) {
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.eventThread = Objects.requireNonNull(eventThread, "eventThread");
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
            return false;
        }
        synchronized (bindLock) {
            if (closed.get()) return false;
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
                return false;
            }
            ingress = candidate;
            manager = resolved;
            this.generation = generation;
            bindCount++;
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
        synchronized (bindLock) {
            closeLocked();
        }
    }

    /** Detaches the native listener permanently; later {@link #bind} calls are refused. */
    @Override
    public void close() {
        closed.set(true);
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

    private void closeLocked() {
        final NativeEditIngress current = ingress;
        ingress = null;
        manager = null;
        generation = -1;
        drainScheduled.set(false);
        if (current != null) current.close();
    }
}
