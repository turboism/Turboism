package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/**
 * Typed observation states for one explicit Cubism model update.
 *
 * <p>The payload retains only the detached semantic-operation correlation value. A live
 * {@code CubismModel} is deliberately not exposed because observation delivery may occur after the
 * host callback returns.</p>
 */
public sealed interface ModelUpdateEvent extends TurboismEvent
    permits ModelUpdateEvent.Before, ModelUpdateEvent.On, ModelUpdateEvent.After {

    /** Returns the detached correlation of the {@link CubismOperation#UPDATE_MODEL} operation. */
    CubismOperationEvent operation();

    /** State published synchronously before the update pass runs. */
    record Before(CubismOperationEvent operation) implements ModelUpdateEvent {
        public Before { operation = requireUpdate(operation); }
    }

    /** State published when the update pass runs. */
    record On(CubismOperationEvent operation) implements ModelUpdateEvent {
        public On { operation = requireUpdate(operation); }
    }

    /** State published after the update pass finished. */
    record After(CubismOperationEvent operation) implements ModelUpdateEvent {
        public After { operation = requireUpdate(operation); }
    }

    private static CubismOperationEvent requireUpdate(final CubismOperationEvent operation) {
        final CubismOperationEvent value = Objects.requireNonNull(operation, "operation");
        if (value.operation() != CubismOperation.UPDATE_MODEL) {
            throw new IllegalArgumentException(
                "Model update event requires UPDATE_MODEL operation: " + value.operation()
            );
        }
        return value;
    }
}
