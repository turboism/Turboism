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

    public synchronized void connect(final VerifiedMemberResolver resolver) {
        requireOpen();
        adapter = new VerifiedTextureAtlasNativeInvocationAdapter(
            Objects.requireNonNull(resolver, "resolver")
        );
        generation++;
    }

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
        try {
            callbackSucceeded = callback.getAsBoolean();
            return callbackSucceeded && invocation.handled();
        } catch (RuntimeException | Error failure) {
            return false;
        } finally {
            active.remove();
            if (!callbackSucceeded || !invocation.handled()) invocation.restore();
        }
    }

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
