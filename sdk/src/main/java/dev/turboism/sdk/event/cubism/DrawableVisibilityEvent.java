package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic ArtMesh visibility write event family. */
@PreviewApi
public sealed interface DrawableVisibilityEvent extends TurboismEvent
    permits DrawableVisibilityEvent.Before,
            DrawableVisibilityEvent.On,
            DrawableVisibilityEvent.After {

    Drawable drawable();

    @PreviewApi
    final class Before implements DrawableVisibilityEvent {
        private final Drawable drawable;
        private final boolean requestedVisible;
        private final CallbackScope callbackScope;
        private boolean visible;

        public Before(
            final Drawable drawable,
            final boolean requestedVisible,
            final boolean visible
        ) {
            this(drawable, requestedVisible, visible, null);
        }

        private Before(
            final Drawable drawable,
            final boolean requestedVisible,
            final boolean visible,
            final CallbackScope callbackScope
        ) {
            this.drawable = Objects.requireNonNull(drawable, "drawable");
            this.requestedVisible = requestedVisible;
            this.visible = visible;
            this.callbackScope = callbackScope;
        }

        public static Callback openCallback(
            final Drawable drawable,
            final boolean requestedVisible,
            final boolean visible
        ) {
            return new Callback(drawable, requestedVisible, visible);
        }

        @Override public Drawable drawable() { return drawable; }
        public boolean requestedVisible() { return requestedVisible; }
        public boolean visible() { return visible; }

        public void setVisible(final boolean visible) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.visible = visible;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Drawable drawable,
                final boolean requestedVisible,
                final boolean visible
            ) {
                event = new Before(drawable, requestedVisible, visible, scope);
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
                        "Drawable visibility before-event mutation is outside its callback scope."
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
    record On(Drawable drawable, boolean oldVisible, boolean newVisible)
        implements DrawableVisibilityEvent {
        public On { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }

    @PreviewApi
    record After(Drawable drawable, boolean finalVisible)
        implements DrawableVisibilityEvent {
        public After { drawable = Objects.requireNonNull(drawable, "drawable"); }
    }
}
