package dev.turboism.sdk.cubism.event;

import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed lifecycle states for one Runtime-confirmed semantic Cubism operation. */
public sealed interface CubismOperationLifecycleEvent extends TurboismEvent
    permits CubismOperationLifecycleEvent.Before,
            CubismOperationLifecycleEvent.On,
            CubismOperationLifecycleEvent.After {

    /** Returns the semantic operation this lifecycle event describes. */
    CubismOperationEvent operation();

    /** State published synchronously before the operation executes. */
    record Before(CubismOperationEvent operation)
        implements CubismOperationLifecycleEvent {
        public Before { operation = Objects.requireNonNull(operation, "operation"); }
    }

    /** State published when the operation runs. */
    record On(CubismOperationEvent operation) implements CubismOperationLifecycleEvent {
        public On { operation = Objects.requireNonNull(operation, "operation"); }
    }

    /** State published after the operation resolved; {@code confirmed} reports Runtime confirmation. */
    record After(CubismOperationEvent operation, boolean confirmed)
        implements CubismOperationLifecycleEvent {
        public After { operation = Objects.requireNonNull(operation, "operation"); }
    }
}
