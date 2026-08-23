package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed states of the Rotation Deformer base-angle write event family. */
@PreviewApi
public sealed interface RotationDeformerBaseAngleEvent extends TurboismEvent
    permits RotationDeformerBaseAngleEvent.Before,
            RotationDeformerBaseAngleEvent.On,
            RotationDeformerBaseAngleEvent.After {

    RotationDeformer deformer();

    @PreviewApi
    final class Before implements RotationDeformerBaseAngleEvent {
        private final RotationDeformer deformer;
        private final float requestedAngle;
        private final CallbackScope callbackScope;
        private float angle;

        public Before(
            final RotationDeformer deformer,
            final float requestedAngle,
            final float angle
        ) {
            this(deformer, requestedAngle, angle, null);
        }

        private Before(
            final RotationDeformer deformer,
            final float requestedAngle,
            final float angle,
            final CallbackScope callbackScope
        ) {
            this.deformer = Objects.requireNonNull(deformer, "deformer");
            this.requestedAngle = requestedAngle;
            this.angle = angle;
            this.callbackScope = callbackScope;
        }

        public static Callback openCallback(
            final RotationDeformer deformer,
            final float requestedAngle,
            final float angle
        ) {
            return new Callback(deformer, requestedAngle, angle);
        }

        @Override public RotationDeformer deformer() { return deformer; }
        public float requestedAngle() { return requestedAngle; }
        public float angle() { return angle; }

        public void setAngle(final float angle) {
            if (callbackScope != null) callbackScope.requireOpen();
            this.angle = angle;
        }

        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final RotationDeformer deformer,
                final float requestedAngle,
                final float angle
            ) {
                event = new Before(deformer, requestedAngle, angle, scope);
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
                        "Rotation base-angle before-event mutation is outside its callback scope."
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
    record On(RotationDeformer deformer, float oldAngle, float newAngle)
        implements RotationDeformerBaseAngleEvent {
        public On { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }

    @PreviewApi
    record After(RotationDeformer deformer, float finalAngle)
        implements RotationDeformerBaseAngleEvent {
        public After { deformer = Objects.requireNonNull(deformer, "deformer"); }
    }
}
