package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the semantic ArtMesh geometry replacement event family. */
@PreviewApi
public sealed interface DrawableGeometryEvent extends TurboismEvent
    permits DrawableGeometryEvent.Before,
            DrawableGeometryEvent.On,
            DrawableGeometryEvent.After {

    Drawable drawable();

    @PreviewApi
    final class Before implements DrawableGeometryEvent {
        private final Drawable drawable;
        private final ArtMeshGeometry requestedGeometry;
        private final CallbackScope callbackScope;
        private ArtMeshGeometry geometry;

        public Before(
            final Drawable drawable,
            final ArtMeshGeometry requestedGeometry,
            final ArtMeshGeometry geometry
        ) {
            this(drawable, requestedGeometry, geometry, null);
        }

        private Before(
            final Drawable drawable,
            final ArtMeshGeometry requestedGeometry,
            final ArtMeshGeometry geometry,
            final CallbackScope callbackScope
        ) {
            this.drawable = Objects.requireNonNull(drawable, "drawable");
            this.requestedGeometry = Objects.requireNonNull(
                requestedGeometry,
                "requestedGeometry"
            );
            this.geometry = Objects.requireNonNull(geometry, "geometry");
            this.callbackScope = callbackScope;
        }

        /** Opens a callback-scoped mutable candidate for the intercepted geometry edit. */
        public static Callback openCallback(
            final Drawable drawable,
            final ArtMeshGeometry requestedGeometry,
            final ArtMeshGeometry geometry
        ) {
            return new Callback(drawable, requestedGeometry, geometry);
        }

        @Override public Drawable drawable() { return drawable; }
        public ArtMeshGeometry requestedGeometry() { return requestedGeometry; }
        /** Returns the candidate geometry value that will be applied. */
        public ArtMeshGeometry geometry() { return geometry; }

        /** Replaces the candidate geometry value for the current callback. */
        public void setGeometry(final ArtMeshGeometry geometry) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.geometry = Objects.requireNonNull(geometry, "geometry");
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Drawable drawable,
                final ArtMeshGeometry requestedGeometry,
                final ArtMeshGeometry geometry
            ) {
                event = new Before(drawable, requestedGeometry, geometry, scope);
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
                        "Drawable geometry before-event mutation is outside its callback scope."
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
    record On(
        Drawable drawable,
        ArtMeshGeometry oldGeometry,
        ArtMeshGeometry newGeometry
    ) implements DrawableGeometryEvent {
        public On {
            drawable = Objects.requireNonNull(drawable, "drawable");
            oldGeometry = Objects.requireNonNull(oldGeometry, "oldGeometry");
            newGeometry = Objects.requireNonNull(newGeometry, "newGeometry");
        }
    }

    @PreviewApi
    record After(Drawable drawable, ArtMeshGeometry finalGeometry)
        implements DrawableGeometryEvent {
        public After {
            drawable = Objects.requireNonNull(drawable, "drawable");
            finalGeometry = Objects.requireNonNull(finalGeometry, "finalGeometry");
        }
    }
}
