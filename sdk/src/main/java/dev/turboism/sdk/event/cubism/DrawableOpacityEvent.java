package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic ArtMesh opacity write event family. */
public sealed interface DrawableOpacityEvent extends TurboismEvent
    permits DrawableOpacityEvent.Before, DrawableOpacityEvent.On, DrawableOpacityEvent.After {

    Drawable drawable();

    final class Before implements DrawableOpacityEvent {
        private final Drawable drawable;
        private final float requestedOpacity;
        private final CallbackScope callbackScope;
        private float opacity;

        public Before(
            final Drawable drawable,
            final float requestedOpacity,
            final float opacity
        ) {
            this(drawable, requestedOpacity, opacity, null);
        }

        private Before(
            final Drawable drawable,
            final float requestedOpacity,
            final float opacity,
            final CallbackScope callbackScope
        ) {
            this.drawable = Objects.requireNonNull(drawable, "drawable");
            this.requestedOpacity = requestedOpacity;
            this.opacity = opacity;
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted opacity edit. */
        public static Callback openCallback(
            final Drawable drawable,
            final float requestedOpacity,
            final float opacity
        ) {
            return new Callback(drawable, requestedOpacity, opacity);
        }

        @Override public Drawable drawable() { return drawable; }
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
                final Drawable drawable,
                final float requestedOpacity,
                final float opacity
            ) {
                event = new Before(drawable, requestedOpacity, opacity, scope);
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
                        "Drawable opacity before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    record On(Drawable drawable, float oldOpacity, float newOpacity)
        implements DrawableOpacityEvent {
        public On { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }

    record After(Drawable drawable, float finalOpacity) implements DrawableOpacityEvent {
        public After { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }
}
