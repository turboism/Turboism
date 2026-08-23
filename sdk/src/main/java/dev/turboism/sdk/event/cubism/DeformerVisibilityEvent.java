package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic Deformer visibility write event family. */
@PreviewApi
public sealed interface DeformerVisibilityEvent extends TurboismEvent
    permits DeformerVisibilityEvent.Before,
            DeformerVisibilityEvent.On,
            DeformerVisibilityEvent.After {

    Deformer deformer();

    @PreviewApi
    final class Before implements DeformerVisibilityEvent {
        private final Deformer deformer;
        private final boolean requestedVisible;
        private final CallbackScope callbackScope;
        private boolean visible;

        public Before(
            final Deformer deformer,
            final boolean requestedVisible,
            final boolean visible
        ) {
            this(deformer, requestedVisible, visible, null);
        }

        private Before(
            final Deformer deformer,
            final boolean requestedVisible,
            final boolean visible,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedVisible = requestedVisible;
            this.visible = visible;
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted visibility edit. */
        public static Callback openCallback(
            final Deformer deformer,
            final boolean requestedVisible,
            final boolean visible
        ) {
            return new Callback(deformer, requestedVisible, visible);
        }

        @Override public Deformer deformer() { return deformer; }
        public boolean requestedVisible() { return requestedVisible; }
        /** Returns the candidate visibility value that will be applied. */
        public boolean visible() { return visible; }

        /** Replaces the candidate visibility value for the current callback. */
        public void setVisible(final boolean visible) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.visible = visible;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Deformer deformer,
                final boolean requestedVisible,
                final boolean visible
            ) {
                event = new Before(deformer, requestedVisible, visible, scope);
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
                        "Deformer visibility before-event mutation is outside its callback scope."
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
    record On(Deformer deformer, boolean oldVisible, boolean newVisible)
        implements DeformerVisibilityEvent {
        public On { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }

    @PreviewApi
    record After(Deformer deformer, boolean finalVisible)
        implements DeformerVisibilityEvent {
        public After { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }
}
