package dev.turboism.sdk.event.cubism;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.event.TurboismEvent;

import java.util.Objects;

/** Typed lifecycle states for one Runtime-confirmed semantic Cubism operation. */
@PreviewApi
public sealed interface CubismOperationLifecycleEvent extends TurboismEvent
    permits CubismOperationLifecycleEvent.Before,
            CubismOperationLifecycleEvent.On,
            CubismOperationLifecycleEvent.After {

    CubismOperationEvent operation();

    @PreviewApi
    record Before(CubismOperationEvent operation)
        implements CubismOperationLifecycleEvent {
        public Before { operation = Objects.requireNonNull(operation, "operation"); }
    }

    @PreviewApi
    record On(CubismOperationEvent operation) implements CubismOperationLifecycleEvent {
        public On { operation = Objects.requireNonNull(operation, "operation"); }
    }

    @PreviewApi
    record After(CubismOperationEvent operation, boolean confirmed)
        implements CubismOperationLifecycleEvent {
        public After { operation = Objects.requireNonNull(operation, "operation"); }
    }
}
