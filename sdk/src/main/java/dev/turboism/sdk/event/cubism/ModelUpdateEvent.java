package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/**
 * Typed observation states for one explicit Cubism model update.
 *
 * <p>The payload retains only the detached semantic-operation correlation value. A live
 * {@code CubismModel} is deliberately not exposed because observation delivery may occur after the
 * host callback returns.</p>
 */
@PreviewApi
public sealed interface ModelUpdateEvent extends TurboismEvent
    permits ModelUpdateEvent.Before, ModelUpdateEvent.On, ModelUpdateEvent.After {

    CubismOperationEvent operation();

    @PreviewApi
    record Before(CubismOperationEvent operation) implements ModelUpdateEvent {
        public Before { operation = requireUpdate(operation); }
    }

    @PreviewApi
    record On(CubismOperationEvent operation) implements ModelUpdateEvent {
        public On { operation = requireUpdate(operation); }
    }

    @PreviewApi
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
