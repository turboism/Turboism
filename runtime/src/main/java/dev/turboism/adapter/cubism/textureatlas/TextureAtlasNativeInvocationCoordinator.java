package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.mapping.verification.VerifiedMemberResolver;

import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Connection-owned same-thread scope for one native texture-atlas packing invocation. */
public final class TextureAtlasNativeInvocationCoordinator implements AutoCloseable {

    private final Object ownerToken = new Object();
    private final ThreadLocal<Invocation> active = new ThreadLocal<>();
    private VerifiedTextureAtlasNativeInvocationAdapter adapter;
    private long generation;
    private boolean closed;

    /**
     * Attaches the verified resolver used to open native packing sessions.
     *
     * <p>Bumps the generation, so any invocation still in flight from a previous connection is
     * no longer recognised as current.
     *
     * @param resolver the verified member resolver backing the native adapter; must not be null
     * @throws NullPointerException if {@code resolver} is null
     * @throws IllegalStateException if this coordinator has been closed
     */
    public synchronized void connect(final VerifiedMemberResolver resolver) {
        requireOpen();
        adapter = new VerifiedTextureAtlasNativeInvocationAdapter(
            Objects.requireNonNull(resolver, "resolver")
        );
        generation++;
    }

    /**
     * Wraps a callback as a native ingress predicate that runs it inside a single-threaded
     * invocation scope bound to the receiver.
     *
     * <p>The returned predicate answers {@code false} without running the callback when the
     * coordinator is closed or unconnected, when the receiver is null, or when this thread is
     * already inside an invocation - invocations never nest. It answers {@code true} only when
     * the callback returns true and the invocation was marked handled; in every other case,
     * including a callback that throws, the native session is restored and {@code false} is
     * returned so the host proceeds with its own behaviour. A connection change while the callback
     * is running also cancels the handled result and restores the native session before falling back.
     * Exceptions from the callback do not propagate to the host.
     *
     * @param callback the work to run inside the invocation scope; must not be null
     * @return a predicate the native hook can call with the packing receiver
     * @throws NullPointerException if {@code callback} is null
     */
    public Predicate<Object> ingress(final BooleanSupplier callback) {
        Objects.requireNonNull(callback, "callback");
        return receiver -> invoke(receiver, callback);
    }

    synchronized Optional<Invocation> current() {
        final Invocation invocation = active.get();
        return invocation != null && invocation.valid(ownerToken, generation, Thread.currentThread())
            ? Optional.of(invocation)
            : Optional.empty();
    }

    private boolean invoke(final Object receiver, final BooleanSupplier callback) {
        final VerifiedTextureAtlasNativeInvocationAdapter selected;
        final long currentGeneration;
        synchronized (this) {
            if (closed || adapter == null || receiver == null || active.get() != null) return false;
            selected = adapter;
            currentGeneration = generation;
        }
        final Invocation invocation;
        try {
            invocation = selected.open(ownerToken, currentGeneration, receiver, Thread.currentThread());
        } catch (RuntimeException failure) {
            return false;
        }
        active.set(invocation);
        boolean callbackSucceeded = false;
        boolean invocationCurrent = false;
        boolean result = false;
        try {
            callbackSucceeded = callback.getAsBoolean();
            invocationCurrent = currentInvocation(invocation);
            result = callbackSucceeded && invocation.handled() && invocationCurrent;
            return result;
        } catch (RuntimeException | Error failure) {
            return false;
        } finally {
            active.remove();
            if (!callbackSucceeded || !invocation.handled() || !invocationCurrent) {
                invocation.restore();
            }
        }
    }

    private synchronized boolean currentInvocation(final Invocation invocation) {
        return !closed && adapter != null
            && invocation.valid(ownerToken, generation, Thread.currentThread());
    }

    /**
     * Detaches the native adapter and bumps the generation, so in-flight invocations stop being
     * recognised as current and later ingress calls decline rather than reach the host. Leaves
     * the coordinator open for a subsequent {@link #connect}; a no-op once closed.
     */
    public synchronized void deactivate() {
        if (closed) return;
        adapter = null;
        generation++;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        adapter = null;
        generation++;
        closed = true;
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Texture atlas native invocation coordinator is closed.");
    }

    static final class Invocation {
        private final Object ownerToken;
        private final long generation;
        private final Object receiver;
        private final Thread thread;
        private final VerifiedTextureAtlasNativeInvocationAdapter.Session session;
        private boolean handled;

        Invocation(
            final Object ownerToken,
            final long generation,
            final Object receiver,
            final Thread thread,
            final VerifiedTextureAtlasNativeInvocationAdapter.Session session
        ) {
            this.ownerToken = ownerToken;
            this.generation = generation;
            this.receiver = receiver;
            this.thread = thread;
            this.session = session;
        }

        boolean valid(final Object owner, final long expectedGeneration, final Thread expectedThread) {
            return ownerToken == owner && generation == expectedGeneration && thread == expectedThread;
        }

        Object receiver() { return receiver; }
        VerifiedTextureAtlasNativeInvocationAdapter.Session session() { return session; }
        boolean handled() { return handled; }
        void handled(final boolean value) { handled = value; }
        void restore() { session.restore(); }
    }
}
