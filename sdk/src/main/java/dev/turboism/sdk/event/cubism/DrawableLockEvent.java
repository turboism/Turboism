package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic ArtMesh lock write event family. */
@PreviewApi
public sealed interface DrawableLockEvent extends TurboismEvent
    permits DrawableLockEvent.Before, DrawableLockEvent.On, DrawableLockEvent.After {

    Drawable drawable();

    @PreviewApi
    final class Before implements DrawableLockEvent {
        private final Drawable drawable;
        private final boolean requestedLocked;
        private final CallbackScope callbackScope;
        private boolean locked;

        public Before(
            final Drawable drawable,
            final boolean requestedLocked,
            final boolean locked
        ) {
            this(drawable, requestedLocked, locked, null);
        }

        private Before(
            final Drawable drawable,
            final boolean requestedLocked,
            final boolean locked,
            final CallbackScope callbackScope
        ) {
            this.drawable = Objects.requireNonNull(drawable, "drawable");
            this.requestedLocked = requestedLocked;
            this.locked = locked;
            this.callbackScope = callbackScope;
        }

        public static Callback openCallback(
            final Drawable drawable,
            final boolean requestedLocked,
            final boolean locked
        ) {
            return new Callback(drawable, requestedLocked, locked);
        }

        @Override public Drawable drawable() { return drawable; }
        public boolean requestedLocked() { return requestedLocked; }
        public boolean locked() { return locked; }

        public void setLocked(final boolean locked) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.locked = locked;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Drawable drawable,
                final boolean requestedLocked,
                final boolean locked
            ) {
                event = new Before(drawable, requestedLocked, locked, scope);
            }

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
                        "Drawable lock before-event mutation is outside its callback scope."
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
    record On(Drawable drawable, boolean oldLocked, boolean newLocked)
        implements DrawableLockEvent {
        public On { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }

    @PreviewApi
    record After(Drawable drawable, boolean finalLocked) implements DrawableLockEvent {
        public After { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }
}
