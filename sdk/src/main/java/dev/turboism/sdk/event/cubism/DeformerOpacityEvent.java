package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Deformer opacity write event family. */
public sealed interface DeformerOpacityEvent extends TurboismEvent
    permits DeformerOpacityEvent.Before,
            DeformerOpacityEvent.On,
            DeformerOpacityEvent.After {

    Deformer deformer();

    final class Before implements DeformerOpacityEvent {
        private final Deformer deformer;
        private final float requestedOpacity;
        private final CallbackScope callbackScope;
        private float opacity;

        public Before(
            final Deformer deformer,
            final float requestedOpacity,
            final float opacity
        ) {
            this(deformer, requestedOpacity, opacity, null);
        }

        private Before(
            final Deformer deformer,
            final float requestedOpacity,
            final float opacity,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedOpacity = requestedOpacity;
            this.opacity = opacity;
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted opacity edit. */
        public static Callback openCallback(
            final Deformer deformer,
            final float requestedOpacity,
            final float opacity
        ) {
            return new Callback(deformer, requestedOpacity, opacity);
        }

        @Override public Deformer deformer() { return deformer; }
        public float requestedOpacity() { return requestedOpacity; }
        /** Returns the candidate opacity value that will be applied. */
        public float opacity() { return opacity; }

        /** Replaces the candidate opacity value for the current callback. */
        public void setOpacity(final float opacity) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.opacity = opacity;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Deformer deformer,
                final float requestedOpacity,
                final float opacity
            ) {
                event = new Before(deformer, requestedOpacity, opacity, scope);
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
                        "Deformer opacity before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    record On(Deformer deformer, float oldOpacity, float newOpacity)
        implements DeformerOpacityEvent {
        public On { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }

    record After(Deformer deformer, float finalOpacity) implements DeformerOpacityEvent {
        public After { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }
}
