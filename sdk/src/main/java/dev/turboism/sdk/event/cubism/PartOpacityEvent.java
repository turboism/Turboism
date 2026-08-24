package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Part opacity set-value event family. */
public sealed interface PartOpacityEvent extends TurboismEvent
    permits PartOpacityEvent.Before, PartOpacityEvent.On, PartOpacityEvent.After {

    /** @return the detached Part projection participating in the operation */
    Part part();

    /** Synchronous state published before the host opacity write. */
    final class Before implements PartOpacityEvent {
        private final Part part;
        private final float requestedOpacity;
        private final CallbackScope callbackScope;
        private float opacity;

        public Before(
            final Part part,
            final float requestedOpacity,
            final float opacity
        ) {
            this(part, requestedOpacity, opacity, null);
        }

        private Before(
            final Part part,
            final float requestedOpacity,
            final float opacity,
            final CallbackScope callbackScope
        ) {
            this.part = Objects.requireNonNull(part, "part");
            this.requestedOpacity = requestedOpacity;
            this.opacity = opacity;
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted opacity edit. */
        public static Callback openCallback(
            final Part part,
            final float requestedOpacity,
            final float opacity
        ) {
            return new Callback(part, requestedOpacity, opacity);
        }

        @Override public Part part() { return part; }
        public float requestedOpacity() { return requestedOpacity; }
        /** Returns the candidate opacity value that will be applied. */
        public float opacity() { return opacity; }

        /** Replaces the candidate opacity value for the current callback. */
        public void setOpacity(final float opacity) {
            if (callbackScope != null) {
                callbackScope.requireOpen();
            }
            this.opacity = opacity;
        }

        /** One Runtime-owned mutable callback scope. */
        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Part part,
                final float requestedOpacity,
                final float opacity
            ) {
                event = new Before(part, requestedOpacity, opacity, scope);
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

            private CallbackScope(final Thread ownerThread) {
                this.ownerThread = ownerThread;
            }

            private void requireOpen() {
                if (!open || Thread.currentThread() != ownerThread) {
                    throw new IllegalStateException(
                        "Part opacity before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    /** State published after a successful opacity write that changed the value. */
    record On(Part part, float oldOpacity, float newOpacity) implements PartOpacityEvent {
        public On {
            part = Objects.requireNonNull(part, "part");
        }
    }

    /** State published after every successful opacity write. */
    record After(Part part, float finalOpacity) implements PartOpacityEvent {
        public After {
            part = Objects.requireNonNull(part, "part");
        }
    }
}
