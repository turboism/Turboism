package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Deformer opacity write event family. */
@PreviewApi
public sealed interface DeformerOpacityEvent extends TurboismEvent
    permits DeformerOpacityEvent.Before,
            DeformerOpacityEvent.On,
            DeformerOpacityEvent.After {

    Deformer deformer();

    @PreviewApi
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

        public static Callback openCallback(
            final Deformer deformer,
            final float requestedOpacity,
            final float opacity
        ) {
            return new Callback(deformer, requestedOpacity, opacity);
        }

        @Override public Deformer deformer() { return deformer; }
        public float requestedOpacity() { return requestedOpacity; }
        public float opacity() { return opacity; }

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

    @PreviewApi
    record On(Deformer deformer, float oldOpacity, float newOpacity)
        implements DeformerOpacityEvent {
        public On { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }

    @PreviewApi
    record After(Deformer deformer, float finalOpacity) implements DeformerOpacityEvent {
        public After { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }
}
