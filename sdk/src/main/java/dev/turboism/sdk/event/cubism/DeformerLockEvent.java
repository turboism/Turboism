package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Deformer lock write event family. */
@PreviewApi
public sealed interface DeformerLockEvent extends TurboismEvent
    permits DeformerLockEvent.Before, DeformerLockEvent.On, DeformerLockEvent.After {

    Deformer deformer();

    @PreviewApi
    final class Before implements DeformerLockEvent {
        private final Deformer deformer;
        private final boolean requestedLocked;
        private final CallbackScope callbackScope;
        private boolean locked;

        public Before(
            final Deformer deformer,
            final boolean requestedLocked,
            final boolean locked
        ) {
            this(deformer, requestedLocked, locked, null);
        }

        private Before(
            final Deformer deformer,
            final boolean requestedLocked,
            final boolean locked,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedLocked = requestedLocked;
            this.locked = locked;
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted lock-state edit. */
        public static Callback openCallback(
            final Deformer deformer,
            final boolean requestedLocked,
            final boolean locked
        ) {
            return new Callback(deformer, requestedLocked, locked);
        }

        @Override public Deformer deformer() { return deformer; }
        public boolean requestedLocked() { return requestedLocked; }
        /** Returns the candidate lock-state value that will be applied. */
        public boolean locked() { return locked; }

        /** Replaces the candidate lock-state value for the current callback. */
        public void setLocked(final boolean locked) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.locked = locked;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Deformer deformer,
                final boolean requestedLocked,
                final boolean locked
            ) {
                event = new Before(deformer, requestedLocked, locked, scope);
            }

            /** Returns the mutable event while this callback scope remains open. */
            public Before event() {
                scope.requireOpen();
                return event;
            }

            @Override public void close() { scope.close(); }
        }

        private static final class CallbackScope {
            private final Thread ownerThread;
            private boolean open = true;

            private CallbackScope(final Thread ownerThread) { this.ownerThread = ownerThread; }

            private void requireOpen() {
                if (!open || Thread.currentThread() != ownerThread) {
                    throw new IllegalStateException(
                        "Deformer lock before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    @PreviewApi
    record On(Deformer deformer, boolean oldLocked, boolean newLocked)
        implements DeformerLockEvent {
        public On { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }

    @PreviewApi
    record After(Deformer deformer, boolean finalLocked) implements DeformerLockEvent {
        public After { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }
}
