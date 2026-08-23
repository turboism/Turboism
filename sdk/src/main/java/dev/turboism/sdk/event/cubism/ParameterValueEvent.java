package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/**
 * Typed states of the semantic parameter set-value event family.
 */
@PreviewApi
public sealed interface ParameterValueEvent extends TurboismEvent
    permits ParameterValueEvent.Before, ParameterValueEvent.On, ParameterValueEvent.After {

    /** @return the parameter participating in the operation */
    Parameter parameter();

    /**
     * Synchronous state published before the host write.
     *
     * <p>Subscribers may replace {@link #value() the current candidate}. The
     * Runtime validates and checkpoints each subscriber's change.</p>
     */
    @PreviewApi
    final class Before implements ParameterValueEvent {

        private final Parameter parameter;
        private final float requestedValue;
        private final CallbackScope callbackScope;
        private float value;

        public Before(
            final Parameter parameter,
            final float requestedValue,
            final float value
        ) {
            this(parameter, requestedValue, value, null);
        }

        private Before(
            final Parameter parameter,
            final float requestedValue,
            final float value,
            final CallbackScope callbackScope
        ) {
            this.parameter = Objects.requireNonNull(parameter, "parameter");
            this.requestedValue = requestedValue;
            this.value = value;
            this.callbackScope = callbackScope;
        }

        /**
         * Opens one Runtime-controlled callback scope.
         *
         * <p>Each subscriber receives a distinct event instance. The candidate is
         * mutable only on the opening thread and only until the returned scope is
         * closed, so retaining the event cannot mutate a later host operation.</p>
         *
         * @param parameter detached parameter projection for the operation
         * @param requestedValue value originally requested by the caller
         * @param value current valid candidate before this subscriber
         * @return the callback scope and its event
         */
        public static Callback openCallback(
            final Parameter parameter,
            final float requestedValue,
            final float value
        ) {
            return new Callback(parameter, requestedValue, value);
        }

        @Override
        public Parameter parameter() {
            return parameter;
        }

        /** @return the value originally requested before any subscriber transformation */
        public float requestedValue() {
            return requestedValue;
        }

        /** @return the current candidate value */
        public float value() {
            return value;
        }

        /**
         * Replaces the current candidate value for this callback.
         *
         * <p>The Runtime validates the candidate after the subscriber returns;
         * non-finite values are discarded without changing the preceding valid
         * candidate.</p>
         *
         * @param value replacement candidate
         */
        public void setValue(final float value) {
            if (callbackScope != null) {
                callbackScope.requireOpen();
            }
            this.value = value;
        }

        /** One Runtime-owned mutable callback scope. */
        public static final class Callback implements AutoCloseable {
            private final CallbackScope scope = new CallbackScope(Thread.currentThread());
            private final Before event;

            private Callback(
                final Parameter parameter,
                final float requestedValue,
                final float value
            ) {
                event = new Before(parameter, requestedValue, value, scope);
            }

            /** @return the event valid for this callback scope */
            public Before event() {
                scope.requireOpen();
                return event;
            }

            @Override
            public void close() {
                scope.close();
            }
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
                        "Parameter before-event mutation is outside its callback scope."
                    );
                }
            }

            private void close() {
                requireOpen();
                open = false;
            }
        }
    }

    /** State published after a successful write that actually changed the value. */
    @PreviewApi
    record On(
        Parameter parameter,
        float oldValue,
        float newValue
    ) implements ParameterValueEvent {

        public On {
            parameter = Objects.requireNonNull(parameter, "parameter");
        }
    }

    /** State published after every successful write, including an unchanged write. */
    @PreviewApi
    record After(
        Parameter parameter,
        float finalValue
    ) implements ParameterValueEvent {

        public After {
            parameter = Objects.requireNonNull(parameter, "parameter");
        }
    }
}
